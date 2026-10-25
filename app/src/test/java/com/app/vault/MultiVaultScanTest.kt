package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "No vault list, no menu, no count" as a build failure — P2-1.
 *
 * The property is that nothing the user can see says how many vaults an install
 * holds or which slot a passphrase opened. The mechanical half of that is that
 * the surface never gets to hold the number: `VaultUnlock.provenSlots` returns
 * slot indices and `VaultSession.openVault` carries one, and both are confined to
 * the lifecycle and the biometric wrap that seals it.
 *
 * The same shape as P1-13's biometric scan and P1-16's entitlement scan, negative
 * assertion included — a scan that has quietly stopped matching passes every run,
 * which is the worst failure mode a gate like this can have.
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class MultiVaultScanTest {

    private companion object {
        /**
         * Symbols that carry occupancy. `slotIndex` names which slot matched;
         * `SLOT_COUNT` is how many there are, and arithmetic on it is how a "3 of
         * 8 used" line would be built; `provenSlots` and `opensAnySlot` are the
         * two calls that learn which slots are taken. `panicSlot` and `homeSlot` are the
         * keyring's panic record — `panic-phrase` — and name slots exactly as `slotIndex`
         * does.
         */
        val OCCUPANCY_SYMBOLS = listOf(
            "slotIndex", "SLOT_COUNT", "provenSlots", "opensAnySlot", "panicSlot", "homeSlot",
        )

        /**
         * Files permitted to name them: the lifecycle that seals, the unlock that
         * walks the slots, the array itself, the export gate that re-derives, and
         * §4.1's wrap, which seals the slot index by design so a biometric unlock
         * is indistinguishable from a passphrase one.
         *
         * **`VaultKeyringSession` joined at `vault-keyring`.** It holds the index of
         * the slot its keyring came from, because migrating an older install means
         * re-sealing exactly that slot. It is not a UI file and renders nothing; the
         * test below still fails the build if a `ui/` source names any of these, which
         * is the half of P2-1 that this change did not touch.
         */
        val PERMITTED = setOf(
            "VaultLifecycle.kt",
            "VaultUnlock.kt",
            "SlotArray.kt",
            "VaultSession.kt",
            "VaultKeyringSession.kt",
            // `panic-phrase`. It declares the panic record and stores it opaquely, as it
            // stores the occupancy record; it renders nothing.
            "VaultKeyring.kt",
            "BiometricWrap.kt",
            "ExportAuth.kt",
        )
    }

    private fun sources(): List<File> {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/java/com/app/vault"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { d -> return d.walkTopDown().filter { it.extension == "kt" }.toList() }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the source package from ${File(".").absolutePath}")
    }

    private fun stripped(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    private fun referencesIn(code: String): Int =
        OCCUPANCY_SYMBOLS.sumOf { symbol ->
            Regex("""\b$symbol\b""").findAll(stripped(code)).count()
        }

    @Test
    fun noSurfaceCanLearnWhichSlotOrHowMany() {
        val files = sources()
        assertTrue("scanned no sources", files.size > 5)

        val offenders = files
            .filter { it.name !in PERMITTED }
            .associate { it.name to referencesIn(it.readText()) }
            .filterValues { it > 0 }

        assertEquals(
            "P2-1: no list, no menu, no count. Only $PERMITTED may name $OCCUPANCY_SYMBOLS, " +
                "and a UI file that can read a slot index is one refactor away from rendering " +
                "one. Found: $offenders",
            emptyMap<String, Int>(), offenders
        )
    }

    @Test
    fun theUiPackageIsActuallyPresentAndClean() {
        val ui = sources().filter { it.parentFile.name == "ui" || it.name == "MainActivity.kt" }
        // A scan that finds no files to read passes vacuously.
        assertTrue("found no UI sources to scan", ui.size > 5)
        val dirty = ui.associate { it.name to referencesIn(it.readText()) }.filterValues { it > 0 }
        assertEquals("a screen can see which slot opened", emptyMap<String, Int>(), dirty)
    }

    @Test
    fun theUnlockOutcomeCarriesNoSlot() {
        val lifecycle = sources().single { it.name == "VaultLifecycle.kt" }.readText()
        val outcome = Regex("""class Outcome\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(stripped(lifecycle))
            ?.groupValues?.get(1)
            ?: throw AssertionError("VaultLifecycle.Outcome was renamed; update this scan")

        assertTrue(
            "what create and unlock report must not name a slot: $outcome",
            !outcome.contains("slotIndex")
        )
        // The counts stay: they are the evidence an early exit would break, and a
        // total attempted is not a slot.
        assertTrue("the counts are the evidence and must stay", outcome.contains("slotsAttempted"))
    }

    @Test
    fun theScanActuallyMatches() {
        assertEquals(1, referencesIn("val i = vault.slotIndex"))
        assertEquals(1, referencesIn("repeat(SlotArray.SLOT_COUNT) { }"))
        assertEquals(1, referencesIn("VaultUnlock.provenSlots(list, slots)"))
        assertEquals(0, referencesIn("// the slotIndex never reaches here"))
        assertEquals(0, referencesIn("/* provenSlots stays in the lifecycle */"))
        assertEquals(0, referencesIn("val slotIndexes = 0"))
    }

    @Test
    fun theScanRejectsAViolatingFixture() {
        // The negative proof: a screen that counts vaults is exactly what P2-1
        // forbids, and the shape it would arrive in.
        val fixture = """
            @Composable
            fun VaultGrid(vaults: List<OpenVault>) {
                Text("${'$'}{vaults.size} of ${'$'}{SlotArray.SLOT_COUNT} vaults")
                Text("opened slot ${'$'}{vaults.first().slotIndex}")
            }
        """.trimIndent()
        assertTrue("the fixture must be caught", referencesIn(fixture) >= 2)
    }
}
