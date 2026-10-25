package com.app.vault

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.SecureRandom

/**
 * P2-13 — one level of albums, against the outline's Verify.
 *
 * Import into a named album, move an item between two albums, and confirm the
 * album survives a lock and unlock. The archive half of that Verify lives in
 * `VaultArchiveTest`, which already has the two-device harness a round-trip needs.
 *
 * The rest is what a derived list has to get right: counts that describe the live
 * vault, an album that ceases to exist when its last item leaves, and a move that
 * costs one index write however many items move.
 */
@RunWith(AndroidJUnit4::class)
class FolderTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = ctx.contentResolver
    private lateinit var root: File
    private lateinit var store: CountingStore
    private lateinit var vault: VaultLifecycle
    private lateinit var indexId: ByteArray
    private val created = mutableListOf<Uri>()
    private val rng = SecureRandom()

    private companion object { const val PASS = "folder-test-passphrase" }

    private fun vmk() = VaultSession.vmk()!!

    /** Counts writes to one blob — the index. `MultiSelectTest`'s subclass. */
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
        root = File(ctx.filesDir, "folders-${System.nanoTime()}").apply { mkdirs() }
        store = CountingStore(File(root, "blobs"), File(root, "incoming"))
        vault = VaultLifecycle(File(root, "vault.slots"), store)
        assertTrue("fixture vault must open", vault.create(PASS.toByteArray()).opened)
        indexId = VaultSession.metadataBlobId()!!.copyOf()
    }

    @After
    fun tearDown() {
        created.forEach { runCatching { resolver.delete(it, null, null) } }
        created.clear()
        VaultLifecycle.lockNow()
        root.deleteRecursively()
    }

    // --- fixtures -------------------------------------------------------------

    /** A real `MediaStore` source, so the import path under test is the real one. */
    private fun source(name: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/VaultFolderTest")
        }
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        )!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        created += uri
        return uri
    }

    /** Adds a row directly, for the cases that are about the index rather than import. */
    private fun addItem(
        name: String,
        folder: VaultFolder?,
        trashed: Boolean = false,
    ): VaultItem {
        val bytes = ByteArray(4096).also { rng.nextBytes(it) }
        val written = store.writeItem(ByteArrayInputStream(bytes), vmk())
        val item = VaultItem(
            blobIds = written.blobIds,
            plaintextLength = written.plaintextLength,
            thumb = null,
            filename = name,
            album = null,
            folderId = folder?.folderId,
            createdAt = null,
            importedAt = System.currentTimeMillis(),
            tags = emptyList(),
            deletedAt = if (trashed) System.currentTimeMillis() else null,
        )
        MetadataDb.commit(store, indexId, vmk()) { db -> db.items += item }
        return item
    }

    private fun index() = MetadataDb.load(store, indexId, vmk())
    private fun row(item: VaultItem) = index().items.first { it.itemId == item.itemId }
    /** The top level's folders, as the grid derives them. */
    private fun folders() = index().let { db ->
        VaultFolders.children(db.folders, db.items, null)
    }

    /** Makes a folder through the real write path and returns the record. */
    private fun folder(name: String, parent: VaultFolder? = null): VaultFolder {
        val result = VaultFolders.create(store, indexId, vmk(), parent?.folderId, name)
        assertNull("the fixture folder must not be refused", result.refusal)
        return index().folders.first {
            it.name == name && it.parentId?.let { p -> hex(p) } == parent?.id
        }
    }

    private fun folderNamed(name: String) = index().folders.first { it.name == name }

    // --- 1. the outline's Verify ---------------------------------------------

    @Test
    fun anImportLandsInItsFolderAndSurvivesLockAndUnlock() {
        val uri = source("into-folder-${System.nanoTime()}.png", ByteArray(8192).also { rng.nextBytes(it) })

        val outcome = ImportPipeline(resolver, store).run(
            uri = uri,
            blobIds = mutableListOf(),
            vmk = vmk(),
            folderId = folder("Trip").folderId,
        )
        assertTrue("the fixture import must succeed", outcome is ImportOutcome.Done)
        val imported = (outcome as ImportOutcome.Done).item
        assertEquals(
            "the import must carry the folder it was started in",
            folderNamed("Trip").id,
            imported.folderId?.let { hex(it) },
        )

        MetadataDb.commit(store, indexId, vmk()) { db -> db.items += imported }

        VaultLifecycle.lockNow()
        assertTrue("the vault must reopen", vault.unlockAndOpen(PASS.toByteArray()))
        val reopened = MetadataDb.load(store, VaultSession.metadataBlobId()!!, vmk())
        assertEquals(
            "the folder must survive lock and unlock",
            reopened.folders.first { it.name == "Trip" }.id,
            reopened.items.single { it.itemId == imported.itemId }.folderId?.let { hex(it) },
        )
    }

    @Test
    fun anImportWithNoFolderCarriesNone() {
        val uri = source("no-folder-${System.nanoTime()}.png", ByteArray(4096).also { rng.nextBytes(it) })

        val outcome = ImportPipeline(resolver, store).run(
            uri = uri,
            blobIds = mutableListOf(),
            vmk = vmk(),
        )
        assertTrue(outcome is ImportOutcome.Done)
        assertNull(
            "an import started at the home screen must record no folder",
            (outcome as ImportOutcome.Done).item.folderId,
        )
    }

    @Test
    fun anItemMovesBetweenTwoFolders() {
        val trip = folder("Trip")
        val archive = folder("Archive")
        val moving = addItem("moving.png", trip)
        val staying = addItem("staying.png", trip)
        addItem("elsewhere.png", archive)

        VaultFolders.move(store, indexId, vmk(), listOf(moving), archive.folderId)

        assertEquals("the item must be in the destination", archive.id, folderOf(moving))
        assertEquals("the item left behind must be untouched", trip.id, folderOf(staying))
        assertEquals(
            "the counts must follow the items",
            listOf("Archive" to 2, "Trip" to 1),
            folders().map { it.name to it.count },
        )

        VaultLifecycle.lockNow()
        assertTrue(vault.unlockAndOpen(PASS.toByteArray()))
        assertEquals(archive.id, folderOf(moving))
    }

    /** The folder an item is in, in hex, or null. */
    private fun folderOf(item: VaultItem) = row(item).folderId?.let { hex(it) }

    // --- 2. one commit for the set -------------------------------------------

    @Test
    fun movingTwentyItemsIsOneIndexWrite() {
        val items = (1..20).map { addItem("batch-$it.png", null) }
        val batch = folder("Batch")

        store.watching = indexId
        store.writes = 0
        VaultFolders.move(store, indexId, vmk(), items, batch.folderId)

        assertEquals("twenty moved rows must be one rewrite of the index", 1, store.writes)
        assertEquals(20, folders().single { it.name == "Batch" }.count)
    }

    // --- 3. what a derived list means ----------------------------------------

    @Test
    fun removingFromAFolderClearsTheReferenceAndKeepsTheFolder() {
        val solo = folder("Solo")
        val only = addItem("only.png", solo)
        assertEquals(1, folders().single().count)

        VaultFolders.move(store, indexId, vmk(), listOf(only), null)

        assertNull("remove from a folder must clear the reference", folderOf(only))
        // ***The inverse of P2-13's assertion, and the whole change.*** An album with
        // nothing in it stopped existing; a folder does not.
        assertEquals("the folder stays, empty", listOf("Solo" to 0), folders().map { it.name to it.count })
    }

    @Test
    fun namesDifferingOnlyInCaseAreTwoFolders() {
        addItem("upper.png", folder("Trip"))
        addItem("lower.png", folder("trip"))

        assertEquals(
            listOf("Trip" to 1, "trip" to 1),
            folders().map { it.name to it.count }.sortedBy { it.first },
        )
    }

    // --- 4. the trash keeps its album ----------------------------------------

    @Test
    fun aTrashedItemKeepsItsFolderButIsNotCountedByIt() {
        val trip = folder("Trip")
        val kept = addItem("kept.png", trip)
        val doomed = addItem("doomed.png", trip)

        VaultDeletion.delete(store, indexId, vmk(), doomed)

        assertEquals("the count must describe the live vault", 1, folders().single().count)
        assertEquals("a trashed item keeps its folder", trip.id, folderOf(doomed))

        VaultDeletion.restore(store, indexId, vmk(), doomed)
        assertEquals("restoring must put it back in its folder", 2, folders().single().count)
        assertEquals(trip.id, folderOf(kept))
    }

    @Test
    fun aFolderWhoseItemsAreAllTrashedIsStillListedAsEmpty() {
        val gone = folder("Gone")
        val one = addItem("one.png", gone)
        val two = addItem("two.png", gone)

        VaultDeletion.delete(store, indexId, vmk(), listOf(one, two))
        assertEquals(
            "a folder whose items are all trashed is listed, showing none",
            listOf("Gone" to 0),
            folders().map { it.name to it.count },
        )

        VaultDeletion.restore(store, indexId, vmk(), one)
        assertEquals(listOf("Gone" to 1), folders().map { it.name to it.count })
    }

    @Test
    fun aFolderSurvivesADeleteOfAnItemInAnother() {
        val item = addItem("trip.png", folder("Trip"))
        addItem("archive.png", folder("Archive"))

        VaultDeletion.delete(store, indexId, vmk(), item)

        assertEquals(
            listOf("Archive" to 1, "Trip" to 0),
            folders().map { it.name to it.count },
        )
        assertNotNull("the other folder's item is untouched", row(item))
    }

    // --- 5. what folders added ------------------------------------------------

    /** Created, named, and there with nothing in it. The reason this change exists. */
    @Test
    fun aFolderIsCreatedEmptyAndSurvivesLockAndUnlock() {
        folder("Empty")
        assertEquals(listOf("Empty" to 0), folders().map { it.name to it.count })

        VaultLifecycle.lockNow()
        assertTrue(vault.unlockAndOpen(PASS.toByteArray()))
        assertEquals(listOf("Empty" to 0), folders().map { it.name to it.count })
    }

    /** A sibling name is refused; the same name under another parent is not. */
    @Test
    fun aSiblingNameIsRefusedAndTheSameNameElsewhereIsNot() {
        val trips = folder("Trips")
        folder("2024", trips)

        val clash = VaultFolders.create(store, indexId, vmk(), trips.folderId, "2024")
        assertEquals(VaultFolders.Refusal.Duplicate, clash.refusal)

        val elsewhere = VaultFolders.create(store, indexId, vmk(), null, "2024")
        assertNull("the same name under a different parent is a different folder", elsewhere.refusal)
    }

    /** A rename writes the folder's record and nothing else. */
    @Test
    fun aRenameWritesNoItem() {
        val trip = folder("Trip")
        val item = addItem("in.png", trip)

        VaultFolders.rename(store, indexId, vmk(), trip.folderId, "Holiday")

        assertEquals("Holiday", folders().single().name)
        assertEquals("the item was not rewritten", trip.id, folderOf(item))
    }

    /** A move writes one field, and everything inside travels with it. */
    @Test
    fun aFolderMovesWithEverythingInside() {
        val trips = folder("Trips")
        val y2024 = folder("2024")
        val item = addItem("in.png", y2024)

        VaultFolders.reparent(store, indexId, vmk(), y2024.folderId, trips.folderId)

        assertEquals("only the parent moved", listOf("Trips"), folders().map { it.name })
        val inside = index().let { db -> VaultFolders.children(db.folders, db.items, trips.folderId) }
        assertEquals(listOf("2024" to 1), inside.map { it.name to it.count })
        assertEquals("the item never moved", y2024.id, folderOf(item))
    }

    /** A folder cannot go inside itself, and nothing is written when it tries. */
    @Test
    fun aFolderCannotBeMovedIntoItsOwnSubtree() {
        val trips = folder("Trips")
        val inner = folder("2024", trips)

        assertEquals(
            VaultFolders.Refusal.Cycle,
            VaultFolders.reparent(store, indexId, vmk(), trips.folderId, inner.folderId).refusal,
        )
        assertEquals("nothing moved", listOf("Trips"), folders().map { it.name })
    }

    /** Deleting a folder moves its contents up. It never deletes an item. */
    @Test
    fun deletingAFolderReparentsAndTrashesNothing() {
        val trips = folder("Trips")
        val inner = folder("2024", trips)
        val item = addItem("in.png", inner)

        VaultFolders.delete(store, indexId, vmk(), inner.folderId)

        assertEquals("the folder is gone", listOf("Trips"), index().folders.map { it.name })
        assertEquals("its item is where it was", trips.id, folderOf(item))
        assertNull("nothing was trashed", row(item).deletedAt)
    }

    /** Deleting a top-level folder lands its contents on the home screen. */
    @Test
    fun deletingATopLevelFolderLandsItsContentsAtTheTop() {
        val solo = folder("Solo")
        val item = addItem("in.png", solo)

        VaultFolders.delete(store, indexId, vmk(), solo.folderId)

        assertNull("the item is at the home screen", folderOf(item))
        assertNull("nothing was trashed", row(item).deletedAt)
    }

    /** Colour and cover are stored, and they survive a lock. */
    @Test
    fun aColourAndACoverSurviveLockAndUnlock() {
        val trip = folder("Trip")
        val item = addItem("cover.png", trip)

        VaultFolders.setColour(store, indexId, vmk(), trip.folderId, FolderColour.Teal)
        VaultFolders.setCover(store, indexId, vmk(), trip.folderId, item.blobIds.first())

        VaultLifecycle.lockNow()
        assertTrue(vault.unlockAndOpen(PASS.toByteArray()))

        val back = folderNamed("Trip")
        assertEquals(FolderColour.Teal, back.colour)
        assertEquals(item.itemId, back.coverItemId?.let { hex(it) })
    }
}
