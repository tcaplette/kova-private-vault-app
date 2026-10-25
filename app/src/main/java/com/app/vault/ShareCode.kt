package com.app.vault

import java.security.SecureRandom

/**
 * The 4-word share code — `FORMAT.md` §12, code mode.
 *
 * Words come from the same BIP39 list the recovery kit ships, because a second
 * list is a second thing to drift, and parsing rides [Mnemonic.read] so this
 * screen and the recovery screen cannot disagree about where a word ends. That
 * is the whole relationship: a share code is **not** a mnemonic. It encodes no
 * key, carries no checksum — the container's AEAD tag is the checksum, and a
 * mistyped code must fail exactly as a tampered file does — and its words are
 * an *input* to Argon2id, not entropy read back out.
 *
 * 4 words is 44 bits, adequate only because the derivation behind it is
 * memory-hard and per-share salted; the count is a codec choice, not a format
 * field, so raising it later is UX, not a version bump.
 */
class ShareCode(private val mnemonic: Mnemonic) {

    companion object {
        const val WORD_COUNT = 4
        private const val WORDLIST_SIZE = 2048

        /**
         * The canonical bytes the share key derives from: lowercase, single-space
         * joined, UTF-8. The caller owns and zeroes them, the app's rule for every
         * secret byte array. Companion, because canonical form is a property of
         * the format (`FORMAT.md` §12), not of any wordlist instance.
         */
        fun canonicalBytes(words: List<String>): ByteArray {
            require(words.size == WORD_COUNT) { "a share code is $WORD_COUNT words" }
            return words.joinToString(" ").lowercase().toByteArray(Charsets.UTF_8)
        }
    }

    private val rng = SecureRandom()

    /**
     * 4 uniform draws from the list. The returned strings are the wordlist's own
     * — public dictionary entries; the secret is the selection, exactly as a
     * passphrase's letters are public and their order is not.
     */
    fun generate(): List<String> =
        (0 until WORD_COUNT).map { mnemonic.wordAt(rng.nextInt(WORDLIST_SIZE)) }

    /**
     * What was typed, as 4 known words — or null.
     *
     * Null is the ordinary outcome for a wrong count or an off-list token,
     * [Mnemonic.decode]'s convention: a mistyped code is not an error. Nothing
     * is derived from a phrase this returns null for — refusing before Argon2id
     * is what makes an off-list token cost nothing.
     */
    fun normalise(phrase: String): List<String>? {
        val reading = mnemonic.read(phrase)
        if (reading.words.size != WORD_COUNT || reading.unknown.isNotEmpty()) return null
        return reading.words
    }

}
