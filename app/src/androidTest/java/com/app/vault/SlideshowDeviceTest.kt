package com.app.vault

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.app.vault.ui.SLIDE_TAG
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
 * `slideshow` in the real app — what only the running activity can show: the screen held while it
 * plays and let go when it pauses, the vault staying open past the screen timeout and locking once
 * paused, back landing on the item showing, and no Slideshow in the trash.
 *
 * **A real vault in the app's own `filesDir`**, made only when none exists (`SortDeviceTest`'s
 * guard), of fifty photographs, one of them in the trash. The screen timeout, the launcher identity
 * and the slideshow's options are put back as they were.
 */
@RunWith(AndroidJUnit4::class)
class SlideshowDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false
    private var timeout = ""
    private lateinit var options: SlideshowSettings

    private companion object {
        const val PASS_TEXT = "slideshow device passphrase"
        const val COUNT = 50
        const val TRASHED = "trashed.png"
        const val TIMEOUT_MS = 15_000
        fun name(i: Int) = "photo-%02d.png".format(i)
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instr.uiAutomation.executeShellCommand(command))
            .use { it.readBytes().toString(Charsets.UTF_8).trim() }

    @Before
    fun setUp() {
        LauncherIdentity.switchTo(ctx, LauncherIdentity.Default)
        val store = BlobStore(blobsDir, incoming)
        val lifecycle = VaultLifecycle(slots, store)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        options = GridPreference.slideshowSettings(ctx)
        GridPreference.setSlideshowSettings(ctx, SlideshowSettings(speed = SlideshowSpeed.Three))
        timeout = shell("settings get system screen_off_timeout")
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true

        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        val thumbs = ThumbnailStore(store)
        MetadataDb.commit(store, indexId, vmk) { db ->
            (0..COUNT).forEach { i ->
                val written = store.write(ByteArrayInputStream(png(i)), vmk)
                var item = VaultItem(
                    blobIds = listOf(written.blobId),
                    plaintextLength = written.plaintextLength,
                    thumb = null,
                    filename = if (i == COUNT) TRASHED else name(i),
                    album = null,
                    // Newest first is the default order, so photo-00 is the first tile.
                    createdAt = 1_700_000_000_000L - i * 60_000L,
                    importedAt = 1L + i,
                    tags = emptyList(),
                )
                Thumbnails.generate(store, item, vmk).webp?.let { item = item.withThumb(thumbs.stage(db, it)) }
                if (i == COUNT) item = item.withDeletedAt(System.currentTimeMillis())
                db.items += item
            }
            thumbs.flush(db, vmk)
        }
        lockKeyringAndVault()
        wake()
    }

    @After
    fun tearDown() {
        if (!created) return
        GridPreference.setSlideshowSettings(ctx, options)
        if (timeout.isNotEmpty() && timeout != "null") shell("settings put system screen_off_timeout $timeout")
        wake()
        lockKeyringAndVault()
        ThumbnailCache.clear()
        WorkManager.getInstance(ctx).cancelAllWork()
        MetadataDb.awaitQuiescent()
        slots.delete()
        blobsDir.deleteRecursively()
        incoming.deleteRecursively()
    }

    private fun png(i: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.rgb(5 * i % 256, 80, 255 - 5 * i % 256)) }
            .compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    private fun wake() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        val until = System.currentTimeMillis() + 5_000L
        while (!interactive() && System.currentTimeMillis() < until) Thread.sleep(100)
    }

    private fun await(what: String, ms: Long = 45_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = ms, condition = condition)
        } catch (e: Throwable) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun showing(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun described(text: String) =
        compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()

    private fun unlock() {
        await("the lock screen") { showing("Welcome back") }
        val field = compose.onNode(hasText("Passphrase"))
        field.performClick()
        field.performTextInput(PASS_TEXT)
        await("the passphrase to register") {
            compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unlock").performClick()
        compose.openFirstVaultFromTheList()
        await("the grid") { described("All vaults") }
    }

    /** The item the slideshow is showing, by its name. */
    private fun slide(): String? = compose.onAllNodes(hasTestTag(SLIDE_TAG))
        .fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.firstOrNull()

    private fun screenHeld(scenario: ActivityScenario<MainActivity>): Boolean {
        var held = false
        scenario.onActivity { held = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
        return held
    }

    private fun interactive() = ctx.getSystemService(PowerManager::class.java).isInteractive

    /** Opens the first photo and starts its slideshow from the ⋮ menu. */
    private fun startSlideshow() {
        await("the first tile") { described(name(0)) }
        compose.onNodeWithContentDescription(name(0)).performClick()
        await("the viewer") { described("More") }
        compose.onNodeWithContentDescription("More").performClick()
        await("the menu") { showing("Slideshow") }
        compose.onNodeWithText("Slideshow").performClick()
        await("the first slide") { slide() == name(0) }
    }

    /** Past the screen timeout, it is still playing: the screen held, the vault open, the slides moving. */
    @Test
    fun aSlideshowKeepsTheVaultOpenPastTheScreenTimeout() {
        shell("settings put system screen_off_timeout $TIMEOUT_MS")
        // A device idle longer than the new timeout sleeps the moment it is set; a wake is activity.
        wake()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            startSlideshow()
            assertTrue("the screen is held while it plays", screenHeld(scenario))
            // Real time for the screen's timeout, and the composition's clock with it — a bare sleep
            // moves only the first, `VoiceMemoScreenshotTest`'s finding.
            repeat(((TIMEOUT_MS + 5_000L) / 100L).toInt()) {
                Thread.sleep(100L)
                compose.mainClock.advanceTimeBy(100L)
            }
            assertTrue("the screen stayed on", interactive())
            assertTrue("the vault stayed open", VaultSession.isUnlocked)
            val at = slide()
            assertTrue("it advanced through the album: $at", at != null && at != name(0))
        }
    }

    /** Paused, the screen is let go: it times out and the vault locks as it would without a slideshow. */
    @Test
    fun pausedTheScreenTimesOutAndTheVaultLocks() {
        shell("settings put system screen_off_timeout $TIMEOUT_MS")
        // A device idle longer than the new timeout sleeps the moment it is set; a wake is activity.
        wake()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            startSlideshow()
            compose.onNode(hasTestTag(SLIDE_TAG)).performClick()
            await("the controls") { described("Pause slideshow") }
            compose.onNodeWithContentDescription("Pause slideshow").performClick()
            await("paused") { described("Play slideshow") }
            assertFalse("paused, the screen is let go", screenHeld(scenario))
            val deadline = System.currentTimeMillis() + TIMEOUT_MS + 20_000L
            while (interactive() && System.currentTimeMillis() < deadline) Thread.sleep(500)
            assertFalse("the screen timed out", interactive())
            val locked = System.currentTimeMillis() + 10_000L
            while (VaultSession.isUnlocked && System.currentTimeMillis() < locked) Thread.sleep(200)
            assertFalse("the vault locked", VaultSession.isUnlocked)
        }
    }

    /** Back ends it on the item showing, and the viewer is left there. */
    @Test
    fun backLandsTheViewerOnTheItemShowing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            unlock()
            startSlideshow()
            await("the slideshow to move on", 15_000) { slide().let { it != null && it != name(0) } }
            val showingNow = slide()!!
            // Back as the system delivers it to the activity — `CoverLifecycleDeviceTest`'s idiom.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("the slideshow to end") { compose.onAllNodes(hasTestTag(SLIDE_TAG)).fetchSemanticsNodes().isEmpty() }
            assertTrue("the viewer is still up", described("More"))
            assertTrue("on the item that was showing: $showingNow", described(showingNow))
            assertFalse("and the screen is let go", screenHeld(scenario))
        }
    }

    /** In the trash the ⋮ menu offers no slideshow. */
    @Test
    fun theTrashHasNoSlideshow() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            compose.onNodeWithContentDescription("Trash and settings").performClick()
            await("the drawer") { showing("Trash") }
            compose.onNodeWithText("Trash").performClick()
            await("the trashed tile") { described(TRASHED) }
            compose.onNodeWithContentDescription(TRASHED).performClick()
            await("the viewer") { described("Back") }
            if (described("More")) {
                compose.onNodeWithContentDescription("More").performClick()
                compose.waitForIdle()
            }
            assertFalse("no Slideshow in the trash", showing("Slideshow"))
        }
    }
}
