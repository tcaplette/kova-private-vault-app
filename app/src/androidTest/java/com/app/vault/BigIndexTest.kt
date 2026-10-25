package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The index across a chunk boundary — the crash a long note produced on a device.
 *
 * A note written before v12 carried its text in the index row, so a note long enough
 * to overflow the editor's window pushed the *index* past one 64 KiB chunk. This is
 * that shape, at the sizes either side of the boundary.
 */
@RunWith(AndroidJUnit4::class)
class BigIndexTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray
    private lateinit var indexId: ByteArray

    @Before fun setUp() {
        dir = File(ctx.filesDir, "big-index-test").apply { mkdirs() }
        store = BlobStore(dir)
        vmk = VaultKeys.newVmk()
        indexId = ByteArray(BlobStore.BLOB_ID_BYTES) { (it + 11).toByte() }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    /** A legacy note row of [bytes] of body, saved and read back. */
    private fun roundTrip(bytes: Int) {
        val db = MetadataDb.empty()
        db.items += VaultItem(
            blobIds = emptyList(),
            plaintextLength = bytes.toLong(),
            thumb = null,
            filename = "long.txt",
            album = null,
            createdAt = null,
            importedAt = 1_700_000_000_000L,
            tags = emptyList(),
            body = "a".repeat(bytes),
            noteId = ByteArray(16) { 1 },
        )
        db.save(store, indexId, vmk)

        val blob = store.fileOf(indexId)
        val back = MetadataDb.load(store, indexId, vmk)
        assertEquals(
            "body of $bytes B: index blob is ${blob.length()} B",
            bytes,
            (back.items.single().body ?: VaultNote.read(store, back.items.single(), vmk)).length,
        )
    }

    @Test fun anIndexInsideOneChunk() = roundTrip(1_000)
    @Test fun anIndexJustUnderOneChunk() = roundTrip(65_000)
    @Test fun anIndexJustOverOneChunk() = roundTrip(66_000)
    @Test fun anIndexOverTwoChunks() = roundTrip(140_000)

    /**
     * **The index blob can never be 64 KiB**, and this is what says so.
     *
     * One sealed chunk is `CHUNK + TAG` = 65552 bytes, plus the 16-byte nonce prefix
     * is 65568 — past the 64 KiB rung of the §5 ladder, so the smallest index blob
     * any write can produce is 128 KiB. A 64 KiB file at an index's blob ID is
     * therefore not an index, and `load` reading it hits EOF on chunk 0 before it
     * parses a byte. That was the crash on a real device, and the arithmetic is
     * asserted here so the floor cannot move without someone noticing.
     */
    @Test fun theIndexBlobIsNeverTheSmallestBucket() {
        listOf(0, 1_000, 65_000, 66_000, 140_000).forEach { n ->
            val db = MetadataDb.empty()
            if (n > 0) {
                db.items += VaultItem(
                    emptyList(), n.toLong(), null, "long.txt", null, null, 1L, emptyList(),
                    body = "a".repeat(n), noteId = ByteArray(16) { 1 },
                )
            }
            db.save(store, indexId, vmk)
            val size = store.fileOf(indexId).length()
            assertTrue(
                "body=$n produced a $size B index, at or below the 64 KiB rung",
                size >= 128 * 1024,
            )
        }
    }

    // --- the guard -------------------------------------------------------------

    /**
     * ***The crash from the device, as a test.*** A pointer aimed at a 64 KiB blob used
     * to read past the end of it and kill the process; it is a typed failure now.
     */
    @Test
    fun aPointerAtABlobTooSmallToBeAnIndexIsRefusedRatherThanReadPast() {
        // An ordinary small blob — the size chaff, a keyring and a small pack land on.
        val notAnIndex = store.writeItem(java.io.ByteArrayInputStream(ByteArray(200) { 4 }), vmk)
        val id = notAnIndex.blobIds.single()
        assertEquals("the premise: a 64 KiB blob", 64L * 1024, store.fileOf(id).length())

        val failure = try {
            MetadataDb.load(store, id, vmk); null
        } catch (e: Throwable) {
            e
        }

        assertTrue(
            "expected NotAnIndexException, got ${failure?.javaClass?.simpleName}",
            failure is MetadataDb.NotAnIndexException,
        )
        assertTrue(
            "an end-of-file error must not escape",
            failure !is java.io.IOException,
        )
        val named = failure as MetadataDb.NotAnIndexException
        assertEquals(64L * 1024, named.size)
        assertTrue("it must say how much was needed", named.needed > named.size)
    }

    /** A pointer at nothing is still an empty vault, not a failure. */
    @Test
    fun aPointerAtNothingIsStillAnEmptyIndex() {
        val missing = ByteArray(BlobStore.BLOB_ID_BYTES) { 99 }
        assertEquals(0, MetadataDb.load(store, missing, vmk).items.size)
    }

    // --- the search ------------------------------------------------------------

    /** A vault with an index, its own items, chaff, and another vault's index beside it. */
    private fun populate(): ByteArray {
        val db = MetadataDb.empty()
        repeat(3) { n ->
            val item = store.writeItem(java.io.ByteArrayInputStream(ByteArray(300_000) { n.toByte() }), vmk)
            db.items += VaultItem(
                item.blobIds, item.plaintextLength, null, "photo$n.jpg", null, null, 1L, emptyList()
            )
        }
        db.save(store, indexId, vmk)
        // Another vault's index — same store, different key.
        val stranger = VaultKeys.newVmk()
        MetadataDb.empty().save(store, ByteArray(16) { 77 }, stranger)
        // Chaff: random bytes nothing can open.
        store.refreshChaff(emptyList())
        return indexId
    }

    @Test
    fun theIndexIsFoundAmongEverythingElse() {
        populate()
        val found = MetadataDb.findIndex(store, vmk)

        assertTrue("nothing was found", found != null)
        assertEquals(indexId.toList(), found!!.toList())
        assertEquals("and it is the real index", 3, MetadataDb.load(store, found, vmk).items.size)
    }

    /** Another vault's index must not be mistaken for this one's — its tag will not open. */
    @Test
    fun aStrangersIndexIsNotReturned() {
        val stranger = VaultKeys.newVmk()
        val strangerId = ByteArray(16) { 55 }
        MetadataDb.empty().save(store, strangerId, stranger)

        assertEquals("a store with no index of ours must find none", null, MetadataDb.findIndex(store, vmk))
    }

    /** A store holding only this vault's photographs has no index to find. */
    @Test
    fun ourOwnContentIsNotMistakenForAnIndex() {
        repeat(4) { n ->
            store.writeItem(java.io.ByteArrayInputStream(ByteArray(200_000) { n.toByte() }), vmk)
        }
        assertEquals(null, MetadataDb.findIndex(store, vmk))
    }

    @Test
    fun anEmptyStoreFindsNothingAndChangesNothing() {
        assertEquals(null, MetadataDb.findIndex(store, vmk))
        assertEquals(0, store.allBlobIds().size)
    }

    /** The search must not write. */
    @Test
    fun theSearchLeavesTheStoreExactlyAsItFoundIt() {
        populate()
        val before = store.allBlobIds().map { it.toList() to store.fileOf(it).length() }.toSet()

        MetadataDb.findIndex(store, vmk)

        val after = store.allBlobIds().map { it.toList() to store.fileOf(it).length() }.toSet()
        assertEquals(before, after)
    }

    /**
     * An index from a newer build is **this vault's index** and is reported as itself.
     *
     * Passing over it would tell its owner their vault was gone at the exact moment it
     * is not — it is there, readable by a build they can install.
     */
    @Test
    fun anIndexFromANewerBuildIsReportedRatherThanPassedOver() {
        // A real index, resealed with its version byte moved past what this build reads.
        //
        // **A literal, and deliberately far above the ceiling** — `IndexVersionTest`'s
        // idiom for the same boundary, for the reason this line has already
        // demonstrated. It read `NOTE_BLOB_VERSION + 1`, which meant "one past the
        // ceiling" only while the ceiling happened to be 12. The ceiling moved to 13
        // and this test began stamping the *current* version, so `findIndex` read it
        // correctly, threw nothing, and the assertion below failed — a green feature
        // and a red suite, with nothing wrong in the app at all. A version derived
        // from a constant that is not the ceiling drifts the moment the ceiling does;
        // 99 does not.
        val db = MetadataDb.empty()
        val body = db.serialise()
        body[0] = FROM_THE_FUTURE.toByte()
        val plain = ByteArray(BlobStore.CHUNK)
        java.nio.ByteBuffer.wrap(plain, 0, 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(body.size.toLong())
        body.copyInto(plain, 8)
        store.writeTo(indexId, java.io.ByteArrayInputStream(plain), vmk)

        val failure = try {
            MetadataDb.findIndex(store, vmk); null
        } catch (e: Throwable) {
            e
        }
        assertTrue(
            "expected NewerVersionException, got ${failure?.javaClass?.simpleName}",
            failure is MetadataDb.NewerVersionException,
        )
        assertEquals(FROM_THE_FUTURE, (failure as MetadataDb.NewerVersionException).found)
    }

    private companion object {
        /**
         * A version no build will ever write, so this test's meaning does not depend on
         * where the ceiling currently sits. `IndexVersionTest` uses the same number for
         * the same boundary.
         */
        const val FROM_THE_FUTURE = 99
    }
}
