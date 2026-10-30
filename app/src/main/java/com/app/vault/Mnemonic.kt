package com.app.vault

import android.content.Context
import java.security.MessageDigest

/**
 * BIP39 encoding of a 32-byte key — P1-12: the keyring key for the list's kit, a VMK for
 * the kit of a vault with its own passphrase.
 *
 * 32 bytes of entropy, plus the first 8 bits of its SHA-256 as a checksum, is 264
 * bits; 264 / 11 is 24, and 2^11 is the size of the wordlist. That is the whole
 * of it. This is base-2048 arithmetic with a transcription check, not
 * cryptography, which is why it is forty lines here rather than a dependency to
 * clear against I1.
 *
 * **BIP39's seed derivation is deliberately absent.** The standard turns a
 * mnemonic into a 512-bit wallet seed with PBKDF2 and an optional passphrase.
 * None of that applies: these words *are* the key, because P1-12 has to recover a
 * vault on an install where nothing survives to unwrap anything with. Putting a
 * KDF between the words and the key would break exactly the property the design
 * needs. Do not "finish" this implementation.
 *
 * The wordlist is checked into `res/raw` and its hash is asserted by a test — a
 * list that shifted by one entry would silently encode the wrong key.
 */
class Mnemonic(private val words: List<String>) {

    init {
        require(words.size == WORDLIST_SIZE) { "wordlist must hold $WORDLIST_SIZE words" }
    }

    companion object {
        const val WORD_COUNT = 24
        const val ENTROPY_BYTES = 32
        private const val WORDLIST_SIZE = 2048
        private const val BITS_PER_WORD = 11
        private const val CHECKSUM_BITS = 8

        /**
         * What counts as being between two words — `recovery-kit-entry`.
         *
         * **Whitespace alone was not enough, and the failure was silent.** Somebody
         * separating a list with commas produced `abandon,` as a token, which is not in
         * the wordlist, and the screen called their correct transcription invalid. A
         * comma, a semicolon, a full stop and the non-breaking space a keyboard can
         * insert are all things people put between words, so all of them are separators.
         *
         * **Characters are never stripped from inside a token.** `ab4ndon` stays
         * unrecognised rather than becoming `abndon`; inventing a word the user did not
         * type is the opposite of the job, which is refusing a transcription that cannot
         * be verified.
         */
        private val SEPARATORS = Regex("[\\s,;.\\u00A0]+")

        fun fromResources(context: Context): Mnemonic =
            context.resources.openRawResource(R.raw.bip39_english).use { input ->
                Mnemonic(
                    input.bufferedReader().lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toList()
                )
            }
    }

    private val index: Map<String, Int> = words.withIndex().associate { (i, w) -> w to i }

    /**
     * The word at [i] — for `ShareCode`, whose 4-word codes draw from this list
     * (`FORMAT.md` §12) rather than shipping a second one to drift.
     */
    internal fun wordAt(i: Int): String = words[i]

    /**
     * 32 bytes in, 24 words out.
     *
     * [entropy] is not retained and not modified; the caller still owns it and
     * still zeroes it.
     */
    fun encode(entropy: ByteArray): List<String> {
        require(entropy.size == ENTROPY_BYTES) { "entropy must be $ENTROPY_BYTES bytes" }

        val bits = ByteArray(ENTROPY_BYTES + 1)
        try {
            entropy.copyInto(bits)
            bits[ENTROPY_BYTES] = sha256(entropy)[0]

            return (0 until WORD_COUNT).map { w -> words[readIndex(bits, w)] }
        } finally {
            bits.fill(0)
        }
    }

    /**
     * 24 words back to 32 bytes, or null.
     *
     * Null is the ordinary outcome for a bad checksum, an unknown word, or the
     * wrong count — a mistyped mnemonic is not an error any more than a wrong
     * passphrase is, and throwing here would make the failure paths differ.
     */
    fun decode(phrase: String): ByteArray? {
        val entered = split(phrase)
        if (entered.size != WORD_COUNT) return null

        // Exact matches only. BIP39 words are unique in their first four letters,
        // which tempts prefix matching; accepting a prefix would mean accepting a
        // transcription this cannot verify, which is the opposite of the job.
        val indices = entered.map { index[it] ?: return null }

        val bits = ByteArray(ENTROPY_BYTES + 1)
        try {
            indices.forEachIndexed { w, value -> writeIndex(bits, w, value) }

            val entropy = bits.copyOfRange(0, ENTROPY_BYTES)
            if (bits[ENTROPY_BYTES] != sha256(entropy)[0]) {
                entropy.fill(0)
                return null
            }
            return entropy
        } finally {
            bits.fill(0)
        }
    }

    /** Whether [phrase] decodes, without handing back the key to find out. */
    fun isValid(phrase: String): Boolean = decode(phrase)?.also { it.fill(0) } != null

    /**
     * What was typed, as this parser reads it — `recovery-kit-entry`.
     *
     * **Facts, and no sentences.** [decode] answers one question with a null, which left
     * the screen above it one message for three unrelated problems: the wrong number of
     * words, a word that is not in the list, and 24 real words in the wrong order. A user
     * copying from paper hits the third and is told the same thing as somebody who hit
     * the first.
     *
     * This hands back what the screen needs to tell them apart and to show the reading
     * back. The copy stays in the UI, where every other sentence in this app lives, and
     * nothing here suggests a correction: guessing at a word over a list of 2048 is a
     * guess at a secret, and a wrong guess the user accepts is a vault they cannot open.
     *
     * **It says nothing about any vault**, only about the characters in the field. What a
     * *submitted* recovery reports is unchanged, and is still one outcome for every cause.
     */
    class Reading(
        /** The words as parsed, in order. Empty for an empty phrase. */
        val words: List<String>,
        /** Indices into [words] that are not in the wordlist. */
        val unknown: Set<Int>,
    )

    fun read(phrase: String): Reading {
        val words = split(phrase)
        return Reading(
            words = words,
            unknown = words.indices.filterTo(mutableSetOf()) { index[words[it]] == null },
        )
    }

    /** One split, so [decode] and [read] cannot disagree about where a word ends. */
    private fun split(phrase: String): List<String> =
        phrase.trim().lowercase().split(SEPARATORS).filter { it.isNotEmpty() }

    /**
     * Whether [ch] is something a person puts *between* two words —
     * `recovery-kit-entry`.
     *
     * The entry screen needs this to know that a word has been finished. It reads the
     * same [SEPARATORS] the split does, so the screen and the parser cannot come to
     * different conclusions about where a word ends.
     */
    fun isSeparator(ch: Char): Boolean = SEPARATORS.matches(ch.toString())

    /**
     * Words in the list beginning with [prefix] — `recovery-kit-entry`.
     *
     * BIP39 words are unique in their first four letters, so by the fourth character
     * there is exactly one of these. That is what makes a completion row honest rather
     * than a guess: it narrows to one, and it narrows to one from a list that is public
     * and shipped in the APK.
     *
     * Empty for an empty prefix — a row of arbitrary words over an untouched field is
     * noise, and completing something nobody has started typing is not completion.
     *
     * **This is not prefix matching.** Selecting one writes the *whole* word into the
     * field; a prefix left in a field is still an unrecognised word, which is what
     * `aWordPrefixIsNotAccepted` holds.
     */
    fun completions(prefix: String, limit: Int = 3): List<String> {
        val start = prefix.trim().lowercase()
        if (start.isEmpty()) return emptyList()
        return words.asSequence().filter { it.startsWith(start) }.take(limit).toList()
    }

    /** The 11 bits of word [w], big-endian across the byte array. */
    private fun readIndex(bits: ByteArray, w: Int): Int {
        var value = 0
        for (b in 0 until BITS_PER_WORD) {
            val bit = w * BITS_PER_WORD + b
            val byte = bits[bit / 8].toInt() and 0xFF
            value = (value shl 1) or ((byte shr (7 - bit % 8)) and 1)
        }
        return value
    }

    private fun writeIndex(bits: ByteArray, w: Int, value: Int) {
        for (b in 0 until BITS_PER_WORD) {
            if ((value shr (BITS_PER_WORD - 1 - b)) and 1 == 1) {
                val bit = w * BITS_PER_WORD + b
                bits[bit / 8] = (bits[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte()
            }
        }
    }

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)
}
