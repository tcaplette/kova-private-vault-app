package com.app.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `panic-pin`'s similarity rule — a panic phrase may not sit within a third of its length
 * of the passphrase entered at enrolment, ignoring case and spacing — as arithmetic.
 *
 * Enrolment refuses a phrase for which [Panic.tooSimilar] is true. The typo guarantee is
 * the floor of that rule: every single-character insertion, deletion and substitution of
 * a passphrase is refused, which this sweeps exhaustively. Above the floor, the phrases a
 * person could confuse from memory are refused and unrelated ones are not.
 *
 * JVM scope: byte arithmetic, no device.
 */
class PanicTest {

    private val passphrases = listOf(
        "correct horse battery staple",
        "12345678",
        "Ω multibyte ✓ passphrase",
    )

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun similar(a: String, b: String) = Panic.tooSimilar(bytes(a), bytes(b))

    /** Every single-byte insertion, deletion and substitution of [p], over a spread of bytes. */
    private fun singleEdits(p: ByteArray): Sequence<ByteArray> = sequence {
        val alphabet = (0..255 step 17).map { it.toByte() } + p.toSet()
        for (i in 0..p.size) for (b in alphabet) {
            yield(p.copyOfRange(0, i) + b + p.copyOfRange(i, p.size))
        }
        for (i in p.indices) {
            yield(p.copyOfRange(0, i) + p.copyOfRange(i + 1, p.size))
            for (b in alphabet) yield(p.copyOf().also { it[i] = b })
        }
    }

    @Test
    fun everySingleEditIsRefused() {
        for (p in passphrases.map(::bytes)) {
            for (edit in singleEdits(p)) {
                assertTrue(
                    "a single edit of \"${String(p)}\" must be refused",
                    Panic.tooSimilar(edit, p),
                )
                assertTrue("and the check is symmetric", Panic.tooSimilar(p, edit))
            }
        }
    }

    @Test
    fun theSamePhraseIsRefused() {
        passphrases.map(::bytes).forEach { assertTrue(Panic.tooSimilar(it, it.copyOf())) }
    }

    @Test
    fun caseAndSpacingAreIgnored() {
        assertTrue(similar("Correct Horse Battery Staple", "correct horse battery staple"))
        assertTrue(similar("correct  horse\tbattery staple ", "correct horse battery staple"))
        assertTrue(similar("CORRECTHORSEBATTERYSTAPLE", "correct horse battery staple"))
    }

    @Test
    fun aPhraseOnlyAFewEditsAwayIsRefused() {
        // Two edits, the case the one-edit rule let through.
        assertTrue(similar("blue river morning 42", "blue river morning 43!"))
        // A changed word at the end of a long phrase.
        assertTrue(similar("the-vault-that-survives", "the-vault-that-survived"))
        assertTrue(similar("my dog rex was born 2019", "my dog rex was born 2020"))
        // A swapped pair of characters is two edits, refused at any realistic length.
        assertTrue(similar("abcdefgh", "bacdefgh"))
    }

    @Test
    fun anUnrelatedPhraseIsAllowed() {
        assertFalse(similar("correct horse battery staple", "purple monkey dishwasher"))
        assertFalse(similar("the-vault-that-survives", "quick exit phrase"))
        assertFalse(similar("blue river morning 42", "seven lanterns over the harbour"))
    }

    @Test
    fun theBoundaryIsAThirdOfTheLongerPhrase() {
        // 24 code points: refused below 8 edits, allowed at 8.
        val base = "abcdefghijklmnopqrstuvwx"
        assertTrue(similar(base, "ABCDEFGHIJKLMNOPQ1234567"))      // 7 substitutions
        assertFalse(similar(base, "abcdefghijklmnop12345678"))     // 8 substitutions
    }

    @Test
    fun anInsertionAnywhereIsFound() {
        assertTrue(similar("x12345678", "12345678"))
        assertTrue(similar("1234x5678", "12345678"))
        assertTrue(similar("12345678x", "12345678"))
    }
}
