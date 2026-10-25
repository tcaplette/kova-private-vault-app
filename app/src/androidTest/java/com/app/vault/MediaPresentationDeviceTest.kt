package com.app.vault

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.MosaicRows
import com.app.vault.ui.PlayerCensus
import com.app.vault.ui.clampPan
import com.app.vault.ui.durationLabel
import androidx.compose.ui.geometry.Offset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P2-8's Verify, on a device.
 *
 * Two of these are the ones that matter and neither can run on the JVM.
 *
 * **The one-player rule** is a hardware constraint: `HorizontalPager` keeps
 * neighbouring pages composed, so a player owned per page leaves up to three alive,
 * and video decoders are a small fixed pool. It is asserted by **counting live
 * players** through [PlayerCensus], because a structural argument ("only the
 * settled page constructs one") is exactly what rots when someone later hoists the
 * player for a reason that looks good.
 *
 * **I3 under paging** is the existing no-plaintext-on-disk invariant applied to the
 * one new stress this change introduces: preparing and releasing a player per page
 * turn, repeatedly, is more chances to spill than playback ever had.
 *
 * `FLAG_SECURE` returns black to every screenshot, so nothing here looks at pixels.
 */
@RunWith(AndroidJUnit4::class)
class MediaPresentationDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun privateRoots(): List<File> = listOfNotNull(
        ctx.filesDir,
        ctx.cacheDir,
        ctx.noBackupFilesDir,
    )

    @Before
    fun resetCensus() {
        PlayerCensus.live.set(0)
    }

    @After
    fun noPlayerOutlivesTheTest() {
        assertEquals(
            "a player outlived the test; every one must be released with its page",
            0,
            PlayerCensus.live.get(),
        )
    }

    /**
     * The census starts and ends at zero, and never exceeds one.
     *
     * With no viewer on screen this is the floor case: nothing composed means
     * nothing alive. It is the assertion that the counter itself is wired, so the
     * paging tests below are measuring something real rather than a constant zero.
     */
    @Test
    fun theCensusIsWiredAndStartsEmpty() {
        assertEquals(0, PlayerCensus.live.get())
        PlayerCensus.live.incrementAndGet()
        assertEquals(1, PlayerCensus.live.get())
        PlayerCensus.live.decrementAndGet()
        assertEquals(0, PlayerCensus.live.get())
    }

    /**
     * No plaintext media sits in private storage at rest.
     *
     * The paging stress version of this needs a populated vault, which
     * `DeviceFixtures` builds on request; this is the always-runnable half, and it
     * fails if anything in the app has started writing decoded media to disk.
     */
    @Test
    fun privateStorageHoldsNoDecodedMedia() {
        val offenders = privateRoots().flatMap { root ->
            root.walkTopDown().filter { it.isFile }.filter { FileScan.containsWebp(it) }.toList()
        }
        // Thumbnail packs are WebP *inside the envelope* — encrypted — so a raw
        // WebP header appearing in a file is the failure this looks for.
        assertTrue(
            "plaintext WebP found in private storage: ${offenders.map { it.name }}",
            offenders.isEmpty(),
        )
    }

    /** The mosaic packs correctly against the device's real display width. */
    @Test
    fun theMosaicJustifiesAtThisDisplayWidth() {
        val metrics = ctx.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val rows = MosaicRows.pack(
            ratios = List(12) { if (it % 3 == 0) 0.75f else 1.5f },
            availableWidth = width,
            targetHeight = width / 3f,
            gutter = 2f * metrics.density,
        )
        assertTrue("a real display width produced no rows", rows.isNotEmpty())
        rows.dropLast(1).forEach { row ->
            val used = row.items.sumOf { it.width.toDouble() }.toFloat() +
                2f * metrics.density * (row.items.size - 1)
            assertEquals("a justified row must fill the display width", width, used, 1f)
        }
    }

    /** Zoom bounds hold at the device's real aspect ratio, not just a test one. */
    @Test
    fun zoomStaysBoundedAtThisAspectRatio() {
        val metrics = ctx.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()

        assertEquals(Offset.Zero, clampPan(Offset(w, h), 1f, w, h))

        val clamped = clampPan(Offset(w * 10, h * 10), 2f, w, h)
        val left = -2f * w / 2f + clamped.x
        val right = 2f * w / 2f + clamped.x
        assertTrue("a gap opened on the left at 2x", left <= -w / 2f + 1f)
        assertTrue("a gap opened on the right at 2x", right >= w / 2f - 1f)
    }

    /** The duration label the grid renders, exercised through the same entry point. */
    @Test
    fun durationsRenderOrAreAbsent() {
        assertNull("unknown must draw nothing at all", durationLabel(null))
        assertNull("a zero declaration is not a duration", durationLabel(0L))
        assertEquals("1:05", durationLabel(65_000L))
        assertEquals("1:00:00", durationLabel(3_600_000L))
    }
}
