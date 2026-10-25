package com.app.vault

import org.bouncycastle.crypto.SecretWithEncapsulation
import org.bouncycastle.crypto.digests.SHA3Digest
import org.bouncycastle.crypto.digests.SHAKEDigest
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters
import java.security.SecureRandom

/**
 * X-Wing — X25519 combined with ML-KEM-768, exactly as draft-connolly-cfrg-xwing-kem-11
 * specifies it. `FORMAT.md` §12 contact mode and §13's identity.
 *
 * **Composed here from BouncyCastle's parts rather than taken from its
 * `pqc.crypto.xwing` classes.** BouncyCastle 1.79's own X-Wing keeps the expanded
 * ML-KEM key plus the X25519 key as its private key, which is an earlier draft's
 * format. The current draft's private key is a 32-byte seed, and that seed is what
 * §13 stores — one field, from which everything else is derived.
 *
 * **The only file in the app that imports `org.bouncycastle`** (`XWingSourceScanTest`).
 * The dependency enters the APK filtered to the classes this file reaches, so
 * libsodium stays the only Argon2id and the only AEAD (`crypto-primitives`).
 *
 * Every function here is checked byte for byte against the draft's published vectors,
 * on the JVM (`XWingTest`) and on the device against the filtered dependency
 * (`XWingDeviceTest`).
 */
object XWing {

    const val SEED_BYTES = 32
    const val PUBLIC_KEY_BYTES = 1216
    const val CIPHERTEXT_BYTES = 1120
    const val SHARED_SECRET_BYTES = 32

    private const val ML_KEM_PUBLIC_KEY_BYTES = 1184
    private const val ML_KEM_CIPHERTEXT_BYTES = 1088
    private const val X25519_BYTES = 32

    /** `\.//^\`, hex 5c2e2f2f5e5c — the draft's domain separator, last in the combiner. */
    private val LABEL = byteArrayOf(0x5c, 0x2e, 0x2f, 0x2f, 0x5e, 0x5c)

    /** A keypair. [secretKey] is the 32-byte seed itself, and the caller's to zero. */
    class Keypair(val publicKey: ByteArray, val secretKey: ByteArray)

    /** One encapsulation. [sharedSecret] is the caller's to zero. */
    class Encapsulation(val ciphertext: ByteArray, val sharedSecret: ByteArray)

    /** The draft's `expandDecapsulationKey`: the ML-KEM key, `sk_X` and `pk_X` from the seed. */
    private class Expanded(val mlKem: MLKEMPrivateKeyParameters, val skX: ByteArray, val pkX: ByteArray)

    private fun expand(seed: ByteArray): Expanded {
        require(seed.size == SEED_BYTES) { "an X-Wing seed is $SEED_BYTES bytes" }
        val expanded = ByteArray(96)
        SHAKEDigest(256).apply {
            update(seed, 0, seed.size)
            doFinal(expanded, 0, expanded.size)
        }
        try {
            // A 64-byte encoding is ML-KEM's `d ‖ z`, run through KeyGen_internal.
            val mlKem = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, expanded.copyOfRange(0, 64))
            val skX = expanded.copyOfRange(64, 96)
            val pkX = ByteArray(X25519_BYTES).also { X25519.scalarMultBase(skX, 0, it, 0) }
            return Expanded(mlKem, skX, pkX)
        } finally {
            expanded.fill(0)
        }
    }

    /** The draft's `GenerateKeyPairDerand`: a 1,216-byte public key from a 32-byte seed. */
    fun keypairFromSeed(seed: ByteArray): Keypair {
        val expanded = expand(seed)
        try {
            return Keypair(expanded.mlKem.publicKey + expanded.pkX, seed.copyOf())
        } finally {
            expanded.skX.fill(0)
        }
    }

    /**
     * The draft's `Encapsulate`. 64 bytes are read from [random] in one call — `eseed`,
     * the first half ML-KEM's message and the second the X25519 ephemeral key — so a
     * test can hand in a source that returns a vector's `eseed` and get its bytes.
     *
     * Throws `IllegalArgumentException` for a public key that fails ML-KEM's modulus
     * check (FIPS 203 §7.2), which a key this app produced never does.
     */
    fun encapsulate(publicKey: ByteArray, random: SecureRandom): Encapsulation {
        require(publicKey.size == PUBLIC_KEY_BYTES) { "an X-Wing public key is $PUBLIC_KEY_BYTES bytes" }
        val pkM = publicKey.copyOfRange(0, ML_KEM_PUBLIC_KEY_BYTES)
        val pkX = publicKey.copyOfRange(ML_KEM_PUBLIC_KEY_BYTES, PUBLIC_KEY_BYTES)
        val eseed = ByteArray(64).also { random.nextBytes(it) }
        val m = eseed.copyOfRange(0, 32)
        val ekX = eseed.copyOfRange(32, 64)
        var ssM: ByteArray? = null
        var mlKem: SecretWithEncapsulation? = null
        val ssX = ByteArray(X25519_BYTES)
        try {
            // ML-KEM.Encaps draws its 32-byte message from the source it is given, in
            // one call, and runs the input checks before EncapsDerand. Handing it a
            // source that holds `eseed[0:32]` is what makes this the draft's
            // EncapsulateDerand rather than a second, independent draw.
            val encapsulated = MLKEMGenerator(OneShot(m))
                .generateEncapsulated(MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, pkM))
            mlKem = encapsulated
            val secret = encapsulated.secret
            ssM = secret
            val ctX = ByteArray(X25519_BYTES).also { X25519.scalarMultBase(ekX, 0, it, 0) }
            X25519.scalarMult(ekX, 0, pkX, 0, ssX, 0)
            return Encapsulation(encapsulated.encapsulation + ctX, combine(secret, ssX, ctX, pkX))
        } finally {
            eseed.fill(0)
            m.fill(0)
            ekX.fill(0)
            ssM?.fill(0)
            mlKem?.destroy()
            ssX.fill(0)
        }
    }

    /**
     * The draft's `Decapsulate`. **Never fails for a wrong key**: ML-KEM's implicit
     * rejection returns a pseudorandom secret instead, so the AEAD tag that secret
     * then fails is the only test of whether a ciphertext was meant for [seed].
     */
    fun decapsulate(seed: ByteArray, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size == CIPHERTEXT_BYTES) { "an X-Wing ciphertext is $CIPHERTEXT_BYTES bytes" }
        val expanded = expand(seed)
        val ctM = ciphertext.copyOfRange(0, ML_KEM_CIPHERTEXT_BYTES)
        val ctX = ciphertext.copyOfRange(ML_KEM_CIPHERTEXT_BYTES, CIPHERTEXT_BYTES)
        val ssM = MLKEMExtractor(expanded.mlKem).extractSecret(ctM)
        val ssX = ByteArray(X25519_BYTES)
        try {
            X25519.scalarMult(expanded.skX, 0, ctX, 0, ssX, 0)
            return combine(ssM, ssX, ctX, expanded.pkX)
        } finally {
            expanded.skX.fill(0)
            ssM.fill(0)
            ssX.fill(0)
        }
    }

    /** A source that yields exactly [bytes], once — anything more is a broken assumption, not entropy. */
    private class OneShot(private val bytes: ByteArray) : SecureRandom() {
        private var spent = false

        override fun nextBytes(out: ByteArray) {
            check(!spent && out.size == bytes.size) { "ML-KEM drew randomness other than its one message" }
            spent = true
            bytes.copyInto(out)
        }
    }

    /** `SHA3-256(ss_M ‖ ss_X ‖ ct_X ‖ pk_X ‖ XWingLabel)`. */
    private fun combine(ssM: ByteArray, ssX: ByteArray, ctX: ByteArray, pkX: ByteArray): ByteArray {
        val out = ByteArray(SHARED_SECRET_BYTES)
        SHA3Digest(256).apply {
            listOf(ssM, ssX, ctX, pkX, LABEL).forEach { update(it, 0, it.size) }
            doFinal(out, 0)
        }
        return out
    }
}
