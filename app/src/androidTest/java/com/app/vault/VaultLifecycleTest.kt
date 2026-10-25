package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom

/**
 * P1-8.5 — vault creation, unlock and lock.
 *
 * The task exists because nothing called any of this. These tests drive the
 * whole lifecycle with no Activity, which is what makes the I8 claims assertable
 * rather than eyeballed.
 */
@RunWith(AndroidJUnit4::class)
class VaultLifecycleTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var slotsFile: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultLifecycleTest"
        val GOOD = "correct horse battery staple"
        val WRONG = "incorrect horse battery staple"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        VaultSession.lock()
        root = File(ctx.filesDir, "lifecycle-${System.nanoTime()}")
        root.mkdirs()
        slotsFile = File(root, "vault.slots")
        store = BlobStore(File(root, "blobs"))
        vault = VaultLifecycle(slotsFile, store)
    }

    @After
    fun tearDown() {
        VaultSession.lock()
        ThumbnailCache.clear()
        root.deleteRecursively()
    }

    private fun blobFiles() = File(root, "blobs").listFiles().orEmpty().filter { it.isFile }

    // --- creation ------------------------------------------------------------

    @Test
    fun creatingAVaultLeavesItOpenAndOnDisk() {
        assertFalse("no vault before", vault.vaultExists())

        var lastDone = 0
        var lastTotal = 0
        val outcome = vault.create(pass(GOOD)) { done, total -> lastDone = done; lastTotal = total }

        assertTrue("did not open", outcome.opened)
        assertTrue("slot file missing", vault.vaultExists())
        assertEquals(SlotArray.FILE_BYTES.toLong(), slotsFile.length())
        assertTrue("session has no key", VaultSession.isUnlocked)

        val expected = VaultLifecycle.INSTALL_CHAFF_BLOBS + VaultLifecycle.CREATION_CHAFF_BLOBS
        assertEquals("progress must reach the total", expected, lastTotal)
        assertEquals(expected, lastDone)

        // Chaff blobs, the index blob, and the tamper log — which is a blob like
        // any other, because a log with a filename would count vaults (P1-11).
        val blobs = blobFiles()
        Log.i(TAG, "after creation: ${blobs.size} blobs, ${blobs.sumOf { it.length() }} B")
        // The index and the log, plus the keyring `vault-keyring` writes — one blob
        // per keyring, holding the vault just created as its single entry.
        assertEquals("chaff count", expected + 3, blobs.size)
        blobs.forEach {
            assertEquals("every blob must be a bucket size", BlobStore.bucketFor(it.length()), it.length())
        }
    }

    @Test
    fun theCreatedVaultOwnsChaffItCanChurn() {
        vault.create(pass(GOOD))
        val db = MetadataDb.load(store, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!)

        assertEquals(
            "creation must seed owned chaff, or the first unlocks churn nothing",
            VaultLifecycle.CREATION_CHAFF_BLOBS, db.ownedChaff.size
        )
    }

    @Test
    fun creationRefusesAShortPassphrase() {
        val failed = runCatching { vault.create(pass("short")) }.isFailure
        assertTrue("a short passphrase must be refused", failed)
        assertFalse("nothing may be written on refusal", vault.vaultExists())
    }

    @Test
    fun theSealedSlotIsNotAlwaysZero() {
        // Not an I8 violation by itself — the slots are indistinguishable without
        // the key — but a fixed choice is a pattern waiting to become one when
        // P2-1 seals a second vault.
        //
        // Driven through VaultLifecycle.create, not through a bare SecureRandom:
        // an earlier version of this test rolled its own die and proved only that
        // SecureRandom works, which is not the claim.
        val seen = mutableSetOf<Int>()
        repeat(10) {
            val dir = File(ctx.filesDir, "slotpick-${System.nanoTime()}").apply { mkdirs() }
            try {
                VaultLifecycle(File(dir, "vault.slots"), BlobStore(File(dir, "blobs")))
                    .create(pass(GOOD))
                val slots = SlotArray(File(dir, "vault.slots"))
                VaultUnlock.unlock(pass(GOOD), slots).vault?.let { seen += it.slotIndex }
                VaultSession.lock()
            } finally {
                dir.deleteRecursively()
            }
        }
        Log.i(TAG, "slot indices chosen across 10 real vault creations: ${seen.sorted()}")
        assertTrue("slot choice looks fixed: $seen", seen.size > 1)
    }

    // --- unlock --------------------------------------------------------------

    @Test
    fun aCreatedVaultReopensWithTheSamePassphrase() {
        vault.create(pass(GOOD))
        val id = VaultSession.metadataBlobId()!!.copyOf()

        // Put something in it, then simulate the process dying.
        val db = MetadataDb.load(store, id, VaultSession.vmk()!!)
        val written = store.write(ByteArrayInputStream(ByteArray(5000) { 7 }), VaultSession.vmk()!!)
        db.items += VaultItem(
            listOf(written.blobId), written.plaintextLength, null, "kept.bin", null, null, 0, emptyList()
        )
        db.save(store, id, VaultSession.vmk()!!)
        VaultSession.lock()
        assertFalse(VaultSession.isUnlocked)

        val reopened = VaultLifecycle(slotsFile, store).unlockAndOpen(pass(GOOD))
        assertTrue("the vault did not reopen", reopened)
        assertArrayEquals("different metadata blob", id, VaultSession.metadataBlobId())

        val back = MetadataDb.load(store, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!)
        assertEquals("the item did not survive", 1, back.items.size)
        assertEquals("kept.bin", back.items[0].filename)
    }

    @Test
    fun aWrongPassphraseAndAnEmptyVaultAreIndistinguishable() {
        // Case A: a real vault, wrong passphrase.
        vault.create(pass(GOOD))
        VaultSession.lock()
        val wrong = VaultLifecycle(slotsFile, store).unlock(pass(WRONG))

        // Case B: a slot array with nothing sealed into it at all.
        val emptyDir = File(ctx.filesDir, "empty-${System.nanoTime()}").apply { mkdirs() }
        val emptyVault = VaultLifecycle(File(emptyDir, "vault.slots"), BlobStore(File(emptyDir, "blobs")))
        val empty = emptyVault.unlock(pass(GOOD))

        Log.i(TAG, "wrong: opened=${wrong.opened} derivations=${wrong.derivations} " +
            "slots=${wrong.slotsAttempted}")
        Log.i(TAG, "empty: opened=${empty.opened} derivations=${empty.derivations} " +
            "slots=${empty.slotsAttempted}")

        // Asserted as one equality across the whole observable surface, not three
        // separate expectations that could each drift.
        assertEquals(
            "a wrong passphrase must be indistinguishable from an empty vault",
            listOf(wrong.opened, wrong.derivations, wrong.slotsAttempted),
            listOf(empty.opened, empty.derivations, empty.slotsAttempted),
        )
        assertFalse(wrong.opened)
        assertFalse(VaultSession.isUnlocked)
        emptyDir.deleteRecursively()
    }

    @Test
    fun operationCountsAreIdenticalWhateverHappens() {
        vault.create(pass(GOOD))
        VaultSession.lock()

        // The outcome's counts are what this measures, so it unlocks rather than
        // opening a vault: the derivation and the eight attempts happen before any
        // keyring is read, which is the property `trial-decrypt-unlock` pins.
        val ok = VaultLifecycle(slotsFile, store).unlock(pass(GOOD))
        lockKeyringAndVault()
        val bad = VaultLifecycle(slotsFile, store).unlock(pass(WRONG))

        val emptyDir = File(ctx.filesDir, "empty2-${System.nanoTime()}").apply { mkdirs() }
        val none = VaultLifecycle(File(emptyDir, "vault.slots"), BlobStore(File(emptyDir, "blobs")))
            .unlock(pass(GOOD))

        val expected = 1 to SlotArray.SLOT_COUNT
        listOf("success" to ok, "wrong" to bad, "empty" to none).forEach { (label, o) ->
            Log.i(TAG, "$label: ${o.derivations} derivation(s), ${o.slotsAttempted} slots")
            assertEquals("$label must cost one derivation and all eight slots",
                expected, o.derivations to o.slotsAttempted)
        }
        emptyDir.deleteRecursively()
    }

    @Test
    fun theOutcomeCarriesNoSlotInformation() {
        // A reflection assertion, so a future field that leaks occupancy fails
        // here rather than in a review.
        val fields = VaultLifecycle.Outcome::class.java.declaredFields
            .filterNot { it.isSynthetic }
            // The Compose compiler adds a `${'$'}stable` static to every class it sees.
            // It is a compiler artifact, not a field anyone declared.
            .filterNot { it.name.startsWith("${'$'}") }
            .map { it.name }
            .sorted()
        Log.i(TAG, "Outcome fields: $fields")
        // `refusal` arrived with P2-1 and is permitted deliberately: creation has
        // two answers a user must be able to act on — the passphrase already opens
        // a vault, and there is no room — and neither is derived from which slot
        // matched. Unlock never sets it. Anything else new here still fails.
        assertEquals(listOf("derivations", "opened", "refusal", "slotsAttempted"), fields)

        // And the reason it is safe is asserted rather than argued: the refusals
        // are a closed set, and no member of it names a slot or a count.
        //
        // The list is exact on purpose — a new refusal has to be added here
        // deliberately, with its disclosure argued in the enum's own KDoc, rather
        // than appearing unnoticed. Four have been since P2-1: one from
        // `slot-occupancy-map` and three from `backup-file-recover-and-import`, each
        // carrying that argument where it is declared. This literal had not kept up
        // with them, which is the one thing an exact-set guard must not do.
        val refusals = VaultLifecycle.Refusal.entries.map { it.name }
        Log.i(TAG, "Refusal constants: $refusals")
        assertEquals(
            listOf(
                "PASSPHRASE_IN_USE",
                "NO_ROOM",
                "DECOY_PASSPHRASE_NEEDED",
                "BACKUP_NOT_OPENED",
                "LIST_NOT_HERE",
                "BACKUP_INCOMPLETE",
            ),
            refusals,
        )
        assertTrue(
            "a refusal must not name a slot or a count",
            refusals.none { it.contains("SLOT") || it.contains("COUNT") },
        )
        assertNull("unlock never refuses; it opens or it does not", vault.unlock(pass(WRONG)).refusal)
    }

    // --- chaff churn ----------------------------------------------------------

    @Test
    fun eachUnlockRewritesExactlyOneOwnedChaffBlobAndNothingElse() {
        vault.create(pass(GOOD))
        val id = VaultSession.metadataBlobId()!!.copyOf()
        val before = blobFiles().associate { it.name to digest(it) }
        val ownedBefore = MetadataDb.load(store, id, VaultSession.vmk()!!)
            .ownedChaff.map { it.joinToString("") { b -> "%02x".format(b) } }.toSet()
        VaultSession.lock()

        assertTrue(VaultLifecycle(slotsFile, store).unlockAndOpen(pass(GOOD)))

        val after = blobFiles().associate { it.name to digest(it) }
        val ownedAfter = MetadataDb.load(store, id, VaultSession.vmk()!!)
            .ownedChaff.map { it.joinToString("") { b -> "%02x".format(b) } }.toSet()

        val gone = before.keys - after.keys
        val fresh = after.keys - before.keys
        Log.i(TAG, "churn: -${gone.size} +${fresh.size}, owned ${ownedBefore.size} -> ${ownedAfter.size}")

        assertEquals("exactly one blob must disappear", 1, gone.size)
        assertTrue("the removed blob must have been owned chaff", gone.single() in ownedBefore)
        assertEquals("owned chaff count must not drift", ownedBefore.size, ownedAfter.size)

        // Two more files change content: the index (it records the new chaff ID)
        // and the tamper log (P1-11 appends the unlock). Everything else must be
        // byte-identical. Both are recorded in FORMAT.md §10 as blobs an attacker
        // with repeated snapshots sees moving together.
        val indexName = id.joinToString("") { "%02x".format(it) }
        val logName = MetadataDb.load(store, id, VaultSession.vmk()!!)
            .logBlobId!!.joinToString("") { "%02x".format(it) }
        val changed = before.keys.intersect(after.keys)
            .filterNot { before[it] == after[it] }
        assertEquals(
            "only the index and the log may change content",
            setOf(indexName, logName), changed.toSet()
        )
    }

    // --- lock ------------------------------------------------------------------

    @Test
    fun lockClearsTheSessionWithoutDestroyingAnything() {
        vault.create(pass(GOOD))
        val id = VaultSession.metadataBlobId()!!.copyOf()
        val db = MetadataDb.load(store, id, VaultSession.vmk()!!)
        val written = store.write(ByteArrayInputStream(ByteArray(3000) { 9 }), VaultSession.vmk()!!)
        db.items += VaultItem(
            listOf(written.blobId), written.plaintextLength, null, "still-here.bin", null, null, 0, emptyList()
        )
        db.save(store, id, VaultSession.vmk()!!)

        vault.lock()
        assertFalse("session still holds a key", VaultSession.isUnlocked)
        assertEquals("thumbnail cache not cleared", 0, ThumbnailCache.size())

        assertTrue(VaultLifecycle(slotsFile, store).unlockAndOpen(pass(GOOD)))
        val back = MetadataDb.load(store, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!)
        assertEquals("locking destroyed data", 1, back.items.size)
        assertEquals("still-here.bin", back.items[0].filename)
    }

    // --- P1-11 -----------------------------------------------------------------

    /**
     * The whole arc: create, lock, fail twice, unlock, import, lock, unlock.
     *
     * The import event is appended through the same call `ImportWorker` makes,
     * not by running a worker — WorkManager's side of that is `ImportWorkerTest`'s,
     * and the thing under test here is what the log and the digest report.
     */
    @Test
    fun theDigestReportsWhatHappenedWhileTheVaultWasClosed() {
        vault.create(pass(GOOD))
        val id = VaultSession.metadataBlobId()!!.copyOf()
        val logId = MetadataDb.load(store, id, VaultSession.vmk()!!).logBlobId
        assertTrue("a created vault owns a log", logId != null)
        VaultLifecycle.lockNow()

        // The failure count is process state that deliberately survives a lock, so
        // earlier tests in this run may have left some. Assert the delta — a fixed
        // expectation here would be asserting on test order.
        val failuresBefore = TamperLog.pendingFailures()
        assertFalse(VaultLifecycle(slotsFile, store).unlockAndOpen(pass("wrongwrongwrong")))
        assertFalse(VaultLifecycle(slotsFile, store).unlockAndOpen(pass("alsowrongwrong")))
        assertEquals("counted while locked", failuresBefore + 2, TamperLog.pendingFailures())

        assertTrue(VaultLifecycle(slotsFile, store).unlockAndOpen(pass(GOOD)))
        assertTrue(
            "the unlock that ended the burst names it: ${TamperLog.digest}",
            TamperLog.digest.any { it == "${failuresBefore + 2} unlock attempts failed." }
        )

        val vmk = VaultSession.vmk()!!
        val db = MetadataDb.load(store, id, vmk)
        TamperLog.append(store, db, id, vmk, TamperLog.IMPORT_COMPLETED, TamperLog.countPayload(1))
        VaultLifecycle.lockNow()
        assertEquals("the digest goes with the key", emptyList<String>(), TamperLog.digest)

        assertTrue(VaultLifecycle(slotsFile, store).unlockAndOpen(pass(GOOD)))
        assertTrue(
            "the import is reported: ${TamperLog.digest}",
            TamperLog.digest.any { it == "1 item imported." }
        )
        assertFalse(
            "and nothing claims tampering: ${TamperLog.digest}",
            TamperLog.digest.any { it.contains("altered") }
        )

        val scan = TamperLog.scan(store, logId!!, VaultSession.vmk()!!)
        assertNull("the chain is intact", scan.chainBreakAt)
        assertEquals(
            "every event in order",
            listOf(
                TamperLog.VAULT_CREATED,
                TamperLog.UNLOCK_FAILED,
                TamperLog.UNLOCK_SUCCEEDED,
                TamperLog.IMPORT_COMPLETED,
                TamperLog.UNLOCK_SUCCEEDED,
            ),
            scan.entries.map { it.event }
        )
    }

    // --- I7 --------------------------------------------------------------------

    @Test
    fun thePassphrasePathTouchesNoKeystore() {
        // Behavioural half: it works, and creating and unlocking a vault add nothing
        // to the Keystore. A delta, not an absolute: P2-5's launch-time InstallAnchor
        // (`vault.anchor`) may already be present from an activity launch elsewhere,
        // and it holds no secret — the invariant that matters is that the passphrase
        // path itself creates no entry, above all no biometric wrap.
        fun aliases() = java.security.KeyStore.getInstance("AndroidKeyStore")
            .apply { load(null) }.aliases().toList().toSet()

        val before = aliases()
        vault.create(pass(GOOD))
        VaultSession.lock()
        assertTrue(VaultLifecycle(slotsFile, store).unlockAndOpen(pass(GOOD)))

        val added = aliases() - before
        Log.i(TAG, "AndroidKeyStore aliases before=$before added=$added")
        assertEquals("the passphrase path must add no Keystore entry", emptySet<String>(), added)
        assertFalse("the passphrase path must create no biometric wrap", BiometricWrap.ALIAS in aliases())
    }

    // --- passphrase bytes -------------------------------------------------------

    @Test
    fun thePassphraseIsZeroedAndReachesNoFile() {
        val bytes = pass(GOOD)
        val copy = bytes.copyOf()
        vault.create(bytes)
        assertTrue("create must zero the passphrase it was given", bytes.all { it == 0.toByte() })

        VaultSession.lock()
        val second = copy.copyOf()
        VaultLifecycle(slotsFile, store).unlock(second)
        assertTrue("unlock must zero the passphrase it was given", second.all { it == 0.toByte() })

        val hits = mutableListOf<String>()
        var files = 0
        listOf(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir)
            .flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }
            .forEach { f ->
                files++
                val content = runCatching { f.readBytes() }.getOrNull() ?: return@forEach
                if (indexOf(content, copy) >= 0) hits += f.path
            }
        Log.i(TAG, "passphrase scan: $files files, ${hits.size} hits")
        assertTrue("the passphrase is on disk: $hits", hits.isEmpty())
        assertTrue("scanned nothing", files > 0)
    }

    /** Streamed, because the chaff alone is ~128 MiB and a byte map is an OOM. */
    private fun digest(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // --- cost -------------------------------------------------------------------

    @Test
    fun firstRunAndUnlockCost() {
        val createStart = System.nanoTime()
        vault.create(pass(GOOD))
        val createMs = (System.nanoTime() - createStart) / 1_000_000
        val blobs = blobFiles()

        // Medians over 5, matching how P1-3 measured, but **interleaved** and
        // preceded by a warm-up — corrected in P1-9. Measuring five successes and
        // then five failures put every bit of first-run cost (JIT, the first
        // touch of Argon2id's 64 MiB buffer, the store settling after creation)
        // into whichever block ran first: 467 ms against 263 ms here, a 1.8x
        // spread with nothing wrong in the code. `VaultUnlockTest` had the same
        // defect and the same fix. Round-robin makes host drift hit both.
        fun time(p: String): Long {
            VaultSession.lock()
            val t = System.nanoTime()
            VaultLifecycle(slotsFile, store).unlock(pass(p))
            return (System.nanoTime() - t) / 1_000_000
        }

        // The derivation is measured in the *same* round-robin, for the same
        // reason. Measured after the unlocks in its own block, it recorded 420 ms
        // against a 265 ms unlock that contains one — an impossibility that is
        // purely an artifact of when each block ran.
        val slots = SlotArray(slotsFile)
        fun timeKdf(): Long {
            val t = System.nanoTime()
            VaultKeys.deriveKek(pass(GOOD), slots.installSalt(), slots.kdfParams()).fill(0)
            return (System.nanoTime() - t) / 1_000_000
        }

        time(GOOD)
        time(WRONG)
        timeKdf()

        val ok = mutableListOf<Long>()
        val bad = mutableListOf<Long>()
        val kdfs = mutableListOf<Long>()
        repeat(5) {
            ok += time(GOOD)
            bad += time(WRONG)
            kdfs += timeKdf()
        }
        // Minimum, not median, for the reason VaultUnlockTest records: contention
        // only ever adds time, so the fastest sample is the least polluted one.
        val okMs = ok.min()
        val badMs = bad.min()
        val kdf = kdfs.min()

        Log.i(TAG, "first run: $createMs ms, ${blobs.size} blobs, ${blobs.sumOf { it.length() }} B")
        Log.i(TAG, "unlock minimum over 5 — success $okMs ms, failure $badMs ms")

        // P1-2 measured deriveKek at a 494 ms median on this target with the same
        // ARGON2ID_PARAMS. If unlock now costs less than that, the difference is
        // in the KDF and not in this code, so measure the KDF directly rather
        // than leave the discrepancy hanging.
        Log.i(TAG, "deriveKek minimum over 5: $kdf ms (P1-2 recorded 494 ms on this target)")
        assertTrue("unlock cannot be faster than its own derivation", okMs >= kdf - 20)

        val spread = maxOf(okMs, badMs).toDouble() / minOf(okMs, badMs).coerceAtLeast(1)
        Log.i(TAG, "success/failure spread: %.2fx".format(spread))
        // A gross-regression check only, exactly as P1-3 framed it: an early exit
        // costs microseconds against a ~300 ms derivation and no stopwatch can
        // see it. The band is wide so GC and scheduler noise cannot fail it.
        assertTrue("success and failure timings diverged: ${okMs}ms vs ${badMs}ms", spread < 1.5)
        assertNotNull(createMs)
    }
}
