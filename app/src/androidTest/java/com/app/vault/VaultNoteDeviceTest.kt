package com.app.vault

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
import java.io.File

/**
 * A note's text in its own blob — `notes-as-documents`.
 *
 * **`VaultNoteTest` keeps the rules that need no device** — the cap counted in bytes,
 * the naming, the preview — because `VaultNote.row` is still pure. What is here is the
 * half that genuinely is a device test: sealing text into the pooled store and reading
 * it back through the envelope.
 *
 * The text used to live in the index row, which meant every note in the vault was
 * re-encrypted whenever anything changed and an image could never go in one. These are
 * the assertions that say it does not any more.
 */
@RunWith(AndroidJUnit4::class)
class VaultNoteDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray
    private lateinit var indexId: ByteArray

    @Before fun setUp() {
        dir = File(ctx.filesDir, "note-blob-test").apply { mkdirs() }
        store = BlobStore(dir)
        vmk = VaultKeys.newVmk()
        indexId = ByteArray(BlobStore.BLOB_ID_BYTES) { (it + 3).toByte() }
        MetadataDb.empty().save(store, indexId, vmk)
    }

    @After fun tearDown() { dir.deleteRecursively() }

    @Test
    fun aNotesTextGoesToABlobAndTheRowKeepsAPreview() {
        val body = "sort code 60-16-13\nbranch: Mill Road"
        val note = VaultNote.create(store, vmk, "Bank details", body)!!

        assertTrue("the text must be in a blob", note.blobIds.isNotEmpty())
        assertNull("the text must not stay in the row", note.body)
        assertEquals(VaultNote.previewOf(body), note.preview)
        assertEquals(VaultNote.sizeOf(body).toLong(), note.plaintextLength)
        assertEquals("the text must read back byte-identical", body, VaultNote.read(store, note, vmk))
    }

    /** Larger than one 64 KiB chunk, so the multi-chunk path is the one under test. */
    @Test
    fun aNoteLongerThanOneChunkRoundTrips() {
        val body = buildString { repeat(20_000) { append("line ").append(it).append('\n') } }
        assertTrue("the premise: more than one chunk", VaultNote.sizeOf(body) > BlobStore.CHUNK)

        val note = VaultNote.create(store, vmk, "Long", body)!!
        assertEquals(body, VaultNote.read(store, note, vmk))
    }

    @Test
    fun aBodyOfMultiByteCharactersSurvivesTheBlob() {
        val body = "🔐 gizli — 秘密\ndeğer: 42"
        val note = VaultNote.create(store, vmk, "Mixed", body)!!

        assertEquals(body, VaultNote.read(store, note, vmk))
    }

    /**
     * An edit writes fresh blobs and drops the old ones **after** the index names the
     * new ones. The other order would leave a row pointing at a file that is gone.
     */
    @Test
    fun anEditReplacesTheBlobAndKeepsTheIdentity() {
        val added = VaultNote.add(store, indexId, vmk, "Codes", "1234")!!
        val before = added.items.single()
        val staleBlobs = before.blobIds

        val after = VaultNote.update(store, indexId, vmk, before, "Codes", "5678")!!
        val edited = after.items.single()

        assertEquals("an edit must not read as a delete and an insert", before.itemId, edited.itemId)
        assertEquals("5678", VaultNote.read(store, edited, vmk))
        assertEquals("5678", edited.preview)
        staleBlobs.forEach {
            assertTrue("the old blob must be gone", !store.fileOf(it).exists())
        }
        edited.blobIds.forEach {
            assertTrue("the new blob must be present", store.fileOf(it).exists())
        }
    }

    @Test
    fun aNoteSurvivesAnIndexReload() {
        VaultNote.add(store, indexId, vmk, "Receipts", "coffee 3.40")!!

        val reloaded = MetadataDb.load(store, indexId, vmk).items.single()
        assertNotNull(reloaded.noteId)
        assertEquals(VaultKind.NOTE, reloaded.kind)
        assertEquals("coffee 3.40", VaultNote.read(store, reloaded, vmk))
    }

    // --- the version-11 migration ---------------------------------------------

    /**
     * A version-11 note — text in the row, no blob, `.txt` — is left exactly as it
     * was by a read, and moved to a blob by the next commit.
     *
     * **Opening a vault to look at it must not write to it.** That is the rule
     * `migrateAlbums` follows and the reason this migration does not run in `parse`.
     */
    @Test
    fun aVersionElevenNoteIsUntouchedByAReadAndMovedByACommit() {
        val db = MetadataDb.empty()
        val legacy = VaultItem(
            blobIds = emptyList(),
            plaintextLength = 14L,
            thumb = null,
            filename = "shopping.txt",
            album = null,
            createdAt = null,
            importedAt = 1_700_000_000_000L,
            tags = emptyList(),
            body = "milk and bread",
            noteId = ByteArray(16) { (it + 1).toByte() },
        )
        db.items += legacy
        db.save(store, indexId, vmk)
        val identity = legacy.itemId

        // A read changes nothing.
        val read = MetadataDb.load(store, indexId, vmk).items.single()
        assertEquals("milk and bread", read.body)
        assertEquals("shopping.txt", read.filename)
        assertEquals("a read must not move it", 0, read.blobIds.size)
        assertEquals("a read must still open it", "milk and bread", VaultNote.read(store, read, vmk))

        // One commit moves it.
        MetadataDb.commit(store, indexId, vmk) { it }
        val moved = MetadataDb.load(store, indexId, vmk).items.single()

        assertNull("the text must have left the row", moved.body)
        assertTrue("the text must be in a blob", moved.blobIds.isNotEmpty())
        assertEquals("milk and bread", VaultNote.read(store, moved, vmk))
        assertEquals("milk and bread", moved.preview)
        assertEquals("the extension follows the text", "shopping.md", moved.filename)
        assertEquals("the title is untouched", "shopping", VaultNote.titleOf(moved))
        assertEquals("identity must survive the move", identity, moved.itemId)
        assertEquals(VaultKind.NOTE, moved.kind)
    }

    /** A second commit finds nothing to move. */
    @Test
    fun theMigrationIsIdempotent() {
        VaultNote.add(store, indexId, vmk, "Codes", "1234")
        val db = MetadataDb.load(store, indexId, vmk)

        assertEquals(0, db.migrateNotes(store, vmk))
    }

    // --- a note owns nothing ---------------------------------------------------

    /**
     * ***Destroying a note takes only the note — `remove-note-attachments`.***
     *
     * This test asserted the opposite: a note owned its attachments and destroying it
     * deleted their blobs too. Notes carry no attachments now, and an item that still
     * records an owning note is an ordinary item — so destroying that note must leave it
     * exactly where it is. Getting this wrong destroys a photograph the user still has.
     */
    @Test
    fun destroyingANoteLeavesAReleasedItemAlone() {
        val added = VaultNote.add(store, indexId, vmk, "Trip", "the photos")!!
        val note = added.items.single()

        val picture = store.writeItem(java.io.ByteArrayInputStream(ByteArray(64) { 9 }), vmk)
        // Written with an owner, exactly as a build that still had attachments would have
        // written it — this is the row an existing vault carries.
        val released = VaultItem(
            blobIds = picture.blobIds,
            plaintextLength = picture.plaintextLength,
            thumb = null,
            filename = "beach.jpg",
            album = null,
            createdAt = null,
            importedAt = 1_700_000_000_000L,
            tags = emptyList(),
            ownerNoteId = note.noteId,
        )
        MetadataDb.commit(store, indexId, vmk) { db -> db.items += released; db }

        VaultDeletion.purge(store, indexId, vmk, listOf(note))

        val after = MetadataDb.load(store, indexId, vmk)
        assertEquals("only the note may go", 1, after.items.size)
        assertEquals("beach.jpg", after.items.single().filename)
        released.blobIds.forEach {
            assertTrue("a released item's blobs must survive its old note", store.fileOf(it).exists())
        }
        note.blobIds.forEach {
            assertTrue("the note's own blob still goes", !store.fileOf(it).exists())
        }
    }

    /** The field survives a commit unread, so an index round trip does not lose it. */
    @Test
    fun theRetainedOwnerFieldRoundTrips() {
        val picture = store.writeItem(java.io.ByteArrayInputStream(ByteArray(32) { 4 }), vmk)
        val owner = ByteArray(16) { 7 }
        MetadataDb.commit(store, indexId, vmk) { db ->
            db.items += VaultItem(
                blobIds = picture.blobIds,
                plaintextLength = picture.plaintextLength,
                thumb = null,
                filename = "kept.jpg",
                album = null,
                createdAt = null,
                importedAt = 1L,
                tags = emptyList(),
                ownerNoteId = owner,
            )
            db
        }
        // A second commit that touches nothing: the field has to come back out.
        MetadataDb.commit(store, indexId, vmk) { db -> db }

        val row = MetadataDb.load(store, indexId, vmk).items.single { it.filename == "kept.jpg" }
        assertTrue("the retained field was lost on a round trip", row.ownerNoteId != null)
        assertTrue("and it must come back unchanged", owner.contentEquals(row.ownerNoteId!!))
    }

    /**
     * ***A released item is listed like any other — `remove-note-attachments`.***
     *
     * The grid used to filter on `ownerNoteId`, and that one line was what made an
     * attachment invisible. Removing it is the whole of the release, so this asserts the
     * property that line used to deny: a row still recording an owning note is an
     * ordinary item, present in the live list the grid, the trash, search and the
     * viewer's pager all descend from.
     */
    @Test
    fun anItemRecordingAnOwnerIsAnOrdinaryItem() {
        val added = VaultNote.add(store, indexId, vmk, "Trip", "the photos")!!
        val note = added.items.single()

        val picture = store.writeItem(java.io.ByteArrayInputStream(ByteArray(48) { 5 }), vmk)
        MetadataDb.commit(store, indexId, vmk) { db ->
            db.items += VaultItem(
                blobIds = picture.blobIds,
                plaintextLength = picture.plaintextLength,
                thumb = null,
                filename = "beach.jpg",
                album = null,
                createdAt = null,
                importedAt = 1L,
                tags = emptyList(),
                ownerNoteId = note.noteId,
            )
            db
        }

        // The chain `MainActivity` builds its list with: live rows, nothing else. Before
        // this change it also read `&& it.ownerNoteId == null`, and this row was absent.
        val live = MetadataDb.load(store, indexId, vmk).items.filter { it.deletedAt == null }

        assertEquals("the released item must be listed", 2, live.size)
        assertTrue(
            "the item that records an owner must appear in the live list",
            live.any { it.filename == "beach.jpg" },
        )
        // And it is findable, which the old filter also denied.
        assertEquals(1, VaultSearch.filter(live, "beach").size)
    }
}
