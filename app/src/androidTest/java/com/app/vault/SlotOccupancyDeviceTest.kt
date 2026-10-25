package com.app.vault

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
import java.io.File

/**
 * A keyring is never destroyed by the creation of another — `slot-occupancy-map`.
 *
 * **The defect this suite exists for.** `create` chose its slot uniformly from the
 * slots no supplied passphrase opened, and a slot holding a keyring is
 * indistinguishable from an unused one, so it was in that set and `SlotArray.seal`
 * overwrote it. The overwritten keyring's key existed nowhere else. Reaching it took
 * no exotic sequence: make a decoy, later add a keyring without typing the decoy's
 * passphrase, and the decoy was gone. [aDecoyIsNotDestroyedByALaterCreation] is that
 * sequence and nothing more.
 *
 * What replaces the gamble is a record **inside the sealed keyring** of the slots it
 * knows are occupied. Everything below is a property of that record: it is inherited,
 * it is taught, it is rebuilt by a full-array rewrite, it refuses rather than guesses
 * where it cannot be complete, and nothing outside the seal can read it.
 *
 * Instrumented because every line of it is a real seal into a real slot array under a
 * real Argon2id derivation. The byte's own layout is `KeyringOccupancyTest`, on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class SlotOccupancyDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var slotsFile: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle

    private companion object {
        const val A = "alpha-keyring-passphrase"
        const val B = "bravo-keyring-passphrase"
        const val C = "charlie-keyring-passphrase"
        const val D = "delta-keyring-passphrase"
        const val DECOY = "the-decoy-keyring-passphrase"
        const val NEW = "a-brand-new-passphrase"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        lockKeyringAndVault()
        root = File(ctx.filesDir, "occupancy-${System.nanoTime()}").apply { mkdirs() }
        slotsFile = File(root, "vault.slots")
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        vault = VaultLifecycle(slotsFile, store)
    }

    @After
    fun tearDown() {
        lockKeyringAndVault()
        root.deleteRecursively()
    }

    // --- the shape every test below is written in --------------------------------

    /** Creates a keyring from the lock screen and locks up again. */
    private fun create(passphrase: String, preserve: List<String> = emptyList()) =
        vault.create(pass(passphrase), preserve.map { pass(it) }).also { lockKeyringAndVault() }

    /** Creates a keyring **from the one already open**, leaving the new one open. */
    private fun createFromOpen(passphrase: String, preserve: List<String> = emptyList()) =
        vault.create(pass(passphrase), preserve.map { pass(it) })

    /** Whether [passphrase] still opens a keyring here. The survival assertion. */
    private fun opens(passphrase: String): Boolean =
        vault.unlock(pass(passphrase)).opened.also { lockKeyringAndVault() }

    /** Opens [passphrase]'s keyring and leaves it open. */
    private fun open(passphrase: String): Boolean {
        lockKeyringAndVault()
        return vault.unlock(pass(passphrase)).opened
    }

    /** What [passphrase]'s keyring knows, as a set of slot indices. */
    private fun recordOf(passphrase: String): Set<Int> {
        assertTrue("$passphrase must open to be read", open(passphrase))
        val bits = VaultKeyringSession.knownSlots()
        lockKeyringAndVault()
        return (0 until SlotArray.SLOT_COUNT).filter { (bits shr it) and 1 == 1 }.toSet()
    }

    private fun slotOf(passphrase: String): Int {
        assertTrue("$passphrase must open to be located", open(passphrase))
        return VaultKeyringSession.openSlotIndex().also { lockKeyringAndVault() }
    }

    private fun blobFiles(): Map<String, Long> =
        File(root, "blobs").walkTopDown().filter { it.isFile }
            .associate { it.absolutePath to it.length() }

    /**
     * Every byte of every blob and of the slot array. What "unchanged" has to mean.
     *
     * A digest per file rather than the bytes: §6's install reserve is 48 chaff blobs
     * averaging 3.55 MiB, so holding two snapshots of the store in memory is a quarter
     * of a gigabyte and an `OutOfMemoryError` where a comparison was wanted.
     */
    private fun everything(): Map<String, String> =
        (File(root, "blobs").walkTopDown().filter { it.isFile }.toList() + slotsFile)
            .filter { it.exists() }
            .associate { it.absolutePath to digestOf(it) }

    private fun digestOf(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The way back from a decoy, as `MainActivity` performs it — `VaultLifecycle.reopen`
     * followed by the bit being written into the keyring the user returns to. The decoy's
     * own keyring never carries it, which is why this cannot be written any earlier.
     */
    private fun buildDecoyFrom(owner: String, decoy: String) {
        assertTrue("the decoy must create", createFromOpen(decoy).opened)
        assertTrue("the owner must come back", vault.reopen(pass(owner), 0).keyringOpen)
        assertTrue("the bit must land", VaultKeyringSession.markDecoyBuilt(store))
    }

    // --- 5.3: the property this change exists for ---------------------------------

    /**
     * Build a decoy, then create another keyring from the same keyring **without**
     * supplying the decoy's passphrase.
     *
     * This failed on the build before `slot-occupancy-map` — not every run, because
     * the choice was uniform over six remaining slots, which is exactly what made it
     * the worst kind of defect: it destroyed a keyring one time in six and looked like
     * a working feature the other five. Repeated here for that reason.
     */
    @Test
    fun aDecoyIsNotDestroyedByALaterCreation() {
        repeat(8) { run ->
            setUp()
            assertTrue("run $run: the owner must create", vault.create(pass(A)).opened)
            buildDecoyFrom(owner = A, decoy = DECOY)

            // The act. Nothing is supplied, and nothing may be lost by that.
            assertTrue("run $run: the third keyring must create", createFromOpen(C).opened)
            lockKeyringAndVault()

            assertTrue("run $run: the decoy must survive", opens(DECOY))
            assertTrue("run $run: the owner must survive", opens(A))
            assertTrue("run $run: and the new keyring opens", opens(C))
            root.deleteRecursively()
        }
    }

    // --- 5.4: inheritance ----------------------------------------------------------

    /**
     * A creates B, B creates C. C must never land on A's slot, and no passphrase is
     * supplied to buy that — C knows A's slot because B did, and B knew it because A
     * wrote it into B at birth.
     *
     * Twelve runs. Under the old rule C drew uniformly from the six slots that were
     * neither B's nor proven, so it would have landed on A about one run in six and
     * this would fail with probability ~0.89.
     */
    @Test
    fun aKeyringInheritsWhatItsCreatorKnew() {
        repeat(12) { run ->
            setUp()
            assertTrue("run $run: A", vault.create(pass(A)).opened)
            assertTrue("run $run: B", createFromOpen(B).opened)
            assertTrue("run $run: C", createFromOpen(C).opened)
            lockKeyringAndVault()

            assertTrue("run $run: A must survive its grandchild", opens(A))
            assertTrue("run $run: B must survive its child", opens(B))
            assertTrue("run $run: C opens", opens(C))

            val slots = setOf(slotOf(A), slotOf(B), slotOf(C))
            assertEquals("run $run: three keyrings, three slots", 3, slots.size)
            assertEquals("run $run: C knows all three", slots, recordOf(C))
            root.deleteRecursively()
        }
    }

    @Test
    fun theCreatorLearnsTheSlotItJustFilled() {
        assertTrue(vault.create(pass(A)).opened)
        val aSlot = VaultKeyringSession.openSlotIndex()
        assertEquals("a first run records its own slot and no other", setOf(aSlot), recordOf(A))

        assertTrue(open(A))
        assertTrue(createFromOpen(B).opened)
        val bSlot = VaultKeyringSession.openSlotIndex()
        lockKeyringAndVault()

        assertEquals("A's record gains B's slot", setOf(aSlot, bSlot), recordOf(A))
        assertEquals("and B is born knowing both", setOf(aSlot, bSlot), recordOf(B))
    }

    /** `recovery-own-slot-only` 1.2: home is recorded at creation, not only by a panic phrase. */
    @Test
    fun aCreatedKeyringAndADecoyEachRecordTheirOwnSlotAsHome() {
        assertTrue(vault.create(pass(A)).opened)
        assertEquals("a first run records its slot as home", VaultKeyringSession.openSlotIndex(), VaultKeyringSession.homeSlot())
        assertEquals("with no panic phrase set", -1, VaultKeyringSession.panicSlot())

        assertTrue(createFromOpen(DECOY).opened)
        assertEquals("a decoy records its own slot as home", VaultKeyringSession.openSlotIndex(), VaultKeyringSession.homeSlot())
        assertEquals(-1, VaultKeyringSession.panicSlot())
        lockKeyringAndVault()

        listOf(A, DECOY).forEach {
            assertTrue(open(it))
            assertEquals("$it's saved record names its slot", VaultKeyringSession.openSlotIndex(), VaultKeyringSession.homeSlot())
            lockKeyringAndVault()
        }
    }

    // --- 5.5: teaching --------------------------------------------------------------

    /**
     * B's passphrase is supplied once, while C is being created from A. B is not the
     * keyring doing the creating and is not the one being created — it is merely
     * named, and naming it used only to spare it *this* act.
     *
     * It now teaches: B is written the slot C landed on, so when D is later created
     * from B with nothing supplied, C is not a candidate. Without that, B's record
     * would hold only what it inherited at birth — A's slot and its own — and D would
     * draw C's slot about one time in five.
     */
    @Test
    fun aPassphraseSuppliedOnceProtectsThatKeyringAfterwards() {
        repeat(10) { run ->
            setUp()
            assertTrue("run $run: A", vault.create(pass(A)).opened)
            assertTrue("run $run: B", createFromOpen(B).opened)
            assertTrue("run $run: back to A", vault.reopen(pass(A), 0).keyringOpen)

            // C is created from A, and B is named — the only thing the user typed.
            assertTrue("run $run: C", createFromOpen(C, preserve = listOf(B)).opened)
            lockKeyringAndVault()

            val cSlot = slotOf(C)
            assertTrue("run $run: B was taught C's slot", cSlot in recordOf(B))

            // D from B, with nothing supplied at all.
            assertTrue("run $run: open B", open(B))
            assertTrue("run $run: D", createFromOpen(D).opened)
            lockKeyringAndVault()

            assertTrue("run $run: C must survive D", opens(C))
            listOf(A, B, D).forEach { assertTrue("run $run: $it must survive", opens(it)) }
            root.deleteRecursively()
        }
    }

    @Test
    fun aSuppliedPassphraseThatOpensNothingTeachesNothingAndIsSilent() {
        assertTrue(vault.create(pass(A)).opened)
        val before = recordOf(A)

        assertTrue(open(A))
        val outcome = createFromOpen(B, preserve = listOf("opens-nothing-at-all-here"))
        lockKeyringAndVault()

        assertTrue("an entry that opens nothing must not refuse the act", outcome.opened)
        assertNull("and must carry no refusal", outcome.refusal)
        assertEquals(
            "A's record gains B's slot and nothing the stranger claimed",
            before + slotOf(B), recordOf(A),
        )
    }

    // --- 5.6: no room ----------------------------------------------------------------

    /**
     * The record and the proven set account for every slot **between them**, which is
     * the case worth asserting: either alone is the easy one.
     *
     * A is the chain's root, so its record holds its own slot and B's — it learned B by
     * creating it and learned nothing since, because teaching reaches only the keyrings
     * a passphrase named. The other six are supplied. Two plus six is eight.
     */
    @Test
    fun thereIsNoRoomAndNothingIsSealed() {
        val chain = listOf(
            A, B, C, D,
            "echo-keyring-passphrase", "foxtrot-keyring-passphrase",
            "golf-keyring-passphrase", "hotel-keyring-passphrase",
        )
        assertTrue("the root", vault.create(pass(chain.first())).opened)
        chain.drop(1).forEach { assertTrue("$it", createFromOpen(it).opened) }
        lockKeyringAndVault()

        assertEquals("eight keyrings, eight slots", 8, chain.map { slotOf(it) }.toSet().size)
        assertEquals("A knows its own slot and its child's, and no more", 2, recordOf(A).size)

        assertTrue(open(A))
        val before = everything()
        val outcome = createFromOpen(NEW, preserve = chain.drop(2))
        lockKeyringAndVault()

        assertFalse("there is no room", outcome.opened)
        assertEquals(VaultLifecycle.Refusal.NO_ROOM, outcome.refusal)
        assertEquals("nothing was sealed and nothing was written", before, everything())
        chain.forEach { assertTrue("$it must survive a refusal", opens(it)) }
    }

    // --- 5.7: a refusal and a failure change nothing ------------------------------------

    @Test
    fun aRefusedOrFailedCreationLeavesEveryRecordByteForByteUnchanged() {
        assertTrue(vault.create(pass(A)).opened)
        assertTrue(createFromOpen(B).opened)
        assertTrue(vault.reopen(pass(A), 0).keyringOpen)

        val before = everything()
        val records = listOf(A, B).associateWith { recordOf(it) }

        // Refused, after deriving: this costs one derivation per supplied passphrase
        // plus the one that would have sealed, and only then discovers the collision.
        assertTrue(open(A))
        val inUse = createFromOpen(B)
        lockKeyringAndVault()
        assertEquals(VaultLifecycle.Refusal.PASSPHRASE_IN_USE, inUse.refusal)
        assertEquals("a refusal after deriving writes nothing", before, everything())

        // Failed while writing: the keyring blob is durable before the slot names it,
        // so a store that will not take it fails ahead of the seal — and ahead of every
        // record write, which is the ordering this asserts.
        val blobs = File(root, "blobs")
        assertTrue("the store must be made unwritable for this leg", blobs.setWritable(false))
        try {
            assertTrue(open(A))
            runCatching { createFromOpen(NEW) }
            lockKeyringAndVault()
        } finally {
            blobs.setWritable(true)
        }
        assertEquals("a failure mid-write writes nothing either", before, everything())

        assertEquals(
            "and every record is what it was",
            records, listOf(A, B).associateWith { recordOf(it) },
        )
    }

    // --- 5.8: recovery keeps the record; an empty-phone restore replaces it ------------

    /** `recovery-own-slot-only`. Nothing the record names moved, so nothing in it changes. */
    @Test
    fun recoveryDoesNotRebuildRecords() {
        val mnemonic = Mnemonic.fromResources(ctx)

        assertTrue(vault.create(pass(A)).opened)
        val words = VaultKeyringSession.withKey { mnemonic.encode(it) }!!.joinToString(" ")
        assertTrue(createFromOpen(B).opened)
        assertTrue(vault.reopen(pass(A), 0).keyringOpen)
        assertTrue(vault.setPanic(pass(A), pass(DECOY)) == VaultLifecycle.PanicResult.DONE)
        val known = VaultKeyringSession.knownSlots()
        val home = VaultKeyringSession.homeSlot()
        val panic = VaultKeyringSession.panicSlot()
        lockKeyringAndVault()

        assertTrue(vault.recover(mnemonic, words, pass(NEW)).opened)
        assertEquals("known slots unchanged", known, VaultKeyringSession.knownSlots())
        assertEquals("home slot unchanged", home, VaultKeyringSession.homeSlot())
        assertEquals("panic slot unchanged", panic, VaultKeyringSession.panicSlot())
        lockKeyringAndVault()

        assertTrue(open(NEW))
        assertEquals("and saved that way", known, VaultKeyringSession.knownSlots())
        assertEquals(home, VaultKeyringSession.homeSlot())
        assertEquals(panic, VaultKeyringSession.panicSlot())
        lockKeyringAndVault()
        assertTrue("B still opens", opens(B))
    }

    /**
     * A keyring that knew three slots and a panic slot, backed up and restored onto an
     * empty phone, knows only the one slot it was sealed into here.
     */
    @Test
    fun aRestoredKeyringKnowsOnlyItsSlotHere() {
        val mnemonic = Mnemonic.fromResources(ctx)

        assertTrue(vault.create(pass(A)).opened)
        val words = VaultKeyringSession.withKey { mnemonic.encode(it) }!!.joinToString(" ")
        assertTrue(createFromOpen(B).opened)
        assertTrue(vault.reopen(pass(A), 0).keyringOpen)
        assertTrue(createFromOpen(C).opened)
        assertTrue(vault.reopen(pass(A), 0).keyringOpen)
        assertTrue(vault.setPanic(pass(A), pass(DECOY)) == VaultLifecycle.PanicResult.DONE)
        assertEquals("three known slots and the panic slot", 4, Integer.bitCount(VaultKeyringSession.knownSlots()))
        lockKeyringAndVault()

        // The empty phone: the blobs come back, no slot file does.
        val phone = File(ctx.filesDir, "occupancy-phone-${System.nanoTime()}").apply { mkdirs() }
        try {
            File(root, "blobs").copyRecursively(File(phone, "blobs"))
            val phoneSlots = File(phone, "vault.slots")
            val restored = VaultLifecycle(phoneSlots, BlobStore(File(phone, "blobs"), File(phone, "incoming")))

            assertTrue(restored.recover(mnemonic, words, pass(NEW), fresh = true).opened)
            val slot = VaultKeyringSession.openSlotIndex()
            assertEquals("home is the slot sealed here", slot, VaultKeyringSession.homeSlot())
            assertEquals("the one known slot", 1 shl slot, VaultKeyringSession.knownSlots())
            assertEquals("no panic slot", -1, VaultKeyringSession.panicSlot())
            assertFalse("no temp file left", File(phone, "vault.slots.tmp").exists())
            lockKeyringAndVault()

            assertTrue(restored.unlock(pass(NEW)).opened)
            assertEquals("and saved that way", 1 shl VaultKeyringSession.openSlotIndex(), VaultKeyringSession.knownSlots())
            assertEquals(-1, VaultKeyringSession.panicSlot())
            lockKeyringAndVault()
        } finally {
            phone.deleteRecursively()
        }
    }

    // --- 5.9: the older-install rule -------------------------------------------------

    /**
     * Rewrites the open keyring's blob as a **version 2** block: the version this build
     * read before the record existed, with the record byte left as the filler an older
     * build would have written there.
     *
     * The entries, the decoy bit and every sealed key go through untouched, so what
     * comes back is exactly what that build would have stored.
     */
    private fun ageTheOpenKeyringToVersionTwo() {
        val entries = arrayOfNulls<VaultKeyring.Entry>(VaultKeyring.CAPACITY)
        VaultKeyringSession.vaults().forEach { entries[it.position] = it }
        val aged = VaultKeyring.Keyring(entries, VaultKeyringSession.hasDecoy(), knownSlots = 0)

        val bytes = VaultKeyring.serialise(aged)
        bytes[0] = ((bytes[0].toInt() and 0x80) or VaultKeyring.VERSION_NO_KNOWN_SLOTS).toByte()
        bytes[187] = 0xC3.toByte()   // filler, where a version 3 block keeps the record

        val id = VaultKeyringSession.keyringBlobId()!!
        VaultKeyringSession.withKey { key ->
            store.writeTo(
                id, java.io.ByteArrayInputStream(bytes), key,
                resume = false, context = VaultKeys.BlobContext.KEYRING,
            )
        }
        lockKeyringAndVault()
    }

    @Test
    fun anOlderKeyringRecordingADecoyIsRefusedUntilItIsTold() {
        assertTrue(vault.create(pass(A)).opened)
        buildDecoyFrom(owner = A, decoy = DECOY)
        ageTheOpenKeyringToVersionTwo()

        assertTrue("an aged keyring still opens", open(A))
        assertTrue("and still knows it built a decoy", VaultKeyringSession.hasDecoy())
        assertEquals("but names no slot but its own", setOf(slotOf(A)), recordOf(A))

        // Nothing supplied: refused rather than gambled.
        assertTrue(open(A))
        val before = everything()
        val refused = createFromOpen(C)
        lockKeyringAndVault()
        assertFalse(refused.opened)
        assertEquals(VaultLifecycle.Refusal.DECOY_PASSPHRASE_NEEDED, refused.refusal)
        assertEquals("and nothing was sealed", before, everything())
        assertTrue("the decoy is untouched", opens(DECOY))

        // Supplied: the act completes, the decoy keeps working, and its slot is recorded.
        assertTrue(open(A))
        assertTrue(createFromOpen(C, preserve = listOf(DECOY)).opened)
        lockKeyringAndVault()
        assertTrue("the decoy survives the act it authorised", opens(DECOY))
        assertTrue("the decoy's slot is now recorded", slotOf(DECOY) in recordOf(A))

        // And it is never asked for again.
        assertTrue(open(A))
        val after = createFromOpen(D)
        lockKeyringAndVault()
        assertTrue("the next creation is not refused", after.opened)
        assertNull(after.refusal)
        listOf(A, C, D, DECOY).forEach { assertTrue("$it must still open", opens(it)) }
    }

    @Test
    fun anOlderKeyringWithNoDecoyIsNotRefused() {
        assertTrue(vault.create(pass(A)).opened)
        ageTheOpenKeyringToVersionTwo()

        assertTrue(open(A))
        assertFalse("no decoy was ever built from this one", VaultKeyringSession.hasDecoy())
        val outcome = createFromOpen(B)
        lockKeyringAndVault()

        assertTrue("an install that never made a decoy has nothing to be taught", outcome.opened)
        assertNull(outcome.refusal)
        assertTrue(opens(A))
        assertTrue(opens(B))
    }

    // --- 5.10: nothing on disk moved, and nothing outside the seal learned -------------

    @Test
    fun theStoredLengthAndBucketAreUnchangedAcrossTheVersionBump() {
        assertTrue(vault.create(pass(A)).opened)
        val id = VaultKeyringSession.keyringBlobId()!!
        val file = store.fileOf(id)
        val atVersionThree = file.length()

        ageTheOpenKeyringToVersionTwo()
        assertEquals(
            "a version 2 blob and a version 3 blob are the same length on disk",
            atVersionThree, file.length(),
        )

        // And a record naming every slot is the same length as one naming a single slot.
        assertTrue(open(A))
        assertTrue("a full record must write", VaultKeyringSession.recordKnownSlots(store, 0xFF))
        lockKeyringAndVault()
        assertEquals("the size bucket does not move with what is recorded", atVersionThree, file.length())
        assertTrue("and it still opens", opens(A))
    }

    @Test
    fun nothingOutsideTheSealedKeyringLearnsWhichSlotsAreOccupied() {
        assertTrue(vault.create(pass(A)).opened)
        assertTrue(createFromOpen(B).opened)
        lockKeyringAndVault()

        // Everything the app owns that is not the blob store: preferences, databases,
        // work state, and every other file in private storage.
        val outside = {
            ctx.filesDir.parentFile!!.walkTopDown()
                .filter { it.isFile && !it.absolutePath.startsWith(root.absolutePath) }
                .associate { it.absolutePath to it.length() }
        }
        val slotBytes = slotsFile.readBytes()
        val before = outside()
        val blobs = blobFiles()

        // A record write, and nothing but a record write.
        assertTrue(open(A))
        assertTrue(VaultKeyringSession.recordKnownSlots(store, 0b1010_1010))
        lockKeyringAndVault()

        assertArrayEquals("the slot array says nothing about the record", slotBytes, slotsFile.readBytes())
        assertEquals("no blob was added or resized", blobs, blobFiles())
        assertEquals("nothing outside the store was written", before, outside())
        assertTrue("and the keyring still opens", open(A))
        assertNotNull("with its vault intact", VaultKeyringSession.openVault(0))
        lockKeyringAndVault()
    }

    /**
     * `backup-file-recover-and-import`, D4.5. A list that comes back through Import is born
     * knowing what the open list knew, plus the open list's slot and its own — and with no
     * panic phrase, whose slot was on the other phone.
     */
    @Test
    fun aRestoredListKnowsWhatTheOpenListKnewPlusBothSlots() {
        val mnemonic = Mnemonic.fromResources(ctx)
        val blobs = File(root, "blobs")
        assertTrue(vault.create(pass(B)).opened)
        assertEquals(VaultLifecycle.PanicResult.DONE, vault.setPanic(pass(B), pass("bravo-panic-phrase")))
        val words = VaultKeyringSession.withKey { mnemonic.encode(it) }!!.joinToString(" ")
        val file = openVaultBackupFile(store, blobs, File(root, "snapshot"))
        lockKeyringAndVault()
        blobs.deleteRecursively()
        blobs.mkdirs()
        assertTrue(slotsFile.delete())

        create(A)
        assertTrue(open(A))
        assertTrue("C, created from A, so A knows C's slot", createFromOpen(C).opened)
        assertTrue(open(A))
        val aKnown = VaultKeyringSession.knownSlots()
        val aSlot = VaultKeyringSession.openSlotIndex()
        assertTrue(
            vault.restoreAsList(mnemonic.decode(words)!!, BackupFile.source(blobs) { file.inputStream() }, pass(NEW)).opened,
        )

        assertTrue(open(NEW))
        val home = VaultKeyringSession.homeSlot()
        assertEquals(VaultKeyringSession.openSlotIndex(), home)
        assertEquals(aKnown or (1 shl aSlot) or (1 shl home), VaultKeyringSession.knownSlots())
        assertFalse("no panic phrase", VaultKeyringSession.hasPanicPhrase())
    }
}
