package com.app.vault

import com.app.vault.ui.GridDensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tiles keep their size when the device turns — `landscape-rotation`, `vault-grid`.
 *
 * The emulator's phone is 411dp across upright and 914dp sideways; sideways the rail takes
 * 80dp of that, leaving the list 834dp.
 */
class TileSizeTest {

    private val upright = 411f

    @Test
    fun uprightIsWhatItWasBefore() {
        // Before this change the target was `screenWidthDp / columns`, and upright the screen
        // width is the shorter side.
        GridDensity.entries.forEach { d ->
            assertEquals(upright / d.columns, GridDensity.rowTarget(upright, d.columns), 0.001f)
            assertEquals(d.columns, GridDensity.folderColumns(upright, upright, d.columns))
        }
    }

    @Test
    fun sidewaysKeepsTheRowHeightAndFitsMoreAcross() {
        val medium = GridDensity.Medium.columns
        // The shorter side is still 411 when the phone is turned; the target does not move.
        assertEquals(137f, GridDensity.rowTarget(upright, medium), 0.001f)
        assertEquals(6, GridDensity.folderColumns(834f, upright, medium))
    }

    @Test
    fun neverFewerFolderColumnsThanTheDensity() {
        GridDensity.entries.forEach { d ->
            listOf(0f, 100f, 300f, 411f).forEach { w ->
                assertTrue(GridDensity.folderColumns(w, upright, d.columns) >= d.columns)
            }
        }
    }
}
