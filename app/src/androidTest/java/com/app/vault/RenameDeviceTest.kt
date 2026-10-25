package com.app.vault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * P2-14 on the real screens.
 *
 * `RenameTest` asserts what a rename does to the row, the store and the export.
 * This one asserts the part that only exists on screen: that the viewer offers it,
 * that the typed name reaches the grid and the delete confirmation, that a refused
 * name commits nothing, that back commits nothing, and that a trashed item is not
 * offered a rename at all.
 *
 * **This test creates a real vault in the app's own `filesDir`**, like
 * `MultiSelectDeviceTest`, because a grid with items only exists behind an unlock.
 * It is removed in [tearDown] including on failure, and it skips rather than
 * touching a vault that is already there.
 */
@RunWith(AndroidJUnit4::class)
class RenameDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false
    private lateinit var store: BlobStore
    private lateinit var indexId: ByteArray

    private companion object {
        const val PASS_TEXT = "correct horse battery staple"
        const val ITEMS = 3

        /** The grid orders by import time descending, so `pick-3` is the first tile. */
        const val FIRST = "pick-3.png"
    }

    @Before
    fun setUp() {
        // ***This suite needs the app to open on the unlock screen, and it never said so.***
        // The launcher identity is an install-wide preference that outlives the vault by
        // design, and `LauncherIdentityDeviceTest` and the cover suites move it. A suite
        // that runs after one of those launches `MainActivity` and gets a **cover front
        // end** — a music player, not a passphrase field — and every wait in it times out
        // on "the lock screen". Sixty tests failed that way in one run, across nine
        // suites, none of which had anything to do with launcher identity.
        //
        // Stating the precondition is the fix: this is a device suite, and the shipped
        // state is the one it is entitled to start from.
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        store = BlobStore(blobsDir, incoming)
        val lifecycle = VaultLifecycle(slots, store)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true

        val vmk = VaultSession.vmk()!!
        indexId = VaultSession.metadataBlobId()!!.copyOf()
        val thumbs = ThumbnailStore(store)

        // A tile's semantics node is its thumbnail's `Image`; an item without one
        // cannot be tapped by a test.
        MetadataDb.commit(store, indexId, vmk) { db ->
            (1..ITEMS).forEach { n ->
                val written = store.write(ByteArrayInputStream(image(600, 400, n)), vmk)
                var item = VaultItem(
                    blobIds = listOf(written.blobId),
                    plaintextLength = written.plaintextLength,
                    thumb = null,
                    filename = "pick-$n.png",
                    album = null,
                    createdAt = null,
                    importedAt = n.toLong(),
                    tags = emptyList(),
                )
                Thumbnails.generate(store, item, vmk).webp?.let { webp ->
                    item = item.withThumb(thumbs.stage(db, webp))
                }
                db.items += item
            }
            thumbs.flush(db, vmk)
        }
        lockKeyringAndVault()
    }

    @After
    fun tearDown() {
        lockKeyringAndVault()
        ThumbnailCache.clear()
        // ***Cancel this test's background work before the next test starts.***
        // Nothing did, so a `MirrorWorker`, `MirrorDestroyWorker` or `ImportWorker`
        // enqueued here could still be running inside a later test — taking the index
        // commit lock, touching `blobs/`, and stalling an unlock past a 45s wait. That is
        // the shape every long-tail full-suite failure in this suite has had: a timeout
        // after unlock that passes in isolation every time.
        WorkManager.getInstance(ctx).cancelAllWork()
        if (!created) return
        // An index write can outlive the screen that started it — `loadIndex` runs the
        // thumbnail backfill on a bare thread. Deleting `blobs/` under a live commit
        // renames a partial index into place, and the index blob ID is derived from the
        // vault key, so every test on this passphrase shares it and the wreckage lands on
        // whichever test runs next. Wait for the write, then delete.
        MetadataDb.awaitQuiescent()
        slots.delete()
        blobsDir.deleteRecursively()
        incoming.deleteRecursively()
    }

    private fun image(w: Int, h: Int, seed: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(20 * seed % 256, 90, 140))
        val paint = Paint().apply { color = Color.rgb(240, 240 - 20 * seed % 200, 30) }
        canvas.drawRect(0f, 0f, w / 2f, h.toFloat(), paint)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    // --- harness (the shape `MultiSelectDeviceTest` proved) --------------------

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 45_000, condition = condition)
        } catch (e: Throwable) {
            // ***Every root, and never in a position to replace the real failure.***
            // `onRoot()` throws when a sheet or dialog is showing, because that is a
            // second composition root — so the dump meant to explain a timeout became
            // "Expected exactly '1' node but found '2'" and the timeout's own message
            // was lost. That happened, and it cost a full-suite run to notice.
            runCatching {
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                    compose.onAllNodes(isRoot())[i].printToLog("RenameVerify")
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun showing(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** `hasText` is an exact match; a refusal line is one sentence inside a paragraph. */
    private fun containing(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun onGrid(): Boolean =
        compose.onAllNodesWithContentDescription("All vaults").fetchSemanticsNodes().isNotEmpty()

    private fun selecting(): Boolean =
        compose.onAllNodesWithContentDescription("Leave selection").fetchSemanticsNodes().isNotEmpty()

    private fun renameOffered(): Boolean =
        compose.onAllNodesWithContentDescription("Rename").fetchSemanticsNodes().isNotEmpty()

    private fun unlock() {
        await("the lock screen") { showing("Welcome back") }
        compose.waitForIdle()
        val field = compose.onNode(hasText("Passphrase"))
        field.performClick()
        compose.waitForIdle()
        field.performTextInput(PASS_TEXT)
        await("the passphrase to register") {
            compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unlock").performClick()
        // `vault-keyring`. The passphrase opens a keyring and the app lands on
        // the list; the grid is behind the vault this taps.
        compose.openFirstVaultFromTheList()
        await("the grid") { onGrid() }
        compose.waitForIdle()
    }

    private fun back(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun awaitTile(filename: String) {
        await("the tile $filename") {
            compose.onAllNodesWithContentDescription(filename).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tap(filename: String) {
        awaitTile(filename)
        compose.onNodeWithContentDescription(filename).performClick()
        compose.waitForIdle()
    }

    private fun longPress(filename: String) {
        awaitTile(filename)
        compose.onNodeWithContentDescription(filename).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    /** Opens the drawer and navigates to the trash, in the open vault's group. */
    private fun openTrash() {
        compose.onNodeWithContentDescription("Trash and settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Trash").performClick()
        await("the trash screen") { showing("Trash") && !onGrid() }
    }

    /** Opens the item and its rename screen. The viewer's controls are revealed at entry. */
    private fun openRename(filename: String) {
        tap(filename)
        await("the viewer's rename control") { renameOffered() }
        compose.onNodeWithContentDescription("Rename").performClick()
        await("the rename screen") { showing("Rename item") }
    }

    private fun names() =
        MetadataDb.load(store, indexId, VaultSession.vmk()!!).items.map { it.filename }

    // --- 1. the surface does what the screen says -----------------------------

    @Test
    fun renamingFromTheViewerReachesTheGrid() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()

            openRename(FIRST)
            compose.onNode(hasText("Name")).performTextReplacement("Passport")
            compose.onNodeWithText("Rename").performClick()

            await("the new name in the index") { names().contains("Passport.png") }
            assertFalse("the old name survived the rename", names().contains(FIRST))

            // Back out of the viewer: the grid's tile is the new name, which is
            // the only place the user reads it.
            back(scenario)
            await("the grid") { onGrid() }
            awaitTile("Passport.png")

            // And the confirmation names it too, without acting on it.
            longPress("Passport.png")
            await("selection mode") { selecting() }
            compose.onNodeWithContentDescription("Delete selected").performClick()
            await("the confirmation") { showing("Delete Passport.png?") }
            compose.onNodeWithText("Keep").performClick()
            await("the confirmation to close") { !showing("Delete Passport.png?") }
            assertEquals("the confirmation acted on the item", ITEMS, names().size)
        }
    }

    /** The extension is shown and is not part of what the field holds. */
    @Test
    fun theExtensionIsShownAndKept() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()

            openRename(FIRST)
            compose.onNodeWithText(".png").assertIsDisplayed()

            compose.onNode(hasText("Name")).performTextReplacement("Birthday")
            compose.onNodeWithText("Rename").performClick()

            await("the new name in the index") { names().contains("Birthday.png") }
        }
    }

    // --- 2. nothing is written unless the user says so ------------------------

    @Test
    fun aRefusedNameStaysOnTheScreenAndCommitsNothing() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            val before = names()

            openRename(FIRST)
            compose.onNode(hasText("Name")).performTextReplacement("holiday/2019")
            compose.onNodeWithText("Rename").performClick()

            await("the refusal") { containing("cannot contain a slash") }
            assertTrue("a refused name must not leave the screen", showing("Rename item"))
            assertEquals("a refused name was committed", before, names())
        }
    }

    @Test
    fun backLeavesTheRenameAndReturnsToTheItem() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            val before = names()

            openRename(FIRST)
            compose.onNode(hasText("Name")).performTextReplacement("Passport")
            back(scenario)

            // The item is still open — back left the rename, not the viewer.
            await("the viewer again") { renameOffered() }
            assertFalse("back left the viewer as well", onGrid())
            assertEquals("back committed the typed name", before, names())
        }
    }

    // --- 3. not in the trash --------------------------------------------------

    @Test
    fun aTrashedItemIsOfferedNoRename() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()

            longPress(FIRST)
            await("selection mode") { selecting() }
            compose.onNodeWithContentDescription("Delete selected").performClick()
            await("the confirmation") { showing("Delete $FIRST?") }
            compose.onNodeWithText("Delete").performClick()
            await("the item to leave the grid") {
                MetadataDb.load(store, indexId, VaultSession.vmk()!!)
                    .items.any { it.deletedAt != null }
            }

            openTrash()
            tap(FIRST)
            // The viewer is open — its page is there — and neither control is.
            compose.waitForIdle()
            assertFalse("a trashed item must not offer a rename", renameOffered())
            assertFalse(
                "a trashed item must not offer a delete either",
                compose.onAllNodesWithContentDescription("Delete").fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }
}
