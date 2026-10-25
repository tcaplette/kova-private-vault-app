package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The away epoch — `screen-off-lock-all-screens`, D1.
 *
 * Driven through `onForeground`/`onBackground` with no UI, which is what `AutoLock`'s
 * count was made drivable for. Nothing is open in either case, so the policy never
 * reaches WorkManager or `lockNow` and the JVM needs nothing Android.
 */
class AutoLockEpochTest {

    @Before
    fun settle() {
        AutoLock.awaitingResult(false)
        while (AutoLock.isForeground) AutoLock.onBackground()
    }

    @Test
    fun leavingMovesTheEpochEvenWithNothingOpen() {
        AutoLock.onForeground()
        val before = AutoLock.awayEpoch
        AutoLock.onBackground()
        assertEquals(before + 1, AutoLock.awayEpoch)
    }

    @Test
    fun aPickerRoundTripDoesNotMoveIt() {
        AutoLock.onForeground()
        val before = AutoLock.awayEpoch
        AutoLock.awaitingResult(true)
        AutoLock.onBackground()
        AutoLock.onForeground()
        AutoLock.awaitingResult(false)
        assertEquals(before, AutoLock.awayEpoch)
    }
}
