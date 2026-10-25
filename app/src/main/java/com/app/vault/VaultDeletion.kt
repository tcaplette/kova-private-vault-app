package com.app.vault

import android.util.Log

/**
 * One item out of the vault — P2-10, given a trash by P2-12.
 *
 * Three operations, and only one of them destroys anything:
 *
 * - [delete] stamps `deletedAt` on the rows. Nothing is unlinked, nothing is
 *   evicted; the item leaves the grid and appears in the trash.
 * - [restore] clears the stamp. Byte-identical by construction — no blob, no
 *   length, no `ThumbRef` and no filename was ever touched.
 * - [purge] is the destruction P2-10 built, under its right name. It runs when the
 *   user destroys a selection from the trash and when [TrashPurge] finds a stamp
 *   older than the window.
 *
 * **The order in [purge] is the whole of its correctness.** The rows are committed
 * out first, the blobs deleted second. Killed between the two steps, this order
 * leaves sealed blobs nothing references — but the same commit that removed the
 * rows already recorded those blob IDs as this vault's owned chaff
 * (`blob-ownership-by-record`), so nothing has to find them afterwards. The other
 * order leaves a row pointing at blobs that are gone: an item listed in the grid
 * that cannot be opened, cannot be exported, and no path repairs.
 *
 * **`Unvaulter` deletes blobs before it commits, and is right to.** It has already
 * written a verified plaintext copy to the destination, so the failure it is
 * avoiding is exporting the same row twice — not an unopenable row. Do not unify
 * the two.
 *
 * **Only [VaultItem.blobIds] is ever deleted.** Not the thumbnail's pack, which is
 * shared with other items (`FORMAT.md` §8); not owned chaff; and nothing found by
 * scanning `blobs/`, because §6 makes an orphaned content blob and a chaff blob the
 * same file. Deletion is by reference or it does not happen.
 */
object VaultDeletion {

    private const val TAG = "VaultDelete"

    /**
     * Moves [item] to the trash and returns the committed index.
     *
     * The one-element case of [delete]. Kept as its own name because the viewer
     * and a selection of one both mean exactly this, and a caller should not have
     * to wrap a single item in a list to say so.
     */
    fun delete(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        item: VaultItem,
        now: Long = System.currentTimeMillis(),
    ): MetadataDb = delete(store, indexId, vmk, listOf(item), now)

    /**
     * Moves every item in [items] to the trash and returns the committed index.
     *
     * **One commit for the whole set — P2-11.** The index is one blob rewritten
     * whole (§7), so stamping fifty items one call at a time would rewrite it
     * fifty times and take the commit lock fifty times while every other writer
     * queued.
     *
     * No blob is touched. **Nothing is evicted from [ThumbnailCache] either**, and
     * that is the P2-12 change rather than an omission: the trash view draws these
     * very thumbnails one screen later, so dropping them here would mean reading
     * and decrypting them again immediately. The claim about decrypted pixels not
     * outliving the user's intent is made in [purge], which is where the item
     * actually stops being theirs.
     *
     * The returned database is a **new** instance — `commit` loads one — so a
     * caller holding it as Compose state gets a recomposition.
     *
     * An item that is no longer in the index — deleted by another path while a
     * selection sat open — is skipped rather than treated as an error.
     */
    fun delete(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        items: List<VaultItem>,
        now: Long = System.currentTimeMillis(),
    ): MetadataDb = stamp(store, indexId, vmk, items, now).also {
        Log.i(TAG, "trashed ${items.size} item(s)")
    }

    /** Takes [items] back out of the trash. The one-element case of [restore]. */
    fun restore(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        item: VaultItem,
    ): MetadataDb = restore(store, indexId, vmk, listOf(item))

    /**
     * Clears the delete stamp on every item in [items], in one commit.
     *
     * There is nothing else to undo. A delete wrote one field, so a restore clears
     * one field, and the item that comes back is the same bytes it always was.
     */
    fun restore(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        items: List<VaultItem>,
    ): MetadataDb = stamp(store, indexId, vmk, items, null).also {
        Log.i(TAG, "restored ${items.size} item(s)")
    }

    /** Writes [at] — a time, or null to restore — onto every named row, in one commit. */
    private fun stamp(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        items: List<VaultItem>,
        at: Long?,
    ): MetadataDb {
        val named = items.map { it.itemId }.toSet()

        return MetadataDb.commit(store, indexId, vmk) { db ->
            db.items.forEachIndexed { i, row ->
                if (row.itemId in named) db.items[i] = row.withDeletedAt(at)
            }
            db
        }
    }

    /** Destroys [item]. The one-element case of [purge]. */
    fun purge(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        item: VaultItem,
    ): MetadataDb = purge(store, indexId, vmk, listOf(item))

    /**
     * Destroys every item in [items] and returns the committed index.
     *
     * **Two commits, in this order.** The first removes every row and, in the
     * same commit, records their blob IDs as this vault's owned chaff
     * (`blob-ownership-by-record`) — so a kill before the unlink below leaves
     * nothing a scan would have to find. The blobs are unlinked afterwards, in
     * the order this file exists to enforce, and a second commit untracks them
     * once the files are gone. Fifty rows still leave together in the first
     * commit; a purge driven by the window ([TrashPurge]) hands its whole expired
     * set here for the same reason.
     *
     * An item that is no longer in the index is skipped rather than treated as an
     * error. Its blobs are still unlinked, which is a no-op for a row that already
     * took them.
     */
    fun purge(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        items: List<VaultItem>,
    ): MetadataDb {
        val doomed = items.map { it.itemId }.toSet()
        // Every blob every row listed, not the first of each. A split item's
        // remaining blobs would otherwise sit in the pool with nothing naming them.
        val blobIds = items.flatMap { it.blobIds }

        // `remove-note-attachments`. **A note owns nothing, so destroying one destroys
        // only itself.** This used to sweep up every item the note owned; an item that
        // used to be an attachment is an ordinary item now, and destroying the note it
        // was once attached to must not take it.
        //
        // The blob IDs are recorded as owned chaff in this same commit
        // (`blob-ownership-by-record`) — this vault names them the moment their
        // rows stop referencing them, so a kill before the unlink below leaves
        // them tracked rather than found by a later scan.
        MetadataDb.commit(store, indexId, vmk) { db ->
            db.items.removeAll { it.itemId in doomed }
            db.ownedChaff += blobIds
            db
        }

        // Outside the commit, deliberately: unlinking files is not index work, and
        // P2-9's lock is meant to hold load-mutate-save and nothing slower.
        items.forEach { item -> item.blobIds.forEach { store.delete(it) } }

        // The thumbnails' *references* went with the rows. Their bytes stay where
        // they are: they are ranges inside packs shared with other items, and §8
        // defers compaction rather than rewriting 4 MiB per deleted photo. The
        // decoded copies are dropped, because decrypted pixels resident after the
        // user asked for the items to be gone is exactly what this app claims not
        // to do.
        items.forEach { item -> item.thumb?.let(ThumbnailCache::evict) }

        // The second commit: now that the files are gone, untrack them. An ID
        // whose file was already absent — the process died part-way through the
        // deletes above and this purge is what runs on the retry — is removed
        // without raising (blob-ownership-by-record, D3).
        val db = MetadataDb.commit(store, indexId, vmk) { db ->
            db.ownedChaff.removeAll { id -> blobIds.any { it.contentEquals(id) } }
            db
        }

        Log.i(TAG, "purged ${items.size} item(s), ${items.sumOf { it.blobIds.size }} blob(s)")
        return db
    }
}
