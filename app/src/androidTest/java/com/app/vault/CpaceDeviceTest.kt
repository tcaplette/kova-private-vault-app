package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * draft-irtf-cfrg-cpace-21's `CPACE-RISTR255-SHA512` vectors (B.3), byte for byte —
 * `remote-pairing` task 1.3. On the emulator because libsodium is native.
 *
 * Writes nothing to the device.
 */
@RunWith(AndroidJUnit4::class)
class CpaceDeviceTest {

    private val v: Map<String, ByteArray> by lazy {
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("fixtures/cpace/ristretto255-sha512.txt").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("revision") }
            .associate { it.substringBefore(' ') to hex(it.substringAfter(' ').trim()) }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private operator fun Map<String, ByteArray>.invoke(key: String) = getValue(key)

    @Test
    fun theFixtureIsRevision21() {
        val revision = InstrumentationRegistry.getInstrumentation().context.assets
            .open("fixtures/cpace/ristretto255-sha512.txt").bufferedReader().readLines()
            .single { it.startsWith("revision") }
        assertEquals("revision 21", revision)
    }

    @Test
    fun lvCatMatchesTheAppendix() {
        // A.1.4: lv_cat(b"1234", b"5", b"", b"678").
        assertArrayEquals(
            hex("043132333401350003363738"),
            Cpace.lvCat("1234".toByteArray(), "5".toByteArray(), ByteArray(0), "678".toByteArray()),
        )
        // A.1.2: a 128-byte input takes a two-byte LEB128 length.
        assertArrayEquals(hex("8001"), Cpace.lvCat(ByteArray(128) { it.toByte() }).copyOf(2))
    }

    @Test
    fun theGeneratorReproduces() {
        assertArrayEquals("generator string", v("generator_string"), Cpace.generatorString(v("prs"), v("ci"), v("sid")))
        assertArrayEquals("generator", v("g"), Cpace.generator(v("prs"), v("ci"), v("sid")))
    }

    @Test
    fun bothSharesKAndIskReproduce() {
        val g = Cpace.generator(v("prs"), v("ci"), v("sid"))
        assertArrayEquals("Ya", v("Ya"), Cpace.share(v("ya"), g))
        assertArrayEquals("Yb", v("Yb"), Cpace.share(v("yb"), g))
        assertArrayEquals("K at A", v("K"), Cpace.sharedPoint(v("ya"), v("Yb")))
        assertArrayEquals("K at B", v("K"), Cpace.sharedPoint(v("yb"), v("Ya")))
        assertArrayEquals(
            "ISK",
            v("isk_ir"),
            Cpace.isk(v("sid"), v("K"), v("Ya"), v("ada"), v("Yb"), v("adb")),
        )
    }

    @Test
    fun scalarMultVfyOnTheValidCase() {
        assertArrayEquals(v("valid_sX"), Cpace.sharedPoint(v("valid_s"), v("valid_X")))
    }

    @Test
    fun anInvalidOrIdentityShareIsRefused() {
        assertNull("non-canonical encoding", Cpace.sharedPoint(v("valid_s"), v("invalid_Y1")))
        assertNull("identity", Cpace.sharedPoint(v("valid_s"), v("invalid_Y2")))
        assertFalse(Cpace.isValidShare(v("invalid_Y1")))
        assertFalse(Cpace.isValidShare(v("invalid_Y2")))
        assertNull("short share", Cpace.sharedPoint(v("valid_s"), ByteArray(31)))
    }

    @Test
    fun freshScalarsAgree() {
        val g = Cpace.generator("abandon ability able about".toByteArray(), "vault-pair-1".toByteArray(), ByteArray(16))
        val a = Cpace.randomScalar()
        val b = Cpace.randomScalar()
        val ka = Cpace.sharedPoint(a, Cpace.share(b, g))
        assertNotNull(ka)
        assertArrayEquals(ka, Cpace.sharedPoint(b, Cpace.share(a, g)))
    }
}
