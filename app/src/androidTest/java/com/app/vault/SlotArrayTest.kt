package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.ln

/**
 * P1-1 — the slot array, against `FORMAT.md` §4 and OUTLINE P1-1's Verify:
 * a fresh install and a one-vault install must be indistinguishable in size and
 * entropy.
 */
@RunWith(AndroidJUnit4::class)
class SlotArrayTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultSlots"
        /**
         * Shannon entropy of 2048 uniform random bytes is bounded below 8 by
         * finite-sample bias: E[H] ~ 8 - 255/(2 * 2048 * ln2) ~ 7.91. The floor
         * has to sit under that expectation, not at 8.
         */
        const val ENTROPY_FLOOR = 7.85
        const val FRESH_INSTALLS = 100
    }

    @Before fun setUp() {
        dir = File(ctx.filesDir, "slot-test").apply { mkdirs() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun newArray(name: String = "vault.slots"): SlotArray =
        SlotArray(File(dir, name)).also { it.initialiseIfAbsent() }

    private fun fileOf(name: String = "vault.slots") = File(dir, name)

    private fun key() = ByteArray(SlotArray.KEY_BYTES).also { rng.nextBytes(it) }
    private fun vmk() = ByteArray(SlotArray.VMK_BYTES).also { rng.nextBytes(it) }
    private fun blobId() = ByteArray(SlotArray.BLOB_ID_BYTES).also { rng.nextBytes(it) }

    // --- 3.1 size --------------------------------------------------------------

    @Test fun freshInitialisationIsExactlyFileBytes() {
        newArray()
        assertEquals(2074L, fileOf().length())
        assertEquals(SlotArray.FILE_BYTES.toLong(), fileOf().length())
    }

    // --- 3.2 the Verify line ---------------------------------------------------

    @Test fun freshInstallAndOneVaultInstallAreIndistinguishable() {
        val fresh = newArray("fresh.slots")
        val used = newArray("used.slots")
        used.seal(2, key(), vmk(), blobId())

        val freshSlots = slotRegion("fresh.slots")
        val usedSlots = slotRegion("used.slots")

        assertEquals(fileOf("fresh.slots").length(), fileOf("used.slots").length())

        val hFresh = entropy(freshSlots)
        val hUsed = entropy(usedSlots)
        val zFresh = deflatedSize(freshSlots)
        val zUsed = deflatedSize(usedSlots)

        Log.i(
            TAG,
            "entropy fresh=%.4f used=%.4f delta=%.4f | deflated fresh=%d used=%d delta=%d"
                .format(hFresh, hUsed, abs(hFresh - hUsed), zFresh, zUsed, abs(zFresh - zUsed))
        )

        assertTrue("fresh entropy $hFresh below floor", hFresh >= ENTROPY_FLOOR)
        assertTrue("used entropy $hUsed below floor", hUsed >= ENTROPY_FLOOR)
        assertTrue("entropy differs by ${abs(hFresh - hUsed)}", abs(hFresh - hUsed) < 0.05)
        assertTrue("deflated sizes differ by ${abs(zFresh - zUsed)}", abs(zFresh - zUsed) < 64)
        // Incompressible: a region with structure would deflate well below its size.
        assertTrue("used region compressed to $zUsed", zUsed > usedSlots.size)
    }

    // --- 3.3 no fixed byte position -------------------------------------------

    /**
     * **Narrowed by P2-25, deliberately.** The claim used to cover the whole file.
     * It now covers the salt and the eight slots — everything the KDF header is
     * not — because the header is *required* to be constant: it records the
     * algorithm and costs this install derives under, and a later build reads it
     * before it has any key.
     *
     * What that costs is stated rather than hidden: an examiner can recognise a
     * `vault.slots` file by those 10 bytes. I8 never protected against that — §2
     * says filenames hide nothing, the launcher icon is visible and the store
     * listing says the app is a vault. I8 is about telling one vault from another
     * and a used slot from an unused one, and the header answers neither: it is
     * byte-identical on every install of a build, which is what
     * [theKdfHeaderIsIdenticalAcrossInstalls] asserts.
     */
    @Test fun noBytePositionOutsideTheHeaderIsConstantAcrossFreshInstalls() {
        val first = run { newArray("i0.slots"); fileOf("i0.slots").readBytes() }
        val constant = BooleanArray(first.size) { true }

        for (i in 1 until FRESH_INSTALLS) {
            newArray("i$i.slots")
            val bytes = fileOf("i$i.slots").readBytes()
            for (p in bytes.indices) {
                if (constant[p] && bytes[p] != first[p]) constant[p] = false
            }
        }

        val header = SlotArray.HEADER_OFFSET until SlotArray.SLOTS_OFFSET
        val stuck = constant.indices.filter { constant[it] && it !in header }
        assertTrue("byte offsets identical across all $FRESH_INSTALLS installs: $stuck", stuck.isEmpty())
    }

    // --- P2-25: the KDF header -------------------------------------------------

    /** The header answers nothing about *this* install, which is the I8 claim. */
    @Test fun theKdfHeaderIsIdenticalAcrossInstalls() {
        newArray("h0.slots")
        newArray("h1.slots")

        fun header(name: String) = fileOf(name).readBytes()
            .copyOfRange(SlotArray.HEADER_OFFSET, SlotArray.SLOTS_OFFSET)

        assertArrayEquals(header("h0.slots"), header("h1.slots"))
        assertEquals(SlotArray.HEADER_BYTES, header("h0.slots").size)
    }

    @Test fun aFreshInstallRecordsTheCreationConstant() {
        val array = newArray("params.slots")
        assertEquals(VaultKeys.ARGON2ID_PARAMS, array.kdfParams())
    }

    /**
     * The recorded parameters are the install's for life. Every sealed slot was
     * sealed under them, so a write that quietly replaced them with a later build's
     * constant would make every one of those slots unopenable — the exact failure
     * this whole change exists to prevent, arriving from inside.
     */
    @Test fun sealingResealingAndDestroyingLeaveTheHeaderAlone() {
        val array = newArray("stable.slots")
        val before = fileOf("stable.slots").readBytes()
            .copyOfRange(0, SlotArray.SLOTS_OFFSET)
        val kek = key()

        array.seal(0, kek, vmk(), blobId())
        array.seal(0, kek, vmk(), blobId())
        array.destroy(0)
        array.seal(7, key(), vmk(), blobId())

        assertArrayEquals(
            "salt or KDF header moved",
            before,
            fileOf("stable.slots").readBytes().copyOfRange(0, SlotArray.SLOTS_OFFSET),
        )
    }

    /**
     * An existing install keeps its own parameters even when the running build
     * would create new installs with different ones — P2-25's whole point, at the
     * layer that stores them.
     */
    @Test fun anExistingHeaderIsNotOverwrittenByALaterBuildsConstant() {
        val other = VaultKeys.Argon2idParams(memoryKib = 8 * 1024, iterations = 2, parallelism = 1)
        val file = fileOf("legacy.slots")
        SlotArray(file).initialiseIfAbsent(other)

        SlotArray(file).initialiseIfAbsent(VaultKeys.ARGON2ID_PARAMS)

        assertEquals(other, SlotArray(file).kdfParams())
    }

    // --- 3.4 no key material on disk ------------------------------------------

    @Test fun sealedVmkDoesNotAppearInTheFile() {
        val array = newArray()
        val vmk = vmk()
        array.seal(5, key(), vmk, blobId())

        val raw = fileOf().readBytes()
        assertEquals(-1, indexOf(raw, vmk))
    }

    // --- 3.5 isolation ---------------------------------------------------------

    @Test fun sealingOneSlotDisturbsNothingElse() {
        val array = newArray()
        val before = fileOf().readBytes()

        array.seal(3, key(), vmk(), blobId())
        val after = fileOf().readBytes()

        assertEquals(2074, after.size)
        assertArrayEquals(
            "salt and KDF header changed",
            before.copyOfRange(0, SlotArray.SLOTS_OFFSET),
            after.copyOfRange(0, SlotArray.SLOTS_OFFSET),
        )
        for (i in 0 until SlotArray.SLOT_COUNT) {
            if (i == 3) continue
            val from = SlotArray.SLOTS_OFFSET + i * 256
            assertArrayEquals(
                "slot $i changed",
                before.copyOfRange(from, from + 256),
                after.copyOfRange(from, from + 256)
            )
        }
    }

    /** `recovery-own-slot-only` 1.1: the seal goes through a temp file and a rename. */
    @Test fun anAtomicSealLeavesNoTempAndOnlyItsSlotChanged() {
        val array = newArray()
        val before = fileOf().readBytes()
        val kek = key()
        val vmk = vmk()
        val blobId = blobId()

        array.seal(5, kek, vmk, blobId)
        val after = fileOf().readBytes()

        assertTrue("temp file left behind", !fileOf("vault.slots.tmp").exists())
        assertArrayEquals(
            "salt and KDF header changed",
            before.copyOfRange(0, SlotArray.SLOTS_OFFSET),
            after.copyOfRange(0, SlotArray.SLOTS_OFFSET),
        )
        for (i in 0 until SlotArray.SLOT_COUNT) {
            if (i == 5) continue
            val from = SlotArray.SLOTS_OFFSET + i * 256
            assertArrayEquals("slot $i changed", before.copyOfRange(from, from + 256), after.copyOfRange(from, from + 256))
        }
        val opened = array.open(5, kek)
        assertArrayEquals(vmk, opened!!.copyOfRange(0, 32))
        assertArrayEquals(blobId, opened.copyOfRange(32, 48))
    }

    // --- 3.6 reseal regenerates everything ------------------------------------

    @Test fun resealingRegeneratesNonceAndPadding() {
        val array = newArray()
        val kek = key()
        array.seal(1, kek, vmk(), blobId())
        val first = slotBytes(1)
        array.seal(1, kek, vmk(), blobId())
        val second = slotBytes(1)

        assertNotEquals(
            "nonce unchanged across reseal",
            first.copyOfRange(0, 24).toList(), second.copyOfRange(0, 24).toList()
        )
        assertNotEquals(
            "padding unchanged across reseal",
            first.copyOfRange(88, 256).toList(), second.copyOfRange(88, 256).toList()
        )
    }

    // --- 3.7 round trip --------------------------------------------------------

    @Test fun sealThenOpenReturnsExactlyWhatWasSealed() {
        val array = newArray()
        val kek = key()
        val vmk = vmk()
        val blobId = blobId()

        array.seal(6, kek, vmk, blobId)
        val opened = array.open(6, kek)

        assertArrayEquals(vmk, opened!!.copyOfRange(0, 32))
        assertArrayEquals(blobId, opened.copyOfRange(32, 48))
    }

    // --- 3.8 failure paths are the same ---------------------------------------

    @Test fun wrongKeyAndUnusedSlotBothReturnNull() {
        val array = newArray()
        array.seal(0, key(), vmk(), blobId())

        assertNull("wrong key opened a sealed slot", array.open(0, key()))
        assertNull("some key opened an unused slot", array.open(4, key()))
    }

    // --- 3.9 the index is bound as AAD ----------------------------------------

    @Test fun aSlotCannotBeTransplantedToAnotherIndex() {
        val array = newArray()
        val kek = key()
        array.seal(3, kek, vmk(), blobId())

        val slot3 = slotBytes(3)
        RandomAccessFile(fileOf(), "rw").use { raf ->
            raf.seek((SlotArray.SLOTS_OFFSET + 4 * 256).toLong())
            raf.write(slot3)
        }

        assertArrayEquals(slot3, slotBytes(4))
        assertNull("transplanted slot opened at the wrong index", array.open(4, kek))
    }

    // --- 3.10 idempotent init --------------------------------------------------

    @Test fun initialiseIfAbsentLeavesAnExistingFileAlone() {
        val array = newArray()
        array.seal(7, key(), vmk(), blobId())
        val before = fileOf().readBytes()

        array.initialiseIfAbsent()
        SlotArray(fileOf()).initialiseIfAbsent()

        assertArrayEquals(before, fileOf().readBytes())
    }

    // --- helpers ---------------------------------------------------------------

    private fun slotRegion(name: String) =
        fileOf(name).readBytes().copyOfRange(SlotArray.SLOTS_OFFSET, SlotArray.FILE_BYTES)

    private fun slotBytes(index: Int) = fileOf().readBytes().copyOfRange(
        SlotArray.SLOTS_OFFSET + index * 256,
        SlotArray.SLOTS_OFFSET + (index + 1) * 256,
    )

    private fun entropy(bytes: ByteArray): Double {
        val counts = IntArray(256)
        bytes.forEach { counts[it.toInt() and 0xFF]++ }
        val n = bytes.size.toDouble()
        return counts.filter { it > 0 }.sumOf { c ->
            val p = c / n
            -p * ln(p) / ln(2.0)
        }
    }

    private fun deflatedSize(bytes: ByteArray): Int {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.size()
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
