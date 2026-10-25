package com.app.vault

import android.Manifest
import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.VoiceMemoScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.thread

/**
 * The voice memo screen as a user sees it — `voice-memo-layout`, task 6.4.
 *
 * **`FLAG_SECURE` is cleared on the way in**, on this throwaway `ComponentActivity` only,
 * because `VaultApplication` sets it on every activity and a screenshot of a secure window
 * is black. The captures come from `uiAutomation`, not `View.draw`, which paints
 * subcompositions that are not on screen. They land in `/data/local/tmp/voice-memo/`.
 *
 * The recording states record from the emulator's microphone into a sealed store under a
 * throwaway key, exactly as `MainActivity` does, so the waveforms are real readings.
 */
@RunWith(AndroidJUnit4::class)
class VoiceMemoScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val root = File(ctx.filesDir, "memo-shots-${System.nanoTime()}")
    private val store = BlobStore(File(root, "blobs"), File(root, "incoming"))
    private val publicKey = VaultKeys.captureKeypair(VaultKeys.newVmk()).publicKey

    @After
    fun tearDown() {
        VoiceMemo.discard(ctx, store)
        MemoPending.resetForTest(ctx)
        root.deleteRecursively()
    }

    /** The screen over the real recorder, as `MainActivity` drives it — D5. */
    private fun screen(activity: ComponentActivity, permitted: Boolean) {
        var memo by mutableStateOf<VoiceMemo.Memo?>(null)
        var changes by mutableIntStateOf(0)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activity.setContent {
            VaultTheme {
                VoiceMemoScreen(
                    permitted = permitted,
                    onRequestPermission = {},
                    memo = memo,
                    changes = changes,
                    onRecord = {
                        VoiceMemo.start(ctx, store, publicKey, null, MemoSettings())
                        memo = VoiceMemo.current
                        changes++
                    },
                    onStop = {
                        thread {
                            VoiceMemo.stop(ctx, store, keepKey = true)
                            instr.runOnMainSync {
                                memo = VoiceMemo.current
                                changes++
                            }
                        }
                    },
                    onSave = {},
                    onDiscard = {},
                    onBack = {},
                    onUnsavedChange = {},
                    startsTrial = false,
                    store = store,
                )
            }
        }
    }

    @Test
    fun everyState() {
        instr.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO)

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { screen(it, permitted = true) }
            compose.waitForIdle()
            capture("1-ready")

            compose.onNodeWithContentDescription("Start recording").performClick()
            elapse(3_000L)
            capture("2-recording")

            compose.onNodeWithContentDescription("Stop recording").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription("Play")).fetchSemanticsNodes().isNotEmpty() &&
                    runCatching { compose.onNodeWithContentDescription("Play").assertIsEnabled() }.isSuccess
            }
            compose.onNodeWithContentDescription("Play").performClick()
            elapse(1_200L)
            capture("3-recorded-playing")
        }

        VoiceMemo.discard(ctx, store)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { screen(it, permitted = false) }
            compose.waitForIdle()
            capture("4-permission")
        }

        // Turned before the activity exists: a plain `ComponentActivity` is recreated by a
        // turn, and the content set on it would go with the old instance.
        try {
            instr.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
            Thread.sleep(1_000L)
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { screen(it, permitted = true) }
                compose.waitForIdle()
                capture("5-sideways-ready")
                compose.onNodeWithContentDescription("Start recording").performClick()
                elapse(2_000L)
                capture("6-sideways-recording")
                compose.onNodeWithContentDescription("Stop recording").performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasContentDescription("Play")).fetchSemanticsNodes().isNotEmpty()
                }
                Thread.sleep(500L)
                capture("7-sideways-recorded")
            }
        } finally {
            instr.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
            instr.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }
    }

    /**
     * Lets [ms] pass on both clocks. The microphone and the player run on real time; the
     * screen's 100ms sampling and polling loops run on the compose test clock, which a
     * bare `Thread.sleep` does not move — the first captures showed three readings in a
     * three-second memo and a playhead that had not left 0:00.
     */
    private fun elapse(ms: Long) {
        repeat((ms / STEP_MS).toInt()) {
            Thread.sleep(STEP_MS)
            compose.mainClock.advanceTimeBy(STEP_MS)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val png = java.io.ByteArrayOutputStream().also {
            instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val ui = instr.uiAutomation
        ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
        // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a
        // large PNG (the keyboard up) fills the pipes and both ends block. HangWatch caught it.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name.png")
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        drain(stdout)
    }

    private fun drain(fd: ParcelFileDescriptor) {
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private companion object {
        const val DIR = "/data/local/tmp/voice-memo"
        const val STEP_MS = 100L
    }
}
