package com.app.vault

import java.io.ByteArrayInputStream
import java.security.SecureRandom

/**
 * Making a note — P2-21.
 *
 * **Pure, and deliberately so.** The two rules worth getting wrong here are the cap
 * and the naming, and neither needs a device. Inside the editor's composable the
 * cap's one genuinely tricky case — that it is measured in *bytes* and not in
 * characters — would be checkable only by typing emoji into an emulator until
 * something broke.
 *
 * No logging in this file. A title is a filename, which P2-14 already put out of
 * bounds, and a body is worse: it is the content itself.
 */
object VaultNote {

    /**
     * The largest body a note may carry, in **encoded UTF-8 bytes**.
     *
     * **64 KiB until `notes-as-documents`, and the reason it was that small is
     * gone.** The text used to live in the index row, and the index is one blob
     * rewritten whole on every commit — so a body was the one field that could grow
     * the index without bound, and every note in the vault was re-encrypted whenever
     * anything changed. The text is in its own blob now, split across blobs by
     * `BlobStore.writeItem` like a photograph, so the format imposes no ceiling at
     * all.
     *
     * **A megabyte is where the editor stops, not where the format does.** Roughly
     * 150,000 words; past it the `TextField`'s own layout cost is the limit and a
     * document that size is a file rather than a note.
     */
    const val MAX_BODY_BYTES = 1024 * 1024

    /**
     * The ending every note carries — `.md` since `notes-as-documents`.
     *
     * A note gained formatting, so it had to change its extension or lie about
     * itself: this app decides what an item *is* from its filename. Markdown is
     * still text — it opens in any editor, which is the property `.txt` was chosen
     * for in the first place — and [LEGACY_EXTENSION] is still recognised so a note
     * written before this keeps opening.
     */
    const val EXTENSION = ".md"

    /** What notes were named before `notes-as-documents`. Read, never written. */
    const val LEGACY_EXTENSION = ".txt"

    /**
     * How much of a note the index keeps so the grid can draw a tile.
     *
     * 160 characters is about three lines at the tile's width. It is the whole
     * reason the text can leave the index: without it a screen of notes would
     * decrypt every one of them to draw itself.
     */
    const val PREVIEW_CHARS = 160

    /** 16 bytes, the width of a blob ID — see `VaultItem.noteId`. */
    private const val ID_BYTES = 16

    /**
     * [body]'s encoded size.
     *
     * **`toByteArray(UTF_8).size`, never `String.length`.** A body of emoji or CJK
     * is two to four bytes a character, so a cap counted in characters is a cap
     * that moves depending on the script the user writes in — generous in English
     * and wrong everywhere else. The editor counts the same way the index stores.
     */
    fun sizeOf(body: String): Int = body.toByteArray(Charsets.UTF_8).size

    /** Whether [body] fits. The editor asks before saving; [create] asks again. */
    fun fits(body: String): Boolean = sizeOf(body) <= MAX_BODY_BYTES

    /**
     * The tile's excerpt of [body] — `notes-as-documents`.
     *
     * Block markers come off and whitespace collapses, so a note that opens with a
     * heading and a checklist reads as words on a tile rather than as `# ` and
     * `- [ ]`. The stored text is untouched; this is only what the index carries.
     */
    fun previewOf(body: String): String = body
        .lineSequence()
        .map { it.trim().removePrefix("#").removePrefix("#").trim() }
        .map { it.removePrefix("- [ ]").removePrefix("- [x]").removePrefix("-").removePrefix("*") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(PREVIEW_CHARS)

    /**
     * A note's text, read back from its blob — `notes-as-documents`.
     *
     * A row written before version 12 still carries its text in [VaultItem.body] and
     * is returned from there, so a note opens whether or not the migration has run.
     */
    fun read(store: BlobStore, item: VaultItem, vmk: ByteArray): String {
        item.body?.let { return it }
        if (item.blobIds.isEmpty()) return ""

        val bytes = ByteArray(item.plaintextLength.toInt())
        store.reader(item.blobIds, vmk, item.plaintextLength).use { r ->
            val buf = ByteArray(BlobStore.CHUNK)
            for (i in 0 until r.chunkCount) {
                val n = r.readChunk(i, buf)
                buf.copyInto(bytes, (i * BlobStore.CHUNK).toInt(), 0, n)
            }
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * A note row from [title] and [body], with the text sealed into its own blob —
     * `notes-as-documents`. Null when either is unusable.
     *
     * Null when the body is over [MAX_BODY_BYTES], and null when the title does not
     * survive [VaultRename.normalise] — a title is a filename stem, and one that a
     * later rename would reject has no business being created in the first place.
     *
     * **The text goes through `writeItem`, the path a photograph uses.** It splits
     * across blobs, pads to a bucket and seals per chunk exactly as any other content
     * does, which is what stops a note being a shape the store has to know about.
     *
     * `createdAt` is **null**, not the current time: P1-6 forbids presenting an
     * import or creation time as a capture date, and a note was never captured.
     * `importedAt` is when it arrived, which is what the grid orders on by default.
     *
     * **[folderId] is where it is written**, and it defaults to the vault root. A note
     * begun inside a folder belongs in that folder, for the reason a photograph taken
     * inside one does — the grid the user is looking at is the place they are adding to.
     */
    fun create(
        store: BlobStore,
        vmk: ByteArray,
        title: String,
        body: String,
        now: Long = System.currentTimeMillis(),
        folderId: ByteArray? = null,
    ): VaultItem? {
        if (row(title, body, now, folderId) == null) return null
        val written = store.writeItem(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)), vmk)
        return row(title, body, now, folderId, written.blobIds, written.plaintextLength)
    }

    /**
     * The row [create] builds, without the blob — and therefore **without a device**.
     *
     * This file's opening claim is that the two rules worth getting wrong here, the
     * cap and the naming, need no emulator to check. `notes-as-documents` gave
     * [create] a `BlobStore`, which would have dragged every one of those checks onto
     * a device; the validation stayed here instead and the sealing moved up. The blob
     * round trip is a device test because it genuinely is one.
     */
    fun row(
        title: String,
        body: String,
        now: Long = System.currentTimeMillis(),
        folderId: ByteArray? = null,
        blobIds: List<ByteArray> = emptyList(),
        plaintextLength: Long = sizeOf(body).toLong(),
    ): VaultItem? {
        if (!fits(body)) return null
        val filename = VaultRename.normalise(title, EXTENSION) ?: return null

        return VaultItem(
            blobIds = blobIds,
            plaintextLength = plaintextLength,
            thumb = null,
            filename = filename,
            album = null,
            createdAt = null,
            importedAt = now,
            tags = emptyList(),
            // The two facts that make it a note: a note ID, and a preview standing in
            // for text that is no longer in the row.
            noteId = newId(),
            folderId = folderId,
            preview = previewOf(body),
        )
    }

    /**
     * [item] renamed and rewritten into **fresh blobs**, or null when the new title
     * or body is unusable.
     *
     * New blobs and not the old ones: `writeItem` skips a blob whose file already
     * exists, because that is how an interrupted import resumes — handing it the
     * existing IDs would write nothing at all. The old blobs are deleted by [update]
     * once the index commit naming the new ones has succeeded, so a failure in
     * between orphans a blob rather than losing the note.
     *
     * [VaultItem.noteId] is carried through: an edit must not change which item this is.
     */
    fun edit(
        store: BlobStore,
        vmk: ByteArray,
        item: VaultItem,
        title: String,
        body: String,
    ): VaultItem? {
        if (editRow(item, title, body) == null) return null
        val written = store.writeItem(ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)), vmk)
        return editRow(item, title, body, written.blobIds, written.plaintextLength)
    }

    /** The row [edit] builds, without the blob — [row]'s split, for [row]'s reason. */
    fun editRow(
        item: VaultItem,
        title: String,
        body: String,
        blobIds: List<ByteArray> = emptyList(),
        plaintextLength: Long = sizeOf(body).toLong(),
    ): VaultItem? {
        if (!fits(body)) return null
        val filename = VaultRename.normalise(title, EXTENSION) ?: return null
        return item
            .withFilename(filename)
            .withNoteBlob(blobIds, previewOf(body), plaintextLength)
            // `destination-settings-and-slideshow` D2. A title or body saved is an edit;
            // colour, pin and folder go through their own `with…` and leave this alone.
            .withEditedAt(System.currentTimeMillis())
    }

    /**
     * [item]'s title — its filename without the ending the user cannot edit.
     *
     * `split` takes whatever extension is there, so a note written as `.txt` before
     * `notes-as-documents` still gives up its title.
     */
    fun titleOf(item: VaultItem): String = VaultRename.split(item.filename).first

    /**
     * Adds a new note to the index — P2-21. Returns the index, or null when
     * [create] refused the title or the body.
     *
     * Through [MetadataDb.commit] and never a bare load/save: the index is one blob
     * rewritten whole, and `IndexCommitScanTest` fails the build for the three-step
     * version. This is the shape [VaultRename.rename] uses, with **no log line** —
     * that file logs a count, and here a count would be the only fact there is.
     *
     * Named `add` and not `save`: `IndexCommitScanTest` reads a file containing both
     * `MetadataDb.load(` and `save(` as the P2-9 unguarded three-liner, and
     * `MainActivity` contains the first. The test is blunt on purpose and it is
     * right to be — renaming was cheaper than an exemption, which is the trade
     * `itemId` already made against `KeyMaterialScanTest`.
     */
    fun add(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        title: String,
        body: String,
        /** The folder it is being written into — see [create]. Null is the vault root. */
        folderId: ByteArray? = null,
    ): MetadataDb? {
        val note = create(store, vmk, title, body, folderId = folderId) ?: return null
        return MetadataDb.commit(store, indexId, vmk) { db ->
            db.items += note
            db
        }
    }

    /**
     * Rewrites an existing note in place — P2-21. Returns the index, or null when
     * [edit] refused the title or the body.
     *
     * Matched by `itemId`, which for a note is its [VaultItem.noteId] and therefore
     * survives the edit. An item gone by another path while the editor sat open is
     * skipped rather than treated as an error — [VaultRename.rename]'s rule.
     */
    fun update(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        item: VaultItem,
        title: String,
        body: String,
    ): MetadataDb? {
        val edited = edit(store, vmk, item, title, body) ?: return null
        var replaced = false
        val db = MetadataDb.commit(store, indexId, vmk) { db ->
            val i = db.items.indexOfFirst { it.itemId == item.itemId }
            if (i >= 0) {
                db.items[i] = edited
                replaced = true
            }
            db
        }
        // **After the commit, never before.** The index now names the new blobs, so
        // the old ones are unreachable and deleting them cannot lose the note. The
        // other order would leave a row pointing at a file that is gone if the commit
        // failed. An item gone by another path while the editor sat open is skipped
        // rather than treated as an error — `VaultRename.rename`'s rule — and its new
        // blobs are the ones dropped instead.
        val stale = if (replaced) item.blobIds else edited.blobIds
        stale.forEach { store.delete(it) }
        return db
    }

    /**
     * 16 bytes from [SecureRandom], the same source and the same width blob IDs use,
     * so `itemId`'s hex is the shape and the collision odds it has always been.
     */
    private fun newId(): ByteArray = ByteArray(ID_BYTES).also { SecureRandom().nextBytes(it) }
}
