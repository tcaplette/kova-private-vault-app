package com.app.vault

import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs

/**
 * A playing video keeps its own shape — the surface is sized to the video, not the screen.
 *
 * Two clips, both encoded 640×480: `land.mp4` untagged (4:3 landscape), and `phone.mp4`
 * tagged to turn 90°, as a phone held upright records — shown 480×640, 3:4. Each case reads
 * the `SurfaceView`'s laid-out size once the first frame is up and holds it to the video's
 * ratio, and to a shape the screen's own is not, so a surface stretched to fill would fail.
 *
 * Needs the app's own `filesDir`, skips rather than overwrite a vault already there, and is
 * gated like the other suites that do — run it by name with
 *   -Pandroid.testInstrumentationRunnerArguments.destructive=true
 */
@RunWith(AndroidJUnit4::class)
class VideoAspectDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")
    private var created = false

    private companion object {
        const val PASS_TEXT = "correct horse battery staple"
    }

    private fun clip(label: String, hint: Int): ByteArray {
        val out = File(ctx.cacheDir, "aspect-$label-${System.nanoTime()}.mp4")
        val spec = VideoCorpus.Spec(
            label, MediaFormat.MIMETYPE_VIDEO_AVC, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4", 0, 6_000,
        )
        return try {
            check(VideoCorpus.generate(spec, out, orientationHint = hint)) { "no H.264 encoder on this target" }
            out.readBytes()
        } finally {
            out.delete()
        }
    }

    private fun fixture() {
        val store = BlobStore(blobsDir, incoming)
        val lifecycle = VaultLifecycle(slots, store)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        val clips = listOf("land.mp4" to clip("land", 0), "phone.mp4" to clip("phone", 90))
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true

        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        val thumbs = ThumbnailStore(store)
        MetadataDb.commit(store, indexId, vmk) { db ->
            clips.forEachIndexed { i, (name, data) ->
                val written = store.writeItem(ByteArrayInputStream(data), vmk)
                val item = VaultItem(
                    blobIds = written.blobIds,
                    plaintextLength = written.plaintextLength,
                    thumb = null,
                    filename = name,
                    album = null,
                    createdAt = 1_700_000_000_000L - i * 60_000L,
                    importedAt = i.toLong(),
                    tags = emptyList(),
                )
                val made = Thumbnails.generate(store, item, vmk)
                db.items += item.withThumb(thumbs.stage(db, made.webp!!), made.durationMs)
            }
            thumbs.flush(db, vmk)
        }
        lockKeyringAndVault()
    }

    @Before
    fun setUp() {
        assumeTrue(
            "gated: pass -Pandroid.testInstrumentationRunnerArguments.destructive=true to run it.",
            InstrumentationRegistry.getArguments().getString("destructive") == "true",
        )
        fixture()
    }

    @After
    fun tearDown() {
        lockKeyringAndVault()
        ThumbnailCache.clear()
        if (!created) return
        WorkManager.getInstance(ctx).cancelAllWork()
        MetadataDb.awaitQuiescent()
        slots.delete()
        blobsDir.deleteRecursively()
        incoming.deleteRecursively()
    }

    private fun has(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun unlock() {
        compose.waitUntil(45_000) { has("Welcome back") }
        val field = compose.onNode(hasText("Passphrase"))
        field.performClick()
        compose.waitForIdle()
        field.performTextInput(PASS_TEXT)
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unlock").performClick()
        compose.openFirstVaultFromTheList()
        val grid = { compose.onAllNodes(hasContentDescription("All vaults")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(45_000) { grid() }
    }

    private fun open(name: String) {
        val tile = hasContentDescription(name) or hasText(name)
        compose.waitUntil(45_000) { compose.onAllNodes(tile).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(tile).performClick()
        compose.waitForIdle()
    }

    private fun surfaceIn(v: View): SurfaceView? = when (v) {
        is SurfaceView -> v.takeIf { it.isShown && it.width > 0 }
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { surfaceIn(v.getChildAt(it)) }
        else -> null
    }

    /** (surface w, surface h, content w, content h), read on the main thread. */
    private fun sizes(scenario: ActivityScenario<MainActivity>): IntArray {
        var out = IntArray(4)
        scenario.onActivity { a ->
            val content = a.findViewById<View>(android.R.id.content)
            val s = surfaceIn(content)
            out = intArrayOf(s?.width ?: 0, s?.height ?: 0, content.width, content.height)
        }
        return out
    }

    /**
     * Real time with the rule's clock kept in step — the app's animations run on that clock
     * here, and a test that only sleeps leaves the poster frozen over the video.
     */
    private fun play(ms: Long) {
        val until = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(100)
            compose.mainClock.advanceTimeBy(100)
        }
        compose.waitForIdle()
    }

    private fun check(name: String, expected: Float) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            open(name)

            var last = IntArray(4)
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < deadline) {
                last = sizes(scenario)
                if (last[1] > 0 && abs(last[0].toFloat() / last[1] - expected) < 0.015f) break
                play(200)
            }
            val (sw, sh, cw, ch) = last.toList()
            val detail = "$name: surface ${sw}x$sh in content ${cw}x$ch, expected ratio $expected"
            android.util.Log.i("VideoAspect", detail)

            assertTrue("no surface on screen — $detail", sh > 0)
            assertTrue("surface is not the video's shape — $detail", abs(sw.toFloat() / sh - expected) < 0.015f)
            assertTrue("surface spills past the screen — $detail", sw <= cw && sh <= ch)
            assertTrue(
                "surface fills neither axis — $detail",
                abs(sw - cw) <= 2 || abs(sh - ch) <= 2,
            )
            assertTrue(
                "content's own shape equals the video's, so this case proves nothing — $detail",
                abs(cw.toFloat() / ch - expected) > 0.05f,
            )
        }
    }

    @Test
    fun aLandscapeVideoOnAnUprightPhoneKeepsItsShape() = check("land.mp4", 4f / 3f)

    @Test
    fun aPhoneRecordedPortraitVideoIsTurnedAndKeepsItsShape() = check("phone.mp4", 3f / 4f)
}
