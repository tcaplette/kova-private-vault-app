package com.app.vault

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.FaceDownScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * `face-down-lock-settings`, task 5.2 — the Face-down lock screen's order, its switch, Lock after
 * and the screen-timeout line, in both schemes, against `settings-screen`'s new requirement.
 *
 * Composed on a throwaway activity with the screen's inputs held here, as the activity holds them.
 * Each state is screenshotted with `FLAG_SECURE` cleared through `uiAutomation`, written with `dd`
 * (CLAUDE.md rule 21), into [DIR].
 */
@RunWith(AndroidJUnit4::class)
class FaceDownScreenTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()

    private var available by mutableStateOf(true)
    private var on by mutableStateOf(true)
    private var lockAfter by mutableStateOf(0L)
    private var timeout by mutableStateOf(Long.MAX_VALUE)

    private val presets = listOf(
        "Immediately", "5 seconds", "15 seconds", "30 seconds", "1 minute", "2 minutes", "5 minutes",
    )

    /** Runs [check] once per scheme, each on a fresh activity, from the state [setUp] leaves. */
    private fun inBothSchemes(name: String, setUp: () -> Unit = {}, check: () -> Unit) {
        for (scheme in listOf(ThemeChoice.Dark, ThemeChoice.Light)) {
            available = true
            on = true
            lockAfter = 0L
            timeout = Long.MAX_VALUE
            setUp()
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    activity.setContent {
                        VaultTheme(scheme) {
                            FaceDownScreen(
                                available = available,
                                on = on,
                                lockAfterMs = lockAfter,
                                screenTimeoutMs = timeout,
                                onToggle = { on = !on },
                                onLockAfter = { lockAfter = it },
                                onBack = {},
                            )
                        }
                    }
                }
                compose.waitForIdle()
                check()
                shot("$name-${scheme.name.lowercase()}")
            }
        }
    }

    private fun choice(text: String) =
        compose.onNode(hasText(text, substring = false) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))

    @Test
    fun theOrderAndTheHeading() = inBothSchemes("order") {
        val lead = "Turn your phone over and set it down to lock the vault."
        val tops = listOf(lead, "Use face-down lock", "Lock after", "Immediately", "5 minutes").map { text ->
            compose.onNode(hasText(text, substring = false), useUnmergedTree = true).performScrollTo()
                .fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals("lead, switch, heading, then the choices, top to bottom", tops.sorted(), tops)
        compose.onNode(isHeading() and hasText("Lock after")).assertExists()
        compose.onNode(isToggleable() and hasText("Use face-down lock")).assertExists()
        compose.onNode(hasText("On", substring = false), useUnmergedTree = true).assertExists()
    }

    @Test
    fun theChoicesAreARadioGroupOfFullTargets() = inBothSchemes("choices") {
        presets.forEach { choice(it).performScrollTo().assertHeightIsAtLeast(48.dp).assertIsEnabled() }
        choice("Immediately").assertIsSelected()
    }

    @Test
    fun switchOffKeepsTheChoicesShownAndGreyed() = inBothSchemes("off", setUp = { on = false; lockAfter = 15_000L }) {
        compose.onNode(isHeading() and hasText("Lock after")).assertExists()
        presets.forEach { choice(it).performScrollTo().assertIsNotEnabled() }
        choice("15 seconds").assertIsSelected()
        compose.onNode(hasText("Off", substring = false), useUnmergedTree = true).assertExists()
    }

    @Test
    fun choosingAPeriodSelectsIt() = inBothSchemes("chosen") {
        choice("30 seconds").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(30_000L, lockAfter)
        choice("30 seconds").assertIsSelected()
    }

    @Test
    fun theScreenTimeoutGreysWhatItMakesMoot() = inBothSchemes("timeout", setUp = { timeout = 30_000L }) {
        listOf("Immediately", "5 seconds", "15 seconds").forEach { choice(it).performScrollTo().assertIsEnabled() }
        listOf("30 seconds", "1 minute", "2 minutes", "5 minutes").forEach { choice(it).performScrollTo().assertIsNotEnabled() }
        compose.onNode(
            hasText("Your screen turns off after 30 seconds, which locks the vault first.", substring = false),
        ).performScrollTo().assertExists()
    }

    @Test
    fun noTimeoutLineWhenNothingIsMoot() = inBothSchemes("no-timeout-line", setUp = { timeout = 600_000L }) {
        assertEquals(0, compose.onAllNodes(hasText("Your screen turns off", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun theSwitchWithNoMotionSensor() = inBothSchemes("no-sensor", setUp = { available = false; on = false }) {
        val reason = "This device has no motion sensor, so it can't tell when it's face down."
        compose.onNode(hasText("Use face-down lock") and hasText(reason)).assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        presets.forEach { choice(it).performScrollTo().assertIsNotEnabled() }
        assertTrue(compose.onAllNodes(isToggleable() and hasText("Use face-down lock")).fetchSemanticsNodes().isEmpty())
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        val png = ByteArrayOutputStream().also {
            instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val ui = instr.uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("mkdir -p $DIR")).use { it.readBytes() }
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name.png")
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
    }

    private companion object {
        const val DIR = "/data/local/tmp/face-down-lock-settings"
    }
}
