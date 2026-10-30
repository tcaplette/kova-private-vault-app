package com.app.vault

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * The panic phrase — P2-3, reshaped by `panic-phrase`.
 *
 * A panic phrase is a credential the user sets, sealed into a slot of its own that holds
 * the same keyring key and blob ID as the keyring's own slot. Typed at the lock screen it
 * opens that keyring in the ordinary eight-slot pass — one derivation, the same screen,
 * the same cost — and `VaultLifecycle.unlock` recognises it afterwards by the slot that
 * opened being the one the keyring's sealed record names as its panic slot.
 *
 * **Why not the marker it replaced.** P2-3's credential was any passphrase with `//` on
 * the end. The marker was a constant, so anyone who knew the app could strip it from a
 * credential they were handed, and it was visible in the credential itself. A phrase the
 * user chose carries nothing that says what it is.
 *
 * **Why this was not done at P2-3.** A panic slot cannot be told from an ordinary one
 * without a stored flag, and at P2-3 a slot opened a vault whose index had nowhere to put
 * one. Since `vault-keyring` a slot opens a keyring, and the keyring's sealed record
 * already carries keyring-level fields — the panic record is two more of them.
 *
 * What is left here is the one rule enrolment applies that needs no key: how close a
 * panic phrase may be to the passphrase it sits beside.
 */
object Panic {

    /**
     * Whether [a] and [b] are too alike to sit side by side — the distance `panic-pin`
     * requires between a panic phrase and the passphrase entered beside it.
     *
     * **A typo is not the realistic accident.** A single-character slip cannot reach a
     * phrase of fifteen code points or more that the user chose separately. Typing the
     * wrong one of two *similar* phrases from memory can: `blue river morning 42` beside
     * `blue river morning 43!`, or the same words with different capitals. So both are
     * compared without case and with every run of whitespace read as one space, and they
     * are too alike when the edit distance between them is under a third of the longer
     * one's length — never less than two, so one edit is always refused.
     *
     * Compared in code points, decoded straight from the bytes, so a multi-byte character
     * counts once. Every buffer holding either one is zeroed before this returns, for
     * P1-10's reason: the passphrase stopped being a `String` at the earliest boundary
     * and must not become one here.
     */
    fun tooSimilar(a: ByteArray, b: ByteArray): Boolean {
        val x = normalised(a)
        val y = normalised(b)
        try {
            val longer = maxOf(x.size, y.size)
            val threshold = maxOf(2, (longer + 2) / 3)
            return editDistance(x, y) < threshold
        } finally {
            x.fill(0)
            y.fill(0)
        }
    }

    /** Lower-cased code points, each whitespace run one space, none at either end. */
    private fun normalised(bytes: ByteArray): IntArray {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val chars = decoder.decode(ByteBuffer.wrap(bytes))
        val buffer = chars.array()
        val out = IntArray(chars.remaining())
        var n = 0
        try {
            var i = chars.position()
            val end = chars.limit()
            var pendingSpace = false
            while (i < end) {
                val cp = Character.codePointAt(buffer, i, end)
                i += Character.charCount(cp)
                if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                    pendingSpace = n > 0
                    continue
                }
                if (pendingSpace) {
                    out[n++] = ' '.code
                    pendingSpace = false
                }
                out[n++] = Character.toLowerCase(cp)
            }
            return out.copyOf(n)
        } finally {
            buffer.fill('\u0000')
            out.fill(0)
        }
    }

    /** Levenshtein distance over code points, two rows. */
    private fun editDistance(x: IntArray, y: IntArray): Int {
        var previous = IntArray(y.size + 1) { it }
        var current = IntArray(y.size + 1)
        for (i in 1..x.size) {
            current[0] = i
            for (j in 1..y.size) {
                val substitution = previous[j - 1] + if (x[i - 1] == y[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[y.size]
    }
}
