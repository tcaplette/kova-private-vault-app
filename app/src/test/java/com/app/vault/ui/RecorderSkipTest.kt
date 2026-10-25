package com.app.vault.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The voice recorder's 15-second skip — `voice-recorder-layout`: exact mid-recording, clamped at both ends. */
class RecorderSkipTest {

    @Test
    fun midRecordingMovesExactlyFifteenSeconds() {
        assertEquals(45_000L, skipTarget(30_000L, 15_000L, 60_000L))
        assertEquals(15_000L, skipTarget(30_000L, -15_000L, 60_000L))
    }

    @Test
    fun backNearTheStartClampsToZero() {
        assertEquals(0L, skipTarget(4_000L, -15_000L, 60_000L))
    }

    @Test
    fun forwardNearTheEndClampsToTheDuration() {
        assertEquals(60_000L, skipTarget(52_000L, 15_000L, 60_000L))
    }

    @Test
    fun zeroDurationStaysAtZero() {
        assertEquals(0L, skipTarget(0L, 15_000L, 0L))
        assertEquals(0L, skipTarget(0L, -15_000L, 0L))
    }
}
