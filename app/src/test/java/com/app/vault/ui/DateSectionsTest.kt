package com.app.vault.ui

import com.app.vault.VaultItem
import com.app.vault.TestWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * [DateSections] — P2-8.
 *
 * The property that matters is not the labels, it is that **grouping changes
 * nothing about the order**. `vault-grid` specifies the ordering; a grouping that
 * reordered would become a second, contradicting rule, and the contradiction would
 * only show up as items appearing to move when a vault is reopened.
 */
class DateSectionsTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val locale = Locale.UK

    private var seq = 0

    private fun item(createdAt: Long?, importedAt: Long): VaultItem {
        seq++
        return VaultItem(
            blobIds = listOf(ByteArray(16) { seq.toByte() }),
            plaintextLength = 1L,
            thumb = null,
            filename = "f$seq.jpg",
            album = null,
            createdAt = createdAt,
            importedAt = importedAt,
            tags = emptyList(),
        )
    }

    /** 2026-08-17T12:00:00Z and neighbours. */
    private val day1 = 1_787_054_400_000L
    private val day1Later = day1 + 3_600_000L
    private val day2 = day1 - 86_400_000L

    @Test
    fun anEmptyGridHasNoSections() {
        assertEquals(emptyList<DateSections.Section>(), DateSections.group(TestWords, emptyList(), zone = utc, locale = locale))
    }

    @Test
    fun itemsOnTheSameDayShareASection() {
        val items = listOf(item(day1, 1L), item(day1Later, 2L))
        val sections = DateSections.group(TestWords, items, zone = utc, locale = locale)
        assertEquals(1, sections.size)
        assertEquals(2, sections.single().items.size)
    }

    @Test
    fun differentDaysGetTheirOwnSections() {
        val items = listOf(item(day1, 1L), item(day2, 2L))
        val sections = DateSections.group(TestWords, items, zone = utc, locale = locale)
        assertEquals(2, sections.size)
    }

    @Test
    fun groupingPreservesTheInputOrderExactly() {
        val items = listOf(
            item(day1, 1L),
            item(day2, 2L),
            item(day1, 3L),   // a day reappearing later in the order
            item(null, day2),
        )
        val flattened = DateSections.group(TestWords, items, zone = utc, locale = locale).flatMap { it.items }
        assertEquals(
            "grouping must not reorder; the grid's order is vault-grid's rule, not this file's",
            items.map { it.itemId },
            flattened.map { it.itemId },
        )
    }

    @Test
    fun aDayReappearingLaterMakesASecondSection() {
        // Merging them would mean moving an item, which is the one thing grouping
        // must never do.
        val items = listOf(item(day1, 1L), item(day2, 2L), item(day1, 3L))
        assertEquals(3, DateSections.group(TestWords, items, zone = utc, locale = locale).size)
    }

    @Test
    fun anUndatedItemIsNotGivenACaptureDate() {
        val sections = DateSections.group(TestWords, listOf(item(null, day1)), zone = utc, locale = locale)
        val only = sections.single()
        assertTrue("an undated run must be marked as such", only.dateIsImportTime)
        assertTrue(
            "its heading must not read as a capture date: \"${only.label}\"",
            only.label.startsWith("Added "),
        )
    }

    @Test
    fun datedAndUndatedItemsOnTheSameDayDoNotShareASection() {
        // They would otherwise sit under one heading, and that heading would be
        // claiming a capture date for the undated one — P1-6's rule.
        val items = listOf(item(day1, 1L), item(null, day1))
        val sections = DateSections.group(TestWords, items, zone = utc, locale = locale)
        assertEquals(2, sections.size)
        assertFalse(sections[0].dateIsImportTime)
        assertTrue(sections[1].dateIsImportTime)
    }

    @Test
    fun everyItemAppearsExactlyOnce() {
        val items = (1..20).map {
            if (it % 4 == 0) item(null, day1 - it * 1000L) else item(day1 - it * 86_400_000L, 1L)
        }
        val flattened = DateSections.group(TestWords, items, zone = utc, locale = locale).flatMap { it.items }
        assertEquals(items.size, flattened.size)
        assertEquals(items.map { it.itemId }.toSet(), flattened.map { it.itemId }.toSet())
    }
}
