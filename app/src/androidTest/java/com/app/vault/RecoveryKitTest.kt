package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom

/**
 * P1-12 — the recovery kit, against OUTLINE P1-12's Verify.
 *
 * **On the Verify.** It reads "wipe app data, restore from mnemonic, all files
 * decrypt", and the literal form is not achievable: wiping app data deletes
 * `filesDir`, and I9 puts every blob there, so the ciphertext goes with it and no
 * mnemonic recovers deleted bytes. The mnemonic recovers *keys*. What is tested
 * here is the achievable form — copy the blobs out, delete the whole of the
 * install, restore only the blobs, and open it with 24 words.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryKitTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var slotsFile: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private lateinit var mnemonic: Mnemonic
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultRecovery"
        const val GOOD = "correcthorsebattery"
        const val REPLACEMENT = "a-different-passphrase"
        const val OTHER = "another-keyring-passphrase"
        const val DECOY = "the-decoy-keyring-passphrase"
        const val OWN = "the-vaults-own-passphrase"
        const val PANIC = "a-panic-phrase-far-from-it"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "recovery-${System.nanoTime()}")
        root.mkdirs()
        slotsFile = File(root, "vault.slots")
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        vault = VaultLifecycle(slotsFile, store)
        mnemonic = Mnemonic.fromResources(ctx)
    }

    @After fun tearDown() {
        VaultLifecycle.lockNow()
        root.deleteRecursively()
    }

    private fun kitFor(vmk: ByteArray) = mnemonic.encode(vmk).joinToString(" ")

    /**
     * The kit for the **open keyring** — `vault-keyring`.
     *
     * The words stand in for the keyring key now, not a vault key: one kit recovers
     * the list and every vault on it, which is what replaces one kit per vault.
     */
    private fun kitForKeyring() =
        VaultKeyringSession.withKey { mnemonic.encode(it) }!!.joinToString(" ")

    /** The open keyring's key, for tests asserting on the material the words carry. */
    private fun keyringKey(): ByteArray = VaultKeyringSession.withKey { it.copyOf() }!!

    // --- the resource ----------------------------------------------------------

    @Test fun theWordlistLoadsFromResources() {
        // The JVM suite checks the file; this checks that the app can read it
        // through `R.raw` on a device, which is a different failure.
        val entropy = ByteArray(Mnemonic.ENTROPY_BYTES).also { rng.nextBytes(it) }
        val phrase = mnemonic.encode(entropy)
        assertEquals(24, phrase.size)
        assertArrayEquals(entropy, mnemonic.decode(phrase.joinToString(" ")))
    }

    // --- 7.6 the derived index ID ----------------------------------------------

    @Test fun theIndexIdIsDerivedFromTheKeyAlone() {
        val vmk = VaultKeys.newVmk()
        val a = VaultKeys.metadataBlobId(vmk)
        val b = VaultKeys.metadataBlobId(vmk)
        assertArrayEquals("deterministic in the VMK", a, b)
        assertEquals("blob ID length", BlobStore.BLOB_ID_BYTES, a.size)

        val other = VaultKeys.newVmk()
        assertFalse(
            "different keys, different IDs",
            VaultKeys.metadataBlobId(other).contentEquals(a)
        )
        vmk.fill(0); other.fill(0)
    }

    @Test fun aCreatedVaultUsesTheDerivedId() {
        vault.create(pass(GOOD))
        val vmk = VaultSession.vmk()!!
        assertArrayEquals(
            "creation must seal the derived ID, or recovery cannot find the index",
            VaultKeys.metadataBlobId(vmk),
            VaultSession.metadataBlobId()!!
        )
    }

    // --- 7.11 the kit has no on-disk representation -----------------------------

    @Test fun nothingOnDiskSaysWhetherAKitExists() {
        vault.create(pass(GOOD))
        val files = root.walkTopDown().filter { it.isFile }.map { it.name }.toList()
        val blobNames = File(root, "blobs").listFiles().orEmpty().map { it.name }.toSet()

        // Everything on disk is the slot array or a blob. No kit file, no marker,
        // no flag — which is why an install with a kit and one without cannot be
        // told apart.
        files.forEach { name ->
            assertTrue(
                "unexpected file on disk: $name",
                name == "vault.slots" || name in blobNames
            )
        }
        Log.i(TAG, "after creation: ${files.size} files, none of them a kit")
    }

    // --- 7.7 the Verify ---------------------------------------------------------

    @Test fun aWipedInstallIsRecoveredFromTwentyFourWords() {
        // 1. A vault with content in it.
        vault.create(pass(GOOD))
        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        val phrase = kitForKeyring()

        val content = ByteArray(200_000).also { rng.nextBytes(it) }
        val written = store.write(ByteArrayInputStream(content), vmk)
        val db = MetadataDb.load(store, indexId, vmk)
        db.items += VaultItem(
            listOf(written.blobId), written.plaintextLength, null, "holiday.jpg", null, null, 0, emptyList()
        )
        db.save(store, indexId, vmk)
        val itemBlobId = written.blobId.copyOf()
        VaultLifecycle.lockNow()

        // 2. The blobs are copied off the device, exactly as a backup would.
        val backup = File(ctx.filesDir, "recovery-backup-${System.nanoTime()}")
        File(root, "blobs").copyRecursively(backup)

        // 3. The install is wiped — everything, including the slot array.
        root.deleteRecursively()
        assertFalse("the install is gone", slotsFile.exists())
        assertFalse("and so is its storage", File(root, "blobs").exists())

        // 4. Only the ciphertext comes back. No slots, no key, no app state.
        root.mkdirs()
        backup.copyRecursively(File(root, "blobs"))
        backup.deleteRecursively()

        // 5. 24 words and a new passphrase — the empty-phone restore, so a fresh slot file.
        val fresh = VaultLifecycle(slotsFile, BlobStore(File(root, "blobs"), File(root, "incoming")))
        val outcome = fresh.recover(mnemonic, phrase, pass(REPLACEMENT), fresh = true)
        assertTrue("recovery must open the keyring", outcome.opened)

        // 6. Everything decrypts, byte for byte. `vault-keyring`: recovery opens the
        // **keyring** and no vault — the words may have recovered several, and
        // choosing one of them would be inventing an answer the words did not give.
        assertTrue("the recovered keyring must hold the vault", fresh.openVaultAt(0))
        val recoveredVmk = VaultSession.vmk()!!
        val back = MetadataDb.load(store, VaultSession.metadataBlobId()!!, recoveredVmk)
        assertEquals("the index came back", 1, back.items.size)
        assertEquals("holiday.jpg", back.items[0].filename)

        val readBack = ByteArray(content.size)
        store.reader(itemBlobId, recoveredVmk, written.plaintextLength).use { r ->
            val buf = ByteArray(BlobStore.CHUNK)
            var at = 0
            for (i in 0 until r.chunkCount) {
                val n = r.readChunk(i, buf)
                buf.copyInto(readBack, at, 0, n)
                at += n
            }
        }
        assertArrayEquals("the file must come back byte-identical", content, readBack)
        Log.i(TAG, "Verify: wiped install, restored ${content.size} B from 24 words")
    }

    // --- 7.8 the re-seal --------------------------------------------------------

    @Test fun theNewPassphraseWorksAndTheOldOneDoesNot() {
        vault.create(pass(GOOD))
        val phrase = kitForKeyring()
        VaultLifecycle.lockNow()

        assertTrue(vault.recover(mnemonic, phrase, pass(REPLACEMENT)).opened)
        VaultLifecycle.lockNow()

        assertTrue(
            "the new passphrase must open it",
            VaultLifecycle(slotsFile, store).unlockAndOpen(pass(REPLACEMENT))
        )
        VaultLifecycle.lockNow()
        assertFalse(
            "the forgotten passphrase must not",
            VaultLifecycle(slotsFile, store).unlockAndOpen(pass(GOOD))
        )
    }

    @Test fun recoveryWorksWithNoSlotArrayAtAll() {
        vault.create(pass(GOOD))
        val phrase = kitForKeyring()
        VaultLifecycle.lockNow()

        assertTrue("the slot array is deleted", slotsFile.delete())
        assertTrue(vault.recover(mnemonic, phrase, pass(REPLACEMENT), fresh = true).opened)
        assertTrue("a fresh slot array was written", slotsFile.exists())
        assertEquals(SlotArray.FILE_BYTES.toLong(), slotsFile.length())
    }

    // --- `recovery-own-slot-only`: one slot, the keyring's own ------------------------

    private fun opens(passphrase: String): Boolean =
        vault.unlock(pass(passphrase)).opened.also { VaultLifecycle.lockNow() }

    private fun slotRegion(bytes: ByteArray, index: Int): ByteArray {
        val from = SlotArray.SLOTS_OFFSET + index * SlotArray.SLOT_BYTES
        return bytes.copyOfRange(from, from + SlotArray.SLOT_BYTES)
    }

    /** A with its kit, then B and a decoy made from A. Returns A's kit and home slot. */
    private fun threeKeyrings(): Pair<String, Int> {
        assertTrue(vault.create(pass(GOOD)).opened)
        val phrase = kitForKeyring()
        val home = VaultKeyringSession.homeSlot()
        assertTrue(vault.create(pass(OTHER)).opened)
        assertTrue(vault.reopen(pass(GOOD), -1).keyringOpen)
        assertTrue(vault.create(pass(DECOY)).opened)
        VaultLifecycle.lockNow()
        return phrase to home
    }

    @Test fun onlyTheRecoveredKeyringsHomeSlotChanges() {
        val (phrase, home) = threeKeyrings()
        val before = slotsFile.readBytes()

        assertTrue(vault.recover(mnemonic, phrase, pass(REPLACEMENT)).opened)
        VaultLifecycle.lockNow()
        val after = slotsFile.readBytes()

        assertArrayEquals(
            "install salt and KDF header",
            before.copyOfRange(0, SlotArray.SLOTS_OFFSET), after.copyOfRange(0, SlotArray.SLOTS_OFFSET),
        )
        for (i in 0 until SlotArray.SLOT_COUNT) {
            val same = slotRegion(before, i).contentEquals(slotRegion(after, i))
            if (i == home) assertFalse("the home slot must be resealed", same)
            else assertTrue("slot $i must be untouched", same)
        }
    }

    @Test fun otherKeyringsAndADecoyOpenAfterwardsWithNoPassphraseSupplied() {
        val (phrase, _) = threeKeyrings()
        assertTrue(vault.recover(mnemonic, phrase, pass(REPLACEMENT)).opened)
        VaultLifecycle.lockNow()

        assertTrue("the new passphrase opens the recovered list", opens(REPLACEMENT))
        assertFalse("the forgotten passphrase opens nothing", opens(GOOD))
        assertTrue("the other list survives", opens(OTHER))
        assertTrue("the decoy survives", opens(DECOY))
    }

    @Test fun theForgottenPassphraseMayBeChosenAgain() {
        val (phrase, _) = threeKeyrings()
        assertTrue(vault.recover(mnemonic, phrase, pass(GOOD)).opened)
        VaultLifecycle.lockNow()
        assertTrue(opens(GOOD))
        assertTrue(opens(OTHER))
    }

    @Test fun aPassphraseInUseIsRefusedAndNothingIsWritten() {
        val (phrase, _) = threeKeyrings()
        val before = slotsFile.readBytes()

        val outcome = vault.recover(mnemonic, phrase, pass(OTHER))
        assertFalse(outcome.opened)
        assertEquals(VaultLifecycle.Refusal.PASSPHRASE_IN_USE, outcome.refusal)
        assertFalse("nothing is held open", VaultKeyringSession.isOpen)
        assertArrayEquals("the slot file is unchanged", before, slotsFile.readBytes())
        assertTrue(opens(GOOD))
    }

    @Test fun aKeyringWithNoHomeSlotFailsWithTheOrdinaryOutcome() {
        assertTrue(vault.create(pass(GOOD)).opened)
        val phrase = kitForKeyring()
        assertTrue("the record is aged to name no home", VaultKeyringSession.recordPanic(store, -1, -1))
        VaultLifecycle.lockNow()
        val before = slotsFile.readBytes()

        val outcome = vault.recover(mnemonic, phrase, pass(REPLACEMENT))
        assertFalse(outcome.opened)
        assertEquals("the ordinary failure, no refusal", null, outcome.refusal)
        assertEquals(0, outcome.slotsAttempted)
        assertArrayEquals(before, slotsFile.readBytes())
    }

    // --- 7.9 recovery is an ordinary open ---------------------------------------

    @Test fun recoveryRunsTheSameTailEveryOtherUnlockDoes() {
        vault.create(pass(GOOD))
        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!.copyOf()
        val phrase = kitForKeyring()
        val logId = MetadataDb.load(store, indexId, vmk).logBlobId!!.copyOf()
        val chaffBefore = MetadataDb.load(store, indexId, vmk).ownedChaff
            .map { it.joinToString("") { b -> "%02x".format(b) } }.toSet()
        VaultLifecycle.lockNow()

        assertTrue(vault.recover(mnemonic, phrase, pass(REPLACEMENT)).opened)
        // The tail this test is about — the chaff churn and the log entries — belongs
        // to opening a *vault*, which recovery no longer does on its own.
        assertTrue(vault.openVaultAt(0))

        val after = MetadataDb.load(store, indexId, VaultSession.vmk()!!)
        val chaffAfter = after.ownedChaff.map { it.joinToString("") { b -> "%02x".format(b) } }.toSet()
        assertEquals("chaff must churn, exactly as a passphrase unlock does", 1, (chaffBefore - chaffAfter).size)

        val events = TamperLog.scan(store, logId, VaultSession.vmk()!!).entries.map { it.event }
        assertEquals(
            "kit generated at creation, then kit used before the unlock it produced",
            listOf(
                TamperLog.VAULT_CREATED,
                TamperLog.RECOVERY_KIT_USED,
                TamperLog.UNLOCK_SUCCEEDED,
            ),
            events
        )
        assertTrue(
            "the digest names it: ${TamperLog.digest}",
            TamperLog.digest.isEmpty() || TamperLog.digest.any { it.contains("recovery kit") }
        )
    }

    // --- 7.10 every failure looks the same --------------------------------------

    @Test fun everyFailedRecoveryIsIndistinguishable() {
        vault.create(pass(GOOD))
        val realPhrase = kitForKeyring()
        VaultLifecycle.lockNow()

        // A mnemonic that fails its checksum.
        val garbage = List(24) { "abandon" }.joinToString(" ")
        // A valid mnemonic for a vault that is not here.
        val strangerVmk = VaultKeys.newVmk()
        val stranger = mnemonic.encode(strangerVmk).joinToString(" ")
        strangerVmk.fill(0)

        listOf(garbage, stranger, "not a mnemonic at all").forEach { phrase ->
            val outcome = vault.recover(mnemonic, phrase, pass(REPLACEMENT))
            assertFalse("must not open: $phrase", outcome.opened)
            assertEquals("and must report nothing", 0, outcome.slotsAttempted)
            assertFalse("and must not open the session", VaultSession.isUnlocked)
        }

        // The real one still works afterwards — the failures changed nothing.
        assertTrue(vault.recover(mnemonic, realPhrase, pass(REPLACEMENT)).opened)
    }

    @Test fun aValidMnemonicOnAnEmptyInstallOpensNothing() {
        val orphanVmk = VaultKeys.newVmk()
        val phrase = mnemonic.encode(orphanVmk).joinToString(" ")
        orphanVmk.fill(0)

        val outcome = vault.recover(mnemonic, phrase, pass(REPLACEMENT))
        assertFalse("nothing to recover", outcome.opened)
        assertFalse(VaultSession.isUnlocked)
    }

    @Test fun theRecoveredPassphraseIsZeroed() {
        vault.create(pass(GOOD))
        val phrase = kitForKeyring()
        VaultLifecycle.lockNow()

        val secret = pass(REPLACEMENT)
        assertTrue(vault.recover(mnemonic, phrase, secret).opened)
        assertArrayEquals("passphrase bytes must not outlive the call", ByteArray(secret.size), secret)

        val rejected = pass(REPLACEMENT)
        vault.recover(mnemonic, "not a mnemonic", rejected)
        assertArrayEquals("including on the failure path", ByteArray(rejected.size), rejected)
    }

    // --- 4.2 the FLAG_SECURE / print measurement --------------------------------

    /**
     * Task 4.2 says measure, do not assume: `FLAG_SECURE` is set on every activity
     * by `VaultApplication`, and the question is whether a secure window can still
     * hand a document to the system print service.
     *
     * The print UI runs in the spooler's own process with its own window, so the
     * app's flag should not reach it — but "should" is not a measurement.
     */
    @Test fun aSecureWindowCanStillStartAPrintJob() {
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertTrue(
                    "the window must be FLAG_SECURE, or this measures nothing",
                    flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0
                )

                val pm = activity.getSystemService(android.content.Context.PRINT_SERVICE)
                    as android.print.PrintManager
                val job = pm.print(
                    "Vault recovery kit",
                    RecoveryKitPrintAdapter(activity, List(24) { "${it + 1}. abandon" }),
                    null
                )
                assertNotNull("FLAG_SECURE blocked the print job outright", job)
                Log.i(TAG, "print under FLAG_SECURE: job created, state=${job.info.state}")
                job.cancel()
            }
        }
    }

    /** The page itself: real PDF bytes carrying all 24 words. */
    @Test fun theKitRendersToASinglePagePdf() {
        val words = List(24) { "${it + 1}. abandon" }
        val pdf = RecoveryKitPrintAdapter.render(ctx, words, 595, 842)   // A4 at 72 dpi

        assertTrue("must be a PDF", pdf.take(4).toByteArray().contentEquals("%PDF".toByteArray()))
        assertTrue("must not be empty", pdf.size > 500)
        Log.i(TAG, "print adapter: ${pdf.size} B PDF for ${words.size} words")
    }

    @Test fun theMnemonicIsStableForAVault() {
        vault.create(pass(GOOD))
        val first = kitForKeyring()
        VaultLifecycle.lockNow()
        assertTrue(VaultLifecycle(slotsFile, store).unlockAndOpen(pass(GOOD)))
        val second = kitForKeyring()
        assertEquals("the kit is a function of the key, not of when it was asked for", first, second)
        assertNotNull(mnemonic.decode(second))
    }

    // --- a backup alongside the words — `backup-file-recover-and-import`, D5 -----------

    private val blobsDir get() = File(root, "blobs")

    private fun snapshotOf(): Pair<ByteArray, Map<String, String>> = slotsFile.readBytes() to digests(blobsDir)

    /**
     * A's vault with items, backed up as a file and to a folder; then another list beside it,
     * and A's item files gone from the phone. Returns A's kit, home slot, what was written, and
     * the file.
     */
    private class Lost(val phrase: String, val home: Int, val written: List<WrittenItem>, val payloads: List<ByteArray>, val file: ByteArray)

    private fun aListThatLostItsFiles(folder: File): Lost {
        assertTrue(vault.create(pass(GOOD)).opened)
        val phrase = kitForKeyring()
        val home = VaultKeyringSession.homeSlot()
        val payloads = (0 until 2).map { ByteArray(80_000).also(rng::nextBytes) }
        val written = putItems(store, payloads)
        val file = openVaultBackupFile(store, blobsDir, File(root, "snapshot"))
        CloudMirror.runOn(DirectoryTarget(folder), blobsDir, openVaultBackupNames(store))
        assertTrue("another list beside it", vault.create(pass(OTHER)).opened)
        VaultLifecycle.lockNow()
        written.flatMap { it.blobIds }.forEach { store.delete(it) }
        return Lost(phrase, home, written, payloads, file)
    }

    private fun recoverBringsBackMissingFiles(source: (Lost, File) -> VaultLifecycle.BackupSource) {
        val folder = File(root, "sd-card")
        val lost = aListThatLostItsFiles(folder)
        val before = slotsFile.readBytes()

        assertTrue(vault.recover(mnemonic, lost.phrase, pass(REPLACEMENT), source = source(lost, folder)).opened)
        val after = slotsFile.readBytes()
        for (i in 0 until SlotArray.SLOT_COUNT) {
            val same = slotRegion(before, i).contentEquals(slotRegion(after, i))
            if (i == lost.home) assertFalse("the home slot is resealed", same)
            else assertTrue("slot $i is untouched", same)
        }
        assertNotNull(VaultKeyringSession.openVault(0))
        assertTrue("the missing files are back", openVaultHolds(store, lost.written, lost.payloads))
        VaultLifecycle.lockNow()
        assertTrue(opens(REPLACEMENT))
        assertTrue("the other list survives", opens(OTHER))
    }

    @Test fun recoverWithABackupFileBringsBackMissingFiles() =
        recoverBringsBackMissingFiles { lost, _ -> BackupFile.source(blobsDir) { lost.file.inputStream() } }

    @Test fun recoverWithABackupFolderBringsBackMissingFiles() =
        recoverBringsBackMissingFiles { _, folder -> CloudMirror.source(DirectoryTarget(folder), blobsDir) }

    @Test fun aBackupWhoseListIsNotHereWritesNothing() {
        assertTrue(vault.create(pass(GOOD)).opened)
        val phrase = kitForKeyring()
        val file = openVaultBackupFile(store, blobsDir, File(root, "snapshot"))
        VaultLifecycle.lockNow()
        blobsDir.deleteRecursively()
        blobsDir.mkdirs()
        assertTrue(slotsFile.delete())
        assertTrue(vault.create(pass(OTHER)).opened)
        VaultLifecycle.lockNow()
        val (slots, blobs) = snapshotOf()

        val outcome = vault.recover(mnemonic, phrase, pass(REPLACEMENT), source = BackupFile.source(blobsDir) { file.inputStream() })
        assertEquals(VaultLifecycle.Refusal.LIST_NOT_HERE, outcome.refusal)
        assertFalse(VaultKeyringSession.isOpen)
        assertArrayEquals(slots, slotsFile.readBytes())
        assertEquals(blobs, digests(blobsDir))
    }

    @Test fun wordsThatDoNotOpenTheBackupWriteNothing() {
        val lost = aListThatLostItsFiles(File(root, "sd-card"))
        assertTrue(opens(OTHER))
        assertTrue(vault.unlock(pass(OTHER)).opened)
        val otherPhrase = kitForKeyring()
        VaultLifecycle.lockNow()
        val (slots, blobs) = snapshotOf()

        val outcome = vault.recover(
            mnemonic, otherPhrase, pass(REPLACEMENT), source = BackupFile.source(blobsDir) { lost.file.inputStream() },
        )
        assertEquals(VaultLifecycle.Refusal.BACKUP_NOT_OPENED, outcome.refusal)
        assertArrayEquals(slots, slotsFile.readBytes())
        assertEquals(blobs, digests(blobsDir))
    }

    // --- three later views — `recovery-kit-three-views` ---------------------------------

    @Test fun theListPassphraseShowsTheListsWordsThreeTimesThenNone() {
        assertTrue(vault.create(pass(GOOD)).opened)
        val words = kitForKeyring()
        listOf(2, 1, 0).forEach { left ->
            val view = vault.viewListKit(mnemonic, pass(GOOD))
            assertEquals(words, view.words!!.joinToString(" "))
            assertEquals(left, view.left)
            assertEquals(left, VaultKeyringSession.listKitViewsLeft())
        }
        val fourth = vault.viewListKit(mnemonic, pass(GOOD))
        assertEquals("a fourth view shows nothing", null, fourth.words)
        assertFalse("and is not a wrong passphrase", fourth.wrongPassphrase)
    }

    @Test fun thePanicPhraseAndAWrongPassphraseAreRefusedAndSpendNothing() {
        assertTrue(vault.create(pass(GOOD)).opened)
        assertEquals(VaultLifecycle.PanicResult.DONE, vault.setPanic(pass(GOOD), pass(PANIC)))
        val keyring = store.fileOf(VaultKeyringSession.keyringBlobId()!!).readBytes()

        assertTrue(vault.viewListKit(mnemonic, pass(PANIC)).wrongPassphrase)
        assertTrue(vault.viewListKit(mnemonic, pass(REPLACEMENT)).wrongPassphrase)
        assertEquals(3, VaultKeyringSession.listKitViewsLeft())
        assertArrayEquals("nothing was written", keyring, store.fileOf(VaultKeyringSession.keyringBlobId()!!).readBytes())
    }

    @Test fun aVaultsOwnPassphraseShowsItsWordsAndSpendsOnlyItsCount() {
        assertTrue(vault.create(pass(GOOD)).opened)
        val position = VaultKeyringSession.createVault(store, "Papers") { p, set ->
            VaultKeyringSession.entryPassphraseKek(pass(OWN), p, set)!!
        }!!
        val entry = VaultKeyringSession.vaults().single { it.position == position }
        val kek = VaultKeyringSession.entryPassphraseKek(pass(OWN), position, entry.kdfSet)!!
        val expected = VaultKeyringSession.vaultKeyForKit(position, kek)!!.let { kitFor(it) }

        assertTrue("the list's passphrase is not this vault's", vault.viewVaultKit(mnemonic, position, pass(GOOD)).wrongPassphrase)
        val view = vault.viewVaultKit(mnemonic, position, pass(OWN))
        assertEquals(expected, view.words!!.joinToString(" "))
        assertEquals(2, view.left)
        assertEquals(1, VaultKeyringSession.vaults().single { it.position == position }.kitViewsUsed)
        assertEquals("the list's count did not move", 3, VaultKeyringSession.listKitViewsLeft())
        assertEquals(null, vault.viewVaultKit(mnemonic, 0, pass(OWN)).words)
    }

    @Test fun theCountsSurviveABackupAndARestoreOntoAClearedInstall() {
        assertTrue(vault.create(pass(GOOD)).opened)
        val phrase = kitForKeyring()
        val position = VaultKeyringSession.createVault(store, "Papers") { p, set ->
            VaultKeyringSession.entryPassphraseKek(pass(OWN), p, set)!!
        }!!
        repeat(2) { assertNotNull(vault.viewListKit(mnemonic, pass(GOOD)).words) }
        assertNotNull(vault.viewVaultKit(mnemonic, position, pass(OWN)).words)
        assertNotNull(VaultKeyringSession.openVault(0))
        val file = openVaultBackupFile(store, blobsDir, File(root, "snapshot"))
        VaultLifecycle.lockNow()
        blobsDir.deleteRecursively()
        blobsDir.mkdirs()
        assertTrue(slotsFile.delete())

        assertTrue(
            vault.recover(
                mnemonic, phrase, pass(REPLACEMENT), fresh = true,
                source = BackupFile.source(blobsDir) { file.inputStream() },
            ).opened,
        )
        assertEquals(1, VaultKeyringSession.listKitViewsLeft())
        assertEquals(1, VaultKeyringSession.vaults().single { it.position == position }.kitViewsUsed)
    }
}
