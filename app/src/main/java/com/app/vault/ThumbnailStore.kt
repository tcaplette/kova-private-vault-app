package com.app.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Thumbnail packs — `FORMAT.md` §8.
 *
 * A thumbnail is a `[u32 length][WebP]` record inside a pack, and a pack is an
 * ordinary pooled blob sealed under the **thumbnail** key. Packing exists
 * because §5's smallest bucket is 64 KiB while a thumbnail is ~20 kB: one blob
 * each would spend 656 MB on a 10 000-item library instead of ~210 MB.
 *
 * Writes are staged and flushed at most once per import batch, the way P1-5
 * batches the index. A blob is written whole, so appending means writing the
 * open pack out again; batching is what bounds that to one write of at most
 * [PACK_CAP] per batch rather than one per item.
 *
 * **Copy-on-write, never in place — `video-frame-thumbnail`.** Appending writes the
 * open pack's bytes plus the new records to a *successor* under a new blob ID, and the
 * successor takes the open pack's place in the index within the same commit. The
 * predecessor is deleted only after that index is saved ([MetadataDb.retired]).
 *
 * That is what makes a flush survive process death. Rewriting the open pack in place
 * — what this did until then — left the index on disk recording the old length for a
 * blob that now held more, and `BlobStore.Reader` sizes a pack's final chunk from that
 * length: every record in the last chunk failed authentication. Now a death before the
 * save leaves the index naming a predecessor nobody touched, and the successor is left
 * as an untracked blob — nothing records it (`blob-ownership-by-record`), which is
 * correct rather than a gap (`FORMAT.md` §6). Nothing already stored is lost, and the
 * new thumbnails regenerate on next view — thumbnails are derived data.
 */
class ThumbnailStore(private val store: BlobStore) {

    companion object {
        /** §8. Bounds the cost of one write. Tunable before first release. */
        const val PACK_CAP = 4 * 1024 * 1024

        /** The `u32` length that precedes each record. */
        const val RECORD_HEADER = 4

        private val CONTEXT = VaultKeys.BlobContext.THUMBNAIL
    }

    private class Pending(
        val pack: ThumbPack,
        /** The pack this one succeeds and begins with, or null for a pack opened empty. */
        val replaces: ThumbPack?,
    ) {
        val records = mutableListOf<ByteArray>()
    }

    private val rng = SecureRandom()
    private val pending = mutableListOf<Pending>()

    private fun newPack() = ThumbPack(
        ByteArray(BlobStore.BLOB_ID_BYTES).also { rng.nextBytes(it) },
        0
    )

    /**
     * Stages [webp] and returns where it will live. Nothing touches disk until
     * [flush]; the returned reference is valid only once that has happened.
     *
     * The first record staged into an existing open pack opens its successor, so the
     * reference returned already names the blob the record will be written to.
     */
    fun stage(db: MetadataDb, webp: ByteArray): ThumbRef {
        require(webp.isNotEmpty()) { "empty thumbnail" }
        require(RECORD_HEADER + webp.size <= PACK_CAP) { "thumbnail larger than a whole pack" }

        if (pending.isEmpty()) {
            val open = db.thumbPacks.lastOrNull()
            if (open == null) {
                pending.add(Pending(newPack().also { db.thumbPacks.add(it) }, null))
            } else {
                val successor = ThumbPack(newPack().id, open.plaintextLength)
                db.thumbPacks[db.thumbPacks.lastIndex] = successor
                pending.add(Pending(successor, open))
            }
        }

        var current = pending.last()
        if (current.pack.plaintextLength + RECORD_HEADER + webp.size > PACK_CAP) {
            // Close it and open a new one. A closed pack is never written again.
            val next = newPack()
            db.thumbPacks.add(next)
            current = Pending(next, null).also { pending.add(it) }
        }

        val offset = current.pack.plaintextLength
        current.records.add(webp)
        current.pack.plaintextLength += RECORD_HEADER + webp.size
        return ThumbRef(current.pack.id, offset, webp.size)
    }

    /**
     * Writes every staged record, each touched pack whole under its successor's ID,
     * and moves every reference into a replaced pack onto its successor.
     *
     * The caller saves [db] afterwards — [MetadataDb.commit] does. The save is what
     * deletes the predecessors; if it never happens, the index on disk still names
     * them, untouched, and the successors are unreferenced blobs.
     */
    fun flush(db: MetadataDb, vmk: ByteArray) {
        pending.forEach { p ->
            val predecessor = p.replaces
            val existingLength = predecessor?.plaintextLength ?: 0

            val out = ByteArrayOutputStream(p.pack.plaintextLength)
            if (predecessor != null && existingLength > 0) {
                store.reader(predecessor.id, vmk, existingLength.toLong(), CONTEXT).use { r ->
                    out.write(r.readRange(0, existingLength))
                }
            }
            p.records.forEach { webp ->
                out.write(
                    ByteBuffer.allocate(RECORD_HEADER).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(webp.size).array()
                )
                out.write(webp)
            }

            val plain = out.toByteArray()
            check(plain.size == p.pack.plaintextLength) {
                "pack length bookkeeping disagrees with the bytes: " +
                    "${plain.size} vs ${p.pack.plaintextLength}"
            }
            store.writeTo(p.pack.id, ByteArrayInputStream(plain), vmk, false, CONTEXT)
        }

        // Same offsets: a successor begins with its predecessor's bytes.
        val successors = pending.mapNotNull { p -> p.replaces?.let { IdKey(it.id) to p.pack.id } }.toMap()
        if (successors.isNotEmpty()) {
            for (i in db.items.indices) {
                val ref = db.items[i].thumb ?: continue
                val to = successors[IdKey(ref.packBlobId)] ?: continue
                db.items[i] = db.items[i].withThumb(ThumbRef(to, ref.offset, ref.length))
            }
            db.retired += pending.mapNotNull { it.replaces?.id }
        }
        pending.clear()
    }

    /** Reads one thumbnail back. Decrypts only the chunks its range spans. */
    fun read(db: MetadataDb, ref: ThumbRef, vmk: ByteArray): ByteArray {
        val pack = db.thumbPacks.firstOrNull { it.id.contentEquals(ref.packBlobId) }
            ?: throw IllegalStateException("thumbnail references a pack the index does not list")
        return store.reader(pack.id, vmk, pack.plaintextLength.toLong(), CONTEXT).use { r ->
            r.readRange(ref.offset.toLong() + RECORD_HEADER, ref.length)
        }
    }

    /** Total on-disk bytes of every pack. Used to report §8's storage claim. */
    fun onDiskBytes(db: MetadataDb): Long =
        db.thumbPacks.sumOf { store.fileOf(it.id).length() }
}
