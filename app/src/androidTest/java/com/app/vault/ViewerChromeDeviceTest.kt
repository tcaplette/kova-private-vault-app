package com.app.vault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
 * The viewer's two rows, on the real screen — `viewer-chrome-and-editor`.
 *
 * `EditorTest` answers what the editor does to pixels and `RotationDeviceTest` what a
 * turn does to the index. This answers the part that exists only on screen: that the
 * controls are all there and named, that they hide with the layer rather than
 * outliving it, and that each is withheld where its act does not apply.
 *
 * **This creates a real vault in the app's own `filesDir`**, like `SortDeviceTest`,
 * and skips rather than overwriting one that is already there.
 */
@RunWith(AndroidJUnit4::class)
class ViewerChromeDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false

    private companion object {
        const val PASS_TEXT = "correct horse battery staple"
        const val PHOTO = "photo.png"

        /**
         * `player-controls-and-menu`. A playable item, by the only thing the layout
         * asks about: its kind, which comes from the filename. The bytes never decode
         * and do not need to — what is under test is which controls the viewer draws
         * for a kind that plays, and that decision is taken before any player exists.
         */
        const val CLIP = "clip.mp4"

        /** Every control on the two rows, by the description a screen reader reads. */
        val ROWS = listOf(
            "Back", "Rotate", "More", "Rename", "Edit image", "Share securely", "Delete",
        )

        /** The controls that leave the rows on an item that plays. */
        val MOVED = listOf("Rename", "Share securely", "Delete")
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
        val store = BlobStore(blobsDir, incoming)
        val lifecycle = VaultLifecycle(slots, store)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true

        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        val thumbs = ThumbnailStore(store)

        MetadataDb.commit(store, indexId, vmk) { db ->
            val written = store.write(ByteArrayInputStream(image()), vmk)
            var item = VaultItem(
                blobIds = listOf(written.blobId),
                plaintextLength = written.plaintextLength,
                thumb = null,
                filename = PHOTO,
                album = null,
                createdAt = 1_700_000_000_000L,
                importedAt = 1L,
                tags = emptyList(),
            )
            var clipThumb: ThumbRef? = null
            Thumbnails.generate(store, item, vmk).webp?.let { webp ->
                item = item.withThumb(thumbs.stage(db, webp))
                // The same pixels for the clip below. A tile is named by its filename
                // only when it has a thumbnail to draw, and what is under test is the
                // item's *kind*, not what its tile looks like.
                clipThumb = thumbs.stage(db, webp)
            }
            db.items += item

            // `player-controls-and-menu`. A second item, of a kind that plays. The
            // bytes never decode and do not need to: the viewer decides which controls
            // to draw from the kind, which comes from the filename, before any player
            // exists.
            val clipBytes = ByteArray(4096) { (it % 251).toByte() }
            val clipWritten = store.write(ByteArrayInputStream(clipBytes), vmk)
            db.items += VaultItem(
                blobIds = listOf(clipWritten.blobId),
                plaintextLength = clipWritten.plaintextLength,
                thumb = clipThumb,
                filename = CLIP,
                album = null,
                createdAt = 1_600_000_000_000L,
                importedAt = 2L,
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

    private fun image(): ByteArray {
        val bmp = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(20, 90, 140))
        canvas.drawRect(0f, 0f, 300f, 400f, Paint().apply { color = Color.rgb(240, 220, 30) })
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
                    compose.onAllNodes(isRoot())[i].printToLog("ViewerChrome")
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun showing(description: String): Boolean =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun onGrid(): Boolean = showing("All vaults")

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
        // `vault-keyring`. The passphrase opens a keyring and the app lands on
        // the list; the grid is behind the vault this taps.
        compose.openFirstVaultFromTheList()
        await("the grid") { onGrid() }
        compose.waitForIdle()
    }

    /** Opens the one item and taps it, which is what raises the revealed layer. */
    private fun openAndReveal() {
        await("the tile") { showing(PHOTO) }
        compose.onNodeWithContentDescription(PHOTO).performClick()
        await("the viewer") { !onGrid() }
        compose.waitForIdle()
        compose.onRoot().performClick()
    }

    // --- 1. every control is there, named, and hides with the layer ------------

    @Test
    fun bothRowsAppearOnATapAndHideThemselvesAgain() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openAndReveal()

            ROWS.forEach { named ->
                await("the $named control") { showing(named) }
            }

            // The layer's own timer, which the rows live and die by. Longer than the
            // linger so a slow device is not the reason this fails.
            await("the rows to hide themselves") { ROWS.none { showing(it) } }
            assertFalse("the item must be left alone on black", showing("More"))
        }
    }

    /** And a second tap brings them back — the layer, not a one-shot. */
    @Test
    fun theRowsComeBackOnAnotherTap() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openAndReveal()
            await("the rows") { showing("Back") }
            await("the rows to hide") { !showing("Back") }

            compose.onRoot().performClick()

            await("the rows again") { showing("Back") && showing("Delete") }
        }
    }

    // --- 2. back leaves the viewer --------------------------------------------

    @Test
    fun theBackControlReturnsToTheGrid() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openAndReveal()
            await("the back control") { showing("Back") }

            compose.onNodeWithContentDescription("Back").performClick()

            await("the grid") { onGrid() }
        }
    }

    // --- 3. the menu holds the two occasional acts ----------------------------

    @Test
    fun theMenuOffersMovingAndWithholdsACoverAtTheRoot() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openAndReveal()
            await("the menu control") { showing("More") }

            compose.onNodeWithContentDescription("More").performClick()

            await("the menu") {
                compose.onAllNodes(hasText("Move to folder")).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(
                "an item in no folder has no cover to set, so the row is absent",
                compose.onAllNodes(hasText("Set as folder cover")).fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    // --- 4. rotate turns the item and keeps it there --------------------------

    @Test
    fun theRotateControlTurnsTheItemAndTheTurnSurvivesLeavingTheViewer() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openAndReveal()
            await("the rotate control") { showing("Rotate") }

            compose.onNodeWithContentDescription("Rotate").performClick()
            compose.waitForIdle()

            // The turn is a row in the index, which is where it has to be readable
            // from: the pixels on screen are the same pixels either way.
            val store = BlobStore(blobsDir, incoming)
            val db = MetadataDb.load(store, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!)
            assertTrue(
                "the rotate control must write the turn, not just redraw it",
                db.items.single { it.filename == PHOTO }.rotation == 90,
            )
        }
    }

    // --- 4a. an item that plays: the transport owns the bottom edge -----------
    //
    // `player-controls-and-menu`. The bottom action row and the player's controls were
    // both aligned to the bottom edge, and the action row was composed after the
    // pager, so it sat on top: play/pause took no touches because rename was over it.
    // These three hold the arrangement that fixed it.

    /** Opens the clip and raises the layer, whichever order the grid sorts in. */
    private fun openClipAndReveal() {
        await("the clip tile") { showing(CLIP) }
        compose.onNodeWithContentDescription(CLIP).performClick()
        await("the viewer") { !onGrid() }
        compose.waitForIdle()
        compose.onRoot().performClick()
    }

    @Test
    fun aPlayableItemDrawsNoActionRowOverItsTransport() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openClipAndReveal()

            await("the menu control") { showing("More") }
            MOVED.forEach { named ->
                assertFalse(
                    "\"$named\" must not be drawn on the bottom edge of a player — that " +
                        "edge belongs to the transport, and this row sitting over it is " +
                        "why play/pause took no touches",
                    showing(named),
                )
            }
            assertFalse("the editor is not offered on a video either", showing("Edit image"))
        }
    }

    @Test
    fun rotateIsWithheldOnAnItemThatPlays() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openClipAndReveal()

            await("the menu control") { showing("More") }
            assertFalse(
                "rotating a video records a turn the player cannot apply, so the " +
                    "control must be absent rather than present and doing nothing",
                showing("Rotate"),
            )
        }
    }

    @Test
    fun theMovedActsAreInTheMenuOnAVideoAndNotOnAnImage() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openClipAndReveal()
            await("the menu control") { showing("More") }
            compose.onNodeWithContentDescription("More").performClick()

            await("the moved acts") {
                compose.onAllNodes(hasText("Rename")).fetchSemanticsNodes().isNotEmpty()
            }
            listOf("Rename", "Share securely", "Delete").forEach { label ->
                assertTrue(
                    "\"$label\" must be reachable from the menu on a player",
                    compose.onAllNodes(hasText(label)).fetchSemanticsNodes().isNotEmpty(),
                )
            }
        }
    }

    @Test
    fun aStillImageKeepsItsRowAndItsMenuIsUnchanged() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openAndReveal()

            // The row it always had. A still has no transport, so the edge is free and
            // burying its reached acts behind a glyph is the mistake P2-7 undid.
            MOVED.forEach { named -> await("the $named control") { showing(named) } }
            await("the rotate control") { showing("Rotate") }

            compose.onNodeWithContentDescription("More").performClick()
            await("the menu") {
                compose.onAllNodes(hasText("Move to folder")).fetchSemanticsNodes().isNotEmpty()
            }
            listOf("Rename", "Share securely", "Delete").forEach { label ->
                assertTrue(
                    "\"$label\" is on the row here; a second copy in the menu is two " +
                        "places for one act",
                    compose.onAllNodes(hasText(label)).fetchSemanticsNodes().isEmpty(),
                )
            }
        }
    }

    // --- 5. the editor opens on an image --------------------------------------

    @Test
    fun theEditorOpensFromTheBottomRowAndBackLeavesIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            openAndReveal()
            await("the editor control") { showing("Edit image") }

            compose.onNodeWithContentDescription("Edit image").performClick()

            await("the editor") {
                // Substring: the selected tool draws its label with a trailing marker
                // ("Crop ·"), which is how the editor names the active tool in words
                // rather than by tint alone.
                compose.onAllNodes(hasText("Crop", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(
                "the editor states what a save does, where the user meets it",
                compose.onAllNodes(
                    hasText("Saving adds a new item and keeps the original.")
                ).fetchSemanticsNodes().isNotEmpty(),
            )

            compose.onNodeWithContentDescription("Discard edits").performClick()

            await("the item again") {
                compose.onAllNodes(hasText("Crop", substring = true)).fetchSemanticsNodes().isEmpty()
            }
        }
    }
}
