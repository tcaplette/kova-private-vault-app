package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A selection never reaches disk — P2-11.
 *
 * An `itemId` is the item's **first blob ID in hex** (`MetadataDb.VaultItem`).
 * WorkManager writes a request's input `Data` and its tags to
 * `no_backup/androidx.work.workdb`, and Gate 1 read exactly that back with
 * `adb shell run-as`. A list of selected item IDs put there is one row per
 * selected item saying "this blob is real content", which is precisely the
 * ambiguity `FORMAT.md` §6's chaff exists to create — and worse than the single
 * index ID that `WorkJobs` was already written to keep out.
 *
 * So the rule is structural: the scheduling path builds no `Data` beyond the
 * progress keys it reads back, and the selection travels in `WorkJobs`.
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class SelectionScanTest {

    private fun sourceDir(): File {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/java/com/app/vault"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the source package from ${File(".").absolutePath}")
    }

    private fun read(name: String) = File(sourceDir(), name).readText()

    private fun stripped(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    /** Every `workDataOf(...)` call in a file, with its argument text. */
    private fun workDataCalls(code: String): List<String> =
        Regex("""workDataOf\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
            .findAll(stripped(code))
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun theSelectionIsCarriedByWorkJobsAndNotByWorkManager() {
        val jobs = stripped(read("WorkJobs.kt"))
        assertTrue(
            "WorkJobs.ExportJob must carry the selection, so nothing else has to",
            jobs.contains("selection"),
        )

        val activity = stripped(read("MainActivity.kt"))
        assertTrue(
            "the export must be enqueued with the selection registered in WorkJobs",
            activity.contains("WorkJobs.ExportJob("),
        )
    }

    @Test
    fun noSchedulingPathPutsAnItemIdIntoWorkManagerData() {
        val offenders = listOf("MainActivity.kt", "ExportWorker.kt", "ImportWorker.kt", "ArchiveWorker.kt")
            .flatMap { name -> workDataCalls(read(name)).map { name to it } }
            .filter { (_, args) ->
                // The progress keys are numbers and flags. Anything naming an
                // item, a selection or a blob is an identity going to disk.
                listOf("itemId", "selection", "blobId", "blobIds", "filename")
                    .any { args.contains(it) }
            }
            .map { (name, args) -> "$name -> workDataOf($args)" }

        assertEquals(
            "an itemId is a blob ID in hex, and WorkManager writes input Data to disk. " +
                "The selection belongs in WorkJobs, in memory. Found: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun theScanActuallyMatches() {
        // Without this the scan above passes just as happily once its pattern
        // stops matching anything at all.
        val sample = """val d = workDataOf("selection" to ids.joinToString())"""
        assertTrue(
            "the scan no longer detects a selection reaching WorkManager",
            workDataCalls(sample).any { it.contains("selection") },
        )
    }

    /**
     * The count in the selection bar is a number that changes on every tap, and
     * `DESIGN.md` §7.4 puts it in the monospace face so the title does not reflow
     * mid-gesture. Asserted here because nothing on screen can be measured for it
     * — `FLAG_SECURE` returns black to every screenshot.
     */
    @Test
    fun theSelectionCountUsesTheTabularFace() {
        val grid = stripped(read("ui/VaultGrid.kt"))
        assertTrue(
            "the selection count must use VaultMono, per DESIGN.md §7.4 and §5.1",
            grid.contains("VaultMono.titleLarge"),
        )
    }
}
