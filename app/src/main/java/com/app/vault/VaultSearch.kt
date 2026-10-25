package com.app.vault

/**
 * Asking the vault a question — P2-15.
 *
 * **P2-21 widened what a query touches, and that is worth naming.** Until notes, a
 * query matched **names and labels** — filename, folder, tags — every one of them
 * something the user attached to content. It now matches the *content* of one kind,
 * because a note whose text cannot be searched is a note nobody finds. Nothing else
 * P2-15 promised moved: the query still lives in `MainActivity`'s Compose state, is
 * cleared on lock, reaches no preference, enqueues no work, and neither it nor a
 * count of its matches reaches a log. `SearchScanTest` still fails the build if that
 * changes.
 *
 * **In memory, over rows that are already loaded.** `MainActivity` holds the index
 * for the whole unlocked session because the grid is drawn from it, so a query
 * costs no read, no decrypt and no I/O. There is no stored search index and no new
 * field: `filename` and `tags` have been in the index since P1-5, the folder since
 * `folder-system`, and
 * `FORMAT.md` §7 already makes all three the index's own concern.
 *
 * **Every term must match, and terms may match different fields.** `passport 2019`
 * finds `IMG_2019.jpg` in the folder `Passports`. Matching the whole query as one
 * substring is one line shorter and returns nothing for almost every two-word
 * query, which reads to the user as a broken feature rather than a strict one.
 *
 * **A query is the most revealing string in the app**, so nothing here logs — not
 * the query, and not a count of matches, which is a fact about the user's content.
 * The query lives in `MainActivity`'s Compose state, is cleared on lock, reaches no
 * preference, and enqueues no work; `SearchScanTest` fails the build if that
 * changes.
 *
 * **`tags` matches nothing in the shipped app today.** `MediaImport`,
 * `ImportWorker` and `CaptureIngest` all write `tags = emptyList()` and no task in
 * `OUTLINE.md` assigns them. The field is matched here because the outline names it
 * and because it is real in the format — it is serialised, archived and recovered —
 * but until something writes a tag, only a row a test wrote can match on one.
 */
object VaultSearch {

    /**
     * [query] split into the terms that must all match.
     *
     * Blank gives an empty list, which matches everything: an empty field is not a
     * question, and answering it with "nothing matches" reads as broken the instant
     * the search is opened.
     */
    fun terms(query: String): List<String> =
        query.trim().split(' ', '\t', '\n').filter { it.isNotEmpty() }

    /**
     * Whether [item] answers every term in [terms].
     *
     * A term matches when it is a substring of the filename, of the name of the
     * folder the item is in, of any one tag, or — P2-21 — of a note's body. Two terms
     * need not land in the same field — requiring that would make the folder and the
     * filename two separate searches the user has to guess between.
     *
     * **Case is ignored without a locale.** `contains(ignoreCase = true)` compares
     * character by character; `lowercase()` is the obvious alternative and is
     * wrong, because it folds in the default locale — on a device set to Turkish
     * `"IMG".lowercase()` is `"ımg"` with a dotless ı, and the query `img` stops
     * matching the filenames every camera on earth produces. That defect appears
     * only on someone else's phone, so it is decided here rather than found there.
     */
    fun matches(
        item: VaultItem,
        terms: List<String>,
        /**
         * The name of the folder [item] is in — `folder-system`, and what the album
         * string used to be. A parameter because a name lives in the folder table
         * now and an item carries only a reference to it; this file knows nothing
         * about the index, as it never did.
         */
        folderName: String? = null,
    ): Boolean = terms.all { term ->
        item.filename.contains(term, ignoreCase = true) ||
            folderName?.contains(term, ignoreCase = true) == true ||
            item.tags.any { it.contains(term, ignoreCase = true) } ||
            // P2-21. `ignoreCase` here for the reason written above and one more: a
            // body is far likelier than a filename to contain the letters the
            // Turkish rule is about.
            //
            // `notes-as-documents` moved the text into a blob, so what is in the row
            // is [VaultItem.preview] — the note's opening. `body` is still read
            // because a row written before version 12 still carries it, and a vault
            // that has only been browsed still has those rows.
            item.preview?.contains(term, ignoreCase = true) == true ||
            item.body?.contains(term, ignoreCase = true) == true
    }

    /**
     * Whether [item] is a note whose *whole* text still has to be read to answer
     * [terms] — `notes-as-documents`.
     *
     * True only for a note that [matches] rejected and whose text is in a blob. That
     * is the second pass's whole job: the first pass answered from the index and cost
     * nothing, and this names the rows the answer was not in.
     */
    fun needsItsText(item: VaultItem, terms: List<String>, folderName: String? = null): Boolean =
        item.noteId != null &&
            item.body == null &&
            item.blobIds.isNotEmpty() &&
            !matches(item, terms, folderName)

    /**
     * Whether [text] — a note's full body, already read — answers every term in
     * [terms] that its row did not.
     *
     * Separate from [matches] so the reading stays at the call site: this object has
     * never known about the store and does not start now.
     */
    fun textMatches(item: VaultItem, text: String, terms: List<String>, folderName: String? = null) =
        terms.all { term ->
            item.filename.contains(term, ignoreCase = true) ||
                folderName?.contains(term, ignoreCase = true) == true ||
                item.tags.any { it.contains(term, ignoreCase = true) } ||
                text.contains(term, ignoreCase = true)
        }

    /**
     * [items] narrowed to those matching [query], in the order they came in.
     *
     * The one call the UI makes. Order is preserved rather than ranked: the grid's
     * ordering is capture-date descending and the date sections are drawn from it,
     * so reordering results would mean a heading over the wrong date.
     */
    fun filter(
        items: List<VaultItem>,
        query: String,
        /** The name of the folder an item is in, or null — `folder-system`. */
        folderName: (VaultItem) -> String? = { null },
        /**
         * A note's full text, read on demand — `notes-as-documents`.
         *
         * Called **only** for a note the index could not answer for, so a query that
         * the preview already matched reads nothing. Default returns null, which
         * keeps every existing caller and every JVM test index-only.
         *
         * The reading itself belongs to the caller and must not be on the main
         * thread: this is the one place the move to blobs made something slower, and
         * a vault of 500 notes does up to 500 small decryptions on a query none of
         * their previews match.
         */
        textOf: (VaultItem) -> String? = { null },
    ): List<VaultItem> {
        val terms = terms(query)
        if (terms.isEmpty()) return items
        return items.filter { item ->
            val name = folderName(item)
            if (matches(item, terms, name)) return@filter true
            if (!needsItsText(item, terms, name)) return@filter false
            val text = textOf(item) ?: return@filter false
            textMatches(item, text, terms, name)
        }
    }
}
