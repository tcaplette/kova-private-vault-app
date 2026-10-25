package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * What a query matches — P2-15.
 *
 * The matcher is pure, so the whole of the outline's first Verify clause — "a
 * query matches on each of the three fields" — is answerable here without a
 * device. `SearchTest` covers the second clause, which is about rows leaving the
 * grid.
 */
class SearchMatchTest {

    /**
     * `folder-system`: the folder is no longer a field on the row — it is a
     * reference, and the *name* is looked up by the caller and handed to the
     * matcher. The helper keeps a name beside the item so these tests read as they
     * did, and [matches] passes it where the album used to be read from.
     */
    private class Row(val item: VaultItem, val folder: String?)

    private fun item(
        filename: String,
        folder: String? = null,
        tags: List<String> = emptyList(),
    ) = Row(
        VaultItem(
            blobIds = listOf(ByteArray(16) { filename.first().code.toByte() }),
            plaintextLength = 1L,
            thumb = null,
            filename = filename,
            album = null,
            createdAt = null,
            importedAt = 1L,
            tags = tags,
        ),
        folder,
    )

    /** A note row — P2-21. No blobs, a body, and its own identity. */
    private fun note(filename: String, body: String) = Row(
        VaultItem(
            blobIds = emptyList(),
            plaintextLength = body.length.toLong(),
            thumb = null,
            filename = filename,
            album = null,
            createdAt = null,
            importedAt = 1L,
            tags = emptyList(),
            body = body,
            noteId = ByteArray(16) { filename.first().code.toByte() },
        ),
        null,
    )

    private fun matches(row: Row, query: String) =
        VaultSearch.matches(row.item, VaultSearch.terms(query), row.folder)

    // --- the three fields ----------------------------------------------------

    @Test
    fun aQueryMatchesTheFilename() {
        assertTrue(matches(item("IMG_4471.jpg"), "4471"))
        assertTrue(matches(item("IMG_4471.jpg"), "img"))
        assertFalse(matches(item("IMG_4471.jpg"), "9999"))
    }

    @Test
    fun aQueryMatchesTheFolder() {
        assertTrue(matches(item("a.jpg", folder = "Passports"), "passport"))
        assertFalse(matches(item("a.jpg", folder = null), "passport"))
    }

    @Test
    fun aQueryMatchesATag() {
        assertTrue(matches(item("a.jpg", tags = listOf("receipt", "2019")), "receipt"))
        assertTrue(matches(item("a.jpg", tags = listOf("receipt", "2019")), "2019"))
        assertFalse(matches(item("a.jpg", tags = listOf("receipt")), "invoice"))
    }

    // --- terms ---------------------------------------------------------------

    @Test
    fun everyTermMustMatch() {
        val it = item("IMG_2019.jpg", folder = "Passports")
        assertTrue("both terms match", matches(it, "passport 2019"))
        assertFalse("one term matching is not enough", matches(it, "passport 1998"))
    }

    /** The whole reason terms are matched separately: they may land in different fields. */
    @Test
    fun termsMayMatchDifferentFields() {
        val it = item("IMG_2019.jpg", folder = "Passports", tags = listOf("scanned"))
        assertTrue(matches(it, "2019 passports scanned"))
        assertTrue(matches(it, "scanned img"))
    }

    @Test
    fun whitespaceBetweenTermsIsCollapsed() {
        val it = item("IMG_2019.jpg", folder = "Passports")
        assertEquals(listOf("passport", "2019"), VaultSearch.terms("  passport   2019  "))
        assertTrue(matches(it, "  passport   2019  "))
    }

    @Test
    fun aBlankQueryMatchesEverything() {
        val items = listOf(item("a.jpg"), item("b.png")).map { it.item }
        assertEquals(items, VaultSearch.filter(items, ""))
        assertEquals(items, VaultSearch.filter(items, "   "))
        assertEquals(items, VaultSearch.filter(items, "\t\n"))
        assertTrue("an empty query is not a question", VaultSearch.terms("").isEmpty())
    }

    @Test
    fun aTermMatchingNothingFiltersEverythingOut() {
        val items = listOf(item("a.jpg"), item("b.png", folder = "Trip")).map { it.item }
        assertEquals(emptyList<VaultItem>(), VaultSearch.filter(items, "nothingmatchesthis"))
    }

    @Test
    fun theOrderOfResultsIsTheOrderOfTheInput() {
        val a = item("one-trip.jpg").item
        val b = item("two-trip.jpg").item
        val c = item("three.jpg").item
        assertEquals(listOf(a, b), VaultSearch.filter(listOf(a, b, c), "trip"))
        assertEquals(listOf(b, a), VaultSearch.filter(listOf(b, a, c), "trip"))
    }

    // --- case, and the locale that breaks it ---------------------------------

    @Test
    fun caseIsIgnoredInBothDirections() {
        assertTrue(matches(item("PASSPORT.jpg"), "passport"))
        assertTrue(matches(item("passport.jpg"), "PASSPORT"))
        assertTrue(matches(item("a.jpg", folder = "Trip"), "tRiP"))
    }

    /**
     * **The test that fails if `lowercase()` is ever substituted for `ignoreCase`.**
     *
     * On a Turkish device `"IMG".lowercase()` is `"ımg"` with a dotless ı, so a
     * locale-folding matcher stops matching the query `img` against the filenames
     * every camera on earth produces. `contains(ignoreCase = true)` compares
     * character by character and applies no locale, so the answer is the same
     * everywhere — which is what this asserts, by asking the same question under
     * two default locales.
     */
    @Test
    fun matchingDoesNotDependOnTheDeviceLocale() {
        val original = Locale.getDefault()
        try {
            val photo = item("IMG_4471.jpg", folder = "Istanbul")

            Locale.setDefault(Locale.ENGLISH)
            val english = listOf(
                matches(photo, "img"),
                matches(photo, "IMG"),
                matches(photo, "istanbul"),
                matches(photo, "ISTANBUL"),
            )

            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val turkish = listOf(
                matches(photo, "img"),
                matches(photo, "IMG"),
                matches(photo, "istanbul"),
                matches(photo, "ISTANBUL"),
            )

            assertEquals(
                "matching must not depend on the device's language",
                english,
                turkish,
            )
            assertEquals(
                "the query img must match IMG_4471.jpg on every device",
                listOf(true, true, true, true),
                turkish,
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    // --- P2-21: bodies -------------------------------------------------------

    @Test
    fun aNoteIsFoundByAWordOnlyInItsBody() {
        // The clause the feature exists for: nothing about the name, the folder or
        // the tags contains this word.
        val n = note("Bank.txt", "sort code 60-16-13 and the branch on Mill Road")

        assertTrue(matches(n, "branch"))
        assertTrue(matches(n, "Mill"))
        assertTrue("case is ignored in a body as it is in a name", matches(n, "MILL"))
    }

    @Test
    fun everyTermStillHasToMatchSomething() {
        val n = note("Bank.txt", "sort code and the branch")

        assertTrue("terms may land in different fields", matches(n, "bank branch"))
        assertTrue(!matches(n, "bank passport"))
    }

    @Test
    fun anItemWithNoBodyIsUnaffected() {
        // The body clause must not change what a non-note matches — the four
        // content kinds carry a null body and the clause has to fall through.
        val photo = item("IMG_4471.jpg", folder = "Istanbul", tags = listOf("holiday"))

        assertTrue(matches(photo, "img"))
        assertTrue(matches(photo, "istanbul"))
        assertTrue(!matches(photo, "branch"))
    }

    @Test
    fun matchingABodyIgnoresCaseWithoutALocale() {
        // P2-15's Turkish rule, applied to the field it matters most for: a body is
        // far likelier than a filename to contain the letters `lowercase()` folds
        // wrongly on a Turkish device.
        val original = Locale.getDefault()
        try {
            val n = note("Notes.txt", "INVOICE for the title deed")

            Locale.setDefault(Locale.UK)
            val english = listOf(matches(n, "invoice"), matches(n, "INVOICE"), matches(n, "title"))

            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val turkish = listOf(matches(n, "invoice"), matches(n, "INVOICE"), matches(n, "title"))

            assertEquals("matching a body must not depend on the device's language", english, turkish)
            assertEquals(listOf(true, true, true), turkish)
        } finally {
            Locale.setDefault(original)
        }
    }

    // --- `notes-as-documents`: the preview, and the second pass ----------------

    private fun blobNote(preview: String, filename: String = "note.md") = VaultItem(
        blobIds = listOf(ByteArray(16) { 5 }),
        plaintextLength = 100L,
        thumb = null,
        filename = filename,
        album = null,
        createdAt = null,
        importedAt = 0L,
        tags = emptyList(),
        noteId = ByteArray(16) { 1 },
        preview = preview,
    )

    /** The row answers on its own when the word is in the note's opening. */
    @Test
    fun aWordInThePreviewMatchesWithoutReadingAnything() {
        val note = blobNote("shopping for the weekend")
        var reads = 0

        val out = VaultSearch.filter(listOf(note), "weekend", textOf = { reads++; null })

        assertEquals(1, out.size)
        assertEquals("a preview match must read nothing", 0, reads)
    }

    /** Past the preview, the text has to be read — and it is, exactly once. */
    @Test
    fun aWordPastThePreviewNeedsTheTextAndIsFound() {
        val note = blobNote("shopping for the weekend")
        var reads = 0

        val out = VaultSearch.filter(listOf(note), "artichoke", textOf = {
            reads++
            "shopping for the weekend\nartichoke"
        })

        assertEquals(1, out.size)
        assertEquals(1, reads)
    }

    /** A caller that cannot read returns nothing rather than a match. */
    @Test
    fun aNoteWhoseTextCannotBeReadDoesNotMatch() {
        val out = VaultSearch.filter(listOf(blobNote("opening")), "elsewhere", textOf = { null })
        assertEquals(0, out.size)
    }

    /** Only notes are ever read, and only the ones the index could not answer for. */
    @Test
    fun nothingButAnUnansweredNoteIsEverRead() {
        val photo = VaultItem(
            blobIds = listOf(ByteArray(16) { 2 }),
            plaintextLength = 10L,
            thumb = null,
            filename = "beach.jpg",
            album = null,
            createdAt = null,
            importedAt = 0L,
            tags = emptyList(),
        )
        val read = mutableListOf<String>()

        VaultSearch.filter(listOf(photo, blobNote("hello")), "zebra", textOf = {
            read += it.filename
            null
        })

        assertEquals(listOf("note.md"), read)
    }

    /** A row written before version 12 still carries its text and still matches. */
    @Test
    fun aLegacyNoteStillMatchesFromItsRow() {
        val legacy = VaultItem(
            blobIds = emptyList(),
            plaintextLength = 5L,
            thumb = null,
            filename = "old.txt",
            album = null,
            createdAt = null,
            importedAt = 0L,
            tags = emptyList(),
            body = "artichoke",
            noteId = ByteArray(16) { 3 },
        )
        var reads = 0

        val out = VaultSearch.filter(listOf(legacy), "artichoke", textOf = { reads++; null })

        assertEquals(1, out.size)
        assertEquals("a legacy row answers from itself", 0, reads)
    }
}
