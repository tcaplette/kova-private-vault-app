package com.app.vault

import android.app.UiAutomation
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.MusicCover
import com.app.vault.ui.theme.VaultTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The music player cover's layout against real tracks — `music-player-layout`, task 5.2.
 *
 * **The emulator has no music, and a grid of albums cannot be judged empty.** So this
 * inserts four 2-second silent MP3s from `androidTest/assets/music-layout/` into
 * `MediaStore.Audio` under `Music/music-player-layout-test/` — two albums of two tracks,
 * each album with a solid-colour embedded cover — and deletes exactly those rows in
 * teardown. They were generated once on the host:
 *
 * ```
 * ffmpeg -f lavfi -i "color=c=0xD97706:s=512x512:d=1" -frames:v 1 D97706.jpg
 * ffmpeg -f lavfi -i "color=c=0x2563EB:s=512x512:d=1" -frames:v 1 2563EB.jpg
 * # a1, a2: "Layout Track A1"/"A2", "Layout Artist A", "Layout Album A", D97706.jpg
 * # b1, b2: "Layout Track B1"/"B2", "Layout Artist B", "Layout Album B", 2563EB.jpg
 * ffmpeg -f lavfi -t 2 -i anullsrc=r=44100:cl=mono -i D97706.jpg -map 0:a -map 1:v \
 *   -c:a libmp3lame -b:a 32k -c:v copy -disposition:v attached_pic -id3v2_version 3 \
 *   -metadata title="Layout Track A1" -metadata artist="Layout Artist A" \
 *   -metadata album="Layout Album A" -metadata:s:v comment="Cover (front)" a1.mp3
 * ```
 *
 * **The audio grant is given here and not taken back here.** Revoking a runtime
 * permission kills the process holding it, and that process is this test run — which
 * is why AndroidX's `GrantPermissionRule` has no revoke either. It is revoked from the
 * host after the run: `adb -s emulator-5554 shell pm revoke com.app.vault
 * android.permission.READ_MEDIA_AUDIO`.
 *
 * Title, artist and album are columns the provider extracts from the file on publish;
 * the SDK sources do not say when, so setup waits for them and the first assertion is
 * that both albums are listed — if they are not, nothing after it could mean anything.
 *
 * `FLAG_SECURE` is cleared on this throwaway `ComponentActivity` so `uiAutomation`
 * screenshots are real; they land in `/data/local/tmp/music-layout/`.
 */
@RunWith(AndroidJUnit4::class)
class MusicCoverLayoutDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val inserted = mutableListOf<Uri>()
    private val density = ctx.resources.displayMetrics.density

    @Before
    fun setUp() {
        instr.uiAutomation.grantRuntimePermission(ctx.packageName, MusicLibrary.permission)
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        ASSETS.forEach { name ->
            val uri = ctx.contentResolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "$name.mp3")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, FOLDER)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                },
            ) ?: error("MediaStore refused $name")
            inserted += uri
            ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                instr.context.assets.open("music-layout/$name.mp3").use { it.copyTo(out) }
            }
            ctx.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
        // Wait for the provider to have read the tags, so the library the cover reads
        // on composition is the one this test is about.
        val deadline = System.currentTimeMillis() + 15_000
        while (MusicLibrary.tracks(ctx).count { it.album.startsWith("Layout Album") } < ASSETS.size &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(250)
        }
    }

    @After
    fun tearDown() {
        inserted.forEach { ctx.contentResolver.delete(it, null, null) }
        instr.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        instr.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    @Test
    fun theLayoutIsAPlayers() {
        var window = Rect()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { window = cover(it) }

            // The tracks arrived: both albums are listed.
            compose.waitUntil(10_000) { showing("Layout Track A1") }
            compose.onNodeWithText("Albums").performClick()
            compose.waitForIdle()
            assertTrue("album A is not listed — the tracks never arrived", showing("Layout Album A"))
            assertTrue("album B is not listed — the tracks never arrived", showing("Layout Album B"))

            // Shuffle all is on Tracks and nowhere else.
            compose.onNodeWithText("Tracks").performClick()
            compose.waitForIdle()
            assertTrue("Shuffle all is missing from Tracks", showing("Shuffle all"))
            capture("1-tracks")
            compose.onNodeWithText("Albums").performClick()
            compose.waitForIdle()
            assertFalse("Shuffle all is on Albums", showing("Shuffle all"))
            capture("2-albums")
            compose.onNodeWithText("Layout Album A").performClick()
            compose.waitForIdle()
            assertFalse("Shuffle all is in an opened album", showing("Shuffle all"))
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Artists").performClick()
            compose.waitForIdle()
            assertFalse("Shuffle all is on Artists", showing("Shuffle all"))
            compose.onNodeWithText("Layout Artist A").performClick()
            compose.waitForIdle()
            assertFalse("Shuffle all is in an opened artist", showing("Shuffle all"))
            compose.onNodeWithContentDescription("Back").performClick()

            // Pressing it leaves "Shuffle on" on the transport.
            compose.onNodeWithText("Tracks").performClick()
            compose.onNodeWithText("Shuffle all").performClick()
            compose.waitForIdle()

            // The mini player's progress line advances with the track: a fifth of the
            // way along its bottom edge turns the accent within the 2-second track.
            // Waited for rather than timed — the player's start-up is not a constant.
            val bar = bounds(miniPlayer())
            var advanced = false
            repeat(20) {
                if (!advanced) {
                    elapse(STEP_MS)
                    val shot = instr.uiAutomation.takeScreenshot()
                    val pixel = shot.getPixel(bar.left + bar.width() / 5, bar.bottom - dp(1))
                    advanced = ACCENTS.any { near(pixel, it) }
                }
            }
            assertTrue("the mini player's progress line did not advance", advanced)
            val mini = capture("4-mini-progress")

            // The artwork is centred in the bar: as much room above it as below.
            val column = bar.left + dp(8) + dp(24)
            val art = (bar.top until bar.bottom).filter { y ->
                COVERS.any { near(mini.getPixel(column, y), it) }
            }
            assertTrue("the mini player shows no artwork", art.isNotEmpty())
            val above = art.first() - bar.top
            val below = bar.bottom - 1 - art.last()
            assertTrue(
                "the mini player's artwork is not centred: $above px above, $below px below",
                abs(above - below) <= dp(1),
            )
            miniPlayer().performClick()
            compose.waitForIdle()
            assertTrue(
                "the transport does not read Shuffle on",
                compose.onAllNodes(hasContentDescription("Shuffle on")).fetchSemanticsNodes().isNotEmpty(),
            )
            compose.onNodeWithContentDescription("Close player").performClick()
            compose.waitForIdle()

            // The first two album cells share a row.
            compose.onNodeWithText("Albums").performClick()
            compose.waitForIdle()
            val a = bounds(compose.onNodeWithText("Layout Album A"))
            val b = bounds(compose.onNodeWithText("Layout Album B"))
            assertEquals("the first two albums are not on one row", a.top, b.top)
            assertTrue("the second album is not beside the first", b.left >= a.right)

            // Every artist row carries a circular thumbnail of its album's art.
            compose.onNodeWithText("Artists").performClick()
            compose.waitForIdle()
            Thread.sleep(500)
            val shot = capture("3-artists")
            listOf("Layout Artist A" to COVERS[0], "Layout Artist B" to COVERS[1]).forEach { (artist, colour) ->
                val row = bounds(topmost(artist))
                val left = row.left + dp(16)
                val top = row.centerY() - dp(24)
                assertTrue(
                    "$artist's row carries no artwork",
                    near(shot.getPixel(left + dp(24), top + dp(24)), colour),
                )
                assertFalse(
                    "$artist's thumbnail is not circular",
                    near(shot.getPixel(left + dp(3), top + dp(3)), colour),
                )
            }

            // Upright, the play control is in the bottom third of the window.
            miniPlayer().performClick()
            compose.waitForIdle()
            Thread.sleep(500)
            val play = bounds(compose.onNode(playControl()))
            assertTrue(
                "the play control is above the bottom third (top ${play.top} of ${window.height()})",
                play.top >= window.height() * 2 / 3,
            )
            capture("5-now-playing")
        }

        // Turned before the activity exists: a plain `ComponentActivity` is recreated by a
        // turn, and the content set on it would go with the old instance.
        instr.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
        Thread.sleep(1_000)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { window = cover(it) }
            compose.waitUntil(10_000) { showing("Layout Track A1") }
            compose.onNodeWithText("Layout Track A1").performClick()
            compose.waitForIdle()
            miniPlayer().performClick()
            compose.waitForIdle()
            Thread.sleep(500)

            // Sideways, every transport control lies inside the window.
            val controls = listOf(
                playControl(),
                hasContentDescription("Previous"),
                hasContentDescription("Next"),
                hasContentDescription("Shuffle off"),
                hasContentDescription("Repeat off"),
            )
            controls.forEach { matcher ->
                val at = bounds(compose.onNode(matcher))
                assertTrue(
                    "${matcher.description} is outside the sideways window: $at in $window",
                    at.left >= 0 && at.top >= 0 && at.right <= window.width() && at.bottom <= window.height(),
                )
            }
            capture("6-now-playing-sideways")

            // "Close player" returns to the library with the mini player showing.
            compose.onNodeWithContentDescription("Close player").performClick()
            compose.waitForIdle()
            assertTrue("the library is not shown after Close player", showing("Tracks"))
            assertTrue(
                "the mini player is not shown after Close player",
                compose.onAllNodes(playControl()).fetchSemanticsNodes().isNotEmpty(),
            )
            assertFalse(
                "the now-playing screen is still up",
                compose.onAllNodes(hasContentDescription("Close player")).fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    /** The cover in the bare host, secure flag cleared; returns the window's bounds. */
    private fun cover(activity: ComponentActivity): Rect {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        activity.setContent {
            VaultTheme { MusicCover(hasAccess = true, onTrigger = {}, persist = false) }
        }
        return activity.windowManager.currentWindowMetrics.bounds
    }

    /**
     * The mini player: the clickable bar holding the library's only play control. Not
     * found by its title — sideways, the last track row can sit underneath it.
     */
    private fun miniPlayer(): SemanticsNodeInteraction =
        compose.onNode(hasClickAction() and hasAnyChild(playControl()))

    /** The topmost node reading [text] — the list row, not the mini player's line. */
    private fun topmost(text: String): SemanticsNodeInteraction {
        val nodes = compose.onAllNodes(hasText(text)).fetchSemanticsNodes()
        return compose.onAllNodes(hasText(text))[nodes.indices.minBy { nodes[it].boundsInWindow.top }]
    }

    private fun playControl(): SemanticsMatcher =
        hasContentDescription("Play") or hasContentDescription("Pause")

    private fun showing(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(node: SemanticsNodeInteraction): Rect =
        node.fetchSemanticsNode().boundsInWindow.let {
            Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        }

    private fun dp(value: Int): Int = (value * density).toInt()

    /** Within a JPEG's and a thumbnail scaler's worth of [rgb]. */
    private fun near(pixel: Int, rgb: Int): Boolean =
        abs((pixel shr 16 and 0xFF) - (rgb shr 16 and 0xFF)) < 24 &&
            abs((pixel shr 8 and 0xFF) - (rgb shr 8 and 0xFF)) < 24 &&
            abs((pixel and 0xFF) - (rgb and 0xFF)) < 24

    /** Lets [ms] pass on both clocks: the player's real one and the compose test clock. */
    private fun elapse(ms: Long) {
        repeat((ms / STEP_MS).toInt()) {
            Thread.sleep(STEP_MS)
            compose.mainClock.advanceTimeBy(STEP_MS)
        }
    }

    private fun capture(name: String): Bitmap {
        compose.waitForIdle()
        val shot = instr.uiAutomation.takeScreenshot()
        val png = java.io.ByteArrayOutputStream().also {
            shot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val ui = instr.uiAutomation
        ui.executeShellCommand("mkdir -p $DIR").use { drain(it) }
        // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a
        // large PNG (the keyboard up) fills the pipes and both ends block. HangWatch caught it.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$DIR/$name.png")
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        drain(stdout)
        return shot
    }

    private fun drain(fd: ParcelFileDescriptor) {
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }

    private companion object {
        val ASSETS = listOf("a1", "a2", "b1", "b2")
        const val FOLDER = "Music/music-player-layout-test/"
        const val DIR = "/data/local/tmp/music-layout"
        const val STEP_MS = 100L

        /** The music palette's accent, dark and light — `MusicPalette`. */
        val ACCENTS = listOf(0x4ADE80, 0x0F7A3D)

        /** The two test albums' solid covers. */
        val COVERS = listOf(0xD97706, 0x2563EB)
    }
}
