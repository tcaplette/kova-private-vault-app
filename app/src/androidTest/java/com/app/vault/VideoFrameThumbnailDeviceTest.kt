package com.app.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Taking the frame on screen as a video's thumbnail — `video-frame-thumbnail`, tasks 4.1–4.3
 * and 4.5.
 *
 * **The clip is muxed on the device and built to catch the wrong frame.** Its first second is
 * red and the rest is blue, and it has exactly one sync frame — the first. The automatic
 * choice takes sync frames, so it lands on red; a frame taken at 1.5 s is blue only if the read
 * was exact, and a nearest-sync read would come back red. That is D3's claim made checkable.
 *
 * **Two fixture locations, by what each test needs.** The store-level tests build in a directory
 * of their own under `filesDir`, `RotationDeviceTest`'s arrangement, so they run whatever else is
 * installed. The screen tests need the app's own `filesDir`, because that is the only vault
 * `MainActivity` opens, and skip rather than overwrite one that is already there.
 */
@RunWith(AndroidJUnit4::class)
class VideoFrameThumbnailDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    /** Where this test's vault lives — see the class KDoc. */
    private var root: File? = null
    private val slots get() = File(root, "vault.slots")
    private val blobsDir get() = File(root, "blobs")
    private val incoming get() = File(root, "incoming")

    private var created = false
    private lateinit var store: BlobStore
    private lateinit var indexId: ByteArray

    private enum class Hue { RED, BLUE, OTHER }

    private companion object {
        const val TAG = "VideoFrameThumb"
        const val PASS_TEXT = "correct horse battery staple"
        const val CLIP = "clip.mp4"
        const val BROKEN = "broken.mp4"
        const val SONG = "song.mp3"
        const val PHOTO = "photo.png"
        const val ROW = "Use this frame as the thumbnail"
        const val FAILED = "This frame couldn't be used."
        const val SHOTS = "/data/local/tmp/video-frame-thumbnail"

        /** Long enough to still be playing when the menu is reached. */
        const val CLIP_MS = 8_000

        /** Muxed once for the suite: eight seconds is several seconds of software encode. */
        var clipBytes: ByteArray? = null

        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }

    // --- fixture --------------------------------------------------------------

    /** Red for the first second, blue after, one sync frame at the start. */
    private fun clip(): ByteArray = clipBytes ?: run {
        val out = File(ctx.cacheDir, "vft-source-${System.nanoTime()}.mp4")
        val spec = VideoCorpus.Spec(
            "red-then-blue", MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4", 0, CLIP_MS,
        )
        try {
            // Video-range BT.601: red is Y81 Cb90 Cr240, blue is Y41 Cb240 Cr110.
            val made = VideoCorpus.generate(spec, out, keyFrameIntervalS = 60) { buf, n ->
                if (n < VideoCorpus.FPS) VideoCorpus.flat(buf, 81, 90, 240)
                else VideoCorpus.flat(buf, 41, 240, 110)
            }
            check(made) { "this target has no H.264 encoder to mux the fixture with" }
            out.readBytes()
        } finally {
            // The source is plaintext by construction; it must be gone before any scan.
            out.delete()
        }
    }.also { clipBytes = it }

    private fun png(): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) for (y in 0 until height) {
                setPixel(x, y, android.graphics.Color.rgb(40 + x, 160, 60 + y))
            }
        }.compress(Bitmap.CompressFormat.PNG, 100, out)
    }.toByteArray()

    /**
     * Builds the fixture — in a directory of its own when [ownDirectory], otherwise in the app's
     * `filesDir`, where `MainActivity` will open it.
     */
    private fun fixture(ownDirectory: Boolean) {
        root = if (ownDirectory) {
            File(ctx.filesDir, "vft-${System.nanoTime()}").apply { mkdirs() }
        } else {
            ctx.filesDir
        }
        store = BlobStore(blobsDir, incoming)
        val lifecycle = VaultLifecycle(slots, store)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        if (!ownDirectory) {
            // The shipped launcher identity, for `ViewerChromeDeviceTest`'s reason: a suite
            // that runs after a cover suite otherwise launches a cover front end, not the lock
            // screen. After the check above, so a skipped run changes nothing that is installed.
            LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        }
        val video = clip()
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true

        val vmk = VaultSession.vmk()!!
        indexId = VaultSession.metadataBlobId()!!.copyOf()
        val thumbs = ThumbnailStore(store)

        MetadataDb.commit(store, indexId, vmk) { db ->
            fun item(name: String, bytes: ByteArray, order: Long): VaultItem {
                val written = store.writeItem(ByteArrayInputStream(bytes), vmk)
                return VaultItem(
                    blobIds = written.blobIds,
                    plaintextLength = written.plaintextLength,
                    thumb = null,
                    filename = name,
                    album = null,
                    createdAt = 1_700_000_000_000L - order * 60_000L,
                    importedAt = order,
                    tags = emptyList(),
                )
            }
            val photo = item(PHOTO, png(), 1)
            val photoWebp = Thumbnails.generate(store, photo, vmk).webp!!
            db.items += photo.withThumb(thumbs.stage(db, photoWebp))

            // The automatic choice, exactly as import makes it.
            val clipItem = item(CLIP, video, 2)
            val auto = Thumbnails.generate(store, clipItem, vmk)
            db.items += clipItem.withThumb(thumbs.stage(db, auto.webp!!), auto.durationMs)

            // Bytes no retriever can decode, carrying a thumbnail so its tile is named.
            db.items += item(BROKEN, ByteArray(64 * 1024) { (it * 31 % 251).toByte() }, 3)
                .let { it.withThumb(thumbs.stage(db, photoWebp)) }

            // An item that plays and has no frame.
            db.items += item(SONG, ByteArray(4096) { (it % 251).toByte() }, 4)
            thumbs.flush(db, vmk)
        }
        // Left open for the store-level tests; the screen tests lock before launching.
    }

    @After
    fun tearDown() {
        lockKeyringAndVault()
        ThumbnailCache.clear()
        val at = root ?: return
        if (created) {
            // Only work this test's vault could have started. A skipped test cancels nothing
            // that belongs to what is installed.
            WorkManager.getInstance(ctx).cancelAllWork()
            MetadataDb.awaitQuiescent()
            slots.delete()
            blobsDir.deleteRecursively()
            incoming.deleteRecursively()
        }
        if (at != ctx.filesDir) at.deleteRecursively()
    }

    // --- helpers ----------------------------------------------------------------

    private fun index() = MetadataDb.load(store, indexId, VaultSession.vmk()!!)

    private fun itemNamed(name: String) = index().items.single { it.filename == name }

    private fun same(a: ThumbRef, b: ThumbRef) =
        a.packBlobId.contentEquals(b.packBlobId) && a.offset == b.offset && a.length == b.length

    private fun decode(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw AssertionError("the record did not decode")

    /** Which of the two fixture colours a thumbnail mostly is. */
    private fun hue(bitmap: Bitmap): Hue {
        var r = 0L
        var b = 0L
        var n = 0L
        for (x in 0 until bitmap.width step 8) for (y in 0 until bitmap.height step 8) {
            val p = bitmap.getPixel(x, y)
            r += (p shr 16) and 0xFF
            b += p and 0xFF
            n++
        }
        val red = r / n
        val blue = b / n
        return when {
            red > blue + 80 -> Hue.RED
            blue > red + 80 -> Hue.BLUE
            else -> Hue.OTHER
        }.also { Log.i(TAG, "mean red $red blue $blue -> $it") }
    }

    /** Every file in private storage holding a WebP or PNG image, by name. */
    private fun plaintextImages(): List<String> =
        listOfNotNull(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir)
            .flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }
            .filter { file ->
                // A file renamed into place mid-walk is gone by the time it is opened.
                runCatching { FileScan.containsWebp(file) || FileScan.contains(file, PNG_SIGNATURE) }
                    .getOrDefault(false)
            }
            .map { it.name }

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 45_000, condition = condition)
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

    /** The first node matching [text]'s whole rendered string, or null. */
    private fun textOf(text: String): String? =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

    /** The readout's position half, as seconds — "0:03 / 0:08" is 3. */
    private fun readoutSeconds(): Int? = textOf(" / ")?.substringBefore(" / ")?.let(::seconds)

    private fun seconds(label: String): Int =
        label.split(":").fold(0) { acc, part -> acc * 60 + part.trim().toInt() }

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

    /** Raises the layer and opens the menu on it. */
    private fun openMenu() {
        compose.onRoot().performClick()
        await("the menu control") { showing("More") }
        compose.onNodeWithContentDescription("More").performClick()
        await("the menu") { has("Rename") }
    }

    private fun top(label: String) =
        compose.onAllNodes(hasText(label)).fetchSemanticsNodes().single().boundsInWindow.top

    private fun shot(name: String) {
        compose.waitForIdle()
        // The frame has to reach the display before uiAutomation reads it.
        Thread.sleep(700)
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

    // --- 4.1. the frame, the write, and what is left behind ----------------------

    @Test
    fun theFrameAtOneAndAHalfSecondsIsTheBlueOneAndTheOldRecordIsLeftUnreachable() {
        fixture(ownDirectory = true)
        val vmk = VaultSession.vmk()!!
        val thumbs = ThumbnailStore(store)
        val before = index()
        val clip = before.items.single { it.filename == CLIP }
        val old = clip.thumb!!

        // The automatic choice took the one sync frame, which is red — and decoding it here
        // puts it in the cache, so the eviction below has something to evict.
        val cached = ThumbnailCache.get(old) { thumbs.read(before, old, vmk) }
        assertEquals("the automatic thumbnail is the red opening", Hue.RED, hue(cached!!))

        // Scanned throughout the write, not only after it: a spill cleaned up before the
        // write returns is still a spill.
        val hits = CopyOnWriteArrayList<String>()
        val scanning = AtomicBoolean(true)
        val scanner = thread { while (scanning.get()) hits.addAll(plaintextImages()) }
        val started = SystemClock.elapsedRealtime()
        val set = try {
            VaultThumbnail.setFromFrame(store, indexId, vmk, clip, 1_500)
        } finally {
            scanning.set(false)
            scanner.join()
        }
        // D's measurement: `OPTION_CLOSEST` decodes forward from the sync frame.
        Log.i(TAG, "setFromFrame at 1.5 s took ${SystemClock.elapsedRealtime() - started} ms")
        hits.addAll(plaintextImages())

        assertTrue("a decodable frame must be set", set)
        val after = index()
        val fresh = after.items.single { it.filename == CLIP }.thumb!!
        assertFalse("the reference must name a new record", same(fresh, old))
        assertArrayEquals(
            "the new record lands in the open pack",
            after.thumbPacks.last().id, fresh.packBlobId,
        )
        assertEquals(
            "the frame at 1.5 s is the blue second — a nearest-sync read would be the red first",
            Hue.BLUE, hue(decode(thumbs.read(after, fresh, vmk))),
        )
        assertEquals("the duration is carried", clip.durationMs, after.items.single { it.filename == CLIP }.durationMs)

        // Copy-on-write: the open pack was replaced by its successor, which carries the old
        // record at the offset it had, and the predecessor went once the index was saved.
        val position = before.thumbPacks.indexOfFirst { it.id.contentEquals(old.packBlobId) }
        val oldNow = ThumbRef(after.thumbPacks[position].id, old.offset, old.length)
        assertFalse(
            "the predecessor pack must be deleted once the index naming its successor is saved",
            store.fileOf(old.packBlobId).exists(),
        )
        assertTrue(
            "no index reference may name the old record",
            after.items.none { item -> item.thumb?.let { same(it, oldNow) } == true },
        )
        assertEquals(
            "the old record still reads from its pack",
            Hue.RED, hue(decode(thumbs.read(after, oldNow, vmk))),
        )
        after.items.filter { it.filename != CLIP }.mapNotNull { it.thumb }.forEach { ref ->
            decode(thumbs.read(after, ref, vmk))
        }
        assertNull("the cache must hold no decoded copy of the old record", ThumbnailCache.get(old) { null })
        assertTrue("plaintext image bytes appeared during the write: $hits", hits.isEmpty())
    }

    // --- 4.6. the process dies between the pack and the index ------------------

    /**
     * A flush on an index that is never saved — exactly what a death between the pack write
     * and the index write leaves on disk, and the reason `ThumbnailStore.flush` is
     * copy-on-write. Before it was, this left the open pack's last-chunk records unreadable.
     */
    @Test
    fun aFlushWhoseIndexIsNeverSavedLeavesEveryThumbnailReading() {
        fixture(ownDirectory = true)
        val vmk = VaultSession.vmk()!!
        val onDisk = index()
        val predecessor = onDisk.thumbPacks.last().id

        // The write, up to the point the index would have been saved.
        val unsaved = index()
        val thumbs = ThumbnailStore(store)
        thumbs.stage(unsaved, ByteArray(30_000) { 7 })
        thumbs.flush(unsaved, vmk)
        val successor = unsaved.thumbPacks.last().id
        assertTrue("the successor must be on disk", store.fileOf(successor).exists())

        // What the next unlock reads.
        val reopened = index()
        val refs = reopened.items.mapNotNull { it.thumb }
        assertEquals("every thumbnail the saved index had", onDisk.items.count { it.thumb != null }, refs.size)
        refs.forEach { decode(thumbs.read(reopened, it, vmk)) }
        assertTrue("the predecessor must be untouched", store.fileOf(predecessor).exists())
        assertTrue(
            "the successor must be referenced by nothing on disk",
            reopened.thumbPacks.none { it.id.contentEquals(successor) } &&
                reopened.items.none { it.thumb?.packBlobId?.contentEquals(successor) == true },
        )

        // And once an index naming the successor is saved, the predecessor goes.
        unsaved.save(store, indexId, vmk)
        assertFalse("the predecessor must be deleted after the save", store.fileOf(predecessor).exists())
        val saved = index()
        saved.items.mapNotNull { it.thumb }.forEach { decode(thumbs.read(saved, it, vmk)) }
    }

    // --- 4.3, the store half ----------------------------------------------------

    @Test
    fun aVideoTheRetrieverCannotDecodeWritesNothing() {
        fixture(ownDirectory = true)
        val vmk = VaultSession.vmk()!!
        val before = index()
        val broken = before.items.single { it.filename == BROKEN }

        val set = VaultThumbnail.setFromFrame(store, indexId, vmk, broken, 0)

        assertFalse(set)
        val after = index()
        assertTrue("the reference must be unchanged", same(after.items.single { it.filename == BROKEN }.thumb!!, broken.thumb!!))
        assertEquals("no pack may be added", before.thumbPacks.size, after.thumbPacks.size)
        assertEquals(
            "the open pack must not grow",
            before.thumbPacks.last().plaintextLength, after.thumbPacks.last().plaintextLength,
        )
    }

    // --- 4.2. the row, its order, and the pause ---------------------------------

    @Test
    fun onAVideoTheRowIsInOrderAndChoosingItPausesWhereTheFrameWasTaken() {
        fixture(ownDirectory = false)
        val old = itemNamed(CLIP).thumb!!
        lockKeyringAndVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            open(CLIP)
            // Playing, and past the red second, so the frame taken is not import's.
            await("playback past a second") { (readoutSeconds() ?: 0) >= 1 }
            openMenu()

            await("the row") { has(ROW) }
            val order = listOf("Move to folder", ROW, "Rename", "Share securely", "Delete")
            val tops = order.map(::top)
            assertEquals("the menu's order on a video is D1's: $order", tops.sorted(), tops)
            assertFalse("an item in no folder has no cover row", has("Set as folder cover"))

            compose.onNodeWithText(ROW).performClick()
            await("the confirmation") { has("Thumbnail set to", substring = true) }
            val shownAt = SystemClock.elapsedRealtime()

            val line = textOf("Thumbnail set to")!!
            val taken = line.removePrefix("Thumbnail set to ").removeSuffix(".")
            await("the paused control") { showing("Play") }
            assertEquals(
                "the video must be paused on the position the line names",
                seconds(taken), readoutSeconds(),
            )
            val node = compose.onAllNodes(hasText("Thumbnail set to", substring = true))
                .fetchSemanticsNodes().single()
            assertTrue(
                "the line must be a polite live region",
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite).matches(node),
            )
            assertTrue("the line must not take focus", node.config.getOrNull(SemanticsProperties.Focused) != true)

            val written = itemNamed(CLIP).thumb!!
            assertFalse("the chosen frame must be the item's thumbnail now", same(written, old))

            await("the line to leave") { !has("Thumbnail set to", substring = true) }
            val held = SystemClock.elapsedRealtime() - shownAt
            assertTrue("the line must hold for three seconds, held $held ms", held >= 3_000)
        }
    }

    @Test
    fun theRowIsAbsentOnAnAudioItem() {
        fixture(ownDirectory = false)
        lockKeyringAndVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            // `vault-destinations`: audio is Memos' kind, not Media's.
            compose.onNodeWithText("Memos").performClick()
            open(SONG)
            openMenu()
            assertFalse("audio has no frame to take", has(ROW))
        }
    }

    // --- 4.3, the screen half ---------------------------------------------------

    @Test
    fun aFrameThatCannotBeDecodedSaysSoAndChangesNothing() {
        fixture(ownDirectory = false)
        val before = itemNamed(BROKEN).thumb!!
        lockKeyringAndVault()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            open(BROKEN)
            openMenu()
            await("the row") { has(ROW) }
            compose.onNodeWithText(ROW).performClick()

            await("the failure line") { has(FAILED) }
            assertTrue("the reference must be unchanged", same(itemNamed(BROKEN).thumb!!, before))
        }
    }

    // --- 4.5. what the user sees ------------------------------------------------

    /**
     * The grid tile before, the menu on a video, the line, and the tile after — real captures
     * through `uiAutomation`, with `FLAG_SECURE` cleared on this activity only. Saved to
     * [SHOTS] on the emulator.
     */
    @Test
    fun screenshotsOfTheTileTheMenuAndTheLine() {
        fixture(ownDirectory = false)
        lockKeyringAndVault()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // `VaultApplication` sets it at creation and never again, so clearing it here holds
            // for this activity and the menu's popup, which inherits it when it opens.
            scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            unlock()
            await("the clip tile") { showing(CLIP) }
            shot("1-grid-before")

            open(CLIP)
            await("playback into the blue") { (readoutSeconds() ?: 0) >= 2 }
            openMenu()
            await("the row") { has(ROW) }
            shot("2-menu")

            compose.onNodeWithText(ROW).performClick()
            await("the confirmation") { has("Thumbnail set to", substring = true) }
            shot("3-line")

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("the grid") { onGrid() }
            await("the clip tile") { showing(CLIP) }
            shot("4-grid-after")
        }
    }
}
