package com.app.vault

import android.Manifest
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.DiscardMemoConfirmation
import com.app.vault.ui.PlayerCensus
import com.app.vault.ui.RECORD_FILL_TAG
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.VoiceMemoScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.concurrent.thread

/**
 * The memo screen's states and what each one offers — `voice-memo`.
 *
 * **One primary action per state** is the claim worth a test. The floating button
 * shipped with two controls that did the same thing and nobody noticed until it was
 * looked at; a screen with three states is three more chances at the same defect.
 *
 * `FLAG_SECURE` makes this app unscreenshottable, so nobody can eyeball these
 * either — which is the reason the structural claims are asserted rather than
 * reviewed.
 */
@RunWith(AndroidJUnit4::class)
class VoiceMemoScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** A sandboxed store, and the capture key of a vault that exists only in this test. */
    private val root = File(ctx.filesDir, "memo-screen-${System.nanoTime()}")
    private val store by lazy { BlobStore(File(root, "blobs"), File(root, "incoming")) }
    private val publicKey by lazy { VaultKeys.captureKeypair(VaultKeys.newVmk()).publicKey }

    @After
    fun tearDown() {
        // The recorder is the process's; a test that recorded leaves nothing for the next.
        VoiceMemo.discard(ctx, store)
        MemoPending.resetForTest(ctx)
        root.deleteRecursively()
    }

    /**
     * The screen over the real recorder — `destination-settings-and-slideshow` D5. Record and
     * Stop go to [VoiceMemo] the way `MainActivity` sends them, against a sandboxed store, so
     * the screen draws a real memo and nothing here imitates one.
     */
    private fun screen(
        permitted: Boolean = true,
        startsTrial: Boolean = false,
        onRequestPermission: () -> Unit = {},
        onBack: () -> Unit = {},
        shown: () -> Boolean = { true },
    ) {
        val inst = InstrumentationRegistry.getInstrumentation()
        var memo by mutableStateOf<VoiceMemo.Memo?>(null)
        var changes by mutableIntStateOf(0)
        var problem by mutableStateOf<String?>(null)
        compose.setContent {
            VaultTheme {
                if (shown()) {
                    VoiceMemoScreen(
                        permitted = permitted,
                        onRequestPermission = onRequestPermission,
                        memo = memo,
                        changes = changes,
                        onRecord = {
                            problem = VoiceMemo.start(ctx, store, publicKey, null, MemoSettings())
                            memo = VoiceMemo.current
                            changes++
                        },
                        onStop = {
                            thread {
                                val stopped = VoiceMemo.stop(ctx, store, keepKey = true)
                                inst.runOnMainSync {
                                    memo = VoiceMemo.current
                                    changes++
                                    if (stopped == null) problem = "That was too short to keep."
                                }
                            }
                        },
                        onSave = {},
                        onDiscard = {},
                        onBack = onBack,
                        onUnsavedChange = {},
                        startsTrial = startsTrial,
                        store = store,
                        problem = problem,
                    )
                }
            }
        }
    }

    private fun count(text: String): Int =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    private fun described(text: String): Int =
        compose.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().size

    @Test
    fun withoutThePermissionItAsksRatherThanShowingADeadButton() {
        var asked = 0
        screen(permitted = false, onRequestPermission = { asked++ })

        compose.onNodeWithText("Allow the microphone").assertIsDisplayed()
        assertEquals(
            "an unpermitted screen must not also offer a record button that cannot work",
            0, described("Start recording") + count("Start recording"),
        )

        compose.onNodeWithText("Allow the microphone").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun theIdleStateOffersOneForwardAction() {
        screen()
        // `voice-memo-layout`. The control is described by what it does; the label under
        // it says the same and is hidden from accessibility so it is not read twice.
        compose.onNodeWithContentDescription("Start recording").assertIsDisplayed()
        assertEquals("nothing is recorded yet, so there is nothing to save", 0, count("Save to vault"))
        assertEquals(0, count("Discard"))
        assertEquals(0, count("Stop"))
        assertEquals(0, described("Stop recording"))
    }

    /**
     * The controls are in the thumb zone — `voice-memo-layout`. The claim the change
     * exists to make, asserted on the rendered bounds: the old screen drew its button
     * around the middle, and nothing but a measurement would have said so.
     */
    @Test
    fun theRecordControlSitsAtTheBottomUpright() {
        screen()
        val root = compose.onRoot().getBoundsInRoot()
        val control = compose.onNodeWithContentDescription("Start recording").getBoundsInRoot()
        val height = root.bottom - root.top
        assertTrue(
            "the record control must sit in the bottom quarter: top ${control.top} of $height",
            control.top > root.top + height * 0.75f,
        )
    }

    /**
     * Recording is stated in words — `voice-memo-layout`. The status line and the
     * control's description change with the state, so neither the red nor the square
     * carries it alone.
     */
    @Test
    fun recordingIsSaidInWords() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO)
        screen()

        compose.onNodeWithContentDescription("Start recording").performClick()
        compose.waitUntil(5_000) { count("Recording") > 0 }
        compose.onNodeWithText("Recording").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop recording").assertIsDisplayed()

        Thread.sleep(1_500L)
        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.waitUntil(10_000) { count("Recorded") > 0 }
        compose.onNodeWithText("Recorded").assertIsDisplayed()
    }

    /**
     * The elapsed time is present as text before anything is recorded.
     *
     * The level meter is deliberately invisible to accessibility, so this is the
     * node that carries the state for anyone the meter does not reach. If it were
     * ever moved inside the meter, this fails.
     */
    @Test
    fun theElapsedTimeIsTextAndNotOnlyAMeter() {
        screen()
        assertTrue("the timer must be readable text", count("0:00") > 0)
    }

    @Test
    fun theTrialCostIsStatedBeforeRecording() {
        screen(startsTrial = true)
        assertTrue(
            "P1-16's cost is said before the act, not after the save",
            count("starts your 30-day trial") > 0,
        )
    }

    @Test
    fun theTrialCostIsNotStatedOnceItHasStarted() {
        screen(startsTrial = false)
        assertEquals(
            "an install whose trial already started must not be told it is about to",
            0, count("starts your 30-day trial"),
        )
    }

    /**
     * Listen-back on the real screen — `voice-memo-layout`, D2. Recorded into a sealed
     * store the way `MainActivity` records, stopped through the control, played, and
     * then left: exactly one player exists while the memo is on screen, and none after.
     */
    @Test
    fun listeningBackBuildsOnePlayerAndLeavingReleasesIt() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO)
        var shown by mutableStateOf(true)
        val census = PlayerCensus.live.get()
        screen(shown = { shown })

        compose.onNodeWithContentDescription("Start recording").performClick()
        Thread.sleep(2_000L)
        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription("Play")).fetchSemanticsNodes().isNotEmpty() &&
                runCatching { compose.onNodeWithContentDescription("Play").assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithText("Recorded").assertIsDisplayed()
        compose.onNodeWithText("Save to vault").assertIsEnabled()
        assertEquals("the held memo gets exactly one player", census + 1, PlayerCensus.live.get())

        compose.onNodeWithContentDescription("Play").performClick()
        compose.waitUntil(5_000) { described("Pause") > 0 }

        shown = false
        compose.waitForIdle()
        assertEquals("leaving releases it", census, PlayerCensus.live.get())
    }

    /**
     * Reduced motion — `voice-memo-layout`, D4. With the system's animator duration
     * scale at 0, the record control is a square within three frames of being
     * pressed, not partway through a morph. The scale is the emulator's own setting,
     * so it is read first and put back exactly as it was.
     */
    @Test
    fun underReducedMotionTheControlSnaps() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String): String =
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
                .use { it.readBytes().toString(Charsets.UTF_8).trim() }
        automation.grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO)
        val previous = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 0")
        try {
            screen()
            compose.mainClock.autoAdvance = false
            compose.onNodeWithContentDescription("Start recording").performClick()
            // Three frames: one for the press to recompose, one for the animation to
            // start, one to draw it. A quarter of the 200ms morph — a tween would still be
            // well short of the square here, so reaching it can only be the snap.
            compose.mainClock.advanceTimeBy(48L)
            val width = compose.onNodeWithTag(RECORD_FILL_TAG, useUnmergedTree = true)
                .getBoundsInRoot().let { it.right - it.left }
            assertEquals(
                "the fill must be the recording square within 48ms, not partway through a morph",
                Spacing.recordFillActive.value, width.value, 0.5f,
            )
        } finally {
            compose.mainClock.autoAdvance = true
            if (previous == "null" || previous.isEmpty()) {
                shell("settings delete global animator_duration_scale")
            } else {
                shell("settings put global animator_duration_scale $previous")
            }
        }
    }

    @Test
    fun theConfirmationSaysWhatIsLostAndOffersToKeepIt() {
        var discarded = 0
        var kept = 0
        compose.setContent {
            VaultTheme {
                DiscardMemoConfirmation(
                    onConfirm = { discarded++ },
                    onDismiss = { kept++ },
                )
            }
        }

        compose.onNodeWithText("Discard this recording?").assertIsDisplayed()
        assertTrue(
            "it must say the recording is lost, and that the vault is not touched",
            count("Nothing already in the vault is affected") > 0,
        )

        compose.onNodeWithText("Keep recording").performClick()
        assertEquals("keeping must not discard", 0, discarded)
        assertEquals(1, kept)
    }
}
