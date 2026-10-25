package com.app.vault

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The Media viewer's filmstrip, on the real screen — `viewer-filmstrip`.
 *
 * Five photos and a video at the vault's root, two photos in the trash, and a folder holding
 * one photo. The strip must hold the five, mark the one on screen as selected, follow a tap and
 * a swipe, hide with the layer, and be absent on a video, in the trash and on a list of one.
 *
 * **This creates a real vault in the app's own `filesDir`**, like `ViewerChromeDeviceTest`, and
 * skips rather than overwriting one that is already there.
 */
@RunWith(AndroidJUnit4::class)
class ViewerFilmstripDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false

    private companion object {
        const val PASS_TEXT = "correct horse battery staple"

        /** Newest first is the grid's default order, so these sit in this order. */
        val PHOTOS = List(5) { "photo-${it + 1}.png" }

        /** A video, by its filename: the viewer picks its axis from the kind before any player. */
        const val CLIP = "clip.mp4"

        val TRASHED = listOf("trashed-1.png", "trashed-2.png")

        const val FOLDER = "Solo"
        const val SOLO = "solo.png"
    }

    @Before
    fun setUp() {
        // The shipped launcher identity, so the app opens on the unlock screen — see
        // `ViewerChromeDeviceTest.setUp`.
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        val store = BlobStore(blobsDir, incoming)
        val lifecycle = VaultLifecycle(slots, store)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true

        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        val thumbs = ThumbnailStore(store)

        MetadataDb.commit(store, indexId, vmk) { db ->
            val solo = VaultFolder(MetadataDb.newFolderId(), null, FOLDER)
            db.folders += solo
            val names = PHOTOS + TRASHED + SOLO
            names.forEachIndexed { i, name ->
                val written = store.write(ByteArrayInputStream(png(i)), vmk)
                var item = VaultItem(
                    blobIds = listOf(written.blobId),
                    plaintextLength = written.plaintextLength,
                    thumb = null,
                    filename = name,
                    album = null,
                    folderId = if (name == SOLO) solo.folderId else null,
                    createdAt = 1_700_000_000_000L - i * 60_000L,
                    importedAt = 1L + i,
                    tags = emptyList(),
                )
                // A tile is found by its thumbnail's description, so every photo has one.
                Thumbnails.generate(store, item, vmk).webp?.let { item = item.withThumb(thumbs.stage(db, it)) }
                if (name in TRASHED) item = item.withDeletedAt(System.currentTimeMillis())
                db.items += item
            }
            val clipBytes = ByteArray(4096) { (it % 251).toByte() }
            val clipWritten = store.write(ByteArrayInputStream(clipBytes), vmk)
            db.items += VaultItem(
                blobIds = listOf(clipWritten.blobId),
                plaintextLength = clipWritten.plaintextLength,
                // The first photo's pixels, so the clip has a tile to press.
                thumb = db.items.first { it.filename == PHOTOS[0] }.thumb,
                filename = CLIP,
                album = null,
                // Newest, so its tile is in the grid's first row and not under the navigation.
                createdAt = 1_700_000_000_000L + 60_000L,
                importedAt = 100L,
                tags = emptyList(),
            )
            thumbs.flush(db, vmk)
        }
        lockKeyringAndVault()
    }

    @After
    fun tearDown() {
        lockKeyringAndVault()
        ThumbnailCache.clear()
        WorkManager.getInstance(ctx).cancelAllWork()
        if (!created) return
        MetadataDb.awaitQuiescent()
        slots.delete()
        blobsDir.deleteRecursively()
        incoming.deleteRecursively()
    }

    /** A distinct colour and a distinct shape per photo, so no two thumbnails are alike. */
    private fun png(i: Int): ByteArray = ByteArrayOutputStream().also {
        val wide = i % 2 == 0
        Bitmap.createBitmap(if (wide) 600 else 400, if (wide) 400 else 600, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.rgb(40 * i % 256, 90, 255 - 40 * i % 256)) }
            .compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    // --- harness --------------------------------------------------------------

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 45_000, condition = condition)
        } catch (e: Throwable) {
            runCatching {
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                    compose.onAllNodes(isRoot())[i].printToLog("ViewerFilmstrip")
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun showing(description: String): Boolean =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun onGrid(): Boolean = showing("All vaults")

    private val inStrip = hasAnyAncestor(hasTestTag("filmstrip"))

    private fun strip(): Boolean = compose.onAllNodes(hasTestTag("filmstrip")).fetchSemanticsNodes().isNotEmpty()

    /** The strip's cells, by the filename each is announced with. */
    private fun cells(): List<String> = compose.onAllNodes(inStrip and hasContentDescriptionAny())
        .fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }

    private fun hasContentDescriptionAny() = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)

    /** The one cell announced as selected, or null. */
    private fun selectedCell(): String? = compose.onAllNodes(inStrip and isSelected())
        .fetchSemanticsNodes()
        .singleOrNull()
        ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    /** The photo on screen — its page, not its cell. */
    private fun onScreen(name: String): Boolean =
        compose.onAllNodes(hasContentDescription(name) and !inStrip).fetchSemanticsNodes().isNotEmpty()

    private fun reveal() {
        compose.onRoot().performClick()
        compose.waitForIdle()
    }

    private fun unlock() {
        await("the lock screen") {
            compose.onAllNodes(hasText("Welcome back")).fetchSemanticsNodes().isNotEmpty()
        }
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

    private fun open(name: String) {
        await("the $name tile") { showing(name) }
        compose.onNodeWithContentDescription(name).performClick()
        await("the viewer") { !onGrid() }
        compose.waitForIdle()
        reveal()
    }

    /**
     * Opens the first photo from Media's Photos chip. Under All, the newest item — the clip — takes
     * a full-width row and pushes the photos under the bottom navigation, where a tap lands on a
     * destination tab instead. The chip changes nothing the viewer pages between: photos only,
     * either way. It resets on lock, so nothing is left behind.
     */
    private fun openFirstPhoto() {
        await("the Photos chip") { showing("Show photos") }
        compose.onNodeWithContentDescription("Show photos").performClick()
        compose.waitForIdle()
        open(PHOTOS[0])
    }

    // --- 1. the strip holds the list and marks the photo on screen -------------

    @Test
    fun theStripHoldsTheFivePhotosAndSelectsTheOpenedOne() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openFirstPhoto()
            await("the strip") { strip() }

            assertEquals("the strip holds the photos the viewer pages between, in order", PHOTOS, cells())
            assertFalse("a video does not page sideways, so it has no cell", CLIP in cells())
            assertEquals("the opened photo is the one selected", PHOTOS[0], selectedCell())
        }
    }

    @Test
    fun theStripHidesWithTheRows() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openFirstPhoto()
            await("the strip and the rows") { strip() && showing("Back") }

            await("the strip to hide with the rows") { !strip() && !showing("Back") }
        }
    }

    // --- 2. it follows a tap and a swipe ---------------------------------------

    @Test
    fun tappingTheFifthCellShowsTheFifthPhoto() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openFirstPhoto()
            await("the fifth cell") { PHOTOS[4] in cells() }

            compose.onNode(inStrip and hasContentDescription(PHOTOS[4])).performClick()

            await("the fifth photo on screen") { onScreen(PHOTOS[4]) && !onScreen(PHOTOS[0]) }
            await("the fifth cell selected") { selectedCell() == PHOTOS[4] }
        }
    }

    @Test
    fun swipingThePhotoMovesTheSelection() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openFirstPhoto()
            await("the strip") { strip() }

            compose.onNode(hasContentDescription(PHOTOS[0]) and !inStrip).performTouchInput { swipeLeft() }
            await("the next photo on screen") { onScreen(PHOTOS[1]) && !onScreen(PHOTOS[0]) }
            if (!strip()) reveal()

            await("the next cell selected") { selectedCell() == PHOTOS[1] }
        }
    }

    // --- 3. where it is not drawn ----------------------------------------------

    @Test
    fun aVideoHasNoStrip() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            open(CLIP)
            await("the revealed layer") { showing("More") }

            assertFalse("a video's bottom edge belongs to the transport", strip())
        }
    }

    @Test
    fun theTrashHasNoStrip() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            compose.onNodeWithContentDescription("Trash and settings").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Trash").performClick()
            val titled = { compose.onAllNodes(hasText("Trash")).fetchSemanticsNodes().isNotEmpty() }
            await("the trash screen") { !onGrid() && titled() && showing(TRASHED[0]) }
            compose.onNodeWithContentDescription(TRASHED[0]).performClick()
            // The viewer carries no title; the trash screen does.
            await("the viewer") { !titled() }
            compose.waitForIdle()
            reveal()
            await("the revealed layer") { showing("Back") }

            assertFalse("the trash is not the Media screen", strip())
        }
    }

    @Test
    fun aListOfOnePhotoHasNoStrip() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            await("the folder's tile") { compose.onAllNodes(hasText(FOLDER)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(FOLDER).performClick()
            open(SOLO)
            await("the revealed layer") { showing("Back") }

            assertFalse("one photo has nothing to move between", strip())
        }
    }
}
