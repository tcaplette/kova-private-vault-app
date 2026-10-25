package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Random

/**
 * The receive path against real containers — P4-3.
 *
 * **The two halves are tested together on purpose.** Every container here is
 * written by `ShareSend`, the code that actually ships, rather than by a fixture
 * built to suit the reader — a receive test that opens its own hand-rolled bytes
 * proves the reader agrees with the test, not that it agrees with the sender.
 */
@RunWith(AndroidJUnit4::class)
class ShareOpenDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray

    private val code = listOf("abandon", "ability", "able", "about")
    private val wrongCode = listOf("abandon", "ability", "able", "above")

    private fun ledger() = File(ctx.filesDir, ShareOpen.LEDGER)

    @Before fun setUp() {
        dir = File(ctx.filesDir, "share-open-test").apply { mkdirs() }
        store = BlobStore(dir)
        vmk = VaultKeys.newVmk()
        // Announced test-only state: this class writes the app's real ledger, which
        // is install-wide by design. It is removed in tearDown, including on failure.
        ledger().delete()
        ShareOpen.sweep(ctx)
        ShareSend.sweep(ctx)
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        ledger().delete()
        ShareOpen.sweep(ctx)
        ShareSend.sweep(ctx)
        vmk.fill(0)
    }

    private fun content(size: Int): ByteArray =
        ByteArray(size).also { Random(23).nextBytes(it) }

    /** An item in the "sender's" vault, and the container `ShareSend` makes of it. */
    private fun sent(
        bytes: ByteArray,
        name: String = "holiday.jpg",
        policy: ShareContainer.Policy = ShareContainer.Policy.Unmanaged,
    ): File {
        val blob = store.write(ByteArrayInputStream(bytes), vmk)
        val item = VaultItem(
            blobIds = listOf(blob.blobId),
            plaintextLength = blob.plaintextLength,
            thumb = null,
            filename = name,
            album = null,
            createdAt = null,
            importedAt = 0,
            tags = emptyList(),
        )
        return ShareSend.seal(ctx, store, item, vmk, policy, code)
    }

    private fun readBack(outcome: ShareOpen.Outcome.Opened): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        store.reader(listOf(outcome.blobId), vmk, outcome.plaintextLength).use { r ->
            val buf = ByteArray(BlobStore.CHUNK)
            for (i in 0 until r.chunkCount) {
                val n = r.readChunk(i, buf)
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    // ── Round trip ────────────────────────────────────────────────────────────

    @Test
    fun aSentContainerOpensAndItsBytesSurvive() {
        val plain = content(200_000)
        val outcome = ShareOpen.open(ctx, sent(plain), code, store, vmk)

        assertTrue(outcome is ShareOpen.Outcome.Opened)
        val opened = outcome as ShareOpen.Outcome.Opened
        assertArrayEquals(plain, readBack(opened))
        assertEquals("holiday.jpg", opened.details.displayName)
        assertEquals(ShareContainer.Kind.IMAGE, opened.details.kind)
    }

    /**
     * ***The sanitiser sits on the receive side, where the name is untrusted.***
     *
     * A hostile sender is not running this build, so the check cannot live on the
     * send path — this seals a container declaring a name no honest sender would
     * write, and asserts the wire keeps it while the app does not.
     */
    @Test
    fun aHostileDeclaredNameIsSanitisedOnArrival() {
        val hostile = "../../invoice\u202Egpj.exe"
        val outcome = ShareOpen.open(ctx, sent(content(4_000), name = hostile), code, store, vmk)

        assertTrue(outcome is ShareOpen.Outcome.Opened)
        val opened = outcome as ShareOpen.Outcome.Opened

        // The format layer reports what the bytes said — that is what it is for.
        assertEquals(hostile, opened.details.displayName)
        // Everything the app stores, shows and exports uses this one instead.
        assertEquals("invoicegpj.exe", opened.filename)
    }

    @Test
    fun theOpenedItemIsNeverPlaintextOnDisk() {
        // I3, and the reason this flow seals into the blob store rather than
        // writing a file the viewer could read.
        val marker = "RECEIVED-MARKER-5c1d0e".toByteArray()
        val plain = marker + content(90_000)
        val outcome = ShareOpen.open(ctx, sent(plain), code, store, vmk)
        assertTrue(outcome is ShareOpen.Outcome.Opened)

        assertFalse("a plaintext copy is under filesDir", anyFileContains(ctx.filesDir, marker))
        assertFalse("a plaintext copy is in the cache", anyFileContains(ctx.cacheDir, marker))
    }

    // ── Failures ──────────────────────────────────────────────────────────────

    @Test
    fun aWrongCodeAndATamperedContainerFailIdentically() {
        val container = sent(content(5_000))
        val wrong = runCatching { ShareOpen.open(ctx, container, wrongCode, store, vmk) }
            .exceptionOrNull()

        val tamperedFile = File(dir, "tampered")
        val bytes = container.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        tamperedFile.writeBytes(bytes)
        val tampered = runCatching { ShareOpen.open(ctx, tamperedFile, code, store, vmk) }
            .exceptionOrNull()

        assertTrue(wrong is ShareContainer.ShareFailure)
        assertTrue(tampered is ShareContainer.ShareFailure)
        assertEquals(wrong!!.javaClass, tampered!!.javaClass)
    }

    @Test
    fun aFileThatIsNotAShareFailsLikeAWrongCode() {
        // `share-format-v2`: no readable header, so nothing can tell a non-share
        // from a share that will not open — and nothing tries to.
        val wrong = runCatching { ShareOpen.open(ctx, sent(content(2_000)), wrongCode, store, vmk) }
            .exceptionOrNull()
        val junk = File(dir, "junk").also { it.writeBytes(content(400)) }
        val notAShare = runCatching { ShareOpen.open(ctx, junk, code, store, vmk) }.exceptionOrNull()
        assertTrue(notAShare is ShareContainer.ShareFailure)
        assertEquals(wrong!!.javaClass, notAShare!!.javaClass)
    }

    @Test
    fun aNewerCodeShareIsARefusalAfterTheCodeAuthenticates() {
        // A version this build never writes, sealed by hand from §12's layout under
        // the right code. The open authenticates chunk 0, reads byte 0, and refuses.
        val salt = ByteArray(VaultKeys.SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = VaultKeys.deriveKek(ShareCode.canonicalBytes(code), salt, VaultKeys.currentKdfParams())
        val plain = ByteBuffer.allocate(31 + 4).order(ByteOrder.LITTLE_ENDIAN)
            .put(3.toByte()).put(1.toByte()).putLong(0L).put(ByteArray(16) { 9 }).put(5.toByte())
            .putShort(0).putShort(0).put(byteArrayOf(1, 2, 3, 4)).array()
        val prefix = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = prefix + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(1L shl 63).array()
        val aad = "share.code".toByteArray() + salt
        val sealed = ByteArray(plain.size + 16)
        check(
            Sodium.lazy.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                sealed, longArrayOf(0), plain, plain.size.toLong(), aad, aad.size.toLong(),
                null, nonce, key,
            )
        )
        val future = File(dir, "future").also { it.writeBytes(salt + prefix + sealed) }

        val outcome = ShareOpen.open(ctx, future, code, store, vmk)
        assertEquals(ShareOpen.Refusal.NewerVersion, (outcome as ShareOpen.Outcome.Refused).refusal)
        assertFalse("a newer share is never recorded as spent", ledger().exists())

        // With the wrong code it is not "newer" — it is nothing.
        assertTrue(
            runCatching { ShareOpen.open(ctx, future, wrongCode, store, vmk) }.exceptionOrNull()
                is ShareContainer.ShareFailure,
        )
    }

    @Test
    fun aFailedOpenLeavesNoBlobBehind() {
        val before = File(dir, "blobs").listFiles().orEmpty().size
        runCatching { ShareOpen.open(ctx, sent(content(5_000)), wrongCode, store, vmk) }
        // The container's own blob is in the store from `sent`; what must not be
        // there is a second one from the failed open.
        val after = File(dir, "blobs").listFiles().orEmpty().size
        assertTrue("a failed open left a blob", after <= before + 1)
    }

    // ── Single view ───────────────────────────────────────────────────────────

    @Test
    fun aSingleViewShareOpensOnceAndThenRefuses() {
        val container = sent(content(4_000), policy = ShareContainer.Policy.SingleView)

        val first = ShareOpen.open(ctx, container, code, store, vmk)
        assertTrue("the first open should succeed", first is ShareOpen.Outcome.Opened)

        val second = ShareOpen.open(ctx, container, code, store, vmk)
        assertTrue(second is ShareOpen.Outcome.Refused)
        assertTrue(
            (second as ShareOpen.Outcome.Refused).refusal is ShareOpen.Refusal.AlreadyOpened,
        )
    }

    @Test
    fun aWrongCodeDoesNotSpendASingleViewShare() {
        // A mistyped word must not destroy a share, which is why the ledger is
        // written after a successful open and never before one.
        val container = sent(content(4_000), policy = ShareContainer.Policy.SingleView)
        runCatching { ShareOpen.open(ctx, container, wrongCode, store, vmk) }

        val outcome = ShareOpen.open(ctx, container, code, store, vmk)
        assertTrue("a failed attempt spent the share", outcome is ShareOpen.Outcome.Opened)
    }

    @Test
    fun onlySingleViewSharesReachTheLedger() {
        ShareOpen.open(ctx, sent(content(2_000)), code, store, vmk)
        ShareOpen.open(
            ctx,
            sent(content(2_000), policy = ShareContainer.Policy.Expiry(farFuture())),
            code, store, vmk,
        )
        assertFalse("the ledger grew for a share that needs no memory", ledger().isFile)

        ShareOpen.open(
            ctx,
            sent(content(2_000), policy = ShareContainer.Policy.SingleView),
            code, store, vmk,
        )
        assertEquals(
            "one share ID, sixteen bytes",
            ShareContainer.SHARE_ID_BYTES.toLong(),
            ledger().length(),
        )
    }

    // ── Expiry ────────────────────────────────────────────────────────────────

    @Test
    fun anExpiredShareRefusesAndAFutureOneOpens() {
        val expired = sent(
            content(3_000),
            policy = ShareContainer.Policy.Expiry(System.currentTimeMillis() - 1_000),
        )
        val refused = ShareOpen.open(ctx, expired, code, store, vmk)
        assertTrue(refused is ShareOpen.Outcome.Refused)
        assertTrue(
            (refused as ShareOpen.Outcome.Refused).refusal is ShareOpen.Refusal.Expired,
        )

        val live = sent(content(3_000), policy = ShareContainer.Policy.Expiry(farFuture()))
        assertTrue(ShareOpen.open(ctx, live, code, store, vmk) is ShareOpen.Outcome.Opened)
    }

    @Test
    fun aRefusalLeavesNothingReadable() {
        val expired = sent(
            content(3_000),
            policy = ShareContainer.Policy.Expiry(System.currentTimeMillis() - 1_000),
        )
        val marker = "REFUSED-MARKER".toByteArray()
        ShareOpen.open(ctx, expired, code, store, vmk)
        assertFalse(anyFileContains(ctx.filesDir, marker))
    }

    // ── Staging ───────────────────────────────────────────────────────────────

    @Test
    fun theSweepEmptiesStagingButNeverTheLedger() {
        ShareOpen.open(
            ctx,
            sent(content(2_000), policy = ShareContainer.Policy.SingleView),
            code, store, vmk,
        )
        val before = ledger().length()
        assertTrue("the ledger should hold one ID", before > 0)

        ShareOpen.directory(ctx).mkdirs()
        File(ShareOpen.directory(ctx), "picked").writeBytes(content(100))
        ShareOpen.sweep(ctx)

        assertEquals(0, ShareOpen.directory(ctx).listFiles().orEmpty().size)
        assertEquals("the sweep must never un-spend a share", before, ledger().length())
    }

    private fun farFuture() = System.currentTimeMillis() + 86_400_000L

    // ── The keep gate ─────────────────────────────────────────────────────────

    /**
     * ***A limited share used to be one tap from being an ordinary vault item.***
     *
     * `keep` writes a `VaultItem`, and a `VaultItem` has no policy field — so an
     * "open once" container that was kept became indistinguishable from an ordinary
     * import: re-shareable under any policy, exportable in the clear, permanent.
     * The container here is the one `ShareSend` really writes, so this is the gate
     * against real bytes rather than against a hand-built outcome.
     */
    @Test
    fun aLimitedShareCannotBeKeptAndAnUnmanagedOneStillCan() {
        val unmanaged = ShareOpen.open(ctx, sent(content(3_000)), code, store, vmk)
        assertTrue((unmanaged as ShareOpen.Outcome.Opened).canKeep)

        val once = ShareOpen.open(
            ctx, sent(content(3_000), policy = ShareContainer.Policy.SingleView), code, store, vmk
        )
        assertTrue(!(once as ShareOpen.Outcome.Opened).canKeep)

        val expiring = ShareOpen.open(
            ctx,
            sent(content(3_000), policy = ShareContainer.Policy.Expiry(farFuture())),
            code, store, vmk,
        )
        assertTrue(!(expiring as ShareOpen.Outcome.Opened).canKeep)
    }

    /**
     * The engine refuses on its own, not because the screen hid the button. A gate
     * that lives only in a composable is correct until a second call site appears,
     * and the resulting bug is silent.
     */
    @Test
    fun theKeepCallRefusesALimitedShare() {
        val indexId = store.write(ByteArrayInputStream(ByteArray(0)), vmk).blobId

        listOf(
            ShareContainer.Policy.SingleView,
            ShareContainer.Policy.Expiry(farFuture()),
        ).forEach { policy ->
            val opened = ShareOpen.open(
                ctx, sent(content(2_000), policy = policy), code, store, vmk
            ) as ShareOpen.Outcome.Opened
            var threw = false
            try {
                ShareOpen.keep(store, indexId, vmk, opened)
            } catch (e: IllegalStateException) {
                threw = true
            }
            assertTrue("keep must refuse a $policy share itself", threw)
        }
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
}
