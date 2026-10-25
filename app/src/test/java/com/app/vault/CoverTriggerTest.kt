package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Triggers and the rate limiter — P2-24, re-shaped by `music-recorder-covers` and
 * again by `pattern-cover-trigger`.
 *
 * All of it is arithmetic and string handling, so all of it is here rather than on a
 * device. The half that matters most — *a near miss is indistinguishable from
 * nothing* — is a property of the caller and is asserted on device; what is asserted
 * here is that the decision underneath it is right, including the parts a hand-driven
 * emulator run would never reach, like the eleventh failed attempt inside a window.
 */
class CoverTriggerTest {

    private fun keys(vararg k: String) = Trigger.Keys(k.toList())

    private fun pattern(vararg dots: Int) = Trigger.Pattern(dots.toList())

    private val fiveDots = intArrayOf(0, 4, 8, 2, 6)

    // --- minimums ------------------------------------------------------------

    /**
     * The floors `FORMAT.md` §10 records are floors because these refuse anything
     * shorter. Without them the stated entropy is an aspiration.
     */
    @Test
    fun aTriggerBelowItsFloorIsNotValid() {
        assertFalse(keys("1", "2").valid)
        assertTrue(keys("1", "2", "3").valid)

        assertFalse(pattern(*fiveDots.take(4).toIntArray()).valid)
        assertTrue(pattern(*fiveDots).valid)
    }

    /**
     * `pattern-cover-trigger`. A stored value naming the same node twice is refused
     * outright rather than treated as a shorter distinct shape — `CoverGestures`'s own
     * handler never produces one (a node the path re-enters is simply not re-added,
     * `note`'s whole point), so a repeat reaching `decode` is a corrupted or
     * hand-edited value, and the safe reading of an unreadable value is null.
     */
    @Test
    fun aRepeatedNodeIsRefusedOutright() {
        assertFalse("a trailing repeat invalidates the whole shape", pattern(0, 4, 8, 2, 0).valid)
        assertFalse(
            "still refused even past the floor's raw length",
            pattern(0, 4, 8, 2, 6, 0).valid,
        )
        assertTrue("five wholly distinct nodes is the ordinary case", pattern(*fiveDots).valid)
    }

    // --- encoding ------------------------------------------------------------

    @Test
    fun everyTriggerRoundTrips() {
        val cases = listOf(
            Cover.Calculator to keys("7", "sin", "3", "("),
            Cover.MusicPlayer to pattern(*fiveDots),
            Cover.VoiceRecorder to pattern(0, 1, 2, 5, 8),
        )
        cases.forEach { (cover, trigger) ->
            assertEquals(cover.name, trigger, Trigger.decode(cover, trigger.encode()))
        }
    }

    /**
     * **Null, never a default.** An absent, empty, corrupt or below-floor stored value
     * leaves the cover unconfigured — a default here would be a published trigger
     * under another name, the same on every install.
     */
    @Test
    fun anUnreadableStoredValueIsNoTriggerAtAll() {
        Cover.entries.forEach { cover ->
            assertNull("${cover.name} null", Trigger.decode(cover, null))
            assertNull("${cover.name} empty", Trigger.decode(cover, ""))
        }
        assertNull("below floor", Trigger.decode(Cover.Calculator, keys("1", "2").encode()))
        assertNull(
            "too few nodes",
            Trigger.decode(Cover.MusicPlayer, pattern(*fiveDots.take(4).toIntArray()).encode()),
        )
        // A token this build cannot perform is no trigger at all, never a nearest
        // match: a stored value written by another build must not open a cover.
        assertNull("a node index outside the grid", Trigger.decode(Cover.MusicPlayer, "0\u001F1\u001F2\u001F3\u001F9"))
        assertNull("a non-numeric token", Trigger.decode(Cover.VoiceRecorder, "0\u001F1\u001F2\u001F3\u001Ffour"))

        // `pattern-cover-trigger`'s migration: an install upgrading from the shapes
        // this build no longer has. `Transport` encoded control names, `Phrase`
        // encoded a raw word — neither parses as digits `0..8`, so both answer null
        // exactly like any other unreadable value, and the cover it named becomes
        // unselectable until a pattern is set.
        assertNull("an old Transport string", Trigger.decode(Cover.MusicPlayer, "Next\u001FNext\u001FShuffle\u001FPlayPause"))
        assertNull("an old Phrase string", Trigger.decode(Cover.VoiceRecorder, "pelican"))
    }

    // --- matching ------------------------------------------------------------

    @Test
    fun matchingIsExactPerShape() {
        val stored = keys("7", "8", "9")
        assertTrue(stored.matches(keys("7", "8", "9")))
        assertFalse("order matters", stored.matches(keys("9", "8", "7")))
        assertFalse("length matters", stored.matches(keys("7", "8", "9", "9")))
        assertFalse("prefix is not a match", stored.matches(keys("7", "8")))

        val storedPattern = pattern(*fiveDots)
        assertTrue(storedPattern.matches(pattern(*fiveDots)))
        assertFalse("order matters", storedPattern.matches(pattern(*fiveDots.reversedArray())))
        assertFalse("a longer shape is another shape", storedPattern.matches(pattern(*fiveDots, 1)))
        assertFalse("a prefix is not a match", storedPattern.matches(pattern(*fiveDots.take(4).toIntArray())))
    }

    /**
     * ***Equality, not a tail match*** — `pattern-cover-trigger`'s D3. `Transport`
     * matched against the end of what had been pressed because transport presses are
     * ordinary use the trigger has to be enterable after; a pattern has a
     * pointer-down and a pointer-up and submits exactly once, so there is no
     * accumulated history for a tail match to mean anything against.
     */
    @Test
    fun aPatternSubmittedAfterOrdinaryUseStillMatchesOnlyExactly() {
        val stored = pattern(*fiveDots)
        // What would have matched a Transport's tail rule does not match a Pattern.
        assertFalse(stored.matches(pattern(1, 2, *fiveDots)))
        assertTrue(stored.matches(pattern(*fiveDots)))
    }

    /**
     * A shape mismatch answers false rather than throwing. A crash on the wrong input
     * would itself be a tell — the cover would behave differently when probed.
     */
    @Test
    fun aShapeMismatchIsFalseAndNotACrash() {
        assertFalse(keys("1", "2", "3").matches(pattern(*fiveDots)))
        assertFalse(pattern(*fiveDots).matches(keys("1", "2", "3")))
    }

    // --- the limiter ---------------------------------------------------------

    @Test
    fun aFreshRecordIsNotLocked() {
        assertFalse(Attempts().lockedAt(1_000L))
    }

    /** Four failures inside the window do not lock; the fifth does. */
    @Test
    fun theThresholdLocksOut() {
        var a = Attempts()
        repeat(Attempts.MAX_FAILURES - 1) { i ->
            a = a.failed(1_000L + i)
            assertFalse("locked after ${i + 1}", a.lockedAt(2_000L))
        }
        a = a.failed(1_000L + Attempts.MAX_FAILURES)
        assertTrue("not locked at the threshold", a.lockedAt(2_000L))
    }

    /** And it expires. A lockout that never ends is a broken cover, not a secure one. */
    @Test
    fun theLockoutExpires() {
        var a = Attempts()
        repeat(Attempts.MAX_FAILURES) { a = a.failed(1_000L) }
        assertTrue(a.lockedAt(1_000L + Attempts.LOCKOUT_MS - 1))
        assertFalse(a.lockedAt(1_000L + Attempts.LOCKOUT_MS))
    }

    /**
     * The window rolls. Four failures today and one tomorrow is not five in a window,
     * and treating it as one would lock out a user who simply fumbles occasionally.
     */
    @Test
    fun failuresOutsideTheWindowDoNotAccumulate() {
        var a = Attempts()
        repeat(Attempts.MAX_FAILURES - 1) { a = a.failed(1_000L) }
        a = a.failed(1_000L + Attempts.WINDOW_MS + 1)
        assertFalse("a stale window still locked out", a.lockedAt(2_000_000L))
        assertEquals("the window did not restart", 1, a.failures)
    }

    @Test
    fun aSuccessClearsTheRecord() {
        var a = Attempts()
        repeat(Attempts.MAX_FAILURES - 1) { a = a.failed(1_000L) }
        assertEquals(Attempts(), a.succeeded())
    }

    /**
     * **What the limiter is worth, stated as arithmetic.** `pattern-cover-trigger`'s
     * floor is the smallest of the three now that the calculator's ≈10 bits holds
     * that place by default — five nodes on a 3×3 grid, no repeats, order matters:
     * 9×8×7×6×5 = 15,120 ordered node-sequences, of which `valid` accepts every one
     * with five or more distinct entries. The limiter has to bite long before an
     * attacker can walk even the five-node slice of that space by hand.
     */
    @Test
    fun theLimiterOutlastsTheSmallestTriggerSpace() {
        val space = (9 downTo 5).fold(1.0) { acc, n -> acc * n }
        assertTrue("the space is smaller than the limiter's threshold", Attempts.MAX_FAILURES < space)
        assertTrue("the smallest space must still be four figures short of trivial", space >= 1_000)
    }

    /**
     * Rejected and Limited are separate values so the *caller* can be asserted to
     * treat them identically. They must never differ in anything a bystander sees,
     * and the device test is what proves that; this only fixes the vocabulary.
     * `cover-unlock-methods` took the fourth, the fingerprint pattern's, away.
     */
    @Test
    fun thereAreExactlyThreeOutcomes() {
        assertEquals(
            setOf(Outcome.Accepted, Outcome.Rejected, Outcome.Limited),
            Outcome.entries.toSet(),
        )
    }

    @Test
    fun coverNamesRoundTrip() {
        Cover.entries.forEach { assertEquals(it, Cover.of(it.name)) }
        assertNull(Cover.of(null))
        assertNull(Cover.of("Weather"))
    }

    // --- what the confirming step compares with ------------------------------

    /**
     * ***The confirming step is `==` and nothing else***, so structural equality is
     * load-bearing: a shape that compared by identity would accept any second
     * performance and the whole guard would be theatre while appearing to work.
     *
     * The near misses below are the ones a user actually makes — a key out of order,
     * a node out of order or one short.
     */
    @Test
    fun aNearMissIsNotEqualToWhatWasRecorded() {
        val keys = Trigger.Keys(listOf("7", "3", "sin"))
        assertEquals(keys, Trigger.Keys(listOf("7", "3", "sin")))
        assertNotEquals(keys, Trigger.Keys(listOf("7", "sin", "3")))
        assertNotEquals(keys, Trigger.Keys(listOf("7", "3", "cos")))
        assertNotEquals(keys, Trigger.Keys(listOf("7", "3", "sin", "4")))

        // The near miss a user actually makes drawing a pattern twice: the right
        // nodes, in the wrong order, or one short of the second attempt.
        val recorded = pattern(*fiveDots)
        assertEquals(recorded, pattern(*fiveDots))
        assertNotEquals(recorded, pattern(*fiveDots.reversedArray()))
        assertNotEquals(recorded, pattern(*fiveDots.take(4).toIntArray()))
    }

    /**
     * A performance under its cover's floor never reaches the confirming step, so the
     * floors `FORMAT.md` §10 records are floors whichever route a trigger arrives by.
     */
    @Test
    fun aPerformanceUnderItsFloorIsNotValid() {
        assertTrue(!Trigger.Keys(List(Trigger.MIN_KEYS - 1) { "1" }).valid)
        assertTrue(Trigger.Keys(List(Trigger.MIN_KEYS) { "1" }).valid)

        assertTrue(!pattern(*fiveDots.take(Trigger.MIN_DOTS - 1).toIntArray()).valid)
        assertTrue(pattern(*fiveDots.take(Trigger.MIN_DOTS).toIntArray()).valid)
    }

    // --- one secret — `cover-unlock-methods` -----------------------------------

    private val stored = pattern(*fiveDots)
    private val wrong = pattern(0, 1, 2, 5, 8)

    /** The limiter first, then the one stored trigger, and nothing after it. */
    @Test
    fun judgeComparesTheOneStoredTrigger() {
        assertEquals(Outcome.Accepted, judge(Attempts(), stored, stored, 1_000L).first)
        assertEquals(Outcome.Rejected, judge(Attempts(), stored, wrong, 1_000L).first)
        assertEquals("nothing stored accepts nothing", Outcome.Rejected, judge(Attempts(), null, stored, 1_000L).first)
        assertEquals("an acceptance clears the record", Attempts(), judge(Attempts(1, 1L), stored, stored, 1_000L).second)

        var a = Attempts()
        repeat(Attempts.MAX_FAILURES) { a = judge(a, stored, wrong, 1_000L + it).second }
        assertEquals("the correct trigger while limited", Outcome.Limited, judge(a, stored, stored, 2_000L).first)
        assertEquals("a limited attempt changes no record", a, judge(a, stored, stored, 2_000L).second)
    }

    /**
     * The doubled cooling-off existed only because two shapes were stored. One secret per
     * cover again, so every cover cools off for [Attempts.LOCKOUT_MS], whatever its method.
     */
    @Test
    fun theCoolingOffIsTheSameEverywhere() {
        listOf(stored to wrong, keys("7", "8", "9") to keys("1", "1", "1")).forEach { (secret, miss) ->
            var a = Attempts()
            repeat(Attempts.MAX_FAILURES) { a = judge(a, secret, miss, 1_000L).second }
            assertEquals(Attempts.LOCKOUT_MS, a.lockedUntilMs - 1_000L)
        }
    }

    // --- `cover-hold-dots` D5 ---------------------------------------------------------

    /**
     * The hold area lives under the cover's own name, so the sweep keeps it for a known cover and
     * takes it for a removed one, and it collides with no other key. The store round trip, `clear`
     * and `isConfigured` are `CoverDeviceTest`'s, which has a store to write to.
     */
    @Test
    fun theHoldKeyBelongsToItsCover() {
        Cover.entries.forEach { cover ->
            val key = holdKey(cover)
            assertTrue(knownCoverKey(key))
            assertTrue(key.startsWith(cover.name))
            listOf("${cover.name}window", "${cover.name}failures", "${cover.name}locked")
                .forEach { assertNotEquals(it, key) }
        }
        assertFalse("a removed cover's area survives the sweep", knownCoverKey("Flashlighthold"))
    }

    // --- `calculator-dots-methods` D1, D2 -------------------------------------------------

    /**
     * Each row's secrets read back as that row, and every other combination as none. The store
     * round trip — `set` writing one row's keys and removing the others', `clear` — is
     * `CoverDeviceTest`'s, which has a store to write to; this is the decision under it.
     */
    @Test
    fun eachCalculatorRowRoundTripsAndNothingElseIsARow() {
        val seq = keys("7", "3", "sin")
        val shape = pattern(*fiveDots)
        mapOf(
            CalculatorMethod.Sequence to calculatorRow(seq, null, null),
            CalculatorMethod.SequenceThenPattern to calculatorRow(seq, null, shape),
            CalculatorMethod.HoldThenPattern to calculatorRow(shape, 11, null),
        ).forEach { (method, row) -> assertEquals(method, row) }
        assertEquals(CalculatorMethod.Sequence, calculatorMethodOf(keys = true, pattern = false, hold = false))
        assertEquals(CalculatorMethod.SequenceThenPattern, calculatorMethodOf(keys = true, pattern = true, hold = false))
        assertEquals(CalculatorMethod.HoldThenPattern, calculatorMethodOf(keys = false, pattern = true, hold = true))

        assertNull("a sequence with a hold", calculatorRow(seq, 3, null))
        assertNull("a sequence with a hold and a pattern", calculatorRow(seq, 3, shape))
        assertNull("a pattern with no hold", calculatorRow(shape, null, null))
        assertNull("a pattern with a second pattern", calculatorRow(shape, 3, shape))
        listOf(
            Triple(false, false, false), Triple(false, false, true), Triple(false, true, false),
            Triple(true, false, true), Triple(true, true, true),
        ).forEach { (k, p, h) -> assertNull("$k $p $h", calculatorMethodOf(k, p, h)) }
    }

    /** The calculator's pattern lives under its own name, beside its sequence, and round-trips. */
    @Test
    fun theCalculatorsPatternKeyBelongsToItAndRoundTrips() {
        val key = patternKey(Cover.Calculator)
        assertEquals("Calculatorpattern", key)
        assertTrue(knownCoverKey(key))
        assertNotEquals(Cover.Calculator.name, key)
        assertNotEquals(holdKey(Cover.Calculator), key)
        assertFalse("a removed cover's pattern survives the sweep", knownCoverKey("Flashlightpattern"))
        val shape = pattern(*fiveDots)
        assertEquals(shape, Trigger.decodePattern(shape.encode()))
        assertNull(Trigger.decodePattern(keys("7", "3", "sin").encode()))
        assertNull(Trigger.decodePattern(pattern(0, 1, 2, 3).encode()))
    }

    /** D2. A non-final match accepts and leaves the record exactly as it was; a final one clears it. */
    @Test
    fun aNonFinalMatchLeavesTheRecordUnchanged() {
        val seq = keys("7", "3", "sin")
        val record = Attempts(3, 1_000L)
        assertEquals(Outcome.Accepted to record, judge(record, seq, seq, 2_000L, final = false))
        assertEquals(Outcome.Accepted to Attempts(), judge(record, seq, seq, 2_000L))
        assertEquals("a wrong non-final sequence still counts", 4, judge(record, seq, keys("1", "1", "1"), 2_000L, final = false).second.failures)
    }

    /**
     * D2's reason. Four wrong patterns, each after the right sequence, leave four failures, and a
     * fifth wrong pattern starts the cooling-off — the right sequence never wipes the count.
     */
    @Test
    fun aRightSequenceDoesNotResetThePatternCount() {
        val seq = keys("7", "3", "sin")
        val shape = pattern(*fiveDots)
        var a = Attempts()
        repeat(4) {
            val (opened, afterSeq) = judge(a, seq, seq, 1_000L + it, final = false)
            assertEquals(Outcome.Accepted, opened)
            a = judge(afterSeq, shape, wrong, 1_000L + it).second
        }
        a = judge(a, seq, seq, 1_010L, final = false).second
        assertEquals(4, a.failures)
        a = judge(a, shape, wrong, 1_011L).second
        assertTrue("the fifth wrong pattern did not lock out", a.lockedAt(1_012L))
        assertEquals("the right sequence during the lockout", Outcome.Limited, judge(a, seq, seq, 1_013L, final = false).first)
    }

    /** No default area: absent, negative or past 11 read as none. */
    @Test
    fun aStoredAreaIsReadBackOrNone() {
        assertEquals(12, HOLD_AREAS)
        (0 until HOLD_AREAS).forEach { assertEquals(it, holdOf(it)) }
        listOf(null, -1, 12, 99).forEach { assertNull("$it", holdOf(it)) }
    }
}
