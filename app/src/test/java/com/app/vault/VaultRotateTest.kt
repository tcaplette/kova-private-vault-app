package com.app.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Turning an item — `viewer-chrome-and-editor`.
 *
 * Two claims, and neither needs a device. The arithmetic is a total function over
 * four angles, and "a rotation changes nothing else" is a statement about one row
 * that can be made by building the row and reading it back.
 */
class VaultRotateTest {

    private fun bytes(n: Int, fill: Byte) = ByteArray(n) { fill }

    private fun item(rotation: Int = 0) = VaultItem(
        blobIds = listOf(bytes(16, 0x41), bytes(16, 0x42)),
        plaintextLength = 4_096L,
        thumb = ThumbRef(bytes(16, 0x43), 12, 34),
        filename = "photo.jpg",
        album = null,
        createdAt = 1_600_000_000_000L,
        importedAt = 1_700_000_000_000L,
        tags = listOf("a", "b"),
        deletable = true,
        durationMs = null,
        deletedAt = null,
        folderId = bytes(16, 0x51),
        rotation = rotation,
    )

    // --- the arithmetic -------------------------------------------------------

    @Test
    fun aQuarterTurnAdvancesThroughTheFourAngles() {
        assertEquals(90, VaultRotate.turned(0, 1))
        assertEquals(180, VaultRotate.turned(90, 1))
        assertEquals(270, VaultRotate.turned(180, 1))
        assertEquals(0, VaultRotate.turned(270, 1))
    }

    /** Four turns is where it started — the claim the spec makes in words. */
    @Test
    fun fourQuarterTurnsReturnToTheStart() {
        listOf(0, 90, 180, 270).forEach { start ->
            var angle = start
            repeat(4) { angle = VaultRotate.turned(angle, 1) }
            assertEquals("four turns from $start must return to $start", start, angle)
        }
    }

    /** No stored angle is ever negative, so no reader has to defend against one. */
    @Test
    fun turningBackwardsNormalisesRatherThanGoingNegative() {
        assertEquals(270, VaultRotate.turned(0, -1))
        assertEquals(0, VaultRotate.turned(90, -1))
        assertEquals(90, VaultRotate.turned(0, 5))
        assertEquals(180, VaultRotate.turned(0, -6))
    }

    // --- a rotation changes nothing else --------------------------------------

    /**
     * The whole of `item-rotation`'s "nothing but the angle" requirement, read off
     * one row: every other field is the field it was.
     */
    @Test
    fun turningChangesTheAngleAndNothingElse() {
        val before = item()

        val after = before.withRotation(90)

        assertEquals(90, after.rotation)
        assertEquals(before.blobIds.size, after.blobIds.size)
        before.blobIds.forEachIndexed { i, id -> assertArrayEquals(id, after.blobIds[i]) }
        assertEquals(before.plaintextLength, after.plaintextLength)
        assertEquals(before.thumb?.offset, after.thumb?.offset)
        assertEquals(before.thumb?.length, after.thumb?.length)
        assertEquals(before.filename, after.filename)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(before.importedAt, after.importedAt)
        assertEquals(before.tags, after.tags)
        assertEquals(before.deletable, after.deletable)
        assertEquals(before.durationMs, after.durationMs)
        assertEquals(before.deletedAt, after.deletedAt)
        assertArrayEquals(before.folderId, after.folderId)
        assertEquals(before.itemId, after.itemId)
    }

    /**
     * Every copy helper carries the angle through. A helper that dropped it would
     * silently straighten an item on rename, on a move, or on a trip to the trash.
     */
    @Test
    fun everyCopyHelperCarriesTheRotation() {
        val turned = item(rotation = 270)

        assertEquals(270, turned.withFilename("other.jpg").rotation)
        assertEquals(270, turned.withFolder(null).rotation)
        assertEquals(270, turned.withAlbum("Trip").rotation)
        assertEquals(270, turned.withDeletedAt(1L).rotation)
        assertEquals(270, turned.withDeletedAt(null).rotation)
        assertEquals(270, turned.withThumb(ThumbRef(bytes(16, 0x44), 1, 2)).rotation)
    }

    /** A note is turned by the same field, so `withBody` must carry it too. */
    @Test
    fun aNotesRewriteCarriesTheRotation() {
        val note = VaultNote.row("Codes", "1234")!!.withRotation(180)

        assertEquals(180, note.withBody("Codes.txt", "5678").rotation)
    }

    // --- the default ----------------------------------------------------------

    @Test
    fun anItemIsUnrotatedUntilItIsTurned() {
        assertEquals(0, item().rotation)
        assertNull("the fixture must not have gained a duration", item().durationMs)
    }

    /** Turning to the angle it already holds builds an equal row, not a broken one. */
    @Test
    fun turningToTheSameAngleIsHarmless() {
        val turned = item(rotation = 90)

        assertEquals(90, turned.withRotation(90).rotation)
        assertSame(turned.filename, turned.withRotation(90).filename)
    }
}
