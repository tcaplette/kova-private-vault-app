package com.app.vault

import android.graphics.Bitmap
import android.graphics.Color
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
import com.app.vault.ui.DestinationSettingsSheet
import com.app.vault.ui.VaultViewer
import com.app.vault.ui.theme.VaultTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The Media settings sheet and a small photo in the viewer, as a user sees them —
 * `media-settings` task 5.5.
 *
 * `PhotoDetailsScreenshotTest`'s method: `FLAG_SECURE` cleared on a throwaway `ComponentActivity`,
 * captures from `uiAutomation`, landing in `/data/local/tmp/media-settings/` on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class MediaSettingsScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray

    @Before fun setUp() {
        dir = File(ctx.filesDir, "media-shots-${System.nanoTime()}").apply { mkdirs() }
        store = BlobStore(File(dir, "blobs"), File(dir, "incoming"))
        vmk = VaultKeys.newVmk()
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun shot(name: String, content: @Composable () -> Unit, check: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent { content() }
            }
            compose.waitForIdle()
            check()
            compose.waitForIdle()
            // The frame has to reach the display before uiAutomation reads it.
            Thread.sleep(500)
            val png = ByteArrayOutputStream().also {
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

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instr.uiAutomation.executeShellCommand(command))
            .use { String(it.readBytes()) }

    private companion object {
        const val DIR = "/data/local/tmp/media-settings"
    }

    private fun count(text: String): Int =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Composable
    private fun Sheet(theme: ThemeChoice) {
        VaultTheme(theme) {
            DestinationSettingsSheet(destination = VaultDestination.Media, onDismiss = {})
        }
    }

    @Test
    fun theSheetInTheDarkScheme() = shot("sheet-dark", { Sheet(ThemeChoice.Dark) }) {
        assertEquals(1, count("Media settings"))
        assertTrue(count("How photos and videos look and play when you open them.") > 0)
        assertTrue(count("Off · The screen turns off on your phone's usual timer") > 0)
        assertTrue(count("On · A photo smaller than the screen is enlarged to fill it.") > 0)
        assertTrue(count("Playback speed") > 0)
    }

    @Test
    fun theSheetInTheLightScheme() = shot("sheet-light", { Sheet(ThemeChoice.Light) }) {
        assertTrue(count("Highest display quality") > 0)
    }

    @Test
    fun theSpeedsRevealUnderTheirRow() = shot("sheet-speeds", { Sheet(ThemeChoice.Dark) }) {
        compose.onNodeWithText("Playback speed").performClick()
        compose.waitForIdle()
        compose.onNode(hasText("The pitch stays the same.")).performScrollTo()
        assertTrue("the six are shown", count("Half again as fast") > 0 && count("Double speed") > 0)
    }

    /**
     * **The system's font scale**, set through the shell for this test alone and put back as it
     * was — a sheet is a window of its own and does not see a composition override.
     */
    @Test
    fun theLargestFontScrollsRatherThanTruncating() {
        val before = shell("settings get system font_scale").trim()
        shell("settings put system font_scale 2.0")
        try {
            shot("sheet-largest-font-top", { Sheet(ThemeChoice.Dark) }) {
                assertEquals(2f, ctx.resources.configuration.fontScale)
            }
            shot("sheet-largest-font-end", { Sheet(ThemeChoice.Dark) }) {
                compose.onNode(hasText("Playback speed")).performScrollTo()
                assertTrue(count("Playback speed") > 0)
            }
        } finally {
            if (before.isEmpty() || before == "null") shell("settings delete system font_scale")
            else shell("settings put system font_scale $before")
        }
    }

    /** A small photo with a gradient, so the capture reads as a picture rather than a block. */
    private fun smallPhoto(): Bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until width) for (y in 0 until height) {
            setPixel(x, y, Color.rgb(60 + x, 90 + y, 200 - x / 2))
        }
    }

    private fun viewerWith(stretch: Boolean): @Composable () -> Unit {
        val bytes = ByteArrayOutputStream().also { smallPhoto().compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
        val written = store.writeItem(ByteArrayInputStream(bytes), vmk)
        val item = VaultItem(written.blobIds, written.plaintextLength, null, "small.png", null, null, 1L, emptyList())
        val bitmap = smallPhoto()
        return {
            VaultTheme(ThemeChoice.Dark) {
                VaultViewer(
                    items = listOf(item),
                    startIndex = 0,
                    store = store,
                    vmk = vmk,
                    fullImageFor = { bitmap },
                    onDelete = null,
                    onRename = null,
                    onExport = null,
                    onBack = {},
                    onRotate = null,
                    onEdit = null,
                    onShare = null,
                    onSetCover = null,
                    onMoveToFolder = null,
                    media = MediaSettings(stretchSmall = stretch),
                )
            }
        }
    }

    @Test
    fun aSmallPhotoStretched() = shot("viewer-small-stretched", viewerWith(stretch = true)) {}

    @Test
    fun aSmallPhotoAtItsOwnSize() = shot("viewer-small-own-size", viewerWith(stretch = false)) {}
}
