package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Face-down auto-lock on a device — P2-20.
 *
 * `FaceDownTest` owns the decision; this owns the two things only a device has:
 * the registration window (`OUTLINE.md`'s "the listener is unregistered while
 * locked") and the end-to-end path from a sample to a locked vault.
 *
 * Driven through [FaceDownLock.onSample] rather than through the sensor.
 * `SensorEvent` has no public constructor, and the alternative to a seam is
 * shaking an emulator, which is not a test. `AutoLock` is driven the same way in
 * `RuntimeHardeningTest` and for the same reason.
 *
 * **Restores the device preference it changes.** The face-down switch is one
 * boolean in `shared_prefs/grid`, shared with the app the developer is using; a
 * test that left it on would silently change how the app behaves afterwards.
 */
@RunWith(AndroidJUnit4::class)
class FaceDownDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var vault: VaultLifecycle
    private var restore = false

    private companion object {
        const val GOOD = "correct horse battery staple"
        const val STEP_MS = 60L
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    private var locks = 0

    private var restoreLockAfter = 0L

    /**
     * Stands in for `MainActivity.lockVault()`: the mechanism, plus the counter that
     * proves the trigger went through the caller's lock rather than reaching past
     * it. The activity's version also clears the screen state; that half is UI and
     * is not what this file is asserting.
     */
    private fun lockLikeTheButton() {
        locks++
        VaultLifecycle.lockNow()
    }

    @Before
    fun setUp() {
        restore = GridPreference.faceDown(ctx)
        restoreLockAfter = GridPreference.faceDownLockAfter(ctx)
        GridPreference.setFaceDownLockAfter(ctx, 0L)
        locks = 0
        // The keyring as well as the vault: either one open arms the trigger, and
        // `create` opens both.
        VaultKeyringSession.lock()
        VaultSession.lock()
        FaceDownLock.stop()
        root = File(ctx.filesDir, "facedown-${System.nanoTime()}")
        root.mkdirs()
        vault = VaultLifecycle(File(root, "vault.slots"), BlobStore(File(root, "blobs")))
    }

    @After
    fun tearDown() {
        FaceDownLock.stop()
        GridPreference.setFaceDown(ctx, restore)
        GridPreference.setFaceDownLockAfter(ctx, restoreLockAfter)
        VaultKeyringSession.lock()
        VaultSession.lock()
        ThumbnailCache.clear()
        root.deleteRecursively()
    }

    /**
     * Feeds a perfectly face-down, perfectly still stream for [durationMs], the way
     * a phone laid on a table reads.
     */
    private fun placeFaceDown(durationMs: Long) {
        val base = android.os.SystemClock.elapsedRealtime()
        var t = 0L
        while (t <= durationMs) {
            FaceDownLock.onSample(0f, 0f, -FaceDown.GRAVITY, base + t)
            t += STEP_MS
        }
    }

    /**
     * Face-down and still from [fromMs] to [toMs] past [base] — one continuous stream across
     * several calls, so a test can look between them without time running backwards.
     */
    private fun faceDownBetween(base: Long, fromMs: Long, toMs: Long) {
        var t = fromMs
        while (t <= toMs) {
            FaceDownLock.onSample(0f, 0f, -FaceDown.GRAVITY, base + t)
            t += STEP_MS
        }
    }

    // --- the registration window ---------------------------------------------

    @Test
    fun theTriggerIsOffByDefault() {
        // A fresh install has never written the key. Cleared explicitly rather than
        // assumed, because this suite shares one app with every other test.
        GridPreference.setFaceDown(ctx, false)
        vault.create(pass(GOOD))

        FaceDownLock.start(ctx, ::lockLikeTheButton)

        assertFalse("face-down must ship off — no listener without the switch", FaceDownLock.isListening)
    }

    @Test
    fun theSwitchPersists() {
        GridPreference.setFaceDown(ctx, true)
        assertTrue("the switch must survive being read back", GridPreference.faceDown(ctx))
        GridPreference.setFaceDown(ctx, false)
        assertFalse(GridPreference.faceDown(ctx))
    }

    @Test
    fun itListensOnlyWhileUnlockedAndEnabled() {
        GridPreference.setFaceDown(ctx, true)

        // Enabled but locked: nothing to lock, so nothing to listen for.
        FaceDownLock.start(ctx, ::lockLikeTheButton)
        assertFalse("a locked vault must not hold a sensor open", FaceDownLock.isListening)

        vault.create(pass(GOOD))
        FaceDownLock.start(ctx, ::lockLikeTheButton)
        assertTrue("unlocked and enabled must listen", FaceDownLock.isListening)
    }

    /** `OUTLINE.md`'s third Verify clause. */
    @Test
    fun theListenerIsUnregisteredWhileLocked() {
        GridPreference.setFaceDown(ctx, true)
        vault.create(pass(GOOD))
        FaceDownLock.start(ctx, ::lockLikeTheButton)
        assertTrue(FaceDownLock.isListening)

        VaultLifecycle.lockNow()
        FaceDownLock.start(ctx, ::lockLikeTheButton)

        assertFalse("a locked vault must leave no listener registered", FaceDownLock.isListening)
    }

    /**
     * `face-down-lock-on-keyring`. Every unlock lands here — the keyring open on the
     * vault list, no vault open — and the trigger used to be deaf to it.
     */
    @Test
    fun theVaultListListens() {
        vault.create(pass(GOOD))
        VaultKeyringSession.leaveVault()
        assertTrue(VaultKeyringSession.isOpen)
        assertFalse(VaultSession.isUnlocked)
        GridPreference.setFaceDown(ctx, true)

        FaceDownLock.start(ctx, ::lockLikeTheButton)

        assertTrue("the vault list shows vault names and must listen", FaceDownLock.isListening)
    }

    @Test
    fun turningTheSwitchOffUnregistersRatherThanReturningEarly() {
        GridPreference.setFaceDown(ctx, true)
        vault.create(pass(GOOD))
        FaceDownLock.start(ctx, ::lockLikeTheButton)
        assertTrue(FaceDownLock.isListening)

        GridPreference.setFaceDown(ctx, false)
        FaceDownLock.start(ctx, ::lockLikeTheButton)

        assertFalse("the conditions are checked before the idempotency guard", FaceDownLock.isListening)
    }

    @Test
    fun startIsIdempotent() {
        GridPreference.setFaceDown(ctx, true)
        vault.create(pass(GOOD))

        FaceDownLock.start(ctx, ::lockLikeTheButton)
        FaceDownLock.start(ctx, ::lockLikeTheButton)
        FaceDownLock.start(ctx, ::lockLikeTheButton)
        assertTrue(FaceDownLock.isListening)

        // One stop must be enough. If the third start had registered a third
        // listener, samples would keep arriving after this.
        FaceDownLock.stop()
        assertFalse(FaceDownLock.isListening)
    }

    // --- end to end -----------------------------------------------------------

    @Test
    fun aFaceDownDeviceLocksTheVaultThroughTheOrdinaryPath() {
        GridPreference.setFaceDown(ctx, true)
        vault.create(pass(GOOD))
        ThumbnailCache.get(ThumbRef(ByteArray(16) { 7 }, 0, 1)) { squarePng() }
        assertTrue("nothing cached to clear", ThumbnailCache.size() > 0)

        FaceDownLock.start(ctx, ::lockLikeTheButton)
        placeFaceDown(FaceDown.HOLD_MS + 200L)

        // The same three assertions the manual-lock path makes in
        // RuntimeHardeningTest: one mechanism, one end state.
        assertFalse("the session still holds a key", VaultSession.isUnlocked)
        assertEquals("decrypted pixels survived the lock", 0, ThumbnailCache.size())
        assertTrue("the log was not closed", TamperLog.digest.isEmpty())
        assertFalse("the listener outlived the lock it caused", FaceDownLock.isListening)
        assertEquals("the trigger must lock through the caller's lock, exactly once", 1, locks)
    }

    @Test
    fun faceDownOnTheVaultListLocksTheKeyring() {
        vault.create(pass(GOOD))
        VaultKeyringSession.leaveVault()
        GridPreference.setFaceDown(ctx, true)

        FaceDownLock.start(ctx, ::lockLikeTheButton)
        placeFaceDown(FaceDown.HOLD_MS + 200L)

        assertEquals("the trigger must lock through the caller's lock, exactly once", 1, locks)
        assertFalse("the keyring outlived a face-down lock on the list", VaultKeyringSession.isOpen)
        assertFalse("the listener outlived the lock it caused", FaceDownLock.isListening)
    }

    @Test
    fun theVaultOpensAgainAfterAFaceDownLock() {
        GridPreference.setFaceDown(ctx, true)
        vault.create(pass(GOOD))
        val slots = File(root, "vault.slots")
        val store = BlobStore(File(root, "blobs"))

        FaceDownLock.start(ctx, ::lockLikeTheButton)
        placeFaceDown(FaceDown.HOLD_MS + 200L)
        assertFalse(VaultSession.isUnlocked)

        assertTrue(
            "a face-down lock must leave the vault openable, as any other lock does",
            VaultLifecycle(slots, store).unlockAndOpen(pass(GOOD)),
        )
    }

    @Test
    fun aBriefFaceDownDoesNotLock() {
        GridPreference.setFaceDown(ctx, true)
        vault.create(pass(GOOD))

        FaceDownLock.start(ctx, ::lockLikeTheButton)
        placeFaceDown(FaceDown.HOLD_MS - 300L)

        assertTrue("a phone picked up before the hold must not lock", VaultSession.isUnlocked)
        assertTrue(FaceDownLock.isListening)
        assertEquals(0, locks)
    }

    // --- Lock after — `face-down-lock-settings` --------------------------------

    @Test
    fun lockAfterPersistsAndRefusesAnythingButAPreset() {
        GridPreference.setFaceDownLockAfter(ctx, 15_000L)
        assertEquals(15_000L, GridPreference.faceDownLockAfter(ctx))
        assertTrue(
            "a period that is not one of the seven must be refused",
            runCatching { GridPreference.setFaceDownLockAfter(ctx, 7_000L) }.isFailure,
        )
        assertEquals("a refused period must not overwrite the stored one", 15_000L, GridPreference.faceDownLockAfter(ctx))
    }

    @Test
    fun aStoredLockAfterDelaysTheLock() {
        GridPreference.setFaceDown(ctx, true)
        GridPreference.setFaceDownLockAfter(ctx, 5_000L)
        vault.create(pass(GOOD))

        FaceDownLock.start(ctx, ::lockLikeTheButton)
        val base = android.os.SystemClock.elapsedRealtime()
        faceDownBetween(base, 0L, FaceDown.HOLD_MS + 4_000L)
        assertTrue("4 s into a 5 s Lock after, the vault must still be open", VaultSession.isUnlocked)
        assertEquals(0, locks)

        faceDownBetween(base, FaceDown.HOLD_MS + 4_000L + STEP_MS, FaceDown.HOLD_MS + 5_200L)
        assertFalse("past Lock after, the vault must be locked", VaultSession.isUnlocked)
        assertEquals("the trigger must lock through the caller's lock, exactly once", 1, locks)
    }

    @Test
    fun samplesArriveNowhereWhileTheTriggerIsOff() {
        GridPreference.setFaceDown(ctx, false)
        vault.create(pass(GOOD))

        FaceDownLock.start(ctx, ::lockLikeTheButton)
        placeFaceDown(FaceDown.HOLD_MS * 3)

        assertTrue("no listener means no lock, whatever the pose", VaultSession.isUnlocked)
        assertEquals(0, locks)
    }

    /**
     * A real PNG, encoded by the platform. Big enough to matter: the cache measures
     * kilobytes, so a 1x1 bitmap is resident and still reads as size 0.
     *
     * A local copy of `RuntimeHardeningTest`'s. That file is the P1-10 hardening
     * suite and this change promises it passes byte-for-byte unchanged; widening a
     * private helper there to share six lines here would break that promise for
     * nothing.
     */
    private fun squarePng(): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(
            256, 256, android.graphics.Bitmap.Config.ARGB_8888
        )
        return java.io.ByteArrayOutputStream().also {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
    }
}
