package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keyring's structure and its two layers of sealing — `vault-keyring`.
 *
 * Bytes in, bytes out: no store, no vault, no UI. It lives here rather than in the
 * JVM suite for `VaultKeysTest`'s reason — libsodium is a native library and the
 * derivations are real ones.
 */
@RunWith(AndroidJUnit4::class)
class VaultKeyringTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun key() = VaultKeys.newKeyringKey()
    private fun vmk() = VaultKeys.newVmk()
    private fun blobId(fill: Byte) = ByteArray(VaultKeys.BLOB_ID_BYTES) { fill }

    private fun keyringOf(keyringKey: ByteArray, count: Int): VaultKeyring.Keyring {
        var ring = VaultKeyring.empty()
        repeat(count) { i ->
            ring = VaultKeyring.withEntry(
                ring, keyringKey, i, "Vault $i", vmk(), blobId((0x40 + i).toByte())
            )
        }
        return ring
    }

    // --- 1. the length says nothing -------------------------------------------

    /**
     * One vault and nine are the same size. This is the whole of "the count is
     * private from someone holding the ciphertext".
     */
    @Test
    fun aKeyringsLengthDoesNotVaryWithWhatItHolds() {
        val k = key()

        val lengths = listOf(0, 1, 9, VaultKeyring.CAPACITY)
            .map { VaultKeyring.serialise(keyringOf(k, it)).size }
            .distinct()

        assertEquals("every keyring must serialise to one length: $lengths", 1, lengths.size)
        assertEquals(VaultKeyring.PLAINTEXT_BYTES, lengths.single())
    }

    /** An unoccupied position is filler, not a run of zeroes waiting to be optimised away. */
    @Test
    fun anUnoccupiedPositionIsNotAllZeroes() {
        val bytes = VaultKeyring.serialise(keyringOf(key(), 1))

        // Position 1 is free. Everything past its occupancy byte should be random.
        val base = 1 + VaultKeyring.ENTRY_BYTES
        val filler = bytes.copyOfRange(base + 1, base + VaultKeyring.ENTRY_BYTES)
        assertTrue("an unoccupied position must not be a block of zeroes", filler.any { it != 0.toByte() })
    }

    /** Two names of different lengths leave the same amount of trace. */
    @Test
    fun aShortNameAndALongNameOccupyTheSameField() {
        val k = key()
        val short = VaultKeyring.withEntry(
            VaultKeyring.empty(), k, 0, "A", vmk(), blobId(0x41)
        )
        val long = VaultKeyring.withEntry(
            VaultKeyring.empty(), k, 0, "A".repeat(VaultKeyring.MAX_NAME_BYTES), vmk(), blobId(0x41)
        )

        assertEquals(
            VaultKeyring.serialise(short).size,
            VaultKeyring.serialise(long).size,
        )
    }

    // --- 2. round trip ---------------------------------------------------------

    @Test
    fun everyFieldSurvivesASaveAndLoad() {
        val k = key()
        val vault = vmk()
        val index = blobId(0x51)
        val ring = VaultKeyring.withEntry(
            VaultKeyring.empty(), k, 3, "Receipts — 2024 ✓", vault, index
        )

        val back = VaultKeyring.parse(VaultKeyring.serialise(ring))!!

        val entry = back.at(3)!!
        assertEquals("Receipts — 2024 ✓", entry.name)
        assertArrayEquals(index, entry.indexBlobId)
        assertEquals(3, entry.position)
        assertNull("an unoccupied position must come back unoccupied", back.at(4))

        val unsealed = VaultKeyring.unsealVaultKey(back, k, 3)!!
        assertArrayEquals("the vault key must survive the round trip", vault, unsealed)
    }

    @Test
    fun aBlockThatIsNotAKeyringDoesNotParse() {
        assertNull(VaultKeyring.parse(ByteArray(10)))
        assertNull(VaultKeyring.parse(ByteArray(VaultKeyring.PLAINTEXT_BYTES)))
    }

    // --- 3. opening a keyring unwraps nothing ---------------------------------

    /**
     * A parsed keyring holds names and ciphertext. If a vault key can be found in what
     * `parse` returned, the "one key in memory" claim is already lost before any
     * screen is involved.
     */
    @Test
    fun aParsedKeyringHoldsNoUsableVaultKey() {
        val k = key()
        val keys = (0 until 9).map { vmk() }
        var ring = VaultKeyring.empty()
        keys.forEachIndexed { i, vault ->
            ring = VaultKeyring.withEntry(ring, k, i, "Vault $i", vault, blobId((0x60 + i).toByte()))
        }

        val back = VaultKeyring.parse(VaultKeyring.serialise(ring))!!

        keys.forEach { vault ->
            back.vaults.forEach { entry ->
                assertFalse(
                    "a vault key appears in a parsed entry",
                    indexOf(entry.sealedKey, vault) >= 0,
                )
            }
        }
    }

    /** Unsealing one entry yields one key and touches no other. */
    @Test
    fun unsealingOneEntryYieldsOnlyThatVaultsKey() {
        val k = key()
        val keys = (0 until 9).map { vmk() }
        var ring = VaultKeyring.empty()
        keys.forEachIndexed { i, vault ->
            ring = VaultKeyring.withEntry(ring, k, i, "Vault $i", vault, blobId((0x60 + i).toByte()))
        }

        val third = VaultKeyring.unsealVaultKey(ring, k, 3)!!

        assertArrayEquals(keys[3], third)
        keys.forEachIndexed { i, vault ->
            if (i != 3) assertFalse("entry $i was unwrapped too", third.contentEquals(vault))
        }
    }

    /** The wrong keyring key opens nothing, and says so the same way every time. */
    @Test
    fun anotherKeyringsKeyUnsealsNothing() {
        val ring = keyringOf(key(), 3)

        assertNull(VaultKeyring.unsealVaultKey(ring, key(), 0))
        assertNull(VaultKeyring.unsealVaultKey(ring, key(), 1))
    }

    /** An entry cannot be moved to another position by anyone who cannot open it. */
    @Test
    fun anEntryCannotBeTransplantedToAnotherPosition() {
        val k = key()
        val vault = vmk()
        val ring = VaultKeyring.withEntry(VaultKeyring.empty(), k, 2, "Trip", vault, blobId(0x41))

        // The same sealed bytes, moved to position 5.
        val moved = VaultKeyring.Keyring(arrayOfNulls<VaultKeyring.Entry>(VaultKeyring.CAPACITY).also {
            val entry = ring.at(2)!!
            it[5] = VaultKeyring.Entry(5, entry.name, entry.sealedKey, entry.indexBlobId)
        })

        assertNull("the position is the AAD; a moved entry must not open", 
            VaultKeyring.unsealVaultKey(moved, k, 5))
    }

    /** Entry keys are per position, and none of them is the keyring key. */
    @Test
    fun everyEntryKeyDiffersFromEveryOtherAndFromTheKeyringKey() {
        val k = key()

        val first = VaultKeys.keyringEntryKey(k, 0)
        val second = VaultKeys.keyringEntryKey(k, 1)

        assertNotEquals(first.toList(), second.toList())
        assertNotEquals(first.toList(), k.toList())
        assertNotEquals(second.toList(), k.toList())
        // And it is deterministic, or a keyring would not open twice.
        assertArrayEquals(first, VaultKeys.keyringEntryKey(k, 0))
    }

    // --- 4. the list's own behaviour ------------------------------------------

    @Test
    fun aKeyringFillsUpAndSaysSo() {
        val k = key()

        val full = keyringOf(k, VaultKeyring.CAPACITY)

        assertTrue(full.isFull)
        assertNull("a full keyring offers no free position", full.firstFree())
        assertEquals(VaultKeyring.CAPACITY, full.vaults.size)
    }

    @Test
    fun removingAVaultFreesItsPosition() {
        val k = key()
        val ring = keyringOf(k, 3)

        val without = VaultKeyring.without(ring, 1)

        assertNull(without.at(1))
        assertEquals(1, without.firstFree())
        assertEquals("the others stay where they were", 2, without.vaults.size)
        assertEquals("Vault 2", without.at(2)!!.name)
    }

    @Test
    fun renamingRewritesTheNameAndNothingElse() {
        val k = key()
        val vault = vmk()
        val ring = VaultKeyring.withEntry(VaultKeyring.empty(), k, 0, "Old", vault, blobId(0x41))

        val renamed = VaultKeyring.edited(ring, 0, "New", 0)

        assertEquals("New", renamed.at(0)!!.name)
        assertArrayEquals(blobId(0x41), renamed.at(0)!!.indexBlobId)
        assertArrayEquals(vault, VaultKeyring.unsealVaultKey(renamed, k, 0))
    }

    @Test
    fun anEmptyKeyringSaysItIsEmpty() {
        assertTrue(VaultKeyring.empty().isEmpty)
        assertEquals(0, VaultKeyring.empty().vaults.size)
        assertEquals(0, VaultKeyring.empty().firstFree())
    }

    @Test
    fun aNameTooLongIsRefusedBeforeItIsWritten() {
        assertTrue(VaultKeyring.fits("Trip"))
        assertFalse(VaultKeyring.fits(""))
        assertFalse(VaultKeyring.fits("x".repeat(VaultKeyring.MAX_NAME_BYTES + 1)))
        // UTF-8 bytes, not characters — the field is a byte field.
        assertFalse(VaultKeyring.fits("é".repeat(VaultKeyring.MAX_NAME_BYTES)))
    }

    // --- 5. through the store --------------------------------------------------

    /**
     * A keyring survives a write and a read, and replacing it never destroys the one
     * that was there — the property the migration's ordering rests on.
     */
    @Test
    fun aKeyringRoundTripsThroughTheStoreAndIsReplacedAtomically() {
        val root = java.io.File(ctx.filesDir, "keyring-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = BlobStore(java.io.File(root, "blobs"), java.io.File(root, "incoming"))
            val k = key()
            val id = ByteArray(VaultKeys.BLOB_ID_BYTES) { 0x71 }
            val vault = vmk()

            assertNull("nothing is there before it is written", VaultKeyring.load(store, id, k))

            VaultKeyring.save(
                store, id, k,
                VaultKeyring.withEntry(VaultKeyring.empty(), k, 0, "First", vault, blobId(0x41)),
            )

            val back = VaultKeyring.load(store, id, k)!!
            assertEquals("First", back.at(0)!!.name)
            assertArrayEquals(vault, VaultKeyring.unsealVaultKey(back, k, 0))

            // Replaced under the same ID, which is what the slot names.
            VaultKeyring.save(store, id, k, VaultKeyring.edited(back, 0, "Renamed", 0))
            assertEquals("Renamed", VaultKeyring.load(store, id, k)!!.at(0)!!.name)

            // Nothing is left behind in the incoming directory to be resumed.
            val incoming = java.io.File(root, "incoming")
            assertTrue(
                "a completed write leaves no partial",
                !incoming.exists() || incoming.listFiles().orEmpty().isEmpty(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    /** Another keyring key reads nothing, rather than reading something wrong. */
    @Test
    fun aKeyringDoesNotLoadUnderAnotherKey() {
        val root = java.io.File(ctx.filesDir, "keyring-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = BlobStore(java.io.File(root, "blobs"), java.io.File(root, "incoming"))
            val k = key()
            val id = ByteArray(VaultKeys.BLOB_ID_BYTES) { 0x72 }
            VaultKeyring.save(store, id, k, keyringOf(k, 2))

            assertNull(VaultKeyring.load(store, id, key()))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // --- 5. a vault sealed under its own passphrase — `per-vault-passphrases` ---

    /** A real Argon2id under a deliberately cheap set: what is under test is which key opens what. */
    private val cheap = VaultKeys.Argon2idParams(memoryKib = 8 * 1024, iterations = 1, parallelism = 1)
    private val ringKey = ByteArray(VaultKeys.KEY_BYTES) { 0x5A }

    private fun entryKek(position: Int, passphrase: String, keyringKey: ByteArray = ringKey): ByteArray =
        VaultKeys.entryPassphraseKek(passphrase.toByteArray(Charsets.UTF_8), keyringKey, position, cheap)

    /** The salt is per position and per keyring, and it is stable. */
    @Test
    fun theEntrySaltIsUniquePerPositionAndStable() {
        val keyringKey = ByteArray(VaultKeys.KEY_BYTES) { 0x11 }
        val other = ByteArray(VaultKeys.KEY_BYTES) { 0x22 }

        val a = VaultKeys.keyringEntrySalt(keyringKey, 0)
        val again = VaultKeys.keyringEntrySalt(keyringKey, 0)
        val b = VaultKeys.keyringEntrySalt(keyringKey, 1)
        val elsewhere = VaultKeys.keyringEntrySalt(other, 0)

        assertEquals("a salt is ${VaultKeys.SALT_BYTES} bytes", VaultKeys.SALT_BYTES, a.size)
        assertArrayEquals("the same position must give the same salt", a, again)
        assertNotEquals(
            "two positions must not share a salt",
            a.toList(), b.toList(),
        )
        assertNotEquals(
            "two keyrings must not share a salt at the same position",
            a.toList(), elsewhere.toList(),
        )
    }

    /**
     * **The claim the feature rests on.** The keyring key does not open a vault that
     * carries its own passphrase; that passphrase does.
     */
    @Test
    fun theKeyringKeyDoesNotOpenAVaultSealedUnderItsOwnPassphrase() {
        val k = key()
        val vmk = vmk()
        val kek = entryKek(0, "the-vaults-own-passphrase")

        val ring = VaultKeyring.withProtectedEntry(
            VaultKeyring.empty(), kek, 0, "Receipts", vmk, blobId(0x40), 0,
        )

        assertNull(
            "the master credential must not open a vault that has its own",
            VaultKeyring.unsealVaultKey(ring, k, 0),
        )
        assertArrayEquals(
            "the vault's own key must open it",
            vmk,
            VaultKeyring.unsealOwnPassphraseVaultKey(ring, kek, 0),
        )
    }

    /** A wrong passphrase and an entry that is not protected fail the same way. */
    @Test
    fun aWrongPassphraseOpensNothing() {
        val kek = entryKek(0, "the-right-one")
        val wrong = entryKek(0, "the-wrong-one")
        val plain = key()

        val protectedRing = VaultKeyring.withProtectedEntry(
            VaultKeyring.empty(), kek, 0, "Receipts", vmk(), blobId(0x40), 0,
        )
        val plainRing = VaultKeyring.withEntry(
            VaultKeyring.empty(), plain, 0, "Receipts", vmk(), blobId(0x40),
        )

        assertNull(VaultKeyring.unsealOwnPassphraseVaultKey(protectedRing, wrong, 0))
        assertNull(
            "an unprotected entry must not open under an entry KEK either",
            VaultKeyring.unsealOwnPassphraseVaultKey(plainRing, kek, 0),
        )
    }

    /**
     * The two forms are the same size, so the flag is the only thing that differs and
     * a keyring's length still says nothing about what it holds.
     */
    @Test
    fun protectedAndPlainKeyringsSerialiseToOneLength() {
        val k = key()
        val kek = entryKek(0, "the-vaults-own-passphrase")

        val plain = VaultKeyring.withEntry(
            VaultKeyring.empty(), k, 0, "Receipts", vmk(), blobId(0x40),
        )
        val guarded = VaultKeyring.withProtectedEntry(
            VaultKeyring.empty(), kek, 0, "Receipts", vmk(), blobId(0x40), 0,
        )

        assertEquals(
            VaultKeyring.serialise(plain).size,
            VaultKeyring.serialise(guarded).size,
        )
        assertEquals(
            "and the sealed-key field is the same width in both",
            plain.at(0)!!.sealedKey.size,
            guarded.at(0)!!.sealedKey.size,
        )
    }

    /** The flag survives a round trip, and a rename does not disturb the seal. */
    @Test
    fun theFlagAndTheSealSurviveSerialisationAndRename() {
        val kek = entryKek(0, "the-vaults-own-passphrase")
        val vmk = vmk()
        val ring = VaultKeyring.withProtectedEntry(
            VaultKeyring.empty(), kek, 0, "Receipts", vmk, blobId(0x40), 0,
        )

        val back = VaultKeyring.parse(VaultKeyring.serialise(ring))!!
        assertTrue("the flag must round-trip", back.at(0)!!.hasOwnPassphrase)
        assertArrayEquals(vmk, VaultKeyring.unsealOwnPassphraseVaultKey(back, kek, 0))

        val renamed = VaultKeyring.edited(back, 0, "Invoices", 0)
        assertEquals("Invoices", renamed.at(0)!!.name)
        assertTrue("renaming must not clear the flag", renamed.at(0)!!.hasOwnPassphrase)
        assertArrayEquals(
            "renaming must not need or disturb the vault's passphrase",
            vmk,
            VaultKeyring.unsealOwnPassphraseVaultKey(renamed, kek, 0),
        )
    }

    /**
     * A keyring written before this change reads as all-unprotected — that is what the
     * version means — and is stored as the current version with nothing else changed.
     */
    @Test
    fun aVersionOneKeyringReadsAsUnprotected() {
        val k = key()
        val vmk = vmk()
        val ring = VaultKeyring.withEntry(
            VaultKeyring.empty(), k, 0, "Receipts", vmk, blobId(0x40),
        )

        // The bytes an older build would have written: the current layout with the
        // older version, whose entries could only ever have been unprotected.
        val older = VaultKeyring.serialise(ring).also {
            it[0] = VaultKeyring.VERSION_UNPROTECTED.toByte()
        }

        val back = VaultKeyring.parse(older)!!
        assertFalse("a version 1 entry is unprotected", back.at(0)!!.hasOwnPassphrase)
        assertArrayEquals(
            "and still opens with the keyring key",
            vmk,
            VaultKeyring.unsealVaultKey(back, k, 0),
        )
        assertEquals(
            "rewriting it stores the current version",
            VaultKeyring.VERSION.toByte(),
            VaultKeyring.serialise(back)[0],
        )
    }

    /** A version this build does not know is refused rather than interpreted. */
    @Test
    fun anUnknownVersionIsRefused() {
        val bytes = VaultKeyring.serialise(keyringOf(key(), 1)).also { it[0] = 99 }
        assertNull(VaultKeyring.parse(bytes))
    }

    // --- 6. the portable protected key — `backup-file-recover-and-import`, D2 ---

    /** Entry offset 189, written out here: a layout test that imports the layout asserts nothing. */
    private val kdfSetOffset = 189

    @Test
    fun aProtectedEntryRoundTripsItsParameterSet() {
        val k = key()
        val ring = VaultKeyring.withEntry(
            VaultKeyring.withProtectedEntry(VaultKeyring.empty(), entryKek(0, "own"), 0, "Papers", vmk(), blobId(0x40), 0),
            k, 1, "Photos", vmk(), blobId(0x41),
        )
        val bytes = VaultKeyring.serialise(ring)
        assertEquals("the set is stored as index + 1", 1, bytes[1 + kdfSetOffset].toInt())
        assertEquals("an unprotected entry stores 0", 0, bytes[1 + VaultKeyring.ENTRY_BYTES + kdfSetOffset].toInt())
        assertEquals(VaultKeyring.VERSION, bytes[0].toInt() and 0x7F)

        val back = VaultKeyring.parse(bytes)!!
        assertEquals(0, back.at(0)!!.kdfSet)
        assertEquals(-1, back.at(1)!!.kdfSet)
        assertEquals("a rename keeps it", 0, VaultKeyring.edited(back, 0, "Letters", 0).at(0)!!.kdfSet)
    }

    @Test
    fun twoPositionsWithOnePassphraseSealDifferently() {
        val kek0 = entryKek(0, "the-same-passphrase")
        val kek1 = entryKek(1, "the-same-passphrase")
        assertNotEquals("the salts differ by position", kek0.toList(), kek1.toList())

        var ring = VaultKeyring.withProtectedEntry(VaultKeyring.empty(), kek0, 0, "A", vmk(), blobId(0x40), 0)
        ring = VaultKeyring.withProtectedEntry(ring, kek1, 1, "B", vmk(), blobId(0x41), 0)
        assertNull("one position's key does not open the other", VaultKeyring.unsealOwnPassphraseVaultKey(ring, kek0, 1))
        assertNotEquals(ring.at(0)!!.sealedKey.toList(), ring.at(1)!!.sealedKey.toList())
    }

    /**
     * **The defect this fixes.** A protected vault restored under a slot file with a new
     * install salt — a new phone — opens with its own passphrase.
     */
    @Test
    fun aProtectedVaultOpensAfterItsSlotFileIsReplaced() {
        val root = java.io.File(ctx.filesDir, "portable-${System.nanoTime()}").apply { mkdirs() }
        val slotsFile = java.io.File(root, "vault.slots")
        val store = BlobStore(java.io.File(root, "blobs"), java.io.File(root, "incoming"))
        val lifecycle = VaultLifecycle(slotsFile, store)
        val mnemonic = Mnemonic.fromResources(ctx)
        val own = "the-vaults-own-passphrase".toByteArray()
        VaultLifecycle.lockNow()
        try {
            assertTrue(lifecycle.create("the-list-passphrase".toByteArray()).opened)
            val position = VaultKeyringSession.createVault(store, "Papers") { p, set ->
                VaultKeyringSession.entryPassphraseKek(own, p, set)!!
            }!!
            val kit = VaultKeyringSession.withKey { mnemonic.encode(it) }!!.joinToString(" ")
            val saltBefore = SlotArray(slotsFile).installSalt()
            VaultLifecycle.lockNow()

            slotsFile.delete()
            assertTrue(lifecycle.recover(mnemonic, kit, "a-new-phone-passphrase".toByteArray(), fresh = true).opened)
            assertNotEquals("a new install salt", saltBefore.toList(), SlotArray(slotsFile).installSalt().toList())

            val entry = VaultKeyringSession.vaults().single { it.position == position }
            assertTrue(entry.hasOwnPassphrase)
            val kek = VaultKeyringSession.entryPassphraseKek(own, position, entry.kdfSet)!!
            assertTrue("its own passphrase opens it", VaultKeyringSession.openVaultWithPassphrase(position, kek) != null)
        } finally {
            VaultLifecycle.lockNow()
            root.deleteRecursively()
        }
    }

    /** Version 4 salted with the install: still protected, and its passphrase is not tried. */
    @Test
    fun aVersionFourProtectedEntryDoesNotOpenWithItsPassphrase() {
        val k = key()
        val ring = VaultKeyring.withProtectedEntry(
            VaultKeyring.empty(), entryKek(0, "own", k), 0, "Papers", vmk(), blobId(0x40), 0,
        )
        val older = VaultKeyring.serialise(ring).also { it[0] = VaultKeyring.VERSION_INSTALL_BOUND.toByte() }
        val back = VaultKeyring.parse(older)!!
        assertTrue("it reads as protected", back.at(0)!!.hasOwnPassphrase)
        assertEquals("with no recorded set", -1, back.at(0)!!.kdfSet)

        VaultKeyringSession.holdRecovered(k, blobId(0x72), back, 0)
        try {
            assertNull(
                "no derivation is attempted, so its passphrase opens nothing",
                VaultKeyringSession.entryPassphraseKek("own".toByteArray(), 0, back.at(0)!!.kdfSet),
            )
        } finally {
            VaultKeyringSession.lock()
        }
    }

    @Test
    fun theStoredLengthIsUnchangedByTheParameterByte() {
        val root = java.io.File(ctx.filesDir, "keyring-len-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = BlobStore(java.io.File(root, "blobs"), java.io.File(root, "incoming"))
            val k = key()
            val plainId = blobId(0x10)
            val guardedId = blobId(0x11)
            VaultKeyring.save(store, plainId, k, keyringOf(k, 1))
            VaultKeyring.save(
                store, guardedId, k,
                VaultKeyring.withProtectedEntry(VaultKeyring.empty(), entryKek(0, "own", k), 0, "P", vmk(), blobId(0x40), 0),
            )
            assertEquals(store.fileOf(plainId).length(), store.fileOf(guardedId).length())
        } finally {
            root.deleteRecursively()
        }
    }

    // --- 7. kit views — `recovery-kit-three-views`, D1 and D2 --------------------

    /** Written out, for [kdfSetOffset]'s reason: entry offset 190, and position 0's byte 191. */
    private val entryViewsOffset = 190
    private val listViewsOffset = 1 + 191

    private fun guardedRing(k: ByteArray) = VaultKeyring.withEntry(
        VaultKeyring.withProtectedEntry(VaultKeyring.empty(), entryKek(0, "own", k), 0, "Papers", vmk(), blobId(0x40), 0),
        k, 1, "Photos", vmk(), blobId(0x41),
    )

    @Test
    fun bothViewCountsRoundTrip() {
        val ring = VaultKeyring.withEntryKitViewsUsed(VaultKeyring.withKitViewsUsed(guardedRing(key()), 1), 0, 2)
        val bytes = VaultKeyring.serialise(ring)
        assertEquals(1, bytes[listViewsOffset].toInt())
        assertEquals(2, bytes[1 + entryViewsOffset].toInt())

        val back = VaultKeyring.parse(bytes)!!
        assertEquals(1, back.kitViewsUsed)
        assertEquals(2, back.at(0)!!.kitViewsUsed)
        assertEquals("an unprotected entry has no count", 0, back.at(1)!!.kitViewsUsed)
        assertEquals("the parameter set is still read", 0, back.at(0)!!.kdfSet)
        assertEquals("a rename keeps both", 2, VaultKeyring.edited(back, 0, "Letters", 0).at(0)!!.kitViewsUsed)
        assertEquals(1, VaultKeyring.edited(back, 0, "Letters", 0).kitViewsUsed)
    }

    @Test
    fun aVersionFiveKeyringReadsAsNoViewsUsed() {
        val ring = VaultKeyring.withEntryKitViewsUsed(VaultKeyring.withKitViewsUsed(guardedRing(key()), 3), 0, 3)
        val older = VaultKeyring.serialise(ring).also { it[0] = VaultKeyring.VERSION_NO_KIT_VIEWS.toByte() }
        val back = VaultKeyring.parse(older)!!
        assertEquals(0, back.kitViewsUsed)
        assertEquals(0, back.at(0)!!.kitViewsUsed)
        assertEquals("version 5 still records the parameter set", 0, back.at(0)!!.kdfSet)
        assertEquals(VaultKeyring.VERSION.toByte(), VaultKeyring.serialise(back)[0])
    }

    @Test
    fun theStoredLengthIsUnchangedByTheViewBytes() {
        val root = java.io.File(ctx.filesDir, "keyring-views-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = BlobStore(java.io.File(root, "blobs"), java.io.File(root, "incoming"))
            val k = key()
            VaultKeyring.save(store, blobId(0x10), k, guardedRing(k))
            VaultKeyring.save(
                store, blobId(0x11), k,
                VaultKeyring.withEntryKitViewsUsed(VaultKeyring.withKitViewsUsed(guardedRing(k), 3), 0, 3),
            )
            assertEquals(store.fileOf(blobId(0x10)).length(), store.fileOf(blobId(0x11)).length())
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * The counts go where the keyring goes. The hidden-keyring transfer this was first written
     * against no longer exists; restore and restore-as-list rewrite the record with
     * [VaultKeyring.withKnownSlots] and [VaultKeyring.withPanicRecord], and adopt moves entries.
     */
    @Test
    fun theCountsSurviveARecordRewriteAndAnAdopt() {
        val k = key()
        val ring = VaultKeyring.withEntryKitViewsUsed(VaultKeyring.withKitViewsUsed(guardedRing(k), 1), 0, 2)
        val rewritten = VaultKeyring.withPanicRecord(VaultKeyring.withKnownSlots(ring, 0b11), -1, 1)
        assertEquals(1, rewritten.kitViewsUsed)
        assertEquals(2, rewritten.at(0)!!.kitViewsUsed)
        assertEquals(1, VaultKeyring.withDecoy(ring).kitViewsUsed)

        val root = java.io.File(ctx.filesDir, "keyring-adopt-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = BlobStore(java.io.File(root, "blobs"), java.io.File(root, "incoming"))
            val incoming = BlobStore(java.io.File(root, "side"), java.io.File(root, "side-in"))
            val id = blobId(0x73)
            // Here: only the plain vault, one list view spent. The backup's copy: the protected one too.
            VaultKeyring.save(store, id, k, VaultKeyring.without(VaultKeyring.withKitViewsUsed(ring, 1), 0))
            VaultKeyring.save(incoming, id, k, ring)

            assertEquals(1, VaultKeyringSession.adoptEntries(store, incoming, k, id))
            val after = VaultKeyring.load(store, id, k)!!
            assertEquals("the protected vault brings its count", 2, after.at(0)!!.kitViewsUsed)
            assertEquals("the list keeps its own", 1, after.kitViewsUsed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun spendingPastThreeReturnsNullAndWritesNothing() {
        val root = java.io.File(ctx.filesDir, "keyring-spend-${System.nanoTime()}").apply { mkdirs() }
        val store = BlobStore(java.io.File(root, "blobs"), java.io.File(root, "incoming"))
        val k = key()
        val id = blobId(0x74)
        try {
            VaultKeyring.save(store, id, k, guardedRing(k))
            VaultKeyringSession.holdRecovered(k, id, VaultKeyring.load(store, id, k)!!, 0)

            assertEquals(3, VaultKeyringSession.listKitViewsLeft())
            assertEquals(listOf(2, 1, 0), List(3) { VaultKeyringSession.spendListKitView(store) })
            val file = store.fileOf(id).readBytes()
            assertNull(VaultKeyringSession.spendListKitView(store))
            assertArrayEquals("a refused view writes nothing", file, store.fileOf(id).readBytes())

            assertEquals(2, VaultKeyringSession.spendVaultKitView(store, 0))
            assertNull("an unprotected vault has no kit of its own", VaultKeyringSession.spendVaultKitView(store, 1))
            val saved = VaultKeyring.load(store, id, k)!!
            assertEquals("the vault's view spent only its own count", 3, saved.kitViewsUsed)
            assertEquals(1, saved.at(0)!!.kitViewsUsed)
        } finally {
            VaultKeyringSession.lock()
            root.deleteRecursively()
        }
    }

    // --- 8. the appearance — `vault-list-refresh`, D1 --------------------------------

    @Test
    fun aVersionSixKeyringShowsTheLetterAndStillOpensOnceWrittenBackAsSeven() {
        val k = key()
        val plain = vmk()
        val own = entryKek(1, "own", k)
        val guarded = vmk()
        val ring = VaultKeyring.withProtectedEntry(
            VaultKeyring.withEntry(VaultKeyring.empty(), k, 0, "Photos", plain, blobId(0x40)),
            own, 1, "Papers", guarded, blobId(0x41), 0,
        )
        // What version 6 wrote: this layout, that version, and the old u16's high byte 0.
        val older = VaultKeyring.serialise(ring).also { it[0] = VaultKeyring.VERSION_NO_APPEARANCE.toByte() }

        val back = VaultKeyring.parse(older)!!
        back.vaults.forEach { assertEquals(VaultAppearance.LETTER, VaultAppearance.of(it.appearance)) }
        val rewritten = VaultKeyring.serialise(back)
        assertEquals(VaultKeyring.VERSION.toByte(), rewritten[0])
        assertEquals(older.size, rewritten.size)

        val again = VaultKeyring.parse(rewritten)!!
        assertArrayEquals("the plain entry still opens", plain, VaultKeyring.unsealVaultKey(again, k, 0))
        assertArrayEquals("the protected one too", guarded, VaultKeyring.unsealOwnPassphraseVaultKey(again, own, 1))
        assertTrue(again.at(1)!!.hasOwnPassphrase)
    }

    @Test
    fun editingAProtectedVaultNeedsNoPassphraseAndTouchesNoSeal() {
        val k = key()
        val own = entryKek(0, "own", k)
        val guarded = vmk()
        val ring = VaultKeyring.withProtectedEntry(VaultKeyring.empty(), own, 0, "Papers", guarded, blobId(0x40), 0)

        val look = VaultAppearance(FolderColour.Blue, 12)
        val edited = VaultKeyring.parse(VaultKeyring.serialise(VaultKeyring.edited(ring, 0, "Letters", look.pack())))!!

        assertEquals("Letters", edited.at(0)!!.name)
        assertEquals(look, VaultAppearance.of(edited.at(0)!!.appearance))
        assertArrayEquals(guarded, VaultKeyring.unsealOwnPassphraseVaultKey(edited, own, 0))
        assertNull("still not the keyring key's to open", VaultKeyring.unsealVaultKey(edited, k, 0))
    }

    @Test
    fun theStoredLengthIsUnchangedByTheAppearance() {
        val root = java.io.File(ctx.filesDir, "keyring-look-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = BlobStore(java.io.File(root, "blobs"), java.io.File(root, "incoming"))
            val k = key()
            VaultKeyring.save(store, blobId(0x12), k, VaultKeyring.empty())
            var ring = VaultKeyring.empty()
            for (p in 0 until VaultKeyring.CAPACITY) {
                val look = VaultAppearance(FolderColour.entries[p % 6], p + 1).pack()
                ring = VaultKeyring.withEntry(ring, k, p, "Vault $p", vmk(), blobId((0x50 + p).toByte()), look)
            }
            VaultKeyring.save(store, blobId(0x13), k, ring)
            assertEquals(store.fileOf(blobId(0x12)).length(), store.fileOf(blobId(0x13)).length())
            val back = VaultKeyring.load(store, blobId(0x13), k)!!
            for (p in 0 until VaultKeyring.CAPACITY) assertEquals(ring.at(p)!!.appearance, back.at(p)!!.appearance)
        } finally {
            root.deleteRecursively()
        }
    }

    // --- 9. a passphrase added, changed and removed — `vault-passphrase-manage`, D1 and D3 ----

    @Test
    fun aProtectedReSealKeepsEverythingButTheSeal() {
        val k = key()
        val v = vmk()
        val look = VaultAppearance(FolderColour.Teal, 4).pack()
        val plain = VaultKeyring.withEntryKitViewsUsed(
            VaultKeyring.withEntry(VaultKeyring.empty(), k, 2, "Papers", v, blobId(0x42), look), 2, 1,
        )
        val own = entryKek(2, "own", k)
        val sealed = VaultKeyring.parse(VaultKeyring.serialise(VaultKeyring.protected(plain, own, 2, v, 0)))!!
        val e = sealed.at(2)!!

        assertTrue(e.hasOwnPassphrase)
        assertEquals("Papers", e.name)
        assertEquals(look, e.appearance)
        assertArrayEquals(blobId(0x42), e.indexBlobId)
        assertEquals("the count is carried, not reset", 1, e.kitViewsUsed)
        assertEquals(0, e.kdfSet)
        assertArrayEquals("the same key, re-sealed", v, VaultKeyring.unsealOwnPassphraseVaultKey(sealed, own, 2))
        assertNull("the keyring entry key no longer opens it", VaultKeyring.unsealVaultKey(sealed, k, 2))
    }

    @Test
    fun anUnprotectedReSealOpensFromTheListAndKeepsTheCount() {
        val k = key()
        val v = vmk()
        val own = entryKek(0, "own", k)
        val guarded = VaultKeyring.withEntryKitViewsUsed(
            VaultKeyring.withProtectedEntry(VaultKeyring.empty(), own, 0, "Papers", v, blobId(0x40), 0, 9), 0, 2,
        )
        val opened = VaultKeyring.parse(VaultKeyring.serialise(VaultKeyring.unprotected(guarded, k, 0, v)))!!
        val e = opened.at(0)!!

        assertFalse(e.hasOwnPassphrase)
        assertEquals("Papers", e.name)
        assertEquals(9, e.appearance)
        assertArrayEquals(blobId(0x40), e.indexBlobId)
        assertEquals("version 8 keeps an unprotected entry's count", 2, e.kitViewsUsed)
        assertArrayEquals(v, VaultKeyring.unsealVaultKey(opened, k, 0))
        assertNull(VaultKeyring.unsealOwnPassphraseVaultKey(opened, own, 0))

        // Removed and added again: the count is where it was.
        val again = VaultKeyring.parse(VaultKeyring.serialise(VaultKeyring.protected(opened, own, 0, v, 0)))!!
        assertEquals(2, again.at(0)!!.kitViewsUsed)
    }

    @Test
    fun theOldKekFailsAfterAChange() {
        val k = key()
        val v = vmk()
        val old = entryKek(0, "old", k)
        val new = entryKek(0, "new", k)
        val ring = VaultKeyring.withProtectedEntry(VaultKeyring.empty(), old, 0, "Papers", v, blobId(0x40), 0)
        val changed = VaultKeyring.parse(VaultKeyring.serialise(VaultKeyring.protected(ring, new, 0, v, 0)))!!

        assertNull(VaultKeyring.unsealOwnPassphraseVaultKey(changed, old, 0))
        assertArrayEquals(v, VaultKeyring.unsealOwnPassphraseVaultKey(changed, new, 0))
    }

    @Test
    fun aVersionSevenKeyringReadsUnprotectedCountsAsZero() {
        val k = key()
        val ring = VaultKeyring.withEntryKitViewsUsed(
            VaultKeyring.withEntryKitViewsUsed(guardedRing(k), 0, 2), 1, 3,
        )
        val bytes = VaultKeyring.serialise(ring)
        assertEquals(3, VaultKeyring.parse(bytes)!!.at(1)!!.kitViewsUsed)

        // What version 7 left on an unprotected entry: filler, not a count.
        val older = bytes.copyOf().also { it[0] = VaultKeyring.VERSION_UNPROTECTED_NO_KIT_VIEWS.toByte() }
        val back = VaultKeyring.parse(older)!!
        assertEquals("an unprotected entry at version 7 has used none", 0, back.at(1)!!.kitViewsUsed)
        assertEquals("a protected one keeps its count", 2, back.at(0)!!.kitViewsUsed)
        assertEquals(VaultKeyring.VERSION.toByte(), VaultKeyring.serialise(back)[0])
    }

    @Test
    fun theStoredLengthIsUnchangedByAReSeal() {
        val root = java.io.File(ctx.filesDir, "keyring-reseal-${System.nanoTime()}").apply { mkdirs() }
        try {
            val store = BlobStore(java.io.File(root, "blobs"), java.io.File(root, "incoming"))
            val k = key()
            val v = vmk()
            val plain = VaultKeyring.withEntry(VaultKeyring.empty(), k, 0, "Papers", v, blobId(0x40))
            VaultKeyring.save(store, blobId(0x14), k, plain)
            VaultKeyring.save(store, blobId(0x15), k, VaultKeyring.protected(plain, entryKek(0, "own", k), 0, v, 0))
            assertEquals(store.fileOf(blobId(0x14)).length(), store.fileOf(blobId(0x15)).length())
        } finally {
            root.deleteRecursively()
        }
    }
}
