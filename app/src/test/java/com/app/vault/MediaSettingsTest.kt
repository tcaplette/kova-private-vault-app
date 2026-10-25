package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `media-settings` — the defaults, the stored speed, and the quality-off arithmetic. */
class MediaSettingsTest {

    @Test
    fun theDefaultsAreWhatTheViewerDidBefore() {
        val d = MediaSettings()
        assertFalse("the screen keeps its usual timer", d.keepScreenOn)
        assertFalse("the phone's brightness is left alone", d.fullBrightness)
        assertTrue("small photos are enlarged to fit, as they always were", d.stretchSmall)
        assertTrue("photos decode at full resolution, as they always did", d.fullQuality)
        assertEquals(PlaybackSpeed.Normal, d.speed)
        assertTrue("videos resume where they stopped, as they did", d.resume)
        assertFalse("a video stops at its end, as it did", d.repeat)
        assertEquals("the double tap skips ten seconds, as it did", 10_000L, d.skip.ms)
        assertEquals("videos follow the phone, as they did", VideoOrientation.Auto, d.orientation)
    }

    @Test
    fun unknownSkipAndOrientationNamesReadTheDefault() {
        assertEquals(SkipLength.Ten, SkipLength.of("Sixty"))
        assertEquals(SkipLength.Ten, SkipLength.of(null))
        assertEquals(SkipLength.Thirty, SkipLength.of("Thirty"))
        assertEquals(VideoOrientation.Auto, VideoOrientation.of("Portrait"))
        assertEquals(VideoOrientation.Landscape, VideoOrientation.of("Landscape"))
    }

    @Test
    fun theFourSkipLengthsAreInOrder() {
        assertEquals(listOf(5, 10, 15, 30), SkipLength.entries.map { it.seconds })
    }

    @Test
    fun anUnknownSpeedReadsNormal() {
        assertEquals(PlaybackSpeed.Normal, PlaybackSpeed.of("Fast300"))
        assertEquals(PlaybackSpeed.Normal, PlaybackSpeed.of(null))
        assertEquals(PlaybackSpeed.Fast150, PlaybackSpeed.of("Fast150"))
    }

    @Test
    fun theSixSpeedsAreInOrder() {
        assertEquals(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), PlaybackSpeed.entries.map { it.factor })
    }

    @Test
    fun theSampleKeepsTheLongEdgeAtOrAboveTheScreen() {
        // Halving 4000 gives 2000, below a 2400 screen, so nothing is taken.
        assertEquals(1, screenSample(4000, 3000, 2400))
        // A 48 MP photo halves once: 4000 is still above 2400, 2000 would not be.
        assertEquals(2, screenSample(8000, 6000, 2400))
        // Exactly at the screen's long edge is still at or above it.
        assertEquals(4, screenSample(4000, 3000, 1000))
        // A photo already smaller than the screen is decoded whole.
        assertEquals(1, screenSample(800, 600, 2400))
        assertEquals(1, screenSample(4000, 3000, 0))
    }
}
