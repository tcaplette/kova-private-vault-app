package com.app.vault

import android.content.Context
import android.util.Log
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One mirror pass — `cloud-mirror` D6.
 *
 * **No network constraint, and that is not an oversight.** `ACCESS_NETWORK_STATE`
 * is stripped from the merged manifest (I1's neighbour: the app cannot act on the
 * answer, so it does not ask). WorkManager does not throw without it — it catches
 * the `SecurityException` and reports `isConnected=false` permanently — so a
 * request carrying `NetworkType.CONNECTED` would sit in `ENQUEUED` for good, with
 * no crash and no log. `:app:checkDebugWorkConstraints` fails the build on any app
 * class that builds one, and `WorkConstraintsTest` proves nothing enqueued carries
 * one at runtime.
 *
 * It is also unnecessary. The provider's own app owns the network side and queues
 * the upload under its own permissions, which is the entire reason this feature
 * exists without the app gaining a socket.
 *
 * **A folder run is not a foreground worker.** `ImportWorker` and `ArchiveWorker` are,
 * because a dead process loses a picker grant it cannot reopen. Nothing here holds a
 * grant that expires: the tree URI is persistable, and a run killed halfway leaves the
 * destination in a state the next diff simply recomputes. **A backup file run is** —
 * `backup-to-cloud-apps` — because it rewrites the whole vault through another app,
 * and a run killed halfway leaves the only cloud copy incomplete until the next one.
 *
 * **It needs the vault open — `settings-separation` — unless it was handed over at lock.**
 * The destination and the upload set both live in the vault's index, so an ordinary run
 * that wakes to a locked vault uploads nothing and succeeds. A change waiting when the
 * vault locks is not lost to that: `BackupAuto` prepares its run inside the lock, while
 * the key exists, in a form that needs none (`backup-to-cloud-apps` D11).
 */
class MirrorWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val MIRROR_TAG = "mirror"

        /** Unique, so a batch of twenty imports coalesces into one run. */
        private const val WORK_NAME = "mirror"

        private const val TAG = "VaultMirrorWork"

        /**
         * Enqueues a run — `BackupAuto` calls it after a change's quiet period. The worker
         * reads the destination from the open vault's index and does nothing without one.
         *
         * `KEEP` rather than `REPLACE`: a run already in flight is already going to
         * pick up whatever the diff finds when it lists, and replacing it would
         * cancel a transfer partway to start the same work again. A twenty-item
         * batch calls this twenty times and gets one run.
         */
        fun enqueue(context: Context, db: MetadataDb?) {
            if (db == null || CloudMirror.destination(db) == null) return
            enqueueAfterChange(context)
        }

        /**
         * [enqueue] for `BackupAuto`, which only calls it for a change to a vault that has a
         * destination — it saw the index at the moment of the change, and holds nothing of
         * it after, so there is no index to hand here.
         */
        internal fun enqueueAfterChange(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<MirrorWorker>()
                    .addTag(MIRROR_TAG)
                    .build(),
            )
        }

        /**
         * A run prepared at lock — `backup-to-cloud-apps` D11. Appended, so a run already going
         * finishes and this one follows; it holds the change the other did not see.
         */
        fun enqueueJob(context: Context, job: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<MirrorWorker>()
                    .addTag(MIRROR_TAG)
                    .setInputData(workDataOf(JOB to job))
                    .build(),
            )
        }

        /** Input: a handed-over run's ID in [BackupAuto]. */
        const val JOB = "job"

        /**
         * Back up now, for a backup file — `backup-to-cloud-apps` D4. Not gated by the day,
         * and appended rather than kept: a queued automatic run that the gate would skip
         * must not swallow the tap, and a running one must not be cancelled halfway
         * through a file only it can finish.
         */
        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<MirrorWorker>()
                    .addTag(MIRROR_TAG)
                    .setInputData(workDataOf(AUTOMATIC to false))
                    .build(),
            )
        }

        /** Input: false for Back up now, which writes even when nothing changed. Absent is automatic. */
        const val AUTOMATIC = "automatic"

        /** Progress: blob bytes written, and the total — the screen's determinate bar. */
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"

        /**
         * Output: what a file run ended in, for the screen — [RESULT_OK], [RESULT_NO_SPACE]
         * with [NEEDED], [RESULT_UNREACHABLE], or [RESULT_SKIPPED] by the gate or a lock.
         */
        const val RESULT = "result"
        const val NEEDED = "needed"
        const val RESULT_OK = "ok"
        const val RESULT_NO_SPACE = "noSpace"
        const val RESULT_UNREACHABLE = "unreachable"
        const val RESULT_SKIPPED = "skipped"

        private const val CHANNEL_ID = "import"
        private const val NOTIFICATION_ID = 2

        /** Progress is posted every 4 MiB, not every buffer. */
        private const val PROGRESS_STEP = 4L * 1024 * 1024
    }

    /**
     * A backup file is the whole vault written through another app, so the process must
     * outlive the person leaving — the reason `ImportWorker` goes foreground, and its
     * channel and wording. A folder run is not foreground (`cloud-mirror`'s argument: a
     * killed run leaves a diff the next one recomputes).
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
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // `backup-to-cloud-apps` D11. A run prepared at lock carries what it needs.
        inputData.getString(JOB)?.let { job ->
            return@withContext BackupAuto.take(job)?.let { runJob(it) } ?: Result.success()
        }

        // Locked. Nothing is uploaded and this is not an error — the key is gone,
        // and a retry would be a retry of doing nothing. `settings-separation`.
        val vmk = VaultSession.vmk() ?: return@withContext Result.success()
        val indexId = VaultSession.metadataBlobId() ?: return@withContext Result.success()

        val blobsDir = File(applicationContext.filesDir, "blobs")
        val store = BlobStore(blobsDir, File(applicationContext.filesDir, "incoming"))

        val db = runCatching { MetadataDb.load(store, indexId, vmk) }.getOrElse {
            Log.w(TAG, "the index could not be read", it)
            return@withContext Result.success()
        }

        // Turned off, or never turned on for this vault.
        val tree = CloudMirror.destination(db) ?: return@withContext Result.success()
        // `per-vault-intruder-capture`. The vault's own capture store travels with it.
        val captures = CloudMirror.captureStoreOf(store, vmk)
        val names = CloudMirror.vaultNames(db, indexId, VaultKeyringSession.keyringBlobId(), captures)

        // `backup-to-cloud-apps`. A cloud app's single file, written whole.
        if (CloudMirror.isFile(tree)) {
            val mutable = CloudMirror.mutableNames(db, indexId, VaultKeyringSession.keyringBlobId(), captures)
            return@withContext writeFile(
                tree, blobsDir, names, mutable, inputData.getBoolean(AUTOMATIC, true), store, vmk,
                indexId, BackupFile.contentDigest(db),
            )
        }

        val outcome = runCatching {
            CloudMirror.run(applicationContext, tree, blobsDir, names)
        }.getOrElse {
            // A provider that is absent, revoked or failing is not an error state
            // to escalate — the next import enqueues another run and the diff is
            // recomputed from scratch, so nothing is lost by giving up on this one.
            Log.w(TAG, "mirror run failed", it)
            return@withContext Result.success()
        }

        if (outcome.failed > 0) Log.w(TAG, "${outcome.failed} file(s) did not transfer")
        Result.success()
    }

    /**
     * A run handed over at lock — `backup-to-cloud-apps` D11. It needs no vault key: a folder
     * run carries its names, a file run its already-sealed table.
     */
    private suspend fun runJob(job: BackupAuto.Job): Result = when (job) {
        is BackupAuto.Job.Folder -> {
            runCatching {
                CloudMirror.run(applicationContext, job.tree, File(applicationContext.filesDir, "blobs"), job.names)
            }.onFailure { Log.w(TAG, "handed-over mirror run failed", it) }
            Result.success()
        }
        is BackupAuto.Job.File -> try {
            streamFile(job.uri, job.prepared, job.indexDigest, job.vault)
        } finally {
            BackupFile.dropSnapshot(job.snapshotDir)
        }
    }

    /**
     * One backup file run from an open vault — design D4, D10.
     *
     * An automatic run first asks whether anything the person would call a change has
     * happened since the last completed write ([BackupFile.status]), and does nothing when
     * nothing has — a whole-vault upload for an unlock's chaff rotation would be pure waste.
     * Back up now always writes. The rewritable blobs are copied aside, the table is sealed
     * and the backup key zeroed before the first blob byte, so a lock mid-write leaves no key.
     */
    private suspend fun writeFile(
        uri: android.net.Uri,
        blobsDir: File,
        names: Set<String>,
        mutable: Set<String>,
        automatic: Boolean,
        store: BlobStore,
        vmk: ByteArray,
        indexId: ByteArray,
        indexDigest: ByteArray,
    ): Result {
        val info = CloudMirror.fileInfo(applicationContext, uri)
            ?: return Result.success(workDataOf(RESULT to RESULT_UNREACHABLE))

        if (automatic) {
            val local = CloudMirror.localEntries(blobsDir).filterKeys { it in names }
            val status = BackupFile.status(local, BackupFile.readRecord(store, vmk), info.size, hex(indexId), indexDigest)
            if (status.pending == 0) return Result.success(workDataOf(RESULT to RESULT_SKIPPED))
        }

        val snapshotDir = BackupAuto.snapshotDir(applicationContext, id.toString())
        try {
            val files = BackupFile.snapshot(blobsDir, names, mutable, snapshotDir)
            val key = VaultKeyringSession.backupKey()
                ?: return Result.success(workDataOf(RESULT to RESULT_SKIPPED))
            return streamFile(uri, BackupFile.prepare(files, key), indexDigest, hex(indexId))
        } finally {
            BackupFile.dropSnapshot(snapshotDir)
        }
    }

    /**
     * Writes a prepared file — shared by the open-vault run and the one handed over at lock.
     *
     * The phone must have room for a full staged copy first: Google Drive holds one while it
     * uploads (measured: free space fell by the file's size, and stayed down). The record of
     * the write is kept at once if [vault] is still open, or held in memory for when it
     * next opens — it needs the vault's key, and no key outlives a lock.
     */
    private suspend fun streamFile(
        uri: android.net.Uri,
        prepared: BackupFile.Prepared,
        indexDigest: ByteArray,
        vault: String,
    ): Result {
        val free = android.os.StatFs(applicationContext.filesDir.path).availableBytes
        if (free < prepared.size) {
            // [NEEDED] is the shortfall — what the person has to free, not the total.
            return Result.success(workDataOf(RESULT to RESULT_NO_SPACE, NEEDED to prepared.size - free))
        }
        runCatching { setForeground(getForegroundInfo()) }

        val done = java.util.concurrent.atomic.AtomicLong(0)
        val written = runCatching {
            applicationContext.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                BackupFile.stream(out, prepared) { d, t ->
                    // Sampled, not awaited: setProgress suspends, and this is inside the stream.
                    if (d - done.get() >= PROGRESS_STEP || d == t) {
                        done.set(d)
                        setProgressAsync(workDataOf(PROGRESS_DONE to d, PROGRESS_TOTAL to t))
                    }
                }
            }
        }
        if (written.isFailure) {
            Log.w(TAG, "backup file could not be written", written.exceptionOrNull())
            return Result.success(workDataOf(RESULT to RESULT_UNREACHABLE))
        }
        Log.i(TAG, "backup file written: ${written.getOrThrow()} blob bytes")

        val record = BackupFile.Record(System.currentTimeMillis(), prepared.size, indexDigest)
        val openId = VaultSession.metadataBlobId()
        val vmk = VaultSession.vmk()
        if (openId != null && vmk != null && hex(openId) == vault) {
            runCatching {
                BackupFile.writeRecord(
                    BlobStore(File(applicationContext.filesDir, "blobs"), File(applicationContext.filesDir, "incoming")),
                    vmk, record,
                )
            }.onFailure { Log.w(TAG, "the backup record could not be written", it) }
        } else {
            BackupAuto.recordLater(vault, record)
        }
        return Result.success(workDataOf(RESULT to RESULT_OK))
    }
}
