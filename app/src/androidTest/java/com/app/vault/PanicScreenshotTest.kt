package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.PanicField
import com.app.vault.ui.PanicProblem
import com.app.vault.ui.PanicScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The panic screen as a user sees it — `panic-phrase`, task 4.6.
 *
 * `PhotoDetailsScreenshotTest`'s method: `FLAG_SECURE` cleared on this throwaway activity,
 * captures from `uiAutomation`, saved to [DIR] on the emulator. The sheets inherit the
 * activity's secure bit, so they are captured too. Composed directly — no vault is created
 * and no passphrase is entered on the device.
 */
@RunWith(AndroidJUnit4::class)
class PanicScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    private companion object {
        const val DIR = "/data/local/tmp/panic-phrase"
    }

    private fun shot(name: String, content: @Composable () -> Unit, check: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent { content() }
            }
            compose.waitForIdle()
            check()
            compose.waitForIdle()
            // The sheet animates in; let it settle before the capture.
            Thread.sleep(600)
            val png = java.io.ByteArrayOutputStream().also {
                instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            save("$name.png", png)
        }
    }

    private fun save(name: String, bytes: ByteArray) {
        val ui = instr.uiAutomation
        ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
        // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a
        // large PNG (the keyboard up) fills the pipes and both ends block. HangWatch caught it.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(bytes) }
        drain(stdout)
    }

    private fun drain(fd: android.os.ParcelFileDescriptor) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    @Composable
    private fun Screen(enabled: Boolean, problem: PanicProblem? = null) {
        VaultTheme(ThemeChoice.Dark) {
            PanicScreen(
                enabled = enabled, busy = false, problem = problem, settled = 0,
                onSet = { _, _ -> }, onChange = { _, _, _ -> }, onTurnOff = {}, onForget = {},
                onCancel = {}, backupConfigured = false,
            )
        }
    }

    private fun count(text: String, ignoreCase: Boolean = false): Int =
        compose.onAllNodes(hasText(text, substring = true, ignoreCase = ignoreCase), useUnmergedTree = true)
            .fetchSemanticsNodes().size

    private fun top(text: String): Float =
        compose.onNode(hasText(text, substring = true), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top

    private fun namesNothingItMustNot() {
        assertEquals("no marker", 0, count("//"))
        // Case-sensitive: the fact "It destroys the way in" is copy, not the old gate's word.
        assertEquals("no acknowledgement word", 0, count("DESTROY"))
        assertEquals("the UI says vault list, never keyring", 0, count("keyring", ignoreCase = true))
        assertEquals("no list of passphrases to check", 0, count("Add this passphrase"))
    }

    @Test
    fun offLeadsWithStateThenFactsThenTheForm() = shot("off-top", { Screen(enabled = false) }) {
        namesNothingItMustNot()
        val state = top("Panic credential is off")
        assertTrue("the status line comes before the facts", state < top("This vault list survives"))
        assertTrue(top("This vault list survives") < top("It destroys the way in"))
        assertTrue(top("Nothing confirms it worked") < top("Set a panic phrase"))
    }

    @Test
    fun offFormWithItsPrimaryAction() = shot("off-form", { Screen(enabled = false) }) {
        compose.onNodeWithText("Turn on").performScrollTo()
        compose.onNodeWithText("Turn on").assertIsNotEnabled()
    }

    @Test
    fun aRefusalSitsUnderItsOwnField() = shot(
        "off-wrong-passphrase",
        {
            Screen(
                enabled = false,
                problem = PanicProblem(PanicField.PASSPHRASE, "That passphrase does not open this vault list."),
            )
        },
    ) {
        compose.onNode(hasText("That passphrase does not open", substring = true), useUnmergedTree = true)
            .performScrollTo()
        val error = top("That passphrase does not open")
        assertTrue("under the passphrase field", error > top("Your passphrase"))
        assertTrue("and above the panic phrase field", error < top(ctx.getString(R.string.passphrase_requirement, PassphraseStrength.MIN_CODE_POINTS)))
        assertEquals("the helper it replaces is gone", 0, count("The one that opened this vault list."))
    }

    @Test
    fun onOffersChangeAndTurnOff() = shot("on", { Screen(enabled = true) }) {
        namesNothingItMustNot()
        assertTrue(count("Panic credential is on") > 0)
        assertTrue(count("Change panic phrase") > 0)
        assertTrue(count("Turn it off") > 0)
        assertEquals("no form while on", 0, count("Set a panic phrase"))
    }

    @Test
    fun theChangeSheet() = shot("sheet-change", { Screen(enabled = true) }) {
        compose.onNodeWithText("Change panic phrase").performClick()
        compose.waitForIdle()
        assertTrue(count("Current panic phrase") > 0)
        assertTrue(count("Forgot your panic phrase?") > 0)
        assertTrue(count("New panic phrase again") > 0)
        // Opened fully: the action is on screen without scrolling.
        compose.onNodeWithText("Save").assertIsDisplayed()
        namesNothingItMustNot()
    }

    @Test
    fun theTurnOffSheet() = shot("sheet-turn-off", { Screen(enabled = true) }) {
        compose.onNodeWithText("Turn it off").performClick()
        compose.waitForIdle()
        assertTrue(count("Turn off panic phrase") > 0)
        assertTrue(count("Current panic phrase") > 0)
        assertEquals("turning off asks for nothing new", 0, count("New panic phrase"))
    }

    @Test
    fun theForgottenPhraseRoute() = shot("sheet-forgot", { Screen(enabled = true) }) {
        compose.onNodeWithText("Turn it off").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Forgot your panic phrase?").performClick()
        compose.waitForIdle()
        assertTrue(count("Clear a forgotten panic phrase") > 0)
        assertEquals("the current-phrase field is gone", 0, count("Current panic phrase"))
        assertTrue(count("Clear panic phrase") > 0)
    }
}
