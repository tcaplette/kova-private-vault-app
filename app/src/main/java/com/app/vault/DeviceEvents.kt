package com.app.vault

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.File
import java.security.MessageDigest

/**
 * The device conditions the tamper log records — P2-5.
 *
 * Five things that happen *around* the app rather than in it: developer options or
 * USB debugging switched on, the device's root verdict changing, signs of an
 * app-data clear, the vault running on a device other than the one it was set up
 * on, and the clock moving backwards. Detected once per launch, while the vault is
 * locked, and — like a failed unlock — held pending and flushed to the log when a
 * vault next opens.
 *
 * **Warn and record, never block** (P1-10). Nothing in the app branches on a
 * finding except the recorder and the digest that shows it. Every one of these is a
 * heuristic; the log says "appears to", and the checks are disclosure to the owner,
 * not defence against an attacker.
 *
 * The decision is a pure function, [evaluate], over a baseline and the current
 * state. That is the whole of the Verify — each event reproduces by constructing
 * the two states, with no camera, no UI, and no dependence on the emulator's real
 * ADB or root state.
 *
 * **Two survivorships do the work.** The `SharedPreferences` baseline is app data:
 * it is wiped by a clear and travels with a data restore. The [InstallAnchor] is a
 * device-bound Keystore alias: it survives a clear and does *not* travel to another
 * device. Their four combinations name the two events an app cannot otherwise see:
 *
 * | anchor  | baseline | meaning            |
 * |---------|----------|--------------------|
 * | absent  | absent   | first run          |
 * | present | absent   | app-data clear     |
 * | absent  | present  | new-device restore |
 * | present | present  | ordinary run       |
 */
object DeviceEvents {

    private const val PREFS = "device_events"
    private const val KEY_INIT = "init"
    private const val KEY_ADB = "adb"
    private const val KEY_DEV = "dev"
    private const val KEY_ROOT = "root"
    private const val KEY_FINGERPRINT = "fp"
    private const val KEY_HIGH_TIME = "high"

    /** Below this much backwards is a rollback; above it, NTP jitter this ignores. */
    const val CLOCK_TOLERANCE_MS = 5 * 60 * 1000L

    /** A device snapshot — the four flags and the wall clock at one instant. */
    class State(
        val adb: Boolean,
        val dev: Boolean,
        val root: Boolean,
        val fingerprint: String,
        val time: Long,
    )

    /** The last-seen state, or null before the first launch has written one. */
    class Baseline(
        val adb: Boolean,
        val dev: Boolean,
        val root: Boolean,
        val fingerprint: String,
        val high: Long,
    )

    /**
     * The truth table, pure. Given the last baseline (or null), the current state,
     * and the two survivorship facts, returns the events to record.
     */
    fun evaluate(
        baseline: Baseline?,
        current: State,
        anchorPresent: Boolean,
        vaultExists: Boolean,
    ): Set<Int> {
        val events = linkedSetOf<Int>()

        if (baseline == null) {
            // First run or a clear: no prior state to compare. The anchor tells them
            // apart — it survives a clear, so its presence with the data gone is the
            // signature of one. A vault still on disk means this is not a clear.
            if (anchorPresent && !vaultExists) events += TamperLog.DATA_CLEAR_DETECTED
            return events
        }

        // The anchor is device-bound and does not travel; a baseline that arrived
        // without it came from another device. The fingerprint confirms it.
        if (!anchorPresent) events += TamperLog.NEW_DEVICE_RESTORE
        if (current.fingerprint != baseline.fingerprint) events += TamperLog.NEW_DEVICE_RESTORE

        // "Newly": only a 0 → 1 against the baseline, so leaving it on does not re-fire.
        if ((current.adb && !baseline.adb) || (current.dev && !baseline.dev)) {
            events += TamperLog.DEV_OPTIONS_ENABLED
        }
        if (current.root != baseline.root) events += TamperLog.ROOT_STATUS_CHANGED
        if (current.time < baseline.high - CLOCK_TOLERANCE_MS) events += TamperLog.CLOCK_ROLLBACK

        return events
    }

    /**
     * Reads the device and the stored baseline, evaluates, then updates the baseline
     * and ensures the anchor exists. Returns the events for the caller to record
     * through [TamperLog.recordDeviceEvent]. Runs while the vault is locked.
     */
    fun check(context: Context): Set<Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = State(
            adb = flagOn(context, Settings.Global.ADB_ENABLED),
            dev = flagOn(context, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
            root = RuntimeWarnings.isRooted(),
            fingerprint = fingerprint(),
            time = System.currentTimeMillis(),
        )
        val baseline = readBaseline(prefs)
        val anchor = InstallAnchor.exists()
        val vaultExists = File(context.filesDir, "vault.slots").exists()

        val events = evaluate(baseline, current, anchor, vaultExists)

        // Ensure the anchor for next time — creating it on a first run and recreating
        // it on the new device after a restore. Idempotent.
        InstallAnchor.createIfAbsent()
        writeBaseline(prefs, current, high = maxOf(baseline?.high ?: current.time, current.time))
        return events
    }

    // --- baseline i/o ----------------------------------------------------------

    private fun readBaseline(prefs: android.content.SharedPreferences): Baseline? {
        if (!prefs.getBoolean(KEY_INIT, false)) return null
        return Baseline(
            adb = prefs.getBoolean(KEY_ADB, false),
            dev = prefs.getBoolean(KEY_DEV, false),
            root = prefs.getBoolean(KEY_ROOT, false),
            fingerprint = prefs.getString(KEY_FINGERPRINT, "") ?: "",
            high = prefs.getLong(KEY_HIGH_TIME, 0L),
        )
    }

    private fun writeBaseline(prefs: android.content.SharedPreferences, s: State, high: Long) {
        prefs.edit()
            .putBoolean(KEY_INIT, true)
            .putBoolean(KEY_ADB, s.adb)
            .putBoolean(KEY_DEV, s.dev)
            .putBoolean(KEY_ROOT, s.root)
            .putString(KEY_FINGERPRINT, s.fingerprint)
            .putLong(KEY_HIGH_TIME, high)
            .apply()
    }

    private fun flagOn(context: Context, name: String): Boolean =
        Settings.Global.getInt(context.contentResolver, name, 0) == 1

    /** A hash of the build fingerprint, so the baseline stores an identifier, not the raw string. */
    private fun fingerprint(): String {
        val raw = (Build.FINGERPRINT ?: "").toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
    }

    /** Test-only: a cleared baseline is a fresh install (paired with [InstallAnchor.deleteForTest]). */
    fun resetForTest(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
