package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The assigned-date path is the decoy flow's and nobody else's — P2-2, as a build
 * failure.
 *
 * `media-import` says an absent capture date is recorded as absent rather than
 * substituted, and that rule still holds for every import the user drives. P2-2
 * carves out one exception, and an exception that anything else can reach is not an
 * exception — it is the rule quietly gone. So the symbols that carry an assigned
 * date may appear only in the files that make one, carry one, or write one.
 *
 * The same shape as P1-16's entitlement scan and P2-1's occupancy scan, negative
 * assertion included: a scan that has stopped matching passes every run.
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class DecoyScanTest {

    private companion object {
        val ASSIGNMENT_SYMBOLS = listOf("assignedCreatedAt", "assignedImportedAt")

        /**
         * `Decoy` computes them, `WorkJobs` carries them in memory, `ImportWorker`
         * applies them as a fallback behind the source's own date, `DecoyScreen` is
         * the surface, and `MainActivity` is where the flow is assembled. Nothing
         * else may name them — least of all the ordinary picker paths.
         */
        val PERMITTED = setOf(
            "Decoy.kt",
            "WorkJobs.kt",
            "ImportWorker.kt",
            "DecoyScreen.kt",
            "MainActivity.kt",
        )

        /** The paths the ordinary import runs through. Named, so a rename fails here. */
        val ORDINARY_IMPORT_PATHS = listOf(
            "MediaImport.kt",
            "SourceMetadata.kt",
            "MetadataDb.kt",
            "Thumbnails.kt",
            "VaultGrid.kt",
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
        ASSIGNMENT_SYMBOLS.sumOf { symbol ->
            Regex("""\b$symbol\b""").findAll(stripped(code)).count()
        }

    @Test
    fun onlyTheDecoyFlowCanAssignADate() {
        val files = sources()
        assertTrue("scanned no sources", files.size > 5)

        val offenders = files
            .filter { it.name !in PERMITTED }
            .associate { it.name to referencesIn(it.readSource()) }
            .filterValues { it > 0 }

        assertEquals(
            "media-import: an absent date is recorded as absent, not substituted. Only " +
                "$PERMITTED may name $ASSIGNMENT_SYMBOLS. Found: $offenders",
            emptyMap<String, Int>(), offenders
        )
    }

    @Test
    fun theOrdinaryImportPathsArePresentAndClean() {
        val byName = sources().associateBy { it.name }
        val missing = ORDINARY_IMPORT_PATHS.filter { it !in byName }
        assertEquals("if one of these was renamed, update this list", emptyList<String>(), missing)

        val dirty = ORDINARY_IMPORT_PATHS
            .associateWith { referencesIn(byName.getValue(it).readSource()) }
            .filterValues { it > 0 }
        assertEquals("an ordinary import path can assign a date", emptyMap<String, Int>(), dirty)
    }

    @Test
    fun theSourcesOwnDateAlwaysWins() {
        // The guarantee is structural, not a convention the caller keeps: the
        // pipeline reads `source.createdAt ?: assignedCreatedAt`, so an assigned
        // value cannot replace a real one from any caller.
        val pipeline = stripped(sources().single { it.name == "ImportWorker.kt" }.readSource())
        assertTrue(
            "the pipeline must prefer the source's own capture date",
            pipeline.contains("source.createdAt ?: assignedCreatedAt")
        )
    }

    /**
     * `decoy-flow-overhaul`. The flow proposes no name, and the scan is what keeps it
     * that way.
     *
     * A name the app supplies would be identical on every install that ran the flow,
     * which is the marker `decoy-tooling` exists to avoid — and it is exactly what the
     * old flow shipped by omitting `firstVaultName` and inheriting `MIGRATED_NAME`. A
     * default is easy to reintroduce by reflex (an `= "My decoy"`, a `placeholder =`),
     * so the refusal is asserted on the source rather than trusted to review.
     */
    @Test
    fun theDecoyFlowProposesNoName() {
        val screen = stripped(sources().single { it.name == "DecoyScreen.kt" }.readSource())

        assertTrue(
            "the name field must exist to be checked",
            screen.contains("label = { Text(\"Name\") }"),
        )
        assertTrue(
            "a name field with a placeholder is a name field with a default",
            !screen.contains("placeholder"),
        )
        assertTrue(
            "the decoy's name must never fall back to the migration placeholder",
            !screen.contains("MIGRATED_NAME"),
        )

        // The activity passes what was typed, trimmed, and nothing else.
        val activity = stripped(sources().single { it.name == "MainActivity.kt" }.readSource())
        assertTrue(
            "the name reaching creation is the user's, trimmed",
            activity.contains("firstVaultName = chosenName"),
        )
        assertTrue(
            "and chosenName is the field, not a substitute for an empty one",
            activity.contains("val chosenName = decoyName.trim()"),
        )
        assertTrue(
            "no elvis may quietly supply a name the user did not choose",
            !Regex("""decoyName[^\n]*\?:""").containsMatchIn(activity),
        )
    }

    /**
     * What the return wiring must contain, as a list of what is missing.
     *
     * A function over a string rather than assertions over one file, so the scan can
     * be run against a fixture that violates it — see
     * [theReturnScanRejectsAFlowThatDoesNotComeBack]. A scan nobody has watched fail
     * is a scan that passes forever, which is the exact shape of the failure this
     * whole rule exists because of.
     */
    private fun returnWiringGaps(code: String): List<String> = buildList {
        if (!code.contains("private fun returnFromDecoy()")) add("no way back at all")
        if (!code.contains("lifecycleOps.reopen(")) add("does not use the shared round trip")
        if (!code.contains("onContinue = ::returnFromDecoy")) add("nothing triggers the return")
        if (!code.contains("ExportAuth.confirms(slotsFile, auth.copyOf())")) {
            add("the owner's passphrase is consumed by the check instead of kept")
        }
        if (!code.contains("decoyReturnPosition = VaultKeyringSession.openVaultPosition()")) {
            add("the vault they were in is not recorded before the switch")
        }
        if (Regex("""configured\s*=\s*vaultEntries""").containsMatchIn(code)) {
            add("the first-run step decides from a vault count again")
        }
    }

    /**
     * The flow comes back, and the wiring that makes it come back is asserted here.
     *
     * `DecoyReturnTest` proves `VaultLifecycle.reopen` works. It cannot prove the
     * activity calls it — delete the call site and that suite still passes green,
     * which is the same failure that let the original bug through: a test verifying a
     * mechanism nobody was using. So the call site is checked too.
     *
     * The bug: creating a decoy opens its keyring and closes the one it was called
     * from, and nothing brought the user back. First run then carried on with the
     * decoy open, and its remaining steps enrol against the open vault — the backup
     * would have mirrored the decoy and the panic credential would have been set
     * on it.
     */
    @Test
    fun theDecoyFlowComesBackToTheVaultItStartedFrom() {
        val activity = stripped(sources().single { it.name == "MainActivity.kt" }.readSource())
        assertEquals(
            "the decoy flow must return to the vault it started from",
            emptyList<String>(),
            returnWiringGaps(activity),
        )
    }

    /** The negative half: the scan must fail on the code that shipped the bug. */
    @Test
    fun theReturnScanRejectsAFlowThatDoesNotComeBack() {
        // The old tail, as it was: the session is left in the decoy and the first-run
        // step decides from the open keyring's vault count.
        val asItWas = """
            private fun createDecoy(passphrase: ByteArray, preserve: List<ByteArray>) {
                val provesOpen = preserve.any { ExportAuth.confirms(slotsFile, it.copyOf()) }
                runOnUiThread {
                    decoying = false
                    unlocked = true
                    open = null
                    recoveryKit = VaultKeyringSession.withKey { mnemonic.encode(it) }
                }
            }
            Onboarding.Step.Decoy -> DecoyStepScreen(configured = vaultEntries.size > 1)
        """.trimIndent()

        val gaps = returnWiringGaps(asItWas)
        assertTrue("the scan must reject the code that shipped the bug", gaps.isNotEmpty())
        assertTrue("and say the flow never comes back", gaps.any { it.contains("no way back") })
        assertTrue(
            "and that the step went back to counting vaults",
            gaps.any { it.contains("vault count") },
        )
    }

    @Test
    fun theScanActuallyMatches() {
        assertEquals(1, referencesIn("val d = assignedCreatedAt(now, rng)"))
        assertEquals(2, referencesIn("job.assignedCreatedAt; job.assignedImportedAt"))
        assertEquals(0, referencesIn("// assignedCreatedAt is never read here"))
        assertEquals(0, referencesIn("/* assignedImportedAt belongs to the decoy flow */"))
        assertEquals(0, referencesIn("val assignedCreatedAtOnce = 1"))
    }

    @Test
    fun theScanRejectsAViolatingFixture() {
        val fixture = """
            class MediaImport {
                fun import(uri: Uri) = VaultItem(
                    createdAt = assignedCreatedAt(now, rng),
                    importedAt = assignedImportedAt(now, origin, window, rng),
                )
            }
        """.trimIndent()
        assertTrue("the fixture must be caught", referencesIn(fixture) >= 2)
    }
}
