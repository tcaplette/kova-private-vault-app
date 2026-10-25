package com.app.vault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
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
 * P2-16 on the real screens.
 *
 * `SortOrderTest` answers what each ordering produces and `SortPreferenceTest`
 * answers that the choice survives a lock. This answers the part that only exists
 * on screen: that the grid's control names the ordering in effect, that its menu
 * opens over the grid and choosing from it reorders the tiles behind it without
 * leaving the surface, that the headings change or vanish with it, that the trash is
 * untouched by it, and that an album and a search inherit it.
 *
 * **This test creates a real vault in the app's own `filesDir`**, like
 * `MultiSelectDeviceTest`, and restores the stored ordering in [tearDown] so
 * nothing it chooses outlives it.
 */
@RunWith(AndroidJUnit4::class)
class SortDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false
    private var restoreSort: VaultSort = VaultSort.Default
    private lateinit var store: BlobStore
    private lateinit var indexId: ByteArray

    private companion object {
        const val PASS_TEXT = "correct horse battery staple"

        /**
         * Two items chosen so that **every** ordering distinguishes them, and so
         * that capture order and name order disagree: `zulu` was captured later
         * but is added first and sorts last by name.
         */
        const val ZULU = "zulu.png"
        const val ALPHA = "alpha.png"

        const val DAY_EARLY = 1_600_000_000_000L
        const val DAY_LATE = 1_700_000_000_000L
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
        restoreSort = GridPreference.sort(ctx)
        GridPreference.setSort(ctx, VaultSort.Default)
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true

        val vmk = VaultSession.vmk()!!
        indexId = VaultSession.metadataBlobId()!!.copyOf()
        val thumbs = ThumbnailStore(store)

        MetadataDb.commit(store, indexId, vmk) { db ->
            listOf(
                Triple(ZULU, DAY_LATE, 1L),
                Triple(ALPHA, DAY_EARLY, 2L),
            ).forEachIndexed { i, (name, createdAt, importedAt) ->
                val written = store.write(ByteArrayInputStream(image(600, 400, i + 1)), vmk)
                var item = VaultItem(
                    blobIds = listOf(written.blobId),
                    plaintextLength = written.plaintextLength,
                    thumb = null,
                    filename = name,
                    album = if (name == ALPHA) "Trip" else null,
                    createdAt = createdAt,
                    importedAt = importedAt,
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
        GridPreference.setSort(ctx, restoreSort)
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

    // --- harness --------------------------------------------------------------

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
                    compose.onAllNodes(isRoot())[i].printToLog("SortVerify")
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun showing(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun containing(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun onGrid(): Boolean =
        compose.onAllNodesWithContentDescription("All vaults").fetchSemanticsNodes().isNotEmpty()

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

    /**
     * Chooses an ordering from the grid's sort menu.
     *
     * **The control is on the grid and its menu opens in place.** Sort orders the
     * tiles it sits above and its whole result is on screen the moment it changes, so
     * the grid never leaves: [onGrid] holds throughout, which is what separates this
     * from the screen it replaces.
     */
    private fun choose(sort: VaultSort) {
        await("the sort control") { labelsShowing() == 1 }
        compose.onNode(hasText(currentSortLabel(), substring = true)).performClick()
        await("the sort menu") { labelsShowing() == VaultSort.entries.size }
        compose.onNodeWithText(ctx.getString(sort.label)).performClick()
        await("the menu to close over the grid") { labelsShowing() == 1 && onGrid() }
        compose.waitForIdle()
    }

    /**
     * How many of the six labels are on screen: one when the menu is shut — the
     * control names the ordering in effect — and six while it is open.
     */
    private fun labelsShowing(): Int = VaultSort.entries.count { containing(ctx.getString(it.label)) }

    /** The label the grid's sort control is currently showing. */
    private fun currentSortLabel(): String =
        ctx.getString(VaultSort.entries.first { containing(ctx.getString(it.label)) }.label)

    /** The order the tiles are actually in on screen, by their content descriptions. */

    private fun tilesOnScreen(): List<String> =
        compose.onAllNodesWithContentDescription(ZULU).fetchSemanticsNodes()
            .map { ZULU to it.positionInRoot.y }
            .plus(
                compose.onAllNodesWithContentDescription(ALPHA).fetchSemanticsNodes()
                    .map { ALPHA to it.positionInRoot.y }
            )
            .sortedBy { it.second }
            .map { it.first }

    // --- 1. the grid names the ordering, and choosing one reorders ------------

    @Test
    fun theGridNamesTheOrderingAndChoosingOneReordersIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            await("both tiles") { tilesOnScreen().size == 2 }

            // Default: capture date descending, so the later capture is first.
            assertTrue(
                "the default ordering must put the later capture first",
                tilesOnScreen() == listOf(ZULU, ALPHA),
            )

            assertTrue(
                "the grid's control must name the ordering in effect",
                containing(ctx.getString(VaultSort.Newest.label)),
            )
            compose.onNode(hasText(ctx.getString(VaultSort.Newest.label), substring = true)).performClick()
            await("the sort menu") { labelsShowing() == VaultSort.entries.size }
            assertTrue(
                "the menu must open over the grid, not navigate away from it",
                onGrid(),
            )
            assertTrue(
                "the active ordering must be marked in words, not by colour alone",
                showing("Currently used"),
            )
            assertTrue(
                "a name ordering must say what alphabetical costs, where it is chosen",
                showing("Alphabetical, not numeric"),
            )
            compose.onNodeWithText(ctx.getString(VaultSort.NameAZ.label)).performClick()

            await("the grid reordered by name") { tilesOnScreen() == listOf(ALPHA, ZULU) }
            assertTrue("the menu must close behind the choice", labelsShowing() == 1)
        }
    }

    @Test
    fun everyOrderingIsReachableAndTakesEffect() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            await("both tiles") { tilesOnScreen().size == 2 }

            mapOf(
                VaultSort.Oldest to listOf(ALPHA, ZULU),
                VaultSort.RecentlyAdded to listOf(ALPHA, ZULU),
                VaultSort.FirstAdded to listOf(ZULU, ALPHA),
                VaultSort.NameZA to listOf(ZULU, ALPHA),
                VaultSort.NameAZ to listOf(ALPHA, ZULU),
                VaultSort.Newest to listOf(ZULU, ALPHA),
            ).forEach { (sort, expected) ->
                choose(sort)
                await("the grid in ${sort.label}") { tilesOnScreen() == expected }
            }
        }
    }

    // --- 2. the headings follow the ordering ----------------------------------

    @Test
    fun theHeadingsFollowTheOrderingAndVanishUnderAName() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            await("both tiles") { tilesOnScreen().size == 2 }

            // Capture-date ordering: real capture dates, no "Added".
            await("a capture-date heading") { containing("2023") || containing("2020") }
            assertFalse(
                "a dated item's heading must not claim it was added then",
                containing("Added"),
            )

            // Import-date ordering: every heading is an import date and says so.
            choose(VaultSort.RecentlyAdded)
            await("an import-date heading") { containing("Added") }

            // Name ordering: no headings at all.
            choose(VaultSort.NameAZ)
            await("the grid in name order") { tilesOnScreen() == listOf(ALPHA, ZULU) }
            assertFalse("a name ordering must draw no date headings", containing("Added"))
            assertFalse("and no capture-date heading either", containing("2023"))
            assertFalse(containing("2020"))
        }
    }

    // --- 3. what inherits it, and what does not -------------------------------

    @Test
    fun anAlbumAndASearchInheritTheOrderingAndTheTrashDoesNot() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            await("both tiles") { tilesOnScreen().size == 2 }

            choose(VaultSort.NameAZ)
            await("name order") { tilesOnScreen() == listOf(ALPHA, ZULU) }

            // A search over both still comes back in name order.
            // `search-at-the-bottom`. The field is at the bottom and always there, so
            // there is no glyph to tap and the grid does not go away to make room.
            await("the search field") { showing("Search") }
            compose.onNode(hasSetTextAction()).performTextReplacement(".png")
            await("both tiles in the results") { tilesOnScreen().size == 2 }
            assertTrue(
                "a search must inherit the grid's ordering",
                tilesOnScreen() == listOf(ALPHA, ZULU),
            )
            assertFalse("results under a name ordering carry no headings", containing("Added"))
        }
    }

    @Test
    fun theTrashKeepsItsOwnOrderAndOffersNoHeadings() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            choose(VaultSort.NameAZ)
            await("name order") { tilesOnScreen() == listOf(ALPHA, ZULU) }

            // The trash is in the drawer, in the open vault's group.
            compose.onNodeWithContentDescription("Trash and settings").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("Trash").performClick()
            await("the trash screen") { showing("Trash") && !onGrid() }

            // Empty, but the point is that it draws and offers no sort of its own.
            assertFalse("the trash must not offer a search",
                compose.onAllNodes(hasText("Search")).fetchSemanticsNodes().isNotEmpty())
            assertTrue("the trash's own empty state", showing("Nothing in the trash."))
        }
    }
}
