package com.app.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * The share container — `FORMAT.md` §12, version 2.
 *
 * One item, sealed into one file, for one recipient, under a key that derives
 * from **nothing in §3**: a leaked share credential opens the container it
 * sealed and says nothing about any vault.
 *
 * ***No byte of the file says what it is.*** It is the key block of its mode, a
 * nonce prefix and sealed chunks — no magic, no readable version, mode, policy or
 * expiry. Whatever app carries it, and anyone who looks at the phone afterwards,
 * sees binary data. Policy, share ID and the format version travel inside the
 * encryption, in the prelude, where the chunk tag authenticates them; the key
 * block is bound to every chunk as associated data under a per-mode label.
 *
 * **The envelope is frozen**: the key block per mode, the prefix, the chunk scheme,
 * the two labels, and prelude byte 0 being the format version. A later format that
 * keeps it is recognised once chunk 0 authenticates, and refused as
 * [NewerShareVersion]. Every other failure — a wrong credential, a file that is not
 * a share at all, any tamper, any cut — is the one [ShareFailure], because with no
 * readable header there is nothing that could tell them apart.
 *
 * This layer carries policy and makes it unforgeable; it does not enforce it.
 * Enforcement needs a ledger and a clock, and both belong to the receive flow.
 *
 * Streaming both ways, one chunk of memory, no file touched: the send flow pipes
 * `BlobStore`'s decrypt stream straight in, which is how I3 holds with no
 * plaintext ever staged.
 */
object ShareContainer {

    /** The prelude's format version — byte 0, the one field the frozen envelope promises. */
    const val VERSION = 2

    const val SHARE_ID_BYTES = 16

    /** Associated-data labels. No version in them, deliberately: they are envelope. */
    private val CODE_LABEL = "share.code".toByteArray(Charsets.US_ASCII)
    private val CONTACT_LABEL = "share.contact".toByteArray(Charsets.US_ASCII)

    /** §12 key blocks: a salt, or an X-Wing ciphertext (ML-KEM-768 ‖ X25519 ephemeral). */
    private const val CODE_BLOCK_BYTES = VaultKeys.SALT_BYTES
    private const val CONTACT_BLOCK_BYTES = XWing.CIPHERTEXT_BYTES

    // §12 chunking is §5's scheme: 64 KiB plaintext, 16 B tag, nonce = 16 B
    // prefix ‖ 8 B little-endian index. The one addition is the final-chunk
    // marker in the index's top bit — this file's only length authority is the
    // file itself, so the last chunk must say it is last or a cut at an exact
    // chunk boundary would read as a clean, shorter stream.
    private const val CHUNK = BlobStore.CHUNK
    private const val TAG_BYTES = 16
    private const val STRIDE = CHUNK + TAG_BYTES
    private const val PREFIX_BYTES = 16
    private const val NONCE_BYTES = 24
    private const val FINAL_BIT = 1L shl 63

    /** version ‖ policy ‖ expiry ‖ share ID ‖ kind — everything before the two lengths. */
    private const val PRELUDE_FIXED_BYTES = 1 + 1 + 8 + SHARE_ID_BYTES + 1

    /** The smallest prelude — empty name, empty MIME — and so the smallest chunk 0. */
    private const val MIN_PRELUDE_BYTES = PRELUDE_FIXED_BYTES + 2 + 2

    private val sodium = Sodium.lazy
    private val rng = SecureRandom()

    /**
     * §12 prelude kind byte.
     *
     * **`FILE` is code 5 and was added after the first four.** A kind is never
     * renumbered: the byte is the contract between two devices that may not be on
     * the same version.
     */
    enum class Kind(internal val code: Int) {
        IMAGE(1), VIDEO(2), NOTE(3), VOICE_MEMO(4), FILE(5);

        internal companion object {
            fun of(code: Int): Kind? = values().firstOrNull { it.code == code }
        }
    }

    /**
     * §12's policy byte and expiry field as one value, so a mode and its
     * timestamp cannot be passed disagreeing.
     */
    sealed class Policy {
        object Unmanaged : Policy()
        object SingleView : Policy()
        class Expiry(val atEpochMillis: Long) : Policy() {
            init {
                require(atEpochMillis > 0) { "an expiry is a positive instant" }
            }
        }
    }

    /** What the sender supplies for the prelude. The name is a display name, never a path. */
    class Prelude(val kind: Kind, val displayName: String, val mime: String)

    /**
     * Everything a successful open authenticated: the prelude's fields, including
     * the policy and share ID the receive flow enforces and ledgers. Trustworthy
     * because the chunk tag covers them — none could have been altered and still
     * decrypted.
     */
    class Opened(
        val kind: Kind,
        val displayName: String,
        val mime: String,
        val policy: Policy,
        val shareId: ByteArray,
    )

    /**
     * The one undifferentiated failure — §12. A wrong code, a wrong recipient, a
     * file that is not a share, a tamper, a truncation and a boundary cut all throw
     * exactly this, and nothing about it says which.
     */
    class ShareFailure : Exception("could not open share")

    /**
     * Chunk 0 authenticated and its prelude names a version above [VERSION] — §12's
     * one distinguishable refusal, decided only after authentication.
     */
    class NewerShareVersion : Exception("newer app needed")

    /** What [probeContact] found. */
    enum class Probe { SEALED_HERE, NEWER_VERSION, NOT_HERE }

    // ── Sealing ────────────────────────────────────────────────────────────────

    /**
     * Seals [content] under a 4-word code — §12 code mode.
     *
     * The share key is Argon2id over the code's canonical bytes with a fresh salt,
     * under [VaultKeys.currentKdfParams]. **The parameters are not written**: the
     * reader tries every published set, so the file carries the salt and nothing
     * else. The derived key and canonical bytes are zeroed here; [content] and
     * [out] belong to the caller.
     */
    fun sealWithCode(
        content: InputStream,
        out: OutputStream,
        prelude: Prelude,
        policy: Policy,
        codeWords: List<String>,
    ) {
        val salt = ByteArray(CODE_BLOCK_BYTES).also { rng.nextBytes(it) }
        val code = ShareCode.canonicalBytes(codeWords)
        val key = try {
            VaultKeys.deriveKek(code, salt, VaultKeys.currentKdfParams())
        } finally {
            code.fill(0)
        }
        try {
            writeSealed(out, CODE_LABEL, salt, key, policy, prelude, content)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Seals [content] to a recipient's 1,216-byte X-Wing public key — §12 contact
     * mode.
     *
     * The share key is the encapsulation's shared secret and the X-Wing ciphertext
     * is the key block. The encapsulation spends a fresh ephemeral, so the
     * container names no sender.
     */
    fun sealToRecipient(
        content: InputStream,
        out: OutputStream,
        prelude: Prelude,
        policy: Policy,
        recipientPublicKey: ByteArray,
    ) {
        require(recipientPublicKey.size == XWing.PUBLIC_KEY_BYTES) {
            "recipient public key must be ${XWing.PUBLIC_KEY_BYTES} bytes"
        }
        val encapsulated = XWing.encapsulate(recipientPublicKey, rng)
        try {
            writeSealed(
                out, CONTACT_LABEL, encapsulated.ciphertext, encapsulated.sharedSecret,
                policy, prelude, content,
            )
        } finally {
            encapsulated.sharedSecret.fill(0)
        }
    }

    // ── Opening ────────────────────────────────────────────────────────────────

    /**
     * Opens a code-mode container, streaming content into [out].
     *
     * The salt is read, then chunk 0 is tried under a key derived with each
     * published parameter set, **newest first**, until one authenticates — the
     * rule `VaultArchive` follows for its own headerless file. A wrong code costs
     * one derivation per published set; today there is one. Throws [ShareFailure]
     * or, once chunk 0 has authenticated, [NewerShareVersion].
     */
    fun openWithCode(input: InputStream, out: OutputStream, codeWords: List<String>): Opened {
        val salt = ByteArray(CODE_BLOCK_BYTES)
        if (fill(input, salt) < salt.size) throw ShareFailure()
        val code = ShareCode.canonicalBytes(codeWords)
        try {
            val keys = VaultKeys.RELEASED_KDF_PARAMS.asReversed().asSequence()
                .map { VaultKeys.deriveKek(code, salt, it) }
            // The code is zeroed once chunk 0 has chosen a key, not when the whole
            // stream ends: trying several sets reads it more than once, and holding
            // it for the length of a video would be holding it for nothing.
            return readSealed(input, out, CODE_LABEL, salt, keys) { code.fill(0) }
        } finally {
            code.fill(0)
        }
    }

    /**
     * Opens a contact-mode container with this install's X-Wing [seed], streaming
     * content into [out]. A container sealed to somebody else decapsulates to a
     * pseudorandom key that chunk 0 then refuses: [ShareFailure], indistinguishable
     * from every other failure. [seed] is the caller's.
     */
    fun openWithSeed(input: InputStream, out: OutputStream, seed: ByteArray): Opened {
        val block = ByteArray(CONTACT_BLOCK_BYTES)
        if (fill(input, block) < block.size) throw ShareFailure()
        val key = XWing.decapsulate(seed, block)
        return try {
            readSealed(input, out, CONTACT_LABEL, block, sequenceOf(key))
        } finally {
            key.fill(0)
        }
    }

    /**
     * Whether [input] was sealed to [seed], learned without writing anything — §12's
     * probe, which is how the receive flow decides what to ask.
     *
     * Decapsulates the key block, authenticates **chunk 0 only**, reads the prelude
     * version and zeroes the plaintext and the key. It reads no further than chunk
     * 0, so its finality is not known: a full-length chunk 0 is tried under both
     * nonces, which only the real open then pins down. A file too short to hold a
     * key block, a prefix and the smallest chunk 0 is [Probe.NOT_HERE] without a
     * decapsulation.
     */
    fun probeContact(input: InputStream, seed: ByteArray): Probe {
        val block = ByteArray(CONTACT_BLOCK_BYTES)
        val prefix = ByteArray(PREFIX_BYTES)
        val chunk = ByteArray(STRIDE)
        if (fill(input, block) < block.size || fill(input, prefix) < prefix.size) return Probe.NOT_HERE
        val n = fill(input, chunk)
        if (n < MIN_PRELUDE_BYTES + TAG_BYTES) return Probe.NOT_HERE

        val key = XWing.decapsulate(seed, block)
        val aad = CONTACT_LABEL + block
        val sealed = chunk.copyOf(n)
        val plain = ByteArray(CHUNK)
        try {
            // A short chunk can only be the last one; a full one may be either.
            val indices = if (n < STRIDE) listOf(FINAL_BIT) else listOf(0L, FINAL_BIT)
            val opened = indices.any { index -> decrypt(plain, sealed, aad, prefix, index, key) }
            if (!opened) return Probe.NOT_HERE
            val version = plain[0].toInt() and 0xFF
            return when {
                version > VERSION -> Probe.NEWER_VERSION
                version == VERSION -> Probe.SEALED_HERE
                else -> Probe.NOT_HERE
            }
        } finally {
            key.fill(0)
            plain.fill(0)
        }
    }

    // ── Payload ────────────────────────────────────────────────────────────────

    private fun preludeBytes(policy: Policy, prelude: Prelude): ByteArray {
        val (mode, expiry) = when (policy) {
            is Policy.Unmanaged -> 0 to 0L
            is Policy.SingleView -> 1 to 0L
            is Policy.Expiry -> 2 to policy.atEpochMillis
        }
        val name = prelude.displayName.toByteArray(Charsets.UTF_8)
        val mime = prelude.mime.toByteArray(Charsets.UTF_8)
        require(name.size <= 0xFFFF) { "display name must fit u16" }
        require(mime.size <= 0xFFFF) { "MIME type must fit u16" }
        val out = ByteBuffer.allocate(MIN_PRELUDE_BYTES + name.size + mime.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        out.put(VERSION.toByte())
        out.put(mode.toByte())
        out.putLong(expiry)
        out.put(ByteArray(SHARE_ID_BYTES).also { rng.nextBytes(it) })
        out.put(prelude.kind.code.toByte())
        out.putShort(name.size.toShort())
        out.put(name)
        out.putShort(mime.size.toShort())
        out.put(mime)
        return out.array()
    }

    private fun writeSealed(
        out: OutputStream,
        label: ByteArray,
        keyBlock: ByteArray,
        key: ByteArray,
        policy: Policy,
        prelude: Prelude,
        content: InputStream,
    ) {
        out.write(keyBlock)
        val prefix = ByteArray(PREFIX_BYTES).also { rng.nextBytes(it) }
        out.write(prefix)
        val aad = label + keyBlock

        // The prelude rides ahead of the content in one plaintext stream, so a
        // chunk boundary can fall anywhere inside it and nothing here cares.
        // It also guarantees every container holds at least one chunk.
        val plaintext = SequenceInputStream(ByteArrayInputStream(preludeBytes(policy, prelude)), content)

        var current = ByteArray(CHUNK)
        var next = ByteArray(CHUNK)
        val sealed = ByteArray(STRIDE)
        var index = 0L
        var n = fill(plaintext, current)
        check(n > 0) { "the prelude makes an empty first chunk impossible" }
        while (true) {
            // One chunk of lookahead decides finality: the last chunk seals
            // under index | FINAL_BIT, §12's answer to having no length field.
            val m = if (n == CHUNK) fill(plaintext, next) else 0
            val isFinal = m <= 0
            val message = if (n == CHUNK) current else current.copyOf(n)
            val ok = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                sealed, longArrayOf(0),
                message, n.toLong(),
                aad, aad.size.toLong(),
                null, nonceFor(prefix, if (isFinal) index or FINAL_BIT else index), key
            )
            check(ok) { "share chunk $index seal failed" }
            out.write(sealed, 0, n + TAG_BYTES)
            if (isFinal) break
            val swap = current
            current = next
            next = swap
            n = m
            index++
        }
        out.flush()
    }

    /**
     * Reads the prefix and the chunks after a key block, streaming content into [out].
     *
     * Chunk 0 is tried under each of [keys] in turn, and the first that
     * authenticates opens the rest. [keys] is lazy, so a code-mode key is derived
     * only when the one before it has failed; every key that fails is zeroed, and so
     * is the winner once the stream ends. [onKeyChosen] runs once chunk 0 has been
     * decided either way, so a caller can drop what its keys were derived from.
     */
    private fun readSealed(
        input: InputStream,
        out: OutputStream,
        label: ByteArray,
        keyBlock: ByteArray,
        keys: Sequence<ByteArray>,
        onKeyChosen: () -> Unit = {},
    ): Opened {
        val aad = label + keyBlock
        val prefix = ByteArray(PREFIX_BYTES)
        if (fill(input, prefix) < prefix.size) throw ShareFailure()

        var current = ByteArray(STRIDE)
        var next = ByteArray(STRIDE)
        val plain = ByteArray(CHUNK)

        // The prelude may straddle chunks, so plaintext buffers here until it
        // parses; everything after streams straight through. Bounded: a prelude
        // is at most 31 + 2 × 65 535 bytes.
        var pending: ByteArrayOutputStream? = ByteArrayOutputStream()
        var opened: Opened? = null
        var key: ByteArray? = null

        try {
            var index = 0L
            var n = fill(input, current)
            while (true) {
                if (n < 1 + TAG_BYTES) throw ShareFailure()
                // Structural finality — this chunk is last iff the file ends after
                // it. The nonce makes the structure honest: a container cut at a
                // chunk boundary presents a non-final chunk as final, a container
                // with bytes appended presents the final chunk as non-final, and
                // both decrypt under a nonce they were not sealed with.
                val m = if (n == STRIDE) fill(input, next) else 0
                val isFinal = m <= 0
                val nonceIndex = if (isFinal) index or FINAL_BIT else index
                val sealedChunk = if (n == STRIDE) current else current.copyOf(n)

                if (index == 0L) {
                    key = try {
                        keys.firstOrNull { candidate ->
                            decrypt(plain, sealedChunk, aad, prefix, nonceIndex, candidate)
                                .also { opened -> if (!opened) candidate.fill(0) }
                        }
                    } finally {
                        onKeyChosen()
                    }
                    if (key == null) throw ShareFailure()
                    // Byte 0 is the envelope's one promise. Read it before anything
                    // else in the prelude: a newer format may lay the rest out
                    // differently, and the user is owed the reason it would not open.
                    val version = plain[0].toInt() and 0xFF
                    if (version > VERSION) throw NewerShareVersion()
                    if (version != VERSION) throw ShareFailure()
                } else if (!decrypt(plain, sealedChunk, aad, prefix, nonceIndex, key!!)) {
                    throw ShareFailure()
                }

                val plainLen = n - TAG_BYTES
                val buffer = pending
                if (buffer != null) {
                    buffer.write(plain, 0, plainLen)
                    val parsed = parsePrelude(buffer.toByteArray())
                    if (parsed != null) {
                        val (result, consumed) = parsed
                        opened = result
                        val held = buffer.toByteArray()
                        out.write(held, consumed, held.size - consumed)
                        held.fill(0)
                        pending = null
                    }
                } else {
                    out.write(plain, 0, plainLen)
                }

                if (isFinal) break
                val swap = current
                current = next
                next = swap
                n = m
                index++
            }
        } finally {
            key?.fill(0)
            plain.fill(0)
        }
        out.flush()
        // A stream that ended before the prelude completed authenticated as a
        // whole container that still cannot be this format.
        return opened ?: throw ShareFailure()
    }

    /** The prelude parsed from [bytes], with how many bytes it spent — or null if incomplete. */
    private fun parsePrelude(bytes: ByteArray): Pair<Opened, Int>? {
        if (bytes.size < PRELUDE_FIXED_BYTES + 2) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val policyMode = bytes[1].toInt() and 0xFF
        val expiry = b.getLong(2)
        val policy = when (policyMode) {
            0 -> Policy.Unmanaged
            1 -> Policy.SingleView
            2 -> if (expiry > 0) Policy.Expiry(expiry) else throw ShareFailure()
            else -> throw ShareFailure()
        }
        // Modes 0 and 1 always write expiry 0; anything else is not this format.
        if (policyMode != 2 && expiry != 0L) throw ShareFailure()
        val shareId = bytes.copyOfRange(10, 10 + SHARE_ID_BYTES)
        val kind = Kind.of(bytes[10 + SHARE_ID_BYTES].toInt() and 0xFF) ?: throw ShareFailure()

        val nameLen = b.getShort(PRELUDE_FIXED_BYTES).toInt() and 0xFFFF
        val mimeAt = PRELUDE_FIXED_BYTES + 2 + nameLen
        if (bytes.size < mimeAt + 2) return null
        val mimeLen = b.getShort(mimeAt).toInt() and 0xFFFF
        val end = mimeAt + 2 + mimeLen
        if (bytes.size < end) return null
        return Opened(
            kind = kind,
            displayName = String(bytes, PRELUDE_FIXED_BYTES + 2, nameLen, Charsets.UTF_8),
            mime = String(bytes, mimeAt + 2, mimeLen, Charsets.UTF_8),
            policy = policy,
            shareId = shareId,
        ) to end
    }

    // ── Shared plumbing ────────────────────────────────────────────────────────

    private fun decrypt(
        plain: ByteArray,
        sealed: ByteArray,
        aad: ByteArray,
        prefix: ByteArray,
        index: Long,
        key: ByteArray,
    ): Boolean = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
        plain, longArrayOf(0),
        null,
        sealed, sealed.size.toLong(),
        aad, aad.size.toLong(),
        nonceFor(prefix, index), key
    )

    private fun nonceFor(prefix: ByteArray, index: Long) = ByteArray(NONCE_BYTES).also {
        prefix.copyInto(it, 0, 0, PREFIX_BYTES)
        ByteBuffer.wrap(it, PREFIX_BYTES, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(index)
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
}
