package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D6's rule — `viewer-swipe-navigation`, task 1.3. */
class VideoResumeTest {

    private val minute = 60_000L

    /** `C.TIME_UNSET`, written out so this stays a JVM test with no Media3 on the path. */
    private val timeUnset = Long.MIN_VALUE + 1

    @Test
    fun theFirstFiveSecondsAreNotKept() {
        assertNull(VideoResume.keep(0, minute))
        assertNull(VideoResume.keep(4_999, minute))
    }

    @Test
    fun fiveSecondsInIsKept() {
        assertEquals(5_000L, VideoResume.keep(5_000, minute))
    }

    @Test
    fun theFinalFiveSecondsClearIt() {
        assertEquals(54_999L, VideoResume.keep(54_999, minute))
        assertNull(VideoResume.keep(55_000, minute))
        assertNull(VideoResume.keep(59_999, minute))
    }

    @Test
    fun theEndClearsIt() {
        assertNull(VideoResume.keep(minute, minute))
    }

    @Test
    fun anUnknownDurationKeepsFromFiveSecondsOn() {
        assertEquals(7_000L, VideoResume.keep(7_000, null))
        assertEquals(7_000L, VideoResume.keep(7_000, timeUnset))
        assertEquals(7_000L, VideoResume.keep(7_000, 0))
        assertNull(VideoResume.keep(4_999, null))
    }

    @Test
    fun aClipShorterThanTenSecondsNeverResumes() {
        (0L..8_000L step 250).forEach { assertNull("at $it ms", VideoResume.keep(it, 8_000)) }
    }
}
