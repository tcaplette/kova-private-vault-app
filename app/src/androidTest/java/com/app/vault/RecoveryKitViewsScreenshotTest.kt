package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.BiometricState
import com.app.vault.ui.NewPassphrase
import com.app.vault.ui.NewPassphraseStep
import com.app.vault.ui.KitStatus
import com.app.vault.ui.RecoveryKitScreen
import com.app.vault.ui.RecoveryKitStepScreen
import com.app.vault.ui.RecoveryKitViewScreen
import com.app.vault.ui.VaultDrawerSheet
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `recovery-kit-three-views`, task 5.2 — the setup copy, the drawer rows, the view screen in each
 * state and the words in their review form, as a user sees them.
 *
 * `RecoveryOwnSlotScreenshotTest`'s method: `FLAG_SECURE` cleared on a throwaway activity,
 * captures from `uiAutomation`, saved to [DIR] on the emulator. Composed directly — no vault is
 * created and nothing is written to the app's storage.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryKitViewsScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val words = Mnemonic.fromResources(instr.targetContext)
        .encode(ByteArray(Mnemonic.ENTROPY_BYTES) { (it * 37 + 11).toByte() })

    private companion object {
        const val DIR = "/data/local/tmp/recovery-kit-three-views"
    }

    private fun shot(name: String, content: @Composable () -> Unit, act: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent { VaultTheme(ThemeChoice.Dark) { content() } }
            }
            compose.waitForIdle()
            act()
            compose.waitForIdle()
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

    private fun count(text: String): Int =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().size

    private fun top(text: String): Float =
        compose.onNode(hasText(text, substring = true), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top

    // --- setup copy -----------------------------------------------------------------

    @Test
    fun firstRunKitStep() = shot("01-first-run-kit-step", {
        RecoveryKitStepScreen(words = words, onPrint = {}, onContinue = {})
    }) {
        assertTrue(count("you can view them 3 more times from the menu") > 0)
        assertTrue(count("gone from this app for good") > 0)
        assertEquals(0, count("only time"))
    }

    @Test
    fun aNewListsKit() = shot("02-new-list-kit", {
        RecoveryKitScreen(words = words, onPrint = {}, onContinue = {})
    }) {
        assertTrue(count("you can view them 3 more times from the menu") > 0)
        assertTrue(count("the copy you write down will be the only one") > 0)
        assertEquals(0, count("only time"))
        compose.onNodeWithText("Continue").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aProtectedVaultsKit() = shot("03-protected-vault-kit", {
        RecoveryKitScreen(words = words, onPrint = {}, onContinue = {}, vaultName = "Papers")
    }) {
        assertTrue(count("Recovery kit for Papers") > 0)
        assertTrue(count("you can view them 3 more times from the menu") > 0)
        assertEquals(0, count("only time"))
    }

    @Test
    fun theNewVaultPassphraseStep() = shot("03b-new-vault-passphrase-step", {
        NewPassphraseStep(name = "Papers", draft = NewPassphrase("Papers"), onContinue = {})
    }) {
        assertTrue(count("so Papers gets a recovery kit of its own") > 0)
        assertEquals(0, count("shown once"))
    }

    // --- the drawer rows ------------------------------------------------------------

    @Composable
    private fun Drawer(vaultName: String?, listLeft: Int = 3, vaultLeft: Int? = null) {
        VaultDrawerSheet(
            vaultName = vaultName,
            owned = false,
            trialDaysRemaining = 12,
            trialStarted = true,
            onPurchase = {},
            currentIdentity = LauncherIdentity.Vault,
            onIdentity = {},
            biometricState = BiometricState.Off,
            onBiometric = {},
            faceDownAvailable = false,
            faceDownOn = false,
            faceDownLockAfter = 0L,
            onFaceDown = {},
            decoySetUp = false,
            onSetUpDecoy = {},
            onPanicCredential = {},
            listKitViewsLeft = listLeft,
            vaultKitViewsLeft = vaultLeft,
            onIntruderCapture = {},
            onExport = {},
            currentAppearance = ThemeChoice.Dark,
            onChooseAppearance = {},
        )
    }

    @Test
    fun listDrawerAtThree() = shot("04-list-drawer-3-left", { Drawer(null, listLeft = 3) }) {
        assertTrue(count("Recovery kit  ·  3 views left") > 0)
        assertTrue("after Panic credential", top("Recovery kit") > top("Panic credential"))
        assertTrue(top("Recovery kit") < top("Photo details"))
    }

    @Test
    fun listDrawerAtNone() = shot("05-list-drawer-none-left", { Drawer(null, listLeft = 0) }) {
        assertTrue(count("Recovery kit  ·  Can't be shown again") > 0)
    }

    @Test
    fun vaultDrawerAtThree() = shot("06-vault-drawer-3-left", { Drawer("Papers", vaultLeft = 3) }) {
        assertTrue(count("Recovery kit  ·  3 views left") > 0)
        assertTrue("above Unvault Everything", top("Recovery kit") < top("Unvault Everything"))
    }

    @Test
    fun vaultDrawerAtNone() = shot("07-vault-drawer-none-left", { Drawer("Papers", vaultLeft = 0) }) {
        assertTrue(count("Recovery kit  ·  Can't be shown again") > 0)
    }

    @Test
    fun aVaultWithoutItsOwnPassphraseHasNoRow() = shot("08-vault-drawer-no-row", { Drawer("Photos") }) {
        assertEquals(0, count("Recovery kit"))
    }

    // --- before a view --------------------------------------------------------------

    @Composable
    private fun View(left: Int, vaultName: String? = null, wrong: Boolean = false) {
        RecoveryKitViewScreen(
            viewsLeft = left,
            vaultName = vaultName,
            busy = false,
            wrongPassphrase = wrong,
            failed = false,
            onShow = { it.fill(0) },
            onDone = {},
        )
    }

    @Test
    fun beforeAViewAtThree() = shot("09-before-view-3-left", { View(3) }) {
        assertTrue(count("You can view your recovery kit 3 more times. Viewing it now uses one, leaving 2.") > 0)
        assertTrue(count("Enter your passphrase to show it.") > 0)
        compose.onNodeWithText("Show recovery kit").assertIsNotEnabled()
        compose.onNodeWithText("Your passphrase").performTextInput("correct horse battery")
        compose.onNodeWithText("Show recovery kit").assertIsEnabled()
        assertTrue(top("Your passphrase") < top("Show recovery kit"))
    }

    @Test
    fun beforeAViewAtTwo() = shot("10-before-view-2-left", { View(2) }) {
        assertTrue(count("2 more times. Viewing it now uses one, leaving 1.") > 0)
    }

    @Test
    fun beforeAViewAtOne() = shot("11-before-view-1-left", { View(1, vaultName = "Papers") }) {
        assertTrue(count("This is the last time your recovery kit can be shown.") > 0)
        assertTrue(count("Enter the passphrase for Papers to show it.") > 0)
        assertTrue(count("Passphrase for Papers") > 0)
        assertTrue("warned before the passphrase is asked for", top("last time") < top("Passphrase for Papers"))
    }

    @Test
    fun aWrongPassphrase() = shot("12-wrong-passphrase", { View(3, wrong = true) }) {
        assertTrue(count("That passphrase doesn't open this list.") > 0)
        assertTrue("under the field", top("doesn't open this list") > top("Your passphrase"))
        assertTrue(top("doesn't open this list") < top("Show recovery kit"))
    }

    // --- the words ------------------------------------------------------------------

    private fun review(left: Int) {
        assertTrue(count(KitStatus(left).line(ctx.words())) > 0)
        val firstWord = compose.onNode(hasText("1. ${words[0]}"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue("above the words", top(KitStatus(left).line(ctx.words())) < firstWord)
        assertEquals("no acknowledgement to type", 0, count("I understand"))
        assertEquals("the setup sentence is gone", 0, count("3 more times"))
        compose.onNodeWithText("Done").performScrollTo().assertIsEnabled()
        assertTrue(count("Print") > 0)
    }

    @Test
    fun wordsWithTwoLeft() = shot("13-words-2-left", {
        RecoveryKitScreen(words = words, onPrint = {}, onContinue = {}, status = KitStatus(2))
    }) {
        assertEquals("2 views left after this one.", KitStatus(2).line(ctx.words()))
        review(2)
    }

    @Test
    fun wordsWithOneLeft() = shot("14-words-1-left", {
        RecoveryKitScreen(words = words, onPrint = {}, onContinue = {}, status = KitStatus(1))
    }) {
        assertEquals("1 view left after this one.", KitStatus(1).line(ctx.words()))
        review(1)
    }

    @Test
    fun wordsOnTheLastView() = shot("15-words-last-view", {
        RecoveryKitScreen(words = words, onPrint = {}, onContinue = {}, status = KitStatus(0), vaultName = "Papers")
    }) {
        assertEquals("Last view — this can't be shown again.", KitStatus(0).line(ctx.words()))
        review(0)
    }

    // --- none left ------------------------------------------------------------------

    @Test
    fun noneLeft() = shot("16-none-left", { View(0) }) {
        assertTrue(count("Your recovery kit has been shown as many times as it can be.") > 0)
        assertTrue(count("Done") > 0)
        assertEquals("no field", 0, count("Your passphrase"))
        assertEquals(0, count("Show recovery kit"))
    }
}
