package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The appearance choice and its resolution — P2-23.
 *
 * `ThemeChoice.resolve` decides which scheme the app draws and which window theme it
 * starts under, and it takes no Android type, so its whole truth table is decidable
 * here. That is deliberate: three choices against two system states is six rows, and
 * six rows should not need an emulator.
 */
class ThemeChoiceTest {

    @Test
    fun theDefaultIsDark() {
        assertSame(ThemeChoice.Dark, ThemeChoice.Default)
        assertSame(ThemeChoice.Dark, ThemeChoice.of(null))
    }

    /**
     * The whole table, written out rather than generated. Six rows is few enough to
     * read, and reading it is the point — a generated version would restate the
     * implementation and agree with it by construction.
     */
    @Test
    fun theResolutionTableIsComplete() {
        // choice, systemIsDark -> draws dark
        assertTrue(ThemeChoice.resolve(ThemeChoice.System, systemIsDark = true))
        assertFalse(ThemeChoice.resolve(ThemeChoice.System, systemIsDark = false))

        assertFalse(ThemeChoice.resolve(ThemeChoice.Light, systemIsDark = true))
        assertFalse(ThemeChoice.resolve(ThemeChoice.Light, systemIsDark = false))

        assertTrue(ThemeChoice.resolve(ThemeChoice.Dark, systemIsDark = true))
        assertTrue(ThemeChoice.resolve(ThemeChoice.Dark, systemIsDark = false))
    }

    /** A forced choice ignores the system; that is the whole distinction it offers. */
    @Test
    fun aForcedChoiceIgnoresTheSystem() {
        listOf(ThemeChoice.Light, ThemeChoice.Dark).forEach { forced ->
            assertEquals(
                "${forced.name} changed with the system flag",
                ThemeChoice.resolve(forced, systemIsDark = true),
                ThemeChoice.resolve(forced, systemIsDark = false),
            )
        }
    }

    /** And `System` does not, which is the other half of the same claim. */
    @Test
    fun systemFollowsTheSystem() {
        assertEquals(
            true,
            ThemeChoice.resolve(ThemeChoice.System, systemIsDark = true) !=
                ThemeChoice.resolve(ThemeChoice.System, systemIsDark = false),
        )
    }

    @Test
    fun everyNameRoundTrips() {
        ThemeChoice.entries.forEach { assertSame(it, ThemeChoice.of(it.name)) }
    }

    @Test
    fun anUnknownNameReadsAsTheDefault() {
        listOf(null, "", " ", "dark", "DARK", "Auto", "Light ")
            .forEach { assertSame("read $it", ThemeChoice.Default, ThemeChoice.of(it)) }
    }

    /**
     * **Stored by name, never ordinal.** An ordinal makes the stored value depend on
     * declaration order, so inserting a choice later would silently re-point every
     * device's saved setting. Fails against any implementation that parses a number.
     */
    @Test
    fun aStoredOrdinalIsNotAChoice() {
        ThemeChoice.entries.forEach {
            assertSame(
                "ordinal ${it.ordinal} resolved to a choice",
                ThemeChoice.Default,
                ThemeChoice.of(it.ordinal.toString()),
            )
        }
    }

    @Test
    fun theThreeChoicesHaveDistinctLabels() {
        assertEquals(3, ThemeChoice.entries.size)
        assertEquals("two choices share a label", 3, ThemeChoice.entries.map { TestWords.text(it.label) }.toSet().size)
    }
}
