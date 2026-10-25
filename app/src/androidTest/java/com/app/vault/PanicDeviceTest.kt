package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
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
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * P2-3 on a device, as `panic-phrase` reshaped it — what a panic phrase destroys, what
 * it leaves, that nothing about it can be seen, and that setting, changing and clearing
 * it never writes a slot it has not proven.
 *
 * The edit-distance half is arithmetic and lives in `PanicTest`. This is the other half:
 * three vault lists, a panic phrase set on one, and the state of the install afterwards.
 */
@RunWith(AndroidJUnit4::class)
class PanicDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var slotsFile: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "PanicDeviceTest"
        const val KEEP = "the-vault-that-survives"
        const val GONE_A = "the-first-vault-that-closes"
        const val GONE_B = "the-second-vault-that-closes"
        const val PANIC = "quick exit phrase"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "panic-${System.nanoTime()}").apply { mkdirs() }
        slotsFile = File(root, "vault.slots")
        store = BlobStore(File(root, "blobs"))
        vault = VaultLifecycle(slotsFile, store)
    }

    @After
    fun tearDown() {
        VaultLifecycle.lockNow()
        // This test enqueues `MirrorDestroyWorker` a dozen times to time the scheduling
        // call. None of them may still be running when the next test starts — see the
        // same line in the nine fixture suites.
        WorkManager.getInstance(ctx).cancelAllWork()
        root.deleteRecursively()
    }

    /** What the activity does with typed text: hand it to `unlock`, nothing more. */
    private fun submit(typed: String, wrap: () -> Unit = {}): VaultLifecycle.Outcome =
        vault.unlock(pass(typed), destroyWrap = wrap)

    /** Sets [PANIC] on [KEEP]'s vault list, as the screen does, and locks. */
    private fun armPanic() {
        assertTrue(vault.unlock(pass(KEEP)).opened)
        assertEquals(VaultLifecycle.PanicResult.DONE, vault.setPanic(pass(KEEP), pass(PANIC)))
        VaultLifecycle.lockNow()
    }

    private fun opens(passphrase: String): Boolean =
        vault.unlockAndOpen(pass(passphrase)).also { VaultLifecycle.lockNow() }

    /**
     * Three vault lists built the way the app builds them — `panic-phrase`. The first on an
     * empty install, each later one from [KEEP] while it is open, as the decoy flow does, so
     * [KEEP]'s occupancy record knows both. A panic phrase is sealed into a slot that record
     * says is free; lists created with nothing open know only themselves, which the app never
     * produces outside first run, and sealing beside them would be sealing blind.
     */
    private fun buildThreeVaults() {
        assertTrue(vault.create(pass(KEEP)).opened)
        assertTrue("created from the open list, which learns its slot", vault.create(pass(GONE_A)).opened)
        VaultLifecycle.lockNow()
        assertTrue(vault.unlock(pass(KEEP)).opened)
        assertTrue(vault.create(pass(GONE_B)).opened)
        VaultLifecycle.lockNow()
    }

    private fun blobFiles() = File(root, "blobs").listFiles().orEmpty().filter { it.isFile }

    private fun digestOf(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
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

    private fun logEntries(): List<LogEntry> {
        // `vault-keyring`. The log belongs to a vault, and an unlock opens a keyring —
        // so the vault this reads is opened from the list first.
        if (!VaultSession.isUnlocked) VaultKeyringSession.openVault(0)
        val vmk = VaultSession.vmk()!!
        val db = MetadataDb.load(store, VaultSession.metadataBlobId()!!, vmk)
        return TamperLog.scan(store, db.logBlobId!!, vmk).entries
    }

    // --- the Verify, second half ------------------------------------------------------

    @Test
    fun aPanicPhraseClosesEveryOtherVaultListAndOpensItsOwn() {
        buildThreeVaults()
        armPanic()

        val outcome = submit(PANIC)
        assertTrue("the panic credential must open its vault", outcome.opened)
        assertEquals("one derivation, as always", 1, outcome.derivations)
        assertEquals("every slot attempted, as always", SlotArray.SLOT_COUNT, outcome.slotsAttempted)
        assertNull("and nothing else reported", outcome.refusal)
        VaultLifecycle.lockNow()

        assertFalse("$GONE_A must be closed", opens(GONE_A))
        assertFalse("$GONE_B must be closed", opens(GONE_B))
        assertTrue("the surviving vault's own passphrase must still work", opens(KEEP))
        assertTrue("and so must its panic phrase", submit(PANIC).opened)
        VaultLifecycle.lockNow()
    }

    @Test
    fun theSlotFileIsIntactAndOnlyTheOtherSlotsChanged() {
        buildThreeVaults()
        armPanic()
        val saltBefore = SlotArray(slotsFile).installSalt()

        assertTrue(submit(PANIC).opened)
        VaultLifecycle.lockNow()

        assertEquals("file size", SlotArray.FILE_BYTES.toLong(), slotsFile.length())
        assertArrayEquals("install salt", saltBefore, SlotArray(slotsFile).installSalt())
    }

    @Test
    fun contentIsNotDestroyed() {
        buildThreeVaults()
        // Give the vault that will close something to lose.
        assertTrue(vault.unlockAndOpen(pass(GONE_A)))
        val vmk = VaultSession.vmk()!!.copyOf()
        val id = VaultSession.metadataBlobId()!!.copyOf()
        val content = ByteArray(4096).also { rng.nextBytes(it) }
        val blob = store.write(ByteArrayInputStream(content), vmk)
        MetadataDb.load(store, id, vmk).also {
            it.items += VaultItem(
                listOf(blob.blobId), blob.plaintextLength, null, "kept.bin", null, null, 1, emptyList()
            )
            it.save(store, id, vmk)
        }
        VaultLifecycle.lockNow()
        armPanic()

        // Named files rather than a count: the vault that *opens* legitimately churns
        // one of its own chaff blobs and rewrites its own index and log, exactly as
        // any unlock does. What must be untouched is everything belonging to the
        // vaults being closed.
        val closedIndex = store.fileOf(id)
        val closedItem = store.fileOf(blob.blobId)
        val closedLog = store.fileOf(MetadataDb.load(store, id, vmk).logBlobId!!)
        val before = listOf(closedIndex, closedItem, closedLog).associate { it.name to digestOf(it) }

        assertTrue(submit(PANIC).opened)
        VaultLifecycle.lockNow()

        before.forEach { (name, hash) ->
            val f = blobFiles().single { it.name == name }
            assertEquals("$name was touched by panic", hash, digestOf(f))
        }

        // The item is still readable with keys recovered outside the passphrase path.
        val db = MetadataDb.load(store, id, vmk)
        val item = db.items.single()
        val out = ByteArray(item.plaintextLength.toInt())
        store.reader(item.blobIds, vmk, item.plaintextLength).use { r ->
            val buf = ByteArray(BlobStore.CHUNK)
            var at = 0
            for (i in 0 until r.chunkCount) {
                val n = r.readChunk(i, buf)
                buf.copyInto(out, at, 0, n)
                at += n
            }
        }
        assertArrayEquals("the closed vault's content must be byte-identical", content, out)
    }

    @Test
    fun aRecoveryKitStillOpensAVaultPanicClosed() {
        val mnemonic = Mnemonic.fromResources(ctx)
        buildThreeVaults()

        assertTrue(vault.unlockAndOpen(pass(GONE_A)))
        // `vault-keyring`. The kit stands in for the **keyring** key, so what these
        // words recover is that keyring and every vault on it. Panic destroys the
        // slots — the way in — and not the keyring blob or any content, so the words
        // still reach it.
        val words = VaultKeyringSession.withKey { mnemonic.encode(it) }!!.joinToString(" ")
        VaultLifecycle.lockNow()
        armPanic()

        assertTrue(submit(PANIC).opened)
        VaultLifecycle.lockNow()
        assertFalse("its passphrase must be dead", opens(GONE_A))

        // The kit is the vault (§10 item 18). Panic destroys the way in, not the data.
        // `recovery-own-slot-only`: it is sealed back into its own slot, and nothing
        // about the survivor is supplied or touched.
        assertTrue(
            "the kit must still open it",
            vault.recover(mnemonic, words, pass("a-new-passphrase-entirely")).opened
        )
        VaultLifecycle.lockNow()
        assertTrue("the recovered list opens", opens("a-new-passphrase-entirely"))
        assertTrue("the survivor still opens", opens(KEEP))
    }

    // --- the entry ---------------------------------------------------------------------

    @Test
    fun theEntryIsWrittenAndIsNotSummarised() {
        buildThreeVaults()
        armPanic()
        assertTrue(submit(PANIC).opened)

        val entries = logEntries()
        val panicEntry = entries.singleOrNull { it.event == TamperLog.PANIC_TRIGGERED }
        assertTrue("the panic entry must be written", panicEntry != null)
        assertTrue(
            "it must use the reserved Phase 2 range",
            TamperLog.PANIC_TRIGGERED in 0x40..0x7F
        )
        // `vault-keyring`. **A panic unlock opens no vault**, so no `UNLOCK_SUCCEEDED`
        // follows it within the same unlock — the assertion that used to compare the
        // two indices was reading an entry from an earlier unlock. What it protected
        // is unchanged and is what is asserted now: the panic entry is written before
        // anything else, so a wipe interrupted halfway still leaves the record. Here
        // that means it is the last entry in the log at this point, with the whole
        // wipe having happened after it.
        assertEquals(
            "the panic entry must be the last thing written before the wipe",
            entries.lastIndex,
            entries.indexOf(panicEntry),
        )
        VaultLifecycle.lockNow()

        // The next unlock's digest must say nothing about it — that screen is read
        // over the shoulder of whoever compelled the unlock.
        assertTrue(vault.unlockAndOpen(pass(KEEP)))
        val shown = TamperLog.digest
        Log.i(TAG, "digest after a panic unlock: $shown")
        assertTrue(
            "the digest must not mention it: $shown",
            shown.none { it.contains("panic", ignoreCase = true) || it.contains("destroy", ignoreCase = true) }
        )
        VaultLifecycle.lockNow()
    }

    // --- off by default, and no marker ----------------------------------------------------

    @Test
    fun theOldMarkerFormIsOrdinaryText() {
        buildThreeVaults()
        armPanic()

        val outcome = submit(KEEP + "//")
        assertFalse("a passphrase with // on the end is derived literally and opens nothing", outcome.opened)
        VaultLifecycle.lockNow()

        assertTrue(opens(GONE_A))
        assertTrue(opens(GONE_B))
        assertTrue(opens(KEEP))
    }

    @Test
    fun aFreshVaultListHasNoPanicPhraseAndNothingOutsideTheKeyringRecordsOne() {
        buildThreeVaults()
        assertTrue(vault.unlock(pass(KEEP)).opened)
        assertFalse("panic is off until a phrase is set", VaultKeyringSession.hasPanicPhrase())
        VaultLifecycle.lockNow()

        armPanic()
        assertTrue(vault.unlock(pass(KEEP)).opened)
        assertTrue(VaultKeyringSession.hasPanicPhrase())
        VaultLifecycle.lockNow()
        assertFalse(
            "no preference records it",
            File(ctx.dataDir, "shared_prefs/panic.xml").exists(),
        )
    }

    @Test
    fun aPanicCredentialForAPassphraseThatOpensNothingDestroysNothing() {
        buildThreeVaults()
        armPanic()

        val outcome = submit("a passphrase that was never sealed")
        assertFalse(outcome.opened)
        VaultLifecycle.lockNow()

        assertTrue("nothing may be destroyed by a failed unlock", opens(GONE_A))
        assertTrue(opens(GONE_B))
        assertTrue(opens(KEEP))
    }

    @Test
    fun oneVaultIsNotAnError() {
        assertTrue(vault.create(pass(KEEP)).opened)
        VaultLifecycle.lockNow()
        armPanic()

        val outcome = submit(PANIC)
        assertTrue("it must open normally", outcome.opened)
        assertNull(outcome.refusal)
        VaultLifecycle.lockNow()
        assertTrue("and still open afterwards", opens(KEEP))
    }

    // --- the wrap -------------------------------------------------------------------------

    @Test
    fun theBiometricWrapGoesWithTheSlots() {
        buildThreeVaults()
        armPanic()
        val wrap = File(root, BiometricWrap.FILE_NAME)
        wrap.writeBytes(ByteArray(BiometricWrap.FILE_BYTES).also { rng.nextBytes(it) })

        assertTrue(submit(PANIC, wrap = { wrap.delete() }).opened)
        VaultLifecycle.lockNow()

        assertFalse("the wrap is VMK-wrapping material and must go", wrap.exists())
    }

    // --- indistinguishable ------------------------------------------------------------------

    @Test
    fun aPanicUnlockCostsWhatAnOrdinaryOneCosts() {
        buildThreeVaults()
        armPanic()

        fun time(run: () -> Unit): Long {
            VaultLifecycle.lockNow()
            val t = System.nanoTime()
            run()
            return (System.nanoTime() - t) / 1_000_000
        }

        // Warm-up first, then interleaved, minimum over 5 — P1-9's methodology and
        // its reason: contention only ever adds time. The panic path is idempotent
        // after the first run, which is what makes repeating it meaningful.
        time { submit(PANIC) }
        time { vault.unlock(pass(KEEP)) }

        val panic = mutableListOf<Long>()
        val plain = mutableListOf<Long>()
        repeat(5) {
            panic += time { submit(PANIC) }
            plain += time { vault.unlock(pass(KEEP)) }
        }
        VaultLifecycle.lockNow()

        val p = panic.min()
        val q = plain.min()
        val spread = maxOf(p, q).toDouble() / minOf(p, q).coerceAtLeast(1)
        Log.i(TAG, "panic ${p}ms, plain ${q}ms, spread %.2fx".format(spread))
        assertTrue(
            "a wipe of seven slots must not be visible against a ~460 ms derivation: " +
                "panic ${p}ms vs plain ${q}ms",
            spread < 1.5
        )
    }

    /**
     * `cloud-mirror` D8 against `panic-pin`'s cost requirement — the three-way case.
     *
     * A configured backup gives panic somewhere else to reach, and reaching it is
     * IPC to another app that may be signed out, throttled or simply hung. If that
     * happened **on** the unlock path, a panic unlock with a backup would take
     * visibly longer than one without, and the whole feature exists to be
     * indistinguishable.
     *
     * So the three timings are: no destination, a destination that answers, and one
     * that never does. The third is the important one, and it is the case a real
     * dead provider produces.
     */
    @Test
    fun aConfiguredDestinationDoesNotChangeWhatPanicCosts() {
        buildThreeVaults()
        armPanic()

        fun time(destroyMirror: () -> Unit): Long {
            VaultLifecycle.lockNow()
            val bytes = pass(PANIC)
            val t = System.nanoTime()
            vault.unlock(bytes, destroyMirror = destroyMirror)
            return (System.nanoTime() - t) / 1_000_000
        }

        // What the app actually passes: something that hands the work to
        // WorkManager and returns. Not the deletion itself.
        val enqueues = { MirrorDestroyWorker.enqueue(ctx) }
        // A provider that never answers. If `destroy` ever waited on one, this
        // column alone would blow the envelope.
        val hangs = { Thread.sleep(4_000) }

        // ***Warmed three times each, not once.*** `MirrorDestroyWorker.enqueue` writes
        // to WorkManager's `work.db`, and the first writes in a process pay for opening
        // and migrating it. One warm-up left that cost inside the measured column, where
        // it read as the destination making panic slower: this failed a full-suite run at
        // `none 278ms vs destination 428ms`, a 1.54x spread out of disk initialisation.
        repeat(3) { time { }; time(enqueues) }

        val none = mutableListOf<Long>()
        val reachable = mutableListOf<Long>()
        // Three, as before. The warm-up above is what removed the noise; more samples
        // only enqueue more workers for the next test to trip over.
        repeat(3) {
            none += time { }
            reachable += time(enqueues)
        }
        VaultLifecycle.lockNow()

        // The minimum of each column, for the reason `SearchTest` records one file along:
        // preemption can only make a sample slower, so the minimum is the honest estimate
        // of the cost, and a genuine wait raises the floor rather than the spread.
        val a = none.min()
        val b = reachable.min()
        val spread = maxOf(a, b).toDouble() / minOf(a, b).coerceAtLeast(1)
        Log.i(TAG, "panic: none ${a}ms, destination ${b}ms, spread %.2fx".format(spread))
        assertTrue(
            "a configured destination must not change what panic costs: " +
                "none ${a}ms vs destination ${b}ms",
            spread < 1.5
        )

        // And the guard on the guard: a lambda that *does* block is detectable, so
        // the assertion above is measuring something rather than passing vacuously.
        // This is why `destroyMirror` must enqueue and must never delete inline.
        val blocked = time(hangs)
        VaultLifecycle.lockNow()
        assertTrue(
            "the envelope check would not detect a blocking destroyMirror, so it " +
                "proves nothing: blocked ${blocked}ms vs none ${a}ms",
            blocked > a * 2
        )
    }
    // --- setting, changing and clearing (`panic-phrase`) ------------------------------------

    @Test
    fun settingRefusesWhatItMust() {
        buildThreeVaults()
        assertTrue(vault.unlock(pass(KEEP)).opened)
        assertEquals(
            "another list's passphrase is not this one's",
            VaultLifecycle.PanicResult.WRONG_PASSPHRASE, vault.setPanic(pass(GONE_A), pass(PANIC)),
        )
        assertEquals(VaultLifecycle.PanicResult.TOO_SHORT, vault.setPanic(pass(KEEP), pass("short")))
        assertEquals(
            "one typo from the passphrase",
            VaultLifecycle.PanicResult.TOO_CLOSE, vault.setPanic(pass(KEEP), pass(KEEP + "x")),
        )
        assertEquals(
            "a phrase that already opens a list",
            VaultLifecycle.PanicResult.IN_USE, vault.setPanic(pass(KEEP), pass(GONE_A)),
        )
        assertFalse("nothing was set by a refusal", VaultKeyringSession.hasPanicPhrase())
        VaultLifecycle.lockNow()
        assertTrue(opens(GONE_A))
    }

    @Test
    fun changingKeepsTheSlotAndRetiresTheOldPhrase() {
        buildThreeVaults()
        armPanic()
        val before = slotsFile.readBytes()

        assertTrue(vault.unlock(pass(KEEP)).opened)
        val slot = VaultKeyringSession.panicSlot()
        assertEquals(
            VaultLifecycle.PanicResult.DONE,
            vault.changePanic(pass(PANIC), pass(KEEP), pass("a brand new exit")),
        )
        VaultLifecycle.lockNow()

        val after = slotsFile.readBytes()
        for (i in 0 until SlotArray.SLOT_COUNT) {
            val range = slotRange(i)
            val same = before.copyOfRange(range.first, range.last + 1)
                .contentEquals(after.copyOfRange(range.first, range.last + 1))
            assertEquals("only the panic slot may be written (slot $i)", i != slot, same)
        }
        assertFalse("the old phrase opens nothing", submit(PANIC).opened)
        VaultLifecycle.lockNow()
        assertTrue("nothing was destroyed by the old phrase", opens(GONE_A))
        assertTrue("the new phrase opens the list", submit("a brand new exit").opened)
        VaultLifecycle.lockNow()
        assertFalse("and it is a panic phrase", opens(GONE_A))
    }

    @Test
    fun turningOffDestroysTheProvenSlot() {
        buildThreeVaults()
        armPanic()

        assertTrue(vault.unlock(pass(KEEP)).opened)
        assertEquals(VaultLifecycle.PanicResult.WRONG_PANIC_PHRASE, vault.clearPanic(pass("not the phrase")))
        assertTrue("a mistyped phrase changes nothing", VaultKeyringSession.hasPanicPhrase())
        assertEquals(VaultLifecycle.PanicResult.DONE, vault.clearPanic(pass(PANIC)))
        assertFalse(VaultKeyringSession.hasPanicPhrase())
        VaultLifecycle.lockNow()

        assertFalse("the phrase opens nothing", submit(PANIC).opened)
        VaultLifecycle.lockNow()
        assertTrue(opens(GONE_A))
        assertTrue(opens(GONE_B))
    }

    @Test
    fun aStaleRecordNeverDestroysAnotherVaultList() {
        buildThreeVaults()
        armPanic()

        // Another list seals over the panic slot — the case a list that never knew the
        // slot can produce. Done by hand: the test knows the slot, the app never shows it.
        assertTrue(vault.unlock(pass(KEEP)).opened)
        val slot = VaultKeyringSession.panicSlot()
        VaultLifecycle.lockNow()
        val slots = SlotArray(slotsFile)
        val other = VaultKeys.deriveKek(pass("someone else's list"), slots.installSalt(), slots.kdfParams())
        slots.seal(slot, other, ByteArray(32).also { rng.nextBytes(it) }, ByteArray(16).also { rng.nextBytes(it) })
        val before = slotsFile.readBytes()

        assertTrue(vault.unlock(pass(KEEP)).opened)
        assertEquals(VaultLifecycle.PanicResult.WRONG_PANIC_PHRASE, vault.clearPanic(pass(PANIC)))
        assertArrayEquals("the other list's slot is untouched", before, slotsFile.readBytes())

        assertEquals(VaultLifecycle.PanicResult.DONE, vault.forgetPanic(pass(KEEP)))
        assertArrayEquals("forgetting writes no slot", before, slotsFile.readBytes())
        assertFalse(VaultKeyringSession.hasPanicPhrase())
        assertTrue(
            "the slot stays known, so nothing seals over it",
            (VaultKeyringSession.knownSlots() shr slot) and 1 == 1,
        )
        VaultLifecycle.lockNow()
    }

    @Test
    fun aForgottenPhraseIsClearedWithoutWritingASlot() {
        buildThreeVaults()
        armPanic()
        val before = slotsFile.readBytes()

        assertTrue(vault.unlock(pass(KEEP)).opened)
        assertEquals(VaultLifecycle.PanicResult.WRONG_PASSPHRASE, vault.forgetPanic(pass(GONE_A)))
        assertEquals(VaultLifecycle.PanicResult.DONE, vault.forgetPanic(pass(KEEP)))
        VaultLifecycle.lockNow()
        assertArrayEquals(before, slotsFile.readBytes())

        assertTrue("the forgotten phrase still opens the list", submit(PANIC).opened)
        VaultLifecycle.lockNow()
        assertTrue("but it is no longer a panic phrase", opens(GONE_A))
    }

    @Test
    fun aVaultListRecoveredFromItsKitKeepsItsPanicPhrase() {
        val mnemonic = Mnemonic.fromResources(ctx)
        buildThreeVaults()
        armPanic()

        assertTrue(vault.unlock(pass(KEEP)).opened)
        val keepWords = VaultKeyringSession.withKey { mnemonic.encode(it) }!!.joinToString(" ")
        VaultLifecycle.lockNow()

        // Recovered on the phone that holds its slots: its panic record is still true.
        assertTrue(vault.recover(mnemonic, keepWords, pass("recovered passphrase")).opened)
        assertTrue("the panic screen reads on", VaultKeyringSession.hasPanicPhrase())
        VaultLifecycle.lockNow()

        assertTrue("the panic phrase still opens", submit(PANIC).opened)
        VaultLifecycle.lockNow()
        assertFalse("and it still triggers panic", opens(GONE_A))
        assertTrue("the recovered list survives its own panic", opens("recovered passphrase"))
    }

    @Test
    fun aVaultListRestoredOntoAnEmptyPhoneHasPanicOff() {
        val mnemonic = Mnemonic.fromResources(ctx)
        buildThreeVaults()
        armPanic()
        assertTrue(vault.unlock(pass(KEEP)).opened)
        val keepWords = VaultKeyringSession.withKey { mnemonic.encode(it) }!!.joinToString(" ")
        VaultLifecycle.lockNow()

        val phone = File(ctx.filesDir, "panic-phone-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(root, "blobs").copyRecursively(File(phone, "blobs"))
            val restored = VaultLifecycle(
                File(phone, "vault.slots"), BlobStore(File(phone, "blobs"), File(phone, "incoming")),
            )
            assertTrue(restored.recover(mnemonic, keepWords, pass("restored passphrase"), fresh = true).opened)
            assertFalse("the panic screen reads off", VaultKeyringSession.hasPanicPhrase())
            assertEquals(
                "turning panic on seals a new slot",
                VaultLifecycle.PanicResult.DONE, restored.setPanic(pass("restored passphrase"), pass(PANIC)),
            )
            assertTrue(VaultKeyringSession.hasPanicPhrase())
            VaultLifecycle.lockNow()
        } finally {
            phone.deleteRecursively()
        }
    }

    /** `FORMAT.md` §4: 26 bytes of salt and header, then 256 per slot. */
    private fun slotRange(i: Int) = (26 + i * 256) until (26 + (i + 1) * 256)
}
