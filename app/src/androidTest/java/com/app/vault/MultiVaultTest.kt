package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom

/**
 * P2-1 — several vaults in the slots that were always there.
 *
 * The unlock half needed no new code: P1-3 already derives once, attempts all
 * eight slots and reports only whether something opened. What is asserted here is
 * the creation half — that a second vault can exist at all, that it costs no
 * second install reserve, that the app declines to destroy a vault whose
 * passphrase it was given, and that it *does* destroy one it was not, which is the
 * price of an app that cannot find a vault it cannot open.
 */
@RunWith(AndroidJUnit4::class)
class MultiVaultTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var slotsFile: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "MultiVaultTest"
        const val A = "first-vault-passphrase"
        const val B = "second-vault-passphrase"
        const val C = "third-vault-passphrase"
        const val NEW = "a-brand-new-passphrase"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "multivault-${System.nanoTime()}").apply { mkdirs() }
        slotsFile = File(root, "vault.slots")
        store = BlobStore(File(root, "blobs"))
        vault = VaultLifecycle(slotsFile, store)
    }

    @After
    fun tearDown() {
        lockEverything()
        root.deleteRecursively()
    }

    private fun blobFiles() = File(root, "blobs").listFiles().orEmpty().filter { it.isFile }

    /** Creates a vault and locks up afterwards, so each step starts from the lock screen. */
    private fun create(passphrase: String, preserve: List<String>): VaultLifecycle.Outcome =
        vault.create(pass(passphrase), preserve.map { pass(it) }).also { lockEverything() }

    private fun opens(passphrase: String): Boolean =
        vault.unlockAndOpen(pass(passphrase)).also { lockEverything() }

    /**
     * Unlocks and opens the first vault on the keyring — `vault-keyring`.
     *
     * **Unlocking no longer opens a vault**, which is the change: a passphrase opens a
     * keyring and the user opens a vault from the list. Every assertion below that
     * used to read `VaultSession.vmk()` straight after an unlock goes through here.
     */
    private fun openFirstVault(passphrase: String): Boolean {
        if (!vault.unlockAndOpen(pass(passphrase))) return false
        val opened = VaultKeyringSession.openVault(0) ?: return false
        vault.open(opened)
        return true
    }

    /** Both levels, because a test that left a keyring open would leak into the next. */
    private fun lockEverything() {
        VaultLifecycle.lockNow()
        VaultKeyringSession.lock()
    }

    /** The index ID a passphrase reaches, or null. Identity without a slot index. */
    private fun indexIdFor(passphrase: String): String? {
        val opened = openFirstVault(passphrase)
        val id = VaultSession.metadataBlobId()?.joinToString("") { "%02x".format(it) }
        lockEverything()
        return if (opened) id else null
    }

    private fun addItem(name: String): ByteArray {
        val vmk = VaultSession.vmk()!!
        val id = VaultSession.metadataBlobId()!!
        val content = ByteArray(4096).also { rng.nextBytes(it) }
        val blob = store.write(ByteArrayInputStream(content), vmk)
        val db = MetadataDb.load(store, id, vmk)
        db.items += VaultItem(
            listOf(blob.blobId), blob.plaintextLength, null, name, null, null, 1, emptyList()
        )
        db.save(store, id, vmk)
        return content
    }

    // --- creation ---------------------------------------------------------------

    @Test
    fun threeVaultsShareOneInstallAndOneReserve() {
        assertTrue(create(A, emptyList()).opened)
        val salt = SlotArray(slotsFile).installSalt()
        val afterFirst = blobFiles().size
        // 48 unowned + 8 owned + the index + the log + the **keyring** — P1-8.5's
        // count plus the one blob `vault-keyring` adds per keyring. It is per keyring
        // and not per vault: a second vault on this list adds an index and a log, and
        // no second keyring.
        assertEquals(
            "first run",
            VaultLifecycle.INSTALL_CHAFF_BLOBS + VaultLifecycle.CREATION_CHAFF_BLOBS + 3,
            afterFirst,
        )

        assertTrue(create(B, listOf(A)).opened)
        val afterSecond = blobFiles().size
        // §6's unowned reserve belongs to the install. A vault created under its own
        // passphrase brings its own owned chaff, its index, its log and — since
        // `vault-keyring` — the keyring that holds it, and nothing else. Three vaults
        // on *one* keyring would add three indexes and three logs and one keyring; the
        // three here are on three keyrings because each was created from the lock
        // screen under a passphrase of its own.
        assertEquals(
            "a second vault must not write a second install reserve",
            VaultLifecycle.CREATION_CHAFF_BLOBS + 3,
            afterSecond - afterFirst,
        )

        assertTrue(create(C, listOf(A, B)).opened)

        assertEquals("file size", SlotArray.FILE_BYTES.toLong(), slotsFile.length())
        assertArrayEquals("install salt", salt, SlotArray(slotsFile).installSalt())

        val ids = listOf(A, B, C).map { indexIdFor(it) }
        assertTrue("every passphrase must open its own vault", ids.all { it != null })
        assertEquals("three vaults, three indexes", 3, ids.toSet().size)
        assertFalse("a passphrase that was never sealed opens nothing", opens("nothing-here-at-all"))
    }

    @Test
    fun eachVaultHoldsOnlyItsOwnItems() {
        create(A, emptyList())
        create(B, listOf(A))

        openFirstVault(A)
        addItem("only-in-a.jpg")
        val aVmk = VaultSession.vmk()!!.copyOf()
        val aId = VaultSession.metadataBlobId()!!.copyOf()
        lockEverything()

        openFirstVault(B)
        val bIndex = MetadataDb.load(store, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!)
        assertEquals("the second vault sees none of the first's items", 0, bIndex.items.size)
        lockEverything()

        val aIndex = MetadataDb.load(store, aId, aVmk)
        assertEquals("the first vault still holds its own", 1, aIndex.items.size)
    }

    @Test
    fun aProvenSlotIsNeverTheSlotChosen() {
        create(A, emptyList())
        // Ten creations, each naming every vault before it. If the chooser ever
        // picked a proven-occupied slot, one of the earlier passphrases would stop
        // opening — which is precisely the destruction the preserve list buys off.
        val kept = mutableListOf(A)
        repeat(6) { i ->
            val next = "vault-$i-passphrase"
            assertTrue("creation $i", create(next, kept.toList()).opened)
            kept += next
            kept.forEach { assertTrue("$it stopped opening after creating $next", opens(it)) }
        }
        assertEquals("seven vaults", 7, kept.size)
    }

    @Test
    fun aPassphraseThatAlreadyOpensAVaultIsRefused() {
        create(A, emptyList())
        val outcome = vault.create(pass(A), listOf(pass(A)))
        lockEverything()

        assertFalse("a second vault under one passphrase would be unreachable", outcome.opened)
        assertEquals(VaultLifecycle.Refusal.PASSPHRASE_IN_USE, outcome.refusal)
        // Nothing was sealed and nothing was written — the same count as after the
        // first creation, keyring included.
        assertEquals(
            VaultLifecycle.INSTALL_CHAFF_BLOBS + VaultLifecycle.CREATION_CHAFF_BLOBS + 3,
            blobFiles().size,
        )
        assertTrue("the original vault is untouched", opens(A))
    }

    @Test
    fun eightIsTheCeilingAndAnUnnamedVaultIsWhatPaysForIgnoringIt() {
        val all = mutableListOf<String>()
        repeat(SlotArray.SLOT_COUNT) { i ->
            val p = "slot-filler-$i"
            assertTrue("creation $i", create(p, all.toList()).opened)
            all += p
        }
        assertEquals(SlotArray.SLOT_COUNT, all.size)

        // Every slot proven occupied: refused, and the refusal names no vault.
        val full = vault.create(pass(NEW), all.map { pass(it) })
        lockEverything()
        assertFalse(full.opened)
        assertEquals(VaultLifecycle.Refusal.NO_ROOM, full.refusal)
        all.forEach { assertTrue("$it must survive a refusal", opens(it)) }

        // Name seven of the eight. The only slot the chooser believes is free is
        // the one holding the vault that was left out, so it is overwritten — the
        // cost the screen states in words before the user commits.
        val abandoned = all.last()
        val named = all.dropLast(1)
        assertTrue(vault.create(pass(NEW), named.map { pass(it) }).opened)
        lockEverything()

        assertFalse("the unnamed vault is gone", opens(abandoned))
        named.forEach { assertTrue("$it was named and must survive", opens(it)) }
        assertTrue("the new vault opens", opens(NEW))
    }

    // --- the open keyring is never the one written -------------------------------

    /**
     * `decoy-flow-overhaul`. The guarantee, with nothing typed to earn it.
     *
     * Before this, the open vault survived a slot seal only because the decoy flow
     * happened to require its passphrase for an unrelated authorisation gate and fed
     * it into `preserve`. That made the protection a property of two gates lining up:
     * reorder the flow, drop the gate, add a caller, and a vault disappears. So the
     * exclusion moved into `create`, which reads it from the session — and this is the
     * test that would have caught the old arrangement, because it supplies nothing.
     *
     * Repeated, because the chooser is uniform over what it believes is free: one run
     * proves nothing about a one-in-seven draw.
     */
    @Test
    fun theOpenKeyringIsNeverOverwrittenEvenWhenItsPassphraseIsNotSupplied() {
        repeat(12) { i ->
            setUp()
            assertTrue("creation $i", vault.create(pass(A)).opened)
            // A is open, and deliberately not named.
            assertTrue("a keyring must be open for this to mean anything", VaultKeyringSession.isOpen)
            assertTrue("the new vault must create", vault.create(pass(B), emptyList()).opened)
            lockEverything()

            assertTrue("run $i: the open keyring must survive being unnamed", opens(A))
            assertTrue("run $i: the new vault opens too", opens(B))
            root.deleteRecursively()
        }
    }

    /** No derivation is spent on the slot the session is already holding a key for. */
    @Test
    fun preservingTheOpenKeyringCostsNothing() {
        assertTrue(vault.create(pass(A)).opened)
        val outcome = vault.create(pass(B), emptyList())
        lockEverything()

        assertTrue(outcome.opened)
        assertEquals("one derivation, for the passphrase that seals", 1, outcome.derivations)
        assertEquals(SlotArray.SLOT_COUNT, outcome.slotsAttempted)
    }

    /**
     * The ceiling, reached one slot earlier when a keyring is open — and refused
     * rather than gambled.
     *
     * The seven named plus the one held account for all eight, so there is no slot
     * this may write. `eightIsTheCeilingAndAnUnnamedVaultIsWhatPaysForIgnoringIt`
     * asserts the other half: with nothing open, the eighth is still overwritten,
     * because a vault nobody named and nobody is holding is a vault the app cannot
     * find.
     */
    @Test
    fun theOpenKeyringCountsTowardsTheCeiling() {
        val all = mutableListOf<String>()
        repeat(SlotArray.SLOT_COUNT) { i ->
            val p = "slot-filler-$i"
            assertTrue("creation $i", create(p, all.toList()).opened)
            all += p
        }

        // Open the last one and name the other seven. Nothing is free.
        assertTrue(vault.unlockAndOpen(pass(all.last())))
        val outcome = vault.create(pass(NEW), all.dropLast(1).map { pass(it) })
        lockEverything()

        assertFalse(outcome.opened)
        assertEquals(VaultLifecycle.Refusal.NO_ROOM, outcome.refusal)
        all.forEach { assertTrue("$it must survive a refusal", opens(it)) }
    }

    // --- the switch ---------------------------------------------------------------

    @Test
    fun creatingAnotherVaultClosesTheOneBeingLeft() {
        create(A, emptyList())
        openFirstVault(A)
        val previous = VaultSession.vmk()!!
        val watched = previous.copyOf()

        // A real decoded thumbnail resident in the cache, so "the pixels go too"
        // is asserted rather than assumed.
        val png = java.io.ByteArrayOutputStream().also { out ->
            android.graphics.Bitmap
                // 256 KB decoded: `size()` counts kilobytes, so a small bitmap
                // rounds to zero and would prove nothing.
                .createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        ThumbnailCache.get(ThumbRef(ByteArray(16) { 3 }, 0, 1)) { png }
        assertTrue("the cache must hold something to prove it is cleared", ThumbnailCache.size() > 0)

        vault.create(pass(B), listOf(pass(A)))

        assertFalse("the previous VMK must not survive the switch", previous.contentEquals(watched))
        assertTrue("every byte of it is zero", previous.all { it == 0.toByte() })
        assertEquals("decrypted pixels must not survive it either", 0, ThumbnailCache.size())
        assertTrue("the new vault is what is open", VaultSession.isUnlocked)
        assertNotEquals(
            "and it is not the old one",
            watched.joinToString(""), VaultSession.vmk()!!.joinToString(""),
        )
    }

    @Test
    fun theCreatingVaultsLogSaysNothingAboutTheNewOne() {
        create(A, emptyList())
        openFirstVault(A)
        val vmk = VaultSession.vmk()!!.copyOf()
        val id = VaultSession.metadataBlobId()!!.copyOf()
        val before = TamperLog.scan(store, MetadataDb.load(store, id, vmk).logBlobId!!, vmk)
        val entriesBefore = before.entries.size

        vault.create(pass(B), listOf(pass(A)))
        lockEverything()

        val db = MetadataDb.load(store, id, vmk)
        val after = TamperLog.scan(store, db.logBlobId!!, vmk)
        assertEquals(
            "the vault that created another must not record that it did",
            entriesBefore, after.entries.size,
        )
    }

    @Test
    fun creatingAVaultDoesNotTouchTheBiometricWrap() {
        create(A, emptyList())

        // §4.1's wrap is one per install and belongs to whichever vault enabled
        // it. Creating another vault is not that vault's business — a fingerprint
        // that stopped working because someone made a decoy would be a surprise
        // the user cannot diagnose. Written here as bytes rather than through a
        // prompt: this asserts what creation does *not* do, and needs no enrolment.
        val wrap = File(root, BiometricWrap.FILE_NAME)
        val record = ByteArray(BiometricWrap.FILE_BYTES).also { rng.nextBytes(it) }
        wrap.writeBytes(record)

        create(B, listOf(A))

        assertArrayEquals("an existing wrap must survive a creation", record, wrap.readBytes())
    }

    // --- recovery writes only its own slot (`recovery-own-slot-only`) -----------------

    @Test
    fun recoveryWritesOnlyItsOwnSlotAndEveryOtherListSurvives() {
        val mnemonic = Mnemonic.fromResources(ctx)

        create(A, emptyList())
        create(B, listOf(A))
        create(C, listOf(A, B))

        // The words for keyring A, taken the way the kit screen takes them.
        assertTrue(vault.unlock(pass(A)).opened)
        val words = VaultKeyringSession.withKey { mnemonic.encode(it) }!!.joinToString(" ")
        val home = VaultKeyringSession.homeSlot()
        lockEverything()
        val before = slotsFile.readBytes()

        // Nothing else is named — there is nowhere to name it.
        val outcome = vault.recover(mnemonic, words, pass(NEW))
        lockEverything()
        assertTrue("recovery must open", outcome.opened)

        val after = slotsFile.readBytes()
        for (i in 0 until SlotArray.SLOT_COUNT) {
            val from = SlotArray.SLOTS_OFFSET + i * SlotArray.SLOT_BYTES
            val same = before.copyOfRange(from, from + SlotArray.SLOT_BYTES)
                .contentEquals(after.copyOfRange(from, from + SlotArray.SLOT_BYTES))
            assertEquals("slot $i changed only if it is A's home", i != home, same)
        }

        assertTrue("the recovered list opens under its new passphrase", opens(NEW))
        assertFalse("the recovered list's old passphrase stops working", opens(A))
        assertTrue("B survives without being named", opens(B))
        assertTrue("C survives without being named", opens(C))
    }

    // --- what unlock still cannot say -------------------------------------------------

    @Test
    fun oneVaultAndSeveralAreIndistinguishableAtUnlock() {
        // Three installs: none, one, three. Built here rather than shared, because
        // the whole claim is about what an install *holds*.
        val empty = File(ctx.filesDir, "mv-empty-${System.nanoTime()}").apply { mkdirs() }
        val single = File(ctx.filesDir, "mv-one-${System.nanoTime()}").apply { mkdirs() }
        try {
            val emptyVault = VaultLifecycle(File(empty, "vault.slots"), BlobStore(File(empty, "blobs")))
            val singleVault = VaultLifecycle(File(single, "vault.slots"), BlobStore(File(single, "blobs")))
            singleVault.create(pass(A))
            lockEverything()

            create(A, emptyList())
            create(B, listOf(A))
            create(C, listOf(A, B))

            // The counts are the evidence: an early exit costs microseconds against
            // a ~460 ms derivation and no stopwatch here could see it.
            val attempts = listOf(
                "empty/miss" to { emptyVault.unlock(pass(NEW)) },
                "one/hit" to { singleVault.unlock(pass(A)) },
                "one/miss" to { singleVault.unlock(pass(NEW)) },
                "three/first" to { vault.unlock(pass(A)) },
                "three/last" to { vault.unlock(pass(C)) },
                "three/miss" to { vault.unlock(pass(NEW)) },
            )
            attempts.forEach { (name, run) ->
                val outcome = run()
                lockEverything()
                assertEquals("$name derivations", 1, outcome.derivations)
                assertEquals("$name slots attempted", SlotArray.SLOT_COUNT, outcome.slotsAttempted)
                assertNull("$name must carry no refusal", outcome.refusal)
            }

            // Interleaved, warm-up first, minimum over 5 — P1-9's methodology, for
            // its reason: contention only ever adds time.
            fun time(run: () -> VaultLifecycle.Outcome): Long {
                lockEverything()
                val t = System.nanoTime()
                run()
                return (System.nanoTime() - t) / 1_000_000
            }
            attempts.forEach { time(it.second) }

            val samples = attempts.associate { it.first to mutableListOf<Long>() }
            repeat(5) { attempts.forEach { (name, run) -> samples.getValue(name) += time(run) } }
            lockEverything()

            val minima = samples.mapValues { it.value.min() }
            Log.i(TAG, "unlock minima over 5: $minima")
            val spread = minima.values.max().toDouble() / minima.values.min().coerceAtLeast(1)
            Log.i(TAG, "envelope: %.2fx".format(spread))
            assertTrue(
                "how many vaults an install holds must not be visible in unlock time: $minima",
                spread < 1.5,
            )
        } finally {
            empty.deleteRecursively()
            single.deleteRecursively()
        }
    }
}
