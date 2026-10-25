package com.app.vault

import androidx.annotation.StringRes
import com.nulabinc.zxcvbn.Strength
import com.nulabinc.zxcvbn.Zxcvbn

/**
 * The floor every passphrase the user chooses has to clear — `passphrase-strength-meter`.
 *
 * **Why there is a floor at all.** This app has no server, no rate limit an attacker
 * must respect, no second factor and no reset. `FORMAT.md` §10 item 22 says it outright:
 * a leaked archive's security "is the passphrase's strength and nothing else". Argon2id
 * at 64 MiB costs ~460 ms a guess, so an attacker manages about two guesses a second per
 * core where a fast hash would give 10^10 — a large multiplier, and not one that saves
 * `password`, which falls inside the first thousand guesses.
 *
 * **Two conditions, and both are needed.** Length alone cannot see `passwordpassword`,
 * `aaaaaaaaaaaaaaa` or `qwertyuiopasdfg`, all of which clear fifteen characters and are
 * guessed almost immediately. The estimate alone would accept `not the phrase` at
 * fourteen. Measured examples are in `PassphraseStrengthTest`, which is the record of
 * this rather than the prose here.
 *
 * **What this deliberately is not.** No composition rule — NIST SP 800-63B-4 §3.1.1.2
 * forbids them, because requiring a capital and a digit is what produces `Password1!`.
 * No crack-time figure: zxcvbn's slowest scenario assumes 10^4 guesses a second and this
 * app is nearer 2, so every number it offers is wrong by three orders of magnitude in the
 * user's favour, and a comforting wrong number is worse than none.
 *
 * **The `String` rule holds here.** [estimate] takes `CharSequence`, never `String`:
 * `KeyMaterialScanTest` fails the build on a secret-named `String` parameter, because a
 * `String` cannot be zeroed and survives until GC. zxcvbn4j measures a `CharSequence`
 * directly, so nothing had to be relaxed to fit.
 */
object PassphraseStrength {

    /**
     * Unicode code points, not UTF-8 bytes.
     *
     * NIST SP 800-63B-4 §3.1.1.2 requires 15 characters where the password is the only
     * authenticator, which is exactly this app; OWASP calls anything shorter weak without
     * a second factor. The count is in code points because the copy has always said
     * "characters" — under the old byte floor, four accented letters passed an
     * eight-byte minimum.
     */
    const val MIN_CODE_POINTS = 15

    /**
     * zxcvbn's score 4: an estimated 10^10 guesses or more.
     *
     * Score 3 *starts* at 10^10, which against a 460 ms derivation is on the order of
     * $500 of compute to exhaust. Score 4 is the only level with no ceiling above it, and
     * the passphrase is the only protection there is. Enforcing deployments more often
     * stop at 3; this one does not, and the cost is more refusals at setup.
     */
    const val MIN_SCORE = 4

    /**
     * The estimate is taken over at most this many code points — zxcvbn's own
     * recommendation, and the reason is latency: ~43 ms at 100 characters against 2–6 ms
     * at ordinary lengths.
     *
     * [MIN_CODE_POINTS] still applies to the whole passphrase. A passphrase longer than
     * this whose first 100 code points already score 4 is not one the floor needs to
     * think about.
     */
    const val ESTIMATE_CODE_POINTS = 100

    /** What the meter draws and what the form gates on. */
    data class Verdict(
        /** zxcvbn's 0..4. The meter fills this many of its four segments. */
        val score: Int,
        /** How many code points were typed — the meter counts up to [MIN_CODE_POINTS]. */
        val codePoints: Int,
        /** The level in a word, or null with nothing typed. Never colour alone — `DESIGN.md` §6.4. */
        @StringRes val word: Int?,
        /**
         * One sentence, or null once the floor is cleared. Below the length it is the
         * count; above it and still short, what is weak and the one thing that fixes it.
         */
        val sentence: Sentence?,
        /** Both conditions met. The forward action gates on this and nothing else. */
        val clears: Boolean,
    )

    /**
     * The meter's second line, held as what it says rather than in words, so the estimate
     * needs no `Context` and the line is drawn in the app's language — `app-languages`.
     */
    sealed interface Sentence {
        /** How many of the [MIN_CODE_POINTS] have been typed. */
        data class Count(val typed: Int) : Sentence
        /** zxcvbn's pattern key — [PassphraseFeedback] names the sentence. */
        data class Reason(val id: String) : Sentence

        fun text(words: Words): String = when (this) {
            is Count -> words.get(R.string.strength_count, typed, MIN_CODE_POINTS)
            is Reason -> words.get(PassphraseFeedback.sentence(id))
        }
    }

    /** The empty field: the requirement is stated by the field's own helper text, not here. */
    val NOTHING_TYPED = Verdict(score = 0, codePoints = 0, word = null, sentence = null, clears = false)

    /**
     * The app's own vocabulary, always treated as guessable.
     *
     * NIST SP 800-63B-4 §3.1.1.2 requires blocking "context-specific information such as
     * service names". Measured, this changes outcomes rather than merely looking diligent:
     * `passphrase passphrase` is 21 code points and scores 4 unaided, and drops to 3 once
     * these words are known — as does `passphrase vault vaults` at 23.
     *
     * It does not rescue every case, and the KDoc should not pretend otherwise:
     * `my vault passphrase` falls from 10^15 to 10^11 estimated guesses and stays score 4,
     * because four of its nineteen characters are still doing real work. The list lowers
     * every estimate it touches and decides the ones near the boundary.
     *
     * Deliberately short. A screen that names a vault adds that name to this; nothing else
     * is guessed at on the user's behalf.
     */
    val APP_WORDS = listOf("vault", "vaults", "passphrase")

    /**
     * Built once for the process and never on the main thread — construction reads about
     * 800 KB of dictionaries off the classpath and measures ~185 ms.
     *
     * `by lazy` rather than an eager field: an install that never opens a
     * passphrase-setting screen never pays for it. [warm] is what makes the first *use*
     * cheap, and the two together are why no screen waits on this.
     */
    private val engine: Zxcvbn by lazy { Zxcvbn() }

    /**
     * Constructs the engine and spends the first-measurement cost, which is ~93 ms of JIT
     * warm-up against 2–6 ms afterwards.
     *
     * Called off the main thread when a passphrase-setting screen first composes, so the
     * whole ~280 ms lands before a user can have typed fifteen characters. Idempotent.
     */
    fun warm() {
        engine.measure("warm the dictionaries and the jit")
    }

    /**
     * The verdict for [typed], with [forbidden] treated as guessable words.
     *
     * [forbidden] carries the vault's name where the screen names one, plus the app's own
     * vocabulary: NIST requires blocking "context-specific information such as service
     * names", and a vault called `Receipts` must not open to `receipts receipts`.
     *
     * Not on the main thread — see [warm]. `CharSequence`, not `String`; see the class
     * KDoc.
     */
    fun estimate(typed: CharSequence, forbidden: List<String> = emptyList()): Verdict {
        if (typed.isEmpty()) return NOTHING_TYPED

        val text = typed.toString()
        val codePoints = text.codePointCount(0, text.length)
        val measured = engine.measure(capped(text, ESTIMATE_CODE_POINTS), forbidden)
        val score = measured.score
        val longEnough = codePoints >= MIN_CODE_POINTS
        val clears = longEnough && score >= MIN_SCORE

        return Verdict(
            score = score,
            codePoints = codePoints,
            word = word(score),
            sentence = when {
                // Below the length, the count is the one actionable fact. Saying "add
                // another word" to somebody eleven characters in is answering a question
                // they have not reached yet.
                !longEnough -> Sentence.Count(codePoints)
                clears -> null
                else -> reason(measured)
            },
            clears = clears,
        )
    }

    /** Whether [typed] may be submitted. The forward action's only gate. */
    fun clears(typed: CharSequence, forbidden: List<String> = emptyList()): Boolean =
        estimate(typed, forbidden).clears

    /**
     * The verdict to *draw* while [measured] may be up to one debounce behind [typed].
     *
     * Two things are separated here for two different reasons.
     *
     * **The character count is arithmetic, so it is never stale.** It is recomputed from
     * [typed] on every keystroke, which is what keeps the field responsive while an
     * estimate is still in flight — a user eleven characters in is told "11 of 15" the
     * instant they type the eleventh, not 120ms later.
     *
     * **The score is kept from the last completed estimate rather than blanked.** Clearing
     * it between keystrokes would make the segments flicker on every letter, which is worse
     * than a level that is briefly one keystroke old.
     *
     * **[settled] gates submission, and only submission.** A stale score must never make
     * [Verdict.clears] true for text nobody has measured — deleting a word out of a strong
     * passphrase would otherwise leave the forward action live for 120ms. So [clears]
     * requires the estimate to belong to exactly this text. The screens re-check with
     * [clears] on the click as well, so this is the advisory half of a belt and braces.
     */
    fun pending(typed: CharSequence, measured: Verdict, settled: Boolean): Verdict {
        val text = typed.toString()
        if (text.isEmpty()) return NOTHING_TYPED

        val codePoints = text.codePointCount(0, text.length)
        val longEnough = codePoints >= MIN_CODE_POINTS
        val strongEnough = measured.score >= MIN_SCORE

        return measured.copy(
            codePoints = codePoints,
            sentence = when {
                !longEnough -> Sentence.Count(codePoints)
                strongEnough -> null
                else -> measured.sentence
            },
            clears = settled && longEnough && strongEnough,
        )
    }

    /**
     * The first [limit] code points of [text], never splitting a surrogate pair.
     *
     * `substring` counts UTF-16 units, so a naive cut at 100 can land between the halves
     * of an emoji and hand the estimator a lone surrogate.
     */
    private fun capped(text: String, limit: Int): String {
        if (text.codePointCount(0, text.length) <= limit) return text
        return text.substring(0, text.offsetByCodePoints(0, limit))
    }

    /**
     * Score to word.
     *
     * **Score 3 is "Almost", not "Strong".** zxcvbn's own vocabulary calls 3 "safely
     * unguessable", but this app refuses 3, and naming a refused passphrase "Strong" tells
     * the user the screen is broken rather than that the passphrase is short of the bar.
     * 4 is "Strong enough" — it names the bar rather than claiming an absolute.
     */
    private fun word(score: Int): Int = when (score) {
        0 -> R.string.strength_very_weak
        1 -> R.string.strength_weak
        2 -> R.string.strength_fair
        3 -> R.string.strength_almost
        else -> R.string.strength_strong_enough
    }

    /**
     * What is wrong, in one sentence, from the app's own bundle — see
     * [PassphraseFeedback].
     *
     * zxcvbn sets a warning only at score 2 and below, so at 3 there is usually nothing to
     * report and the generic line is the honest answer: the passphrase is not weak in any
     * nameable way, it is simply not long enough yet.
     */
    private fun reason(measured: Strength): Sentence {
        val feedback = measured.feedback.withResourceBundle(PassphraseFeedback.KEYS)
        val warning = feedback.getWarning().orEmpty()
        if (warning.isNotBlank()) return Sentence.Reason(warning)
        return Sentence.Reason(
            feedback.getSuggestions().firstOrNull { it.isNotBlank() }
                ?: "feedback.extra.suggestions.addAnotherWord"
        )
    }
}
