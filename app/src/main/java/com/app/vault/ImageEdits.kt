package com.app.vault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.StringRes
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What the editor does to an image, as data — `viewer-chrome-and-editor`.
 *
 * **A stack of values, not a sequence of bitmaps.** Every operation the editor offers
 * is recorded here and applied once, at the end, to the full-resolution source. That
 * is not tidiness: applying crop, then straighten, then a colour pass to a
 * many-megapixel bitmap one at a time allocates a full copy per step and resamples
 * the pixels twice, so the saved image is softer than the preview the user approved.
 * Composed, crop and straighten are one `Matrix` and one sampling.
 *
 * **The preview and the save run the same code.** The editor previews by applying
 * this to a screen-sized bitmap and saves by applying it to the source; there is no
 * second implementation to disagree with the first.
 *
 * **`android.graphics`, and nothing else.** `Matrix`, `ColorMatrix`, `Canvas` and
 * `Bitmap` are the whole of it. An editing library here would be a dependency and an
 * I1 clearance run to draw what the framework already draws — the trade §9 refused
 * for `material-icons-core`.
 *
 * Nothing in this file touches the vault, a key, or a file. It takes a bitmap and
 * returns a bitmap, which is what lets the geometry be tested without a device.
 */
object ImageEdits {

    /**
     * How far a straighten reaches, in degrees either side of level.
     *
     * ±15° was too tight to level a horizon photographed off a moving thing; ±45° is
     * what the operation has everywhere it exists, and past 45° the user wanted a
     * quarter turn, which is now offered beside it.
     */
    const val MAX_STRAIGHTEN = 45f

    /** The smallest a crop rectangle may be dragged, as a fraction of the source. */
    const val MIN_CROP = 0.08f


    /**
     * A crop, in **fractions of the source**, not pixels.
     *
     * Fractions because the editor previews at screen resolution and saves at full
     * resolution: a rect in preview pixels would crop a different region of the
     * source, and the user would get an image that is not the one they framed.
     */
    data class Crop(
        val left: Float = 0f,
        val top: Float = 0f,
        val right: Float = 1f,
        val bottom: Float = 1f,
    ) {
        val isWhole: Boolean get() = left == 0f && top == 0f && right == 1f && bottom == 1f

        /** Clamped into the source and never inverted, so a drag past an edge is not a defect. */
        fun sane(): Crop = Crop(
            left = left.coerceIn(0f, 1f),
            top = top.coerceIn(0f, 1f),
            right = right.coerceIn(0f, 1f),
            bottom = bottom.coerceIn(0f, 1f),
        ).let { c ->
            Crop(min(c.left, c.right), min(c.top, c.bottom), max(c.left, c.right), max(c.top, c.bottom))
        }
    }

    /**
     * A fixed shape a crop is held to, or [Free] for none.
     *
     * The set is the one the editor offers and the whole of it: an aspect ratio the
     * user cannot pick is a value nothing can produce.
     */
    enum class Aspect(@StringRes val label: Int, val ratio: Float?) {
        Free(R.string.aspect_free, null),
        Square(R.string.aspect_1_1, 1f),
        FourThree(R.string.aspect_4_3, 4f / 3f),
        ThreeTwo(R.string.aspect_3_2, 3f / 2f),
        SixteenNine(R.string.aspect_16_9, 16f / 9f),
    }

    /**
     * A named look — a preset of the same colour matrix the sliders build.
     *
     * A filter is not a separate mechanism from the light and colour controls; it is
     * a position on them with a name. Keeping it that way means one matrix reaches
     * the pixels however the user got there, and a filter plus a slider compose
     * instead of fighting.
     */
    enum class Filter(@StringRes val label: Int) {
        None(R.string.filter_original),
        Mono(R.string.filter_mono),
        Warm(R.string.filter_warm),
        Cool(R.string.filter_cool),
        Fade(R.string.filter_fade),
        Punch(R.string.filter_punch),
    }

    /**
     * The stroke widths markup offers.
     *
     * Fractions of the longest edge, for [Stroke.width]'s reason: a stroke drawn on a
     * preview has to mean the same thickness on the full-resolution save.
     */
    enum class Width(@StringRes val label: Int, val fraction: Float) {
        Fine(R.string.width_fine, 0.003f),
        Medium(R.string.width_medium, 0.006f),
        Bold(R.string.width_bold, 0.012f),
    }

    /** One markup stroke: a colour, a width in source fractions, and the path it took. */
    data class Stroke(
        val colour: Int = Color.RED,
        /** A fraction of the image's longest edge, so a stroke scales with the source. */
        val width: Float = Width.Medium.fraction,
        /** Points in fractions of the source, for [Crop]'s reason. */
        val points: List<Pair<Float, Float>> = emptyList(),
    )

    /**
     * Everything the editor has been told to do, and the only thing it saves from.
     *
     * An empty stack is the identity: [apply] returns the bitmap it was given, so
     * saving without editing is not a lossy no-op.
     */
    data class Stack(
        val crop: Crop = Crop(),
        /**
         * Straighten, in degrees, within ±[MAX_STRAIGHTEN].
         *
         * A correction judged against the picture rather than a rotation: the
         * quarter turns below are the rotation, and the item's own stored rotation
         * is a third thing this does not touch.
         */
        val straighten: Float = 0f,
        /** Quarter turns clockwise, 0..3. Composed into the same single sampling. */
        val quarterTurns: Int = 0,
        val flipHorizontal: Boolean = false,
        val flipVertical: Boolean = false,
        val aspect: Aspect = Aspect.Free,
        val filter: Filter = Filter.None,
        /**
         * How much of [filter] reaches the picture, 0..1.
         *
         * The filter's matrix interpolated towards identity, not a second mechanism:
         * a strength control that worked another way would be a second place for a
         * look to be decided.
         */
        val filterIntensity: Float = 1f,
        /** −1..1, zero being untouched, for each of the four. */
        val brightness: Float = 0f,
        val contrast: Float = 0f,
        val saturation: Float = 0f,
        val warmth: Float = 0f,
        val strokes: List<Stroke> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = crop.isWhole && straighten == 0f && quarterTurns == 0 &&
                !flipHorizontal && !flipVertical &&
                (filter == Filter.None || filterIntensity == 0f) &&
                brightness == 0f && contrast == 0f && saturation == 0f && warmth == 0f &&
                strokes.isEmpty()
    }

    /**
     * [source] with [stack] applied — one sampling, one colour pass, one draw.
     *
     * Returns [source] itself for an empty stack, so the caller can tell "nothing was
     * edited" from "this is the edit" by identity, and so a save with no edits does
     * not re-encode for nothing.
     *
     * **Crop and straighten are composed before anything is sampled.** The output
     * size is the crop's size measured after the straighten, and the matrix maps the
     * source into it in one step. Straighten-then-crop as two bitmaps would resample
     * every pixel twice for the same result.
     */
    fun apply(source: Bitmap, stack: Stack): Bitmap {
        if (stack.isEmpty) return source

        val crop = stack.crop.sane()
        val cropped = RectF(
            crop.left * source.width,
            crop.top * source.height,
            crop.right * source.width,
            crop.bottom * source.height,
        )
        if (cropped.width() < 1f || cropped.height() < 1f) return source

        val plan = geometry(source.width, source.height, stack)
        val result = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        drawOnto(Canvas(result), source, stack, plan.matrix)
        return result
    }

    /**
     * [source] drawn through [matrix] with [stack]'s colour and markup — **the one
     * draw the preview and the save both make.**
     *
     * The preview passes a screen canvas and a matrix scaled to fit it; the save
     * passes a canvas over the full-resolution output. Neither owns a second
     * implementation, which is the property `image-editing` requires and the reason
     * the preview may stop producing bitmaps without the two drifting.
     */
    internal fun drawOnto(canvas: Canvas, source: Bitmap, stack: Stack, matrix: Matrix) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            val matrixFor = colourMatrix(stack)
            if (matrixFor != null) colorFilter = ColorMatrixColorFilter(matrixFor)
        }
        canvas.drawBitmap(source, matrix, paint)

        // Markup last and unfiltered: a stroke the user drew in red is red, not red
        // through whatever the colour pass is doing to the photograph under it.
        drawStrokes(canvas, stack.strokes, source.width, source.height, matrix)
    }

    /**
     * The index of the topmost stroke passing within [reach] of ([x], [y]), or null.
     *
     * Fractions of the source throughout, like everything else the editor records.
     * Topmost, because the strokes are drawn in order and the eraser should take the
     * one the user can see.
     */
    internal fun strokeAt(strokes: List<Stroke>, x: Float, y: Float, reach: Float): Int? =
        strokes.indices.lastOrNull { i ->
            strokes[i].points.any { (px, py) ->
                val dx = px - x
                val dy = py - y
                dx * dx + dy * dy <= reach * reach
            }
        }

    /**
     * What a touch on the crop overlay has taken hold of.
     *
     * [INSIDE] moves the rectangle; the eight others resize it. The set is closed and
     * the corners come first, because a corner takes precedence where its hit area
     * overlaps an edge's — a finger in the corner meant the corner.
     */
    enum class Grip { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, TOP, BOTTOM, LEFT, RIGHT, INSIDE }

    /**
     * Which part of [crop] a touch at ([x], [y]) has taken, all in fractions of the
     * image, or null for a touch outside the rectangle and away from its edges.
     *
     * [reach] is the hit radius **in the same fractions**, and the caller is expected
     * to pass one derived from a real touch target rather than from the size the
     * handles are drawn at. A crop handle that needs a precise finger is the single
     * most common way this control is got wrong.
     */
    internal fun gripAt(crop: Crop, x: Float, y: Float, reach: Float): Grip? {
        val c = crop.sane()
        val nearLeft = abs(x - c.left) <= reach
        val nearRight = abs(x - c.right) <= reach
        val nearTop = abs(y - c.top) <= reach
        val nearBottom = abs(y - c.bottom) <= reach
        val withinX = x >= c.left - reach && x <= c.right + reach
        val withinY = y >= c.top - reach && y <= c.bottom + reach

        return when {
            nearLeft && nearTop -> Grip.TOP_LEFT
            nearRight && nearTop -> Grip.TOP_RIGHT
            nearLeft && nearBottom -> Grip.BOTTOM_LEFT
            nearRight && nearBottom -> Grip.BOTTOM_RIGHT
            nearLeft && withinY -> Grip.LEFT
            nearRight && withinY -> Grip.RIGHT
            nearTop && withinX -> Grip.TOP
            nearBottom && withinX -> Grip.BOTTOM
            x > c.left && x < c.right && y > c.top && y < c.bottom -> Grip.INSIDE
            else -> null
        }
    }

    /**
     * [crop] after a drag of ([dx], [dy]) on [grip], clamped into the image and never
     * inverted or collapsed.
     *
     * A drag past the opposite edge stops at [MIN_CROP] rather than turning the
     * rectangle inside out — the user dragged too far, they did not ask for a
     * negative crop.
     *
     * With a [ratio] the two axes move together, so a rectangle under a fixed shape
     * stays that shape however it is dragged. The driving axis is the one the grip
     * names: a side handle drives its own axis and the other follows.
     */
    internal fun dragged(crop: Crop, grip: Grip, dx: Float, dy: Float, ratio: Float?): Crop {
        val c = crop.sane()
        if (grip == Grip.INSIDE) return moved(c, dx, dy)

        var left = c.left
        var right = c.right
        var top = c.top
        var bottom = c.bottom

        when (grip) {
            Grip.TOP_LEFT, Grip.BOTTOM_LEFT, Grip.LEFT -> left = (c.left + dx)
                .coerceIn(0f, c.right - MIN_CROP)
            Grip.TOP_RIGHT, Grip.BOTTOM_RIGHT, Grip.RIGHT -> right = (c.right + dx)
                .coerceIn(c.left + MIN_CROP, 1f)
            else -> Unit
        }
        when (grip) {
            Grip.TOP_LEFT, Grip.TOP_RIGHT, Grip.TOP -> top = (c.top + dy)
                .coerceIn(0f, c.bottom - MIN_CROP)
            Grip.BOTTOM_LEFT, Grip.BOTTOM_RIGHT, Grip.BOTTOM -> bottom = (c.bottom + dy)
                .coerceIn(c.top + MIN_CROP, 1f)
            else -> Unit
        }

        val dragged = Crop(left, top, right, bottom)
        return if (ratio == null) dragged else dragged.heldToRatio(grip, ratio)
    }

    /** [crop] moved by ([dx], [dy]), stopping at the image's edges rather than leaving them. */
    internal fun moved(crop: Crop, dx: Float, dy: Float): Crop {
        val c = crop.sane()
        val width = c.right - c.left
        val height = c.bottom - c.top
        val left = (c.left + dx).coerceIn(0f, 1f - width)
        val top = (c.top + dy).coerceIn(0f, 1f - height)
        return Crop(left, top, left + width, top + height)
    }

    /**
     * This rectangle brought to [ratio] while [grip] was being dragged, holding the
     * corner opposite the one under the finger.
     *
     * The opposite corner and not the centre, here and only here: while a handle is
     * under the finger, the corner across from it is the fixed point the user is
     * sizing against. [heldTo] — a ratio *chosen* rather than dragged — holds the
     * centre instead, and the difference is the difference between sizing and
     * reframing.
     */
    private fun Crop.heldToRatio(grip: Grip, ratio: Float): Crop {
        val width = right - left
        val height = bottom - top
        // The dragged axis drives; a corner drives on width, which is the axis a
        // finger moving diagonally is most likely to have meant.
        val drivesWidth = grip != Grip.TOP && grip != Grip.BOTTOM
        var w = if (drivesWidth) width else height * ratio
        var h = if (drivesWidth) width / ratio else height
        w = w.coerceAtLeast(MIN_CROP)
        h = h.coerceAtLeast(MIN_CROP)

        val anchorRight = grip == Grip.LEFT || grip == Grip.TOP_LEFT || grip == Grip.BOTTOM_LEFT
        val anchorBottom = grip == Grip.TOP || grip == Grip.TOP_LEFT || grip == Grip.TOP_RIGHT

        var l = if (anchorRight) right - w else left
        var t = if (anchorBottom) bottom - h else top
        // Pushed back inside rather than clipped: a ratio that will not fit where it
        // was dragged still has to be the ratio that was asked for.
        if (w > 1f) { w = 1f; h = (w / ratio).coerceAtMost(1f) }
        if (h > 1f) { h = 1f; w = (h * ratio).coerceAtMost(1f) }
        l = l.coerceIn(0f, 1f - w)
        t = t.coerceIn(0f, 1f - h)
        return Crop(l, t, l + w, t + h)
    }

    /**
     * [crop] brought to [aspect] **about its own centre**.
     *
     * The centre and not the top-left corner, which is what this did until
     * `conventional-image-editor`. The old comment argued that a user who framed the
     * left edge has framed it; in practice a ratio tap is a decision about the
     * subject, the subject is in the middle, and anchoring a corner slides the frame
     * off it on every tap. The result is pushed back inside the image if it overhangs
     * rather than being clipped, so the ratio asked for is the ratio produced.
     *
     * [Aspect.Free] returns the rectangle untouched: releasing a constraint must not
     * also reset the framing the user made under it.
     */
    internal fun heldTo(crop: Crop, aspect: Aspect): Crop {
        val ratio = aspect.ratio ?: return crop
        val c = crop.sane()
        val cx = (c.left + c.right) / 2f
        val cy = (c.top + c.bottom) / 2f

        // Sized from the area it already occupies, so a ratio change is a reshaping
        // rather than a resize to some fixed fraction of the picture.
        val area = (c.right - c.left) * (c.bottom - c.top)
        var w = kotlin.math.sqrt(area * ratio)
        var h = w / ratio
        if (w > 1f) { w = 1f; h = w / ratio }
        if (h > 1f) { h = 1f; w = h * ratio }
        w = w.coerceAtLeast(MIN_CROP)
        h = h.coerceAtLeast(MIN_CROP)

        val left = (cx - w / 2f).coerceIn(0f, 1f - w)
        val top = (cy - h / 2f).coerceIn(0f, 1f - h)
        return Crop(left, top, left + w, top + h)
    }

    /** The one mapping from source to output, and the size of the output it fills. */
    data class Geometry(val matrix: Matrix, val width: Int, val height: Int)

    /**
     * How [stack] maps [sourceWidth] × [sourceHeight] onto its output — the **single
     * construction the preview draws with and the save samples with.**
     *
     * Before `conventional-image-editor` the preview re-ran [apply] on a screen-sized
     * bitmap for every frame of every slider drag, which allocated per frame on the
     * main thread inside composition. The preview now draws this matrix instead, and
     * the save builds it from the same function, so the two cannot disagree about
     * what a stack means. That agreement is the whole reason this is one function
     * rather than two similar ones.
     *
     * Crop, straighten, the quarter turns and the flips compose here: one sampling
     * of the source, never a bitmap per operation.
     */
    internal fun geometry(sourceWidth: Int, sourceHeight: Int, stack: Stack): Geometry {
        val crop = stack.crop.sane()
        val cropped = RectF(
            crop.left * sourceWidth,
            crop.top * sourceHeight,
            crop.right * sourceWidth,
            crop.bottom * sourceHeight,
        )
        val out = outputSize(cropped.width(), cropped.height(), stack.straighten, stack.quarterTurns)

        // Source → output in one map: move the crop's centre to the origin, turn it
        // (the straighten and the quarter turns are one rotation), mirror it, then put
        // it back at the output's centre.
        val matrix = Matrix().apply {
            postTranslate(-cropped.centerX(), -cropped.centerY())
            postRotate(stack.straighten + stack.quarterTurns * 90f)
            postScale(
                if (stack.flipHorizontal) -1f else 1f,
                if (stack.flipVertical) -1f else 1f,
            )
            postTranslate(out.first / 2f, out.second / 2f)
        }
        return Geometry(matrix, out.first, out.second)
    }

    /**
     * The output size for a crop of [width] × [height] turned by [angle] and
     * [quarterTurns].
     *
     * **This inset is what makes a straighten usable.** It used to return the box
     * that *holds* the turned rectangle's corners, so straightening by three degrees
     * produced a picture with transparent wedges in all four corners and the user was
     * expected to go and crop them away by hand. No editor works that way. This
     * returns the largest rectangle **of the crop's own aspect** that fits *inside*
     * the turned frame, so the corners never come into existence.
     *
     * The quarter turns swap the result rather than entering the inset: a right angle
     * introduces no corners to lose.
     */
    internal fun outputSize(
        width: Float,
        height: Float,
        angle: Float,
        quarterTurns: Int = 0,
    ): Pair<Int, Int> {
        val inset = insetForAngle(width, height, angle)
        val w = max(1, inset.first.toInt())
        val h = max(1, inset.second.toInt())
        return if (quarterTurns % 2 != 0) h to w else w to h
    }

    /**
     * The largest rectangle of [width]:[height]'s **own aspect** that fits inside that
     * rectangle turned by [angle].
     *
     * Its aspect and not the largest by area, which is a different rectangle and a
     * worse answer: a straighten that also reshapes the picture is a straighten the
     * user did not ask for, and the shape would drift a little further on every
     * degree of the dial. Holding the aspect is what every editor's straighten does.
     *
     * Turn a centred `w × h` box back by the angle and its half-extents are
     * `(w·cos + h·sin)/2` and `(w·sin + h·cos)/2`; both must fit inside the original.
     * Substituting `h = w / r` leaves two bounds on `w` and the answer is the smaller.
     * No branch and no singularity — the earlier version had both, because it was
     * solving the other problem.
     */
    internal fun insetForAngle(width: Float, height: Float, angle: Float): Pair<Float, Float> {
        if (angle == 0f || width <= 0f || height <= 0f) return width to height
        val radians = Math.toRadians(angle.toDouble())
        val c = abs(cos(radians)).toFloat()
        val s = abs(sin(radians)).toFloat()

        val ratio = width / height
        val w = min(width / (c + s / ratio), height / (s + c / ratio))
        return w to (w / ratio)
    }

    /**
     * The one colour matrix for the whole stack, or null when nothing tints.
     *
     * Order matters and is fixed: saturation, then the filter's own look, then
     * contrast and brightness, then warmth. Multiplying in a different order gives a
     * different picture from the same slider positions, and a preview that disagreed
     * with the save would be exactly that bug.
     */
    internal fun colourMatrix(stack: Stack): ColorMatrix? {
        val filtering = stack.filter != Filter.None && stack.filterIntensity > 0f
        if (!filtering && stack.brightness == 0f && stack.contrast == 0f &&
            stack.saturation == 0f && stack.warmth == 0f
        ) {
            return null
        }

        val result = ColorMatrix()

        result.postConcat(ColorMatrix().apply { setSaturation(1f + stack.saturation) })
        if (filtering) {
            filterMatrix(stack.filter)
                ?.let { towardsIdentity(it, stack.filterIntensity.coerceIn(0f, 1f)) }
                ?.let(result::postConcat)
        }

        // Contrast pivots on mid-grey, so raising it darkens the shadows rather than
        // brightening the whole frame — which is what a contrast control has to do.
        if (stack.contrast != 0f) {
            val scale = 1f + stack.contrast
            val shift = 128f * (1f - scale)
            result.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        scale, 0f, 0f, 0f, shift,
                        0f, scale, 0f, 0f, shift,
                        0f, 0f, scale, 0f, shift,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        }

        if (stack.brightness != 0f) {
            val shift = stack.brightness * 100f
            result.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, shift,
                        0f, 1f, 0f, 0f, shift,
                        0f, 0f, 1f, 0f, shift,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        }

        // Warmth moves red and blue in opposite directions, which is what a colour
        // temperature is; a single channel gain would be a colour cast.
        if (stack.warmth != 0f) {
            val shift = stack.warmth * 40f
            result.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, shift,
                        0f, 1f, 0f, 0f, 0f,
                        0f, 0f, 1f, 0f, -shift,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        }

        return result
    }

    /**
     * [matrix] blended towards the identity by [amount], 1 being the matrix itself.
     *
     * A linear blend of the two, which is what a strength control is: at zero the
     * picture is untouched, at one it is the filter as defined, and every position
     * between is one matrix rather than two passes fighting.
     */
    internal fun towardsIdentity(matrix: ColorMatrix, amount: Float): ColorMatrix {
        if (amount >= 1f) return matrix
        val identity = ColorMatrix().array
        val from = matrix.array
        val blended = FloatArray(from.size) { i ->
            identity[i] + (from[i] - identity[i]) * amount
        }
        return ColorMatrix(blended)
    }

    /** A named look as a matrix. Null for [Filter.None], which multiplies nothing. */
    internal fun filterMatrix(filter: Filter): ColorMatrix? = when (filter) {
        Filter.None -> null
        Filter.Mono -> ColorMatrix().apply { setSaturation(0f) }
        Filter.Warm -> ColorMatrix(
            floatArrayOf(
                1.06f, 0f, 0f, 0f, 12f,
                0f, 1f, 0f, 0f, 4f,
                0f, 0f, 0.94f, 0f, -8f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        Filter.Cool -> ColorMatrix(
            floatArrayOf(
                0.94f, 0f, 0f, 0f, -8f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1.08f, 0f, 12f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        // A lifted black point: the shadows stop at grey, which is the whole of a fade.
        Filter.Fade -> ColorMatrix(
            floatArrayOf(
                0.86f, 0f, 0f, 0f, 26f,
                0f, 0.86f, 0f, 0f, 26f,
                0f, 0f, 0.86f, 0f, 26f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        Filter.Punch -> ColorMatrix().apply {
            setSaturation(1.35f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1.15f, 0f, 0f, 0f, -18f,
                        0f, 1.15f, 0f, 0f, -18f,
                        0f, 0f, 1.15f, 0f, -18f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        }
    }

    /**
     * The strokes, mapped through the same [matrix] the image went through.
     *
     * Through the matrix and not drawn straight onto the output, because a stroke was
     * drawn over a *particular part of the photograph*: crop and straighten have to
     * carry it with them, or the annotation ends up pointing at something else.
     *
     * The points are fractions of the **whole source**, so they are scaled by the
     * source's own size — the same coordinates the matrix takes as input.
     */
    private fun drawStrokes(
        canvas: Canvas,
        strokes: List<Stroke>,
        sourceWidth: Int,
        sourceHeight: Int,
        matrix: Matrix,
    ) {
        if (strokes.isEmpty()) return
        val longest = max(sourceWidth, sourceHeight).toFloat()

        canvas.save()
        canvas.concat(matrix)
        strokes.forEach { stroke ->
            if (stroke.points.size < 2) return@forEach
            val path = android.graphics.Path()
            stroke.points.forEachIndexed { i, (x, y) ->
                val sx = x * sourceWidth
                val sy = y * sourceHeight
                if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            }
            canvas.drawPath(
                path,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = stroke.colour
                    style = Paint.Style.STROKE
                    strokeWidth = stroke.width * longest
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                },
            )
        }
        canvas.restore()
    }
}
