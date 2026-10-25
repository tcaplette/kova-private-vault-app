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
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom

/**
 * An older install becomes a keyring — `vault-keyring`.
 *
 * The migration is the sharpest edge in this change: it runs once, on unlock, against
 * a vault that already exists, and getting it wrong loses that vault rather than
 * inconveniencing anyone. So the two interruption points are tested by actually
 * stopping between the two writes, not by reasoning about the order.
 *
 * The fixture builds a **real pre-change install**: a slot sealed with a VMK and an
 * index blob ID, which is exactly what `VaultLifecycle.create` wrote before this
 * change. Nothing here simulates the old layout — it constructs it.
 */
@RunWith(AndroidJUnit4::class)
class KeyringMigrationTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: BlobStore
    private lateinit var slotsFile: File
    private val rng = SecureRandom()

    private companion object {
        const val PASS = "correcthorsebattery"
    }

    private fun pass() = PASS.toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        VaultKeyringSession.lock()
        root = File(ctx.filesDir, "migrate-${System.nanoTime()}").apply { mkdirs() }
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        slotsFile = File(root, "vault.slots")
    }

    @After
    fun tearDown() {
        VaultLifecycle.lockNow()
        VaultKeyringSession.lock()
        root.deleteRecursively()
    }

    /**
     * A pre-change install: one slot holding a VMK and an index blob ID directly.
     *
     * Returns the VMK and the index ID so a test can assert nothing about either
     * moved. The caller owns neither — this fixture keeps no copy.
     */
    private fun oldLayoutInstall(items: Int = 3): Triple<ByteArray, ByteArray, List<ByteArray>> {
        val slots = SlotArray(slotsFile)
        slots.initialiseIfAbsent()

        val vmk = VaultKeys.newVmk()
        val indexId = VaultKeys.metadataBlobId(vmk)
        val kek = VaultKeys.deriveKek(pass(), slots.installSalt(), slots.kdfParams())
        try {
            // The old meaning of the 48 bytes, written by the old code's own call.
            slots.seal(3, kek, vmk, indexId)
        } finally {
            kek.fill(0)
        }

        val blobIds = mutableListOf<ByteArray>()
        val db = MetadataDb.empty()
        repeat(items) { i ->
            val content = ByteArray(1_024).also { rng.nextBytes(it) }
            val written = store.write(ByteArrayInputStream(content), vmk)
            blobIds += written.blobId
            db.items += VaultItem(
                blobIds = listOf(written.blobId),
                plaintextLength = written.plaintextLength,
                thumb = null,
                filename = "photo$i.jpg",
                album = null,
                createdAt = null,
                importedAt = (i + 1).toLong(),
                tags = emptyList(),
            )
        }
        db.save(store, indexId, vmk)
        return Triple(vmk, indexId, blobIds)
    }

    private fun bytesOf(blobId: ByteArray): ByteArray = store.fileOf(blobId).readBytes()

    // --- 1. it migrates, and nothing moves ------------------------------------

    @Test
    fun anOlderInstallOpensAsAKeyringHoldingOneNamedVault() {
        val (vmk, indexId, blobIds) = oldLayoutInstall()
        val before = blobIds.map { bytesOf(it) }

        val lifecycle = VaultLifecycle(slotsFile, store)
        assertTrue("the older install must still unlock", lifecycle.unlockAndOpen(pass()))

        // A keyring, with the one vault it had.
        val vaults = VaultKeyringSession.vaults()
        assertEquals(1, vaults.size)
        assertEquals(VaultKeyringSession.MIGRATED_NAME, vaults.single().name)
        assertArrayEquals(
            "the entry must name the index that was already there",
            indexId,
            vaults.single().indexBlobId,
        )

        // Opening it yields the same key, so the same content reads back.
        val opened = VaultKeyringSession.openVault(0)!!
        assertArrayEquals("the vault key must be the one that was sealed", vmk, opened.vmk)

        val db = MetadataDb.load(store, opened.metadataBlobId, opened.vmk)
        assertEquals("every item must still be present", 3, db.items.size)

        // Nothing re-encrypted, nothing moved.
        blobIds.forEachIndexed { i, id ->
            assertArrayEquals("blob $i was rewritten by a migration", before[i], bytesOf(id))
        }
    }

    /** Migrating is once. A second unlock finds the keyring and writes nothing new. */
    @Test
    fun theMigrationDoesNotRunTwice() {
        oldLayoutInstall(items = 1)
        val lifecycle = VaultLifecycle(slotsFile, store)

        assertTrue(lifecycle.unlockAndOpen(pass()))
        val firstEntry = VaultKeyringSession.vaults().single().indexBlobId.copyOf()
        val blobsAfterFirst = File(root, "blobs").listFiles().orEmpty().size

        VaultLifecycle.lockNow()
        VaultKeyringSession.lock()
        assertTrue(lifecycle.unlockAndOpen(pass()))

        assertEquals(1, VaultKeyringSession.vaults().size)
        assertArrayEquals(firstEntry, VaultKeyringSession.vaults().single().indexBlobId)
        assertEquals(
            "a second unlock must not write a second keyring",
            blobsAfterFirst,
            File(root, "blobs").listFiles().orEmpty().size,
        )
    }

    // --- 2. the two interruption points ---------------------------------------

    /**
     * Killed after the keyring is written and before the slot is re-sealed.
     *
     * The keyring blob exists and nothing points at it. The slot still holds the old
     * meaning, so the vault opens by the older path and the migration runs again — the
     * orphan is what the store's sweeper already collects.
     */
    @Test
    fun anInterruptionBeforeTheSlotIsResealedLeavesTheVaultOpenable() {
        val (vmk, indexId, _) = oldLayoutInstall(items = 2)

        // Exactly what `migrate` does first, and then nothing else — the kill.
        val orphanKey = VaultKeys.newKeyringKey()
        val orphanId = ByteArray(VaultKeys.BLOB_ID_BYTES).also { rng.nextBytes(it) }
        VaultKeyring.save(
            store, orphanId, orphanKey,
            VaultKeyring.withEntry(VaultKeyring.empty(), orphanKey, 0, "My vault", vmk, indexId),
        )

        val lifecycle = VaultLifecycle(slotsFile, store)
        assertTrue("the vault must still open by the older path", lifecycle.unlockAndOpen(pass()))

        val vaults = VaultKeyringSession.vaults()
        assertEquals("the migration must have run cleanly this time", 1, vaults.size)
        val opened = VaultKeyringSession.openVault(0)!!
        assertEquals(2, MetadataDb.load(store, opened.metadataBlobId, opened.vmk).items.size)
    }

    /**
     * Killed immediately after the slot is re-sealed.
     *
     * The state on disk is a completed migration, because the slot was the last write.
     * The next unlock finds a keyring and does not migrate anything.
     */
    @Test
    fun anInterruptionAfterTheSlotIsResealedIsACompletedMigration() {
        val (vmk, indexId, _) = oldLayoutInstall(items = 2)

        // Both writes, in order, and then the kill — which is simply not doing more.
        val keyringKey = VaultKeys.newKeyringKey()
        val keyringId = ByteArray(VaultKeys.BLOB_ID_BYTES).also { rng.nextBytes(it) }
        VaultKeyring.save(
            store, keyringId, keyringKey,
            VaultKeyring.withEntry(VaultKeyring.empty(), keyringKey, 0, "My vault", vmk, indexId),
        )
        val slots = SlotArray(slotsFile)
        val kek = VaultKeys.deriveKek(pass(), slots.installSalt(), slots.kdfParams())
        try {
            slots.seal(3, kek, keyringKey, keyringId)
        } finally {
            kek.fill(0)
        }

        val lifecycle = VaultLifecycle(slotsFile, store)
        assertTrue(lifecycle.unlockAndOpen(pass()))

        assertEquals(1, VaultKeyringSession.vaults().size)
        val opened = VaultKeyringSession.openVault(0)!!
        assertArrayEquals(vmk, opened.vmk)
        assertEquals(2, MetadataDb.load(store, opened.metadataBlobId, opened.vmk).items.size)
    }

    // --- 3. what a slot yields is decided cryptographically -------------------

    /**
     * An index blob read as a keyring does not decrypt, so the layout is never a guess
     * about bytes.
     *
     * This is what lets the layout be resolved per slot rather than per file — and it
     * has to be, because the version byte in the slot header is shared by all eight
     * slots and only the one whose passphrase was given can ever be migrated.
     */
    @Test
    fun anIndexBlobDoesNotReadAsAKeyring() {
        val (vmk, indexId, _) = oldLayoutInstall(items = 1)

        assertNull(
            "an index must not decrypt under the keyring's blob context",
            VaultKeyring.load(store, indexId, vmk),
        )
    }

    // --- 3b. the biometric wrap ------------------------------------------------

    /**
     * A wrap written **after** the migration opens the keyring, exactly as a
     * passphrase does — §4.1 seals the same 48 bytes a slot holds, so it carries the
     * same keyring reference.
     */
    @Test
    fun aWrapHoldingAKeyringResolvesToIt() {
        val lifecycle = VaultLifecycle(slotsFile, store)
        assertTrue(lifecycle.create(pass(), firstVaultName = "First").opened)
        // What `BiometricWrap.read` hands back: the payload the slot holds, which is
        // exactly what `enableBiometric` seals.
        val asWrapped = VaultKeyringSession.wrapPayload()!!
        VaultLifecycle.lockNow()

        assertTrue("a current wrap must open the keyring", 
            VaultKeyringSession.resolveFromWrap(store, asWrapped))
        assertEquals(1, VaultKeyringSession.vaults().size)
        assertEquals("First", VaultKeyringSession.vaults().single().name)
        assertFalse("and it opens no vault, like every other unlock", VaultSession.isUnlocked)
    }

    /**
     * A wrap written **before** the migration is refused rather than opening a vault
     * outside every keyring.
     *
     * It cannot be migrated here: re-sealing a slot needs a KEK and a biometric
     * unlock has none (I7). Refusing sends the user to the passphrase field, which is
     * the thing that fixes it.
     */
    @Test
    fun anOldLayoutWrapIsRefusedRatherThanOpeningAVaultOffTheList() {
        val (vmk, indexId, _) = oldLayoutInstall(items = 1)
        // Exactly what a pre-migration wrap holds: the vault's own key and index.
        val stale = OpenVault(vmk.copyOf(), indexId.copyOf(), 3)

        assertFalse(
            "an old-layout wrap must not open a vault that no keyring holds",
            VaultKeyringSession.resolveFromWrap(store, stale),
        )
        assertFalse(VaultKeyringSession.isOpen)
        assertFalse(VaultSession.isUnlocked)

        // And the passphrase path still fixes it, migrating as it goes.
        assertTrue(VaultLifecycle(slotsFile, store).unlock(pass()).opened)
        assertEquals(1, VaultKeyringSession.vaults().size)
    }

    /** A migration reports itself, so whatever sealed the old payload can be retired. */
    @Test
    fun aMigrationTellsTheCallerItHappened() {
        oldLayoutInstall(items = 1)
        var migrated = 0

        assertTrue(VaultLifecycle(slotsFile, store).unlock(pass(), onMigrated = { migrated++ }).opened)
        assertEquals("the migration must report itself exactly once", 1, migrated)

        VaultLifecycle.lockNow()
        assertTrue(VaultLifecycle(slotsFile, store).unlock(pass(), onMigrated = { migrated++ }).opened)
        assertEquals("a second unlock migrates nothing and reports nothing", 1, migrated)
    }

    // --- 4. one key in memory -------------------------------------------------

    /**
     * Opening a keyring unwraps no vault key, and opening a second vault zeroes the
     * first — the claim `vault-keyring` makes that cannot be read off the source.
     */
    @Test
    fun atMostOneVaultKeyIsUnwrappedAtATime() {
        val lifecycle = VaultLifecycle(slotsFile, store)
        assertTrue(lifecycle.create(pass(), firstVaultName = "First").opened)

        // A second vault on the same keyring.
        VaultKeyringSession.leaveVault()
        assertNotNull(VaultKeyringSession.createVault(store, "Second"))
        assertEquals(2, VaultKeyringSession.vaults().size)

        // A keyring open and no vault: nothing is unwrapped.
        assertFalse("no vault key may exist with no vault open", VaultSession.isUnlocked)
        assertEquals(-1, VaultKeyringSession.openVaultPosition())

        val first = VaultKeyringSession.openVault(0)!!
        val firstKey = first.vmk.copyOf()
        assertTrue(VaultSession.isUnlocked)

        val second = VaultKeyringSession.openVault(1)!!

        // The first vault's key was zeroed on the way past, so the session holds one
        // key and it is the second one's.
        assertFalse(
            "opening a second vault must not leave the first one's key in the session",
            VaultSession.vmk()!!.contentEquals(firstKey),
        )
        assertFalse("two vaults must not share a key", second.vmk.contentEquals(firstKey))
        assertEquals(1, VaultKeyringSession.openVaultPosition())

        // And leaving takes the last one with it.
        VaultKeyringSession.leaveVault()
        assertFalse(VaultSession.isUnlocked)
        assertNull(VaultSession.vmk())
        firstKey.fill(0)
    }

    /** Locking clears the keyring as well as the vault. */
    @Test
    fun lockingClearsTheKeyringAndTheOpenVault() {
        val lifecycle = VaultLifecycle(slotsFile, store)
        assertTrue(lifecycle.create(pass()).opened)
        assertTrue(VaultKeyringSession.isOpen)

        lifecycle.lock()
        VaultKeyringSession.lock()

        assertFalse(VaultKeyringSession.isOpen)
        assertTrue(VaultKeyringSession.vaults().isEmpty())
        assertNull(VaultSession.vmk())
        assertNull(VaultSession.metadataBlobId())
    }
}
