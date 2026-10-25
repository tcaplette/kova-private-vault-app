package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What is and is not a folder name — P2-13's `AlbumNameTest`, one model along.
 *
 * `normalise` returning null is a **refusal the caller reports**, never a silent
 * fallback: the two mean opposite things to the user, and committing the second when
 * they asked for the first puts their items somewhere they did not choose.
 */
class FolderNameTest {

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("Trip", VaultFolders.normalise("  Trip  "))
        assertEquals("Trip 2019", VaultFolders.normalise("\tTrip 2019\n"))
    }

    @Test
    fun aBlankNameIsRefused() {
        assertNull(VaultFolders.normalise(""))
        assertNull(VaultFolders.normalise("   "))
        assertNull(VaultFolders.normalise("\t\n"))
    }

    @Test
    fun theLengthCapIsOnTheTrimmedName() {
        val atCap = "a".repeat(VaultFolders.MAX_NAME)
        assertEquals(atCap, VaultFolders.normalise(atCap))
        assertEquals(atCap, VaultFolders.normalise("  $atCap  "))
        assertNull(VaultFolders.normalise("a".repeat(VaultFolders.MAX_NAME + 1)))
    }

    @Test
    fun caseIsNotFolded() {
        assertEquals("Trip", VaultFolders.normalise("Trip"))
        assertEquals("trip", VaultFolders.normalise("trip"))
    }

    @Test
    fun aNameKeepsItsOwnCharacters() {
        assertEquals("Réunion 🏝", VaultFolders.normalise("Réunion 🏝"))
    }

    /**
     * A separator is an ordinary character.
     *
     * This is what identity-keyed folders bought. A path-keyed model would have had
     * to reserve a character user-typed names already contain, and the names already
     * in shipped vaults were never restricted.
     */
    @Test
    fun aSeparatorIsJustACharacter() {
        assertEquals("Trips/2024", VaultFolders.normalise("Trips/2024"))
    }
}
