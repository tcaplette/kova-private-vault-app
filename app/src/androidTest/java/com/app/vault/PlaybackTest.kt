package com.app.vault

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.SKIP_MS
import com.app.vault.ui.clampSeek
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * P1-8 — playback over the encrypted store, and the third clause of the Verify:
 * playback and seek still work with `ACCESS_NETWORK_STATE` stripped.
 *
 * This is P0-5's measurement re-run against product code. The spike was deleted
 * at Gate 0; what survived was the number, and a number nothing reproduces is a
 * claim.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray
    private lateinit var item: VaultItem
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null

    private companion object { const val TAG = "VaultPlayback" }

    @Before
    fun setUp() {
        dir = File(ctx.filesDir, "playback-test-${System.nanoTime()}")
        store = BlobStore(dir)
        vmk = VaultKeys.newVmk()

        // A longer clip than the thumbnail corpus: seek behaviour at 10/50/97%
        // is meaningless over three seconds.
        val spec = VideoCorpus.Spec(
            "playback", android.media.MediaFormat.MIMETYPE_VIDEO_AVC,
            android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4",
            blackLeadMs = 0, durationMs = 20_000
        )
        val fixture = File(ctx.cacheDir, "playback.mp4")
        assertTrue("could not generate the fixture", VideoCorpus.generate(spec, fixture))
        val bytes = fixture.readBytes()
        fixture.delete()

        val written = store.write(ByteArrayInputStream(bytes), vmk)
        item = VaultItem(
            blobIds = listOf(written.blobId),
            plaintextLength = written.plaintextLength,
            thumb = null,
            filename = "playback.mp4",
            album = null,
            createdAt = null,
            importedAt = 0,
            tags = emptyList()
        )
        Log.i(TAG, "fixture: ${bytes.size} B plaintext, blob ${store.fileOf(written.blobId).length()} B")
    }

    @After
    fun tearDown() {
        surface?.release()
        texture?.release()
        dir.deleteRecursively()
        vmk.fill(0)
    }

    /**
     * A player rendering to an off-screen surface.
     *
     * The surface is not decoration: `onRenderedFirstFrame` never fires without
     * one, so a test with no Activity measures nothing. This is also closer to
     * what P0-5 measured — decode plus render, not decode alone.
     */
    private fun newPlayer(): ExoPlayer {
        val tex = SurfaceTexture(0).also { it.setDefaultBufferSize(VideoCorpus.WIDTH, VideoCorpus.HEIGHT) }
        val surf = Surface(tex)
        texture = tex
        surface = surf

        var player: ExoPlayer? = null
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(ctx).build().apply {
                val factory = VaultDataSource.Factory(store, item.blobIds, vmk, item.plaintextLength)
                setMediaSource(
                    ProgressiveMediaSource.Factory(factory)
                        .createMediaSource(MediaItem.fromUri(VaultDataSource.uriFor(item.blobIds.first())))
                )
                setVideoSurface(surf)
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "player error: ${error.errorCodeName} - ${error.message}", error)
                    }
                })
            }
        }
        return player!!
    }

    /** Waits for the next rendered frame, returning the wall-clock cost. */
    private fun timeToFirstFrame(player: ExoPlayer, action: () -> Unit): Long {
        val latch = CountDownLatch(1)
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() = latch.countDown()
        }
        instrumentation.runOnMainSync { player.addListener(listener) }
        val start = System.nanoTime()
        instrumentation.runOnMainSync(action)
        val rendered = latch.await(20, TimeUnit.SECONDS)
        val ms = (System.nanoTime() - start) / 1_000_000
        instrumentation.runOnMainSync { player.removeListener(listener) }
        assertTrue("no frame rendered within 20 s", rendered)
        return ms
    }

    @Test
    fun playsAndSeeksWithNetworkStatePermissionStripped() {
        // The permission is absent from the merged manifest; this asserts it at
        // runtime so the test fails for the right reason if it ever returns.
        val declared = ctx.packageManager
            .getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()
        Log.i(TAG, "runtime permissions: $declared")
        assertTrue(
            "ACCESS_NETWORK_STATE is back in the manifest",
            declared.none { it == "android.permission.ACCESS_NETWORK_STATE" }
        )

        val player = newPlayer()
        try {
            val firstFrame = timeToFirstFrame(player) {
                player.prepare()
                player.playWhenReady = true
            }

            var duration = 0L
            instrumentation.runOnMainSync { duration = player.duration }
            assertTrue("no duration", duration > 0)

            val seeks = listOf(0.10, 0.50, 0.97).map { fraction ->
                val target = (duration * fraction).toLong()
                val ms = timeToFirstFrame(player) { player.seekTo(target) }
                fraction to ms
            }

            Log.i(TAG, "first frame: $firstFrame ms, duration $duration ms")
            seeks.forEach { (f, ms) -> Log.i(TAG, "seek to %.0f%% -> %d ms".format(f * 100, ms)) }

            // P0-5's property: seek cost does not scale with offset. Asserted as a
            // ratio with a wide band — this catches "seeking to 97% decrypts
            // everything before it", not scheduler noise.
            val early = seeks.first().second.coerceAtLeast(1)
            val late = seeks.last().second
            assertTrue(
                "seek to 97% cost ${late}ms against ${early}ms at 10% — that is the shape of a " +
                    "linear scan, which is what chunk independence exists to prevent",
                late <= early * 8
            )
        } finally {
            instrumentation.runOnMainSync { player.release() }
        }
    }

    @Test
    fun playbackLeavesNoPlaintextOnDisk() {
        val stop = AtomicBoolean(false)
        var passes = 0
        val hits = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // The fixture's own marker: ftyp/moov boxes are what an MP4 on disk looks
        // like. If plaintext video ever lands, this finds it.
        val markers = listOf("ftyp", "moov", "mdat").map { it.toByteArray(Charsets.US_ASCII) }

        val scanner = thread {
            while (!stop.get()) {
                passes++
                listOf(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir)
                    .flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }
                    .forEach { f ->
                        seen += f.name
                        if (markers.any { m -> FileScan.contains(f, m) }) hits += f.path
                    }
            }
        }

        val player = newPlayer()
        try {
            timeToFirstFrame(player) {
                player.prepare()
                player.playWhenReady = true
            }
            var duration = 0L
            instrumentation.runOnMainSync { duration = player.duration }
            listOf(0.25, 0.60, 0.90).forEach { f ->
                timeToFirstFrame(player) { player.seekTo((duration * f).toLong()) }
            }
        } finally {
            instrumentation.runOnMainSync { player.release() }
        }

        stop.set(true)
        scanner.join(10_000)

        // And after release: an artifact left behind is worse than one glimpsed.
        listOf(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir)
            .flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }
            .forEach { f ->
                if (markers.any { m -> FileScan.contains(f, m) }) hits += "post: ${f.path}"
            }

        Log.i(TAG, "I3 during playback: $passes passes, ${seen.size} files, ${hits.size} hits")
        Log.i(TAG, "files: ${seen.sorted()}")
        assertTrue("scanner never ran", passes > 0)
        assertTrue("plaintext media found on disk: $hits", hits.isEmpty())
    }

    @Test
    fun skipClampsAtBothEnds() {
        val duration = 60_000L
        assertEquals("mid-clip forward", 30_000L, clampSeek(20_000L, SKIP_MS, duration))
        assertEquals("mid-clip back", 10_000L, clampSeek(20_000L, -SKIP_MS, duration))
        assertEquals("back past the start clamps to 0", 0L, clampSeek(3_000L, -SKIP_MS, duration))
        assertEquals("forward past the end clamps to duration", duration,
            clampSeek(55_000L, SKIP_MS, duration))
        assertEquals("at the very start", 0L, clampSeek(0L, -SKIP_MS, duration))
        // Unknown duration must not wrap or throw.
        assertEquals("unknown duration", 10_000L, clampSeek(0L, SKIP_MS, -1L))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
