package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The six orderings — P2-16.
 *
 * `OUTLINE.md`'s Verify, clause for clause: **each ordering is correct across a
 * corpus with duplicate dates and missing `createdAt`**. The corpus below is built
 * to be hostile on exactly those axes — two items sharing a capture date, two
 * sharing an import time, two with no capture date at all, two with the same
 * filename, and two whose names differ only by case or by an accent.
 *
 * The lock/unlock half of the Verify is `SortPreferenceTest`, because it is about
 * `SharedPreferences` and needs a device.
 */
class SortOrderTest {

    /**
     * `itemId` is the first blob's ID in hex, so a distinct seed gives a distinct
     * identity — and the seeds here are deliberately **not** in the same sequence
     * as the corpus, so a comparator falling through to the tiebreaker cannot
     * accidentally agree with insertion order.
     */
    private fun item(
        seed: Int,
        filename: String,
        createdAt: Long?,
        importedAt: Long,
    ) = VaultItem(
        blobIds = listOf(ByteArray(16) { seed.toByte() }),
        plaintextLength = 1L,
        thumb = null,
        filename = filename,
        album = null,
        createdAt = createdAt,
        importedAt = importedAt,
        tags = emptyList(),
    )

    private val day1 = 1_600_000_000_000L
    private val day2 = 1_700_000_000_000L

    /** Two same capture dates, two same import times, two undated, two same names. */
    private val corpus = listOf(
        item(0x11, "bravo.jpg", createdAt = day2, importedAt = 10L),
        item(0x22, "alpha.jpg", createdAt = day2, importedAt = 20L),   // same capture as bravo
        item(0x33, "delta.jpg", createdAt = day1, importedAt = 30L),
        item(0x44, "charlie.jpg", createdAt = null, importedAt = 40L), // undated
        item(0x55, "echo.jpg", createdAt = null, importedAt = 40L),    // undated, same import
        item(0x66, "alpha.jpg", createdAt = day1, importedAt = 50L),   // duplicate filename
    )

    private fun names(sort: VaultSort, items: List<VaultItem> = corpus) =
        sort.order(items).map { it.filename }

    private fun ids(sort: VaultSort, items: List<VaultItem> = corpus) =
        sort.order(items).map { it.itemId }

    // --- 1. each ordering is the ordering it says it is -----------------------

    @Test
    fun newestIsCaptureDateDescendingWithTheImportFallback() {
        // day2: bravo, alpha. day1: delta, alpha(0x66). undated -> importedAt 40:
        // charlie and echo, which sort above day1's 1970-era... no: importedAt is
        // milliseconds, so 40L is 1970 and both undated items sort last.
        val ordered = VaultSort.Newest.order(corpus)
        val dates = ordered.map { it.createdAt ?: it.importedAt }
        assertEquals("not descending", dates.sortedDescending(), dates)
        assertEquals(
            "an undated item must order by its import time, not vanish",
            setOf("charlie.jpg", "echo.jpg"),
            ordered.takeLast(2).map { it.filename }.toSet(),
        )
    }

    @Test
    fun oldestIsNewestReversedExceptForTheTiebreaker() {
        val dates = VaultSort.Oldest.order(corpus).map { it.createdAt ?: it.importedAt }
        assertEquals("not ascending", dates.sorted(), dates)
    }

    @Test
    fun recentlyAddedIsImportTimeDescending() {
        val times = VaultSort.RecentlyAdded.order(corpus).map { it.importedAt }
        assertEquals("not descending", times.sortedDescending(), times)
        assertEquals(
            "the most recently added item must be first",
            "alpha.jpg",
            VaultSort.RecentlyAdded.order(corpus).first().filename,
        )
    }

    @Test
    fun firstAddedIsImportTimeAscending() {
        val times = VaultSort.FirstAdded.order(corpus).map { it.importedAt }
        assertEquals("not ascending", times.sorted(), times)
        assertEquals(
            "the first added item must be first",
            "bravo.jpg",
            VaultSort.FirstAdded.order(corpus).first().filename,
        )
    }

    /** Import-date orderings ignore the capture date entirely, including the fallback. */
    @Test
    fun importOrderingsDoNotConsultTheCaptureDate() {
        val old = item(0x77, "ancient.jpg", createdAt = 1L, importedAt = 99L)
        val items = corpus + old
        assertEquals(
            "an item captured in 1970 but imported last must be first",
            "ancient.jpg",
            VaultSort.RecentlyAdded.order(items).first().filename,
        )
    }

    @Test
    fun nameOrderingsAreAlphabeticalInBothDirections() {
        assertEquals(
            listOf("alpha.jpg", "alpha.jpg", "bravo.jpg", "charlie.jpg", "delta.jpg", "echo.jpg"),
            names(VaultSort.NameAZ),
        )
        assertEquals(
            listOf("echo.jpg", "delta.jpg", "charlie.jpg", "bravo.jpg", "alpha.jpg", "alpha.jpg"),
            names(VaultSort.NameZA),
        )
    }

    // --- 2. totality: the Verify's "duplicate dates" clause -------------------

    @Test
    fun everyOrderingIsTotalOverTheHostileCorpus() {
        VaultSort.entries.forEach { sort ->
            val ordered = sort.order(corpus)
            assertEquals("$sort dropped or duplicated an item", corpus.size, ordered.size)
            assertEquals(
                "$sort produced two items in the same position",
                corpus.size,
                ordered.map { it.itemId }.toSet().size,
            )
        }
    }

    @Test
    fun orderingIsIndependentOfTheInputOrder() {
        val shuffles = listOf(
            corpus.reversed(),
            corpus.shuffled(kotlin.random.Random(7)),
            corpus.sortedBy { it.filename },
        )
        VaultSort.entries.forEach { sort ->
            val reference = ids(sort)
            shuffles.forEachIndexed { i, shuffled ->
                assertEquals(
                    "$sort depends on the order it was handed (shuffle $i)",
                    reference,
                    ids(sort, shuffled),
                )
            }
        }
    }

    /**
     * The reason the tiebreaker exists: a purge removes rows and shifts the rest,
     * and a stable-but-not-total sort would let the survivors reshuffle.
     */
    @Test
    fun removingAnItemLeavesTheRemainingSequenceUnchanged() {
        VaultSort.entries.forEach { sort ->
            val full = sort.order(corpus).map { it.itemId }
            corpus.indices.forEach { i ->
                val without = corpus.filterIndexed { j, _ -> j != i }
                assertEquals(
                    "$sort reshuffled the survivors when item $i was removed",
                    full.filter { it != corpus[i].itemId },
                    sort.order(without).map { it.itemId },
                )
            }
        }
    }

    @Test
    fun anEmptyOrSingleCorpusIsHandled() {
        VaultSort.entries.forEach { sort ->
            assertEquals(emptyList<VaultItem>(), sort.order(emptyList()))
            assertEquals(1, sort.order(listOf(corpus.first())).size)
        }
    }

    // --- 3. collation ---------------------------------------------------------

    @Test
    fun accentedNamesSortBesideTheirUnaccentedForm() {
        val items = listOf(
            item(0x01, "Zulu.jpg", day1, 1L),
            item(0x02, "Ávila.jpg", day1, 2L),
            item(0x03, "Avila.jpg", day1, 3L),
        )
        val ordered = VaultSort.NameAZ.order(items).map { it.filename }
        assertEquals(
            "an accented name must sort beside its unaccented form, not after Z",
            listOf("Avila.jpg", "Ávila.jpg", "Zulu.jpg"),
            ordered,
        )
    }

    @Test
    fun caseIsIgnored() {
        val items = listOf(
            item(0x01, "banana.jpg", day1, 1L),
            item(0x02, "Apple.jpg", day1, 2L),
            item(0x03, "apple.jpg", day1, 3L),
        )
        val ordered = VaultSort.NameAZ.order(items).map { it.filename }
        // Comparing character codes would give Apple, banana, apple — every
        // capital before every lowercase letter. Collation keeps the two apples
        // together and banana last.
        assertEquals("banana.jpg", ordered.last())
        assertEquals(
            "the two spellings of apple must be adjacent, ahead of banana",
            setOf("Apple.jpg", "apple.jpg"),
            ordered.take(2).toSet(),
        )
    }

    /**
     * Ordering follows the device's alphabet, on purpose.
     *
     * **This is the opposite of `VaultSearch`'s rule, and both are right.** A query
     * must match regardless of locale, because the user typed it and it has to find
     * the item. An ordering must follow the locale, because the alphabet belongs to
     * the language: on a Turkish device `i` and `I` are different letters — `I` is
     * the capital of dotless `ı` — so `img` sorting apart from `IMG` there is
     * Turkish alphabetical order, not a case-folding defect.
     *
     * What must hold everywhere is that the ordering is **deterministic**, that it
     * is a total order, and that names with no locale-sensitive letters in them
     * sort identically wherever they are read.
     */
    @Test
    fun nameOrderingFollowsTheDeviceAlphabetAndIsAlwaysDeterministic() {
        val original = Locale.getDefault()
        try {
            val dotted = listOf(
                item(0x01, "IMG_0002.jpg", day1, 1L),
                item(0x02, "img_0001.jpg", day1, 2L),
                item(0x03, "IMG_0003.jpg", day1, 3L),
            )

            Locale.setDefault(Locale.ENGLISH)
            val english = VaultSort.NameAZ.order(dotted).map { it.filename }
            assertEquals(
                "in English, case must not separate img from IMG",
                listOf("img_0001.jpg", "IMG_0002.jpg", "IMG_0003.jpg"),
                english,
            )

            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val turkish = VaultSort.NameAZ.order(dotted).map { it.filename }
            assertEquals(
                "the Turkish ordering must still be deterministic and complete",
                dotted.size,
                turkish.toSet().size,
            )
            assertEquals(
                "and repeatable",
                turkish,
                VaultSort.NameAZ.order(dotted.reversed()).map { it.filename },
            )

            // Names carrying no locale-sensitive letter sort the same everywhere.
            val neutral = listOf(
                item(0x04, "delta.jpg", day1, 1L),
                item(0x05, "alpha.jpg", day1, 2L),
                item(0x06, "charlie.jpg", day1, 3L),
            )
            val turkishNeutral = VaultSort.NameAZ.order(neutral).map { it.filename }
            Locale.setDefault(Locale.ENGLISH)
            assertEquals(
                "names without a dotted or dotless i must sort identically in any locale",
                VaultSort.NameAZ.order(neutral).map { it.filename },
                turkishNeutral,
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun duplicateFilenamesGetADefinedSequence() {
        val ordered = VaultSort.NameAZ.order(corpus)
        val alphas = ordered.filter { it.filename == "alpha.jpg" }
        assertEquals("both duplicates must be present", 2, alphas.size)
        assertNotEquals("they are two items, not one", alphas[0].itemId, alphas[1].itemId)
        assertEquals(
            "the duplicates must be adjacent and in itemId order",
            alphas.map { it.itemId }.sorted(),
            alphas.map { it.itemId },
        )
    }

    // --- 4. the default is what shipped ---------------------------------------

    /**
     * A vault opened with nothing stored must be in the order it was in before
     * P2-16 — `createdAt ?: importedAt` descending, which is what `MainActivity`
     * used to write inline.
     */
    @Test
    fun theDefaultIsTheOrderingTheAppShippedWith() {
        assertEquals(VaultSort.Newest, VaultSort.Default)

        val shipped = corpus.sortedByDescending { it.createdAt ?: it.importedAt }
        val now = VaultSort.Default.order(corpus)

        assertEquals(
            "the default ordering's dates must match the pre-P2-16 sequence",
            shipped.map { it.createdAt ?: it.importedAt },
            now.map { it.createdAt ?: it.importedAt },
        )
        assertTrue(
            "the default must still be a total order",
            now.map { it.itemId }.toSet().size == corpus.size,
        )
    }

    @Test
    fun anUnknownOrUnsetStoredValueReadsAsTheDefault() {
        assertEquals(VaultSort.Default, VaultSort.of(null))
        assertEquals(VaultSort.Default, VaultSort.of(""))
        assertEquals(VaultSort.Default, VaultSort.of("SortedByVibes"))
        VaultSort.entries.forEach { assertEquals(it, VaultSort.of(it.name)) }
    }

    // --- 5. grouping follows the ordering -------------------------------------

    @Test
    fun theGroupingDateFollowsTheOrdering() {
        val undated = item(0x01, "a.jpg", createdAt = null, importedAt = 500L)
        val dated = item(0x02, "b.jpg", createdAt = day1, importedAt = 500L)

        assertEquals(day1, VaultSort.Newest.groupingDate(dated))
        assertEquals("an undated item groups where it is ordered", 500L, VaultSort.Newest.groupingDate(undated))
        assertEquals("an import ordering groups by import time", 500L, VaultSort.RecentlyAdded.groupingDate(dated))

        assertTrue("an undated item's heading must say Added", VaultSort.Newest.dateIsImportTime(undated))
        assertTrue(
            "under an import ordering every heading says Added",
            VaultSort.RecentlyAdded.dateIsImportTime(dated),
        )
        assertTrue(
            "a dated item under a capture ordering must not say Added",
            !VaultSort.Newest.dateIsImportTime(dated),
        )
    }

    @Test
    fun nameOrderingsGroupNothing() {
        assertEquals(VaultSort.Grouping.None, VaultSort.NameAZ.grouping)
        assertEquals(VaultSort.Grouping.None, VaultSort.NameZA.grouping)
        assertEquals(VaultSort.Grouping.ByCaptureDate, VaultSort.Newest.grouping)
        assertEquals(VaultSort.Grouping.ByImportDate, VaultSort.RecentlyAdded.grouping)
    }

    /**
     * P2-16 replaced `itemId`'s `String.format` hex with a table lookup, because a
     * comparator reading it made a 5 000-item ordering take 13.9 s. The output must
     * be identical, so this asserts it against the expression that was there —
     * across every byte value, negatives included.
     */
    @Test
    fun theFastItemIdIsIdenticalToTheFormattedOne() {
        fun formatted(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        val cases = listOf(
            ByteArray(16) { 0 },
            ByteArray(16) { -1 },
            ByteArray(16) { it.toByte() },
            ByteArray(16) { (it * 17 - 128).toByte() },
            ByteArray(16) { (255 - it * 16).toByte() },
        )
        cases.forEach { bytes ->
            val row = VaultItem(
                blobIds = listOf(bytes),
                plaintextLength = 1L,
                thumb = null,
                filename = "x.jpg",
                album = null,
                createdAt = null,
                importedAt = 1L,
                tags = emptyList(),
            )
            assertEquals(formatted(bytes), row.itemId)
            assertEquals("an itemId is two lowercase hex characters per byte", 32, row.itemId.length)
        }

        // Every byte value, one at a time, so no sign-extension case is missed.
        (0..255).forEach { v ->
            val bytes = ByteArray(16) { v.toByte() }
            val row = VaultItem(
                blobIds = listOf(bytes),
                plaintextLength = 1L,
                thumb = null,
                filename = "x.jpg",
                album = null,
                createdAt = null,
                importedAt = 1L,
                tags = emptyList(),
            )
            assertEquals("byte value $v", formatted(bytes), row.itemId)
        }
    }

    @Test
    fun everyOrderingHasADistinctLabel() {
        assertEquals(
            "two orderings with the same label are indistinguishable on the sort screen",
            VaultSort.entries.size,
            VaultSort.entries.map { TestWords.text(it.label) }.toSet().size,
        )
    }
    // --- `destination-settings-and-slideshow`: Last edited ----------------------

    private fun note(seed: Int, importedAt: Long, editedAt: Long?) = item(seed, "n$seed.md", null, importedAt)
        .withEditedAt(editedAt)

    /** An edited note sorts above a newer one never edited, by when it was edited. */
    @Test
    fun lastEditedPutsAnEditAboveANewerUneditedItem() {
        val edited = note(0x01, importedAt = day1, editedAt = day2 + 5)
        val newer = note(0x02, importedAt = day2, editedAt = null)
        assertEquals(listOf(edited.itemId, newer.itemId), ids(VaultSort.LastEdited, listOf(newer, edited)))
    }

    /** An item never edited falls back to when it was added, in the same sequence. */
    @Test
    fun lastEditedFallsBackToImportTime() {
        val older = note(0x03, importedAt = day1, editedAt = null)
        val newer = note(0x04, importedAt = day2, editedAt = null)
        val between = note(0x05, importedAt = 1L, editedAt = day1 + 10)
        assertEquals(
            listOf(newer.itemId, between.itemId, older.itemId),
            ids(VaultSort.LastEdited, listOf(older, between, newer)),
        )
    }

    /** Ties on the edit date are broken by identity, so the order is total and repeatable. */
    @Test
    fun lastEditedIsATotalOrder() {
        val a = note(0x0A, importedAt = 1L, editedAt = day1)
        val b = note(0x0B, importedAt = 2L, editedAt = day1)
        val c = note(0x0C, importedAt = day1, editedAt = null)
        val once = ids(VaultSort.LastEdited, listOf(c, b, a))
        assertEquals(once, ids(VaultSort.LastEdited, listOf(a, b, c)))
        assertEquals(listOf(a.itemId, b.itemId, c.itemId), once)
    }

    /** Headings say Edited over edited items and Added over the rest. */
    @Test
    fun lastEditedHeadingsSayWhichDateTheyAre() {
        val edited = note(0x06, importedAt = 1L, editedAt = day2)
        val added = note(0x07, importedAt = day1, editedAt = null)
        assertTrue(VaultSort.LastEdited.dateIsEditTime(edited))
        assertTrue(!VaultSort.LastEdited.dateIsImportTime(edited))
        assertTrue(VaultSort.LastEdited.dateIsImportTime(added))
        assertTrue(!VaultSort.LastEdited.dateIsEditTime(added))
        assertTrue("no other ordering shows an edit date", !VaultSort.Newest.dateIsEditTime(edited))
        val sections = com.app.vault.ui.DateSections.group(TestWords, 
            listOf(edited, added), VaultSort.LastEdited,
            zone = java.util.TimeZone.getTimeZone("UTC"), locale = Locale.UK,
        )
        assertTrue(sections[0].label, sections[0].label.startsWith("Edited "))
        assertTrue(sections[1].label, sections[1].label.startsWith("Added "))
    }
}
