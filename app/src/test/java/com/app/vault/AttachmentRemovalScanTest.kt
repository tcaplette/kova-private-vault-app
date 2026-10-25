package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The note attachment feature is gone, and stays gone — `remove-note-attachments`.
 *
 * ***A removal cannot be guarded by the tests that covered the feature, because those
 * tests were deleted with it.*** Every remaining test passes whether or not a path was
 * left behind — the paths were only ever reached from a control that no longer exists.
 * So the guard is a scan for absence, which is the one check that does not have that
 * hole.
 *
 * Written in `ScreenChromeTest`'s shape and for its reason: reading the source is how
 * this codebase asserts "no such thing exists anywhere", and `FLAG_SECURE` puts a
 * screenshot out of reach anyway.
 */
class AttachmentRemovalScanTest {

    private fun sources(rel: String): List<File> {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { d -> return d.walkTopDown().filter { it.extension == "kt" }.toList() }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $rel from ${File(".").absolutePath}")
    }

    private fun mainSources(): List<File> = sources("src/main/java/com/app/vault")

    /** Comments and string payloads removed — "is this call present", not "is it mentioned". */
    private fun code(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("\"\"\"", i) -> {
                    val close = text.indexOf("\"\"\"", i + 3)
                    i = if (close < 0) text.length else close + 3
                }
                text[i] == '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '"') {
                        if (text[j] == '\\') j++
                        j++
                    }
                    i = minOf(j + 1, text.length)
                }
                text[i] == '\'' -> {
                    var j = i + 1
                    while (j < text.length && text[j] != '\'') {
                        if (text[j] == '\\') j++
                        j++
                    }
                    i = minOf(j + 1, text.length)
                }
                text.startsWith("/*", i) -> {
                    val close = text.indexOf("*/", i + 2)
                    i = if (close < 0) text.length else close + 2
                }
                text.startsWith("//", i) -> {
                    val nl = text.indexOf('\n', i)
                    out.append('\n')
                    i = if (nl < 0) text.length else nl + 1
                }
                else -> {
                    out.append(text[i]); i++
                }
            }
        }
        return out.toString()
    }

    /**
     * The parser's attachment block, the reference builder and the reference reader are
     * gone from every file.
     */
    @Test
    fun noAttachmentSyntaxSurvives() {
        listOf("Block.Attachment", "attachmentsIn", "referenceTo").forEach { gone ->
            val offenders = mainSources()
                .filter { code(it.readText()).contains(gone) }
                .map { it.name }
            assertEquals(
                "`$gone` is part of the removed note-attachment feature. Found in: $offenders",
                emptyList<String>(),
                offenders,
            )
        }
    }

    /**
     * ***`ownerNoteId` survives in exactly one file, and only as storage.***
     *
     * `MetadataDb` still parses it, carries it through the withers and writes it back, so
     * an index round trip does not lose it — the format retains the field deliberately
     * rather than bumping a version to say nothing new. Every *behavioural* read is gone:
     * the grid filter, the trash, search, deletion, thumbnails, the import pipeline and
     * export each had one, and each was a branch that existed only for this feature.
     */
    @Test
    fun theOwnerFieldIsStorageAndNothingElse() {
        val offenders = mainSources()
            .filter { it.name != "MetadataDb.kt" }
            .filter { code(it.readText()).contains("ownerNoteId") }
            .map { it.name }
        assertEquals(
            "`ownerNoteId` is retained in the format and read by nothing. A use outside " +
                "MetadataDb is a path the removal left behind. Found in: $offenders",
            emptyList<String>(),
            offenders,
        )

        val db = code(mainSources().single { it.name == "MetadataDb.kt" }.readText())
        assertTrue(
            "the field must still be parsed, or an existing index loses it",
            db.contains("ownerNoteId ="),
        )
        assertTrue(
            "the field must still be written, or a commit loses it",
            db.contains("item.ownerNoteId"),
        )
        assertTrue(
            "the wither that *set* an owner is what made an attachment; it must be gone",
            !db.contains("fun withOwner"),
        )
    }

    /**
     * The index version did not move.
     *
     * Retaining the field is the whole reason there is no migration. A bump here would
     * mean every existing vault needs a pass to express the absence of a value nothing
     * reads, which is work with no outcome — and it would silently invalidate that claim.
     */
    @Test
    fun theIndexVersionDidNotMoveForThisRemoval() {
        val db = code(mainSources().single { it.name == "MetadataDb.kt" }.readText())
        // **The claim is that *this removal* created no version of its own**, not that
        // the format never moves again. Pinning the current `VERSION` asserted the
        // second by accident, and the next feature to add a trailing field broke it —
        // `intruder-capture-record` did, at 13. What holds the claim is that the
        // version current when the owner field was retained is still 12: the removal
        // rode on it rather than introducing one.
        val noteBlob = Regex("""NOTE_BLOB_VERSION\s*=\s*(\d+)""").find(db)?.groupValues?.get(1)
        assertEquals(
            "the format retains the owner field, so this removal must not bump the version",
            "12",
            noteBlob,
        )
    }

    /** No screen offers an attach control any more. */
    @Test
    fun nothingOffersToAttachAnImage() {
        val offenders = mainSources()
            .filter { it.readText().contains("Attach an image") || code(it.readText()).contains("onAttach") }
            .map { it.name }
        assertEquals(
            "the attach control is removed. Found in: $offenders",
            emptyList<String>(),
            offenders,
        )
    }
}
