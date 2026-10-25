package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A query never leaves the session — P2-15.
 *
 * `AlbumScanTest`'s rule against a sharper case again. An album name says what a
 * set of items *is*; a **query** says what the user was looking for at a moment,
 * which is the same class of thing and is often narrower — a person, a document, a
 * date. It has no reason to exist for longer than the search is open, so this
 * fails the build if it acquires one: WorkManager's database, a preference, or a
 * log line.
 *
 * **Scoped to the search path deliberately, not app-wide.** `Thumbnails` and
 * `Unvaulter` already interpolate `item.filename` into failure lines; tightening
 * those is a separate change, and a blanket ban here would fail the build on code
 * P2-15 does not touch (P2-14 recorded the same boundary).
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class SearchScanTest {

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

    /**
     * ***Strings out before comments***, the ordering `CoverScanTest` records and for
     * the reason it records: this app's SAF any-type wildcard is a string literal
     * whose middle two characters close a block comment, and to a naive stripper that
     * is where a comment begins. In `MainActivity` it opened one that ran for hundreds
     * of lines and swallowed the search call site whole — so the checks below were
     * reading a truncated file and finding nothing, which is the failure mode a scan
     * for something's *absence* can always have.
     *
     * [theStripperLeavesRealCodeBehind] is what stops that happening again quietly.
     */
    private fun stripped(text: String): String = text
        .replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
        .replace(Regex("\"(\\\\.|[^\"\\\\])*\""), "\"\"")
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\n]*"), "")

    /**
     * The scans below all look for something's **absence**, so a stripper that
     * returned nothing would make every one of them pass while measuring nothing.
     * `MainActivity` is the file where that actually happened.
     */
    @Test
    fun theStripperLeavesRealCodeBehind() {
        val main = sources().first { it.name == "MainActivity.kt" }
        val code = stripped(main.readText())
        assertTrue("MainActivity stripped to nothing", code.length > 20_000)
        assertTrue(
            "the stripper swallowed the search call site",
            code.contains("VaultSearch.filter"),
        )
    }

    /**
     * ***`VaultSearch.filter` is never called with a trailing lambda.***
     *
     * `notes-as-documents` added `textOf` **after** `folderName`, and Kotlin binds a
     * trailing lambda to the last parameter. Every existing call site that passed the
     * folder lookup that way silently started answering a different question: the
     * query kept matching filenames and tags, so nothing looked broken, and folder
     * names stopped matching. It compiled, it shipped, and only `SearchTest` caught
     * it — a search that quietly answers less is the worst shape a defect can take,
     * because the user reads it as "there is nothing there".
     *
     * So the argument is named at every call site in `src/main`, and this fails the
     * build when it is not.
     *
     * **`src/main` only, deliberately.** This task's inputs are the main and unit-test
     * sources, so a scan of `androidTest` would go stale the moment only that source
     * set changed — an up-to-date check that silently stops looking is worse than one
     * that never looked. The instrumented tests catch their own version of this by
     * failing outright, which is how the shipped defect was found.
     */
    @Test
    fun noCallerPassesTheFolderLookupAsATrailingLambda() {
        val offenders = sources().filter { f ->
            val code = stripped(f.readText())
            // `filter(...)  {` — a call whose closing paren is followed by a lambda.
            Regex("""VaultSearch\.filter\([^)]*\)\s*\{""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(code)
        }.map { it.name }
        assertEquals(
            "VaultSearch.filter is called with a trailing lambda in $offenders — name the " +
                "argument (folderName = ... / textOf = ...), because the trailing position " +
                "is textOf and the folder stops matching without a word of warning",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun noQueryReachesWorkManagerInputData() {
        val offenders = sources().filter { f ->
            val code = stripped(f.readText())
            Regex("""workDataOf\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(code)
                .any { Regex("""\bquery\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.groupValues[1]) }
        }.map { it.name }

        assertEquals(
            "a query reached WorkManager input data, where run-as recovers it: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun noDataBuilderCarriesAQuery() {
        val offenders = sources().filter { f ->
            val code = stripped(f.readText())
            code.contains("Data.Builder") &&
                Regex("""\bquery\b""", RegexOption.IGNORE_CASE).containsMatchIn(code)
        }.map { it.name }

        assertEquals(
            "a query may not reach a Data.Builder either: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * No preference holds one.
     *
     * `GridPreference` is the app's only `SharedPreferences` surface and it stores
     * a column count. A query stored beside it would outlive the lock, which is the
     * one thing the search state must not do.
     */
    @Test
    fun noPreferenceHoldsAQuery() {
        val offenders = sources().filter { f ->
            val code = stripped(f.readText())
            (code.contains("SharedPreferences") || code.contains("getPreferences")) &&
                Regex("""\bquery\b""", RegexOption.IGNORE_CASE).containsMatchIn(code)
        }.map { it.name }

        assertEquals(
            "a query may not be written to a preference: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The search path logs nothing at all — not the query, and not a count of
     * matches, which is itself a fact about the user's content.
     */
    @Test
    fun theSearchPathDoesNotLog() {
        val code = stripped(File(sourceDir(), "VaultSearch.kt").readText())
        assertFalse(
            "VaultSearch must not log: a query, and even a match count, describe the user's content",
            code.contains("Log."),
        )
        assertFalse("and must not import the logger either", code.contains("import android.util.Log"))
    }

    /**
     * Nowhere else interpolates a query into a log line.
     *
     * **The identifier, not the English word.** The first version of this matched
     * "query" anywhere in a log call and failed on `Billing.kt`'s "purchase query
     * failed" — a sentence about a Play Store call, with nothing of the user's in
     * it. What leaks a search term is an *interpolation* of a value named `query`,
     * which is what this looks for.
     */
    @Test
    fun noLogLineCarriesAQuery() {
        val logCall = Regex("""Log\.[a-z]\((?:[^()]|\([^()]*\))*\)""", RegexOption.DOT_MATCHES_ALL)
        val interpolated = Regex("""\$\{?query\b""", RegexOption.IGNORE_CASE)
        val offenders = mutableListOf<String>()
        sources().forEach { f ->
            logCall.findAll(stripped(f.readText())).forEach { m ->
                if (interpolated.containsMatchIn(m.value)) offenders += "${f.name}: ${m.value}"
            }
        }
        assertEquals(
            "a query reached logcat, which a bug report captures: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /** The matcher is where the search lives; nothing else may hold the rows back. */
    @Test
    fun theMatcherIsTheOnlyPlaceSearchIsDecided() {
        val search = File(sourceDir(), "VaultSearch.kt")
        assertTrue("VaultSearch.kt must exist", search.isFile)
        val code = stripped(search.readText())
        assertTrue(
            "matching must be case-insensitive without a locale — see the Turkish ı",
            code.contains("ignoreCase = true"),
        )
        assertFalse(
            "lowercase() folds in the default locale and breaks matching on a Turkish device",
            code.contains("lowercase()"),
        )
    }
}
