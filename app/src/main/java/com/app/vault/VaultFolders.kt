package com.app.vault

import android.util.Log

/** A folder as the grid draws it: the record, and how many live items are directly in it. */
class FolderView(val folder: VaultFolder, val count: Int) {
    val id: String get() = folder.id
    val name: String get() = folder.name
    val colour: FolderColour get() = folder.colour
}

/**
 * Whether this folder is listed in [destination]: where it was made, or anywhere
 * when it is unscoped. A null [destination] asks for no narrowing.
 */
fun VaultFolder.listedIn(destination: VaultDestination?) =
    destination == null || this.destination == null || this.destination == destination

/**
 * Folders — `folder-system`. Every write that creates, names, moves, colours,
 * covers or removes one goes through here.
 *
 * ***This replaces `VaultAlbums`, and the decision it reverses is the point.*** P2-13
 * derived albums from the items and stored no list of them, on the grounds that a
 * stored list bought exactly one thing — an album that exists with nothing in it —
 * and cost an index field, a version bump and a second place the truth lives. That
 * reasoning was sound and the price is now being paid deliberately: a folder the user
 * makes before they have anything to put in it *is* the feature.
 *
 * **What is stored and what is derived.** A folder's existence, name, parent, colour
 * and chosen cover are stored ([VaultFolder]). Its **count is still derived** from the
 * live items, so the two can never disagree — there is nothing to reconcile, because
 * only one of them is written down.
 *
 * **A name is content.** It is a sentence the user wrote about what their items are,
 * so it lives in the encrypted index and nowhere else — never in WorkManager's
 * database (see [WorkJobs]), never in a preference, and never in a log line. The
 * logging here counts items and names nothing, and `AlbumScanTest` fails the build if
 * that changes. The identity and the colour are held to the same rule: an identity
 * that appears in `work.db` beside a count is a fact about this vault's shape.
 */
object VaultFolders {

    private const val TAG = "VaultFolders"

    /**
     * The longest name accepted — `VaultAlbums.MAX_NAME`'s value and its reason.
     * Long enough for a sentence fragment, short enough that a bar title has
     * something to truncate rather than a paragraph.
     */
    const val MAX_NAME = 64

    /** Why a name or a move was refused. Reported in place; never a silent fallback. */
    enum class Refusal {
        /** Blank after trimming, or longer than [MAX_NAME]. */
        Name,
        /** A folder under the same parent already carries this name. */
        Duplicate,
        /** The destination is the folder itself, or a folder inside it. */
        Cycle,
    }

    /** A refusal, or the committed index. */
    class Result private constructor(val refusal: Refusal?, val db: MetadataDb?) {
        companion object {
            fun refused(refusal: Refusal) = Result(refusal, null)
            fun done(db: MetadataDb) = Result(null, db)
        }
    }

    /**
     * Trims [name] and returns it, or null when it cannot be a folder name.
     *
     * Null is a refusal the caller reports, never a silent fallback — `VaultAlbums`'
     * contract, for its reason: a repaired name is a name the user did not choose.
     */
    fun normalise(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME) return null
        return trimmed
    }

    /**
     * The folders directly inside [parentId] — null for the top level — ordered by
     * name, each with its live count.
     *
     * **Its own items only.** A sub-folder's items are in the sub-folder; counting
     * them here as well would show the same item twice on one screen, once as a
     * number and once as a tile.
     */
    fun children(
        folders: List<VaultFolder>,
        items: List<VaultItem>,
        parentId: ByteArray?,
        destination: VaultDestination? = null,
    ): List<FolderView> {
        val parent = parentId?.let { IdKey(it) }
        // Keyed by [IdKey] and not by hex: this runs over every item in the vault on
        // every recomposition, and a hex key there costs a string per item per frame.
        val live = items.asSequence()
            .filter { it.deletedAt == null }
            .mapNotNull { it.folderKey }
            .groupingBy { it }
            .eachCount()
        return folders.asSequence()
            .filter { it.parentKey == parent && it.listedIn(destination) }
            .sortedBy { it.name }
            .map { FolderView(it, live[it.key] ?: 0) }
            .toList()
    }

    /**
     * The folders [items] may be moved into: made on the destination that holds every
     * one of their kinds.
     *
     * **Decided by the items, not the tab** — the tab the user was last on says nothing
     * about items moved from somewhere else, such as intruder captures moved in from the
     * home screen. A folder made on no destination accepts nothing, and a set spanning two
     * destinations is accepted by no folder.
     */
    fun accepting(folders: List<VaultFolder>, items: Collection<VaultItem>): List<VaultFolder> =
        folders.filter { folder ->
            val kinds = folder.destination?.kinds ?: return@filter false
            items.all { it.kind in kinds }
        }

    /**
     * The items directly inside [folderId] — null for the top level.
     *
     * An item whose folder names no record is at the top level: a dangling reference
     * must not hide an item. See [VaultItem.folderId].
     */
    fun items(
        folders: List<VaultFolder>,
        items: List<VaultItem>,
        folderId: ByteArray?,
    ): List<VaultItem> {
        val known = folders.mapTo(mutableSetOf()) { it.key }
        val want = folderId?.let { IdKey(it) }
        return items.filter { item ->
            val at = item.folderKey?.takeIf { it in known }
            at == want
        }
    }

    /**
     * [folderId] and every folder inside it, at any depth — the search scope.
     *
     * Walks over the folders, which are few; the result is then asked of every item,
     * which is why it is a set of [IdKey] rather than of hex strings.
     */
    internal fun subtree(folders: List<VaultFolder>, folderId: ByteArray): Set<IdKey> {
        val byParent = folders.groupBy { it.parentKey }
        val start = IdKey(folderId)
        val out = mutableSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            byParent[at].orEmpty().forEach { if (out.add(it.key)) queue.addLast(it.key) }
        }
        return out
    }

    /**
     * [subtree] as hex identities, for the one caller that is not a per-item path.
     *
     * The picker compares a handful of rows on one screen and already holds hex; it
     * has no reason to know about the key type the hot paths use.
     */
    fun subtreeIds(folders: List<VaultFolder>, folderId: ByteArray): Set<String> {
        val within = subtree(folders, folderId)
        return folders.asSequence().filter { it.key in within }.mapTo(mutableSetOf()) { it.id }
    }

    /**
     * The item a folder's tile shows: the chosen cover while it is live, otherwise
     * the newest live item within the folder **or anything inside it**, otherwise
     * none.
     *
     * Descending into sub-folders is why a folder holding only folders still shows a
     * picture. The stored choice is left alone when its item is trashed, so restoring
     * the item restores the cover rather than the user having to choose it again.
     */
    fun coverFor(
        folders: List<VaultFolder>,
        items: List<VaultItem>,
        folder: VaultFolder,
    ): VaultItem? {
        val live = items.filter { it.deletedAt == null }
        val chosen = folder.coverItemId?.let { id -> live.firstOrNull { it.itemId == hex(id) } }
        if (chosen != null) return chosen

        val within = subtree(folders, folder.folderId)
        return live.asSequence()
            .filter { it.folderKey in within }
            .maxByOrNull { it.createdAt ?: it.importedAt }
    }

    /**
     * Creates a folder called [name] inside [parentId] — null for the top level.
     *
     * Refuses a name that is blank or too long, and a name a sibling already carries.
     * Siblings, not the whole vault: two folders under different parents with the
     * same name are two different places, which is the ordinary way people organise.
     */
    fun create(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        parentId: ByteArray?,
        name: String,
        destination: VaultDestination? = null,
    ): Result {
        val clean = normalise(name) ?: return Result.refused(Refusal.Name)

        return MetadataDb.commit(store, indexId, vmk) { db ->
            if (nameTaken(db.folders, parentId, clean, null)) {
                return@commit Result.refused(Refusal.Duplicate)
            }
            db.folders.add(
                VaultFolder(
                    MetadataDb.newFolderId(), parentId, clean, FolderColour.None,
                    destination = destination,
                )
            )
            Log.i(TAG, "created 1 folder")
            Result.done(db)
        }
    }

    /**
     * Renames [folderId].
     *
     * **One record and nothing else.** No item is rewritten and no sub-folder is
     * rewritten, because an item points at an identity rather than at a name — the
     * whole return on storing folders by identity.
     */
    fun rename(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        folderId: ByteArray,
        name: String,
    ): Result {
        val clean = normalise(name) ?: return Result.refused(Refusal.Name)

        return MetadataDb.commit(store, indexId, vmk) { db ->
            val at = db.folders.indexOfFirst { it.id == hex(folderId) }
            if (at < 0) return@commit Result.done(db)
            if (nameTaken(db.folders, db.folders[at].parentId, clean, folderId)) {
                return@commit Result.refused(Refusal.Duplicate)
            }
            db.folders[at] = db.folders[at].withName(clean)
            Log.i(TAG, "renamed 1 folder")
            Result.done(db)
        }
    }

    /**
     * Moves [folderId] under [newParentId] — null for the top level.
     *
     * **One field on one record.** Everything inside travels because it points at
     * this folder, not at a path, so a subtree of any size moves in one commit.
     *
     * **A folder cannot be moved into itself or into anything inside it.** Either
     * would detach that subtree from the root: every level query walks down from the
     * top, so the folders would exist in the index and be unreachable on screen.
     * `MetadataDb.parse` pulls such a folder back to the top level if one ever
     * appears, but the check is here, before anything is written.
     */
    fun reparent(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        folderId: ByteArray,
        newParentId: ByteArray?,
    ): Result = MetadataDb.commit(store, indexId, vmk) { db ->
        val at = db.folders.indexOfFirst { it.id == hex(folderId) }
        if (at < 0) return@commit Result.done(db)

        if (newParentId != null && IdKey(newParentId) in subtree(db.folders, folderId)) {
            return@commit Result.refused(Refusal.Cycle)
        }
        if (nameTaken(db.folders, newParentId, db.folders[at].name, folderId)) {
            return@commit Result.refused(Refusal.Duplicate)
        }
        db.folders[at] = db.folders[at].withParent(newParentId)
        Log.i(TAG, "moved 1 folder")
        Result.done(db)
    }

    /**
     * Removes [folderId], moving what it held up to the folder that held it.
     *
     * **Deleting a folder never destroys an item.** Its direct sub-folders and its
     * items reparent to this folder's own parent — the home screen when it had none —
     * inside the **same** commit that drops the record, so nothing is ever pointing
     * at a folder that is gone. Nothing is trashed, nothing is purged, and no field
     * but the reference is written.
     */
    fun delete(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        folderId: ByteArray,
    ): MetadataDb = MetadataDb.commit(store, indexId, vmk) { db ->
        val gone = db.folders.firstOrNull { it.id == hex(folderId) } ?: return@commit db
        val heir = gone.parentId

        db.folders.forEachIndexed { i, folder ->
            if (folder.parentId?.let { hex(it) } == gone.id) {
                db.folders[i] = folder.withParent(heir)
            }
        }
        db.items.forEachIndexed { i, item ->
            if (item.folderId?.let { hex(it) } == gone.id) {
                db.items[i] = item.withFolder(heir)
            }
        }
        db.folders.removeAll { it.id == gone.id }

        // Counts, never the name — the P2-13 rule this file inherits.
        Log.i(TAG, "deleted 1 folder")
        db
    }

    /** Sets [folderId]'s colour. One record. */
    fun setColour(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        folderId: ByteArray,
        colour: FolderColour,
    ): MetadataDb = MetadataDb.commit(store, indexId, vmk) { db ->
        val at = db.folders.indexOfFirst { it.id == hex(folderId) }
        if (at >= 0) db.folders[at] = db.folders[at].withColour(colour)
        db
    }

    /** Sets [folderId]'s cover, or clears it when [coverItemId] is null. One record. */
    fun setCover(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        folderId: ByteArray,
        coverItemId: ByteArray?,
    ): MetadataDb = MetadataDb.commit(store, indexId, vmk) { db ->
        val at = db.folders.indexOfFirst { it.id == hex(folderId) }
        if (at >= 0) db.folders[at] = db.folders[at].withCover(coverItemId)
        db
    }

    /**
     * Puts every item in [items] into [folderId], or takes them out of any folder
     * when it is null, and returns the committed index.
     *
     * **One commit for the whole set** — `VaultAlbums.move`'s shape and its reason:
     * the index is one blob rewritten whole (§7), so twenty moves one call at a time
     * would rewrite it twenty times and hold the commit lock twenty times while every
     * other writer queued.
     *
     * An item is in one folder at most, so moving into one replaces whatever it was
     * in; there is no add-and-keep. Removing is this call with a null folder rather
     * than an operation of its own.
     *
     * Nothing but the folder reference is touched: not the blobs, not the thumbnail
     * reference, not the filename, the dates, the tags, or the trash state.
     */
    fun move(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        items: List<VaultItem>,
        folderId: ByteArray?,
    ): MetadataDb {
        val named = items.map { it.itemId }.toSet()

        val db = MetadataDb.commit(store, indexId, vmk) { db ->
            db.items.forEachIndexed { i, row ->
                if (row.itemId in named) db.items[i] = row.withFolder(folderId)
            }
            db
        }

        // Counts, never the name. A log line naming a folder is the same leak as a
        // name in `work.db`, written by the app about itself.
        Log.i(TAG, "moved ${items.size} item(s)")
        return db
    }

    /** Whether [parentId] already holds a folder called [name], ignoring [except]. */
    private fun nameTaken(
        folders: List<VaultFolder>,
        parentId: ByteArray?,
        name: String,
        except: ByteArray?,
    ): Boolean {
        val parent = parentId?.let { hex(it) }
        val skip = except?.let { hex(it) }
        return folders.any {
            it.parentId?.let { p -> hex(p) } == parent && it.name == name && it.id != skip
        }
    }
}
