package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.BackupScreen
import com.app.vault.ui.BackupStatus
import com.app.vault.ui.NameStep
import com.app.vault.ui.OnboardingFrame
import com.app.vault.ui.OnboardingWelcomeScreen
import com.app.vault.ui.RestoreFile
import com.app.vault.ui.RestoreState
import com.app.vault.ui.theme.VaultTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `switch-builds-without-losing-vaults`, task 6.2 — the welcome, the first-run restore screen
 * idle, with a `.vault` chosen, copying and refused, and step 1 with the restored line, in the
 * dark and the light scheme.
 *
 * `VaultSetupFlowScreenshotTest`'s method: `FLAG_SECURE` cleared on a throwaway activity, captures
 * from `uiAutomation`, written with `dd` to [DIR] on the emulator. Composed directly — nothing is
 * read or written in the app's storage.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunRestoreScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()

    private companion object {
        const val DIR = "/data/local/tmp/switch-builds"
        const val FILE = "family.vault"
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

    @Composable
    private fun Restore(state: RestoreState = RestoreState.Idle, file: RestoreFile? = null) {
        BackupScreen(
            restoreMode = true,
            status = BackupStatus.Checking,
            folderName = null,
            fileDestination = false,
            storeBytes = 0L,
            problem = null,
            restoreState = state,
            destinations = { emptyList() },
            onChooseDestination = {},
            onBackUpNow = {},
            onStop = {},
            onChooseRestoreFile = {},
            onChooseRestoreFolder = {},
            onBack = {},
            restoreFile = file,
        )
    }

    @Test fun welcome() = shots("1-welcome") {
        OnboardingWelcomeScreen(onBegin = {}, onRestore = {}, onLanguage = {})
    }

    @Test fun restoreIdle() = shots("2-restore-idle") { Restore() }

    @Test fun vaultChosen() = shots("3-vault-chosen") { Restore(file = RestoreFile(FILE, vault = true)) }

    @Test fun copying() = shots("4-copying") {
        Restore(state = RestoreState.Copying(37, 214), file = RestoreFile(FILE, vault = true))
    }

    @Test fun refused() = shots("5-refused") {
        Restore(file = RestoreFile(FILE, vault = true, problem = stringResource(R.string.import_failed)))
    }

    @Test fun stepOne() = shots("6-step-one") {
        OnboardingFrame(step = Onboarding.Step.Name, onBack = null) {
            NameStep(
                name = "", onName = {},
                appearance = VaultAppearance.defaultFor(0), onAppearance = {},
                accepts = { true }, error = null, onContinue = {},
                restored = pluralStringResource(R.plurals.restore_from_file, 214, FILE, 214),
            )
        }
    }
}
