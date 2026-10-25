package com.app.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing identity's arithmetic — P4-4, and `share-format-v2`'s binary payload.
 *
 * Everything here runs on the JVM because everything here is pure: the payload
 * codec, the store's serialisation, the add rules, and — the part worth having —
 * the **generate-then-decode loop over real image bytes**. `zxing:core` is a plain
 * Java JAR, so a QR code can be rendered to a luminance frame in the shape the
 * camera hands over and read back through the same decoder the camera callback
 * uses, with no emulator anywhere.
 *
 * What that loop does not prove is the one thing no test on this machine can:
 * that light through a lens reaches the decoder. `PairingDeviceTest` carries the
 * rest, and the OUTLINE result says plainly where the boundary is.
 */
class IdentityTest {

    private fun key(fill: Byte): ByteArray = ByteArray(Identity.PUBLIC_KEY_BYTES) { fill }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    // ── the payload ───────────────────────────────────────────────────────────

    @Test
    fun aPayloadRoundTrips() {
        val public = ByteArray(Identity.PUBLIC_KEY_BYTES) { it.toByte() }
        val payload = Identity.PairingCode.encode(public)
        assertArrayEquals("payload must announce itself", ascii("VPAIR2"), payload.copyOfRange(0, 6))
        // 5 prefix + 1 version digit + 1,216 raw key bytes, and nothing else.
        assertEquals(6 + 1216, payload.size)
        val parsed = Identity.PairingCode.parse(payload)
        assertTrue(parsed is Identity.PairingCode.Parsed.Key)
        assertArrayEquals(public, (parsed as Identity.PairingCode.Parsed.Key).publicKey)
    }

    @Test
    fun thePayloadCarriesNothingButTheKey() {
        // The whole payload is reconstructible from the key alone. If a name, a
        // device id or a timestamp is ever added, this fails — which is the point.
        val public = ByteArray(Identity.PUBLIC_KEY_BYTES) { (it * 7).toByte() }
        assertArrayEquals(ascii("VPAIR2") + public, Identity.PairingCode.encode(public))
    }

    @Test
    fun anythingElseIsNotAPairingCode() {
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
        listOf(
            ByteArray(0),
            ascii("https://example.com"),
            ascii("VPAIR"),
            ascii("VPAIR2"),
            ascii("VPAIRx") + ByteArray(1216),
            // A version-1 code — text around a 32-byte X25519 key — is not one any more.
            ascii("VPAIR1:" + b64.encodeToString(ByteArray(32))),
            ascii("VPAIR1") + ByteArray(1216),
            // A version *below* this build's is not a code this build reads.
            ascii("VPAIR0") + ByteArray(1216),
            // A key of the wrong length is not a key.
            ascii("VPAIR2") + ByteArray(1215),
            ascii("VPAIR2") + ByteArray(1217),
            ascii("VPAIR2") + ByteArray(32),
        ).forEach {
            assertEquals(
                "«${String(it, Charsets.ISO_8859_1).take(24)}» (${it.size} B) must be refused",
                Identity.PairingCode.Parsed.NotAPairingCode,
                Identity.PairingCode.parse(it),
            )
        }
    }

    @Test
    fun aNewerVersionIsItsOwnRefusal() {
        val newer = ascii("VPAIR3") + ByteArray(1216)
        assertEquals(Identity.PairingCode.Parsed.NewerApp, Identity.PairingCode.parse(newer))
        // Distinguishable from the other one — DESIGN.md §7.21's table depends on it.
        assertTrue(
            Identity.PairingCode.parse(newer) != Identity.PairingCode.Parsed.NotAPairingCode,
        )
    }

    @Test
    fun theNewerRefusalDoesNotNeedTheRestToParse() {
        // A build two versions ahead may lay out the rest any way it likes. The
        // version is read first, so the user still gets the message that names the
        // reason rather than a generic refusal.
        assertEquals(
            Identity.PairingCode.Parsed.NewerApp,
            Identity.PairingCode.parse(ascii("VPAIR9whatever-a-future-build-writes")),
        )
    }

    // ── the store's bytes ─────────────────────────────────────────────────────

    @Test
    fun theStoreRoundTripsAtBothNameExtremes() {
        val seed = ByteArray(32) { 9 }
        val short = Identity.Contact("A", key(1))
        val long = Identity.Contact("x".repeat(Identity.MAX_NAME_BYTES), key(2))
        val decoded = Identity.decode(Identity.encode(Identity.Snapshot(seed, listOf(short, long))))
        assertNotNull(decoded)
        assertArrayEquals(seed, decoded!!.seed)
        assertEquals(listOf(short, long), decoded.contacts)
    }

    @Test
    fun aMultiByteNameIsMeasuredInBytesNotCharacters() {
        // Sixteen four-byte emoji is 64 bytes and 16 characters. The cap is bytes,
        // because the length prefix is one byte and the file is what has to fit.
        val name = "🔑".repeat(16)
        assertEquals(Identity.MAX_NAME_BYTES, name.toByteArray(Charsets.UTF_8).size)
        val decoded = Identity.decode(
            Identity.encode(Identity.Snapshot(ByteArray(32), listOf(Identity.Contact(name, key(3))))),
        )
        assertEquals(name, decoded!!.contacts.single().name)
    }

    @Test
    fun aTruncatedOrPaddedStoreIsRefused() {
        val bytes = Identity.encode(
            Identity.Snapshot(ByteArray(32) { 4 }, listOf(Identity.Contact("Mum", key(5)))),
        )
        assertNull("a truncated store must not parse", Identity.decode(bytes.copyOf(bytes.size - 3)))
        assertNull("trailing bytes must not parse", Identity.decode(bytes + byteArrayOf(0)))
        assertNull("a store shorter than a seed must not parse", Identity.decode(ByteArray(8)))
    }

    @Test
    fun anEmptyStoreIsAValidStore() {
        val decoded = Identity.decode(Identity.encode(Identity.Snapshot(ByteArray(32), emptyList())))
        assertEquals(emptyList<Identity.Contact>(), decoded!!.contacts)
    }

    // ── the add rules ─────────────────────────────────────────────────────────

    @Test
    fun ownKeyIsRefused() {
        val own = key(1)
        val (outcome, contacts) = Identity.add(own, emptyList(), own, "Me")
        assertEquals(Identity.AddOutcome.OwnCode, outcome)
        assertEquals(emptyList<Identity.Contact>(), contacts)
    }

    @Test
    fun aKeyAlreadyHeldNamesTheContactItIsHeldAs() {
        val existing = Identity.Contact("Mum", key(2))
        val (outcome, contacts) = Identity.add(key(1), listOf(existing), key(2), "Mother")
        assertEquals(Identity.AddOutcome.AlreadyKnown("Mum"), outcome)
        assertEquals(listOf(existing), contacts)
    }

    @Test
    fun aNameOutsideTheCapIsRefusedAndStoresNothing() {
        listOf("", "   ", "x".repeat(Identity.MAX_NAME_BYTES + 1)).forEach { name ->
            val (outcome, contacts) = Identity.add(key(1), emptyList(), key(2), name)
            assertEquals("«$name» must be refused", Identity.AddOutcome.BadName, outcome)
            assertEquals(emptyList<Identity.Contact>(), contacts)
        }
    }

    @Test
    fun anAcceptedContactIsStoredTrimmedAndSorted() {
        val (outcome, contacts) = Identity.add(
            key(1),
            listOf(Identity.Contact("Zoe", key(2))),
            key(3),
            "  Ana  ",
        )
        assertEquals(Identity.AddOutcome.Added, outcome)
        assertEquals(listOf("Ana", "Zoe"), contacts.map { it.name })
    }

    @Test
    fun theListIsAlphabeticalWithoutCaseDecidingIt() {
        val sorted = Identity.sorted(
            listOf(
                Identity.Contact("zoe", key(1)),
                Identity.Contact("Ana", key(2)),
                Identity.Contact("bea", key(3)),
            ),
        )
        assertEquals(listOf("Ana", "bea", "zoe"), sorted.map { it.name })
    }

    @Test
    fun aScanIsJudgedBeforeAnyNameIsTyped() {
        val own = key(1)
        val known = Identity.Contact("Mum", key(2))
        assertEquals(Identity.AddOutcome.OwnCode, Identity.scanned(own, listOf(known), own))
        assertEquals(
            Identity.AddOutcome.AlreadyKnown("Mum"),
            Identity.scanned(own, listOf(known), key(2)),
        )
        assertEquals(Identity.AddOutcome.Added, Identity.scanned(own, listOf(known), key(3)))
    }

    // ── the code, as an image ─────────────────────────────────────────────────

    /**
     * A rendered code, in the shape a camera frame arrives in: 8-bit luminance,
     * dark modules at 0, a light quiet zone at 255, and a **row stride wider than
     * the image**, because that is what a real plane is and getting it wrong reads
     * every row at an offset.
     */
    private fun luminance(
        modules: Array<BooleanArray>,
        scale: Int = 4,
        quiet: Int = 4,
        extraStride: Int = 13,
    ): Triple<ByteArray, Int, Int> {
        val count = modules.size
        val width = (count + quiet * 2) * scale
        val height = width
        val stride = width + extraStride
        val luma = ByteArray(stride * height) { 0xFF.toByte() }
        for (y in 0 until count) {
            for (x in 0 until count) {
                if (!modules[y][x]) continue
                for (dy in 0 until scale) {
                    val row = ((y + quiet) * scale + dy) * stride
                    for (dx in 0 until scale) {
                        luma[row + (x + quiet) * scale + dx] = 0
                    }
                }
            }
        }
        return Triple(luma, width, height)
    }

    @Test
    fun aRenderedCodeIsReadBackByTheDecoderTheCameraUses() {
        val public = ByteArray(Identity.PUBLIC_KEY_BYTES) { (it * 11 + 3).toByte() }
        val payload = Identity.PairingCode.encode(public)
        val (luma, width, height) = luminance(PairingQr.matrix(payload))
        val read = PairingQr.decode(luma, width, height, rowStride = width + 13)
        // Byte for byte: every value 0x00–0xFF survives, which a text round trip
        // through a guessed character set would not.
        assertArrayEquals(payload, read)
        val parsed = Identity.PairingCode.parse(read!!)
        assertArrayEquals(public, (parsed as Identity.PairingCode.Parsed.Key).publicKey)
    }

    /**
     * The camera's view of a code on another phone: the grid drawn large, then
     * area-averaged down into a 640×480 frame with the code spanning half its width
     * — about 2.4 frame pixels a module, and every module edge blurred into grey.
     */
    @Test
    fun aCodeDownscaledToACameraFrameStillReadsExactly() {
        val public = ByteArray(Identity.PUBLIC_KEY_BYTES) { (it * 37 + 5).toByte() }
        val payload = Identity.PairingCode.encode(public)
        val modules = PairingQr.matrix(payload)
        val count = modules.size
        val frameW = 640
        val frameH = 480
        val codeSide = frameW / 2
        val left = (frameW - codeSide) / 2
        val top = (frameH - codeSide) / 2
        val luma = ByteArray(frameW * frameH) { 0xFF.toByte() }
        // Area sampling: each frame pixel is the dark fraction of the module grid
        // it covers, sampled on an 8×8 sub-grid.
        val sub = 8
        for (py in 0 until codeSide) {
            for (px in 0 until codeSide) {
                var dark = 0
                for (sy in 0 until sub) {
                    for (sx in 0 until sub) {
                        val mx = ((px + (sx + 0.5) / sub) * count / codeSide).toInt()
                        val my = ((py + (sy + 0.5) / sub) * count / codeSide).toInt()
                        if (modules[my][mx]) dark++
                    }
                }
                luma[(top + py) * frameW + left + px] = (255 - 255 * dark / (sub * sub)).toByte()
            }
        }
        val read = PairingQr.decode(luma, frameW, frameH)
        assertArrayEquals(payload, read)
    }

    @Test
    fun aTextCodeIsRefusedAsNotAPairingCode() {
        // A URL is byte mode; its bytes are not a payload. An all-alphanumeric code
        // has no byte segment at all and decodes to an empty payload — a code, not a
        // pairing code, which is the sentence the person scanning it is owed.
        listOf("https://example.com/not-a-pairing-code", "HELLO 123").forEach { text ->
            val (luma, width, height) = luminance(PairingQr.matrix(text.toByteArray(Charsets.ISO_8859_1)))
            val read = PairingQr.decode(luma, width, height, rowStride = width + 13)
            assertNotNull("«$text» must decode as a code", read)
            assertEquals(Identity.PairingCode.Parsed.NotAPairingCode, Identity.PairingCode.parse(read!!))
        }
    }

    @Test
    fun aFrameOfNothingDecodesToNothingRatherThanFailing() {
        // Most frames are this one. It must be null, not an exception: the callback
        // runs on the camera thread and a throw there takes the preview with it.
        assertNull(PairingQr.decode(ByteArray(640 * 480) { 0x7F }, 640, 480))
    }

    @Test
    fun aMalformedFrameIsRefusedRatherThanIndexedOutOfBounds() {
        assertNull(PairingQr.decode(ByteArray(10), 640, 480))
        assertNull(PairingQr.decode(ByteArray(0), 0, 0))
        assertNull(PairingQr.decode(ByteArray(640 * 480), 640, 480, rowStride = 320))
    }

    @Test
    fun theCodeIsVersion29AtLevelM() {
        // Measured in `share-format-v2` D8: raw binary is version 29 (133 modules),
        // where Base64 of the same key would be version 34 (153). A change that
        // makes the code denser than this is a change to the scan, and fails here.
        val modules = PairingQr.matrix(Identity.PairingCode.encode(ByteArray(Identity.PUBLIC_KEY_BYTES)))
        assertEquals(133, modules.size)
        assertEquals("the code must be square", modules.size, modules[0].size)
    }
}
