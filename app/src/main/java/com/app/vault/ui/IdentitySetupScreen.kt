package com.app.vault.ui

import android.graphics.Bitmap
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.vault.BiometricWrap
import com.app.vault.CalculatorMethod
import com.app.vault.Cover
import com.app.vault.HOLD_AREAS
import com.app.vault.HOLD_COLUMNS
import com.app.vault.HOLD_ROWS
import com.app.vault.LauncherIdentity
import com.app.vault.R
import com.app.vault.Trigger
import com.app.vault.holdPlace
import com.app.vault.ui.theme.Spacing
import com.app.vault.ui.theme.VaultColors
import com.app.vault.words
import kotlinx.coroutines.launch
import com.app.vault.localWords

/**
 * Recording the trigger that opens a cover, and confirming it.
 *
 * ***A trigger is performed, never typed.*** The screen this replaced asked for a
 * space-separated key list — `7 3 sin` — and switched the launcher icon on the next
 * tap, so the first time a user performed their trigger was on a phone whose only
 * launcher entry had already become the cover. There is no recovery from a
 * misremembered one, deliberately: every escape hatch on a cover is a published
 * backdoor. So the gesture is produced twice, on the real cover, and the two must
 * match before anything is stored.
 *
 * The step, both performances, both holds and the running count all live in `remember`
 * **here**. The calculator opens on [Step.Choose], which offers three methods
 * (`calculator-dots-methods` D6), and runs the steps of the one chosen:
 *
 * - **Button sequence** — the sequence, recorded and confirmed;
 * - **Button sequence, then pattern** — the sequence, then the pattern on the calculator: a
 *   second [Stage] through the same [Step.Record] and [Step.Confirm];
 * - **Hold, then pattern** — the music player's hold steps over the calculator, then the pattern:
 *   the order the identity is opened in.
 *
 * The music player and the voice recorder have one method and no choice (`cover-hold-dots` D3):
 * they open on where to hold — [Step.Hold], [Step.HoldAgain], [Step.HoldMismatch] — and once the
 * holds match record the pattern, held and then drawn as the live cover is. Whatever is confirmed waits, stored
 * nowhere, until the method's last confirmation matches. Nothing is handed on until then.
 * `MainActivity` holds only which identity is being set up. Composable-local state
 * dies with the screen, so "abandoning stores nothing and switches nothing" is true
 * by construction rather than by a cleanup somebody has to remember to write — the
 * argument `LauncherIdentity.reconcile` already makes for having no
 * "switch in progress" flag.
 *
 * ***Two steps used to sit in front of these and neither asked anything.*** A preview
 * carrying six paragraphs for the music player, and a grant step whose "Allow access"
 * button did nothing but raise the system dialog that asks the same question. The
 * preview's words moved to where each is decided — the switch's cost to
 * [IdentityCommitSheet], the comparison between covers to [IdentityScreen]'s music
 * player row, the concealment boundary to that screen's disclosure. The grant step
 * simply went: the dialog is now raised by the press that chooses the cover, and a
 * refusal is answered on the sheet afterwards. By the time this screen draws, a cover
 * that needs a grant has it.
 *
 * **The trigger is not the credential.** It reveals the unlock screen; the vault's
 * secret still opens the vault. That is why a short one is safe here and would not be
 * safe as a secret. The hold area is not a secret either: it keeps the dots from being found
 * by casual pressing, and the pattern on them is judged as any trigger is.
 *
 * ***This screen is exempt from the silence rule, and that is deliberate.*** A live
 * cover must not react to a gesture, because a reaction tells whoever is holding the
 * phone that a trigger exists. Here the user is already inside the vault, on a screen
 * titled for the purpose; there is nothing left to conceal, and a setup screen that
 * said nothing would not be discreet, it would be broken — which is what the old one
 * was, acknowledging no press until the user committed a long-press and found out
 * whether any of it had counted. Nothing here consults the limiter either, so
 * rehearsing never spends the user's own.
 */
@Composable
fun IdentitySetupScreen(
    identity: LauncherIdentity,
    /**
     * Called **once**, when the identity is ready to switch — with the confirmed trigger
     * for a cover identity, or null for one that opens the vault directly; the confirmed hold
     * area, `0..11`, where the method has a hold; and the second stage's pattern on **Button
     * sequence, then pattern** (`calculator-dots-methods` D6), null otherwise. On a method with a
     * hold it is called when the pattern's confirmation matches, the holds having matched before it.
     *
     * The caller stores and switches but **leaves this screen up**, because
     * [Step.Done] describes the way back in and that description is only true once
     * the launcher entry has actually changed.
     */
    onComplete: (Trigger?, Int?, Trigger.Pattern?) -> Unit,
    /** Dismiss [Step.Done]. The switch has already happened by then. */
    onFinish: () -> Unit,
    /** Leave before the switch. Stores nothing, switches nothing. */
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether this cover's runtime grant has been given — `music-recorder-covers`.
     *
     * Handed to the hosted cover so it draws its ordinary state rather than its
     * no-access one. It is **not** a gate here any more: the grant is asked for by
     * [IdentityCommitSheet]'s button and a refusal never reaches this screen, so a
     * cover that arrives here already has what it needs. It stays a parameter because
     * a grant can be revoked from the notification shade while this screen is up, and
     * a cover drawn as though it still had access would rehearse the user against a
     * library that is no longer there.
     */
    grantGiven: Boolean = true,
    /**
     * `cover-hold-dots` D3. A hold step's 2 seconds completed: the activity performs `CONFIRM`.
     * Its call and not this screen's, because this screen hosts the covers and is held, with them,
     * to `CoverScanTest`'s rule that no cover file performs a haptic.
     */
    onHoldConfirmed: () -> Unit = {},
) {
    var step by remember {
        mutableStateOf(
            when (identity.cover) {
                null -> Step.Done
                Cover.Calculator -> Step.Choose
                // `cover-hold-dots` D3: one method left, so nothing to choose. Opened by holding the
                // spot first, so set up by choosing it first.
                Cover.MusicPlayer, Cover.VoiceRecorder -> Step.Hold
            },
        )
    }
    /** The calculator's chosen method — D6. Kept across Back to [Step.Choose]. */
    var method by remember { mutableStateOf(CalculatorMethod.Sequence) }
    /** What [Step.Record] and [Step.Confirm] are recording: the calculator's sequence, or a pattern. */
    var stage by remember {
        mutableStateOf(if (identity.cover == Cover.Calculator) Stage.Sequence else Stage.Pattern)
    }
    var recorded by remember { mutableStateOf<Trigger?>(null) }
    /** The confirmed trigger — the sequence where the method has one — for [Step.Done] to describe. */
    var confirmed by remember { mutableStateOf<Trigger?>(null) }
    /** The second stage's confirmed pattern, on **Button sequence, then pattern**. */
    var dots by remember { mutableStateOf<Trigger.Pattern?>(null) }
    /** `cover-hold-dots` D3. The first hold's area, until the second is judged against it. */
    var firstHold by remember { mutableStateOf<Int?>(null) }
    /** The confirmed hold area, for [Step.Done] to describe. */
    var hold by remember { mutableStateOf<Int?>(null) }
    /** How much of the current performance the cover is holding. Zero on every step change. */
    var performed by remember { mutableStateOf(0) }
    /**
     * Set when a completed performance fell under the floor. Distance, never blame.
     *
     * ***Cleared by a capture or a step change, and by nothing else.*** It cannot be
     * cleared on the next press, because on the calculator there always is one: a long
     * press on `=` fires the hold *and then* the release fires `=`'s own click, so the
     * gesture that falls short is followed immediately by a key landing behind it. A
     * notice cleared by progress would be wiped by the very gesture that raised it.
     */
    var short by remember { mutableStateOf(false) }

    // An identity with no cover has nothing to record, so the screen opens on its last
    // step — and the switch it describes has to have happened by the time it draws.
    LaunchedEffect(Unit) { if (identity.cover == null) onComplete(null, null, null) }

    /**
     * Titled and mismatched as the second of two. Every pattern stage is: row 2's comes after its
     * sequence, and every hold method's after its holds.
     */
    val secondStage = stage == Stage.Pattern

    // `pattern-cover-trigger`. `short` never becomes true for a pattern, on any cover
    // — `CoverGestures.silentPatternGrid` never calls `onTrigger` at all below the
    // floor, so `capture` never sees a short pattern to reject. That is deliberate
    // and not a gap: a sub-floor drag is indistinguishable from an ordinary scroll of
    // whatever the grid sits over, in setup exactly as on a live cover, so a "too
    // short" message here would fire on the user's own scrolling. The live count and
    // the drawn path above the cover already say everything a message would —
    // "2 more to go" still on screen when the finger lifts is the short notice.
    fun capture(gesture: Trigger) {
        if (!gesture.valid) {
            short = true
            return
        }
        when (step) {
            Step.Record -> {
                recorded = gesture
                short = false
                step = Step.Confirm
            }
            // A mismatch discards **both** performances. Retrying against a retained
            // first one lets luck choose between two remembered variants, and
            // rehearses the user against a target they cannot see.
            Step.Confirm -> if (gesture == recorded) {
                short = false
                recorded = null
                when {
                    // `cover-hold-dots` D3. The holds were confirmed before the pattern, so this
                    // match is the method's last.
                    identity.cover != Cover.Calculator || method == CalculatorMethod.HoldThenPattern -> {
                        confirmed = gesture
                        onComplete(gesture, hold, null)
                        step = Step.Done
                    }
                    // D6. The sequence is kept, stored nowhere, while the pattern is recorded.
                    method == CalculatorMethod.SequenceThenPattern && stage == Stage.Sequence -> {
                        confirmed = gesture
                        stage = Stage.Pattern
                        step = Step.Record
                    }
                    method == CalculatorMethod.SequenceThenPattern -> {
                        val pattern = gesture as Trigger.Pattern
                        dots = pattern
                        onComplete(confirmed, null, pattern)
                        step = Step.Done
                    }
                    else -> {
                        confirmed = gesture
                        onComplete(gesture, null, null)
                        step = Step.Done
                    }
                }
            } else {
                recorded = null
                short = false
                step = Step.Mismatch
            }
            Step.Choose, Step.Mismatch, Step.Hold, Step.HoldAgain, Step.HoldMismatch, Step.Done -> Unit
        }
    }

    // `cover-hold-dots` D3. Two holds in the same cell confirm it; two in different cells
    // discard both, as two different patterns do. A confirmed area is kept, stored nowhere,
    // while the pattern is recorded.
    fun held(area: Int) {
        when (step) {
            Step.Hold -> {
                firstHold = area
                step = Step.HoldAgain
            }
            Step.HoldAgain -> if (area == firstHold) {
                hold = area
                step = Step.Record
            } else {
                firstHold = null
                step = Step.HoldMismatch
            }
            else -> Unit
        }
    }

    when (step) {
        Step.Choose -> VaultScaffold(
            title = stringResource(R.string.setup_cover_choose_title),
            modifier = modifier,
            onBack = onCancel,
        ) {
            Choose(
                method = method,
                onSelect = { method = it },
                onContinue = {
                    stage = if (method == CalculatorMethod.HoldThenPattern) Stage.Pattern else Stage.Sequence
                    step = if (method == CalculatorMethod.HoldThenPattern) Step.Hold else Step.Record
                },
            )
        }

        Step.Record, Step.Confirm -> VaultScaffold(
            // D6. A pattern stage says the owner has moved on — past row 2's sequence, or past the
            // holds — so it does not read as the first stage repeating.
            title = when {
                step == Step.Record -> stringResource(if (secondStage) R.string.setup_cover_draw_shape else R.string.setup_cover_record)
                else -> stringResource(if (secondStage) R.string.setup_cover_draw_again else R.string.setup_cover_do_again)
            },
            modifier = modifier,
            // The sequence's Record is the one Record reached before anything has been
            // performed, so on the calculator it goes back to the choice; every pattern Record
            // comes after a confirmed sequence or confirmed holds, and abandons setup.
            onBack = if (step == Step.Record && identity.cover == Cover.Calculator && !secondStage) {
                { step = Step.Choose }
            } else {
                onCancel
            },
            // The calculator's keypad needs the height, so this step frames its own
            // content rather than sitting in a scrolling column.
            scrollable = false,
        ) {
            Progress(identity.cover, stage, step, performed, short)
            // Tagged so a device test can find the exact area a pattern cover's
            // grid occupies during setup — it is nested below this header, not the
            // full root, so `onRoot()` would compute node centres against the wrong
            // bounds. `pattern-cover-trigger`.
            Box(Modifier.fillMaxWidth().weight(1f).testTag(COVER_HOST_TAG)) {
                // Remounted on every step change, so the confirming performance starts
                // from a cleared cover and cannot be completed by what the recording
                // left on screen.
                key(step) {
                    // Belongs with the remount: the cover's buffer is gone, and a count
                    // still showing the last step's total would be describing nothing.
                    LaunchedEffect(Unit) { performed = 0; short = false }
                    when (identity.cover) {
                        Cover.Calculator -> if (stage == Stage.Sequence) {
                            CalculatorCover(
                                onTrigger = { capture(it) },
                                onProgress = {
                                    performed = it
                                    // Zero inside a step is only ever `AC`, which starts the
                                    // performance again — so the notice about the last one
                                    // goes with it. The click that follows a short hold on
                                    // `=` reports at least one, so it cannot clear it.
                                    if (it == 0) short = false
                                },
                            )
                        } else {
                            // D6. The grid shown and the keypad held still beneath it.
                            CalculatorCover(
                                onTrigger = { capture(it) },
                                dotsProgress = { performed = it },
                            )
                        }
                        // `persist = false` on both, and it is the requirement rather
                        // than a nicety: no cover control may write while setup is
                        // hosting it. The recorder's trigger *is* the word typed into
                        // its search field, and this is the one moment the app tells
                        // the user to type it — a recording filed instead of the
                        // gesture would leave that session on disk, created by the
                        // screen meant to prevent exactly that.
                        Cover.MusicPlayer -> MusicCover(
                            hasAccess = grantGiven,
                            onTrigger = { capture(it) },
                            persist = false,
                            onProgress = { performed = it },
                        )
                        Cover.VoiceRecorder -> RecorderCover(
                            hasPermission = grantGiven,
                            onTrigger = { capture(it) },
                            persist = false,
                            onProgress = { performed = it },
                        )
                        // Unreachable: a cover-less identity opens on Step.Done.
                        null -> Unit
                    }
                }
            }
        }

        Step.Mismatch -> VaultScaffold(
            title = stringResource(R.string.setup_cover_mismatch_title),
            modifier = modifier,
            onBack = onCancel,
        ) {
            // Start over returns to the stage that failed, keeping what was confirmed before it.
            Mismatch(
                stage,
                kept = when {
                    !secondStage -> null
                    hold != null -> R.string.setup_cover_spot_kept
                    else -> R.string.setup_cover_sequence_kept
                },
            ) {
                recorded = null
                step = Step.Record
            }
        }

        // `cover-hold-dots` D3. The first hold is the method's first step: on the calculator Back
        // returns to the choice, and on the music player and the voice recorder, with nothing
        // before it, abandons. Back on the second hold abandons. Remounted per step, so the
        // second hold starts from an empty grid.
        Step.Hold, Step.HoldAgain -> VaultScaffold(
            title = stringResource(if (step == Step.Hold) R.string.setup_cover_hold_title else R.string.setup_cover_hold_again_title),
            modifier = modifier,
            onBack = if (step == Step.Hold && identity.cover == Cover.Calculator) {
                { step = Step.Choose }
            } else {
                onCancel
            },
            scrollable = false,
        ) {
            key(step) {
                HoldStep(
                    cover = identity.cover!!,
                    again = step == Step.HoldAgain,
                    grantGiven = grantGiven,
                    onConfirmed = onHoldConfirmed,
                    onHeld = ::held,
                )
            }
        }

        Step.HoldMismatch -> VaultScaffold(
            title = stringResource(R.string.setup_cover_hold_mismatch_title),
            modifier = modifier,
            onBack = onCancel,
        ) {
            Body(stringResource(R.string.setup_cover_hold_mismatch))
            Button(
                // Nothing comes before the holds, so nothing is kept: setup starts again at the first.
                onClick = { step = Step.Hold },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xl),
            ) {
                Text(stringResource(R.string.setup_cover_start_over))
            }
        }

        Step.Done -> VaultScaffold(
            title = stringResource(R.string.setup_cover_done_title),
            modifier = modifier,
            onBack = onFinish,
        ) {
            Done(
                cover = identity.cover,
                trigger = confirmed,
                hold = hold,
                dots = dots,
                onFinish = onFinish,
            )
        }
    }
}

private enum class Step { Choose, Record, Confirm, Mismatch, Hold, HoldAgain, HoldMismatch, Done }

/** What [Step.Record], [Step.Confirm] and [Step.Mismatch] are about — `calculator-dots-methods` D6. */
private enum class Stage { Sequence, Pattern }

/**
 * **Choose where to hold** and **Hold it again** — `cover-hold-dots` D3. The first steps of every
 * method with a hold, before the pattern, because the spot is held before the dots appear.
 *
 * Laid out as the recording step is — the header line above, the cover hosted beneath it — so
 * the owner chooses the spot on the cover they will hold it on. The cover is held still and is
 * not read out: here it is the ground the spot is chosen on, not something to use. Over it, the
 * 12 cells the live cover divides its screen into, so "a spot" is something the owner can see.
 */
@Composable
private fun ColumnScope.HoldStep(
    cover: Cover,
    again: Boolean,
    grantGiven: Boolean,
    onConfirmed: () -> Unit,
    onHeld: (Int) -> Unit,
) {
    /** The last hold ended before 2 seconds — the line says so until the next touch. */
    var early by remember { mutableStateOf(false) }
    HoldHeader(again, early)
    Box(Modifier.fillMaxWidth().weight(1f).testTag(COVER_HOST_TAG)) {
        Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            when (cover) {
                Cover.MusicPlayer -> MusicCover(hasAccess = grantGiven, onTrigger = {}, persist = false)
                Cover.VoiceRecorder -> RecorderCover(hasPermission = grantGiven, onTrigger = {}, persist = false)
                // `calculator-dots-methods` D6, **Hold, then pattern**: no sequence, and a reporter
                // that does nothing, which is what marks the calculator as hosted by setup — the
                // vault's bar keeps its own icons.
                Cover.Calculator -> CalculatorCover(onTrigger = {}, sequence = false, onProgress = {})
            }
        }
        HoldCells(
            onPress = { early = false },
            onEarly = { early = true },
            onConfirmed = onConfirmed,
            onHeld = onHeld,
            modifier = Modifier.matchParentSize(),
        )
    }
}

private val HOLD_LINE = R.string.setup_cover_hold_line
private val HOLD_AGAIN_LINE = R.string.setup_cover_hold_again_line
private val HOLD_EARLY_LINE = R.string.setup_cover_hold_early_line

/**
 * D3's header line. As tall as its longest line throughout, so the cover beneath never moves
 * under a finger as the line changes — the recording step's rule and its reason. Announced
 * politely when it changes.
 */
@Composable
private fun HoldHeader(again: Boolean, early: Boolean) {
    Box(Modifier.padding(horizontal = Spacing.screenInset, vertical = Spacing.md)) {
        listOf(HOLD_LINE, HOLD_AGAIN_LINE, HOLD_EARLY_LINE).forEach {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.alpha(0f).clearAndSetSemantics {},
            )
        }
        Text(
            text = stringResource(
                when {
                    early -> HOLD_EARLY_LINE
                    again -> HOLD_AGAIN_LINE
                    else -> HOLD_LINE
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = VaultColors.OnSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * The 12 cells over the hosted cover, and the hold that chooses one — D3.
 *
 * The pattern grid's scrim, each cell outlined in white at 24%. A finger down fills its cell
 * from the centre outward in `Primary` at 24% over the 2 seconds, so the hold is seen accruing
 * from the moment it lands; lifted early, the fill drains in 150ms and the header says to keep
 * holding. At 2 seconds the cell stays filled and [onConfirmed] has the activity perform the
 * confirm haptic; the step
 * advances when the finger comes up, so the filled cell is what the owner sees last. Where the
 * animator duration scale is 0 a ring at the cell's centre shows the same progress, and the
 * drain snaps.
 *
 * The hold is `holdToChoose`, the live cover's own detector, so what is recorded here is what
 * the cover hears: one finger, within the touch slop, for 2 seconds. Each cell is a node named
 * by its place, so TalkBack's double-tap-and-hold passes the press through to the cell it is on.
 */
@Composable
private fun HoldCells(
    onPress: () -> Unit,
    onEarly: () -> Unit,
    onConfirmed: () -> Unit,
    onHeld: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reduced = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var cell by remember { mutableStateOf<Int?>(null) }
    var done by remember { mutableStateOf(false) }
    // The detector is keyed on nothing, so it reads the newest callbacks through these.
    val press by rememberUpdatedState(onPress)
    val early by rememberUpdatedState(onEarly)
    val chosen by rememberUpdatedState(onHeld)
    val confirmed by rememberUpdatedState(onConfirmed)

    val scrim = VaultColors.Scrim.copy(alpha = HOLD_SCRIM_ALPHA)
    val outline = Color.White.copy(alpha = HOLD_GUIDE_ALPHA)
    val fill = VaultColors.Primary.copy(alpha = HOLD_GUIDE_ALPHA)
    val ring = VaultColors.Primary
    val density = LocalDensity.current
    val linePx = with(density) { 1.dp.toPx() }
    val ringPx = with(density) { HOLD_RING_WIDTH.toPx() }
    val ringRadiusPx = with(density) { HOLD_RING_RADIUS.toPx() }

    Box(
        modifier
            .holdToChoose(
                onPress = { area ->
                    if (!done) {
                        cell = area
                        press()
                        scope.launch {
                            progress.snapTo(0f)
                            progress.animateTo(1f, tween(LONG_PRESS_MS.toInt(), easing = LinearEasing))
                        }
                    }
                },
                onRelease = {
                    if (!done) {
                        scope.launch {
                            if (reduced) progress.snapTo(0f) else progress.animateTo(0f, tween(HOLD_DRAIN_MS, easing = EaseIn))
                        }
                        early()
                    }
                },
                onHeld = { area ->
                    if (!done) {
                        done = true
                        cell = area
                        scope.launch { progress.snapTo(1f) }
                        confirmed()
                    }
                },
                onLifted = { area -> chosen(area) },
            )
            // Held still: every touch is taken here, before the hosted cover could see it.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(scrim)
            val w = size.width / HOLD_COLUMNS
            val h = size.height / HOLD_ROWS
            val active = cell
            if (active != null) {
                val cx = (active % HOLD_COLUMNS + 0.5f) * w
                val cy = (active / HOLD_COLUMNS + 0.5f) * h
                val p = progress.value
                if (reduced && !done) {
                    drawCircle(outline, ringRadiusPx, Offset(cx, cy), style = Stroke(ringPx))
                    drawArc(
                        color = ring,
                        startAngle = -90f,
                        sweepAngle = 360f * p,
                        useCenter = false,
                        topLeft = Offset(cx - ringRadiusPx, cy - ringRadiusPx),
                        size = Size(ringRadiusPx * 2, ringRadiusPx * 2),
                        style = Stroke(ringPx, cap = StrokeCap.Round),
                    )
                } else {
                    drawRect(fill, Offset(cx - w * p / 2, cy - h * p / 2), Size(w * p, h * p))
                }
            }
            // Each line once, so a shared edge is not drawn twice as bright.
            for (c in 0..HOLD_COLUMNS) {
                val x = (c * w).coerceIn(linePx / 2, size.width - linePx / 2)
                drawLine(outline, Offset(x, 0f), Offset(x, size.height), linePx)
            }
            for (r in 0..HOLD_ROWS) {
                val y = (r * h).coerceIn(linePx / 2, size.height - linePx / 2)
                drawLine(outline, Offset(0f, y), Offset(size.width, y), linePx)
            }
        }
        val words = localWords()
        Column(Modifier.matchParentSize()) {
            repeat(HOLD_ROWS) { r ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    repeat(HOLD_COLUMNS) { c ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .semantics { contentDescription = holdPlace(words, r * HOLD_COLUMNS + c) },
                        )
                    }
                }
            }
        }
    }
}

/** The pattern grid's scrim, so the cells sit on the ground the dots will. */
private const val HOLD_SCRIM_ALPHA = 0.55f

/** The cells' outlines (white) and the fill (`Primary`). */
private const val HOLD_GUIDE_ALPHA = 0.24f

/** Lifted early: the fill drains in this. */
private const val HOLD_DRAIN_MS = 150

/** Reduced motion's ring at the cell's centre. */
private val HOLD_RING_RADIUS = 20.dp
private val HOLD_RING_WIDTH = 3.dp

/**
 * The chosen area as a picture — `cover-hold-dots` D8: the 12 cells at 48×64dp, the chosen one
 * filled in `Primary`, the rest outlined in `OutlineVariant`. The sentence beside it names the
 * place, so the picture is not read out.
 */
@Composable
private fun HoldDiagram(area: Int, modifier: Modifier = Modifier) {
    val chosen = VaultColors.Primary
    val rest = VaultColors.OutlineVariant
    // The tag is for tests only: a test tag is not exposed to accessibility services.
    Canvas(modifier.size(48.dp, 64.dp).clearAndSetSemantics { testTag = HOLD_DIAGRAM_TAG }) {
        val w = size.width / HOLD_COLUMNS
        val h = size.height / HOLD_ROWS
        val gap = 2.dp.toPx()
        val line = 1.dp.toPx()
        val corner = CornerRadius(2.dp.toPx())
        for (i in 0 until HOLD_AREAS) {
            val topLeft = Offset(i % HOLD_COLUMNS * w + gap / 2, i / HOLD_COLUMNS * h + gap / 2)
            val cellSize = Size(w - gap, h - gap)
            if (i == area) {
                drawRoundRect(chosen, topLeft, cellSize, corner)
            } else {
                drawRoundRect(
                    rest,
                    topLeft + Offset(line / 2, line / 2),
                    Size(cellSize.width - line, cellSize.height - line),
                    corner,
                    style = Stroke(line),
                )
            }
        }
    }
}

/**
 * How this icon is to be opened, asked before anything is performed, on the calculator — the
 * one cover with a choice since `cover-hold-dots`. Three methods since `calculator-dots-methods`
 * D6, **Button sequence** selected on arrival; a tap selects, and the selection is the screen's,
 * so it survives Back to here.
 */
@Composable
private fun Choose(method: CalculatorMethod, onSelect: (CalculatorMethod) -> Unit, onContinue: () -> Unit) {
    Body(stringResource(BiometricWrap.CHOOSE_LEAD))
    Column(Modifier.selectableGroup().padding(top = Spacing.md)) {
        listOf(
            Triple(CalculatorMethod.Sequence, BiometricWrap.PLAIN_METHOD, BiometricWrap.PLAIN_METHOD_LINE),
            Triple(
                CalculatorMethod.SequenceThenPattern,
                BiometricWrap.SEQUENCE_PATTERN_METHOD,
                BiometricWrap.SEQUENCE_PATTERN_METHOD_LINE,
            ),
            Triple(CalculatorMethod.HoldThenPattern, BiometricWrap.HOLD_PATTERN_METHOD, BiometricWrap.HOLD_PATTERN_METHOD_LINE),
        ).forEach { (row, name, detail) ->
            ChoiceRow(
                name = stringResource(name),
                detail = stringResource(detail),
                selected = method == row,
                onClick = { onSelect(row) },
            )
        }
    }
    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xl),
    ) {
        Text(stringResource(R.string.action_continue))
    }
}

/**
 * How far the performance has got, said above the cover and updated on every press.
 *
 * ***The step this replaced acknowledged nothing.*** A user pressed four transport
 * controls, saw no response of any kind, and learned whether any of it had counted
 * only after committing the long-press — as a red paragraph. Enrolling a secret the
 * user cannot see is the problem a fingerprint or a face enrolment solves, and both
 * solve it the same way: the capture is the screen, and the progress is shown while it
 * accrues rather than judged once it is over.
 *
 * The bar carries no meaning the sentence above it does not — §6.4, and a progress bar
 * is the one control most likely to be the only thing a hurried reader looks at.
 */
@Composable
private fun Progress(cover: Cover?, stage: Stage, step: Step, performed: Int, short: Boolean) {
    val floor = floor(stage)
    Column(Modifier.padding(horizontal = Spacing.screenInset, vertical = Spacing.md)) {
        // Above the count and not instead of it. The count stays true — after a short
        // hold on the calculator it has already moved on — and this says what happened
        // to the gesture, which the count cannot.
        //
        // Not in the error colour and not phrased as a mistake: the user performed a
        // gesture, there was simply less of it than the floor.
        if (short) {
            Text(
                text = stringResource(R.string.setup_cover_too_short, unitCount(stage, floor)),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier
                    .padding(bottom = Spacing.xs)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        // ***Always as tall as the longest thing it says.*** The opening instruction
        // runs to two or three lines and the count to one, so a line sized to its own
        // text shrank on the first key or node — and the cover beneath jumped up
        // under a finger still drawing on it, landing the rest of a pattern on nodes
        // the grid had already moved away from. The longest lines are laid out
        // invisibly to hold the height; their semantics are cleared so nothing reads
        // them out or finds them.
        Box {
            listOf(opening(cover, stage, step), progressText(cover, stage, step, cap(stage))).forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.alpha(0f).clearAndSetSemantics {},
                )
            }
            Text(
                text = progressText(cover, stage, step, performed),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
            )
        }
        val fraction by animateFloatAsState(
            targetValue = if (floor == 0) 0f else (performed.toFloat() / floor).coerceAtMost(1f),
            label = "trigger-progress",
        )
        LinearProgressIndicator(
            progress = { fraction },
            color = VaultColors.Primary,
            trackColor = VaultColors.SurfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md)
                // The sentence above already says the count and what is still needed.
                // Announcing a percentage after it would read the same fact twice, in
                // the less useful of the two units.
                .clearAndSetSemantics {},
        )
    }
}

/**
 * The count line above the bar.
 *
 * Three things it can be saying, in the order a user meets them: what to do, how far in
 * they are, and that they are there and how to finish. A performance that fell short is
 * said above it by [Progress], because by then this line has already moved on.
 */
@Composable
private fun progressText(cover: Cover?, stage: Stage, step: Step, performed: Int): String {
    val floor = floor(stage)
    return when {
        performed == 0 -> opening(cover, stage, step)

        performed < floor ->
            stringResource(R.string.setup_cover_so_far, unitCount(stage, performed), floor - performed)

        performed >= cap(stage) ->
            stringResource(R.string.setup_cover_at_cap, unitCount(stage, performed), finish(stage))

        else -> stringResource(R.string.setup_cover_enough, unitCount(stage, performed), finish(stage))
    }
}

/** What to do, before there is any progress to report. */
@Composable
private fun opening(cover: Cover?, stage: Stage, step: Step): String =
    mainOpening(cover, stage, again = step == Step.Confirm)

@Composable
private fun mainOpening(cover: Cover?, stage: Stage, again: Boolean): String = when (cover) {
    Cover.Calculator -> when {
        stage == Stage.Sequence && again -> stringResource(R.string.setup_cover_keys_again)
        stage == Stage.Sequence -> stringResource(R.string.setup_cover_keys)
        // `calculator-dots-methods` D6. The music player's lines.
        else -> mainOpening(Cover.MusicPlayer, Stage.Pattern, again)
    }
    // `pattern-cover-trigger`. Drawn over the real cover, at the position and scale
    // the live cover keeps it at — see [PatternGridOverlay].
    Cover.MusicPlayer -> stringResource(if (again) R.string.setup_cover_shape_again else R.string.setup_cover_shape_music)
    Cover.VoiceRecorder -> stringResource(if (again) R.string.setup_cover_shape_again else R.string.setup_cover_shape_recorder)
    null -> ""
}

/** The gesture that ends the performance, named once the floor is met and not before. */
@Composable
private fun finish(stage: Stage): String = when (stage) {
    Stage.Sequence -> stringResource(R.string.setup_cover_finish_keys)
    // `pattern-cover-trigger`. A drawn path has no terminator to name — it ends when
    // the finger lifts, which no cover has a control for.
    Stage.Pattern -> stringResource(R.string.setup_cover_finish_shape)
}

/** The count in the stage's own units, so the sentence never says a bare number. */
@Composable
private fun unitCount(stage: Stage, n: Int): String = when (stage) {
    Stage.Sequence -> pluralStringResource(R.plurals.setup_cover_key_count, n, n)
    Stage.Pattern -> pluralStringResource(R.plurals.setup_cover_node_count, n, n)
}

private fun floor(stage: Stage): Int = when (stage) {
    Stage.Sequence -> Trigger.MIN_KEYS
    Stage.Pattern -> Trigger.MIN_DOTS
}

/**
 * Where each cover stops accumulating.
 *
 * The calculator and the player hold a rolling tail and drop from the front; a user
 * pressing past it is adding nothing, and the step says so rather than letting a
 * counter climb over a total that is no longer growing. The recorder's word has no cap.
 */
private fun cap(stage: Stage): Int = when (stage) {
    Stage.Sequence -> MAX_TRIGGER_KEYS
    // `pattern-cover-trigger`. The grid has nine nodes and no repeats, so nine is
    // where a pattern can no longer grow — not a rolling-tail cap like the
    // calculator's, an actual ceiling on the shape.
    Stage.Pattern -> Trigger.GRID_NODES
}

/**
 * Both performances are gone, and the step says so rather than implying one survived.
 *
 * ***What this replaced dropped the user straight back onto a live cover with a red
 * sentence above it*** — reading an error while standing on the control that produced
 * it, with no way to tell whether the app had kept the first attempt. The discard is
 * correct and is unchanged; what it needed was somewhere to be said.
 */
@Composable
private fun Mismatch(stage: Stage, @StringRes kept: Int?, onRetry: () -> Unit) {
    Body(
        stringResource(
            when (stage) {
                Stage.Sequence -> R.string.setup_cover_mismatch_sequence
                Stage.Pattern -> R.string.setup_cover_mismatch_pattern
            }
        ),
    )
    // `calculator-dots-methods` D6. A pattern stage: what was confirmed before it survives — row 2's
    // sequence, or the hold methods' spot.
    if (kept != null) Body(stringResource(kept))
    Body(stringResource(R.string.setup_cover_nothing_changed))

    Button(
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xl),
    ) {
        Text(stringResource(R.string.setup_cover_start_over))
    }
}

/**
 * The switch has already happened by the time this draws, which is why it can describe
 * the way back in — after this screen closes, the route to it runs through the very
 * gesture being described.
 *
 * ***One subject.*** It used to carry four paragraphs and a repeated icon-and-label
 * header, with the one sentence that cannot be recovered from sitting third of four on
 * the screen users tap through fastest. What the launcher now shows is on the launcher;
 * what the identity does and does not conceal is on the chooser's disclosure.
 *
 * ***The route is the chosen method's*** — `calculator-dots-methods` D7. The calculator's dots
 * work only once its sequence or its hold has brought them up, so its routes never say the
 * shape works on hidden dots; the music player's and the voice recorder's do.
 */
@Composable
private fun Done(
    cover: Cover?,
    trigger: Trigger?,
    hold: Int?,
    dots: Trigger.Pattern?,
    onFinish: () -> Unit,
) {
    if (cover == null || trigger == null) {
        Body(stringResource(R.string.setup_cover_done_vault))
    } else if (hold != null && trigger is Trigger.Pattern) {
        // `cover-hold-dots` D8. The hold, then the shape on the dots it brings up; the place is
        // shown as well as named, because a place is read faster as a picture.
        Body(stringResource(R.string.setup_cover_done_route))
        Row(Modifier.padding(top = Spacing.md)) {
            Text(
                text = holdRoute(hold, trigger.dots.size),
                style = MaterialTheme.typography.bodyMedium,
                color = VaultColors.OnSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            HoldDiagram(hold, Modifier.padding(start = Spacing.md))
        }
        if (cover != Cover.Calculator) Body(stringResource(R.string.setup_cover_done_hidden_dots))
        Remember()
    } else if (trigger is Trigger.Keys && dots != null) {
        // D7, **Button sequence, then pattern**: the sequence and the hold on `=`, then the shape.
        Body(stringResource(R.string.setup_cover_done_route))
        Body(
            pluralStringResource(
                R.plurals.setup_cover_route_keys_shape, dots.dots.size,
                trigger.keys.joinToString(", "), dots.dots.size,
            ),
        )
        Body(stringResource(R.string.setup_cover_wrong_key))
        Remember()
    } else {
        Body(stringResource(R.string.setup_cover_done_route))
        Body(route(trigger))
        Remember()
    }

    Button(
        onClick = onFinish,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xl),
    ) {
        Text(stringResource(R.string.action_done))
    }
}

/** The final step's last sentence, on every cover and method. */
@Composable
private fun Remember() {
    Body(stringResource(R.string.setup_cover_remember))
}

/**
 * The recorded gesture, read back as the sentence the user will act on.
 *
 * ***A pattern is described as a shape, not a route.*** "Press these five buttons in
 * this order" is a sentence someone can act on from words alone; nine numbered grid
 * points are not something anyone visualises from a sentence, and the shape was never
 * shown to be read back — `cover-trigger`'s "the app will not show it to you again"
 * applies here precisely because there is nothing safe to print. What can be said
 * honestly is where to draw and how many points it takes.
 */
@Composable
private fun route(trigger: Trigger): String = when (trigger) {
    is Trigger.Keys -> stringResource(R.string.setup_cover_route_keys, trigger.keys.joinToString(", "))
    is Trigger.Pattern -> pluralStringResource(R.plurals.setup_cover_route_shape, trigger.dots.size, trigger.dots.size)
}

/** D8's route for every method with a hold: where to hold, then what to draw. */
@Composable
private fun holdRoute(area: Int, points: Int): String =
    pluralStringResource(R.plurals.setup_cover_route_hold, points, holdPlace(localWords(), area), points)

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = VaultColors.OnSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.md),
    )
}

/**
 * The launcher entry's own icon, drawn from the same resource the manifest names, so
 * a preview cannot drift from what the launcher will actually show.
 *
 * Rasterised by hand rather than through `painterResource`, which parses XML as a
 * vector and throws on the `<adaptive-icon>` root every one of these has. The
 * alternative was a drawable-painter dependency for six lines of `Canvas`.
 */
@Composable
internal fun IdentityIcon(identity: LauncherIdentity, size: Dp = ICON_SIZE) {
    val resources = LocalResources.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    val image = remember(identity, sizePx) {
        resources.getDrawable(identity.iconRes, null)?.let { drawable ->
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(android.graphics.Canvas(bitmap))
            bitmap.asImageBitmap()
        }
    }
    if (image != null) {
        Image(
            bitmap = image,
            // The label sits beside it and says the same thing, so the icon is
            // decorative to a screen reader rather than read out twice.
            contentDescription = null,
            modifier = Modifier.size(size),
        )
    }
}

internal val ICON_SIZE = 64.dp

/**
 * Tags the area a cover is hosted in during [Step.Record] and [Step.Confirm] —
 * `pattern-cover-trigger`. A device test drives the pattern grid by computing node
 * centres from this node's own measured size, which is not the full root: the
 * progress header above it takes real space, and `onRoot()` would compute against
 * the wrong bounds.
 */
const val COVER_HOST_TAG = "identity-setup-cover-host"

/** Tags the final step's hold diagram — `cover-hold-dots` D8 — so a test can find and measure it. */
const val HOLD_DIAGRAM_TAG = "identity-setup-hold-diagram"
