package com.app.vault

import java.io.ByteArrayOutputStream

/**
 * CPace, exactly as draft-irtf-cfrg-cpace-21 specifies its `CPACE-RISTR255-SHA512`
 * suite — `remote-pairing` D1, `FORMAT.md` §13a.
 *
 * Everything is libsodium's: SHA-512, `crypto_core_ristretto255_from_hash` as the
 * element derivation, `crypto_scalarmult_ristretto255` as both `scalar_mult` and
 * `scalar_mult_vfy`. Points are handled in their 32-byte public encoding throughout,
 * which is all libsodium's Ristretto255 API speaks; the generator is therefore its
 * encoding too, and decoding it inside [share] cannot fail for a value [generator]
 * returned.
 *
 * `CpaceDeviceTest` reproduces every byte of the draft's B.3 vectors. Because
 * libsodium is native, that test runs on the emulator.
 */
object Cpace {

    const val POINT_BYTES = 32
    const val SCALAR_BYTES = 32
    const val ISK_BYTES = 64

    private const val SHA512_BLOCK_BYTES = 128
    private val DSI = "CPaceRistretto255".toByteArray(Charsets.US_ASCII)
    private val DSI_ISK = "CPaceRistretto255_ISK".toByteArray(Charsets.US_ASCII)

    private val sodium = Sodium.lazy

    /** The draft's `prepend_len`: the LEB128 length, then the bytes. */
    private fun ByteArrayOutputStream.prependLen(data: ByteArray) {
        var length = data.size
        while (true) {
            if (length < 128) write(length) else write((length and 0x7f) + 0x80)
            length = length ushr 7
            if (length == 0) break
        }
        write(data)
    }

    /** The draft's `lv_cat`. */
    fun lvCat(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { parts.forEach { prependLen(it) } }.toByteArray()

    private fun lenPrefixed(data: ByteArray): Int = lvCat(data).size

    /** The draft's `generator_string` for SHA-512's 128-byte input block. */
    fun generatorString(prs: ByteArray, ci: ByteArray, sid: ByteArray): ByteArray {
        val zpad = maxOf(0, SHA512_BLOCK_BYTES - 1 - lenPrefixed(prs) - lenPrefixed(DSI))
        return lvCat(DSI, prs, ByteArray(zpad), ci, sid)
    }

    private fun sha512(message: ByteArray): ByteArray = ByteArray(64).also {
        check(sodium.cryptoHashSha512(it, message, message.size.toLong())) { "SHA-512 failed" }
    }

    /** `G.calculate_generator`: SHA-512 of the generator string, mapped by `from_hash`. Returned encoded. */
    fun generator(prs: ByteArray, ci: ByteArray, sid: ByteArray): ByteArray {
        val hash = sha512(generatorString(prs, ci, sid))
        try {
            return ByteArray(POINT_BYTES).also {
                check(sodium.cryptoCoreRistretto255FromHash(it, hash)) { "ristretto255 from_hash failed" }
            }
        } finally {
            hash.fill(0)
        }
    }

    /**
     * `G.sample_scalar`, as libsodium's uniform draw from ]0, L[ — the draft's
     * permitted alternative to masked random bytes. The caller zeroes it.
     */
    fun randomScalar(): ByteArray =
        ByteArray(SCALAR_BYTES).also { sodium.cryptoCoreRistretto255ScalarRandom(it) }

    /** `G.scalar_mult(y, g)`: this side's public share. */
    fun share(scalar: ByteArray, generator: ByteArray): ByteArray = ByteArray(POINT_BYTES).also {
        check(sodium.cryptoScalarmultRistretto255(it, scalar, generator)) { "share on a valid generator failed" }
    }

    /**
     * `G.scalar_mult_vfy(y, X)`, with the draft's abort folded in: **null** where
     * libsodium refuses — [peerShare] does not decode, or the product is the
     * identity — which is exactly the draft's `K = G.I` case.
     */
    fun sharedPoint(scalar: ByteArray, peerShare: ByteArray): ByteArray? {
        if (peerShare.size != POINT_BYTES) return null
        val out = ByteArray(POINT_BYTES)
        return if (sodium.cryptoScalarmultRistretto255(out, scalar, peerShare)) out else null
    }

    /**
     * Whether [share] is a canonical encoding of a non-identity point — a picked
     * invite's first test. libsodium's check is decoding only, and the identity's
     * canonical encoding (all zeros, `G.I`) decodes, so it is refused by value.
     */
    fun isValidShare(share: ByteArray): Boolean =
        share.size == POINT_BYTES &&
            share.any { it != 0.toByte() } &&
            sodium.cryptoCoreRistretto255IsValidPoint(share)

    /**
     * The initiator-responder `ISK`:
     * `SHA-512(lv_cat(DSI ‖ "_ISK", sid, K) ‖ lv_cat(Ya, ADa) ‖ lv_cat(Yb, ADb))`.
     * The caller zeroes it.
     */
    fun isk(
        sid: ByteArray,
        k: ByteArray,
        ya: ByteArray,
        ada: ByteArray,
        yb: ByteArray,
        adb: ByteArray,
    ): ByteArray {
        val input = lvCat(DSI_ISK, sid, k) + lvCat(ya, ada) + lvCat(yb, adb)
        try {
            return sha512(input)
        } finally {
            input.fill(0)
        }
    }
}
