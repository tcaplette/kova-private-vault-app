package com.app.vault

import com.app.vault.FaceDown.Companion.GRAVITY
import com.app.vault.FaceDown.Companion.HOLD_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * `OUTLINE.md`'s P2-20 Verify, on the JVM — P2-20.
 *
 * Both halves of that Verify are here: "face-down locks within the debounce" and
 * "a pocket-shaped false positive does not fire". The second is the reason the
 * decision was pulled out of `onSensorChanged` at all — inside the listener it is
 * checkable only by putting a phone in a pocket and walking, which means it gets
 * checked once and never again. The third clause, "the listener is unregistered
 * while locked", is about registration rather than the decision and is asserted in
 * `FaceDownDeviceTest`.
 *
 * Streams are fed at the delivery rate the app registers for, `SENSOR_DELAY_UI`, so
 * the timings here are the ones the device sees.
 */
class FaceDownTest {

    /** `SENSOR_DELAY_UI`, in the units the detector is given. */
    private val stepMs = 60L

    /**
     * Feeds a stream and reports how many times it fired and when the first fire
     * landed, so a test can assert both "it fired" and "not before the hold".
     */
    private class Run(val fires: Int, val firstFireAtMs: Long?)

    /**
     * @param sample maps elapsed milliseconds to one (x, y, z) reading.
     */
    private fun run(
        durationMs: Long,
        detector: FaceDown = FaceDown(),
        sample: (Long) -> Triple<Float, Float, Float>,
    ): Run {
        var fires = 0
        var first: Long? = null
        // Deliberately not starting at 0: `nowMs` is an uptime, and a detector that
        // only works when the first sample is at zero would pass here and fail on a
        // device that has been up for a week.
        val base = 8_640_000L
        var t = 0L
        while (t <= durationMs) {
            val (x, y, z) = sample(t)
            if (detector.sample(x, y, z, base + t)) {
                fires++
                if (first == null) first = t
            }
            t += stepMs
        }
        return Run(fires, first)
    }

    /** Perfectly face-down and perfectly still. */
    private fun placed(): Triple<Float, Float, Float> = Triple(0f, 0f, -GRAVITY)

    /** Face-up on a table. */
    private fun faceUp(): Triple<Float, Float, Float> = Triple(0f, 0f, GRAVITY)

    /**
     * Face-down at [degrees] of tilt off flat, still. Gravity split between z and
     * y, so the magnitude stays exactly gravity and only the angle is under test.
     */
    private fun tilted(degrees: Double): Triple<Float, Float, Float> {
        val radians = Math.toRadians(degrees)
        return Triple(0f, (GRAVITY * sin(radians)).toFloat(), (-GRAVITY * cos(radians)).toFloat())
    }

    @Test
    fun heldFaceDownFiresAtTheHoldAndNotBefore() {
        val result = run(durationMs = HOLD_MS + 500L) { placed() }

        assertEquals("a phone placed face down and left must lock", 1, result.fires)
        val at = result.firstFireAtMs!!
        assertTrue("it must not fire before the hold elapses (fired at $at ms)", at >= HOLD_MS)
        assertTrue(
            "it must fire within one sample of the hold (fired at $at ms)",
            at < HOLD_MS + stepMs,
        )
    }

    @Test
    fun releasedBeforeTheHoldNeverFires() {
        val letGoAt = HOLD_MS - 100L
        val result = run(durationMs = 5_000L) { t ->
            if (t < letGoAt) placed() else faceUp()
        }

        assertEquals("a phone picked back up before the hold must not lock", 0, result.fires)
    }

    @Test
    fun faceUpNeverFires() {
        val result = run(durationMs = 30_000L) { faceUp() }

        assertEquals("a phone lying face up must never lock", 0, result.fires)
    }

    @Test
    fun theAngleThresholdHoldsOnBothSides() {
        // 34° of tilt is inside the ~35° the threshold allows; 36° is outside it.
        val inside = run(durationMs = HOLD_MS + 500L) { tilted(34.0) }
        val outside = run(durationMs = HOLD_MS + 500L) { tilted(36.0) }

        assertEquals("34° off flat is still face down", 1, inside.fires)
        assertEquals("36° off flat is not face down", 0, outside.fires)
    }

    /**
     * The half of the Verify that the pure decision exists for.
     *
     * A phone in a trouser pocket hangs roughly on edge and the screen may face
     * either way, so the angle alone does not settle it — the stream below is
     * deliberately built so that it *does* pass the angle test. What it cannot do
     * is be still: walking swings the magnitude several m/s² either side of gravity
     * on every step.
     */
    @Test
    fun aPocketNeverFires() {
        val random = Random(20)
        val result = run(durationMs = 120_000L) { t ->
            // ~2 Hz stride, ±4 m/s² — a walk, not a run.
            val stride = sin(t / 500.0 * Math.PI * 2)
            val jitter = (random.nextDouble() - 0.5) * 1.5
            val magnitude = GRAVITY + (stride * 4.0 + jitter).toFloat()
            Triple(0f, 0f, -magnitude)
        }

        assertEquals("two minutes of walking must not lock the vault", 0, result.fires)
    }

    @Test
    fun faceDownWhileBeingShakenNeverFires() {
        val result = run(durationMs = 30_000L) { t ->
            // Screen-down throughout, but never at rest.
            Triple(3f, 3f, -GRAVITY - (if ((t / stepMs) % 2L == 0L) 3f else -3f))
        }

        assertEquals("a phone face down in a moving hand must not lock", 0, result.fires)
    }

    @Test
    fun aHeldFaceDownFiresExactlyOnce() {
        val result = run(durationMs = 60_000L) { placed() }

        assertEquals("a minute face down is one lock, not a thousand", 1, result.fires)
    }

    @Test
    fun leavingFaceDownAndReturningRearmsIt() {
        val detector = FaceDown()
        val first = run(durationMs = HOLD_MS + 200L, detector = detector) { placed() }
        val away = run(durationMs = 1_000L, detector = detector) { faceUp() }
        val second = run(durationMs = HOLD_MS + 200L, detector = detector) { placed() }

        assertEquals(1, first.fires)
        assertEquals(0, away.fires)
        assertEquals("a second placement must lock again", 1, second.fires)
    }

    @Test
    fun aCompletedHoldIsNotRestartedByStayingPut() {
        // The latch is checked after the hold, not before it: a detector that
        // re-armed on every sample would keep re-serving a hold it already served
        // and fire again HOLD_MS later, forever.
        val result = run(durationMs = HOLD_MS * 5) { placed() }

        assertEquals(1, result.fires)
    }

    @Test
    fun stillnessAloneIsNotEnough() {
        // Perfectly still, on its edge. The magnitude test passes; the angle does not.
        val result = run(durationMs = 30_000L) { Triple(GRAVITY, 0f, 0f) }

        assertFalse("a phone standing on edge must not lock", result.fires > 0)
    }

    // --- Lock after — `face-down-lock-settings` D2 ---------------------------------

    @Test
    fun lockAfterFiresAtTheHoldPlusThePeriodAndNotBefore() {
        val lockAfter = 5_000L
        val result = run(durationMs = HOLD_MS + lockAfter + 500L, detector = FaceDown(lockAfter)) { placed() }

        assertEquals("a phone left face down must lock once the period ends", 1, result.fires)
        val at = result.firstFireAtMs!!
        assertTrue("it must not fire before hold + period (fired at $at ms)", at >= HOLD_MS + lockAfter)
        assertTrue("it must fire within two samples of it (fired at $at ms)", at < HOLD_MS + lockAfter + 2 * stepMs)
    }

    @Test
    fun pickingThePhoneUpDuringLockAfterCancelsIt() {
        val lockAfter = 5_000L
        val detector = FaceDown(lockAfter)
        val liftAt = HOLD_MS + 3_000L
        val first = run(durationMs = 10_000L, detector = detector) { t ->
            if (t < liftAt) placed() else faceUp()
        }
        assertEquals("lifted 3 s into a 5 s period, it must not lock", 0, first.fires)

        // The next placement starts from zero: hold and period both, not what was left of them.
        val second = run(durationMs = HOLD_MS + lockAfter + 500L, detector = detector) { placed() }
        assertEquals(1, second.fires)
        assertTrue(second.firstFireAtMs!! >= HOLD_MS + lockAfter)
    }

    @Test
    fun aKnockDuringLockAfterDoesNotCancelIt() {
        val lockAfter = 5_000L
        val knockFrom = HOLD_MS + 1_000L
        val knockTo = knockFrom + 600L
        val result = run(durationMs = HOLD_MS + lockAfter + 500L, detector = FaceDown(lockAfter)) { t ->
            // Still screen-down, but jolted well outside the stillness band for 0.6 s.
            if (t in knockFrom until knockTo) {
                Triple(0f, 0f, -GRAVITY - (if ((t / stepMs) % 2L == 0L) 4f else -4f))
            } else {
                placed()
            }
        }

        assertEquals("a knock on the table must not cancel a phone still lying there", 1, result.fires)
        assertTrue(result.firstFireAtMs!! >= HOLD_MS + lockAfter)
    }

    @Test
    fun aKnockDuringTheHoldStillResetsIt() {
        // Stillness is still the hold's test; only Lock after stops asking it.
        val knockAt = HOLD_MS - 200L
        val result = run(durationMs = HOLD_MS * 4) { t ->
            if (t in knockAt until knockAt + 2 * stepMs) Triple(0f, 0f, -GRAVITY - 6f) else placed()
        }

        assertEquals(1, result.fires)
        assertTrue("the hold restarts after the knock", result.firstFireAtMs!! >= knockAt + HOLD_MS)
    }
}
