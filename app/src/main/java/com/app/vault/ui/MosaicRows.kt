package com.app.vault.ui

/**
 * Packing items into justified rows — P2-8.
 *
 * The grid used to crop every thumbnail into a square. It did not have to:
 * `Thumbnailer.scale()` preserves aspect ratio by scaling the longest edge, so the
 * real shape is sitting in the decoded bitmap, and `aspectRatio(1f)` +
 * `ContentScale.Crop` was throwing it away at display time.
 *
 * **Pure arithmetic over ratios, with no Android type in sight**, so it is tested
 * on the JVM. Layout maths is where an off-by-one turns into a ragged edge that
 * nobody can screenshot — `FLAG_SECURE` returns black — so it is checked by
 * calculation, the same way the palette's contrast is.
 *
 * The algorithm is the standard justified-rows one: fill a row with items scaled
 * to a common target height until it would exceed the available width, then scale
 * the whole row so it fits the width exactly. The last row keeps the target height
 * rather than being stretched, because stretching four photographs across a full
 * width to finish a grid is how a mosaic starts looking like a mistake.
 */
object MosaicRows {

    /**
     * One item's place in the layout.
     *
     * @param index position in the input list, so the caller can map back to its
     *   own item without this file knowing what an item is.
     */
    data class Placed(val index: Int, val width: Float, val height: Float)

    /** A finished row: its items, and the height they share. */
    data class Row(val items: List<Placed>, val height: Float)

    /**
     * @param ratios width ÷ height for each item, in display order. A
     *   non-positive or non-finite ratio is treated as square — an item whose
     *   thumbnail failed to decode still takes a sane place rather than
     *   collapsing the row it is in.
     * @param availableWidth the width rows are justified to, in pixels or dp; the
     *   unit is the caller's and comes back unchanged.
     * @param targetHeight the height a row aims for before justification.
     * @param gutter space between items in a row.
     */
    fun pack(
        ratios: List<Float>,
        availableWidth: Float,
        targetHeight: Float,
        gutter: Float,
    ): List<Row> {
        if (ratios.isEmpty() || availableWidth <= 0f || targetHeight <= 0f) return emptyList()

        val safe = ratios.map { if (it.isFinite() && it > 0f) it else 1f }
        val rows = mutableListOf<Row>()
        var current = mutableListOf<Int>()

        fun widthOf(indices: List<Int>): Float =
            indices.sumOf { (safe[it] * targetHeight).toDouble() }.toFloat() +
                gutter * (indices.size - 1).coerceAtLeast(0)

        safe.indices.forEach { i ->
            val candidate = current + i
            if (current.isNotEmpty() && widthOf(candidate) > availableWidth) {
                rows += justify(current, safe, availableWidth, targetHeight, gutter)
                current = mutableListOf(i)
            } else {
                current = candidate.toMutableList()
            }
        }

        if (current.isNotEmpty()) {
            // The trailing row is laid out at the target height and left short.
            // Justifying it would blow one or two photographs across the whole
            // width, which reads as a layout bug rather than as the end of a list.
            val gutters = gutter * (current.size - 1).coerceAtLeast(0)
            val natural = current.sumOf { (safe[it] * targetHeight).toDouble() }.toFloat() + gutters
            rows += if (natural > availableWidth) {
                justify(current, safe, availableWidth, targetHeight, gutter)
            } else {
                Row(
                    current.map { Placed(it, safe[it] * targetHeight, targetHeight) },
                    targetHeight,
                )
            }
        }

        return rows
    }

    /**
     * Scales a row so its items plus gutters occupy exactly [availableWidth].
     *
     * The last item absorbs the rounding remainder. Distributing it evenly would
     * leave every item a fraction off and the row a fraction short, which is the
     * ragged right edge this whole function exists to avoid.
     */
    private fun justify(
        indices: List<Int>,
        ratios: List<Float>,
        availableWidth: Float,
        targetHeight: Float,
        gutter: Float,
    ): Row {
        val gutters = gutter * (indices.size - 1).coerceAtLeast(0)
        val contentWidth = (availableWidth - gutters).coerceAtLeast(1f)
        val ratioSum = indices.sumOf { ratios[it].toDouble() }.toFloat()
        val height = if (ratioSum > 0f) contentWidth / ratioSum else targetHeight

        var used = 0f
        val placed = indices.mapIndexed { position, index ->
            val isLast = position == indices.lastIndex
            val w = if (isLast) contentWidth - used else ratios[index] * height
            used += w
            Placed(index, w, height)
        }
        return Row(placed, height)
    }
}
