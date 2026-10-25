package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A note's edit time — `destination-settings-and-slideshow` D2. A title or body saved stamps
 * it; colour, pin and folder do not.
 */
class VaultNoteTest {

    private val note = VaultItem(
        blobIds = emptyList(),
        plaintextLength = 0L,
        thumb = null,
        filename = "shopping.md",
        album = null,
        createdAt = null,
        importedAt = 100L,
        tags = emptyList(),
        noteId = ByteArray(16) { 9 },
        preview = "",
    )

    @Test
    fun aNewNoteHasNoEditTime() {
        assertNull(note.editedAt)
    }

    @Test
    fun anEditSetsTheEditTime() {
        val before = System.currentTimeMillis()
        val edited = VaultNote.editRow(note, "shopping", "milk")!!
        assertNotNull(edited.editedAt)
        assert(edited.editedAt!! >= before)
        assertEquals("identity is carried", note.itemId, edited.itemId)
    }

    @Test
    fun pinColourAndMoveAreNotEdits() {
        assertNull(note.withPinned(true).editedAt)
        assertNull(note.withColour(FolderColour.Teal).editedAt)
        assertNull(note.withFolder(ByteArray(16) { 1 }).editedAt)
        val edited = note.withEditedAt(555L)
        assertEquals(555L, edited.withPinned(true).editedAt)
        assertEquals(555L, edited.withColour(FolderColour.Teal).editedAt)
        assertEquals(555L, edited.withFolder(null).editedAt)
    }
}
