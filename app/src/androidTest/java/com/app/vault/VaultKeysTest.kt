package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goterl.lazysodium.interfaces.PwHash
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * P1-2 — the key hierarchy, against `FORMAT.md` §3 and OUTLINE P1-2's Verify:
 * derivation determinism, wrong-passphrase rejection, and domain separation.
 */
@RunWith(AndroidJUnit4::class)
class VaultKeysTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultKeys"
        val PASS_A = "correct horse battery staple".toByteArray()
        val PASS_B = "correct horse battery stapl3".toByteArray()
    }

    @Before fun setUp() { dir = File(ctx.filesDir, "keys-test").apply { mkdirs() } }
    @After fun tearDown() { dir.deleteRecursively() }

    private val P = VaultKeys.ARGON2ID_PARAMS

    private fun salt() = ByteArray(VaultKeys.SALT_BYTES).also { rng.nextBytes(it) }
    private fun blobId() = ByteArray(16).also { rng.nextBytes(it) }

    // --- 2.1-2.3 determinism ---------------------------------------------------

    private fun kek(pass: ByteArray, salt: ByteArray, params: VaultKeys.Argon2idParams = P) =
        VaultKeys.deriveKek(pass, salt, params)

    @Test fun sameInputsDeriveTheSameKek() {
        val salt = salt()
        assertArrayEquals(kek(PASS_A, salt), kek(PASS_A, salt))
    }

    @Test fun differentPassphrasesDeriveDifferentKeks() {
        val salt = salt()
        assertFalse(kek(PASS_A, salt).contentEquals(kek(PASS_B, salt)))
    }

    @Test fun differentSaltsDeriveDifferentKeks() {
        assertFalse(kek(PASS_A, salt()).contentEquals(kek(PASS_A, salt())))
    }

    // --- P2-25: the parameters are an input, not a constant --------------------

    /**
     * **The load-bearing test of P2-25.**
     *
     * A vault is created under parameters that are deliberately not
     * [VaultKeys.ARGON2ID_PARAMS] — standing in for an install made by an earlier
     * release — and then opened through the ordinary unlock path on a build whose
     * constant is the current one. It fails the moment any part of the derivation
     * reaches for the constant instead of the file.
     *
     * That failure is the one this whole change exists to prevent, and it is
     * otherwise invisible: `VaultUnlock` would return "opened nothing", which P1-3
     * requires to be indistinguishable from a wrong passphrase. Nothing else in the
     * suite can tell those apart, which is why this test is written as an assertion
     * about the recovered VMK and not about an error.
     *
     * Cheap parameters, because what is under test is which values are used, not
     * how long they take.
     */
    @Test fun aVaultCreatedUnderOtherParametersOpensOnThisBuild() {
        val older = VaultKeys.Argon2idParams(memoryKib = 8 * 1024, iterations = 2, parallelism = 1)
        assertNotEquals("the fixture must differ from the constant", VaultKeys.ARGON2ID_PARAMS, older)

        val file = File(dir, "older.slots")
        SlotArray(file).initialiseIfAbsent(older)

        val slots = SlotArray(file)
        val vmk = VaultKeys.newVmk()
        val blobId = blobId()
        // Sealed the way `VaultLifecycle.create` seals: under the file's recorded
        // parameters, whatever this build's constant happens to be.
        slots.seal(3, kek(PASS_A, slots.installSalt(), slots.kdfParams()), vmk, blobId)

        val outcome = VaultUnlock.unlock(PASS_A.copyOf(), SlotArray(file))

        assertTrue("the vault did not open", outcome.opened)
        assertArrayEquals("the VMK came back wrong", vmk, outcome.vault!!.vmk)
        assertArrayEquals(blobId, outcome.vault!!.metadataBlobId)
    }

    /** A wrong passphrase is still wrong under an install's own parameters. */
    @Test fun otherParametersDoNotMakeAWrongPassphraseWork() {
        val older = VaultKeys.Argon2idParams(memoryKib = 8 * 1024, iterations = 2, parallelism = 1)
        val file = File(dir, "older-wrong.slots")
        SlotArray(file).initialiseIfAbsent(older)

        val slots = SlotArray(file)
        slots.seal(3, kek(PASS_A, slots.installSalt(), older), VaultKeys.newVmk(), blobId())

        assertFalse(VaultUnlock.unlock(PASS_B.copyOf(), SlotArray(file)).opened)
    }

    /**
     * The header is fixed-width and every field survives it, including the
     * algorithm — the input that would otherwise still be compile-time only.
     */
    @Test fun theKdfHeaderRoundTripsEveryField() {
        val params = VaultKeys.Argon2idParams(
            memoryKib = 262_144,
            iterations = 11,
            parallelism = 3,
            alg = PwHash.Alg.PWHASH_ALG_ARGON2I13,
        )
        val header = params.toHeader()

        assertEquals(VaultKeys.Argon2idParams.HEADER_BYTES, header.size)
        assertEquals(params, VaultKeys.Argon2idParams.fromHeader(header))
    }

    /** An unknown layout version is refused, not guessed at. */
    @Test fun anUnknownSlotLayoutVersionIsRefused() {
        val header = VaultKeys.ARGON2ID_PARAMS.toHeader().also { it[0] = 99 }
        assertThrows(IllegalStateException::class.java) {
            VaultKeys.Argon2idParams.fromHeader(header)
        }
    }

    // --- 2.4-2.5 through SlotArray ---------------------------------------------

    @Test fun wrongPassphraseOpensNothing() {
        val slots = SlotArray(File(dir, "wrong.slots")).also { it.initialiseIfAbsent() }
        val salt = slots.installSalt()

        slots.seal(2, kek(PASS_A, salt), VaultKeys.newVmk(), blobId())

        assertNull(slots.open(2, kek(PASS_B, salt)))
    }

    @Test fun rightPassphraseRecoversTheVmkExactly() {
        val slots = SlotArray(File(dir, "right.slots")).also { it.initialiseIfAbsent() }
        val salt = slots.installSalt()
        val vmk = VaultKeys.newVmk()
        val blobId = blobId()

        slots.seal(2, kek(PASS_A, salt), vmk, blobId)
        val opened = slots.open(2, kek(PASS_A, salt))

        assertArrayEquals(vmk, opened!!.copyOfRange(0, 32))
        assertArrayEquals(blobId, opened.copyOfRange(32, 48))
    }

    // --- 2.6 domain separation, exhaustively -----------------------------------

    @Test fun allDerivedKeysDifferPairwise() {
        val vmk = VaultKeys.newVmk()
        val keys = linkedMapOf(
            "metadata" to VaultKeys.metadataKey(vmk),
            "thumbnail" to VaultKeys.thumbnailKey(vmk),
            "logSeal" to VaultKeys.logSealKey(vmk),
            "logMac" to VaultKeys.logMacKey(vmk)
        )
        val names = keys.keys.toList()

        var pairs = 0
        for (i in names.indices) for (j in i + 1 until names.size) {
            pairs++
            assertFalse(
                "${names[i]} and ${names[j]} collide",
                keys[names[i]]!!.contentEquals(keys[names[j]]!!)
            )
        }
        assertEquals("expected all 6 pairs compared", 6, pairs)
        Log.i(TAG, "domain separation: $pairs pairs, all distinct")
    }

    // --- 2.7 per-blob keys -----------------------------------------------------

    @Test fun blobKeysDifferByBlobAndAreStable() {
        val vmk = VaultKeys.newVmk()
        val a = blobId()
        val b = blobId()

        assertFalse(VaultKeys.blobKey(vmk, a).contentEquals(VaultKeys.blobKey(vmk, b)))
        assertArrayEquals(VaultKeys.blobKey(vmk, a), VaultKeys.blobKey(vmk, a))
    }

    @Test fun blobKeysDifferWhenOnlyTheFirstEightBytesDiffer() {
        val vmk = VaultKeys.newVmk()
        val a = blobId()
        val b = a.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }

        assertFalse(VaultKeys.blobKey(vmk, a).contentEquals(VaultKeys.blobKey(vmk, b)))
    }

    // --- 2.8-2.9 stability and VMK separation ----------------------------------

    @Test fun everySubkeyIsStableAcrossDerivations() {
        val vmk = VaultKeys.newVmk()
        assertArrayEquals(VaultKeys.metadataKey(vmk), VaultKeys.metadataKey(vmk))
        assertArrayEquals(VaultKeys.thumbnailKey(vmk), VaultKeys.thumbnailKey(vmk))
        assertArrayEquals(VaultKeys.logSealKey(vmk), VaultKeys.logSealKey(vmk))
        assertArrayEquals(VaultKeys.logMacKey(vmk), VaultKeys.logMacKey(vmk))
    }

    @Test fun differentVmksShareNoSubkeys() {
        val one = VaultKeys.newVmk()
        val two = VaultKeys.newVmk()
        val blobId = blobId()

        assertFalse(VaultKeys.metadataKey(one).contentEquals(VaultKeys.metadataKey(two)))
        assertFalse(VaultKeys.thumbnailKey(one).contentEquals(VaultKeys.thumbnailKey(two)))
        assertFalse(VaultKeys.logSealKey(one).contentEquals(VaultKeys.logSealKey(two)))
        assertFalse(VaultKeys.logMacKey(one).contentEquals(VaultKeys.logMacKey(two)))
        assertFalse(VaultKeys.blobKey(one, blobId).contentEquals(VaultKeys.blobKey(two, blobId)))
    }

    // --- P2-4 capture keypair --------------------------------------------------

    @Test fun captureKeypairIsStableAcrossDerivations() {
        val vmk = VaultKeys.newVmk()
        val a = VaultKeys.captureKeypair(vmk)
        val b = VaultKeys.captureKeypair(vmk)
        assertArrayEquals(a.publicKey, b.publicKey)
        assertArrayEquals(a.secretKey, b.secretKey)
    }

    @Test fun differentVmksProduceDifferentCaptureKeys() {
        val one = VaultKeys.captureKeypair(VaultKeys.newVmk())
        val two = VaultKeys.captureKeypair(VaultKeys.newVmk())
        assertFalse(one.publicKey.contentEquals(two.publicKey))
        assertFalse(one.secretKey.contentEquals(two.secretKey))
    }

    @Test fun captureSeedCollidesWithNoOtherSubkey() {
        val vmk = VaultKeys.newVmk()
        val seed = VaultKeys.captureSeed(vmk)
        val blobId = blobId()
        val others = listOf(
            VaultKeys.metadataKey(vmk),
            VaultKeys.thumbnailKey(vmk),
            VaultKeys.logSealKey(vmk),
            VaultKeys.logMacKey(vmk),
            VaultKeys.blobKey(vmk, blobId),
            VaultKeys.metadataBlobId(vmk),
        )
        others.forEach { assertFalse("capture seed collides with a subkey", seed.contentEquals(it)) }
    }

    @Test fun aCaptureSealedToOneVaultDoesNotOpenUnderAnother() {
        val a = VaultKeys.captureKeypair(VaultKeys.newVmk())
        val b = VaultKeys.captureKeypair(VaultKeys.newVmk())
        val message = "who is holding the phone".toByteArray()

        val sealed = Capture.seal(a.publicKey, message)
        assertArrayEquals(message, Capture.open(a.secretKey, a.publicKey, sealed))
        assertNull(Capture.open(b.secretKey, b.publicKey, sealed))
    }

    // --- 2.10 timing -----------------------------------------------------------

    @Test fun recordDerivationWallClock() {
        val salt = salt()
        kek(PASS_A, salt)   // warm up

        val runs = (1..5).map {
            val start = System.nanoTime()
            kek(PASS_A, salt)
            (System.nanoTime() - start) / 1_000_000
        }
        val median = runs.sorted()[runs.size / 2]

        Log.i(
            TAG,
            "deriveKek m=${VaultKeys.ARGON2ID_PARAMS.memoryKib / 1024}MiB " +
                "t=${VaultKeys.ARGON2ID_PARAMS.iterations} p=${VaultKeys.ARGON2ID_PARAMS.parallelism} " +
                ": median ${median} ms (runs: $runs)"
        )
        assertTrue("derivation produced no timing", median > 0)
    }
}
