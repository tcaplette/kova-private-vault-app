package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Random

/**
 * Contact shares against real containers — P4-5.
 *
 * Every container here is written by `ShareSend.sealToContact`, the code that
 * ships, probed through `ShareOpen.probe` and opened through
 * `ShareOpen.openWithIdentity`, the code that ships —
 * `ShareOpenDeviceTest`'s rule, for its reason: a test that opens bytes it wrote
 * itself proves the reader agrees with the test, not with the sender.
 *
 * **Announced test-only state**: the app's real `identity.bin`, its `vault.identity`
 * Keystore alias, and the consumed-share ledger, all install-wide by design. All
 * three are removed in `tearDown`, including on failure.
 */
@RunWith(AndroidJUnit4::class)
class ContactShareDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val sodium = Sodium.lazy

    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray

    private fun identityFile() = File(ctx.filesDir, "identity.bin")
    private fun ledger() = File(ctx.filesDir, ShareOpen.LEDGER)

    @Before fun setUp() {
        dir = File(ctx.filesDir, "contact-share-test").apply { mkdirs() }
        store = BlobStore(dir)
        vmk = VaultKeys.newVmk()
        Identity.deleteKeyForTest()
        identityFile().delete()
        ledger().delete()
        ShareOpen.sweep(ctx)
        ShareSend.sweep(ctx)
    }

    @After fun tearDown() {
        Identity.deleteKeyForTest()
        identityFile().delete()
        File(ctx.filesDir, "identity.bin.tmp").delete()
        ledger().delete()
        ShareOpen.sweep(ctx)
        ShareSend.sweep(ctx)
        dir.deleteRecursively()
        vmk.fill(0)
    }

    /** Another install's X-Wing seed — a stranger. */
    private fun strangerSeed(): ByteArray = ByteArray(XWing.SEED_BYTES).also { SecureRandom().nextBytes(it) }

    /** This install's own seed, as the receive flow reads it. */
    private fun mySeed(): ByteArray = Identity.seed(ctx)!!

    private fun item(bytes: ByteArray, name: String = "sunset.jpg"): VaultItem {
        val written: WrittenBlob = store.write(ByteArrayInputStream(bytes), vmk)
        return VaultItem(
            blobIds = listOf(written.blobId),
            plaintextLength = written.plaintextLength,
            thumb = null,
            filename = name,
            album = null,
            createdAt = null,
            importedAt = System.currentTimeMillis(),
            tags = emptyList(),
        )
    }

    private fun content(size: Int): ByteArray = ByteArray(size).also { Random(7).nextBytes(it) }

    private fun readBack(outcome: ShareOpen.Outcome.Opened): ByteArray {
        val out = ByteArrayOutputStream()
        store.reader(listOf(outcome.blobId), vmk, outcome.plaintextLength).use { r ->
            val buf = ByteArray(BlobStore.CHUNK)
            for (i in 0 until r.chunkCount) {
                val n = r.readChunk(i, buf)
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun anyFileContains(root: File, needle: ByteArray): Boolean =
        root.walkTopDown().filter { it.isFile && it.length() < 4_000_000 }.any { f ->
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: return@any false
            indexOf(bytes, needle) >= 0
        }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun sealTo(
        recipient: ByteArray,
        bytes: ByteArray,
        policy: ShareContainer.Policy = ShareContainer.Policy.Unmanaged,
    ): File = ShareSend.sealToContact(ctx, store, item(bytes), vmk, policy, recipient)

    // ── the round trip ────────────────────────────────────────────────────────

    @Test
    fun aContactShareOpensOnTheHolderOfTheKeyWithNothingTyped() {
        val plaintext = content(120_000)
        // This install is the recipient: seal to its own published key, which is
        // what a sender who scanned this phone would have.
        val mine = (Identity.state(ctx) as Identity.State.Ready).publicKey
        val container = sealTo(mine, plaintext)

        assertEquals(
            "the probe finds it sealed here, so the flow asks for nothing",
            ShareContainer.Probe.SEALED_HERE,
            ShareOpen.probe(container, mySeed()),
        )

        val outcome = ShareOpen.openWithIdentity(ctx, container, mySeed(), store, vmk)
        val opened = outcome as ShareOpen.Outcome.Opened
        assertEquals("sunset.jpg", opened.details.displayName)
        assertEquals(plaintext.size.toLong(), opened.plaintextLength)

        assertArrayEquals(
            "the recipient's bytes are the sender's bytes",
            plaintext,
            readBack(opened),
        )
    }

    @Test
    fun aThirdInstallGetsTheSingleFailure() {
        val mine = (Identity.state(ctx) as Identity.State.Ready).publicKey
        val container = sealTo(mine, content(4_000))
        assertEquals(
            "a stranger's probe finds nothing, so they get the code entry like any file",
            ShareContainer.Probe.NOT_HERE,
            ShareOpen.probe(container, strangerSeed()),
        )
        try {
            ShareOpen.openWithIdentity(ctx, container, strangerSeed(), store, vmk)
            throw AssertionError("a container sealed elsewhere must not open")
        } catch (_: ShareContainer.ShareFailure) {
        }
    }

    @Test
    fun aTamperedKeyBlockIsTheSingleFailure() {
        val mine = (Identity.state(ctx) as Identity.State.Ready).publicKey
        val bytes = sealTo(mine, content(4_000)).readBytes()
        // The key block is the X-Wing ciphertext, and every chunk's AAD.
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val tampered = File(dir, "tampered").also { it.writeBytes(bytes) }

        assertEquals(ShareContainer.Probe.NOT_HERE, ShareOpen.probe(tampered, mySeed()))
        try {
            ShareOpen.openWithIdentity(ctx, tampered, mySeed(), store, vmk)
            throw AssertionError("a tampered key block must not open")
        } catch (_: ShareContainer.ShareFailure) {
        }
    }

    @Test
    fun theProbeSendsEveryOtherFileToTheCodeEntry() {
        Identity.state(ctx)
        val photo = File(dir, "photo.jpg").also { it.writeBytes(ByteArray(64) { 0x41 }) }
        assertEquals("a file that is not a share", ShareContainer.Probe.NOT_HERE, ShareOpen.probe(photo, mySeed()))

        val codeShare = ShareSend.seal(
            ctx, store, item(content(2_000)), vmk, ShareContainer.Policy.Unmanaged,
            listOf("abandon", "ability", "able", "about"),
        )
        assertEquals("a code share", ShareContainer.Probe.NOT_HERE, ShareOpen.probe(codeShare, mySeed()))
    }

    @Test
    fun theProbeWritesNothing() {
        val mine = (Identity.state(ctx) as Identity.State.Ready).publicKey
        val container = sealTo(mine, content(90_000), ShareContainer.Policy.SingleView)
        fun snapshot() = ctx.filesDir.walkTopDown().filter { it.isFile }
            .associate { it.path to (it.length() to it.lastModified()) }
        val before = snapshot()
        assertEquals(ShareContainer.Probe.SEALED_HERE, ShareOpen.probe(container, mySeed()))
        assertEquals("no blob, no index row, no ledger entry", before, snapshot())
        assertTrue("and a single-view share is not spent by being probed", !ledger().exists())
    }

    @Test
    fun aNewerContactShareIsRefusedByTheProbeAndByTheOpen() {
        val mine = (Identity.state(ctx) as Identity.State.Ready).publicKey
        // A version this build never writes, sealed by hand from §12's layout.
        val encapsulated = XWing.encapsulate(mine, SecureRandom())
        val plain = ByteBuffer.allocate(31 + 4).order(ByteOrder.LITTLE_ENDIAN)
            .put(3.toByte()).put(0.toByte()).putLong(0L).put(ByteArray(16)).put(5.toByte())
            .putShort(0).putShort(0).put(byteArrayOf(1, 2, 3, 4)).array()
        val prefix = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = prefix + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(1L shl 63).array()
        val aad = "share.contact".toByteArray() + encapsulated.ciphertext
        val sealed = ByteArray(plain.size + 16)
        check(
            sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                sealed, longArrayOf(0), plain, plain.size.toLong(), aad, aad.size.toLong(),
                null, nonce, encapsulated.sharedSecret,
            )
        )
        val newer = File(dir, "newer").also { it.writeBytes(encapsulated.ciphertext + prefix + sealed) }

        assertEquals(ShareContainer.Probe.NEWER_VERSION, ShareOpen.probe(newer, mySeed()))
        val outcome = ShareOpen.openWithIdentity(ctx, newer, mySeed(), store, vmk)
        assertEquals(ShareOpen.Refusal.NewerVersion, (outcome as ShareOpen.Outcome.Refused).refusal)
    }

    // ── policy, which does not know about key modes ───────────────────────────

    @Test
    fun aSingleViewContactShareOpensOnceAndThenRefuses() {
        val mine = (Identity.state(ctx) as Identity.State.Ready).publicKey
        val container = sealTo(mine, content(3_000), ShareContainer.Policy.SingleView)

        val first = ShareOpen.openWithIdentity(ctx, container, mySeed(), store, vmk)
        assertTrue("the first open succeeds", first is ShareOpen.Outcome.Opened)

        val second = ShareOpen.openWithIdentity(ctx, container, mySeed(), store, vmk)
        assertEquals(
            "the second is the policy refusal, not the single failure",
            ShareOpen.Refusal.AlreadyOpened,
            (second as ShareOpen.Outcome.Refused).refusal,
        )
    }

    @Test
    fun anExpiredContactShareRefusesWithItsDate() {
        val mine = (Identity.state(ctx) as Identity.State.Ready).publicKey
        val expired = System.currentTimeMillis() - 60_000
        val container = sealTo(mine, content(3_000), ShareContainer.Policy.Expiry(expired))

        val outcome = ShareOpen.openWithIdentity(ctx, container, mySeed(), store, vmk)
        val refusal = (outcome as ShareOpen.Outcome.Refused).refusal
        assertEquals(expired, (refusal as ShareOpen.Refusal.Expired).atEpochMillis)
    }

    // ── I3 ────────────────────────────────────────────────────────────────────

    @Test
    fun noPlaintextIsWrittenAnywhereDuringOrAfterASeal() {
        val marker = "MARKER-e7f1a2c4-contact-share".toByteArray(Charsets.US_ASCII)
        val plaintext = ByteArray(80_000).also { Random(3).nextBytes(it) }
        marker.copyInto(plaintext, 40_000)

        val mine = (Identity.state(ctx) as Identity.State.Ready).publicKey
        val container = sealTo(mine, plaintext, ShareContainer.Policy.Unmanaged)

        assertTrue(
            "a plaintext copy survived the seal — I3",
            !anyFileContains(ctx.filesDir, marker),
        )
        assertTrue(
            "a plaintext copy is in the cache — I3",
            !anyFileContains(ctx.cacheDir, marker),
        )
        assertTrue(
            "the container itself must not carry the plaintext",
            indexOf(container.readBytes(), marker) < 0,
        )
    }

    @Test
    fun theIdentitySecretIsZeroedByAnOpenThatFails() {
        val mine = (Identity.state(ctx) as Identity.State.Ready).publicKey
        val container = sealTo(mine, content(2_000))
        val seed = strangerSeed()   // the wrong identity, so the open fails
        runCatching { ShareOpen.openWithIdentity(ctx, container, seed, store, vmk) }
        assertTrue(
            "a failed open must still zero the seed it was handed",
            seed.all { it == 0.toByte() },
        )
        val probed = strangerSeed()
        ShareOpen.probe(container, probed)
        assertTrue("and so must the probe", probed.all { it == 0.toByte() })
    }
}
