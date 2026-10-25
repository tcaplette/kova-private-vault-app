package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Random

/**
 * The share container's golden-vector inputs — everything deterministic that
 * [DeviceFixtures.generateShareVectors] seals and [ShareContainerTest]'s vector
 * tests must recover from the **committed** bytes.
 *
 * The credentials and plaintext are recomputable, so the assets hold ciphertext
 * only. **Fixtures are add-only from version 2 on**, P2-25's rule: editing a
 * committed container turns the vector test back into a round trip without
 * changing a line of it. Version 1 was removed by `share-format-v2` because no
 * release ever published it; a future format adds `share/v3/`, and `v2` stays.
 */
internal object ShareVectorFixture {
    const val DIR = "fixtures/share/v2"

    val CODE = listOf("abandon", "ability", "able", "about")
    const val EXPIRY_MILLIS = 1_893_456_000_000L // 2030-01-01T00:00:00Z

    /** Contact-mode recipient's X-Wing seed — a test-only identity. */
    val SEED = ByteArray(XWing.SEED_BYTES) { 0x42 }

    val CODE_PRELUDE = ShareContainer.Prelude(
        ShareContainer.Kind.NOTE, "vector.txt", "text/plain"
    )
    val CONTACT_PRELUDE = ShareContainer.Prelude(
        ShareContainer.Kind.IMAGE, "vector.jpg", "image/jpeg"
    )

    /** ~1.5 chunks, so the committed bytes exercise a boundary and the final marker. */
    fun content(): ByteArray = ByteArray(100_000).also { Random(7).nextBytes(it) }

    fun publicKey(): ByteArray = XWing.keypairFromSeed(SEED).publicKey
}

/**
 * `ShareContainer` — `FORMAT.md` §12, version 2.
 *
 * Device test because every path crosses libsodium. Code-mode opens each pay a
 * real Argon2id derivation at the current released costs; the byte-by-byte tamper
 * sweep runs against a contact-mode container, where a failed open costs one
 * decapsulation, and code mode's own key block — the salt — is swept separately.
 */
@RunWith(AndroidJUnit4::class)
class ShareContainerTest {

    private val sodium = Sodium.lazy
    private val rng = SecureRandom()

    // A fixed, valid code: canonical form is the codec's job, tested on the JVM.
    private val code = listOf("abandon", "ability", "able", "about")
    private val wrongCode = listOf("abandon", "ability", "able", "above")

    private val name = "sunset over the pier.jpg"
    private val prelude = ShareContainer.Prelude(
        kind = ShareContainer.Kind.IMAGE,
        displayName = name,
        mime = "image/jpeg",
    )

    /** §12 v2's fixed layout, restated here so the test does not borrow the code under test. */
    private val contactBlock = XWing.CIPHERTEXT_BYTES
    private val codeBlock = VaultKeys.SALT_BYTES
    private val prefixBytes = 16
    private val preludeBytes = 1 + 1 + 8 + 16 + 1 + 2 + name.length + 2 + "image/jpeg".length

    /** Deterministic content: the tests compare bytes, not luck. */
    private fun content(size: Int): ByteArray =
        ByteArray(size).also { Random(42).nextBytes(it) }

    private fun seed(): ByteArray = ByteArray(XWing.SEED_BYTES).also { rng.nextBytes(it) }

    private fun sealWithCode(
        content: ByteArray,
        policy: ShareContainer.Policy = ShareContainer.Policy.Unmanaged,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ShareContainer.sealWithCode(ByteArrayInputStream(content), out, prelude, policy, code)
        return out.toByteArray()
    }

    private fun sealToSeed(
        seed: ByteArray,
        content: ByteArray,
        policy: ShareContainer.Policy = ShareContainer.Policy.Unmanaged,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ShareContainer.sealToRecipient(
            ByteArrayInputStream(content), out, prelude, policy, XWing.keypairFromSeed(seed).publicKey,
        )
        return out.toByteArray()
    }

    private fun openWithCode(
        container: ByteArray,
        words: List<String> = code,
    ): Pair<ShareContainer.Opened, ByteArray> {
        val out = ByteArrayOutputStream()
        val opened = ShareContainer.openWithCode(ByteArrayInputStream(container), out, words)
        return opened to out.toByteArray()
    }

    private fun openWithSeed(container: ByteArray, seed: ByteArray): Pair<ShareContainer.Opened, ByteArray> {
        val out = ByteArrayOutputStream()
        val opened = ShareContainer.openWithSeed(ByteArrayInputStream(container), out, seed)
        return opened to out.toByteArray()
    }

    private fun assertSingleFailure(what: String, open: () -> Unit) {
        try {
            open()
            fail("$what opened")
        } catch (expected: ShareContainer.ShareFailure) {
        }
    }

    /**
     * A one-chunk container sealed by hand from §12's layout: key block ‖ prefix ‖
     * one final chunk, AAD = [label] ‖ key block. It lets a test write what this
     * build never would — a newer version byte, an unknown policy mode, the wrong
     * label — and still authenticate.
     */
    private fun handSealed(
        label: String,
        keyBlock: ByteArray,
        key: ByteArray,
        version: Int = 2,
        policyMode: Int = 0,
        body: ByteArray = content(100),
    ): ByteArray {
        val n = "x.bin".toByteArray()
        val m = "application/octet-stream".toByteArray()
        val plain = ByteBuffer.allocate(31 + n.size + m.size + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(version.toByte()).put(policyMode.toByte()).putLong(0L)
            .put(ByteArray(16) { 7 }).put(5.toByte()) // Kind.FILE
            .putShort(n.size.toShort()).put(n).putShort(m.size.toShort()).put(m).put(body)
            .array()
        val prefix = ByteArray(prefixBytes).also { rng.nextBytes(it) }
        val nonce = ByteArray(24).also {
            prefix.copyInto(it)
            ByteBuffer.wrap(it, 16, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(1L shl 63)
        }
        val aad = label.toByteArray(Charsets.US_ASCII) + keyBlock
        val sealed = ByteArray(plain.size + 16)
        check(
            sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                sealed, longArrayOf(0), plain, plain.size.toLong(),
                aad, aad.size.toLong(), null, nonce, key,
            )
        )
        return keyBlock + prefix + sealed
    }

    /** A hand-sealed contact container to [seed], under [label]. */
    private fun handSealedToSeed(seed: ByteArray, label: String = "share.contact", version: Int = 2, policyMode: Int = 0): ByteArray {
        val encapsulated = XWing.encapsulate(XWing.keypairFromSeed(seed).publicKey, rng)
        return handSealed(label, encapsulated.ciphertext, encapsulated.sharedSecret, version, policyMode)
    }

    // ── Round trips ────────────────────────────────────────────────────────────

    @Test
    fun codeModeRoundTripsMultiChunk() {
        // ~3.2 chunks, so boundaries, ordering and the final marker all engage.
        val plain = content(210_000)
        val expiry = ShareContainer.Policy.Expiry(1_900_000_000_000L)
        val container = sealWithCode(plain, expiry)

        val (opened, decrypted) = openWithCode(container)
        assertArrayEquals(plain, decrypted)
        assertEquals(ShareContainer.Kind.IMAGE, opened.kind)
        assertEquals(name, opened.displayName)
        assertEquals("image/jpeg", opened.mime)
        assertEquals(ShareContainer.SHARE_ID_BYTES, opened.shareId.size)
        val policy = opened.policy as ShareContainer.Policy.Expiry
        assertEquals(1_900_000_000_000L, policy.atEpochMillis)
    }

    /**
     * ***The file kind survives the wire.*** It is prelude code 5, added after the
     * first four shipped, and the byte is the only thing that carries it.
     */
    @Test
    fun theFileKindRoundTrips() {
        val plain = content(4_000)
        val out = ByteArrayOutputStream()
        ShareContainer.sealWithCode(
            ByteArrayInputStream(plain),
            out,
            ShareContainer.Prelude(
                kind = ShareContainer.Kind.FILE,
                displayName = "contract.pdf",
                mime = "application/pdf",
            ),
            ShareContainer.Policy.Unmanaged,
            code,
        )

        val (opened, decrypted) = openWithCode(out.toByteArray())
        assertArrayEquals(plain, decrypted)
        assertEquals(ShareContainer.Kind.FILE, opened.kind)
        assertEquals("contract.pdf", opened.displayName)
        assertEquals("application/pdf", opened.mime)
    }

    @Test
    fun contactModeRoundTripsMultiChunk() {
        val seed = seed()
        val plain = content(150_000)
        val container = sealToSeed(seed, plain, ShareContainer.Policy.SingleView)
        assertEquals(
            "key block ‖ prefix ‖ chunks, and nothing else",
            contactBlock + prefixBytes + (preludeBytes + plain.size) + 3 * 16,
            container.size,
        )
        val (opened, decrypted) = openWithSeed(container, seed)
        assertArrayEquals(plain, decrypted)
        assertTrue(opened.policy is ShareContainer.Policy.SingleView)
        assertEquals(name, opened.displayName)
    }

    @Test
    fun emptyContentRoundTrips() {
        // A note can be empty; the prelude alone still makes a full container.
        val container = sealWithCode(ByteArray(0))
        val (opened, decrypted) = openWithCode(container)
        assertEquals(0, decrypted.size)
        assertEquals("image/jpeg", opened.mime)
    }

    @Test
    fun exactChunkMultipleRoundTrips() {
        // Prelude + content landing exactly on a chunk boundary is the case
        // where a missing final marker would hide — the seam §12 exists for.
        val plain = content(2 * BlobStore.CHUNK - preludeBytes)
        val seed = seed()
        val (_, decrypted) = openWithSeed(sealToSeed(seed, plain), seed)
        assertArrayEquals(plain, decrypted)
    }

    // ── Wrong credentials ─────────────────────────────────────────────────────

    @Test
    fun wrongCodeIsTheSingleFailure() {
        val container = sealWithCode(content(1_000))
        assertSingleFailure("a wrong code") { openWithCode(container, wrongCode) }
    }

    @Test
    fun wrongRecipientIsTheSingleFailure() {
        val container = sealToSeed(seed(), content(1_000))
        assertSingleFailure("a different identity") { openWithSeed(container, seed()) }
    }

    // ── Tamper ────────────────────────────────────────────────────────────────

    @Test
    fun everyKeyBlockPrefixAndPreludeByteIsLoadBearing() {
        // Contact mode: 1,120 key-block bytes, 16 prefix bytes, and the sealed
        // prelude at the head of chunk 0. Flipping any one must be the single
        // failure — the key block through the label-and-block AAD, the prefix
        // through the nonce, the prelude through the chunk tag. The policy byte
        // and expiry are in the prelude, so a loosened policy is in this sweep.
        val seed = seed()
        val container = sealToSeed(seed, content(1_000), ShareContainer.Policy.SingleView)
        val swept = contactBlock + prefixBytes + preludeBytes
        for (i in 0 until swept) {
            val tampered = container.copyOf()
            tampered[i] = (tampered[i].toInt() xor 0x01).toByte()
            assertSingleFailure("byte $i flipped") { openWithSeed(tampered, seed) }
        }
    }

    @Test
    fun everySaltByteIsLoadBearing() {
        // Code mode's key block is the salt. A flipped salt byte derives another
        // key; the label-and-block AAD would refuse it even if it did not.
        val container = sealWithCode(content(1_000))
        for (i in 0 until codeBlock) {
            val tampered = container.copyOf()
            tampered[i] = (tampered[i].toInt() xor 0x01).toByte()
            assertSingleFailure("salt byte $i flipped") { openWithCode(tampered) }
        }
    }

    @Test
    fun aKeyBlockIsNotReadUnderTheOtherMode() {
        // The spec's case: a contact container's first 16 bytes taken as a salt.
        val contact = sealToSeed(seed(), content(1_000))
        assertSingleFailure("a contact container opened as a code share") { openWithCode(contact) }

        // And the label itself: the right key and the right block, sealed under
        // the code label, must not open as a contact share — while the same thing
        // under the contact label does, so the label is what refused it.
        val seed = seed()
        val control = handSealedToSeed(seed, label = "share.contact")
        openWithSeed(control, seed)
        val crossed = handSealedToSeed(seed, label = "share.code")
        assertSingleFailure("a container under the other mode's label") { openWithSeed(crossed, seed) }
    }

    @Test
    fun anUnknownPolicyModeIsTheSingleFailure() {
        val seed = seed()
        assertSingleFailure("policy mode 3") { openWithSeed(handSealedToSeed(seed, policyMode = 3), seed) }
    }

    // ── Stream integrity ──────────────────────────────────────────────────────

    @Test
    fun truncationFailsMidChunkAndAtChunkBoundary() {
        val seed = seed()
        val container = sealToSeed(seed, content(210_000))

        // Mid-chunk: the cut lands inside a tag.
        val midChunk = container.copyOf(container.size - 7)
        assertSingleFailure("a mid-chunk cut") { openWithSeed(midChunk, seed) }

        // Exact boundary: one whole chunk survives, which without the final
        // marker would read as a clean, shorter stream.
        val boundary = container.copyOf(contactBlock + prefixBytes + BlobStore.STRIDE)
        assertSingleFailure("a chunk-boundary cut") { openWithSeed(boundary, seed) }
    }

    @Test
    fun reorderedChunksFail() {
        val seed = seed()
        val container = sealToSeed(seed, content(210_000))
        val base = contactBlock + prefixBytes
        val stride = BlobStore.STRIDE
        val tampered = container.copyOf()
        // Swap chunks 0 and 1 wholesale; each is sealed under its index.
        System.arraycopy(container, base + stride, tampered, base, stride)
        System.arraycopy(container, base, tampered, base + stride, stride)
        assertSingleFailure("reordered chunks") { openWithSeed(tampered, seed) }
    }

    @Test
    fun appendedBytesFail() {
        // Garbage after the true final chunk makes the real end non-final,
        // so the marker no longer matches the nonce it was sealed under.
        val container = sealWithCode(content(50_000))
        assertSingleFailure("appended bytes") { openWithCode(container + content(64)) }
    }

    // ── Versions and recognition ──────────────────────────────────────────────

    @Test
    fun aNewerPreludeVersionIsRefusedAsNewerOnlyAfterItAuthenticates() {
        val seed = seed()
        val newer = handSealedToSeed(seed, version = 3)
        val out = ByteArrayOutputStream()
        try {
            ShareContainer.openWithSeed(ByteArrayInputStream(newer), out, seed)
            fail("a newer container version opened")
        } catch (expected: ShareContainer.NewerShareVersion) {
        }
        assertEquals("no content is written for a newer version", 0, out.size())

        // The same container for somebody else is not "newer" — it is nothing.
        assertSingleFailure("a newer container for another phone") { openWithSeed(newer, seed()) }

        // And version 1 is not read at all.
        assertSingleFailure("prelude version 1") { openWithSeed(handSealedToSeed(seed, version = 1), seed) }
    }

    @Test
    fun anArbitraryFileIsTheSingleFailureInBothModes() {
        for (bytes in listOf(ByteArray(0), ByteArray(3), content(500), content(5_000))) {
            assertSingleFailure("${bytes.size} arbitrary bytes as a code share") { openWithCode(bytes) }
            assertSingleFailure("${bytes.size} arbitrary bytes as a contact share") { openWithSeed(bytes, seed()) }
        }
    }

    @Test
    fun aCodeShareSealedUnderAnEarlierPublishedSetOpens() {
        // `crypto-primitives`: a retune must not strand a headerless file. Seal
        // under one set, then publish a newer one after it; the reader tries the
        // newest first, fails, and opens under the earlier one.
        val released = VaultKeys.RELEASED_KDF_PARAMS
        val current = released.last()
        val earlier = current.copy(iterations = current.iterations + 1)
        try {
            VaultKeys.RELEASED_KDF_PARAMS = listOf(earlier)
            val plain = content(2_000)
            val container = sealWithCode(plain)
            VaultKeys.RELEASED_KDF_PARAMS = listOf(earlier, current)
            assertArrayEquals(plain, openWithCode(container).second)
        } finally {
            VaultKeys.RELEASED_KDF_PARAMS = released
        }
    }

    @Test
    fun nothingInTheClearSaysWhatTheFileIs() {
        // No magic, no version, no mode, no policy, no expiry, no name, no MIME
        // and no content anywhere in the sealed bytes, in either mode.
        val expiry = 1_900_000_000_123L
        val marker = "THE-CONTENT-MARKER-0123456789".toByteArray()
        val distinctive = ShareContainer.Prelude(
            ShareContainer.Kind.NOTE, "extremely-distinctive-name", "text/x-distinctive"
        )
        val expiryBytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(expiry).array()
        val codeShare = ByteArrayOutputStream().also {
            ShareContainer.sealWithCode(
                ByteArrayInputStream(marker), it, distinctive, ShareContainer.Policy.Expiry(expiry), code,
            )
        }.toByteArray()
        val contact = ByteArrayOutputStream().also {
            ShareContainer.sealToRecipient(
                ByteArrayInputStream(marker), it, distinctive, ShareContainer.Policy.Expiry(expiry),
                XWing.keypairFromSeed(seed()).publicKey,
            )
        }.toByteArray()
        for (bytes in listOf(codeShare, contact)) {
            for (needle in listOf(
                "VSHARE".toByteArray(),
                "share.".toByteArray(),
                "extremely-distinctive-name".toByteArray(),
                "text/x-distinctive".toByteArray(),
                expiryBytes,
                marker,
            )) {
                assertEquals("«${String(needle, Charsets.ISO_8859_1)}» is in the clear", -1, indexOf(bytes, needle))
            }
        }
    }

    // ── The probe ─────────────────────────────────────────────────────────────

    private fun probe(bytes: ByteArray, seed: ByteArray) =
        ShareContainer.probeContact(ByteArrayInputStream(bytes), seed)

    @Test
    fun theProbeNamesOnlyWhatChunkZeroProves() {
        val seed = seed()
        assertEquals(
            "a one-chunk share sealed here",
            ShareContainer.Probe.SEALED_HERE, probe(sealToSeed(seed, content(1_000)), seed),
        )
        assertEquals(
            "a multi-chunk share sealed here — chunk 0 is full and not final",
            ShareContainer.Probe.SEALED_HERE, probe(sealToSeed(seed, content(150_000)), seed),
        )
        assertEquals(
            "a share whose chunk 0 is full and final",
            ShareContainer.Probe.SEALED_HERE,
            probe(sealToSeed(seed, content(BlobStore.CHUNK - preludeBytes)), seed),
        )
        assertEquals(
            "a newer share sealed here",
            ShareContainer.Probe.NEWER_VERSION, probe(handSealedToSeed(seed, version = 3), seed),
        )
        assertEquals(
            "a share sealed to another phone",
            ShareContainer.Probe.NOT_HERE, probe(sealToSeed(seed(), content(1_000)), seed),
        )
        assertEquals(
            "a code share",
            ShareContainer.Probe.NOT_HERE, probe(sealWithCode(content(1_000)), seed),
        )
        assertEquals(
            "an arbitrary file",
            ShareContainer.Probe.NOT_HERE, probe(content(5_000), seed),
        )
        val shortest = contactBlock + prefixBytes + 31 + 16
        assertEquals(
            "a file too short to hold the smallest share",
            ShareContainer.Probe.NOT_HERE, probe(ByteArray(shortest - 1), seed),
        )
    }

    @Test
    fun theProbeReadsNoFurtherThanChunkZero() {
        val seed = seed()
        val container = sealToSeed(seed, content(210_000))
        var read = 0
        val counting = object : java.io.InputStream() {
            val inner = ByteArrayInputStream(container)
            override fun read(): Int = inner.read().also { if (it >= 0) read++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                inner.read(b, off, len).also { if (it > 0) read += it }
        }
        assertEquals(ShareContainer.Probe.SEALED_HERE, ShareContainer.probeContact(counting, seed))
        assertEquals(contactBlock + prefixBytes + BlobStore.STRIDE, read)
    }

    // ── Golden vectors ────────────────────────────────────────────────────────
    //
    // These open the COMMITTED bytes, not a fresh round trip — the property
    // under test is that this build still reads what a past build wrote, which
    // is the property the two ends of a share live on. Fixtures are add-only.

    /** Committed vectors live in the test APK, so the instrumentation context. */
    private fun vector(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("${ShareVectorFixture.DIR}/$name").use { it.readBytes() }

    @Test
    fun committedCodeVectorOpens() {
        val out = ByteArrayOutputStream()
        val opened = ShareContainer.openWithCode(
            ByteArrayInputStream(vector("code.bin")), out, ShareVectorFixture.CODE
        )
        assertArrayEquals(ShareVectorFixture.content(), out.toByteArray())
        assertEquals(ShareContainer.Kind.NOTE, opened.kind)
        assertEquals("vector.txt", opened.displayName)
        assertEquals("text/plain", opened.mime)
        assertEquals(
            ShareVectorFixture.EXPIRY_MILLIS,
            (opened.policy as ShareContainer.Policy.Expiry).atEpochMillis,
        )
    }

    @Test
    fun committedContactVectorOpens() {
        val out = ByteArrayOutputStream()
        val opened = ShareContainer.openWithSeed(
            ByteArrayInputStream(vector("contact.bin")), out, ShareVectorFixture.SEED
        )
        assertArrayEquals(ShareVectorFixture.content(), out.toByteArray())
        assertEquals(ShareContainer.Kind.IMAGE, opened.kind)
        assertEquals("vector.jpg", opened.displayName)
        assertEquals("image/jpeg", opened.mime)
        assertTrue(opened.policy is ShareContainer.Policy.SingleView)
    }

    @Test
    fun theVersionOneFixturesAreGone() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assertEquals(
            "fixtures/share/ holds v2 only",
            listOf("v2"),
            assets.list("fixtures/share")!!.toList(),
        )
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
