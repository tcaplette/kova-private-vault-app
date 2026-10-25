package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.AddPassphraseStep
import com.app.vault.ui.DiscardFlowDialog
import com.app.vault.ui.NameStep
import com.app.vault.ui.NewPassphrase
import com.app.vault.ui.NewPassphraseStep
import com.app.vault.ui.NewVaultStep
import com.app.vault.ui.OpensStep
import com.app.vault.ui.RecoveryKitScreen
import com.app.vault.ui.StepFrame
import com.app.vault.ui.VaultCaptureStep
import com.app.vault.ui.theme.VaultTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `new-vault-setup-flow`, task 7.4 — every step of New vault and Add a passphrase as a user sees
 * it, in the dark and the light scheme, in the frame the activity draws them in.
 *
 * `RecoveryKitViewsScreenshotTest`'s method: `FLAG_SECURE` cleared on a throwaway activity,
 * captures from `uiAutomation`, written with `dd` to [DIR] on the emulator. Composed directly — no
 * vault is created and nothing is written to the app's storage.
 */
@RunWith(AndroidJUnit4::class)
class VaultSetupFlowScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val words = Mnemonic.fromResources(instr.targetContext)
        .encode(ByteArray(Mnemonic.ENTROPY_BYTES) { (it * 37 + 11).toByte() })
    private val look = VaultAppearance(FolderColour.entries[2], 3)
    private val papers = VaultKeyring.Entry(
        0, "Papers", ByteArray(72), ByteArray(16), appearance = VaultAppearance(FolderColour.entries[4], 0).pack(),
    )

    private companion object {
        const val DIR = "/data/local/tmp/new-vault-setup-flow"
        const val OWN = "lantern quarry obsidian meadow"
    }

    private fun shots(name: String, content: @Composable () -> Unit) {
        listOf(ThemeChoice.Dark, ThemeChoice.Light).forEach { theme ->
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    activity.setContent { VaultTheme(theme) { content() } }
                }
                compose.waitForIdle()
                Thread.sleep(600)
                val png = java.io.ByteArrayOutputStream().also {
                    instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
                }.toByteArray()
                save("$name-${theme.name.lowercase()}.png", png)
            }
        }
    }

    private fun save(name: String, bytes: ByteArray) {
        val ui = instr.uiAutomation
        ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
        // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a large
        // PNG fills the pipes and both ends block.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(bytes) }
        drain(stdout)
    }

    private fun drain(fd: android.os.ParcelFileDescriptor) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    /** The frame as the activity draws it: no Back on a first step, neither control on the kit. */
    @Composable
    private fun Frame(step: NewVaultStep, steps: List<NewVaultStep>, content: @Composable () -> Unit) {
        val at = steps.indexOf(step)
        StepFrame(
            position = at + 1,
            total = steps.size,
            onBack = if (at > 0 && step != NewVaultStep.Kit) ({}) else null,
            onCancel = if (step != NewVaultStep.Kit) ({}) else null,
            cancelDescription = stringResource(R.string.action_cancel),
            content = content,
        )
    }

    private val five = NewVaultStep.shown(own = true)
    private val two = NewVaultStep.shown(own = false)

    private fun typed() = NewPassphrase("Receipts").apply { new = OWN; again = OWN }

    // --- New vault -----------------------------------------------------------------------------

    @Test fun nameAndLook() = shots("01-name") {
        Frame(NewVaultStep.Name, two) {
            NameStep("Receipts", {}, look, {}, { true }, error = null, onContinue = {})
        }
    }

    @Test fun nameAndLookRefusedForAFullList() = shots("01b-name-full") {
        Frame(NewVaultStep.Name, two) {
            NameStep(
                "Receipts", {}, look, {}, { true },
                error = "This list is full. Remove a vault to make room.", onContinue = {},
            )
        }
    }

    @Test fun howItOpensWithTheList() = shots("02a-opens-list") {
        Frame(NewVaultStep.Opens, two) { OpensStep("Receipts", own = false, onOwn = {}, busy = false, problem = null) {} }
    }

    @Test fun howItOpensWithItsOwnPassphrase() = shots("02b-opens-own") {
        Frame(NewVaultStep.Opens, five) { OpensStep("Receipts", own = true, onOwn = {}, busy = false, problem = null) {} }
    }

    @Test fun itsPassphrase() = shots("03-passphrase") {
        Frame(NewVaultStep.Passphrase, five) { NewPassphraseStep("Receipts", typed()) {} }
    }

    @Test fun intruderCaptureWithPhotoOnAndTheBanner() = shots("04-capture") {
        Frame(NewVaultStep.Capture, five) {
            VaultCaptureStep(
                name = "Receipts",
                enabled = setOf(Capture.Mode.PHOTO),
                hasFrontCamera = true,
                onEnable = {},
                onDisable = {},
                autoResetRisk = true,
                onKeepPermissions = {},
                problem = null,
                actionLabel = "Create vault",
                busyLabel = "Creating…",
                busy = false,
                onCommit = {},
            )
        }
    }

    @Test fun theKit() = shots("05-kit") {
        Frame(NewVaultStep.Kit, five) {
            RecoveryKitScreen(words = words, onPrint = {}, onContinue = {}, vaultName = "Receipts")
        }
    }

    @Test fun theDiscardDialog() = shots("06-discard") {
        Frame(NewVaultStep.Passphrase, five) { NewPassphraseStep("Receipts", typed()) {} }
        DiscardFlowDialog("Discard this vault?", "Nothing has been created yet.", onKeep = {}, onDiscard = {})
    }

    // --- Add a passphrase ----------------------------------------------------------------------

    @Test fun addStepOneWithTheWrongListError() = shots("07-add-wrong-list") {
        Frame(NewVaultStep.Passphrase, NewVaultStep.ADD) {
            AddPassphraseStep(
                vault = papers,
                list = "a different passphrase",
                onList = {},
                wrong = true,
                draft = NewPassphrase("Papers").apply { new = OWN; again = OWN },
                busy = false,
                onProve = {},
            )
        }
    }

    @Test fun addCapture() = shots("08-add-capture") {
        Frame(NewVaultStep.Capture, NewVaultStep.ADD) {
            VaultCaptureStep(
                name = "Papers",
                enabled = emptySet(),
                hasFrontCamera = true,
                onEnable = {},
                onDisable = {},
                autoResetRisk = true,
                onKeepPermissions = {},
                problem = null,
                actionLabel = "Add passphrase",
                busyLabel = "Adding…",
                busy = false,
                onCommit = {},
            )
        }
    }

    @Test fun addKit() = shots("09-add-kit") {
        Frame(NewVaultStep.Kit, NewVaultStep.ADD) {
            RecoveryKitScreen(words = words, onPrint = {}, onContinue = {}, vaultName = "Papers")
        }
    }

    @Test fun addDiscardDialog() = shots("10-add-discard") {
        Frame(NewVaultStep.Passphrase, NewVaultStep.ADD) {
            AddPassphraseStep(papers, "", {}, false, NewPassphrase("Papers").apply { new = OWN }, false) {}
        }
        DiscardFlowDialog(
            "Discard this passphrase?", "Papers still opens with your vault list's passphrase.",
            onKeep = {}, onDiscard = {},
        )
    }
}
