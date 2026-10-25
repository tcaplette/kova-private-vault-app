package com.app.vault

import android.app.UiAutomation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.swipe
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.app.vault.ui.PlayerCensus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * Turning the device — `landscape-rotation`.
 *
 * ***The display is actually turned.*** `UiAutomation.setRotation` freezes the display at a
 * rotation through the window manager, the path a hand on the phone takes: the activity gets a
 * configuration change, not a `requestedOrientation` request and not a hand-made recreation.
 * Every test waits for the activity's own configuration to report the new orientation before it
 * asserts anything, and `tearDown` returns the display to natural and unfreezes it, pass or fail,
 * so auto-rotate is left as it was found.
 *
 * **The fixture** is three videos of 13, 16 and 19 seconds — the readout's total names which one
 * is settled — ahead of forty photographs, enough for the Media mosaic to run several screens
 * sideways. Newest first is the grid's order, so the list reads as written.
 *
 * Needs the app's own `filesDir`, the only vault `MainActivity` opens, and skips rather than
 * overwrite one that is already there. **Gated**, because it uses the install's own files — run
 * it by name with
 *   -Pandroid.testInstrumentationRunnerArguments.destructive=true
 *
 * **Restores everything it changes**: the rotation, the launcher identity and the calculator's
 * trigger, and the vault it created.
 */
@RunWith(AndroidJUnit4::class)
class LandscapeDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false
    private lateinit var identityBefore: LauncherIdentity
    private var triggerBefore: Trigger? = null

    private companion object {
        const val TAG = "Landscape"
        const val PASS_TEXT = "correct horse battery staple"
        const val SHOTS = "/data/local/tmp/landscape-rotation"
        const val BASE = 1_700_000_000_000L
        const val PHOTOS = 40

        /** Seconds per video, far enough apart that a rounded readout still names one. */
        val SECONDS = listOf(13, 16, 19)

        /** Flat red, green and blue in video-range BT.601, so the pages differ on screen. */
        val YUV = listOf(Triple(81, 90, 240), Triple(145, 54, 34), Triple(41, 240, 110))

        /** Muxed once for the suite. */
        val clips = HashMap<Int, ByteArray>()

        /** `sin` is on the calculator's keypad and on no other screen in the app. */
        const val CALCULATOR_KEY = "sin"
        val CALC_TRIGGER = Trigger.Keys(listOf("7", "3", CALCULATOR_KEY))
    }

    // --- fixture ---------------------------------------------------------------

    private fun clip(i: Int): ByteArray = clips.getOrPut(i) {
        val out = File(ctx.cacheDir, "land-source-$i-${System.nanoTime()}.mp4")
        val spec = VideoCorpus.Spec(
            "land-$i", MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4", 0, SECONDS[i] * 1_000,
        )
        try {
            val (y, u, v) = YUV[i]
            check(VideoCorpus.generate(spec, out) { buf, _ -> VideoCorpus.flat(buf, y, u, v) }) {
                "this target has no H.264 encoder to mux the fixture with"
            }
            out.readBytes()
        } finally {
            // Plaintext by construction; gone before anything else runs.
            out.delete()
        }
    }

    private fun png(i: Int): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb((i * 37) % 256, (i * 91) % 256, (i * 53) % 256))
        }.compress(Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()

    private fun fixture() {
        val store = BlobStore(blobsDir, incoming)
        val lifecycle = VaultLifecycle(slots, store)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        val videos = (0 until 3).map(::clip)
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true

        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        val thumbs = ThumbnailStore(store)
        val order = (0 until 3).map { "v$it.mp4" } + (0 until PHOTOS).map { "p$it.png" }

        MetadataDb.commit(store, indexId, vmk) { db ->
            order.forEachIndexed { i, name ->
                val n = name.drop(1).substringBefore('.').toInt()
                val data = if (name.endsWith(".mp4")) videos[n] else png(n)
                val written = store.writeItem(ByteArrayInputStream(data), vmk)
                val item = VaultItem(
                    blobIds = written.blobIds,
                    plaintextLength = written.plaintextLength,
                    thumb = null,
                    filename = name,
                    album = null,
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
        identityBefore = GridPreference.identity(ctx)
        triggerBefore = CoverTrigger.triggerFor(ctx, Cover.Calculator)
        PlayerCensus.live.set(0)
        fixture()
    }

    @After
    fun tearDown() {
        // First, and unconditionally: a test that failed sideways must not leave the device so.
        instr.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        instr.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        if (!::identityBefore.isInitialized) return
        AutoLock.awaitingResult(false)
        lockKeyringAndVault()
        ThumbnailCache.clear()
        triggerBefore
            ?.let { CoverTrigger.set(ctx, Cover.Calculator, it) }
            ?: CoverTrigger.clear(ctx, Cover.Calculator)
        CoverTrigger.clearAttempts(ctx, Cover.Calculator)
        LauncherIdentity.switchTo(ctx, identityBefore)
        // The switch only enables; the departure pass leaves exactly one entry.
        LauncherIdentity.reconcile(ctx)
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

    /** Wall time and the rule's clock together — the app's timers run on the latter here. */
    private fun tick(ms: Long = 300) {
        SystemClock.sleep(ms)
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun play(ms: Long) {
        val until = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(100)
            compose.mainClock.advanceTimeBy(100)
        }
        compose.waitForIdle()
    }

    /** One tap, carried past the double-tap window so it is not a skip. */
    private fun reveal() {
        compose.onRoot().performClick()
        tick(500)
    }

    private fun readoutNow(): String? =
        compose.onAllNodes(hasText(" / ", substring = true)).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    private fun readout(): String {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (!showing("Pause") && !showing("Play")) reveal()
            tick()
            readoutNow()?.let { return it }
        }
        throw AssertionError("no readout appeared")
    }

    private fun seconds(label: String): Int =
        label.trim().split(":").fold(0) { acc, part -> acc * 60 + part.trim().toInt() }

    /**
     * Plays on until the revealed layer has lingered out — `ViewerNavigationDeviceTest`'s method:
     * the linger waits on the rule's clock and then checks the wall's, so both have to move, and a
     * moment of wall time first puts the wall ahead of a clock that swipes have advanced.
     */
    private fun lingerOut(): Boolean {
        reveal()
        SystemClock.sleep(1_000)
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (showing("Back")) {
            if (SystemClock.uptimeMillis() > deadline) return false
            play(250)
        }
        return true
    }

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

    private fun orientation(scenario: ActivityScenario<MainActivity>): Int {
        var o = Configuration.ORIENTATION_UNDEFINED
        scenario.onActivity { o = it.resources.configuration.orientation }
        return o
    }

    /**
     * Turns the display to [rotation] and waits until the activity itself reports the matching
     * orientation — the turn has landed, not merely been asked for.
     */
    private fun turn(scenario: ActivityScenario<MainActivity>, rotation: Int) {
        val want = if (rotation == UiAutomation.ROTATION_FREEZE_90 || rotation == UiAutomation.ROTATION_FREEZE_270) {
            Configuration.ORIENTATION_LANDSCAPE
        } else {
            Configuration.ORIENTATION_PORTRAIT
        }
        assertTrue("the display would not turn", instr.uiAutomation.setRotation(rotation))
        await("the activity to report orientation $want", 15_000) { orientation(scenario) == want }
        // A frame for the re-layout, and a second for anything that would have come after it —
        // auto-lock's watcher is asynchronous, so a lock would land in this window.
        play(1_000)
    }

    private fun activity(scenario: ActivityScenario<MainActivity>): MainActivity {
        var a: MainActivity? = null
        scenario.onActivity { a = it }
        return a!!
    }

    private fun surfaceIn(v: View): SurfaceView? = when (v) {
        is SurfaceView -> v.takeIf { it.isShown && it.width > 0 }
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { surfaceIn(v.getChildAt(it)) }
        else -> null
    }

    private fun surface(scenario: ActivityScenario<MainActivity>): SurfaceView? {
        var s: SurfaceView? = null
        scenario.onActivity { s = surfaceIn(it.findViewById(android.R.id.content)) }
        return s
    }

    /** (surface w, surface h, content w, content h). */
    private fun sizes(scenario: ActivityScenario<MainActivity>): IntArray {
        var out = IntArray(4)
        scenario.onActivity { a ->
            val content = a.findViewById<View>(android.R.id.content)
            val s = surfaceIn(content)
            out = intArrayOf(s?.width ?: 0, s?.height ?: 0, content.width, content.height)
        }
        return out
    }

    /** Whether the status and navigation bars are showing, as the window's own insets say. */
    private fun barsVisible(scenario: ActivityScenario<MainActivity>): Pair<Boolean, Boolean> {
        var bars = true to true
        scenario.onActivity { a ->
            val insets = ViewCompat.getRootWindowInsets(a.window.decorView)!!
            bars = insets.isVisible(WindowInsetsCompat.Type.statusBars()) to
                insets.isVisible(WindowInsetsCompat.Type.navigationBars())
        }
        return bars
    }

    private fun coverIsUp(): Boolean = has(CALCULATOR_KEY)

    /** The stored sequence, completed by the hold on `=`. */
    private fun performTrigger() {
        CALC_TRIGGER.keys.forEach { compose.onAllNodes(hasText(it))[0].performClick() }
        compose.waitForIdle()
        compose.onAllNodes(hasText("="))[0].performTouchInput { longClick(durationMillis = 2_500) }
        compose.waitForIdle()
    }

    private fun back(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        await("the grid") { onGrid() }
    }

    /** A real capture, with `FLAG_SECURE` cleared on this test's own window first. */
    private fun shot(scenario: ActivityScenario<MainActivity>, name: String) {
        scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
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

    /**
     * Off screen by either route: a lazy list disposes a row scrolled far enough away, so its node
     * is gone, while the band is clipped in place, so its node stays and is not displayed.
     */
    private fun offScreen(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().isEmpty() ||
            runCatching { compose.onNode(matcher).assertIsNotDisplayed() }.isSuccess

    /** A destination entry, which the bar and the rail both mark with `Selected`. */
    private fun entry(label: String) =
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and hasText(label)

    // --- device-rotation ------------------------------------------------------------

    /** The defect as reported: a video playing upright, then the phone turned on its side. */
    @Test
    fun aPlayingVideoSurvivesTheTurn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            open("v0.mp4")
            play(2_500)
            val before = activity(scenario)
            val surfaceBefore = surface(scenario)
            val p0 = position()
            assertEquals("one player before the turn", 1, PlayerCensus.live.get())
            assertTrue("no surface on screen before the turn", surfaceBefore != null)

            turn(scenario, UiAutomation.ROTATION_FREEZE_90)
            play(1_500)
            assertSame("the turn recreated the activity", before, activity(scenario))
            assertTrue("the turn locked the vault", VaultSession.isUnlocked)
            assertFalse("the turn raised the lock screen", has("Welcome back"))
            assertEquals("the turn changed the number of players", 1, PlayerCensus.live.get())
            assertSame("the turn rebuilt the player's surface", surfaceBefore, surface(scenario))
            assertEquals("the turn changed which video is open", 0, video())
            val p1 = position()
            assertTrue("playback did not continue through the turn: $p0 s then $p1 s", p1 > p0)

            turn(scenario, UiAutomation.ROTATION_FREEZE_0)
            play(1_500)
            assertSame("turning back recreated the activity", before, activity(scenario))
            assertTrue("turning back locked the vault", VaultSession.isUnlocked)
            assertEquals(1, PlayerCensus.live.get())
            assertEquals("turning back changed which video is open", 0, video())
            val p2 = position()
            assertTrue("playback did not continue through the turn back: $p1 s then $p2 s", p2 > p1)
        }
    }

    @Test
    fun aCoverIsNotRaisedByATurn() {
        CoverTrigger.set(ctx, Cover.Calculator, CALC_TRIGGER)
        CoverTrigger.clearAttempts(ctx, Cover.Calculator)
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Calculator)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await("the calculator") { coverIsUp() }
            performTrigger()
            unlock()
            assertFalse("the cover is still up after the trigger and the passphrase", coverIsUp())

            turn(scenario, UiAutomation.ROTATION_FREEZE_90)
            assertFalse("turning the phone raised the cover", coverIsUp())
            assertTrue("turning the phone locked the vault", VaultSession.isUnlocked)
            assertTrue("the vault is no longer on screen after the turn", onGrid())

            turn(scenario, UiAutomation.ROTATION_FREEZE_0)
            assertFalse("turning back raised the cover", coverIsUp())
            assertTrue("turning back locked the vault", VaultSession.isUnlocked)
            assertTrue(onGrid())
        }
    }

    @Test
    fun fourTurnsNeverLock() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            val before = activity(scenario)
            listOf(
                UiAutomation.ROTATION_FREEZE_90,
                UiAutomation.ROTATION_FREEZE_0,
                UiAutomation.ROTATION_FREEZE_270,
                UiAutomation.ROTATION_FREEZE_0,
            ).forEach { r ->
                turn(scenario, r)
                assertTrue("rotation $r locked the vault", VaultSession.isUnlocked)
                assertTrue("rotation $r locked the keyring", VaultKeyringSession.isOpen)
                assertSame("rotation $r recreated the activity", before, activity(scenario))
                assertTrue("rotation $r left the grid", onGrid())
            }
        }
    }

    // --- media-viewer -----------------------------------------------------------------

    @Test
    fun aLandscapeVideoFillsTheHeightWithTheBarsHidden() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            open("v0.mp4")
            play(1_500)
            assertEquals("upright the bars stay shown; hiding them there is not this change", true to true, barsVisible(scenario))

            turn(scenario, UiAutomation.ROTATION_FREEZE_90)
            var last = IntArray(4)
            await("the surface to fill the height", 15_000) {
                last = sizes(scenario)
                last[1] > 0 && abs(last[1] - last[3]) <= 2
            }
            val (sw, sh, cw, ch) = last.toList()
            val detail = "surface ${sw}x$sh in content ${cw}x$ch"
            assertTrue("the video is not its own shape sideways — $detail", abs(sw.toFloat() / sh - 4f / 3f) < 0.015f)
            assertTrue("the video spills past the screen — $detail", sw <= cw)
            await("the bars to hide", 5_000) { barsVisible(scenario) == (false to false) }
            shot(scenario, "video-sideways-layer-shown")
            reveal()
            assertEquals("recalling the controls brought the bars back", false to false, barsVisible(scenario))
            // The bare picture. The linger is not this change's, so a layer that outstays the wait
            // costs the capture, not the test.
            lingerOut()
            shot(scenario, "video-sideways-layer-hidden")

            back(scenario)
            await("the bars to return after leaving the viewer", 5_000) { barsVisible(scenario) == (true to true) }

            open("p0.png")
            play(1_000)
            assertEquals("a photograph hid the bars sideways", true to true, barsVisible(scenario))
        }
    }

    @Test
    fun theViewerPagesSideways() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            open("v0.mp4")
            play(1_000)
            turn(scenario, UiAutomation.ROTATION_FREEZE_90)
            assertEquals(0, video())

            drag(0.5f, 0.7f, 0.5f, 0.3f)
            await("the next video to settle", 20_000) { runCatching { video() == 1 }.getOrDefault(false) }
            play(500)
            assertEquals("paging sideways left more than one player alive", 1, PlayerCensus.live.get())
        }
    }

    // --- the grid sideways ------------------------------------------------------------

    @Test
    fun theGridScrollsSideways() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            turn(scenario, UiAutomation.ROTATION_FREEZE_90)
            await("the grid sideways") { onGrid() }

            // The rail, and only the rail: each entry is drawn once, on the leading edge.
            listOf("Media", "Files", "Notes", "Memos").forEach { label ->
                assertEquals("$label is drawn twice or not at all", 1, compose.onAllNodes(entry(label)).fetchSemanticsNodes().size)
            }
            val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
            val media = compose.onNode(entry("Media")).fetchSemanticsNode().boundsInRoot
            assertTrue("the destinations are not on the leading edge: $media in $width", media.right < width * 0.2f)
            compose.onNode(entry("Media") and isSelected()).assertIsDisplayed()
            shot(scenario, "grid-sideways")

            // The whole band yields, and the tiles scroll.
            compose.onNode(tile("v0.mp4")).assertIsDisplayed()
            compose.onNode(hasSetTextAction()).assertIsDisplayed()
            repeat(3) { drag(0.6f, 0.85f, 0.6f, 0.2f) }
            play(500)
            assertTrue("the tiles did not scroll sideways", offScreen(tile("v0.mp4")))
            assertTrue("the search field did not yield sideways", offScreen(hasSetTextAction()))
            assertTrue(
                "the sort band did not yield sideways",
                offScreen(hasContentDescription("Show as list") or hasContentDescription("Show as grid")),
            )
            shot(scenario, "grid-sideways-scrolled")

            // The band comes back as a collapsing header does, from its bottom edge, and the field
            // is its top — so the drag has to be longer than the band for the field to show. It
            // starts below the top bar, which does not scroll the list.
            drag(0.6f, 0.3f, 0.6f, 0.95f)
            play(500)
            compose.onNode(hasSetTextAction()).assertIsDisplayed()

            // The button's set opens upward from the corner, whole and tappable.
            compose.onNodeWithContentDescription("Actions").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Voice memo").assertIsDisplayed()
            compose.onNodeWithText("New note").assertIsDisplayed()
            shot(scenario, "grid-sideways-actions-open")
            compose.onNodeWithContentDescription("Close actions").performClick()
            compose.waitForIdle()

            compose.onNode(entry("Files")).performClick()
            await("Files to become the active entry") {
                compose.onAllNodes(entry("Files") and isSelected()).fetchSemanticsNodes().isNotEmpty()
            }
        }
    }

    @Test
    fun tilesKeepTheirSizeAcrossATurn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            await("a photograph tile") { compose.onAllNodes(tile("p0.png")).fetchSemanticsNodes().isNotEmpty() }
            // The layout size, not `boundsInRoot`: those are clipped to the screen, and a tile below
            // the fold reports the sliver that shows.
            val upright = compose.onNode(tile("p0.png")).fetchSemanticsNode().size.height

            turn(scenario, UiAutomation.ROTATION_FREEZE_90)
            await("a photograph tile sideways") { compose.onAllNodes(tile("p0.png")).fetchSemanticsNodes().isNotEmpty() }
            val sideways = compose.onNode(tile("p0.png")).fetchSemanticsNode().size.height
            assertTrue(
                "a tile is $sideways px sideways against $upright px upright",
                abs(sideways - upright) <= upright * 0.25f,
            )
        }
    }
}
