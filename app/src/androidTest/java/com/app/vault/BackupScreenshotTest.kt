package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.BackupScreen
import com.app.vault.ui.BackupStatus
import com.app.vault.ui.BackupStepScreen
import com.app.vault.ui.BiometricState
import com.app.vault.ui.RestoreState
import com.app.vault.ui.VaultDrawerSheet
import com.app.vault.ui.backupDrawerLine
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The backup screen's states as a user sees them — `backup-screen-status-first` task 7.1.
 *
 * [PhotoDetailsScreenshotTest]'s method: `FLAG_SECURE` cleared on this throwaway activity
 * only, captures from `uiAutomation` rather than `View.draw`, written through the
 * instrumentation shell to [DIR] because the run uninstalls the app.
 */
@RunWith(AndroidJUnit4::class)
class BackupScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    private fun shot(name: String, content: @Composable () -> Unit, check: () -> Unit = {}) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent { content() }
            }
            compose.waitForIdle()
            check()
            compose.waitForIdle()
            // A sheet settles after the frame that opened it.
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

    private companion object {
        const val DIR = "/data/local/tmp/backup-screen"
    }

    private fun has(text: String) =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** The emulator's own cloud apps and volumes, as the real list would find them. */
    private fun realDestinations(): List<CloudMirror.Destination> = CloudMirror.destinations(instr.targetContext)

    @Composable
    private fun Screen(
        status: BackupStatus,
        theme: ThemeChoice = ThemeChoice.Dark,
        restoreMode: Boolean = false,
        restoreState: RestoreState = RestoreState.Idle,
        file: Boolean = true,
        destinations: () -> List<CloudMirror.Destination> = { realDestinations() },
    ) {
        VaultTheme(theme) {
            BackupScreen(
                restoreMode = restoreMode,
                status = status,
                folderName = if (file) "Drive · backup.bin" else "SD card › Vault backup",
                fileDestination = file,
                storeBytes = 1_240_000_000L,
                problem = null,
                restoreState = restoreState,
                destinations = destinations,
                onChooseDestination = {},
                onBackUpNow = {},
                onStop = {},
                onChooseRestoreFile = {},
                onChooseRestoreFolder = {},
                onBack = {},
            )
        }
    }

    private val hoursAgo = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
    private val daysAgo = System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000L

    @Test fun off() = shot("01-off-dark", { Screen(BackupStatus.Off) }) {
        assertTrue(has("Backup is off"))
    }

    @Test fun offLight() = shot("02-off-light", { Screen(BackupStatus.Off, ThemeChoice.Light) })

    @Test fun turnOnSheet() = shot("03-turn-on-sheet", { Screen(BackupStatus.Off) }) {
        compose.onNodeWithText("Turn on backup").performClick()
        compose.waitForIdle()
        assertTrue(has("Before you turn on backup"))
    }

    /** The same sheet scrolled to its list — the destinations this emulator really has. */
    @Test fun turnOnSheetList() = shot("03b-back-up-to-list", { Screen(BackupStatus.Off) }) {
        compose.onNodeWithText("Turn on backup").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Not now").performScrollTo()
        assertTrue(has("Back up to"))
    }

    @Test fun emptyList() = shot("03c-back-up-to-empty", { Screen(BackupStatus.Off, destinations = { emptyList<CloudMirror.Destination>() }) }) {
        compose.onNodeWithText("Turn on backup").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Not now").performScrollTo()
        assertTrue(has("No cloud app or SD card"))
    }

    @Test fun changeDestination() = shot("03d-change-destination", { Screen(BackupStatus.UpToDate(System.currentTimeMillis())) }) {
        compose.onNodeWithText("Backed up to").performClick()
        compose.waitForIdle()
        assertTrue(has("Back up to"))
    }

    @Test fun checking() = shot("04-checking", { Screen(BackupStatus.Checking) })

    @Test fun upToDate() = shot("05-up-to-date-dark", { Screen(BackupStatus.UpToDate(hoursAgo)) }) {
        assertTrue(has("Up to date"))
    }

    @Test fun upToDateLight() =
        shot("06-up-to-date-light", { Screen(BackupStatus.UpToDate(hoursAgo), ThemeChoice.Light) })

    @Test fun behind() = shot("07-behind", { Screen(BackupStatus.Behind(12, daysAgo)) }) {
        assertTrue(has("12 changes to copy"))
    }

    @Test fun copying() = shot("08-copying", { Screen(BackupStatus.Copying(340_000_000L, 1_240_000_000L, bytes = true)) })

    @Test fun copyingFolder() = shot("08b-copying-folder", { Screen(BackupStatus.Copying(4, 12), file = false) })

    @Test fun partial() = shot("09-partial", { Screen(BackupStatus.Partial(3)) })

    @Test fun unreachable() = shot("10-unreachable-file", { Screen(BackupStatus.Unreachable(file = true)) })

    @Test fun unreachableFolder() =
        shot("10b-unreachable-folder", { Screen(BackupStatus.Unreachable(), file = false) })

    @Test fun noSpace() = shot("10c-no-space", { Screen(BackupStatus.NoSpace(400_000_000L)) })

    @Test fun upToDateFolder() =
        shot("06b-up-to-date-sd-card", { Screen(BackupStatus.UpToDate(hoursAgo), file = false) })

    @Test fun explained() = shot("11-how-backup-works", { Screen(BackupStatus.UpToDate(hoursAgo)) }) {
        compose.onNodeWithText("How backup works").performClick()
        compose.waitForIdle()
        assertTrue(has("What the provider can see"))
    }

    @Test fun stopDialog() = shot("12-stop-dialog", { Screen(BackupStatus.UpToDate(hoursAgo)) }) {
        compose.onNodeWithText("Stop backing up").performClick()
        compose.waitForIdle()
        assertTrue(has("Stop backing up this vault?"))
    }

    @Test fun restore() = shot("13-restore", { Screen(BackupStatus.Off, restoreMode = true) })

    @Test fun restoreCopying() = shot(
        "14-restore-copying",
        { Screen(BackupStatus.Off, restoreMode = true, restoreState = RestoreState.Copying(40, 212)) },
    )

    @Composable
    private fun Drawer(status: BackupStatus?) {
        VaultTheme(ThemeChoice.Dark) {
            VaultDrawerSheet(
                vaultName = "Photos",
                backupLine = backupDrawerLine(ctx.words(), status).first,
                backupAttention = backupDrawerLine(ctx.words(), status).second,
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
        }
    }

    @Test fun drawerOff() = shot("15-drawer-off", { Drawer(BackupStatus.Off) })

    @Test fun drawerUpToDate() = shot("16-drawer-up-to-date", { Drawer(BackupStatus.UpToDate(hoursAgo)) })

    @Test fun drawerUnreachable() = shot("17-drawer-unreachable", { Drawer(BackupStatus.Unreachable(file = true)) })

    @Composable
    private fun Step(status: BackupStatus, folder: String?) {
        VaultTheme(ThemeChoice.Dark) {
            BackupStepScreen(
                status = status,
                folderName = folder,
                storeBytes = 1_240_000_000L,
                problem = null,
                destinations = { realDestinations() },
                onChooseDestination = {},
                onContinue = {},
            )
        }
    }

    @Test fun stepBefore() = shot("18-step-before", { Step(BackupStatus.Off, null) })

    @Test fun stepAfter() =
        shot("19-step-after", { Step(BackupStatus.UpToDate(System.currentTimeMillis()), "Drive › Vault backup") })
}
