package com.app.vault

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import android.provider.MediaStore
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * One file's import — P1-7.
 *
 * Resumable: the partial blob's length is the progress, so an interrupted run
 * continues from the last whole chunk. Cancellable: `isStopped` is honoured and
 * the partial blob is left resumable rather than presented as a finished item.
 *
 * **Nothing here persists anything.** Input [Data] is empty and the only tag is
 * the work type: the source URI, the blob ID and the index ID live in
 * [WorkJobs], in memory, keyed by this request's own id. Gate 1 found them in
 * `no_backup/androidx.work.workdb` when they were input data, and `WorkJobs`
 * carries the whole argument for why they are not any more. The VMK comes from
 * [VaultSession], which has always been memory-only. A worker that wakes with the
 * vault locked retries instead of failing.
 */
class ImportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val PROGRESS_BYTES = "bytesWritten"
        const val PROGRESS_TOTAL = "bytesTotal"
        const val PROGRESS_WAITING = "waitingForUnlock"

        private const val CHANNEL_ID = "import"
        private const val NOTIFICATION_ID = 1

        /**
         * Tag on every import request. Lives here rather than in `MainActivity`
         * because P1-10's auto-lock asks WorkManager whether an import is in
         * flight, and it has no activity to ask.
         *
         * It names the work type and identifies nothing. There is deliberately no
         * per-blob tag beside it any more: `blob:<hex>` put a real blob's ID into
         * WorkManager's database in plaintext, which Gate 1 read straight back.
         */
        const val IMPORT_TAG = "import"

        /** Logcat tag for a failed attempt — step, class and attempt number, nothing else. */
        private const val LOG_TAG = "import"

        /** One run and two reruns of a live read failure (`concurrent-import-failure` D2). */
        const val ATTEMPTS = 3
        private val RETRY_DELAYS_MS = longArrayOf(500, 2_000)

        /**
         * Test-only: told the step and number of every failed attempt, before the
         * worker decides whether to rerun. Null in the app.
         */
        @Volatile
        var onAttemptFailed: ((ImportStep, Int) -> Unit)? = null
    }

    /**
     * Keeps the process alive for the duration of the import.
     *
     * This is the whole of P1-8.6: `PickVisualMedia`'s read grant is
     * process-scoped, so an import that outlives its process cannot reopen its
     * own source no matter how good the resume is. The fix is not to let the
     * process die.
     *
     * The notification carries no item count and no filename. Android forces the
     * app name onto it, which conflicts with nothing — `OUTLINE.md` states this
     * is an openly obvious vault and rejects disguise.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.notification_channel_import), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.notification_working))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override suspend fun doWork(): Result {
        val vmk = VaultSession.vmk()
            ?: run {
                // Locked. Not a failure — there is simply no key yet, and there
                // is no key on disk to fall back to. Wait for the user.
                setProgress(workDataOf(PROGRESS_WAITING to true))
                return Result.retry()
            }

        // Absent means the process that enqueued this is gone. The source URI's
        // read grant died with it (P1-8.5), so there is nothing to reopen and
        // nothing to wait for — fail now rather than at the SecurityException a
        // few lines later. The staged partial becomes an orphan and is swept.
        val job = WorkJobs.get<WorkJobs.ImportJob>(id) ?: return Result.failure()
        val uri = job.uri
        val blobIds = job.blobIds
        val indexId = job.indexId

        val store = BlobStore(File(applicationContext.filesDir, "blobs"))
        val resolver = applicationContext.contentResolver

        // Foreground for the duration. Failing to go foreground must not fail the
        // import — a suppressed notification (no POST_NOTIFICATIONS on API 33+)
        // still leaves the service running.
        runCatching { setForeground(getForegroundInfo()) }

        // setProgress suspends, and progress arrives from inside the byte stream.
        // Record it in atomics and sample from a coroutine, so reporting never
        // blocks the copy and stays real rather than a per-file estimate.
        val bytesWritten = java.util.concurrent.atomic.AtomicLong(0)
        val bytesTotal = java.util.concurrent.atomic.AtomicLong(-1)

        // `concurrent-import-failure` D2. A failure to read the source while the job is
        // still in memory is a live grant failing, which can pass — the picker's provider
        // was mid-sync when one batch lost an item. Rerun from what was staged, twice;
        // only the third is final. A write failure is this phone's storage, final at once.
        var attempt = 1
        var outcome: ImportOutcome
        while (true) {
            outcome = runOnce(resolver, store, uri, blobIds, vmk, job, bytesWritten, bytesTotal)
            if (outcome !is ImportOutcome.Failed) break
            Log.w(LOG_TAG, "import failed at ${outcome.step}: ${outcome.cause} (attempt $attempt of $ATTEMPTS)")
            onAttemptFailed?.invoke(outcome.step, attempt)
            val retryable = outcome.step != ImportStep.WRITE &&
                attempt < ATTEMPTS &&
                WorkJobs.get<WorkJobs.ImportJob>(id) != null
            if (!retryable) break
            // A stop during the wait is a stop, not a failure: the partial stays, as
            // it does for any cancellation.
            try {
                delay(RETRY_DELAYS_MS[attempt - 1])
            } catch (_: CancellationException) {
                return Result.retry()
            }
            if (isStopped) return Result.retry()
            attempt++
        }
        setProgress(
            workDataOf(
                PROGRESS_BYTES to bytesWritten.get(),
                PROGRESS_TOTAL to bytesTotal.get()
            )
        )

        // Nothing may escape without cleaning up: a partial outliving a failure
        // is a format leak (FORMAT.md §10), not merely wasted space.
        return when (outcome) {
            // Stopped, not broken. The system may re-run this, so the partial
            // stays — it *is* the resume state P1-7 measured. If the stop was a
            // real user cancellation, WorkManager will not re-run it and the
            // unlock sweep collects the partial instead.
            is ImportOutcome.Cancelled -> Result.retry()

            // Final: the third read failure, a write failure, or a read failure
            // with no job — after process death a picker URI can never become
            // readable again, so retrying is a loop that never terminates. The
            // partial is now garbage.
            is ImportOutcome.Failed -> {
                // Every blob this item claimed: the partial and any sealed blobs
                // from an earlier attempt. A sealed blob whose item never
                // completed is unreferenced by any index and would sit in the
                // pool forever.
                blobIds.forEach { store.discardPartial(it); store.delete(it) }
                WorkJobs.clear(id)
                Result.failure()
            }
            is ImportOutcome.Done -> done(outcome, store, indexId, vmk)
        }
    }

    /** One run of the pipeline, with progress sampled while it copies. */
    private suspend fun runOnce(
        resolver: ContentResolver,
        store: BlobStore,
        uri: Uri,
        blobIds: MutableList<ByteArray>,
        vmk: ByteArray,
        job: WorkJobs.ImportJob,
        bytesWritten: java.util.concurrent.atomic.AtomicLong,
        bytesTotal: java.util.concurrent.atomic.AtomicLong,
    ): ImportOutcome =
        coroutineScope {
            val reporter = launch {
                while (isActive) {
                    setProgress(
                        workDataOf(
                            PROGRESS_BYTES to bytesWritten.get(),
                            PROGRESS_TOTAL to bytesTotal.get()
                        )
                    )
                    delay(250)
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    ImportPipeline(
                        resolver, store,
                        // `exif-strip-choice`. Whether is the import switch; what is the
                        // install's one selection.
                        if (GridPreference.stripExifOnImport(applicationContext)) {
                            ExifPolicy.removed(applicationContext)
                        } else {
                            null
                        },
                    ).run(
                        uri = uri,
                        blobIds = blobIds,
                        vmk = vmk,
                        isCancelled = { isStopped },
                        onProgress = { written, total ->
                            bytesWritten.set(written)
                            bytesTotal.set(total)
                        },
                        assignedCreatedAt = job.assignedCreatedAt,
                        assignedImportedAt = job.assignedImportedAt,
                        folderId = job.folderId,
                    )
                }
            } finally {
                reporter.cancel()
            }
        }

    private fun done(
        outcome: ImportOutcome.Done,
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
    ): Result {
        // Recorded the moment the content blobs are known, ahead of
        // anything else this branch does: they are already sealed on
        // disk, so a kill from here to the commit below must not leave
        // them untracked (`blob-ownership-by-record`, D4). One extra
        // index commit per import, against a write-then-commit race that
        // otherwise leaks — the same race closed for purge.
        MetadataDb.commit(store, indexId, vmk) { db ->
            db.ownedChaff += outcome.item.blobIds
            db
        }

        // Decode the thumbnail and write the chaff blobs **before** the
        // commit. Neither touches the index, both are slow, and P2-9's
        // guard serializes everything inside it — holding it across a
        // full-frame decode would turn a batch import into a queue.
        val made = Thumbnails.generate(store, outcome.item, vmk)
        val rng = java.security.SecureRandom()
        val chaffCount = outcome.item.blobIds.sumOf {
            rng.nextInt(VaultLifecycle.IMPORT_CHAFF_MAX_PER_BLOB + 1)
        }
        val chaff = if (chaffCount > 0) store.writeChaff(chaffCount) else emptyList()

        // Save the index per completed item, not per batch: a batch that
        // dies halfway must keep what finished.
        //
        // **Every mutation of the index happens in here.** Before P2-9 this
        // was a bare load / mutate / save, and N parallel workers ran it at
        // once: seven imports recorded one item on a real device, and on the
        // emulator left the index unparseable. See `MetadataDb.commit`.
        val item = MetadataDb.commit(store, indexId, vmk) { db ->
            // Staging and flushing both mutate the index — `stage` appends
            // to the pack table and `flush` writes the pack blobs those
            // entries name — so both are inside, on one store instance.
            // The decode that produced these bytes is not, and was not.
            val thumbs = ThumbnailStore(store)
            val staged = if (made.webp != null) {
                outcome.item.withThumb(thumbs.stage(db, made.webp), made.durationMs)
                    .also { thumbs.flush(db, vmk) }
            } else {
                outcome.item
            }
            db.items += staged

            // The content blobs just became this item's, not owned chaff
            // any more — remove the record the commit above added
            // (`blob-ownership-by-record`, D4).
            db.ownedChaff.removeAll { id ->
                outcome.item.blobIds.any { it.contentEquals(id) }
            }

            // §6's per-import chaff, scaled to the blobs this import wrote.
            // A split item writes many same-sized files at one timestamp; on
            // its own that is a legible burst, and this is what it hides in.
            // Owned and recorded, so the per-unlock churn can rewrite it.
            // Recorded in here because the record lives in the index — before
            // P2-9 it was lost with the commit and the chaff was orphaned.
            if (chaff.isNotEmpty()) db.ownedChaff += chaff

            // One entry per completed item, because one worker *is* one item.
            // The batch edge exists only in the Activity, which P1-10 lets die
            // while imports keep running, so a per-batch entry would be missing
            // exactly when the log matters most.
            //
            // Nested inside the commit deliberately: the log's blob ID is an
            // index field, so this must be part of the same transaction. The
            // guard is reentrant, which is why the save this may perform when
            // the log grows does not deadlock.
            TamperLog.append(
                store, db, indexId, vmk,
                TamperLog.IMPORT_COMPLETED, TamperLog.countPayload(1)
            )
            staged
        }

        WorkJobs.clear(id)
        return Result.success(workDataOf("deletable" to item.deletable))
    }
}

/**
 * Where an import failed — `concurrent-import-failure` D1. The first three read the
 * source; [WRITE] is this phone's store.
 */
enum class ImportStep { DESCRIBE, SIZE, OPEN, WRITE }

sealed class ImportOutcome {
    class Done(val item: VaultItem, val sourceHash: String, val blobHash: String) : ImportOutcome()

    /**
     * [cause] is the exception's simple class name, `"NoStream"` for a provider that
     * returned no stream. **Never its message, never the URI**: a provider's message
     * routinely carries both, and `import-pipeline` forbids recording the source.
     */
    class Failed(val step: ImportStep, val cause: String) : ImportOutcome() {
        constructor(step: ImportStep, e: Exception) : this(step, e.javaClass.simpleName.ifEmpty { "Exception" })
    }
    object Cancelled : ImportOutcome()
}

/**
 * The import itself, independent of WorkManager so it can be driven directly by
 * tests and by any future caller.
 */
class ImportPipeline(
    private val resolver: ContentResolver,
    private val store: BlobStore,
    /**
     * Remove image metadata on the way in — `exif-strip-choice`.
     *
     * Passed rather than read here, because this class holds a `ContentResolver` and no
     * `Context`, and because the decoy flow imports through it too and is not the user
     * choosing anything. The worker reads the preference and hands over the answer: what a
     * strip removes, or null to store the file as it was given.
     */
    private val strip: Set<PhotoDetail>? = null,
) {

    fun run(
        uri: Uri,
        /**
         * The blobs this item has claimed, in order. Empty on a first attempt;
         * on a retry it carries what the previous one claimed, so sealed blobs
         * are skipped and the partial is resumed. The caller keeps it — the
         * import job table (`WorkJobs`) — because it is also what tells the
         * unlock sweep which partial is live.
         */
        blobIds: MutableList<ByteArray>,
        vmk: ByteArray,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        claim: (ByteArray) -> Unit = {},
        /**
         * P2-2, and null for every ordinary import.
         *
         * [assignedCreatedAt] is a **fallback, not an override**: the source's own
         * capture date wins whenever it has one. That ordering is the guarantee, not
         * a convention the decoy flow is trusted to keep — an index claiming June for
         * a file whose EXIF says 2019 is the tell the whole feature exists to avoid,
         * and no caller can produce it from here.
         */
        assignedCreatedAt: Long? = null,
        assignedImportedAt: Long? = null,
        /**
         * The folder the import was started in, or null — P2-13, `folder-system`.
         * Arrives from [WorkJobs.ImportJob] in memory and is written onto the row;
         * it is never read from or written to the work request's input `Data`.
         */
        folderId: ByteArray? = null,
    ): ImportOutcome {
        // Inside the failure handling, not before it. These touch the source, so
        // on a dead picker grant they throw — and until P1-8.6 they threw *past*
        // the caller's handling, so WorkManager saw a raw exception instead of a
        // Failed outcome and the partial was never cleaned up.
        val source = try {
            SourceMetadata.describe(resolver, uri)
        } catch (e: Exception) {
            return ImportOutcome.Failed(ImportStep.DESCRIBE, e)
        }
        val total = try {
            sizeOf(uri)
        } catch (e: Exception) {
            return ImportOutcome.Failed(ImportStep.SIZE, e)
        }
        // Where this item stands: whole blobs already sealed, plus whatever of
        // the next one survived. Blob-granular, so a resumed 4 GB import re-reads
        // at most one blob rather than starting the file again.
        val resumeAt = itemResumeOffset(blobIds)

        // `exif-strip-choice`. The filter sits **ahead** of the resume and progress
        // accounting, not behind it: `resumeAt` is an offset into what was sealed, and what
        // was sealed is the filtered bytes. Filtering after the skip would resume at the wrong
        // place on a stripped item.
        val removed = strip?.takeIf { ExifPolicy.strippable(ExportDestination.mimeOf(source.name)) }
        // Opening is its own step: a revoked grant or a provider mid-sync fails here,
        // and that is the failure the worker retries. Everything after it is the write.
        val opened = try {
            resolver.openInputStream(uri)
        } catch (e: Exception) {
            return ImportOutcome.Failed(ImportStep.OPEN, e)
        } ?: return ImportOutcome.Failed(ImportStep.OPEN, "NoStream")
        val written = try {
            opened.use { raw ->
                val seal = { src: java.io.InputStream ->
                    val counted = CountingStream(src)
                    if (resumeAt > 0) skipExactly(counted, resumeAt)
                    counted.resetCount()

                    val watched = ProgressStream(counted, resumeAt, total, onProgress, isCancelled)
                    store.writeItem(watched, vmk, blobIds, claim, isCancelled)
                }
                if (removed != null) {
                    Pipe.between({ sink -> ExifPolicy.copyStripped(raw, sink, removed) }, seal)
                } else {
                    seal(raw)
                }
            }
        } catch (_: CancelledException) {
            return ImportOutcome.Cancelled
        } catch (e: Exception) {
            return ImportOutcome.Failed(ImportStep.WRITE, e)
        }

        // I6: decrypt what was stored and compare against the source before
        // anything is allowed to offer deleting that source.
        val check = verify(uri, written.blobIds, vmk, written.plaintextLength)
        val sourceHash = check.sourceHash
        val blobHash = check.blobHash
        val verified = check.matches

        return ImportOutcome.Done(
            item = VaultItem(
                blobIds = written.blobIds,
                plaintextLength = written.plaintextLength,
                thumb = null,
                filename = source.name,
                // The folder the import was started in — from `WorkJobs` in
                // memory, never from input `Data`.
                album = null,
                folderId = folderId,
                // Was hard-coded null until P1-8.5. P1-6 read this and P1-7 did
                // not, and nothing compared them until the UI moved onto this path.
                createdAt = source.createdAt ?: assignedCreatedAt,
                importedAt = assignedImportedAt ?: System.currentTimeMillis(),
                tags = emptyList(),
                deletable = verified,
            ),
            sourceHash = sourceHash,
            blobHash = blobHash
        )
    }

    /**
     * Re-runnable I6 check: decrypt the stored blob and compare it against the
     * source. Public because "corrupt the encrypted copy, deletion must not be
     * offered" is a thing that has to be checkable *after* an import, not only
     * during one.
     *
     * A blob damaged badly enough that a chunk fails authentication reports as a
     * mismatch rather than throwing — a corrupt copy is a verification failure,
     * not a crash.
     */
    fun verify(
        uri: Uri,
        blobIds: List<ByteArray>,
        vmk: ByteArray,
        length: Long,
    ): Verification {
        val sourceHash = hashSource(uri)
        // Over the whole item, every blob of it. A per-blob check that passed on
        // some blobs would mark a truncated item deletable, which is the one bug
        // in this file that loses the user's only copy.
        val blobHash = try {
            hashBlob(blobIds, vmk, length)
        } catch (_: Exception) {
            "unreadable"
        }
        if (sourceHash == blobHash) return Verification(sourceHash, blobHash, true)

        // ***A stripped import is a faithful copy of a source it does not equal.***
        // `exif-strip-choice` removes metadata between the source and the envelope, so the
        // stored bytes are deliberately not the source's bytes — and I6 gates `deletable`,
        // which is what decides whether the app may offer to remove the user's original. Left
        // alone, every stripped import would silently become undeletable: no crash, no
        // message, and the copy in the gallery that the user vaulted the photograph to be rid
        // of stays there.
        //
        // Asked as a second question rather than a flag on the row, because this check is
        // re-runnable long after the import and nothing on a `VaultItem` records that it was
        // stripped — adding a field for it would be a format change, which this change does
        // not make. The cost is one extra hash of the source, on the mismatch path only.
        // Under the selection this import used, which is this instance's: a pipeline that did
        // not strip has no stripped copy to compare against.
        if (strip != null && ExifPolicy.strippable(resolver.getType(uri))) {
            val strippedHash = hashStrippedSource(uri, strip)
            if (strippedHash == blobHash) return Verification(strippedHash, blobHash, true)
        }
        return Verification(sourceHash, blobHash, false)
    }

    class Verification(val sourceHash: String, val blobHash: String, val matches: Boolean)

    /**
     * A deletion request the user must confirm — produced only for a verified
     * item. Nothing in this app deletes a source; it asks, and the system asks
     * the user.
     */
    fun deleteRequestFor(item: VaultItem, uris: List<Uri>): PendingIntent? =
        if (!item.deletable) null else MediaStore.createDeleteRequest(resolver, uris)

    // --- hashing ---------------------------------------------------------------

    private fun hashSource(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(BlobStore.CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        } ?: return "no-source"
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The source as it would have been sealed with metadata removed — `exif-strip-choice`. */
    private fun hashStrippedSource(uri: Uri, removed: Set<PhotoDetail>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                Pipe.between({ sink -> ExifPolicy.copyStripped(input, sink, removed) }) { src ->
                    val buf = ByteArray(BlobStore.CHUNK)
                    while (true) {
                        val n = src.read(buf)
                        if (n <= 0) break
                        digest.update(buf, 0, n)
                    }
                }
            } ?: return "no-source"
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("unreadable")
    }

    private fun hashBlob(blobIds: List<ByteArray>, vmk: ByteArray, length: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        store.reader(blobIds, vmk, length).use { r ->
            val buf = ByteArray(BlobStore.CHUNK)
            for (i in 0 until r.chunkCount) {
                val n = r.readChunk(i, buf)
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // --- source metadata -------------------------------------------------------

    private fun sizeOf(uri: Uri): Long =
        resolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (c.moveToFirst() && i >= 0 && !c.isNull(i)) c.getLong(i) else -1L
        } ?: -1L

    private fun nameOf(uri: Uri): String =
        resolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0 && !c.isNull(i)) c.getString(i) else null
        } ?: uri.lastPathSegment ?: "import-${System.currentTimeMillis()}"

    /**
     * How much of the item is already on disk: whole sealed blobs plus the
     * partial's whole chunks.
     *
     * A blob in the pool is complete and padded by definition (§5), so it is
     * exactly one split's worth of plaintext — except the last, which cannot be
     * both in the pool and mid-write.
     */
    private fun itemResumeOffset(blobIds: List<ByteArray>): Long {
        var sealed = 0
        while (sealed < blobIds.size && store.fileOf(blobIds[sealed]).exists()) sealed++
        val partial = blobIds.getOrNull(sealed)?.let { store.resumeOffset(it) } ?: 0L
        return sealed * BlobStore.SPLIT_PLAINTEXT + partial
    }

    private fun skipExactly(input: InputStream, bytes: Long) {
        var remaining = bytes
        val scratch = ByteArray(BlobStore.CHUNK)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) { remaining -= skipped; continue }
            val n = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (n <= 0) break
            remaining -= n
        }
    }
}

private class CancelledException : RuntimeException()

/** Counts bytes actually pulled from the source, so a test can prove a resume. */
class CountingStream(private val delegate: InputStream) : InputStream() {
    var bytesRead = 0L
        private set

    fun resetCount() { bytesRead = 0 }

    override fun read(): Int = delegate.read().also { if (it >= 0) bytesRead++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        delegate.read(b, off, len).also { if (it > 0) bytesRead += it }

    override fun skip(n: Long): Long = delegate.skip(n)
    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()
}

private class ProgressStream(
    private val delegate: InputStream,
    private val startedAt: Long,
    private val total: Long,
    private val onProgress: (Long, Long) -> Unit,
    private val isCancelled: () -> Boolean
) : InputStream() {

    private var written = startedAt

    override fun read(): Int = delegate.read().also { if (it >= 0) advance(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (isCancelled()) throw CancelledException()
        return delegate.read(b, off, len).also { if (it > 0) advance(it.toLong()) }
    }

    private fun advance(n: Long) {
        written += n
        onProgress(written, total)
    }

    override fun close() = delegate.close()
}

/** Lowercase hex, for comparing two identities the index holds as bytes. */
private fun hexOf(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
