package com.app.vault.ui

import com.app.vault.TestWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * A size in words — P2-18.
 *
 * Sizes are one of those things that look right until someone checks the boundary,
 * and the boundary is where a file manager and this app would visibly disagree
 * about the same file. So the boundaries are what this pins: 999 and 1000, and the
 * step from kB to MB.
 *
 * Runs under a fixed locale, because the separator is the one part of the label
 * that is deliberately locale-dependent (unlike P2-16's ordering, which
 * deliberately is not).
 */
class BytesTest {

    private fun <T> inLocale(locale: Locale, body: () -> T): T {
        val previous = Locale.getDefault()
        return try {
            Locale.setDefault(locale)
            body()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `bytes below a kilobyte are exact`() = inLocale(Locale.US) {
        assertEquals("0 B", sizeLabel(TestWords, 0))
        assertEquals("1 B", sizeLabel(TestWords, 1))
        assertEquals("999 B", sizeLabel(TestWords, 999))
    }

    /** 1000, not 1024: SI names over SI units, so the number matches a file manager's. */
    @Test
    fun `the kilobyte boundary is decimal`() = inLocale(Locale.US) {
        assertEquals("1.0 kB", sizeLabel(TestWords, 1_000))
        assertEquals("1.0 kB", sizeLabel(TestWords, 1_024))
        assertEquals("1.5 kB", sizeLabel(TestWords, 1_500))
    }

    @Test
    fun `each step carries its own unit`() = inLocale(Locale.US) {
        assertEquals("1.0 MB", sizeLabel(TestWords, 1_000_000))
        assertEquals("4.2 MB", sizeLabel(TestWords, 4_200_000))
        assertEquals("1.0 GB", sizeLabel(TestWords, 1_000_000_000))
        assertEquals("2.5 GB", sizeLabel(TestWords, 2_500_000_000))
        assertEquals("1.0 TB", sizeLabel(TestWords, 1_000_000_000_000))
    }

    @Test
    fun `the step up happens at the unit and not before`() = inLocale(Locale.US) {
        assertEquals("999.9 kB", sizeLabel(TestWords, 999_900))
        assertEquals("1.0 MB", sizeLabel(TestWords, 1_000_000))
    }

    /** A negative length cannot happen and must not print a minus if it does. */
    @Test
    fun `a negative size reads as zero`() = inLocale(Locale.US) {
        assertEquals("0 B", sizeLabel(TestWords, -1))
    }

    @Test
    fun `the separator follows the device language`() {
        val us = inLocale(Locale.US) { sizeLabel(TestWords, 1_500_000) }
        val de = inLocale(Locale.GERMANY) { sizeLabel(TestWords, 1_500_000) }
        assertEquals("1.5 MB", us)
        assertEquals("1,5 MB", de)
    }

    /** Nothing overflows or falls off the end of the unit list. */
    @Test
    fun `the largest plausible vault still has a unit`() = inLocale(Locale.US) {
        assertTrue(sizeLabel(TestWords, Long.MAX_VALUE).endsWith(" PB"))
    }
}
