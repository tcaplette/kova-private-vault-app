package com.app.vault

/**
 * The cover front ends and their triggers — P2-24.
 *
 * **Pure, and deliberately so**, for [FaceDown]'s reason one task along. What a
 * trigger is, whether an input matches one, whether an attempt is rate-limited and
 * how a trigger encodes to a string are all arithmetic and string handling; none of
 * it needs a device. Buried inside a Compose gesture handler, the half that matters
 * — *a near miss must be indistinguishable from nothing* — would be checkable only
 * by mashing an emulator by hand, which means it gets checked once and never again.
 *
 * `CoverTrigger` is the Android half: it owns the `SharedPreferences` store and
 * calls into this. No Android import in this file and no logging — `CoverScanTest`
 * asserts both. Time arrives as a plain [Long]; this holds no opinion about where it
 * came from, only that it moves forward.
 *
 * ***The trigger is never the credential.*** It reveals the unlock screen; the
 * passphrase still opens the vault, against the same slot array, at the same cost to
 * an attacker. Nothing in this file derives key material, and nothing may be added
 * here that does — the moment a trigger authenticates, an offline attack path opens
 * against ten to fifteen bits, which is the pattern-unlock failure `Do not build`
 * already refused. No exception: the hold that brings up a cover's dots
 * (`cover-hold-dots`), and the calculator's sequence where it brings them up
 * (`calculator-dots-methods`), only show them, and the pattern drawn on them is judged as
 * any trigger is.
 */
enum class Cover {
    Calculator,
    MusicPlayer,
    VoiceRecorder,
    ;

    companion object {
        fun of(name: String?): Cover? = entries.firstOrNull { it.name == name }
    }
}

/**
 * How the calculator opens — `calculator-dots-methods` D1. Never stored: read from which of
 * its secrets are stored ([calculatorMethodOf]), so there is no second record to disagree with
 * them.
 */
enum class CalculatorMethod {
    /** The keystroke sequence and a hold on `=` show the unlock screen. */
    Sequence,

    /** The sequence and a hold on `=` bring up the dots; the pattern on them shows the unlock screen. */
    SequenceThenPattern,

    /** A 2-second hold in the stored area brings up the dots, as on the music player. No sequence. */
    HoldThenPattern,
}

/**
 * D1. The calculator's method from which secrets are stored — the sequence, the pattern, the
 * hold area — or null where they fit no method, and the calculator is not configured.
 */
fun calculatorMethodOf(keys: Boolean, pattern: Boolean, hold: Boolean): CalculatorMethod? = when {
    keys && !pattern && !hold -> CalculatorMethod.Sequence
    keys && pattern && !hold -> CalculatorMethod.SequenceThenPattern
    !keys && pattern && hold -> CalculatorMethod.HoldThenPattern
    else -> null
}

/**
 * D1. The method a completed calculator setup hands over — [trigger], the hold [area] and the
 * second stage's [dots] — or null where that is not one of the three, and `CoverTrigger.set`
 * refuses it. Validity is the caller's; this reads only the shape.
 */
fun calculatorRow(trigger: Trigger, area: Int?, dots: Trigger.Pattern?): CalculatorMethod? = when (trigger) {
    is Trigger.Keys -> if (area != null) null else if (dots == null) CalculatorMethod.Sequence else CalculatorMethod.SequenceThenPattern
    is Trigger.Pattern -> if (area != null && dots == null) CalculatorMethod.HoldThenPattern else null
}

/**
 * A configured trigger. One shape per cover, because the covers have different
 * controls and a single shape would fit none of them well.
 *
 * **Every shape carries a minimum**, enforced by [valid], so that the entropy floors
 * recorded in `FORMAT.md` §10 are floors rather than aspirations. A user who wants a
 * longer one may have it; nobody gets a shorter one.
 */
sealed interface Trigger {

    /** Whether this trigger meets its cover's minimum size. */
    val valid: Boolean

    /** Round-trips through [decode]. Plaintext by design — see `CoverTrigger`. */
    fun encode(): String

    /**
     * The calculator: a keystroke sequence, completed by a long-press on `=`.
     *
     * **Floor 10³ ≈ 10 bits**, not the 25⁴ ≈ 18.6 the outline estimated. That figure
     * assumed free choice across the whole keypad; most people will pick digits, and
     * the floor is what the design commits to. The terminator is what stops mashing
     * landing it by accident: nobody long-presses `=` in the ordinary course of
     * using a calculator.
     */
    data class Keys(val keys: List<String>) : Trigger {
        override val valid get() = keys.size >= MIN_KEYS
        override fun encode() = keys.joinToString(SEP)
    }

    /**
     * The music player and the voice recorder: a pattern drawn on an invisible 3×3
     * grid — `pattern-cover-trigger`. The calculator's, on its two pattern methods, is drawn
     * only on its shown dots (`calculator-dots-methods`). [dots] are node indices `0..8` in reading
     * order, in the order the path visited them; no index repeats.
     *
     * **Floor 5 nodes, 7,152 possibilities ≈ 12.8 bits.** Four is refused: a straight
     * drag across a 3×3 grid reaches at most three nodes, so a four-node floor leaves
     * one node of margin between an ordinary scroll and a submitted trigger — the
     * geometry `DesignTokensTest`-style arithmetic this cover's own test asserts
     * directly. Six is refused the other way — a missed gesture on a live cover is
     * silent by requirement, so a floor nobody can draw blind spends the rate
     * limiter's attempts on the owner rather than an attacker.
     *
     * **This replaces both `Transport` and `Phrase`.** The player's floor rises from
     * 9.3 bits; the recorder's falls from 15 — recorded as a decrease, not presented
     * as a new number standing alone, in `FORMAT.md` §10. The path needs no
     * terminator: it ends when the finger lifts, which a discrete press sequence has
     * no equivalent of.
     */
    data class Pattern(val dots: List<Int>) : Trigger {
        override val valid get() = dots.size >= MIN_DOTS && dots.distinct().size == dots.size
        override fun encode() = dots.joinToString(SEP)
    }

    companion object {

        const val MIN_KEYS = 3
        const val MIN_DOTS = 5

        /** The 3×3 grid has nine nodes, `0..8`. */
        const val GRID_NODES = 9

        /**
         * ASCII unit separator, written as an escape because a raw control character
         * in source is invisible to whoever reads this next. A keystroke is a token
         * like `7`, `sin` or `(`, so any printable delimiter would collide with the
         * keypad, and a chosen word can contain anything a user types.
         */
        private const val SEP = "\u001F"

        /**
         * Read a stored trigger back, or null if it is absent or unreadable.
         *
         * Null rather than a throw or a default, and that is the whole design: a
         * cover with no readable trigger is a cover that cannot be enabled. **A
         * default value would be a published trigger under another name** — the same
         * on every install, so anyone who has heard of the app could confirm a vault
         * on someone else's phone in one press.
         */
        fun decode(cover: Cover, stored: String?): Trigger? {
            if (stored.isNullOrEmpty()) return null
            val parts = stored.split(SEP)
            return when (cover) {
                Cover.Calculator -> Keys(parts).takeIf { it.valid }
                Cover.MusicPlayer, Cover.VoiceRecorder -> decodePattern(stored)
            }
        }

        /**
         * A stored pattern read back — a pattern cover's trigger, or the calculator's pattern
         * (`calculator-dots-methods`) — or null, for [decode]'s reason.
         */
        fun decodePattern(stored: String?): Pattern? {
            if (stored.isNullOrEmpty()) return null
            // A token that is not a single digit `0..8` is a stored value this
            // build cannot perform — never a nearest match, and this is also the
            // route an install upgrading from a Transport or Phrase string takes:
            // neither survives this parse, so it answers null, the same as no
            // trigger at all. `pattern-cover-trigger`'s migration.
            return Pattern(
                stored.split(SEP).map { part -> part.toIntOrNull()?.takeIf { it in 0 until GRID_NODES } ?: return null },
            ).takeIf { it.valid }
        }
    }
}

/**
 * Which of the pattern grid's nine nodes, if any, `(x, y)` lands in —
 * `pattern-cover-trigger`. Pure geometry, no Compose and no Android, for the same
 * reason the rest of this file is: the claim it exists to make checkable —
 * *no straight drag ever reaches more than three nodes* — has to be verifiable on the
 * JVM, not by mashing an emulator.
 *
 * Nodes sit at the centre of each of nine equal cells tiling a `width`×`height` area,
 * numbered `0..8` in reading order. A point counts as landing on a node only within
 * [HIT_RADIUS_FRACTION] of that cell's shorter side from its exact centre — **not**
 * anywhere in the cell.
 *
 * **The radius is the whole safety argument, not a detail of it.** A brute-force
 * sweep over every line angle and offset through this grid found the critical radius
 * at ≈0.354 of a cell's side — below it no line comes within radius of more than
 * three of the nine centres, at or above it some line reaches four. `0.22` carries
 * ~38% margin under that bound, verified by the same sweep as a JVM test
 * (`GridGeometryTest`) rather than asserted from the arithmetic alone. Whole-cell
 * hit-testing — any point in a cell counts — was rejected outright: a diagonal fling
 * across a 3×3 grid of *cells* can cross five of them, not three.
 */
fun gridNodeAt(x: Float, y: Float, width: Float, height: Float): Int? {
    if (width <= 0f || height <= 0f) return null
    val cellW = width / 3f
    val cellH = height / 3f
    val radius = minOf(cellW, cellH) * HIT_RADIUS_FRACTION
    for (row in 0 until 3) {
        for (col in 0 until 3) {
            val dx = x - (col + 0.5f) * cellW
            val dy = y - (row + 0.5f) * cellH
            if (dx * dx + dy * dy <= radius * radius) return row * 3 + col
        }
    }
    return null
}

/** See [gridNodeAt]. Measured, not chosen by eye — `GridGeometryTest`. */
const val HIT_RADIUS_FRACTION = 0.22f

/** The hold areas' grid — `cover-hold-dots` D1: 3 across, 4 down. */
const val HOLD_COLUMNS = 3
const val HOLD_ROWS = 4
const val HOLD_AREAS = HOLD_COLUMNS * HOLD_ROWS

/**
 * Which of the 12 hold areas `(x, y)` lands in — `cover-hold-dots` D1. The cover's bounds,
 * `width`×`height` as measured at the touch, divided into [HOLD_COLUMNS]×[HOLD_ROWS] equal
 * cells numbered `0..11` in reading order; the whole cell counts, and a point on or past an
 * edge is clamped into the nearest cell. Measured against the bounds at the moment of the
 * touch, so the cells follow rotation: the top-left cell is the top-left cell in landscape too.
 */
fun holdAreaAt(x: Float, y: Float, width: Float, height: Float): Int {
    val col = if (width <= 0f) 0 else (x / width * HOLD_COLUMNS).toInt().coerceIn(0, HOLD_COLUMNS - 1)
    val row = if (height <= 0f) 0 else (y / height * HOLD_ROWS).toInt().coerceIn(0, HOLD_ROWS - 1)
    return row * HOLD_COLUMNS + col
}

/** A stored hold area read back: `0..11`, or null where absent or unreadable — no default area. */
fun holdOf(stored: Int?): Int? = stored?.takeIf { it in 0 until HOLD_AREAS }

/** An area's place in words — "{Row} row, {column}", D3 and D8. */
fun holdPlace(words: Words, area: Int): String {
    val row = listOf(
        R.string.hold_row_top, R.string.hold_row_second, R.string.hold_row_third, R.string.hold_row_bottom,
    )[area / HOLD_COLUMNS]
    val column = listOf(R.string.hold_column_left, R.string.hold_column_centre, R.string.hold_column_right)[area % HOLD_COLUMNS]
    return words.get(R.string.hold_place, words.get(row), words.get(column))
}

/**
 * Whether an observed input matches a configured trigger.
 *
 * Pure and total: every shape answers for its own input and refuses anything else,
 * so a mismatched pair is a `false` rather than a crash that would itself be a tell.
 */
fun Trigger.matches(input: Trigger): Boolean = when {
    this is Trigger.Keys && input is Trigger.Keys -> keys == input.keys
    // Equality, not a tail match. `Transport` matched against the end of what had
    // been pressed because transport presses are ordinary use the trigger has to be
    // enterable after; a pattern has a pointer-down and a pointer-up, submits once
    // per path, and has no accumulated history to match a tail against.
    this is Trigger.Pattern && input is Trigger.Pattern -> dots == input.dots
    else -> false
}

/**
 * The rate limiter's arithmetic — P2-24.
 *
 * ***This is the one secret in the app that rate limiting works on.*** The slot
 * array cannot be limited: it is offline-attackable by design, which is P1-1's whole
 * shape, and a limiter there would be theatre an attacker simply steps around by
 * copying the file. Reaching a trigger requires the running app, so a limiter here is
 * real. It is what carries the music player's 9.3 bits from "a few minutes of
 * pressing" to "several sessions".
 *
 * **It composes with silence for free.** A wrong trigger already produces nothing
 * observable from the app, so a rate-limited trigger producing the same is the same
 * event to anyone watching — on the pattern covers that same is one intruder capture
 * each (`cover-fingerprint-dots`), and the error state on shown dots, whatever brought them up.
 * The limiter adds no tell, which is the only reason it is safe to add at all.
 */
data class Attempts(
    val failures: Int = 0,
    val windowStartMs: Long = 0L,
    val lockedUntilMs: Long = 0L,
) {

    fun lockedAt(nowMs: Long): Boolean = nowMs < lockedUntilMs

    /**
     * Record a failure. Opens a new window when the old one has elapsed, and locks
     * out for [LOCKOUT_MS] once the threshold is reached inside one.
     */
    fun failed(nowMs: Long): Attempts {
        val fresh = windowStartMs == 0L || nowMs - windowStartMs > WINDOW_MS
        val count = if (fresh) 1 else failures + 1
        val start = if (fresh) nowMs else windowStartMs
        return if (count >= MAX_FAILURES) {
            Attempts(0, 0L, nowMs + LOCKOUT_MS)
        } else {
            Attempts(count, start, lockedUntilMs)
        }
    }

    /** A success clears the record. Nothing is gained by holding it. */
    fun succeeded(): Attempts = Attempts()

    companion object {
        const val MAX_FAILURES = 5
        const val WINDOW_MS = 5 * 60_000L
        const val LOCKOUT_MS = 10 * 60_000L
    }
}

/**
 * One attempt judged — `CoverTrigger.attempt`'s decision, here so the JVM can drive it.
 *
 * The limiter first, so a [Outcome.Limited] never compares anything; then [stored]. Answers
 * the outcome and the record to keep, which is [attempts] itself where nothing changed.
 *
 * [final] false — the calculator's sequence on **Button sequence, then pattern**
 * (`calculator-dots-methods` D2) — accepts a match and leaves the record **unchanged**: only
 * the stage that shows the unlock screen clears it. A right first stage that cleared it would
 * let whoever knows the sequence guess the pattern forever, four at a time.
 */
fun judge(
    attempts: Attempts,
    stored: Trigger?,
    input: Trigger,
    nowMs: Long,
    final: Boolean = true,
): Pair<Outcome, Attempts> = when {
    attempts.lockedAt(nowMs) -> Outcome.Limited to attempts
    stored == null -> Outcome.Rejected to attempts
    stored.matches(input) -> Outcome.Accepted to if (final) attempts.succeeded() else attempts
    else -> Outcome.Rejected to attempts.failed(nowMs)
}

/**
 * The key a cover's hold area lives under — `cover-hold-dots` D5, and the calculator's on
 * **Hold, then pattern**. The cover's name plus a suffix, so [knownCoverKey] already covers it.
 */
fun holdKey(cover: Cover) = "${cover.name}hold"

/**
 * The key the calculator's pattern lives under — `calculator-dots-methods` D1. Its own key, not
 * the cover's name, which holds the sequence. The pattern covers keep theirs under their name.
 */
fun patternKey(cover: Cover) = "${cover.name}pattern"

/**
 * Whether a key in the covers' store belongs to a cover this build has — the sweep's rule.
 * By prefix, because every per-cover key is the cover's name with a suffix; [Cover.of]'s
 * entries decide what is known, so a cover added later needs no edit here.
 */
fun knownCoverKey(name: String): Boolean = Cover.entries.any { name == it.name || name.startsWith(it.name) }

/** What an attempt did. [Rejected] and [Limited] are the same event to a bystander. */
enum class Outcome {
    /**
     * The trigger matched. The caller reveals the unlock screen, wherever the pattern was drawn —
     * or, for the calculator's sequence on **Button sequence, then pattern**, brings up the dots.
     */
    Accepted,

    /** It did not match. Hidden dots show nothing; a pattern fires intruder capture, a sequence nothing. */
    Rejected,

    /** Rate-limited. Deliberately the same as [Rejected], capture included. */
    Limited,
}
