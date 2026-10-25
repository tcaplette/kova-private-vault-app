package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Invariant I4, as a build failure — P1-16.
 *
 * "Read and export are never gated, metered or delayed by billing state" is a
 * sentence in `OUTLINE.md`, and a sentence does not survive a later phase. This is
 * the mechanical form: [Entitlement] and [Billing] may be named only in the files
 * that decide whether the app may **add** to a vault, and nowhere near a file that
 * reads from one. A reference from `ExportWorker`, `Unvaulter`, `VaultViewer`,
 * `VaultDataSource`, `ArchiveWorker` or `VaultUnlock` fails the build.
 *
 * The same shape as P1-10's `SelectionContainer` scan and P1-13's biometric scan,
 * including the negative assertion — a scan that has quietly stopped matching passes
 * every run, which is the worst failure mode a gate like this can have.
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class EntitlementScanTest {

    private companion object {
        val GATED_SYMBOLS = listOf("Entitlement", "Billing")

        /**
         * The only files allowed to name them. `MainActivity` holds the gates,
         * `PurchaseScreen` shows the state, and the two engine files are themselves.
         * Nothing else in the app may ask whether the user has paid.
         *
         * **`VaultDrawer` is deliberately not on this list — `settings-separation`.**
         * The drawer draws the entitlement's row, but it is handed a boolean, a count
         * and a flag; it cannot ask whether the user has paid, only render the answer
         * somebody else got. That is the shape every row on it has, and it is why
         * moving the settings into the drawer did not widen this set.
         */
        val PERMITTED = setOf(
            "Entitlement.kt",
            "Billing.kt",
            "MainActivity.kt",
            "PurchaseScreen.kt",
        )

        /**
         * Named explicitly rather than left to the permitted-set check, because
         * these are the files I4 exists to protect. If one of them is renamed, this
         * list should be updated — and if it is not, the permitted-set test still
         * fails on the new name.
         */
        val READ_AND_EXPORT_PATHS = listOf(
            "ExportWorker.kt",
            "ExportAuth.kt",
            "Unvaulter.kt",
            "ArchiveWorker.kt",
            "VaultArchive.kt",
            "VaultDataSource.kt",
            "VaultViewer.kt",
            "VaultUnlock.kt",
            "ExportScreen.kt",
            "BlobStore.kt",
            "Thumbnails.kt",
            // `one-time-purchase`. Opening a received share is never gated — it is
            // usually a paying sender's share, and the recipient is who a gate would
            // refuse. Only keeping is an add, and the screen is handed that answer.
            "ShareOpen.kt",
            "ShareContainer.kt",
            "ShareOpenScreen.kt",
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
        GATED_SYMBOLS.sumOf { symbol ->
            Regex("""\b$symbol\b""").findAll(stripped(code)).count()
        }

    @Test
    fun onlyTheAddPathsKnowAboutEntitlement() {
        val files = sources()
        // A scan that finds no files to read passes vacuously. P0-1 named that the
        // worst failure mode available to a gate; it does not get to have it.
        assertTrue("scanned no sources", files.size > 5)

        val offenders = files
            .filter { it.name !in PERMITTED }
            .associate { it.name to referencesIn(it.readText()) }
            .filterValues { it > 0 }

        assertEquals(
            "I4: read and export are never gated by billing state, so no file outside " +
                "$PERMITTED may name $GATED_SYMBOLS. Found: $offenders",
            emptyMap<String, Int>(), offenders
        )
    }

    @Test
    fun theReadAndExportPathsAreActuallyPresentAndClean() {
        val byName = sources().associateBy { it.name }
        val missing = READ_AND_EXPORT_PATHS.filter { it !in byName }
        assertEquals(
            "these files are what I4 protects; if one was renamed, update this list",
            emptyList<String>(), missing
        )
        val dirty = READ_AND_EXPORT_PATHS
            .associateWith { referencesIn(byName.getValue(it).readText()) }
            .filterValues { it > 0 }
        assertEquals("a read or export path names the entitlement engine", emptyMap<String, Int>(), dirty)
    }

    @Test
    fun theScanActuallyMatches() {
        assertEquals(1, referencesIn("if (Entitlement.importAllowed(ctx)) export()"))
        assertEquals(1, referencesIn("Billing.refresh()"))
        assertEquals(2, referencesIn("Entitlement.trialDay(c); Billing.owned"))
        // Comments explain the rule; they must not be mistaken for breaking it.
        assertEquals(0, referencesIn("// Entitlement is not consulted here"))
        assertEquals(0, referencesIn("/* Billing never reaches this file */"))
        // Near-misses are not the real thing.
        assertEquals(0, referencesIn("EntitlementState()"))
        assertEquals(0, referencesIn("BillingHelper()"))
    }

    @Test
    fun theScanRejectsAViolatingFixture() {
        // The negative proof: an export path that consults entitlement is exactly
        // what this gate exists to catch, and it must not pass.
        val fixture = """
            class ExportWorker {
                fun doWork(): Result {
                    if (!Entitlement.importAllowed(context)) return Result.failure()
                    return export()
                }
            }
        """.trimIndent()
        assertTrue("the fixture must be caught", referencesIn(fixture) > 0)
    }
}
