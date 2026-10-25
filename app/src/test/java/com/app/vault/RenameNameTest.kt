package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a filename splits into, and what is and is not a new one — P2-14.
 *
 * `normalise` returning null is a **refusal the caller reports**, never a silent
 * repair: a repaired name means the item is called something the user did not
 * type, and they find out at export time.
 */
class RenameNameTest {

    // --- the split ---------------------------------------------------------

    @Test
    fun anOrdinaryNameSplitsAtItsLastDot() {
        assertEquals("photo" to ".jpg", VaultRename.split("photo.jpg"))
        assertEquals("IMG_4471" to ".JPG", VaultRename.split("IMG_4471.JPG"))
    }

    @Test
    fun onlyTheLastDotSplits() {
        assertEquals("archive.tar" to ".gz", VaultRename.split("archive.tar.gz"))
        assertEquals("a.b" to ".c", VaultRename.split("a.b.c"))
    }

    @Test
    fun aNameWithNoDotIsAllStem() {
        assertEquals("photo" to "", VaultRename.split("photo"))
        assertEquals("" to "", VaultRename.split(""))
    }

    /**
     * The rule `ExportDestination.suffixed` already applies — a dot at index 0 does
     * not split — so `.gitignore` is a name and not an empty one with a
     * nine-character extension.
     */
    @Test
    fun aLeadingDotIsNotAnExtension() {
        assertEquals(".gitignore" to "", VaultRename.split(".gitignore"))
        assertEquals(".hidden" to ".txt", VaultRename.split(".hidden.txt"))
    }

    @Test
    fun aTrailingDotIsAnEmptyExtension() {
        assertEquals("photo" to ".", VaultRename.split("photo."))
    }

    // --- the refusals ------------------------------------------------------

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("Passport.jpg", VaultRename.normalise("  Passport  ", ".jpg"))
        assertEquals("Passport.jpg", VaultRename.normalise("\tPassport\n", ".jpg"))
    }

    @Test
    fun theExtensionIsAppendedUnchanged() {
        assertEquals("Passport.JPG", VaultRename.normalise("Passport", ".JPG"))
        assertEquals("Passport", VaultRename.normalise("Passport", ""))
    }

    @Test
    fun aBlankStemIsRefused() {
        assertNull(VaultRename.normalise("", ".jpg"))
        assertNull(VaultRename.normalise("   ", ".jpg"))
        assertNull(VaultRename.normalise("\t\n", ".jpg"))
    }

    /** No destination takes these, and an item named `..` is not a name the user meant. */
    @Test
    fun aStemOfOnlyDotsIsRefused() {
        assertNull(VaultRename.normalise(".", ".jpg"))
        assertNull(VaultRename.normalise("..", ".jpg"))
        assertNull(VaultRename.normalise("...", ""))
        // A dot with something else in it is an ordinary name.
        assertEquals(".a.jpg", VaultRename.normalise(".a", ".jpg"))
    }

    /** Refused rather than stripped: a stripped separator looks like the typed name and is not. */
    @Test
    fun aSeparatorOrNulIsRefused() {
        assertNull(VaultRename.normalise("holiday/2019", ".jpg"))
        assertNull(VaultRename.normalise("holiday\\2019", ".jpg"))
        assertNull(VaultRename.normalise("holiday\u00002019", ".jpg"))
        assertNull(VaultRename.normalise("/", ".jpg"))
    }

    /** The cap is on the assembled name, because that is what the filesystem sees. */
    @Test
    fun theLengthCapCountsTheExtension() {
        val atCap = "a".repeat(VaultRename.MAX_NAME - 4)
        assertEquals("$atCap.jpg", VaultRename.normalise(atCap, ".jpg"))
        assertNull(VaultRename.normalise("a".repeat(VaultRename.MAX_NAME - 3), ".jpg"))
        // With no extension the whole budget is the stem's.
        val whole = "a".repeat(VaultRename.MAX_NAME)
        assertEquals(whole, VaultRename.normalise(whole, ""))
        assertNull(VaultRename.normalise("a".repeat(VaultRename.MAX_NAME + 1), ""))
    }

    @Test
    fun aNameKeepsItsOwnCharacters() {
        assertEquals("Réunion 🏝.jpg", VaultRename.normalise("Réunion 🏝", ".jpg"))
    }

    /** Split then normalise is the identity for a name the app already stores. */
    @Test
    fun aStoredNameSurvivesARoundTrip() {
        for (name in listOf("photo.jpg", "photo", ".gitignore", "archive.tar.gz", "Réunion 🏝.png")) {
            val (stem, ext) = VaultRename.split(name)
            assertEquals(name, VaultRename.normalise(stem, ext))
        }
    }
}
