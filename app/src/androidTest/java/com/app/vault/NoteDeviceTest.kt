package com.app.vault

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * Encrypted notes on a device — P2-21.
 *
 * `VaultNoteTest` owns the cap, the naming and the identity; this owns the four
 * clauses of `OUTLINE.md`'s Verify, each of which needs a real vault: **a note
 * survives lock/unlock, appears in search, exports as text, and round-trips through
 * a P1-14 archive.**
 *
 * The last of those is a test over code nobody edited — the archive derives its blob
 * set from each item's list and a note contributes an empty one — which is exactly
 * the kind of claim that rots without one.
 */
@RunWith(AndroidJUnit4::class)
class NoteDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var deviceA: File
    private lateinit var deviceB: File
    private lateinit var storeA: BlobStore
    private lateinit var storeB: BlobStore
    private lateinit var vaultA: VaultLifecycle
    private lateinit var vaultB: VaultLifecycle
    private lateinit var archive: File
    private val rng = SecureRandom()

    private companion object {
        const val PASS = "correcthorsebattery"
        const val NEW_PASS = "a-different-passphrase"
        const val TITLE = "Bank details"
        const val BODY = "sort code 60-16-13\nbranch: Mill Road\nmemorable word: pelican"
    }

    private fun pass(s: String = PASS) = s.toByteArray(Charsets.UTF_8)

    @Before fun setUp() {
        VaultLifecycle.lockNow()
        val stamp = System.nanoTime()
        deviceA = File(ctx.filesDir, "note-a-$stamp").apply { mkdirs() }
        deviceB = File(ctx.filesDir, "note-b-$stamp").apply { mkdirs() }
        storeA = BlobStore(File(deviceA, "blobs"), File(deviceA, "incoming"))
        storeB = BlobStore(File(deviceB, "blobs"), File(deviceB, "incoming"))
        vaultA = VaultLifecycle(File(deviceA, "vault.slots"), storeA)
        vaultB = VaultLifecycle(File(deviceB, "vault.slots"), storeB)
        archive = File(ctx.cacheDir, "note-$stamp.vault")
    }

    @After fun tearDown() {
        VaultLifecycle.lockNow()
        deviceA.deleteRecursively()
        deviceB.deleteRecursively()
        archive.delete()
    }

    // --- helpers --------------------------------------------------------------

    private fun vmk() = VaultSession.vmk()!!
    private fun indexId() = VaultSession.metadataBlobId()!!

    private fun addNote(title: String = TITLE, body: String = BODY): VaultItem {
        VaultNote.add(storeA, indexId(), vmk(), title, body)!!
        // `notes-as-documents`: the text is in a blob, so a note is its note ID.
        return items().single { it.noteId != null && VaultNote.titleOf(it) == title }
    }

    /** An ordinary blob-backed item, so every assertion has something to contrast with. */
    private fun addItem(name: String, size: Int): ByteArray {
        val content = ByteArray(size).also { rng.nextBytes(it) }
        val blob = storeA.write(ByteArrayInputStream(content), vmk())
        MetadataDb.commit(storeA, indexId(), vmk()) { db ->
            db.items += VaultItem(
                listOf(blob.blobId), blob.plaintextLength, null, name, null, null, 42, emptyList()
            )
            db
        }
        return content
    }

    private fun items() = MetadataDb.load(storeA, indexId(), vmk()).items

    private fun blobCount() = File(deviceA, "blobs").listFiles()?.size ?: 0

    private class FakeDestination : ExportDestination {
        val files = LinkedHashMap<String, ByteArray>()
        val mimes = LinkedHashMap<String, String>()
        val finished = mutableSetOf<String>()

        override fun create(name: String, mime: String, dateTaken: Long?): ExportEntry {
            files[name] = ByteArray(0)
            mimes[name] = mime
            return ExportEntry(Uri.parse("fake:///$name"), name)
        }

        override fun openOutput(entry: ExportEntry): OutputStream =
            object : ByteArrayOutputStream() {
                override fun close() { files[entry.name] = toByteArray(); super.close() }
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
            if (files.containsKey(name)) ExportEntry(Uri.parse("fake:///$name"), name) else null

        override fun finish(entry: ExportEntry) { finished += entry.name }
        override fun discard(entry: ExportEntry) { files.remove(entry.name) }
        override fun sweep(): Int = 0
    }

    // --- Verify 1: a note survives lock/unlock --------------------------------

    @Test fun aNoteSurvivesLockAndUnlock() {
        vaultA.create(pass())
        val before = addNote()

        VaultLifecycle.lockNow()
        assertFalse(VaultSession.isUnlocked)
        assertTrue(VaultLifecycle(File(deviceA, "vault.slots"), storeA).unlockAndOpen(pass()))

        val after = items().single()
        assertEquals(
            "the text must come back byte-identical",
            BODY,
            VaultNote.read(storeA, after, vmk()),
        )
        assertEquals("Bank details.md", after.filename)
        assertEquals("its identity must survive the round trip", before.itemId, after.itemId)
        assertEquals(VaultKind.NOTE, after.kind)
        // `notes-as-documents`: the text is in a blob now, and the row keeps the
        // opening so the grid can draw a tile without decrypting it.
        assertTrue("a note's text must be in a blob", after.blobIds.isNotEmpty())
        assertNull("the text must not stay in the row", after.body)
        assertEquals(VaultNote.previewOf(BODY), after.preview)
    }

    // --- the storage decision, asserted rather than assumed -------------------

    /**
     * **This assertion was inverted by `notes-as-documents`**, and the inversion is
     * the change. A note used to claim no blob, which is why the body was capped at
     * 64 KiB and why an image could never go in one: the text was in the index, and
     * the index is one blob rewritten whole on every commit. It has its own blob now,
     * written through the path a photograph uses.
     */
    @Test fun creatingANoteAddsItsOwnContentBlob() {
        vaultA.create(pass())
        val before = blobCount()

        addNote()

        // The index blob is rewritten under the same ID (§7), so the only new file is
        // the note's own text.
        assertEquals("a note's text must be a blob of its own", before + 1, blobCount())
    }

    /** Inverted with the one above, and for the same reason. */
    @Test fun destroyingANoteDeletesItsBlob() {
        vaultA.create(pass())
        val note = addNote()
        addItem("photo.jpg", 5_000)
        val withPhoto = blobCount()

        VaultDeletion.purge(storeA, indexId(), vmk(), listOf(note))

        assertEquals("the note's blob must go with it", withPhoto - 1, blobCount())
        assertEquals("and must leave the photograph alone", 1, items().size)
    }

    @Test fun aNoteIsTrashedAndRestoredLikeAnyOtherRow() {
        vaultA.create(pass())
        val note = addNote()

        MetadataDb.commit(storeA, indexId(), vmk()) { db ->
            val i = db.items.indexOfFirst { it.itemId == note.itemId }
            db.items[i] = db.items[i].withDeletedAt(1_700_000_000_000L)
            db
        }
        assertNotNull("the trash stamp must land", items().single().deletedAt)
        assertEquals(
            "and must not disturb the text",
            BODY,
            VaultNote.read(storeA, items().single(), vmk()),
        )

        MetadataDb.commit(storeA, indexId(), vmk()) { db ->
            val i = db.items.indexOfFirst { it.itemId == note.itemId }
            db.items[i] = db.items[i].withDeletedAt(null)
            db
        }
        assertNull(items().single().deletedAt)
        assertEquals(note.itemId, items().single().itemId)
    }

    // --- Verify 2: a note appears in search -----------------------------------

    @Test fun aNoteIsFoundByAWordOnlyInItsBody() {
        vaultA.create(pass())
        addNote()
        addItem("photo.jpg", 5_000)

        // "pelican" is in no filename, no album and no tag anywhere in this vault.
        val hits = VaultSearch.filter(items(), "pelican")

        assertEquals(1, hits.size)
        assertEquals("Bank details.md", hits.single().filename)
        assertEquals("a term in nothing must still match nothing", 0, VaultSearch.filter(items(), "flamingo").size)
    }

    // --- Verify 3: a note exports as text -------------------------------------

    @Test fun aNoteExportsAsAMarkdownFile() {
        vaultA.create(pass())
        addNote()

        val dest = FakeDestination()
        val result = Unvaulter(storeA, dest, ExportManifest.openAt(dest)).exportAll(indexId(), vmk())

        assertEquals("the note must export", 1, result.exported)
        assertEquals(
            "the file's bytes must be the body",
            BODY,
            String(dest.files["Bank details.md"]!!, Charsets.UTF_8),
        )
        assertEquals(
            "a note must not land as an opaque byte stream",
            "text/markdown",
            dest.mimes["Bank details.md"],
        )
        assertTrue("the export must be finished, not left staged", "Bank details.md" in dest.finished)

        val manifest = ExportManifest.openAt(dest).lines()
        assertTrue(
            "the manifest must record it as OK: $manifest",
            manifest.any { it.startsWith("OK\tBank details.md\t") },
        )
    }

    @Test fun aNoteAndAPhotographExportSideBySide() {
        vaultA.create(pass())
        addNote()
        val photo = addItem("photo.jpg", 5_000)

        val dest = FakeDestination()
        val result = Unvaulter(storeA, dest, ExportManifest.openAt(dest)).exportAll(indexId(), vmk())

        assertEquals(2, result.exported)
        assertEquals(BODY, String(dest.files["Bank details.md"]!!, Charsets.UTF_8))
        assertTrue("the blob-backed item is unaffected", photo.contentEquals(dest.files["photo.jpg"]))
    }

    // --- Verify 4: a note round-trips through an archive ----------------------

    /** `recovery-own-slot-only`. An import joins device B's open list, made here first. */
    private fun importOnB() {
        assertTrue(vaultB.create(pass(NEW_PASS)).opened)
        VaultKeyringSession.leaveVault()
        assertTrue(
            "the note's blob must travel with every other",
            archive.inputStream().use { vaultB.importArchive(it, pass()) }.opened,
        )
    }

    @Test fun aNoteRestoresOnADeviceThatHasNeverHeldTheVault() {
        vaultA.create(pass())
        val note = addNote()
        val photo = addItem("photo.jpg", 20_000)

        val db = MetadataDb.load(storeA, indexId(), vmk())
        val blobs = archive.outputStream().use { VaultArchive.write(it, vmk(), db, storeA, pass()) }
        VaultLifecycle.lockNow()

        importOnB()
        // `vault-keyring`. The import lands the vault on device B's list; opening it
        // is the second act, exactly as it is after an unlock.
        assertTrue(vaultB.openVaultAt(VaultKeyringSession.vaults().last().position))
        val restored = MetadataDb.load(storeB, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!).items
        val back = restored.single { it.noteId != null }

        assertEquals(2, restored.size)
        assertEquals(
            "the text must survive the archive",
            BODY,
            VaultNote.read(storeB, back, VaultSession.vmk()!!),
        )
        assertEquals("Bank details.md", back.filename)
        assertEquals("the identity must survive the move", note.itemId, back.itemId)
        assertEquals(VaultKind.NOTE, back.kind)
        // `notes-as-documents`: the note has a blob now, and the archive has to carry
        // it like any other. This assertion used to say the opposite.
        assertTrue("the note's blob must have travelled", back.blobIds.isNotEmpty())

        val other = restored.single { it.noteId == null }
        assertEquals(1, other.blobIds.size)
        assertTrue("the archive must still carry real blobs", blobs > 0)
        assertEquals(photo.size.toLong(), other.plaintextLength)
    }

    @Test fun aNoteIsSearchableAndExportableAfterAnArchiveRoundTrip() {
        vaultA.create(pass())
        addNote()

        val db = MetadataDb.load(storeA, indexId(), vmk())
        archive.outputStream().use { VaultArchive.write(it, vmk(), db, storeA, pass()) }
        VaultLifecycle.lockNow()
        importOnB()
        assertTrue(vaultB.openVaultAt(VaultKeyringSession.vaults().last().position))

        val restored = MetadataDb.load(storeB, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!)
        assertEquals(1, VaultSearch.filter(restored.items, "pelican").size)

        val dest = FakeDestination()
        val result = Unvaulter(storeB, dest, ExportManifest.openAt(dest))
            .exportAll(VaultSession.metadataBlobId()!!, VaultSession.vmk()!!)

        assertEquals(1, result.exported)
        assertEquals(BODY, String(dest.files["Bank details.md"]!!, Charsets.UTF_8))
    }

    // --- `remove-note-attachments`: a note leaves as one file ------------------

    /**
     * ***A note exports as one Markdown file, and its text is not rewritten.***
     *
     * This asserted the opposite: a note took its attachments out beside it as
     * `Trip-1.jpg`, and the `vault:` references inside the Markdown were rewritten to
     * name those files. A note was the only item kind in the app that produced several
     * export files, and it did it for attachments. One vault item is one exported file
     * now, for every kind without exception.
     */
    @Test fun aNoteExportsAsOneFileWithItsTextUnrewritten() {
        vaultA.create(pass())
        val body = "the beach\n![](vault:9f3c1b)"
        addNote(title = "Trip", body = body)

        val dest = FakeDestination()
        val result = Unvaulter(storeA, dest, ExportManifest.openAt(dest)).exportAll(indexId(), vmk())

        assertEquals("one note, one file", 1, result.exported)
        assertTrue("the note must land", dest.files.containsKey("Trip.md"))
        // The manifest is written into the same destination and is not an exported item,
        // so it is excluded rather than counted — the original test never counted at all,
        // which is why this needed saying.
        assertEquals(
            "a note may write exactly one file: ${dest.files.keys}",
            setOf("Trip.md", ExportManifest.NAME),
            dest.files.keys,
        )

        // **Byte-identical, references and all.** The reference is inert text now — the
        // app does not rewrite a person's document on the way out any more than it does
        // in place.
        assertEquals(body, String(dest.files["Trip.md"]!!, Charsets.UTF_8))

        val manifest = ExportManifest.openAt(dest).lines()
        assertTrue(
            "the note gets its own verified line: $manifest",
            manifest.any { it.startsWith("OK\tTrip.md\t") },
        )
        assertEquals("nothing may be left in the vault", 0, items().size)
    }

    /**
     * ***An item that used to be an attachment exports on its own, under its own name.***
     *
     * The inverse of the rule this replaces. A row still recording an owning note is an
     * ordinary item: it is exported once, as itself, not folded into a note's stem.
     */
    @Test fun aReleasedItemExportsUnderItsOwnName() {
        vaultA.create(pass())
        val note = addNote(title = "Trip", body = "the beach")
        val picture = ByteArray(64) { 3 }
        val blob = storeA.writeItem(ByteArrayInputStream(picture), vmk())
        // Written with an owner, as a build that still had attachments would have.
        MetadataDb.commit(storeA, indexId(), vmk()) { db ->
            db.items += VaultItem(
                blob.blobIds, blob.plaintextLength, null, "beach.jpg", null, null, 42, emptyList(),
                ownerNoteId = note.noteId,
            )
            db
        }

        val dest = FakeDestination()
        val result = Unvaulter(storeA, dest, ExportManifest.openAt(dest)).exportAll(indexId(), vmk())

        assertEquals("both items count", 2, result.exported)
        assertTrue("it must land under its own name", dest.files.containsKey("beach.jpg"))
        assertTrue("and never under the note's", !dest.files.containsKey("Trip-1.jpg"))
        assertTrue("its bytes must survive", picture.contentEquals(dest.files["beach.jpg"]))
        assertEquals("nothing may be left in the vault", 0, items().size)
    }
}
