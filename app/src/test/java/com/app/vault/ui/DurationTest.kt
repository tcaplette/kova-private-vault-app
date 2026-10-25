package com.app.vault.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [durationLabel]'s boundaries — P2-8.
 *
 * The function it replaced was `= "--:--"`, so there was nothing to test and
 * nothing failed while every video in the app showed a placeholder. These are the
 * cases where a duration formatter is actually wrong: the minute and hour
 * rollovers, and the absent value.
 */
class DurationTest {

    @Test
    fun unknownDrawsNoLabelAtAll() {
        assertNull("null is 'not known', not 'zero'", durationLabel(null))
    }

    @Test
    fun zeroAndNegativeDrawNoLabel() {
        // A container declaring 0 has told us nothing, and "0:00" would read as a
        // real zero-length video. Negative is not reachable through the importer,
        // but a formatter that renders "-1:-1" for it is one bad extractor away.
        assertNull(durationLabel(0L))
        assertNull(durationLabel(-1L))
    }

    @Test
    fun subSecondRoundsDownToZeroSeconds() {
        assertEquals("0:00", durationLabel(999L))
    }

    @Test
    fun secondsPadToTwoDigits() {
        assertEquals("0:01", durationLabel(1_000L))
        assertEquals("0:09", durationLabel(9_999L))
        assertEquals("0:59", durationLabel(59_999L))
    }

    @Test
    fun theMinuteRollover() {
        assertEquals("1:00", durationLabel(60_000L))
        assertEquals("1:01", durationLabel(61_000L))
        assertEquals("9:59", durationLabel(599_000L))
        assertEquals("10:00", durationLabel(600_000L))
    }

    @Test
    fun theHourRollover() {
        assertEquals("59:59", durationLabel(3_599_000L))
        assertEquals("1:00:00", durationLabel(3_600_000L))
        assertEquals("1:00:01", durationLabel(3_601_000L))
        assertEquals("1:01:00", durationLabel(3_660_000L))
    }

    @Test
    fun pastAnHourMinutesAlsoPad() {
        // The bug this catches is formatting "2:5:07" for two hours five minutes.
        assertEquals("2:05:07", durationLabel(7_507_000L))
        assertEquals("12:00:00", durationLabel(43_200_000L))
    }

    @Test
    fun everyLabelIsTabularSafe() {
        // Nothing but digits and colons, so `tnum` keeps the width stable and the
        // grid tile does not reflow as a video plays or a list scrolls.
        listOf(1_000L, 61_000L, 3_601_000L, 43_200_000L).forEach { ms ->
            val label = durationLabel(ms)!!
            assertEquals(
                "unexpected character in \"$label\"",
                "",
                label.filterNot { it.isDigit() || it == ':' },
            )
        }
    }
}
