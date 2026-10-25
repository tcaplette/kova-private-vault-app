package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * P2-14 — a new name for one item.
 *
 * `OUTLINE.md`'s Verify line first: rename, export, and confirm the exported file
 * carries the new name with byte-identical contents. That one runs against the
 * real `MediaStoreDestination`, because "the exported file" is a row in the
 * device's gallery and the extension argument this change rests on is precisely
 * about which collection it lands in.
 *
 * Then the claims the design makes: one index write, nothing but the name moved,
 * the name survives everything the vault survives, and two items may share one.
 *
 * **Test-only gallery rows.** Every file this exports is removed in `tearDown`,
 * along with the manifest — nothing it writes outlives it.
 */
@RunWith(AndroidJUnit4::class)
class RenameTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: CountingStore
    private lateinit var vault: VaultLifecycle
    private lateinit var indexId: ByteArray
    private lateinit var destination: MediaStoreDestination
    private val exported = mutableListOf<String>()
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultRenameTest"
        const val PASS = "correcthorsebattery"
    }

    private fun pass(s: String = PASS) = s.toByteArray(Charsets.UTF_8)
    private fun vmk() = VaultSession.vmk()!!

    /**
     * A store that counts writes to one blob — the index. `ItemTrashTest`'s
     * subclass, for its reason: "one commit" is a claim about how many times the
     * index is rewritten, and timing would measure the emulator.
     */
    private class CountingStore(dir: File, incoming: File) : BlobStore(dir, incoming) {
        var watching: ByteArray? = null
        var writes = 0

        override fun writeTo(blobId: ByteArray, input: InputStream, vmk: ByteArray): WrittenBlob {
            if (watching?.contentEquals(blobId) == true) writes++
            return super.writeTo(blobId, input, vmk)
        }
    }

    @Before fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "rename-${System.nanoTime()}").apply { mkdirs() }
        store = CountingStore(File(root, "blobs"), File(root, "incoming"))
        vault = VaultLifecycle(File(root, "vault.slots"), store)
        assertTrue("fixture vault must open", vault.create(pass()).opened)
        indexId = VaultSession.metadataBlobId()!!.copyOf()
        destination = MediaStoreDestination(ctx)
    }

    @After fun tearDown() {
        exported.forEach { name -> destination.find(name)?.let { destination.discard(it) } }
        destination.find(ExportManifest.NAME)?.let { destination.discard(it) }
        destination.sweep()
        VaultLifecycle.lockNow()
        root.deleteRecursively()
    }

    // --- fixtures -------------------------------------------------------------

    /** Adds an item with known content and returns it, with the plaintext beside it. */
    private fun addItem(
        name: String,
        size: Int = 4096,
        album: String? = "Album",
    ): Pair<VaultItem, ByteArray> {
        val content = ByteArray(size).also { rng.nextBytes(it) }
        val blob = store.write(ByteArrayInputStream(content), vmk())
        val item = VaultItem(
            blobIds = listOf(blob.blobId),
            plaintextLength = blob.plaintextLength,
            thumb = ThumbRef(packBlobId = ByteArray(16) { 7 }, offset = 128, length = 64),
            filename = name,
            album = album,
            createdAt = 1_400_000_000_000L,
            importedAt = 42L,
            tags = listOf("tag"),
            deletable = true,
        )
        MetadataDb.commit(store, indexId, vmk()) { db -> db.items += item; db }
        return item to content
    }

    private fun index() = MetadataDb.load(store, indexId, vmk())
    private fun row(item: VaultItem) = index().items.first { it.itemId == item.itemId }
    private fun blobCount() = store.allBlobIds().size
    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun readBack(store: BlobStore, item: VaultItem, vmk: ByteArray): ByteArray {
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

    /** Renames through the whole path the screen takes: split, normalise, commit. */
    private fun rename(item: VaultItem, stem: String): VaultItem {
        val (_, extension) = VaultRename.split(row(item).filename)
        val name = VaultRename.normalise(stem, extension)
        assertNotNull("the fixture's own name was refused", name)
        VaultRename.rename(store, indexId, vmk(), item, name!!)
        return row(item)
    }

    private fun exportEverything() {
        val manifest = ExportManifest.openAt(destination)
        val result = Unvaulter(store, destination, manifest, ctx).exportAll(indexId, vmk())
        assertEquals("an item failed to export", 0, result.failed)
    }

    // --- 1. the outline's Verify ----------------------------------------------

    @Test fun aRenamedItemExportsUnderItsNewNameByteIdentical() {
        val (item, content) = addItem("IMG_4471.jpg")
        exported += listOf("IMG_4471.jpg", "Passport.jpg")

        val renamed = rename(item, "Passport")
        assertEquals("the stem was not replaced", "Passport.jpg", renamed.filename)

        exportEverything()

        val entry = destination.find("Passport.jpg")
        assertNotNull("the export did not write the new name", entry)
        assertNull("the old name is still at the destination", destination.find("IMG_4471.jpg"))

        val landed = destination.openInput(entry!!).use { it.readBytes() }
        assertArrayEquals("the exported file is not the item's plaintext", content, landed)
        Log.i(TAG, "exported under a new name, ${landed.size} B byte-identical")
    }

    /** The other half of the extension decision: the file still lands as an image. */
    @Test fun theExtensionDecidesTheMimeAndTheRenameDoesNotChangeIt() {
        val (item, _) = addItem("clip.mp4")
        val before = ExportDestination.mimeOf(row(item).filename)

        val renamed = rename(item, "Birthday")

        assertEquals("the extension moved", "Birthday.mp4", renamed.filename)
        assertEquals("the MIME changed under a rename", before, ExportDestination.mimeOf(renamed.filename))
        assertEquals("the fixture is not exercising the video path", "video/mp4", before)
    }

    // --- 2. one write, and nothing else moved ---------------------------------

    @Test fun aRenameIsOneIndexWriteAndTouchesNothingElse() {
        val (item, content) = addItem("notes.bin", size = 64 * 1024)
        val blobsBefore = blobCount()
        val before = row(item)

        store.watching = indexId
        store.writes = 0
        rename(item, "Tax return 2019")
        store.watching = null

        assertEquals("a rename must be one index write", 1, store.writes)

        val after = row(item)
        assertEquals("the name did not change", "Tax return 2019.bin", after.filename)
        assertEquals("the item's identity changed", before.itemId, after.itemId)
        assertEquals("a blob was written or unlinked", blobsBefore, blobCount())
        assertEquals("the plaintext length changed", before.plaintextLength, after.plaintextLength)
        assertEquals("the album changed", before.album, after.album)
        assertEquals("the tags changed", before.tags, after.tags)
        assertEquals("createdAt changed", before.createdAt, after.createdAt)
        assertEquals("importedAt changed", before.importedAt, after.importedAt)
        assertEquals("deletable changed", before.deletable, after.deletable)
        assertEquals("the trash state changed", before.deletedAt, after.deletedAt)
        assertArrayEquals(
            "the thumbnail's pack changed",
            before.thumb!!.packBlobId,
            after.thumb!!.packBlobId,
        )
        assertEquals("the thumbnail's offset changed", before.thumb!!.offset, after.thumb!!.offset)
        assertEquals("the thumbnail's length changed", before.thumb!!.length, after.thumb!!.length)
        assertArrayEquals(
            "the stored bytes changed",
            sha256(content),
            sha256(readBack(store, after, vmk())),
        )
        Log.i(TAG, "renamed 1 item(s) at ${store.writes} index write")
    }

    @Test fun renamingOneItemLeavesTheOthersAlone() {
        val (a, _) = addItem("one.jpg")
        val (b, _) = addItem("two.jpg")

        rename(a, "first")

        assertEquals("the wrong row was written", "first.jpg", row(a).filename)
        assertEquals("a bystander was renamed", "two.jpg", row(b).filename)
        assertEquals("a row appeared or vanished", 2, index().items.size)
    }

    // --- 3. it survives what the vault survives -------------------------------

    @Test fun theNewNameSurvivesLockAndUnlock() {
        val (item, _) = addItem("photo.png")
        rename(item, "Réunion 🏝")

        VaultLifecycle.lockNow()
        assertTrue("the fixture vault must reopen", vault.unlockAndOpen(pass()))

        assertEquals(
            "the name did not survive the lock",
            "Réunion 🏝.png",
            MetadataDb.load(store, indexId, vmk()).items.first { it.itemId == item.itemId }.filename,
        )
    }

    @Test fun theNewNameTravelsInAnArchive() {
        val (item, content) = addItem("IMG_0001.jpg")
        rename(item, "Passport")

        val deviceB = File(ctx.filesDir, "rename-b-${System.nanoTime()}").apply { mkdirs() }
        try {
            val storeB = BlobStore(File(deviceB, "blobs"), File(deviceB, "incoming"))
            val vaultB = VaultLifecycle(File(deviceB, "vault.slots"), storeB)
            val archive = File(ctx.cacheDir, "rename-${System.nanoTime()}.vault")

            try {
                archive.outputStream().use { out ->
                    VaultArchive.write(out, vmk(), index(), store, pass())
                }
                assertTrue(
                    "the archive must import on device B",
                    archive.inputStream().use {
                        vaultB.importArchive(it, pass())
                    }.opened,
                )

                val onB = MetadataDb.load(storeB, VaultSession.metadataBlobId()!!, vmk())
                    .items.single()
                assertEquals("the name did not travel", "Passport.jpg", onB.filename)
                assertArrayEquals(
                    "the contents did not travel byte-identical",
                    sha256(content),
                    sha256(readBack(storeB, onB, vmk())),
                )
            } finally {
                archive.delete()
            }
        } finally {
            VaultLifecycle.lockNow()
            deviceB.deleteRecursively()
        }
    }

    // --- 4. duplicates are the vault's business, not the rename's -------------

    @Test fun twoItemsMayShareANameAndBothExportIntact() {
        val (a, contentA) = addItem("one.jpg")
        val (b, contentB) = addItem("two.jpg")
        exported += listOf("Passport.jpg", ExportDestination.suffixed("Passport.jpg", 2))

        rename(a, "Passport")
        rename(b, "Passport")

        assertEquals("the first was not renamed", "Passport.jpg", row(a).filename)
        assertEquals("a duplicate name was refused", "Passport.jpg", row(b).filename)

        exportEverything()

        val first = destination.find("Passport.jpg")
        val second = destination.find(ExportDestination.suffixed("Passport.jpg", 2))
        assertNotNull("the first file is missing", first)
        assertNotNull("the collision was not suffixed at the destination", second)

        val landed = listOf(first!!, second!!)
            .map { sha256(destination.openInput(it).use { s -> s.readBytes() }).toList() }
            .toSet()
        assertEquals(
            "the two exported files are not the two items",
            setOf(sha256(contentA).toList(), sha256(contentB).toList()),
            landed,
        )
    }
}
