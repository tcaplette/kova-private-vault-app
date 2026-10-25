package com.app.vault

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log

/**
 * The accelerometer listener behind face-down auto-lock — P2-20.
 *
 * A **second trigger for the mechanism [AutoLock] already drives**, not a second
 * mechanism: it calls the same [VaultLifecycle.lockNow] the Lock button and
 * backgrounding call, so the in-memory key, the thumbnail cache and the tamper log
 * end in one state and not three.
 *
 * Every part of the decision lives in [FaceDown], which has no Android type in it.
 * Nothing here knows what a threshold is.
 *
 * **The registration window is the whole suppression story.** This listens only
 * while the keyring or a vault is open, the preference is on, *and* the activity is
 * resumed.
 * P1-10's two suppressions — a picker this app launched, and in-flight import or
 * export work — both leave the activity not resumed, so face-down is already off
 * during both and cannot park an unattended import on `ImportWorker`'s
 * wait-for-unlock branch. No third suppression case exists, and none is needed.
 *
 * Scoping to *resumed* costs the feature nothing: turning a phone over pauses no
 * activity. Nothing in Android takes focus for a change of pose.
 */
object FaceDownLock : SensorEventListener {

    private const val TAG = "FaceDownLock"

    private var sensors: SensorManager? = null
    private var detector: FaceDown? = null

    /**
     * What to do when the hold completes — supplied by the caller, not chosen here.
     *
     * **Not [VaultLifecycle.lockNow] directly.** That is the mechanism, and calling
     * it from the foreground would zero the key and clear the cache while the grid
     * is still on screen showing rows nothing can decrypt — the activity only reads
     * `VaultSession.isUnlocked` back on `onStart`, which is why [AutoLock] can call
     * the mechanism and this cannot. The caller passes the same lock its Lock button
     * calls, which reaches `lockNow` through the identical path and takes the screen
     * with it.
     *
     * Cleared by [stop], so this process-lifetime object never outlives a reference
     * to the activity that supplied it.
     */
    private var onLock: (() -> Unit)? = null

    /** For tests, and for the one branch [start] needs: is a listener registered. */
    val isListening: Boolean get() = detector != null

    /** Whether the device can do this at all — the drawer row's gate. */
    fun available(context: Context): Boolean =
        manager(context)?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    /**
     * Register, if all three conditions hold. Idempotent: calling it while already
     * listening does nothing rather than registering twice.
     *
     * "Open" is the keyring **or** a vault — the same test [VaultLifecycle.lockNow]
     * uses. The keyring alone would miss a vault opened by recovery or archive
     * import, which never opens it; the vault alone misses the vault list, where
     * every unlock lands.
     *
     * Conditions are checked **before** the idempotency guard, so a call made after
     * the preference was turned off unregisters rather than returning early.
     */
    fun start(context: Context, onLock: () -> Unit) {
        val open = VaultKeyringSession.isOpen || VaultSession.isUnlocked
        if (!open || !GridPreference.faceDown(context)) {
            stop()
            return
        }
        if (isListening) return

        val sensorManager = manager(context) ?: return
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return

        // A fresh detector per registration: a half-elapsed hold from the previous
        // unlocked session must not carry into this one. Lock after is read here, so a
        // change takes effect on the next registration — `MainActivity` keys its effect
        // on the value for that.
        detector = FaceDown(GridPreference.faceDownLockAfter(context))
        this.onLock = onLock
        sensors = sensorManager
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    /** Unregister, drop the detector, and release the caller's lock. */
    fun stop() {
        sensors?.unregisterListener(this)
        sensors = null
        detector = null
        onLock = null
    }

    /**
     * `SENSOR_DELAY_UI` — about 60 ms a sample, two orders of magnitude below the
     * 200 Hz that `HIGH_SAMPLING_RATE_SENSORS` governs, so still no permission. The
     * cheaper `SENSOR_DELAY_NORMAL` would add up to a fifth of a second past the
     * hold before the lock landed, which turns "locks within the debounce" into an
     * approximation.
     *
     * [SystemClock.elapsedRealtime] and not the wall clock: the clock moving
     * backwards is a condition [DeviceEvents] already watches for, and it must not
     * be able to lengthen or skip a hold. Not `SensorEvent.timestamp` either —
     * vendors disagree about its base.
     */
    override fun onSensorChanged(event: SensorEvent) =
        onSample(event.values[0], event.values[1], event.values[2], SystemClock.elapsedRealtime())

    /**
     * One sample, past the platform.
     *
     * `SensorEvent` has no public constructor, so a test cannot deliver one — this
     * is the seam that makes the trigger drivable end to end without shaking an
     * emulator. `AutoLock.onForeground`/`onBackground` take no `Activity` for the
     * identical reason; this is that decision again.
     */
    fun onSample(x: Float, y: Float, z: Float, nowMs: Long) {
        if (detector?.sample(x, y, z, nowMs) != true) return

        // Read before `stop` clears it. No tamper event naming the trigger — a lock
        // that records *how* it was triggered is a fact about the user's hands, and
        // P2-20 asked for a lock.
        val lock = onLock
        stop()
        lock?.invoke()
        Log.i(TAG, "locked face down")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun manager(context: Context): SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
}
