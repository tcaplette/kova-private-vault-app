package com.app.vault

import kotlin.math.sqrt

/**
 * Whether the device has been held screen-down long enough to lock — P2-20.
 *
 * **Pure, and deliberately so.** The rule is four constants, two filters and a
 * latch, and none of them need a device. Buried inside `onSensorChanged` its
 * harder half — "a pocket-shaped false positive does not fire" — would be
 * checkable only by putting a phone in a pocket and walking, which means it gets
 * checked once and never again.
 *
 * A **class** rather than an object because it carries state. One instance is
 * created per registration and discarded with it, so a lock/unlock cycle cannot
 * inherit a half-elapsed hold from the session before it.
 *
 * No Android import in this file and no logging — `FaceDownScanTest` asserts both.
 * Time arrives as a plain [Long]; this holds no opinion about where it came from,
 * only that it moves forward.
 *
 * **Nothing here is calibrated to a handset.** Both tests below are invariant to
 * the sensor's zero offset, which is what an earlier version got wrong: it asked
 * whether the acceleration magnitude sat within a band of a hardcoded 9.81, and a
 * part whose factory bias put its resting magnitude outside that band failed that
 * test on every sample, forever, with no pose able to satisfy it.
 */
class FaceDown(
    /**
     * Face-down's **Lock after**, in ms — `face-down-lock-settings` D2. A grace period that starts
     * when the hold completes and runs while the angle holds; 0 fires on the hold's completing
     * sample, which is what this did before the setting existed.
     */
    private val lockAfterMs: Long = 0L,
) {

    /**
     * The low-pass estimate of the gravity vector, and whether it holds a reading.
     *
     * Seeded from the first sample of each face-down entry rather than converged
     * towards: an exponential average crossing from face-up to face-down needs
     * about a second to settle, and paying that before the hold even starts is a
     * second of a deliberate flip doing nothing.
     */
    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var seeded = false

    /**
     * How far the raw sample is running from the gravity estimate, smoothed.
     *
     * This is the stillness measure, and it is a *difference between two readings
     * of the same sensor*, so any constant zero offset the part carries appears in
     * both terms and cancels. Smoothed rather than read raw so that one stray
     * sample — a haptic buzz, a knock on the same table — costs a fraction of the
     * hold instead of all of it.
     */
    private var motion = 0f

    /**
     * When the face-down condition began, or 0 while it does not hold.
     *
     * Not nullable: `nowMs` is a monotonic uptime, which is never 0 in a running
     * process, and a sentinel here reads the same as the null would.
     */
    private var since = 0L

    /**
     * False from the moment a lock is reported until the device leaves face-down.
     *
     * The listener unregisters microseconds after a fire, so in the app this never
     * matters. It matters in [FaceDownTest], where the class is driven standalone
     * and must not report a lock on every sample of a phone lying on a table.
     */
    private var armed = true

    /**
     * When the hold completed, or 0 while it has not. From here only the **angle** is asked:
     * a knock on the table during Lock after must not cancel a phone still lying there, and
     * the phone being picked up must.
     */
    private var heldAt = 0L

    /**
     * One accelerometer sample.
     *
     * @param nowMs a monotonic clock. The wall clock moving backwards is a
     *   condition [DeviceEvents] already watches for, and it must not be able to
     *   lengthen, shorten or skip a hold.
     * @return true **exactly once**, on the sample where the hold completes.
     */
    fun sample(x: Float, y: Float, z: Float, nowMs: Long): Boolean {
        if (seeded) {
            gx = ALPHA * gx + (1f - ALPHA) * x
            gy = ALPHA * gy + (1f - ALPHA) * y
            gz = ALPHA * gz + (1f - ALPHA) * z
            val drift = sqrt(sq(x - gx) + sq(y - gy) + sq(z - gz))
            motion = ALPHA * motion + (1f - ALPHA) * drift
        } else {
            gx = x
            gy = y
            gz = z
            motion = 0f
            seeded = true
        }

        if (!pointsDown() || (heldAt == 0L && motion > MOTION_BAND)) {
            since = 0L
            heldAt = 0L
            armed = true
            // Dropped with the hold, so the next entry starts from the pose it
            // actually arrives in rather than converging out of the last one.
            seeded = false
            return false
        }

        if (heldAt == 0L) {
            if (since == 0L) since = nowMs
            if (nowMs - since < HOLD_MS) return false
            heldAt = nowMs
        }

        // `armed` is checked after the period, not before it, so a device that stays
        // put keeps a completed hold rather than restarting one it already served.
        if (nowMs - heldAt < lockAfterMs || !armed) return false

        armed = false
        return true
    }

    /**
     * The angle half of screen-down. The stillness half is `motion <= MOTION_BAND`, asked in
     * [sample] during the hold and not during Lock after; either one failing during the hold
     * resets it to zero.
     *
     * The angle is read off the filtered vector as a **direction**, normalised, so
     * a part reading heavy or light is asked the same question as a part reading
     * true: how far off flat is it, not how many m/s² is it.
     *
     * The stillness half is what rejects a pocket, not the angle. Android's z axis
     * is defined against the screen face and is unaffected by display rotation, so
     * a pocket that happens to hang screen-inward reads as face-down on the angle
     * alone. What it cannot fake is being still: walking moves the reading several
     * m/s² away from its own average on every step. Raising the angle instead would
     * reject the real case — a phone laid on something soft is not at a perfect
     * 180° — while still admitting the hanging pocket.
     */
    private fun pointsDown(): Boolean {
        val magnitude = sqrt(sq(gx) + sq(gy) + sq(gz))
        // Free fall, or a sensor reporting nothing usable. Neither is a pose.
        if (magnitude < GRAVITY / 2f) return false
        return gz / magnitude <= Z_COS
    }

    private fun sq(v: Float): Float = v * v

    companion object {

        /** Standard gravity. Not `SensorManager.STANDARD_GRAVITY`, which is an Android type. */
        const val GRAVITY = 9.81f

        /**
         * The z axis points out of the screen face, so screen-down reads negative.
         * A **cosine**, not an acceleration: this is the normalised z component, so
         * `-0.8155` is **≈35° of tilt** from perfectly face-down whatever the part's
         * zero offset. A phone on a table, a cushion, a coat or a stack of paper is
         * inside it; a phone on its edge or in a stand is not.
         */
        const val Z_COS = -0.8155f

        /**
         * How far the reading may run from its own smoothed average and still count
         * as placed rather than carried. A resting surface holds it within a few
         * hundredths; a walking pocket leaves it by several m/s² on every step, so
         * the band sits an order of magnitude clear of both.
         *
         * ponytail: a steady hand holding the phone overhead — reading in bed — can
         * sit inside this band, and will lock after [HOLD_MS] and Lock after. Separating
         * held from placed needs a variance window over several seconds, which is more
         * state and a second thing that can be mistuned. The feature ships off, so the
         * ceiling is opt-in; add the variance window if it turns out to matter.
         */
        const val MOTION_BAND = 0.6f

        /**
         * The smoothing weight both filters use — about a 200 ms time constant at
         * the listener's sample rate.
         *
         * One constant and not two: the gravity estimate and the motion estimate
         * want the same thing from it, which is to ride out a single bad sample
         * without riding out a step.
         */
        const val ALPHA = 0.75f

        /**
         * How long the condition must hold before a lock — or before Lock after starts.
         *
         * Measured on a Samsung A53 and a Pixel 7, 2026-10-11 (`face-down-lock-settings`): both
         * were at rest well inside half a second of landing, the landing jolt resetting the hold
         * for at most 0.25 s. At 1 500 ms the lock landed about 2 s after the flip began, which
         * the owner found too slow; at 500 it lands at about 1 s, Pixel's Flip to Shhh's pace.
         * Not 250: the Pixel's landing jolt alone lasted that long. Fixed — the user's choice is
         * Lock after, which comes after this, not instead of it.
         */
        const val HOLD_MS = 500L
    }
}
