package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.ArchiveImportScreen
import com.app.vault.ui.ImportKind
import com.app.vault.ui.BiometricState
import com.app.vault.ui.RecoverScreen
import com.app.vault.ui.VaultDrawerSheet
import com.app.vault.ui.VaultUnlockScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `recovery-own-slot-only`, task 5.2 — the new-passphrase step, the import screen, the lock
 * screen and the list's drawer as a user sees them.
 *
 * `PanicScreenshotTest`'s method: `FLAG_SECURE` cleared on this throwaway activity, captures
 * from `uiAutomation`, saved to [DIR] on the emulator. Composed directly — no vault is
 * created and nothing is written to the app's storage.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryOwnSlotScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val mnemonic = Mnemonic.fromResources(instr.targetContext)

    private companion object {
        const val DIR = "/data/local/tmp/recovery-own-slot-only"
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

    // --- recovery -----------------------------------------------------------------

    private var busy by mutableStateOf(false)
    private var failed by mutableStateOf(false)

    @Composable
    private fun Recover(restoring: Boolean = false) {
        RecoverScreen(
            mnemonic = mnemonic,
            onRecover = { _, bytes -> bytes.fill(0) },
            onCancel = {},
            busy = busy,
            failed = failed,
            restoring = restoring,
        )
    }

    /** Types a valid kit into the first box — it spreads across all 24 — and continues. */
    private fun toStepTwo() {
        val words = mnemonic.encode(ByteArray(Mnemonic.ENTROPY_BYTES)).joinToString(" ")
        compose.onNodeWithText("1").performScrollTo().performTextInput(words)
        compose.waitForIdle()
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun stepTwoCarriesOneJob() {
        assertTrue(count("Choose a new passphrase") > 0)
        assertTrue(count("New passphrase") > 0)
        assertTrue(count("Enter it again") > 0)
        assertTrue(count("Set passphrase") > 0)
        assertTrue("helper text states the floor", count(ctx.getString(R.string.passphrase_requirement, PassphraseStrength.MIN_CODE_POINTS)) > 0)
        listOf("keep", "destroy", "interrupt", "decoy", "panic", "Add this passphrase").forEach {
            assertEquals("the step says nothing about \"$it\"", 0, count(it))
        }
    }

    @Test
    fun stepTwoEmpty() = shot("01-step2-empty", { Recover() }) {
        toStepTwo()
        stepTwoCarriesOneJob()
        assertTrue(count("your old one stops working") > 0)
        assertTrue(count("Keep the paper") > 0)
        assertTrue(top("New passphrase") < top("Enter it again"))
        assertTrue(top("Enter it again") < top("Set passphrase"))
    }

    @Test
    fun stepTwoOnRestore() = shot("02-step2-restore", { Recover(restoring = true) }) {
        toStepTwo()
        stepTwoCarriesOneJob()
        assertTrue(count("this phone will use to open your vaults") > 0)
        assertEquals(0, count("stops working"))
    }

    @Test
    fun stepTwoMismatch() = shot("03-step2-mismatch", { Recover() }) {
        toStepTwo()
        compose.onNodeWithText("New passphrase").performTextInput("correct horse battery")
        compose.onNodeWithText("Enter it again").performTextInput("correct horse batery")
        compose.onNodeWithText("Set passphrase").performClick()
        compose.waitForIdle()
        assertTrue("the mismatch sits under the second field", top("don't match") > top("Enter it again"))
        assertTrue(top("don't match") < top("Set passphrase"))
    }

    @Test
    fun stepTwoBusy() = shot("04-step2-busy", { Recover() }) {
        toStepTwo()
        compose.onNodeWithText("New passphrase").performTextInput("correct horse battery")
        compose.onNodeWithText("Enter it again").performTextInput("correct horse battery")
        busy = true
        compose.waitForIdle()
        compose.onNodeWithText("Setting passphrase").assertIsNotEnabled()
        // The compose rule suspends infinite animations, so the capture shows the indicator's
        // first frame — a short arc. Its presence is asserted here instead of eyeballed.
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun aWordsFailureReturnsToTheWords() = shot("05-step1-words-failure", { Recover() }) {
        toStepTwo()
        compose.onNodeWithText("New passphrase").performTextInput("correct horse battery")
        failed = true
        compose.waitForIdle()
        assertTrue("back on the words", count("Enter your kit") > 0)
        assertEquals("the passphrase step is gone", 0, count("Set passphrase"))
        // Compared on screen, against the last word box — its label is exactly "24".
        compose.onNode(hasText("Those words do not open", substring = true)).performScrollTo()
        compose.waitForIdle()
        val lastBox = compose.onNode(hasText("24"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue("under the words", top("Those words do not open") > lastBox)
        // The 24 words were kept: Continue is live again without retyping them. Scrolled to,
        // with the message just above it, so both are placed when compared.
        compose.onNodeWithText("Continue").performScrollTo().assertIsEnabled()
        compose.waitForIdle()
        val error = top("Those words do not open")
        val action = top("Continue")
        assertTrue("above the action: error $error, Continue $action", error in 1f..action)
    }

    // --- import -------------------------------------------------------------------

    private var file by mutableStateOf<String?>(null)
    private var full by mutableStateOf(false)
    private var progress by mutableStateOf<Pair<Int, Int>?>(null)

    @Composable
    private fun Import() {
        ArchiveImportScreen(
            mnemonic = mnemonic,
            onPickFile = {},
            onPickFolder = {},
            onImport = { it.fill(0) },
            onRestoreThisList = {},
            onWords = {},
            onRestoreAsList = { _, b -> b.fill(0) },
            onBackToWords = {},
            onDone = {},
            onCancel = {},
            fileChosen = file,
            busy = busy,
            failed = failed,
            progress = progress,
            full = full,
            // `backup-file-recover-and-import`: a chosen `.vault` is read as one first.
            kind = if (file != null) ImportKind.VAULT_FILE else null,
        )
    }

    private fun importCarriesOneJob() {
        assertTrue(count("Passphrase for this file") > 0)
        assertTrue(count("Import vault") + count("Importing") > 0)
        listOf("New passphrase", "keep", "destroy", "interrupt").forEach {
            assertEquals("the screen says nothing about \"$it\"", 0, count(it))
        }
    }

    @Test
    fun importEmpty() = shot("06-import-empty", { Import() }) {
        assertTrue(count("Choose a file") > 0)
        assertEquals("nothing is asked before a file is chosen", 0, count("Passphrase for this file"))
    }

    @Test
    fun importFileChosen() = shot("07-import-file-chosen", { Import() }) {
        file = "holiday-photos.vault"
        compose.waitForIdle()
        importCarriesOneJob()
        assertTrue(count("holiday-photos.vault") > 0)
        assertEquals(0, count("Choose a file"))
        compose.onNodeWithText("Import vault").assertIsNotEnabled()
    }

    @Test
    fun importing() = shot("08-importing", { Import() }) {
        file = "holiday-photos.vault"
        compose.onNodeWithText("Passphrase for this file").performTextInput("the archive passphrase")
        busy = true
        progress = 37 to 120
        compose.waitForIdle()
        compose.onNodeWithText("Importing").assertIsNotEnabled()
        assertTrue(count("37 of 120") > 0)
    }

    @Test
    fun importListFull() = shot("09-import-list-full", { Import() }) {
        file = "holiday-photos.vault"
        compose.onNodeWithText("Passphrase for this file").performTextInput("the archive passphrase")
        full = true
        compose.waitForIdle()
        assertTrue(count("This list is full") > 0)
        assertTrue("under the passphrase field", top("This list is full") > top("Passphrase for this file"))
        assertTrue(top("This list is full") < top("Import vault"))
    }

    // --- the lock screen and the list's drawer --------------------------------------

    @Test
    fun lockScreenHasNoImport() = shot(
        "10-lock-screen",
        { VaultUnlockScreen(onUnlock = { it.fill(0) }, busy = false, failed = false, onRecover = {}) },
    ) {
        assertEquals(0, count("Import"))
        assertTrue(count("Forgot passphrase?") > 0)
    }

    @Test
    fun listDrawerOffersImport() = shot(
        "11-list-drawer",
        {
            VaultDrawerSheet(
                onRecoverVault = {},
                onRestoreVault = {},
                onImportVault = {},
                vaultName = null,
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
                onIntruderCapture = {},
                onExport = {},
                currentAppearance = ThemeChoice.Dark,
                onChooseAppearance = {},
            )
        },
    ) {
        compose.onNodeWithText("Import a vault from a file").performScrollTo()
        assertTrue(
            "beneath Restore a vault from a backup",
            top("Import a vault from a file") > top("Restore a vault from a backup"),
        )
    }
}
