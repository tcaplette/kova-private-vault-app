package com.app.vault.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MosaicRows] — P2-8.
 *
 * `FLAG_SECURE` returns black to every screenshot, so a ragged row cannot be seen
 * in a capture. The layout is therefore checked as arithmetic: every justified row
 * fills the width, no item is dropped or duplicated, and the degenerate inputs a
 * vault actually contains — one item, a panorama, a failed thumbnail — do not
 * collapse a row.
 */
class MosaicRowsTest {

    private val width = 1000f
    private val target = 200f
    private val gutter = 4f

    private fun rowWidth(row: MosaicRows.Row): Float =
        row.items.sumOf { it.width.toDouble() }.toFloat() +
            gutter * (row.items.size - 1).coerceAtLeast(0)

    @Test
    fun anEmptyVaultProducesNoRows() {
        assertEquals(emptyList<MosaicRows.Row>(), MosaicRows.pack(emptyList(), width, target, gutter))
    }

    @Test
    fun oneItemKeepsItsOwnShape() {
        val rows = MosaicRows.pack(listOf(1.5f), width, target, gutter)
        val only = rows.single().items.single()
        assertEquals("a lone item is not stretched across the width", 300f, only.width, 0.01f)
        assertEquals(target, only.height, 0.01f)
    }

    @Test
    fun everyFullRowFillsTheWidthExactly() {
        // Ten 4:3 items at 200 high are ~267 wide, so rows of three overflow and
        // rows of three-minus-one justify — several full rows, one trailing.
        val rows = MosaicRows.pack(List(10) { 4f / 3f }, width, target, gutter)
        rows.dropLast(1).forEach { row ->
            assertEquals(
                "a justified row must fill the width",
                width,
                rowWidth(row),
                0.5f,
            )
        }
    }

    @Test
    fun noItemIsLostOrDuplicated() {
        val ratios = listOf(1.5f, 0.6f, 1f, 2.4f, 0.8f, 1.2f, 3f, 0.5f, 1f)
        val indices = MosaicRows.pack(ratios, width, target, gutter).flatMap { it.items }.map { it.index }
        assertEquals(ratios.indices.toList(), indices)
    }

    @Test
    fun theTrailingRowIsNotStretched() {
        // Two items that comfortably fit stay at the target height rather than
        // being blown across the full width to finish the grid.
        val rows = MosaicRows.pack(listOf(1f, 1f), width, target, gutter)
        val last = rows.last()
        assertEquals(target, last.height, 0.01f)
        assertTrue("a short trailing row must not fill the width", rowWidth(last) < width)
    }

    @Test
    fun aPanoramaGetsItsOwnRow() {
        // 6:1 at 200 high is 1200 wide, wider than the row. It must still be
        // placed, scaled down to fit, rather than overflowing or being dropped.
        val rows = MosaicRows.pack(listOf(6f), width, target, gutter)
        val only = rows.single().items.single()
        assertEquals(width, only.width, 0.5f)
        assertTrue("a very wide item is scaled down, not clipped", only.height < target)
    }

    @Test
    fun aVeryTallItemStaysInARow() {
        val rows = MosaicRows.pack(listOf(0.2f, 1f, 1f), width, target, gutter)
        assertTrue(rows.isNotEmpty())
        rows.flatMap { it.items }.forEach {
            assertTrue("every item has positive width", it.width > 0f)
            assertTrue("every item has positive height", it.height > 0f)
        }
    }

    @Test
    fun anUndecodableThumbnailIsTreatedAsSquare() {
        // A failed decode gives no ratio. It must not collapse the row it is in,
        // and must not produce NaN widths that propagate into layout.
        val rows = MosaicRows.pack(listOf(0f, Float.NaN, -3f, 1.5f), width, target, gutter)
        rows.flatMap { it.items }.forEach {
            assertTrue("width must be finite and positive: ${it.width}", it.width.isFinite() && it.width > 0f)
            assertTrue("height must be finite and positive: ${it.height}", it.height.isFinite() && it.height > 0f)
        }
    }

    @Test
    fun aZeroWidthViewportProducesNoRowsRatherThanCrashing() {
        assertEquals(emptyList<MosaicRows.Row>(), MosaicRows.pack(listOf(1f), 0f, target, gutter))
        assertEquals(emptyList<MosaicRows.Row>(), MosaicRows.pack(listOf(1f), width, 0f, gutter))
    }

    @Test
    fun gutterIsCountedBetweenItemsAndNotAtTheEnds() {
        // Six squares at 240 high: four fit the first row, which justifies, and
        // two trail. The assertion is on the *justified* row — a trailing row is
        // deliberately left at its natural width, so it is the wrong place to
        // check that gutters are counted n-1 times.
        val rows = MosaicRows.pack(List(6) { 1f }, width, 240f, gutter)
        assertTrue("expected a justified row plus a trailing one", rows.size >= 2)

        val justified = rows.first()
        val content = justified.items.sumOf { it.width.toDouble() }.toFloat()
        assertEquals(
            "content plus (n-1) gutters must equal the width",
            width - gutter * (justified.items.size - 1),
            content,
            0.5f,
        )
    }
}
