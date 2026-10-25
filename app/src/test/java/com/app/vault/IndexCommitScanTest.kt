package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No file may load the index and save it without the guard — P2-9.
 *
 * The bug this change fixes was not exotic. It was three ordinary lines —
 * `load`, mutate, `save` — in a worker that happened to run seven at a time. The
 * pattern is invisible at a review because each line is correct; only the three
 * together, on two threads, are wrong. So it is checked mechanically.
 *
 * Five files are exempt, and each has no concurrent writer by construction:
 * `VaultLifecycle` writes a vault's first index before anything else can reach it,
 * `MetadataDb` is where `commit` itself lives, `MediaImport` takes the index as
 * a parameter and has no product caller, and `VaultKeyringSession` touches an index
 * only at the two moments a vault has no other writer — writing an empty one for a
 * vault that does not exist yet, and reading a removed vault's one to delete the
 * blobs it named, after its keyring entry is already gone and nothing can reach it.
 * `CaptureStore` does the same two things for a capture store: writes the empty index of one
 * that does not exist yet, and reads a destroyed vault's to delete the blobs it named.
 */
class IndexCommitScanTest {

    private val exempt = setOf(
        "MetadataDb.kt", "VaultLifecycle.kt", "MediaImport.kt", "VaultKeyringSession.kt",
        "CaptureStore.kt",
    )

    private fun sources(): List<File> {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/java/com/app/vault"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { d -> return d.walkTopDown().filter { it.extension == "kt" }.toList() }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the source package")
    }

    private fun stripped(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    @Test
    fun noUnguardedLoadAndSaveOutsideTheExemptFiles() {
        val offenders = sources()
            .filter { it.name !in exempt }
            .filter {
                val src = stripped(it.readText())
                src.contains("MetadataDb.load(") && Regex("""\bsave\(""").containsMatchIn(src)
            }
            .map { it.name }

        assertEquals(
            "these load the index and save it outside MetadataDb.commit — the P2-9 bug. " +
                "Found in: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /** The guard exists and is reentrant, which `TamperLog` nested inside a commit needs. */
    @Test
    fun theGuardIsReentrantByConstruction() {
        val src = stripped(sources().single { it.name == "MetadataDb.kt" }.readText())
        assertTrue(
            "commit must exist",
            src.contains("fun <T> commit("),
        )
        assertTrue(
            "a plain monitor is what makes it reentrant on the same thread",
            src.contains("synchronized(commitLock)"),
        )
        assertTrue(
            "a nested commit must reuse the open index rather than reloading it",
            src.contains("open.get()"),
        )
    }

    /**
     * `TamperLog`'s self-loading append must not be `@Synchronized`.
     *
     * It would take TamperLog's monitor and then the commit lock, while
     * `ImportWorker` takes the commit lock and then TamperLog's monitor. ABBA, and
     * it hangs both threads.
     */
    @Test
    fun tamperLogDoesNotInvertTheLockOrder() {
        val src = stripped(sources().single { it.name == "TamperLog.kt" }.readText())
        val selfLoading = src.substringAfter("fun append(\n        store: BlobStore,\n        event: Int")
        assertTrue(
            "the self-loading append must reach TamperLog's monitor through the commit lock",
            selfLoading.contains("MetadataDb.commit("),
        )
        val beforeIt = src.substringBefore("fun append(\n        store: BlobStore,\n        event: Int")
        assertTrue(
            "@Synchronized on the self-loading append inverts the lock order into a deadlock",
            !beforeIt.trimEnd().endsWith("@Synchronized"),
        )
    }
}
