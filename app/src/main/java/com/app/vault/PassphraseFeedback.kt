package com.app.vault

import java.util.Collections
import java.util.Enumeration
import java.util.ResourceBundle

/**
 * What the strength meter says is wrong, in the app's own words —
 * `passphrase-strength-meter` D7.
 *
 * **Why these are rewritten rather than taken from the library.** zxcvbn4j ships perfectly
 * serviceable English and documents `Feedback.withResourceBundle` for supplying another.
 * Using its own would have been free, and free is the wrong test: this is the screen that
 * stands in front of everything in the product, and its sentences are read by somebody
 * being told their passphrase is not good enough. They get written in the register the rest
 * of the app uses — second person, plain, one sentence, no exclamation, and always naming
 * something to do rather than only what is wrong.
 *
 * **Why the library's bundle seam and not string matching.** Swapping the bundle is the
 * library's own seam, keyed by the pattern it matched. Matching on its output text would
 * break the first time it reworded anything. `app-languages`: the bundle hands back the
 * pattern's key itself, and [SENTENCES] turns the key into the app's string resource, so the
 * sentence is drawn in the app's language and the estimate stays free of any `Context`.
 *
 * **Why no key can go missing.** [KEYS] answers every key, so `getString` never throws
 * `MissingResourceException`, and a key a future version of the library adds and
 * [SENTENCES] does not carry falls back to [ADD_ANOTHER_WORD] — a general sentence rather
 * than an exception or an empty line.
 *
 * The keys are zxcvbn4j 1.9.0's, verbatim from its `messages.properties`. Its six other
 * locale bundles are excluded from the APK in `app/build.gradle.kts`.
 */
object PassphraseFeedback {

    /**
     * The generic line, used at score 3 where zxcvbn reports no specific weakness, and as
     * the fallback for any key [SENTENCES] does not carry.
     *
     * It says the two things that actually raise the score — more words, less common ones —
     * and nothing about capitals, digits or symbols, which NIST forbids requiring and which
     * barely move the estimate.
     */
    val ADD_ANOTHER_WORD = R.string.strength_add_another_word

    /** The library's bundle seam, answering each key with the key itself. */
    val KEYS: ResourceBundle = object : ResourceBundle() {
        override fun handleGetObject(id: String): Any = id
        override fun getKeys(): Enumeration<String> = Collections.emptyEnumeration()
    }

    /** zxcvbn's pattern key to the app's sentence. */
    val SENTENCES: Map<String, Int> = mapOf(
        "feedback.extra.suggestions.addAnotherWord" to ADD_ANOTHER_WORD,
        "feedback.default.suggestions.useFewWords" to R.string.strength_default_suggestions_use_few_words,
        "feedback.default.suggestions.noNeedSymbols" to R.string.strength_default_suggestions_no_need_symbols,
        "feedback.dictionary.warning.passwords.top10" to R.string.strength_dictionary_warning_passwords_top10,
        "feedback.dictionary.warning.passwords.top100" to R.string.strength_dictionary_warning_passwords_top100,
        "feedback.dictionary.warning.passwords.veryCommon" to R.string.strength_dictionary_warning_passwords_very_common,
        "feedback.dictionary.warning.passwords.similar" to R.string.strength_dictionary_warning_passwords_similar,
        "feedback.dictionary.warning.englishWikipedia.itself" to R.string.strength_dictionary_warning_english_wikipedia_itself,
        "feedback.dictionary.warning.etc.namesThemselves" to R.string.strength_dictionary_warning_etc_names_themselves,
        "feedback.dictionary.warning.etc.namesCommon" to R.string.strength_dictionary_warning_etc_names_common,
        "feedback.dictionary.suggestions.capitalization" to R.string.strength_dictionary_suggestions_capitalization,
        "feedback.dictionary.suggestions.allUppercase" to R.string.strength_dictionary_suggestions_all_uppercase,
        "feedback.dictionary.suggestions.reversed" to R.string.strength_dictionary_suggestions_reversed,
        "feedback.dictionary.suggestions.l33t" to R.string.strength_dictionary_suggestions_l33t,
        "feedback.spatial.warning.straightRowsOfKeys" to R.string.strength_spatial_warning_straight_rows_of_keys,
        "feedback.spatial.warning.shortKeyboardPatterns" to R.string.strength_spatial_warning_short_keyboard_patterns,
        "feedback.spatial.suggestions.UseLongerKeyboardPattern" to R.string.strength_spatial_suggestions_use_longer_keyboard_pattern,
        "feedback.repeat.warning.likeAAA" to R.string.strength_repeat_warning_like_aaa,
        "feedback.repeat.warning.likeABCABCABC" to R.string.strength_repeat_warning_like_abcabcabc,
        "feedback.repeat.suggestions.avoidRepeatedWords" to R.string.strength_repeat_suggestions_avoid_repeated_words,
        "feedback.sequence.warning.likeABCor6543" to R.string.strength_sequence_warning_like_abcor6543,
        "feedback.sequence.suggestions.avoidSequences" to R.string.strength_sequence_suggestions_avoid_sequences,
        "feedback.regex.warning.recentYears" to R.string.strength_regex_warning_recent_years,
        "feedback.regex.suggestions.avoidRecentYears" to R.string.strength_regex_suggestions_avoid_recent_years,
        "feedback.date.warning.dates" to R.string.strength_date_warning_dates,
        "feedback.date.suggestions.avoidDates" to R.string.strength_date_suggestions_avoid_dates,
    )

    /** The sentence for zxcvbn's [id]. */
    fun sentence(id: String): Int = SENTENCES[id] ?: ADD_ANOTHER_WORD
}
