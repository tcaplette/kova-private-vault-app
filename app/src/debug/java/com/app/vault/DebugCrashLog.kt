package com.app.vault

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Debug-build crash log — `debug-crash-log`.
 *
 * Logcat is the only record of a crash today, and it is a ring buffer: shared with every other app
 * on the device, small, and empty after a reboot. A crash noticed an hour ago with no terminal open
 * is not recoverable, which is exactly the intermittent kind worth catching before an upload. This
 * writes one plain-text file per uncaught exception into the app's own storage, where it stays until
 * somebody pulls it.
 *
 * **This class exists only in the debug source set**, and that is the whole of what makes it
 * permissible. `OUTLINE.md` I2 forbids analytics, telemetry and crash-reporting SDKs because a
 * third-party data channel inside a privacy product is the thing the product claims not to have.
 * This is not that: no dependency, no socket, no `INTERNET`, and no presence in the release APK. It
 * is also why the record is written in the clear (see [write]) — a `BuildConfig.DEBUG` branch in
 * `src/main` would have shipped the class and only branched around it, and a class that is not in
 * the APK is a stronger claim than a class that is.
 *
 * Retrieval, which works because and only because the build is debuggable:
 *
 * ```
 * adb -s emulator-5554 shell run-as com.app.vault ls -l files/crashes
 * adb -s emulator-5554 exec-out run-as com.app.vault cat files/crashes/<name> > <name>
 * ```
 *
 * **What this does not catch.** JVM exceptions only. A SIGSEGV in lazysodium's or JNA's native code
 * never reaches a Java handler, and an ANR is not an exception at all; both go to
 * `/data/tombstones` and `/data/anr`, read with `adb bugreport`. An empty crashes directory after a
 * hard failure means "not this kind of failure", not "nothing happened".
 */
class DebugCrashLog private constructor(
    private val dir: File,
    private val version: String,
    /**
     * The handler this one replaced. On a normal process that is the platform's
     * `RuntimeInit.KillApplicationHandler`, and delegating to it is the entire reason crash
     * behaviour is unchanged — it is what calls `ActivityManager.handleApplicationCrash` (the
     * system's "app has stopped" dialog) and ends in `finally { killProcess(); System.exit(10); }`.
     * Re-implementing any of that would be re-implementing it wrong.
     */
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(t: Thread, e: Throwable) {
        // The write may not be able to change the outcome. A full disk, a revoked directory, a
        // SecurityException — a crash we cannot read must not become a crash that behaves
        // differently. The platform's own handler carries this guard one layer down, commented
        // "avoid infinite loops if crash-reporting crashes"; this is the same lesson above it.
        runCatching { write(t, e) }

        // Outside the runCatching, unconditionally. This is the line that keeps the app dying the
        // way it does today.
        val previous = previous
        if (previous != null) {
            previous.uncaughtException(t, e)
            return
        }
        // No prior handler is not a state a normal process reaches, but a harness can leave one
        // that way, and a crashed thread whose process stays alive is worse than the crash. The
        // same pair KillApplicationHandler ends on.
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    /**
     * Written **synchronously on the crashing thread**. The process is milliseconds from
     * `System.exit(10)`; anything handed to an executor, a coroutine or `WorkManager` loses that
     * race and the record is the thing that was supposed to survive.
     *
     * One file per crash rather than one appended log: a process killed partway through an append
     * leaves a truncated final record, and the truncated one is always the crash you were reading
     * the file for. A file is whole or absent.
     *
     * **Unredacted, and deliberately.** An exception message can carry a vault item's name or a
     * path, and it lands here in the clear. A redactor that silently eats the one frame that
     * mattered is worse than a trace read by the person who built the app — and the file never
     * leaves the app's uid on its own, in a build that is never uploaded.
     */
    private fun write(t: Thread, e: Throwable) {
        dir.mkdirs()
        val now = System.currentTimeMillis()
        // Fixed-width epoch millis, so lexical order is chronological order. The thread id is on
        // the name because two threads can crash inside the same millisecond, and a collision would
        // silently overwrite the first record.
        // `Thread.id` over `threadId()`: the replacement arrived in API 35 and minSdk is 30.
        @Suppress("DEPRECATION") val tid = t.id
        File(dir, "crash-$now-$tid.txt").writeText(
            buildString {
                appendLine("time:     ${TIMESTAMP.format(Instant.ofEpochMilli(now))}")
                appendLine("version:  $version")
                appendLine("device:   ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("android:  ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("thread:   ${t.name}")
                appendLine()
                // getStackTraceString walks `cause` and the suppressed list to the bottom, which is
                // the half of a trace that says what actually went wrong.
                append(Log.getStackTraceString(e))
            }
        )
        prune()
    }

    /**
     * Keeps the 20 most recent records. A crash loop should leave twenty copies of one stack, not a
     * full disk and a directory nobody can read.
     *
     * Called from inside [write]'s `runCatching` on purpose: a prune that fails must not cost the
     * record that was just written successfully.
     */
    private fun prune() {
        val files = dir.listFiles() ?: return
        if (files.size <= KEEP) return
        files.sortedBy { it.name }
            .dropLast(KEEP)
            .forEach { it.delete() }
    }

    companion object {
        private const val KEEP = 20

        private val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSXXX").withZone(ZoneId.systemDefault())

        /** Where [install] puts records, and what the adb commands in this file's header read. */
        fun directory(context: Context): File = File(context.filesDir, "crashes")

        /**
         * `versionName (versionCode)` of the installed package.
         *
         * Read from `PackageManager` rather than `BuildConfig`, which this project does not
         * generate — `buildFeatures.buildConfig` is off, and turning it on to reach two constants
         * would put a generated `BuildConfig` into every variant for the sake of a debug-only
         * string. It is also the better source: it is the version of the APK actually installed,
         * not the version the source tree was compiled from.
         *
         * Read once at install rather than at crash time. A binder call is a poor thing to make
         * while the process is dying, and its failure would cost the whole record.
         */
        fun version(context: Context): String = runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("unknown")

        /**
         * Captures the current default handler and replaces it. Called as the first statement of
         * [DebugVaultApplication.onCreate] — the earliest point in the process where our code runs
         * without a `ContentProvider` of our own. A throw earlier than that is logcat's.
         */
        fun install(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(
                DebugCrashLog(
                    dir = directory(context),
                    version = version(context),
                    previous = Thread.getDefaultUncaughtExceptionHandler(),
                )
            )
        }

        /** For tests: an instance over a chosen directory and a chosen handler to chain to. */
        fun forTest(
            dir: File,
            version: String,
            previous: Thread.UncaughtExceptionHandler?,
        ): DebugCrashLog = DebugCrashLog(dir, version, previous)
    }
}
