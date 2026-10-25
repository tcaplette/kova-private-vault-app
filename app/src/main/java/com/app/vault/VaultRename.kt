package com.app.vault

import android.util.Log

/**
 * A new name for one item — P2-14.
 *
 * The filename is the only field the user could read and could not write. It is
 * set once, by whatever handed the item over — `MediaImport` takes `source.name`,
 * `CaptureIngest` synthesises one — and it is what leaves: `Unvaulter` passes it
 * to `ExportDestination.create`, and `DeleteConfirm` puts it in front of the user
 * as the thing about to be destroyed.
 *
 * **No format change.** `filename` has been serialised since P1-5 and is non-null
 * on every readable index version, so there is no new field and no version bump.
 * `FORMAT.md` §7 already makes filenames the index's own concern.
 *
 * **The extension is not editable, and that is the decision.** It is the app's
 * only type information — [Thumbnails] reads it to choose a video path,
 * `VaultGrid` to draw the play badge, `VaultViewer` to choose a page, and
 * [ExportDestination.mimeOf] to choose a MediaStore collection. Editable, a rename
 * makes the viewer hand a JPEG to ExoPlayer and files a photograph under
 * `Movies/Vault`, with the stored bytes unchanged and four readers newly wrong.
 * The cost is that a file which arrived with a wrong extension keeps it;
 * correcting that means a MIME stored at import, which is a format change and is
 * not this.
 *
 * **A name is content.** The rename path enqueues no work, so nothing can reach
 * `work.db`, and the logging here counts and never names. The older lines in
 * [Thumbnails] and `Unvaulter` that do interpolate a filename on a failure path
 * are untouched by P2-14 and are not covered by that claim.
 */
object VaultRename {

    private const val TAG = "VaultRename"

    /**
     * The longest assembled `stem + extension` accepted — the limit ext4, FAT and
     * every SAF provider agree on. A display budget it is not: the bar and the
     * confirmation truncate at their own widths, which is where truncation
     * belongs.
     */
    const val MAX_NAME = 255

    /**
     * What a stem may not contain.
     *
     * A path separator would be mangled differently by `MediaStoreDestination` and
     * by the SAF provider, and NUL terminates the name in every layer underneath
     * both. Refused rather than stripped — see [normalise].
     */
    private val FORBIDDEN = charArrayOf('/', '\\', '\u0000')

    /**
     * [filename] as its stem and its extension, the extension carrying its dot or
     * empty when there is none.
     *
     * Split at the last dot that is **not the first character** — the rule
     * [ExportDestination.suffixed] already applies, stated once so the two cannot
     * disagree about the same name. So `.gitignore` is all stem rather than an
     * empty name with a nine-character extension, and `photo` is all stem.
     */
    fun split(filename: String): Pair<String, String> {
        val dot = filename.lastIndexOf('.')
        return if (dot <= 0) filename to ""
        else filename.substring(0, dot) to filename.substring(dot)
    }

    /**
     * The assembled filename for [stem] under [extension], or null when the stem
     * cannot be one.
     *
     * Null is a refusal the caller reports, never a silent repair —
     * [VaultFolders.normalise]'s contract, for its reason: a repaired name means
     * the item is called something the user did not type, and they find out at
     * export time. A separator is refused rather than stripped, because a stripped
     * one produces a name that looks like the one typed and is not.
     */
    fun normalise(stem: String, extension: String): String? {
        val trimmed = stem.trim()
        if (trimmed.isEmpty()) return null
        // "." and ".." are not names any destination will take, and neither is any
        // other run of dots on its own.
        if (trimmed.all { it == '.' }) return null
        if (trimmed.any { it in FORBIDDEN }) return null
        val assembled = trimmed + extension
        if (assembled.length > MAX_NAME) return null
        return assembled
    }

    /**
     * Gives [item] the name [filename] and returns the committed index.
     *
     * One item and not a list: a rename applies to the thing on screen, and the
     * surface that offers it shows one item by construction.
     *
     * **One `MetadataDb.commit`**, the shape [VaultFolders.move] and [VaultDeletion]
     * use. Nothing but the filename changes: not the blobs, not the plaintext
     * length, not the `ThumbRef` — the extension is preserved, so the thumbnail is
     * still the right kind of thumbnail and regenerating it would be work for no
     * difference — not the folder, the dates, the tags, `deletable` or `deletedAt`.
     *
     * The item's identity is its first blob's ID in hex, so the pager, the
     * selection and every UI key survive a rename unchanged.
     */
    fun rename(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        item: VaultItem,
        filename: String,
    ): MetadataDb {
        val db = MetadataDb.commit(store, indexId, vmk) { db ->
            val i = db.items.indexOfFirst { it.itemId == item.itemId }
            // Gone by another path while the screen sat open: skipped, not an error.
            if (i >= 0) db.items[i] = db.items[i].withFilename(filename)
            db
        }

        // A count, never the name. A filename is frequently the most descriptive
        // thing in a vault.
        Log.i(TAG, "renamed 1 item(s)")
        return db
    }
}
