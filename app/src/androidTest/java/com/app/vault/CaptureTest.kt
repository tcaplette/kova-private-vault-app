package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * P2-4's non-camera half — the seal, the store, and the per-mode FIFO cap.
 *
 * Instrumented rather than JVM for the reason `VaultKeysTest` is: `crypto_box_seal`
 * is libsodium, which needs the native `.so`, and the store and prefs need a real
 * `Context`. The camera path is exercised separately on a device with a front
 * camera; nothing here opens one.
 */
@RunWith(AndroidJUnit4::class)
class CaptureTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: BlobStore
    private val rng = SecureRandom()

    @Before
    fun setUp() {
        Capture.resetForTest(ctx)
        root = File(ctx.filesDir, "capture-${System.nanoTime()}").apply { mkdirs() }
        store = BlobStore(File(root, "blobs"))
    }

    @After
    fun tearDown() {
        Capture.resetForTest(ctx)
        root.deleteRecursively()
    }

    private fun keypair() = VaultKeys.captureKeypair(VaultKeys.newVmk())

    // --- 2.4 seal / open round trip and foreign key ----------------------------

    @Test fun sealOpenRoundTrips() {
        val kp = keypair()
        val message = ByteArray(4096).also { rng.nextBytes(it) }
        val sealed = Capture.seal(kp.publicKey, message)
        assertArrayEquals(message, Capture.open(kp.secretKey, kp.publicKey, sealed))
    }

    /**
     * `cover-fingerprint-dots` D6a. The stage record rides inside the seal: what is opened is
     * the record and the media, byte for byte, and the record reads back as it was written.
     */
    @Test fun theStageRecordRoundTripsThroughSealAndOpen() {
        val kp = keypair()
        val media = ByteArray(4096).also { rng.nextBytes(it) }
        val stage = CaptureRecord.Stage(
            CaptureRecord.STAGE_COVER_PATTERN,
            CaptureRecord.FLAG_DOTS_SHOWN or CaptureRecord.FLAG_LIMITED,
            CaptureRecord.COVER_RECORDER,
        )
        val takenAt = 1_700_000_123_456L
        val sealed = Capture.seal(kp.publicKey, CaptureRecord.prepend(stage, takenAt, media))
        assertEquals(CaptureRecord.BYTES + media.size + com.goterl.lazysodium.interfaces.Box.SEALBYTES, sealed.size)

        val opened = Capture.open(kp.secretKey, kp.publicKey, sealed)!!
        val record = CaptureRecord.read(opened)!!
        assertEquals(CaptureRecord.STAGE_COVER_PATTERN, record.stage.stage)
        assertEquals(CaptureRecord.FLAG_DOTS_SHOWN or CaptureRecord.FLAG_LIMITED, record.stage.flags)
        assertEquals(CaptureRecord.COVER_RECORDER, record.stage.cover)
        assertEquals(takenAt, record.takenAt)
        assertArrayEquals(media, opened.copyOfRange(CaptureRecord.BYTES, opened.size))
    }

    @Test fun aForeignKeyOpensNothing() {
        val a = keypair()
        val b = keypair()
        val sealed = Capture.seal(a.publicKey, "front camera frame".toByteArray())
        assertNull(Capture.open(b.secretKey, b.publicKey, sealed))
    }

    // --- 2.5 the public key alone is not enough --------------------------------

    @Test fun theDestinationPublicKeyCannotOpenACapture() {
        // A rooted read of prefs yields the destination public key. It seals; it
        // does not open. The secret half only ever exists inside a VMK derivation.
        val kp = keypair()
        Capture.setDestination(ctx, kp.publicKey)
        val stored = Capture.destinationKey(ctx)!!
        val sealed = Capture.seal(stored, "who is holding the phone".toByteArray())

        // There is no API that opens with a public key, and passing it where the
        // secret key goes fails to authenticate rather than leaking the plaintext.
        assertNull(Capture.open(stored, stored, sealed))
        assertArrayEquals(
            "who is holding the phone".toByteArray(),
            Capture.open(kp.secretKey, kp.publicKey, sealed),
        )
    }

    // --- 2.4 FIFO cap, per mode ------------------------------------------------

    private fun sealACapture(mode: Capture.Mode, pub: ByteArray, size: Int) {
        val plain = ByteArray(size).also { rng.nextBytes(it) }
        val sealed = Capture.seal(pub, plain)
        val blobId = Capture.newBlobId()
        store.writeSealed(blobId, sealed)
        Capture.add(ctx, mode, blobId, sealed.size, store)
    }

    @Test fun theCapEvictsTheOldestAtFiftyOne() {
        val kp = keypair()
        repeat(Capture.PER_MODE_CAP + 1) { sealACapture(Capture.Mode.PHOTO, kp.publicKey, 1024) }

        val pending = Capture.pending(ctx, Capture.Mode.PHOTO)
        assertEquals(Capture.PER_MODE_CAP, pending.size)

        // The evicted (oldest) blob is gone from the store; the survivors are all present.
        pending.forEach { assertTrue("survivor blob missing", store.fileOf(it.blobId).exists()) }
    }

    @Test fun theCapIsPerMode() {
        val kp = keypair()
        repeat(Capture.PER_MODE_CAP) { sealACapture(Capture.Mode.PHOTO, kp.publicKey, 1024) }
        sealACapture(Capture.Mode.AUDIO, kp.publicKey, 1024)

        assertEquals(Capture.PER_MODE_CAP, Capture.pending(ctx, Capture.Mode.PHOTO).size)
        assertEquals(1, Capture.pending(ctx, Capture.Mode.AUDIO).size)
    }

    // --- the store round trip: a sealed capture survives padding ---------------

    @Test fun aCaptureSurvivesTheBucketPaddedBlob() {
        val kp = keypair()
        val message = ByteArray(20_000).also { rng.nextBytes(it) }
        val sealed = Capture.seal(kp.publicKey, message)
        val blobId = Capture.newBlobId()
        store.writeSealed(blobId, sealed)

        // The blob is bucket-padded — bigger than the sealed bytes, revealing nothing.
        assertTrue(store.fileOf(blobId).length() >= sealed.size.toLong())
        assertTrue(BlobStore.SMALL_BUCKETS.contains(store.fileOf(blobId).length()))

        val readBack = store.readSealed(blobId, sealed.size)
        assertArrayEquals(message, Capture.open(kp.secretKey, kp.publicKey, readBack))
    }

    // --- defaults --------------------------------------------------------------

    @Test fun everyModeIsOffOnAFreshInstall() {
        Capture.Mode.entries.forEach { assertFalse(Capture.enabled(ctx, it)) }
        assertFalse(Capture.anyEnabled(ctx))
        assertNull(Capture.destinationKey(ctx))
    }
}
