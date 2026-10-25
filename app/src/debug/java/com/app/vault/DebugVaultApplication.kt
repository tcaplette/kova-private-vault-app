package com.app.vault

/**
 * The debug build's [android.app.Application] — `debug-crash-log`.
 *
 * It exists to install [DebugCrashLog] and to do nothing else. It **extends** [VaultApplication]
 * rather than replacing it, so `FLAG_SECURE` and [AutoLock] are still registered, from the same
 * `onCreate`, in the same order — the debug build and the release build differ by a crash handler
 * and by nothing else.
 *
 * Wired through `src/debug/AndroidManifest.xml`, which merges only into the debug APK. The release
 * manifest still names [VaultApplication], and neither this class nor [DebugCrashLog] is compiled
 * into the release variant at all.
 */
class DebugVaultApplication : VaultApplication() {

    override fun onCreate() {
        // Before super, not after. super.onCreate() registers lifecycle callbacks and touches
        // AutoLock; a throw in there is exactly the crash worth having a record of, and a handler
        // installed afterwards would miss it.
        DebugCrashLog.install(this)
        super.onCreate()
    }
}
