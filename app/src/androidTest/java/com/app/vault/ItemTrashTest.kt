package com.app.vault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * P2-12 — the trash, and the window that empties it.
 *
 * `OUTLINE.md`'s Verify line, clause for clause: a deleted item is absent from the
 * grid, present in the trash, restorable **byte-identical**, and after a purge its
 * blobs are gone. Then the two surfaces that must not see the trash at all — the
 * plaintext export and the archive — and the accounting that must not mistake a
 * trashed item's blobs for orphans.
 *
 * "Absent from the grid" is asserted against the same predicate the grid filters
 * on, not against the screen: `MainActivity` sorts `items.filter { deletedAt ==
 * null }`, and what makes that correct is the field, which is what these check.
 * `MultiSelectDeviceTest` drives the real screens.
 */
@RunWith(AndroidJUnit4::class)
class ItemTrashTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: CountingStore
    private lateinit var thumbs: ThumbnailStore
    private lateinit var vault: VaultLifecycle
    private lateinit var indexId: ByteArray
    private val rng = SecureRandom()

    private companion object {
        const val PASS = "trash-test-passphrase"
    }

    private fun vmk() = VaultSession.vmk()!!

    /**
     * A store that counts writes to one blob — the index. The same subclass
     * `MultiSelectTest` uses, for the same reason: "one commit for the set" is a
     * claim about how many times the index is rewritten, and timing would measure
     * the emulator.
     */
    private class CountingStore(dir: File, incoming: File) : BlobStore(dir, incoming) {
        var watching: ByteArray? = null
        var writes = 0

        override fun writeTo(blobId: ByteArray, input: InputStream, vmk: ByteArray): WrittenBlob {
            if (watching?.contentEquals(blobId) == true) writes++
            return super.writeTo(blobId, input, vmk)
        }
    }

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "trash-${System.nanoTime()}").apply { mkdirs() }
        store = CountingStore(File(root, "blobs"), File(root, "incoming"))
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
        canvas.drawColor(Color.rgb(21, 26, 34))
        val paint = Paint()
        for (i in 0 until 12) {
            paint.color = Color.rgb((i * 17) % 256, (i * 37) % 256, (i * 59) % 256)
            canvas.drawRect(i * w / 12f, 0f, (i + 1) * w / 12f, h.toFloat(), paint)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /**
     * The folder the fixture's rows live in — `folder-system`, minted once so every
     * row shares it and a restore has a folder to land back in.
     */
    private val fixtureFolder: ByteArray by lazy {
        val id = MetadataDb.newFolderId()
        MetadataDb.commit(store, indexId, vmk()) { db ->
            db.folders += VaultFolder(id, null, "Folder")
        }
        id
    }

    private fun addItem(
        bytes: ByteArray,
        filename: String,
        withThumb: Boolean = true,
        deletable: Boolean = false,
    ): VaultItem {
        val written = store.writeItem(ByteArrayInputStream(bytes), vmk())
        var item = VaultItem(
            blobIds = written.blobIds,
            plaintextLength = written.plaintextLength,
            thumb = null,
            filename = filename,
            album = null,
            // `folder-system`: a trashed item keeps its folder, so the fixture puts
            // it in one and the restore asserts it is still there.
            folderId = fixtureFolder,
            createdAt = null,
            importedAt = System.currentTimeMillis(),
            tags = listOf("tag"),
            deletable = deletable,
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

    private fun index() = MetadataDb.load(store, indexId, vmk())
    private fun row(item: VaultItem) = index().items.firstOrNull { it.itemId == item.itemId }
    private fun live() = index().items.filter { it.deletedAt == null }
    private fun trashed() = index().items.filter { it.deletedAt != null }
    private fun blobCount() = store.allBlobIds().size

    private fun readBack(item: VaultItem): ByteArray {
        val out = ByteArray(item.plaintextLength.toInt())
        store.reader(item.blobIds, vmk(), item.plaintextLength).use { r ->
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

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    // --- 1. the outline's Verify ---------------------------------------------

    @Test
    fun aDeletedItemLeavesTheGridEntersTheTrashAndRestoresByteIdentical() {
        val photo = addItem(image(1000, 700), "photo.png")
        val clip = addItem(ByteArray(300 * 1024).also { rng.nextBytes(it) }, "clip.mp4", withThumb = false)
        val file = addItem(ByteArray(64 * 1024).also { rng.nextBytes(it) }, "notes.bin", withThumb = false)

        listOf(photo, clip, file).forEach { item ->
            val before = sha256(readBack(item))
            val blobsBefore = blobCount()

            VaultDeletion.delete(store, indexId, vmk(), item)

            // "absent from the grid, present in Trash"
            assertFalse(
                "${item.filename}: a trashed item is still in the grid's list",
                live().any { it.itemId == item.itemId },
            )
            assertTrue(
                "${item.filename}: a deleted item must be in the trash",
                trashed().any { it.itemId == item.itemId },
            )
            assertNotNull("${item.filename}: no delete stamp was written", row(item)!!.deletedAt)

            // "…blobs survive until purge"
            assertEquals(
                "${item.filename}: a delete must unlink nothing",
                blobsBefore,
                blobCount(),
            )
            item.blobIds.forEach { id ->
                assertTrue(
                    "${item.filename}: blob ${id.hex()} left the pool on a delete",
                    store.fileOf(id).exists(),
                )
            }

            // "…restorable to the grid byte-identical"
            VaultDeletion.restore(store, indexId, vmk(), item)
            val restored = row(item)!!
            assertNull("${item.filename}: the stamp survived the restore", restored.deletedAt)
            assertArrayEquals(
                "${item.filename}: the restored item is not the bytes that went in",
                before,
                sha256(readBack(restored)),
            )
            assertEquals("${item.filename}: the filename changed", item.filename, restored.filename)
            assertEquals(
                "${item.filename}: the folder changed",
                item.folderId?.let { hex(it) },
                restored.folderId?.let { hex(it) },
            )
            assertEquals("${item.filename}: the tags changed", item.tags, restored.tags)
            assertEquals(
                "${item.filename}: the thumbnail reference changed",
                item.thumb?.offset,
                restored.thumb?.offset,
            )
        }

        // "…after purge its blobs are gone."
        val blobsBefore = blobCount()
        val all = index().items
        VaultDeletion.delete(store, indexId, vmk(), all)
        VaultDeletion.purge(store, indexId, vmk(), trashed())

        assertEquals("every row must be gone", 0, index().items.size)
        assertEquals(
            "the pool must lose exactly the purged items' blobs",
            blobsBefore - all.sumOf { it.blobIds.size },
            blobCount(),
        )

        VaultLifecycle.lockNow()
        assertTrue("the vault must reopen", vault.unlockAndOpen(PASS.toByteArray()))
        assertEquals(0, MetadataDb.load(store, VaultSession.metadataBlobId()!!, vmk()).items.size)
    }

    /** `deletedAt` is not `deletable`, and the serialiser puts them side by side. */
    @Test
    fun theSourceDeletableFlagSurvivesATrashAndRestore() {
        val item = addItem(image(400, 400), "verified.png", deletable = true)

        VaultDeletion.delete(store, indexId, vmk(), item)
        assertTrue("a trash must not touch the source flag", row(item)!!.deletable)

        VaultDeletion.restore(store, indexId, vmk(), item)
        assertTrue("a restore must not touch the source flag", row(item)!!.deletable)
    }

    // --- 2. purge on demand ---------------------------------------------------

    @Test
    fun destroyingFromTheTrashRemovesExactlyThoseBlobs() {
        val doomed = addItem(image(600, 400), "doomed.png")
        val keeper = addItem(image(600, 400), "keeper.png")
        val chaffBefore = index().ownedChaff.size

        VaultDeletion.delete(store, indexId, vmk(), listOf(doomed, keeper))
        val blobsBefore = blobCount()

        VaultDeletion.purge(store, indexId, vmk(), listOf(doomed))

        assertNull("the destroyed row survived", row(doomed))
        assertNotNull("the other trashed row went with it", row(keeper))
        doomed.blobIds.forEach {
            assertFalse("a destroyed blob is still in the pool", store.fileOf(it).exists())
        }
        keeper.blobIds.forEach {
            assertTrue("a blob of another trashed item was destroyed", store.fileOf(it).exists())
        }
        assertEquals(blobsBefore - doomed.blobIds.size, blobCount())
        assertEquals("chaff is not a purge's business", chaffBefore, index().ownedChaff.size)
    }

    // --- 3. the window --------------------------------------------------------

    @Test
    fun theWindowPurgesWhatHasExpiredAndNothingElse() {
        val old = addItem(image(500, 500), "old.png", withThumb = false)
        val recent = addItem(image(500, 500), "recent.png", withThumb = false)
        val live = addItem(image(500, 500), "live.png", withThumb = false)

        val now = System.currentTimeMillis()
        // Stamped through the index rather than waited for: the window is thirty
        // days and a test that waits it out is a test nobody runs.
        VaultDeletion.delete(store, indexId, vmk(), old, now - TrashPurge.WINDOW_MS - 1)
        VaultDeletion.delete(store, indexId, vmk(), recent, now)

        val purged = TrashPurge.run(store, indexId, vmk(), now)

        assertEquals("exactly the expired item goes", 1, purged)
        assertNull("the expired item survived its window", row(old))
        old.blobIds.forEach {
            assertFalse("an expired item's blob is still in the pool", store.fileOf(it).exists())
        }
        assertNotNull("an item inside the window was destroyed", row(recent))
        assertNotNull("the window touched a live item", row(live))
        recent.blobIds.forEach { assertTrue(store.fileOf(it).exists()) }
    }

    @Test
    fun theWindowWritesNothingWhenNothingHasExpired() {
        val item = addItem(image(400, 300), "fresh.png", withThumb = false)
        VaultDeletion.delete(store, indexId, vmk(), item)

        store.watching = indexId
        store.writes = 0
        assertEquals(0, TrashPurge.run(store, indexId, vmk()))
        assertEquals("an empty sweep must not rewrite the index", 0, store.writes)
    }

    // --- 4. a set costs a constant, not a row ---------------------------------

    /**
     * Twenty rows cost the same number of index writes as one. The constant is the
     * property; the fact that it is a constant at all is what stops a fifty-item
     * selection from rewriting the index fifty times.
     *
     * **Purge costs two, and it buys something** — `blob-ownership-by-record`. The
     * first commit removes the rows and records their blob IDs as owned chaff in the
     * same transaction, so a kill before the files are unlinked leaves them tracked
     * rather than stranded for a scan to find. The second untracks them once the bytes
     * are actually gone. Two is the number that record costs, and it does not grow with
     * the size of the selection.
     */
    @Test
    fun aSetCostsAConstantNumberOfIndexWrites() {
        val items = (1..20).map { addItem(image(120, 120), "batch-$it.png", withThumb = false) }

        store.watching = indexId
        store.writes = 0
        VaultDeletion.delete(store, indexId, vmk(), items)
        assertEquals("twenty trashed rows must be one rewrite", 1, store.writes)

        store.writes = 0
        VaultDeletion.restore(store, indexId, vmk(), items)
        assertEquals("twenty restored rows must be one rewrite", 1, store.writes)

        VaultDeletion.delete(store, indexId, vmk(), items)
        store.writes = 0
        VaultDeletion.purge(store, indexId, vmk(), items)
        assertEquals(
            "twenty destroyed rows must be the record commit and the untrack commit, " +
                "and nothing per row",
            2, store.writes,
        )
        assertEquals(0, index().items.size)
    }

    // --- 5. the trash owns what it holds --------------------------------------

    /**
     * A trashed item is still a row, so its blobs are **referenced** and never enter
     * owned chaff — `blob-ownership-by-record`.
     *
     * This used to be a test about a sweep not mistaking the trash for orphans. There
     * is no sweep now, and the property is simpler and stronger for it: ownership is
     * what the index records, a trashed row records its blobs exactly as a live row
     * does, and nothing anywhere is deciding what these bytes are by looking at the
     * pool.
     */
    @Test
    fun aTrashedItemsBlobsStayReferencedAndOutOfOwnedChaff() {
        val item = addItem(image(700, 500), "trashed.png")
        VaultDeletion.delete(store, indexId, vmk(), item)

        val trashed = index()
        assertTrue(
            "a trashed item must still be a row that references its blobs",
            trashed.items.any { it.itemId == item.itemId },
        )
        item.blobIds.forEach { id ->
            assertFalse(
                "blob ${id.hex()} of a trashed item was recorded as owned chaff",
                trashed.ownedChaff.any { it.contentEquals(id) },
            )
            assertTrue("a trashed item's blob must still be on disk", store.fileOf(id).exists())
        }

        // And emptying the trash goes down the same path a delete does — the same
        // `purge`, with no second implementation: the IDs enter owned chaff in the
        // commit that removes the row, the files are unlinked, and the second commit
        // untracks them once the bytes are gone.
        VaultDeletion.purge(store, indexId, vmk(), listOf(item))

        val purged = index()
        assertFalse("the row must be gone", purged.items.any { it.itemId == item.itemId })
        item.blobIds.forEach { id ->
            assertFalse("the blob must be unlinked", store.fileOf(id).exists())
            assertFalse(
                "a completed purge must leave nothing of it in owned chaff",
                purged.ownedChaff.any { it.contentEquals(id) },
            )
        }
    }

    // --- 6. the export --------------------------------------------------------

    @Test
    fun anExportCarriesOnlyLiveItemsAndLeavesTheTrashIntact() {
        val out1 = addItem(ByteArray(2048).also { rng.nextBytes(it) }, "out-1.bin", withThumb = false)
        val stays = addItem(ByteArray(2048).also { rng.nextBytes(it) }, "stays.bin", withThumb = false)
        val out2 = addItem(ByteArray(2048).also { rng.nextBytes(it) }, "out-2.bin", withThumb = false)

        VaultDeletion.delete(store, indexId, vmk(), stays)

        val destination = MemoryDestination()
        val result = Unvaulter(store, destination, ExportManifest.openAt(destination))
            .exportAll(indexId = indexId, vmk = vmk())

        assertEquals("the total must count only what will leave", 2, result.total)
        assertEquals(2, result.exported)
        // The manifest is written at the destination beside the files (P1-13), so
        // what this asserts is the *items* that landed, not the directory listing.
        assertEquals(
            setOf("out-1.bin", "out-2.bin"),
            destination.files.keys.filterNot { it.startsWith("unvault-manifest") }.toSet(),
        )
        assertNull("an exported row must be gone", row(out1))
        assertNull("an exported row must be gone", row(out2))

        val left = row(stays)
        assertNotNull("the trashed item left the vault with the export", left)
        assertNotNull("the trashed item lost its stamp", left!!.deletedAt)
        stays.blobIds.forEach {
            assertTrue("a trashed item's blob was exported and unlinked", store.fileOf(it).exists())
        }
    }

    /** An export path that lives in a map — the same shape `MultiSelectTest` uses. */
    private class MemoryDestination : ExportDestination {
        val files = LinkedHashMap<String, ByteArray>()

        override fun create(name: String, mime: String, dateTaken: Long?): ExportEntry {
            files[name] = ByteArray(0)
            return ExportEntry(Uri.parse("memory:///$name"), name)
        }

        override fun openOutput(entry: ExportEntry): OutputStream =
            object : ByteArrayOutputStream() {
                override fun close() {
                    files[entry.name] = toByteArray()
                    super.close()
                }
            }

        override fun openAppend(entry: ExportEntry): OutputStream =
            object : ByteArrayOutputStream() {
                override fun close() {
                    files[entry.name] = (files[entry.name] ?: ByteArray(0)) + toByteArray()
                    super.close()
                }
            }

        override fun openInput(entry: ExportEntry): InputStream =
            ByteArrayInputStream(files[entry.name] ?: ByteArray(0))

        override fun find(name: String): ExportEntry? =
            if (files.containsKey(name)) ExportEntry(Uri.parse("memory:///$name"), name) else null

        override fun finish(entry: ExportEntry) = Unit
        override fun discard(entry: ExportEntry) { files.remove(entry.name) }
        override fun sweep(): Int = 0
    }
}
