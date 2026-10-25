package com.app.vault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
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
 * `one-time-purchase`, against the real activity: the gates that live in
 * `MainActivity` and the trial start that a route other than import now triggers.
 *
 * Every state is reached through [Entitlement]'s two test seams, so no Play account
 * and no purchase is involved. "The purchase lands" is the probe flipping and
 * [Billing.onChange] firing — exactly what Play's answer does in the shipping app.
 *
 * **Announced test-only state:** this creates a real vault in the app's own
 * `filesDir` (skipping if one is already there), and clears the entitlement
 * preference and the probe in both `@Before` and `@After`, so the device is never
 * left saying a trial started or ended.
 */
@RunWith(AndroidJUnit4::class)
class PurchaseGateDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")
    private val sharesDir get() = File(ctx.filesDir, "shares")

    private var created = false

    private companion object {
        const val PASS_TEXT = "correct horse battery staple"
        const val PHOTO = "photo.png"
        const val DAY_MS = 86_400_000L
    }

    @Before
    fun setUp() {
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        Entitlement.resetForTest(ctx)
        ShareSend.sweep(ctx)
        val store = BlobStore(blobsDir, incoming)
        val lifecycle = VaultLifecycle(slots, store)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true

        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        val thumbs = ThumbnailStore(store)
        // A thumbnail, because a tile without one draws no node to press.
        MetadataDb.commit(store, indexId, vmk) { db ->
            val written = store.write(ByteArrayInputStream(image()), vmk)
            var item = VaultItem(
                blobIds = listOf(written.blobId),
                plaintextLength = written.plaintextLength,
                thumb = null,
                filename = PHOTO,
                album = null,
                createdAt = null,
                importedAt = 1,
                tags = emptyList(),
            )
            Thumbnails.generate(store, item, vmk).webp?.let { item = item.withThumb(thumbs.stage(db, it)) }
            db.items += item
            thumbs.flush(db, vmk)
            db
        }
        lockKeyringAndVault()
    }

    @After
    fun tearDown() {
        lockKeyringAndVault()
        Entitlement.resetForTest(ctx)
        ShareSend.sweep(ctx)
        ThumbnailCache.clear()
        WorkManager.getInstance(ctx).cancelAllWork()
        if (!created) return
        MetadataDb.awaitQuiescent()
        slots.delete()
        blobsDir.deleteRecursively()
        incoming.deleteRecursively()
    }

    private fun image(): ByteArray {
        val bmp = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.rgb(40, 90, 140))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Trial started, thirty-one days gone, and Play's definitive "not owned". */
    private fun expire() {
        Entitlement.setTrialStartedAtForTest(ctx, System.currentTimeMillis() - 31 * DAY_MS)
        Entitlement.purchasedProbe = { false }
    }

    // --- harness -------------------------------------------------------------

    private fun await(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 45_000, condition = condition)
        } catch (e: Throwable) {
            runCatching {
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { i ->
                    compose.onAllNodes(isRoot())[i].printToLog("PurchaseGate")
                }
            }
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun showing(text: String, substring: Boolean = false): Boolean =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun described(text: String): Boolean =
        compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()

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
        compose.openFirstVaultFromTheList()
        await("the grid") { described("All vaults") }
        compose.waitForIdle()
    }

    private fun assertRefused() {
        await("the send refusal") { showing("Your trial has ended") }
        assertTrue(showing("Sending a share needs the one-time purchase", substring = true))
        assertTrue("what stays free is said", showing("Opening shares sent to you", substring = true))
        assertTrue("the way forward is beside it", showing("Buy", substring = true))
        assertTrue("no compose state behind it", !showing("No limit"))
        assertEquals(
            "nothing was sealed",
            0,
            sharesDir.listFiles()?.size ?: 0,
        )
    }

    // --- sending ---------------------------------------------------------------

    @Test
    fun afterTheTrialSharingFromTheSelectionBarIsRefusedOnTheSendScreen() {
        expire()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            await("the tile") { described(PHOTO) }
            compose.onNodeWithContentDescription(PHOTO).performTouchInput { longClick() }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Share securely").performClick()
            assertRefused()
        }
    }

    @Test
    fun afterTheTrialSharingFromTheViewerIsRefusedWhereTheUserIsLooking() {
        expire()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            await("the tile") { described(PHOTO) }
            compose.onNodeWithContentDescription(PHOTO).performClick()
            await("the viewer") { !described("All vaults") }
            compose.waitForIdle()
            compose.onRoot().performClick()
            await("the viewer's controls") { described("Share securely") }
            compose.onNodeWithContentDescription("Share securely").performClick()
            assertRefused()
            shot(scenario, "09-send-refused-from-viewer")
        }
    }

    /**
     * The real activity's pixels. `FLAG_SECURE` is cleared on this activity only, for
     * the frame, and `uiAutomation` takes it — never `View.draw`.
     */
    private fun shot(scenario: ActivityScenario<MainActivity>, name: String) {
        scenario.onActivity { it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        compose.waitForIdle()
        Thread.sleep(400)
        val instr = InstrumentationRegistry.getInstrumentation()
        val png = ByteArrayOutputStream().also {
            instr.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val dir = "/data/local/tmp/purchase-screen"
        instr.uiAutomation.executeShellCommand("mkdir -p $dir").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s -> s.readBytes() }
        }
        // `dd`, not `tee`: tee echoes the bytes to stdout, read only after the write, so a
        // large PNG (the keyboard up) fills the pipes and both ends block. HangWatch caught it.
        val (stdout, stdin) = instr.uiAutomation.executeShellCommandRw("dd of=$dir/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { it.write(png) }
        android.os.ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
    }

    @Test
    fun aPurchaseLiftsTheRefusalInPlace() {
        expire()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            await("the tile") { described(PHOTO) }
            compose.onNodeWithContentDescription(PHOTO).performTouchInput { longClick() }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Share securely").performClick()
            assertRefused()

            // Play answers "owned": the probe flips and Billing tells the activity.
            Entitlement.purchasedProbe = { true }
            Billing.onChange?.invoke()

            await("the compose state for the same item") { showing("No limit") }
            assertTrue("the refusal is gone", !showing("Your trial has ended"))
            assertTrue("on the same item", showing(PHOTO))
        }
    }

    @Test
    fun duringTheTrialSharingOpensTheComposeState() {
        Entitlement.startTrialIfNeeded(ctx)
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            await("the tile") { described(PHOTO) }
            compose.onNodeWithContentDescription(PHOTO).performTouchInput { longClick() }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Share securely").performClick()
            await("the compose state") { showing("No limit") }
            assertTrue(!showing("Your trial has ended"))
        }
    }

    // --- notes -----------------------------------------------------------------

    @Test
    fun afterTheTrialANewNoteIsRefusedOnTheGrid() {
        expire()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            compose.onNodeWithContentDescription("Actions").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("New note").performClick()
            await("the refusal") { showing("buy the app once", substring = true) }
            assertTrue("the editor did not open", !described("Note"))
            assertTrue(showing("Opening, viewing and exporting", substring = true))
        }
    }

    @Test
    fun aNewNoteStartsTheTrial() {
        assertEquals("no trial yet", 0L, Entitlement.trialStartedAt(ctx))
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            assertEquals("unlocking a vault with an item starts nothing", 0L, Entitlement.trialStartedAt(ctx))
            compose.onNodeWithContentDescription("Actions").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("New note").performClick()
            await("the editor") { described("Title") }
            // A note is saved only once it has a name — the autosave refuses a blank
            // title, so the title is what makes this an add.
            compose.onNodeWithContentDescription("Title").performTextInput("Shopping")
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Note").performTextInput("milk and bread")
            // The editor autosaves after a pause; the save is the add.
            await("the note to land and the trial to start") { Entitlement.trialStartedAt(ctx) != 0L }
        }
    }
}
