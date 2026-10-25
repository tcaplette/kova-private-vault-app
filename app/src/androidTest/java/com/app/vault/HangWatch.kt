package com.app.vault

import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.junit.runner.Description
import org.junit.runner.notification.RunListener
import java.util.Timer
import java.util.TimerTask
import kotlin.concurrent.schedule

/**
 * Catches a hung device test instead of leaving the run waiting on it.
 *
 * Registered for every instrumented run by the `listener` runner argument in
 * `app/build.gradle.kts`. It times the whole test — rules, `@Before`, body and `@After` —
 * which the runner's own `timeout_msec` does not: that one wraps the body only.
 *
 * A test still running after [DUMP_EVERY_MS] has every thread's stack written to logcat under
 * [TAG], the test thread first and the main thread second, and again every [DUMP_EVERY_MS]. The
 * same frames on the test thread in two dumps in a row is a hang; frames that move are a slow
 * test. At [KILL_AFTER_MS] the stacks are written a last time and the test process is killed:
 * the orchestrator records that test as crashed and starts the next one in a fresh process.
 *
 * Read it live with `adb -s emulator-5554 logcat -s HangWatch`, or after the run in that test's
 * `app/build/outputs/androidTest-results/connected/debug/<device>/logcat-<class>-<test>.txt`.
 */
class HangWatch : RunListener() {

    private val timer = Timer(TAG, true)

    @Volatile
    private var watch: TimerTask? = null

    override fun testStarted(description: Description) {
        // The runner notifies on the thread that runs the test.
        val testThread = Thread.currentThread()
        val started = SystemClock.uptimeMillis()
        watch = timer.schedule(DUMP_EVERY_MS, DUMP_EVERY_MS) {
            val ran = SystemClock.uptimeMillis() - started
            val kill = ran >= KILL_AFTER_MS
            Log.e(TAG, "${description.displayName} still running after ${ran / 1000}s" +
                if (kill) " — hung, killing the test process" else "")
            dump(testThread)
            if (kill) Process.killProcess(Process.myPid())
        }
    }

    override fun testFinished(description: Description) {
        watch?.cancel()
    }

    private fun dump(testThread: Thread) {
        val main = Looper.getMainLooper().thread
        Thread.getAllStackTraces().entries
            .sortedBy { (t, _) -> if (t == testThread) 0 else if (t == main) 1 else 2 }
            .forEach { (t, frames) ->
                val role = if (t == testThread) " [test]" else if (t == main) " [main]" else ""
                // logcat truncates an entry near 4 KB; a deep Compose stack is longer.
                "\"${t.name}\"$role ${t.state}\n${frames.joinToString("\n") { "    at $it" }}"
                    .chunked(3_500).forEach { Log.e(TAG, it) }
            }
    }

    companion object {
        const val TAG = "HangWatch"

        /** One dump alone is a slow test; the same `[test]` frames in two dumps is a hang. */
        const val DUMP_EVERY_MS = 30_000L

        /**
         * About twice the slowest test measured on the emulator — 86 s,
         * `UnavailableStatesScreenshotTest.theBiometricScreenInEveryLayoutAndState`, 2026-10-11,
         * from a sample of 194 tests. A legitimate test that outgrows it is killed: raise this.
         */
        const val KILL_AFTER_MS = 180_000L
    }
}
