package com.app.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * The keyring's record of which slots it knows are occupied — `slot-occupancy-map`.
 *
 * **Bytes in, bytes out.** Nothing here seals or opens anything: an [VaultKeyring.Entry]
 * carries its key already sealed, and `parse` never verifies it, so an entry built from
 * arbitrary bytes exercises the layout exactly as a real one does. That is what keeps
 * this on the JVM — the property under test is a byte at an offset and a version
 * number, and neither needs a device.
 *
 * **The offsets are written out here rather than imported**, because they are private to
 * `VaultKeyring` and because a layout test that reads the layout from the thing it is
 * testing asserts nothing. If a refactor moves the record, this file fails, which is
 * the point of it.
 */
class KeyringOccupancyTest {

    private companion object {
        const val VERSION_BYTES = 1
        const val OCCUPIED_OFFSET = 0
        /** A u8 name length since version 7; the u16's high byte became the appearance. */
        const val NAME_OFFSET = 1
        const val APPEARANCE_OFFSET = 2
        const val NAME_TEXT_OFFSET = 3
        const val SEALED_KEY_OFFSET = 97
        const val SEALED_KEY_BYTES = 72
        const val INDEX_ID_OFFSET = 169
        const val INDEX_ID_BYTES = 16
        const val PROTECTED_OFFSET = 185

        /** Position 0's second spare byte. The whole of the new field. */
        const val KNOWN_SLOTS_OFFSET = 187

        /** `panic-phrase`: the panic slot and the home slot, each 0 for none, else slot + 1. */
        const val PANIC_SLOT_OFFSET = 188
        const val HOME_SLOT_OFFSET = 189
    }

    private val rng = SecureRandom()

    private fun entry(position: Int, name: String, protected: Boolean = false, appearance: Int = 0) =
        VaultKeyring.Entry(
            position = position,
            name = name,
            sealedKey = ByteArray(SEALED_KEY_BYTES).also { rng.nextBytes(it) },
            indexBlobId = ByteArray(INDEX_ID_BYTES).also { rng.nextBytes(it) },
            hasOwnPassphrase = protected,
            appearance = appearance,
        )

    /** A keyring with a spread of positions, forms and gaps — not just position 0. */
    private fun populated(knownSlots: Int = 0, hasDecoy: Boolean = false): VaultKeyring.Keyring {
        val entries = arrayOfNulls<VaultKeyring.Entry>(VaultKeyring.CAPACITY)
        entries[0] = entry(0, "Photos")
        entries[1] = entry(1, "Papers", protected = true)
        entries[7] = entry(7, "Sounds")
        entries[15] = entry(15, "Ω — a name with multibyte bytes in it")
        return VaultKeyring.Keyring(entries, hasDecoy, knownSlots)
    }

    private fun base(position: Int) = VERSION_BYTES + position * VaultKeyring.ENTRY_BYTES

    /** What a position actually claims, filler excluded — filler is random by design. */
    private fun meaning(bytes: ByteArray, position: Int): List<ByteArray> {
        val b = base(position)
        val occupied = bytes[b + OCCUPIED_OFFSET]
        if (occupied.toInt() != 1) return listOf(byteArrayOf(occupied))
        val nameLength = bytes[b + NAME_OFFSET].toInt() and 0xFF
        return listOf(
            byteArrayOf(occupied),
            bytes.copyOfRange(b + NAME_OFFSET, b + NAME_TEXT_OFFSET + nameLength),
            bytes.copyOfRange(b + SEALED_KEY_OFFSET, b + SEALED_KEY_OFFSET + SEALED_KEY_BYTES),
            bytes.copyOfRange(b + INDEX_ID_OFFSET, b + INDEX_ID_OFFSET + INDEX_ID_BYTES),
            byteArrayOf(bytes[b + PROTECTED_OFFSET]),
        )
    }

    private fun assertEntriesIdentical(expected: ByteArray, actual: ByteArray) {
        for (position in 0 until VaultKeyring.CAPACITY) {
            val a = meaning(expected, position)
            val b = meaning(actual, position)
            assertEquals("position $position changed shape", a.size, b.size)
            a.zip(b).forEachIndexed { i, (x, y) ->
                assertArrayEquals("position $position, field $i", x, y)
            }
        }
    }

    // --- the record itself ----------------------------------------------------

    @Test
    fun theRecordSurvivesARoundTrip() {
        for (value in 0..0xFF) {
            val bytes = VaultKeyring.serialise(populated(knownSlots = value))
            assertEquals(
                "the record must live at $KNOWN_SLOTS_OFFSET",
                value, bytes[KNOWN_SLOTS_OFFSET].toInt() and 0xFF,
            )
            val back = VaultKeyring.parse(bytes)
            assertNotNull("a version 3 block must parse", back)
            assertEquals("the record must come back", value, back!!.knownSlots)
        }
    }

    @Test
    fun theRecordIsWrittenEvenWhenPositionZeroIsEmpty() {
        // The one wrinkle in the layout: the byte sits in position 0's reserved tail
        // but is not part of that entry, so an unoccupied position 0 must not skip it.
        val entries = arrayOfNulls<VaultKeyring.Entry>(VaultKeyring.CAPACITY)
        entries[3] = entry(3, "Only one, and not at the front")
        val bytes = VaultKeyring.serialise(VaultKeyring.Keyring(entries, false, 0b1001_0110))

        assertEquals(0, bytes[base(0) + OCCUPIED_OFFSET].toInt())
        assertEquals(0b1001_0110, bytes[KNOWN_SLOTS_OFFSET].toInt() and 0xFF)
        assertEquals(0b1001_0110, VaultKeyring.parse(bytes)!!.knownSlots)
    }

    @Test
    fun theRecordDoesNotMoveTheLength() {
        val one = VaultKeyring.serialise(populated(knownSlots = 0b0000_0001))
        val eight = VaultKeyring.serialise(populated(knownSlots = 0b1111_1111))
        assertEquals(VaultKeyring.PLAINTEXT_BYTES, one.size)
        assertEquals("a keyring knowing eight slots is no longer", one.size, eight.size)
    }

    @Test
    fun removingPositionZeroDoesNotClearTheRecord() {
        val ring = VaultKeyring.without(populated(knownSlots = 0b0010_1101), 0)
        assertNull("position 0 is gone", ring.at(0))
        assertEquals(0b0010_1101, ring.knownSlots)
        assertEquals(0b0010_1101, VaultKeyring.parse(VaultKeyring.serialise(ring))!!.knownSlots)
    }

    // --- versions --------------------------------------------------------------

    /** A block at [version], otherwise exactly what this build writes. */
    private fun at(version: Int, keyring: VaultKeyring.Keyring): ByteArray {
        val bytes = VaultKeyring.serialise(keyring)
        val header = bytes[0].toInt() and 0x80
        bytes[0] = (header or version).toByte()
        // What an older build left at this offset: filler, not a record. A version 2
        // reader that answered with these bits would be answering with noise.
        if (version < VaultKeyring.VERSION_NO_PANIC_SLOT) bytes[KNOWN_SLOTS_OFFSET] = 0xA7.toByte()
        if (version < VaultKeyring.VERSION_INSTALL_BOUND) {
            bytes[PANIC_SLOT_OFFSET] = 0x03
            bytes[HOME_SLOT_OFFSET] = 0x05
        }
        // Below version 7 this byte was the u16 name length's high byte, and so always 0.
        if (version < VaultKeyring.VERSION_UNPROTECTED_NO_KIT_VIEWS) for (position in 0 until VaultKeyring.CAPACITY) {
            if (bytes[base(position) + OCCUPIED_OFFSET].toInt() == 1) bytes[base(position) + APPEARANCE_OFFSET] = 0
        }
        return bytes
    }

    @Test
    fun aVersionTwoBlockParsesWithAnEmptyRecord() {
        val bytes = at(VaultKeyring.VERSION_NO_KNOWN_SLOTS, populated(knownSlots = 0xFF))
        val parsed = VaultKeyring.parse(bytes)
        assertNotNull("a version 2 block must still read", parsed)
        assertEquals("it knows of no slot, which is what the version means", 0, parsed!!.knownSlots)
        // And nothing else about it is reinterpreted.
        assertEquals(4, parsed.vaults.size)
        assertTrue("the second form still reads at version 2", parsed.at(1)!!.hasOwnPassphrase)
    }

    @Test
    fun aVersionOneBlockParsesWithAnEmptyRecordAndNoProtectedEntry() {
        val bytes = at(VaultKeyring.VERSION_UNPROTECTED, populated(knownSlots = 0xFF))
        val parsed = VaultKeyring.parse(bytes)!!
        assertEquals(0, parsed.knownSlots)
        assertTrue(
            "every entry in a version 1 block is unprotected",
            parsed.vaults.none { it.hasOwnPassphrase },
        )
    }

    @Test
    fun aVersionTwoBlockIsWrittenBackAsVersionThreeUnchanged() {
        val original = at(VaultKeyring.VERSION_NO_KNOWN_SLOTS, populated(hasDecoy = true))
        val parsed = VaultKeyring.parse(original)!!
        val rewritten = VaultKeyring.serialise(parsed)

        assertEquals("the stored plaintext is the same length", original.size, rewritten.size)
        assertEquals(
            "it is stored under the current version",
            VaultKeyring.VERSION, rewritten[0].toInt() and 0x7F,
        )
        assertTrue("the decoy bit survives", (rewritten[0].toInt() and 0x80) != 0)
        assertEquals("and it still records nothing", 0, rewritten[KNOWN_SLOTS_OFFSET].toInt())
        assertEntriesIdentical(original, rewritten)
    }

    @Test
    fun anUnknownVersionIsRefused() {
        val ring = populated()
        assertNull("0 is not a version", VaultKeyring.parse(at(0, ring)))
        assertNull("the next version is the future", VaultKeyring.parse(at(VaultKeyring.VERSION + 1, ring)))
        assertNull("and so is 0x7F", VaultKeyring.parse(at(0x7F, ring)))
        // The top bit is the decoy flag and must not be read as a version.
        val withDecoy = at(VaultKeyring.VERSION, VaultKeyring.withDecoy(ring))
        assertNotNull("bit 7 set is a decoy, not an unknown version", VaultKeyring.parse(withDecoy))
    }

    // --- the panic record (`panic-phrase`) -----------------------------------------

    @Test
    fun thePanicRecordSurvivesARoundTrip() {
        for (panic in -1 until SlotArray.SLOT_COUNT) for (home in -1 until SlotArray.SLOT_COUNT) {
            val ring = VaultKeyring.withPanicRecord(populated(knownSlots = 0b0101_0011), panic, home)
            val bytes = VaultKeyring.serialise(ring)
            assertEquals(panic + 1, bytes[PANIC_SLOT_OFFSET].toInt() and 0xFF)
            assertEquals(home + 1, bytes[HOME_SLOT_OFFSET].toInt() and 0xFF)
            val back = VaultKeyring.parse(bytes)!!
            assertEquals(panic, back.panicSlot)
            assertEquals(home, back.homeSlot)
            assertEquals("the occupancy record is not disturbed", 0b0101_0011, back.knownSlots)
        }
    }

    @Test
    fun aVersionThreeBlockHasNoPanicRecord() {
        val ring = VaultKeyring.withPanicRecord(populated(knownSlots = 0b0000_0110), 2, 1)
        val parsed = VaultKeyring.parse(at(VaultKeyring.VERSION_NO_PANIC_SLOT, ring))!!
        assertEquals("a version 3 block has no panic phrase", -1, parsed.panicSlot)
        assertEquals(-1, parsed.homeSlot)
        assertEquals("and still reads its occupancy record", 0b0000_0110, parsed.knownSlots)
    }

    @Test
    fun thePanicRecordIsCarriedThroughEveryChange() {
        val ring = VaultKeyring.withPanicRecord(populated(knownSlots = 0b0000_0011), 4, 0)
        val changed = listOf(
            VaultKeyring.without(ring, 7),
            VaultKeyring.edited(ring, 0, "Renamed", 0),
            VaultKeyring.relinked(ring, 1, ByteArray(INDEX_ID_BYTES)),
            VaultKeyring.withDecoy(ring),
            VaultKeyring.withKnownSlots(ring, 0b0001_0011),
        )
        changed.forEach {
            assertEquals(4, it.panicSlot)
            assertEquals(0, it.homeSlot)
        }
    }

    @Test
    fun aBlockOfTheWrongLengthIsRefused() {
        val bytes = VaultKeyring.serialise(populated())
        assertNull(VaultKeyring.parse(bytes.copyOf(bytes.size - 1)))
        assertNull(VaultKeyring.parse(bytes.copyOf(bytes.size + 1)))
    }

    // --- 5.2: every modification carries the record through ---------------------

    @Test
    fun everyModificationCarriesTheRecordThrough() {
        val record = 0b1011_0010
        val ring = populated(knownSlots = record)

        val indexId = ByteArray(INDEX_ID_BYTES).also { rng.nextBytes(it) }
        val cases = mapOf(
            "renamed" to VaultKeyring.edited(ring, 0, "Renamed", 0),
            "relinked" to VaultKeyring.relinked(ring, 0, indexId),
            "without" to VaultKeyring.without(ring, 7),
            "without, a position that holds nothing" to VaultKeyring.without(ring, 5),
            "withDecoy" to VaultKeyring.withDecoy(ring),
            "withDecoy, already carrying one" to
                VaultKeyring.withDecoy(VaultKeyring.withDecoy(ring)),
            "renamed, a position that holds nothing" to VaultKeyring.edited(ring, 5, "Nothing", 0),
        )
        cases.forEach { (name, result) ->
            assertEquals("$name dropped the record", record, result.knownSlots)
            assertEquals(
                "$name did not survive a round trip",
                record, VaultKeyring.parse(VaultKeyring.serialise(result))!!.knownSlots,
            )
        }
    }

    @Test
    fun withKnownSlotsChangesTheRecordAndNothingElse() {
        val ring = populated(knownSlots = 0b0000_0011, hasDecoy = true)
        val updated = VaultKeyring.withKnownSlots(ring, 0b0000_1011)

        assertEquals(0b0000_1011, updated.knownSlots)
        assertTrue("the decoy bit is not this function's business", updated.hasDecoy)
        assertEquals(ring.vaults.size, updated.vaults.size)
        ring.vaults.forEach { before ->
            val after = updated.at(before.position)!!
            assertEquals(before.name, after.name)
            assertArrayEquals(before.sealedKey, after.sealedKey)
            assertArrayEquals(before.indexBlobId, after.indexBlobId)
            assertEquals(before.hasOwnPassphrase, after.hasOwnPassphrase)
        }
        // Eight bits and no more: a ninth would be a slot that does not exist.
        assertEquals(0xFF, VaultKeyring.withKnownSlots(ring, 0x1FF).knownSlots)
    }

    // --- the appearance (`vault-list-refresh`, D1) --------------------------------

    /** Every position holding a different appearance and a different name. */
    private fun dressed(): VaultKeyring.Keyring {
        val entries = arrayOfNulls<VaultKeyring.Entry>(VaultKeyring.CAPACITY)
        for (p in 0 until VaultKeyring.CAPACITY) {
            val look = VaultAppearance(FolderColour.entries[p % FolderColour.entries.size], 24 - p)
            entries[p] = entry(p, "Vault ${"é".repeat(p)}", protected = p % 3 == 0, appearance = look.pack())
        }
        return VaultKeyring.Keyring(entries, false, 0b0000_0001)
    }

    @Test
    fun theAppearanceRoundTripsAtEveryPositionWithoutMovingTheLength() {
        val ring = dressed()
        val bytes = VaultKeyring.serialise(ring)
        assertEquals("the same length as an empty keyring", VaultKeyring.serialise(VaultKeyring.empty()).size, bytes.size)
        assertEquals(VaultKeyring.PLAINTEXT_BYTES, bytes.size)
        assertEquals(VaultKeyring.VERSION, bytes[0].toInt() and 0x7F)

        val back = VaultKeyring.parse(bytes)!!
        for (p in 0 until VaultKeyring.CAPACITY) {
            assertEquals("position $p's name", ring.at(p)!!.name, back.at(p)!!.name)
            assertEquals("position $p's appearance", ring.at(p)!!.appearance, back.at(p)!!.appearance)
            assertEquals(
                "stored at entry offset 2",
                ring.at(p)!!.appearance, bytes[base(p) + APPEARANCE_OFFSET].toInt() and 0xFF,
            )
            assertEquals(
                "the name length is one byte at offset 1",
                ring.at(p)!!.name.toByteArray().size, bytes[base(p) + NAME_OFFSET].toInt() and 0xFF,
            )
        }
    }

    @Test
    fun aVersionSixBlockReadsAsTheLetterWithNoColourAndWritesBackAsSeven() {
        val original = at(VaultKeyring.VERSION_NO_APPEARANCE, dressed())
        val parsed = VaultKeyring.parse(original)!!
        assertEquals(VaultKeyring.CAPACITY, parsed.vaults.size)
        parsed.vaults.forEach {
            assertEquals(VaultAppearance.LETTER, VaultAppearance.of(it.appearance))
            assertEquals("the name reads as it did", dressed().at(it.position)!!.name, it.name)
        }

        val rewritten = VaultKeyring.serialise(parsed)
        assertEquals(VaultKeyring.VERSION, rewritten[0].toInt() and 0x7F)
        assertEquals(original.size, rewritten.size)
        // Sealed keys, index IDs, the flag, the name and a 0 appearance: all identical.
        assertEntriesIdentical(original, rewritten)
    }

    @Test
    fun anUnknownIconOrColourReadsAsNoneInThatFieldAlone() {
        val bytes = VaultKeyring.serialise(dressed())
        bytes[base(0) + APPEARANCE_OFFSET] = ((31 shl 3) or FolderColour.Teal.ordinal).toByte()
        bytes[base(1) + APPEARANCE_OFFSET] = ((5 shl 3) or 7).toByte()
        val back = VaultKeyring.parse(bytes)
        assertNotNull("an appearance never refuses a keyring", back)
        assertEquals(VaultAppearance(FolderColour.Teal, 0), VaultAppearance.of(back!!.at(0)!!.appearance))
        assertEquals(VaultAppearance(FolderColour.None, 5), VaultAppearance.of(back.at(1)!!.appearance))
    }

    @Test
    fun editedChangesTheNameAndTheAppearanceAndNothingElse() {
        val ring = populated(knownSlots = 0b0000_0011)
        val look = VaultAppearance(FolderColour.Pink, 7).pack()
        val edited = VaultKeyring.edited(ring, 1, "Letters", look)

        val before = ring.at(1)!!
        val after = edited.at(1)!!
        assertEquals("Letters", after.name)
        assertEquals(look, after.appearance)
        assertArrayEquals(before.sealedKey, after.sealedKey)
        assertArrayEquals(before.indexBlobId, after.indexBlobId)
        assertEquals(before.hasOwnPassphrase, after.hasOwnPassphrase)
        assertEquals(before.kdfSet, after.kdfSet)
        assertEquals(before.kitViewsUsed, after.kitViewsUsed)
        assertEquals(0b0000_0011, edited.knownSlots)
        listOf(0, 7, 15).forEach { assertTrue("position $it untouched", ring.at(it) === edited.at(it)) }
    }

    @Test
    fun everyOtherChangeCarriesTheAppearanceThrough() {
        val ring = dressed()
        val look = ring.at(3)!!.appearance
        listOf(
            VaultKeyring.relinked(ring, 3, ByteArray(INDEX_ID_BYTES)),
            VaultKeyring.withEntryKitViewsUsed(ring, 3, 2),
            VaultKeyring.withDecoy(ring),
            VaultKeyring.withKnownSlots(ring, 0b11),
            VaultKeyring.without(ring, 4),
        ).forEach { assertEquals(look, it.at(3)!!.appearance) }
    }

    @Test
    fun adjacentNewVaultsGetDifferentColoursAndTheLetter() {
        for (p in 0 until VaultKeyring.CAPACITY - 1) {
            val a = VaultAppearance.defaultFor(p)
            val b = VaultAppearance.defaultFor(p + 1)
            assertTrue("position $p and ${p + 1} differ", a.colour != b.colour)
            assertTrue("never No colour", a.colour != FolderColour.None)
            assertEquals("the letter", 0, a.icon)
        }
        assertEquals(24, VaultAppearance.ICONS.size)
        assertTrue("every packed value fits the byte", VaultAppearance(FolderColour.Pink, 24).pack() <= 0xFF)
    }
}
