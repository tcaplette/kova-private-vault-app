package com.app.vault

import android.content.Context
import android.util.Base64
import com.goterl.lazysodium.interfaces.Box
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Intruder capture, everything but the camera — P2-4.
 *
 * The camera and microphone live in [IntruderCapture]; this holds the parts that
 * work with no VMK and no hardware: the seal, the enrolment state, and the per-mode
 * pending queue. It is called from two places that never overlap in time — a
 * *failed* unlock, when the vault is locked, seals and enqueues; a *successful*
 * unlock ingests.
 *
 * **Why a sealed box.** A capture is taken at a failed unlock, so there is no VMK
 * to derive a symmetric key from, and it must be readable only by the owner later.
 * `crypto_box_seal` needs nothing but the recipient's public key and leaves no
 * sender identity — the exact shape of "an untrusted, locked process writes
 * something only the owner can read". The public key is [destinationKey], stored at
 * enrolment while the destination vault was open; the secret half is re-derived
 * from that vault's VMK at ingest ([VaultKeys.captureKeypair]).
 *
 * **What it leaks**, recorded in `FORMAT.md` §10 rather than argued away: an
 * adversary who can read app-private storage learns capture is enrolled and which
 * modes are on — not which vault, not what was seen, not how many vaults exist.
 */
object Capture {

    /** The three modes. Each is a permission, a toggle, and a pending queue. */
    enum class Mode(internal val tag: String) {
        PHOTO("photo"),
        AUDIO("audio"),
        VIDEO("video"),
    }

    /** Per mode, FIFO. `OUTLINE.md` P2-4. */
    const val PER_MODE_CAP = 50

    private const val PREFS = "capture"
    private const val KEY_DESTINATION = "destination"        // base64 X25519 public key
    private const val KEY_ENABLED_PREFIX = "enabled_"        // + mode.tag
    private const val KEY_PENDING_PREFIX = "pending_"        // + mode.tag, CSV of "blobHex:sealedLen"

    private val sodium = Sodium.lazy
    private val rng = SecureRandom()

    // --- the seal --------------------------------------------------------------

    /**
     * Seals [plaintext] to a vault's capture public key. Anyone can call this — that
     * is the point of an anonymous sealed box — and only the matching secret key
     * opens it.
     */
    fun seal(publicKey: ByteArray, plaintext: ByteArray): ByteArray {
        require(publicKey.size == Box.PUBLICKEYBYTES) { "public key must be ${Box.PUBLICKEYBYTES} bytes" }
        val sealed = ByteArray(plaintext.size + Box.SEALBYTES)
        val ok = sodium.cryptoBoxSeal(sealed, plaintext, plaintext.size.toLong(), publicKey)
        check(ok) { "capture seal failed" }
        return sealed
    }

    /**
     * Opens a sealed capture, or null if this keypair did not seal it — a foreign
     * key authenticates nothing, exactly as a foreign VMK finds no log entries. The
     * public key is needed too: `crypto_box_seal_open` recomputes the shared secret
     * from both halves.
     */
    fun open(secretKey: ByteArray, publicKey: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < Box.SEALBYTES) return null
        val plain = ByteArray(sealed.size - Box.SEALBYTES)
        val ok = sodium.cryptoBoxSealOpen(plain, sealed, sealed.size.toLong(), publicKey, secretKey)
        return if (ok) plain else null
    }

    // --- enrolment -------------------------------------------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context, mode: Mode): Boolean =
        prefs(context).getBoolean(KEY_ENABLED_PREFIX + mode.tag, false)

    fun setEnabled(context: Context, mode: Mode, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED_PREFIX + mode.tag, on).apply()
    }

    /** True when any mode is on — the cheap check the lock screen makes before capturing. */
    fun anyEnabled(context: Context): Boolean = Mode.entries.any { enabled(context, it) }

    /** The destination vault's capture public key, or null if capture was never enrolled. */
    fun destinationKey(context: Context): ByteArray? =
        prefs(context).getString(KEY_DESTINATION, null)
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.takeIf { it.size == Box.PUBLICKEYBYTES }

    /**
     * Records the vault open at enrolment as the destination. Replaces any previous
     * one — there is a single destination, and re-enrolling from a different vault
     * strands captures still sealed to the old key, which the screen states.
     */
    fun setDestination(context: Context, publicKey: ByteArray) {
        require(publicKey.size == Box.PUBLICKEYBYTES) { "public key must be ${Box.PUBLICKEYBYTES} bytes" }
        prefs(context).edit()
            .putString(KEY_DESTINATION, Base64.encodeToString(publicKey, Base64.NO_WRAP))
            .apply()
    }

    // --- pending queue, per mode, FIFO -----------------------------------------

    /**
     * One pending sealed capture: its blob ID and the sealed length to feed
     * `crypto_box_seal_open` at ingest.
     *
     * **The length lives here, in app-private prefs, and never in the blob.** A
     * length byte inside the blob would refine its true size below the §5 bucket and
     * break the invariant that every file in `blobs/` reveals nothing — the same
     * leak §10 item 11 keeps partials out of the pool to avoid. In prefs it is
     * transient (gone at the next unlock) and it sits beside the enrolment residue
     * §10 already records.
     */
    class Pending(val blobId: ByteArray, val sealedLength: Int)

    /** The pending captures for [mode], oldest first. */
    fun pending(context: Context, mode: Mode): List<Pending> =
        prefs(context).getString(KEY_PENDING_PREFIX + mode.tag, "")!!
            .split(",")
            .filter { it.isNotEmpty() }
            .map { entry ->
                val (hex, len) = entry.split(":")
                Pending(hexToBytes(hex), len.toInt())
            }

    /**
     * Records a freshly sealed capture and enforces the cap: past [PER_MODE_CAP],
     * the oldest pending blob is deleted from the store and dropped from the list.
     *
     * Works with no VMK, because eviction *deletes* the oldest sealed blob rather
     * than opening it — the whole reason the cap can be enforced from a locked
     * screen.
     */
    fun add(context: Context, mode: Mode, sealedBlobId: ByteArray, sealedLength: Int, store: BlobStore) {
        val queue = pending(context, mode).toMutableList()
        queue += Pending(sealedBlobId, sealedLength)
        while (queue.size > PER_MODE_CAP) {
            store.delete(queue.removeAt(0).blobId)
        }
        writeQueue(context, mode, queue)
    }

    /**
     * Clears a mode's pending list after ingest and returns what was on it, oldest
     * first. The caller (the ingest loop) deletes each blob once its plaintext is an
     * item.
     */
    fun drainPending(context: Context, mode: Mode): List<Pending> {
        val ids = pending(context, mode)
        writeQueue(context, mode, emptyList())
        return ids
    }

    private fun writeQueue(context: Context, mode: Mode, queue: List<Pending>) {
        val csv = queue.joinToString(",") { p ->
            p.blobId.joinToString("") { "%02x".format(it) } + ":" + p.sealedLength
        }
        prefs(context).edit().putString(KEY_PENDING_PREFIX + mode.tag, csv).apply()
    }

    /** A fresh blob ID for a sealed capture. Random, like every content blob's. */
    fun newBlobId(): ByteArray = ByteArray(BlobStore.BLOB_ID_BYTES).also { rng.nextBytes(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Test-only. A cleared install has never enrolled. */
    fun resetForTest(context: Context) {
        prefs(context).edit().clear().commit()
    }
}

/**
 * The stage record — `cover-fingerprint-dots` D6a, `FORMAT.md` §9.
 *
 * Twelve bytes written ahead of the media in the plaintext handed to [Capture.seal], so where
 * an attempt failed and when the capture was taken are readable only by the destination vault,
 * exactly as the media is. The pending list in preferences is unchanged — blob ID and sealed
 * length only — so nothing outside the sealed blob states a stage or a moment.
 *
 * Its own object and not [Capture]'s: that one initialises libsodium, which the JVM suite cannot
 * load, and this is byte arithmetic the JVM suite should drive.
 */
object CaptureRecord {

    const val BYTES = 12
    const val VERSION = 1

    // `cover-hold-dots` D7: 1 was the cover's fingerprint, retired with it and never written.
    const val STAGE_COVER_PATTERN = 2
    const val STAGE_UNLOCK_BIOMETRIC = 3
    const val STAGE_PASSPHRASE = 4

    /** A vault's own passphrase sheet — `per-vault-intruder-capture` D3. Sealed to that vault. */
    const val STAGE_VAULT_PASSPHRASE = 5

    /**
     * The dots were shown — after the owner's hold (`cover-hold-dots` D7), or after the
     * calculator's sequence (`calculator-dots-methods` D5).
     */
    const val FLAG_DOTS_SHOWN = 1

    /** Refused by the limiter, so never compared. */
    const val FLAG_LIMITED = 2

    /**
     * The capture came out blank (photo, video) or silent (audio) — `per-vault-intruder-capture`
     * D10. A result, never a cause.
     */
    const val FLAG_BLANK = 4

    const val COVER_NONE = 0
    const val COVER_MUSIC = 1
    const val COVER_RECORDER = 2
    const val COVER_CALCULATOR = 3

    /** One failed attempt's stage, as it is sealed and as the `0x41` entry's payload carries it. */
    class Stage(val stage: Int, val flags: Int = 0, val cover: Int = COVER_NONE) {
        /** `stage ‖ flags ‖ cover` — three bytes, so the log's `count` still reads 1. */
        fun payload(): ByteArray = byteArrayOf(stage.toByte(), flags.toByte(), cover.toByte())

        /** This stage with [FLAG_BLANK] set when [blank] — `per-vault-intruder-capture` D10. */
        fun blankIf(blank: Boolean): Stage = if (blank) Stage(stage, flags or FLAG_BLANK, cover) else this
    }

    /**
     * The cover byte for [cover]. The calculator captures only on its shown dots — its sequence
     * never fires one — `calculator-dots-methods` D5.
     */
    fun coverCode(cover: Cover?): Int = when (cover) {
        Cover.MusicPlayer -> COVER_MUSIC
        Cover.VoiceRecorder -> COVER_RECORDER
        Cover.Calculator -> COVER_CALCULATOR
        null -> COVER_NONE
    }

    /** [stage] taken at [takenAt], ahead of [media]: the plaintext to seal. The caller zeroes [media]. */
    fun prepend(stage: Stage, takenAt: Long, media: ByteArray): ByteArray {
        val plain = ByteArray(BYTES + media.size)
        plain[0] = VERSION.toByte()
        plain[1] = stage.stage.toByte()
        plain[2] = stage.flags.toByte()
        plain[3] = stage.cover.toByte()
        ByteBuffer.wrap(plain, 4, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(takenAt)
        media.copyInto(plain, BYTES)
        return plain
    }

    /** What an opened plaintext says about itself. The media is everything after [BYTES]. */
    class Read(val stage: Stage, val takenAt: Long)

    /**
     * The record off an opened plaintext, or null for one shorter than the record or of a version
     * this build did not write — not a capture this build sealed, so it is dropped.
     */
    fun read(plain: ByteArray): Read? {
        if (plain.size < BYTES || plain[0].toInt() != VERSION) return null
        val takenAt = ByteBuffer.wrap(plain, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long
        return Read(Stage(plain[1].toInt() and 0xFF, plain[2].toInt() and 0xFF, plain[3].toInt() and 0xFF), takenAt)
    }
}
