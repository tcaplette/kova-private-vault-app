package com.app.vault

import android.content.Context
import java.io.InputStream

/**
 * Memos finished while their vault was locked, made items at its next unlock — `voice-memo`,
 * `destination-settings-and-slideshow` D5.
 *
 * Beside [CaptureIngest] in the unlock's ingest step and built the same way: each pending record is
 * opened with the capture keypair the opening vault derives from its own key, and a record another
 * vault sealed opens to nothing and stays. What opens is read under its memo key and **re-sealed
 * under the vault's key** into an ordinary audio item, in the folder it was started in, named and
 * dated by when it started; then the memo-key blobs and the record go.
 *
 * **Counted apart from captures.** A memo is the owner's own recording, and the tamper digest's
 * capture figure is a count of failed unlocks seen by the camera or microphone.
 */
object MemoIngest {

    /**
     * Ingests every pending memo [vault] sealed, except [skip] — the memo recording right now,
     * whose record is still being written. Returns how many became items.
     */
    fun ingest(context: Context, store: BlobStore, vault: OpenVault, skip: String? = null): Int {
        val kp = VaultKeys.captureKeypair(vault.vmk)
        try {
            val opened = MemoPending.ids(context).filter { it != skip }
                .mapNotNull { id -> MemoPending.open(context, id, kp)?.let { id to it } }
            if (opened.isEmpty()) return 0

            class Ready(val id: String, val record: MemoRecord.Record, val oldBlobs: List<ByteArray>, val item: WrittenItem)
            val ready = mutableListOf<Ready>()
            for ((id, record) in opened) {
                try {
                    val (blobs, length) = recover(store, record)
                    if (length <= 0L) {
                        // Nothing was recorded. Nothing is kept, and nothing is left behind.
                        forget(store, record.blobIds)
                        MemoPending.remove(context, id)
                        continue
                    }
                    ready += Ready(id, record, record.blobIds, reseal(store, blobs, record.memoKey, length, vault.vmk))
                } catch (e: Exception) {
                    // Left pending: a later unlock tries again, and nothing is lost by waiting.
                    android.util.Log.w("MemoIngest", "not ingested: ${e.javaClass.simpleName}")
                } finally {
                    record.memoKey.fill(0)
                }
            }
            if (ready.isEmpty()) return 0

            try {
                MetadataDb.commit(store, vault.metadataBlobId, vault.vmk) { db ->
                    val taken = db.items.mapTo(HashSet()) { it.filename }
                    for (r in ready) {
                        val name = VaultCamera.uniqueFilename(taken, VoiceMemo.filename(r.record.startedAt))
                        taken += name
                        db.items += VaultItem(
                            blobIds = r.item.blobIds,
                            plaintextLength = r.item.plaintextLength,
                            thumb = null,
                            filename = name,
                            album = null,
                            // A folder deleted while the memo waited reads as the home screen,
                            // `VaultItem.folderId`'s rule for a dangling reference.
                            folderId = r.record.folderId?.takeIf { f -> db.folders.any { it.folderId.contentEquals(f) } },
                            createdAt = r.record.startedAt,
                            importedAt = System.currentTimeMillis(),
                            tags = emptyList(),
                        )
                    }
                    db
                }
            } catch (e: Exception) {
                // The index did not take them: the re-sealed copies go, the memos stay pending.
                ready.forEach { forget(store, it.item.blobIds) }
                throw e
            }
            ready.forEach {
                forget(store, it.oldBlobs)
                MemoPending.remove(context, it.id)
            }
            return ready.size
        } finally {
            kp.zero()
        }
    }

    /**
     * The memo's blobs and its length, finishing what a killed recording left. A stopped memo
     * recorded its length; a killed one did not, and its last blob is still a partial — sealed
     * whole chunks, staged. Finishing it seals nothing new: it drops a torn chunk, pads to the
     * bucket and moves it into the pool, and the length is what survived.
     */
    fun recover(store: BlobStore, record: MemoRecord.Record): Pair<List<ByteArray>, Long> {
        val blobs = mutableListOf<ByteArray>()
        var length = 0L
        for (id in record.blobIds) {
            if (!store.fileOf(id).exists()) {
                if (!store.partialFileOf(id).exists()) break   // claimed, never written
                length += store.writeTo(id, java.io.ByteArrayInputStream(ByteArray(0)), record.memoKey, resume = true).plaintextLength
                blobs += id
                break                                          // a partial is always the last
            }
            blobs += id
            length += BlobStore.SPLIT_PLAINTEXT
        }
        if (record.length >= 0L) length = record.length
        return blobs to length
    }

    /**
     * [blobIds], sealed under [memoKey], read and sealed again under [vmk] — a new item's blobs.
     * The audio is copied, never re-encoded, and never exists anywhere but in one chunk's buffer.
     */
    fun reseal(store: BlobStore, blobIds: List<ByteArray>, memoKey: ByteArray, length: Long, vmk: ByteArray): WrittenItem =
        store.reader(blobIds, memoKey, length).use { reader -> store.writeItem(Plain(reader, length), vmk) }

    /** Deletes [blobIds] and any partial staged for them. */
    fun forget(store: BlobStore, blobIds: List<ByteArray>) {
        blobIds.forEach {
            store.discardPartial(it)
            store.delete(it)
        }
    }

    /** A reader's plaintext as a stream, one chunk at a time. Zeroes its buffer at the end. */
    private class Plain(private val reader: BlobStore.Reader, private val length: Long) : InputStream() {
        private val buf = ByteArray(BlobStore.CHUNK)
        private var chunk = 0L
        private var pos = 0
        private var lim = 0

        private fun fill(): Boolean {
            if (pos < lim) return true
            if (chunk * BlobStore.CHUNK >= length) {
                buf.fill(0)
                return false
            }
            lim = reader.readChunk(chunk++, buf)
            pos = 0
            return lim > 0
        }

        override fun read(): Int = if (fill()) buf[pos++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, lim - pos)
            System.arraycopy(buf, pos, b, off, n)
            pos += n
            return n
        }
    }
}
