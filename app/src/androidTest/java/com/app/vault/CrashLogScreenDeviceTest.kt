package com.app.vault

import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.theme.VaultTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * `debug-crash-log-screen`, task 4.1 — the list, the empty state, a record, and Clear all.
 *
 * Against a directory of this test's own, never `filesDir/crashes`: a developer's real records are
 * not this test's to clear. The screen is hosted the way `MainActivity` hosts it — the step state
 * outside, the files inside.
 */
@RunWith(AndroidJUnit4::class)
class CrashLogScreenDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "crash-log-screen-test")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A record in `DebugCrashLog`'s format. */
    private fun record(millis: Long, e: Throwable) {
        dir.mkdirs()
        File(dir, "crash-$millis-2.txt").writeText(
            "time:     2026-09-29 10:00:00.000Z\n" +
                "version:  1.0 (1)\n" +
                "device:   Google sdk_gphone64\n" +
                "android:  15 (SDK 35)\n" +
                "thread:   main\n" +
                "\n" +
                Log.getStackTraceString(e)
        )
    }

    private fun three() {
        record(1_000, IllegalStateException("oldest"))
        record(3_000, IllegalArgumentException("newest"))
        record(2_000, NullPointerException("middle"))
    }

    @Composable
    private fun Host() {
        var open by remember { mutableStateOf<String?>(null) }
        var confirming by remember { mutableStateOf(false) }
        CrashLogHook.Screen(
            record = open,
            onOpen = { open = it },
            confirmingClear = confirming,
            onAskClear = { confirming = true },
            onDismissClear = { confirming = false },
            onBack = { open = null },
            dir = dir,
        )
    }

    private fun screen(waitFor: String, assertions: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { VaultTheme { Host() } } }
            await(waitFor)
            assertions()
        }
    }

    /** The records are read on `Dispatchers.IO`, which `waitForIdle` does not wait for. */
    private fun await(text: String) = compose.waitUntil(5_000) { count(text) > 0 }

    private fun count(text: String): Int =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().size

    private fun top(text: String): Float =
        compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    private fun files(): Int = dir.listFiles()?.size ?: 0

    @Test
    fun threeRecordsListNewestFirstWithTheirClassNames() {
        three()
        screen("IllegalArgumentException") {
            assertTrue(top("IllegalArgumentException") < top("NullPointerException"))
            assertTrue(top("NullPointerException") < top("IllegalStateException"))
            assertEquals(1, count("newest"))
            assertEquals(1, count("Newest first. The 20 most recent are kept."))
            assertEquals(1, count("Clear all"))
        }
    }

    @Test
    fun anEmptyDirectoryShowsTheEmptyStateAndNoClearAll() {
        screen("No crashes recorded") {
            assertEquals(1, count("When the app crashes, the record appears here."))
            assertEquals(0, count("Clear all"))
        }
    }

    @Test
    fun aRecordWithTwoCausesShowsBothAndTheFiveFacts() {
        record(
            5_000,
            IllegalStateException("outer failure", RuntimeException("mid", IOException("root cause"))),
        )
        screen("IllegalStateException") {
            compose.onNodeWithText("IllegalStateException").performClick()
            await("Stack trace")
            assertTrue(count("outer failure") >= 1)
            listOf("Time", "Version", "Device", "Android", "Thread").forEach {
                assertEquals(it, 1, compose.onAllNodes(hasText(it), useUnmergedTree = true).fetchSemanticsNodes().size)
            }
            assertEquals(1, count("Google sdk_gphone64"))
            assertEquals(1, count("Caused by: java.lang.RuntimeException: mid"))
            assertEquals(1, count("Caused by: java.io.IOException: root cause"))
        }
    }

    @Test
    fun clearAllThenClearEmptiesTheDirectory() {
        three()
        screen("Clear all") {
            compose.onNodeWithText("Clear all").performClick()
            await("Clear 3 crash records?")
            compose.onNodeWithText("Clear").performClick()
            await("No crashes recorded")
            assertEquals(0, files())
            assertEquals(0, count("Clear all"))
        }
    }

    @Test
    fun clearAllThenKeepDeletesNothing() {
        three()
        screen("Clear all") {
            compose.onNodeWithText("Clear all").performClick()
            await("Clear 3 crash records?")
            compose.onNodeWithText("Keep").performClick()
            compose.waitForIdle()
            assertEquals(3, files())
            assertEquals(0, count("Clear 3 crash records?"))
            assertEquals(1, count("IllegalArgumentException"))
            assertEquals(1, count("IllegalStateException"))
            assertEquals(1, count("NullPointerException"))
        }
    }

    @Test
    fun aMalformedFileIsListedAndOpensAsRawText() {
        dir.mkdirs()
        File(dir, "leftover.txt").writeText("not a crash record at all")
        screen("Unreadable record") {
            assertEquals(1, count("leftover.txt"))
            compose.onNodeWithText("Unreadable record").performClick()
            await("not a crash record at all")
        }
    }
}
