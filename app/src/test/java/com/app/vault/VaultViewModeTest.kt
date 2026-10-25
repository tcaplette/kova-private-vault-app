package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two view modes and each destination's default — `destination-view-modes`.
 *
 * The pure half. What the modes *draw* is on screen and is asserted on a device; what is
 * decidable here is that there are two of them, that every destination has a default, and
 * that those defaults are exactly what the app drew before the toggle existed.
 */
class VaultViewModeTest {

    /** Two, and the toggle is a two-state control because of it. */
    @Test
    fun thereAreExactlyTwoModes() {
        assertEquals(
            setOf(VaultViewMode.List, VaultViewMode.Tiles),
            VaultViewMode.entries.toSet(),
        )
    }

    /** [VaultViewMode.other] is the toggle's whole job: it must never return itself. */
    @Test
    fun everyModeSwitchesToTheOtherOne() {
        VaultViewMode.entries.forEach { mode ->
            assertNotEquals("$mode switches to itself", mode, mode.other)
            assertEquals("$mode does not round-trip", mode, mode.other.other)
        }
    }

    /**
     * ***The defaults are exactly today's appearance.***
     *
     * This is the requirement that no vault changes shape because of an update, written as an
     * assertion. Media drew a mosaic and the other three drew rows before the toggle existed;
     * a user who never finds the toggle must see precisely that.
     */
    @Test
    fun theDefaultsAreWhatEachDestinationAlreadyDrew() {
        assertEquals(VaultViewMode.Tiles, VaultDestination.Media.defaultViewMode)
        assertEquals(VaultViewMode.List, VaultDestination.Files.defaultViewMode)
        assertEquals(VaultViewMode.List, VaultDestination.Notes.defaultViewMode)
        assertEquals(VaultViewMode.List, VaultDestination.Memos.defaultViewMode)
    }

    /**
     * No destination defaults to a mode that cannot show its kind.
     *
     * Media is the only destination whose every kind can carry a thumbnail
     * ([VaultKind.canHaveThumbnail]), so it is the only one that may default to tiles. A
     * destination holding a kind with no picture defaulting to a picture grid would ship the
     * "rectangle with a filename in it" that `vault-destinations` calls a defect.
     */
    @Test
    fun noDestinationDefaultsToAModeThatCannotShowItsKind() {
        VaultDestination.entries.forEach { destination ->
            if (destination.defaultViewMode == VaultViewMode.Tiles) {
                assertTrue(
                    "${destination.label} defaults to tiles but holds a kind with no thumbnail",
                    destination.kinds.all { it.canHaveThumbnail },
                )
            }
        }
    }

    /** Every entry names its action, not its state — see [VaultViewMode.actionLabel]. */
    @Test
    fun everyModeIsNamedByTheActionItPerforms() {
        VaultViewMode.entries.forEach { mode ->
            assertTrue("${mode.name} has no action label", TestWords.text(mode.actionLabel).isNotBlank())
            assertTrue(
                "\"${TestWords.text(mode.actionLabel)}\" names a state rather than an action",
                TestWords.text(mode.actionLabel).startsWith("Show as"),
            )
        }
    }

    /** No two modes share a mark; the toggle would otherwise say nothing by changing. */
    @Test
    fun theTwoModesDoNotShareAMark() {
        assertNotEquals(VaultViewMode.List.icon, VaultViewMode.Tiles.icon)
    }

    /**
     * ***Pinch follows the tiles, not one destination.***
     *
     * The gate read `destination == VaultDestination.Media`, which was right while Media was
     * the only mosaic and wrong the moment the shape became a preference: density is a
     * property of a grid of pictures, so Files in Tiles has one and Media in List does not.
     *
     * A source scan, in the shape `ScreenChromeTest` and `SearchScanTest` already use in this
     * codebase, and for their reason: `FLAG_SECURE` returns black to a screenshot and a
     * synthetic two-finger transform tests the gesture recogniser rather than which screens
     * carry it.
     */
    @Test
    fun pinchIsGatedOnTheViewModeAndNotOnTheDestination() {
        var dir: File? = File(".").absoluteFile
        var grid: File? = null
        while (dir != null && grid == null) {
            listOf(
                File(dir, "src/main/java/com/app/vault/ui/VaultGrid.kt"),
                File(dir, "app/src/main/java/com/app/vault/ui/VaultGrid.kt"),
            ).firstOrNull { it.isFile }?.let { grid = it }
            dir = dir.parentFile
        }
        val src = requireNotNull(grid) { "could not locate VaultGrid.kt" }.readText()

        // `lastIndexOf`, not `substringBefore`: the first mention of this name in the file is
        // its **import**, near the top, and cutting there reads the imports instead of the
        // gate. The scan reported a missing gate that was there all along.
        val call = src.lastIndexOf("detectTransformGestures")
        assertTrue("the pinch was removed entirely", call > 0)

        val gate = src.take(call).takeLast(400)
        assertTrue(
            "the pinch is not gated on the view mode",
            gate.contains("viewMode == VaultViewMode.Tiles"),
        )
        assertFalse(
            "the pinch is still gated on the destination being Media",
            gate.contains("destination == VaultDestination.Media"),
        )
    }

    /**
     * ***The I8 count.*** One key per destination, four in total, and none of them names a
     * vault.
     *
     * `GridPreference`'s own KDoc sets the rule: an entry is one per device and never per
     * vault, because a per-vault entry makes the number of entries the number of vaults —
     * which is the count I8 exists to hide. Four destination-named keys satisfy it, and this
     * fails the build if a later change makes the count depend on anything else.
     */
    @Test
    fun thereIsOneStoredKeyPerDestinationAndNoneNamesAVault() {
        val keys = GridPreference.viewModeKeys()
        assertEquals("the key count must be the destination count", 4, keys.size)
        assertEquals("two destinations share a key", keys.size, keys.toSet().size)
        keys.forEach { key ->
            assertTrue("\"$key\" is not a view-mode key", key.startsWith("view_"))
            listOf("vault", "slot", "keyring", "album", "item").forEach { forbidden ->
                assertFalse("\"$key\" names $forbidden", key.contains(forbidden))
            }
        }
    }
}
