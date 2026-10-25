package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The floor, as measurements rather than as prose — `passphrase-strength-meter` D2.
 *
 * Every passphrase named here was measured against zxcvbn4j 1.9.0 before the floor was
 * chosen, and the choice of *two* conditions rather than one comes directly out of the two
 * groups below: strings that clear fifteen characters and are guessed immediately, and a
 * string that scores 4 at fourteen characters.
 *
 * JVM scope. zxcvbn is a plain jar reading its dictionaries off the classpath, so no
 * emulator is involved — which is also what makes this the right place to pin the numbers
 * the whole change rests on. That the same resources survive **APK** packaging is a
 * different claim and is proven on the emulator, not here.
 */
class PassphraseStrengthTest {

    companion object {
        /** Once for the class: the engine reads ~800 KB of dictionaries on construction. */
        @BeforeClass
        @JvmStatic
        fun warm() = PassphraseStrength.warm()
    }

    // --- the two conditions are each load-bearing -------------------------------

    @Test
    fun lengthAloneDoesNotPass() {
        // Each of these is at or above MIN_CODE_POINTS and would have passed a
        // length-only floor. This is the whole argument for estimating at all.
        for (weak in listOf(
            "passwordpassword",
            "aaaaaaaaaaaaaaa",
            "abcdefghijklmno",
            "qwertyuiopasdfg",
            "Tr0ub4dour&3xxx",
        )) {
            val verdict = PassphraseStrength.estimate(weak)
            assertTrue(
                "$weak is meant to be long enough to defeat a length-only floor",
                verdict.codePoints >= PassphraseStrength.MIN_CODE_POINTS,
            )
            assertFalse("$weak must not clear the floor", verdict.clears)
            assertTrue(
                "$weak scored ${verdict.score}, expected below ${PassphraseStrength.MIN_SCORE}",
                verdict.score < PassphraseStrength.MIN_SCORE,
            )
        }
    }

    @Test
    fun estimatedStrengthAloneDoesNotPass() {
        // Scores 4 and is 14 code points. A score-only floor would have taken it.
        val verdict = PassphraseStrength.estimate("not the phrase")
        assertEquals(14, verdict.codePoints)
        assertEquals(4, verdict.score)
        assertFalse("14 code points must be refused whatever it scores", verdict.clears)
    }

    @Test
    fun clearingBothConditionsPasses() {
        for (good in listOf(
            "three quiet lemons argue",
            "correct horse battery",
            "xK7#mQ2\$vL9pR4wZ",
        )) {
            val verdict = PassphraseStrength.estimate(good)
            assertTrue("$good must clear the floor", verdict.clears)
            assertEquals("cleared, so nothing more to say", null, verdict.sentence)
            assertEquals("Strong enough", verdict.wordText)
        }
    }

    /**
     * A generated random password is the case zxcvbn is known to under-rate: with no
     * pattern to match it falls back on a conservative pool. It still clears the floor,
     * which is what makes the conservatism safe rather than obstructive.
     */
    @Test
    fun aGeneratedPasswordIsNotPunishedForHavingNoPattern() {
        assertTrue(PassphraseStrength.clears("xK7#mQ2\$vL9pR4wZ"))
    }

    // --- code points, not bytes -------------------------------------------------

    @Test
    fun lengthIsCountedInCodePointsNotBytes() {
        // 14 characters, 28 UTF-8 bytes. The old 8-byte floor took four of these.
        val accented = "ééééééééééééé" + "é"
        assertEquals(14, accented.codePointCount(0, accented.length))
        assertTrue("well over the byte floor", accented.toByteArray(Charsets.UTF_8).size > 15)
        assertFalse("14 characters is 14 characters", PassphraseStrength.estimate(accented).clears)
    }

    @Test
    fun astralCharactersCountOnceEach() {
        // Each emoji is one code point and two UTF-16 units. Counting `length` would
        // have called this 30 characters.
        val astral = "🔐".repeat(15)
        assertEquals(30, astral.length)
        assertEquals(15, PassphraseStrength.estimate(astral).codePoints)
    }

    // --- forbidden words --------------------------------------------------------

    /**
     * The case is chosen so that the forbidden word is the *only* thing refusing it.
     * `marmalade marmalade marmalade` is 29 code points and scores 4 on its own, so it
     * clears the floor outright — until the vault it protects is called `Marmalade`, at
     * which point it scores 2. A passphrase that failed either way would not prove the
     * mechanism does anything.
     */
    @Test
    fun theVaultsOwnNameIsTreatedAsGuessable() {
        val repeated = "marmalade marmalade marmalade"

        val bare = PassphraseStrength.estimate(repeated)
        assertTrue("the case is only meaningful if it clears unaided", bare.clears)

        val named = PassphraseStrength.estimate(repeated, listOf("Marmalade"))
        assertFalse("a vault must not open to its own name", named.clears)
        assertTrue(
            "the name must lower the score, not merely fail the length",
            named.score < bare.score,
        )
    }

    /**
     * The app's own words, like the vault's name, have to change an outcome rather than
     * merely be passed in. `passphrase passphrase` is 21 code points and scores 4 unaided.
     */
    @Test
    fun theAppsOwnWordsAreTreatedAsGuessable() {
        val phrase = "passphrase passphrase"
        assertTrue("only meaningful if it clears unaided", PassphraseStrength.estimate(phrase).clears)
        assertFalse(
            "the app's own vocabulary must not be usable as the credential",
            PassphraseStrength.estimate(phrase, PassphraseStrength.APP_WORDS).clears,
        )
    }

    // --- the display verdict while an estimate is in flight ---------------------

    @Test
    fun theCharacterCountIsNeverStale() {
        // A score measured from a longer passphrase must not stop the count tracking
        // what is actually in the field — this is what keeps the field responsive.
        val measured = PassphraseStrength.estimate("three quiet lemons argue")
        val shown = PassphraseStrength.pending("three quiet", measured, settled = false)
        assertEquals(11, shown.codePoints)
        assertEquals("11 of 15 characters", shown.sentenceText)
    }

    @Test
    fun anUnsettledEstimateCannotUnlockTheForwardAction() {
        // Deleting a word out of a strong passphrase must not leave `clears` true for
        // text nobody has measured yet.
        val strong = PassphraseStrength.estimate("three quiet lemons argue")
        assertTrue(strong.clears)
        val stale = PassphraseStrength.pending("three quiet lemons argu", strong, settled = false)
        assertFalse("a score must never be credited to text it was not measured from", stale.clears)
        val settled = PassphraseStrength.pending("three quiet lemons argue", strong, settled = true)
        assertTrue(settled.clears)
    }

    @Test
    fun theLastKnownLevelIsHeldRatherThanBlanked() {
        // Blanking between keystrokes would make the segments flicker on every letter.
        val strong = PassphraseStrength.estimate("three quiet lemons argue")
        val shown = PassphraseStrength.pending("three quiet lemons argue!", strong, settled = false)
        assertEquals("the word holds while the next estimate runs", strong.word, shown.word)
        assertEquals(strong.score, shown.score)
    }

    @Test
    fun anEmptiedFieldGoesBackToSayingNothing() {
        val strong = PassphraseStrength.estimate("three quiet lemons argue")
        val shown = PassphraseStrength.pending("", strong, settled = false)
        assertEquals(PassphraseStrength.NOTHING_TYPED, shown)
    }

    // --- the estimate's cap -----------------------------------------------------

    @Test
    fun aPassphraseLongerThanTheCapIsStillMeasured() {
        val long = "correct horse battery staple ".repeat(6)
        assertTrue(long.codePointCount(0, long.length) > PassphraseStrength.ESTIMATE_CODE_POINTS)
        assertTrue("a long strong passphrase still clears", PassphraseStrength.clears(long))
    }

    @Test
    fun theCapDoesNotSplitASurrogatePair() {
        // 100 code points of astral characters then a word: capping on UTF-16 units
        // would hand the estimator half a surrogate pair.
        val astral = "🔐".repeat(PassphraseStrength.ESTIMATE_CODE_POINTS) + " tail"
        val verdict = PassphraseStrength.estimate(astral)
        assertNotNull("must produce a verdict rather than throwing", verdict)
        assertEquals(
            PassphraseStrength.ESTIMATE_CODE_POINTS + 5,
            verdict.codePoints,
        )
    }

    // --- what the meter is told to draw -----------------------------------------

    @Test
    fun aShortPassphraseIsToldHowFarItHasToGo() {
        val verdict = PassphraseStrength.estimate("three quiet")
        assertEquals(11, verdict.codePoints)
        assertEquals("11 of 15 characters", verdict.sentenceText)
        assertFalse(verdict.clears)
    }

    @Test
    fun aLongButWeakPassphraseIsToldWhatIsWrong() {
        val verdict = PassphraseStrength.estimate("passwordpassword")
        assertNotNull("a reason is owed once length is no longer the problem", verdict.sentence)
        assertFalse(
            "the reason must not be the character count once it is long enough",
            verdict.sentenceText!!.contains("of 15 characters"),
        )
    }

    @Test
    fun theReasonComesFromTheAppsOwnBundle() {
        // zxcvbn's own text for this pattern is "This is a top-10 common password."
        val verdict = PassphraseStrength.estimate("password12345678")
        assertNotNull(verdict.sentence)
        assertFalse(
            "the library's wording must not reach the screen: '${verdict.sentenceText}'",
            verdict.sentenceText!!.startsWith("This is a top-10"),
        )
    }

    @Test
    fun noVerdictOffersACrackTime() {
        // zxcvbn's slow-hash scenario assumes 10^4 guesses/sec; this app is nearer 2.
        val forbidden = listOf("second", "minute", "hour", "day", "month", "year", "centur")
        for (candidate in listOf("password", "passwordpassword", "Tr0ub4dour&3xxx", "three quiet")) {
            val sentence = PassphraseStrength.estimate(candidate).sentenceText?.lowercase() ?: continue
            for (unit in forbidden) {
                assertFalse(
                    "'$sentence' names a time unit — no crack time may reach the user",
                    sentence.contains(unit),
                )
            }
        }
    }

    @Test
    fun everyScoreHasItsOwnWord() {
        val words = listOf(
            "password",                 // 0 — log10 0.48
            "Password1!",               // 1 — log10 4.26
            "Summer2026x",              // 2 — log10 7.44
            "Tr0ub4dour&3xxx",          // 3 — log10 9.47
            "three quiet lemons argue", // 4 — log10 18.29
        ).map { PassphraseStrength.estimate(it).wordText }
        assertEquals("each level must be distinguishable in words alone", 5, words.toSet().size)
        assertFalse(
            "a refused level must not be called strong",
            PassphraseStrength.estimate("Tr0ub4dour&3xxx").wordText.contains("Strong"),
        )
    }

    @Test
    fun anEmptyFieldSaysNothing() {
        val verdict = PassphraseStrength.estimate("")
        assertEquals("", verdict.wordText)
        assertNull(verdict.sentence)
        assertFalse(verdict.clears)
    }

    // --- the floor's own numbers ------------------------------------------------

    @Test
    fun theFloorIsFifteenCodePointsAndScoreFour() {
        // Stated here so a change to either number has to be a deliberate edit to a
        // test that says why, not a silent constant bump.
        assertEquals(15, PassphraseStrength.MIN_CODE_POINTS)
        assertEquals(4, PassphraseStrength.MIN_SCORE)
    }
}

/** The verdict as the meter draws it in English — `app-languages`. */
private val PassphraseStrength.Verdict.wordText: String get() = word?.let(TestWords::text) ?: ""
private val PassphraseStrength.Verdict.sentenceText: String? get() = sentence?.text(TestWords)
