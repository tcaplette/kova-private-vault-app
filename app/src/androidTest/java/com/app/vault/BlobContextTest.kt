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
import java.security.SecureRandom

/**
 * P1-8 task 3.2 — the key-derivation context, against `FORMAT.md` §3 and §8.
 *
 * Content blobs seal under `vaultblb`, thumbnail packs under `vaultthm`. The
 * separation is only worth having if it actually separates, and only safe if it
 * changes nothing an attacker can see on disk. Both halves are asserted here.
 */
@RunWith(AndroidJUnit4::class)
class BlobContextTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray
    private val rng = SecureRandom()

    private companion object { const val TAG = "VaultBlobCtx" }

    @Before
    fun setUp() {
        dir = File(ctx.filesDir, "blobctx-test-${System.nanoTime()}")
        store = BlobStore(dir)
        vmk = VaultKeys.newVmk()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        vmk.fill(0)
    }

    private fun write(bytes: ByteArray, context: VaultKeys.BlobContext) =
        store.write(ByteArrayInputStream(bytes), vmk, context)

    @Test
    fun aThumbnailPackDoesNotOpenUnderTheContentKey() {
        val plain = ByteArray(40_000).also { rng.nextBytes(it) }
        val written = write(plain, VaultKeys.BlobContext.THUMBNAIL)

        // Correct context round-trips.
        store.reader(listOf(written.blobId), vmk, written.plaintextLength, VaultKeys.BlobContext.THUMBNAIL)
            .use { r ->
                val buf = ByteArray(BlobStore.CHUNK)
                assertEquals(plain.size, r.readChunk(0, buf))
                assertArrayEquals(plain, buf.copyOf(plain.size))
            }

        // Content context does not.
        val failed = runCatching {
            store.reader(listOf(written.blobId), vmk, written.plaintextLength).use { r ->
                r.readChunk(0, ByteArray(BlobStore.CHUNK))
            }
        }.isFailure
        assertTrue("a thumbnail pack must not open under the content key", failed)
    }

    @Test
    fun aContentBlobDoesNotOpenUnderTheThumbnailKey() {
        val plain = ByteArray(40_000).also { rng.nextBytes(it) }
        val written = write(plain, VaultKeys.BlobContext.CONTENT)

        val failed = runCatching {
            store.reader(listOf(written.blobId), vmk, written.plaintextLength, VaultKeys.BlobContext.THUMBNAIL)
                .use { r -> r.readChunk(0, ByteArray(BlobStore.CHUNK)) }
        }.isFailure
        assertTrue("a content blob must not open under the thumbnail key", failed)
    }

    @Test
    fun theEnvelopeIsIdenticalWhicheverContextSealedIt() {
        val plain = ByteArray(70_000).also { rng.nextBytes(it) }
        val content = write(plain, VaultKeys.BlobContext.CONTENT)
        val thumb = write(plain, VaultKeys.BlobContext.THUMBNAIL)

        val a = store.fileOf(content.blobId)
        val b = store.fileOf(thumb.blobId)

        // Same plaintext, same structure: an attacker sorting the directory by
        // size learns nothing about which key class sealed what.
        assertEquals("file size must not depend on the key context", a.length(), b.length())
        assertEquals(BlobStore.bucketFor(a.length()), a.length())
        assertEquals(content.plaintextLength, thumb.plaintextLength)

        val ba = a.readBytes()
        val bb = b.readBytes()
        assertFalse("ciphertext must differ", ba.contentEquals(bb))
        Log.i(TAG, "envelope parity: content=${a.length()} B, thumbnail=${b.length()} B")
    }

    @Test
    fun theDefaultContextIsContent() {
        val plain = ByteArray(9_000).also { rng.nextBytes(it) }
        // Written with no context named, read with the content context explicitly.
        val written = store.write(ByteArrayInputStream(plain), vmk)
        store.reader(listOf(written.blobId), vmk, written.plaintextLength, VaultKeys.BlobContext.CONTENT)
            .use { r ->
                val buf = ByteArray(BlobStore.CHUNK)
                assertEquals(plain.size, r.readChunk(0, buf))
                assertArrayEquals(plain, buf.copyOf(plain.size))
            }
    }

    @Test
    fun subkeysDifferBetweenContextsForTheSameBlobId() {
        val blobId = ByteArray(16).also { rng.nextBytes(it) }
        val content = VaultKeys.blobKey(vmk, blobId, VaultKeys.BlobContext.CONTENT)
        val thumb = VaultKeys.blobKey(vmk, blobId, VaultKeys.BlobContext.THUMBNAIL)
        assertFalse("same blob ID must not yield the same key in both contexts",
            content.contentEquals(thumb))
        content.fill(0); thumb.fill(0)
    }

    @Test
    fun readRangeSpansChunkBoundaries() {
        // A thumbnail record is a range inside a pack, so the range read is the
        // access pattern that matters, not whole-chunk reads.
        val plain = ByteArray(200_000).also { rng.nextBytes(it) }
        val written = write(plain, VaultKeys.BlobContext.THUMBNAIL)

        store.reader(listOf(written.blobId), vmk, written.plaintextLength, VaultKeys.BlobContext.THUMBNAIL)
            .use { r ->
                // Straddles chunks 0 and 1.
                val offset = BlobStore.CHUNK - 100L
                val range = r.readRange(offset, 500)
                assertArrayEquals(plain.copyOfRange(offset.toInt(), offset.toInt() + 500), range)

                // Entirely inside one chunk.
                assertArrayEquals(plain.copyOfRange(1000, 1064), r.readRange(1000, 64))

                // Spans three chunks.
                val wide = r.readRange(10, BlobStore.CHUNK * 2 + 50)
                assertArrayEquals(plain.copyOfRange(10, 10 + BlobStore.CHUNK * 2 + 50), wide)

                // The tail.
                val tailLen = 123
                val tailOff = written.plaintextLength - tailLen
                assertArrayEquals(
                    plain.copyOfRange(tailOff.toInt(), plain.size),
                    r.readRange(tailOff, tailLen)
                )
            }
    }
}
