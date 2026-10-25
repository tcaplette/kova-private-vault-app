package com.app.vault

import android.provider.MediaStore
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * P2-17's Verify, on the emulated sensor.
 *
 * `CameraGeometryTest` answers the arithmetic — orientation, names, sizes — without
 * a camera. This answers the two clauses `OUTLINE.md` puts on the task and only a
 * device can settle: **a photo and a video both arrive in the vault**, and
 * **`MediaStore`, `cacheDir` and `filesDir` show no plaintext at any point**.
 *
 * "At any point" is why the recording is scanned by a thread running *while it
 * records*, the shape `PlaybackTest` established: a design that staged a file and
 * deleted it at `stop()` would pass a scan taken afterwards, and that scan would be
 * testing the cleanup rather than the claim.
 *
 * The needles are the item's **own decrypted bytes**, taken out of the vault after
 * the fact. A header-shaped needle — `FFD8FF`, or a transport stream's 0x47 — is
 * three bytes or one, and ciphertext hits those by chance about once every few
 * megabytes scanned. Sixty-four bytes of the frame itself cannot be hit by chance
 * and cannot be produced by anything but the plaintext.
 *
 * **This test creates a real vault in the app's own `filesDir`**, as
 * `SortDeviceTest` and `MultiSelectDeviceTest` do, and removes it in [tearDown].
 */
@RunWith(AndroidJUnit4::class)
class CameraDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false
    private lateinit var store: BlobStore
    private lateinit var indexId: ByteArray

    private companion object {
        const val TAG = "CameraVerify"
        const val PASS_TEXT = "correct horse battery staple"
        const val RECORD_MS = 3_000L

        /** Long enough that ciphertext cannot match it by chance. */
        const val NEEDLE = 64

        /** The receipt, by the description a screen reader reads. */
        const val RECEIPT = "Last capture, saved to the vault"
    }

    @Before
    fun setUp() {
        assumeTrue("this device has no camera", VaultCamera.available(ctx))
        store = BlobStore(blobsDir, incoming)
        val lifecycle = VaultLifecycle(slots, store)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())

        // Granted here rather than tapped through, because the system dialog is not
        // this app's surface and P2-4 already owns the request path. What is being
        // tested is what happens to the bytes, not what the platform draws.
        listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
        ).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(ctx.packageName, it)
        }

        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true
        indexId = VaultSession.metadataBlobId()!!.copyOf()
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

    // --- the Verify ------------------------------------------------------------

    @Test
    fun aPhotographIsWrittenIntoTheVaultAndNowhereElse() = inTheApp {
        unlock()
        openCamera()

        compose.onNodeWithContentDescription("Take photo").performClick()
        val item = awaitItem("photo-")

        val plain = decrypt(item)
        assertTrue(
            "what came back is not a JPEG",
            plain.size > 3 && plain[0] == 0xFF.toByte() && plain[1] == 0xD8.toByte(),
        )
        assertNotNull("a capture arrives with its thumbnail, not without one", item.thumb)
        assertNotNull("a capture has a real capture date", item.createdAt)
        assertTrue(
            "the capture date is not the moment it was taken",
            kotlin.math.abs(System.currentTimeMillis() - item.createdAt!!) < 120_000,
        )

        val hits = scanFor(needles(plain))
        assertTrue("plaintext from the capture found on disk: $hits", hits.isEmpty())
        assertNoMediaStoreRows()
    }

    @Test
    fun aRecordingIsStreamedAndNeverStaged() = inTheApp {
        unlock()
        openCamera()

        chooseVideoMode()

        val before = privateFiles().map { it.path }.toSet()
        val stop = AtomicBoolean(false)
        var passes = 0
        val appeared = mutableSetOf<String>()
        val scanner = thread {
            while (!stop.get()) {
                passes++
                privateFiles()
                    .filterNot { it.path.startsWith(blobsDir.path) }
                    .filterNot { it.path.startsWith(incoming.path) }
                    .filterNot { it.path in before }
                    // `androidx.profileinstaller` writes this marker on the first launch
                    // after an install, and `clearPackageData` makes **every** test a
                    // first launch — so it lands mid-recording, after `before` was taken.
                    // Excluded by exact name and nothing wider: the claim here is I3, that
                    // no plaintext user content is written outside the blob store, and a
                    // zero-content marker the platform writes is not that.
                    .filterNot { it.name == "profileInstalled" }
                    .forEach { appeared += it.path }
            }
        }

        compose.onNodeWithContentDescription("Start recording").performClick()
        await("the recording to start") { hasDescription("Stop recording") }
        Thread.sleep(RECORD_MS)
        compose.onNodeWithContentDescription("Stop recording").performClick()

        val item = awaitItem("video-")
        stop.set(true)
        scanner.join(10_000)

        assertTrue("the scanner never ran", passes > 0)
        assertTrue(
            "a file appeared outside the blob store while recording: $appeared",
            appeared.isEmpty(),
        )
        assertTrue("the recording is empty", item.plaintextLength > 0)

        val plain = decrypt(item)
        val hits = scanFor(needles(plain))
        assertTrue("plaintext from the recording found on disk: $hits", hits.isEmpty())
        assertNoMediaStoreRows()

        Log.i(
            TAG,
            "recording: ${item.plaintextLength} B in ${item.blobIds.size} blob(s), " +
                "$passes scanner passes"
        )
    }

    /**
     * The lock wins. `runtime-hardening` permits two auto-lock suppressions and a
     * recording is not a third — so a lock during one ends it, and what was recorded
     * so far is discarded rather than committed under a key that no longer exists.
     */
    @Test
    fun aLockDuringARecordingDiscardsIt() = inTheApp {
        unlock()
        openCamera()

        chooseVideoMode()
        compose.onNodeWithContentDescription("Start recording").performClick()
        await("the recording to start") { hasDescription("Stop recording") }
        Thread.sleep(RECORD_MS)

        // What auto-lock does: the VMK is zeroed and the session is gone. Leaving the
        // screen then runs the same teardown a lock's recomposition would.
        instrumentation.runOnMainSync { lockKeyringAndVault() }
        // The camera's own leave control. It was `Back` until `camera-conventions`
        // gave this screen a viewfinder's chrome instead of an app bar; the stale
        // `chooseVideoMode` above meant this line was never reached to find out.
        compose.onNodeWithContentDescription("Close the camera").performClick()
        compose.waitForIdle()
        Thread.sleep(2_000)

        val vault = VaultLifecycle(slots, store)
        assertTrue("the vault must reopen", vault.unlockAndOpen(PASS_TEXT.toByteArray()))
        val db = MetadataDb.load(store, indexId, VaultSession.vmk()!!)
        assertEquals(
            "an interrupted recording became an item",
            0,
            db.items.count { it.filename.startsWith("video-") },
        )
        assertNoMediaStoreRows()
    }

    /** No row this app contributed, in either collection, after any of the above. */
    private fun assertNoMediaStoreRows() {
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).forEach { uri ->
            // No permission is needed and none is held: an app always sees its own
            // contributions and nothing else, which is exactly the question here.
            val count = ctx.contentResolver
                .query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
                ?.use { it.count } ?: 0
            assertEquals("this app contributed rows to $uri", 0, count)
        }
    }

    // --- harness ---------------------------------------------------------------

    /** The app, launched and torn down around one test — `SortDeviceTest`'s shape. */
    /**
     * ***A capture the user can see happen*** — `camera-capture-feedback`.
     *
     * The reported fault was that pressing the shutter changed nothing on screen, so
     * there was no way to know a photograph had been taken or kept. The haptic and
     * the blink are not assertable from here; the receipt is, and it is the one that
     * carries the claim — it persists after the moment, it says the item is in the
     * vault, and it opens that item.
     */
    @Test
    fun aCaptureLeavesAReceiptThatOpensTheItem() = inTheApp {
        unlock()
        openCamera()

        assertTrue(
            "the corner starts empty — the receipt is this session's, not the vault's",
            compose.onAllNodesWithContentDescription(RECEIPT).fetchSemanticsNodes().isEmpty(),
        )

        compose.onNodeWithContentDescription("Take photo").performClick()
        val item = awaitItem("photo-")

        await("the receipt") { hasDescription(RECEIPT) }
        compose.onNodeWithContentDescription(RECEIPT).performClick()

        // The convention's other half: the thumbnail opens that item.
        await("the viewer on the item just captured") { hasDescription(item.filename) }

        // And back returns to the viewfinder rather than leaving the camera.
        compose.onNodeWithContentDescription("Back").performClick()
        await("the viewfinder again") { hasDescription("Take photo") }
        assertTrue(
            "the receipt survives the round trip",
            compose.onAllNodesWithContentDescription(RECEIPT).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    /**
     * ***The clip gets the same receipt, and the chrome recedes while it films.***
     *
     * A recording already says it is running — the dot and the clock. What it never
     * said is that the clip was kept, and stopping used to leave a screen with less
     * on it than the one before the shutter was pressed.
     */
    @Test
    fun stoppingARecordingLeavesAReceiptToo() = inTheApp {
        unlock()
        openCamera()
        chooseVideoMode()

        compose.onNodeWithContentDescription("Start recording").performClick()
        await("the recording to start") { hasDescription("Stop recording") }
        assertTrue(
            "the receipt is hidden while filming, as the lens switch is",
            compose.onAllNodesWithContentDescription(RECEIPT).fetchSemanticsNodes().isEmpty(),
        )

        Thread.sleep(RECORD_MS)
        compose.onNodeWithContentDescription("Stop recording").performClick()
        awaitItem("video-")

        await("the receipt for the clip") { hasDescription(RECEIPT) }
    }

    private fun inTheApp(body: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { body() }
    }

    private fun privateFiles(): List<File> =
        listOf(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir)
            .flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }

    /** Two slices of the plaintext — the head, and a piece from the middle. */
    private fun needles(plain: ByteArray): List<ByteArray> = buildList {
        if (plain.size >= NEEDLE) add(plain.copyOfRange(0, NEEDLE))
        if (plain.size >= NEEDLE * 4) {
            val mid = plain.size / 2
            add(plain.copyOfRange(mid, mid + NEEDLE))
        }
    }

    private fun scanFor(needles: List<ByteArray>): List<String> {
        assertTrue("nothing to scan for", needles.isNotEmpty())
        return privateFiles().filter { f -> needles.any { FileScan.contains(f, it) } }
            .map { it.path }
    }

    private fun decrypt(item: VaultItem): ByteArray =
        store.reader(item.blobIds, VaultSession.vmk()!!, item.plaintextLength).use {
            it.readRange(0, item.plaintextLength.toInt())
        }

    /** Polls the committed index — the app writes it on every capture. */
    private fun awaitItem(prefix: String): VaultItem {
        var found: VaultItem? = null
        await("an item named $prefix… in the index") {
            val vmk = VaultSession.vmk() ?: return@await false
            // **A torn read is a retry, not a failure.** This polls the same index
            // file the app is committing into, so it can arrive mid-write and parse
            // as truncated; the next poll reads the finished one. Without this the
            // test fails on a race of its own making rather than on the claim.
            found = runCatching {
                MetadataDb.load(store, indexId, vmk).items
                    .firstOrNull { it.filename.startsWith(prefix) }
            }.getOrNull()
            found != null
        }
        return found!!
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
     * Taps the strip's Video half and waits for the shutter to become the one that
     * starts a recording.
     *
     * ***This read `Mode  ·  Photo` until now.*** `camera-conventions` replaced that
     * text button with a two-segment strip naming both modes, and the helper was left
     * pointing at a label that no longer exists — so both recording tests failed on
     * the tap rather than on anything they were asserting. The shutter's own
     * description is what is waited on, because it is the thing the mode changes.
     */
    private fun chooseVideoMode() {
        compose.onNodeWithText("Video").performClick()
        await("video mode") { hasDescription("Start recording") }
        compose.waitForIdle()
    }

    /**
     * Opens the camera from the floating action button.
     *
     * It was a drawer row until `settings-screen-and-fab` moved the four things you
     * *do to the vault* onto the button — the camera among them.
     */
    private fun openCamera() {
        compose.onNodeWithContentDescription("Actions").performClick()
        await("the camera action") { containing("Camera") }
        compose.onNodeWithText("Camera").performClick()
        await("the shutter") { hasDescription("Take photo") }
        compose.waitForIdle()
    }
}
