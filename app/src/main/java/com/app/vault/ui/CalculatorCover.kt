package com.app.vault.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.app.vault.Calculator
import com.app.vault.Calculator.Token
import com.app.vault.Trigger
import com.app.vault.ui.theme.CalculatorColors
import com.app.vault.ui.theme.CalculatorTheme
import com.app.vault.ui.theme.CalculatorType
import androidx.compose.material3.Text as MaterialText
import com.app.vault.words
import com.app.vault.localWords

/**
 * The calculator cover — P2-24, re-skinned by P2-25.
 *
 * **A cover is worth exactly as much as it is convincing, and P2-24 built one that
 * computed correctly and did not look like an app anybody downloaded.** Twenty-four
 * identical rounded rectangles in the vault's grey, labelled `*` `/` `sqrt`, over a
 * readout in the app's monospace *code* face. The public advice for spotting a
 * disguised vault is one sentence long — *open it and try to use it as a calculator*
 * — and that screen failed it in the first ten seconds, before arithmetic entered
 * into it.
 *
 * So this screen is built the way the calculators on the store are built:
 *
 * - **Round keys.** Google Calculator's digits are circles at rest; iOS and Samsung
 *   are circles too. It is the calculator form, and a rounded-rectangle grid is the
 *   most visible thing it is not. The scientific block above is pills, smaller and
 *   denser — the arrangement HiPER, RealCalc and Google's own scientific panel share,
 *   because the digits are what a thumb hits and the functions are what an eye reads.
 * - **A warm accent on the operators**, from [CalculatorColors] and not from the
 *   vault's. A cover drawn out of the host's tokens changes when the host's
 *   appearance changes, which is a tell with a switch attached.
 * - **Four key roles.** Digits carry the lightest fill (Google 9.0's change, and the
 *   cheapest single thing that stops a keypad reading as uniform); `=` is the only
 *   filled key on the screen.
 * - **One flat ground, edge to edge**, with no app bar, no title, no divider and no
 *   container drawn around the readout.
 * - **Two display lines** — the expression as typed, and its running value beneath.
 *   `1 + 2` shows `1 + 2`, which is the tell P2-24 had and nothing on either store
 *   shares.
 *
 * **No history tape and no memory registers**, unchanged from P2-24 and not a
 * compromise: iOS carried no tape for a decade and every physical scientific
 * calculator on a desk has none, so its absence is invisible — while its presence
 * would write the trigger keystrokes into a list.
 *
 * **The trigger is the stored keystroke sequence completed by a long-press on `=`.**
 * Every key except `AC` contributes its symbol to that sequence, `DEL` included: it is a
 * keystroke log and not an expression, and a `DEL` that erased history would make any
 * trigger containing it unenterable. `AC` empties the sequence and adds nothing to it —
 * the one key whose ordinary meaning is "start again", so a wrong key is recovered on the
 * spot and the screen does nothing a calculator's `AC` does not. A hold that falls
 * short simply evaluates, because that is what `=` is for. [onTrigger] is handed the
 * sequence and decides; this screen never learns whether it was right, so there is
 * nothing here to leak.
 *
 * ***The dots — `calculator-dots-methods`.*** On **Button sequence, then pattern** the right
 * sequence brings them up; on **Hold, then pattern** a 2-second hold in the owner's area does,
 * as on the music player, and there is no sequence — [sequence] is false and `=` is a plain
 * `=`. Either way `showDots` draws them exactly as the music player draws its own, holds the
 * keypad still beneath them, and reports the pattern drawn on them. **There is no grid while
 * they are hidden**, unlike the pattern covers' invisible one: a drag across a keypad is key
 * presses, and a grid that read them would turn typing into attempts against the limiter.
 */
@Composable
fun CalculatorCover(
    onTrigger: (Trigger) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * How many keystrokes the sequence currently holds, reported on every press.
     *
     * ***Null on a live cover, and that is what keeps the cover silent.*** A launcher
     * entry that visibly reacted to a gesture would tell whoever is holding the phone
     * that a gesture exists. Only trigger setup passes a reporter, so a live cover has
     * nothing to switch off and nobody has to remember to set a flag.
     */
    onProgress: ((Int) -> Unit)? = null,
    /** Whether a hold on `=` reports the sequence. False on **Hold, then pattern**: `=` is a plain key. */
    sequence: Boolean = true,
    /** The live dots — after the sequence or the hold — drawn and held as setup draws and holds them. */
    showDots: Boolean = false,
    /** A refused pattern on the shown dots: the error state, and no further path drawn. */
    dotsError: Boolean = false,
    /** The stored hold area, `0..11`, on **Hold, then pattern** — null otherwise, and the hold is inert. */
    holdArea: Int? = null,
    /** A 2-second hold in [holdArea]: the activity brings up the dots. */
    onHold: () -> Unit = {},
    /**
     * Setup's pattern steps: the grid is shown, the keypad held still, and the count of nodes
     * reported as each is reached. Null on a live cover, for [onProgress]'s reason.
     */
    dotsProgress: ((Int) -> Unit)? = null,
    /**
     * `recents-shows-identity` D3. Drawn as the recent-apps card's picture: a fresh display,
     * no trigger, no hold and no dots. Nor the system bars — this copy sits beneath the live
     * screen for the whole session, and the bars are the live screen's.
     */
    still: Boolean = false,
) {
    CalculatorTheme {
        // Live only. While setup hosts the keypad the top of the window is the vault's bar,
        // and the calculator's icons over it went dark-on-dark.
        if (onProgress == null && dotsProgress == null && !still) SystemBarsFollowTheCalculator()
        CalculatorScreen(
            onTrigger = if (still) ({}) else onTrigger,
            onProgress = onProgress.takeIf { !still },
            sequence = sequence && !still,
            showDots = showDots && !still,
            dotsError = dotsError && !still,
            holdArea = holdArea.takeIf { !still },
            onHold = if (still) ({}) else onHold,
            dotsProgress = dotsProgress.takeIf { !still },
            modifier = modifier,
        )
    }
}

/**
 * The status and navigation bar icons follow the **calculator's** scheme while it is
 * up, and are put back when it goes away.
 *
 * `MainActivity` sets them from the vault's stored appearance, which is a vault
 * artefact — light icons over a light calculator would be the cover's own chrome
 * disagreeing with it.
 */
@Composable
private fun SystemBarsFollowTheCalculator() {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    DisposableEffect(view, dark) {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val window = (context as? Activity)?.window
            ?: return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, view)
        val before = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        onDispose {
            controller.isAppearanceLightStatusBars = before
            controller.isAppearanceLightNavigationBars = before
        }
    }
}

@Composable
private fun CalculatorScreen(
    onTrigger: (Trigger) -> Unit,
    onProgress: ((Int) -> Unit)?,
    sequence: Boolean,
    showDots: Boolean,
    dotsError: Boolean,
    holdArea: Int?,
    onHold: () -> Unit,
    dotsProgress: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Every one of these is view state and dies with the composition. Nothing here is
    // written anywhere — see this file's KDoc, and `CoverScanTest` asserts it.
    var tokens by remember { mutableStateOf(listOf<Token>()) }
    var entry by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf(listOf<String>()) }
    var degrees by remember { mutableStateOf(true) }
    var inverse by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Double?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val words = localWords()

    val angle = if (degrees) Calculator.Angle.Degrees else Calculator.Angle.Radians

    // The dots — `calculator-dots-methods` D3. The path is read only by the grid below, and only
    // while it is shown: in setup's pattern steps, or after the sequence or the hold.
    var pathDrawn by remember { mutableStateOf(emptyList<Int>()) }
    val gridShown = dotsProgress != null || showDots
    // Each showing of the dots starts from nothing, not from the last try's shape.
    LaunchedEffect(showDots) { if (showDots) pathDrawn = emptyList() }
    // Read at each touch by the hold, and at each key by [press].
    val dotsUp by rememberUpdatedState(gridShown)
    // The line sits below the status bar this cover draws under.
    val statusTop = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }

    fun clearAll() {
        tokens = emptyList()
        entry = ""
        result = null
        failure = null
    }

    fun flush() {
        // `parse`, never `toDouble`. A crash here would be the loudest possible tell —
        // the "calculator" dying under a finger is exactly what makes somebody look at
        // an app twice — and a half-typed number is an ordinary state, not a failure.
        Calculator.parse(entry)?.let { tokens = tokens + Token.Num(it) }
        entry = ""
    }

    /**
     * `2(3+4)`, `2π` and `2sin(30)` all mean a multiplication nobody typed. Inserting
     * it is what the calculators on the store do; refusing it would put "Invalid
     * format" on the screen for an expression a person considers ordinary, which is
     * the sort of thing that makes them look twice.
     */
    fun implicitTimes() {
        val closes = entry.isNotEmpty() ||
            tokens.lastOrNull().let { it is Token.Close || it is Token.Const || it is Token.Post }
        if (closes) {
            flush()
            tokens = tokens + Token.Op('*')
        }
    }

    fun backspace() {
        when {
            entry.isNotEmpty() -> entry = entry.dropLast(1)
            tokens.isEmpty() -> Unit
            // A function was pushed as `Fn` followed by `Open`; one press removes both,
            // because a lone `sin` with no parenthesis is a state the keypad cannot
            // otherwise produce and the parser would reject.
            tokens.last() is Token.Open && tokens.getOrNull(tokens.size - 2) is Token.Fn ->
                tokens = tokens.dropLast(2)
            else -> {
                val last = tokens.last()
                tokens = tokens.dropLast(1)
                if (last is Token.Num) entry = Calculator.format(last.value).filter { it.isDigit() || it == Calculator.DECIMAL || it == '-' }
            }
        }
    }

    fun evaluate() {
        flush()
        try {
            result = Calculator.evaluate(tokens, angle)
            failure = null
        } catch (failed: Exception) {
            // A calculator names what went wrong and stays usable. It does not crash,
            // and it does not say anything a bystander could read as unusual.
            failure = Calculator.message(words, failed)
            result = null
        }
    }

    /**
     * A key, and everything it means.
     *
     * `symbol` and not `key`: `KeyMaterialScanTest` forbids a `key: String` anywhere in
     * the app, because cryptographic key material must arrive as a `ByteArray` that can
     * be zeroed. A keypad key is not that, but the test is blunt on purpose and it is
     * right to be — renaming is cheaper than an exemption.
     */
    fun press(symbol: String) {
        // On **Button sequence, then pattern** the finger that held `=` is still down when the
        // dots arrive, and `=`'s own click fires on its release — it is a child of the hold, and
        // sees the release first. Under the dots no key acts, so the display keeps what was typed.
        if (dotsUp) return
        // The sequence compared against the stored trigger is every key pressed since
        // the screen opened or since the last `AC` — the arithmetic included. `AC`
        // empties it and is never part of it: that is how a wrong key is taken back,
        // on the live cover and in setup alike.
        typed = if (symbol == "AC") emptyList() else (typed + symbol).takeLast(MAX_TRIGGER_KEYS)
        // After the cap, so a user pressing past it is told the count is holding rather
        // than that it is still climbing — the front of the sequence is being dropped.
        // Zero after `AC`, which is how setup learns the performance started again.
        onProgress?.invoke(typed.size)

        // An error is cleared by whatever is pressed next, and a committed result is
        // continued by an operator and replaced by anything that starts a new value.
        if (failure != null) clearAll()
        result?.let { previous ->
            if (symbol in CONTINUES_A_RESULT) {
                tokens = listOf(Token.Num(previous))
                entry = ""
            } else {
                clearAll()
            }
            result = null
        }

        when {
            symbol.length == 1 && symbol[0].isDigit() -> entry += symbol
            symbol == "." ->
                if (!entry.contains(Calculator.DECIMAL) && !entry.contains('E')) {
                    entry = entry.ifEmpty { "0" } + Calculator.DECIMAL
                }
            symbol == "±" ->
                entry = if (entry.startsWith("-")) entry.substring(1) else "-$entry"
            symbol == "EXP" ->
                if (!entry.contains('E')) entry = entry.ifEmpty { "1" } + "E"
            symbol == "AC" -> clearAll()
            symbol == "DEL" -> backspace()
            symbol == "=" -> evaluate()
            symbol == "deg" -> degrees = !degrees
            symbol == "inv" -> inverse = !inverse
            symbol in BINARY_OPERATORS -> {
                flush()
                tokens = tokens + Token.Op(symbol[0])
            }
            symbol == "(" -> {
                implicitTimes()
                tokens = tokens + Token.Open
            }
            symbol == ")" -> {
                flush()
                tokens = tokens + Token.Close
            }
            symbol == "pi" || symbol == "e" -> {
                implicitTimes()
                tokens = tokens + Token.Const(symbol)
            }
            Calculator.isPostfix(POSTFIX[symbol] ?: "") -> {
                flush()
                if (tokens.isNotEmpty()) tokens = tokens + Token.Post(POSTFIX.getValue(symbol))
            }
            Calculator.isFunction(symbol) -> {
                implicitTimes()
                tokens = tokens + Token.Fn(resolve(symbol, inverse)) + Token.Open
                // `INV` unlatches after one function, as Google Calculator's does. A
                // mode that stayed on would be a mode somebody forgets they set.
                inverse = false
            }
        }
    }

    // --- what the two lines say ---------------------------------------------

    val expression = Calculator.render(tokens, entry)
    val committed = result?.let { runCatching { Calculator.format(it) }.getOrNull() }
    val preview = if (result == null && failure == null) {
        Calculator.preview(tokens, entry, angle)?.let { runCatching { Calculator.format(it) }.getOrNull() }
    } else {
        null
    }

    // The preview is hidden when it would repeat the expression — typing `7` should
    // not print `7` twice. Google Calculator hides it for the same reason.
    val below = failure ?: committed ?: preview?.takeIf { it != expression } ?: ""

    /**
     * Which of the two lines is the large one.
     *
     * At rest and after `=` the **answer** is large and the expression is the small
     * line above it; while an expression is being typed those roles are the other way
     * round. Animating between them is what makes `=` read as a result arriving rather
     * than as a field being filled in — Google Calculator's signature flourish, and the
     * difference between a calculator and a form.
     */
    val settled = result != null || failure != null || expression.isEmpty()
    val rise by animateFloatAsState(
        targetValue = if (settled) 1f else 0f,
        animationSpec = tween(durationMillis = RISE_MS, easing = Expressive),
        label = "rise",
    )

    Box(
        modifier
            .fillMaxSize()
            .background(CalculatorColors.Ground)
            // D3. Attached **only while the grid is shown**: then it reads every event first and
            // consumes it, so no key beneath presses or repeats. Hidden, there is no grid at all.
            .let { outer ->
                if (!gridShown) {
                    outer
                } else {
                    outer.silentPatternGrid(
                        onProgress = { path ->
                            // The refused shape stays on screen through the error state.
                            if (!dotsError) pathDrawn = path
                            dotsProgress?.invoke(path.size)
                        },
                        onDrawn = { dots -> onTrigger(Trigger.Pattern(dots)) },
                    )
                }
            }
            // D3. Over the whole calculator, as on the music player: a hold in the owner's area
            // brings up the dots and takes the key under the finger; anywhere else it is that key's.
            .silentHold(holdArea, active = { !dotsUp }, onHold = onHold),
    ) {
        val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
        val display: @Composable (Modifier) -> Unit = { m ->
            Display(
                expression = expression,
                below = below.ifEmpty { if (settled) "0" else "" },
                rise = rise,
                failed = failure != null,
                degrees = degrees,
                inverse = inverse,
                onPress = ::press,
                modifier = m,
            )
        }
        val keypad: @Composable (Modifier, Boolean) -> Unit = { m, wide ->
            Keypad(
                inverse = inverse,
                wide = wide,
                onPress = ::press,
                // Only where the method has a sequence, and never under the dots.
                onHoldEquals = if (sequence && !gridShown) {
                    { onTrigger(Trigger.Keys(typed)) }
                } else {
                    null
                },
                modifier = m,
            )
        }

        if (landscape) {
            Row(Modifier.fillMaxSize().safeDrawingPadding().padding(Inset)) {
                display(Modifier.weight(LANDSCAPE_DISPLAY).fillMaxHeight())
                Spacer(Modifier.width(BlockGap))
                keypad(Modifier.weight(1f - LANDSCAPE_DISPLAY).fillMaxHeight(), true)
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(Inset)) {
                // The two display lines lerp in opposite directions, so their sum is
                // the same at rest, mid-rise and while typing — and so is this.
                val readoutMin = ChipHeight + Gutter + Gutter +
                    with(LocalDensity.current) { ((BIG_SP + SMALL_SP) * 1.15f).sp.toDp() }
                val pad = keypadHeight(maxHeight, readoutMin)
                val readout = maxHeight - BlockGap - pad
                Column(Modifier.fillMaxSize()) {
                    display(Modifier.fillMaxWidth().height(readout))
                    Spacer(Modifier.height(BlockGap))
                    keypad(Modifier.fillMaxWidth().height(pad), false)
                }
            }
        }

        // Setup's pattern steps, and the live dots — the same grid either way, with the line on
        // the live dots only.
        CoverPatternGrid(gridShown, pathDrawn, dotsError, showDots, statusTop)
    }
}

// --- the display ---------------------------------------------------------------

@Composable
private fun Display(
    expression: String,
    below: String,
    rise: Float,
    failed: Boolean,
    degrees: Boolean,
    inverse: Boolean,
    onPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = CalculatorColors.TextPrimary
    val secondary = CalculatorColors.TextSecondary
    val error = CalculatorColors.Error

    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        // The readout sits at the bottom of its region and the mode chips sit with it.
        // Parked at the top of the screen they read as chrome belonging to an app bar
        // this screen does not have, with a hand's width of nothing between them and
        // the number they describe.
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.fillMaxWidth().padding(start = Gutter, bottom = Gutter),
            horizontalArrangement = Arrangement.spacedBy(Gutter),
        ) {
            Chip(label = if (degrees) "DEG" else "RAD", engaged = !degrees) { onPress("deg") }
            Chip(label = "INV", engaged = inverse) { onPress("inv") }
        }
        // The expression is large while it is being typed and small once the answer
        // has arrived; the answer does the opposite. Both lerp on the same `rise`.
        Line(
            text = expression,
            style = CalculatorType.expression,
            sizeSp = lerp(BIG_SP, SMALL_SP, rise),
            colour = lerpColour(primary, secondary, rise),
        )
        Spacer(Modifier.height(Gutter))
        Line(
            text = below,
            style = CalculatorType.result,
            sizeSp = lerp(SMALL_SP, BIG_SP, rise),
            colour = if (failed) error else lerpColour(secondary, primary, rise),
        )
    }
}

/**
 * One line of the readout.
 *
 * **It scrolls; it does not shrink.** A font that changes size mid-entry is exactly
 * the sort of wrongness that makes somebody look twice at a cover, so a long
 * expression runs off the leading edge and the trailing edge — where the caret is —
 * stays put.
 */
@Composable
private fun Line(text: String, style: TextStyle, sizeSp: Float, colour: Color) {
    val scroll = rememberScrollState()
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    Box(
        Modifier.fillMaxWidth().horizontalScroll(scroll),
        contentAlignment = Alignment.CenterEnd,
    ) {
        MaterialText(
            text = text,
            style = style.copy(fontSize = sizeSp.sp, lineHeight = (sizeSp * 1.15f).sp),
            color = colour,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Gutter),
        )
    }
}

/**
 * A latching mode, drawn so its state is visible.
 *
 * A mode as an ordinary key hides whether it is on, which is the bug where somebody
 * computes `sin(30)` in radians and cannot see why the answer is wrong.
 */
@Composable
private fun Chip(label: String, engaged: Boolean, onPress: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PRESS_SCALE else 1f, pressSpec(pressed), label = "chip")
    val fill by animateColorAsState(
        if (engaged) CalculatorColors.Accent else Color.Transparent,
        tween(SWAP_MS, easing = Expressive),
        label = "chipFill",
    )
    val ink by animateColorAsState(
        if (engaged) CalculatorColors.OnAccent else CalculatorColors.TextSecondary,
        tween(SWAP_MS, easing = Expressive),
        label = "chipInk",
    )
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .height(ChipHeight)
            .background(fill, ChipShape)
            .border(1.dp, if (engaged) Color.Transparent else CalculatorColors.ChipOutline, ChipShape)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = CalculatorColors.Accent),
                onClick = onPress,
            )
            .padding(horizontal = ChipPadding),
        contentAlignment = Alignment.Center,
    ) {
        MaterialText(text = label, style = CalculatorType.chip, color = ink)
    }
}

// --- the keypad ----------------------------------------------------------------

@Composable
private fun Keypad(
    inverse: Boolean,
    wide: Boolean,
    onPress: (String) -> Unit,
    onHoldEquals: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val functions = (if (wide) SCIENTIFIC_WIDE else SCIENTIFIC).map { row -> row.map { it.under(inverse) } }
    if (wide) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(BlockGap)) {
            Pills(functions, onPress, Modifier.weight(2.6f).fillMaxHeight())
            Circles(NUMPAD, onPress, onHoldEquals, Modifier.weight(4f).fillMaxHeight())
        }
    } else {
        Column(modifier) {
            Pills(functions, onPress, Modifier.fillMaxWidth())
            Spacer(Modifier.height(BlockGap))
            Circles(NUMPAD, onPress, onHoldEquals, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** The scientific block. Smaller, denser, and a different shape from the numpad. */
@Composable
private fun Pills(rows: List<List<Pad>>, onPress: (String) -> Unit, modifier: Modifier) {
    // A **fixed** row height, never a share of what is left. Stretched to fill, a
    // stadium shape on a near-square cell resolves to a circle, and the block stops
    // being the denser, quieter thing above the numpad and becomes a second numpad.
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(PillGutter, Alignment.CenterVertically),
    ) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().height(PillHeight),
                horizontalArrangement = Arrangement.spacedBy(PillGutter),
            ) {
                row.forEach { pad ->
                    PadKey(
                        pad = pad,
                        shape = PillShape,
                        diameter = null,
                        onPress = { onPress(pad.symbol) },
                        onHold = null,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * The numpad. **Round**, because that is the calculator form on every platform that
 * ships one, and the single most visible thing a rounded-rectangle grid is not.
 *
 * Each key is a circle of the shorter cell dimension, centred in its cell — measured
 * rather than derived from an aspect ratio, because a cell that is taller than it is
 * wide (which landscape produces) would otherwise overflow.
 *
 * Labels hold their designed size on keys of [KeyComfort] or more and scale with the
 * diameter below it, so a smaller key keeps the live keypad's label-to-key balance
 * instead of being filled to the rim.
 */
@Composable
private fun Circles(
    rows: List<List<Pad>>,
    onPress: (String) -> Unit,
    onHoldEquals: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Gutter)) {
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Gutter),
            ) {
                row.forEach { pad ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        BoxWithConstraints {
                            val diameter = min(maxWidth, maxHeight)
                            PadKey(
                                pad = pad,
                                shape = CircleShape,
                                diameter = diameter,
                                onPress = { onPress(pad.symbol) },
                                // Only `=` carries the trigger, on the methods with a sequence.
                                // A hold that falls short evaluates; a completed one hands the
                                // sequence over and this screen is told nothing about the outcome.
                                onHold = if (pad.symbol == "=") onHoldEquals else null,
                                modifier = Modifier.size(diameter),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PadKey(
    pad: Pad,
    shape: Shape,
    /** A numpad circle's measured diameter; null for a pill, whose height is fixed. */
    diameter: Dp?,
    onPress: () -> Unit,
    onHold: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) PRESS_SCALE else 1f, pressSpec(pressed), label = "press")

    val fill = when (pad.role) {
        Role.Digit -> CalculatorColors.KeyDigit
        Role.Function -> CalculatorColors.KeyFunction
        Role.Operator -> CalculatorColors.KeyOperator
        Role.Equals -> CalculatorColors.EqualsFill
    }
    val ink = when (pad.role) {
        Role.Digit -> CalculatorColors.TextPrimary
        Role.Function -> if (pad.loud) CalculatorColors.Accent else CalculatorColors.TextSecondary
        Role.Operator -> CalculatorColors.Accent
        Role.Equals -> CalculatorColors.OnEquals
    }
    val designed = when (pad.role) {
        Role.Digit -> CalculatorType.keyDigit
        Role.Function -> CalculatorType.keyFunction
        else -> CalculatorType.keyOperator
    }
    val style = if (diameter == null) {
        designed
    } else {
        designed.copy(fontSize = designed.fontSize * minOf(1f, diameter / KeyComfort))
    }
    val splash = when (pad.role) {
        Role.Digit -> CalculatorColors.TextPrimary
        Role.Equals -> CalculatorColors.OnEquals
        else -> CalculatorColors.Accent
    }

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(fill, shape)
            // The order matters: the silent hold sits outside, does not consume until it
            // fires, and so leaves an ordinary tap to the clickable below.
            .let { if (onHold != null) it.silentLongPress(onHold) else it }
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = splash),
                onClick = onPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MaterialText(text = pad.label(), style = style, color = ink, maxLines = 1)
    }
}

// --- the keys ------------------------------------------------------------------

private enum class Role { Digit, Function, Operator, Equals }

/**
 * One key.
 *
 * [superscript] is drawn with a **baseline shift** and never as a Unicode superscript
 * codepoint. `ˣ` and `ʸ` are modifier letters that a face need not carry, and a tofu
 * box on a key is a louder tell than anything this screen was rebuilt to fix.
 */
private data class Pad(
    val symbol: String,
    val text: String,
    val superscript: String = "",
    val role: Role = Role.Function,
    /** A function key drawn in the accent rather than receding — `AC` and `DEL`. */
    val loud: Boolean = false,
) {
    @Composable
    fun label(): AnnotatedString = buildAnnotatedString {
        append(text)
        if (superscript.isNotEmpty()) {
            withStyle(
                SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = SUPERSCRIPT_EM),
            ) { append(superscript) }
        }
    }

    /** The same physical key under `INV`, where one exists. */
    fun under(inverse: Boolean): Pad = if (inverse) INVERSES[symbol] ?: this else this
}

private fun digit(text: String, symbol: String = text) = Pad(symbol, text, role = Role.Digit)
private fun operator(symbol: String, text: String) = Pad(symbol, text, role = Role.Operator)

/**
 * The scientific block, portrait: five columns, three rows.
 *
 * Visible rather than folded behind an expand chevron, which is where Google
 * Calculator 9.0 puts it. This cover's whole answer to *"why do you have two
 * calculators"* is that this one is visibly scientific; hiding trigonometry and
 * parentheses puts that answer one tap away from somebody who will not tap.
 */
private val SCIENTIFIC = listOf(
    listOf(Pad("(", "("), Pad(")", ")"), Pad("sqrt", "√"), Pad("^", "x", "y"), Pad("fact", "x!")),
    listOf(Pad("sin", "sin"), Pad("cos", "cos"), Pad("tan", "tan"), Pad("ln", "ln"), Pad("log", "log")),
    listOf(Pad("pi", "π"), Pad("e", "e"), Pad("EXP", "EXP"), Pad("recip", "1/x"), Pad("sqr", "x", "2")),
)

/** The same fifteen keys as three columns, for the wider screen. */
private val SCIENTIFIC_WIDE = listOf(
    listOf(Pad("(", "("), Pad(")", ")"), Pad("sqrt", "√")),
    listOf(Pad("sin", "sin"), Pad("cos", "cos"), Pad("tan", "tan")),
    listOf(Pad("ln", "ln"), Pad("log", "log"), Pad("fact", "x!")),
    listOf(Pad("pi", "π"), Pad("e", "e"), Pad("EXP", "EXP")),
    listOf(Pad("recip", "1/x"), Pad("sqr", "x", "2"), Pad("^", "x", "y")),
)

/** `INV` swaps five keys and unlatches after one of them, as Google Calculator's does. */
private val INVERSES = mapOf(
    "sin" to Pad("sin", "sin", "-1"),
    "cos" to Pad("cos", "cos", "-1"),
    "tan" to Pad("tan", "tan", "-1"),
    "ln" to Pad("ln", "e", "x"),
    "log" to Pad("log", "10", "x"),
)

/** Which function a key applies, given the inverse mode. */
private fun resolve(symbol: String, inverse: Boolean): String = when {
    !inverse -> symbol
    symbol == "sin" || symbol == "cos" || symbol == "tan" -> "a$symbol"
    symbol == "ln" -> "exp"
    symbol == "log" -> "pow10"
    else -> symbol
}

/**
 * The numpad: four columns, five rows, in both orientations. Read per use, because the decimal
 * key's label is the app language's separator and a change of language does not restart the
 * process — `app-languages` D8. Its symbol stays `.`, so a stored sequence survives the change.
 */
private val NUMPAD get() = listOf(
    listOf(
        Pad("AC", "AC", loud = true),
        Pad("DEL", "DEL", loud = true),
        operator("pct", "%"),
        operator("/", "÷"),
    ),
    listOf(digit("7"), digit("8"), digit("9"), operator("*", "×")),
    listOf(digit("4"), digit("5"), digit("6"), operator("-", "−")),
    listOf(digit("1"), digit("2"), digit("3"), operator("+", "+")),
    listOf(
        digit("±"),
        digit("0"),
        digit(Calculator.DECIMAL.toString(), "."),
        Pad("=", "=", role = Role.Equals),
    ),
)

/** The keypad's postfix keys, and the evaluator's name for each. */
private val POSTFIX = mapOf("sqr" to "sqr", "recip" to "inv", "fact" to "fact", "pct" to "pct")

private val BINARY_OPERATORS = setOf("+", "-", "*", "/", "^")

/**
 * After `=`, these continue from the answer and everything else starts a new entry —
 * which is what a calculator does and what a person expects without thinking about it.
 */
private val CONTINUES_A_RESULT =
    BINARY_OPERATORS + setOf("sqr", "recip", "fact", "pct", ")", "=", "DEL", "deg", "inv")

// --- metrics and motion --------------------------------------------------------

/**
 * How many keystrokes are kept for comparison.
 *
 * A rolling tail, bounded so nothing accumulates across a long session. The match is
 * exact, so a trigger is entered on a freshly opened cover or after `AC`, which empties
 * the sequence — the same `AC` anybody presses before starting a new sum. P2-25 grew the alphabet from 24 keys to 35, which enlarges
 * the space this compares over and changes nothing else.
 */
internal const val MAX_TRIGGER_KEYS = 8

private val Inset = 12.dp
private val Gutter = 8.dp
private val PillGutter = 6.dp
private val BlockGap = 16.dp
private val ChipHeight = 32.dp
private val ChipPadding = 12.dp
private val ChipShape = RoundedCornerShape(50)
private val PillShape = RoundedCornerShape(50)

/** A scientific key's height. Small enough that the block reads as denser than the numpad. */
private val PillHeight = 44.dp

/**
 * The numpad key the portrait layout protects before it lets the keypad shrink, and the
 * size below which key labels scale with their key.
 *
 * 64dp because it sits at or below the live key on the reference device (≈68dp), so the
 * live cover is never touched, and well above Android's 48dp touch floor.
 */
private val KeyComfort = 64.dp

/**
 * The portrait keypad's height, given the [available] height and the least the readout
 * needs to draw its chips and both lines ([readoutMin]).
 *
 * - **Room to spare** — the two-thirds share already fits [KeyComfort] keys, so it is
 *   returned unchanged: the layout the calculator has always drawn.
 * - **Tight** (identity setup) — the readout gives up its empty space until the keys
 *   reach [KeyComfort] or the readout is down to [readoutMin], whichever comes first.
 * - **Very tight** — never less than the share, so no device gets a smaller keypad than
 *   it had before.
 */
internal fun keypadHeight(available: Dp, readoutMin: Dp): Dp {
    val share = (available - BlockGap) * NUMPAD_SHARE / (1 + NUMPAD_SHARE)
    val comfortable = PillHeight * 3 + PillGutter * 2 + BlockGap + KeyComfort * 5 + Gutter * 4
    return max(share, min(comfortable, available - BlockGap - readoutMin))
}

/**
 * How the portrait screen divides: the numpad takes twice what the readout does, once
 * the scientific block has taken its fixed height out of the middle.
 */
private const val NUMPAD_SHARE = 2f

/** How much of the wide screen the readout takes. */
private const val LANDSCAPE_DISPLAY = 0.34f

private const val BIG_SP = 56f
private const val SMALL_SP = 26f
private val SUPERSCRIPT_EM = 0.65f.em

/**
 * Expressive easing — the curve Material 3's expressive motion uses for a settle.
 *
 * Compose runs every animation in this file through the platform's animator duration
 * scale, so all of them collapse to no animation when a device has animations turned
 * off. That is the platform doing it, not this file, which is why there is no check
 * for it here.
 */
private val Expressive = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

private const val RISE_MS = 300
private const val SWAP_MS = 150
private const val PRESS_IN_MS = 100
private const val PRESS_OUT_MS = 140
private const val PRESS_SCALE = 0.97f

private fun pressSpec(pressed: Boolean) =
    tween<Float>(durationMillis = if (pressed) PRESS_IN_MS else PRESS_OUT_MS, easing = Expressive)

private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t

private fun lerpColour(from: Color, to: Color, t: Float) = Color(
    red = lerp(from.red, to.red, t),
    green = lerp(from.green, to.green, t),
    blue = lerp(from.blue, to.blue, t),
    alpha = lerp(from.alpha, to.alpha, t),
)
