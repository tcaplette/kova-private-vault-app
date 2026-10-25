package com.app.vault

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A memo's pending record — `voice-memo`, `destination-settings-and-slideshow` D5, `FORMAT.md` §10.
 *
 * A memo is sealed under its own **memo key** from its first byte, so the recorder never needs the
 * vault's key and keeps going when the vault locks. What the memo key is, which blobs it sealed,
 * the folder the memo was started in and when it started are written here, **sealed to the vault's
 * capture public key** with `crypto_box_seal` — the intruder capture's construction — so only that
 * vault can open it, and only at its next unlock.
 *
 * **Re-sealed as each blob is claimed**, so a recording cut off by the process dying is found and
 * kept up to its last whole chunk. ADTS frames itself, so what was written plays to there.
 *
 * **The blob IDs are also kept in the clear beside the sealed record.** The unlock's sweep of
 * orphaned partials runs with any vault open, and a recording's last blob is a partial until the
 * recording stops: without the IDs it would sweep a live memo, or a killed one waiting for another
 * vault. It is the residue the capture queue already leaves — random IDs waiting for something —
 * and it is gone when the memo is saved, discarded or ingested.
 */
object MemoPending {

    private const val PREFS = "memo"
    private const val KEY_PREFIX = "pending_"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A fresh identity for one memo's record. Random, and nothing but a name for the entry. */
    fun newId(): String = hex(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })

    /**
     * Seals [record] to [publicKey] and stores it under [id], replacing what was there. `commit`,
     * not `apply`: a record that is not on disk when the process dies is a memo that is lost.
     */
    fun write(context: Context, id: String, publicKey: ByteArray, record: MemoRecord.Record) {
        val plain = MemoRecord.encode(record)
        try {
            val sealed = Capture.seal(publicKey, plain)
            val ids = record.blobIds.joinToString(",") { hex(it) }
            prefs(context).edit()
                .putString(KEY_PREFIX + id, ids + "|" + Base64.encodeToString(sealed, Base64.NO_WRAP))
                .commit()
        } finally {
            plain.fill(0)
        }
    }

    /** Every pending memo's identity. */
    fun ids(context: Context): List<String> =
        prefs(context).all.keys.filter { it.startsWith(KEY_PREFIX) }.map { it.removePrefix(KEY_PREFIX) }

    /** Every blob a pending memo claimed, as lowercase hex — what the partial sweep must leave. */
    fun claimedBlobIds(context: Context): Set<String> =
        prefs(context).all.filterKeys { it.startsWith(KEY_PREFIX) }.values
            .flatMap { (it as? String)?.substringBefore('|')?.split(',').orEmpty() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * The record under [id], opened with [keypair], or null when another vault sealed it — that
     * vault's memo stays pending, the capture design's rule. The caller zeroes the memo key.
     */
    fun open(context: Context, id: String, keypair: CaptureKeypair): MemoRecord.Record? {
        val stored = prefs(context).getString(KEY_PREFIX + id, null) ?: return null
        val sealed = runCatching { Base64.decode(stored.substringAfter('|'), Base64.NO_WRAP) }.getOrNull()
            ?: return null
        val plain = Capture.open(keypair.secretKey, keypair.publicKey, sealed) ?: return null
        return try {
            MemoRecord.decode(plain)
        } finally {
            plain.fill(0)
        }
    }

    fun remove(context: Context, id: String) {
        prefs(context).edit().remove(KEY_PREFIX + id).commit()
    }

    /** Test-only. */
    fun resetForTest(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * The pending record's bytes — pure, so the JVM suite holds the layout. Inside the sealed box:
 *
 * `version (1) ‖ memo key (32) ‖ started at (i64) ‖ length (i64, -1 while recording) ‖
 * has folder (1) ‖ folder ID (16, if present) ‖ blob count (u16) ‖ blob IDs (16 each)`
 */
object MemoRecord {

    const val VERSION = 1
    const val KEY_BYTES = 32
    private const val ID_BYTES = 16

    class Record(
        /** The memo key. The holder zeroes it. */
        val memoKey: ByteArray,
        val startedAt: Long,
        /** Plaintext bytes, or -1 while still recording — a killed recording ends that way. */
        val length: Long,
        val folderId: ByteArray?,
        val blobIds: List<ByteArray>,
    )

    fun encode(r: Record): ByteArray {
        require(r.memoKey.size == KEY_BYTES) { "a memo key is $KEY_BYTES bytes" }
        val size = 1 + KEY_BYTES + 8 + 8 + 1 + (if (r.folderId != null) ID_BYTES else 0) + 2 +
            r.blobIds.size * ID_BYTES
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(VERSION.toByte())
        b.put(r.memoKey)
        b.putLong(r.startedAt)
        b.putLong(r.length)
        if (r.folderId != null) {
            b.put(1); b.put(r.folderId)
        } else {
            b.put(0)
        }
        b.putShort(r.blobIds.size.toShort())
        r.blobIds.forEach { b.put(it) }
        return b.array()
    }

    /** The record, or null for bytes this build did not write. */
    fun decode(plain: ByteArray): Record? = runCatching {
        val b = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
        if (b.get().toInt() != VERSION) return null
        val key = ByteArray(KEY_BYTES).also { b.get(it) }
        val startedAt = b.long
        val length = b.long
        val folder = if (b.get().toInt() == 1) ByteArray(ID_BYTES).also { b.get(it) } else null
        val count = b.short.toInt() and 0xFFFF
        val ids = List(count) { ByteArray(ID_BYTES).also { b.get(it) } }
        if (b.hasRemaining()) return null
        Record(key, startedAt, length, folder, ids)
    }.getOrNull()
}
