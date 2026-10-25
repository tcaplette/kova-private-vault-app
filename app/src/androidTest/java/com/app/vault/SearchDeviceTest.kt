package com.app.vault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
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
 * P2-15 on the real screens.
 *
 * `SearchTest` asserts what a query does to rows. This one asserts the part that
 * only exists on screen: that the bar offers search, that typing narrows the grid,
 * that a selection can be made inside results and back ends it **before** it leaves
 * the search, that a search inside an album stays inside that album, and that the
 * trash offers no search at all.
 *
 * **This test creates a real vault in the app's own `filesDir`**, like
 * `MultiSelectDeviceTest`. It is removed in [tearDown] including on failure, and it
 * skips rather than touching a vault that is already there.
 */
@RunWith(AndroidJUnit4::class)
class SearchDeviceTest {

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

        /**
         * Three named items, imported in this order, so the grid's import-time
         * descending order puts `zebra` first and `bravo` second.
         *
         * **The assertions use `zebra` and `bravo` and never `alpha`.** A
         * `LazyColumn` composes only what is on screen, and at this density the
         * third tile is below the fold with no semantics node to find — which
         * reads as "the grid did not widen" and is really a scroll position. It is
         * the trap `MultiSelectDeviceTest` records; `alpha` exists to give the
         * unfiltered vault a third row and is not asserted on.
         */
        const val ALPHA = "alpha-photo.png"
        const val BRAVO = "bravo-photo.png"
        const val ZEBRA = "zebra-photo.png"

        /**
         * Enough further tiles that the list is **taller than the screen** —
         * `search-at-the-top`.
         *
         * ***Without these the yield cannot be tested at all, and the test that tries
         * reports the implementation broken.*** Three items and one folder do not fill a
         * viewport, so the `LazyColumn` has no scroll range, a swipe over it moves
         * nothing, and no scroll delta ever reaches the field — which reads exactly like
         * a field that refuses to move. The measurement that settled it:
         * `fieldHeight=168 listOffset=0.0 -> 0.0`, the field healthy and the list never
         * having scrolled.
         *
         * **They sort below [ALPHA] and match no query the suite types.** Their
         * `importedAt` is lower than the three named ones, so the grid's import-time
         * descending order leaves `zebra`, `bravo` and `alpha` exactly where every other
         * test expects them and puts these underneath. Their names carry none of
         * `zebra`, `bravo`, `alpha` or `nothingmatchesthis`, so no narrowing assertion
         * can see them — and they *do* carry `photo`, so the list is still long enough
         * to scroll under the one query that scrolls a filtered grid.
         */
        const val FILLER = 24
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
        // cannot be found or pressed by a test.
        MetadataDb.commit(store, indexId, vmk) { db ->
            // `folder-system`. Bravo is the only one in a folder, so a search inside
            // that folder has exactly one row to be right about.
            val trip = VaultFolder(MetadataDb.newFolderId(), null, "Trip")
            db.folders += trip
            // The filler first, so its `importedAt` stays below the three named ones and
            // the grid's descending order leaves them on top. See [FILLER].
            val names = List(FILLER) { n -> "filler-%02d-photo.png".format(n) } +
                listOf(ALPHA, BRAVO, ZEBRA)
            names.forEachIndexed { i, name ->
                val written = store.write(ByteArrayInputStream(image(600, 400, i + 1)), vmk)
                var item = VaultItem(
                    blobIds = listOf(written.blobId),
                    plaintextLength = written.plaintextLength,
                    thumb = null,
                    filename = name,
                    album = null,
                    folderId = if (name == BRAVO) trip.folderId else null,
                    createdAt = null,
                    importedAt = (i + 1).toLong(),
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
                    compose.onAllNodes(isRoot())[i].printToLog("SearchVerify")
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

    private fun searchOffered(): Boolean =
        compose.onAllNodes(hasText("Search")).fetchSemanticsNodes().isNotEmpty()

    private fun tileShown(filename: String): Boolean =
        compose.onAllNodesWithContentDescription(filename).fetchSemanticsNodes().isNotEmpty()

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
        await("the tile $filename") { tileShown(filename) }
    }

    /**
     * Waits for the search field — `search-at-the-bottom`, placed by `search-at-the-top`.
     *
     * **There is nothing to open any more.** This tapped a magnifier in the grid's
     * header and waited for a field to appear under the bar. The field is at the top of
     * the screen's content now, above the chips and the sort band, and is always there,
     * so the whole act is waiting for it — and the grid stays on screen, where the old
     * wait required it to be gone.
     */
    private fun openSearch() {
        await("the search field") { showing("Search") }
        compose.waitForIdle()
    }

    /**
     * The field, not the bar title — both say "Search", and only one takes text.
     */
    private fun type(text: String) {
        val field = compose.onNode(hasSetTextAction())
        // `performTextReplacement("")` does not clear a field — clearance is its
        // own action, and asking for a replacement with nothing leaves the text
        // that was there. Emptying the query is a case this screen has to handle,
        // so the test has to be able to produce it.
        if (text.isEmpty()) field.performTextClearance() else field.performTextReplacement(text)
        compose.waitForIdle()
    }

    // --- 1. the query narrows the grid ----------------------------------------

    @Test
    fun typingNarrowsTheGridAndClearingRestoresIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            awaitTile(ZEBRA)

            openSearch()

            // ***An empty field shows the level being viewed, not the whole vault.***
            // `search-at-the-bottom`. This asserted the opposite, and it was right to:
            // opening the search set `query = ""`, and an empty-but-non-null query meant
            // "searching", which reached into folders. [BRAVO] lives in one.
            //
            // With the field always on screen, empty is the ordinary state of the grid.
            // A field nobody has typed in must not be silently flattening every folder
            // in the vault into one list.
            await("the root's own tiles") { tileShown(ZEBRA) && tileShown(ALPHA) }
            assertFalse("an empty field is not a search across folders", tileShown(BRAVO))

            // Typing still reaches into them, which is what a search is for.
            type("bravo")
            await("the search to reach into the folder") { tileShown(BRAVO) }

            type("zebra")
            await("the grid to narrow") { !tileShown(ALPHA) }
            assertTrue("the matching tile stayed", tileShown(ZEBRA))

            type("")
            await("the field to empty") { !tileShown(BRAVO) && tileShown(ZEBRA) }

            // ***Scrolled to, not waited for.*** [ALPHA] is the mosaic's second
            // full-width row — a 600x400 source justified to 1080px is 719px tall — and
            // with the keyboard up the grid's viewport is 783px, so the row after the
            // first is below the fold. A `LazyVerticalGrid` does not compose what is not
            // near its viewport, and **"not composed" and "not in the list" are different
            // facts**; waiting on the first while meaning the second is what made this
            // assertion fail for a grid that had already widened correctly.
            //
            // This is the assertion the test always meant: clearing the query put the
            // item back in the list.
            compose.onNode(hasScrollToIndexAction())
                .performScrollToNode(hasContentDescription(ALPHA))
            compose.onNodeWithContentDescription(ALPHA).assertIsDisplayed()
        }
    }

    @Test
    fun aQueryThatMatchesNothingSaysSo() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openSearch()
            type("nothingmatchesthis")
            await("the empty state") { showing("Nothing matches.") }
            assertFalse("no tile may survive a query that matches nothing", tileShown(ZEBRA))
        }
    }

    /** The folder is the scope; the query narrows within it. */
    @Test
    fun aSearchInsideAFolderStaysInsideIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()

            // `folder-system`: the folder is a tile on the home screen, not a drawer
            // row, and walking into it is what sets the scope.
            await("the folder's tile") { showing("Trip") }
            compose.onNodeWithText("Trip").performClick()
            await("the folder's grid") { tileShown(BRAVO) && !tileShown(ZEBRA) }

            openSearch()
            // `zebra` is in the vault but not in this folder, so it must not appear.
            type("zebra")
            await("the empty state inside the folder") { showing("Nothing matches.") }
            assertFalse("a search inside a folder reached outside it", tileShown(ZEBRA))

            type("bravo")
            await("the folder's own row") { tileShown(BRAVO) }
        }
    }

    // --- 1b. the field yields to the scroll -----------------------------------
    // `search-at-the-top`. The field's *unclipped* top is what these read, not whether
    // it is displayed: the box that hides it clips it, and "clipped away" and "still
    // laid out where it was" are exactly the two things a displayed-check cannot tell
    // apart. The offset moves the field by its own height, so that is what is measured.

    private fun fieldNode() = compose.onNode(hasSetTextAction()).fetchSemanticsNode()

    private fun fieldTop(): Float = fieldNode().positionInRoot.y

    private fun listOffset(): Float =
        compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()

    /**
     * ***Started at the middle of the list, never at `swipeUp()`'s default.***
     *
     * That default starts the gesture at the node's **bottom edge**, and the floating
     * action button and the destination bar are drawn over exactly that strip — the list
     * runs underneath them, it only keeps its content clear of them. So the press landed
     * on the bar, the bar consumed it, and the list never saw a drag: `listOffset` read
     * `0.0 -> 0.0` through a swipe that looked right, which reads as a field refusing to
     * move and is really a gesture that never reached the tiles.
     *
     * The whole stroke stays inside the list's own visible middle, clear of the bottom
     * block and of the chrome above.
     */
    private fun scrollTilesDown() {
        compose.onNode(hasScrollToIndexAction()).performTouchInput {
            swipeUp(startY = centerY, endY = centerY - height * 0.4f, durationMillis = 300)
        }
        compose.waitForIdle()
    }

    /** With a blank query and no focus the field leaves on a downward scroll and returns on an upward one. */
    @Test
    fun theFieldYieldsToADownwardScrollAndReturnsOnAnUpwardOne() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            awaitTile(ZEBRA)
            openSearch()
            val shown = fieldTop()
            val fieldHeight = fieldNode().size.height
            val listBefore = listOffset()

            scrollTilesDown()
            val hidden = fieldTop()
            // The list's own offset is in the message on purpose: the two ways this
            // assertion fails — the field not yielding, and the tiles never having
            // scrolled at all — look identical without it, and the second one cost a
            // full debugging round.
            assertTrue(
                "the field did not leave on a downward scroll: $shown -> $hidden " +
                    "(listOffset $listBefore -> ${listOffset()})",
                hidden <= shown - fieldHeight,
            )

            // Any upward scroll, however short, starts it back — it does not wait for the
            // top of the list. Slow, so the drag is not a fling.
            compose.onNode(hasScrollToIndexAction()).performTouchInput {
                swipeDown(
                    startY = fieldHeight / 3f,
                    endY = fieldHeight / 3f + fieldHeight,
                    durationMillis = 600,
                )
            }
            compose.waitForIdle()
            assertTrue(
                "the field did not begin to return on an upward scroll: $hidden -> ${fieldTop()}",
                fieldTop() > hidden,
            )
        }
    }

    /** A non-blank query holds the field where it is. */
    @Test
    fun aQueryHoldsTheFieldWhileTheTilesScroll() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            awaitTile(ZEBRA)
            openSearch()
            type("photo")
            await("the results") { tileShown(ZEBRA) }
            val shown = fieldTop()

            scrollTilesDown()

            assertEquals("the field moved while a query was active", shown, fieldTop(), 0.5f)
            assertTrue(
                "the query is not visible in the field",
                compose.onAllNodes(hasSetTextAction() and hasText("photo"))
                    .fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    /**
     * The keyboard raised by the field: the field stays put and the bar stays drawn and tappable.
     *
     * Skips where the emulator shows no soft keyboard — there is nothing to be raised, and
     * a pass would be a pass about nothing.
     */
    @Test
    fun theKeyboardDoesNotMoveTheFieldOrSqueezeTheBar() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            awaitTile(ZEBRA)
            openSearch()
            val shown = fieldTop()
            val barBefore = compose.onNodeWithText(ctx.getString(VaultDestination.Files.label))
                .fetchSemanticsNode().size.height

            compose.onNode(hasSetTextAction()).performClick()
            compose.onNode(hasSetTextAction()).performTextInput("z")
            compose.waitForIdle()

            var imeShown = false
            scenario.onActivity { activity ->
                imeShown = androidx.core.view.ViewCompat
                    .getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            assumeTrue("this emulator shows no soft keyboard", imeShown)

            compose.onNode(hasSetTextAction()).assertIsDisplayed()
            assertEquals("the keyboard moved the field", shown, fieldTop(), 0.5f)
            compose.onNodeWithText(ctx.getString(VaultDestination.Files.label)).assertIsDisplayed()
            assertEquals(
                "the bar was squeezed with the keyboard up",
                barBefore,
                compose.onNodeWithText(ctx.getString(VaultDestination.Files.label)).fetchSemanticsNode().size.height,
            )

            compose.onNodeWithText(ctx.getString(VaultDestination.Files.label)).performClick()
            // ***Not "No files yet."*** — that line cannot appear here, and waiting for it
            // waits the full timeout on a screen that is behaving. `VaultFolders.children`
            // returns every root folder whatever the destination's kind, so Files at the
            // root holds the `Trip` folder with a count of zero; `folders` is therefore not
            // empty and the empty-destination copy is never drawn. The destination did
            // change — the observable was wrong.
            //
            // Two signals, both of which only the destination change produces: the media
            // tile that matched `z` is gone, and the folder that a non-blank query had
            // been hiding is back, because Files carries its own empty query.
            await("Files to be chosen with the keyboard up") {
                !tileShown(ZEBRA) && showing("Trip")
            }
        }
    }

    // --- 2. back, and what it acts on -----------------------------------------

    @Test
    fun backEndsASelectionBeforeItLeavesTheSearch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            openSearch()
            type("zebra")
            await("the narrowed grid") { tileShown(ZEBRA) && !tileShown(ALPHA) }

            compose.onNodeWithContentDescription(ZEBRA).performTouchInput { longClick() }
            await("selection mode inside the results") { selecting() }

            back(scenario)
            await("the selection to end") { !selecting() }
            assertTrue("back left the search as well as the selection", showing("Nothing matches.") || tileShown(ZEBRA))
            assertFalse("back returned to the unsearched grid too early", tileShown(ALPHA))

            back(scenario)
            await("the grid again") { onGrid() && tileShown(ALPHA) }
            assertEquals(
                "back through a search changed the vault",
                FILLER + 3,
                MetadataDb.load(store, indexId, VaultSession.vmk()!!).items.size,
            )
        }
    }

    // --- 3. not over the trash ------------------------------------------------

    @Test
    fun theTrashOffersNoSearch() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()

            // The trash is in the drawer, in the open vault's group.
            compose.onNodeWithContentDescription("Trash and settings").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Trash").performClick()
            await("the trash screen") { showing("Trash") && !onGrid() }

            assertFalse("the trash must not offer a search", searchOffered())
        }
    }




    // --- 1c. the hold can be escaped ------------------------------------------
    // `search-at-the-top`. The field is held while it has focus, and until these two
    // existed nothing gave focus back: the field stayed selected for the life of the
    // destination, pinned, with the keyboard covering the tiles that were left.

    private fun fieldIsFocused(): Boolean =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Focused) == true

    private fun focusTheField() {
        compose.onNode(hasSetTextAction()).performClick()
        await("the field to take focus") { fieldIsFocused() }
    }

    /** Dragging the tiles puts the field down. */
    @Test
    fun aScrollOfTheTilesGivesTheFieldUp() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            awaitTile(ZEBRA)
            openSearch()
            focusTheField()

            scrollTilesDown()
            await("the field to lose focus on a scroll") { !fieldIsFocused() }
        }
    }

    /** A tap on ground nothing else claims puts the field down. */
    @Test
    fun aTapOnTheEmptyGroundGivesTheFieldUp() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            awaitTile(ZEBRA)
            openSearch()
            focusTheField()

            // The date heading: real, always drawn over the tiles, and claims no tap of
            // its own — so this lands on the ground behind it, which is the gesture.
            compose.onAllNodes(hasText("Added", substring = true))[0].performClick()
            await("the field to lose focus on a tap elsewhere") { !fieldIsFocused() }
        }
    }

    /** A tile keeps its own tap: putting the field down must not cost the viewer. */
    @Test
    fun aTapOnATileStillOpensIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            awaitTile(ZEBRA)
            openSearch()
            focusTheField()

            compose.onNodeWithContentDescription(ZEBRA).performClick()
            await("the viewer") { !onGrid() }
        }
    }


    /**
     * Back puts the field down, and the grid stays where it is.
     *
     * ***The back press here never reaches the app.*** While the IME is showing Android
     * hides the keyboard on the first back and no `BackHandler` runs, which is exactly why
     * the release watches the keyboard rather than the gesture. What this asserts is the
     * user-visible contract: press back with the field selected, and the field is no longer
     * selected — while the grid is still the grid, because that back was spent on the
     * keyboard and not on navigation.
     */
    @Test
    fun backGivesTheFieldUpAndDoesNotLeaveTheGrid() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            awaitTile(ZEBRA)
            openSearch()
            focusTheField()

            var imeShown = false
            scenario.onActivity { activity ->
                imeShown = androidx.core.view.ViewCompat
                    .getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            assumeTrue("this emulator shows no soft keyboard", imeShown)

            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                )
            await("the field to lose focus when the keyboard goes") { !fieldIsFocused() }
            assertTrue("back left the grid instead of only the keyboard", onGrid())
        }
    }

    /** The keyboard's own dismiss puts the field down too — the same watcher. */
    @Test
    fun dismissingTheKeyboardGivesTheFieldUp() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            awaitTile(ZEBRA)
            openSearch()
            focusTheField()

            var imeShown = false
            scenario.onActivity { activity ->
                imeShown = androidx.core.view.ViewCompat
                    .getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            assumeTrue("this emulator shows no soft keyboard", imeShown)

            scenario.onActivity { activity ->
                androidx.core.view.WindowCompat
                    .getInsetsController(activity.window, activity.window.decorView)
                    .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
            await("the field to lose focus with the keyboard") { !fieldIsFocused() }
        }
    }

}
