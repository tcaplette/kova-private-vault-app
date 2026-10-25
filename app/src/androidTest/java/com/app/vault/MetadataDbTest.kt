package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * P1-5 — the metadata index, against `FORMAT.md` §7 and OUTLINE P1-5's Verify:
 * the decision recorded, and no plaintext filename in the raw file.
 */
@RunWith(AndroidJUnit4::class)
class MetadataDbTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray
    private lateinit var dbId: ByteArray
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultMeta"
        val CORPUS = listOf(
            "holiday-invoice-2019.jpg",
            "Zwangsvollstreckung.pdf",
            "私的な写真.png",
            "passport-scan-final.heic",
            "🔥party🔥.mp4"
        )
    }

    @Before fun setUp() {
        dir = File(ctx.filesDir, "meta-test").apply { mkdirs() }
        store = BlobStore(dir)
        vmk = VaultKeys.newVmk()
        dbId = ByteArray(16).also { rng.nextBytes(it) }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun id() = ByteArray(16).also { rng.nextBytes(it) }

    private fun item(
        filename: String = "photo.jpg",
        thumb: ByteArray? = id(),
        album: String? = "Holidays",
        tags: List<String> = listOf("beach", "2019")
    ) = VaultItem(
        blobIds = listOf(id()),
        plaintextLength = 4_211_337L,
        thumb = thumb?.let { ThumbRef(it, 0, 1234) },
        filename = filename,
        album = album,
        createdAt = 1_565_000_000_000L,
        importedAt = 1_765_000_000_000L,
        tags = tags
    )

    private fun roundTrip(db: MetadataDb): MetadataDb {
        db.save(store, dbId, vmk)
        return MetadataDb.load(store, dbId, vmk)
    }

    private fun assertSame(a: VaultItem, b: VaultItem) {
        assertEquals(a.blobIds.size, b.blobIds.size)
        a.blobIds.zip(b.blobIds).forEach { (x, y) -> assertArrayEquals(x, y) }
        assertEquals(a.plaintextLength, b.plaintextLength)
        if (a.thumb == null) {
            assertNull(b.thumb)
        } else {
            assertArrayEquals(a.thumb!!.packBlobId, b.thumb!!.packBlobId)
            assertEquals(a.thumb!!.offset, b.thumb!!.offset)
            assertEquals(a.thumb!!.length, b.thumb!!.length)
        }
        assertEquals(a.filename, b.filename)
        assertEquals(a.album, b.album)
        assertEquals(a.createdAt, b.createdAt)
        assertEquals(a.importedAt, b.importedAt)
        assertEquals(a.tags, b.tags)
    }

    // --- group 4: serialisation ------------------------------------------------

    @Test fun emptyIndexRoundTrips() {
        val back = roundTrip(MetadataDb.empty())
        assertEquals(0, back.items.size)
        assertEquals(0, back.ownedChaff.size)
    }

    @Test fun fullyPopulatedItemRoundTrips() {
        val db = MetadataDb.empty()
        val original = item()
        db.items += original
        assertSame(original, roundTrip(db).items.single())
    }

    @Test fun absentOptionalsStayAbsent() {
        val db = MetadataDb.empty()
        db.items += item(thumb = null, album = null, tags = emptyList())

        val back = roundTrip(db).items.single()
        assertNull("thumbnail became non-null", back.thumb)
        assertNull("album became non-null", back.album)
        assertEquals(emptyList<String>(), back.tags)
    }

    @Test fun tenThousandItemsRoundTrip() {
        val db = MetadataDb.empty()
        repeat(10_000) { db.items += item(filename = "IMG_$it.jpg") }

        val start = System.nanoTime()
        db.save(store, dbId, vmk)
        val saveMs = (System.nanoTime() - start) / 1_000_000
        val onDisk = store.fileOf(dbId).length()

        val back = MetadataDb.load(store, dbId, vmk)
        Log.i(TAG, "10000 items: blob $onDisk B on disk, save ${saveMs} ms")

        assertEquals(10_000, back.items.size)
        assertEquals("IMG_9999.jpg", back.items.last().filename)
    }

    @Test fun emojiAndNonLatinFilenamesSurvive() {
        val db = MetadataDb.empty()
        CORPUS.forEach { db.items += item(filename = it) }
        assertEquals(CORPUS, roundTrip(db).items.map { it.filename })
    }

    @Test fun truncatedIndexFailsLoudly() {
        // Forge the *plaintext*: a length prefix claiming more body than follows,
        // so the parser runs off the end. Corrupting ciphertext instead would fail
        // at AEAD authentication and never reach the parser.
        val body = byteArrayOf(1, 0, 0, 0, 40)   // version 1, then a truncated item count
        writeForgedPlaintext(body, claimedLength = 4096L)

        var failed = false
        try { MetadataDb.load(store, dbId, vmk) } catch (_: Exception) { failed = true }
        assertTrue("a truncated index loaded", failed)
    }

    @Test fun unknownVersionFailsLoudly() {
        // A well-formed, correctly encrypted index whose version byte is not one
        // this build knows. Reaches the version check rather than dying at AEAD.
        val body = byteArrayOf(0x5A, 0, 0, 0, 0, 0, 0, 0, 0)   // version 90, 0 items, 0 chaff
        writeForgedPlaintext(body, claimedLength = body.size.toLong())

        var failed = false
        try { MetadataDb.load(store, dbId, vmk) } catch (_: Exception) { failed = true }
        assertTrue("an unknown version loaded", failed)
    }

    // --- group 5: the Verify line ----------------------------------------------

    @Test fun noPlaintextFilenameAppearsInTheRawBlob() {
        val db = MetadataDb.empty()
        CORPUS.forEach { db.items += item(filename = it, album = "Sensitive Album") }
        db.save(store, dbId, vmk)

        val raw = store.fileOf(dbId).readBytes()
        (CORPUS + "Sensitive Album" + "beach").forEach { needle ->
            assertEquals(
                "found '$needle' as UTF-8 in the raw blob",
                -1, indexOf(raw, needle.toByteArray(Charsets.UTF_8))
            )
            assertEquals(
                "found '$needle' as UTF-16 in the raw blob",
                -1, indexOf(raw, needle.toByteArray(Charsets.UTF_16LE))
            )
        }
        Log.i(TAG, "grep: ${CORPUS.size + 2} needles, none present in ${raw.size} B blob")
    }

    @Test fun theIndexBlobIsBucketSized() {
        val db = MetadataDb.empty()
        db.items += item()
        db.save(store, dbId, vmk)
        assertTrue(
            "index blob size ${store.fileOf(dbId).length()} is not a bucket value",
            store.fileOf(dbId).length() in BlobStore.SMALL_BUCKETS
        )
    }

    @Test fun theBlobIdIsStableAcrossSaves() {
        val db = MetadataDb.empty()
        db.items += item()
        db.save(store, dbId, vmk)
        val first = store.fileOf(dbId).readBytes()

        db.items += item()
        db.save(store, dbId, vmk)

        assertEquals(1, dir.listFiles()!!.size)
        assertTrue("blob did not change", !first.contentEquals(store.fileOf(dbId).readBytes()))
    }

    // --- group 6: persistence --------------------------------------------------

    @Test fun mutatingWithoutSavingChangesNothingOnDisk() {
        val db = MetadataDb.empty()
        db.items += item(filename = "saved.jpg")
        db.save(store, dbId, vmk)

        db.items += item(filename = "not-saved.jpg")

        val reloaded = MetadataDb.load(store, dbId, vmk)
        assertEquals(1, reloaded.items.size)
        assertEquals("saved.jpg", reloaded.items.single().filename)
    }

    @Test fun oneHundredItemsCostOneWrite() {
        val db = MetadataDb.empty()
        repeat(100) { db.items += item() }
        db.save(store, dbId, vmk)

        // One blob in the directory, and no leftover temp file.
        assertEquals(1, dir.listFiles()!!.size)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun aMissingBlobLoadsAsEmpty() {
        val back = MetadataDb.load(store, id(), vmk)
        assertEquals(0, back.items.size)
    }

    @Test fun ownedChaffRoundTripsAndFeedsRefresh() {
        val db = MetadataDb.empty()
        db.ownedChaff += store.writeChaff(3)
        db.save(store, dbId, vmk)

        val back = MetadataDb.load(store, dbId, vmk)
        assertEquals(3, back.ownedChaff.size)

        val fresh = store.refreshChaff(back.ownedChaff)
        assertEquals(3, fresh.size)
        fresh.forEach { assertTrue(store.fileOf(it).exists()) }
    }

    // --- helpers ---------------------------------------------------------------

    /**
     * Encrypts a hand-built index plaintext under the real key, so the parser is
     * what rejects it — not the AEAD.
     */
    private fun writeForgedPlaintext(body: ByteArray, claimedLength: Long) {
        val plain = ByteArray(BlobStore.CHUNK)
        ByteBuffer.wrap(plain, 0, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(claimedLength)
        body.copyInto(plain, 8)
        store.writeTo(dbId, java.io.ByteArrayInputStream(plain), vmk)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
