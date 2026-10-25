package com.app.vault

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
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
import com.app.vault.ui.PlayerCensus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Video orientation, Landscape — `destination-settings-and-slideshow` D8, in the real app. A
 * throwaway activity is recreated by the turn it asks for and loses the viewer with it; the app's
 * activity handles the turn itself (`landscape-rotation`), which is the claim: the video turns
 * sideways with the phone held upright, and plays on.
 *
 * **A real vault in the app's own `filesDir`**, made only when none exists. Media's settings and
 * the rotation lock are put back as they were.
 */
@RunWith(AndroidJUnit4::class)
class VideoOrientationDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")

    private var created = false
    private lateinit var media: MediaSettings

    private companion object {
        const val PASS_TEXT = "video orientation passphrase"
        const val CLIP = "upright.mp4"
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
        media = GridPreference.mediaSettings(ctx)
        GridPreference.setMediaSettings(ctx, media.copy(orientation = VideoOrientation.Landscape))
        // The phone upright and its rotation free, as a person holding it would have it.
        instr.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
        instr.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
        lifecycle.create(PASS_TEXT.toByteArray(Charsets.UTF_8))
        created = true

        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        val file = File(ctx.cacheDir, "orientation-clip.mp4")
        check(VideoCorpus.generate(VideoCorpus.SPECS.first(), file)) { "the emulator has no H.264 encoder" }
        val thumbs = ThumbnailStore(store)
        MetadataDb.commit(store, indexId, vmk) { db ->
            val written = store.writeItem(ByteArrayInputStream(file.readBytes()), vmk)
            var item = VaultItem(written.blobIds, written.plaintextLength, null, CLIP, null, 1_000L, 1L, emptyList())
            val made = Thumbnails.generate(store, item, vmk)
            made.webp?.let { item = item.withThumb(thumbs.stage(db, it), made.durationMs) }
            db.items += item
            thumbs.flush(db, vmk)
        }
        file.delete()
        lockKeyringAndVault()
    }

    @After
    fun tearDown() {
        if (!created) return
        GridPreference.setMediaSettings(ctx, media)
        lockKeyringAndVault()
        ThumbnailCache.clear()
        WorkManager.getInstance(ctx).cancelAllWork()
        MetadataDb.awaitQuiescent()
        slots.delete()
        blobsDir.deleteRecursively()
        incoming.deleteRecursively()
    }

    private fun await(what: String, ms: Long = 45_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = ms, condition = condition)
        } catch (e: Throwable) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun described(text: String) =
        compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun landscapeTurnsAnUprightVideoAndLetsGoOnLeaving() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await("the lock screen") { compose.onAllNodes(hasText("Welcome back")).fetchSemanticsNodes().isNotEmpty() }
            val field = compose.onNode(hasText("Passphrase"))
            field.performClick()
            field.performTextInput(PASS_TEXT)
            await("the passphrase to register") {
                compose.onAllNodes(hasText("Unlock") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Unlock").performClick()
            compose.openFirstVaultFromTheList()
            await("the video tile") { described(CLIP) }

            PlayerCensus.lastPlayer = null
            compose.onNodeWithContentDescription(CLIP).performClick()
            await("the video sideways") {
                var requested = 0
                var orientation = 0
                scenario.onActivity {
                    requested = it.requestedOrientation
                    orientation = it.resources.configuration.orientation
                }
                requested == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE &&
                    orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            await("the player through the turn") {
                var playing = false
                instr.runOnMainSync { playing = PlayerCensus.lastPlayer?.get()?.isPlaying == true }
                playing
            }

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await("the grid") { described("All vaults") }
            var requested = -2
            scenario.onActivity { requested = it.requestedOrientation }
            assertEquals("released when the viewer closes", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, requested)
        }
    }
}
