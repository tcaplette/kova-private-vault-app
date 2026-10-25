package com.app.vault.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.app.vault.ImageEdits
import com.app.vault.R
import com.app.vault.ui.theme.Radius
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The image editor — `viewer-chrome-and-editor`, rebuilt by `conventional-image-editor`.
 *
 * **A screen and not a sheet**, which is the distinction the app has been drawing all
 * along: a sheet is something you do to an item in a moment and dismiss; this is a
 * place you go, work, and come back from. It is the only screen besides the viewer
 * that puts an image on the viewer's black ground, because it is showing the same
 * photograph and a surface colour behind it would change how the edit reads.
 *
 * **Controls belong on the picture here, and that is the difference from the viewer.**
 * The first version of this screen cropped by four numbered sliders and defended it on
 * the grounds that a handle over a photograph would be the first chrome on an image in
 * this app. That rule is a rule about *viewing*. An editor exists to put controls on a
 * picture; direct manipulation is what an editor is, and arithmetic is what it is not.
 *
 * **The preview draws, it does not bake.** Everything here builds an
 * [ImageEdits.Stack] and hands it to [ImageEdits.geometry] and [ImageEdits.drawOnto] —
 * the same two functions the save calls, at a different scale. The previous version
 * ran the whole bitmap pipeline inside composition for every frame of every slider
 * drag; a `remember` was claimed to prevent that and could not, because a slider
 * reports a new value on every frame. Nothing on this screen allocates a bitmap.
 *
 * **No plaintext leaves memory.** This composable never sees a path, a `Uri` or a
 * stream — it is handed a decoded bitmap and hands back a stack. What the bytes do
 * before and after belongs to `MainActivity`, where the key is (I3).
 *
 * **Saving keeps the original**, stated on the screen where the user meets it,
 * because a user who expects an overwrite and gets two items will delete the wrong
 * one.
 */
@Composable
fun EditorScreen(
    /** The item, decoded at screen resolution. Never a path — I3. */
    preview: Bitmap,
    onSave: (ImageEdits.Stack) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** True while the save runs, so the action cannot be pressed twice. */
    saving: Boolean = false,
) {
    var stack by remember { mutableStateOf(ImageEdits.Stack()) }
    var tool by remember { mutableStateOf(Tool.Crop) }

    // Undo as a list of values. The stack is a small immutable data class, so a
    // history is a list of them and needs no command pattern — the laziest thing that
    // is also the correct thing.
    var past by remember { mutableStateOf(listOf<ImageEdits.Stack>()) }
    var future by remember { mutableStateOf(listOf<ImageEdits.Stack>()) }

    // Where a continuous gesture began, so a slider drag is **one** entry in the
    // history rather than one per frame. Set on the first reported value of a drag
    // and cleared when the drag ends.
    var gestureStart by remember { mutableStateOf<ImageEdits.Stack?>(null) }

    // Press and hold shows the picture unedited. The universal gesture for it, and
    // one boolean.
    var comparing by remember { mutableStateOf(false) }

    var confirmingDiscard by remember { mutableStateOf(false) }

    // The pen, not the picture. These are tool settings and never reach the saved
    // stack, so changing colour is not an edit and does not land in the history.
    var markupColour by remember { mutableStateOf(MARKUP_COLOURS.first().second) }
    var markupWidth by remember { mutableStateOf(ImageEdits.Width.Medium) }
    var erasing by remember { mutableStateOf(false) }

    /** A discrete change: its own history entry, taken immediately. */
    fun commit(next: ImageEdits.Stack) {
        if (next == stack) return
        past = (past + stack).takeLast(HISTORY_DEPTH)
        future = emptyList()
        stack = next
    }

    /** A continuous change: the picture follows now, the history entry lands on release. */
    fun during(next: ImageEdits.Stack) {
        if (gestureStart == null) gestureStart = stack
        stack = next
    }

    fun endGesture() {
        val started = gestureStart ?: return
        gestureStart = null
        if (started == stack) return
        past = (past + started).takeLast(HISTORY_DEPTH)
        future = emptyList()
    }

    fun undo() {
        val previous = past.lastOrNull() ?: return
        past = past.dropLast(1)
        future = listOf(stack) + future
        stack = previous
    }

    fun redo() {
        val next = future.firstOrNull() ?: return
        future = future.drop(1)
        past = (past + stack).takeLast(HISTORY_DEPTH)
        stack = next
    }

    fun leave() {
        if (stack.isEmpty) onCancel() else confirmingDiscard = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(VaultColors.ViewerGround),
    ) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            EditorTopRow(
                canUndo = past.isNotEmpty(),
                canRedo = future.isNotEmpty(),
                onLeave = ::leave,
                onUndo = ::undo,
                onRedo = ::redo,
            )

            EditorCanvas(
                source = preview,
                stack = stack,
                tool = tool,
                comparing = comparing,
                markupColour = markupColour,
                markupWidth = markupWidth,
                erasing = erasing,
                onCompare = { comparing = it },
                onDuring = ::during,
                onEnd = ::endGesture,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(Spacing.sm),
            )

            ToolRow(current = tool, onSelect = { tool = it })

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenInset)) {
                when (tool) {
                    Tool.Crop -> CropControls(stack, ::commit)
                    Tool.Straighten -> StraightenControls(stack, ::commit, ::during, ::endGesture)
                    Tool.Filters -> FilterControls(preview, stack, ::commit, ::during, ::endGesture)
                    Tool.Light -> LightControls(stack, ::commit, ::during, ::endGesture)
                    Tool.Colour -> ColourControls(stack, ::commit, ::during, ::endGesture)
                    Tool.Markup -> MarkupControls(
                        stack = stack,
                        colour = markupColour,
                        width = markupWidth,
                        erasing = erasing,
                        onColour = { markupColour = it; erasing = false },
                        onWidth = { markupWidth = it; erasing = false },
                        onErasing = { erasing = it },
                        onCommit = ::commit,
                    )
                }

                // Where the user meets the save, not in a settings screen they will
                // never open: two items after a save is a surprise that makes people
                // delete the wrong one.
                Text(
                    text = stringResource(R.string.editor_save_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VaultColors.OnViewerGroundVariant,
                    modifier = Modifier.padding(top = Spacing.md),
                )

                Button(
                    onClick = { onSave(stack) },
                    enabled = !saving && !stack.isEmpty,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm, bottom = Spacing.md),
                ) {
                    Text(
                        text = stringResource(if (saving) R.string.editor_saving else R.string.editor_save_copy),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_body)) },
            confirmButton = {
                TextButton(onClick = { confirmingDiscard = false; onCancel() }) {
                    Text(stringResource(R.string.editor_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) { Text(stringResource(R.string.editor_keep_editing)) }
            },
        )
    }
}

/** How many steps back the history holds. Bounded so a long session cannot grow it forever. */
private const val HISTORY_DEPTH = 40

@Composable
private fun EditorTopRow(
    canUndo: Boolean,
    canRedo: Boolean,
    onLeave: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onLeave, modifier = Modifier.size(Spacing.touchTarget)) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.editor_discard_edits),
                tint = VaultColors.OnViewerGround,
            )
        }
        Text(
            text = stringResource(R.string.editor_title),
            style = MaterialTheme.typography.titleLarge,
            color = VaultColors.OnViewerGround,
            modifier = Modifier.padding(start = Spacing.sm),
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(
            onClick = onUndo,
            enabled = canUndo,
            modifier = Modifier.size(Spacing.touchTarget),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_undo),
                contentDescription = stringResource(R.string.undo),
                tint = if (canUndo) VaultColors.OnViewerGround else VaultColors.OnViewerGroundVariant,
            )
        }
        IconButton(
            onClick = onRedo,
            enabled = canRedo,
            modifier = Modifier.size(Spacing.touchTarget),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_redo),
                contentDescription = stringResource(R.string.redo),
                tint = if (canRedo) VaultColors.OnViewerGround else VaultColors.OnViewerGroundVariant,
            )
        }
    }
}

/** The editor's tools. The set the change named, and the whole of it. */
private enum class Tool(@StringRes val label: Int, val icon: Int) {
    Crop(R.string.tool_crop, R.drawable.ic_crop),
    Straighten(R.string.tool_straighten, R.drawable.ic_rotate_right),
    Filters(R.string.tool_filters, R.drawable.ic_photo_filter),
    Light(R.string.tool_light, R.drawable.ic_tune),
    Colour(R.string.vault_colour, R.drawable.ic_palette),
    Markup(R.string.tool_markup, R.drawable.ic_draw),
}

/**
 * Which tool the controls below belong to.
 *
 * **A filled container marks the selection, not a trailing character.** The previous
 * version appended " ·" to the active tool's label and relied on a tint; a middle dot
 * after a word is not a state anybody perceives, and colour alone is not one either.
 */
@Composable
private fun ToolRow(current: Tool, onSelect: (Tool) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Tool.entries.forEach { entry ->
            val active = entry == current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(Spacing.touchTarget / 2))
                    .background(if (active) VaultColors.OnViewerGround.copy(alpha = 0.16f) else Color.Transparent)
                    .semantics { selected = active }
                    .height(Spacing.touchTarget),
            ) {
                TextButton(onClick = { onSelect(entry) }) {
                    Icon(
                        painter = painterResource(entry.icon),
                        contentDescription = null,
                        tint = if (active) VaultColors.OnViewerGround else VaultColors.OnViewerGroundVariant,
                        modifier = Modifier.padding(end = Spacing.xs),
                    )
                    Text(
                        text = stringResource(entry.label),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) VaultColors.OnViewerGround else VaultColors.OnViewerGroundVariant,
                    )
                }
            }
        }
    }
}

// --- the picture ------------------------------------------------------------

/**
 * The photograph with the stack drawn over it, and the tool's own manipulation on top.
 *
 * **This is the whole preview, and it allocates nothing.** It asks [ImageEdits] for
 * the matrix the save will use, scales it to fit this canvas, and hands the pair to
 * [ImageEdits.drawOnto] — the function the save also calls. A slider drag redraws;
 * it does not re-encode.
 *
 * The crop tool draws the picture **in the space the crop is defined in** — the
 * source, with no straighten, turn or flip — so a rectangle in source fractions maps
 * onto the drawn image by a plain scale. Mapping a crop through a rotation to draw it
 * and back again to record it is two chances to be a pixel out, in the one control
 * where being a pixel out is the whole experience.
 */
@Composable
private fun EditorCanvas(
    source: Bitmap,
    stack: ImageEdits.Stack,
    tool: Tool,
    comparing: Boolean,
    markupColour: Int,
    markupWidth: ImageEdits.Width,
    erasing: Boolean,
    onCompare: (Boolean) -> Unit,
    onDuring: (ImageEdits.Stack) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cropping = tool == Tool.Crop
    val shown = when {
        comparing -> ImageEdits.Stack()
        cropping -> stack.copy(
            crop = ImageEdits.Crop(),
            straighten = 0f,
            quarterTurns = 0,
            flipHorizontal = false,
            flipVertical = false,
        )
        else -> stack
    }

    // Where the picture actually landed inside this box, so the overlays and the
    // pointer handling speak the same coordinates the drawing does. Derived rather
    // than written from the draw pass: assigning state while drawing is how a
    // composable ends up redrawing itself forever.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val drawn = remember(canvasSize, shown, source) {
        if (canvasSize.width == 0 || canvasSize.height == 0) {
            Rect.Zero
        } else {
            val plan = ImageEdits.geometry(source.width, source.height, shown)
            val fit = min(
                canvasSize.width / plan.width.toFloat(),
                canvasSize.height / plan.height.toFloat(),
            )
            val w = plan.width * fit
            val h = plan.height * fit
            Rect(
                Offset((canvasSize.width - w) / 2f, (canvasSize.height - h) / 2f),
                Size(w, h),
            )
        }
    }
    val grip = remember { GripState() }
    val reachPx = with(LocalDensity.current) { Spacing.touchTarget.toPx() } / 2f

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                // Press and hold to see the picture unedited — the universal gesture.
                // `onPress` rather than `onLongPress` so it is held, not toggled.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onCompare(true)
                            tryAwaitRelease()
                            onCompare(false)
                        },
                    )
                }
                .then(
                    if (cropping) {
                        Modifier.pointerInput(drawn) {
                            if (drawn.width <= 0f || drawn.height <= 0f) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val start = (down.position - drawn.topLeft).let {
                                    Offset(it.x / drawn.width, it.y / drawn.height)
                                }
                                val taken = ImageEdits.gripAt(
                                    stack.crop,
                                    start.x,
                                    start.y,
                                    reachPx / drawn.width,
                                ) ?: return@awaitEachGesture
                                grip.dragging = true
                                var current = stack
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.pressed }
                                        ?: break
                                    val delta = change.positionChange()
                                    current = current.copy(
                                        crop = ImageEdits.dragged(
                                            current.crop,
                                            taken,
                                            delta.x / drawn.width,
                                            delta.y / drawn.height,
                                            current.aspect.ratio,
                                        ),
                                    )
                                    onDuring(current)
                                    if (change.positionChanged()) change.consume()
                                }
                                grip.dragging = false
                                onEnd()
                            }
                        }
                    } else if (tool == Tool.Markup) {
                        Modifier.pointerInput(drawn, erasing, markupColour, markupWidth) {
                            if (drawn.width <= 0f || drawn.height <= 0f) return@pointerInput
                            var current = stack
                            detectDragGestures(
                                onDragStart = { at ->
                                    val p = Offset(
                                        (at.x - drawn.left) / drawn.width,
                                        (at.y - drawn.top) / drawn.height,
                                    )
                                    current = stack
                                    current = if (erasing) {
                                        val hit = ImageEdits.strokeAt(current.strokes, p.x, p.y, ERASE_REACH)
                                        if (hit == null) current
                                        else current.copy(
                                            strokes = current.strokes.filterIndexed { i, _ -> i != hit },
                                        )
                                    } else {
                                        current.copy(
                                            strokes = current.strokes + ImageEdits.Stroke(
                                                colour = markupColour,
                                                width = markupWidth.fraction,
                                                points = listOf(p.x to p.y),
                                            ),
                                        )
                                    }
                                    onDuring(current)
                                },
                                onDragEnd = { onEnd() },
                                onDragCancel = { onEnd() },
                            ) { change, _ ->
                                val p = Offset(
                                    (change.position.x - drawn.left) / drawn.width,
                                    (change.position.y - drawn.top) / drawn.height,
                                )
                                current = if (erasing) {
                                    val hit = ImageEdits.strokeAt(current.strokes, p.x, p.y, ERASE_REACH)
                                    if (hit == null) current
                                    else current.copy(
                                        strokes = current.strokes.filterIndexed { i, _ -> i != hit },
                                    )
                                } else {
                                    val last = current.strokes.lastOrNull() ?: return@detectDragGestures
                                    current.copy(
                                        strokes = current.strokes.dropLast(1) +
                                            last.copy(points = last.points + (p.x to p.y)),
                                    )
                                }
                                onDuring(current)
                            }
                        }
                    } else {
                        Modifier
                    }
                ),
        ) {
            val plan = ImageEdits.geometry(source.width, source.height, shown)
            val fit = min(size.width / plan.width, size.height / plan.height)
            val placed = Matrix(plan.matrix).apply {
                postScale(fit, fit)
                postTranslate(
                    (size.width - plan.width * fit) / 2f,
                    (size.height - plan.height * fit) / 2f,
                )
            }
            drawContext.canvas.nativeCanvas.let { ImageEdits.drawOnto(it, source, shown, placed) }
        }

        if (cropping && drawn.width > 0f) {
            CropOverlay(crop = stack.crop, area = drawn, gridded = grip.dragging)
        }
    }
}

/** Whether a crop handle is currently under a finger, so the grid knows to show. */
private class GripState {
    var dragging by mutableStateOf(false)
}

/** How near a stroke a touch must be to erase it, in fractions of the source. */
private const val ERASE_REACH = 0.03f

/**
 * The crop rectangle, drawn as a scrim and eight handles.
 *
 * **The scrim is the affordance.** Nothing is drawn *on* the photograph — the
 * photograph is dimmed where it is about to be discarded, which is the picture saying
 * what the crop does rather than chrome sitting over it.
 *
 * The composition grid appears only while a handle is under a finger. Present when it
 * is being used to compose and absent when it would be decoration.
 */
@Composable
private fun CropOverlay(crop: ImageEdits.Crop, area: Rect, gridded: Boolean) {
    val c = crop.sane()
    val armPx = with(LocalDensity.current) { CROP_ARM.toPx() }
    val thickPx = with(LocalDensity.current) { CROP_THICKNESS.toPx() }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val l = area.left + c.left * area.width
        val r = area.left + c.right * area.width
        val t = area.top + c.top * area.height
        val b = area.top + c.bottom * area.height

        // Outside the crop, dimmed. Four bands rather than a path with a hole: the
        // same result, and no allocation per frame.
        val scrim = Color.Black.copy(alpha = CROP_SCRIM)
        drawRect(scrim, topLeft = Offset(area.left, area.top), size = Size(area.width, t - area.top))
        drawRect(scrim, topLeft = Offset(area.left, b), size = Size(area.width, area.bottom - b))
        drawRect(scrim, topLeft = Offset(area.left, t), size = Size(l - area.left, b - t))
        drawRect(scrim, topLeft = Offset(r, t), size = Size(area.right - r, b - t))

        if (gridded) {
            val thirds = Color.White.copy(alpha = 0.35f)
            for (i in 1..2) {
                val x = l + (r - l) * i / 3f
                val y = t + (b - t) * i / 3f
                drawLine(thirds, Offset(x, t), Offset(x, b), strokeWidth = 1f)
                drawLine(thirds, Offset(l, y), Offset(r, y), strokeWidth = 1f)
            }
        }

        drawRect(
            Color.White.copy(alpha = 0.7f),
            topLeft = Offset(l, t),
            size = Size(r - l, b - t),
            style = DrawStroke(width = 1f),
        )

        val white = Color.White
        // Corners as L-shapes and edges as bars. Drawn at [CROP_ARM]; the hit area is
        // a full touch target, taken in `EditorCanvas`, because a crop handle that
        // needs a precise finger is how this control is usually got wrong.
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(white, Offset(x, y), Offset(x + dx * armPx, y), strokeWidth = thickPx)
            drawLine(white, Offset(x, y), Offset(x, y + dy * armPx), strokeWidth = thickPx)
        }
        corner(l, t, 1f, 1f)
        corner(r, t, -1f, 1f)
        corner(l, b, 1f, -1f)
        corner(r, b, -1f, -1f)

        val midX = (l + r) / 2f
        val midY = (t + b) / 2f
        drawLine(white, Offset(midX - armPx / 2f, t), Offset(midX + armPx / 2f, t), strokeWidth = thickPx)
        drawLine(white, Offset(midX - armPx / 2f, b), Offset(midX + armPx / 2f, b), strokeWidth = thickPx)
        drawLine(white, Offset(l, midY - armPx / 2f), Offset(l, midY + armPx / 2f), strokeWidth = thickPx)
        drawLine(white, Offset(r, midY - armPx / 2f), Offset(r, midY + armPx / 2f), strokeWidth = thickPx)
    }
}

private val CROP_ARM = 22.dp
private val CROP_THICKNESS = 3.dp
private const val CROP_SCRIM = 0.55f

// --- the tools --------------------------------------------------------------

/**
 * The crop's shapes and its way back.
 *
 * **The rectangle itself is on the picture**, where it belongs; this row carries the
 * ratios that shape it. They live here and not in a tool of their own because a ratio
 * modifies the crop and nothing else, and choosing one in a different place from the
 * thing it changes is what the old separate "Aspect" tool did.
 */
@Composable
private fun CropControls(stack: ImageEdits.Stack, onCommit: (ImageEdits.Stack) -> Unit) {
    Text(
        text = stringResource(R.string.editor_crop_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnViewerGroundVariant,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ImageEdits.Aspect.entries.forEach { aspect ->
            ChoiceChip(
                label = stringResource(aspect.label),
                selected = aspect == stack.aspect,
                onClick = {
                    onCommit(
                        stack.copy(aspect = aspect, crop = ImageEdits.heldTo(stack.crop, aspect)),
                    )
                },
            )
        }
    }
    ResetRow(enabled = !stack.crop.isWhole || stack.aspect != ImageEdits.Aspect.Free) {
        onCommit(stack.copy(crop = ImageEdits.Crop(), aspect = ImageEdits.Aspect.Free))
    }
}

/**
 * The straighten dial, the quarter turns and the flips.
 *
 * They are one tool because they are one geometry: all three compose into the single
 * matrix `ImageEdits.geometry` builds, and the save samples the source once for all of
 * them together.
 */
@Composable
private fun StraightenControls(
    stack: ImageEdits.Stack,
    onCommit: (ImageEdits.Stack) -> Unit,
    onDuring: (ImageEdits.Stack) -> Unit,
    onEnd: () -> Unit,
) {
    DegreeDial(
        degrees = stack.straighten,
        onChange = { onDuring(stack.copy(straighten = it)) },
        onSettled = onEnd,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onCommit(stack.copy(quarterTurns = (stack.quarterTurns + 3) % 4)) },
            modifier = Modifier.size(Spacing.touchTarget),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_rotate_right),
                contentDescription = stringResource(R.string.editor_rotate_left),
                tint = VaultColors.OnViewerGround,
                // Mirrored rather than a second asset for the same arrow.
                modifier = Modifier.graphicsLayer { scaleX = -1f },
            )
        }
        IconButton(
            onClick = { onCommit(stack.copy(quarterTurns = (stack.quarterTurns + 1) % 4)) },
            modifier = Modifier.size(Spacing.touchTarget),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_rotate_right),
                contentDescription = stringResource(R.string.editor_rotate_right),
                tint = VaultColors.OnViewerGround,
            )
        }
        IconButton(
            onClick = { onCommit(stack.copy(flipHorizontal = !stack.flipHorizontal)) },
            modifier = Modifier.size(Spacing.touchTarget),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_flip_camera),
                contentDescription = stringResource(R.string.editor_flip_horizontal),
                tint = if (stack.flipHorizontal) VaultColors.OnViewerGround
                else VaultColors.OnViewerGroundVariant,
            )
        }
        IconButton(
            onClick = { onCommit(stack.copy(flipVertical = !stack.flipVertical)) },
            modifier = Modifier.size(Spacing.touchTarget),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_flip_camera),
                contentDescription = stringResource(R.string.editor_flip_vertical),
                tint = if (stack.flipVertical) VaultColors.OnViewerGround
                else VaultColors.OnViewerGroundVariant,
                modifier = Modifier.graphicsLayer { rotationZ = 90f },
            )
        }
    }
    ResetRow(
        enabled = stack.straighten != 0f || stack.quarterTurns != 0 ||
            stack.flipHorizontal || stack.flipVertical,
    ) {
        onCommit(
            stack.copy(
                straighten = 0f,
                quarterTurns = 0,
                flipHorizontal = false,
                flipVertical = false,
            ),
        )
    }
}

/**
 * A graduated dial, marked in degrees, with a detent at level.
 *
 * **Not a slider.** A straighten is a small continuous correction judged against the
 * picture: the ruler affords that — it moves under a fixed indicator, so the user
 * watches the photograph and not the control — and an unmarked slider with a number
 * beside it does not.
 */
@Composable
private fun DegreeDial(degrees: Float, onChange: (Float) -> Unit, onSettled: () -> Unit) {
    val perDegree = with(LocalDensity.current) { DIAL_SPACING.toPx() }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.editor_straighten_value, "%+.1f".format(degrees)),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnViewerGroundVariant,
        )
        val major = VaultColors.OnViewerGround
        val faint = VaultColors.OnViewerGroundVariant
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(DIAL_HEIGHT)
                .pointerInput(perDegree) {
                    detectDragGestures(
                        onDragEnd = { onSettled() },
                        onDragCancel = { onSettled() },
                    ) { change, drag ->
                        // Dragging left turns the picture clockwise, the way a ruler
                        // under a fixed mark behaves.
                        val next = (degrees - drag.x / perDegree)
                            .coerceIn(-ImageEdits.MAX_STRAIGHTEN, ImageEdits.MAX_STRAIGHTEN)
                        // The detent: level is where people mean to be, and a ruler
                        // that will not settle on zero is one you fight.
                        onChange(if (abs(next) < DIAL_DETENT) 0f else next)
                        change.consume()
                    }
                },
        ) {
            val centre = size.width / 2f
            for (d in -ImageEdits.MAX_STRAIGHTEN.toInt()..ImageEdits.MAX_STRAIGHTEN.toInt()) {
                val x = centre + (d - degrees) * perDegree
                if (x < 0f || x > size.width) continue
                val fifth = d % 5 == 0
                drawLine(
                    color = if (fifth) major else faint,
                    start = Offset(x, size.height * if (fifth) 0.35f else 0.55f),
                    end = Offset(x, size.height * 0.9f),
                    strokeWidth = if (fifth) 2f else 1f,
                )
            }
            drawLine(
                color = major,
                start = Offset(centre, 0f),
                end = Offset(centre, size.height),
                strokeWidth = 4f,
            )
        }
    }
}

private val DIAL_HEIGHT = 48.dp
private val DIAL_SPACING = 8.dp
private const val DIAL_DETENT = 0.6f

/**
 * The named looks, **shown on the user's own photograph.**
 *
 * A filter presented by name alone costs a tap per look and a glance back up at the
 * picture. Each cell here is the image being edited with that filter on it, which is
 * the whole of what a filter row is for. One shared downsample carries all six, so the
 * strip costs one small bitmap for the life of the screen and none per frame.
 */
@Composable
private fun FilterControls(
    source: Bitmap,
    stack: ImageEdits.Stack,
    onCommit: (ImageEdits.Stack) -> Unit,
    onDuring: (ImageEdits.Stack) -> Unit,
    onEnd: () -> Unit,
) {
    val thumb = remember(source) {
        val side = THUMB_SOURCE
        val scale = min(side / source.width.toFloat(), side / source.height.toFloat())
        if (scale >= 1f) source
        else Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ImageEdits.Filter.entries.forEach { filter ->
            val active = filter == stack.filter
            val filterName = stringResource(filter.label)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(THUMB_SIDE)
                    .semantics {
                        contentDescription = filterName
                        selected = active
                    }
                    .pointerInput(filter) {
                        detectTapGestures {
                            onCommit(stack.copy(filter = filter, filterIntensity = 1f))
                        }
                    },
            ) {
                Canvas(
                    modifier = Modifier
                        .size(THUMB_SIDE)
                        .clip(RoundedCornerShape(Radius.control))
                        // Selection marked by a ring as well as by the label's weight,
                        // never by colour alone.
                        .border(
                            width = if (active) 2.dp else 1.dp,
                            color = if (active) VaultColors.OnViewerGround
                            else VaultColors.OnViewerGroundVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(Radius.control),
                        ),
                ) {
                    val look = ImageEdits.Stack(filter = filter)
                    val plan = ImageEdits.geometry(thumb.width, thumb.height, look)
                    val fit = min(size.width / plan.width, size.height / plan.height)
                    val placed = Matrix(plan.matrix).apply {
                        postScale(fit, fit)
                        postTranslate(
                            (size.width - plan.width * fit) / 2f,
                            (size.height - plan.height * fit) / 2f,
                        )
                    }
                    drawContext.canvas.nativeCanvas.let {
                        ImageEdits.drawOnto(it, thumb, look, placed)
                    }
                }
                Text(
                    text = filterName,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) VaultColors.OnViewerGround else VaultColors.OnViewerGroundVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
    }

    // Absent rather than disabled when there is no filter to strengthen: a greyed
    // slider explains a state the row above already shows.
    if (stack.filter != ImageEdits.Filter.None) {
        EditorSlider(
            label = stringResource(R.string.editor_strength),
            value = stack.filterIntensity,
            range = 0f..1f,
            onChange = { onDuring(stack.copy(filterIntensity = it)) },
            onSettled = onEnd,
        )
    }
    ResetRow(enabled = stack.filter != ImageEdits.Filter.None) {
        onCommit(stack.copy(filter = ImageEdits.Filter.None, filterIntensity = 1f))
    }
}

private val THUMB_SIDE = 72.dp
private const val THUMB_SOURCE = 220f

@Composable
private fun LightControls(
    stack: ImageEdits.Stack,
    onCommit: (ImageEdits.Stack) -> Unit,
    onDuring: (ImageEdits.Stack) -> Unit,
    onEnd: () -> Unit,
) {
    EditorSlider(stringResource(R.string.editor_brightness), stack.brightness, onChange = { onDuring(stack.copy(brightness = it)) }, onSettled = onEnd)
    EditorSlider(stringResource(R.string.editor_contrast), stack.contrast, onChange = { onDuring(stack.copy(contrast = it)) }, onSettled = onEnd)
    ResetRow(enabled = stack.brightness != 0f || stack.contrast != 0f) {
        onCommit(stack.copy(brightness = 0f, contrast = 0f))
    }
}

@Composable
private fun ColourControls(
    stack: ImageEdits.Stack,
    onCommit: (ImageEdits.Stack) -> Unit,
    onDuring: (ImageEdits.Stack) -> Unit,
    onEnd: () -> Unit,
) {
    EditorSlider(stringResource(R.string.editor_saturation), stack.saturation, onChange = { onDuring(stack.copy(saturation = it)) }, onSettled = onEnd)
    EditorSlider(stringResource(R.string.editor_warmth), stack.warmth, onChange = { onDuring(stack.copy(warmth = it)) }, onSettled = onEnd)
    ResetRow(enabled = stack.saturation != 0f || stack.warmth != 0f) {
        onCommit(stack.copy(saturation = 0f, warmth = 0f))
    }
}

/**
 * Markup's colours, widths and eraser.
 *
 * **The swatches carry no glyph.** The previous version drew a one-letter label in
 * black on every swatch including the black one, which is a 1:1 contrast ratio — the
 * colour was simply invisible. The name is the accessibility label now, where it can
 * be read without being drawn.
 */
@Composable
private fun MarkupControls(
    stack: ImageEdits.Stack,
    colour: Int,
    width: ImageEdits.Width,
    erasing: Boolean,
    onColour: (Int) -> Unit,
    onWidth: (ImageEdits.Width) -> Unit,
    onErasing: (Boolean) -> Unit,
    onCommit: (ImageEdits.Stack) -> Unit,
) {
    Text(
        text = stringResource(if (erasing) R.string.editor_erase_hint else R.string.editor_draw_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnViewerGroundVariant,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        MARKUP_COLOURS.forEach { (label, swatch) ->
            val active = !erasing && swatch == colour
            val name = stringResource(label)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(Spacing.touchTarget)
                    .semantics {
                        contentDescription = name
                        selected = active
                    }
                    .pointerInput(swatch) { detectTapGestures { onColour(swatch) } },
            ) {
                Box(
                    modifier = Modifier
                        .size(if (active) SWATCH_SELECTED else SWATCH)
                        .clip(CircleShape)
                        .background(Color(swatch))
                        // Every swatch keeps a ring against the black ground, so the
                        // black one is a circle and not a hole.
                        .border(
                            width = if (active) 3.dp else 1.dp,
                            color = VaultColors.OnViewerGround,
                            shape = CircleShape,
                        ),
                )
            }
        }
        IconButton(
            onClick = { onErasing(!erasing) },
            modifier = Modifier
                .size(Spacing.touchTarget)
                .semantics { selected = erasing },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_ink_eraser),
                contentDescription = stringResource(R.string.editor_eraser),
                tint = if (erasing) VaultColors.OnViewerGround
                else VaultColors.OnViewerGroundVariant,
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ImageEdits.Width.entries.forEach { choice ->
            ChoiceChip(
                label = stringResource(choice.label),
                selected = !erasing && choice == width,
                onClick = { onWidth(choice) },
            )
        }
    }
    ResetRow(enabled = stack.strokes.isNotEmpty()) {
        onCommit(stack.copy(strokes = emptyList()))
    }
}

private val SWATCH = 24.dp
private val SWATCH_SELECTED = 30.dp

/** Named for assistive technology rather than drawn over the colour — §6.4. */
private val MARKUP_COLOURS = listOf(
    R.string.colour_red to 0xFFE53935.toInt(),
    R.string.colour_yellow to 0xFFFDD835.toInt(),
    R.string.colour_white to 0xFFFFFFFF.toInt(),
    R.string.colour_black to 0xFF000000.toInt(),
)

// --- shared pieces ----------------------------------------------------------

/** One choice in a row of them. Selection is a filled container, never a character. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(Spacing.touchTarget)
            .clip(RoundedCornerShape(Spacing.touchTarget / 2))
            .background(
                if (selected) VaultColors.OnViewerGround.copy(alpha = 0.16f) else Color.Transparent,
            )
            .semantics { this.selected = selected },
    ) {
        TextButton(onClick = onClick) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) VaultColors.OnViewerGround else VaultColors.OnViewerGroundVariant,
            )
        }
    }
}

/** A tool's way back to its own defaults, leaving every other tool alone. */
@Composable
private fun ResetRow(enabled: Boolean, onReset: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
        TextButton(onClick = onReset, enabled = enabled) {
            Text(
                text = stringResource(R.string.editor_reset),
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) VaultColors.OnViewerGround else VaultColors.OnViewerGroundVariant,
            )
        }
    }
}

/**
 * One labelled slider. The label carries the value, so nothing is read from position
 * alone.
 *
 * [onSettled] is what makes a drag **one** entry in the undo history: the value
 * follows the finger through [onChange], and the history entry lands when the finger
 * lifts.
 */
@Composable
private fun EditorSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float> = -1f..1f,
    onChange: (Float) -> Unit,
    onSettled: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label  ·  ${"%+.2f".format(value)}",
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnViewerGroundVariant,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onSettled,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = VaultColors.OnViewerGround,
                activeTrackColor = VaultColors.OnViewerGround,
                inactiveTrackColor = VaultColors.OnViewerGroundVariant,
            ),
        )
    }
}
