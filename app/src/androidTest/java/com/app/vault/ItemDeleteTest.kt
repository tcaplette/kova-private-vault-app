package com.app.vault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * P2-10 — one item out, and nothing else with it.
 *
 * **Since P2-12 these drive `VaultDeletion.purge`.** The destruction did not change
 * — this file is still the ordering rule, the split item, the shared pack, the
 * interrupted run and the cache eviction — but the name `delete` now means "move to
 * the trash", and what these assert is what happens when an item is actually
 * destroyed. The trash's own behaviour is `ItemTrashTest`.
 *
 * `OUTLINE.md`'s Verify line is `deleteOneOfEachTypeLeavesNothingBehind`, clause
 * for clause: the row is gone, the item's blob IDs are absent from `blobs/`, the
 * store's total blob count drops by exactly the item's blob count, the chaff count
 * is unchanged, and the vault re-locks and re-opens clean.
 *
 * The rest are the three ways a delete can be written wrong that no assertion in
 * the app caught before: leaving a split item's later blobs behind, deleting a
 * thumbnail pack that other items point into, and unlinking the blobs before the
 * index says they are gone.
 */
@RunWith(AndroidJUnit4::class)
class ItemDeleteTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: BlobStore
    private lateinit var thumbs: ThumbnailStore
    private lateinit var vault: VaultLifecycle
    private lateinit var indexId: ByteArray
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "ItemDeleteTest"
        const val PASS = "delete-test-passphrase"
    }

    private fun vmk() = VaultSession.vmk()!!

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "delete-${System.nanoTime()}").apply { mkdirs() }
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        thumbs = ThumbnailStore(store)
        vault = VaultLifecycle(File(root, "vault.slots"), store)
        assertTrue("fixture vault must open", vault.create(PASS.toByteArray()).opened)
        indexId = VaultSession.metadataBlobId()!!.copyOf()
    }

    @After
    fun tearDown() {
        VaultLifecycle.lockNow()
        ThumbnailCache.clear()
        root.deleteRecursively()
    }

    // --- fixtures -------------------------------------------------------------

    private fun image(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(18, 24, 33))
        val paint = Paint()
        for (i in 0 until 16) {
            paint.color = Color.rgb((i * 13) % 256, (i * 41) % 256, (i * 67) % 256)
            canvas.drawRect(i * w / 16f, 0f, (i + 1) * w / 16f, h.toFloat(), paint)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** A real h264 mp4 where the target can encode one; recorded, not skipped, when it cannot. */
    private fun video(): ByteArray {
        val file = File(root, "src.mp4")
        if (VideoCorpus.generate(VideoCorpus.SPECS.first(), file)) return file.readBytes()
        Log.w(TAG, "no h264 encoder on this target; using opaque bytes for the video item")
        return ByteArray(96 * 1024).also { rng.nextBytes(it) }
    }

    /** Stores [bytes] and records the row. Returns the item as the index holds it. */
    private fun addItem(input: InputStream, filename: String, withThumb: Boolean): VaultItem {
        val written = store.writeItem(input, vmk())
        var item = VaultItem(
            blobIds = written.blobIds,
            plaintextLength = written.plaintextLength,
            thumb = null,
            filename = filename,
            album = null,
            createdAt = null,
            importedAt = System.currentTimeMillis(),
            tags = emptyList(),
        )
        MetadataDb.commit(store, indexId, vmk()) { db ->
            if (withThumb) {
                Thumbnails.generate(store, item, vmk()).webp?.let { webp ->
                    item = item.withThumb(thumbs.stage(db, webp))
                    thumbs.flush(db, vmk())
                }
            }
            db.items += item
        }
        return item
    }

    private fun addItem(bytes: ByteArray, filename: String, withThumb: Boolean = true) =
        addItem(ByteArrayInputStream(bytes), filename, withThumb)

    private fun index() = MetadataDb.load(store, indexId, vmk())

    private fun blobCount() = store.allBlobIds().size

    // --- 1. the outline's Verify ---------------------------------------------

    @Test
    fun deleteOneOfEachTypeLeavesNothingBehind() {
        val photo = addItem(image(1200, 900), "photo.png")
        val clip = addItem(video(), "clip.mp4")
        val file = addItem(ByteArray(200 * 1024).also { rng.nextBytes(it) }, "notes.bin", withThumb = false)

        assertEquals("three rows to start", 3, index().items.size)

        listOf(photo, clip, file).forEach { item ->
            val before = index()
            val blobsBefore = blobCount()

            VaultDeletion.purge(store, indexId, vmk(), item)

            val after = index()
            assertFalse(
                "${item.filename}: the row survived the delete",
                after.items.any { it.itemId == item.itemId },
            )
            item.blobIds.forEach { id ->
                assertFalse(
                    "${item.filename}: blob ${id.hex()} is still in the pool",
                    store.fileOf(id).exists(),
                )
            }
            assertEquals(
                "${item.filename}: the pool must lose exactly this item's blobs",
                blobsBefore - item.blobIds.size,
                blobCount(),
            )
            assertEquals(
                "${item.filename}: chaff is not a delete's business",
                before.ownedChaff.size,
                after.ownedChaff.size,
            )
            before.ownedChaff.forEachIndexed { i, id ->
                assertArrayEquals(
                    "${item.filename}: a chaff blob was rewritten by a delete",
                    id,
                    after.ownedChaff[i],
                )
            }
        }

        assertEquals("every row gone", 0, index().items.size)

        // "…and the vault re-locks and re-opens clean."
        val chaffBeforeLock = index().ownedChaff.size
        VaultLifecycle.lockNow()
        assertTrue("the vault must reopen", vault.unlockAndOpen(PASS.toByteArray()))
        val reopened = MetadataDb.load(store, VaultSession.metadataBlobId()!!, vmk())
        assertEquals("the reopened index must be empty of items", 0, reopened.items.size)
        assertEquals(
            "the reopened vault must still own its chaff",
            chaffBeforeLock,
            reopened.ownedChaff.size,
        )
    }

    // --- 2. the split item ----------------------------------------------------

    @Test
    fun aSplitItemLeavesNoneOfItsBlobsBehind() {
        // One byte past the split, so the item is two blobs and the second one is
        // the one a delete written against `blobIds.first()` would leave behind.
        val length = BlobStore.SPLIT_PLAINTEXT + 1
        val item = addItem(patternStream(length), "large.bin", withThumb = false)
        assertTrue("fixture must actually span blobs", item.blobIds.size >= 2)

        val before = blobCount()
        VaultDeletion.purge(store, indexId, vmk(), item)

        item.blobIds.forEachIndexed { i, id ->
            assertFalse("blob $i of the split item survived", store.fileOf(id).exists())
        }
        assertEquals(before - item.blobIds.size, blobCount())
    }

    // --- 3. the shared thumbnail pack ----------------------------------------

    @Test
    fun deletingAnItemLeavesTheSharedPackAndItsNeighboursThumbnail() {
        addItem(image(900, 600), "doomed.png")
        addItem(image(640, 800), "keeper.png")
        // As the index holds them now. `video-frame-thumbnail` made a flush copy-on-write, so
        // the open pack moves to a successor at every flush and a row returned before the second
        // was added names a pack that has since been replaced.
        val doomed = index().items.single { it.filename == "doomed.png" }
        val keeper = index().items.single { it.filename == "keeper.png" }

        val pack = keeper.thumb!!.packBlobId
        assertArrayEquals("fixture must put both thumbnails in one pack", pack, doomed.thumb!!.packBlobId)

        VaultDeletion.purge(store, indexId, vmk(), doomed)

        assertTrue("the pack was deleted on behalf of one item", store.fileOf(pack).exists())
        val db = index()
        assertNotNull(
            "the surviving item's thumbnail must still read",
            thumbs.read(db, keeper.thumb, vmk()),
        )
        assertTrue(
            "the pack must still be listed by the index",
            db.thumbPacks.any { it.id.contentEquals(pack) },
        )
    }

    // --- 4. the interrupted delete -------------------------------------------

    @Test
    fun aDeleteInterruptedAfterTheCommitLeavesAVaultThatOpens() {
        val item = addItem(image(800, 600), "orphaned.png", withThumb = false)

        // Exactly what `VaultDeletion.purge` does, stopped after its **first** commit:
        // the row is out, the blob IDs went into owned chaff in that same commit, and
        // the files are still on disk. This is the state a kill between the two steps
        // leaves, and it is the state the ordering exists to make survivable.
        MetadataDb.commit(store, indexId, vmk()) { db ->
            db.items.removeAll { it.itemId == item.itemId }
            db.ownedChaff += item.blobIds
        }

        // **Recorded by the commit, and by nothing after it** —
        // `blob-ownership-by-record`. The index is read here, before any unlock and
        // with no sweep of any kind in between, so what the assertions below find is
        // what the commit itself wrote. That is the stronger property: the leftovers
        // are known to be this vault's because this vault said so while it still had
        // the rows in hand, rather than because a later scan found them unreferenced
        // and guessed — a guess with another vault's content on the other side of it.
        val db = index()
        assertFalse("the row must be gone", db.items.any { it.itemId == item.itemId })
        item.blobIds.forEach { id ->
            assertTrue(
                "leftover blob ${id.hex()} must have been recorded by the commit that " +
                    "removed the row",
                db.ownedChaff.any { it.contentEquals(id) },
            )
            assertTrue(
                "and its file must still be there for the unlink that did not happen",
                store.fileOf(id).exists(),
            )
        }

        VaultLifecycle.lockNow()
        assertTrue("the vault must still open", vault.unlockAndOpen(PASS.toByteArray()))
    }

    // --- 5. a delete beside an import ----------------------------------------

    @Test
    fun aDeleteConcurrentWithAnImportKeepsBothChanges() {
        val doomed = addItem(image(700, 500), "doomed.png", withThumb = false)
        val keeper = addItem(image(700, 500), "keeper.png", withThumb = false)

        val arriving = store.write(ByteArrayInputStream(image(500, 500)), vmk())
        val newRow = VaultItem(
            blobIds = listOf(arriving.blobId),
            plaintextLength = arriving.plaintextLength,
            thumb = null,
            filename = "arriving.png",
            album = null,
            createdAt = null,
            importedAt = System.currentTimeMillis(),
            tags = emptyList(),
        )

        val importer = thread { MetadataDb.commit(store, indexId, vmk()) { it.items += newRow } }
        val deleter = thread { VaultDeletion.purge(store, indexId, vmk(), doomed) }
        importer.join()
        deleter.join()

        val ids = index().items.map { it.itemId }
        assertFalse("the deleted row came back", ids.contains(doomed.itemId))
        assertTrue("the untouched row was lost", ids.contains(keeper.itemId))
        assertTrue("the imported row was lost", ids.contains(newRow.itemId))
    }

    // --- 6. the decoded copy --------------------------------------------------

    @Test
    fun deletingAnItemDropsItsDecodedThumbnail() {
        ThumbnailCache.clear()
        val item = addItem(image(1000, 750), "cached.png")
        val ref = item.thumb!!

        val db = index()
        assertNotNull(ThumbnailCache.get(ref) { thumbs.read(db, ref, vmk()) })
        val resident = ThumbnailCache.size()
        assertTrue("nothing was cached, so nothing would be proven", resident > 0)

        VaultDeletion.purge(store, indexId, vmk(), item)

        assertTrue(
            "the decoded thumbnail of a deleted item is still resident",
            ThumbnailCache.size() < resident,
        )
    }

    /** [length] bytes without holding them in memory — the split fixture is ~16 MiB. */
    private fun patternStream(length: Long): InputStream = object : InputStream() {
        private var at = 0L
        override fun read(): Int {
            if (at >= length) return -1
            return ((at++ * 31) and 0xFF).toInt()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= length) return -1
            val n = minOf(len.toLong(), length - at).toInt()
            for (i in 0 until n) b[off + i] = (((at + i) * 31) and 0xFF).toByte()
            at += n
            return n
        }
    }
}
