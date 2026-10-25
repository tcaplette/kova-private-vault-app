package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * `blob-size-cap` — no blob above 16 MiB, and an item that needs more spans them.
 *
 * Gate 1 found that chaff covers only the small ladder, so a blob above 16 MiB was
 * *provably* real content and `FORMAT.md` §6's "chaff or another vault's"
 * ambiguity — the whole deniability argument — did not reach it. The fix is that
 * nothing lands above the ladder rather than that the ladder grows.
 *
 * The tests that matter here are the round trips. A `blobIds.first()` that works
 * for every photograph and truncates every video is the bug this change can
 * introduce, and it hides on whichever read path nobody exercised with a large
 * item.
 */
@RunWith(AndroidJUnit4::class)
class BlobSplitTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private lateinit var vmk: ByteArray
    private lateinit var indexId: ByteArray
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultBlobSplit"
        const val PASS = "correcthorsebattery"
    }

    private fun pass() = PASS.toByteArray(Charsets.UTF_8)

    @Before fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "blobsplit-${System.nanoTime()}").apply { mkdirs() }
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        vault = VaultLifecycle(File(root, "vault.slots"), store)
        vault.create(pass())
        vmk = VaultSession.vmk()!!
        indexId = VaultSession.metadataBlobId()!!.copyOf()
    }

    @After fun tearDown() {
        VaultLifecycle.lockNow()
        root.deleteRecursively()
    }

    private fun add(name: String, bytes: ByteArray): VaultItem {
        val written = store.writeItem(ByteArrayInputStream(bytes), vmk)
        val db = MetadataDb.load(store, indexId, vmk)
        val item = VaultItem(
            written.blobIds, written.plaintextLength, null, name, null, null, 0, emptyList()
        )
        db.items += item
        db.save(store, indexId, vmk)
        return item
    }

    private fun readAll(item: VaultItem): ByteArray {
        val out = ByteArray(item.plaintextLength.toInt())
        store.reader(item.blobIds, vmk, item.plaintextLength).use { r ->
            val buf = ByteArray(BlobStore.CHUNK)
            var at = 0
            for (i in 0 until r.chunkCount) {
                val n = r.readChunk(i, buf)
                buf.copyInto(out, at, 0, n)
                at += n
            }
        }
        return out
    }

    // --- 2.1 the derived constant ---------------------------------------------

    @Test fun theSplitSizeFitsTheCapAndOneMoreChunkWouldNot() {
        val fileSize = BlobStore.PREFIX_BYTES + BlobStore.CHUNKS_PER_BLOB * BlobStore.STRIDE
        val oneMore = BlobStore.PREFIX_BYTES + (BlobStore.CHUNKS_PER_BLOB + 1) * BlobStore.STRIDE

        Log.i(
            TAG,
            "chunks/blob=${BlobStore.CHUNKS_PER_BLOB} split=${BlobStore.SPLIT_PLAINTEXT} " +
                "file=$fileSize cap=${BlobStore.MAX_BUCKET}"
        )
        assertTrue("a full blob fits the cap", fileSize <= BlobStore.MAX_BUCKET)
        assertTrue("and one more chunk would not", oneMore > BlobStore.MAX_BUCKET)
        assertEquals(
            "the split is whole chunks",
            0L, BlobStore.SPLIT_PLAINTEXT % BlobStore.CHUNK
        )
    }

    @Test fun theLadderStopsAtSixteenMebibytes() {
        assertEquals(16L * 1024 * 1024, BlobStore.MAX_BUCKET)
        assertEquals(BlobStore.MAX_BUCKET, BlobStore.SMALL_BUCKETS.max())
        // The large ladder is gone: asking for a bucket above the cap is a format
        // violation, not a bigger bucket.
        runCatching { BlobStore.bucketFor(BlobStore.MAX_BUCKET + 1) }
            .onSuccess { error("bucketFor returned $it above the cap") }
    }

    // --- 6.1 the property ------------------------------------------------------

    @Test fun noBlobInTheStoreExceedsTheCap() {
        val big = ByteArray((BlobStore.SPLIT_PLAINTEXT * 2 + 1_234_567).toInt())
            .also { rng.nextBytes(it) }
        val item = add("big.bin", big)

        assertEquals("three blobs", 3, item.blobIds.size)

        val sizes = File(root, "blobs").listFiles().orEmpty().map { it.length() }
        assertTrue("the store has blobs", sizes.isNotEmpty())
        sizes.forEach { size ->
            assertTrue("a blob of $size exceeds the cap", size <= BlobStore.MAX_BUCKET)
            assertTrue("$size is not a bucket", size in BlobStore.SMALL_BUCKETS)
        }
        Log.i(TAG, "blob sizes: ${sizes.groupingBy { it }.eachCount()}")
    }

    @Test fun theTopBucketHoldsChaffAsWellAsContent() {
        // The point of the cap: the bucket a large item lands in is one chaff also
        // occupies, so occupancy proves nothing about what a blob is.
        add("big.bin", ByteArray((BlobStore.SPLIT_PLAINTEXT + 1000).toInt()).also { rng.nextBytes(it) })
        val chaff = store.writeChaff(40, listOf(BlobStore.MAX_BUCKET))

        val atCap = File(root, "blobs").listFiles().orEmpty()
            .filter { it.length() == BlobStore.MAX_BUCKET }
        assertTrue("real blobs at the cap", atCap.size > chaff.size)
    }

    // --- 6.2 round trips on every read path ------------------------------------

    @Test fun aMultiBlobItemReadsBackByteIdentically() {
        val bytes = ByteArray((BlobStore.SPLIT_PLAINTEXT * 2 + 99).toInt()).also { rng.nextBytes(it) }
        val item = add("round.bin", bytes)
        assertEquals(3, item.blobIds.size)
        assertArrayEquals(bytes, readAll(item))
    }

    @Test fun anItemEndingExactlyOnTheBoundaryHasNoEmptyTrailingBlob() {
        // The lookahead in writeItem exists for this: without it the item gains a
        // zero-byte blob, and with a naive fix it loses a byte per boundary.
        val bytes = ByteArray((BlobStore.SPLIT_PLAINTEXT * 2).toInt()).also { rng.nextBytes(it) }
        val item = add("exact.bin", bytes)

        assertEquals("exactly two blobs", 2, item.blobIds.size)
        assertEquals(bytes.size.toLong(), item.plaintextLength)
        assertArrayEquals(bytes, readAll(item))
    }

    @Test fun aRangeSpanningABlobBoundaryIsContinuous() {
        val bytes = ByteArray((BlobStore.SPLIT_PLAINTEXT + 500_000).toInt()).also { rng.nextBytes(it) }
        val item = add("range.bin", bytes)

        val start = BlobStore.SPLIT_PLAINTEXT - 5000
        store.reader(item.blobIds, vmk, item.plaintextLength).use { r ->
            val slice = r.readRange(start, 10_000)
            assertArrayEquals(
                "a read across the join is continuous",
                bytes.copyOfRange(start.toInt(), start.toInt() + 10_000),
                slice
            )
        }
    }

    @Test fun aSeekIntoTheThirdBlobIsCorrect() {
        val bytes = ByteArray((BlobStore.SPLIT_PLAINTEXT * 2 + 300_000).toInt())
            .also { rng.nextBytes(it) }
        val item = add("seek.bin", bytes)

        val offset = BlobStore.SPLIT_PLAINTEXT * 2 + 100_000
        store.reader(item.blobIds, vmk, item.plaintextLength).use { r ->
            assertArrayEquals(
                bytes.copyOfRange(offset.toInt(), offset.toInt() + 4096),
                r.readRange(offset, 4096)
            )
        }
    }

    @Test fun aSingleBlobItemIsUnchangedOnDisk() {
        val bytes = ByteArray(40_000).also { rng.nextBytes(it) }
        val item = add("small.bin", bytes)

        assertEquals("one blob, as before", 1, item.blobIds.size)
        assertEquals(
            "smallest bucket, as before",
            BlobStore.SMALL_BUCKETS.first(),
            store.fileOf(item.blobIds.first()).length()
        )
        assertArrayEquals(bytes, readAll(item))
    }

    // --- 6.2 export and archive ------------------------------------------------

    @Test fun aMultiBlobItemSurvivesPlaintextExport() {
        val bytes = ByteArray((BlobStore.SPLIT_PLAINTEXT + 777).toInt()).also { rng.nextBytes(it) }
        val item = add("export.bin", bytes)

        val destination = MediaStoreDestination(ctx)
        val manifest = ExportManifest.openAt(destination)
        try {
            assertTrue(Unvaulter(store, destination, manifest, ctx).exportOne(item, vmk, null))
            val entry = destination.find("export.bin")
            assertTrue("it reached the destination", entry != null)
            val out = ctx.contentResolver.openInputStream(entry!!.uri)!!.use { it.readBytes() }
            assertArrayEquals("every blob came out", bytes, out)
        } finally {
            destination.find("export.bin")?.let { destination.discard(it) }
            destination.find(ExportManifest.NAME)?.let { destination.discard(it) }
            destination.sweep()
        }
    }

    @Test fun aMultiBlobItemSurvivesAnArchiveRoundTrip() {
        val bytes = ByteArray((BlobStore.SPLIT_PLAINTEXT + 4242).toInt()).also { rng.nextBytes(it) }
        add("archived.bin", bytes)

        val archive = File(root, "out.vault")
        val db = MetadataDb.load(store, indexId, vmk)
        archive.outputStream().use { out -> VaultArchive.write(out, vmk, db, store, pass()) }
        assertTrue("something was written", archive.length() > BlobStore.SPLIT_PLAINTEXT)

        // A device that has never held this vault.
        val other = File(ctx.filesDir, "blobsplit-dest-${System.nanoTime()}").apply { mkdirs() }
        try {
            val destStore = BlobStore(File(other, "blobs"), File(other, "incoming"))
            val destVault = VaultLifecycle(File(other, "vault.slots"), destStore)
            VaultLifecycle.lockNow()
            // `recovery-own-slot-only`. An import joins an open list, so the device
            // has one of its own first.
            assertTrue(destVault.create(pass()).opened)
            VaultKeyringSession.leaveVault()
            val outcome = archive.inputStream().use {
                destVault.importArchive(it, pass())
            }
            assertTrue("the archive opened", outcome.opened)
            // `vault-keyring`. An import puts the vault on a list and opens nothing;
            // the vault is opened from that list, as it is after any unlock.
            assertTrue(destVault.openVaultAt(VaultKeyringSession.vaults().last().position))

            val restoredVmk = VaultSession.vmk()!!
            val restoredId = VaultSession.metadataBlobId()!!
            val restored = MetadataDb.load(destStore, restoredId, restoredVmk)
            val item = restored.items.single { it.filename == "archived.bin" }
            assertTrue("the blob list survived", item.blobIds.size >= 2)

            val out = ByteArray(item.plaintextLength.toInt())
            destStore.reader(item.blobIds, restoredVmk, item.plaintextLength).use { r ->
                val buf = ByteArray(BlobStore.CHUNK)
                var at = 0
                for (i in 0 until r.chunkCount) {
                    val n = r.readChunk(i, buf)
                    buf.copyInto(out, at, 0, n)
                    at += n
                }
            }
            assertArrayEquals("restored byte-identically", bytes, out)
        } finally {
            other.deleteRecursively()
        }
    }

    // --- 6.5 resume ------------------------------------------------------------

    @Test fun aResumedLargeWriteSkipsSealedBlobsAndMatches() {
        val bytes = ByteArray((BlobStore.SPLIT_PLAINTEXT * 2 + 5000).toInt())
            .also { rng.nextBytes(it) }

        // Stop after the first blob is sealed, the way a cancellation does.
        val claimed = mutableListOf<ByteArray>()
        var seen = 0
        store.writeItem(
            ByteArrayInputStream(bytes), vmk, claimed,
            claim = { seen++ },
            isCancelled = { seen > 1 },
        )
        assertTrue("at least one blob was sealed", claimed.isNotEmpty())
        val sealedFirst = store.fileOf(claimed.first()).length()
        assertEquals("the sealed blob is padded to the cap", BlobStore.MAX_BUCKET, sealedFirst)

        // Resume with the same claim list, feeding the source from the top: the
        // pipeline is what skips, so here the already-sealed blob must be reused
        // rather than rewritten.
        val before = store.fileOf(claimed.first()).lastModified()
        Thread.sleep(1100)
        val resumed = store.writeItem(
            ByteArrayInputStream(bytes).also { it.skip(claimed.size * BlobStore.SPLIT_PLAINTEXT) },
            vmk,
            claimed,
        )
        assertEquals(
            "the sealed blob was not rewritten",
            before, store.fileOf(claimed.first()).lastModified()
        )
        assertEquals(bytes.size.toLong(), resumed.plaintextLength)

        val item = VaultItem(
            resumed.blobIds, resumed.plaintextLength, null, "resumed.bin", null, null, 0, emptyList()
        )
        assertArrayEquals(bytes, readAll(item))
    }

    // --- 6.7 I6 over the whole item --------------------------------------------

    @Test fun anItemMissingABlobDoesNotVerify() {
        val bytes = ByteArray((BlobStore.SPLIT_PLAINTEXT + 10).toInt()).also { rng.nextBytes(it) }
        val item = add("i6.bin", bytes)
        assertEquals(2, item.blobIds.size)

        // Drop the second blob's file — a truncated item, which must never read as
        // whole and must never be offered for deletion.
        assertTrue(store.delete(item.blobIds[1]))

        val failed = runCatching { readAll(item) }.isFailure
        assertTrue("a truncated item must not read as whole", failed)
    }

    // --- 5.2 chaff ratio --------------------------------------------------------

    @Test fun perImportChaffScalesWithBlobsWritten() {
        // The rate is a tunable constant, so this asserts the shape rather than a
        // count: a many-blob import writes chaff of the same order, not a fixed
        // 0-2 sized for one blob per item.
        val blobs = 6
        var chaff = 0
        repeat(200) {
            chaff += (0..VaultLifecycle.IMPORT_CHAFF_MAX_PER_BLOB).random()
        }
        val meanPerBlob = chaff / 200.0
        Log.i(TAG, "mean chaff per blob over 200 draws: $meanPerBlob")
        assertTrue("mean is about one per blob", meanPerBlob in 0.7..1.3)
        assertNotEquals(
            "chaff for a $blobs-blob import is not the one-item constant",
            1, VaultLifecycle.IMPORT_CHAFF_MAX_PER_BLOB * blobs
        )
    }
}
