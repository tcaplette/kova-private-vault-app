package com.app.vault

import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Every key in the app — `FORMAT.md` §3.
 *
 * Argon2id over the passphrase and the install salt gives the KEK. The KEK opens
 * a slot and yields the VMK. Every other key is derived from the VMK by
 * `crypto_kdf_derive_from_key` under a distinct 8-byte context.
 *
 * Stateless, and nothing is cached: a cached subkey is a key living longer than
 * the operation that needed it, and derivation is one BLAKE2b compression.
 *
 * Passphrases arrive as `ByteArray`. There is deliberately no `String` overload —
 * a `String` cannot be zeroed and survives until GC. P1-10 owns the boundary
 * above this one, where Compose hands over exactly that.
 */
/**
 * A vault's intruder-capture keypair — P2-4.
 *
 * The public half seals a capture from a locked screen; the secret half exists only
 * inside a VMK derivation and is the caller's to zero.
 */
class CaptureKeypair(val publicKey: ByteArray, val secretKey: ByteArray) {
    fun zero() {
        secretKey.fill(0)
    }
}

object VaultKeys {

    /**
     * Every input to the KEK derivation that is not the passphrase or the salt —
     * `FORMAT.md` §4.
     *
     * `parallelism` is recorded for the format's sake but is not passed to
     * libsodium: `crypto_pwhash` fixes Argon2id at one lane and exposes no knob,
     * so p=1 is forced, not chosen.
     *
     * **The algorithm belongs here with the costs.** `crypto_pwhash` takes it as
     * an argument, so a build that changed it would derive a different KEK from an
     * unchanged passphrase — the same failure a retune causes, through a different
     * field. Storing three of the four inputs would leave the fourth live.
     */
    data class Argon2idParams(
        val memoryKib: Int,
        val iterations: Int,
        val parallelism: Int,
        val alg: PwHash.Alg = PwHash.Alg.PWHASH_ALG_ARGON2ID13
    ) {
        /**
         * The §4 header, 10 bytes: layout version, algorithm, then the three costs.
         * Fixed width, little-endian, and written before any key exists — these are
         * inputs to the derivation that produces the key that would decrypt them,
         * so they cannot be sealed.
         */
        fun toHeader(): ByteArray {
            require(memoryKib in 1..Int.MAX_VALUE) { "memoryKib out of range" }
            require(iterations in 1..0xFFFF) { "iterations must fit u16" }
            require(parallelism in 1..0xFFFF) { "parallelism must fit u16" }
            val out = ByteArray(HEADER_BYTES)
            out[0] = SLOT_FORMAT_VERSION.toByte()
            out[1] = alg.value.toByte()
            ByteBuffer.wrap(out, 2, 8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(memoryKib)
                .putShort(iterations.toShort())
                .putShort(parallelism.toShort())
            return out
        }

        companion object {
            /** Bytes 16..26 of `vault.slots`. */
            const val HEADER_BYTES = 10

            /**
             * The §4 layout this build writes. Distinct from the costs: the costs
             * discriminate *derivation* changes, this discriminates *layout* ones —
             * a ninth slot, a wider slot, a different AAD scheme — which the costs
             * cannot express.
             */
            const val SLOT_FORMAT_VERSION = 1

            /**
             * Parses a §4 header. Throws on a layout version this build does not
             * know, because guessing at an unknown layout is worse than refusing.
             */
            fun fromHeader(header: ByteArray): Argon2idParams {
                require(header.size == HEADER_BYTES) { "header must be $HEADER_BYTES bytes" }
                val version = header[0].toInt() and 0xFF
                check(version == SLOT_FORMAT_VERSION) {
                    "unknown slot-array layout version $version"
                }
                val buf = ByteBuffer.wrap(header, 2, 8).order(ByteOrder.LITTLE_ENDIAN)
                return Argon2idParams(
                    memoryKib = buf.int,
                    iterations = buf.short.toInt() and 0xFFFF,
                    parallelism = buf.short.toInt() and 0xFFFF,
                    alg = PwHash.Alg.valueOf(header[1].toInt() and 0xFF)
                )
            }
        }
    }

    /**
     * The parameters a **new** install is created with, and nothing else.
     *
     * Retuning this is safe and is a one-value edit: an install records what it was
     * created with (§4) and derives under that for life, so a later build's retune
     * reaches new installs only. Never read on an unlock path — `VaultKeysTest`
     * fails if it is.
     *
     * **Provisional.** t=8 lands near 500 ms on an x86_64 emulator, which is not an
     * ARM phone.
     */
    val ARGON2ID_PARAMS = Argon2idParams(memoryKib = 64 * 1024, iterations = 8, parallelism = 1)

    /**
     * Every parameter set the app has published, oldest first — P2-25.
     *
     * An archive records nothing about what sealed it, because a recorded block
     * would be a fingerprint §11 forbids. It does not need to: the importing side
     * is this same app, so it already knows every set it has ever shipped and tries
     * each. Unlock cannot do this — P1-3 holds it to one derivation — which is why
     * `vault.slots` records its parameters and the archive does not.
     *
     * Append only. Never reorder, never remove: a `.vault` written under an entry
     * that leaves this list stops importing, and it may be sitting in someone's
     * cloud folder where no later release can reach it.
     *
     * The **last** entry is the newest published set and is therefore
     * [ARGON2ID_PARAMS]; an archive is sealed under it. Checked below rather than
     * assumed, because the whole list is worthless if its order is not what the
     * reader thinks.
     *
     * `var` only so `VaultArchiveTest` can stand a retune up inside one build. A
     * cross-release property cannot be tested any other way — there is only ever
     * one build in the test runner — and the test this seam exists for is the one
     * that proves an archive from an earlier release still imports. Nothing in
     * `main` assigns it.
     *
     * ponytail: linear scan, import cost is one Argon2id derivation per entry. With
     * two or three entries that is noise against copying gigabytes of blobs. If it
     * ever grows long enough to feel, the archive gains a recorded parameter block
     * and pays §11's fingerprint then, when it buys something.
     */
    var RELEASED_KDF_PARAMS: List<Argon2idParams> = listOf(ARGON2ID_PARAMS)
        set(value) {
            check(value.isNotEmpty()) { "the published parameter list cannot be empty" }
            field = value
        }

    /** The newest published set, which is what a new archive is sealed under. */
    fun currentKdfParams(): Argon2idParams = RELEASED_KDF_PARAMS.last()

    const val KEY_BYTES = 32
    const val VMK_BYTES = 32
    const val SALT_BYTES = PwHash.SALTBYTES          // 16, matches FORMAT.md §4

    // crypto_kdf contexts are exactly 8 bytes.
    private const val CTX_METADATA = "vaultmet"
    private const val CTX_THUMBNAIL = "vaultthm"
    private const val CTX_LOG = "vaultlog"
    private const val CTX_BLOB = "vaultblb"
    private const val CTX_INDEX_ID = "vaultidx"
    private const val CTX_CAPTURE = "vaultcap"

    /**
     * The keyring's per-entry context — `vault-keyring`.
     *
     * **Domain-separated from every context above, and that is the point.** An entry
     * key is derived from the *keyring* key, not from a VMK, so it is the one context
     * in this file whose input is a different kind of key — and a collision with any
     * of the six above would mean a vault's metadata key and some entry's sealing key
     * were the same bytes.
     */
    private const val CTX_KEYRING_ENTRY = "vaultkyr"

    /**
     * The keyring blob's own context — `vault-keyring`, and see [BlobContext.KEYRING]
     * for why the separation carries weight beyond tidiness.
     */
    private const val CTX_KEYRING_BLOB = "vaultkrb"

    /**
     * `screen-off-lock-all-screens`, D5. A recovery kit held across a lock is sealed under
     * a subkey of the keyring key, so only that keyring opening again can read it.
     */
    private const val CTX_HELD_KIT = "vaultkit"

    /**
     * `backup-to-cloud-apps`, `FORMAT.md` §14. A backup file's table of contents is sealed
     * under a subkey of the keyring key, so the 24 words — which **are** that key — open
     * it with nothing else carried over, and no passphrase can.
     */
    private const val CTX_BACKUP = "vaultbak"

    /**
     * The context [keyringEntrySalt] derives under — `backup-file-recover-and-import`, D2.
     *
     * A subkey of the **keyring** key, like [CTX_KEYRING_ENTRY], and separated from it for
     * the same reason: a salt and a sealing key must never be the same bytes.
     */
    private const val CTX_ENTRY_SALT = "vaultkes"

    /**
     * The context [captureStoreKey] derives under — `intruder-captures-at-home` D1. A subkey
     * of the **keyring** key, like [CTX_KEYRING_ENTRY], so captures belong to a keyring and
     * no vault's key reaches them.
     */
    private const val CTX_CAPTURE_STORE = "vaultcst"

    /** `FORMAT.md` §5. The index blob's ID, like every blob ID, is 16 bytes. */
    const val BLOB_ID_BYTES = 16

    private val sodium = Sodium.lazy
    private val rng = SecureRandom()

    /**
     * Argon2id(passphrase, salt, params) → 32 byte KEK. Deterministic in all of them.
     *
     * [params] is an argument rather than a constant read here because a build's own
     * constant is not the one an existing install was written under. Callers on an
     * unlock path pass what the slot file recorded; only vault creation passes
     * [ARGON2ID_PARAMS].
     */
    fun deriveKek(passphrase: ByteArray, salt: ByteArray, params: Argon2idParams): ByteArray {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }

        val kek = ByteArray(KEY_BYTES)
        val ok = sodium.cryptoPwHash(
            kek, kek.size,
            passphrase, passphrase.size,
            salt,
            params.iterations.toLong(),
            NativeLong(params.memoryKib.toLong() * 1024L),
            params.alg
        )
        check(ok) { "Argon2id failed — could not allocate ${params.memoryKib / 1024} MiB?" }
        return kek
    }

    /** A fresh vault master key. */
    fun newVmk(): ByteArray = ByteArray(VMK_BYTES).also { rng.nextBytes(it) }

    /**
     * A fresh keyring key — `vault-keyring`. The same width and the same source as a
     * VMK, because it is sealed in the same 48-byte slot payload the VMK used to
     * occupy and no byte of that payload moves.
     */
    fun newKeyringKey(): ByteArray = ByteArray(KEY_BYTES).also { rng.nextBytes(it) }

    /**
     * The key sealing the vault key at [position] in a keyring — `vault-keyring`.
     *
     * Derived from the keyring key and the position, so **opening a keyring unwraps
     * nothing**: reading the names needs the keyring key alone, and each vault key
     * needs a further derivation that only happens when that vault is opened. One
     * BLAKE2b compression per vault opened, against every vault's key sitting in
     * memory for the length of an unlocked session.
     *
     * The position is the subkey ID rather than part of a context string, for
     * [blobKey]'s reason: `crypto_kdf` takes a 64-bit ID and contexts are exactly
     * eight bytes.
     */
    fun keyringEntryKey(keyringKey: ByteArray, position: Int): ByteArray {
        require(position >= 0) { "entry position must not be negative" }
        return subkey(keyringKey, CTX_KEYRING_ENTRY, position.toLong())
    }

    /**
     * The salt a vault's **own** passphrase derives under — `backup-file-recover-and-import`,
     * D2.
     *
     * Derived from the keyring key and the position, so it is unique per keyring and per
     * entry and **the same on every phone the keyring reaches**. It used to be derived from
     * the install salt, and a protected vault restored onto a new phone then stopped opening
     * with its own passphrase. Not stored: a salt needs sixteen bytes the entry does not have.
     */
    fun keyringEntrySalt(keyringKey: ByteArray, position: Int): ByteArray {
        require(position >= 0) { "entry position must not be negative" }
        return subkey(keyringKey, CTX_ENTRY_SALT, position.toLong(), SALT_BYTES)
    }

    /**
     * The key sealing a vault whose passphrase is its own — `per-vault-passphrases`.
     *
     * The same Argon2id every other passphrase in the app meets, over [keyringEntrySalt] and
     * under the parameter set **the entry records** (`VaultKeyring.Entry.kdfSet`), never
     * whatever this build's newest set is: a retune would otherwise strand every protected
     * vault sealed before it.
     *
     * Called only from `VaultKeyringSession`, which holds [keyringKey] and does not hand it
     * out. [passphrase] belongs to the caller and is zeroed by the caller, exactly as
     * [deriveKek] leaves it.
     */
    fun entryPassphraseKek(
        passphrase: ByteArray,
        keyringKey: ByteArray,
        position: Int,
        params: Argon2idParams,
    ): ByteArray {
        val salt = keyringEntrySalt(keyringKey, position)
        return try {
            deriveKek(passphrase, salt, params)
        } finally {
            salt.fill(0)
        }
    }

    fun metadataKey(vmk: ByteArray): ByteArray = subkey(vmk, CTX_METADATA, 1)

    fun thumbnailKey(vmk: ByteArray): ByteArray = subkey(vmk, CTX_THUMBNAIL, 1)

    fun logSealKey(vmk: ByteArray): ByteArray = subkey(vmk, CTX_LOG, 1)

    fun logMacKey(vmk: ByteArray): ByteArray = subkey(vmk, CTX_LOG, 2)

    /**
     * The vault's index blob ID — P1-12.
     *
     * Derived, not random, so that 24 words are enough to find the index on an
     * install holding nothing but restored blobs. Without this, recovery would
     * have to try every blob in a flat directory as a candidate index.
     *
     * It is still written into the slot exactly where a random ID was, so nothing
     * on disk changes shape: `SlotArray` and `BiometricWrap` are untouched. To
     * anyone without the VMK the filename is as random-looking as it ever was.
     *
     * `crypto_kdf` accepts 16..64 bytes, so this is a derivation at the ID's own
     * length rather than a truncated 32-byte key.
     */
    fun metadataBlobId(vmk: ByteArray): ByteArray =
        subkey(vmk, CTX_INDEX_ID, 1, BLOB_ID_BYTES)

    /**
     * The keyring blob's ID — `vault-keyring`, and [metadataBlobId]'s rule one level
     * up for exactly its reason.
     *
     * **Derived and not random, so 24 words are enough.** The recovery kit stands in
     * for the keyring key; if the keyring's blob ID were random, the words would open
     * a key with nothing to point it at, and recovery would be back to trying every
     * blob in a flat directory as a candidate.
     *
     * A different subkey ID from the index's, so a keyring key and a VMK of the same
     * bytes could not name the same blob — they cannot be the same bytes, but the
     * separation costs a constant and removes the question.
     */
    fun keyringBlobId(keyringKey: ByteArray): ByteArray =
        subkey(keyringKey, CTX_INDEX_ID, 2, BLOB_ID_BYTES)

    /** The key a held recovery kit is sealed under — see [CTX_HELD_KIT]. */
    fun heldKitKey(keyringKey: ByteArray): ByteArray = subkey(keyringKey, CTX_HELD_KIT, 1)

    /**
     * The blob recording when this vault's backup file was last written, and how many
     * bytes — `backup-to-cloud-apps` D5. Derived from the VMK like [metadataBlobId], so no
     * file anywhere maps a vault to it; a different subkey ID keeps it apart from the
     * index's (1) and the keyring's (2).
     */
    fun backupRecordBlobId(vmk: ByteArray): ByteArray =
        subkey(vmk, CTX_INDEX_ID, 3, BLOB_ID_BYTES)

    /** The key a backup file's table of contents is sealed under — see [CTX_BACKUP]. */
    fun backupKey(keyringKey: ByteArray): ByteArray = subkey(keyringKey, CTX_BACKUP, 1)

    /**
     * The keyring's capture store key — `intruder-captures-at-home` D1.
     *
     * Used **as a VMK, for the capture store only**: the store's index blob ID is
     * [metadataBlobId] of it, its items and thumbnails are sealed under it, and the keypair
     * intruder captures are sealed to is [captureKeypair] of it. It is no entry on the
     * keyring, and no vault's key derives it or is derived from it. The caller zeroes it.
     *
     * **Applied to a VMK too** — `per-vault-intruder-capture` D1. A vault with its own
     * passphrase has a capture store of its own, keyed by this derivation of its VMK under the
     * same context, and its wrong-passphrase captures are sealed to [captureKeypair] of that —
     * not to [captureKeypair] of the VMK itself, which is the voice memos' keypair, so neither
     * queue opens the other's blobs.
     */
    fun captureStoreKey(keyringKey: ByteArray): ByteArray = subkey(keyringKey, CTX_CAPTURE_STORE, 1)

    /**
     * The X25519 capture keypair of a VMK — P2-4, and the app's **first asymmetric key**.
     *
     * Two keys are fed to it. A vault's own VMK gives the keypair its voice memos are sealed
     * to (`MemoPending`). The keyring's [captureStoreKey] gives the one intruder captures are
     * sealed to — `intruder-captures-at-home` — and no vault's keypair opens a capture.
     *
     * Every other key here is symmetric because every other write happens with the
     * vault open. A capture does not: it is taken at a *failed* unlock, which by
     * definition produced no VMK, and it must be readable only by the owner, later.
     * "Encrypt now, decrypt with a key I do not currently hold" is asymmetric
     * encryption, and `crypto_box_seal` is its minimum form — the public half is
     * all a locked screen needs, and it identifies no sender.
     *
     * **Derived from a key rather than held in the Keystore**, so captures belong
     * to the *keyring* and not to the device: the key comes back with the keyring's
     * 24 words, and it costs no Keystore alias. A Keystore
     * key would be invalidated by an enrolment change and lost on recovery, which is
     * the one moment the owner most wants to see who was holding the phone.
     *
     * [CaptureKeypair.secretKey] is the caller's to zero, exactly like every subkey
     * above. Only [CaptureKeypair.publicKey] may be stored outside the keyring.
     */
    fun captureKeypair(vmk: ByteArray): CaptureKeypair {
        val seed = captureSeed(vmk)
        try {
            val publicHalf = ByteArray(Box.PUBLICKEYBYTES)
            val secretHalf = ByteArray(Box.SECRETKEYBYTES)
            val ok = sodium.cryptoBoxSeedKeypair(publicHalf, secretHalf, seed)
            check(ok) { "capture keypair derivation failed" }
            return CaptureKeypair(publicHalf, secretHalf)
        } finally {
            seed.fill(0)
        }
    }

    /**
     * The seed the keypair expands from. Internal so `VaultKeysTest` can assert it
     * collides with no other subkey — the property §3's table claims for every row.
     */
    internal fun captureSeed(vmk: ByteArray): ByteArray =
        subkey(vmk, CTX_CAPTURE, 1, Box.SEEDBYTES)

    /**
     * Which key class a blob is sealed under — `FORMAT.md` §3 and §8.
     *
     * The separation is the whole of what a distinct thumbnail key buys: code
     * holding one class cannot derive the other, so a preview surface, or a bug
     * that leaks one class, does not yield originals. Both use the same
     * subkey-id rule, so nothing is stored per blob either way.
     */
    enum class BlobContext(internal val context: String) {
        CONTENT(CTX_BLOB),
        THUMBNAIL(CTX_THUMBNAIL),

        /**
         * The keyring blob — `vault-keyring`.
         *
         * **This context is what tells the two slot layouts apart, and that is not a
         * side effect.** A slot yields 48 bytes: a key and a blob ID. Under the old
         * layout they are a VMK and an index ID; under the new one a keyring key and
         * a keyring ID. The layout version in the file header cannot discriminate,
         * because that header is shared by all eight slots and the app can only ever
         * migrate the one slot whose passphrase it was given — a per-file flag would
         * claim one layout for a file that has two.
         *
         * So the app tries the keyring reading first. Because a keyring is sealed
         * under **this** context and an index under [CONTENT], the attempt fails
         * cryptographically on an old-layout slot rather than heuristically: an index
         * blob read as a keyring does not decrypt at all, instead of decrypting into
         * something that has to be judged.
         */
        KEYRING(CTX_KEYRING_BLOB),
    }

    /**
     * Per-blob DEK. The subkey ID is computed here from the blob ID rather than
     * passed in, so two callers cannot disagree about how it is derived.
     */
    fun blobKey(
        vmk: ByteArray,
        blobId: ByteArray,
        context: BlobContext = BlobContext.CONTENT
    ): ByteArray {
        require(blobId.size == 16) { "blob ID must be 16 bytes" }
        val subkeyId = ByteBuffer.wrap(blobId, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        return subkey(vmk, context.context, subkeyId)
    }

    private fun subkey(
        vmk: ByteArray,
        context: String,
        subkeyId: Long,
        length: Int = KEY_BYTES
    ): ByteArray {
        require(vmk.size == VMK_BYTES) { "VMK must be $VMK_BYTES bytes" }
        require(length in 16..64) { "crypto_kdf subkey length must be 16..64, not $length" }
        val ctx = context.toByteArray(Charsets.US_ASCII)
        check(ctx.size == 8) { "crypto_kdf context must be exactly 8 bytes, '$context' is ${ctx.size}" }

        val out = ByteArray(length)
        // libsodium convention: 0 is success, not a boolean.
        val result = sodium.cryptoKdfDeriveFromKey(out, out.size, subkeyId, ctx, vmk)
        check(result == 0) { "subkey derivation failed for context $context/$subkeyId (rc=$result)" }
        return out
    }
}
