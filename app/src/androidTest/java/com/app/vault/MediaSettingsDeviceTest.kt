package com.app.vault

import android.graphics.Bitmap
import android.graphics.Color
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.doubleClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.PlayerCensus
import com.app.vault.ui.VaultViewer
import com.app.vault.ui.theme.VaultTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * `media-settings` on a device — the half `MediaSettingsTest` cannot reach: what the window does,
 * what a small photo looks like, what a decode produces, and what speed a player is built at.
 *
 * Sandboxed into its own directory under `filesDir`, like `ViewerRotateTest`, and deleted after.
 * No install files are touched.
 */
@RunWith(AndroidJUnit4::class)
class MediaSettingsDeviceTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray

    @Before fun setUp() {
        dir = File(ctx.filesDir, "media-settings-${System.nanoTime()}").apply { mkdirs() }
        store = BlobStore(File(dir, "blobs"), File(dir, "incoming"))
        vmk = VaultKeys.newVmk()
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun item(name: String, bytes: ByteArray): VaultItem {
        val written = store.writeItem(ByteArrayInputStream(bytes), vmk)
        return VaultItem(
            written.blobIds, written.plaintextLength, null, name,
            null, null, 1L, emptyList(),
        )
    }

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .compress(Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()

    private fun red(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    /**
     * The viewer in a throwaway activity, with `FLAG_SECURE` cleared so a screenshot sees it.
     * [body] gets the scenario and a way to close the viewer while the activity stays up.
     */
    private fun withViewer(
        items: List<VaultItem>,
        media: MediaSettings,
        image: Bitmap = red(40, 30),
        memos: MemoSettings = MemoSettings(),
        resumeAt: ((VaultItem) -> Long?)? = null,
        onPosition: ((VaultItem, Long?) -> Unit)? = null,
        body: (ActivityScenario<ComponentActivity>, () -> Unit) -> Unit,
    ) {
        var showing by mutableStateOf(true)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                activity.setContent {
                    VaultTheme {
                        if (showing) {
                            VaultViewer(
                                items = items,
                                startIndex = 0,
                                store = store,
                                vmk = vmk,
                                fullImageFor = { image },
                                onDelete = null,
                                onRename = null,
                                onExport = null,
                                onBack = {},
                                onRotate = null,
                                onEdit = null,
                                onShare = null,
                                onSetCover = null,
                                onMoveToFolder = null,
                                media = media,
                                memos = memos,
                                resumeAt = resumeAt,
                                onPosition = onPosition,
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            body(scenario) {
                scenario.onActivity { showing = false }
                compose.waitForIdle()
            }
        }
    }

    private fun attributes(scenario: ActivityScenario<ComponentActivity>): WindowManager.LayoutParams {
        lateinit var copy: WindowManager.LayoutParams
        scenario.onActivity { copy = WindowManager.LayoutParams().apply { copyFrom(it.window.attributes) } }
        return copy
    }

    private fun held(scenario: ActivityScenario<ComponentActivity>): Boolean =
        (attributes(scenario).flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    @Test
    fun keepScreenOnHoldsTheWindowOnlyWhileAPhotoIsSettled() {
        val items = listOf(item("photo.png", png(40, 30)), item("notes.pdf", "not a pdf".toByteArray()))

        withViewer(items, MediaSettings(keepScreenOn = true)) { scenario, _ ->
            assertTrue("the screen is held while a photo is settled", held(scenario))
            compose.onRoot().performTouchInput { swipeLeft() }
            compose.waitForIdle()
            assertFalse("a file is not a photo or video, so the screen is let go", held(scenario))
        }

        withViewer(items, MediaSettings(keepScreenOn = true)) { scenario, close ->
            assertTrue(held(scenario))
            close()
            assertFalse("closing the viewer lets the screen go", held(scenario))
        }

        withViewer(items, MediaSettings()) { scenario, _ ->
            assertFalse("with the setting off the screen is never held", held(scenario))
        }
    }

    @Test
    fun fullBrightnessIsTheWindowsAndReturnsWhenTheViewerCloses() {
        val system = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)

        withViewer(listOf(item("photo.png", png(40, 30))), MediaSettings(fullBrightness = true)) { scenario, close ->
            assertEquals(
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL,
                attributes(scenario).screenBrightness,
                0.001f,
            )
            close()
            assertEquals(
                "the window hands brightness back",
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
                attributes(scenario).screenBrightness,
                0.001f,
            )
        }

        assertEquals(
            "the phone's brightness setting is never touched",
            system,
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1),
        )
    }

    /** The widest run of red in any row of a real screenshot. */
    private fun widestRed(): Int {
        Thread.sleep(500)
        val shot = instr.uiAutomation.takeScreenshot()
        return (0 until shot.height step 4).maxOf { y ->
            (0 until shot.width).count { x ->
                val c = shot.getPixel(x, y)
                Color.red(c) > 200 && Color.green(c) < 60 && Color.blue(c) < 60
            }
        }
    }

    @Test
    fun stretchDecidesHowASmallPhotoRests() {
        val photo = listOf(item("photo.png", png(120, 120)))

        withViewer(photo, MediaSettings(stretchSmall = true), image = red(120, 120)) { _, _ ->
            val across = widestRed()
            assertTrue("stretched, a 120 px photo fills the width — measured $across px", across > 800)
        }

        withViewer(photo, MediaSettings(stretchSmall = false), image = red(120, 120)) { _, _ ->
            val across = widestRed()
            assertTrue("unstretched, it rests at its own 120 px — measured $across px", across in 116..124)
        }
    }

    @Test
    fun qualityOffDecodesAtTheScreensSample() {
        val jpeg = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.JPEG, 90, out)
        }.toByteArray()

        val full = decodeForViewer(jpeg, fullQuality = true, screenLongEdge = 700)!!
        assertEquals("quality on decodes the whole photo", 2000 to 1500, full.width to full.height)

        val sampled = decodeForViewer(jpeg, fullQuality = false, screenLongEdge = 700)!!
        assertEquals("quality off halves while the long edge stays above 700", 1000 to 750, sampled.width to sampled.height)
        assertEquals(Bitmap.Config.ARGB_8888, sampled.config)

        val alreadySmall = decodeForViewer(jpeg, fullQuality = false, screenLongEdge = 2400)!!
        assertEquals("a photo below the screen is decoded whole", 2000, alreadySmall.width)
    }

    @Test
    fun aVideoPlaysAtTheChosenSpeedAndARecordingAtNormal() {
        PlayerCensus.lastSpeed = 0f
        withViewer(listOf(item("clip.mp4", ByteArray(4096))), MediaSettings(speed = PlaybackSpeed.Fast150)) { _, _ ->
            compose.waitUntil(5_000) { PlayerCensus.lastSpeed != 0f }
            assertEquals("a video plays at the chosen speed", 1.5f, PlayerCensus.lastSpeed, 0.001f)
        }

        PlayerCensus.lastSpeed = 0f
        withViewer(listOf(item("memo.m4a", ByteArray(4096))), MediaSettings(speed = PlaybackSpeed.Fast200)) { _, _ ->
            compose.waitUntil(5_000) { PlayerCensus.lastSpeed != 0f }
            assertEquals("a recording plays at Memos' speed, Normal, not Media's", 1f, PlayerCensus.lastSpeed, 0.001f)
        }

        // `memos-settings`: a memo plays at Memos' speed while Media's differs, and skips silence.
        PlayerCensus.lastSpeed = 0f
        withViewer(
            listOf(item("memo.m4a", ByteArray(4096))),
            MediaSettings(speed = PlaybackSpeed.Fast200),
            memos = MemoSettings(speed = PlaybackSpeed.Fast150, skipSilence = true),
        ) { _, _ ->
            compose.waitUntil(5_000) { PlayerCensus.lastSpeed != 0f }
            assertEquals("a recording plays at Memos' speed", 1.5f, PlayerCensus.lastSpeed, 0.001f)
            assertTrue("and skips silence", onMain { it.skipSilenceEnabled })
        }
    }

    // --- `destination-settings-and-slideshow` D8: Media's four new rows ----------------------

    /** A real clip of [seconds], sealed into the sandbox. */
    private fun clip(seconds: Int): VaultItem {
        val file = File(dir, "clip-$seconds.mp4")
        val spec = VideoCorpus.SPECS.first().let {
            VideoCorpus.Spec(it.label, it.mime, it.container, it.extension, 0, durationMs = seconds * 1_000)
        }
        check(VideoCorpus.generate(spec, file)) { "the emulator has no H.264 encoder" }
        return item("clip-$seconds.mp4", file.readBytes())
    }

    private fun <T> onMain(read: (androidx.media3.exoplayer.ExoPlayer) -> T): T {
        var value: T? = null
        instr.runOnMainSync { value = read(PlayerCensus.lastPlayer!!.get()!!) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun awaitPlayer() {
        compose.waitUntil(10_000) {
            PlayerCensus.lastPlayer?.get() != null &&
                onMain { it.playbackState } == androidx.media3.common.Player.STATE_READY
        }
    }

    @Test
    fun resumeOffStartsAtTheBeginningAndKeepsNothing() {
        PlayerCensus.lastPlayer = null
        val reports = mutableListOf<Long?>()
        withViewer(
            listOf(clip(6)),
            MediaSettings(resume = false),
            resumeAt = { 4_000L },
            onPosition = { _, at -> synchronized(reports) { reports += at } },
        ) { _, close ->
            awaitPlayer()
            assertTrue("starts at 0:00, not where it stopped", onMain { it.currentPosition } < 1_000L)
            Thread.sleep(1_500L)
            close()
        }
        assertTrue("no stopping point is written while off: $reports", reports.isEmpty())
    }

    @Test
    fun repeatLoopsAShortClip() {
        PlayerCensus.lastPlayer = null
        withViewer(listOf(clip(2)), MediaSettings(repeat = true)) { _, _ ->
            awaitPlayer()
            assertEquals(androidx.media3.common.Player.REPEAT_MODE_ONE, onMain { it.repeatMode })
            Thread.sleep(3_500L)
            assertTrue("it plays on past its end", onMain { it.isPlaying })
            assertTrue("from the start again", onMain { it.currentPosition } < 2_000L)
        }
    }

    @Test
    fun aThirtySecondSkipLandsThirtySecondsOn() {
        PlayerCensus.lastPlayer = null
        withViewer(listOf(clip(45)), MediaSettings(skip = SkipLength.Thirty)) { _, _ ->
            awaitPlayer()
            instr.runOnMainSync {
                PlayerCensus.lastPlayer!!.get()!!.apply { seekTo(5_000L); playWhenReady = false }
            }
            awaitPlayer()
            compose.onRoot().performTouchInput {
                doubleClick(androidx.compose.ui.geometry.Offset(width * 0.8f, height / 2f))
            }
            compose.waitUntil(3_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText("+30 s")).fetchSemanticsNodes().isNotEmpty()
            }
            val at = onMain { it.currentPosition }
            assertTrue("1:00 + 30 s is 1:30; here 0:05 + 30 s: $at", at in 34_000L..36_500L)
        }
    }

    @Test
    fun everySettingSurvivesTheProcess() {
        val chosen = MediaSettings(
            keepScreenOn = true,
            fullBrightness = true,
            stretchSmall = false,
            fullQuality = false,
            speed = PlaybackSpeed.Slow75,
            resume = false,
            repeat = true,
            skip = SkipLength.Five,
            orientation = VideoOrientation.Landscape,
        )
        val before = GridPreference.mediaSettings(ctx)
        try {
            GridPreference.setMediaSettings(ctx, chosen)
            assertEquals(chosen, GridPreference.mediaSettings(ctx))
        } finally {
            GridPreference.setMediaSettings(ctx, before)
        }
    }
}
