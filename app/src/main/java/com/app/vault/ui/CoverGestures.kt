package com.app.vault.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import com.app.vault.Trigger
import com.app.vault.gridNodeAt
import com.app.vault.holdAreaAt

/**
 * The trigger gestures — P2-24.
 *
 * ***Silence is the requirement, not a nicety, and `combinedClickable` violates
 * it.*** That modifier fires `HapticFeedbackConstants.LONG_PRESS` by default, and a
 * buzz on a control that visibly does nothing is a tell: it says *something is here*
 * to whoever is holding the phone, which is the one thing a cover exists to avoid
 * saying. So these are raw `pointerInput` handlers and they emit **nothing** — no
 * haptic, no sound, no ripple, no visual state change, on success or failure.
 *
 * `CoverScanTest` fails the build if a cover file names `combinedClickable`,
 * `performHapticFeedback` or a toast.
 *
 * **A gesture that does not fire leaves the control alone.** These do not consume the
 * pointer until the moment they fire, so a short press on `=` still computes and a
 * single tap on a header still does whatever the header does. Only a completed
 * trigger consumes, so the control's own action does not also run on the way out.
 *
 * [silentPatternGrid] — `pattern-cover-trigger` — is the odd one out in one way:
 * every other handler here fires only on a rare, deliberate gesture (a two-second
 * hold, three taps), so "consume only on success" costs nothing observable most of
 * the time. A drawn pattern has no timing tell to hide behind — every drag ends in a
 * pointer-up — so its floor (five nodes, below which nothing is reported at all) is
 * what does the job silence usually does, and it is a geometric guarantee rather
 * than a probabilistic one: [gridNodeAt]'s doc comment has the bound.
 */

/** How long a trigger press must be held. Longer than the platform's own long-press. */
const val LONG_PRESS_MS = 2_000L

/** How long a triple tap may take, end to end. */
private const val TRIPLE_TAP_MS = 900L

/**
 * Fire [onTriggered] when the pointer is held for [LONG_PRESS_MS].
 *
 * A hold that falls short does nothing at all — not a toast, not a shake, not a
 * "wrong code", because every one of those confirms that a trigger exists and
 * therefore that a vault does.
 */
fun Modifier.silentLongPress(onTriggered: () -> Unit): Modifier = pointerInput(onTriggered) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val heldLongEnough = try {
            withTimeout(LONG_PRESS_MS) { waitForUpOrCancellation() }
            false
        } catch (_: PointerEventTimeoutCancellationException) {
            true
        }
        if (heldLongEnough) {
            // Consume only now, so the control's own click does not also run as the
            // finger lifts. Until this point the gesture was never ours.
            currentEvent.changes.forEach { it.consume() }
            onTriggered()
            // Drain the rest of the gesture so the release is not delivered either.
            waitForUpOrCancellation()
        }
    }
}

/**
 * [silentLongPress] for a control that handles its own gestures — the recorder's
 * search field.
 *
 * ***A text field long-presses for itself.*** It starts text selection at its own
 * threshold and consumes the pointer doing it, which cancels [silentLongPress] before
 * the trigger's own hold has elapsed: the gesture would simply never fire, on the one
 * cover whose trigger is performed in a text box.
 *
 * So this observes the **initial** pass and never asks whether anything was consumed.
 * The field keeps its own behaviour — the selection toolbar appears exactly as it
 * would in any other app, which is the point: an ordinary long press in a search box
 * must look ordinary. Nothing here consumes, and nothing here reacts.
 */
fun Modifier.silentLongPressOverText(onTriggered: () -> Unit): Modifier =
    pointerInput(onTriggered) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val held = try {
                withTimeout(LONG_PRESS_MS) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.none { it.pressed }) break
                    }
                    false
                }
            } catch (_: PointerEventTimeoutCancellationException) {
                true
            }
            if (held) onTriggered()
        }
    }

/**
 * Observes a pattern drawn across the whole composable's bounds and fires [onDrawn]
 * once it reaches [Trigger.MIN_DOTS] nodes — `pattern-cover-trigger`. Below the
 * floor **nothing is reported at all**: no partial path, no near-miss, nothing. That
 * is what keeps an ordinary scroll from ever becoming a candidate — the geometry
 * [gridNodeAt] documents guarantees a straight drag never reaches more than three
 * nodes, so the floor alone is the filter, with nothing timed and nothing counted
 * for it to slip past.
 *
 * Observed on [PointerEventPass.Initial], [silentLongPressOverText]'s choice and its
 * reason: whatever the grid sits over — a scrollable list, a search field, a
 * transport control — sees every event exactly as it would with no trigger
 * configured, because this never consumes below the floor and the event has already
 * been read here before the child underneath gets to react to it.
 *
 * **With [onProgress] set — setup hosting the cover, or a live cover whose dots the owner's
 * hold or the calculator's sequence brought up (`cover-hold-dots`, `calculator-dots-methods`) —
 * every change is consumed after it is read**, so
 * nothing beneath reacts and a target does not move while it is being aimed at. A live cover
 * with its dots hidden leaves [onProgress] null and never takes this branch.
 *
 * **A completed candidate does consume its final event**, so the control under the
 * last node does not also fire on the way out — the same rule [silentLongPress]
 * already follows, and for the same reason: the raw criterion firing is what
 * suppresses the ordinary action, not whether the shape goes on to match a stored
 * trigger, which this function has no way to know and does not ask.
 */
fun Modifier.silentPatternGrid(
    /**
     * The growing node list, called only when it actually changes — a new node
     * entered, or a fresh gesture starting over. **Only a shown grid passes this** —
     * setup, or a live cover's revealed dots; a live cover with its dots hidden leaves it
     * null, so there is nothing to switch off there and nobody has to remember to set a
     * flag.
     */
    onProgress: ((List<Int>) -> Unit)? = null,
    onDrawn: (List<Int>) -> Unit,
): Modifier =
    pointerInput(onProgress, onDrawn) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Read fresh per gesture rather than once for the whole pointerInput
            // block, so a size change between gestures (a config change while no
            // pattern is being drawn) cannot leave this hit-testing against a
            // stale width and height.
            val width = size.width.toFloat()
            val height = size.height.toFloat()
            val visited = mutableListOf<Int>()
            fun note(x: Float, y: Float) {
                val node = gridNodeAt(x, y, width, height) ?: return
                if (node !in visited) {
                    visited += node
                    onProgress?.invoke(visited.toList())
                }
            }
            // Reported even when the down lands on no node, so a fresh gesture
            // visibly starts from nothing rather than leaving the previous
            // attempt's shape on screen until the finger reaches its first node.
            onProgress?.invoke(emptyList())
            note(down.position.x, down.position.y)
            // A shown grid only (`onProgress != null`): read first, then consumed, so the
            // cover beneath does not scroll, press or take focus under the finger.
            if (onProgress != null) down.consume()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (onProgress != null) event.changes.forEach { it.consume() }
                if (!change.pressed) {
                    if (visited.size >= Trigger.MIN_DOTS) {
                        change.consume()
                        onDrawn(visited.toList())
                    }
                    break
                }
                // Every sample, not just the latest: moves that arrive inside one frame
                // are batched into a single event, and reading only its last position
                // skips any node the finger crossed in between — Android's own
                // `LockPatternView` walks the history for the same reason.
                change.historical.forEach { note(it.position.x, it.position.y) }
                note(change.position.x, change.position.y)
            }
        }
    }

/**
 * `cover-hold-dots` D2. Calls [onHold] when one pointer goes down in the hold [area] — one of
 * the 12 cells `holdAreaAt` divides the cover's bounds into, measured at the touch — and stays
 * within the platform's touch slop for [LONG_PRESS_MS]. Null [area]: inert.
 *
 * ***A press anywhere else is not ours, and nothing here touches it*** — no consumption, no
 * report — so the control beneath behaves exactly as it does with no trigger configured. A
 * second pointer, movement past the slop or a lift before [LONG_PRESS_MS] in the area itself
 * end it with nothing, the same way.
 *
 * Read at [PointerEventPass.Initial] with consumption ignored, [silentLongPressOverText]'s
 * choice and its reason: the recorder's search field selects text under the finger at the
 * platform's own long-press threshold and consumes doing it, and the hold must still complete.
 * Completed, it consumes every event to the lift, so the control under it does not also act
 * on release.
 *
 * [active] is read at each touch, not keyed on: the dots coming up while the finger is still
 * down must not cancel the drain that keeps the release from the control beneath. Nothing
 * here performs a haptic — the dots are the response.
 */
fun Modifier.silentHold(area: Int?, active: () -> Boolean, onHold: () -> Unit): Modifier =
    if (area == null) this else pointerInput(area, onHold) {
        detectHold(accept = { it == area && active() }, onHeld = { onHold() })
    }

/**
 * The hold, for setup's hold steps — `cover-hold-dots` D3. Any cell is accepted; [onPress]
 * says which, as the finger lands, so the step can fill it; [onRelease] says the hold ended
 * short — a lift, a slide or a second finger; [onHeld] says it completed, at 2 seconds with the
 * finger still down; [onLifted] says that finger came up after it. Keyed on nothing:
 * the caller passes callbacks that read current state, so a recomposition mid-hold never
 * restarts it.
 */
fun Modifier.holdToChoose(
    onPress: (Int) -> Unit,
    onRelease: () -> Unit,
    onHeld: (Int) -> Unit,
    onLifted: (Int) -> Unit,
): Modifier = pointerInput(Unit) {
    detectHold(accept = { true }, onPress = onPress, onRelease = onRelease, onHeld = onHeld, onLifted = onLifted)
}

/** One hold detector for the live cover and setup, so what setup records is what the cover hears. */
private suspend fun PointerInputScope.detectHold(
    accept: (Int) -> Boolean,
    onPress: (Int) -> Unit = {},
    onRelease: () -> Unit = {},
    onHeld: (Int) -> Unit,
    onLifted: (Int) -> Unit = {},
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val area = holdAreaAt(down.position.x, down.position.y, size.width.toFloat(), size.height.toFloat())
        if (!accept(area)) return@awaitEachGesture
        onPress(area)
        val slop = viewConfiguration.touchSlop
        val held = try {
            withTimeout(LONG_PRESS_MS) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if (event.changes.any { it.id != down.id && it.pressed }) break
                    if ((change.position - down.position).getDistance() > slop) break
                }
                false
            }
        } catch (_: PointerEventTimeoutCancellationException) {
            true
        }
        if (!held) {
            onRelease()
            return@awaitEachGesture
        }
        onHeld(area)
        // Ours from here to the lift: every event is taken before the control beneath sees it.
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
        onLifted(area)
    }
}

/**
 * Fire [onTriggered] on three taps inside [TRIPLE_TAP_MS].
 *
 * The count resets rather than accumulating across a pause, so idle tapping never
 * arrives at a trigger by drifting into it.
 */
fun Modifier.silentTripleTap(onTriggered: () -> Unit): Modifier = pointerInput(onTriggered) {
    var taps = 0
    var firstTapMs = 0L
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        waitForUpOrCancellation() ?: return@awaitEachGesture

        val now = System.currentTimeMillis()
        if (taps == 0 || now - firstTapMs > TRIPLE_TAP_MS) {
            taps = 1
            firstTapMs = now
        } else {
            taps++
        }
        if (taps >= 3) {
            taps = 0
            onTriggered()
        }
    }
}
