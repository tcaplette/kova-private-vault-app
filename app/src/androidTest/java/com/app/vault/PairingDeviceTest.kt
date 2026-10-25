package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.Random

/**
 * The mutual scan, end to end — P4-4.
 *
 * **What "two installs" means here, stated rather than implied.** Two identities
 * exist: this install's, in its real `identity.bin` under the real Keystore key,
 * and a second one generated in-process standing in for the other phone. Each
 * one's code is *rendered by the shipping generator*, converted to a luminance
 * frame in the shape `VaultCameraSession` hands over, and read back through the
 * *shipping decoder* — the same call the camera callback makes, stride and all.
 *
 * What is not covered, and is not claimed: no light passes through a lens in this
 * test. That is a physical scan across a table, and the OUTLINE result says
 * whether it was done and on what hardware.
 *
 * **Announced test-only state**: the real `identity.bin` and the real
 * `vault.identity` alias. Both are removed in `tearDown`, including on failure.
 */
@RunWith(AndroidJUnit4::class)
class PairingDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun file() = File(ctx.filesDir, "identity.bin")

    private fun clear() {
        Identity.deleteKeyForTest()
        file().delete()
        File(ctx.filesDir, "identity.bin.tmp").delete()
    }

    @Before fun setUp() = clear()

    @After fun tearDown() = clear()

    /** The other phone. A real X-Wing identity: its public key and its seed, held only by this test. */
    private fun otherInstall(): Pair<ByteArray, ByteArray> {
        val seed = ByteArray(XWing.SEED_BYTES).also { SecureRandom().nextBytes(it) }
        return XWing.keypairFromSeed(seed).publicKey to seed
    }

    /**
     * A code, rendered and handed over the way a frame arrives: 8-bit luminance,
     * dark modules at 0, light quiet zone at 255, and a row stride wider than the
     * image, because a camera plane is padded and a decoder given the wrong row
     * length reads nothing at all.
     */
    private fun scan(publicKey: ByteArray): ByteArray? {
        val modules = PairingQr.matrix(Identity.PairingCode.encode(publicKey))
        val scale = 5
        val quiet = 4
        val width = (modules.size + quiet * 2) * scale
        val stride = width + 23
        val luma = ByteArray(stride * width) { 0xFF.toByte() }
        for (y in modules.indices) {
            for (x in modules.indices) {
                if (!modules[y][x]) continue
                for (dy in 0 until scale) {
                    val row = ((y + quiet) * scale + dy) * stride
                    for (dx in 0 until scale) luma[row + (x + quiet) * scale + dx] = 0
                }
            }
        }
        return PairingQr.decode(luma, width, width, stride)
    }

    /** The whole scan path, exactly as `MainActivity.pairingDecoded` runs it. */
    private fun decodeAndInspect(publicKey: ByteArray): Pair<ByteArray?, Identity.AddOutcome?> {
        val payload = scan(publicKey)
        assertNotNull("the rendered code must decode", payload)
        val parsed = Identity.PairingCode.parse(payload!!)
        assertTrue("a rendered code must parse as a key", parsed is Identity.PairingCode.Parsed.Key)
        val key = (parsed as Identity.PairingCode.Parsed.Key).publicKey
        return key to Identity.inspect(ctx, key)
    }

    @Test
    fun twoInstallsPairInBothDirections() {
        val mine = (Identity.state(ctx) as Identity.State.Ready).publicKey
        val (theirs, theirSecret) = otherInstall()

        // This phone scans theirs, and names them.
        val (scanned, outcome) = decodeAndInspect(theirs)
        assertEquals(Identity.AddOutcome.Added, outcome)
        assertArrayEquals("the key that came off the image is theirs", theirs, scanned)
        assertEquals(Identity.AddOutcome.Added, Identity.add(ctx, scanned!!, "Bea"))

        val stored = (Identity.state(ctx) as Identity.State.Ready).contacts.single()
        assertEquals("Bea", stored.name)
        assertArrayEquals(theirs, stored.publicKey)

        // The other direction: their phone scans mine. It has no store of its own
        // here, so what is asserted is the half this install is responsible for —
        // that the code it shows decodes to exactly the key it holds.
        val backPayload = scan(mine)
        val backParsed = Identity.PairingCode.parse(backPayload!!)
        assertArrayEquals(
            "the code this phone shows must be the key it pairs with",
            mine,
            (backParsed as Identity.PairingCode.Parsed.Key).publicKey,
        )

        // And the names are independent: theirs for me is whatever they typed, and
        // nothing in my payload proposed one.
        assertTrue("no name travels in a payload", backPayload.size == 6 + Identity.PUBLIC_KEY_BYTES)
        theirSecret.fill(0)
    }

    @Test
    fun aContainerSealedToAStoredContactOpensWithTheirSeedAndNoOther() {
        Identity.state(ctx)
        val (theirs, theirSecret) = otherInstall()
        val (_, thirdSecret) = otherInstall()
        val (scanned, _) = decodeAndInspect(theirs)
        Identity.add(ctx, scanned!!, "Bea")

        val stored = (Identity.state(ctx) as Identity.State.Ready).contacts.single().publicKey
        val plaintext = ByteArray(70_000).also { Random(42).nextBytes(it) }
        val container = ByteArrayOutputStream().also { out ->
            ShareContainer.sealToRecipient(
                ByteArrayInputStream(plaintext),
                out,
                ShareContainer.Prelude(
                    kind = ShareContainer.Kind.IMAGE,
                    displayName = "for bea.jpg",
                    mime = "image/jpeg",
                ),
                ShareContainer.Policy.Unmanaged,
                stored,
            )
        }.toByteArray()

        // The recipient opens it.
        val opened = ByteArrayOutputStream()
        val result = ShareContainer.openWithSeed(ByteArrayInputStream(container), opened, theirSecret)
        assertEquals("for bea.jpg", result.displayName)
        assertArrayEquals(plaintext, opened.toByteArray())

        // A third install does not, and fails the way everything else fails.
        try {
            ShareContainer.openWithSeed(ByteArrayInputStream(container), ByteArrayOutputStream(), thirdSecret)
            throw AssertionError("a container sealed to Bea must not open for anyone else")
        } catch (_: ShareContainer.ShareFailure) {
        }

        theirSecret.fill(0)
        thirdSecret.fill(0)
    }

    @Test
    fun thisPhonesOwnCodeIsRefusedRatherThanStored() {
        val mine = (Identity.state(ctx) as Identity.State.Ready).publicKey
        val (_, outcome) = decodeAndInspect(mine)
        assertEquals(Identity.AddOutcome.OwnCode, outcome)
        assertEquals(
            "nothing is stored",
            emptyList<Identity.Contact>(),
            (Identity.state(ctx) as Identity.State.Ready).contacts,
        )
    }

    @Test
    fun aCodeAlreadyHeldNamesTheContactRatherThanAddingASecond() {
        Identity.state(ctx)
        val (theirs, theirSecret) = otherInstall()
        val (scanned, _) = decodeAndInspect(theirs)
        Identity.add(ctx, scanned!!, "Bea")

        val (_, second) = decodeAndInspect(theirs)
        assertEquals(Identity.AddOutcome.AlreadyKnown("Bea"), second)
        assertEquals(1, (Identity.state(ctx) as Identity.State.Ready).contacts.size)
        theirSecret.fill(0)
    }

    @Test
    fun aCodeFromAnotherApplicationIsRefusedAndStoresNothing() {
        Identity.state(ctx)
        // Rendered by the same generator, carrying what any other QR code carries.
        val modules = PairingQr.matrix("https://example.com/not-a-pairing-code".toByteArray(Charsets.ISO_8859_1))
        val scale = 5
        val quiet = 4
        val width = (modules.size + quiet * 2) * scale
        val luma = ByteArray(width * width) { 0xFF.toByte() }
        for (y in modules.indices) {
            for (x in modules.indices) {
                if (!modules[y][x]) continue
                for (dy in 0 until scale) {
                    val row = ((y + quiet) * scale + dy) * width
                    for (dx in 0 until scale) luma[row + (x + quiet) * scale + dx] = 0
                }
            }
        }
        val payload = PairingQr.decode(luma, width, width)
        assertArrayEquals("https://example.com/not-a-pairing-code".toByteArray(Charsets.ISO_8859_1), payload)
        assertEquals(
            Identity.PairingCode.Parsed.NotAPairingCode,
            Identity.PairingCode.parse(payload!!),
        )
        assertEquals(
            emptyList<Identity.Contact>(),
            (Identity.state(ctx) as Identity.State.Ready).contacts,
        )
    }
}
