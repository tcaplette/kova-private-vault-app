package com.app.vault

import android.util.Log
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
import com.app.vault.ui.PlayerCensus
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P2-18 on the real screens.
 *
 * `NonMediaDeviceTest` answers what happens to the bytes — playback through the
 * decrypting source, byte-identical export, no per-unlock decrypt. This answers what
 * the user is shown, which is the half the outline calls "a dead cell": that a
 * document is presented **as a document** and never as a failure, that a song says
 * what it is while it plays, and that the one-player rule survives a vault holding
 * both.
 *
 * **This test creates a real vault in the app's own `filesDir`**, as
 * `SortDeviceTest` and `CameraDeviceTest` do, and removes it in [tearDown].
 */
@RunWith(AndroidJUnit4::class)
class NonMediaScreenTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false
    private lateinit var store: BlobStore

    private companion object {
        const val TAG = "VaultNonMediaScreen"
        const val PASS_TEXT = "correct horse battery staple"
        const val MP3 = "song.mp3"
        const val PDF = "statement.pdf"
        const val ZIP = "archive.zip"

        /** The words the viewer must **not** say about an intact document. */
        const val FAILURE_TEXT = "This item could not be opened."
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
        clearViewModes()

        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        MetadataDb.commit(store, indexId, vmk) { db ->
            listOf(MP3 to silentMp3(), PDF to pdfBytes(), ZIP to zipBytes())
                .forEach { (name, content) ->
                    val written = store.writeItem(ByteArrayInputStream(content), vmk)
                    val item = VaultItem(
                        blobIds = written.blobIds,
                        plaintextLength = written.plaintextLength,
                        thumb = null,
                        filename = name,
                        album = null,
                        createdAt = 1_700_000_000_000L,
                        importedAt = 1_700_000_000_000L,
                        tags = emptyList(),
                    )
                    db.items += item
                }
            db
        }
        lockKeyringAndVault()
    }

    /**
     * ***Clears the stored view modes — `destination-view-modes`.***
     *
     * A view mode is an app-wide `SharedPreferences` entry and it deliberately outlives the
     * vault, which is the whole point of it. That makes it the one piece of state in this
     * file that survives [tearDown] deleting the vault, so a test that switches a mode
     * silently changes what every test after it sees. Two tests failed exactly that way
     * before this existed. Reset at both ends, so the order tests run in cannot matter.
     */
    private fun clearViewModes() {
        VaultDestination.entries.forEach { at ->
            GridPreference.setViewMode(ctx, at, at.defaultViewMode)
        }
    }

    @After
    fun tearDown() {
        clearViewModes()
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

    // --- what the user is shown -----------------------------------------------

    /** The outline's "dead cell", gone: the tile says what it is. */
    @Test
    fun eachDestinationNamesWhatItCannotDraw() = inTheApp { _ ->
        unlock()

        // `vault-destinations`. Media holds images and video and nothing else, so the
        // three fixtures below are *absent* here — which is the first half of the claim.
        assertTrue("a document is still in the media grid", !showing(PDF))
        assertTrue("a recording is still in the media grid", !showing(MP3))

        goTo(VaultDestination.Files)
        await("the documents' rows") { showing(PDF) && showing(ZIP) }
        assertTrue("a recording is in Files", !showing(MP3))

        goTo(VaultDestination.Memos)
        await("the recording's row") { showing(MP3) }
        assertTrue("a document is in Memos", !showing(PDF))

        Log.i(TAG, "each destination holds its own kind and no other")
    }

    /**
     * The claim this whole task rests on: an intact document is not a broken item.
     */
    @Test
    fun aDocumentIsPresentedAndNotReportedAsBroken() = inTheApp { _ ->
        unlock()
        goTo(VaultDestination.Files)
        await("the document's row") { showing(PDF) }
        compose.onNodeWithText(PDF).performClick()

        await("the identity page") { containing("Export this item") }
        assertTrue("the identity page does not name the item", showing(PDF))
        assertTrue(
            "an intact document was reported as a failure",
            !containing(FAILURE_TEXT),
        )
        // The size comes from the index row; the fixture's PDF is a couple of
        // hundred bytes, so the label is in bytes and exact.
        assertTrue("no size is shown", containing(" B"))
        Log.i(TAG, "document shown by identity, not as a failure")
    }

    /** A song says what it is: there is no picture to say it instead. */
    @Test
    fun anAudioItemNamesItselfWhilePlaying() = inTheApp { _ ->
        unlock()
        goTo(VaultDestination.Memos)
        await("the song's row") { showing(MP3) }
        compose.onNodeWithText(MP3).performClick()

        await("the player to name the item") { showing(MP3) && !onGrid() }
        assertTrue(
            "audio was routed to the image path and reported as a failure",
            !containing(FAILURE_TEXT),
        )
        assertTrue("no seek control on the audio page", hasDescription("Seek"))
        Log.i(TAG, "audio page names the item and carries the player's controls")
    }

    /**
     * The one-player rule, across kinds. P2-8 asserts it for video; audio shares the
     * page precisely so this cannot regress, and this is the assertion that says so.
     */
    @Test
    fun onlyOnePlayerExistsWithAudioInTheVault() = inTheApp { scenario ->
        unlock()
        goTo(VaultDestination.Memos)
        await("the song's row") { showing(MP3) }
        compose.onNodeWithText(MP3).performClick()
        await("the player") { !onGrid() }
        compose.waitForIdle()

        assertTrue("more than one player is alive: ${PlayerCensus.live.get()}",
            PlayerCensus.live.get() <= 1)

        // Back to the grid and into a document: leaving the player must release it.
        // **The viewer has no back control** — §6.6 keeps it out of `VaultScaffold`,
        // so back is the system gesture through the activity's one handler, which is
        // how `ViewerPagingDeviceTest` leaves it too.
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        await("the grid") { onGrid() }
        goTo(VaultDestination.Files)
        compose.onNodeWithText(PDF).performClick()
        await("the identity page") { containing("Export this item") }
        compose.waitForIdle()

        assertTrue(
            "a player is still alive on a page that has none: ${PlayerCensus.live.get()}",
            PlayerCensus.live.get() == 0,
        )
        Log.i(TAG, "players alive on the identity page: ${PlayerCensus.live.get()}")
    }

    /**
     * Back returns to Media before it leaves the vault — `vault-destinations`.
     *
     * Android's documented behaviour for a bottom bar: back goes to the start
     * destination. Without it a user who tapped Memos and then pressed back would leave
     * the vault from a screen they never opened it on.
     *
     * Asserted here rather than in `DestinationDeviceTest` because it needs the running
     * activity and its one `BackHandler`, and this file already creates a vault —
     * `DestructiveTestScanTest`'s debt list may only shrink, so a second such file is not
     * an option.
     */
    @Test
    fun backReturnsToMediaBeforeItLeavesTheVault() = inTheApp { scenario ->
        unlock()

        listOf(VaultDestination.Files, VaultDestination.Memos).forEach { destination ->
            goTo(destination)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("Media after back from ${destination.label}") { onGrid() }
            assertTrue(
                "back left the vault from ${destination.label}",
                onGrid(),
            )
        }
        Log.i(TAG, "back returns to Media from every other destination")
    }

    /**
     * A destination gives back the place it was left in — `vault-destinations`.
     *
     * The query is the half of "place" a test can read: a scroll position needs a list
     * longer than a screen, and three fixtures are not that. The level and the query are
     * saved and restored together by one function, so this covers the mechanism.
     */
    @Test
    fun aDestinationKeepsTheQueryItWasLeftWith() = inTheApp { _ ->
        unlock()
        goTo(VaultDestination.Files)
        await("the documents") { showing(PDF) && showing(ZIP) }

        compose.onNode(hasText("Search")).performClick()
        compose.onNode(hasText("Search")).performTextInput("statement")
        await("the query to narrow Files") { showing(PDF) && !showing(ZIP) }

        goTo(VaultDestination.Memos)
        await("the recording, unnarrowed") { showing(MP3) }

        goTo(VaultDestination.Files)
        await("Files to come back narrowed as it was left") { showing(PDF) && !showing(ZIP) }
        Log.i(TAG, "a query belongs to the destination it was typed in")
    }

    /**
     * A chosen mode survives a lock — `destination-view-modes`.
     *
     * ***This is the line between a view mode and everything else this screen remembers.***
     * The destination, the media filter and each destination's saved place are views of an
     * *unlocked vault* and every one of them dies with the session. A view mode is not that:
     * it is how the person likes their lists, it is a device preference beside the ordering
     * and the density, and a vault that reopened having forgotten it would be forgetting a
     * setting rather than protecting a secret.
     */
    @Test
    fun aChosenViewModeSurvivesALock() = inTheApp { _ ->
        unlock()
        goTo(VaultDestination.Files)
        await("the documents") { showing(PDF) }

        // Files defaults to List, so its toggle offers Tiles.
        compose.onNodeWithContentDescription(ctx.getString(VaultViewMode.Tiles.actionLabel)).performClick()
        await("tiles") {
            compose.onAllNodesWithContentDescription(ctx.getString(VaultViewMode.List.actionLabel))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The vault grid's leading action is Home, not Lock — the lock lives on the vault
        // list one level up. Leave the vault, then lock from there, which is the path a
        // person actually takes.
        compose.onNodeWithContentDescription("All vaults").performClick()
        await("the vault list") { !onGrid() }
        compose.onAllNodesWithContentDescription("Lock")[0].performClick()
        await("the lock screen") { showing("Welcome back") }
        unlockAgain()
        goTo(VaultDestination.Files)

        await("Files still in tiles after a lock") {
            compose.onAllNodesWithContentDescription(ctx.getString(VaultViewMode.List.actionLabel))
                .fetchSemanticsNodes().isNotEmpty()
        }
        Log.i(TAG, "a view mode outlives the session that chose it")
    }

    /**
     * Changing the mode keeps the query — `destination-view-modes`.
     *
     * Changing the shape of a list is not leaving it, so nothing about where the user is
     * standing may reset. The scroll position is carried the same way and by the same
     * mechanism; three fixtures are not a list long enough to assert it on.
     */
    @Test
    fun changingTheModeKeepsTheQuery() = inTheApp { _ ->
        unlock()
        goTo(VaultDestination.Files)
        await("the documents") { showing(PDF) && showing(ZIP) }

        compose.onNode(hasText("Search")).performClick()
        compose.onNode(hasText("Search")).performTextInput("statement")
        await("the query to narrow Files") { showing(PDF) && !showing(ZIP) }

        compose.onNodeWithContentDescription(ctx.getString(VaultViewMode.Tiles.actionLabel)).performClick()
        compose.waitForIdle()

        await("the query to survive the mode change") { showing(PDF) && !showing(ZIP) }
        Log.i(TAG, "a mode change is not a departure from the list")
    }

    // --- fixtures and harness --------------------------------------------------

    private fun silentMp3(frames: Int = 200): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(frames) {
            val frame = ByteArray(417)
            frame[0] = 0xFF.toByte()
            frame[1] = 0xFB.toByte()
            frame[2] = 0x90.toByte()
            frame[3] = 0x00
            out.write(frame)
        }
        return out.toByteArray()
    }

    private fun pdfBytes(): ByteArray = buildString {
        append("%PDF-1.4\n")
        append("1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n")
        append("2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n")
        append("3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n")
        append("trailer<</Root 1 0 R>>\n%%EOF\n")
    }.toByteArray(Charsets.US_ASCII)

    private fun zipBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("payload.bin"))
            zip.write(ByteArray(4096))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun inTheApp(body: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { body(it) }
    }

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 60_000, condition = condition)
        } catch (e: Throwable) {
            // ***Every root, and never in a position to replace the real failure.***
            // `onRoot()` throws when a sheet or dialog is showing, because that is a
            // second composition root — so the dump meant to explain a timeout became
            // "Expected exactly '1' node but found '2'" and the timeout's own message
            // was lost. That happened, and it cost a full-suite run to notice.
            runCatching {
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                    compose.onAllNodes(isRoot())[i].printToLog(TAG)
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun showing(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun containing(text: String): Boolean =
        compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun hasDescription(description: String): Boolean =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun onGrid(): Boolean =
        compose.onAllNodesWithContentDescription("All vaults").fetchSemanticsNodes().isNotEmpty()

    /**
     * Taps a destination in the bottom bar — `vault-destinations`.
     *
     * These items used to be in the grid beside the photographs. They are not any more:
     * a document is a row in Files and a recording is a row in Memos, each in the form
     * that kind is read in. Everything this file asserts about them is unchanged; only
     * the two taps that reach them are new.
     */
    private fun goTo(destination: VaultDestination) {
        compose.onNodeWithText(ctx.getString(destination.label)).performClick()
        await("the ${destination.label} destination") { showing(ctx.getString(destination.label)) }
        compose.waitForIdle()
    }

    /** The second unlock in one test — the disclosure is already behind us. */
    private fun unlockAgain() {
        await("the lock screen") { showing("Welcome back") }
        compose.waitForIdle()
        typePassphrase()
        compose.openFirstVaultFromTheList()
        await("the grid") { onGrid() }
        compose.waitForIdle()
    }

    private fun typePassphrase() {
        val field = compose.onNode(hasText("Passphrase"))
        field.performClick()
        compose.waitForIdle()
        field.performTextInput(PASS_TEXT)
        await("the passphrase to register") {
            compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unlock").performClick()
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
}
