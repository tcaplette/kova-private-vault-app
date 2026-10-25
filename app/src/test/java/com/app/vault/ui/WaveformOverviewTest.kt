package com.app.vault.ui

import com.app.vault.OVERVIEW_BUCKETS
import com.app.vault.LevelRing
import com.app.vault.WaveformOverview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorded waveform's memory — `voice-memo-layout`, D3.
 *
 * `voice-memo` promises a memo of any length records without memory growing with its
 * duration. That promise has to hold of the screen as well as the audio, and this is
 * the one structure on the screen that sees every reading.
 */
class WaveformOverviewTest {

    @Test
    fun anyLengthYieldsAtMostTheBucketCount() {
        for (readings in listOf(10, OVERVIEW_BUCKETS, 100_000)) {
            val o = WaveformOverview()
            repeat(readings) { o.add(0.5f) }
            val bars = o.bars().size
            assertTrue("$readings readings drew $bars bars", bars in 1..OVERVIEW_BUCKETS)
        }
    }

    @Test
    fun aShortMemoKeepsOneBarPerReading() {
        val o = WaveformOverview()
        repeat(10) { o.add(it / 10f) }
        assertEquals(10, o.bars().size)
        assertEquals(0.9f, o.bars().last(), 0f)
    }

    @Test
    fun aSingleLoudReadingSurvivesEveryMerge() {
        val o = WaveformOverview()
        repeat(50_000) { o.add(if (it == 31_337) 1f else 0.1f) }
        assertEquals(
            "a bucket keeps its loudest reading, so a shout forty minutes in is still drawn",
            1f, o.bars().max(), 0f,
        )
    }

    @Test
    fun theBackingArrayNeverGrows() {
        val o = WaveformOverview()
        val before = o.capacity
        repeat(1_000_000) { o.add(0.3f) }
        assertEquals(before, o.capacity)
        assertEquals(OVERVIEW_BUCKETS, o.capacity)
    }

    @Test
    fun clearStartsOver() {
        val o = WaveformOverview()
        repeat(500) { o.add(1f) }
        o.clear()
        o.add(0.2f)
        assertEquals(1, o.bars().size)
        assertEquals(0.2f, o.bars()[0], 0f)
    }

    @Test
    fun theLiveRingReturnsTheNewestFirst() {
        val r = LevelRing(4)
        listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f).forEach(r::add)
        assertEquals(4, r.size)
        assertEquals(0.5f, r.recent(0), 0f)
        assertEquals(0.2f, r.recent(3), 0f)
    }

    @Test
    fun theRecordedWaveformAlwaysFillsTheWidth() {
        assertEquals("a short memo is stretched, not drawn as far-apart dots", 76, resample(FloatArray(3) { 0.5f }, 76).size)
        assertEquals(76, resample(FloatArray(96) { it / 96f }, 76).size)
        assertEquals("the loudest reading survives pooling", 1f, resample(FloatArray(96) { if (it == 50) 1f else 0f }, 10).max(), 0f)
        assertEquals(0, resample(FloatArray(0), 76).size)
    }
}
