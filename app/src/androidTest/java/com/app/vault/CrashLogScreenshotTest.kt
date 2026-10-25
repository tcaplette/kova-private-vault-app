package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.BiometricState
import com.app.vault.ui.VaultDrawerSheet
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `debug-crash-log-screen`, task 4.4 — the drawer row, the list, a record, the confirmation and
 * the empty state after clearing, over records seeded into the real `files/crashes` with `run-as`
 * before the run. Run by hand with `am instrument`, not through the orchestrator, whose
 * `clearPackageData` would wipe the seeded records first.
 *
 * `RecoveryKitViewsScreenshotTest`'s method: `FLAG_SECURE` cleared on a throwaway activity,
 * captures from `uiAutomation`, saved to [DIR] — and cleared on the confirmation's own window too.
 * The Clear at the end is what removes the seed.
 */
@RunWith(AndroidJUnit4::class)
class CrashLogScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    private companion object {
        const val DIR = "/data/local/tmp/debug-crash-log-screen"
    }

    @Test
    fun theWholeWalk() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent {
                    VaultTheme(ThemeChoice.Dark) {
                        var screen by remember { mutableStateOf(false) }
                        var record by remember { mutableStateOf<String?>(null) }
                        var confirming by remember { mutableStateOf(false) }
                        if (!screen) {
                            VaultDrawerSheet(
                                onRecoverVault = {},
                                onRestoreVault = {},
                                onImportVault = {},
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
                                onCrashLog = { screen = true },
                                crashLogIcon = CrashLogHook.icon,
                                crashLogLine = CrashLogHook.summary(ctx),
                                currentAppearance = ThemeChoice.Dark,
                                onChooseAppearance = {},
                            )
                        } else {
                            CrashLogHook.Screen(
                                record = record,
                                onOpen = { record = it },
                                confirmingClear = confirming,
                                onAskClear = { confirming = true },
                                onDismissClear = { confirming = false },
                                onBack = { if (record != null) record = null else screen = false },
                            )
                        }
                    }
                }
            }
            compose.onNodeWithText("Crash log").performScrollTo()
            await("3 recorded")
            shot("01-drawer-row")

            compose.onNodeWithText("Crash log").performClick()
            await("Newest first")
            shot("02-list")

            compose.onNodeWithText("IllegalStateException").performClick()
            await("Stack trace")
            shot("03-record")

            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Clear all").performClick()
            await("Clear 3 crash records?")
            // The dialog is its own window with `SecureOn`, which the activity's cleared flag
            // does not reach; cleared here, for the capture only.
            val dialog = compose.onNode(isDialog()).fetchSemanticsNode().root as ViewRootForTest
            instr.runOnMainSync {
                generateSequence(dialog.view.parent) { it.parent }
                    .filterIsInstance<DialogWindowProvider>().first()
                    .window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            shot("04-confirmation")

            compose.onNodeWithText("Clear").performClick()
            await("No crashes recorded")
            shot("05-empty")
            assertEquals(0, DebugCrashLog.directory(ctx).listFiles()?.size ?: 0)
        }
    }

    private fun await(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        val png = java.io.ByteArrayOutputStream().also {
            instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val ui = instr.uiAutomation
        ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
        // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a
        // large PNG (the keyboard up) fills the pipes and both ends block. HangWatch caught it.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        drain(stdout)
    }

    private fun drain(fd: android.os.ParcelFileDescriptor) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}
