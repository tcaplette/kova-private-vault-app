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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * P1-4 — the pooled blob store, against `FORMAT.md` §5/§6 and OUTLINE P1-4's
 * Verify: only bucket values in the size histogram, and no correlation between
 * blob size and source size.
 *
 * On that second clause: absolute non-correlation is impossible — a 4 GB file
 * cannot occupy 64 KiB. What padding actually buys, and what is asserted here,
 * is that source size is unrecoverable *within* a bucket and known only to
 * bucket granularity across them.
 */
@RunWith(AndroidJUnit4::class)
class BlobStoreTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray
    private val rng = SecureRandom()

    private companion object { const val TAG = "VaultBlobs" }

    @Before fun setUp() {
        dir = File(ctx.filesDir, "blob-test").apply { mkdirs() }
        store = BlobStore(dir)
        vmk = VaultKeys.newVmk()
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun bytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }

    private fun put(data: ByteArray) = store.write(ByteArrayInputStream(data), vmk)

    private fun readAll(blob: WrittenBlob): ByteArray {
        store.reader(listOf(blob.blobId), vmk, blob.plaintextLength).use { r ->
            val out = ByteArray(blob.plaintextLength.toInt())
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

    // --- group 4: envelope -----------------------------------------------------

    @Test fun roundTripAcrossChunkBoundaries() {
        val data = bytes(BlobStore.CHUNK * 3 + 1234)
        assertArrayEquals(data, readAll(put(data)))
    }

    @Test fun singleShortChunkRoundTrips() {
        val data = bytes(37)
        assertArrayEquals(data, readAll(put(data)))
    }

    @Test fun anyChunkReadsWithoutItsPredecessors() {
        val data = bytes(BlobStore.CHUNK * 4 + 99)
        val blob = put(data)

        store.reader(listOf(blob.blobId), vmk, blob.plaintextLength).use { r ->
            val buf = ByteArray(BlobStore.CHUNK)
            // Straight to chunk 3 — nothing before it is touched.
            val n = r.readChunk(3, buf)
            assertArrayEquals(
                data.copyOfRange(3 * BlobStore.CHUNK, 3 * BlobStore.CHUNK + n),
                buf.copyOf(n)
            )
        }
    }

    @Test fun wrongKeyProducesNothing() {
        val blob = put(bytes(5000))
        val other = VaultKeys.newVmk()

        var failed = false
        try {
            store.reader(listOf(blob.blobId), other, blob.plaintextLength).use {
                it.readChunk(0, ByteArray(BlobStore.CHUNK))
            }
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue("a wrong key decrypted a chunk", failed)
    }

    @Test fun aChunkCannotBeMovedWithinItsBlob() {
        val data = bytes(BlobStore.CHUNK * 2)
        val blob = put(data)

        val file = store.fileOf(blob.blobId)
        val chunk0 = ByteArray(BlobStore.STRIDE)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(BlobStore.offsetOfChunk(0)); raf.readFully(chunk0)
            raf.seek(BlobStore.offsetOfChunk(1)); raf.write(chunk0)
        }

        var failed = false
        try {
            store.reader(listOf(blob.blobId), vmk, blob.plaintextLength).use {
                it.readChunk(1, ByteArray(BlobStore.CHUNK))
            }
        } catch (_: IllegalStateException) { failed = true }
        assertTrue("a relocated chunk authenticated", failed)
    }

    @Test fun aChunkCannotBeTransplantedBetweenBlobs() {
        val a = put(bytes(BlobStore.CHUNK))
        val b = put(bytes(BlobStore.CHUNK))

        val chunk = ByteArray(BlobStore.STRIDE)
        RandomAccessFile(store.fileOf(a.blobId), "r").use {
            it.seek(BlobStore.offsetOfChunk(0)); it.readFully(chunk)
        }
        RandomAccessFile(store.fileOf(b.blobId), "rw").use {
            it.seek(BlobStore.offsetOfChunk(0)); it.write(chunk)
        }

        var failed = false
        try {
            store.reader(listOf(b.blobId), vmk, b.plaintextLength).use {
                it.readChunk(0, ByteArray(BlobStore.CHUNK))
            }
        } catch (_: IllegalStateException) { failed = true }
        assertTrue("a transplanted chunk authenticated", failed)
    }

    @Test fun noPlaintextLengthAppearsInTheFile() {
        val length = 123_457
        val blob = put(bytes(length))
        val raw = store.fileOf(blob.blobId).readBytes()

        val needle = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(length.toLong()).array()
        // A 4-byte LE encoding would be the likelier accident; check both.
        val needle4 = needle.copyOf(4)

        assertEquals(-1, indexOf(raw, needle))
        assertEquals(-1, indexOf(raw, needle4))
    }

    // --- group 5: the Verify line ----------------------------------------------

    @Test fun histogramOverAMixedCorpusShowsOnlyBucketValues() {
        val corpus = listOf(
            1, 1000, 60_000, BlobStore.CHUNK, BlobStore.CHUNK + 1,
            200_000, 700_000, 1_500_000, 3_000_000
        )
        val sizes = corpus.map { store.fileOf(put(bytes(it)).blobId).length() }
        val buckets = sizes.toSortedSet()

        Log.i(TAG, "histogram: " + corpus.zip(sizes).joinToString { "${it.first}B->${it.second}B" })

        // No `|| size % 16 MiB == 0` any more: `blob-size-cap` removed the large
        // ladder, so a size off this list is a violation rather than a big file.
        val allowed = BlobStore.SMALL_BUCKETS.toSet()
        sizes.forEach { size ->
            assertTrue("on-disk size $size is not a bucket value", size in allowed)
            assertTrue("on-disk size $size exceeds the cap", size <= BlobStore.MAX_BUCKET)
        }
        assertTrue("corpus collapsed to too few buckets to be meaningful", buckets.size >= 3)
    }

    @Test fun differentSourceSizesCollapseToTheSameOnDiskSize() {
        // All four land in the same bucket. This is what "no correlation with
        // source size" actually means: within a bucket, the source size is gone.
        val sizes = listOf(70_000, 90_000, 110_000, 128_000)
            .map { store.fileOf(put(bytes(it)).blobId).length() }

        Log.i(TAG, "collapse: sources 70k/90k/110k/128k -> $sizes")
        assertEquals("sources did not collapse to one size", 1, sizes.toSet().size)
    }

    @Test fun paddingIsNotDistinguishableByStructure() {
        val blob = put(bytes(1000))
        val raw = store.fileOf(blob.blobId).readBytes()
        val used = 16 + 1000 + 16

        val padding = raw.copyOfRange(used, raw.size)
        assertTrue("padding region is empty", padding.size > 1000)
        assertFalse("padding is all zeroes", padding.all { it == 0.toByte() })

        val counts = IntArray(256)
        padding.forEach { counts[it.toInt() and 0xFF]++ }
        assertTrue("padding uses too few distinct byte values", counts.count { it > 0 } > 200)
    }

    // --- group 6: chaff --------------------------------------------------------

    @Test fun chaffIsIndistinguishableFromRealBlobsBySizeAndName() {
        put(bytes(50_000))
        put(bytes(300_000))
        store.writeChaff(4)

        val files = dir.listFiles()!!
        assertEquals(6, files.size)
        files.forEach { f ->
            assertTrue("name is not a 32-hex blob ID: ${f.name}", f.name.matches(Regex("[0-9a-f]{32}")))
            assertTrue("size ${f.length()} is not a bucket value", f.length() in BlobStore.SMALL_BUCKETS)
        }
    }

    @Test fun writeChaffReturnsItsIdsAndRecordsNothing() {
        val ids = store.writeChaff(3)
        assertEquals(3, ids.size)
        ids.forEach { assertTrue(store.fileOf(it).exists()) }
        // Nothing else in the directory: no index, no manifest, no marker file.
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test fun refreshChaffReplacesOnlyWhatItIsGiven() {
        val source = bytes(40_000)
        val real = put(source)
        val chaff = store.writeChaff(3)
        val untouched = chaff[2]
        val untouchedBefore = store.fileOf(untouched).readBytes()

        val fresh = store.refreshChaff(listOf(chaff[0], chaff[1]))

        assertFalse("old chaff still present", store.fileOf(chaff[0]).exists())
        assertFalse("old chaff still present", store.fileOf(chaff[1]).exists())
        fresh.forEach { assertTrue("replacement missing", store.fileOf(it).exists()) }

        assertArrayEquals(untouchedBefore, store.fileOf(untouched).readBytes())
        // The real blob still decrypts to its original bytes — compared against
        // the source, not against another read of itself.
        assertArrayEquals(source, readAll(real))
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
