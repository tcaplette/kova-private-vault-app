package com.app.vault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * Noticing that capture lost its permission — `per-vault-intruder-capture` D8.
 *
 * Android revokes a permission with the app killed or not running, so nothing can record the
 * moment it happened. What the app can know is when it **noticed**: [observe] runs at every start
 * and every return to the foreground, compares `CAMERA` and `RECORD_AUDIO` with the last state it
 * saw, and keeps each change with that moment — the last [CAP]. The first observation only sets
 * the baseline. The keyring's opening turns them into timeline rows (`CaptureIngest.recordPermissions`).
 *
 * `FORMAT.md` §10 item 52 records the residue: a permission turned off or on, and when the app
 * noticed — nothing about which vault, if any, is enrolled.
 */
object CapturePermissions {

    /** One change the app noticed. [permission] and [state] are `TamperLog`'s `PERMISSION_*`. */
    class Transition(val permission: Int, val state: Int, val at: Long)

    private const val PREFS = "capture_permissions"
    private const val KEY_HELD = "held_"          // + permission
    private const val KEY_ASKED = "asked_"        // + permission
    private const val KEY_TRANSITIONS = "transitions"
    private const val CAP = 8

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The Android permission behind each of `TamperLog`'s permission codes. */
    fun manifestName(permission: Int): String =
        if (permission == TamperLog.PERMISSION_CAMERA) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO

    fun held(context: Context, permission: Int): Boolean =
        context.checkSelfPermission(manifestName(permission)) == PackageManager.PERMISSION_GRANTED

    /** The permissions [mode] needs, camera first. */
    fun needed(mode: Capture.Mode): List<Int> = when (mode) {
        Capture.Mode.PHOTO -> listOf(TamperLog.PERMISSION_CAMERA)
        Capture.Mode.AUDIO -> listOf(TamperLog.PERMISSION_MICROPHONE)
        Capture.Mode.VIDEO -> listOf(TamperLog.PERMISSION_CAMERA, TamperLog.PERMISSION_MICROPHONE)
    }

    /** Of [modes], those that need [permission], in the modes' order. */
    fun modesNeeding(permission: Int, modes: Set<Capture.Mode>): List<Capture.Mode> =
        Capture.Mode.entries.filter { it in modes && permission in needed(it) }

    /** The first permission [mode] needs and lacks, or null when it has them all. */
    fun missing(context: Context, mode: Capture.Mode): Int? = needed(mode).firstOrNull { !held(context, it) }

    /**
     * Records each held↔missing change since the last call. The first call only sets the baseline.
     * True when a change was recorded.
     */
    fun observe(context: Context): Boolean {
        val prefs = prefs(context)
        val now = System.currentTimeMillis()
        val list = transitions(context).toMutableList()
        val edit = prefs.edit()
        var changed = false
        var noticed = false
        for (permission in listOf(TamperLog.PERMISSION_CAMERA, TamperLog.PERMISSION_MICROPHONE)) {
            val held = held(context, permission)
            val key = KEY_HELD + permission
            if (prefs.contains(key) && prefs.getBoolean(key, false) == held) continue
            if (prefs.contains(key)) {
                list += Transition(permission, if (held) TamperLog.PERMISSION_RESTORED else TamperLog.PERMISSION_LOST, now)
                noticed = true
            }
            edit.putBoolean(key, held)
            changed = true
        }
        if (!changed) return false
        edit.putString(KEY_TRANSITIONS, list.takeLast(CAP).joinToString(",") { "${it.permission}:${it.state}:${it.at}" })
        edit.apply()
        return noticed
    }

    /** The changes noticed, oldest first. */
    fun transitions(context: Context): List<Transition> =
        prefs(context).getString(KEY_TRANSITIONS, "")!!.split(",").filter { it.isNotEmpty() }.mapNotNull { row ->
            val parts = row.split(":")
            if (parts.size != 3) return@mapNotNull null
            Transition(parts[0].toIntOrNull() ?: return@mapNotNull null, parts[1].toIntOrNull() ?: return@mapNotNull null, parts[2].toLongOrNull() ?: return@mapNotNull null)
        }

    /**
     * Whether Android will no longer show its dialog for [permission] — asked before, refused, and
     * `shouldShowRequestPermissionRationale` false. Then only its settings page grants it (3.3).
     */
    fun wontAsk(activity: android.app.Activity, permission: Int): Boolean =
        !held(activity, permission) && prefs(activity).getBoolean(KEY_ASKED + permission, false) &&
            !activity.shouldShowRequestPermissionRationale(manifestName(permission))

    /** Notes that [permission] was requested, so a later refusal can be told from never-asked. */
    fun markAsked(context: Context, permission: Int) {
        prefs(context).edit().putBoolean(KEY_ASKED + permission, true).apply()
    }

    /** Test-only. */
    fun resetForTest(context: Context) {
        prefs(context).edit().clear().commit()
    }
}
