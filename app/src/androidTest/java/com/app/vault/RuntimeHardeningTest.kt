package com.app.vault

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

/**
 * P1-10 — auto-lock policy, key material, and the environment warnings.
 *
 * The heap-dump test at the end is the task's Verify line, and it is the only
 * one here that can prove its claim: everything else about zeroing is an
 * assertion about code, and a dump is an observation about memory.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeHardeningTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var slotsFile: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle

    private companion object {
        const val TAG = "RuntimeHardeningTest"
        const val GOOD = "correct horse battery staple"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        VaultKeyringSession.lock()
        VaultSession.lock()
        root = File(ctx.filesDir, "hardening-${System.nanoTime()}")
        root.mkdirs()
        slotsFile = File(root, "vault.slots")
        store = BlobStore(File(root, "blobs"))
        vault = VaultLifecycle(slotsFile, store)
    }

    @After
    fun tearDown() {
        VaultKeyringSession.lock()
        VaultSession.lock()
        ThumbnailCache.clear()
        root.deleteRecursively()
    }

    // --- the lock itself ------------------------------------------------------

    @Test
    fun lockClearsBothTheKeyAndTheDecryptedPixels() {
        vault.create(pass(GOOD))
        ThumbnailCache.get(ThumbRef(ByteArray(16) { 1 }, 0, 1)) { squarePng() }
        assertTrue("nothing cached to clear", ThumbnailCache.size() > 0)

        VaultLifecycle.lockNow()

        assertFalse("the session still holds a key", VaultSession.isUnlocked)
        assertEquals("decrypted pixels survived the lock", 0, ThumbnailCache.size())
    }

    @Test
    fun theLockNowPathIsTheSameOneTheButtonUses() {
        vault.create(pass(GOOD))
        vault.lock()
        assertFalse(VaultSession.isUnlocked)
        assertEquals(0, ThumbnailCache.size())

        VaultSession.lock()
        assertTrue(VaultLifecycle(slotsFile, store).unlockAndOpen(pass(GOOD)))
        VaultLifecycle.lockNow()
        assertFalse("lockNow must do what lock() does", VaultSession.isUnlocked)
    }

    @Test
    fun aVaultLockedByPolicyOpensAgainWithEverythingIntact() {
        vault.create(pass(GOOD))
        val id = VaultSession.metadataBlobId()!!.copyOf()
        val db = MetadataDb.load(store, id, VaultSession.vmk()!!)
        val written = store.write(ByteArrayInputStream(ByteArray(4000) { 3 }), VaultSession.vmk()!!)
        db.items += VaultItem(
            listOf(written.blobId), written.plaintextLength, null, "survives.bin", null, null, 0, emptyList()
        )
        db.save(store, id, VaultSession.vmk()!!)

        VaultLifecycle.lockNow()
        assertTrue(VaultLifecycle(slotsFile, store).unlockAndOpen(pass(GOOD)))

        val back = MetadataDb.load(store, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!)
        assertEquals(1, back.items.size)
        assertEquals("survives.bin", back.items[0].filename)
    }

    // --- the suppressions -----------------------------------------------------

    @Test
    fun aLaunchedPickerSuppressesTheLockAndReleasingItDoesNot() {
        vault.create(pass(GOOD))

        AutoLock.onForeground()
        AutoLock.awaitingResult(true)
        AutoLock.onBackground()
        Thread.sleep(500)
        assertTrue("a picker this app launched must not lock the vault", VaultSession.isUnlocked)

        AutoLock.awaitingResult(false)
        assertTrue("the lock must follow the picker returning", waitForLock())
    }

    @Test
    fun backgroundingWithNoSuppressionLocksAtOnce() {
        vault.create(pass(GOOD))

        AutoLock.onForeground()
        AutoLock.onBackground()

        assertTrue("backgrounding must lock", waitForLock())
    }

    @Test
    fun comingBackBeforeTheLockLandsKeepsTheVaultOpen() {
        vault.create(pass(GOOD))

        AutoLock.onForeground()
        AutoLock.awaitingResult(true)
        AutoLock.onBackground()
        AutoLock.onForeground()
        AutoLock.awaitingResult(false)
        Thread.sleep(500)

        assertTrue("returning to the foreground must not lock", VaultSession.isUnlocked)
        AutoLock.onBackground()
        assertTrue(waitForLock())
    }

    /**
     * The import suppression, through the same seam the app uses: WorkManager is
     * asked for unfinished work under the import tag.
     *
     * The work is real and enqueued, and is held unfinished by an initial delay
     * rather than by a fake — the policy reads WorkManager's own view, so a stub
     * would be testing the stub. Cancelling it is what a finishing import looks
     * like to this query, and the lock has to land at that moment even though the
     * app never returned to the foreground.
     */
    @Test
    fun anImportInFlightSuppressesTheLockUntilItFinishes() {
        vault.create(pass(GOOD))
        val work = androidx.work.OneTimeWorkRequestBuilder<ImportWorker>()
            .setInitialDelay(10, java.util.concurrent.TimeUnit.MINUTES)
            .addTag(ImportWorker.IMPORT_TAG)
            .build()
        val wm = androidx.work.WorkManager.getInstance(ctx)
        wm.enqueue(work).result.get()

        try {
            AutoLock.onForeground()
            AutoLock.onBackground()
            Thread.sleep(1_500)
            assertTrue("an import in flight must hold the vault open", VaultSession.isUnlocked)

            wm.cancelWorkById(work.id).result.get()
            assertTrue("the lock must land when the last import finishes", waitForLock())
        } finally {
            wm.cancelWorkById(work.id)
        }
    }

    /**
     * The same suppression, for P1-13's export.
     *
     * Not a third kind of suppression — P1-10's rule is a launched picker and
     * long-running work the user can see in the shade, and an export is the second
     * kind. Without it a backgrounded multi-GB export would lock the vault under
     * itself and park on `ExportWorker`'s "wait for the user" branch, which is the
     * failure P1-8.6 and P1-10 already fixed once for imports.
     */
    @Test
    fun anExportInFlightSuppressesTheLockUntilItFinishes() {
        vault.create(pass(GOOD))
        val work = androidx.work.OneTimeWorkRequestBuilder<ExportWorker>()
            .setInitialDelay(10, java.util.concurrent.TimeUnit.MINUTES)
            .addTag(ExportWorker.EXPORT_TAG)
            .build()
        val wm = androidx.work.WorkManager.getInstance(ctx)
        wm.enqueue(work).result.get()

        try {
            AutoLock.onForeground()
            AutoLock.onBackground()
            Thread.sleep(1_500)
            assertTrue("an export in flight must hold the vault open", VaultSession.isUnlocked)

            wm.cancelWorkById(work.id).result.get()
            assertTrue("the lock must land when the last export finishes", waitForLock())
        } finally {
            wm.cancelWorkById(work.id)
        }
    }

    @Test
    fun noUnfinishedImportMeansTheLockLands() {
        vault.create(pass(GOOD))
        AutoLock.onForeground()
        AutoLock.onBackground()
        assertTrue(waitForLock())
    }

    private fun waitForLock(timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!VaultSession.isUnlocked) return true
            Thread.sleep(50)
        }
        return false
    }

    /** As [waitForLock], for the level above it — `keyring-auto-lock`. */
    private fun waitForKeyringLock(timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!VaultKeyringSession.isOpen) return true
            Thread.sleep(50)
        }
        return false
    }

    // --- the level above the vault — `keyring-auto-lock` ------------------------
    //
    // A passphrase opens a keyring; choosing from the list opens a vault. Every test
    // above asserts on `VaultSession`, which is the vault. `AutoLock` guarded on the
    // same thing, so the vault list — a keyring open, no vault open — never locked at
    // all: screen off, home and recents all left every vault's name readable. These
    // four are that state, and they are what stops the guard regressing to asking
    // about vaults alone.

    @Test
    fun aKeyringWithNoVaultOpenLocksOnBackground() {
        vault.create(pass(GOOD))
        // Back to the vault list: the vault closes, the keyring stays open. This is
        // exactly what the user is looking at when they press home.
        VaultKeyringSession.leaveVault()
        assertTrue("the fixture must leave the keyring open", VaultKeyringSession.isOpen)
        assertFalse("and no vault open", VaultSession.isUnlocked)

        AutoLock.onForeground()
        AutoLock.onBackground()

        assertTrue(
            "backgrounding on the vault list must lock the keyring — it holds every " +
                "vault's name in the clear",
            waitForKeyringLock(),
        )
    }

    @Test
    fun aKeyringOnlySessionRespectsThePickerSuppression() {
        vault.create(pass(GOOD))
        VaultKeyringSession.leaveVault()

        AutoLock.onForeground()
        AutoLock.awaitingResult(true)
        AutoLock.onBackground()
        Thread.sleep(500)
        assertTrue(
            "a picker this app launched must not lock the keyring either",
            VaultKeyringSession.isOpen,
        )

        AutoLock.awaitingResult(false)
        assertTrue("and the lock must follow the picker returning", waitForKeyringLock())
    }

    @Test
    fun lockingAKeyringOnlySessionClearsTheDecryptedPixels() {
        vault.create(pass(GOOD))
        ThumbnailCache.get(ThumbRef(ByteArray(16) { 9 }, 0, 1)) { squarePng() }
        assertTrue("nothing cached to clear", ThumbnailCache.size() > 0)
        VaultKeyringSession.leaveVault()

        AutoLock.onForeground()
        AutoLock.onBackground()

        assertTrue(waitForKeyringLock())
        assertEquals(
            "the keyring-only path reaches lockNow, so it clears the cache like every " +
                "other lock does",
            0,
            ThumbnailCache.size(),
        )
    }

    @Test
    fun openingAVaultAndReturningToTheListStillLocksBothLevels() {
        vault.create(pass(GOOD))
        VaultKeyringSession.leaveVault()

        AutoLock.onForeground()
        AutoLock.onBackground()

        assertTrue(waitForKeyringLock())
        assertFalse("both levels, never one", VaultSession.isUnlocked)
        assertFalse(VaultKeyringSession.isOpen)
    }

    // --- environment warnings --------------------------------------------------

    @Test
    fun theWarningsDescribeTheDeviceAndBlockNothing() {
        val findings = RuntimeWarnings.check(ctx)
        Log.i(TAG, "runtime warnings: ${findings.map { it.message }}")

        // Instrumented builds are debuggable, so this run must produce at least
        // that finding — a check that can only ever return nothing is not a check.
        assertTrue(
            "a debuggable build must be reported",
            findings.any { it.message.contains("debuggable", ignoreCase = true) }
        )
        findings.forEach { assertTrue("a finding must be a sentence", it.message.length > 20) }

        // And nothing about the vault behaves differently for it.
        assertTrue(vault.create(pass(GOOD)).opened)
        VaultSession.lock()
        assertTrue(VaultLifecycle(slotsFile, store).unlockAndOpen(pass(GOOD)))
    }

    // --- the Verify line -------------------------------------------------------

    /**
     * Unlock, use the keys, lock, dump the heap, and search it for key bytes.
     *
     * The expected patterns are held **masked** while the dump is taken: the test
     * needs to know what to look for, and a plain copy in a local would be found
     * by its own search. They are unmasked afterwards, when the dump is already
     * on disk and nothing further is captured.
     */
    @Test
    fun aLockedAppLeavesNoKeyMaterialOnTheHeap() {
        vault.create(pass(GOOD))
        val blobId = VaultSession.metadataBlobId()!!.copyOf()
        val vmk = VaultSession.vmk()!!

        // Every kind of key P1-10 names: the master key, a per-blob DEK, and the
        // subkeys derived from the VMK.
        val masked = listOf(
            "VMK" to vmk,
            "metadata DEK" to VaultKeys.blobKey(vmk, blobId),
            "thumbnail key" to VaultKeys.thumbnailKey(vmk),
            "metadata key" to VaultKeys.metadataKey(vmk),
            "log seal key" to VaultKeys.logSealKey(vmk),
        ).map { (name, bytes) -> name to mask(bytes).also { if (bytes !== vmk) bytes.fill(0) } }

        // Use the vault, so a DEK and a decrypted buffer have really existed.
        val written = store.write(ByteArrayInputStream(ByteArray(9000) { 5 }), vmk)
        store.reader(listOf(written.blobId), vmk, written.plaintextLength).use {
            it.readRange(0, written.plaintextLength.toInt())
        }

        VaultLifecycle.lockNow()
        repeat(3) { System.gc(); Thread.sleep(100) }

        val dump = File(ctx.cacheDir, "heap-${System.nanoTime()}.hprof")
        try {
            Debug.dumpHprofData(dump.absolutePath)
            Log.i(TAG, "heap dump: ${dump.length()} B")

            val patterns = masked.map { (name, m) -> name to unmask(m) }
            val hits = patterns.filter { (_, bytes) -> dump.containsBytes(bytes) }.map { it.first }
            Log.i(TAG, "searched for ${patterns.size} key patterns, found: $hits")
            assertTrue("key material survived the lock on the heap: $hits", hits.isEmpty())

            // Measured, never asserted: Compose hands text input over as an
            // immutable String, so the typed passphrase lives until GC. FORMAT.md
            // §10 records it; gating on it would be gating on the collector.
            val passphraseResidue = dump.containsBytes(GOOD.toByteArray(Charsets.UTF_8))
            Log.i(TAG, "passphrase String residue present in dump: $passphraseResidue")
        } finally {
            // A heap dump holding vault key material is the artefact this app
            // exists to prevent. It does not outlive the test either way.
            dump.delete()
        }
    }

    // --- helpers ---------------------------------------------------------------

    private val maskKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }

    private fun mask(bytes: ByteArray) =
        ByteArray(bytes.size) { i -> (bytes[i].toInt() xor maskKey[i % maskKey.size].toInt()).toByte() }

    private fun unmask(bytes: ByteArray) = mask(bytes)

    /** Streamed, with an overlap so a pattern spanning two chunks is still found. */
    private fun File.containsBytes(needle: ByteArray): Boolean {
        val overlap = needle.size - 1
        val buffer = ByteArray(1 shl 20)
        inputStream().use { input ->
            var carry = ByteArray(0)
            while (true) {
                val n = input.read(buffer, 0, buffer.size)
                if (n <= 0) return false
                val window = carry + buffer.copyOf(n)
                if (indexOf(window, needle) >= 0) return true
                carry = if (window.size > overlap) window.copyOfRange(window.size - overlap, window.size)
                else window
            }
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /**
     * A real PNG, encoded by the platform. Big enough to matter: the cache
     * measures kilobytes, so a 1x1 bitmap is resident and still reads as size 0.
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
