package com.app.vault

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `debug-crash-log` — the handler, on a device.
 *
 * **Why the handler is called directly rather than by actually crashing.** The thing under test
 * ends by delegating to the platform's `KillApplicationHandler`, whose last act is
 * `Process.killProcess(myPid())`. A test that reaches that line kills its own app process and
 * reports as a crashed run, not a passing one. So the delegation target is a stub that records
 * instead of killing, and every claim except "the platform really dispatches to us" is asserted
 * here. That last claim is [theHandlerIsInstalledAtStart]'s, and the end-to-end crash-and-pull was
 * run by hand on the emulator (change task 3.6).
 *
 * Records go to a directory of this test's own, never `filesDir/crashes`: a developer's real crash
 * records are not this test's to evict against a cap of twenty.
 */
@RunWith(AndroidJUnit4::class)
class DebugCrashLogDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "crash-log-test")
    private val version = DebugCrashLog.version(ctx)

    /** Records the delegation instead of ending the process. */
    private class StubPrevious : Thread.UncaughtExceptionHandler {
        var thread: Thread? = null
        var throwable: Throwable? = null
        override fun uncaughtException(t: Thread, e: Throwable) {
            thread = t
            throwable = e
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun records(): List<File> = (dir.listFiles() ?: emptyArray()).sortedBy { it.name }

    @Test
    fun aRecordNamesTheThreadTheBuildTheDeviceAndTheAndroidVersion() {
        val previous = StubPrevious()
        val log = DebugCrashLog.forTest(dir, version, previous)
        val thread = Thread(null, {}, "the-crashing-thread")

        log.uncaughtException(thread, IllegalStateException("boom"))

        val record = records().single().readText()
        assertTrue(record, record.contains("thread:   the-crashing-thread"))
        // Read from PackageManager here, independently. Asserting against
        // DebugCrashLog.version(ctx) would have compared the code under test with itself — which
        // it did, and a literal "${info.versionName}" passed that assertion and reached a real
        // crash record before the end-to-end run caught it.
        @Suppress("DEPRECATION")
        val installed = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        assertTrue(record, record.contains("version:  ${installed.versionName} (${installed.longVersionCode})"))
        assertFalse(record, record.contains("version:  unknown"))
        assertFalse("the version line is an unevaluated template", record.contains("\${info."))
        assertTrue(record, record.contains(android.os.Build.MODEL))
        assertTrue(record, record.contains("SDK ${android.os.Build.VERSION.SDK_INT}"))
        assertTrue(record, record.contains("java.lang.IllegalStateException: boom"))
        // The timestamp line is present and is not the epoch — the formatter ran.
        assertTrue(record, Regex("""time:     20\d\d-\d\d-\d\d \d\d:\d\d:\d\d""").containsMatchIn(record))
    }

    /**
     * The half of a stack trace that says what actually went wrong. A record that stops at the
     * outermost wrapper is the record you throw away.
     */
    @Test
    fun aTwoDeepCausalChainAppearsInFull() {
        val log = DebugCrashLog.forTest(dir, version, StubPrevious())
        val root = java.io.IOException("the-root-cause")
        val middle = RuntimeException("the-middle", root)

        log.uncaughtException(Thread.currentThread(), IllegalStateException("the-outer", middle))

        val record = records().single().readText()
        assertTrue(record, record.contains("the-outer"))
        assertTrue(record, record.contains("Caused by: java.lang.RuntimeException: the-middle"))
        assertTrue(record, record.contains("Caused by: java.io.IOException: the-root-cause"))
    }

    @Test
    fun theHandlerItReplacedStillRuns() {
        val previous = StubPrevious()
        val log = DebugCrashLog.forTest(dir, version, previous)
        val thrown = IllegalStateException("boom")
        val thread = Thread.currentThread()

        log.uncaughtException(thread, thrown)

        assertEquals(thread, previous.thread)
        assertEquals(thrown, previous.throwable)
    }

    /**
     * D3 — the write may not change the outcome. A file standing where the directory must go makes
     * `mkdirs` and the write fail; the crash must still be handled exactly as it would have been.
     */
    @Test
    fun aFailedWriteStillDelegates() {
        dir.parentFile?.mkdirs()
        assertTrue("could not stage the unwritable case", dir.createNewFile())

        val previous = StubPrevious()
        val thrown = IllegalStateException("boom")

        DebugCrashLog.forTest(dir, version, previous).uncaughtException(Thread.currentThread(), thrown)

        assertEquals(thrown, previous.throwable)
        assertFalse("the file standing in for the directory was replaced", dir.isDirectory)
    }

    /**
     * No prior handler is not a state a normal process reaches, but a crashed thread whose process
     * stays alive is worse than the crash — so the fallback exists and must not be reached here.
     * Asserting it directly would kill the test process, so what is asserted is that the record is
     * still written before the fallback would run.
     */
    @Test
    fun theRecordIsWrittenBeforeAnyDelegation() {
        val previous = StubPrevious()
        DebugCrashLog.forTest(dir, version, previous).uncaughtException(Thread.currentThread(), Error("late"))

        assertEquals(1, records().size)
        assertNotNull(previous.throwable)
    }

    @Test
    fun theTwentyMostRecentRecordsAreKept() {
        val log = DebugCrashLog.forTest(dir, version, StubPrevious())

        repeat(21) { i ->
            log.uncaughtException(Thread.currentThread(), IllegalStateException("crash-$i"))
            // The name carries epoch millis and a thread id; 21 crashes on one thread inside one
            // millisecond would otherwise collide and this would be testing nothing.
            Thread.sleep(2)
        }

        val kept = records()
        assertEquals(20, kept.size)
        // Oldest evicted, newest present: the first crash is gone, the last is not.
        assertFalse(kept.any { it.readText().contains("crash-0\n") })
        assertTrue(kept.last().readText().contains("crash-20"))
        // Lexical order is chronological order.
        assertEquals(kept.map { it.name }, kept.map { it.name }.sorted())
    }

    /**
     * The installation actually happened, and `super.onCreate()` still ran.
     *
     * Two claims in one test on purpose: they are the two halves of D1. If `DebugVaultApplication`
     * were wired in but did not chain to `VaultApplication`, the first assertion would pass and the
     * second would fail — which is the failure mode worth a test, because nothing about it is
     * visible on screen.
     */
    @Test
    fun theHandlerIsInstalledAtStartAndFlagSecureSurvives() {
        assertEquals(
            DebugCrashLog::class.java,
            Thread.getDefaultUncaughtExceptionHandler()?.javaClass,
        )

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertTrue(
                    "FLAG_SECURE was not set — VaultApplication.onCreate did not run",
                    flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
                )
            }
        }
    }
}
