package com.app.vault

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/** What an import recovered. The caller owns [vmk] and must zero it. */
class ImportedArchive(val vmk: ByteArray, val blobIds: List<ByteArray>)

/**
 * The `.vault` archive — `FORMAT.md` §11.
 *
 * One file, one vault, for moving to another device. The blobs travel
 * **verbatim**: each is already sealed under a per-blob DEK derived from the VMK
 * and named by its own ID, so re-encrypting them would spend a full pass over
 * multi-GB content to reach ciphertext no better protected — the archive hands
 * over the VMK to whoever opens the header anyway.
 *
 * **The VMK is preserved**, which is what makes this a migration rather than a
 * restore into something else: P1-12's mnemonic still opens the vault, P1-12's
 * derived index blob ID still locates the index, and every blob ID and DEK stays
 * valid.
 *
 * **Every failure is one failure.** A wrong passphrase, a truncated file, a
 * corrupted file and a file that was never an archive all fail the header's AEAD
 * and return null — the stance `SlotArray.open`, `VaultUnlock` and
 * `Mnemonic.decode` already take.
 */
object VaultArchive {

    private const val SALT_BYTES = 16
    private const val LENGTH_BYTES = 4
    private const val NONCE_BYTES = 24
    private const val TAG_BYTES = 16
    private const val HASH_BYTES = 32

    private const val VMK_OFFSET = 0
    private const val ITEM_COUNT_OFFSET = 32
    private const val BLOB_COUNT_OFFSET = 36
    private const val TOC_OFFSET = 40

    /** §11: 16 B ID + 8 B length + 32 B SHA-256. */
    private const val TOC_ENTRY_BYTES = 16 + 8 + HASH_BYTES

    private const val COPY_BUFFER = 1 shl 16
    private const val TAG = "VaultArchive"

    private val sodium = Sodium.lazy
    private val rng = SecureRandom()

    // --- write -----------------------------------------------------------------

    /**
     * Writes [db]'s vault to [out].
     *
     * [passphrase] is zeroed here on every path. Returns the number of blobs
     * written.
     *
     * **Every blob is read twice** — once to hash, once to copy. The header
     * precedes the bytes it describes and is sealed, so its hashes have to exist
     * before the first byte of content is written. Both reads are local disk; the
     * alternative is a trailer, which would make import seek instead of stream.
     */
    fun write(
        out: OutputStream,
        vmk: ByteArray,
        db: MetadataDb,
        store: BlobStore,
        passphrase: ByteArray,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Int {
        // P2-12. The archive carries the vault's live items only, and therefore an
        // index that names only them — generated below, never the one on disk.
        val live = db.items.filter { it.deletedAt == null }
        val indexId = VaultKeys.metadataBlobId(vmk)

        val blobIds = ownedBlobIds(db, live, vmk)
        val salt = ByteArray(SALT_BYTES).also { rng.nextBytes(it) }
        // The newest published set, which is the creation constant. Import on the
        // far side does not need to be told which one: it is this same app and
        // tries every entry — see `read`.
        val key = try {
            VaultKeys.deriveKek(passphrase, salt, VaultKeys.currentKdfParams())
        } finally {
            passphrase.fill(0)
        }

        try {
            // Sealed here rather than above, so that anything it throws happens
            // with the passphrase already zeroed and inside the block that zeroes
            // the KEK. In memory and never a file — see `FORMAT.md` §11.
            val generated = mapOf(
                indexId.hex() to ByteArrayOutputStream().also { buffer ->
                    store.sealTo(
                        buffer, indexId, vmk,
                        ByteArrayInputStream(db.withItems(live).plaintext()),
                    )
                }.toByteArray()
            )

            // Pass one: lengths and hashes. A blob that has vanished is skipped
            // rather than fatal — its absence is already visible as a missing item
            // on the far side, and refusing to archive anything because one chaff
            // blob went missing would be worse.
            val present = blobIds.filter { it.hex() in generated || store.fileOf(it).exists() }
            val hashes = HashMap<String, ByteArray>(present.size)
            val lengths = HashMap<String, Long>(present.size)
            present.forEachIndexed { i, id ->
                val made = generated[id.hex()]
                if (made != null) {
                    hashes[id.hex()] = sha256Of(made)
                    lengths[id.hex()] = made.size.toLong()
                } else {
                    val f = store.fileOf(id)
                    hashes[id.hex()] = sha256Of(f)
                    lengths[id.hex()] = f.length()
                }
                onProgress(i + 1, present.size * 2)
            }

            val header = buildHeader(vmk, live.size, present, lengths, hashes)
            val nonce = ByteArray(NONCE_BYTES).also { rng.nextBytes(it) }
            val sealed = ByteArray(header.size + TAG_BYTES)
            try {
                val ok = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                    sealed, longArrayOf(0),
                    header, header.size.toLong(),
                    salt, SALT_BYTES.toLong(),
                    null, nonce, key
                )
                check(ok) { "archive header seal failed" }
            } finally {
                header.fill(0)
            }

            out.write(salt)
            out.write(ByteArray(LENGTH_BYTES).also {
                ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(sealed.size)
            })
            out.write(nonce)
            out.write(sealed)

            // Pass two: the bytes, in the order the header lists them.
            val buf = ByteArray(COPY_BUFFER)
            present.forEachIndexed { i, id ->
                val made = generated[id.hex()]
                if (made != null) {
                    out.write(made)
                } else {
                    store.fileOf(id).inputStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                onProgress(present.size + i + 1, present.size * 2)
            }
            out.flush()

            Log.i(
                TAG,
                "archived ${present.size} blobs, ${live.size} items" +
                    ", ${db.items.size - live.size} trashed left behind"
            )
            return present.size
        } finally {
            key.fill(0)
        }
    }

    /**
     * Everything this vault owns — `FORMAT.md` §11.
     *
     * Content blobs, the index, thumbnail packs, the tamper log, and **owned**
     * chaff. Install chaff is deliberately absent: it is the unowned reserve that
     * keeps unaccounted blobs ambiguous (§6), and two devices sharing one reserve
     * is a correlation where §6 wanted ambiguity.
     *
     * Deduplicated — a thumbnail pack is referenced by many items.
     */
    private fun ownedBlobIds(
        db: MetadataDb,
        /** P2-12. The rows that travel: a trashed item's blobs are left behind. */
        live: List<VaultItem>,
        vmk: ByteArray,
    ): List<ByteArray> {
        val seen = LinkedHashMap<String, ByteArray>()
        fun add(id: ByteArray?) {
            if (id != null) seen.putIfAbsent(id.hex(), id)
        }

        add(VaultKeys.metadataBlobId(vmk))
        // Every blob of every item. A missed blob is not a leak — it is an
        // archive that restores a truncated file, which is the failure splitting
        // an item across blobs introduces.
        live.forEach { item -> item.blobIds.forEach { add(it) }; add(item.thumb?.packBlobId) }
        db.thumbPacks.forEach { add(it.id) }
        db.ownedChaff.forEach { add(it) }
        add(db.logBlobId)

        return seen.values.toList()
    }

    private fun buildHeader(
        vmk: ByteArray,
        itemCount: Int,
        blobIds: List<ByteArray>,
        lengths: Map<String, Long>,
        hashes: Map<String, ByteArray>,
    ): ByteArray {
        val header = ByteArray(TOC_OFFSET + blobIds.size * TOC_ENTRY_BYTES)
        vmk.copyInto(header, VMK_OFFSET)
        ByteBuffer.wrap(header, ITEM_COUNT_OFFSET, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(itemCount)
        ByteBuffer.wrap(header, BLOB_COUNT_OFFSET, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(blobIds.size)

        blobIds.forEachIndexed { i, id ->
            val at = TOC_OFFSET + i * TOC_ENTRY_BYTES
            id.copyInto(header, at)
            ByteBuffer.wrap(header, at + 16, 8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(lengths.getValue(id.hex()))
            hashes.getValue(id.hex()).copyInto(header, at + 24)
        }
        return header
    }

    // --- read ------------------------------------------------------------------

    /**
     * Reads an archive from [input] into [store].
     *
     * Returns null for **every** failure — a wrong passphrase, a truncated file, a
     * corrupt file, or a file that was never an archive. Nothing distinguishes
     * them, by design (§11).
     *
     * A hash mismatch aborts and removes everything this call wrote: a partial
     * vault from a corrupt archive is worse than no vault, because the user would
     * find out one photograph at a time.
     *
     * [passphrase] is zeroed on every path.
     */
    fun read(
        input: InputStream,
        passphrase: ByteArray,
        store: BlobStore,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImportedArchive? {
        val salt = ByteArray(SALT_BYTES)
        if (!readFully(input, salt)) {
            passphrase.fill(0)
            return null
        }

        val written = mutableListOf<ByteArray>()
        // Nullable and zeroed in the outer `finally`, because every early return
        // below happens before it is opened, and the passphrase must be zeroed on
        // all of those paths too — it is now consumed later than it used to be.
        var header: ByteArray? = null
        try {
            val lengthBytes = ByteArray(LENGTH_BYTES)
            if (!readFully(input, lengthBytes)) return null
            val sealedLength = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).int
            // A hostile length must not become an allocation. 64 MiB of header is
            // ~1.2 million blobs, far past anything this app produces.
            if (sealedLength <= TAG_BYTES || sealedLength > 64 * 1024 * 1024) return null

            val nonce = ByteArray(NONCE_BYTES)
            if (!readFully(input, nonce)) return null

            val sealed = ByteArray(sealedLength)
            if (!readFully(input, sealed)) return null

            // P2-25. The archive records nothing about what sealed it — a recorded
            // parameter block would be the fingerprint §11 forbids — so every set
            // this app has published is tried. The bytes are already in memory, so
            // the stream is still read exactly once.
            //
            // A miss attempts all of them and returns the one failure §11 requires;
            // there is no per-entry outcome and nothing says which entry matched.
            val opened = openHeader(sealed, nonce, salt, passphrase) ?: return null
            header = opened

            run {
                if (opened.size < TOC_OFFSET) return null
                val blobCount = ByteBuffer.wrap(opened, BLOB_COUNT_OFFSET, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                if (blobCount < 0 || opened.size != TOC_OFFSET + blobCount * TOC_ENTRY_BYTES) return null

                val buf = ByteArray(COPY_BUFFER)
                for (i in 0 until blobCount) {
                    val at = TOC_OFFSET + i * TOC_ENTRY_BYTES
                    val id = opened.copyOfRange(at, at + 16)
                    val length = ByteBuffer.wrap(opened, at + 16, 8)
                        .order(ByteOrder.LITTLE_ENDIAN).long
                    val expected = opened.copyOfRange(at + 24, at + 24 + HASH_BYTES)
                    if (length < 0) return null

                    val digest = MessageDigest.getInstance("SHA-256")
                    val file = store.fileOf(id)
                    file.parentFile?.mkdirs()
                    var remaining = length
                    file.outputStream().use { out ->
                        while (remaining > 0) {
                            val want = minOf(remaining, buf.size.toLong()).toInt()
                            val n = input.read(buf, 0, want)
                            if (n <= 0) return abort(store, written)
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            remaining -= n
                        }
                    }
                    written += id

                    if (!digest.digest().contentEquals(expected)) {
                        Log.w(TAG, "blob ${id.hex()} failed its archive hash")
                        return abort(store, written)
                    }
                    onProgress(i + 1, blobCount)
                }

                Log.i(TAG, "imported $blobCount blobs")
                return ImportedArchive(
                    vmk = opened.copyOfRange(VMK_OFFSET, VMK_OFFSET + VaultKeys.VMK_BYTES),
                    blobIds = written.toList()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "archive import failed", e)
            abort(store, written)
            return null
        } finally {
            header?.fill(0)
            // `openHeader` already zeroed it the moment the last derivation was
            // done. This covers the early returns above, which never reach it.
            passphrase.fill(0)
        }
    }

    /**
     * Opens the sealed header under whichever published parameter set sealed it —
     * P2-25.
     *
     * The archive says nothing about what sealed it, so every entry of
     * [VaultKeys.RELEASED_KDF_PARAMS] is derived and attempted. A miss attempts all
     * of them and returns null: one failure, no per-entry outcome, and nothing that
     * reveals which entry matched.
     *
     * [passphrase] is zeroed **here**, in the `finally`, rather than only in the
     * caller's. Trying several parameter sets means it is read more than once, so
     * the caller cannot zero it after the first derivation the way it used to —
     * and leaving it to the end of `read` would hold it for the whole import. It is
     * zeroed once the last derivation has taken it, and not per attempt, which
     * would leave every later entry deriving from zeroes.
     */
    private fun openHeader(
        sealed: ByteArray,
        nonce: ByteArray,
        salt: ByteArray,
        passphrase: ByteArray,
    ): ByteArray? = try {
        openUnderAnyPublishedParams(sealed, nonce, salt, passphrase)
    } finally {
        passphrase.fill(0)
    }

    private fun openUnderAnyPublishedParams(
        sealed: ByteArray,
        nonce: ByteArray,
        salt: ByteArray,
        passphrase: ByteArray,
    ): ByteArray? {
        VaultKeys.RELEASED_KDF_PARAMS.forEach { params ->
            val key = VaultKeys.deriveKek(passphrase, salt, params)
            try {
                val header = ByteArray(sealed.size - TAG_BYTES)
                val opened = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
                    header, longArrayOf(0),
                    null,
                    sealed, sealed.size.toLong(),
                    salt, SALT_BYTES.toLong(),
                    nonce, key
                )
                if (opened) return header
                header.fill(0)
            } finally {
                key.fill(0)
            }
        }
        return null
    }

    /** Removes everything this import wrote. A partial vault is worse than none. */
    private fun abort(store: BlobStore, written: List<ByteArray>): ImportedArchive? {
        written.forEach { store.delete(it) }
        return null
    }

    // --- internals ---------------------------------------------------------------

    /** The same digest over bytes that were never a file — P2-12's generated index. */
    private fun sha256Of(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun sha256Of(file: java.io.File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(COPY_BUFFER)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest()
    }

    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var read = 0
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }
}
