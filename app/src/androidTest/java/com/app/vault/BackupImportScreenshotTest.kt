package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import com.app.vault.ui.ImportDone
import com.app.vault.ui.ImportKind
import com.app.vault.ui.PassphraseRequirement
import com.app.vault.ui.RecoverScreen
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `backup-file-recover-and-import`, task 6.2 — Import (D7) and Recover's words step (D8) as a
 * user sees them. `RecoveryOwnSlotScreenshotTest`'s method: `FLAG_SECURE` cleared on this
 * throwaway activity, real captures from `uiAutomation`, saved to [DIR] on the emulator.
 * Composed directly — nothing is written to the app's storage.
 */
@RunWith(AndroidJUnit4::class)
class BackupImportScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val mnemonic = Mnemonic.fromResources(instr.targetContext)
    private val kit = mnemonic.encode(ByteArray(Mnemonic.ENTROPY_BYTES)).joinToString(" ")

    private companion object {
        const val DIR = "/data/local/tmp/backup-file-recover-and-import"
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
            val ui = instr.uiAutomation
            ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
            // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a
            // large PNG (the keyboard up) fills the pipes and both ends block. HangWatch caught it.
            val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name.png")
            android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
            drain(stdout)
        }
    }

    private fun drain(fd: android.os.ParcelFileDescriptor) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private fun count(text: String): Int =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun top(text: String): Float =
        // Unclipped: `boundsInRoot` reads 0 for a node scrolled below the fold.
        compose.onNode(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNode().positionInRoot.y

    private fun typeKit() {
        compose.onNodeWithText("1").performScrollTo().performTextInput(kit)
        compose.waitForIdle()
    }

    // --- import -------------------------------------------------------------------

    private var file by mutableStateOf<String?>(null)
    private var kind by mutableStateOf<ImportKind?>(null)
    private var folder by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var progress by mutableStateOf<Pair<Int, Int>?>(null)
    private var sealing by mutableStateOf(false)
    private var wordsFailed by mutableStateOf(false)
    private var needsPassphrase by mutableStateOf(false)
    private var noRoom by mutableStateOf(false)
    private var done by mutableStateOf<ImportDone?>(null)

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
            failed = false,
            progress = progress,
            kind = kind,
            folder = folder,
            wordsFailed = wordsFailed,
            needsPassphrase = needsPassphrase,
            noRoom = noRoom,
            sealing = sealing,
            done = done,
        )
    }

    private fun choose(name: String, k: ImportKind, isFolder: Boolean = false) {
        file = name
        kind = k
        folder = isFolder
        compose.waitForIdle()
    }

    @Test
    fun importChoose() = shot("01-import-choose", { Import() }) {
        assertTrue(count("Import a vault") > 0)
        assertTrue(count("from a backup saved to your cloud app or an SD card") > 0)
        assertTrue(top("Choose a file") < top("Backup is on an SD card"))
        listOf("Passphrase for this file", "24 words", "Restore vault").forEach { assertEquals(0, count(it)) }
    }

    @Test
    fun importVaultChosen() = shot("02-import-vault-chosen", { Import() }) {
        choose("holiday-photos.vault", ImportKind.VAULT_FILE)
        assertTrue(count(".vault file") > 0)
        assertEquals("both actions became the row", 0, count("Choose a file"))
        assertTrue(top("holiday-photos.vault") < top("Passphrase for this file"))
        compose.onNodeWithText("Import vault").assertIsNotEnabled()
    }

    @Test
    fun importFolderChosen() = shot("03-import-folder-chosen", { Import() }) {
        choose("SD card › Backups", ImportKind.THIS_LIST, isFolder = true)
        assertTrue(count("SD card backup") > 0)
        assertEquals(0, count("Backup is on an SD card"))
    }

    @Test
    fun importThisListBackup() = shot("04-import-this-list", { Import() }) {
        choose("backup.bin", ImportKind.THIS_LIST)
        assertTrue(count("Backup file") > 0)
        assertTrue(count("This is a backup of a vault on this list.") > 0)
        compose.onNodeWithText("Restore vault").assertIsEnabled()
        listOf("Passphrase for this file", "24 words").forEach { assertEquals("asks for nothing", 0, count(it)) }
    }

    @Test
    fun importAnotherListWithWords() = shot("05-import-other-list-words", { Import() }) {
        choose("backup.bin", ImportKind.OTHER_LIST)
        assertTrue(count("This backup is from another vault list") > 0)
        typeKit()
        wordsFailed = true
        compose.waitForIdle()
        compose.onNode(hasText("Those words don't open this backup.")).performScrollTo()
        val lastBox = compose.onNode(hasText("24"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue("under the words", top("Those words don't open this backup.") > lastBox)
        compose.onNodeWithText("Continue").performScrollTo().assertIsEnabled()
    }

    @Test
    fun importPassphraseStep() = shot("06-import-passphrase-step", { Import() }) {
        choose("backup.bin", ImportKind.OTHER_LIST)
        needsPassphrase = true
        compose.waitForIdle()
        assertTrue(count("It comes back as its own vault list") > 0)
        assertTrue(count(ctx.getString(R.string.passphrase_requirement, PassphraseStrength.MIN_CODE_POINTS)) > 0)
        assertTrue(top("New passphrase") < top("Enter it again"))
        assertTrue(top("Enter it again") < top("Restore vault list"))
        assertEquals("the chosen file is behind this step", 0, count("backup.bin"))
    }

    @Test
    fun importCopying() = shot("07-import-copying", { Import() }) {
        choose("backup.bin", ImportKind.OTHER_LIST)
        needsPassphrase = true
        compose.onNodeWithText("New passphrase").performTextInput("correct horse battery")
        compose.onNodeWithText("Enter it again").performTextInput("correct horse battery")
        busy = true
        progress = 37 to 120
        compose.waitForIdle()
        compose.onNodeWithText("Restore vault list").assertIsNotEnabled()
        assertTrue(count("Copying files… 37 of 120") > 0)
        assertTrue(top("Restore vault list") < top("Copying files"))
    }

    @Test
    fun importSettingPassphrase() = shot("08-import-setting-passphrase", { Import() }) {
        choose("backup.bin", ImportKind.OTHER_LIST)
        needsPassphrase = true
        busy = true
        progress = 120 to 120
        sealing = true
        compose.waitForIdle()
        assertTrue(count("Setting passphrase") > 0)
        assertEquals(0, count("Copying files"))
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate), useUnmergedTree = true).assertExists()
    }

    @Test
    fun importListRestored() = shot("09-import-list-restored", { Import() }) {
        choose("backup.bin", ImportKind.OTHER_LIST)
        done = ImportDone.LIST_RESTORED
        compose.waitForIdle()
        assertTrue(count("Vault list restored") > 0)
        assertTrue(count("Lock the app and enter its passphrase to open it.") > 0)
        assertTrue(top("Vault list restored") < top("Done"))
    }

    @Test
    fun importVaultRestored() = shot("10-import-vault-restored", { Import() }) {
        choose("backup.bin", ImportKind.OTHER_LIST)
        done = ImportDone.VAULT_RESTORED
        compose.waitForIdle()
        assertTrue(count("Vault restored") > 0)
        assertTrue(count("It's back on its vault list.") > 0)
    }

    @Test
    fun importNoRoom() = shot("11-import-no-room", { Import() }) {
        choose("backup.bin", ImportKind.OTHER_LIST)
        needsPassphrase = true
        compose.onNodeWithText("New passphrase").performTextInput("correct horse battery")
        compose.onNodeWithText("Enter it again").performTextInput("correct horse battery")
        compose.onNodeWithText("Restore vault list").performClick()
        noRoom = true
        compose.waitForIdle()
        assertTrue(top("There's no room for another vault list on this phone.") > top("Enter it again"))
        assertTrue(top("There's no room") < top("Restore vault list"))
    }

    // --- recover --------------------------------------------------------------------

    private var backup by mutableStateOf<String?>(null)
    private var backupFolder by mutableStateOf(false)
    private var backupProblem by mutableStateOf<String?>(null)
    private var removable by mutableStateOf(true)

    @Composable
    private fun Recover() {
        RecoverScreen(
            mnemonic = mnemonic,
            onRecover = { _, b -> b.fill(0) },
            onCancel = {},
            busy = busy,
            failed = false,
            backupName = backup,
            backupFolder = backupFolder,
            onRemoveBackup = if (removable) ({ backup = null }) else null,
            backupProblem = backupProblem,
            progress = progress,
            sealing = sealing,
        )
    }

    @Test
    fun recoverWordsStepOffersABackup() = shot("12-recover-both-actions", { Recover() }) {
        assertTrue(count("Type the 24 words from your recovery kit, one to a box.") > 0)
        assertEquals("the lead no longer says files cannot come back", 0, count("does not bring back files"))
        compose.onNodeWithText("Add a backup file").performScrollTo()
        compose.waitForIdle()
        val add = top("Add a backup file")
        val sd = top("Backup is on an SD card")
        val helper = top("Lost files too?")
        val action = top("Continue")
        assertTrue("add $add, sd $sd, helper $helper, continue $action", add < sd && sd < helper && helper < action)
        compose.onNodeWithText("Continue").performScrollTo()
    }

    @Test
    fun recoverWithAFile() = shot("13-recover-file-row", { Recover() }) {
        backup = "backup.bin"
        compose.waitForIdle()
        compose.onNodeWithText("Remove").performScrollTo()
        assertTrue(count("Backup file") > 0)
        assertEquals(0, count("Add a backup file"))
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()
        assertTrue("Remove brings the actions back", count("Add a backup file") > 0)
        backup = "backup.bin"
        compose.waitForIdle()
        compose.onNodeWithText("Continue").performScrollTo()
    }

    @Test
    fun recoverWithAFolder() = shot("14-recover-folder-row", { Recover() }) {
        backup = "SD card › Backups"
        backupFolder = true
        compose.waitForIdle()
        compose.onNodeWithText("Remove").performScrollTo()
        assertTrue(count("SD card backup") > 0)
        compose.onNodeWithText("Continue").performScrollTo()
    }

    @Test
    fun recoverRestoringHidesRemove() = shot("15-recover-restore-path", { Recover() }) {
        backup = "backup.bin"
        removable = false
        compose.waitForIdle()
        compose.onNodeWithText("backup.bin").performScrollTo()
        assertEquals(0, count("Remove"))
        compose.onNodeWithText("Continue").performScrollTo()
    }

    private fun refusal(name: String, message: String) = shot(name, { Recover() }) {
        backup = "backup.bin"
        typeKit()
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.waitForIdle()
        backupProblem = message
        compose.waitForIdle()
        assertTrue("back on the words", count("Enter your kit") > 0)
        compose.onNode(hasText(message)).performScrollTo()
        compose.waitForIdle()
        assertTrue("under the row", top(message) > top("backup.bin"))
        compose.onNodeWithText("Continue").performScrollTo().assertIsEnabled()
    }

    @Test
    fun recoverWordsDoNotOpenTheBackup() = refusal("16-recover-words-refused", "These words don't open this backup.")

    @Test
    fun recoverListNotOnThisPhone() = refusal(
        "17-recover-list-not-here",
        "This backup's vault list isn't on this phone. Unlock, then choose Import a vault from a file in the menu.",
    )

    @Test
    fun recoverCopying() = shot("18-recover-copying", { Recover() }) {
        backup = "backup.bin"
        typeKit()
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.onNodeWithText("New passphrase").performTextInput("correct horse battery")
        compose.onNodeWithText("Enter it again").performTextInput("correct horse battery")
        busy = true
        progress = 12 to 40
        compose.waitForIdle()
        assertTrue(count("Copying files… 12 of 40") > 0)
        compose.onNodeWithText("Set passphrase").assertIsNotEnabled()
    }
}
