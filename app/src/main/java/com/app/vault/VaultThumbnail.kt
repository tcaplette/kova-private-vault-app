package com.app.vault

import android.util.Log

/**
 * Making the frame on screen a video's thumbnail — `video-frame-thumbnail`.
 *
 * [VaultRotate]'s shape, and for its reasons: one item, through `MetadataDb.commit`
 * rather than a bare load/save, an item gone by another path skipped rather than
 * treated as an error, and a log line that counts and never names.
 *
 * **Written like every thumbnail.** Decoded in memory from the encrypted video,
 * scaled and encoded exactly as import does, staged into the open pack and flushed,
 * then the reference repointed — inside the one commit that saves the index. No
 * plaintext image touches a file at any point (I3).
 *
 * **The old record stays in its pack, unreferenced**, as a deleted item's does —
 * `FORMAT.md` §8's deferred compaction covers both. Its decoded copy leaves
 * [ThumbnailCache] for P2-10's reason: decrypted content the user has replaced is not
 * held until an LRU gets to it.
 */
object VaultThumbnail {

    private const val TAG = "VaultThumbnail"

    /**
     * Makes the frame at [positionMs] into [item]'s thumbnail. Returns whether one was
     * set: false when no frame could be decoded, or when the item is gone.
     */
    fun setFromFrame(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        item: VaultItem,
        positionMs: Long,
    ): Boolean {
        // Outside the commit: decoding is the slow part and touches no index, which is
        // `MetadataDb.commit`'s rule for what belongs inside it.
        val webp = store.reader(item.blobIds, vmk, item.plaintextLength).let { reader ->
            Thumbnailer.BlobDataSource(reader, item.plaintextLength).use { source ->
                Thumbnailer.fromVideoAt(source, positionMs * 1000)
            }
        }
        if (webp == null) {
            Log.i(TAG, "set 0 thumbnail(s): no frame")
            return false
        }

        var replaced: ThumbRef? = null
        val set = MetadataDb.commit(store, indexId, vmk) { db ->
            val i = db.items.indexOfFirst { it.itemId == item.itemId }
            // Gone by another path while the viewer sat open: skipped, not an error.
            if (i < 0) return@commit false
            val current = db.items[i]
            // Staged and flushed before the row is repointed. The flush rewrites the
            // open pack with its existing bytes plus this record, so the old record is
            // still there under its old reference.
            val thumbs = ThumbnailStore(store)
            val ref = thumbs.stage(db, webp)
            thumbs.flush(db, vmk)
            replaced = current.thumb
            db.items[i] = current.withThumb(ref, current.durationMs)
            true
        }

        // After the commit, so a failed save leaves the old copy where the grid can
        // still find it.
        if (set) replaced?.let(ThumbnailCache::evict)
        Log.i(TAG, "set ${if (set) 1 else 0} thumbnail(s)")
        return set
    }
}
