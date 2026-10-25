package com.app.vault

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `panic-pin`'s typo guarantee — no single-character edit of the passphrase entered at
 * enrolment is the panic phrase enrolment accepted — as arithmetic.
 *
 * Enrolment refuses a phrase for which [Panic.withinOneEdit] is true. So the guarantee is
 * that every single-character insertion, deletion and substitution of a passphrase is
 * within one edit of it, which this sweeps exhaustively, and that a phrase further away
 * is not refused.
 *
 * JVM scope: byte arithmetic, no device.
 */
class PanicTest {

    private val passphrases = listOf(
        "correct horse battery staple",
        "12345678",
        "Ω multibyte ✓",
    )

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

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
                    Panic.withinOneEdit(edit, p),
                )
                assertTrue("and the check is symmetric", Panic.withinOneEdit(p, edit))
            }
        }
    }

    @Test
    fun theSamePhraseIsRefused() {
        passphrases.map(::bytes).forEach { assertTrue(Panic.withinOneEdit(it, it.copyOf())) }
    }

    @Test
    fun twoEditsAwayIsAllowed() {
        assertFalse(Panic.withinOneEdit(bytes("12345678"), bytes("12345690")))
        assertFalse(Panic.withinOneEdit(bytes("12345678"), bytes("1234567890")))
        assertFalse(Panic.withinOneEdit(bytes("12345678"), bytes("123456")))
        assertFalse(Panic.withinOneEdit(bytes("abcdefgh"), bytes("bacdefgh")))
        assertFalse(
            Panic.withinOneEdit(bytes("correct horse battery staple"), bytes("purple monkey dishwasher")),
        )
    }

    @Test
    fun anInsertionAnywhereIsFound() {
        // The skip in the length-differs branch must work at the front, middle and end.
        assertTrue(Panic.withinOneEdit(bytes("x12345678"), bytes("12345678")))
        assertTrue(Panic.withinOneEdit(bytes("1234x5678"), bytes("12345678")))
        assertTrue(Panic.withinOneEdit(bytes("12345678x"), bytes("12345678")))
        assertFalse(Panic.withinOneEdit(bytes("x1234567x"), bytes("12345678")))
    }
}
