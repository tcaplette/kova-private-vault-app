package com.app.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.SecureRandom

/**
 * X-Wing against draft-connolly-cfrg-xwing-kem-11's published vectors — `share-format-v2`.
 *
 * Every byte is compared: the public key from each seed, the ciphertext and the shared
 * secret from each `eseed`, and the shared secret again from decapsulation. A
 * hand-composed KEM that is wrong anywhere produces different bytes here, and that is
 * the only test that can tell, because nothing about a wrong X-Wing fails loudly.
 *
 * The JVM half. `XWingDeviceTest` runs the same file on the emulator against the
 * filtered dependency, which is what proves the filter kept enough.
 */
class XWingTest {

    @Test
    fun everyVectorReproducesByteForByte() {
        val vectors = XWingVectors.load(javaClass.getResourceAsStream("/xwing/draft-11.txt")!!)
        assertEquals("draft -11 publishes three vectors", 3, vectors.size)
        vectors.forEachIndexed { i, v ->
            val keypair = XWing.keypairFromSeed(v.seed)
            assertArrayEquals("vector $i: public key", v.pk, keypair.publicKey)

            val encapsulated = XWing.encapsulate(v.pk, XWingVectors.Eseed(v.eseed))
            assertArrayEquals("vector $i: ciphertext", v.ct, encapsulated.ciphertext)
            assertArrayEquals("vector $i: encapsulated secret", v.ss, encapsulated.sharedSecret)

            assertArrayEquals("vector $i: decapsulated secret", v.ss, XWing.decapsulate(v.seed, v.ct))
        }
    }

    @Test
    fun aWrongSecretKeyDecapsulatesToADifferentSecretWithoutThrowing() {
        val v = XWingVectors.load(javaClass.getResourceAsStream("/xwing/draft-11.txt")!!).first()
        val wrong = v.seed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        // Implicit rejection: no exception, just a secret the chunk tag will then refuse.
        val secret = XWing.decapsulate(wrong, v.ct)
        assertEquals(XWing.SHARED_SECRET_BYTES, secret.size)
        assertFalse(secret.contentEquals(v.ss))
    }

    @Test
    fun aFreshEncapsulationOpensUnderItsSeed() {
        val seed = ByteArray(XWing.SEED_BYTES).also { SecureRandom().nextBytes(it) }
        val keypair = XWing.keypairFromSeed(seed)
        assertEquals(XWing.PUBLIC_KEY_BYTES, keypair.publicKey.size)
        val encapsulated = XWing.encapsulate(keypair.publicKey, SecureRandom())
        assertEquals(XWing.CIPHERTEXT_BYTES, encapsulated.ciphertext.size)
        assertArrayEquals(encapsulated.sharedSecret, XWing.decapsulate(seed, encapsulated.ciphertext))
    }
}

/** The committed vector file's reader. A copy lives in `XWingDeviceTest`, because the two source sets share nothing. */
internal object XWingVectors {

    class Vector(val seed: ByteArray, val pk: ByteArray, val eseed: ByteArray, val ct: ByteArray, val ss: ByteArray)

    fun load(input: java.io.InputStream): List<Vector> =
        input.bufferedReader().readText()
            .split(Regex("\n\\s*\n"))
            .map { block ->
                block.lines()
                    .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("revision") }
                    .associate { line -> line.substringBefore(' ') to hex(line.substringAfter(' ').trim()) }
            }
            .filter { it.isNotEmpty() }
            .map { Vector(it["seed"]!!, it["pk"]!!, it["eseed"]!!, it["ct"]!!, it["ss"]!!) }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** Test-only randomness: the vector's `eseed`, then nothing. */
    class Eseed(private val bytes: ByteArray) : SecureRandom() {
        private var at = 0

        override fun nextBytes(out: ByteArray) {
            check(at + out.size <= bytes.size) { "drew past the vector's eseed" }
            bytes.copyInto(out, 0, at, at + out.size)
            at += out.size
        }
    }
}
