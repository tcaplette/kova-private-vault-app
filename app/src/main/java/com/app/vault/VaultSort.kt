package com.app.vault

import androidx.annotation.StringRes
import java.text.CollationKey
import java.text.Collator

/**
 * How the grid is ordered — P2-16.
 *
 * Until this, the app had exactly one opinion about sequence: `createdAt ?:
 * importedAt` descending, written in `MainActivity` and repeated in
 * `DateSections`. [Newest] is that expression, so a vault opened with nothing
 * stored is in the order it was already in.
 *
 * **Each entry owns three things** — how to compare, what to call itself, and what
 * the headings should do. Together, because a grouping that decides its own date
 * becomes a second ordering rule that disagrees with the first, which is the
 * failure `DateSections`' own documentation warns about.
 *
 * **Every ordering is a total order.** Each comparator ends in [VaultItem.itemId]
 * ascending, so no two rows ever compare equal. This is not decoration: `sortedBy`
 * is stable, so without it items sharing a date keep whatever sequence the index
 * rows are in — and that sequence shifts when a purge removes rows, so a burst of
 * photographs taken in one second can reshuffle between sessions with nothing
 * having changed about them. `itemId` rather than the filename, because P2-14
 * declined to make filenames unique and a filename tiebreaker would leave ties in
 * exactly the corpus this exists to fix.
 *
 * Nothing here logs. A filename in a log line is the boundary P2-14 recorded.
 */
enum class VaultSort(
    /** What the sort screen and the drawer row call this. */
    @StringRes val label: Int,
    /** How the grid groups under this ordering — [Grouping]. */
    val grouping: Grouping,
) {

    /**
     * Capture date, most recent first — the app's ordering before P2-16 and its
     * default after.
     */
    Newest(R.string.sort_newest, Grouping.ByCaptureDate),
    Oldest(R.string.sort_oldest, Grouping.ByCaptureDate),

    /** Import time, most recent first: what was *put in* most recently. */
    RecentlyAdded(R.string.sort_recently_added, Grouping.ByImportDate),
    FirstAdded(R.string.sort_first_added, Grouping.ByImportDate),

    NameAZ(R.string.sort_name_az, Grouping.None),
    NameZA(R.string.sort_name_za, Grouping.None),

    /**
     * Last edited, most recent first — `destination-settings-and-slideshow` D2. An item never
     * edited orders by when it was added, so every destination has an answer: a Media list
     * under this reads as Recently added, as Drive's "Last modified" does for unmodified files.
     */
    LastEdited(R.string.sort_last_edited, Grouping.ByEditDate);

    /** What the grid's date headings are derived from, or that there are none. */
    enum class Grouping {
        /**
         * The capture date, falling back to the import time for an undated item —
         * which is where that item is already ordered, and whose heading says
         * "Added" so no capture date is claimed (P1-6).
         */
        ByCaptureDate,

        /**
         * The import time, for every item. **Every** heading says "Added", because
         * printing an import date bare is precisely the claim P1-6 forbids.
         */
        ByImportDate,

        /**
         * The edit time, falling back to the import time for an item never edited. A heading
         * says "Edited" over the first and "Added" over the second — P1-6's rule, that no
         * date is claimed which the item does not have.
         */
        ByEditDate,

        /** No headings. A date heading over an alphabetical list is about the wrong thing. */
        None,
    }

    /**
     * The date this ordering groups an item under.
     *
     * Only meaningful for the two date groupings; [Grouping.None] never asks.
     */
    fun groupingDate(item: VaultItem): Long = when (grouping) {
        Grouping.ByImportDate -> item.importedAt
        Grouping.ByEditDate -> item.editedAt ?: item.importedAt
        else -> item.createdAt ?: item.importedAt
    }

    /**
     * Whether the date this ordering shows for [item] is an import time rather
     * than a capture date — the flag that makes a heading say "Added".
     */
    fun dateIsImportTime(item: VaultItem): Boolean = when (grouping) {
        Grouping.ByImportDate -> true
        Grouping.ByEditDate -> item.editedAt == null
        else -> item.createdAt == null
    }

    /** Whether the date this ordering shows for [item] is an edit time — a heading says "Edited". */
    fun dateIsEditTime(item: VaultItem): Boolean =
        grouping == Grouping.ByEditDate && item.editedAt != null

    /**
     * [items] in this order.
     *
     * The one call the UI makes.
     *
     * **Every key is computed once per item, before anything is compared.** That
     * is not a micro-optimisation, it is the difference between working and not:
     * [VaultItem.itemId] is a computed property that formats sixteen bytes as hex
     * on every read, and every comparator here ends in it. Read inside the
     * comparator, a 5 000-item name ordering took **13.9 s** on a Pixel_8 AVD —
     * roughly two million `String.format` calls — against 2 ms for a date
     * ordering, which touches `itemId` only on the rare tie. Building n keys and
     * comparing those makes it O(n) reads instead of O(n log n).
     *
     * The collation key is built in the same pass, and only for the orderings that
     * need one.
     */
    fun order(items: List<VaultItem>): List<VaultItem> {
        if (items.size < 2) return items

        // `SECONDARY` strength: accents distinguish, case does not. `Ávila` sorts
        // beside `Avila` rather than after every unaccented name, which is what
        // comparing character codes gives — and case is ignored *by collation
        // strength* rather than by `lowercase()`, which rewrites the string before
        // anything compares it.
        //
        // **The collator is the device's, and that is deliberate.** Alphabetical
        // order is a property of a language, not of a program: a Turkish device
        // sorts `i` and `I` apart because in Turkish they are different letters —
        // `I` is the capital of dotless `ı` — and that is the right answer there,
        // not a case-folding bug. Ordering differs by locale on purpose.
        //
        // **This is the opposite of P2-15's rule and the two do not conflict.**
        // Matching must be locale-independent, because a query the user typed has
        // to find the item wherever they are standing; ordering must be
        // locale-dependent, because the alphabet is theirs. `VaultSearch` compares
        // without a locale for the first reason; this collates with one for the
        // second. Found by `SortOrderTest` under `tr-TR`.
        val collator = if (grouping == Grouping.None) {
            Collator.getInstance().apply { strength = Collator.SECONDARY }
        } else null

        val keyed = items.map { item ->
            Keyed(
                item = item,
                id = item.itemId,
                name = collator?.getCollationKey(item.filename),
            )
        }

        val byId = compareBy<Keyed> { it.id }
        val comparator = when (this) {
            Newest -> compareByDescending<Keyed> { it.item.createdAt ?: it.item.importedAt }
            Oldest -> compareBy<Keyed> { it.item.createdAt ?: it.item.importedAt }
            RecentlyAdded -> compareByDescending<Keyed> { it.item.importedAt }
            FirstAdded -> compareBy<Keyed> { it.item.importedAt }
            NameAZ -> compareBy<Keyed> { it.name!! }
            NameZA -> compareByDescending<Keyed> { it.name!! }
            LastEdited -> compareByDescending<Keyed> { it.item.editedAt ?: it.item.importedAt }
        }.then(byId)

        return keyed.sortedWith(comparator).map { it.item }
    }

    /** An item with its sort keys already computed — see [order]. */
    private class Keyed(val item: VaultItem, val id: String, val name: CollationKey?)

    companion object {

        /** What the grid uses until the user chooses — the ordering the app shipped with. */
        val Default = Newest

        /**
         * The stored value's ordering, or [Default] when it is absent or is a name
         * this build does not know.
         *
         * An unrecognised value reads as the default rather than throwing: a
         * preference written by a later build must not stop this one from opening
         * a grid.
         */
        fun of(name: String?): VaultSort =
            entries.firstOrNull { it.name == name } ?: Default

    }
}
