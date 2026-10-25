package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The safety property `pattern-cover-trigger`'s whole non-consuming design rests on —
 * a straight drag across the pattern grid never reaches more than three of its nine
 * nodes — verified computationally rather than asserted from the arithmetic alone.
 *
 * ***Why a sweep, not a hand proof.*** The claim is about every line through the
 * grid, not a handful of hand-picked ones, and a closed-form proof over an arbitrary
 * radius is more code to get subtly wrong than a fine sweep is. What is asserted here
 * is exactly what [gridNodeAt]'s doc comment claims: at [HIT_RADIUS_FRACTION] the
 * bound holds everywhere the sweep looks, and the bound's critical point — the
 * smallest radius at which it stops holding — sits with real margin above it.
 *
 * A cell is 1.0 units square; node centres sit at `(col + 0.5, row + 0.5)`.
 */
class GridGeometryTest {

    private val nodeCenters: List<Pair<Float, Float>> =
        (0 until 3).flatMap { row -> (0 until 3).map { col -> (col + 0.5f) to (row + 0.5f) } }

    /** Perpendicular distance from `(px, py)` to the line through `(ax, ay)` with unit direction `(dx, dy)`. */
    private fun distanceToLine(px: Float, py: Float, ax: Float, ay: Float, dx: Float, dy: Float): Float {
        val vx = px - ax
        val vy = py - ay
        val proj = vx * dx + vy * dy
        val perpX = vx - proj * dx
        val perpY = vy - proj * dy
        return hypot(perpX, perpY)
    }

    private fun touchCount(ax: Float, ay: Float, dx: Float, dy: Float, radius: Float): Int =
        nodeCenters.count { (nx, ny) -> distanceToLine(nx, ny, ax, ay, dx, dy) <= radius }

    /**
     * The claim itself, at the radius [HIT_RADIUS_FRACTION] actually ships with. Swept
     * over every angle in quarter-degree steps and every offset in hundredth-of-a-cell
     * steps across the grid's full extent — 720 × 401 ≈ 289,000 lines, each checked
     * against all nine nodes.
     */
    @Test
    fun noStraightLineTouchesMoreThanThreeNodesAtTheShippedRadius() {
        val centerX = 1.5f
        val centerY = 1.5f
        var worst = 0
        var worstAngle = 0.0
        var worstOffset = 0f

        for (step in 0 until 720) {
            val angle = Math.PI * step / 720.0
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            // Perpendicular direction, to sweep the line's offset from the grid's centre.
            val px = -dy
            val py = dx
            var offsetSteps = -200
            while (offsetSteps <= 200) {
                val off = offsetSteps * 0.01f
                val ax = centerX + off * px
                val ay = centerY + off * py
                val count = touchCount(ax, ay, dx, dy, HIT_RADIUS_FRACTION)
                if (count > worst) {
                    worst = count
                    worstAngle = angle
                    worstOffset = off
                }
                offsetSteps++
            }
        }

        assertTrue(
            "a line touched $worst nodes at angle $worstAngle offset $worstOffset — " +
                "the non-consuming grid's whole safety argument assumes at most 3",
            worst <= 3,
        )
    }

    /**
     * The radius's margin, not just its correctness. If a future edit nudges
     * [HIT_RADIUS_FRACTION] upward, this fails before [noStraightLineTouchesMoreThanThreeNodesAtTheShippedRadius]
     * does, and says by how much room there was.
     */
    @Test
    fun theShippedRadiusHasRealMarginBelowTheCriticalPoint() {
        val centerX = 1.5f
        val centerY = 1.5f
        var smallestRadiusTouchingFour = Float.MAX_VALUE

        for (step in 0 until 720) {
            val angle = Math.PI * step / 720.0
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            val px = -dy
            val py = dx
            var offsetSteps = -200
            while (offsetSteps <= 200) {
                val off = offsetSteps * 0.01f
                val ax = centerX + off * px
                val ay = centerY + off * py
                val fourthClosest = nodeCenters
                    .map { (nx, ny) -> distanceToLine(nx, ny, ax, ay, dx, dy) }
                    .sorted()[3]
                if (fourthClosest < smallestRadiusTouchingFour) {
                    smallestRadiusTouchingFour = fourthClosest
                }
                offsetSteps++
            }
        }

        // Found by the same sweep, offline, at ≈0.354 (refined ≈0.3539). Asserted with
        // slack either side so a finer future sweep tightening the number by a
        // thousandth does not fail this on its own.
        assertTrue(
            "critical radius drifted to $smallestRadiusTouchingFour — re-derive HIT_RADIUS_FRACTION",
            smallestRadiusTouchingFour in 0.34f..0.37f,
        )
        assertTrue(
            "shipped radius has less than 30% margin below the critical point",
            HIT_RADIUS_FRACTION < smallestRadiusTouchingFour * 0.7f,
        )
    }

    // --- gridNodeAt itself, not just the geometry it is built on -------------

    @Test
    fun eachCellsCentreHitsExactlyItsOwnNode() {
        val width = 300f
        val height = 300f
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val x = (col + 0.5f) * (width / 3f)
                val y = (row + 0.5f) * (height / 3f)
                assertEquals(row * 3 + col, gridNodeAt(x, y, width, height))
            }
        }
    }

    @Test
    fun aCellCornerHitsNoNode() {
        // Corners are equidistant from up to four node centres, at roughly 0.707 of a
        // cell — far outside HIT_RADIUS_FRACTION.
        assertNull(gridNodeAt(0f, 0f, 300f, 300f))
        assertNull(gridNodeAt(300f, 300f, 300f, 300f))
    }

    @Test
    fun aZeroOrNegativeSizeHitsNothing() {
        assertNull(gridNodeAt(10f, 10f, 0f, 0f))
        assertNull(gridNodeAt(10f, 10f, -5f, 100f))
    }

    // --- `cover-hold-dots` D1: the 12 hold areas ---------------------------------

    /** Every cell's centre answers that cell, in reading order, in portrait and in landscape. */
    @Test
    fun everyHoldCellsCentreIsThatCell() {
        listOf(1080f to 2400f, 2400f to 1080f).forEach { (width, height) ->
            for (row in 0 until HOLD_ROWS) {
                for (col in 0 until HOLD_COLUMNS) {
                    val x = (col + 0.5f) * width / HOLD_COLUMNS
                    val y = (row + 0.5f) * height / HOLD_ROWS
                    assertEquals("${width}x$height ($col, $row)", row * HOLD_COLUMNS + col, holdAreaAt(x, y, width, height))
                }
            }
        }
    }

    /** A cell's own edges belong to it; the next pixel belongs to the next cell. */
    @Test
    fun holdCellsMeetAtTheirEdges() {
        val width = 1080f
        val height = 2400f
        val w = width / HOLD_COLUMNS
        val h = height / HOLD_ROWS
        assertEquals(0, holdAreaAt(0f, 0f, width, height))
        assertEquals(0, holdAreaAt(w - 0.5f, h - 0.5f, width, height))
        assertEquals(1, holdAreaAt(w + 0.5f, 0f, width, height))
        assertEquals(3, holdAreaAt(0f, h + 0.5f, width, height))
        assertEquals(4, holdAreaAt(w + 0.5f, h + 0.5f, width, height))
    }

    /** Corners land in the corner cells, and a touch on or past the far edge is clamped in. */
    @Test
    fun holdCornersAndOutsideAreClamped() {
        listOf(1080f to 2400f, 2400f to 1080f).forEach { (width, height) ->
            assertEquals(0, holdAreaAt(0f, 0f, width, height))
            assertEquals(2, holdAreaAt(width, 0f, width, height))
            assertEquals(9, holdAreaAt(0f, height, width, height))
            assertEquals(11, holdAreaAt(width, height, width, height))
            assertEquals(0, holdAreaAt(-50f, -50f, width, height))
            assertEquals(11, holdAreaAt(width + 50f, height + 50f, width, height))
            assertEquals(5, holdAreaAt(width + 50f, height * 0.3f, width, height))
        }
        assertEquals("no size: the first cell, never a crash", 0, holdAreaAt(10f, 10f, 0f, 0f))
    }

    /** Landscape is measured against its own bounds: the top-left of the rotated screen is cell 0. */
    @Test
    fun theCellsFollowRotation() {
        // A point in the top-left cell of a landscape screen lies in portrait's top row, middle
        // column territory by raw coordinates — the bounds, not the device, decide.
        assertEquals(0, holdAreaAt(700f, 200f, 2400f, 1080f))
        assertEquals(1, holdAreaAt(700f, 200f, 1080f, 2400f))
    }

    /** Every place in words, reading order. */
    @Test
    fun everyHoldPlaceIsNamed() {
        val expected = listOf(
            "Top row, left", "Top row, centre", "Top row, right",
            "Second row, left", "Second row, centre", "Second row, right",
            "Third row, left", "Third row, centre", "Third row, right",
            "Bottom row, left", "Bottom row, centre", "Bottom row, right",
        )
        assertEquals(HOLD_AREAS, expected.size)
        (0 until HOLD_AREAS).forEach { assertEquals(expected[it], holdPlace(TestWords, it)) }
    }
}
