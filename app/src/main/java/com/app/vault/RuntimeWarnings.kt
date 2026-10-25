package com.app.vault

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import android.view.accessibility.AccessibilityManager
import java.io.File

/**
 * What the app can see about the device it is running on — P1-10.
 *
 * **Warn, never block.** `OUTLINE.md` says so, and it is the right call twice
 * over: none of these checks is reliable enough to gate on, and a vault that
 * refuses to open on a rooted phone is a vault that has decided its owner is the
 * threat. Nothing in this app branches on a finding except the code that draws
 * the text.
 *
 * No Compose and no Activity here, so an instrumented test can drive it
 * directly.
 */
object RuntimeWarnings {

    /**
     * One finding, already written as the sentence the user reads. There is no
     * severity, no code, and no category, because nothing consumes those — the
     * only consumer draws the string.
     */
    @JvmInline
    value class Finding(val message: String)

    /**
     * Paths a rooted device usually has. A heuristic list, and worth saying
     * plainly: anyone who wants to hide root from an app can, and this check is
     * disclosure to the *user*, not defence against an attacker. It exists so a
     * person who did not know their device was rooted finds out before they type
     * a passphrase into it.
     */
    private val SU_PATHS = listOf(
        "/sbin/su", "/system/bin/su", "/system/xbin/su", "/system/sbin/su",
        "/vendor/bin/su", "/su/bin/su", "/data/local/su", "/data/local/bin/su",
        "/data/local/xbin/su",
    )

    /** Magisk's usual homes. Same caveat as [SU_PATHS]. */
    private val MAGISK_PATHS = listOf(
        "/sbin/.magisk", "/data/adb/magisk", "/data/adb/modules", "/system/app/Magisk",
    )

    fun check(context: Context): List<Finding> {
        val findings = mutableListOf<Finding>()

        if (isRooted()) {
            findings += Finding(context.getString(R.string.warning_rooted))
        }

        if (isDebuggable(context)) {
            findings += Finding(context.getString(R.string.warning_debuggable))
        }

        if (Debug.isDebuggerConnected()) {
            findings += Finding(context.getString(R.string.warning_debugger))
        }

        val services = enabledAccessibilityServices(context)
        if (services > 0) {
            findings += Finding(
                // Stated as capability, not accusation. A screen reader is a
                // legitimate reason to see the screen, and its user knows why it
                // is on; someone who does not know is the reason this line exists.
                context.resources.getQuantityString(R.plurals.warning_accessibility, services, services)
            )
        }

        return findings
    }

    /**
     * The root heuristic, shared. P2-5's `DeviceEvents` records a *change* in this
     * verdict rather than duplicating the path list — one heuristic, one place to
     * keep current.
     */
    internal fun isRooted(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        if (SU_PATHS.any { File(it).exists() }) return true
        if (MAGISK_PATHS.any { File(it).exists() }) return true
        return pathHasSu()
    }

    /** `su` anywhere on `PATH`, which catches installs the fixed list above misses. */
    private fun pathHasSu(): Boolean =
        (System.getenv("PATH") ?: "").split(':')
            .any { it.isNotBlank() && File(it, "su").exists() }

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun enabledAccessibilityServices(context: Context): Int {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return 0
        // FEEDBACK_ALL_MASK: a service that speaks, one that shows text, and one
        // that does neither all read the same screen.
        return manager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).size
    }
}
