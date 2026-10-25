package com.app.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The BIP39 codec — P1-12.
 *
 * JVM scope, deliberately. The codec is base-2048 arithmetic over a word list and
 * a `MessageDigest`; nothing in it is libsodium, so it needs no device. The
 * resource load and every path that touches a vault are covered on a device by
 * `RecoveryKitTest`.
 *
 * The wordlist is read from the shipping resource rather than a copy, so a test
 * cannot pass against a list the app does not use.
 */
class MnemonicTest {

    private fun wordlistFile(): File {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/res/raw/bip39_english.txt"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the wordlist from ${File(".").absolutePath}")
    }

    private val words: List<String> by lazy {
        wordlistFile().readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun mnemonic() = Mnemonic(words)

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    // --- 7.2 the wordlist itself ----------------------------------------------

    @Test
    fun theWordlistIsTheCanonicalBip39EnglishList() {
        assertEquals("wordlist size", 2048, words.size)
        assertEquals("every word distinct", 2048, words.toSet().size)
        assertTrue("sorted", words == words.sorted())
        assertTrue("all lowercase ascii", words.all { it.matches(Regex("[a-z]{3,8}")) })

        // The published SHA-256 of english.txt. A list that shifted by one entry
        // would encode a different key for the same VMK and be found only by a
        // user whose recovery silently failed.
        val canonical = words.joinToString("\n") + "\n"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "wordlist hash",
            "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda",
            digest
        )
    }

    // --- 7.3 published test vectors -------------------------------------------

    @Test
    fun thePublishedVectorsEncodeAndDecode() {
        val vectors = listOf(
            "0000000000000000000000000000000000000000000000000000000000000000" to
                ("abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                    "abandon abandon abandon abandon abandon art"),
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff" to
                ("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo " +
                    "zoo zoo zoo zoo zoo vote"),
            "8080808080808080808080808080808080808080808080808080808080808080" to
                ("letter advice cage absurd amount doctor acoustic avoid letter advice cage " +
                    "absurd amount doctor acoustic avoid letter advice cage absurd amount " +
                    "doctor acoustic bless"),
            "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f" to
                ("legal winner thank year wave sausage worth useful legal winner thank year " +
                    "wave sausage worth useful legal winner thank year wave sausage worth title"),
        )

        val m = mnemonic()
        vectors.forEach { (entropyHex, phrase) ->
            val entropy = hex(entropyHex)
            assertEquals("encode $entropyHex", phrase, m.encode(entropy).joinToString(" "))
            assertArrayEquals("decode $entropyHex", entropy, m.decode(phrase))
        }
    }

    // --- 7.1 round trip --------------------------------------------------------

    @Test
    fun everyEntropyRoundTrips() {
        val m = mnemonic()
        val rng = SecureRandom()
        repeat(1000) {
            val entropy = ByteArray(Mnemonic.ENTROPY_BYTES).also { b -> rng.nextBytes(b) }
            val phrase = m.encode(entropy)
            assertEquals("24 words", Mnemonic.WORD_COUNT, phrase.size)
            assertArrayEquals(entropy, m.decode(phrase.joinToString(" ")))
        }
    }

    // --- 7.4 rejection ---------------------------------------------------------

    @Test
    fun aChangedWordFailsTheChecksum() {
        val m = mnemonic()
        val phrase = m.encode(ByteArray(Mnemonic.ENTROPY_BYTES)).toMutableList()
        // "abandon" -> "ability": index 0 becomes 1, so the checksum no longer fits.
        phrase[0] = "ability"
        assertNull(m.decode(phrase.joinToString(" ")))
    }

    @Test
    fun theWrongWordCountFails() {
        val m = mnemonic()
        val phrase = m.encode(ByteArray(Mnemonic.ENTROPY_BYTES))
        assertNull("23 words", m.decode(phrase.dropLast(1).joinToString(" ")))
        assertNull("25 words", m.decode((phrase + "abandon").joinToString(" ")))
        assertNull("empty", m.decode(""))
    }

    @Test
    fun anUnknownWordFails() {
        val m = mnemonic()
        val phrase = m.encode(ByteArray(Mnemonic.ENTROPY_BYTES)).toMutableList()
        phrase[5] = "notaword"
        assertNull(m.decode(phrase.joinToString(" ")))
    }

    @Test
    fun everyRejectionReturnsNullAndNothingThrows() {
        val m = mnemonic()
        listOf(
            "", "   ", "abandon", "not a valid mnemonic at all",
            List(24) { "notaword" }.joinToString(" "),
            List(24) { "abandon" }.joinToString(" "),
        ).forEach { bad ->
            assertNull("should reject: '$bad'", m.decode(bad))
        }
    }

    // --- 7.5 transcription tolerance -------------------------------------------

    @Test
    fun spacingAndCaseAreForgiven() {
        val m = mnemonic()
        val entropy = ByteArray(Mnemonic.ENTROPY_BYTES) { it.toByte() }
        val phrase = m.encode(entropy).joinToString(" ")

        assertArrayEquals("upper", entropy, m.decode(phrase.uppercase()))
        assertArrayEquals("mixed", entropy, m.decode(phrase.replaceFirstChar { it.uppercase() }))
        assertArrayEquals("padded", entropy, m.decode("   $phrase   "))
        assertArrayEquals("double spaces", entropy, m.decode(phrase.replace(" ", "  ")))
        assertArrayEquals("newlines", entropy, m.decode(phrase.replace(" ", "\n")))
        assertArrayEquals("tabs", entropy, m.decode(phrase.replace(" ", "\t")))
    }

    /**
     * `recovery-kit-entry`. **The reported bug.** Separating a list with commas is a
     * reasonable thing to do, and it produced `abandon,` as a token — not in the
     * wordlist — so a correct transcription was reported as invalid.
     */
    @Test
    fun theSeparatorsPeopleActuallyUseAreForgiven() {
        val m = mnemonic()
        val entropy = ByteArray(Mnemonic.ENTROPY_BYTES) { it.toByte() }
        val words = m.encode(entropy)
        val phrase = words.joinToString(" ")

        assertArrayEquals("comma space", entropy, m.decode(words.joinToString(", ")))
        assertArrayEquals("comma only", entropy, m.decode(words.joinToString(",")))
        assertArrayEquals("semicolons", entropy, m.decode(words.joinToString("; ")))
        assertArrayEquals("full stops", entropy, m.decode(words.joinToString(". ")))
        assertArrayEquals(
            "non-breaking spaces", entropy, m.decode(words.joinToString("\u00A0")),
        )
        assertArrayEquals(
            "a mixture, which is what a real paste looks like",
            entropy,
            m.decode(words.mapIndexed { i, w -> if (i % 3 == 0) "$w,\n" else "$w " }.joinToString("")),
        )
        assertArrayEquals("trailing separator", entropy, m.decode("$phrase."))
    }

    /**
     * `recovery-kit-entry`. Punctuation *between* words is a separator; a character
     * *inside* a token leaves it unrecognised. Stripping it would invent a word the
     * user did not type.
     */
    @Test
    fun aCharacterInsideAWordIsNotStrippedAway() {
        val m = mnemonic()
        val words = m.encode(ByteArray(Mnemonic.ENTROPY_BYTES)).toMutableList()
        val original = words[0]
        words[0] = original.take(2) + "." + original.drop(2)

        assertNull(
            "a full stop inside a word must not be removed to make it match",
            m.decode(words.joinToString(" ")),
        )
    }

    // --- 7.6 reading a phrase back — `recovery-kit-entry` ------------------------

    /** One message for three problems was the defect. This is what tells them apart. */
    @Test
    fun readNamesTheWordsItDoesNotRecognise() {
        val m = mnemonic()
        val words = m.encode(ByteArray(Mnemonic.ENTROPY_BYTES)).toMutableList()
        words[4] = "abandonn"

        val reading = m.read(words.joinToString(" "))

        assertEquals("every word is read, in order", Mnemonic.WORD_COUNT, reading.words.size)
        assertEquals("the misspelt one is named by position", setOf(4), reading.unknown)
        assertEquals("abandonn", reading.words[4])
    }

    @Test
    fun readCountsWhatWasTypedWithoutJudgingIt() {
        val m = mnemonic()
        val words = m.encode(ByteArray(Mnemonic.ENTROPY_BYTES)).take(21)

        val reading = m.read(words.joinToString(", "))

        assertEquals("21 words counted", 21, reading.words.size)
        assertTrue("and all of them recognised", reading.unknown.isEmpty())
    }

    @Test
    fun readOfAnEmptyPhraseIsEmpty() {
        val reading = mnemonic().read("   ")
        assertTrue(reading.words.isEmpty())
        assertTrue(reading.unknown.isEmpty())
    }

    @Test
    fun aWordPrefixIsNotAccepted() {
        val m = mnemonic()
        val phrase = m.encode(ByteArray(Mnemonic.ENTROPY_BYTES)).toMutableList()
        // BIP39 words are unique in four letters, which is exactly the temptation.
        phrase[0] = "aban"
        assertNull("a prefix is a transcription this cannot verify", m.decode(phrase.joinToString(" ")))
    }

    @Test
    fun isValidAgreesWithDecode() {
        val m = mnemonic()
        val phrase = m.encode(ByteArray(Mnemonic.ENTROPY_BYTES)).joinToString(" ")
        assertTrue(m.isValid(phrase))
        assertTrue(!m.isValid("not a mnemonic"))
        assertNotNull(m.decode(phrase))
    }

    @Test
    fun aShortWordlistIsRejected() {
        val failed = runCatching { Mnemonic(words.take(100)) }.isFailure
        assertTrue("a wrong-sized wordlist must not construct", failed)
    }
}
