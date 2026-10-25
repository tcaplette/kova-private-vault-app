package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A folder never reaches disk outside the index — P2-13, `folder-system`.
 *
 * This is `SelectionScanTest`'s rule with a sharper case. An `itemId` in
 * `no_backup/androidx.work.workdb` is a blob ID: provably real content, which is
 * the ambiguity `FORMAT.md` §6's chaff exists to create. A **folder name** is a
 * sentence the user wrote about what the items *are* — "Clinic", a person's name —
 * and a reader of that database with `adb shell run-as` learns it without opening
 * a single blob.
 *
 * `folder-system` extends the rule to the folder's **identity**: sixteen bytes beside
 * a count still says this vault has folders and how full they are, which is a fact
 * about its shape rather than about one item.
 *
 * The same argument covers the log: `Log.i` goes to logcat, which is readable on a
 * developer-enabled device and captured by bug reports, so the folder code reports
 * counts and names nothing.
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class FolderScanTest {

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

    private fun sources(): List<File> =
        sourceDir().walkTopDown().filter { it.extension == "kt" }.toList()

    private fun stripped(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    @Test
    fun theFolderIsCarriedByWorkJobsAndNotByWorkManager() {
        val jobs = stripped(File(sourceDir(), "WorkJobs.kt").readText())
        assertTrue(
            "WorkJobs.ImportJob must carry the folder, so nothing else has to",
            jobs.contains("folderId"),
        )

        val offenders = sources().filter { f ->
            val code = stripped(f.readText())
            Regex("""workDataOf\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(code)
                .any { call ->
                    listOf("album", "folder").any {
                        call.groupValues[1].contains(it, ignoreCase = true)
                    }
                }
        }.map { it.name }

        assertEquals(
            "a folder reached WorkManager input data, where run-as recovers it: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun noDataBuilderCarriesAFolder() {
        val offenders = sources().filter { f ->
            val code = stripped(f.readText())
            code.contains("Data.Builder") &&
                listOf("album", "folder").any { code.contains(it, ignoreCase = true) }
        }.map { it.name }

        assertEquals(
            "a folder was put into a Data.Builder: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * No log line interpolates a name.
     *
     * Checked as "a `Log.` call whose text mentions a folder variable", which is
     * what a lazily written line would look like: `Log.i(TAG, "moved to $folder")`.
     */
    @Test
    fun noLogLineNamesAFolder() {
        val offenders = mutableListOf<String>()
        sources().forEach { f ->
            val code = stripped(f.readText())
            Regex("""Log\.[a-z]\([^)]*\)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(code)
                .forEach { call ->
                    val text = call.value
                    val names = Regex("""\$\{?(album|folder)""", RegexOption.IGNORE_CASE)
                    if (names.containsMatchIn(text)) {
                        offenders += "${f.name}: ${text.take(80)}"
                    }
                }
        }

        assertEquals(
            "a log line interpolates a folder; logcat is readable on a developer device",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The folder table is in the index and nowhere else.
     *
     * ***This assertion is the inverse of the one it replaces.*** P2-13 asserted that
     * **no** folder list was serialised, because an album was derived from the items;
     * `folder-system` requires exactly such a list, because a folder that exists with
     * nothing in it cannot be derived from anything. What survives unchanged is where
     * it may live: the encrypted index, and no other file on the device.
     */
    @Test
    fun theIndexIsTheOnlyPlaceFoldersAreStored() {
        val db = stripped(File(sourceDir(), "MetadataDb.kt").readText())
        assertTrue(
            "the index must carry the folder table",
            Regex("""folders\s*:\s*MutableList<VaultFolder>""").containsMatchIn(db),
        )
        assertTrue(
            "the item record must carry its folder reference",
            db.contains("val folderId: ByteArray?"),
        )

        // `MemoPending` is not an exception to this: the folder a memo was started in is a field
        // of its pending record, which is sealed to the vault's capture key before it reaches the
        // preference file — ciphertext, like the index. The scan asserts the seal instead.
        val pending = stripped(File(sourceDir(), "MemoPending.kt").readText())
        assertTrue(
            "a memo's folder must reach preferences only inside the sealed record",
            pending.contains("Capture.seal(publicKey, plain)") && pending.contains("MemoRecord.encode(record)"),
        )
        val prefs = sources().filter { f ->
            val code = stripped(f.readText())
            f.name != "MemoPending.kt" &&
                code.contains("SharedPreferences") &&
                Regex("""folder""", RegexOption.IGNORE_CASE).containsMatchIn(code)
        }.map { it.name }

        assertEquals(
            "a folder reached a preference file, which is plaintext on disk: $prefs",
            emptyList<String>(),
            prefs,
        )
    }
}
