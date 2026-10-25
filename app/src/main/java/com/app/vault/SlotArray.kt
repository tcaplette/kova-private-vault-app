package com.app.vault

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * The slot array — `FORMAT.md` §4.
 *
 * 2074 bytes: a 16 byte install salt, a 10 byte KDF header, then eight 256 byte
 * slots. The salt and all 2048 slot bytes come from a CSPRNG at first run, so an
 * unused slot is random and a used one is ciphertext, and neither is
 * distinguishable from the other without the key (I8).
 *
 * **The header is the one part that is not random, and it has to be** — P2-25. It
 * records the algorithm and costs this install's KEKs derive under, and those are
 * inputs to the derivation that produces the key that would decrypt them, so they
 * cannot be sealed. It is byte-identical across every install of a build, sits
 * outside the eight slots, and answers nothing about vault count or occupancy;
 * §10(6) already records the parameters as public, because they ship in the APK.
 *
 * This class knows nothing about passphrases. It takes a 32 byte KEK and does
 * not care what produced it; Argon2id derivation is P1-2. It holds no key
 * material — keys arrive as arguments and leave with the stack.
 */
class SlotArray(private val file: File) {

    companion object {
        const val SALT_BYTES = 16
        const val HEADER_BYTES = VaultKeys.Argon2idParams.HEADER_BYTES // 10
        const val HEADER_OFFSET = SALT_BYTES                          // 16
        const val SLOTS_OFFSET = HEADER_OFFSET + HEADER_BYTES         // 26
        const val SLOT_BYTES = 256
        const val SLOT_COUNT = 8
        const val FILE_BYTES = SLOTS_OFFSET + SLOT_COUNT * SLOT_BYTES // 2074

        const val KEY_BYTES = 32
        const val VMK_BYTES = 32
        const val BLOB_ID_BYTES = 16
        const val PAYLOAD_BYTES = VMK_BYTES + BLOB_ID_BYTES           // 48

        private const val NONCE_BYTES = 24
        private const val TAG_BYTES = 16
        private const val CIPHERTEXT_BYTES = PAYLOAD_BYTES + TAG_BYTES // 64

        private const val NONCE_OFFSET = 0
        private const val CIPHERTEXT_OFFSET = NONCE_OFFSET + NONCE_BYTES        // 24
        private const val PADDING_OFFSET = CIPHERTEXT_OFFSET + CIPHERTEXT_BYTES // 88
    }

    private val sodium = Sodium.lazy
    private val rng = SecureRandom()

    private fun slotOffset(index: Int) = (SLOTS_OFFSET + index * SLOT_BYTES).toLong()

    /** The slot index, bound as associated data so a slot cannot be transplanted. */
    private fun aad(index: Int) = byteArrayOf(index.toByte())

    /**
     * Creates the file if it is absent: [FILE_BYTES] of CSPRNG output with
     * [creationParams] written over the header region. Writes to a temp file and
     * renames — a crash partway through must not leave a file that is part random
     * and part zeroes, because the zeroes would be the one thing visible at a
     * glance.
     *
     * An existing file is left alone **including its header**. The recorded
     * parameters are the install's for life; a later build must not quietly
     * overwrite them with its own constant, because every sealed slot was sealed
     * under the recorded ones.
     */
    fun initialiseIfAbsent(creationParams: VaultKeys.Argon2idParams = VaultKeys.ARGON2ID_PARAMS) {
        if (file.exists()) return

        val bytes = ByteArray(FILE_BYTES).also { rng.nextBytes(it) }
        creationParams.toHeader().copyInto(bytes, HEADER_OFFSET)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            check(tmp.renameTo(file)) { "could not move slot file into place" }
        } finally {
            bytes.fill(0)
            tmp.delete()
        }
    }

    fun installSalt(): ByteArray = read(0L, SALT_BYTES)

    /**
     * The algorithm and costs this file's KEKs derive under — P2-25.
     *
     * Every unlock path reads this instead of [VaultKeys.ARGON2ID_PARAMS], which is
     * what lets a later build retune without stranding installs that already exist.
     */
    fun kdfParams(): VaultKeys.Argon2idParams =
        VaultKeys.Argon2idParams.fromHeader(read(HEADER_OFFSET.toLong(), HEADER_BYTES))

    /**
     * Seals [vmk] and [metadataBlobId] into slot [index] under [kek].
     *
     * The whole slot is regenerated from the CSPRNG first, so both the nonce and
     * the 168 byte tail are fresh on every write. Preserving the tail would leave
     * a region that never changes across writes, which distinguishes a slot
     * written once from one rewritten.
     *
     * **Atomic** — `recovery-own-slot-only`, D2. The slot is written into a copy of
     * the file which is then renamed over the original, [initialiseIfAbsent]'s
     * pattern: an interruption leaves the old file or the new one, never a torn
     * slot. Every other slot, the salt and the header are copied byte-for-byte.
     * [destroy] does not do this, because panic's cost must match an unlock's.
     */
    fun seal(index: Int, kek: ByteArray, vmk: ByteArray, metadataBlobId: ByteArray) {
        require(index in 0 until SLOT_COUNT) { "slot index out of range" }
        require(kek.size == KEY_BYTES) { "KEK must be $KEY_BYTES bytes" }
        require(vmk.size == VMK_BYTES) { "VMK must be $VMK_BYTES bytes" }
        require(metadataBlobId.size == BLOB_ID_BYTES) { "blob ID must be $BLOB_ID_BYTES bytes" }

        val slot = ByteArray(SLOT_BYTES).also { rng.nextBytes(it) }
        val nonce = slot.copyOfRange(NONCE_OFFSET, NONCE_OFFSET + NONCE_BYTES)

        val payload = ByteArray(PAYLOAD_BYTES)
        try {
            vmk.copyInto(payload, 0)
            metadataBlobId.copyInto(payload, VMK_BYTES)

            val ciphertext = ByteArray(CIPHERTEXT_BYTES)
            val sealed = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                ciphertext, longArrayOf(0),
                payload, PAYLOAD_BYTES.toLong(),
                aad(index), 1L,
                null, nonce, kek
            )
            check(sealed) { "slot seal failed" }
            ciphertext.copyInto(slot, CIPHERTEXT_OFFSET)
        } finally {
            payload.fill(0)
        }

        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.copyTo(tmp, overwrite = true)
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.seek(slotOffset(index))
                raf.write(slot)
                raf.fd.sync()
            }
            check(tmp.renameTo(file)) { "could not move slot file into place" }
        } finally {
            slot.fill(0)
            tmp.delete()
        }
    }

    /**
     * Overwrites slot [index] with CSPRNG output — P2-3.
     *
     * The inverse of [seal] and deliberately the same write path: one 256 byte
     * write at the slot's offset, `fd.sync()`, nothing else touched. What was
     * there cannot be opened again by the KEK that used to open it, and the result
     * is indistinguishable from a slot that was never written — which is what §4
     * already says an unused slot is.
     *
     * **Not a re-initialisation.** The install salt and the KDF header survive, so
     * every surviving slot's passphrase keeps working; that is the difference
     * between this and the fresh file `VaultLifecycle.recover` writes for a backup
     * restored onto an empty phone.
     */
    fun destroy(index: Int) {
        require(index in 0 until SLOT_COUNT) { "slot index out of range" }

        val slot = ByteArray(SLOT_BYTES).also { rng.nextBytes(it) }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(slotOffset(index))
            raf.write(slot)
            raf.fd.sync()
        }
        slot.fill(0)
    }

    /**
     * Opens slot [index] with [kek], returning `VMK ‖ metadata blob ID` or null.
     *
     * Null is the ordinary outcome for a wrong key *and* for a slot that was
     * never written — both fail AEAD authentication, on the same code path, which
     * is what makes them indistinguishable. Not an exception: a passphrase that
     * opens nothing is not an error.
     */
    fun open(index: Int, kek: ByteArray): ByteArray? {
        require(index in 0 until SLOT_COUNT) { "slot index out of range" }
        require(kek.size == KEY_BYTES) { "KEK must be $KEY_BYTES bytes" }

        val slot = read(slotOffset(index), SLOT_BYTES)
        val nonce = slot.copyOfRange(NONCE_OFFSET, NONCE_OFFSET + NONCE_BYTES)
        val ciphertext = slot.copyOfRange(CIPHERTEXT_OFFSET, PADDING_OFFSET)

        val payload = ByteArray(PAYLOAD_BYTES)
        val opened = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            payload, longArrayOf(0),
            null,
            ciphertext, CIPHERTEXT_BYTES.toLong(),
            aad(index), 1L,
            nonce, kek
        )

        return if (opened) {
            payload
        } else {
            payload.fill(0)
            null
        }
    }

    private fun read(offset: Long, length: Int): ByteArray =
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            ByteArray(length).also { raf.readFully(it) }
        }
}
