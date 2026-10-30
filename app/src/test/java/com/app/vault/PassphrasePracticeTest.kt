package com.app.vault

import com.app.vault.PassphrasePractice.Companion.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `recovery-practice` D1, on the JVM: every rule of the practice schedule, with the clock
 * handed in and the three values in memory.
 */
class PassphrasePracticeTest {

    private class Memory : PassphrasePractice.Store {
        val values = mutableMapOf<String, Long>()
        override fun getLong(name: String) = values[name]
        override fun putLong(name: String, value: Long) { values[name] = value }
        override fun remove(vararg names: String) { names.forEach { values.remove(it) } }
    }

    private var extraSecurity = true
    private var wrap = true
    private val store = Memory()
    private val practice = PassphrasePractice(store, { extraSecurity }, { wrap })

    /** A week's uptime in, so nothing passes only because the clock started at zero. */
    private val t0 = 1_790_000_000_000L
    private fun day(n: Double) = t0 + (n * DAY_MS).toLong()
    private fun dueIn(): Double = (store.values.getValue(PassphrasePractice.KEY_DUE_AT) - t0).toDouble() / DAY_MS

    @Test fun nothingIsDueBeforeTheScheduleStarts() {
        assertFalse(practice.isDue(day(100.0)))
    }

    @Test fun theFirstCheckIsDueOneDayAfterStarting() {
        practice.start(t0)
        assertFalse("not yet", practice.isDue(day(0.99)))
        assertTrue("25 hours later", practice.isDue(day(25.0 / 24)))
    }

    @Test fun rightOnTheFirstTryWidensOneIntervalAtATimeThenRepeatsThirty() {
        var now = t0
        practice.start(now)
        listOf(3, 7, 14, 30, 30, 30).forEach { expected ->
            now += practice.nextInterval() * DAY_MS
            assertTrue(practice.isDue(now))
            practice.recordRight(now, firstTry = true)
            assertEquals(expected, practice.nextInterval())
            assertFalse("just answered", practice.isDue(now + 1))
            assertTrue("due at the interval", practice.isDue(now + expected * DAY_MS))
            assertFalse("not a moment before", practice.isDue(now + expected * DAY_MS - 1))
        }
    }

    @Test fun aWrongEntryThenARightOneReturnsToOneDay() {
        practice.start(t0)
        var now = t0
        repeat(3) {
            now += practice.nextInterval() * DAY_MS
            practice.recordRight(now, firstTry = true)
        }
        assertEquals("at the 14-day step", 14, practice.nextInterval())
        now += 14 * DAY_MS
        practice.recordWrong()
        assertTrue("a wrong entry leaves the check due", practice.isDue(now))
        practice.recordRight(now, firstTry = false)
        assertEquals(1, practice.nextInterval())
        assertTrue(practice.isDue(now + DAY_MS))
        assertFalse(practice.isDue(now + DAY_MS - 1))
    }

    @Test fun skipKeepsTheStepAndAsksAgainInOneDay() {
        practice.start(t0)
        practice.recordRight(day(1.0), firstTry = true)
        practice.recordRight(day(4.0), firstTry = true)
        assertEquals(7, practice.nextInterval())
        practice.skip(day(11.0))
        assertEquals("the step is kept", 7, practice.nextInterval())
        assertEquals(12.0, dueIn(), 1e-9)
    }

    @Test fun aPassphraseUnlockMovesTheCheckOneCurrentIntervalAhead() {
        practice.start(t0)
        practice.recordRight(day(1.0), firstTry = true)
        assertEquals(3, practice.nextInterval())
        practice.notePassphraseUnlock(day(5.0))
        assertEquals("the step is unchanged", 3, practice.nextInterval())
        assertEquals(8.0, dueIn(), 1e-9)
        assertFalse(practice.isDue(day(7.9)))
    }

    @Test fun aPassphraseUnlockWithNoScheduleWritesNothing() {
        practice.notePassphraseUnlock(t0)
        assertTrue(store.values.isEmpty())
    }

    @Test fun aClockSetBeforeTheLastSetTimeIsDueAtOnce() {
        practice.start(t0)
        assertTrue(practice.isDue(t0 - 1))
    }

    @Test fun extraSecurityOffIsNeverDue() {
        practice.start(t0)
        extraSecurity = false
        assertFalse(practice.isDue(day(10.0)))
        assertFalse(practice.isDue(t0 - 1))
    }

    @Test fun noBiometricWrapIsNeverDue() {
        practice.start(t0)
        wrap = false
        assertFalse(practice.isDue(day(10.0)))
    }

    @Test fun clearRemovesAllThreeValues() {
        practice.start(t0)
        practice.recordRight(day(1.0), firstTry = true)
        practice.clear()
        assertTrue(store.values.isEmpty())
        assertFalse(practice.isDue(day(100.0)))
    }

    @Test fun startingAgainReturnsToTheFirstInterval() {
        practice.start(t0)
        practice.recordRight(day(1.0), firstTry = true)
        practice.recordRight(day(4.0), firstTry = true)
        practice.start(day(5.0))
        assertEquals(1, practice.nextInterval())
        assertEquals(6.0, dueIn(), 1e-9)
    }
}
