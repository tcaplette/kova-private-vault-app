package com.app.vault

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.BiometricState
import com.app.vault.ui.ExportScreen
import com.app.vault.ui.PhotoDetailsSheet
import com.app.vault.ui.ShareScreen
import com.app.vault.ui.VaultDrawerSheet
import com.app.vault.ui.theme.VaultTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Photo details sheet and the three controls that read it, as a user sees them —
 * `exif-strip-choice`, tasks 9.9 and the settings-screen scenarios.
 *
 * **`FLAG_SECURE` is cleared on the way in**, on this throwaway `ComponentActivity` only, because
 * `VaultApplication` sets it on every activity and a screenshot of a secure window is black. The
 * captures come from `uiAutomation`, not `View.draw`, which paints subcompositions that are not
 * on screen. They land in `/data/local/tmp/photo-details/` on the emulator — see [save].
 */
@RunWith(AndroidJUnit4::class)
class PhotoDetailsScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    private fun shot(name: String, content: @Composable () -> Unit, check: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent { content() }
            }
            compose.waitForIdle()
            check()
            compose.waitForIdle()
            val png = java.io.ByteArrayOutputStream().also {
                instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
            }.toByteArray()
            save("$name.png", png)
        }
    }

    /**
     * Through the instrumentation shell to [DIR], because the run uninstalls the app and its
     * files go with it. A test-only folder on the emulator, removed when the captures are read.
     */
    private fun save(name: String, bytes: ByteArray) {
        val ui = instr.uiAutomation
        ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
        // No quotes and no redirection: the instrumentation shell splits on spaces and runs the
        // words as they are, so `tee` does the writing.
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
        const val DIR = "/data/local/tmp/photo-details"
    }

    private fun count(text: String): Int =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    private fun switches(): Int =
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .fetchSemanticsNodes().size

    @Composable
    private fun Sheet(preset: PhotoDetailsPreset, custom: Set<PhotoDetail>, stripOnImport: Boolean) {
        PhotoDetailsSheet(
            preset = preset,
            custom = custom,
            stripOnImport = stripOnImport,
            onPreset = {},
            onCategory = { _, _ -> },
            onStripOnImport = {},
            onDismiss = {},
        )
    }

    @Test
    fun privacyIsChosenOnANewInstallAndDrawsNoCategorySwitch() = shot(
        "sheet-privacy-dark",
        { VaultTheme(ThemeChoice.Dark) { Sheet(PhotoDetailsPreset.PRIVACY, ExifPolicy.PRIVACY, false) } },
    ) {
        assertTrue(count("Privacy (recommended)") > 0)
        assertTrue(count("Removes location, camera info and edit history") > 0)
        assertEquals("under Privacy no category row is drawn", 0, count("GPS position"))
        assertEquals("only the import switch", 1, switches())
        assertTrue(count("Off") > 0)
    }

    @Test
    fun customShowsEachCategoryWithItsStateInWords() = shot(
        "sheet-custom-dark",
        { VaultTheme(ThemeChoice.Dark) { Sheet(PhotoDetailsPreset.CUSTOM, ExifPolicy.PRIVACY, false) } },
    ) {
        assertTrue(count("Removed · GPS position, altitude and direction") > 0)
        assertTrue(count("Removed · Make, model, lens, serial numbers and settings") > 0)
        assertTrue(count("Kept · When it was taken and last changed") > 0)
        assertTrue(count("Removed · The apps that made or edited it") > 0)
        assertEquals("each category row, and the import row, is one switch node", 5, switches())
    }

    @Test
    fun customInTheLightScheme() = shot(
        "sheet-custom-light",
        { VaultTheme(ThemeChoice.Light) { Sheet(PhotoDetailsPreset.CUSTOM, setOf(PhotoDetail.LOCATION), true) } },
    ) {
        assertTrue(count("Kept · Make, model, lens, serial numbers and settings") > 0)
        assertTrue(count("On · Can't be undone for a photo once it's imported.") > 0)
    }

    /**
     * **The system's font scale, not a composition override.** The sheet is a window of its
     * own and provides the platform's density to what it draws, so an override in the caller's
     * composition never reaches it — measured: the first capture of this test was at 1.0. The
     * scale is set to 2.0 through the shell for this test alone and put back as it was.
     */
    @Test
    fun theLargestFontScrollsRatherThanTruncating() {
        val before = shell("settings get system font_scale").trim()
        shell("settings put system font_scale 2.0")
        try {
            val sheet: @Composable () -> Unit = {
                VaultTheme(ThemeChoice.Dark) { Sheet(PhotoDetailsPreset.CUSTOM, ExifPolicy.PRIVACY, false) }
            }
            shot("sheet-custom-largest-font-top", sheet) {
                assertEquals("the scale reached the app", 2f, instr.targetContext.resources.configuration.fontScale)
            }
            shot("sheet-custom-largest-font-end", sheet) {
                compose.onNode(hasText("JPEG photos only", substring = true)).performScrollTo()
                assertTrue("the footer is reachable", count("including motion and HDR.") > 0)
            }
        } finally {
            if (before.isEmpty() || before == "null") shell("settings delete system font_scale")
            else shell("settings put system font_scale $before")
        }
    }

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instr.uiAutomation.executeShellCommand(command))
            .use { String(it.readBytes()) }

    @Test
    fun theDrawerRowNamesThePresetAndNothingElse() = shot(
        "drawer-row",
        {
            VaultTheme(ThemeChoice.Dark) {
                VaultDrawerSheet(
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
                    photoDetailsPreset = PhotoDetailsPreset.CUSTOM,
                    currentAppearance = ThemeChoice.Dark,
                    onChooseAppearance = {},
                )
            }
        },
    ) {
        assertTrue(count("Photo details  ·  Custom") > 0)
        assertEquals("the row carries no line under it", 0, count("import"))
    }

    @Test
    fun theExportControlNamesWhatItRemoves() = shot(
        "export-control",
        {
            VaultTheme(ThemeChoice.Dark) {
                ExportScreen(
                    itemCount = 12,
                    removed = ExifPolicy.PRIVACY,
                    onExport = { _, _, _ -> },
                    onPickFolder = {},
                    onCancel = {},
                    folderChosen = null,
                    busy = false,
                    failed = false,
                    progress = null,
                )
            }
        },
    ) {
        assertTrue(count("Remove photo details") > 0)
        assertTrue(count(ExifPolicy.exportSummary(ctx.words(), ExifPolicy.PRIVACY)) > 0)
    }

    @Test
    fun theShareBlockNamesWhatItRemoves() = shot(
        "share-block",
        {
            VaultTheme(ThemeChoice.Dark) {
                ShareScreen(
                    itemName = "holiday.jpg",
                    itemSize = 2_400_000,
                    refusal = null,
                    contacts = emptyList(),
                    selections = com.app.vault.ui.ShareSelections(recipient = null),
                    onSelections = {},
                    onPair = {},
                    code = null,
                    sealedTo = null,
                    busy = false,
                    progress = null,
                    failed = null,
                    strippable = true,
                    removed = ExifPolicy.PRIVACY,
                    onCreate = { _, _, _ -> },
                    onSend = {},
                    onCopyCode = {},
                    onBack = {},
                )
            }
        },
    ) {
        runCatching { compose.onNode(hasText("Send it as it is")).performScrollTo() }
        assertTrue(count(ExifPolicy.shareSummary(ctx.words(), ExifPolicy.PRIVACY)) > 0)
        assertTrue(count("Location, date and camera info go too") > 0)
    }
}
