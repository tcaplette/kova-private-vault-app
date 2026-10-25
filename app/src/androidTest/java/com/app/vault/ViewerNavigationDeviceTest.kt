package com.app.vault

import android.graphics.Bitmap
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.FileObserver
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.swipe
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.app.vault.ui.PlayerCensus
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Moving through the viewer, and resuming a video — `viewer-swipe-navigation`, tasks 4.1–4.3
 * and 4.6.
 *
 * **The list is photos and videos interleaved**, muxed on the device: `v0, p0, v1, p1, v2` in the
 * grid's order, so every move between videos passes over a photo and every move between photos
 * passes over a video. A swipe that crossed kinds would land on the wrong one.
 *
 * **The three videos have three lengths** — 13, 16 and 19 seconds — so the readout's total says
 * which one is settled. A video page carries no name, and adding one for a test would be chrome
 * nobody asked for.
 *
 * Needs the app's own `filesDir`, the only vault `MainActivity` opens, and skips rather than
 * overwrite one that is already there.
 *
 * **Gated, because it uses the install's own files** — `DestructiveTestScanTest`'s rule. A
 * routine run skips it; run it by name with
 *   -Pandroid.testInstrumentationRunnerArguments.destructive=true
 */
@RunWith(AndroidJUnit4::class)
class ViewerNavigationDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false
    private lateinit var store: BlobStore
    private lateinit var indexId: ByteArray

    private companion object {
        const val TAG = "ViewerNav"
        const val PASS_TEXT = "correct horse battery staple"
        const val SHOTS = "/data/local/tmp/viewer-swipe-navigation"
        const val BASE = 1_700_000_000_000L

        /** Seconds per video, far enough apart that a rounded readout still names one. */
        val SECONDS = listOf(13, 16, 19)

        /** Flat red, green and blue in video-range BT.601, so the pages differ on screen. */
        val YUV = listOf(Triple(81, 90, 240), Triple(145, 54, 34), Triple(41, 240, 110))

        /** Muxed once for the suite: forty-eight seconds is a while of software encode. */
        val clips = HashMap<Int, ByteArray>()

        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }

    // --- fixture ---------------------------------------------------------------

    private fun clip(i: Int): ByteArray = clips.getOrPut(i) {
        val out = File(ctx.cacheDir, "nav-source-$i-${System.nanoTime()}.mp4")
        val spec = VideoCorpus.Spec(
            "nav-$i", MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4", 0, SECONDS[i] * 1_000,
        )
        try {
            val (y, u, v) = YUV[i]
            check(VideoCorpus.generate(spec, out) { buf, _ -> VideoCorpus.flat(buf, y, u, v) }) {
                "this target has no H.264 encoder to mux the fixture with"
            }
            out.readBytes()
        } finally {
            // Plaintext by construction; gone before any scan starts.
            out.delete()
        }
    }

    private fun png(i: Int): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) for (y in 0 until height) {
                setPixel(x, y, android.graphics.Color.rgb(200 - i * 80, 120 + x / 2, 60 + y))
            }
        }.compress(Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()

    /** `v0 p0 v1 p1 v2`, or `v0 p0 p1` for the only video among photos. */
    private fun fixture(videos: Int = 3) {
        store = BlobStore(blobsDir, incoming)
        val lifecycle = VaultLifecycle(slots, store)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        val bytes = (0 until videos).map(::clip)
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true

        val vmk = VaultSession.vmk()!!
        indexId = VaultSession.metadataBlobId()!!.copyOf()
        val thumbs = ThumbnailStore(store)
        val order = if (videos == 1) listOf("v0.mp4", "p0.png", "p1.png")
        else listOf("v0.mp4", "p0.png", "v1.mp4", "p1.png", "v2.mp4")

        MetadataDb.commit(store, indexId, vmk) { db ->
            order.forEachIndexed { i, name ->
                val n = name[1].digitToInt()
                val data = if (name.endsWith(".mp4")) bytes[n] else png(n)
                val written = store.writeItem(ByteArrayInputStream(data), vmk)
                val item = VaultItem(
                    blobIds = written.blobIds,
                    plaintextLength = written.plaintextLength,
                    thumb = null,
                    filename = name,
                    album = null,
                    // Newest first is the grid's order, so the list reads as written.
                    createdAt = BASE - i * 60_000L,
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
        PlayerCensus.live.set(0)
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

    // --- helpers ---------------------------------------------------------------

    private fun await(what: String, timeoutMs: Long = 45_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = timeoutMs, condition = condition)
        } catch (e: Throwable) {
            runCatching {
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                    compose.onAllNodes(isRoot())[i].printToLog(TAG)
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun has(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun showing(description: String) =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun onGrid() = showing("All vaults")

    private fun unlock() {
        await("the lock screen") { has("Welcome back") }
        compose.waitForIdle()
        val field = compose.onNode(hasText("Passphrase"))
        field.performClick()
        compose.waitForIdle()
        field.performTextInput(PASS_TEXT)
        await("the passphrase to register") {
            compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unlock").performClick()
        compose.openFirstVaultFromTheList()
        await("the grid") { onGrid() }
        compose.waitForIdle()
    }

    private fun tile(name: String) = hasContentDescription(name) or hasText(name)

    private fun open(name: String) {
        await("the $name tile") { compose.onAllNodes(tile(name)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(tile(name)).performClick()
        await("the viewer") { !onGrid() }
        compose.waitForIdle()
    }

    /**
     * ***The app's timers run on this rule's clock, not the wall's.*** The layer's linger, the
     * readout's tick, the once-a-second report and a tap's double-tap window are all delays in
     * the composition's coroutines, and here those advance only when the test clock does. A
     * test that only sleeps freezes them: a tap never becomes a tap, the readout never ticks,
     * and nothing is reported. This moves the clock by [ms] and lets the frame land.
     *
     * **Real time first, and by the same amount.** The linger waits on this clock and then checks
     * the wall's; a test clock that runs ahead finishes the wait while the wall says "not yet",
     * and the layer then stays up until the next touch. On a phone the two are one clock.
     */
    private fun tick(ms: Long = 300) {
        SystemClock.sleep(ms)
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    /**
     * One tap, carried past the double-tap window, so it lands as a tap — a second tap inside
     * that window is a ten-second skip, which would move the very position these tests read.
     */
    private fun reveal() {
        compose.onRoot().performClick()
        tick(500)
    }

    private fun readoutNow(): String? =
        compose.onAllNodes(hasText(" / ", substring = true)).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    /** The settled video's readout, raising the layer if it has lingered out. */
    private fun readout(): String {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (!showing("Pause") && !showing("Play")) reveal()
            // One tick of the readout, so what it says is where the player is now.
            tick()
            readoutNow()?.let { return it }
        }
        throw AssertionError("no readout appeared")
    }

    private fun seconds(label: String): Int =
        label.trim().split(":").fold(0) { acc, part -> acc * 60 + part.trim().toInt() }

    /** Which video is settled, by its length. */
    private fun video(): Int {
        val total = seconds(readout().substringAfter(" / "))
        return SECONDS.indexOfFirst { abs(it - total) <= 1 }
            .also { assertTrue("a length of $total s names no fixture video", it >= 0) }
    }

    private fun position(): Int = seconds(readout().substringBefore(" / "))

    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        compose.onRoot().performTouchInput {
            swipe(Offset(width * fromX, height * fromY), Offset(width * toX, height * toY), 300)
        }
        compose.waitForIdle()
    }

    // Across the middle of the screen, clear of both control rows.
    private fun swipeUp() = drag(0.5f, 0.7f, 0.5f, 0.3f)
    private fun swipeDown() = drag(0.5f, 0.3f, 0.5f, 0.7f)
    private fun swipeLeft() = drag(0.8f, 0.5f, 0.2f, 0.5f)
    private fun swipeRight() = drag(0.2f, 0.5f, 0.8f, 0.5f)

    private fun page(action: SemanticsPropertyKey<AccessibilityAction<() -> Boolean>>) {
        compose.onNode(SemanticsMatcher.keyIsDefined(action)).performSemanticsAction(action)
        compose.waitForIdle()
    }

    private fun awaitPhoto(name: String) = await(name) { showing(name) }

    private fun rows() = MetadataDb.load(store, indexId, VaultSession.vmk()!!).items

    private fun resumeOf(name: String) = rows().single { it.filename == name }.resumeMs

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    /** Every file in private storage holding a decoded picture or an MP4, by name. */
    private fun plaintext(): List<String> =
        listOfNotNull(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir)
            .flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }
            .filter { file ->
                runCatching {
                    FileScan.containsWebp(file) || FileScan.contains(file, PNG_SIGNATURE) || mp4(file)
                }.getOrDefault(false)
            }
            .map { it.name }

    /** An MP4 starts with a box whose type is `ftyp`. A sealed blob starts with a nonce. */
    private fun mp4(file: File): Boolean = file.inputStream().use { s ->
        val head = ByteArray(8)
        s.read(head) == 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp"
    }

    /** Pauses the settled video and returns where it paused, in seconds. */
    private fun pauseHere(): Int {
        readout()
        compose.onNodeWithContentDescription("Pause").performClick()
        await("the pause") { showing("Play") }
        return position()
    }

    /**
     * Lets the settled video play for [ms] of real time, with the test clock kept in step, so the
     * app's once-a-second report runs as it does outside a test — see [tick].
     */
    private fun play(ms: Long) {
        val until = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(100)
            compose.mainClock.advanceTimeBy(100)
        }
        compose.waitForIdle()
    }

    /**
     * Plays on until the revealed layer has lingered out. The linger waits on the composition's
     * clock and then checks the wall's, so both have to move — which is what [play] does.
     */
    private fun lingerOut() {
        // A touch starts the linger afresh, and a moment of wall time puts the wall ahead of the
        // test clock — swipes and settles have moved this clock without moving the wall.
        reveal()
        SystemClock.sleep(1_000)
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (showing("Back")) {
            check(SystemClock.uptimeMillis() < deadline) {
                val transport = if (showing("Pause")) "Pause" else if (showing("Play")) "Play" else "neither"
                "the layer did not linger out; transport shows $transport, readout ${readoutNow()}"
            }
            play(250)
        }
    }

    private fun back(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        await("the grid") { onGrid() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(700)
        val png = ByteArrayOutputStream().also {
            instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val ui = instr.uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("mkdir -p $SHOTS")).use { it.readBytes() }
        // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a
        // large PNG (the keyboard up) fills the pipes and both ends block. HangWatch caught it.
        val (stdout, stdin) = ui.executeShellCommandRw("dd of=$SHOTS/$name.png")
        ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
    }

    // --- 4.1. the two sequences, the ways out, and what paging leaves behind ------

    @Test
    fun videosPageVerticallyAmongVideosAndPhotosSidewaysAmongPhotos() {
        fixture()
        val hits = CopyOnWriteArrayList<String>()
        val mostAlive = AtomicInteger(0)
        val running = AtomicBoolean(true)
        // Throughout, not only at the end: a spill cleaned up before the test finishes is still
        // a spill, and a second player that lived for one frame still took a decoder.
        val watcher = thread {
            while (running.get()) {
                mostAlive.accumulateAndGet(PlayerCensus.live.get(), ::maxOf)
                hits.addAll(plaintext())
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                unlock()
                open("v0.mp4")
                assertEquals("opened on the first video", 0, video())

                swipeDown()
                assertFalse("a downward swipe on the first video must not close the viewer", onGrid())
                assertEquals("the first video stops at the edge", 0, video())

                swipeUp()
                assertEquals("up is the next video, passing over p0", 1, video())
                swipeUp()
                assertEquals("up again passes over p1", 2, video())
                swipeUp()
                assertEquals("the last video stops at the edge", 2, video())

                swipeLeft()
                assertEquals("a sideways drag on a video is not a gesture", 2, video())
                // `video()` has the layer up, so the transport says which state it is in.
                assertTrue("and the video keeps playing", showing("Pause"))

                swipeDown()
                assertFalse("down on a video pages and does not dismiss", onGrid())
                assertEquals("down is the previous video", 1, video())

                page(SemanticsActions.PageDown)
                assertEquals("a screen reader's page down is the next video", 2, video())
                page(SemanticsActions.PageUp)
                assertEquals("and page up the previous one", 1, video())

                reveal()
                compose.onNodeWithContentDescription("Back").performClick()
                await("the grid, by the visible control") { onGrid() }
                open("v1.mp4")
                back(scenario)

                open("p0.png")
                awaitPhoto("p0.png")
                swipeLeft()
                awaitPhoto("p1.png")
                assertNull("left from a photo passes over v1 to p1", readoutNow())
                swipeLeft()
                assertTrue("the last photo stops at the edge", showing("p1.png"))
                swipeRight()
                awaitPhoto("p0.png")

                page(SemanticsActions.PageRight)
                awaitPhoto("p1.png")
                page(SemanticsActions.PageLeft)
                awaitPhoto("p0.png")

                swipeDown()
                await("the grid, by the downward drag on a photo") { onGrid() }
            }
        } finally {
            running.set(false)
            watcher.join()
        }
        hits.addAll(plaintext())
        assertTrue("more than one player was alive: ${mostAlive.get()}", mostAlive.get() <= 1)
        assertTrue("plaintext appeared in private storage: $hits", hits.isEmpty())
    }

    // --- 4.2. resuming, and what it writes ----------------------------------------

    @Test
    fun aVideoStoppedAtSevenSecondsReopensThereAndIsSwipedBackToThere() {
        fixture()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            open("v1.mp4")
            await("playback") { showing("Pause") }
            play(7_000)
            val stopped = pauseHere()
            Log.i(TAG, "stopped at $stopped s")
            assertTrue("the stop must be kept: $stopped s", stopped in 6..10)
            back(scenario)

            await("the stopping point to be written") { resumeOf("v1.mp4") != null }
            assertEquals("the index holds where it stopped", stopped, (resumeOf("v1.mp4")!! / 1_000).toInt())

            open("v1.mp4")
            assertTrue("the layer is up on arrival", showing("Pause") || showing("Play"))
            val reopened = position()
            assertTrue("reopened at $reopened s, stopped at $stopped s", abs(reopened - stopped) <= 1)

            // Swiped away and back: the position is held for the session, not waiting on a write.
            swipeUp()
            assertEquals(2, video())
            lingerOut()
            swipeDown()
            assertTrue(
                "arriving at a video that resumes raises the layer",
                showing("Pause") || showing("Play"),
            )
            val swipedBack = position()
            assertTrue(
                "swiped back to $swipedBack s; it was left at about $reopened s",
                swipedBack in (reopened - 1)..(reopened + 3),
            )
        }
    }

    @Test
    fun aLockWhileAVideoPlaysKeepsWhereItWas() {
        fixture()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            open("v1.mp4")
            await("playback") { showing("Pause") }
            play(6_500)
            val atLock = position()

            // The app is left while the video plays — auto-lock, from its background coroutine,
            // where the player cannot be read.
            scenario.moveToState(Lifecycle.State.CREATED)
            await("the auto-lock") { !VaultSession.isUnlocked }
            scenario.moveToState(Lifecycle.State.RESUMED)

            unlock()
            open("v1.mp4")
            val resumed = position()
            Log.i(TAG, "left at $atLock s, resumed at $resumed s")
            assertTrue(
                "resumed at $resumed s; the lock came at about $atLock s",
                resumed in (atLock - 1)..(atLock + 2),
            )
        }
    }

    @Test
    fun aVideoPlayedToItsEndReopensAtTheStart() {
        fixture()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            open("v2.mp4")
            await("playback") { showing("Pause") }
            play(7_000)
            pauseHere()
            back(scenario)
            await("a stopping point to exist first") { resumeOf("v2.mp4") != null }

            open("v2.mp4")
            readout()
            compose.onNodeWithContentDescription("Seek")
                .performSemanticsAction(SemanticsActions.SetProgress) { it(0.9f) }
            await("the end", timeoutMs = 20_000) { showing("Play") && readoutNow() != null }
            back(scenario)
            await("the stopping point to be cleared") { resumeOf("v2.mp4") == null }

            open("v2.mp4")
            assertEquals("a finished video starts over", 0, position())
        }
    }

    @Test
    fun aSessionOfPhotosLeavesTheIndexByteForByteAsItWas() {
        fixture()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            // The unlock's own write — the chaff churn — and any backfill, finished first.
            MetadataDb.awaitQuiescent()
            val before = sha256(store.fileOf(indexId))

            open("p0.png")
            awaitPhoto("p0.png")
            swipeLeft()
            awaitPhoto("p1.png")
            swipeRight()
            awaitPhoto("p0.png")
            back(scenario)

            SystemClock.sleep(1_500)
            MetadataDb.awaitQuiescent()
            assertArrayEquals(
                "viewing photos must not touch the index",
                before, sha256(store.fileOf(indexId)),
            )
        }
    }

    @Test
    fun severalVideosSweptThroughAreOneIndexWrite() {
        fixture()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            MetadataDb.awaitQuiescent()

            // `BlobStore` stages a write in `incoming/` and renames it into the pool, so every
            // index write is exactly one move onto the index's name.
            val name = store.fileOf(indexId).name
            val writes = AtomicInteger(0)
            val observer = object : FileObserver(blobsDir, MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == name) writes.incrementAndGet()
                }
            }
            observer.startWatching()
            try {
                open("v0.mp4")
                await("playback") { showing("Pause") }
                play(6_500)
                swipeUp()
                assertEquals(1, video())
                play(6_500)
                swipeUp()
                assertEquals(2, video())
                play(6_500)
                back(scenario)

                await("all three stopping points") {
                    rows().filter { it.filename.endsWith(".mp4") }.all { it.resumeMs != null }
                }
                SystemClock.sleep(1_500)
                MetadataDb.awaitQuiescent()
            } finally {
                observer.stopWatching()
            }
            assertEquals("three videos, one viewing session, one index write", 1, writes.get())
        }
    }

    // --- 4.3. deleting within a sequence --------------------------------------------

    /** The dialog's confirm, told apart from the menu row of the same name by its neighbour. */
    private fun confirmDelete() {
        await("the confirmation") { has("Keep") }
        compose.onNode(hasText("Delete") and hasAnySibling(hasText("Keep"))).performClick()
        compose.waitForIdle()
    }

    private fun deleteSettledVideo() {
        readout()
        compose.onNodeWithContentDescription("More").performClick()
        await("the menu") { has("Delete") }
        compose.onNodeWithText("Delete").performClick()
        confirmDelete()
    }

    @Test
    fun deletingMovesWithinTheSequenceBeingViewed() {
        fixture()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            open("v1.mp4")
            assertEquals(1, video())
            deleteSettledVideo()
            assertFalse(onGrid())
            assertEquals("the next video, not p1", 2, video())

            deleteSettledVideo()
            assertFalse(onGrid())
            assertEquals("the last video deleted lands on the one before it", 0, video())
            back(scenario)

            open("p0.png")
            awaitPhoto("p0.png")
            reveal()
            compose.onNodeWithContentDescription("Delete").performClick()
            confirmDelete()
            awaitPhoto("p1.png")
            assertNull("the next photo, not a video", readoutNow())
        }
    }

    @Test
    fun deletingTheOnlyVideoAmongPhotosClosesOntoThePhotos() {
        fixture(videos = 1)
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            open("v0.mp4")
            deleteSettledVideo()
            await("the list") { onGrid() }
            await("its photos") { showing("p0.png") && showing("p1.png") }
            assertFalse("the video is gone from the list", showing("v0.mp4"))
        }
    }

    // --- 4.6. what the user sees ----------------------------------------------------

    /**
     * A drag held halfway between two videos, a video that resumed with its readout, and a photo
     * mid-swipe — real captures through `uiAutomation`, with `FLAG_SECURE` cleared on this
     * activity only. Saved to [SHOTS] on the emulator.
     */
    @Test
    fun screenshotsOfTheSwipeTheResumeAndThePhoto() {
        fixture()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            unlock()

            // A stopping point on v1, so arriving there resumes.
            open("v1.mp4")
            await("playback") { showing("Pause") }
            play(7_500)
            back(scenario)
            await("the stopping point") { resumeOf("v1.mp4") != null }

            open("v0.mp4")
            await("playback") { showing("Pause") }
            lingerOut()
            compose.onRoot().performTouchInput {
                down(Offset(width / 2f, height * 0.75f))
                repeat(10) { moveBy(Offset(0f, -height * 0.05f)) }
            }
            shot("1-drag-halfway-between-videos")
            // Past half before letting go, so the pager commits to v1 rather than choosing
            // between two equally near pages with no velocity to decide it.
            compose.onRoot().performTouchInput {
                repeat(4) { moveBy(Offset(0f, -height * 0.05f)) }
                up()
            }
            compose.waitForIdle()

            assertEquals(1, video())
            shot("2-resumed-with-readout")
            back(scenario)

            open("p0.png")
            awaitPhoto("p0.png")
            lingerOut()
            compose.onRoot().performTouchInput {
                down(Offset(width * 0.85f, height / 2f))
                repeat(10) { moveBy(Offset(-width * 0.05f, 0f)) }
            }
            shot("3-photo-mid-swipe")
            compose.onRoot().performTouchInput {
                repeat(4) { moveBy(Offset(-width * 0.05f, 0f)) }
                up()
            }
            compose.waitForIdle()
        }
    }
}
