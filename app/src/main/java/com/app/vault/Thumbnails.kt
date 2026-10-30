package com.app.vault

import android.util.Log

/**
 * Generating a thumbnail for an item that is already stored.
 *
 * Deliberately separate from [ImportPipeline]: generation is per item, but pack
 * writes are per batch (`FORMAT.md` §8), and batching already lives in the
 * callers. This produces bytes; the caller stages and flushes them.
 *
 * The source is always the **encrypted blob**, never the original URI. Reading
 * the blob proves the stored copy is decodable, and it is the only source that
 * still exists after I6 offers to delete the original.
 */
object Thumbnails {

    private const val TAG = "VaultThumb"

    /** Longest run of bytes handed to `BitmapFactory` for a still image. */
    private const val MAX_IMAGE_BYTES = 64 * 1024 * 1024

    /**
     * WebP bytes for [item], or null if nothing decodable came out.
     *
     * Video takes the [android.media.MediaDataSource] path so the extractor
     * never sees a file. Images are pulled into memory — `BitmapFactory` has no
     * incremental API that avoids a file, and a still image that will not fit in
     * memory will not decode either way.
     */
    fun generate(store: BlobStore, item: VaultItem, vmk: ByteArray): Generated = try {
        when (item.kind) {
            // P2-18. Nothing is read at all. A document handed to `BitmapFactory`
            // returns null however many times it is asked, and asking costs a whole
            // decrypt — see [backfill], where it used to cost one per unlock.
            VaultKind.FILE -> Generated(null, null)

            // P2-21. Nothing is read either, and for a stronger reason than FILE's:
            // a note has no blobs to read. `canHaveThumbnail` already keeps it out
            // of `backfill`, so reaching here at all means someone asked directly.
            VaultKind.NOTE -> Generated(null, null)

            // P2-18. The length, and no picture. Same retriever over the same
            // `BlobDataSource` the video path uses to choose a frame; it reads
            // `METADATA_KEY_DURATION` and extracts nothing.
            VaultKind.AUDIO ->
                store.reader(item.blobIds, vmk, item.plaintextLength).let { reader ->
                    Thumbnailer.BlobDataSource(reader, item.plaintextLength).use { source ->
                        Generated(null, Thumbnailer.durationOf(source))
                    }
                }

            VaultKind.VIDEO ->
                store.reader(item.blobIds, vmk, item.plaintextLength).let { reader ->
                    Thumbnailer.BlobDataSource(reader, item.plaintextLength).use { source ->
                        val v = Thumbnailer.fromVideo(source)
                        Generated(v.webp, v.durationMs)
                    }
                }

            VaultKind.IMAGE -> {
            if (item.plaintextLength > MAX_IMAGE_BYTES) {
                // No filename: this runs inside an import, and nothing an import logs names
                // its source (`import-pipeline`).
                Log.w(TAG, "skipping a thumbnail: ${item.plaintextLength} B is too large to decode")
                Generated(null, null)
            } else {
                store.reader(item.blobIds, vmk, item.plaintextLength).use { r ->
                    // An image has no duration, and null is how that is said.
                    Generated(Thumbnailer.fromImage(r.readRange(0, item.plaintextLength.toInt())), null)
                }
            }
            }
        }
    } catch (e: Exception) {
        // A thumbnail is derived data. Failing to make one must not fail the
        // import that already stored the item, and must not leave a partial
        // artifact — there is none to leave, since nothing has been staged yet.
        // The class only — a decoder's message can carry the file's name.
        Log.w(TAG, "thumbnail failed: ${e.javaClass.simpleName}")
        Generated(null, null)
    }

    /**
     * What one generation pass produced — P2-8.
     *
     * The duration rides along because the video path already had it: the
     * retriever that picks a frame reads `METADATA_KEY_DURATION` to decide where
     * to sample. Before P2-8 it was discarded and the grid rendered a hardcoded
     * `--:--` in its place. Null for images, and for a container that declares
     * no length.
     */
    class Generated(val webp: ByteArray?, val durationMs: Long?)

    /**
     * Generates and stages thumbnails for [items], then flushes the packs once.
     *
     * Returns the items with their [ThumbRef] attached. One flush per batch is
     * the point: a blob is rewritten whole, so a flush per item would rewrite up
     * to 4 MiB per photo.
     */
    fun generateAll(
        store: BlobStore,
        thumbs: ThumbnailStore,
        db: MetadataDb,
        items: List<VaultItem>,
        vmk: ByteArray
    ): List<VaultItem> {
        var staged = 0
        val out = items.map { item ->
            if (item.thumb != null) return@map item
            val made = generate(store, item, vmk)
            val webp = made.webp ?: return@map item
            staged++
            item.withThumb(thumbs.stage(db, webp), made.durationMs)
        }
        if (staged > 0) thumbs.flush(db, vmk)
        Log.i(TAG, "staged $staged of ${items.size}, packs now ${db.thumbPacks.size}")
        return out
    }

    /**
     * The lazy path: fills in thumbnails for items already in the index that have
     * none — an import that died before its flush, or a generation that failed
     * and is worth retrying on view.
     *
     * **Filtered by kind since P2-18, and that is not a tidy-up.** `thumb == null`
     * means two different things: *failed, and could succeed next time*, and *this
     * kind can never have one*. This runs at every unlock, so conflating them meant
     * every document in the vault was decrypted **whole**, handed to
     * `BitmapFactory`, and refused — once per unlock, forever, silently. An image
     * whose decode genuinely failed keeps its retry; that is the case this pass was
     * built for and it is not being narrowed.
     */
    fun backfill(store: BlobStore, thumbs: ThumbnailStore, db: MetadataDb, vmk: ByteArray): Int {
        val missing = db.items.filter {
            it.thumb == null && it.kind.canHaveThumbnail
        }
        if (missing.isEmpty()) return 0

        val filled = generateAll(store, thumbs, db, missing, vmk)
        filled.forEach { updated ->
            if (updated.thumb == null) return@forEach
            val i = db.items.indexOfFirst { it.itemId == updated.itemId }
            if (i >= 0) db.items[i] = updated
        }
        return filled.count { it.thumb != null }
    }

}
