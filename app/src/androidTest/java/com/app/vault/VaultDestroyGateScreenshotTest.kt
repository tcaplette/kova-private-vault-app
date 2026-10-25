package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.DestroyStep
import com.app.vault.ui.ExportScreen
import com.app.vault.ui.VaultDestroyDialog
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `protected-vault-destroy-gate`, task 7.3 — the gate in each state, the choice, and the export
 * screen's two new forms, in the dark and the light scheme.
 *
 * `CrashLogScreenshotTest`'s method: `FLAG_SECURE` cleared on a throwaway activity and on the
 * dialog's own window, captures from `uiAutomation`, written with `dd` to [DIR] on the emulator.
 * Composed directly — no vault is created and nothing is written to the app's storage.
 */
@RunWith(AndroidJUnit4::class)
class VaultDestroyGateScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()

    private companion object {
        const val DIR = "/data/local/tmp/protected-vault-destroy-gate"
    }

    private enum class Shot { GateList, GateOwn, Checking, Wrong, Choice, ExportAuthorised, ExportOwn }

    private var showing by mutableStateOf(Shot.GateList)

    @Test fun dark() = walk(ThemeChoice.Dark, "dark")

    @Test fun light() = walk(ThemeChoice.Light, "light")

    private fun walk(theme: ThemeChoice, scheme: String) {
        showing = Shot.GateList
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent { VaultTheme(theme) { Content() } }
            }

            await("enter your vault list's passphrase")
            assertEquals("no lost line for an unprotected vault", 0, count("Recover a vault with its kit"))
            shotDialog("$scheme-01-gate-unprotected")

            showing = Shot.GateOwn
            await("its own, not your vault list's")
            assertTrue("the lost line names where to enter the words", count("Recover a vault with its kit") > 0)
            assertTrue("the field says which passphrase", count("Passphrase") > 0)
            shotDialog("$scheme-02-gate-protected-from-list")

            compose.onNode(hasSetTextAction()).performTextInput("lantern quarry obsidian")
            showing = Shot.Checking
            await("Checking…")
            shotDialog("$scheme-03-gate-checking")

            showing = Shot.Wrong
            await("That passphrase did not open Papers.")
            assertTrue("what was typed is kept", count("•") > 0 || count("lantern") > 0)
            shotDialog("$scheme-04-gate-wrong")

            showing = Shot.Choice
            await("Destroy everything in it")
            assertTrue("the backup line is named before the choice", count("Its backup folder is emptied too") > 0)
            shotDialog("$scheme-05-choice-with-backup")

            showing = Shot.ExportAuthorised
            await("When every file is out and checked, Papers is destroyed.")
            assertEquals("no passphrase field", 0, compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
            assertEquals("no archive", 0, count("Save encrypted archive"))
            shot("$scheme-06-export-authorised")

            showing = Shot.ExportOwn
            await("Enter this vault's own passphrase to continue.")
            shot("$scheme-07-export-own-passphrase")
        }
    }

    @androidx.compose.runtime.Composable
    private fun Content() {
        when (val s = showing) {
            Shot.ExportAuthorised, Shot.ExportOwn -> ExportScreen(
                itemCount = 12,
                onExport = { b, _, _ -> b.fill(0) },
                onPickFolder = {},
                onCancel = {},
                folderChosen = null,
                busy = false,
                failed = false,
                progress = null,
                authorised = s == Shot.ExportAuthorised,
                vaultName = "Papers",
                ownPassphrase = s == Shot.ExportOwn,
            )
            else -> VaultDestroyDialog(
                name = if (s == Shot.GateList) "My vault" else "Papers",
                hasBackup = true,
                onReturnThenDestroy = {},
                onDestroyEverything = {},
                onDismiss = {},
                step = if (s == Shot.Choice) DestroyStep.Choice else DestroyStep.Gate,
                ownPassphrase = s != Shot.GateList,
                showLost = s != Shot.GateList,
                checking = s == Shot.Checking,
                wrong = s == Shot.Wrong,
                onSubmit = { it.fill(0) },
            )
        }
    }

    private fun count(text: String): Int =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun await(text: String) = compose.waitUntil(10_000) { count(text) > 0 }

    /** The dialog is its own window with `SecureOn`, which the activity's cleared flag does not reach. */
    private fun shotDialog(name: String) {
        compose.waitForIdle()
        val dialog = compose.onNode(isDialog()).fetchSemanticsNode().root as ViewRootForTest
        instr.runOnMainSync {
            generateSequence(dialog.view.parent) { it.parent }
                .filterIsInstance<DialogWindowProvider>().first()
                .window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        shot(name)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        // Past the 200 ms crossfade and the keyboard's own animation.
        val until = android.os.SystemClock.elapsedRealtime() + 800
        compose.waitUntil(5_000) { android.os.SystemClock.elapsedRealtime() > until }
        val png = java.io.ByteArrayOutputStream().also {
            instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val ui = instr.uiAutomation
        ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
        // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a
        // large PNG (the keyboard up) fills the pipes and both ends block.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        drain(stdout)
    }

    private fun drain(fd: android.os.ParcelFileDescriptor) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}
