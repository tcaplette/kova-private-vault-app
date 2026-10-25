package com.app.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/**
 * Decoded thumbnails, in memory only.
 *
 * No image-loading library. Coil's network module pulls OkHttp and with it
 * `INTERNET`, which I1 forbids outright; Glide's disk cache is an I3 violation
 * that would have to be configured off. Neither is loading anything from
 * anywhere — the app decrypts its own bytes — so what is actually needed is
 * `decodeByteArray` and a bounded map.
 *
 * Memory only is not a limitation here, it is the requirement: a decoded
 * thumbnail on the heap is no different from the one already on screen, but one
 * written to a disk cache is plaintext user content on disk (I3).
 */
object ThumbnailCache {

    private val cache = object : LruCache<String, Bitmap>(
        // Kilobytes, matching sizeOf below.
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt()
    ) {
        // `id`, not `key`: in this codebase "key" means key material, and P1-10's
        // scan for String-typed secrets is right to flag anything else wearing
        // the name.
        override fun sizeOf(id: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /**
     * The cache id for one thumbnail — P2-10.
     *
     * A pack blob holds many thumbnails, so the pack ID alone names all of them
     * at once; the offset is what makes it one. This lives here rather than at
     * the call site because P2-10 added a second caller — eviction on delete —
     * and an eviction that builds the id differently from the read silently
     * misses, which is worse than not evicting at all.
     */
    private fun idOf(ref: ThumbRef) =
        (ref.packBlobId + ref.offset.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun Int.toByteArray() = byteArrayOf(
        (this shr 24).toByte(), (this shr 16).toByte(), (this shr 8).toByte(), toByte()
    )

    /** Decoded bitmap for [ref], decoding through [bytes] on a miss. */
    fun get(ref: ThumbRef, bytes: () -> ByteArray?): Bitmap? {
        val id = idOf(ref)
        cache.get(id)?.let { return it }

        val encoded = bytes() ?: return null
        val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size) ?: return null
        cache.put(id, bitmap)
        return bitmap
    }

    /**
     * Drops one thumbnail — P2-10's delete.
     *
     * Its bytes are still in the pack (§8 defers compaction), but the decoded
     * copy is decrypted user content held after the user asked for the item to be
     * gone. The LRU would get to it eventually; eventually is not the promise
     * this app makes.
     */
    fun evict(ref: ThumbRef) {
        cache.remove(idOf(ref))
    }

    /** Dropped on lock, so a locked vault holds no decrypted pixels. */
    fun clear() = cache.evictAll()

    /**
     * For tests: the resident size in **kilobytes**, per `sizeOf` above — not an
     * entry count. Zero means nothing decoded is held.
     */
    fun size(): Int = cache.size()
}
