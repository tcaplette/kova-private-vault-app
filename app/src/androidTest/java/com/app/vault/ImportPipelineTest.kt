package com.app.vault

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * P1-7 — resumable import, against OUTLINE P1-7's Verify.
 *
 * The kill clause is simulated: an instrumented test runs inside the app process,
 * so killing that process kills the test. Resume is proved instead by truncating
 * the partial blob exactly as a kill leaves it and asserting the resumed run
 * reads the source **only from the resume offset** — which is the property that
 * separates resuming from restarting.
 */
@RunWith(AndroidJUnit4::class)
class ImportPipelineTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = ctx.contentResolver
    private lateinit var dir: File
    /**
     * Where partials are staged (P1-8.6). These tests fabricate a partial by
     * hand, so they necessarily know where one lives — which is also why they
     * never caught `writeTo` deleting its own partial on failure. The real
     * interruption path is covered in `DurableImportTest`.
     */
    private lateinit var incoming: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray
    private lateinit var indexId: ByteArray
    private val created = mutableListOf<Uri>()
    private val rng = SecureRandom()

    private companion object { const val TAG = "VaultPipeline" }

    @Before fun setUp() {
        dir = File(ctx.filesDir, "pipeline-test").apply { mkdirs() }
        incoming = File(ctx.filesDir, "pipeline-test-incoming").apply { mkdirs() }
        store = BlobStore(dir, incoming)
        vmk = VaultKeys.newVmk()
        indexId = ByteArray(16).also { rng.nextBytes(it) }
        VaultSession.lock()
    }

    @After fun tearDown() {
        created.forEach { runCatching { resolver.delete(it, null, null) } }
        created.clear()
        dir.deleteRecursively()
        incoming.deleteRecursively()
        VaultSession.lock()
    }

    private fun blobId() = ByteArray(16).also { rng.nextBytes(it) }
    private fun pipeline() = ImportPipeline(resolver, store)

    private fun source(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/VaultTest")
        }
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        )!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        created += uri
        return uri
    }

    private fun readAll(item: VaultItem): ByteArray {
        store.reader(item.blobIds, vmk, item.plaintextLength).use { r ->
            val out = ByteArray(item.plaintextLength.toInt())
            val buf = ByteArray(BlobStore.CHUNK)
            var offset = 0
            for (i in 0 until r.chunkCount) {
                val n = r.readChunk(i, buf)
                buf.copyInto(out, offset, 0, n)
                offset += n
            }
            return out
        }
    }

    /**
     * Interrupts a **real** write after exactly [wholeChunks] chunks.
     *
     * These tests used to write the blob in full, rename the finished file back
     * to a partial, and truncate it. That fabricated the state instead of
     * producing it, which is why they never noticed `writeTo` deleting its own
     * partial on failure — the defect P1-8.6 found. Driving the real failure path
     * is both deterministic (a source that stops at a chunk boundary leaves
     * exactly that many sealed chunks) and able to catch it.
     */
    private fun interruptAfter(blobId: ByteArray, source: ByteArray, wholeChunks: Int) {
        val limit = wholeChunks * BlobStore.CHUNK
        val stream = object : java.io.InputStream() {
            private var served = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= limit) throw java.io.IOException("interrupted")
                val n = minOf(len, limit - served)
                source.copyInto(b, off, served, served + n)
                served += n
                return n
            }
        }
        runCatching { store.writeTo(blobId, stream, vmk, resume = false) }
        check(store.resumeOffset(blobId) == wholeChunks.toLong() * BlobStore.CHUNK) {
            "interruption did not leave $wholeChunks whole chunks"
        }
    }

    /**
     * Leaves a **torn** chunk — fewer bytes than a sealed chunk needs.
     *
     * This one still truncates, because that state cannot be produced through the
     * stream: it only arises from a kill part-way through writing a chunk. The
     * path comes from [BlobStore.partialFileOf] so the staging layout stays
     * written down in exactly one place.
     */
    private fun tearAfter(blobId: ByteArray, source: ByteArray, wholeChunks: Int, extraBytes: Int) {
        interruptAfter(blobId, source, wholeChunks + 1)
        RandomAccessFile(store.partialFileOf(blobId), "rw").use {
            it.setLength(16L + wholeChunks.toLong() * BlobStore.STRIDE + extraBytes)
        }
    }

    // --- group 5: resume -------------------------------------------------------

    @Test fun aTruncatedImportResumesAndMatchesAnUninterruptedOne() {
        val bytes = ByteArray(BlobStore.CHUNK * 4 + 5000).also { rng.nextBytes(it) }
        val uri = source("resume-a.png", bytes)

        val reference = pipeline().run(uri, mutableListOf(), vmk) as ImportOutcome.Done

        val partialId = blobId()
        interruptAfter(partialId, bytes, wholeChunks = 2)

        val resumed = pipeline().run(uri, mutableListOf(partialId), vmk) as ImportOutcome.Done

        assertArrayEquals(readAll(reference.item), readAll(resumed.item))
        assertEquals(bytes.size.toLong(), resumed.item.plaintextLength)
        assertArrayEquals(bytes, readAll(resumed.item))
    }

    @Test fun aResumeReadsTheSourceOnlyFromTheResumeOffset() {
        val bytes = ByteArray(BlobStore.CHUNK * 4 + 111).also { rng.nextBytes(it) }
        val uri = source("resume-b.png", bytes)

        val partialId = blobId()
        interruptAfter(partialId, bytes, wholeChunks = 3)

        val resumeAt = store.resumeOffset(partialId)
        assertEquals(3L * BlobStore.CHUNK, resumeAt)

        // Count what the resumed run actually consumes past the skip.
        val counted = CountingStream(ByteArrayInputStream(bytes.copyOfRange(resumeAt.toInt(), bytes.size)))
        store.writeTo(partialId, counted, vmk, resume = true)

        val expectedRemaining = bytes.size - resumeAt
        Log.i(TAG, "resume: offset $resumeAt, remaining read ${counted.bytesRead} of $expectedRemaining")
        assertEquals(expectedRemaining, counted.bytesRead)
        assertTrue("resume re-read the whole source", counted.bytesRead < bytes.size)
    }

    @Test fun aPartialChunkIsDiscardedBeforeResuming() {
        val bytes = ByteArray(BlobStore.CHUNK * 3 + 42).also { rng.nextBytes(it) }
        val uri = source("resume-c.png", bytes)

        val partialId = blobId()
        // Two whole chunks plus a torn 900 bytes of a third.
        tearAfter(partialId, bytes, wholeChunks = 2, extraBytes = 900)

        assertEquals(2L * BlobStore.CHUNK, store.resumeOffset(partialId))

        val resumed = pipeline().run(uri, mutableListOf(partialId), vmk) as ImportOutcome.Done
        assertArrayEquals(bytes, readAll(resumed.item))
    }

    @Test fun aLockedVaultHasNoKeyToFindAnywhere() {
        assertFalse("session should start locked", VaultSession.isUnlocked)
        assertNull("a locked session handed out a key", VaultSession.vmk())
    }

    @Test fun theSchedulerDatabaseNeverHoldsTheKey() {
        val marker = VaultKeys.newVmk()
        VaultSession.unlocked(OpenVault(marker, ByteArray(16), 0))

        // Everything WorkManager persists lives under databases/ and no-backup.
        val roots = listOf(
            File(ctx.dataDir, "databases"),
            ctx.noBackupFilesDir,
            ctx.filesDir
        )
        val hits = mutableListOf<String>()
        roots.filter { it.exists() }.forEach { root ->
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                runCatching { if (indexOf(f.readBytes(), marker) >= 0) hits += f.absolutePath }
            }
        }
        assertTrue("the VMK was found on disk: $hits", hits.isEmpty())
    }

    // --- group 6: I6 -----------------------------------------------------------

    @Test fun aVerifiedImportIsDeletableAndCanProduceARequest() {
        val bytes = ByteArray(20_000).also { rng.nextBytes(it) }
        val uri = source("i6-good.png", bytes)

        val done = pipeline().run(uri, mutableListOf(), vmk) as ImportOutcome.Done

        assertEquals(done.sourceHash, done.blobHash)
        assertTrue("a verified item was not deletable", done.item.deletable)
        Log.i(TAG, "I6 ok: source ${done.sourceHash.take(16)}… blob ${done.blobHash.take(16)}…")
    }

    @Test fun aCorruptCopyIsNotDeletable() {
        val bytes = ByteArray(BlobStore.CHUNK * 2).also { rng.nextBytes(it) }
        val uri = source("i6-bad.png", bytes)
        val id = blobId()

        // Import, then damage the stored blob and re-verify — the second clause of
        // P1-7's Verify line.
        pipeline().run(uri, mutableListOf(id), vmk)
        RandomAccessFile(store.fileOf(id), "rw").use {
            it.seek(BlobStore.offsetOfChunk(1))
            it.write(ByteArray(64) { 0x7F })
        }

        // Re-run the real I6 check against the damaged copy. The verdict must come
        // from the pipeline, not from a flag the test set itself.
        val check = pipeline().verify(uri, listOf(id), vmk, bytes.size.toLong())

        assertFalse("a damaged copy verified", check.matches)
        Log.i(TAG, "I6 corrupt: source ${check.sourceHash.take(16)}… blob ${check.blobHash}")

        val damaged = VaultItem(
            blobIds = listOf(id),
            plaintextLength = bytes.size.toLong(),
            thumb = null,
            filename = "i6-bad.png",
            album = null,
            createdAt = null,
            importedAt = 0,
            tags = emptyList(),
            deletable = check.matches          // whatever the pipeline decided
        )
        assertFalse("a damaged copy was marked deletable", damaged.deletable)
        assertNull(
            "a delete request was produced for an unverified item",
            pipeline().deleteRequestFor(damaged, listOf(uri))
        )
    }

    // --- group 7: progress, cancel, batch --------------------------------------

    @Test fun progressReflectsBytesNotFiles() {
        val bytes = ByteArray(BlobStore.CHUNK * 3).also { rng.nextBytes(it) }
        val uri = source("progress.png", bytes)

        val seen = mutableListOf<Long>()
        pipeline().run(uri, mutableListOf(), vmk, onProgress = { written, _ -> seen += written })

        assertTrue("no progress reported", seen.isNotEmpty())
        assertEquals("final progress is not the file size", bytes.size.toLong(), seen.last())
        assertTrue("progress did not increase", seen.first() < seen.last())
        Log.i(TAG, "progress: ${seen.size} updates, ${seen.first()}..${seen.last()} of ${bytes.size}")
    }

    @Test fun cancellationStopsAndLeavesNoFinishedItem() {
        val bytes = ByteArray(BlobStore.CHUNK * 6).also { rng.nextBytes(it) }
        val uri = source("cancel.png", bytes)
        val id = blobId()

        var reads = 0
        val outcome = pipeline().run(uri, mutableListOf(id), vmk, isCancelled = { reads++ > 2 })

        assertTrue("cancel did not stop the import", outcome is ImportOutcome.Cancelled)
        assertFalse("a cancelled import left a finished blob", store.fileOf(id).exists())
    }

    @Test fun completedItemsSurviveABatchThatStopsPartway() {
        val db = MetadataDb.empty()
        val ok = source("batch-1.png", ByteArray(5_000).also { rng.nextBytes(it) })
        val alsoOk = source("batch-2.png", ByteArray(6_000).also { rng.nextBytes(it) })

        listOf(ok, alsoOk).forEach { uri ->
            val done = pipeline().run(uri, mutableListOf(), vmk) as ImportOutcome.Done
            db.items += done.item
            db.save(store, indexId, vmk)   // per completed item, not per batch
        }
        // A third item never runs, as though the process stopped here.

        assertEquals(2, MetadataDb.load(store, indexId, vmk).items.size)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
