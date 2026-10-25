package com.app.vault.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The viewer's arithmetic — P2-8.
 *
 * Zoom bounds and the position readout are the two places in the viewer that are
 * pure functions, and both are places where being wrong is invisible: a bad pan
 * clamp loses the photograph off-screen with no indication which way to drag back,
 * and `FLAG_SECURE` means nobody can screenshot it to show you.
 */
class ViewerMathTest {

    private val w = 1000f
    private val h = 2000f

    @Test
    fun unmagnifiedCannotPanAtAll() {
        // At 1x the image already fits. Any offset is the user dragging the
        // picture out of a frame it exactly fills.
        assertEquals(Offset.Zero, clampPan(Offset(500f, 500f), 1f, w, h))
        assertEquals(Offset.Zero, clampPan(Offset(-999f, 42f), 0.5f, w, h))
    }

    @Test
    fun theOverhangIsTheTravel() {
        // At 2x the image is twice the viewport, so it overhangs by half the
        // viewport in each direction — and that is exactly how far it may move.
        val clamped = clampPan(Offset(10_000f, 10_000f), 2f, w, h)
        assertEquals(w * 0.5f, clamped.x, 0.01f)
        assertEquals(h * 0.5f, clamped.y, 0.01f)
    }

    @Test
    fun clampingIsSymmetric() {
        val positive = clampPan(Offset(10_000f, 10_000f), 3f, w, h)
        val negative = clampPan(Offset(-10_000f, -10_000f), 3f, w, h)
        assertEquals(positive.x, -negative.x, 0.01f)
        assertEquals(positive.y, -negative.y, 0.01f)
    }

    @Test
    fun aPanInsideTheBoundsIsUntouched() {
        val desired = Offset(100f, 200f)
        assertEquals(desired, clampPan(desired, 4f, w, h))
    }

    @Test
    fun theImageAlwaysStillCoversTheViewport() {
        // The property that matters is not "the offset is small". It is that at
        // maximum pan the magnified image still covers the viewport edge to edge
        // — no empty band appears beside the photograph.
        //
        // `graphicsLayer` scales about the centre, so at scale s the image spans
        // [-s*w/2 + t, s*w/2 + t] against a viewport of [-w/2, w/2].
        listOf(1.1f, 1.5f, 2f, 3f, 4f).forEach { scale ->
            listOf(1f, -1f).forEach { direction ->
                val t = clampPan(Offset(1e6f * direction, 1e6f * direction), scale, w, h)

                val left = -scale * w / 2f + t.x
                val right = scale * w / 2f + t.x
                assertTrue("at ${scale}x a gap opens on the left: $left", left <= -w / 2f + 0.01f)
                assertTrue("at ${scale}x a gap opens on the right: $right", right >= w / 2f - 0.01f)

                val top = -scale * h / 2f + t.y
                val bottom = scale * h / 2f + t.y
                assertTrue("at ${scale}x a gap opens on top: $top", top <= -h / 2f + 0.01f)
                assertTrue("at ${scale}x a gap opens below: $bottom", bottom >= h / 2f - 0.01f)
            }
        }
    }

    @Test
    fun positionAndDurationReadTogether() {
        assertEquals("0:30 / 2:00", positionLabel(30_000L, 120_000L))
        assertEquals("1:00:00 / 2:00:00", positionLabel(3_600_000L, 7_200_000L))
    }

    @Test
    fun anUnknownDurationShowsThePositionAlone() {
        // Inventing a total would be the `--:--` mistake in a second place.
        assertEquals("0:30", positionLabel(30_000L, 0L))
        assertEquals("0:00", positionLabel(0L, 0L))
    }

    @Test
    fun aNegativePositionReadsAsZero() {
        // ExoPlayer reports a negative position before it is prepared.
        assertEquals("0:00 / 1:00", positionLabel(-5L, 60_000L))
    }

    @Test
    fun clampSeekStillClampsAtBothEnds() {
        // P1-8's rule, unchanged by P2-8 and asserted here so the pager work
        // cannot quietly alter it.
        assertEquals(0L, clampSeek(1_000L, -10_000L, 60_000L))
        assertEquals(60_000L, clampSeek(55_000L, 10_000L, 60_000L))
        assertEquals(20_000L, clampSeek(10_000L, 10_000L, 60_000L))
    }

    @Test
    fun clampSeekWithAnUnknownDurationDoesNotWrap() {
        assertEquals(20_000L, clampSeek(10_000L, 10_000L, 0L))
        assertEquals(0L, clampSeek(1_000L, -10_000L, 0L))
    }

    // --- viewer-paging-gestures ---------------------------------------------
    //
    // The arbitration between the pager, the pan and the dismiss is three pure
    // predicates and a latch. The latch needs a device; the predicates do not, and
    // they are where being wrong produces the bug this change exists to fix — a
    // swipe that turns no page and moves no image.

    @Test
    fun anUnmagnifiedDragBelongsToThePager() {
        // The reported bug in one assertion: at fit scale there is no pan to give,
        // so a horizontal drag is the pager's and nothing may consume it.
        assertEquals(DragIntent.PAGE, dragIntent(Offset(60f, 4f), magnified = false))
        assertTrue(handsOffToPager(Offset.Zero, deltaX = -30f, scale = 1f, width = w))
    }

    @Test
    fun aVerticalDragOnAnUnmagnifiedImageDismisses() {
        assertEquals(DragIntent.DISMISS, dragIntent(Offset(4f, 60f), magnified = false))
    }

    @Test
    fun magnificationOutranksTheAxis() {
        // Whichever way it went. `media-viewer` already required that a vertical
        // drag pans a magnified image, and the dismiss must not take that away.
        assertEquals(DragIntent.PAN, dragIntent(Offset(4f, 60f), magnified = true))
        assertEquals(DragIntent.PAN, dragIntent(Offset(60f, 4f), magnified = true))
    }

    @Test
    fun aPanTravellingTowardsABoundKeepsPanning() {
        // Not yet resting on anything: this is the ordinary middle of a pan.
        assertFalse(handsOffToPager(Offset.Zero, deltaX = 20f, scale = 2f, width = w))
    }

    @Test
    fun aPanPushingIntoABoundHandsOff() {
        val maxX = w * (2f - 1f) / 2f
        assertTrue(handsOffToPager(Offset(maxX, 0f), deltaX = 20f, scale = 2f, width = w))
        assertTrue(handsOffToPager(Offset(-maxX, 0f), deltaX = -20f, scale = 2f, width = w))
    }

    @Test
    fun aPanLeavingABoundDoesNotHandOff() {
        // At the bound but travelling back into the picture. Handing this over
        // would make a magnified image impossible to pan away from its own edge.
        val maxX = w * (2f - 1f) / 2f
        assertFalse(handsOffToPager(Offset(maxX, 0f), deltaX = -20f, scale = 2f, width = w))
        assertFalse(handsOffToPager(Offset(-maxX, 0f), deltaX = 20f, scale = 2f, width = w))
    }

    @Test
    fun aVerticalPanAtAHorizontalBoundDoesNotHandOff() {
        // The spec's own scenario: resting against the left bound and dragging
        // straight down must pan, not turn the page.
        val maxX = w * (2f - 1f) / 2f
        assertFalse(handsOffToPager(Offset(maxX, 0f), deltaX = 0f, scale = 2f, width = w))
    }

    @Test
    fun dismissTravelIsZeroUpwardsAndOneAtFullTravel() {
        assertEquals(0f, dismissTravel(-500f, h), 0f)
        assertEquals(0f, dismissTravel(0f, h), 0f)
        assertEquals(1f, dismissTravel(h, h), 0f)
        assertEquals(0.5f, dismissTravel(h * DISMISS_TRAVEL / 2f, h), 1e-4f)
    }

    @Test
    fun aShortFastFlickDismisses() {
        // Well under the distance, well over the speed. A distance-only threshold
        // rejects this, and rejecting it reads as the gesture not existing.
        val short = h * DISMISS_COMMIT / 4f
        assertTrue(dismissCommitted(short, speedY = DISMISS_VELOCITY + 1f, height = h))
    }

    @Test
    fun aLongSlowDragDismisses() {
        val far = h * DISMISS_COMMIT + 1f
        assertTrue(dismissCommitted(far, speedY = 0f, height = h))
    }

    @Test
    fun aShortSlowDragSpringsBack() {
        val short = h * DISMISS_COMMIT / 4f
        assertFalse(dismissCommitted(short, speedY = 10f, height = h))
    }

    @Test
    fun anUpwardFlickIsNotADismissal() {
        // Fast, and the wrong way. Dragging up is not leaving.
        assertFalse(dismissCommitted(-h, speedY = -5_000f, height = h))
    }
}
