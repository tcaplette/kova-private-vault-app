package com.app.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ShareCode` — `FORMAT.md` §12 code mode, the codec half.
 *
 * JVM: nothing here touches libsodium. The derivation these codes feed is
 * device-tested in `ShareContainerTest`.
 */
class ShareCodeTest {

    // MnemonicTest's locator, unchanged: the list is a build input, not a fixture.
    private fun wordlistFile(): File {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/res/raw/bip39_english.txt"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the wordlist from ${File(".").absolutePath}")
    }

    private val words: List<String> =
        wordlistFile().readLines().map { it.trim() }.filter { it.isNotEmpty() }

    private val codes = ShareCode(Mnemonic(words))

    @Test
    fun generatedCodesAreFourWordsFromTheList() {
        repeat(100) {
            val code = codes.generate()
            assertEquals(ShareCode.WORD_COUNT, code.size)
            code.forEach { w -> assertTrue("'$w' is not on the wordlist", w in words) }
        }
    }

    @Test
    fun generationIsNotDegenerate() {
        // 400 uniform draws over 2048 words collide constantly but never collapse:
        // fewer than 50 distinct words would mean the RNG or indexing is broken.
        val seen = (1..100).flatMap { codes.generate() }.toSet()
        assertTrue("only ${seen.size} distinct words in 400 draws", seen.size > 50)
    }

    @Test
    fun generatedCodeSurvivesItsOwnNormalisation() {
        repeat(20) {
            val code = codes.generate()
            assertEquals(code, codes.normalise(code.joinToString(" ")))
        }
    }

    @Test
    fun normalisationAcceptsWhatPeopleType() {
        // Mixed case, a comma, a non-breaking space, ragged whitespace — the
        // recovery kit's separator rules, so both screens read entry identically.
        val typed = "Abandon,  ability ABLE   about"
        assertEquals(listOf("abandon", "ability", "able", "about"), codes.normalise(typed))
    }

    @Test
    fun offListTokenIsRefused() {
        assertNull(codes.normalise("abandon ability able aboot"))
        // Characters are never stripped from inside a token — Mnemonic's rule.
        assertNull(codes.normalise("abandon ab4ndon able about"))
    }

    @Test
    fun wrongWordCountIsRefused() {
        assertNull(codes.normalise("abandon ability able"))
        assertNull(codes.normalise("abandon ability able about above"))
        assertNull(codes.normalise(""))
    }

    @Test
    fun canonicalBytesAreLowercaseSingleSpaced() {
        val bytes = ShareCode.canonicalBytes(listOf("abandon", "ability", "able", "about"))
        assertArrayEquals("abandon ability able about".toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun canonicalBytesRejectWrongCount() {
        val failed = runCatching { ShareCode.canonicalBytes(listOf("abandon", "ability")) }.isFailure
        assertTrue(failed)
    }
}
