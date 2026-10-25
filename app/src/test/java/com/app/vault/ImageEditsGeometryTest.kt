package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The editor's geometry — `conventional-image-editor`.
 *
 * The half of `ImageEdits` that is arithmetic over fractions and touches no Android
 * type, so it is checkable without a device. `EditorTest` covers the half that needs
 * `Matrix`, `ColorMatrix` and a real `Bitmap`.
 *
 * These are the functions where being wrong is invisible in review and obvious in the
 * hand: a crop that inverts, a ratio that slides off the subject, a straighten that
 * leaves empty corners.
 */
class ImageEditsGeometryTest {

    // --- the straighten inset ------------------------------------------------

    @Test
    fun aLevelCropIsNotInset() {
        assertEquals(400 to 200, ImageEdits.outputSize(400f, 200f, 0f))
    }

    @Test
    fun aStraightenInsetsRatherThanGrows() {
        // The behaviour this change inverted. The old `outputSize` returned the box
        // that *holds* the turned corners, so a straighten produced transparent
        // wedges and the user was sent to the crop tool to remove them.
        val turned = ImageEdits.outputSize(400f, 200f, 10f)
        assertTrue("a straighten must not grow the box: $turned", turned.first < 400)
        assertTrue("nor the height: $turned", turned.second < 200)
    }

    @Test
    fun theInsetKeepsTheCropsAspect() {
        val (w, h) = ImageEdits.insetForAngle(400f, 200f, 8f)
        assertEquals("the inset keeps the shape it was given", 2f, w / h, 0.01f)
    }

    @Test
    fun theInsetShrinksAsTheAngleGrows() {
        var previous = Float.MAX_VALUE
        for (angle in intArrayOf(0, 5, 10, 20, 30, 45)) {
            val (w, h) = ImageEdits.insetForAngle(400f, 200f, angle.toFloat())
            val area = w * h
            assertTrue("area must never grow: $angle° gave $area after $previous", area <= previous + 1f)
            assertTrue("and must stay positive at $angle°", w > 0f && h > 0f)
            previous = area
        }
    }

    @Test
    fun aQuarterTurnSwapsTheOutputRatherThanInsettingIt() {
        // A right angle introduces no corners to lose.
        assertEquals(200 to 400, ImageEdits.outputSize(400f, 200f, 0f, quarterTurns = 1))
        assertEquals(400 to 200, ImageEdits.outputSize(400f, 200f, 0f, quarterTurns = 2))
    }

    // --- the crop rectangle --------------------------------------------------

    private val whole = ImageEdits.Crop()

    @Test
    fun aTouchOnACornerTakesTheCorner() {
        assertEquals(ImageEdits.Grip.TOP_LEFT, ImageEdits.gripAt(whole, 0.01f, 0.01f, 0.05f))
        assertEquals(ImageEdits.Grip.BOTTOM_RIGHT, ImageEdits.gripAt(whole, 0.99f, 0.99f, 0.05f))
    }

    @Test
    fun aCornerWinsWhereItOverlapsAnEdge() {
        // A finger in the corner meant the corner, whichever hit area it also touched.
        assertEquals(ImageEdits.Grip.TOP_RIGHT, ImageEdits.gripAt(whole, 0.98f, 0.02f, 0.05f))
    }

    @Test
    fun aTouchNearButNotOnAHandleStillTakesIt() {
        // The whole point of a reach wider than the drawn mark.
        assertEquals(ImageEdits.Grip.LEFT, ImageEdits.gripAt(whole, 0.04f, 0.5f, 0.05f))
    }

    @Test
    fun aTouchInsideMovesTheRectangle() {
        assertEquals(ImageEdits.Grip.INSIDE, ImageEdits.gripAt(whole, 0.5f, 0.5f, 0.02f))
    }

    @Test
    fun aTouchOutsideTakesNothing() {
        val small = ImageEdits.Crop(0.4f, 0.4f, 0.6f, 0.6f)
        assertNull(ImageEdits.gripAt(small, 0.05f, 0.05f, 0.02f))
    }

    @Test
    fun aDragPastTheOppositeEdgeStopsAndDoesNotInvert() {
        val dragged = ImageEdits.dragged(whole, ImageEdits.Grip.LEFT, dx = 5f, dy = 0f, ratio = null)
        assertTrue("left must not pass right", dragged.left < dragged.right)
        assertEquals(
            "and must stop at the minimum",
            ImageEdits.MIN_CROP,
            dragged.right - dragged.left,
            0.001f,
        )
    }

    @Test
    fun aDragStopsAtTheImagesEdge() {
        val dragged = ImageEdits.dragged(whole, ImageEdits.Grip.RIGHT, dx = 5f, dy = 0f, ratio = null)
        assertEquals(1f, dragged.right, 0.001f)
    }

    @Test
    fun movingStopsAtTheEdgesRatherThanLeavingThem() {
        val small = ImageEdits.Crop(0.4f, 0.4f, 0.6f, 0.6f)
        val moved = ImageEdits.moved(small, dx = 5f, dy = -5f)
        assertEquals(0.8f, moved.left, 0.001f)
        assertEquals(1f, moved.right, 0.001f)
        assertEquals(0f, moved.top, 0.001f)
        assertEquals("moving must not resize", 0.2f, moved.bottom - moved.top, 0.001f)
    }

    @Test
    fun aDragUnderARatioKeepsTheRatio() {
        val start = ImageEdits.Crop(0.2f, 0.2f, 0.8f, 0.8f)
        val dragged = ImageEdits.dragged(
            start,
            ImageEdits.Grip.BOTTOM_RIGHT,
            dx = -0.1f,
            dy = -0.05f,
            ratio = 1f,
        )
        assertEquals(
            "a square under the finger stays square",
            1f,
            (dragged.right - dragged.left) / (dragged.bottom - dragged.top),
            0.02f,
        )
    }

    // --- the aspect ratio ----------------------------------------------------

    @Test
    fun aRatioHoldsTheCropsCentre() {
        // The behaviour this change inverted. Anchoring the top-left corner slid the
        // frame off the subject on every ratio tap.
        val offCentre = ImageEdits.Crop(0.5f, 0.1f, 0.9f, 0.5f)
        val cx = (offCentre.left + offCentre.right) / 2f
        val cy = (offCentre.top + offCentre.bottom) / 2f

        val square = ImageEdits.heldTo(offCentre, ImageEdits.Aspect.Square)

        assertEquals(cx, (square.left + square.right) / 2f, 0.01f)
        assertEquals(cy, (square.top + square.bottom) / 2f, 0.01f)
    }

    @Test
    fun aRatioIsTheRatioAskedFor() {
        val square = ImageEdits.heldTo(ImageEdits.Crop(0f, 0f, 0.8f, 0.4f), ImageEdits.Aspect.Square)
        assertEquals(1f, (square.right - square.left) / (square.bottom - square.top), 0.02f)

        val wide = ImageEdits.heldTo(ImageEdits.Crop(0.2f, 0.2f, 0.8f, 0.8f), ImageEdits.Aspect.SixteenNine)
        assertEquals(16f / 9f, (wide.right - wide.left) / (wide.bottom - wide.top), 0.02f)
    }

    @Test
    fun aRatioThatWouldOverhangIsMovedBackInside() {
        // Against the right edge: the result must be the ratio asked for, inside the
        // image, rather than the ratio clipped to fit where it was.
        val edge = ImageEdits.Crop(0.75f, 0.4f, 1f, 0.6f)
        val wide = ImageEdits.heldTo(edge, ImageEdits.Aspect.SixteenNine)

        assertTrue("inside the left edge", wide.left >= -0.001f)
        assertTrue("inside the right edge", wide.right <= 1.001f)
        assertEquals(16f / 9f, (wide.right - wide.left) / (wide.bottom - wide.top), 0.02f)
    }

    @Test
    fun theFreeRatioLeavesTheRectangleAlone() {
        // Releasing a constraint must not also reset the framing made under it.
        val framed = ImageEdits.Crop(0.3f, 0.1f, 0.7f, 0.9f)
        assertEquals(framed, ImageEdits.heldTo(framed, ImageEdits.Aspect.Free))
    }

    // --- markup --------------------------------------------------------------

    @Test
    fun theEraserTakesTheStrokeUnderTheTouch() {
        val strokes = listOf(
            ImageEdits.Stroke(points = listOf(0.1f to 0.1f, 0.2f to 0.2f)),
            ImageEdits.Stroke(points = listOf(0.8f to 0.8f, 0.9f to 0.9f)),
        )
        assertEquals(0, ImageEdits.strokeAt(strokes, 0.1f, 0.1f, 0.03f))
        assertEquals(1, ImageEdits.strokeAt(strokes, 0.89f, 0.89f, 0.03f))
    }

    @Test
    fun theEraserTakesTheTopmostOfOverlappingStrokes() {
        val strokes = listOf(
            ImageEdits.Stroke(points = listOf(0.5f to 0.5f)),
            ImageEdits.Stroke(points = listOf(0.5f to 0.5f)),
        )
        assertEquals(1, ImageEdits.strokeAt(strokes, 0.5f, 0.5f, 0.03f))
    }

    @Test
    fun theEraserTakesNothingWhereThereIsNothing() {
        val strokes = listOf(ImageEdits.Stroke(points = listOf(0.1f to 0.1f)))
        assertNull(ImageEdits.strokeAt(strokes, 0.9f, 0.9f, 0.03f))
    }

    // --- the stack ------------------------------------------------------------

    @Test
    fun theNewOperationsCountAsEdits() {
        assertTrue(ImageEdits.Stack().isEmpty)
        assertTrue("a quarter turn is an edit", !ImageEdits.Stack(quarterTurns = 1).isEmpty)
        assertTrue("a flip is an edit", !ImageEdits.Stack(flipHorizontal = true).isEmpty)
        assertTrue("and so is the other one", !ImageEdits.Stack(flipVertical = true).isEmpty)
    }

    @Test
    fun aFilterAtZeroStrengthIsNotAnEdit() {
        // Nothing reaches the pixels, so saving would re-encode for no change.
        val none = ImageEdits.Stack(filter = ImageEdits.Filter.Warm, filterIntensity = 0f)
        assertTrue(none.isEmpty)
        assertTrue(!ImageEdits.Stack(filter = ImageEdits.Filter.Warm).isEmpty)
    }

    @Test
    fun theStrokeWidthsAreDistinctFractionsOfTheLongestEdge() {
        val widths = ImageEdits.Width.entries.map { it.fraction }
        assertEquals(widths.size, widths.distinct().size)
        assertTrue(widths.all { it > 0f && it < 0.1f })
        assertNotNull(ImageEdits.Width.entries.firstOrNull { abs(it.fraction - 0.006f) < 1e-6f })
    }
}
