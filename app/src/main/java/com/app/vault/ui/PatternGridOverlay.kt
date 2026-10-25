package com.app.vault.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.vault.R
import com.app.vault.ui.theme.VaultColors
import com.app.vault.ui.theme.VaultPalette

/**
 * The pattern grid, drawn — `pattern-cover-trigger`, `DESIGN.md` §7.18.
 *
 * **The one place these nine nodes are ever visible.** [MusicCover] and
 * [RecorderCover] compose this while setup hosts them (`onProgress` non-null) and — since
 * `cover-hold-dots` — on a live cover whose owner held their area (`showDots`); [CalculatorCover]
 * in setup's pattern steps and on its dots after its sequence or its hold
 * (`calculator-dots-methods`). Never otherwise. It draws over the real cover rather than a
 * blank screen, at the same size and position the live cover's own invisible grid occupies —
 * so what is learned here is the geometry the user finds blind on the plain method, anchored
 * to the cover's own layout rather than to nothing, and a revealed live grid is this same
 * drawing, not a second one.
 *
 * Node centres are computed the same way [com.app.vault.gridNodeAt] hit-tests them —
 * nine equal cells, centre of each — so a circle drawn here sits exactly where a
 * finger has to land to register. The two are kept as separate small formulas rather
 * than one shared function: this file draws pixels, that one is pure geometry kept
 * importable with no Compose dependency, and the arithmetic is two lines either way.
 *
 * **The error state** (`cover-fingerprint-dots` D4) is said four ways, so no one of them
 * carries it alone: the path and the visited fill turn [VaultColors.Error] with their white
 * rings kept, the line reads **Wrong pattern** and is announced, the activity performs the
 * reject haptic, and the nodes shake ±8dp three times in 300ms — the scrim stays put, so no
 * edge of the cover shows. Where Android's animator duration scale is 0 the shake is skipped
 * and the other three stand.
 */
@Composable
fun PatternGridOverlay(
    visited: List<Int>,
    modifier: Modifier = Modifier,
    /** D4. A refused pattern on shown dots: the error colour, the words, the shake. */
    error: Boolean = false,
    /**
     * D4. **Draw your pattern** — **Wrong pattern** in [error] — centred at the top. A live
     * cover's revealed dots only: setup has its own header.
     */
    line: Boolean = false,
    /** Where the line's 24dp is measured from: the foot of the status bar the cover draws under. */
    lineTop: Dp = 0.dp,
) {
    // Resolved here, in composition, and captured by the draw lambda below —
    // `Canvas`'s content runs in `DrawScope`, not composition, so a colour token or a
    // dp-to-px conversion cannot be read from inside it.
    val scrimColor = VaultColors.Scrim.copy(alpha = SCRIM_ALPHA)
    // White on a black scrim in both schemes: the scrim, not the scheme, is what the
    // nodes sit on, so one colour holds 3:1 over the lightest and the darkest cover.
    val restColor = Color.White
    // The error tone is chosen for the scrim, as white is — the dark scheme's, in both schemes. The
    // grid sits on black over a cover that follows the device, not the app: the light scheme's
    // error measured 1.8:1 over a light cover's scrim and 2.2:1 over a dark one; this one 3.0:1
    // and 11.6:1.
    val visitedColor = if (error) VaultPalette.Dark.error else VaultColors.Primary
    val strokeColor = visitedColor
    val density = LocalDensity.current
    val nodeRadiusPx = with(density) { NODE_RADIUS.toPx() }
    val strokeWidthPx = with(density) { STROKE_WIDTH.toPx() }
    val visitedRadiusPx = with(density) { VISITED_RADIUS.toPx() }
    val ringPx = with(density) { RING_WIDTH.toPx() }
    val shakePx = with(density) { SHAKE.toPx() }

    // §8's reduced motion. Compose does not honour the animator scale on its own —
    // `voice-memo-layout` measured it — so it is read, and at 0 the shake does not run.
    val context = LocalContext.current
    val shake = remember { Animatable(0f) }
    LaunchedEffect(error) {
        val reduced = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
        if (!error || reduced) {
            shake.snapTo(0f)
            return@LaunchedEffect
        }
        // Three cycles in 300ms: out, across, back.
        repeat(SHAKE_CYCLES) {
            shake.animateTo(1f, tween(SHAKE_CYCLE_MS / 4))
            shake.animateTo(-1f, tween(SHAKE_CYCLE_MS / 2))
            shake.animateTo(0f, tween(SHAKE_CYCLE_MS / 4))
        }
    }

    Box(modifier) {
        Canvas(Modifier.matchParentSize().testTag(PATTERN_GRID_TAG)) {
            drawRect(scrimColor)
            val cellW = size.width / 3f
            val cellH = size.height / 3f
            fun centerOf(node: Int): Offset {
                val col = node % 3
                val row = node / 3
                return Offset((col + 0.5f) * cellW, (row + 0.5f) * cellH)
            }

            // The nodes and the path move; the scrim does not.
            translate(left = shake.value * shakePx) {
                // The path first, under the nodes, so a node's fill is never interrupted by
                // the stroke passing through it.
                if (visited.size >= 2) {
                    for (i in 1 until visited.size) {
                        drawLine(
                            color = strokeColor,
                            start = centerOf(visited[i - 1]),
                            end = centerOf(visited[i]),
                            strokeWidth = strokeWidthPx,
                            cap = StrokeCap.Round,
                        )
                    }
                }

                for (node in 0 until 9) {
                    if (node in visited) {
                        // Larger and ringed as well as recoloured, so a visited node is not
                        // told from a resting one by colour alone — and the ring stays white
                        // in the error state, so that state is not colour alone either.
                        drawCircle(color = restColor, radius = visitedRadiusPx, center = centerOf(node))
                        drawCircle(color = visitedColor, radius = visitedRadiusPx - ringPx, center = centerOf(node))
                    } else {
                        drawCircle(color = restColor, radius = nodeRadiusPx, center = centerOf(node))
                    }
                }
            }
        }
        if (line) {
            Text(
                text = stringResource(if (error) WRONG_PATTERN else DRAW_PATTERN),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = lineTop + LINE_TOP, start = LINE_SIDE, end = LINE_SIDE)
                    // On its own pill: the line sits where a cover keeps its own top row — the
                    // player's tabs, the recorder's search field — and the 55% scrim alone left
                    // the two reading through each other on the device.
                    .background(VaultColors.Scrim.copy(alpha = LINE_BACKING_ALPHA), RoundedCornerShape(50))
                    .padding(horizontal = LINE_PAD_H, vertical = LINE_PAD_V)
                    // The change to "Wrong pattern" is announced; nothing is moved for it.
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * The grid as a cover shows it: entering in 250ms and leaving in 150ms, by fade —
 * `cover-fingerprint-dots` D4. Shown from the first frame where it is visible on arrival, so
 * setup's grid does not fade in under a finger already drawing. Leaving, it keeps the error it
 * last showed, so a refused pattern fades out red rather than turning back first.
 */
@Composable
fun BoxScope.CoverPatternGrid(
    visible: Boolean,
    visited: List<Int>,
    error: Boolean,
    line: Boolean,
    lineTop: Dp,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.matchParentSize(),
        enter = fadeIn(tween(DOTS_ENTER_MS, easing = EaseOut)),
        exit = fadeOut(tween(DOTS_EXIT_MS, easing = EaseIn)),
    ) {
        val leaving = transition.targetState == EnterExitState.PostExit
        val held = remember { HeldError() }
        if (!leaving) held.error = error
        PatternGridOverlay(visited, Modifier.fillMaxSize(), held.error, line, lineTop)
    }
}

/** What the grid last showed — a plain holder, read only while it fades out. */
private class HeldError {
    var error = false
}

/** 20dp diameter at rest, `DESIGN.md` §7.18. */
private val NODE_RADIUS = 10.dp

/** A visited node: 28dp across, with a [RING_WIDTH] white ring. */
private val VISITED_RADIUS = 14.dp
private val RING_WIDTH = 3.dp

/** Black at this alpha over the cover; the cover stays recognisable beneath it. */
private const val SCRIM_ALPHA = 0.55f

/** The drawn path's width, `DESIGN.md` §7.18. */
private val STROKE_WIDTH = 3.dp

/** D4. The error's horizontal shake: ±8dp, three cycles in 300ms. */
private val SHAKE = 8.dp
private const val SHAKE_CYCLES = 3
private const val SHAKE_CYCLE_MS = 100

/** D4. The line sits 24dp below the status bar, clear of the grid's top row. */
private val LINE_TOP = 24.dp
private val LINE_SIDE = 16.dp

/** The line's pill: the scrim's black, darker than the scrim, so white holds over any cover. */
private const val LINE_BACKING_ALPHA = 0.75f
private val LINE_PAD_H = 16.dp
private val LINE_PAD_V = 6.dp

/** D4. The dots' fade in and out. */
private const val DOTS_ENTER_MS = 250
private const val DOTS_EXIT_MS = 150

/** D4. The revealed grid's line, and its line after a refused pattern. */
val DRAW_PATTERN = R.string.pattern_draw
val WRONG_PATTERN = R.string.pattern_wrong

/**
 * Tags the drawn grid, so a device test can tell whether a cover's dots are showing. A test
 * tag is not exposed to accessibility services, so a live cover gains nothing a person or a
 * screen reader can find.
 */
const val PATTERN_GRID_TAG = "pattern-grid"
