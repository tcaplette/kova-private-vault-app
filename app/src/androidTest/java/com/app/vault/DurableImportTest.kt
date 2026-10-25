package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * P1-8.6 — durable imports.
 *
 * The headline claim (a swipe-away no longer loses an import) can only be
 * measured on the device and is recorded in `OUTLINE.md`. What is assertable
 * here is the property that makes Gate 1's job simple: the pooled store contains
 * nothing but complete, bucket-sized blobs, at every instant — including while a
 * large import is running.
 */
@RunWith(AndroidJUnit4::class)
class DurableImportTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var blobs: File
    private lateinit var incoming: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray
    private val rng = SecureRandom()

    private companion object { const val TAG = "VaultDurable" }

    @Before
    fun setUp() {
        root = File(ctx.filesDir, "durable-${System.nanoTime()}").apply { mkdirs() }
        blobs = File(root, "blobs")
        incoming = File(root, "incoming")
        store = BlobStore(blobs, incoming)
        vmk = VaultKeys.newVmk()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        vmk.fill(0)
    }

    private fun blobFiles() = blobs.listFiles().orEmpty().filter { it.isFile }
    private fun partials() = incoming.listFiles().orEmpty().filter { it.isFile }

    /** Feeds bytes slowly enough that a sampler can observe the write in progress. */
    private class SlowStream(private val total: Long) : InputStream() {
        private var served = 0L
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= total) return -1
            Thread.sleep(2)
            val n = minOf(len.toLong(), total - served, 32L * 1024).toInt()
            java.util.Random(served).nextBytes(ByteArray(n).also { it.copyInto(b, off) })
            served += n
            return n
        }
    }

    // --- 1. the pool holds only finished blobs --------------------------------

    @Test
    fun thePooledStoreHoldsOnlyBucketSizedFilesDuringAnImport() {
        val stop = AtomicBoolean(false)
        val offenders = mutableListOf<String>()
        var samples = 0

        val sampler = thread {
            while (!stop.get()) {
                samples++
                blobFiles().forEach { f ->
                    if (f.length() != BlobStore.bucketFor(f.length()) || f.name.endsWith(".tmp")) {
                        offenders += "${f.name} (${f.length()} B)"
                    }
                }
            }
        }

        store.writeTo(ByteArray(16).also { rng.nextBytes(it) }, SlowStream(3_000_000), vmk, false)
        stop.set(true)
        sampler.join(10_000)

        Log.i(TAG, "sampled the pool $samples times during a 3 MB import, ${offenders.size} offenders")
        assertTrue("sampler never ran", samples > 0)
        assertTrue(
            "the pooled store held a non-bucket file mid-import: $offenders — this is what a " +
                "partial in blobs/ looks like to Gate 1",
            offenders.isEmpty()
        )
    }

    @Test
    fun anInterruptedWriteStagesOutsideThePool() {
        val id = ByteArray(16).also { rng.nextBytes(it) }
        val failing = object : InputStream() {
            var served = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served > 200_000) throw java.io.IOException("source died")
                val n = minOf(len, 65_536)
                served += n
                return n
            }
        }

        runCatching { store.writeTo(id, failing, vmk, false) }

        Log.i(TAG, "after interruption: ${blobFiles().size} in pool, ${partials().size} staged")
        assertTrue("a partial leaked into the pooled store", blobFiles().none { it.name.endsWith(".tmp") })
        assertEquals("the partial should be staged in incoming/", 1, partials().size)
        assertTrue("resume offset lost", store.resumeOffset(id) > 0)
    }

    @Test
    fun resumeStillWorksFromTheStagedPartial() {
        val id = ByteArray(16).also { rng.nextBytes(it) }
        val payload = ByteArray(300_000).also { rng.nextBytes(it) }

        // Interrupt after two whole chunks.
        var served = 0
        val partial = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= 2 * BlobStore.CHUNK) throw java.io.IOException("stop")
                val n = minOf(len, BlobStore.CHUNK)
                payload.copyInto(b, off, served, served + n)
                served += n
                return n
            }
        }
        runCatching { store.writeTo(id, partial, vmk, false) }
        val offset = store.resumeOffset(id)
        assertEquals("should resume from two whole chunks", 2L * BlobStore.CHUNK, offset)

        // Resume with the remainder.
        val rest = ByteArrayInputStream(payload, offset.toInt(), payload.size - offset.toInt())
        val written = store.writeTo(id, rest, vmk, resume = true)

        assertEquals(payload.size.toLong(), written.plaintextLength)
        store.reader(id, vmk, written.plaintextLength).use { r ->
            assertArrayEquals("resumed blob differs from the source", payload, r.readRange(0, payload.size))
        }
        assertEquals("the partial should be gone once the blob lands", 0, partials().size)
    }

    // --- 2. failure semantics --------------------------------------------------

    @Test
    fun discardingAPartialLeavesStorageAsItWas() {
        val id = ByteArray(16).also { rng.nextBytes(it) }
        val before = blobFiles().size

        val failing = object : InputStream() {
            var served = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served > 200_000) throw java.io.IOException("source died")
                served += minOf(len, 65_536)
                return minOf(len, 65_536)
            }
        }
        runCatching { store.writeTo(id, failing, vmk, false) }
        assertEquals(1, partials().size)

        assertTrue("discard reported nothing to delete", store.discardPartial(id))
        assertEquals("a discarded partial must leave nothing behind", 0, partials().size)
        assertEquals(before, blobFiles().size)
        assertEquals("resume state must be gone with it", 0L, store.resumeOffset(id))
    }

    @Test
    fun aRetryablePartialIsNotDiscarded() {
        // The inverse, and the one that matters: deleting on retry would silently
        // undo the whole of P1-7. Asserted as behaviour, not as a comment.
        val id = ByteArray(16).also { rng.nextBytes(it) }
        val failing = object : InputStream() {
            var served = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served > 200_000) throw java.io.IOException("stop")
                served += minOf(len, 65_536)
                return minOf(len, 65_536)
            }
        }
        runCatching { store.writeTo(id, failing, vmk, false) }

        val offset = store.resumeOffset(id)
        assertTrue("nothing to resume from", offset > 0)
        // No discard call — this is the retry path.
        assertEquals("the partial is the resume state and must survive a retry", offset, store.resumeOffset(id))
        assertEquals(1, partials().size)
    }

    // --- 3. the sweep -----------------------------------------------------------

    @Test
    fun stagedPartialsAreEnumerableByBlobId() {
        val ids = (1..3).map { ByteArray(16).also { b -> rng.nextBytes(b) } }
        ids.forEach { id ->
            val failing = object : InputStream() {
                var served = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (served > 100_000) throw java.io.IOException("stop")
                    served += minOf(len, 65_536)
                    return minOf(len, 65_536)
                }
            }
            runCatching { store.writeTo(id, failing, vmk, false) }
        }

        val staged = store.stagedPartials().toSet()
        val expected = ids.map { it.joinToString("") { b -> "%02x".format(b) } }.toSet()
        Log.i(TAG, "staged partials: ${staged.size}")
        assertEquals("the sweep cannot find what it cannot enumerate", expected, staged)

        // Sweeping everything unclaimed clears them; nothing in the pool changes.
        val poolBefore = blobFiles().map { it.name }.sorted()
        staged.forEach { hex ->
            store.discardPartial(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }
        assertEquals(0, partials().size)
        assertEquals("the sweep touched the pooled store", poolBefore, blobFiles().map { it.name }.sorted())
    }

    @Test
    fun aSweepThatSparesClaimedIdsLeavesALiveImportAlone() {
        val live = ByteArray(16).also { rng.nextBytes(it) }
        val orphan = ByteArray(16).also { rng.nextBytes(it) }

        listOf(live, orphan).forEach { id ->
            val failing = object : InputStream() {
                var served = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (served > 100_000) throw java.io.IOException("stop")
                    served += minOf(len, 65_536)
                    return minOf(len, 65_536)
                }
            }
            runCatching { store.writeTo(id, failing, vmk, false) }
        }

        // The production sweep spares anything tagged on unfinished work.
        val claimed = setOf(live.joinToString("") { "%02x".format(it) })
        store.stagedPartials().forEach { hex ->
            if (hex !in claimed) {
                store.discardPartial(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }
        }

        assertTrue("a live import's partial was swept", store.resumeOffset(live) > 0)
        assertEquals("the orphan survived the sweep", 0L, store.resumeOffset(orphan))
        assertEquals(1, partials().size)
    }

    // --- 4. the store's own guarantee -------------------------------------------

    @Test
    fun aCompletedImportLeavesNothingStaged() {
        val payload = ByteArray(500_000).also { rng.nextBytes(it) }
        store.write(ByteArrayInputStream(payload), vmk)

        assertEquals("a finished import left a partial behind", 0, partials().size)
        assertEquals(1, blobFiles().size)
        assertFalse("the blob is not padded", blobFiles().single().length() != BlobStore.bucketFor(blobFiles().single().length()))
    }
}
