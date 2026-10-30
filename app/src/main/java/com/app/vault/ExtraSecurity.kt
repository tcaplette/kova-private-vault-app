package com.app.vault

import android.content.Context

/**
 * The Extra Security switch — `recovery-practice` D7.
 *
 * **On by default.** While on, [PassphrasePractice] asks a fingerprint user for the
 * list's passphrase on its schedule, and every kit shown at creation is followed by
 * the kit check. First run's kit check runs whatever this says, because the menu
 * that holds the switch cannot be reached before it. The printed kit is the same
 * either way.
 *
 * One boolean for the device in [GridPreference]'s file, on that file's I8 terms.
 */
object ExtraSecurity {

    private const val KEY = "extra_security"

    fun isOn(context: Context): Boolean =
        GridPreference.prefs(context).getBoolean(KEY, true)

    fun set(context: Context, on: Boolean) {
        GridPreference.prefs(context).edit().putBoolean(KEY, on).apply()
    }
}
