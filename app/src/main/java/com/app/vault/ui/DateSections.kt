package com.app.vault.ui

import com.app.vault.R
import com.app.vault.VaultItem
import com.app.vault.VaultSort
import com.app.vault.Words
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Grouping the grid under date headings — P2-8.
 *
 * **Grouping does not reorder.** The grid's order is `vault-grid`'s own
 * requirement — since P2-16, one of six orderings the user chooses — and this
 * inserts headings around that sequence without touching it. A grouping that
 * sorted for itself would silently become a second ordering rule, disagreeing with
 * the spec the first one is written in. That is why the date each item is grouped
 * under is asked of [VaultSort] rather than written out again here.
 *
 * An undated item is grouped by the date it is already *ordered* by, which is its
 * import time. That is a deliberate compromise with P1-6's rule that an undated
 * item is "never treated as having a capture date it does not have": it appears
 * where the ordering already put it, and its heading is marked so the date is not
 * claimed as a capture date. Hiding such items or giving them a heading of their
 * own would move them, which is the thing that must not happen.
 */
object DateSections {

    /** A heading and the items under it, in the order they arrived. */
    data class Section(
        val label: String,
        /** True when the items here have no capture date of their own. */
        val dateIsImportTime: Boolean,
        val items: List<VaultItem>,
        /** True when the date is when the items were last edited — under Last edited only. */
        val dateIsEditTime: Boolean = false,
    )

    /**
     * The day an item sorts by, **asked of the ordering** — P2-16.
     *
     * It used to be `createdAt ?: importedAt` written out here, which was correct
     * while the grid had one ordering and would have become a second ordering rule
     * the moment it had six. [VaultSort] owns the answer for the same reason it
     * owns the comparator.
     */
    private fun sortDate(item: VaultItem, sort: VaultSort): Long = sort.groupingDate(item)

    private fun dayKey(millis: Long, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone).apply { timeInMillis = millis }
        return c.get(Calendar.YEAR) * 10_000L +
            c.get(Calendar.MONTH) * 100L +
            c.get(Calendar.DAY_OF_MONTH)
    }

    /**
     * Groups [items] into consecutive runs sharing a day.
     *
     * Consecutive, not collected: if the caller hands over an order where a day
     * appears twice, two sections appear. That is on purpose — silently merging
     * them would reorder items to bring them together.
     */
    fun group(
        words: Words,
        items: List<VaultItem>,
        /**
         * P2-16. Which date the headings are about, and whether it is an import
         * time. [VaultSort.Grouping.None] never reaches here — the grid is drawn
         * flat under a name ordering.
         */
        sort: VaultSort = VaultSort.Default,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.getDefault(),
        label: (Words, Long, Boolean, Boolean, Locale, TimeZone) -> String = ::defaultLabel,
    ): List<Section> {
        if (items.isEmpty()) return emptyList()

        val sections = mutableListOf<Section>()
        var run = mutableListOf<VaultItem>()
        var key: Long? = null
        var undated = false
        var edited = false

        items.forEach { item ->
            val itemKey = dayKey(sortDate(item, sort), zone)
            // P2-16. Under an import-date ordering this is true for every item, so
            // every heading says "Added" — printing an import date bare is the
            // claim P1-6 forbids.
            val itemUndated = sort.dateIsImportTime(item)
            // `destination-settings-and-slideshow` D2. Under Last edited an edited run and an
            // added run on one day are two runs, each saying which date it is.
            val itemEdited = sort.dateIsEditTime(item)
            if (key == null || itemKey != key || itemUndated != undated || itemEdited != edited) {
                if (run.isNotEmpty()) {
                    sections += Section(
                        label(words, sortDate(run.first(), sort), undated, edited, locale, zone),
                        undated,
                        run.toList(),
                        edited,
                    )
                }
                run = mutableListOf()
                key = itemKey
                undated = itemUndated
                edited = itemEdited
            }
            run += item
        }
        if (run.isNotEmpty()) {
            sections += Section(
                label(words, sortDate(run.first(), sort), undated, edited, locale, zone),
                undated,
                run.toList(),
                edited,
            )
        }
        return sections
    }

    /**
     * The heading text.
     *
     * An undated run says when it was *added*, in those words. The alternative —
     * printing the import date bare — is exactly the claim P1-6 forbids.
     */
    fun defaultLabel(
        words: Words,
        millis: Long,
        undated: Boolean,
        edited: Boolean,
        locale: Locale,
        zone: TimeZone,
    ): String {
        // `app-languages` D8. The language's own long date — "6 October 2026", "2026年10月6日".
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
            .format(Instant.ofEpochMilli(millis).atZone(zone.toZoneId()))
        return when {
            edited -> words.get(R.string.section_edited, date)
            undated -> words.get(R.string.section_added, date)
            else -> date
        }
    }
}
