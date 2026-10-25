package com.app.vault

import android.content.Context
import android.media.ExifInterface
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * "Unvault Everything", without WorkManager — P1-13.
 *
 * Separate from [ExportWorker] for the same reason `ImportPipeline` is separate
 * from `ImportWorker`: the interesting behaviour is per item, and a test should
 * be able to drive it against a fake destination without a worker, a
 * notification, or a device gallery.
 *
 * **The order below is the whole of I6.** Decrypt to the destination, read the
 * file *back*, hash-compare, and only then delete the encrypted copy. Hashing the
 * stream on the way out proves the decrypt; it does not prove the write. A
 * truncated write, a full disk, or a provider that quietly dropped bytes all
 * produce a correct stream and a wrong file.
 *
 * **The index is the resume state.** A verified item is removed from the index as
 * part of exporting it, so whatever is still in the index has not been exported.
 * Nothing else records progress, and nothing needs to.
 */
class Unvaulter(
    private val store: BlobStore,
    private val destination: ExportDestination,
    private val manifest: ExportManifest,
    private val context: Context? = null,
) {

    class Progress(val exported: Int, val failed: Int, val total: Int)

    /**
     * Empties the vault into the destination.
     *
     * [isStopped] is honoured between items, never inside one: a half-written
     * file is discarded by the item that was writing it, so stopping can only
     * happen at a point where the vault and the destination agree.
     */
    fun exportAll(
        indexId: ByteArray,
        vmk: ByteArray,
        /** `exif-strip-choice`. What the strip removes, or null for an export that preserves. */
        strip: Set<PhotoDetail>? = null,
        /**
         * P2-11. The `itemId`s to export, or null for the whole vault.
         *
         * A filter on the walk and nothing else: the manifest, the destination,
         * the metadata policy, I6's verify-then-delete and the resume behaviour
         * are the same code either way. Resume still works for a scoped run for
         * the same reason it always did — the index is the progress record, so an
         * exported row is gone and what remains of the selection is what is left
         * to do.
         */
        selection: Set<String>? = null,
        isStopped: () -> Boolean = { false },
        /**
         * `intruder-captures-at-home` D9. Run inside the commit that removes each verified item,
         * so the capture store's `0x47` lands in the same index write as the removal.
         */
        onRemoved: (MetadataDb, VaultItem) -> Unit = { _, _ -> },
        onProgress: (Progress) -> Unit = {},
    ): Progress {
        // P2-12: a trashed item is not exported, whatever the selection says. It
        // is still in the vault and still restorable, so carrying it out as
        // plaintext would export something the user asked to delete.
        // `remove-note-attachments`: a note owns nothing, so there is no second rule
        // about what leaves with it. **One vault item is one exported file, for every
        // kind without exception** — a note was the only thing that ever broke that,
        // and it broke it for attachments.
        fun chosen(item: VaultItem) =
            item.deletedAt == null && (selection == null || item.itemId in selection)

        val total = MetadataDb.load(store, indexId, vmk).items.count(::chosen)
        val skip = mutableSetOf<String>()
        var exported = 0
        var failed = 0

        while (!isStopped()) {
            // Re-read each time: the index changed on the previous iteration, and
            // it *is* the progress record. The first item not already failed this
            // run is the next one out.
            val item = MetadataDb.commit(store, indexId, vmk) { db ->
                db.items.firstOrNull { it.itemId !in skip && chosen(it) }
            } ?: break

            if (exportOne(item, vmk, strip)) {
                // Every blob of the item, not the first. Deleting one of a
                // split item's blobs would leave the rest orphaned in the pool.
                // P2-9: the removal is a commit — the export loop and a resumed
                // import can both be writing the index.
                item.blobIds.forEach { store.delete(it) }
                MetadataDb.commit(store, indexId, vmk) { db ->
                    db.items.removeAll { it.itemId == item.itemId }
                    onRemoved(db, item)
                }
                exported++
            } else {
                // Blob and row both stay. Skipped for the rest of this run so the
                // loop cannot spin on the same failure.
                skip += item.itemId
                failed++
            }
            onProgress(Progress(exported, failed, total))
        }

        return Progress(exported, failed, total)
    }

    /**
     * One item out. Returns whether it verified; never throws for an ordinary failure.
     *
     * **One item, one file** — `remove-note-attachments`. A note used to take the items
     * it owned with it, which made it the only kind that produced several files and gave
     * this path a second shape. It has one shape now.
     */
    fun exportOne(
        item: VaultItem,
        vmk: ByteArray,
        /** `exif-strip-choice`. What the strip removes, or null for an export that preserves. */
        strip: Set<PhotoDetail>?,
    ): Boolean {
        if (item.noteId != null) return exportNote(item, vmk)
        val mime = ExportDestination.mimeOf(item.filename)
        var entry: ExportEntry? = null

        try {
            entry = destination.create(item.filename, mime, item.createdAt)

            // Straight to the destination. Nothing is staged to cacheDir or a temp
            // file — I3 carves out the destination and nothing else.
            // P2-21. One branch, and everything after it is the code that already
            // ran: read the landed file back, compare hash and length, discard on
            // mismatch, write the manifest line. A note is not a second export path
            // and does not get a lower bar for being text.
            // `exif-strip-choice`: and that now covers the photograph's own edits. They are
            // made here, in the one pass that writes the file — `ExifInterface.saveAttributes()`
            // made them afterwards by copying the whole file into the app's cache.
            val written = destination.openOutput(entry).use { out ->
                if (item.body != null) writeBody(item.body, out) else writeItem(item, vmk, mime, strip, out)
            }
            val landed = destination.openInput(entry).use { hashOf(it) }

            // Two checks, because an edited file is not the bytes the vault holds: the source
            // was read whole, and authenticated, at its recorded length; and the destination
            // holds exactly what was written.
            if (written.hash != landed || written.source != item.plaintextLength) {
                destination.discard(entry)
                manifest.failed(item.filename, "did not round-trip")
                return false
            }

            destination.finish(entry)
            manifest.succeeded(entry.name, written.bytes, written.hash, written.edited, written.stampSkipped)
            return true
        } catch (e: Exception) {
            entry?.let { destination.discard(it) }
            manifest.failed(item.filename, e.message ?: e.javaClass.simpleName)
            Log.w(TAG, "export failed for ${item.filename}", e)
            return false
        }
    }

    /**
     * A note, as one Markdown file — `remove-note-attachments`.
     *
     * ***This used to write several files and it is the only thing in the app that ever
     * did.*** A note carried attachments, so exporting one wrote `<stem>.md` plus
     * `<stem>-1.jpg`, `<stem>-2.jpg` beside it, and rewrote the references inside the
     * text to name them — because [ExportDestination] is flat and a `MediaStoreDestination`
     * has no folder a person ever sees.
     *
     * All of that is gone with the attachments. One vault item is one exported file, for
     * every kind without exception, which is what the export path promised everywhere
     * else. It still goes through [writeVerified] like anything else: the same hash and
     * length check, the same manifest line, the same discard on mismatch.
     */
    private fun exportNote(item: VaultItem, vmk: ByteArray): Boolean {
        val stem = item.filename.substringBeforeLast('.')
        // **The text goes out exactly as it is stored.** It used to be rewritten, to
        // re-point `vault:` references at the attachment files written beside it; there
        // are no attachment files and no references to re-point. A note's exported bytes
        // are now the note's bytes, which is what every other item already promised.
        val text = VaultNote.read(store, item, vmk)

        return writeVerified(
            name = "$stem${VaultNote.EXTENSION}",
            mime = ExportDestination.mimeOf("$stem${VaultNote.EXTENSION}"),
            createdAt = item.createdAt,
            expected = VaultNote.sizeOf(text).toLong(),
            produce = { out -> writeBody(text, out) },
        )
    }

    /**
     * One file to the destination, verified and recorded.
     *
     * `exportOne`'s body, lifted so a note's several files each get the same
     * treatment rather than a second, laxer path. Same hash check against the same
     * expected length, same manifest line, same discard on mismatch.
     */
    private fun writeVerified(
        name: String,
        mime: String,
        createdAt: Long?,
        expected: Long,
        produce: (OutputStream) -> Written,
    ): Boolean {
        var entry: ExportEntry? = null
        try {
            entry = destination.create(name, mime, createdAt)
            val written = destination.openOutput(entry).use(produce)
            val landed = destination.openInput(entry).use { hashOf(it) }

            if (written.hash != landed || written.bytes != expected) {
                destination.discard(entry)
                manifest.failed(name, "did not round-trip")
                return false
            }
            destination.finish(entry)
            manifest.succeeded(entry.name, written.bytes, written.hash, false)
            return true
        } catch (e: Exception) {
            entry?.let { destination.discard(it) }
            manifest.failed(name, e.message ?: e.javaClass.simpleName)
            Log.w(TAG, "export failed for $name", e)
            return false
        }
    }

    private class Written(
        val bytes: Long,
        val hash: String,
        /** Plaintext read from the vault, which is what the recorded length measures. */
        val source: Long = bytes,
        val edited: Boolean = false,
        val stampSkipped: Boolean = false,
    )

    /**
     * An image out, with whatever edits it gets made as it is written — `exif-strip-choice`.
     *
     * JPEG goes through the strip, which also carries the two additions; PNG and WebP get the
     * additions alone, because the strip reaches no further than JPEG and says so; everything
     * else is written as it is stored. When nothing needs editing, nothing is engaged and the
     * file is byte-identical to its import.
     */
    private fun writeItem(
        item: VaultItem,
        vmk: ByteArray,
        mime: String,
        strip: Set<PhotoDetail>?,
        out: OutputStream,
    ): Written {
        val additions = ExifPolicy.Additions(
            // A strip that removes the date does not then write one back.
            dateStamp = item.createdAt
                ?.takeIf { strip == null || PhotoDetail.DATE !in strip }
                ?.let { java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(it)) },
            orientation = item.rotation.takeIf { it != 0 }
                ?.let { turn -> { tag: Int -> composedOrientation(tag, turn) } },
        )
        val format = ExportStamp.formatOf(mime)
        return when {
            ExifPolicy.strippable(mime) && (strip != null || !additions.isEmpty) -> {
                val hashing = HashingStream(out)
                val source = Plaintext(store.reader(item.blobIds, vmk, item.plaintextLength))
                val outcome = source.use { ExifPolicy.copyStripped(it, hashing, strip, additions) }
                hashing.written(source.count, outcome.changed, outcome.additionsSkipped)
            }
            format != null && !additions.isEmpty -> {
                val plan = Plaintext(store.reader(item.blobIds, vmk, item.plaintextLength))
                    .use { ExportStamp.plan(format, it, additions) }
                    ?: return decryptTo(item, vmk, out)
                val hashing = HashingStream(out)
                val source = Plaintext(store.reader(item.blobIds, vmk, item.plaintextLength))
                source.use { ExportStamp.copy(plan, it, hashing) }
                hashing.written(source.count, true, plan.additionsSkipped)
            }
            else -> decryptTo(item, vmk, out)
        }
    }

    /** The item's plaintext as a stream, counting as it goes. One chunk is the memory bound. */
    private class Plaintext(private val reader: BlobStore.Reader) : InputStream() {
        private val buf = ByteArray(BlobStore.CHUNK)
        private var chunk = 0L
        private var length = 0
        private var offset = 0
        var count = 0L
            private set

        private fun fill(): Boolean {
            if (offset < length) return true
            if (chunk >= reader.chunkCount) return false
            length = reader.readChunk(chunk, buf)
            offset = 0
            chunk++
            return length > 0
        }

        override fun read(): Int {
            if (!fill()) return -1
            count++
            return buf[offset++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, length - offset)
            buf.copyInto(b, off, offset, offset + n)
            offset += n
            count += n
            return n
        }

        override fun close() = reader.close()
    }

    /** Hashes what reaches the destination. Closing is the destination's business, not this. */
    private class HashingStream(private val out: OutputStream) : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var bytes = 0L

        override fun write(b: Int) {
            out.write(b)
            digest.update(b.toByte())
            bytes++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            digest.update(b, off, len)
            bytes += len
        }

        override fun flush() = out.flush()

        fun written(source: Long, edited: Boolean, stampSkipped: Boolean): Written {
            out.flush()
            return Written(bytes, digest.digest().hex(), source, edited, stampSkipped)
        }
    }

    /**
     * A note's body to the destination — P2-21.
     *
     * Hashes as it writes and returns the same [Written] `decryptTo` does, so the
     * verification that follows is not a special case: it is the same check against
     * the same `plaintextLength`, which a note row carries as its body's encoded
     * size for exactly this reason.
     *
     * **Not staged as a blob first.** Putting note plaintext through the §5 envelope
     * for the sole purpose of reading it straight back out buys nothing, and I3's
     * carve-out is the destination, not a staging blob.
     */
    private fun writeBody(body: String, out: OutputStream): Written {
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write(bytes)
        out.flush()
        return Written(bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).hex())
    }

    private fun decryptTo(item: VaultItem, vmk: ByteArray, out: OutputStream): Written {
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L

        store.reader(item.blobIds, vmk, item.plaintextLength).use { r ->
            val buf = ByteArray(BlobStore.CHUNK)
            for (i in 0 until r.chunkCount) {
                val n = r.readChunk(i, buf)
                out.write(buf, 0, n)
                digest.update(buf, 0, n)
                bytes += n
            }
        }
        out.flush()
        return Written(bytes, digest.digest().hex())
    }

    private fun hashOf(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BlobStore.CHUNK)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().hex()
    }

    companion object {

        private const val TAG = "VaultExport"

        /**
         * The EXIF orientation an export writes: the tag already in the file, turned
         * by the rotation stored in the index — `viewer-chrome-and-editor`.
         *
         * **Internal so it can be tested directly.** Everything else on this path —
         * opening the descriptor, `setAttribute`, `saveAttributes` — is the same
         * plumbing the date stamp above already runs through and already has
         * coverage; the arithmetic is the part that can be silently wrong, and a
         * wrong answer here is a photograph that leaves the vault on its side.
         *
         * A mirrored input tag (2, 4, 5, 7) reads as its rotation and loses the flip,
         * which is `Thumbnailer.exifDegrees`' rule on the reading side: the app
         * cannot produce one and does not display one either, so an export that
         * claimed to preserve it would be claiming more than the app ever showed.
         */
        internal fun composedOrientation(tag: Int, rotation: Int): Int {
            val degrees = when (tag) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            return when (((degrees + rotation) % 360 + 360) % 360) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
        }
    }
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
