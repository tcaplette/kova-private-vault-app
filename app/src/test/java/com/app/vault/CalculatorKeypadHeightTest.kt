package com.app.vault

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.vault.ui.keypadHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `calculator-setup-keypad-sizing`: the portrait keypad takes its two-thirds share
 * when that already fits comfortable keys, borrows the readout's empty space when it
 * does not, and never gets less than the share.
 *
 * `comfortable` is 3 × 44 + 2 × 6 + 16 + 5 × 64 + 4 × 8 = 512dp.
 */
class CalculatorKeypadHeightTest {

    private val readoutMin = 142.dp
    private val comfortable = 512.dp

    private fun share(available: Float) = (available - 16f) * 2f / 3f

    private fun assertDp(expected: Float, actual: Dp) =
        assertEquals(expected, actual.value, 0.01f)

    /** The live cover on the reference emulator: unchanged, keys stay ≈68dp. */
    @Test
    fun roomToSpareKeepsTheShare() {
        assertDp(share(814f), keypadHeight(814.dp, readoutMin))
    }

    /** Setup on the reference emulator: the readout gives up its empty space. */
    @Test
    fun tightTakesTheReadoutsSpareHeight() {
        val got = keypadHeight(653.dp, readoutMin)
        assertDp(653f - 16f - 142f, got)
        assertTrue("larger than the share", got.value > share(653f))
        assertTrue("no larger than comfortable", got <= comfortable)
    }

    /** Between the two: the keys reach 64dp and the readout keeps the rest. */
    @Test
    fun comfortableBindsWhenTheReadoutHasMoreToGive() {
        assertDp(comfortable.value, keypadHeight(700.dp, readoutMin))
    }

    /** A very short host never gets less keypad than it had before. */
    @Test
    fun veryTightNeverGoesBelowTheShare() {
        assertDp(share(400f), keypadHeight(400.dp, readoutMin))
    }
}
