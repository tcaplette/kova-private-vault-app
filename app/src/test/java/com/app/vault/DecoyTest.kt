package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * P2-2's generator, as arithmetic — the two properties the Verify names, asserted
 * over thousands of generated sets rather than one lucky run.
 *
 * JVM scope on purpose: [Decoy] takes its clock and its randomness as arguments,
 * so none of this needs a device, and a property that holds "usually" fails here
 * instead of on a phone six months from now.
 */
class DecoyTest {

    private val now = 1_770_000_000_000L   // a fixed instant, so failures are reproducible
    private val startedAt = now - 10 * 60_000L
    private val window = Decoy.setupWindow(startedAt, now)

    private fun rng(seed: Int) = Random(seed)

    // --- the window -------------------------------------------------------------

    @Test
    fun theSetupWindowCoversTheWholeSitting() {
        assertTrue("the moment the flow started", startedAt in window)
        assertTrue("now", now in window)
        assertTrue("this morning", now - 6 * 3_600_000L in window)
        assertTrue("yesterday evening, inside the widening", now - 20 * 3_600_000L in window)
        assertFalse("three days ago", now - 3 * Decoy.DAY in window)
    }

    // --- assigned dates ---------------------------------------------------------

    @Test
    fun anAssignedCaptureDateSitsBetweenEightMonthsAndTwoWeeksAgo() {
        repeat(10_000) { seed ->
            val date = Decoy.assignedCreatedAt(now, rng(seed))
            assertTrue("too old: $date", date >= now - Decoy.OLDEST_DAYS * Decoy.DAY)
            assertTrue("too recent: $date", date <= now - Decoy.NEWEST_DAYS * Decoy.DAY)
            assertFalse("landed in the setup window", date in window)
        }
    }

    @Test
    fun anAssignedImportNeverPrecedesCaptureOrTheVault() {
        repeat(10_000) { seed ->
            val r = rng(seed)
            val origin = Decoy.origin(now - 100 * Decoy.DAY, window, now, r)
            val created = Decoy.assignedCreatedAt(now, r)
            val imported = Decoy.assignedImportedAt(created, origin, window, r)

            assertTrue("import before capture", imported >= created)
            assertTrue("import before the vault existed", imported >= origin)
            assertFalse("landed in the setup window", imported in window)
        }
    }

    // --- the origin and its floor -------------------------------------------------

    @Test
    fun theOriginIsNeverEarlierThanTheInstall() {
        val installedAt = now - 30 * Decoy.DAY
        repeat(10_000) { seed ->
            val origin = Decoy.origin(installedAt, window, now, rng(seed))
            assertTrue("a vault cannot predate its app: $origin", origin >= installedAt)
            assertFalse("origin must not reach the setup window", origin in window)
        }
    }

    @Test
    fun theOriginIsCappedAtEightMonthsOnAnOldInstall() {
        val installedAt = now - 5 * 365 * Decoy.DAY
        repeat(1_000) { seed ->
            val origin = Decoy.origin(installedAt, window, now, rng(seed))
            assertTrue("older than the cap", origin >= now - Decoy.OLDEST_DAYS * Decoy.DAY)
        }
    }

    @Test
    fun aFreshInstallGetsTheHonestAnswerRatherThanAnInventedOne() {
        // Installed an hour ago. There is no history available and the generator
        // says so by collapsing onto the install itself, rather than claiming an
        // age the file list would disprove.
        val installedAt = now - 3_600_000L
        val origin = Decoy.origin(installedAt, window, now, rng(1))
        assertEquals("the truthful answer is now, not an age `ls` disproves", now, origin)
        assertTrue(
            "no history fits between an hour-old install and the setup window",
            Decoy.unlockHistory(origin, window, rng(1)).isEmpty()
        )
    }

    // --- the plan ------------------------------------------------------------------

    @Test
    fun aRealCaptureDateIsKeptAndNeverReplaced() {
        val real = now - 200 * Decoy.DAY
        val decisions = Decoy.plan(listOf(real), window, now, rng(7))
        val kept = decisions.single()
        assertTrue(kept is Decoy.Decision.Keep)
        assertEquals("the index must agree with the file's own metadata", real, kept.date)
    }

    @Test
    fun anUndatedSourceIsAssignedOne() {
        val decision = Decoy.plan(listOf(null), window, now, rng(7)).single()
        assertTrue(decision is Decoy.Decision.Assign)
        assertFalse("assigned into the setup window", decision.date!! in window)
    }

    @Test
    fun aSourceCapturedDuringSetupIsDeclined() {
        val decisions = Decoy.plan(
            listOf(now - 3_600_000L, now, startedAt, now + Decoy.DAY / 2),
            window, now, rng(7)
        )
        decisions.forEach {
            assertTrue("must be declined: $it", it is Decoy.Decision.Decline)
            assertNull(it.date)
            assertEquals(Decoy.CAPTURED_DURING_SETUP, (it as Decoy.Decision.Decline).reason)
        }
    }

    @Test
    fun theWindowEdgeIsInsideTheWindow() {
        // Exactly on the boundary, both sides. A decision that flips here is a
        // decision that flips on a clock tick.
        listOf(window.from, window.to).forEach { edge ->
            val decision = Decoy.plan(listOf(edge), window, now, rng(3)).single()
            assertTrue("the edge belongs to the window: $edge", decision is Decoy.Decision.Decline)
        }
        val justOutside = Decoy.plan(listOf(window.from - 1), window, now, rng(3)).single()
        assertTrue(justOutside is Decoy.Decision.Keep)
    }

    // --- the gates -------------------------------------------------------------------

    @Test
    fun anAllUndatedSetPassesBothGates() {
        repeat(2_000) { seed ->
            val r = rng(seed)
            val dates = Decoy.plan(List(Decoy.MIN_ITEMS) { null }, window, now, r).mapNotNull { it.date }
            assertEquals(Decoy.MIN_ITEMS, dates.size)
            assertTrue("something landed in the setup window", Decoy.outsideWindow(dates, window))
            assertTrue("spread was ${Decoy.spreadWeeks(dates)} weeks", Decoy.spreadIsEnough(dates))
        }
    }

    @Test
    fun aSetSpanningSixWeeksFailsTheGate() {
        val dates = (0 until 40).map { now - 20 * Decoy.DAY - it * Decoy.DAY }
        assertEquals(5, Decoy.spreadWeeks(dates))
        assertFalse("six weeks is not eight", Decoy.spreadIsEnough(dates))
        assertTrue("but nothing is in the setup window", Decoy.outsideWindow(dates, window))
    }

    @Test
    fun oneDateHasNoSpread() {
        assertEquals(0, Decoy.spreadWeeks(listOf(now - 100 * Decoy.DAY)))
        assertEquals(0, Decoy.spreadWeeks(emptyList()))
    }

    // --- the unlock history -----------------------------------------------------------

    @Test
    fun theHistoryAscendsStrictlyAndSpansTheWholePeriod() {
        val installedAt = now - 200 * Decoy.DAY
        repeat(5_000) { seed ->
            val r = rng(seed)
            val origin = Decoy.origin(installedAt, window, now, r)
            val history = Decoy.unlockHistory(origin, window, r)
            if (history.isEmpty()) return@repeat

            assertTrue("too few", history.size >= Decoy.MIN_UNLOCKS)
            assertTrue("too many", history.size <= Decoy.MAX_UNLOCKS)
            assertEquals("must ascend strictly", history.sorted().distinct(), history)
            assertTrue("started before the vault existed", history.first() > origin)
            assertTrue("ran into the setup window", history.last() < window.from)
        }
    }

    @Test
    fun theHistoryNeverPrecedesTheVaultsOwnCreationEntry() {
        // The defect this ordering exists to prevent: entry 0 says "created", and
        // an entry before it in time is something no honest vault can produce.
        val origin = Decoy.origin(now - 200 * Decoy.DAY, window, now, rng(11))
        val history = Decoy.unlockHistory(origin, window, rng(11))
        val timeline = listOf(origin) + history
        assertEquals("the whole timeline ascends", timeline.sorted(), timeline)
    }
}
