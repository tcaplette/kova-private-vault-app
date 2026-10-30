package com.app.vault

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.math.min

/** What [BlobStore.write] produced. The length is not on disk — P1-5 stores it. */
class WrittenBlob(val blobId: ByteArray, val plaintextLength: Long)

/**
 * What [BlobStore.writeItem] produced — an item's blobs, in order, and its total
 * plaintext length.
 *
 * The order lives here and in the index (§7) and nowhere else. No blob names
 * another: a chained layout would mean recovering one blob's plaintext unrolls
 * the rest of the item.
 */
class WrittenItem(val blobIds: List<ByteArray>, val plaintextLength: Long)

/**
 * The pooled blob store — `FORMAT.md` §5 and §6.
 *
 * One flat directory, no per-vault partitioning. A blob is a 16 byte nonce
 * prefix followed by independently sealed 64 KiB chunks, padded with CSPRNG to
 * a bucket size. Chunk *i* decrypts without chunk *i-1*, which is what makes
 * video seek cheap (P0-5 measured 38–66 ms).
 *
 * The store cannot tell a chaff blob from real content, or this vault's blob
 * from another vault's. That is the format working correctly, and it is why
 * nothing here ever chooses a blob to overwrite.
 */
open class BlobStore(
    private val dir: File,
    /**
     * Where partially written blobs are staged — **not** in [dir].
     *
     * §5 claims every file in the pooled store is bucket-padded ciphertext. A
     * partial is none of those things: unpadded, distinctly named, evidence that
     * an import was interrupted, and a lower bound on a real item's true size —
     * the exact quantity the padding exists to destroy. Keeping partials out
     * makes that claim exact rather than true-between-imports, which is one line
     * to check at Gate 1 instead of an argument.
     */
    private val incoming: File = File(dir.parentFile ?: dir, "incoming"),
) {

    companion object {
        const val CHUNK = 64 * 1024                       // plaintext per chunk
        const val BLOB_ID_BYTES = 16
        internal const val PREFIX_BYTES = 16              // nonce prefix
        private const val TAG_BYTES = 16
        const val STRIDE = CHUNK + TAG_BYTES              // 65552, stored per full chunk
        private const val NONCE_BYTES = 24

        private const val KIB = 1024L
        private const val MIB = 1024L * KIB

        /**
         * §5, the whole ladder. There is deliberately nothing above 16 MiB.
         *
         * The large ladder — 32 MiB, 48 MiB, … — was removed by `blob-size-cap`.
         * Chaff is drawn uniformly across these buckets (§6), so a blob above the
         * top rung would sit in a size class chaff never occupies, and Gate 1
         * showed what that costs: a 32 MiB blob alone in its bucket is *provably*
         * real content, and §6's "chaff or another vault's" ambiguity — the whole
         * deniability argument — does not reach it.
         */
        val SMALL_BUCKETS = listOf(
            64 * KIB, 128 * KIB, 256 * KIB, 512 * KIB,
            1 * MIB, 2 * MIB, 4 * MIB, 8 * MIB, 16 * MIB
        )

        /** No blob file may exceed this. The cap is the ladder's top rung. */
        val MAX_BUCKET = SMALL_BUCKETS.last()

        /**
         * Whole chunks that fit a [MAX_BUCKET] file: `16 + k * 65552 <= 16 MiB`.
         *
         * Derived, never typed in. Change [CHUNK], [STRIDE] or the top bucket and
         * this follows; `BlobSplitTest` asserts the arithmetic so a change that
         * would push a blob over the cap fails the build instead of the gate.
         */
        const val CHUNKS_PER_BLOB: Long = (16L * 1024 * 1024 - PREFIX_BYTES) / STRIDE

        /** Plaintext per blob — what an item is split on. */
        const val SPLIT_PLAINTEXT: Long = CHUNKS_PER_BLOB * CHUNK

        fun bucketFor(size: Long): Long =
            SMALL_BUCKETS.firstOrNull { it >= size }
                ?: throw IllegalArgumentException(
                    "blob of $size B exceeds the $MAX_BUCKET B cap; an item this large must be " +
                        "split across blobs (FORMAT.md 5)"
                )

        fun offsetOfChunk(index: Long): Long = PREFIX_BYTES + index * STRIDE

        /**
         * Staging files a [writeTo] in this process has open, by path, with how many.
         * Process-wide because every store on one directory shares its `incoming/`, and
         * in memory because after a process death nothing is writing.
         */
        private val writing = HashMap<String, Int>()
    }

    private val sodium = Sodium.lazy
    private val rng = SecureRandom()

    init {
        dir.mkdirs()
        incoming.mkdirs()
    }

    fun fileOf(blobId: ByteArray): File =
        File(dir, blobId.joinToString("") { "%02x".format(it) })

    /**
     * Deletes every file in the store and in [incoming] — `VaultLifecycle.sweepWithoutList`'s,
     * for an install with no slot file, and nobody else's.
     */
    fun clear() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
        incoming.listFiles()?.forEach { it.deleteRecursively() }
    }

    /**
     * Every sealed blob in the pool, by ID — P2-9.
     *
     * The pool is flat and nothing on disk says what a blob is (§5, §6), so this
     * returns content, chaff, packs, the index and the log alike. The caller is the
     * only thing that can tell them apart, and only by what the index references.
     */
    fun allBlobIds(): List<ByteArray> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.length == BLOB_ID_BYTES * 2 }
            .mapNotNull { f ->
                runCatching { f.name.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
                    .getOrNull()
            }

    private fun nonceFor(prefix: ByteArray, index: Long) = ByteArray(NONCE_BYTES).also {
        prefix.copyInto(it, 0, 0, PREFIX_BYTES)
        ByteBuffer.wrap(it, PREFIX_BYTES, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(index)
    }

    /**
     * Streams [input] into a new blob. Never buffers the whole input — P1-7 puts
     * multi-GB video through this.
     */
    fun write(
        input: InputStream,
        vmk: ByteArray,
        context: VaultKeys.BlobContext = VaultKeys.BlobContext.CONTENT
    ): WrittenBlob =
        writeTo(ByteArray(BLOB_ID_BYTES).also { rng.nextBytes(it) }, input, vmk, false, context)

    /**
     * Streams [input] into as many blobs as it takes, each at most
     * [SPLIT_PLAINTEXT] of plaintext, and returns them in order.
     *
     * This is how every item is written. A file smaller than the split produces a
     * list of one and is byte-identical on disk to what the single-blob path
     * wrote before `blob-size-cap` — so the common case gained nothing to go
     * wrong in.
     *
     * [claim] is called with each blob's ID **before** that blob is written, so a
     * caller tracking partials (the import job table) knows about the in-flight
     * blob and the unlock sweep does not mistake it for an orphan. [ids] carries
     * the blobs a previous attempt already claimed: those already in the pool are
     * skipped, and the first one that is not is resumed.
     */
    fun writeItem(
        input: InputStream,
        vmk: ByteArray,
        ids: MutableList<ByteArray> = mutableListOf(),
        claim: (ByteArray) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): WrittenItem {
        // Leading blobs already sealed and in the pool are finished work. The
        // first claimed ID that is not in the pool is the partial to resume.
        var sealed = 0
        while (sealed < ids.size && fileOf(ids[sealed]).exists()) sealed++

        var total = sealed * SPLIT_PLAINTEXT
        var index = sealed
        // A byte read past a full blob to find out whether the source is done.
        // It must be handed to the next blob or the item loses one byte per
        // boundary — corrupting exactly the large files this change exists for,
        // and nothing else.
        var pushback = -1
        val one = ByteArray(1)
        while (true) {
            if (isCancelled()) break
            val blobId = ids.getOrNull(index)
                ?: ByteArray(BLOB_ID_BYTES).also { rng.nextBytes(it) }.also { ids.add(it) }
            claim(blobId)

            val resumeAt = resumeOffset(blobId)
            val bounded = Bounded(input, SPLIT_PLAINTEXT - resumeAt, pushback)
            pushback = -1
            val written = writeTo(blobId, bounded, vmk, resume = resumeAt > 0)
            total = index * SPLIT_PLAINTEXT + written.plaintextLength
            index++

            // Short of the split means the source ended inside this blob. A
            // source ending exactly on the boundary is why the lookahead exists:
            // without it the item gains a trailing empty blob.
            if (written.plaintextLength < SPLIT_PLAINTEXT) break
            // Through the array form, never `read()`. A source is free to
            // implement only `read(b, off, len)` — the export and archive corpora
            // do exactly that — and a single-byte read would throw on it. Found
            // by those tests, on the multi-blob path only.
            pushback = if (input.read(one) == 1) one[0].toInt() and 0xFF else -1
            if (pushback < 0) break
        }
        // Blobs claimed by an earlier attempt beyond what this item needs (a
        // shorter source on a re-run) are not ours to leave in the pool.
        while (ids.size > index) {
            val extra = ids.removeAt(ids.size - 1)
            discardPartial(extra)
            delete(extra)
        }
        return WrittenItem(ids.toList(), total)
    }

    /**
     * Caps a stream at [limit] bytes, serving [pushback] first when the caller
     * read a byte ahead to test for end-of-source.
     *
     * No shared state: each blob gets its own, so two imports running at once
     * cannot steal each other's lookahead.
     */
    private class Bounded(
        private val delegate: InputStream,
        private val limit: Long,
        private var pushback: Int,
    ) : InputStream() {

        private var served = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= limit || len == 0) return if (len == 0) 0 else -1
            var written = 0
            if (pushback >= 0) {
                b[off] = pushback.toByte()
                pushback = -1
                written = 1
                served++
            }
            val room = minOf(limit - served, (len - written).toLong()).toInt()
            if (room > 0) {
                val n = delegate.read(b, off + written, room)
                if (n > 0) {
                    written += n
                    served += n
                } else if (written == 0) {
                    return -1
                }
            }
            return written
        }
    }

    /**
     * Writes to a **specified** blob ID, replacing any existing blob atomically:
     * temp file, fsync, rename. P1-5's index needs a stable ID it can rewrite in
     * place, because moving it would mean rewriting the slot, which needs a KEK
     * that no longer exists after unlock.
     */
    open fun writeTo(blobId: ByteArray, input: InputStream, vmk: ByteArray): WrittenBlob =
        writeTo(blobId, input, vmk, resume = false)

    /**
     * Plaintext bytes already written for [blobId], or 0 if there is nothing to
     * resume. A caller resuming an interrupted import skips its source to here.
     *
     * The partial file *is* the resume state — chunks are fixed-size, so its
     * length says exactly how much survived. A side-car progress record could
     * disagree with the file it describes; this cannot.
     */
    fun resumeOffset(blobId: ByteArray): Long {
        val tmp = tempFileOf(blobId)
        if (!tmp.exists()) return 0L
        val completeChunks = ((tmp.length() - PREFIX_BYTES) / STRIDE).coerceAtLeast(0)
        return completeChunks * CHUNK
    }

    private fun tempFileOf(blobId: ByteArray) = File(incoming, fileOf(blobId).name + ".tmp")

    /**
     * Where a partial for [blobId] is staged.
     *
     * Public so nothing outside has to rebuild the path from its parts. Tests
     * that need to damage a partial in a way only a kill mid-write produces used
     * to construct it themselves, which meant the staging layout was written
     * down in more than one place and every one of them broke when it moved.
     */
    fun partialFileOf(blobId: ByteArray): File = tempFileOf(blobId)

    /**
     * Drops a partial. For a **finally failed** import only — a retrying one must
     * keep it, because the partial *is* the resume state P1-7 measured.
     */
    fun discardPartial(blobId: ByteArray): Boolean = tempFileOf(blobId).delete()

    /** Blob IDs with a partial staged, as lowercase hex. Used by the unlock sweep. */
    fun stagedPartials(): List<String> =
        incoming.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".tmp") }
            .map { it.name.removeSuffix(".tmp") }

    /**
     * Deletes every staged partial that [claimed] does not name — the unlock sweep.
     *
     * **Lists first, then asks for the claims.** A writer claims a blob before it creates
     * that blob's partial, so any partial in the listing was claimed before the claims are
     * read, and is in them while its import lives. Read the other way round, a blob
     * claimed between the two would be listed unclaimed and a live import's partial
     * deleted under it (`concurrent-import-failure` D3).
     *
     * @return how many went.
     */
    fun sweepUnclaimed(claimed: () -> Set<String>): Int {
        val staged = stagedPartials()
        val live = claimed()
        var swept = 0
        staged.forEach { hex ->
            if (hex in live) return@forEach
            // Not every staged file is an import's: the index and a thumbnail pack stage
            // here too, unclaimed by any job. One being written right now is
            // not an orphan, and deleting it fails that write at its rename — the
            // index's, under an import that outlived a lock.
            val tmp = File(incoming, "$hex.tmp")
            if (synchronized(writing) { tmp.path !in writing && tmp.delete() }) swept++
        }
        return swept
    }

    /**
     * @param resume continue an interrupted write, reusing the existing nonce
     *   prefix and appending after the last **whole** chunk. Any partial chunk is
     *   truncated away first, so nothing half-sealed is ever kept.
     */
    open fun writeTo(
        blobId: ByteArray,
        input: InputStream,
        vmk: ByteArray,
        resume: Boolean,
        context: VaultKeys.BlobContext = VaultKeys.BlobContext.CONTENT
    ): WrittenBlob {
        val file = tempFileOf(blobId)
        // Registered before the staging file is created, so the unlock sweep — which
        // checks this under the same lock before it deletes — can never take a file
        // out from under a write in progress (`concurrent-import-failure`, 4.2).
        synchronized(writing) { writing.merge(file.path, 1, Int::plus) }
        try {
            val resuming = resume && file.exists() && file.length() >= PREFIX_BYTES

            val completeChunks = if (resuming) {
                ((file.length() - PREFIX_BYTES) / STRIDE).coerceAtLeast(0)
            } else {
                0L
            }

            val prefix: ByteArray
            if (resuming) {
                prefix = ByteArray(PREFIX_BYTES)
                RandomAccessFile(file, "rw").use { raf ->
                    raf.readFully(prefix)
                    // Drop any partial chunk: a truncated chunk was never sealed.
                    raf.setLength(PREFIX_BYTES + completeChunks * STRIDE)
                }
            } else {
                prefix = ByteArray(PREFIX_BYTES).also { rng.nextBytes(it) }
                file.delete()
            }

            val dek = VaultKeys.blobKey(vmk, blobId, context)
            var plaintextLength = completeChunks * CHUNK
            try {
                FileOutputStream(file, resuming).use { out ->
                    if (!resuming) out.write(prefix)
                    plaintextLength += sealChunks(out, blobId, dek, prefix, completeChunks, input)
                    out.fd.sync()
                }
                // Streaming is done. Past this point the file is padded, so a
                // failure below leaves something that is *not* a valid resume state
                // — resumeOffset would read the bucket size as written length — and
                // it has to go.
                try {
                    // A blob over the cap is a format violation, not a big file: the
                    // ladder stops at 16 MiB and chaff covers nothing above it. Items
                    // go through writeItem and never reach this. What can is a
                    // fixed-ID blob — the index (§7) or a thumbnail pack (§8) — which
                    // has nowhere to record a blob list, so this fails loudly rather
                    // than writing something Gate 1 would find.
                    padToBucket(file)
                    check(file.renameTo(fileOf(blobId))) { "could not move blob into place" }
                } catch (e: Throwable) {
                    file.delete()
                    throw e
                }
            } finally {
                // The partial is deliberately NOT deleted here.
                //
                // It was, until P1-8.6: `finally { file.delete() }` destroyed the
                // resume state on every failure path, so an interrupted write could
                // never be resumed. P1-7's resume test did not catch it because it
                // built its partial by truncating a file rather than by interrupting
                // a real write. Only a process kill — where this block never runs —
                // left anything behind.
                //
                // A partial that outlives a failure is now cleaned up deliberately:
                // discardPartial on final failure, and the unlock sweep otherwise.
                dek.fill(0)
            }

            return WrittenBlob(blobId, plaintextLength)
        } finally {
            synchronized(writing) { writing.computeIfPresent(file.path) { _, n -> (n - 1).takeIf { it > 0 } } }
        }
    }

    /** Reads until the buffer is full or the stream ends — `read` may return short. */
    private fun fill(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /**
     * Seals [input] into [out] as §5 chunks, starting at chunk [startIndex].
     *
     * The envelope's one implementation. [writeTo] calls it against the staging
     * file and [sealTo] against whatever stream its caller supplies — extracted at
     * P2-12 rather than copied, because two loops computing nonces from a prefix is
     * two places for the nonce to be computed differently, which is the failure
     * that does not announce itself.
     *
     * @return plaintext bytes consumed.
     */
    private fun sealChunks(
        out: OutputStream,
        blobId: ByteArray,
        dek: ByteArray,
        prefix: ByteArray,
        startIndex: Long,
        input: InputStream,
    ): Long {
        val plain = ByteArray(CHUNK)
        val sealed = ByteArray(STRIDE)
        var index = startIndex
        var written = 0L
        while (true) {
            val n = fill(input, plain)
            if (n <= 0) break
            val message = if (n == CHUNK) plain else plain.copyOf(n)
            val ok = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                sealed, longArrayOf(0),
                message, n.toLong(),
                blobId, BLOB_ID_BYTES.toLong(),
                null, nonceFor(prefix, index), dek
            )
            check(ok) { "chunk $index seal failed" }
            out.write(sealed, 0, n + TAG_BYTES)
            written += n
            index++
            if (n < CHUNK) break
        }
        return written
    }

    /**
     * Seals [input] under [blobId]'s DEK into [out] — a complete blob's bytes,
     * nonce prefix and bucket padding included, with **no file created anywhere**.
     *
     * P2-12, for `VaultArchive`: the archive must carry an index it *builds* rather
     * than the one on disk, and staging that at a path inside the store would put a
     * file in `blobs/`or `incoming/` that nothing accounts for — [allBlobIds] lists
     * `blobs/` and only 32-hex-character names, and [stagedPartials] matches
     * `*.tmp`. An archive killed halfway would leave it there permanently. In a
     * stream there is nothing to leave.
     *
     * Bounded by the format: a blob is capped at 16 MiB (§5), so a caller sealing
     * into memory has a ceiling rather than a hope.
     *
     * The bytes are byte-for-byte what [writeTo] would have written for the same
     * input, except for the random nonce prefix and the random padding, which are
     * fresh on every call by design.
     */
    fun sealTo(
        out: OutputStream,
        blobId: ByteArray,
        vmk: ByteArray,
        input: InputStream,
        context: VaultKeys.BlobContext = VaultKeys.BlobContext.CONTENT,
    ) {
        val prefix = ByteArray(PREFIX_BYTES).also { rng.nextBytes(it) }
        out.write(prefix)

        val dek = VaultKeys.blobKey(vmk, blobId, context)
        var cipherLength = PREFIX_BYTES.toLong()
        try {
            val counting = object : OutputStream() {
                override fun write(b: Int) {
                    out.write(b); cipherLength++
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len); cipherLength += len
                }
            }
            sealChunks(counting, blobId, dek, prefix, 0, input)
        } finally {
            dek.fill(0)
        }

        // The same ladder [padToBucket] climbs, against a length counted rather
        // than measured on disk. Without it the sealed length says what the
        // plaintext length is, which is the whole thing §5's buckets destroy.
        var padding = bucketFor(cipherLength) - cipherLength
        check(padding >= 0) { "sealed blob is over the size cap" }
        val buf = ByteArray(min(padding, 1L shl 20).toInt().coerceAtLeast(1))
        while (padding > 0) {
            rng.nextBytes(buf)
            val n = min(padding, buf.size.toLong()).toInt()
            out.write(buf, 0, n)
            padding -= n
        }
        out.flush()
    }

    private fun padToBucket(file: File) {
        val target = bucketFor(file.length())
        val padding = target - file.length()
        if (padding <= 0) return

        val buf = ByteArray(min(padding, 1L shl 20).toInt())
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(file.length())
            var remaining = padding
            while (remaining > 0) {
                rng.nextBytes(buf)
                val n = min(remaining, buf.size.toLong()).toInt()
                raf.write(buf, 0, n)
                remaining -= n
            }
            raf.fd.sync()
        }
    }

    /**
     * Random-access reader. [plaintextLength] comes from the metadata DB — it is
     * deliberately absent from the file, because a length header would defeat the
     * padding.
     */
    fun reader(
        blobId: ByteArray,
        vmk: ByteArray,
        plaintextLength: Long,
        context: VaultKeys.BlobContext = VaultKeys.BlobContext.CONTENT
    ): Reader = reader(listOf(blobId), vmk, plaintextLength, context)

    /**
     * Reader over an item's blobs, in order.
     *
     * The surface is what it always was — [Reader.chunkCount], [Reader.readChunk],
     * [Reader.readRange] — and callers do not learn how many blobs are underneath.
     * A chunk index is global to the item; which blob holds it is arithmetic
     * ([CHUNKS_PER_BLOB]), so seeking stays constant-time and no blob carries an
     * index table.
     */
    // `open` for the reason [writeTo] is: a test subclass counts calls. P2-18's
    // Verify claims a document is no longer decrypted at every unlock, and "no
    // read happened" is a claim about calls — timing would measure the emulator.
    open fun reader(
        blobIds: List<ByteArray>,
        vmk: ByteArray,
        plaintextLength: Long,
        context: VaultKeys.BlobContext = VaultKeys.BlobContext.CONTENT
    ): Reader {
        require(blobIds.isNotEmpty()) { "an item has at least one blob" }
        return Reader(blobIds, vmk, context, plaintextLength)
    }

    inner class Reader(
        private val blobIds: List<ByteArray>,
        vmk: ByteArray,
        context: VaultKeys.BlobContext,
        private val plaintextLength: Long
    ) : AutoCloseable {

        private val deks = blobIds.map { VaultKeys.blobKey(vmk, it, context) }
        private val rafs = arrayOfNulls<RandomAccessFile>(blobIds.size)
        private val prefixes = arrayOfNulls<ByteArray>(blobIds.size)

        val chunkCount: Long = (plaintextLength + CHUNK - 1) / CHUNK

        /** Opened on first touch: a seek into blob 200 must not open the other 199. */
        private fun rafFor(blob: Int): RandomAccessFile {
            rafs[blob]?.let { return it }
            val raf = RandomAccessFile(fileOf(blobIds[blob]), "r")
            val prefix = ByteArray(PREFIX_BYTES).also { raf.readFully(it) }
            rafs[blob] = raf
            prefixes[blob] = prefix
            return raf
        }

        /**
         * Decrypts chunk [index] of the **item** into [into], returning its
         * plaintext size.
         */
        fun readChunk(index: Long, into: ByteArray): Int {
            val size = min(CHUNK.toLong(), plaintextLength - index * CHUNK).toInt()
            if (size <= 0) return 0

            val blob = (index / CHUNKS_PER_BLOB).toInt()
            val local = index % CHUNKS_PER_BLOB
            check(blob < blobIds.size) {
                "chunk $index needs blob $blob but the item records ${blobIds.size}"
            }

            val sealed = ByteArray(size + TAG_BYTES)
            val raf = rafFor(blob)
            raf.seek(offsetOfChunk(local))
            raf.readFully(sealed)

            val ok = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
                into, longArrayOf(0),
                null,
                sealed, sealed.size.toLong(),
                blobIds[blob], BLOB_ID_BYTES.toLong(),
                // The nonce is per blob, so the index is the one *within* this
                // blob. Using the item-global index would break every blob after
                // the first, and only for items large enough to have one.
                nonceFor(prefixes[blob]!!, local), deks[blob]
            )
            check(ok) { "chunk $index (blob $blob, local $local) failed authentication" }
            return size
        }

        /**
         * Decrypts [length] plaintext bytes starting at [offset], crossing chunk
         * boundaries as needed.
         *
         * A thumbnail is a byte range inside a pack (`FORMAT.md` §8), not a whole
         * blob, so it needs this rather than [readChunk]. Only the chunks the
         * range actually touches are decrypted — the envelope's chunk
         * independence applied to a range.
         */
        fun readRange(offset: Long, length: Int): ByteArray {
            require(offset >= 0 && length >= 0) { "range must be non-negative" }
            require(offset + length <= plaintextLength) {
                "range $offset..${offset + length} exceeds plaintext length $plaintextLength"
            }

            val out = ByteArray(length)
            val buf = ByteArray(CHUNK)
            var written = 0
            var index = offset / CHUNK
            var within = (offset % CHUNK).toInt()

            while (written < length) {
                val n = readChunk(index, buf)
                if (n <= 0) break
                val take = min((n - within).toLong(), (length - written).toLong()).toInt()
                buf.copyInto(out, written, within, within + take)
                written += take
                within = 0
                index++
            }
            check(written == length) { "short read: $written of $length" }
            return out
        }

        override fun close() {
            deks.forEach { it.fill(0) }
            rafs.forEach { it?.close() }
        }
    }

    fun delete(blobId: ByteArray): Boolean = fileOf(blobId).delete()

    // --- sealed captures (P2-4) ------------------------------------------------

    /**
     * Writes an **already-sealed** capture into a bucket-padded blob — P2-4.
     *
     * Unlike [write], nothing here derives a key or wraps a chunk: a `crypto_box_seal`
     * ciphertext is the envelope, produced at a *failed* unlock where there is no VMK
     * to derive one. The blob is `[sealed bytes] ‖ [CSPRNG pad to bucket]`, and the
     * two halves are indistinguishable, so the blob reveals neither the capture's
     * true size nor that it is a capture at all — it is one more bucket-sized file in
     * `blobs/`, exactly what §5 promises every file there is. The sealed length is
     * recorded by the caller in the pending queue, never on disk.
     *
     * Atomic like [writeTo]: temp file, fsync, rename, so a kill mid-write leaves no
     * half-blob in the pool.
     */
    fun writeSealed(blobId: ByteArray, sealed: ByteArray) {
        require(sealed.size <= MAX_BUCKET) {
            "a sealed capture of ${sealed.size} B exceeds the $MAX_BUCKET B cap (FORMAT.md 5)"
        }
        val tmp = tempFileOf(blobId)
        try {
            FileOutputStream(tmp).use { it.write(sealed); it.fd.sync() }
            padToBucket(tmp)
            check(tmp.renameTo(fileOf(blobId))) { "could not move capture blob into place" }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Reads the first [sealedLength] bytes back out of a capture blob — the sealed
     * box the caller stored, without the bucket padding after it. The length comes
     * from the pending queue; the blob carries no header that could disagree with it.
     */
    fun readSealed(blobId: ByteArray, sealedLength: Int): ByteArray {
        val out = ByteArray(sealedLength)
        RandomAccessFile(fileOf(blobId), "r").use { it.readFully(out) }
        return out
    }

    // --- chaff (§6) ------------------------------------------------------------

    /**
     * Writes [count] chaff blobs and returns their IDs.
     *
     * Chaff is CSPRNG bytes at a bucket size — not encrypted random plaintext.
     * Ciphertext is indistinguishable from random, so random is indistinguishable
     * from ciphertext, and at install time there is no VMK to derive a key from.
     *
     * The returned IDs are the caller's to record. Install-time chaff is written
     * by a caller that records nothing, and is never refreshed (§6).
     */
    fun writeChaff(count: Int, sizes: List<Long> = SMALL_BUCKETS): List<ByteArray> =
        (0 until count).map {
            val blobId = ByteArray(BLOB_ID_BYTES).also { id -> rng.nextBytes(id) }
            writeRandomBlob(blobId, sizes[rng.nextInt(sizes.size)])
            blobId
        }

    /**
     * Replaces each blob in [ids] with fresh random content under a fresh ID,
     * returning the new IDs in the same order.
     *
     * **[ids] must be chaff this vault owns.** Passing a real blob's ID destroys
     * it, and passing another vault's destroys theirs. The store cannot tell the
     * difference — that is the format working — so it never chooses for you and
     * there is no scan-and-pick path anywhere in this class.
     */
    fun refreshChaff(ids: List<ByteArray>): List<ByteArray> = ids.map { old ->
        val size = fileOf(old).length().coerceAtLeast(SMALL_BUCKETS.first())
        delete(old)
        val fresh = ByteArray(BLOB_ID_BYTES).also { rng.nextBytes(it) }
        writeRandomBlob(fresh, bucketFor(size))
        fresh
    }

    private fun writeRandomBlob(blobId: ByteArray, size: Long) {
        val buf = ByteArray(min(size, 1L shl 20).toInt())
        FileOutputStream(fileOf(blobId)).use { out ->
            var remaining = size
            while (remaining > 0) {
                rng.nextBytes(buf)
                val n = min(remaining, buf.size.toLong()).toInt()
                out.write(buf, 0, n)
                remaining -= n
            }
            out.fd.sync()
        }
    }
}
