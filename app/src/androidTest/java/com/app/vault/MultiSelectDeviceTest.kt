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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
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
 * P2-11's gestures and bar, against the real activity.
 *
 * `MultiSelectTest` asserts what a selection *does* to rows and blobs. This one
 * asserts the part that only exists on screen: that a long-press begins a
 * selection rather than a delete, that a tap toggles instead of opening, that the
 * bar counts, and — the outline's second Verify clause — that **back leaves a
 * selection without acting on it**.
 *
 * **This test creates a real vault in the app's own `filesDir`**, like
 * `ScreenChromeDeviceTest`, because a grid with items only exists behind an
 * unlock. It is removed in [tearDown] including on failure, and it skips rather
 * than touching a vault that is already there.
 */
@RunWith(AndroidJUnit4::class)
class MultiSelectDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false
    private lateinit var store: BlobStore
    private lateinit var indexId: ByteArray

    /** The content blobs the fixture wrote, so a delete can be checked by identity. */
    private val fixtureBlobIds = mutableListOf<ByteArray>()

    private companion object {
        const val PASS_TEXT = "correct horse battery staple"
        const val ITEMS = 6

        /**
         * The tiles the tests touch, in the order the grid shows them.
         *
         * The grid sorts by capture date descending and falls back to the import
         * time, and the fixture imports `pick-1` first — so `pick-6` is the
         * **first** tile. A `LazyColumn` composes only what is on screen, so the
         * later ones have no semantics node to press at all: the first attempt at
         * this test pressed `pick-1` and failed with "could not find any node",
         * which reads like a broken gesture and is really a scroll position.
         */
        const val FIRST = "pick-6.png"
        const val SECOND = "pick-5.png"
        const val THIRD = "pick-4.png"
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

        // Thumbnails are not decoration here: a tile without one draws no image,
        // and an item with no image node cannot be long-pressed by a test.
        MetadataDb.commit(store, indexId, vmk) { db ->
            (1..ITEMS).forEach { n ->
                val written = store.write(ByteArrayInputStream(image(600, 400, n)), vmk)
                fixtureBlobIds += written.blobId
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
        fixtureBlobIds.clear()
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

    // --- harness (the shape `ScreenChromeDeviceTest` proved) -------------------

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
                    compose.onAllNodes(isRoot())[i].printToLog("MultiSelectVerify")
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun showing(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun onGrid(): Boolean =
        compose.onAllNodesWithContentDescription("All vaults").fetchSemanticsNodes().isNotEmpty()

    private fun selecting(): Boolean =
        compose.onAllNodesWithContentDescription("Leave selection").fetchSemanticsNodes().isNotEmpty()

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

    /**
     * Waits for a tile to exist before touching it.
     *
     * The grid arrives before its tiles do: a tile's semantics node is the
     * thumbnail's `Image`, and that only exists once the pack has been read and
     * decoded — which happens off the composition, after `loadIndex` has already
     * put the header on screen. Pressing the moment "All vaults" appears finds
     * nothing, and the failure reads as a broken gesture rather than as a race.
     */
    private fun awaitTile(filename: String) {
        await("the tile $filename") {
            compose.onAllNodesWithContentDescription(filename).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun longPress(filename: String) {
        awaitTile(filename)
        compose.onNodeWithContentDescription(filename).performTouchInput { longClick() }
        compose.waitForIdle()
    }

    private fun tap(filename: String) {
        awaitTile(filename)
        compose.onNodeWithContentDescription(filename).performClick()
        compose.waitForIdle()
    }

    /**
     * Rows the **grid** is showing — P2-12.
     *
     * Live rows, not every row: a delete now stamps `deletedAt` and leaves the row
     * in the index, so counting `items.size` would count the trash and read as
     * "the delete did nothing".
     */
    private fun rows() =
        MetadataDb.load(store, indexId, VaultSession.vmk()!!).items.count { it.deletedAt == null }

    /** Rows in the trash — the other half of what [rows] used to count. */
    private fun trashed() =
        MetadataDb.load(store, indexId, VaultSession.vmk()!!).items.count { it.deletedAt != null }

    private fun blobs() = store.allBlobIds().size

    // --- 1. the mode ----------------------------------------------------------

    @Test
    fun longPressSelectsAndTapsToggleWithoutOpening() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()

            longPress(FIRST)
            await("selection mode") { selecting() }
            compose.onNodeWithText("1 selected").assertIsDisplayed()
            assertFalse(
                "long-press must begin a selection, not raise P2-10's confirmation",
                showing("The copy in this vault is destroyed. It cannot be recovered."),
            )

            tap(SECOND)
            await("two selected") { showing("2 selected") }
            // The viewer would have replaced the grid; the bar proves it did not.
            assertTrue("a tap while selecting must not open the viewer", selecting())

            // The same tile again removes it.
            tap(SECOND)
            await("one selected") { showing("1 selected") }

            // Deselecting the last one leaves the mode rather than standing an
            // empty bar over the grid.
            tap(FIRST)
            await("the ordinary grid") { onGrid() && !selecting() }
        }
    }

    @Test
    fun selectAllTakesEveryItemTheGridLists() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            longPress(FIRST)
            await("selection mode") { selecting() }

            compose.onNodeWithContentDescription("Select all").performClick()
            await("all selected") { showing("$ITEMS selected") }
        }
    }

    // --- 2. the outline's second Verify clause --------------------------------

    @Test
    fun backLeavesTheSelectionWithoutActingOnAnything() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            val rowsBefore = rows()
            val blobsBefore = blobs()

            longPress(FIRST)
            await("selection mode") { selecting() }
            tap(THIRD)
            await("two selected") { showing("2 selected") }

            back(scenario)

            await("the ordinary grid") { onGrid() && !selecting() }
            assertEquals("back must delete nothing", rowsBefore, rows())
            assertEquals("back must unlink nothing", blobsBefore, blobs())
            // Still on the grid, not out of the activity.
            assertTrue("back must not leave the grid", onGrid())
        }
    }

    // --- 3. the delete, from the bar -----------------------------------------

    @Test
    fun deletingASelectionConfirmsWithACountAndRemovesThatMany() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            val rowsBefore = rows()

            longPress(FIRST)
            await("selection mode") { selecting() }
            tap(SECOND)
            tap(THIRD)
            await("three selected") { showing("3 selected") }

            compose.onNodeWithContentDescription("Delete selected").performClick()
            await("the confirmation") { showing("Delete 3 items?") }
            compose.onNodeWithText("Keep").performClick()

            compose.waitForIdle()
            assertEquals("keeping must delete nothing", rowsBefore, rows())
            assertTrue("the selection survives a declined confirmation", selecting())

            compose.onNodeWithContentDescription("Delete selected").performClick()
            await("the confirmation again") { showing("Delete 3 items?") }
            compose.onNodeWithText("Delete").performClick()

            await("three fewer rows") { rows() == rowsBefore - 3 }
            await("the ordinary grid") { onGrid() && !selecting() }
            // P2-12: they went to the trash, not out of the vault. The blobs are
            // still there, which is what makes a restore possible.
            assertEquals("the three must be in the trash", 3, trashed())
        }
    }

    @Test
    fun selectAllThenDeleteEmptiesTheVaultAndLandsOnTheEmptyState() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            assertEquals("the fixture must have items to empty", ITEMS, rows())

            longPress(FIRST)
            await("selection mode") { selecting() }
            compose.onNodeWithContentDescription("Select all").performClick()
            await("all selected") { showing("$ITEMS selected") }

            compose.onNodeWithContentDescription("Delete selected").performClick()
            await("the confirmation") { showing("Delete $ITEMS items?") }
            compose.onNodeWithText("Delete").performClick()

            await("an empty grid") { rows() == 0 }
            await("the empty state") { showing("This vault is empty.") }
            assertFalse("the selection bar must be gone with the items", selecting())
            // P2-12: emptying the *grid* is not emptying the vault. Every item is
            // in the trash and every blob is still on disk until it is purged.
            assertEquals("every item must be in the trash", ITEMS, trashed())
            assertEquals(
                "a delete must unlink nothing; the blobs are what a restore restores",
                ITEMS,
                contentBlobsLeft(),
            )
        }
    }

    /**
     * Blobs that belonged to the fixture's items and are still on disk.
     *
     * Counted by identity rather than by total, because the pool also holds chaff,
     * the index and a thumbnail pack — deleting every item does not empty
     * `blobs/`, and asserting that it does would be asserting the opposite of
     * `FORMAT.md` §6.
     */
    private fun contentBlobsLeft(): Int =
        fixtureBlobIds.count { store.fileOf(it).exists() }

    // --- 3b. the trash, on the real screens (P2-12) ---------------------------

    /**
     * The whole trip: delete on the grid, find it in the trash, restore it, and
     * leave — with back acting on nothing at either level.
     */
    @Test
    fun theTrashHoldsWhatWasDeletedAndBackActsOnNothingThere() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            val rowsBefore = rows()
            val blobsBefore = blobs()

            longPress(FIRST)
            await("selection mode") { selecting() }
            compose.onNodeWithContentDescription("Delete selected").performClick()
            await("the confirmation") { showing("Delete 1 items?") || showing("Delete pick-6.png?") }
            // The reversible text must say where it went, not that it is gone.
            assertTrue(
                "a delete must not claim the copy cannot be recovered",
                showing("Moved to Trash. Deleted permanently after 30 days."),
            )
            compose.onNodeWithText("Delete").performClick()
            await("one fewer row on the grid") { rows() == rowsBefore - 1 }
            assertEquals("the delete unlinked a blob", blobsBefore, blobs())

            openTrash()
            await("the deleted item in the trash") {
                compose.onAllNodesWithContentDescription("pick-6.png").fetchSemanticsNodes()
                    .isNotEmpty()
            }

            // A selection in the trash, escaped by back: nothing restored, nothing
            // destroyed, and the trash still open.
            longPress("pick-6.png")
            await("selection mode in the trash") { selecting() }
            back(scenario)
            await("the trash without a selection") { showing("Trash") && !selecting() }
            assertEquals("back in the trash restored something", 1, trashed())
            assertEquals("back in the trash destroyed something", blobsBefore, blobs())

            // Restore it, and it is back on the grid.
            longPress("pick-6.png")
            await("selection mode in the trash") { selecting() }
            compose.onNodeWithContentDescription("Restore selected").performClick()
            await("an empty trash") { showing("Nothing in the trash.") }
            assertEquals("the item did not come back", rowsBefore, rows())
            assertEquals(0, trashed())

            back(scenario)
            await("the grid") { onGrid() }
        }
    }

    /** Delete forever, from the trash, with the words that go with it. */
    @Test
    fun deletingForeverFromTheTrashDestroysTheBlobs() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            val blobsBefore = blobs()

            longPress(FIRST)
            await("selection mode") { selecting() }
            compose.onNodeWithContentDescription("Delete selected").performClick()
            await("the confirmation") { showing("Moved to Trash. Deleted permanently after 30 days.") }
            compose.onNodeWithText("Delete").performClick()
            await("the trash to hold it") { trashed() == 1 }

            openTrash()
            longPress("pick-6.png")
            await("selection mode in the trash") { selecting() }
            compose.onNodeWithContentDescription("Delete selected forever").performClick()
            await("the irreversible confirmation") {
                showing("The copy in this vault is destroyed. It cannot be recovered.")
            }
            compose.onNodeWithText("Delete forever").performClick()

            await("an empty trash") { showing("Nothing in the trash.") }
            await("one fewer blob") { blobs() == blobsBefore - 1 }
            assertEquals(0, trashed())
        }
    }

    // --- 3c. folders, on the real screens (P2-13, `folder-system`) ------------

    /**
     * Make a folder from the button, move a selection into it, walk in, and leave —
     * with back climbing one level and moving nothing.
     *
     * ***The order is the reverse of P2-13's.*** An album was made by moving items
     * into a name; a folder is made first and filled afterwards, which is why the
     * naming step happens before anything is selected here.
     */
    @Test
    fun aFolderIsMadeFirstFilledAfterwardsAndLeftByBack() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            val rowsBefore = rows()

            // `new-folder-in-the-header`. One header tap; it left the floating set.
            compose.onNodeWithContentDescription("New folder").performClick()
            await("the naming screen") { showing("New folder") }
            // `folder-actions-sheet`. The field, not its label: the sheet renders in a
            // window of its own, and `hasText("Name")` can match the label node, which
            // carries no set-text action — the typing then lands nowhere, the confirm
            // stays disabled, and the failure looks like a folder that was not created.
            //
            // `search-at-the-bottom`. **And not the search field**, which is now always on
            // the grid behind this sheet, so `hasSetTextAction()` alone matches two nodes
            // and resolves to neither.
            compose.onNode(hasSetTextAction() and !hasText("Search"))
                .performTextInput("Trip")
            compose.onNodeWithText("Create").performClick()

            // It exists before anything is in it — the whole change.
            await("the empty folder on the home screen") { folderCount("Trip") == 0 }
            await("its tile") { showing("Trip") }

            longPress(FIRST)
            await("selection mode") { selecting() }
            tap(SECOND)
            await("two selected") { showing("2 selected") }

            compose.onNodeWithContentDescription("Move to folder").performClick()
            await("the move screen") { showing("Move 2 items") }
            compose.onNodeWithText("Move here").performClick()

            await("the grid again") { onGrid() && !selecting() }
            await("two items in the folder") { folderCount("Trip") == 2 }
            assertEquals("a move must not add or remove rows", rowsBefore, rows())

            // The tile walks into it; back climbs back out.
            compose.onNodeWithText("Trip").performClick()
            await("inside the folder") { showing("Trip") && !onGrid() }

            back(scenario)
            await("the home screen again") { onGrid() }
            assertEquals("leaving a folder must move nothing", rowsBefore, rows())
            assertEquals(2, folderCount("Trip"))
        }
    }

    /**
     * Removing the last item leaves the folder standing.
     *
     * ***This assertion is the inverse of the one it replaces.*** P2-13 asserted the
     * album ended when its last item left, because an album *was* its items. A folder
     * is a record, so it is still there, showing none.
     */
    @Test
    fun removingTheLastItemLeavesTheFolderStanding() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()

            // `new-folder-in-the-header`. One header tap; it left the floating set.
            compose.onNodeWithContentDescription("New folder").performClick()
            await("the naming screen") { showing("New folder") }
            // Not the search field behind the sheet — `search-at-the-bottom`.
            compose.onNode(hasSetTextAction() and !hasText("Search"))
                .performTextInput("Solo")
            compose.onNodeWithText("Create").performClick()
            await("the folder") { showing("Solo") }

            longPress(FIRST)
            await("selection mode") { selecting() }
            compose.onNodeWithContentDescription("Move to folder").performClick()
            await("the move screen") { showing("Move 1 item") }
            compose.onNodeWithText("Move here").performClick()
            await("one item in the folder") { folderCount("Solo") == 1 }

            // The item is *in* the folder now, so it is not on the home screen to be
            // picked up: walk in, take it out, and the folder is what is left.
            compose.onNodeWithText("Solo").performClick()
            longPress(FIRST)
            await("selection mode") { selecting() }
            compose.onNodeWithContentDescription("Move to folder").performClick()
            await("the move screen") { showing("Move 1 item") }
            compose.onNodeWithText("Move to the home screen").performClick()

            await("the item back at the top") { folderCount("Solo") == 0 }
            // Still standing *inside* the folder, which is now empty — and the folder
            // is still there to be standing in, which is the assertion.
            await("the empty folder") { showing("Nothing in this folder.") }

            back(scenario)
            await("the home screen") { onGrid() && !selecting() }
            assertTrue("the folder is still there, empty", showing("Solo"))
        }
    }

    /** Live items in the folder called [name], as the grid counts them. */
    private fun folderCount(name: String): Int {
        val db = MetadataDb.load(store, indexId, VaultSession.vmk()!!)
        return VaultFolders.children(db.folders, db.items, null)
            .firstOrNull { it.name == name }?.count ?: 0
    }

    /** Opens the drawer and navigates to the trash, in the open vault's group. */
    private fun openTrash() {
        compose.onNodeWithContentDescription("Trash and settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Trash").performClick()
        await("the trash screen") { showing("Trash") && !onGrid() }
        compose.waitForIdle()
    }

    // --- 4. the scoped export screen -----------------------------------------

    @Test
    fun theExportScreenNamesTheSelectionRatherThanEverything() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            longPress(FIRST)
            await("selection mode") { selecting() }
            tap(SECOND)
            await("two selected") { showing("2 selected") }

            compose.onNodeWithContentDescription("Unvault selected").performClick()

            await("the scoped export screen") { showing("Unvault 2 items") }
            assertFalse(
                "a scoped screen must not claim it is exporting everything",
                showing("Unvault Everything"),
            )
        }
    }
}
