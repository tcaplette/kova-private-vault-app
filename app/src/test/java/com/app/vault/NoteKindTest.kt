package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A note is a note structurally, and the other four kinds did not move — P2-21.
 *
 * `item-kinds` required the kind be derived from the extension "and from nothing
 * else". P2-21 amended that for one kind and one kind only, and the second half of
 * this file is what proves the amendment did not reach the other four: an item
 * already in a vault must classify today exactly as it did before notes existed.
 */
class NoteKindTest {

    private fun item(filename: String, body: String? = null) = VaultItem(
        blobIds = if (body == null) listOf(ByteArray(16) { 1 }) else emptyList(),
        plaintextLength = 0,
        thumb = null,
        filename = filename,
        album = null,
        createdAt = null,
        importedAt = 0,
        tags = emptyList(),
        body = body,
        noteId = if (body == null) null else ByteArray(16) { 2 },
    )

    // --- the structural half --------------------------------------------------

    @Test
    fun anItemWithABodyIsANoteWhateverItIsNamed() {
        // The whole reason the check is structural and comes first: a rename must
        // not be able to convert a note into a file whose bytes do not exist.
        listOf("list.txt", "list.jpg", "list.mp4", "list", ".txt", "")
            .forEach { assertEquals(it, VaultKind.NOTE, item(it, body = "text").kind) }
    }

    @Test
    fun anImportedTextFileIsNotANote() {
        // Same extension, no body — bytes in blobs, so it is a file. There is no
        // conversion between the two and none is offered.
        assertEquals(VaultKind.FILE, item("readme.txt").kind)
    }

    @Test
    fun aNoteHasNoThumbnailAndIsNotPlayable() {
        // This is what keeps `Thumbnails.pending` from queueing a note and the
        // player from ever being handed one, with no branch in either.
        assertEquals(false, VaultKind.NOTE.canHaveThumbnail)
        assertEquals(false, VaultKind.NOTE.isPlayable)
    }

    // --- the four content kinds, unmoved --------------------------------------

    @Test
    fun theFourContentKindsStillComeFromTheExtension() {
        mapOf(
            "IMG_2019.jpg" to VaultKind.IMAGE,
            "photo.HEIC" to VaultKind.IMAGE,
            "clip.mp4" to VaultKind.VIDEO,
            "clip.MKV" to VaultKind.VIDEO,
            "song.flac" to VaultKind.AUDIO,
            "voice.m4a" to VaultKind.AUDIO,
            "deed.pdf" to VaultKind.FILE,
            "archive.zip" to VaultKind.FILE,
            "noextension" to VaultKind.FILE,
            ".jpg" to VaultKind.FILE,
        ).forEach { (name, expected) ->
            assertEquals(name, expected, item(name).kind)
            assertEquals("$name via kindOf", expected, VaultKind.kindOf(name))
        }
    }

    @Test
    fun theItemsKindAgreesWithKindOfForEveryNonNote() {
        // One classification point, which is what `item-kinds` requires: for a row
        // with no body, `item.kind` must be exactly `kindOf(filename)` and not a
        // second opinion that could drift from it.
        listOf("a.jpg", "a.mp4", "a.mp3", "a.bin", "a", "a.TXT", "..", "x.jpeg")
            .forEach { assertEquals(it, VaultKind.kindOf(it), item(it).kind) }
    }

    @Test
    fun theVideoExtensionSetIsTheOneThatShipped() {
        // P2-18's guarantee, re-asserted here because P2-21 touched the enum: a
        // rewrite of these sets would silently change the kind of items already in
        // vaults, which no test would otherwise notice.
        assertEquals(
            setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "avi", "ts"),
            VaultKind.videoExtensions(),
        )
    }
}
