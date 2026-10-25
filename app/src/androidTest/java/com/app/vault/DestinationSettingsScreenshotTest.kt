package com.app.vault

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.DestinationSettingsSheet
import com.app.vault.ui.SLIDE_TAG
import com.app.vault.ui.VaultGrid
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
 * `destination-settings-and-slideshow` task 10.3, as a user sees it: the Notes, Memos and Media
 * sheets in both schemes and at the largest font, the slideshow with its controls up and its
 * Options sheet, the recording notification in the shade, and the Files header with no settings
 * control.
 *
 * `MediaSettingsScreenshotTest`'s method: `FLAG_SECURE` cleared on a throwaway `ComponentActivity`,
 * captures from `uiAutomation`, landing in `/data/local/tmp/destination-settings/` on the emulator.
 * The font scale, the slideshow's options and the shade are put back as they were; the microphone
 * and notification grants are left for the run's cleanup, because revoking one kills this process.
 */
@RunWith(AndroidJUnit4::class)
class DestinationSettingsScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray

    @Before fun setUp() {
        dir = File(ctx.filesDir, "destination-shots-${System.nanoTime()}").apply { mkdirs() }
        store = BlobStore(File(dir, "blobs"), File(dir, "incoming"))
        vmk = VaultKeys.newVmk()
    }

    @After fun tearDown() {
        if (VoiceMemo.current != null) VoiceMemo.discard(ctx, store)
        MemoRecordingService.stop(ctx)
        MemoPending.resetForTest(ctx)
        dir.deleteRecursively()
    }

    private fun shot(name: String, content: @Composable () -> Unit, check: (ActivityScenario<ComponentActivity>) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent { content() }
            }
            compose.waitForIdle()
            check(scenario)
            compose.waitForIdle()
            Thread.sleep(600)
            capture(name)
        }
    }

    private fun capture(name: String) {
        val png = ByteArrayOutputStream().also {
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

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instr.uiAutomation.executeShellCommand(command))
            .use { String(it.readBytes()).trim() }

    private fun count(text: String): Int =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().size

    @Composable
    private fun Sheet(destination: VaultDestination, theme: ThemeChoice) {
        VaultTheme(theme) {
            DestinationSettingsSheet(destination = destination, onDismiss = {})
        }
    }

    private companion object {
        const val DIR = "/data/local/tmp/destination-settings"
    }

    // --- the three sheets, both schemes ----------------------------------------------------

    @Test
    fun notesDark() = shot("notes-dark", { Sheet(VaultDestination.Notes, ThemeChoice.Dark) }) {
        assertEquals(1, count("Notes settings"))
        assertTrue(count("Move checked items to the bottom") > 0)
        assertTrue(count("Incognito keyboard") > 0)
        assertTrue("incognito is on by default, in words", count("On · Your keyboard is asked not to learn") > 0)
    }

    @Test
    fun notesLight() = shot("notes-light", { Sheet(VaultDestination.Notes, ThemeChoice.Light) }) {}

    @Test
    fun memosDark() = shot("memos-dark", { Sheet(VaultDestination.Memos, ThemeChoice.Dark) }) {
        assertEquals(1, count("Memos settings"))
        listOf("Recording quality", "Stereo recording", "Use Bluetooth microphone", "Playback speed", "Skip silence")
            .forEach { assertTrue(it, count(it) > 0) }
    }

    @Test
    fun memosLight() = shot("memos-light", { Sheet(VaultDestination.Memos, ThemeChoice.Light) }) {}

    @Test
    fun memosQualityRevealed() = shot("memos-quality", { Sheet(VaultDestination.Memos, ThemeChoice.Dark) }) {
        compose.onNodeWithText("Recording quality").performClick()
        compose.waitForIdle()
        assertTrue("the three are shown", count("256 kbps") > 0 && count("128 kbps") > 0)
    }

    @Test
    fun mediaVideosDark() = shot("media-videos-dark", { Sheet(VaultDestination.Media, ThemeChoice.Dark) }) {
        compose.onNode(hasText("Video orientation")).performScrollTo()
        listOf("Resume videos", "Repeat video", "Double-tap to skip", "Video orientation")
            .forEach { assertTrue(it, count(it) > 0) }
    }

    @Test
    fun mediaVideosLight() = shot("media-videos-light", { Sheet(VaultDestination.Media, ThemeChoice.Light) }) {
        compose.onNode(hasText("Video orientation")).performScrollTo()
    }

    @Test
    fun mediaSkipRevealed() = shot("media-skip", { Sheet(VaultDestination.Media, ThemeChoice.Dark) }) {
        compose.onNode(hasText("Double-tap to skip")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasText("30 seconds")).performScrollTo()
    }

    /** The largest system font: every sheet reachable by scrolling, nothing truncated. */
    @Test
    fun theLargestFont() {
        val before = shell("settings get system font_scale")
        shell("settings put system font_scale 2.0")
        try {
            for (d in listOf(VaultDestination.Notes, VaultDestination.Memos, VaultDestination.Media)) {
                val name = d.name.lowercase()
                shot("$name-largest-top", { Sheet(d, ThemeChoice.Dark) }) {
                    assertEquals(2f, ctx.resources.configuration.fontScale)
                }
                val last = when (d) {
                    VaultDestination.Notes -> "Incognito keyboard"
                    VaultDestination.Memos -> "Skip silence"
                    else -> "Video orientation"
                }
                shot("$name-largest-end", { Sheet(d, ThemeChoice.Dark) }) {
                    compose.onNode(hasText(last)).performScrollTo()
                    assertTrue(count(last) > 0)
                }
            }
        } finally {
            if (before.isEmpty() || before == "null") shell("settings delete system font_scale")
            else shell("settings put system font_scale $before")
        }
    }

    // --- the slideshow --------------------------------------------------------------------

    private fun photo(i: Int): Bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until width step 4) for (y in 0 until height step 4) {
            val c = Color.rgb((x / 2 + 40 * i) % 256, (y / 2 + 60) % 256, (200 - x / 3) % 256)
            for (dx in 0 until 4) for (dy in 0 until 4) setPixel(x + dx, y + dy, c)
        }
    }

    private fun slideshowViewer(): @Composable () -> Unit {
        val photos = (0 until 3).map { photo(it) }
        val items = photos.mapIndexed { i, bmp ->
            val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            val written = store.writeItem(ByteArrayInputStream(bytes), vmk)
            VaultItem(written.blobIds, written.plaintextLength, null, "photo-$i.png", null, 1_000L - i, 1L, emptyList())
        }
        return {
            VaultTheme(ThemeChoice.Dark) {
                VaultViewer(
                    items = items,
                    startIndex = 0,
                    store = store,
                    vmk = vmk,
                    fullImageFor = { item -> photos[items.indexOf(item)] },
                    onDelete = null,
                    onRename = null,
                    onExport = null,
                    onBack = {},
                    onRotate = null,
                    onEdit = null,
                    onShare = null,
                    onSetCover = null,
                    onMoveToFolder = null,
                    slideshow = true,
                )
            }
        }
    }

    private fun startSlideshowAndReveal() {
        compose.onNodeWithContentDescription("More").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Slideshow").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(SLIDE_TAG)).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(800)
        compose.onNode(hasTestTag(SLIDE_TAG)).performClick()
        compose.waitUntil(3_000) { compose.onAllNodes(hasContentDescription("Pause slideshow")).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * Android's own one-time "Viewing full screen" notice dims the first full-screen window an app
     * shows and covers its top edge — the platform's, not the slideshow's. Marked as seen for the
     * capture and put back as it was.
     */
    private fun <T> withoutTheImmersiveNotice(block: () -> T): T {
        val before = shell("settings get secure immersive_mode_confirmations")
        shell("settings put secure immersive_mode_confirmations confirmed")
        try {
            return block()
        } finally {
            if (before.isEmpty() || before == "null") shell("settings delete secure immersive_mode_confirmations")
            else shell("settings put secure immersive_mode_confirmations $before")
        }
    }

    @Test
    fun theSlideshowWithItsControls() = withoutTheImmersiveNotice {
        val options = GridPreference.slideshowSettings(ctx)
        try {
            shot("slideshow-controls", slideshowViewer()) { startSlideshowAndReveal() }
        } finally {
            GridPreference.setSlideshowSettings(ctx, options)
        }
    }

    @Test
    fun theSlideshowOptions() = withoutTheImmersiveNotice {
        val options = GridPreference.slideshowSettings(ctx)
        try {
            shot("slideshow-options", slideshowViewer()) {
                startSlideshowAndReveal()
                compose.onNodeWithContentDescription("Slideshow options").performClick()
                compose.waitUntil(3_000) { count("Slideshow options") > 0 && count("Shuffle") > 0 }
                compose.onNodeWithText("Speed").performClick()
                compose.waitForIdle()
            }
        } finally {
            GridPreference.setSlideshowSettings(ctx, options)
        }
    }

    // --- the recording notification -------------------------------------------------------

    @Test
    fun theRecordingNotificationInTheShade() {
        instr.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO)
        instr.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    val publicKey = VaultKeys.captureKeypair(vmk).publicKey
                    assertEquals(null, VoiceMemo.start(activity, store, publicKey, null, MemoSettings()))
                    MemoRecordingService.start(activity)
                }
                Thread.sleep(3_000)
                shell("cmd statusbar expand-notifications")
                Thread.sleep(1_500)
                capture("notification-shade")
                // Low importance sits collapsed under Silent; expanding its row shows Stop. Through this
                // test's own UiAutomation — `uiautomator dump` cannot run while the test holds it.
                val ui = instr.uiAutomation
                ui.serviceInfo = ui.serviceInfo.apply {
                    flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }
                // Every node of the shade, walked by hand: text search does not reach into its rows.
                fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
                    val out = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                    fun walk(n: android.view.accessibility.AccessibilityNodeInfo?) {
                        if (n == null) return
                        out += n
                        for (i in 0 until n.childCount) walk(n.getChild(i))
                    }
                    ui.windows.forEach { walk(it.root) }
                    walk(ui.rootInActiveWindow)
                    return out
                }
                fun says(n: android.view.accessibility.AccessibilityNodeInfo, what: String) =
                    n.text?.toString() == what || n.contentDescription?.toString() == what
                fun centreY(n: android.view.accessibility.AccessibilityNodeInfo) =
                    android.graphics.Rect().also { n.getBoundsInScreen(it) }.centerY()
                repeat(5) {
                    val all = nodes()
                    if (all.any { says(it, "Stop") }) return@repeat
                    val title = all.firstOrNull { says(it, "Recording") } ?: return@repeat
                    all.filter { says(it, "Expand") }
                        .minByOrNull { kotlin.math.abs(centreY(it) - centreY(title)) }
                        ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    Thread.sleep(1_000)
                }
                assertTrue("the expanded notification shows Stop", nodes().any { says(it, "Stop") })
                capture("notification-expanded")
                shell("cmd statusbar collapse")
                Thread.sleep(500)
            }
        } finally {
            VoiceMemo.discard(ctx, store)
            MemoRecordingService.stop(ctx)
            // The grants are removed at the run's cleanup: revoking one kills the process under test.
        }
    }

    // --- the Files header -----------------------------------------------------------------

    @Test
    fun theFilesHeaderHasNoSettingsControl() = shot("files-header", {
        VaultTheme(ThemeChoice.Dark) {
            VaultGrid(
                items = emptyList(),
                thumbnailFor = { null },
                onOpen = {},
                selected = emptySet(),
                onBeginSelection = {},
                onToggleSelection = {},
                onSelectAll = {},
                onClearSelection = {},
                onDeleteSelected = {},
                onExportSelected = {},
                onImport = {},
                onNewNote = {},
                onVoiceMemo = {},
                trash = false,
                destination = VaultDestination.Files,
                onDestination = {},
                mediaFilter = MediaFilter.Default,
                onMediaFilter = {},
                viewMode = VaultDestination.Files.defaultViewMode,
                onViewMode = {},
                drawerState = DrawerState(DrawerValue.Closed),
                drawerContent = {},
                vaultName = "Vault",
                // What `MainActivity` hands Files — D1.
                onDestinationSettings = null,
            )
        }
    }) {
        assertEquals(0, compose.onAllNodes(hasContentDescription("Files settings")).fetchSemanticsNodes().size)
    }
}
