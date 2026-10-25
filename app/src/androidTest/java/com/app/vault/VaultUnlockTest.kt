package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * P1-3 — trial-decrypt unlock, against OUTLINE P1-3's Verify.
 *
 * The no-early-exit property is proven by the operation counts in the first two
 * groups. The timing test at the end is a gross-regression check only: an early
 * exit costs ~10 µs against a ~500 ms derivation, so no stopwatch could see it.
 */
@RunWith(AndroidJUnit4::class)
class VaultUnlockTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultUnlock"
        val PASS = "correct horse battery staple".toByteArray()
        val OTHER = "an entirely different passphrase".toByteArray()
        const val TIMING_RUNS = 5
        /** Wide on purpose: GC and scheduling must never fail this. */
        const val TIMING_BAND = 1.5
    }

    @Before fun setUp() { dir = File(ctx.filesDir, "unlock-test").apply { mkdirs() } }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun blobId() = ByteArray(16).also { rng.nextBytes(it) }

    /** A slot array with [passphrase] sealed into each of [slots]. */
    private fun install(
        name: String,
        vararg slots: Int,
        passphrase: ByteArray = PASS
    ): Pair<SlotArray, Map<Int, Pair<ByteArray, ByteArray>>> {
        val array = SlotArray(File(dir, name)).also { it.initialiseIfAbsent() }
        val kek = VaultKeys.deriveKek(passphrase, array.installSalt(), array.kdfParams())
        val sealed = slots.associateWith {
            val vmk = VaultKeys.newVmk()
            val blob = blobId()
            array.seal(it, kek, vmk, blob)
            vmk to blob
        }
        return array to sealed
    }

    // --- group 2: operation counts --------------------------------------------

    @Test fun matchAtFirstSlotStillAttemptsEveryone() {
        val (array, _) = install("first.slots", 0)
        val outcome = VaultUnlock.unlock(PASS, array)

        assertTrue("vault did not open", outcome.opened)
        assertEquals(8, outcome.slotsAttempted)
        assertEquals(1, outcome.derivations)
    }

    @Test fun matchAtLastSlotAttemptsEveryone() {
        val (array, _) = install("last.slots", 7)
        val outcome = VaultUnlock.unlock(PASS, array)

        assertTrue("vault did not open", outcome.opened)
        assertEquals(8, outcome.slotsAttempted)
        assertEquals(1, outcome.derivations)
    }

    @Test fun noMatchAttemptsEveryone() {
        val (array, _) = install("none.slots", 3)
        val outcome = VaultUnlock.unlock(OTHER, array)

        assertFalse("something opened", outcome.opened)
        assertEquals(8, outcome.slotsAttempted)
        assertEquals(1, outcome.derivations)
    }

    @Test fun allThreeOutcomesDoIdenticalWork() {
        val first = VaultUnlock.unlock(PASS, install("w-first.slots", 0).first)
        val last = VaultUnlock.unlock(PASS, install("w-last.slots", 7).first)
        val none = VaultUnlock.unlock(OTHER, install("w-none.slots", 3).first)

        val work = listOf(first, last, none).map { it.derivations to it.slotsAttempted }
        assertEquals("work differs across outcomes: $work", 1, work.distinct().size)
        Log.i(TAG, "work per outcome (derivations, slotsAttempted): ${work.first()} for all three")
    }

    @Test fun oneVaultBehavesLikeSeveral() {
        val one = VaultUnlock.unlock(PASS, install("one.slots", 4).first)
        val three = VaultUnlock.unlock(PASS, install("three.slots", 1, 4, 6).first)

        assertEquals(one.slotsAttempted, three.slotsAttempted)
        assertEquals(one.derivations, three.derivations)
    }

    // --- group 3: behaviour ----------------------------------------------------

    @Test fun roundTripReturnsWhatWasSealed() {
        val (array, sealed) = install("round.slots", 5)
        val (vmk, blob) = sealed.getValue(5)

        val vault = VaultUnlock.unlock(PASS, array).vault!!
        assertArrayEquals(vmk, vault.vmk)
        assertArrayEquals(blob, vault.metadataBlobId)
        assertEquals(5, vault.slotIndex)
    }

    @Test fun wrongPassphraseOpensNothing() {
        val (array, _) = install("wrong.slots", 2)
        assertNull(VaultUnlock.unlock(OTHER, array).vault)
    }

    @Test fun freshInstallOpensNothingWithSameCounts() {
        val array = SlotArray(File(dir, "fresh.slots")).also { it.initialiseIfAbsent() }
        val outcome = VaultUnlock.unlock(PASS, array)

        assertNull(outcome.vault)
        assertEquals(8, outcome.slotsAttempted)
        assertEquals(1, outcome.derivations)
    }

    @Test fun sharedPassphraseOpensTheLowestSlotSilently() {
        val (array, _) = install("shared.slots", 2, 5)
        val outcome = VaultUnlock.unlock(PASS, array)

        assertEquals(2, outcome.vault!!.slotIndex)
        assertEquals(8, outcome.slotsAttempted)
        assertEquals(1, outcome.derivations)
    }

    @Test fun outcomeExposesNoOccupancyCount() {
        val names = UnlockOutcome::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()

        assertEquals(
            "UnlockOutcome gained a field — check it does not reveal occupancy: $names",
            setOf("vault", "derivations", "slotsAttempted"),
            names
        )
    }

    // --- group 4: timing envelope ----------------------------------------------

    @Test fun unlockCostDoesNotVaryWithOutcome() {
        // Gross-regression check only. The no-early-exit property is proven by
        // the operation counts above; a stopwatch cannot see a 10 us difference
        // underneath a ~500 ms Argon2id derivation.
        // Built outside the timed region: install() derives a KEK of its own to
        // seal with, and timing it here would measure two derivations per run.
        val first = install("t0.slots", 0).first
        val last = install("t7.slots", 7).first
        val none = install("tn.slots", 3).first

        val cases = linkedMapOf(
            "match slot 0" to { VaultUnlock.unlock(PASS, first) },
            "match slot 7" to { VaultUnlock.unlock(PASS, last) },
            "no match" to { VaultUnlock.unlock(OTHER, none) }
        )

        // Warm up, then interleave. Both corrections are P1-9's, and both are
        // about the measurement rather than the property.
        //
        // As written, this ran each case five times in its own block, so anything
        // that made the *first ten seconds* of the run slow landed entirely on
        // whichever case went first. On this emulator that is worth ~160 ms
        // against a ~270 ms derivation — 1.6x on its own, which fails a 1.5x band
        // with nothing wrong in the code. Three consecutive runs blamed
        // {427, 428, 272}, {420, 272, 276} and {429, 271, 268}: the slow slot
        // moves, and a real outcome dependency does not move.
        //
        // Round-robin makes host drift hit all three equally, which is the only
        // way a wall clock can compare them at all. The band stays at 1.5x.
        cases.values.forEach { it() }

        val samples = cases.mapValues { mutableListOf<Long>() }
        repeat(TIMING_RUNS) {
            cases.forEach { (name, run) ->
                val start = System.nanoTime()
                run()
                samples.getValue(name) += (System.nanoTime() - start) / 1_000_000
            }
        }

        // The **minimum** of each case's samples, not the median. Contention only
        // ever adds time, so the fastest sample is the one least polluted by the
        // host; a median still carries whatever the machine was doing during half
        // the run. Measured on this emulator: Argon2id ranges 249–500 ms with the
        // same parameters, so medians of five swung past a 1.5x band while the
        // code under test did not change at all.
        val costs = samples.mapValues { (_, ms) -> ms.min() }

        val slowest = costs.values.max()
        val fastest = costs.values.min()
        val spread = slowest.toDouble() / fastest

        Log.i(TAG, "unlock minima: $costs | spread ${"%.2f".format(spread)}x, band ${TIMING_BAND}x")

        assertTrue(
            "unlock cost varies with outcome: $costs (spread ${"%.2f".format(spread)}x)",
            spread <= TIMING_BAND
        )
    }
}
