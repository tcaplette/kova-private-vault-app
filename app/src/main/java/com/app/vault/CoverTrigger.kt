package com.app.vault

import android.content.Context

/**
 * Where a cover's trigger lives, and the one place an attempt is judged — P2-24.
 *
 * [Cover] holds the arithmetic; this holds the storage. The split is [FaceDown] and
 * `FaceDownLock`'s, for its reason: the decidable half stays decidable without a
 * device.
 *
 * **Its own store, not `GridPreference`.** That file is the device's display
 * preferences — density, ordering, interval, face-down, identity, appearance — and
 * this is a trigger per cover, with its hold area, the calculator's pattern where its method
 * has one, plus a limiter's counters.
 * Same directory, different concern.
 *
 * ***Plaintext, deliberately.*** A cover holding an encrypted blob is I8's tell
 * arriving through a different door: the one file on the device that makes no sense
 * for the app it claims to be. And it costs nothing that was not already gone —
 * anyone who can read this file has already read the package name, which defeats
 * every cover by a shorter route. `FORMAT.md` §10 records that in those words.
 *
 * ***No default and no fallback.*** [triggerFor] answers null until the user chooses,
 * and a null trigger makes the cover unselectable rather than openable-by-anyone. A
 * default value would be a published trigger under another name: the same on every
 * install, so anyone who had heard of this app could confirm a vault on somebody
 * else's phone in one press. That is the entire loss of the only thing a cover buys.
 *
 * Nothing here logs. A trigger, a cover name or an attempt count in logcat is the
 * fact the whole feature exists to withhold.
 */
object CoverTrigger {

    private const val PREFS = "covers"
    private const val KEY_FAILURES = "failures"
    private const val KEY_WINDOW = "window"
    private const val KEY_LOCKED = "locked"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- the trigger --------------------------------------------------------

    /**
     * The configured trigger for [cover], or **null** when the user has not set one. On the
     * calculator, its sequence — null on **Hold, then pattern**, which has none.
     */
    fun triggerFor(context: Context, cover: Cover): Trigger? =
        Trigger.decode(cover, prefs(context).getString(cover.name, null))

    /**
     * The pattern drawn on [cover]'s dots — `calculator-dots-methods` D1: the calculator's own
     * [patternKey], null on **Button sequence**; the pattern covers' trigger elsewhere.
     */
    fun patternFor(context: Context, cover: Cover): Trigger.Pattern? = when (cover) {
        Cover.Calculator -> Trigger.decodePattern(prefs(context).getString(patternKey(cover), null))
        Cover.MusicPlayer, Cover.VoiceRecorder -> triggerFor(context, cover) as? Trigger.Pattern
    }

    /** D1. The calculator's method, read from which of its secrets are stored; null where none fits. */
    fun calculatorMethod(context: Context): CalculatorMethod? = calculatorMethodOf(
        keys = triggerFor(context, Cover.Calculator) != null,
        pattern = patternFor(context, Cover.Calculator) != null,
        hold = holdFor(context, Cover.Calculator) != null,
    )

    /**
     * Whether [cover] can be enabled at all. The music player and the voice recorder need their
     * hold area as well as their pattern — `cover-hold-dots` D5: there is no default area. The
     * calculator needs what one of its methods needs, and nothing besides (D1).
     */
    fun isConfigured(context: Context, cover: Cover): Boolean = when (cover) {
        Cover.Calculator -> calculatorMethod(context) != null
        Cover.MusicPlayer, Cover.VoiceRecorder -> triggerFor(context, cover) != null && holdFor(context, cover) != null
    }

    /**
     * Store a cover's secrets — `cover-hold-dots` D5, `calculator-dots-methods` D1:
     *
     * - the music player and the voice recorder: their pattern [trigger] and hold [area];
     * - the calculator: [trigger] and whatever its method adds — the second stage's [dots] on
     *   **Button sequence, then pattern**, the [area] on **Hold, then pattern** — and every key
     *   the method does not use is removed **in the same edit**, so setting it up again as another
     *   method leaves nothing of the old one behind.
     *
     * Refuses a secret under its minimum, an area outside `0..11`, and any combination that is not
     * one of the cover's methods. Returns whether it was stored, so setup can decline to complete
     * rather than enabling a cover whose trigger is below the floor `FORMAT.md` §10 records.
     */
    fun set(
        context: Context,
        cover: Cover,
        trigger: Trigger,
        area: Int? = null,
        dots: Trigger.Pattern? = null,
    ): Boolean {
        if (!trigger.valid || dots?.valid == false) return false
        if (area != null && area !in 0 until HOLD_AREAS) return false
        val edit = prefs(context).edit()
        when (cover) {
            Cover.Calculator -> when (calculatorRow(trigger, area, dots) ?: return false) {
                CalculatorMethod.Sequence ->
                    edit.putString(cover.name, trigger.encode()).remove(patternKey(cover)).remove(holdKey(cover))
                CalculatorMethod.SequenceThenPattern ->
                    edit.putString(cover.name, trigger.encode()).putString(patternKey(cover), dots!!.encode())
                        .remove(holdKey(cover))
                CalculatorMethod.HoldThenPattern ->
                    edit.remove(cover.name).putString(patternKey(cover), trigger.encode()).putInt(holdKey(cover), area!!)
            }
            Cover.MusicPlayer, Cover.VoiceRecorder -> {
                if (trigger !is Trigger.Pattern || area == null || dots != null) return false
                edit.putString(cover.name, trigger.encode()).putInt(holdKey(cover), area)
            }
        }
        edit.apply()
        return true
    }

    /** [cover]'s hold area, `0..11`, or null where none is stored — `cover-hold-dots` D5. */
    fun holdFor(context: Context, cover: Cover): Int? =
        holdOf(prefs(context).getInt(holdKey(cover), -1))

    /** Forget a cover's trigger, pattern and hold area. The cover becomes unselectable again. */
    fun clear(context: Context, cover: Cover) {
        prefs(context).edit().remove(cover.name).remove(patternKey(cover)).remove(holdKey(cover)).apply()
        clearAttempts(context, cover)
    }

    /**
     * Delete every stored value naming a cover this build does not have —
     * `music-recorder-covers`.
     *
     * Called from [LauncherIdentity.admit], which runs at every start, and from
     * [LauncherIdentity.reconcile] at every departure, so an install updated away from
     * a removed cover is swept the first time it opens.
     *
     * **A preference that still says `Flashlight` is a record that this install once
     * wore one**, kept by the build that took the cover away and readable by anyone
     * who can read the file. There is nothing left to do with it: the trigger opens
     * a screen that no longer exists, and the limiter counts attempts against it.
     *
     * Keys are matched by prefix rather than enumerated — [knownCoverKey].
     */
    fun sweepUnknownCovers(context: Context) {
        val p = prefs(context)
        val stale = p.all.keys.filterNot(::knownCoverKey)
        if (stale.isEmpty()) return
        p.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    // --- the attempt --------------------------------------------------------

    /**
     * The **one** entry point every cover's gesture handler calls.
     *
     * One place decides, so silence cannot be forgotten at a call site: the limiter
     * is checked before the match, and a [Outcome.Limited] is returned without the
     * stored trigger ever being compared — [judge] holds that order. Callers are required to treat
     * [Outcome.Rejected] and [Outcome.Limited] identically: nothing for the calculator's sequence,
     * and for a pattern the same intruder capture and the same error on shown dots
     * (`cover-hold-dots`).
     *
     * `calculator-dots-methods` D2: a sequence is judged against [triggerFor] and a pattern against
     * [patternFor], through the **one** limiter record per cover. The calculator's sequence on
     * **Button sequence, then pattern** is not final — a match brings up the dots and leaves the
     * record as it was; only reaching the unlock screen clears it.
     *
     * [nowMs] is a parameter rather than a clock read here, so the limiter's
     * behaviour is drivable from a test without waiting ten real minutes.
     */
    fun attempt(
        context: Context,
        cover: Cover,
        input: Trigger,
        nowMs: Long = System.currentTimeMillis(),
    ): Outcome {
        val attempts = attempts(context, cover)
        val stored = when (input) {
            is Trigger.Keys -> triggerFor(context, cover)
            is Trigger.Pattern -> patternFor(context, cover)
        }
        val final = !(input is Trigger.Keys && cover == Cover.Calculator &&
            calculatorMethod(context) == CalculatorMethod.SequenceThenPattern)
        val (outcome, next) = judge(attempts, stored, input, nowMs, final)
        if (next != attempts) save(context, cover, next)
        return outcome
    }

    // --- the limiter's state ------------------------------------------------

    fun attempts(context: Context, cover: Cover): Attempts {
        val p = prefs(context)
        return Attempts(
            failures = p.getInt("${cover.name}$KEY_FAILURES", 0),
            windowStartMs = p.getLong("${cover.name}$KEY_WINDOW", 0L),
            lockedUntilMs = p.getLong("${cover.name}$KEY_LOCKED", 0L),
        )
    }

    private fun save(context: Context, cover: Cover, attempts: Attempts) {
        prefs(context).edit()
            .putInt("${cover.name}$KEY_FAILURES", attempts.failures)
            .putLong("${cover.name}$KEY_WINDOW", attempts.windowStartMs)
            .putLong("${cover.name}$KEY_LOCKED", attempts.lockedUntilMs)
            .apply()
    }

    fun clearAttempts(context: Context, cover: Cover) {
        save(context, cover, Attempts())
    }
}
