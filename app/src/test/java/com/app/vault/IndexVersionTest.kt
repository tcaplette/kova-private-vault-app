package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * The index's version boundary — P2-8 and P2-12, rewritten by P2-25.
 *
 * **The floor is 8 and this file pins it.** P2-25 raised `MIN_READABLE_VERSION`
 * from 4 to 8 at the publication boundary, because no index below 8 will exist
 * outside a developer's emulator: the version 4, 5, 6 and 7 read paths were
 * branches no published vault could reach, and — by this change's own rule that
 * compatibility is proven only by bytes a release actually produced — no fixture
 * could ever cover them. The hand-written version-4, -5 and -6 fixtures that used
 * to live here went with them.
 *
 * What replaces them is the pair of boundary tests that will matter for the life of
 * the app: a version **below** the floor is rejected, and a version **above** this
 * build's ceiling is the rollback case and reports itself as one. From the first
 * published release onward, every new field is appended, optional, and read where
 * present — and the floor never moves again, which is what
 * [theReadFloorNeverRises] exists to enforce.
 *
 * These are pure bytes in, index out, so they run on the JVM with no device.
 */
class IndexVersionTest {

    private fun bytes(n: Int, fill: Byte) = ByteArray(n) { fill }

    /**
     * An index at an arbitrary version byte, otherwise empty and well-formed, so a
     * version boundary is tested by the version and nothing else.
     */
    private fun indexAtVersion(version: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeByte(version)
            o.writeInt(0)                       // no items
            o.writeInt(0)                       // no chaff
            o.writeInt(0)                       // no packs
            o.writeByte(0)                      // no log blob
        }
        return out.toByteArray()
    }

    // --- P2-25: the floor, the ceiling, and the field ---------------------------

    /**
     * **The one number in this file that must never change.**
     *
     * Play delivers updates that skip versions, so a build must open what *any*
     * earlier published build wrote — not merely the previous one. Raising this
     * floor after publication strands every install below it, and the failure is
     * silent in the worst way: the vault is intact and unreadable.
     *
     * It was raised once, from 4 to 8, at the publication boundary and for the
     * reason this file's KDoc gives. That was the last time.
     */
    @Test
    fun theReadFloorNeverRises() {
        assertEquals(
            "MIN_READABLE_VERSION is frozen at the first published index version. " +
                "Raising it strands every install below it.",
            8,
            MetadataDb.MIN_READABLE_VERSION,
        )
    }

    @Test
    fun aVersionBelowTheFloorIsRejected() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            MetadataDb.parse(indexAtVersion(MetadataDb.MIN_READABLE_VERSION - 1))
        }
        assertTrue(
            "the message must name the version: ${thrown.message}",
            thrown.message!!.contains("unknown index version"),
        )
    }

    /**
     * The rollback case — a release pulled back after a staged rollout, running
     * against data the newer build wrote.
     *
     * Its own exception type, because the message is the whole point: at the moment
     * a user is most likely to believe their vault is gone, "corrupt" would be both
     * frightening and false.
     */
    @Test
    fun aVersionAboveThisBuildIsTheRollbackCase() {
        val thrown = assertThrows(MetadataDb.NewerVersionException::class.java) {
            MetadataDb.parse(indexAtVersion(99))
        }
        assertEquals(99, thrown.found)
        assertTrue(
            "the message must say a newer version wrote it: ${thrown.message}",
            thrown.message!!.contains("newer version of the app"),
        )
    }

    /** Too old and too new are different conditions and must not collapse into one. */
    @Test
    fun theFloorAndTheCeilingAreDistinctFailures() {
        val below = runCatching { MetadataDb.parse(indexAtVersion(4)) }.exceptionOrNull()
        val above = runCatching { MetadataDb.parse(indexAtVersion(99)) }.exceptionOrNull()

        assertTrue("below the floor is an ordinary parse failure", below is IllegalStateException)
        assertTrue("above the ceiling is not", above is MetadataDb.NewerVersionException)
        assertTrue(
            "a NewerVersionException must not also be an IllegalStateException, " +
                "or every `catch (IllegalStateException)` in the app swallows it",
            above !is IllegalStateException,
        )
    }

    /**
     * P2-25's reserved discriminator. Nothing reads it yet; it round-trips per row
     * so that a later build which does read it finds what was written.
     *
     * Per row and not per index, because a store mid-envelope-change holds blobs
     * under both — an index-level field would claim one envelope for a store with
     * two.
     */
    @Test
    fun theEnvelopeVersionRoundTripsPerRow() {
        val db = MetadataDb.empty()
        db.items += VaultItem(
            blobIds = listOf(bytes(16, 0x31)),
            plaintextLength = 1L,
            thumb = null,
            filename = "original-envelope.jpg",
            album = null,
            createdAt = null,
            importedAt = 1L,
            tags = emptyList(),
        )
        db.items += VaultItem(
            blobIds = listOf(bytes(16, 0x32)),
            plaintextLength = 1L,
            thumb = null,
            filename = "second-envelope.jpg",
            album = null,
            createdAt = null,
            importedAt = 1L,
            tags = emptyList(),
            envelopeVersion = 2,
        )

        val back = MetadataDb.parse(db.serialise())

        assertNull("absent means the original envelope", back.items[0].envelopeVersion)
        assertEquals(2, back.items[1].envelopeVersion)
    }

    /** A rename, a move or a trash stamp must not silently drop the discriminator. */
    @Test
    fun theEnvelopeVersionSurvivesEveryRowCopy() {
        val item = VaultItem(
            blobIds = listOf(bytes(16, 0x33)),
            plaintextLength = 1L,
            thumb = null,
            filename = "a.jpg",
            album = null,
            createdAt = null,
            importedAt = 1L,
            tags = emptyList(),
            envelopeVersion = 2,
        )

        assertEquals(2, item.withFilename("b.jpg").envelopeVersion)
        assertEquals(2, item.withAlbum("Trip").envelopeVersion)
        assertEquals(2, item.withDeletedAt(1L).envelopeVersion)
        assertEquals(2, item.withThumb(ThumbRef(bytes(16, 0x44), 0, 1)).envelopeVersion)
    }

    /**
     * `deletedAt` and `deletable` are adjacent in the record and one keystroke
     * apart in a `when`. Asserted on the same two items, in both combinations,
     * because "they round-trip" is not the claim — "they do not swap" is.
     */
    @Test
    fun theTrashStampAndTheSourceFlagAreIndependent() {
        val db = MetadataDb.empty()
        db.items += VaultItem(
            blobIds = listOf(bytes(16, 0x77)),
            plaintextLength = 1L,
            thumb = null,
            filename = "trashed-source-deletable.png",
            album = null,
            createdAt = null,
            importedAt = 1L,
            tags = emptyList(),
            deletable = true,
            durationMs = null,
            deletedAt = 1_800_000_000_000L,
        )
        db.items += VaultItem(
            blobIds = listOf(bytes(16, 0x08)),
            plaintextLength = 1L,
            thumb = null,
            filename = "live-source-kept.png",
            album = null,
            createdAt = null,
            importedAt = 1L,
            tags = emptyList(),
            deletable = false,
            durationMs = null,
            deletedAt = null,
        )

        val back = MetadataDb.parse(db.serialise())

        assertEquals(1_800_000_000_000L, back.items[0].deletedAt)
        assertEquals(true, back.items[0].deletable)
        assertNull(back.items[1].deletedAt)
        assertEquals(false, back.items[1].deletable)
    }

    @Test
    fun anIndexRoundTripsEveryAppendedField() {
        val db = MetadataDb.empty()
        db.items += VaultItem(
            blobIds = listOf(bytes(16, 0x33)),
            plaintextLength = 9001L,
            thumb = null,
            filename = "clip.mp4",
            album = null,
            createdAt = null,
            importedAt = 1_700_000_000_000L,
            tags = listOf("a", "b"),
            deletable = false,
            durationMs = 90_500L,
        )
        db.items += VaultItem(
            blobIds = listOf(bytes(16, 0x44)),
            plaintextLength = 1234L,
            thumb = null,
            filename = "photo.jpg",
            album = "Trip",
            createdAt = 1_500_000_000_000L,
            importedAt = 1_700_000_000_000L,
            tags = emptyList(),
            deletable = true,
            durationMs = null,
        )

        val back = MetadataDb.parse(db.serialise())

        assertEquals(2, back.items.size)
        assertEquals(90_500L, back.items[0].durationMs)
        assertNull("an image carries no duration", back.items[1].durationMs)
        assertEquals("Trip", back.items[1].album)
        assertEquals(listOf("a", "b"), back.items[0].tags)
    }

    // --- `settings-separation`: version 11 and the mirror destination ----------

    /**
     * A version-10 index, written by hand.
     *
     * Bytes a version-10 release actually produced — [versionNineIndex]'s rule one
     * version along: the whole question is whether a record that *ends* before
     * version 11's trailing section still reads, and a v11 serialisation with the
     * version byte patched would not ask it.
     */
    private fun versionTenIndex(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeByte(10)

            o.writeInt(1)                       // one item
            o.writeInt(1)                       // one blob ID
            o.writeInt(16); o.write(bytes(16, 0x41))
            o.writeLong(1_024L)                 // plaintext length
            o.writeByte(0)                      // no thumb
            "photo.jpg".toByteArray(Charsets.UTF_8).let { o.writeInt(it.size); o.write(it) }
            o.writeByte(0)                      // no album
            o.writeByte(0)                      // no createdAt
            o.writeLong(1_700_000_000_000L)     // importedAt
            o.writeInt(0)                       // no tags
            o.writeByte(0)                      // not deletable
            o.writeByte(0)                      // no durationMs
            o.writeByte(0)                      // no deletedAt
            o.writeByte(0)                      // no body
            o.writeByte(0)                      // no noteId
            o.writeByte(0)                      // no envelopeVersion
            o.writeByte(0)                      // no folder
            o.writeInt(0)                       // unrotated — version 10's last field

            o.writeInt(0)                       // no chaff
            o.writeInt(0)                       // no packs
            o.writeByte(0)                      // no log blob
            o.writeInt(0)                       // no folders
            // and the index ends here — no destination was ever written at 10.
        }
        return out.toByteArray()
    }

    /**
     * The two numbers, pinned — [rotationArrivedAtTenAndTheFloorStayedAtEight]'s test
     * one version along.
     *
     * The version moves when a section is appended; the floor never moves at all.
     */
    @Test
    fun theMirrorDestinationArrivedAtElevenAndTheFloorStayedAtEight() {
        assertEquals(11, MetadataDb.MIRROR_VERSION)
        assertEquals(8, MetadataDb.MIN_READABLE_VERSION)
    }

    /**
     * A version-10 index opens, and reads as a vault with no destination.
     *
     * Which is what every vault written before `settings-separation` held: backup was
     * a property of the install, in a preferences file, and D6's migration is what
     * hands that one folder to the first vault opened after the update.
     */
    @Test
    fun aVersionTenIndexStillOpensAndCarriesNoDestination() {
        val back = MetadataDb.parse(versionTenIndex())

        assertEquals(1, back.items.size)
        assertEquals("photo.jpg", back.items[0].filename)
        assertNull(
            "absent must read as no destination, which is what version 10 held",
            back.mirrorDestination,
        )
    }

    /**
     * A version-10 index re-serialised comes back at 11, and a destination set on it
     * survives the round trip.
     */
    @Test
    fun aDestinationSurvivesASaveAndAReload() {
        val loaded = MetadataDb.parse(versionTenIndex())
        loaded.mirrorDestination = "content://provider/tree/backup"

        val back = MetadataDb.parse(loaded.serialise())

        // The current write version, which `notes-as-documents` moved 11 -> 12,
        // `intruder-capture-record` moved 12 -> 13 and `viewer-swipe-navigation` moved
        // 13 -> 14. Re-serialising anything older always lands at the current version;
        // the assertion is that the *rest* of the record survived the upgrade, which is
        // why this names the newest constant rather than a literal.
        assertEquals(MetadataDb.VAULT_CAPTURES_VERSION, loaded.serialise()[0].toInt())
        assertEquals("content://provider/tree/backup", back.mirrorDestination)
        assertEquals("the rest of the index must be untouched", 1, back.items.size)
    }

    // --- `notes-as-documents`: version 12 and the note's blob ------------------

    /**
     * **A version-11 record ends before the four fields version 12 appends**, and it
     * still reads — the property that makes 11 -> 12 an appending bump rather than a
     * rejecting one.
     *
     * The fixture is a version-11 index carrying a note the old way: text in `body`,
     * no blob. That is the exact shape every published release wrote, and the shape
     * [MetadataDb.migrateNotes] has to recognise.
     */
    @Test
    fun aVersionElevenNoteStillReadsAndKeepsItsIdentity() {
        val db = MetadataDb.parse(versionElevenNote())
        val note = db.items.single()

        assertEquals("shopping.txt", note.filename)
        assertEquals("milk and bread", note.body)
        assertEquals("a version-11 note has no blob", 0, note.blobIds.size)
        assertEquals("a version-11 note has no preview", null, note.preview)
        assertEquals("a version-11 note is not pinned", false, note.pinned)
        assertEquals(FolderColour.None, note.colour)
        assertEquals("a version-11 note owns nothing", null, note.ownerNoteId)
        // Its identity is its note ID, before the migration and after it.
        assertEquals(hexOf(NOTE_ID), note.itemId)
        assertEquals(VaultKind.NOTE, note.kind)
    }

    /** The four appended fields survive a round trip at version 12. */
    @Test
    fun theVersionTwelveFieldsSurviveARoundTrip() {
        val db = MetadataDb.parse(versionElevenNote())
        val note = db.items.single()
        db.items[0] = note.withNoteBlob(
            blobIds = listOf(ByteArray(16) { 7 }),
            preview = "milk and bread",
        ).let { it.withColour(FolderColour.Teal).withPinned(true) }

        val back = MetadataDb.parse(db.serialise()).items.single()
        assertEquals(MetadataDb.VAULT_CAPTURES_VERSION, db.serialise()[0].toInt())
        assertEquals("milk and bread", back.preview)
        assertEquals(true, back.pinned)
        assertEquals(FolderColour.Teal, back.colour)
        assertEquals("the text left the index", null, back.body)
        assertEquals("identity is unchanged by the move", hexOf(NOTE_ID), back.itemId)
        assertEquals(VaultKind.NOTE, back.kind)
    }

    @Test
    fun theNoteBlobVersionIsPinned() {
        assertEquals(12, MetadataDb.NOTE_BLOB_VERSION)
        assertEquals(8, MetadataDb.MIN_READABLE_VERSION)
    }

    private val NOTE_ID = ByteArray(16) { (it + 1).toByte() }

    private fun hexOf(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /**
     * A version-11 index holding one note, written by hand.
     *
     * Bytes a version-11 release actually produced — this file's rule about what a
     * compatibility fixture is worth. Serialising at 12 and patching the version byte
     * would test a v12 record wearing a v11 label, and the whole question is whether a
     * record that *ends* before the new fields still reads.
     */
    private fun versionElevenNote(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeByte(11)
            o.writeInt(1)                      // one item
            o.writeInt(0)                      // no blobs — a version-11 note has none
            o.writeLong(14L)                   // plaintextLength: the body's bytes
            o.writeByte(0)                     // no thumb
            writeStringTo(o, "shopping.txt")
            o.writeByte(0)                     // no album
            o.writeByte(0)                     // no createdAt
            o.writeLong(1_700_000_000_000L)    // importedAt
            o.writeInt(0)                      // no tags
            o.writeByte(0)                     // not deletable
            o.writeByte(0)                     // no duration
            o.writeByte(0)                     // not deleted
            o.writeByte(1); writeStringTo(o, "milk and bread")
            o.writeByte(1); o.writeInt(NOTE_ID.size); o.write(NOTE_ID)
            o.writeByte(0)                     // no envelope version
            o.writeByte(0)                     // no folder
            o.writeInt(0)                      // rotation
            // and here a version-11 record ends.
            o.writeInt(0)                      // chaff
            o.writeInt(0)                      // packs
            o.writeByte(0)                     // no log blob
            o.writeInt(0)                      // folders
            o.writeByte(0)                     // no mirror destination
        }
        return out.toByteArray()
    }

    private fun writeStringTo(o: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        o.writeInt(bytes.size)
        o.write(bytes)
    }

    // --- `viewer-chrome-and-editor`: version 10 and the rotation ---------------

    /**
     * A version-9 index, written by hand.
     *
     * **Bytes a version-9 release actually produced**, which is this file's own rule
     * about what a compatibility fixture is worth: serialising at 10 and patching
     * the version byte would test a v10 record wearing a v9 label, and the whole
     * question is whether a record that *ends* before the new field still reads.
     *
     * One item, one folder, and every optional field absent except the folder
     * reference — the shortest record that reaches the field this version stops at.
     */
    private fun versionNineIndex(folderId: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeByte(9)

            o.writeInt(1)                       // one item
            o.writeInt(1)                       // one blob ID
            o.writeInt(16); o.write(bytes(16, 0x41))
            o.writeLong(1_024L)                 // plaintext length
            o.writeByte(0)                      // no thumb
            "photo.jpg".toByteArray(Charsets.UTF_8).let { o.writeInt(it.size); o.write(it) }
            o.writeByte(0)                      // no album
            o.writeByte(0)                      // no createdAt
            o.writeLong(1_700_000_000_000L)     // importedAt
            o.writeInt(0)                       // no tags
            o.writeByte(0)                      // not deletable
            o.writeByte(0)                      // no durationMs
            o.writeByte(0)                      // no deletedAt
            o.writeByte(0)                      // no body
            o.writeByte(0)                      // no noteId
            o.writeByte(0)                      // no envelopeVersion
            o.writeByte(1); o.writeInt(16); o.write(folderId)
            // and the record ends here — no rotation was ever written at 9.

            o.writeInt(0)                       // no chaff
            o.writeInt(0)                       // no packs
            o.writeByte(0)                      // no log blob

            o.writeInt(1)                       // one folder
            o.writeInt(16); o.write(folderId)
            o.writeByte(0)                      // no parent
            "Trips".toByteArray(Charsets.UTF_8).let { o.writeInt(it.size); o.write(it) }
            o.writeInt(FolderColour.Blue.ordinal)
            o.writeByte(0)                      // no cover
        }
        return out.toByteArray()
    }

    /**
     * The two numbers, pinned — the folder table's test one version along.
     *
     * The version moves when a field is appended; the floor never moves at all.
     */
    @Test
    fun rotationArrivedAtTenAndTheFloorStayedAtEight() {
        assertEquals(10, MetadataDb.ROTATION_VERSION)
        assertEquals(8, MetadataDb.MIN_READABLE_VERSION)
    }

    /** All four angles survive, and the angle is stored as itself. */
    @Test
    fun everyRotationRoundTrips() {
        val db = MetadataDb.empty()
        listOf(0, 90, 180, 270).forEachIndexed { i, angle ->
            db.items += VaultItem(
                blobIds = listOf(bytes(16, (0x60 + i).toByte())),
                plaintextLength = 1L,
                thumb = null,
                filename = "photo$i.jpg",
                album = null,
                createdAt = null,
                importedAt = 1L,
                tags = emptyList(),
                rotation = angle,
            )
        }

        val back = MetadataDb.parse(db.serialise())

        assertEquals(listOf(0, 90, 180, 270), back.items.map { it.rotation })
    }

    /**
     * A version-9 index loads, every record reads as unrotated, and nothing else
     * about it moved.
     */
    @Test
    fun aVersionNineIndexLoadsWithEveryItemUnrotated() {
        val folderId = bytes(16, 0x51)

        val back = MetadataDb.parse(versionNineIndex(folderId))

        val item = back.items.single()
        assertEquals("absent must read as unrotated, which is what version 9 held", 0, item.rotation)
        assertEquals("photo.jpg", item.filename)
        assertEquals(1_024L, item.plaintextLength)
        assertTrue(
            "the folder reference must survive the version this fixture stops at",
            item.folderId.contentEquals(folderId),
        )
        assertEquals("Trips", back.folders.single().name)
    }

    /**
     * A version-9 index re-serialised comes back at 10 with the rotation written,
     * and nothing is migrated on the way through.
     */
    @Test
    fun aVersionNineIndexRewritesAtTenWithoutMigrating() {
        val loaded = MetadataDb.parse(versionNineIndex(bytes(16, 0x51)))

        val again = MetadataDb.parse(loaded.serialise())

        assertEquals(0, again.items.single().rotation)
        assertEquals("photo.jpg", again.items.single().filename)
    }

    // --- `folder-system`: version 9, the folder table, and the migration --------

    /**
     * The two numbers, pinned.
     *
     * The version moves when a field is appended; the floor never moves at all. A
     * change that raises the floor to reach a new field is the one this file exists
     * to stop, and it would pass every other test in the suite.
     */
    @Test
    fun theFolderTableArrivedAtNineAndTheFloorStayedAtEight() {
        assertEquals(9, MetadataDb.FOLDERS_VERSION)
        assertEquals(8, MetadataDb.MIN_READABLE_VERSION)
    }

    /** Every field of a folder survives the round trip, nesting included. */
    @Test
    fun aFolderTableRoundTrips() {
        val db = MetadataDb.empty()
        val trips = VaultFolder(bytes(16, 0x51), null, "Trips", FolderColour.Blue)
        val inner = VaultFolder(
            folderId = bytes(16, 0x52),
            parentId = trips.folderId,
            name = "2024",
            colour = FolderColour.None,
            coverItemId = bytes(16, 0x33),
        )
        // An empty folder, which is the whole point of storing them at all.
        val empty = VaultFolder(bytes(16, 0x53), null, "Empty", FolderColour.Pink)
        db.folders += listOf(trips, inner, empty)
        db.items += VaultItem(
            blobIds = listOf(bytes(16, 0x33)),
            plaintextLength = 1L,
            thumb = null,
            filename = "photo.jpg",
            album = null,
            createdAt = null,
            importedAt = 1L,
            tags = emptyList(),
            folderId = inner.folderId,
        )

        val back = MetadataDb.parse(db.serialise())

        assertEquals(3, back.folders.size)
        val backInner = back.folders.single { it.name == "2024" }
        assertEquals(trips.id, backInner.parentId?.let { hex(it) })
        assertEquals(FolderColour.Blue, back.folders.single { it.name == "Trips" }.colour)
        assertEquals(hex(bytes(16, 0x33)), backInner.coverItemId?.let { hex(it) })
        assertEquals(0, back.items.count { it.folderId == null })
        assertEquals(inner.id, back.items.single().folderId?.let { hex(it) })
        // The empty one survived with nothing in it.
        assertEquals(0, back.items.count { it.folderId?.let { f -> hex(f) } == empty.id })
        assertTrue(back.folders.any { it.name == "Empty" })
    }

    /**
     * A version-8 index opens, and its albums arrive as folders.
     *
     * This is the migration's only fixture that is genuinely a *previous version* —
     * built by hand at version 8, with the album string every shipped build wrote and
     * none of the fields version 9 appends.
     */
    @Test
    fun aVersionEightIndexMigratesItsAlbumsToFolders() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeByte(8)
            o.writeInt(2)
            // Two rows: one in an album, one trashed in the same album. The trashed
            // one must migrate too, or restoring it months later lands it nowhere.
            listOf(false, true).forEach { trashed ->
                o.writeInt(1); o.writeInt(16); o.write(bytes(16, if (trashed) 0x22.toByte() else 0x11.toByte()))
                o.writeLong(1L)
                o.writeByte(0)                                  // no thumb
                val name = if (trashed) "b.jpg" else "a.jpg"
                o.writeInt(name.length); o.write(name.toByteArray())
                o.writeByte(1); o.writeInt(4); o.write("Trip".toByteArray())
                o.writeByte(0)                                  // no createdAt
                o.writeLong(1L)
                o.writeInt(0)                                   // no tags
                o.writeByte(0)                                  // not deletable
                o.writeByte(0)                                  // no duration
                if (trashed) { o.writeByte(1); o.writeLong(9L) } else o.writeByte(0)
                o.writeByte(0)                                  // no body
                o.writeByte(0)                                  // no note id
                o.writeByte(0)                                  // no envelope version
            }
            o.writeInt(0)                                       // no chaff
            o.writeInt(0)                                       // no packs
            o.writeByte(0)                                      // no log blob
        }

        val back = MetadataDb.parse(out.toByteArray())

        assertEquals("one album became one folder", 1, back.folders.size)
        val folder = back.folders.single()
        assertEquals("Trip", folder.name)
        assertNull("a migrated folder is at the top level", folder.parentId)
        assertEquals(FolderColour.None, folder.colour)
        assertEquals(
            "every item carrying the name is in it, trashed ones included",
            2,
            back.items.count { it.folderId?.let { f -> hex(f) } == folder.id },
        )
        assertEquals(
            "the legacy field is left as written rather than rewritten away",
            listOf("Trip", "Trip"),
            back.items.map { it.album },
        )
    }

    /** A vault that never had an album gains no folders from the migration. */
    @Test
    fun aVersionEightIndexWithNoAlbumsGainsNothing() {
        assertTrue(MetadataDb.parse(indexAtVersion(8)).folders.isEmpty())
    }

    /**
     * A folder whose parent is missing is pulled to the top level, not dropped.
     *
     * Every level query walks down from the root, so a folder hanging off nothing is
     * a folder — and everything inside it — that exists in the index and is invisible
     * on screen. Nothing this app writes can produce one; the parse is the wrong
     * place to trust that.
     */
    @Test
    fun anUnreachableFolderIsPulledToTheTopLevel() {
        val db = MetadataDb.empty()
        db.folders += VaultFolder(bytes(16, 0x61), bytes(16, 0x99.toByte()), "Orphan")

        val back = MetadataDb.parse(db.serialise())

        assertEquals(1, back.folders.size)
        assertNull("an orphan is reachable or it is nothing", back.folders.single().parentId)
    }

    /** A cycle is the same failure with a different shape, and gets the same answer. */
    @Test
    fun aFolderCycleIsBrokenRatherThanThrown() {
        val a = bytes(16, 0x71)
        val b = bytes(16, 0x72)
        val db = MetadataDb.empty()
        db.folders += VaultFolder(a, b, "A")
        db.folders += VaultFolder(b, a, "B")

        val back = MetadataDb.parse(db.serialise())

        assertEquals(2, back.folders.size)
        assertTrue(
            "both ends of a cycle are pulled to the top rather than lost",
            back.folders.all { it.parentId == null },
        )
    }

    /** A zero-length declaration is not a duration, and must not survive as one. */
    @Test
    fun aNullDurationSurvivesTheRoundTripAsNull() {
        val db = MetadataDb.empty()
        db.items += VaultItem(
            blobIds = listOf(bytes(16, 0x55)),
            plaintextLength = 10L,
            thumb = null,
            filename = "x.mp4",
            album = null,
            createdAt = null,
            importedAt = 1L,
            tags = emptyList(),
            durationMs = null,
        )
        assertNull(MetadataDb.parse(db.serialise()).items.single().durationMs)
    }

    @Test
    fun aNoteRoundTrips() {
        val db = MetadataDb.empty()
        val note = VaultNote.row("Bank details", "sort code 60-16-13\nbranch: Mill Road")!!
        db.items += note
        db.items += VaultItem(
            blobIds = listOf(bytes(16, 0x21)),
            plaintextLength = 12L,
            thumb = null,
            filename = "photo.jpg",
            album = null,
            createdAt = null,
            importedAt = 1_700_000_000_000L,
            tags = emptyList(),
        )

        val back = MetadataDb.parse(db.serialise())

        assertEquals(2, back.items.size)
        // `notes-as-documents`: the preview is what the row carries now.
        assertEquals(note.preview, back.items[0].preview)
        assertNull("the text must not be in the row", back.items[0].body)
        assertEquals(note.itemId, back.items[0].itemId)
        assertEquals("Bank details.md", back.items[0].filename)
        assertEquals(VaultKind.NOTE, back.items[0].kind)

        assertNull("the row beside it must not gain a preview", back.items[1].preview)
        assertNull("the row beside it must not gain a body", back.items[1].body)
        assertNull(back.items[1].noteId)
    }

    /** A body is length-prefixed UTF-8, not `writeUTF` — emoji and 64 KiB both survive. */
    @Test
    fun aBodyOfMultiByteCharactersRoundTrips() {
        val db = MetadataDb.empty()
        val body = "🔐 gizli — 秘密\ndeğer: 42"
        db.items += VaultNote.row("Mixed", body)!!

        // The preview is the multi-byte round trip the row can still make; the text
        // itself travels in a blob and `VaultNoteDeviceTest` covers that.
        assertEquals(
            VaultNote.previewOf(body),
            MetadataDb.parse(db.serialise()).items.single().preview,
        )
    }

    @Test
    fun aRowWithNeitherABlobNorANoteIdFailsTheParse() {
        // The invariant is checked on the way in, so a corrupt index fails at load
        // rather than somewhere in the grid the first time `itemId` is read.
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeByte(8)
            o.writeInt(1)
            o.writeInt(0)                       // no blob ids
            o.writeLong(0L)
            o.writeByte(0)                      // no thumb
            o.writeInt(5); o.write("x.txt".toByteArray(Charsets.UTF_8))
            o.writeByte(0); o.writeByte(0)      // no album, no createdAt
            o.writeLong(1L)
            o.writeInt(0)                       // no tags
            o.writeByte(0)                      // not deletable
            o.writeByte(0); o.writeByte(0)      // no duration, no deletedAt
            o.writeByte(0)                      // no body
            o.writeByte(0)                      // no note id
            o.writeByte(0)                      // no envelope version
            o.writeInt(0); o.writeInt(0); o.writeByte(0)
        }

        val thrown = assertThrows(IllegalStateException::class.java) {
            MetadataDb.parse(out.toByteArray())
        }
        assertTrue(
            "the message must name what is wrong: ${thrown.message}",
            thrown.message!!.contains("neither a blob nor a note ID"),
        )
    }

    // --- `viewer-swipe-navigation`: version 14 and the stopping point -----------

    /** The two numbers, pinned, and the version a fresh index is written at. */
    @Test
    fun theStoppingPointArrivedAtFourteenAndTheFloorStayedAtEight() {
        assertEquals(14, MetadataDb.RESUME_VERSION)
        assertEquals(15, MetadataDb.FOLDER_DESTINATION_VERSION)
        // `destination-settings-and-slideshow` moved the write version to 16, and
        // `per-vault-intruder-capture` to 17.
        assertEquals("an index is written at 17", 17, MetadataDb.empty().serialise()[0].toInt())
        assertEquals(8, MetadataDb.MIN_READABLE_VERSION)
    }

    /** A folder keeps the destination it was made in; one with none stays unscoped. */
    @Test
    fun aFolderKeepsTheDestinationItWasMadeIn() {
        val db = MetadataDb.empty()
        db.folders += VaultFolder(bytes(16, 0x71), null, "Pics", destination = VaultDestination.Media)
        db.folders += VaultFolder(bytes(16, 0x72), null, "Any")

        val back = MetadataDb.parse(db.serialise()).folders
        assertEquals(VaultDestination.Media, back.single { it.name == "Pics" }.destination)
        assertEquals(null, back.single { it.name == "Any" }.destination)
    }

    /**
     * A version-13 index, written by hand — [versionTenIndex]'s rule three versions along.
     *
     * One video with a known duration, and every other optional field absent. The record
     * ends at the owner reference, which is where every version-13 record ends.
     */
    private fun versionThirteenIndex(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeByte(13)

            o.writeInt(1)                       // one item
            o.writeInt(1)                       // one blob ID
            o.writeInt(16); o.write(bytes(16, 0x5A))
            o.writeLong(4_096L)                 // plaintext length
            o.writeByte(0)                      // no thumb
            writeStringTo(o, "clip.mp4")
            o.writeByte(0)                      // no album
            o.writeByte(0)                      // no createdAt
            o.writeLong(1_700_000_000_000L)     // importedAt
            o.writeInt(0)                       // no tags
            o.writeByte(0)                      // not deletable
            o.writeByte(1); o.writeLong(90_500L) // durationMs
            o.writeByte(0)                      // no deletedAt
            o.writeByte(0)                      // no body
            o.writeByte(0)                      // no noteId
            o.writeByte(0)                      // no envelopeVersion
            o.writeByte(0)                      // no folder
            o.writeInt(0)                       // unrotated
            o.writeByte(0)                      // no preview
            o.writeByte(0)                      // not pinned
            o.writeInt(FolderColour.None.ordinal)
            o.writeByte(0)                      // no owner — version 13's last field
            // and the record ends here — no stopping point was ever written at 13.

            o.writeInt(0)                       // no chaff
            o.writeInt(0)                       // no packs
            o.writeByte(0)                      // no log blob
            o.writeInt(0)                       // no folders
            o.writeByte(0)                      // no mirror destination
            o.writeByte(0)                      // no captures seen
        }
        return out.toByteArray()
    }

    /** A version-13 index opens, and every record reads as never stopped. */
    @Test
    fun aVersionThirteenIndexReadsWithNoStoppingPoint() {
        val item = MetadataDb.parse(versionThirteenIndex()).items.single()

        assertNull("absent must read as from the beginning", item.resumeMs)
        assertEquals("clip.mp4", item.filename)
        assertEquals("the field before it must be untouched", 90_500L, item.durationMs)
    }

    @Test
    fun itemsWithAndWithoutAStoppingPointRoundTrip() {
        val db = MetadataDb.parse(versionThirteenIndex())
        db.items[0] = db.items[0].withResume(42_000L)
        db.items += VaultItem(
            blobIds = listOf(bytes(16, 0x5B)),
            plaintextLength = 1L,
            thumb = null,
            filename = "never-stopped.mp4",
            album = null,
            createdAt = null,
            importedAt = 1L,
            tags = emptyList(),
        )

        val back = MetadataDb.parse(db.serialise())

        assertEquals(42_000L, back.items[0].resumeMs)
        assertNull(back.items[1].resumeMs)
    }

    /** `metadata-db`: recording a stopping point changes nothing else about the row. */
    @Test
    fun recordingAStoppingPointChangesNothingElse() {
        val folder = bytes(16, 0x5C)
        val item = VaultItem(
            blobIds = listOf(bytes(16, 0x5D), bytes(16, 0x5E)),
            plaintextLength = 70_000_000L,
            thumb = ThumbRef(bytes(16, 0x5F), 128, 2_048),
            filename = "film.mp4",
            album = null,
            createdAt = 1_600_000_000_000L,
            importedAt = 1_700_000_000_000L,
            tags = emptyList(),
            durationMs = 600_000L,
            folderId = folder,
            rotation = 90,
        )

        val stopped = item.withResume(80_000L)

        assertEquals(80_000L, stopped.resumeMs)
        assertEquals(item.blobIds.map { hex(it) }, stopped.blobIds.map { hex(it) })
        assertEquals(item.plaintextLength, stopped.plaintextLength)
        assertTrue(stopped.thumb!!.packBlobId.contentEquals(item.thumb!!.packBlobId))
        assertEquals(item.thumb!!.offset, stopped.thumb!!.offset)
        assertEquals(item.thumb!!.length, stopped.thumb!!.length)
        assertEquals(item.filename, stopped.filename)
        assertEquals(item.createdAt, stopped.createdAt)
        assertEquals(item.importedAt, stopped.importedAt)
        assertEquals(item.rotation, stopped.rotation)
        assertTrue(stopped.folderId.contentEquals(folder))
        assertEquals(item.durationMs, stopped.durationMs)
    }

    /** Every other change to a row carries the stopping point through unchanged. */
    @Test
    fun renameMoveRotateTrashAndRestoreCarryTheStoppingPoint() {
        val item = VaultItem(
            blobIds = listOf(bytes(16, 0x60)),
            plaintextLength = 1L,
            thumb = null,
            filename = "a.mp4",
            album = null,
            createdAt = null,
            importedAt = 1L,
            tags = emptyList(),
            resumeMs = 80_000L,
        )

        assertEquals(80_000L, item.withFilename("b.mp4").resumeMs)
        assertEquals(80_000L, item.withFolder(bytes(16, 0x61)).resumeMs)
        assertEquals(80_000L, item.withRotation(90).resumeMs)
        val trashed = item.withDeletedAt(1_800_000_000_000L)
        assertEquals(80_000L, trashed.resumeMs)
        assertEquals(80_000L, trashed.withDeletedAt(null).resumeMs)
    }

    // --- `destination-settings-and-slideshow`: version 16 and the edit time -----

    /**
     * A version-15 record ends at its stopping point and reads as never edited; an edit time
     * set on it survives a save at 16.
     */
    @Test
    fun theEditTimeIsAppendedAtSixteenAndSurvivesARoundTrip() {
        assertEquals(16, MetadataDb.EDITED_VERSION)
        assertEquals(8, MetadataDb.MIN_READABLE_VERSION)
        val db = MetadataDb.parse(versionElevenNote())
        assertNull("an older record has no edit time", db.items.single().editedAt)
        db.items[0] = db.items[0].withEditedAt(1_234L)
        val back = MetadataDb.parse(db.serialise()).items.single()
        assertEquals(MetadataDb.VAULT_CAPTURES_VERSION, db.serialise()[0].toInt())
        assertEquals(1_234L, back.editedAt)
        assertEquals("identity is unchanged", hexOf(NOTE_ID), back.itemId)
    }

    // --- `per-vault-intruder-capture`: version 17 and a keyring capture store's vault lists ---

    @Test
    fun theVaultListsAreAppendedAtSeventeenAndRoundTrip() {
        assertEquals(17, MetadataDb.VAULT_CAPTURES_VERSION)
        assertEquals(8, MetadataDb.MIN_READABLE_VERSION)
        val vault = bytes(16, 0x11)
        val other = bytes(16, 0x22)
        val db = MetadataDb.empty()
        db.vaultCaptures += VaultEnrolment(vault, bytes(32, 0x33), setOf(Capture.Mode.PHOTO, Capture.Mode.VIDEO))
        db.vaultCaptures += VaultEnrolment(other, bytes(32, 0x44), setOf(Capture.Mode.AUDIO))
        db.vaultPending += VaultPending(vault, Capture.Mode.VIDEO, bytes(16, 0x55), 4_096)
        db.vaultNotices += VaultNotice(other, TamperLog.PERMISSION_MICROPHONE, TamperLog.PERMISSION_LOST, 1_700_000_000_123L)

        assertEquals(MetadataDb.VAULT_CAPTURES_VERSION, db.serialise()[0].toInt())
        val back = MetadataDb.parse(db.serialise())
        assertEquals(2, back.vaultCaptures.size)
        assertEquals(hexOf(vault), hexOf(back.vaultCaptures[0].indexBlobId))
        assertEquals(hexOf(bytes(32, 0x33)), hexOf(back.vaultCaptures[0].publicKey))
        assertEquals(setOf(Capture.Mode.PHOTO, Capture.Mode.VIDEO), back.vaultCaptures[0].modes)
        assertEquals(setOf(Capture.Mode.AUDIO), back.vaultCaptures[1].modes)
        val pending = back.vaultPending.single()
        assertEquals(hexOf(vault), hexOf(pending.indexBlobId))
        assertEquals(Capture.Mode.VIDEO, pending.mode)
        assertEquals(hexOf(bytes(16, 0x55)), hexOf(pending.blobId))
        assertEquals(4_096, pending.sealedLength)
        val notice = back.vaultNotices.single()
        assertEquals(hexOf(other), hexOf(notice.indexBlobId))
        assertEquals(TamperLog.PERMISSION_MICROPHONE, notice.permission)
        assertEquals(TamperLog.PERMISSION_LOST, notice.state)
        assertEquals(1_700_000_000_123L, notice.at)
    }

    /** A version-16 index ends at the seen mark and reads with all three lists empty. */
    @Test
    fun aVersionSixteenIndexReadsWithTheVaultListsEmpty() {
        val current = MetadataDb.parse(versionElevenNote()).apply { capturesSeenAt = 42L }.serialise()
        // Exactly version 16's bytes: the same index, its three empty v17 counts not yet written.
        val sixteen = current.copyOf(current.size - 12).also { it[0] = 16 }
        val db = MetadataDb.parse(sixteen)
        assertTrue(db.vaultCaptures.isEmpty())
        assertTrue(db.vaultPending.isEmpty())
        assertTrue(db.vaultNotices.isEmpty())
        assertEquals("the field before them is untouched", 42L, db.capturesSeenAt)
        assertEquals("the rest of the index is untouched", hexOf(NOTE_ID), db.items.single().itemId)
    }
}
