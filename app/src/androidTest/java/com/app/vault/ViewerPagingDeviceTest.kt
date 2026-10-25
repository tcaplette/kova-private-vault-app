package com.app.vault

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.PlayerCensus
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Paging the viewer, on a device — P2-8's two load-bearing checks.
 *
 * **The one-player rule.** `HorizontalPager` keeps neighbouring pages composed, so
 * a player owned per page leaves up to three alive; hardware video decoders are a
 * small fixed pool, so that fails on exactly the vaults holding the most video and
 * fails as a decode error nobody can interpret. Asserted by counting live players
 * through [PlayerCensus] at every step, because the structural argument is what
 * rots when the player is later hoisted for a good-looking reason.
 *
 * **I3 under paging.** No plaintext media on disk was already required during
 * playback; paging prepares and releases players repeatedly, which is more chances
 * to spill than playback alone ever had. Private storage is swept while paging, not
 * only afterwards.
 *
 * This populates the app's own `filesDir` with a real vault and removes it in
 * [tearDown]. A vault that is already there is left alone and the test skips.
 */
@RunWith(AndroidJUnit4::class)
class ViewerPagingDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false

    private companion object {
        val PASS = "correct horse battery staple".toByteArray(Charsets.UTF_8)
        const val PASS_TEXT = "correct horse battery staple"
    }

    /** A tiny valid PNG, so the decode path is real rather than mocked. */
    private fun png(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF3366AA.toInt())
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
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

        lifecycle.create(PASS.copyOf())
        created = true

        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!
        val db = MetadataDb.load(store, indexId, vmk)

        // Three items so the pager has somewhere to go in both directions. Images
        // only: a generated video would make this test about VideoCorpus, and the
        // census assertions below hold for the image pages too — the rule being
        // checked is "no player is alive that should not be".
        repeat(3) { i ->
            val written = store.write(ByteArrayInputStream(png()), vmk)
            db.items += VaultItem(
                blobIds = listOf(written.blobId),
                plaintextLength = written.plaintextLength,
                thumb = null,
                filename = "shot$i.png",
                album = null,
                createdAt = 1_700_000_000_000L - i * 86_400_000L,
                importedAt = 1_700_000_000_000L,
                tags = emptyList(),
            )
        }
        // Generate the thumbnails here rather than letting the app's lazy
        // backfill do it. A tile only carries its filename as a content
        // description once it has a thumbnail to draw, so without this the test
        // is waiting on a background pass whose timing depends on how loaded the
        // device is — which is why it passed alone and timed out in the full
        // suite. A fixture that is only sometimes complete is not a fixture.
        val withThumbs = Thumbnails.generateAll(
            store,
            ThumbnailStore(store),
            db,
            db.items.toList(),
            vmk,
        )
        db.items.clear()
        db.items.addAll(withThumbs)

        db.save(store, indexId, vmk)
        lockKeyringAndVault()
        PlayerCensus.live.set(0)
    }

    @After
    fun tearDown() {
        lockKeyringAndVault()
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

    private fun showing(text: String) =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun onGrid() =
        compose.onAllNodesWithContentDescription("All vaults").fetchSemanticsNodes().isNotEmpty()

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
                    compose.onAllNodes(isRoot())[i].printToLog("PagingVerify")
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

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

    /** Opens the first tile, whatever the mosaic laid out. */
    private fun openFirstItem() {
        await("a tile") {
            compose.onAllNodesWithContentDescription("shot0.png").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("shot0.png").performClick()
        compose.waitForIdle()
    }

    private fun plaintextInPrivateStorage(): List<String> =
        listOfNotNull(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir)
            .flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }
            .filter { FileScan.containsWebp(it) }
            .map { it.name }

    @Test
    fun pagingHoldsTheOnePlayerRuleAndLeavesNoPlaintext() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            unlock()
            openFirstItem()

            // Page forward and back across the whole set, checking both invariants
            // at every step rather than only at the end — a spill that is cleaned
            // up before the test finishes is still a spill.
            repeat(2) {
                compose.onRoot().performTouchInput { swipeLeft() }
                compose.waitForIdle()
                assertTrue(
                    "more than one player alive after paging forward: ${PlayerCensus.live.get()}",
                    PlayerCensus.live.get() <= 1,
                )
                assertTrue(
                    "plaintext appeared while paging: ${plaintextInPrivateStorage()}",
                    plaintextInPrivateStorage().isEmpty(),
                )
            }
            repeat(2) {
                compose.onRoot().performTouchInput { swipeRight() }
                compose.waitForIdle()
                assertTrue(
                    "more than one player alive after paging back: ${PlayerCensus.live.get()}",
                    PlayerCensus.live.get() <= 1,
                )
                assertTrue(
                    "plaintext appeared while paging back: ${plaintextInPrivateStorage()}",
                    plaintextInPrivateStorage().isEmpty(),
                )
            }
        }
    }

    /** Back from the viewer returns to the grid, and releases whatever it held. */
    @Test
    fun leavingTheViewerReleasesEverything() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            unlock()
            openFirstItem()

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("the grid") { onGrid() }

            assertTrue(
                "a player outlived the viewer: ${PlayerCensus.live.get()}",
                PlayerCensus.live.get() == 0,
            )
        }
    }

    /** The mosaic and its headings render against real items. */
    @Test
    fun theGridShowsSectionsAndNonSquareTiles() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForIdle()
            unlock()

            // Three items on three different days, so three headings.
            await("a tile") {
                compose.onAllNodesWithContentDescription("shot0.png").fetchSemanticsNodes().isNotEmpty()
            }
            val tile = compose.onNodeWithContentDescription("shot0.png")
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInWindow

            // The fixture images are 64x48 — 4:3. A square tile would mean the
            // mosaic is still cropping, which is what P2-8 removed.
            val ratio = tile.width / tile.height
            assertTrue(
                "tile is square (${tile.width}x${tile.height}); the mosaic is not preserving shape",
                kotlin.math.abs(ratio - 4f / 3f) < 0.25f,
            )
        }
    }
}
