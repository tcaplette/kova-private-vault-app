package com.app.vault

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One vault's backup as one file — `backup-to-cloud-apps`, `FORMAT.md` §14.
 *
 * **Why a file and not the folder mirror.** A cloud app like Google Drive grants single
 * files and never folders: it is absent from `ACTION_OPEN_DOCUMENT_TREE`, and a file
 * created under a single-document grant is not granted to its creator. So what reaches
 * Drive is one file the person saved once, rewritten whole on each backup.
 *
 * **What is in it.** The vault's blobs and the keyring blob, verbatim, behind a table of
 * contents sealed under [VaultKeys.backupKey], and a MAC over every blob byte at the end.
 * Nothing derived from a passphrase: the words open it and nothing else does. The provider
 * sees one file, its size and when it changed.
 *
 * **Prepared in an instant, streamed later.** [prepare] only lists names and lengths and
 * seals them — no blob is read — so it can run inside a lock while the key still exists
 * (`backup-to-cloud-apps` D11), and [stream] then needs no vault key at all. Integrity is a
 * per-file HMAC key sealed in the table, not a per-blob hash that would need every byte
 * read before the table could be sealed.
 *
 * Sodium is reached inside the functions that seal and open, never at object
 * initialisation, so [status] and [expectedSize] run on the JVM.
 */
object BackupFile {

    private const val NONCE_BYTES = 24
    private const val LENGTH_BYTES = 4
    private const val TAG_BYTES = 16
    private const val HASH_BYTES = 32
    private const val MAC_BYTES = 32
    private const val ID_BYTES = BlobStore.BLOB_ID_BYTES
    private const val ENTRY_BYTES = ID_BYTES + 8
    private const val TOC_HEAD = 4 + 4 + MAC_BYTES
    private const val VERSION = 2

    /** The sealed table's plaintext is padded to this, so its length is not the blob count. */
    const val TOC_STEP = 65_536

    /** Everything before the sealed table. */
    const val HEADER_BYTES = NONCE_BYTES + LENGTH_BYTES

    /** A garbage file must not ask for a gigabyte of table. */
    private const val MAX_SEALED = 64 * 1024 * 1024

    private const val COPY_BUFFER = 1 shl 16

    private val AAD = "vaultbak/v2".toByteArray(Charsets.US_ASCII)
    private val rng = SecureRandom()

    /** One blob as the table records it. */
    class Entry(val name: String, val length: Long)

    // --- arithmetic, JVM-testable -------------------------------------------------

    /** The table's plaintext length for [count] blobs: head, entries, padded to [TOC_STEP]. */
    fun tocPlainLength(count: Int): Int {
        val raw = TOC_HEAD + count.toLong() * ENTRY_BYTES
        return (((raw + TOC_STEP - 1) / TOC_STEP) * TOC_STEP).toInt()
    }

    /**
     * The size a complete file for these blobs has. A file of any other size is one whose
     * write did not finish — the status reads it as behind, and the next run rewrites it.
     */
    fun expectedSize(count: Int, blobBytes: Long): Long =
        HEADER_BYTES + tocPlainLength(count).toLong() + TAG_BYTES + blobBytes + MAC_BYTES

    // --- the record of the last write — design D5 -------------------------------------

    /**
     * When this vault's backup file was last written by this app, on this device's clock,
     * how many bytes went into it, and a digest of the index's content at the time.
     *
     * **Why it is stored.** The provider's own modified time cannot stand in for it: Google
     * Drive's `COLUMN_LAST_MODIFIED` stayed at the file's creation time through two
     * successful rewrites (measured on the emulator). So the one fact only this app knows —
     * that its write finished — is kept, sealed under the vault's key in a blob whose ID is
     * derived from that key ([VaultKeys.backupRecordBlobId]). It is not part of the backup,
     * it maps nothing to anything, and it is padded like every other blob.
     */
    class Record(val writtenAt: Long, val size: Long, val indexDigest: ByteArray)

    private const val RECORD_BYTES = 16 + HASH_BYTES

    /**
     * The index as the person would see it — design D5.
     *
     * Every unlock rotates one owned chaff blob and saves the index (`FORMAT.md` §6), and a
     * growing tamper log moves its pointer. Neither is the person's content, and neither is
     * in the backup. Counted as changes, they would read "1 change to copy" after every
     * unlock. So the index is compared by this digest: its serialised form with the chaff
     * list and the log pointer taken out.
     *
     * [db] must be the caller's own instance — it is changed and put back in place.
     */
    fun contentDigest(db: MetadataDb): ByteArray {
        val chaff = db.ownedChaff.toList()
        val log = db.logBlobId
        return try {
            db.ownedChaff.clear()
            db.logBlobId = null
            MessageDigest.getInstance("SHA-256").digest(db.plaintext())
        } finally {
            db.ownedChaff.addAll(chaff)
            db.logBlobId = log
        }
    }

    fun readRecord(store: BlobStore, vmk: ByteArray): Record? = runCatching {
        val id = VaultKeys.backupRecordBlobId(vmk)
        if (!store.fileOf(id).exists()) return null
        val plain = store.reader(id, vmk, RECORD_BYTES.toLong()).use { it.readRange(0, RECORD_BYTES) }
        val buf = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
        Record(buf.long, buf.long, ByteArray(HASH_BYTES).also { buf.get(it) })
    }.getOrNull()

    fun writeRecord(store: BlobStore, vmk: ByteArray, record: Record) {
        val plain = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(record.writtenAt).putLong(record.size).put(record.indexDigest).array()
        store.writeTo(VaultKeys.backupRecordBlobId(vmk), java.io.ByteArrayInputStream(plain), vmk)
    }

    /**
     * Where a file destination stands — design D5.
     *
     * [local] is this vault's blobs, name to size and modification time; [record] is this
     * app's own note of its last completed write, null before the first; [remoteSize] is
     * what the provider reports for the file now. A blob other than the index newer than
     * the write is a change; the index is a change only when its content digest differs.
     * A file whose size is not what was written did not finish, or was replaced.
     */
    fun status(
        local: Map<String, CloudMirror.Entry>,
        record: Record?,
        remoteSize: Long,
        indexName: String,
        indexDigest: ByteArray,
    ): CloudMirror.Status {
        if (record == null) return CloudMirror.Status(local.size.coerceAtLeast(1), null)
        val complete = remoteSize < 0 || remoteSize == record.size
        val pending = if (!complete) local.size.coerceAtLeast(1)
        else local.count { (name, e) -> name != indexName && e.modified > record.writtenAt } +
            (if (indexDigest.contentEquals(record.indexDigest)) 0 else 1)
        return CloudMirror.Status(pending, record.writtenAt)
    }

    // --- the snapshot ---------------------------------------------------------------

    /**
     * The files to write for [names] — design D3.
     *
     * The table records each blob's length before any blob is streamed, so a blob must not
     * change under the writer. [mutable] names the ones rewritten under the same ID — the
     * index and the keyring (`BlobStore.writeTo`'s callers) — and those are **copied** into
     * [snapshotDir] first; everything else is written once and read where it is.
     *
     * ponytail: a copy, not a hard link — `link()` on app data is denied by SELinux for
     * untrusted apps (`avc: denied { link } … untrusted_app`, measured on the emulator).
     */
    fun snapshot(blobsDir: File, names: Set<String>, mutable: Set<String>, snapshotDir: File): List<File> {
        dropSnapshot(snapshotDir)
        snapshotDir.mkdirs()
        return names.sorted().mapNotNull { name ->
            val source = File(blobsDir, name)
            if (!source.isFile) return@mapNotNull null
            if (name !in mutable) return@mapNotNull source
            val copy = File(snapshotDir, name)
            runCatching { source.copyTo(copy, overwrite = true) }.getOrNull()
        }
    }

    fun dropSnapshot(snapshotDir: File) {
        snapshotDir.deleteRecursively()
    }

    // --- write ----------------------------------------------------------------------

    /**
     * A file ready to stream: the sealed header, the MAC key, the files. The MAC key opens
     * nothing — it authenticates this one file — and is zeroed by [stream].
     */
    class Prepared(val header: ByteArray, internal val macKey: ByteArray, val files: List<File>, val lengths: List<Long>) {
        val blobBytes: Long get() = lengths.sum()
        val size: Long get() = header.size + blobBytes + MAC_BYTES
    }

    /**
     * Seals the table for [files] and returns it with a fresh MAC key. Reads no blob, so it
     * is instant. **[backupKey] is consumed** — zeroed before this returns.
     */
    fun prepare(files: List<File>, backupKey: ByteArray): Prepared {
        val lengths = files.map { it.length() }
        val macKey = ByteArray(MAC_BYTES).also { rng.nextBytes(it) }
        val toc = ByteArray(tocPlainLength(files.size))
        val nonce = ByteArray(NONCE_BYTES).also { rng.nextBytes(it) }
        val sealed = ByteArray(toc.size + TAG_BYTES)
        try {
            val head = ByteBuffer.wrap(toc).order(ByteOrder.LITTLE_ENDIAN)
            head.putInt(VERSION).putInt(files.size).put(macKey)
            files.forEachIndexed { i, f ->
                head.put(hexBytes(f.name))
                head.putLong(lengths[i])
            }
            val ok = Sodium.lazy.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                sealed, longArrayOf(0),
                toc, toc.size.toLong(),
                AAD, AAD.size.toLong(),
                null, nonce, backupKey,
            )
            check(ok) { "backup table seal failed" }
        } finally {
            toc.fill(0)
            backupKey.fill(0)
        }
        return Prepared(nonce + le32(sealed.size) + sealed, macKey, files, lengths)
    }

    /**
     * Writes [p] to [out]: the header, every blob, the MAC. A blob whose length is no longer
     * the table's fails the write rather than producing a file that does not restore.
     * Returns the blob bytes written; zeroes the MAC key either way.
     */
    fun stream(out: OutputStream, p: Prepared, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): Long {
        try {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(p.macKey, "HmacSHA256")) }
            out.write(p.header)
            val total = p.blobBytes
            var done = 0L
            onProgress(0, total)
            val buf = ByteArray(COPY_BUFFER)
            p.files.forEachIndexed { i, f ->
                var written = 0L
                f.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        mac.update(buf, 0, n)
                        written += n
                        done += n
                        onProgress(done, total)
                    }
                }
                check(written == p.lengths[i]) { "a blob changed length during the backup" }
            }
            out.write(mac.doFinal())
            return total
        } finally {
            p.macKey.fill(0)
        }
    }

    /** [prepare] then [stream], for a caller that holds the key and the output at once. */
    fun write(
        out: OutputStream,
        files: List<File>,
        backupKey: ByteArray,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Long = stream(out, prepare(files, backupKey), onProgress)

    // --- read -----------------------------------------------------------------------

    /** How much of a file [isBackup] needs. */
    const val HEAD_BYTES = HEADER_BYTES

    /**
     * Whether [head] — a file's first [HEAD_BYTES] — is shaped like a backup —
     * `backup-file-recover-and-import`, D3.
     *
     * A backup carries no marker, but its sealed table's length at bytes 24–27 is always a
     * whole number of [TOC_STEP]s plus the tag, and never more than [MAX_SEALED]. A `.vault`
     * has nonce bytes there and passes about once in four million; the words then fail with
     * the one message every wrong-words outcome gives. The file's name is not consulted:
     * providers rename files.
     */
    fun isBackup(head: ByteArray): Boolean {
        if (head.size < HEAD_BYTES) return false
        val v = ByteBuffer.wrap(head, NONCE_BYTES, LENGTH_BYTES).order(ByteOrder.LITTLE_ENDIAN).int
        val table = v - TAG_BYTES
        return table > 0 && table % TOC_STEP == 0 && v <= MAX_SEALED
    }

    /** An opened table: the blobs in order, and the key that authenticates their bytes. */
    class Opened(val entries: List<Entry>, internal val macKey: ByteArray)

    /**
     * Opens the table at the head of [input] under [backupKey], leaving [input] at the first
     * blob. Null for every failure — wrong words, a truncated file, a file that is not a
     * backup — because the restore says one thing for all of them. [backupKey] stays the
     * caller's.
     */
    fun open(input: InputStream, backupKey: ByteArray): Opened? {
        val nonce = ByteArray(NONCE_BYTES)
        val length = ByteArray(LENGTH_BYTES)
        if (!readFully(input, nonce) || !readFully(input, length)) return null
        val sealedLength = ByteBuffer.wrap(length).order(ByteOrder.LITTLE_ENDIAN).int
        if (sealedLength <= TAG_BYTES || sealedLength > MAX_SEALED) return null
        val sealed = ByteArray(sealedLength)
        if (!readFully(input, sealed)) return null

        val toc = ByteArray(sealedLength - TAG_BYTES)
        try {
            val ok = Sodium.lazy.cryptoAeadXChaCha20Poly1305IetfDecrypt(
                toc, longArrayOf(0),
                null,
                sealed, sealed.size.toLong(),
                AAD, AAD.size.toLong(),
                nonce, backupKey,
            )
            if (!ok) return null
            val buf = ByteBuffer.wrap(toc).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.int != VERSION) return null
            val count = buf.int
            if (count < 0 || TOC_HEAD + count.toLong() * ENTRY_BYTES > toc.size) return null
            val macKey = ByteArray(MAC_BYTES).also { buf.get(it) }
            val entries = List(count) {
                val id = ByteArray(ID_BYTES).also { buf.get(it) }
                val len = buf.long
                if (len < 0) return null
                Entry(id.joinToString("") { b -> "%02x".format(b) }, len)
            }
            return Opened(entries, macKey)
        } finally {
            toc.fill(0)
        }
    }

    /**
     * Streams the blobs after the table into [blobsDir] and checks the file's MAC at the
     * end. Nothing survives a failure: every blob this call wrote is deleted, because a
     * partial vault is not a restore.
     *
     * [skipExisting] is a second vault restored onto a device that already has one —
     * `backup-to-cloud-apps` D13. A name already in the store (the shared keyring, above all,
     * which the words just rewrote) is read past and left alone; blob IDs are random 128-bit
     * values, so any other overlap is the same blob.
     *
     * Returns the number of blobs written, or -1. A read that throws deletes what it wrote
     * and rethrows — `backup-file-recover-and-import`, D6: the store may hold another list.
     */
    fun unpack(
        input: InputStream,
        opened: Opened,
        blobsDir: File,
        skipExisting: Boolean = false,
        /**
         * Names written to [sideDir] instead of [blobsDir] — the keyring's copy when a second
         * vault is restored, which is merged into the open keyring rather than replacing it.
         */
        side: Set<String> = emptySet(),
        sideDir: File? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        blobsDir.mkdirs()
        val written = mutableListOf<File>()
        try {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(opened.macKey, "HmacSHA256")) }
            val buf = ByteArray(COPY_BUFFER)
            val entries = opened.entries
            onProgress(0, entries.size)
            entries.forEachIndexed { i, e ->
                // Names come from inside the seal, but they are still names on a filesystem.
                if (e.name.length != ID_BYTES * 2 || !e.name.all { it in '0'..'9' || it in 'a'..'f' }) return fail(written)
                val toSide = sideDir != null && e.name in side
                val target = if (toSide) File(sideDir!!.also { it.mkdirs() }, e.name) else File(blobsDir, e.name)
                val keep = toSide || !(skipExisting && target.exists())
                val out = if (keep) target.outputStream().also { written += target } else null
                var left = e.length
                try {
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out?.write(buf, 0, n)
                        mac.update(buf, 0, n)
                        left -= n
                    }
                } finally {
                    out?.close()
                }
                if (left != 0L) return fail(written)
                onProgress(i + 1, entries.size)
            }
            val tag = ByteArray(MAC_BYTES)
            if (!readFully(input, tag) || !MessageDigest.isEqual(tag, mac.doFinal())) return fail(written)
            return written.size
        } catch (e: Throwable) {
            fail(written)
            throw e
        } finally {
            opened.macKey.fill(0)
        }
    }

    /**
     * A backup file as `VaultLifecycle` takes one — `backup-file-recover-and-import`, D3.
     * [openStream] gives a fresh stream each time: once to check the words, once to unpack. It opens
     * when the words open the sealed table; it unpacks into [blobsDir] with nothing already
     * there overwritten, and the keyring's copy into the side directory.
     */
    fun source(blobsDir: File, openStream: () -> InputStream?): VaultLifecycle.BackupSource =
        VaultLifecycle.BackupSource(
            opens = { key ->
                val backupKey = VaultKeys.backupKey(key)
                try {
                    openStream()?.use { input -> open(input, backupKey)?.also { it.macKey.fill(0) } != null } ?: false
                } finally {
                    backupKey.fill(0)
                }
            },
            unpack = { key, sideDir, progress ->
                val backupKey = VaultKeys.backupKey(key)
                try {
                    (openStream() ?: error("the backup file could not be opened")).use { input ->
                        val opened = open(input, backupKey)
                        opened != null && unpack(
                            input, opened, blobsDir, skipExisting = true,
                            side = setOf(hex(VaultKeys.keyringBlobId(key))), sideDir = sideDir,
                            onProgress = progress,
                        ) >= 0
                    }
                } finally {
                    backupKey.fill(0)
                }
            },
        )

    private fun fail(written: List<File>): Int {
        written.forEach { it.delete() }
        return -1
    }

    // --- internals ------------------------------------------------------------------

    private fun hexBytes(name: String): ByteArray =
        ByteArray(name.length / 2) { i -> name.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun le32(v: Int) = ByteArray(LENGTH_BYTES).also {
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(v)
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
