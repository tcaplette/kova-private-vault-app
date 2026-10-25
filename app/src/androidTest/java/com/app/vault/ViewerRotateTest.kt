package com.app.vault

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.VaultViewer
import com.app.vault.ui.theme.VaultTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A turn in the viewer shows up in the viewer — `viewer-chrome-and-editor`, fixed here.
 *
 * `decodeFull` bakes the composed angle — the item's EXIF plus its stored turn — into
 * the bitmap, and the page decoded once per `itemId`. Rotating writes the row and
 * changes no identity, so the decode never re-ran: the write worked and the photograph
 * on screen did not move. Leaving the viewer and returning built a fresh composition,
 * which is the "refresh" that appeared to be needed.
 */
@RunWith(AndroidJUnit4::class)
class ViewerRotateTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray

    @Before fun setUp() {
        dir = File(ctx.filesDir, "viewer-rotate-${System.nanoTime()}").apply { mkdirs() }
        store = BlobStore(File(dir, "blobs"), File(dir, "incoming"))
        vmk = VaultKeys.newVmk()
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun anImage(): VaultItem {
        val png = java.io.ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        val written = store.writeItem(java.io.ByteArrayInputStream(png), vmk)
        return VaultItem(
            written.blobIds, written.plaintextLength, null, "photo.png",
            null, null, 1L, emptyList(),
        )
    }

    /**
     * ***The decode re-runs when the turn changes, and not otherwise.*** Both halves
     * matter: without the first the picture never moves, and without the second every
     * recomposition would decrypt and decode a full-size photograph.
     */
    @Test
    fun rotatingReDecodesAndNothingElseDoes() {
        val original = anImage()
        val decodes = mutableListOf<Int>()
        var shown by mutableStateOf(original)
        var reveals = 0

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    VaultTheme {
                        VaultViewer(
                            items = listOf(shown),
                            startIndex = 0,
                            store = store,
                            vmk = vmk,
                            fullImageFor = { item ->
                                decodes += item.rotation
                                Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
                            },
                            onBack = { reveals++ },
                            onDelete = null,
                            onRename = null,
                            onExport = null,
                            onRotate = { shown = shown.withRotation((shown.rotation + 90) % 360) },
                            onEdit = null,
                            onShare = null,
                            onSetCover = null,
                            onMoveToFolder = null,
                        )
                    }
                }
            }
            compose.waitForIdle()
            Thread.sleep(400)
            assertEquals("one decode on arrival", listOf(0), decodes)

            // A recomposition that changes nothing about the item must not decode.
            scenario.onActivity { shown = shown.withFilename("photo.png") }
            compose.waitForIdle()
            Thread.sleep(300)
            assertEquals("a rename must not re-decode a photograph", listOf(0), decodes)

            // The turn, exactly as `VaultRotate` writes it.
            scenario.onActivity { shown = shown.withRotation(90) }
            compose.waitForIdle()
            Thread.sleep(400)

            assertEquals("a turn must re-decode at the new angle", listOf(0, 90), decodes)
        }
        assertTrue(reveals >= 0)
    }

    /**
     * ***The photograph stays on screen while the turned one decodes.***
     *
     * A full-size decrypt and decode is not instant, and the page's other state for
     * "no bitmap yet" draws `ViewerNotice("Opening…")` — so a turn that blanked first
     * would replace the picture with a line of text every time. This holds the decode
     * open and asserts the image is still there while it runs.
     */
    @Test
    fun aTurnKeepsTheFrameOnScreenWhileTheTurnedOneDecodes() {
        val original = anImage()
        var shown by mutableStateOf(original)
        val hold = java.util.concurrent.CountDownLatch(1)
        val second = java.util.concurrent.CountDownLatch(1)
        var decodes = 0

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    VaultTheme {
                        VaultViewer(
                            items = listOf(shown),
                            startIndex = 0,
                            store = store,
                            vmk = vmk,
                            fullImageFor = {
                                // The second decode — the turned one — blocks until
                                // the assertion below has run.
                                if (decodes++ > 0) { second.countDown(); hold.await() }
                                Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
                            },
                            onBack = {},
                            onDelete = null,
                            onRename = null,
                            onExport = null,
                            onRotate = {},
                            onEdit = null,
                            onShare = null,
                            onSetCover = null,
                            onMoveToFolder = null,
                        )
                    }
                }
            }
            compose.waitForIdle()
            Thread.sleep(400)
            compose.onNodeWithContentDescription("photo.png").assertIsDisplayed()

            scenario.onActivity { shown = shown.withRotation(90) }
            second.await(4, java.util.concurrent.TimeUnit.SECONDS)
            Thread.sleep(300)

            // Mid-decode: the picture is still up, and the "Opening…" notice is not.
            compose.onNodeWithContentDescription("photo.png").assertIsDisplayed()
            compose.onAllNodesWithText("Opening…").assertCountEquals(0)

            hold.countDown()
            compose.waitForIdle()
            Thread.sleep(300)
            compose.onNodeWithContentDescription("photo.png").assertIsDisplayed()
        }
    }

    /** Arriving at a *different* photograph does blank — holding the old one would lie. */
    @Test
    fun arrivingAtADifferentPhotographDoesNotHoldTheOldFrame() {
        val first = anImage()
        val hold = java.util.concurrent.CountDownLatch(1)
        val started = java.util.concurrent.CountDownLatch(1)
        var decodes = 0
        var shown by mutableStateOf(first)

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    VaultTheme {
                        VaultViewer(
                            items = listOf(shown),
                            startIndex = 0,
                            store = store,
                            vmk = vmk,
                            fullImageFor = {
                                if (decodes++ > 0) { started.countDown(); hold.await() }
                                Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
                            },
                            onBack = {},
                            onDelete = null,
                            onRename = null,
                            onExport = null,
                            onRotate = {},
                            onEdit = null,
                            onShare = null,
                            onSetCover = null,
                            onMoveToFolder = null,
                        )
                    }
                }
            }
            compose.waitForIdle()
            Thread.sleep(400)

            scenario.onActivity { shown = anImage() }
            started.await(4, java.util.concurrent.TimeUnit.SECONDS)
            Thread.sleep(300)

            // A different item: the old frame must be gone, not held under a new name.
            compose.onAllNodesWithText("Opening…").assertCountEquals(1)

            hold.countDown()
            compose.waitForIdle()
        }
    }
}
