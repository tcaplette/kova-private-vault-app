package com.app.vault

import android.Manifest
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.RecorderCover
import com.app.vault.ui.theme.VaultTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The voice recorder cover's layout against real recordings — `voice-recorder-layout`, D7.
 *
 * `persist = true`: this records two short recordings of real microphone input through
 * the UI, and teardown deletes exactly those through [RecorderStore.delete] — whatever the
 * device held before is left alone.
 *
 * **The microphone grant is given here and not taken back here.** Revoking a runtime
 * permission kills the process holding it, and that process is this test run — the
 * reason `MusicCoverLayoutDeviceTest` gives. It is revoked from the host after the run:
 * `adb -s emulator-5554 shell pm revoke com.app.vault android.permission.RECORD_AUDIO`.
 *
 * `FLAG_SECURE` is cleared on this throwaway `ComponentActivity` so `uiAutomation`
 * screenshots are real; they land in `/data/local/tmp/recorder-layout/`.
 */
@RunWith(AndroidJUnit4::class)
class RecorderCoverLayoutDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val density = ctx.resources.displayMetrics.density
    private lateinit var before: Set<String>

    @Before
    fun setUp() {
        instr.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO)
        before = RecorderStore.recordings(ctx).map { it.name }.toSet()
    }

    @After
    fun tearDown() {
        RecorderStore.recordings(ctx).filter { it.name !in before }.forEach { RecorderStore.delete(ctx, it) }
        instr.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        instr.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    @Test
    fun theLayoutIsARecorders() {
        var window = Rect()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { window = cover(it) }
            compose.waitForIdle()
            if (before.isEmpty()) capture("1-empty")

            // Upright, the record control sits in the bottom quarter of the window.
            val record = bounds(recordControl())
            assertTrue(
                "the record control is above the bottom quarter (top ${record.top} of ${window.height()})",
                record.top >= window.height() * 3 / 4,
            )

            // The first recording: long enough to still be playing when it is collapsed.
            recordControl().performClick()
            compose.waitForIdle()
            assertTrue("the status does not read Recording", showing("Recording"))
            assertTrue(
                "the timer does not announce the recording",
                compose.onAllNodes(hasContentDescription("Recording, ", substring = true))
                    .fetchSemanticsNodes().isNotEmpty(),
            )
            elapse(2_000)
            capture("2-recording")
            compose.onNodeWithContentDescription("Pause").performClick()
            compose.waitForIdle()
            assertTrue("pause did not turn the status to Paused", showing("Paused"))
            assertFalse("the status still reads Recording while paused", showing("Recording"))
            capture("3-paused")
            compose.onNodeWithContentDescription("Resume").performClick()
            elapse(3_000)
            recordControl().performClick()
            compose.waitForIdle()
            assertEquals("the first recording was not filed", 1, made().size)

            // The second. While it runs, the list is gone.
            recordControl().performClick()
            compose.waitForIdle()
            assertTrue(
                "the list is still shown while recording",
                compose.onAllNodes(clickLabel("Show controls")).fetchSemanticsNodes().isEmpty(),
            )
            elapse(2_000)
            recordControl().performClick()
            compose.waitForIdle()
            assertEquals("the second recording was not filed", 2, made().size)

            // Collapsed rows expose no Play, Rename or Delete control.
            assertEquals("not every row is shown collapsed", 2, compose.onAllNodes(clickLabel("Show controls")).fetchSemanticsNodes().size)
            listOf("Play", "Delete", "Back 15 seconds", "Forward 15 seconds").forEach {
                assertFalse("a collapsed row exposes $it", described(it))
            }
            assertTrue(
                "a collapsed row exposes Rename",
                compose.onAllNodes(clickLabel("Rename")).fetchSemanticsNodes().isEmpty(),
            )
            capture("4-list-collapsed")

            // Tapping a row exposes its controls, each at least 48dp.
            val first = made().maxBy { it.durationMs }
            rowHeader(first.title).performClick()
            compose.waitForIdle()
            listOf("Back 15 seconds", "Play", "Forward 15 seconds", "Delete").forEach { control ->
                val at = bounds(compose.onNodeWithContentDescription(control))
                assertTrue(
                    "$control is under 48dp: ${at.width()}x${at.height()}",
                    at.width() >= dp(48) && at.height() >= dp(48),
                )
            }
            capture("5-row-expanded")

            // Tapping the expanded title opens the rename dialog.
            compose.onNode(clickLabel("Rename")).performClick()
            compose.waitForIdle()
            assertTrue("the rename dialog did not open", showing("Save"))
            compose.onNodeWithText("Cancel").performClick()
            compose.waitForIdle()

            // Collapsing the playing row pauses it.
            val audio = ctx.getSystemService(AudioManager::class.java)
            compose.onNodeWithContentDescription("Play").performClick()
            var playing = false
            repeat(20) { if (!playing) { elapse(100); playing = audio.isMusicActive } }
            assertTrue("play did not start playback", playing)
            // On the meta line, away from the title: the title renames.
            compose.onNode(clickLabel("Hide controls")).performTouchInput { click(bottomRight - Offset(24f, 24f)) }
            assertFalse("collapsing opened the rename dialog instead", showing("Save"))
            // The stream reads active for a moment after a pause while the mixer drains. A
            // row left playing stays active for the seconds of recording still to come.
            var quietAfter = -1L
            repeat(15) { i -> if (quietAfter < 0) { elapse(100); if (!audio.isMusicActive) quietAfter = (i + 1) * 100L } }
            assertTrue("collapsing the playing row left it playing", quietAfter in 0..1_500)
            assertFalse("the collapsed row still exposes Pause", described("Pause"))
        }

        // Turned before the activity exists: a plain `ComponentActivity` is recreated by a
        // turn, and the content set on it would go with the old instance.
        instr.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
        Thread.sleep(1_000)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { window = cover(it) }
            compose.waitForIdle()

            // Sideways, the record control lies inside the window and the list beside it.
            val record = bounds(recordControl())
            assertTrue(
                "the record control is outside the sideways window: $record in $window",
                record.left >= 0 && record.top >= 0 && record.right <= window.width() && record.bottom <= window.height(),
            )
            val row = bounds(rowHeader(made().first().title))
            assertTrue("the list is not beside the record control: $row, $record", row.right <= record.left)
            capture("6-sideways")
        }
    }

    /** The cover in the bare host, secure flag cleared; returns the window's bounds. */
    private fun cover(activity: ComponentActivity): Rect {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activity.setContent {
            VaultTheme { RecorderCover(hasPermission = true, onTrigger = {}, persist = true) }
        }
        return activity.windowManager.currentWindowMetrics.bounds
    }

    private fun made(): List<RecorderStore.Recording> =
        RecorderStore.recordings(ctx).filter { it.name !in before }

    /** The record control: the one clickable 72dp circle. It carries no label to find it by. */
    private fun recordControl(): SemanticsNodeInteraction {
        val all = compose.onAllNodes(hasClickAction())
        val nodes = all.fetchSemanticsNodes()
        val index = nodes.indices.single { i ->
            val b = nodes[i].boundsInWindow
            abs(b.width - dp(72)) <= 2 && abs(b.height - dp(72)) <= 2
        }
        return all[index]
    }

    /** A row's header: the clickable that shows or hides its controls, holding [title]. */
    private fun rowHeader(title: String): SemanticsNodeInteraction =
        compose.onNode(
            (clickLabel("Show controls") or clickLabel("Hide controls")) and
                SemanticsMatcher("holds $title") { node ->
                    node.children.any { child -> child.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text == title } == true } ||
                        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text == title } == true
                },
        )

    private fun clickLabel(label: String): SemanticsMatcher =
        SemanticsMatcher("click label $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    private fun described(description: String): Boolean =
        compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()

    private fun showing(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(node: SemanticsNodeInteraction): Rect =
        node.fetchSemanticsNode().boundsInWindow.let {
            Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        }

    private fun dp(value: Int): Int = (value * density).toInt()

    /** Lets [ms] pass on both clocks: the recorder's and player's real one and the compose test clock. */
    private fun elapse(ms: Long) {
        repeat((ms / STEP_MS).toInt()) {
            Thread.sleep(STEP_MS)
            compose.mainClock.advanceTimeBy(STEP_MS)
        }
    }

    private fun capture(name: String): Bitmap {
        compose.waitForIdle()
        val shot = instr.uiAutomation.takeScreenshot()
        val png = java.io.ByteArrayOutputStream().also {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val ui = instr.uiAutomation
        ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
        // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a
        // large PNG (the keyboard up) fills the pipes and both ends block. HangWatch caught it.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name.png")
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        drain(stdout)
        return shot
    }

    private fun drain(fd: ParcelFileDescriptor) {
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private companion object {
        const val DIR = "/data/local/tmp/recorder-layout"
        const val STEP_MS = 100L
    }
}
