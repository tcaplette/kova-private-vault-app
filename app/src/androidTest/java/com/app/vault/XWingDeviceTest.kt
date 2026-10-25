package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/**
 * X-Wing's draft vectors on the device, against the **filtered** BouncyCastle —
 * `share-format-v2` D10, `crypto-primitives`.
 *
 * `XWingTest` proves the construction on the JVM. This proves the APK: the build
 * rewrites bcprov down to the classes `XWing.kt` loads, and a class the filter
 * dropped shows up nowhere but here, as a `NoClassDefFoundError` on ART.
 *
 * Writes nothing to the device.
 */
@RunWith(AndroidJUnit4::class)
class XWingDeviceTest {

    private class Vector(val seed: ByteArray, val pk: ByteArray, val eseed: ByteArray, val ct: ByteArray, val ss: ByteArray)

    /** The same reader as `XWingTest`'s; the two source sets share no code. */
    private fun vectors(): List<Vector> =
        InstrumentationRegistry.getInstrumentation().context.assets.open("draft-11.txt")
            .bufferedReader().readText()
            .split(Regex("\n\\s*\n"))
            .map { block ->
                block.lines()
                    .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("revision") }
                    .associate { line -> line.substringBefore(' ') to hex(line.substringAfter(' ').trim()) }
            }
            .filter { it.isNotEmpty() }
            .map { Vector(it["seed"]!!, it["pk"]!!, it["eseed"]!!, it["ct"]!!, it["ss"]!!) }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private class Eseed(private val bytes: ByteArray) : SecureRandom() {
        private var at = 0

        override fun nextBytes(out: ByteArray) {
            check(at + out.size <= bytes.size) { "drew past the vector's eseed" }
            bytes.copyInto(out, 0, at, at + out.size)
            at += out.size
        }
    }

    @Test
    fun everyVectorReproducesOnTheFilteredDependency() {
        val vectors = vectors()
        assertEquals("draft -11 publishes three vectors", 3, vectors.size)
        vectors.forEachIndexed { i, v ->
            assertArrayEquals("vector $i: public key", v.pk, XWing.keypairFromSeed(v.seed).publicKey)
            val encapsulated = XWing.encapsulate(v.pk, Eseed(v.eseed))
            assertArrayEquals("vector $i: ciphertext", v.ct, encapsulated.ciphertext)
            assertArrayEquals("vector $i: encapsulated secret", v.ss, encapsulated.sharedSecret)
            assertArrayEquals("vector $i: decapsulated secret", v.ss, XWing.decapsulate(v.seed, v.ct))
        }
    }

    @Test
    fun aFreshEncapsulationOpensOnTheDevice() {
        val seed = ByteArray(XWing.SEED_BYTES).also { SecureRandom().nextBytes(it) }
        val encapsulated = XWing.encapsulate(XWing.keypairFromSeed(seed).publicKey, SecureRandom())
        assertArrayEquals(encapsulated.sharedSecret, XWing.decapsulate(seed, encapsulated.ciphertext))
    }
}
