package com.app.vault

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.app.vault.ui.ExportTarget
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * Destroying a vault, and the ordering that makes the return safe — `delete-vault`.
 *
 * **The rule this exists to hold is D2's**: the vault is removed only once the return has
 * finished with nothing left. `Unvaulter` deletes an item's ciphertext only after writing
 * it out, reading it back and comparing it, so an index with rows left in it is an index
 * whose items are the *only* copy. Destroying at that point destroys them.
 *
 * That is why every assertion below about a failed return is about what **survived**.
 */
@RunWith(AndroidJUnit4::class)
class VaultDestroyTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var blobs: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultDestroy"
        const val PASS = "correct horse battery staple"
        const val OWN = "lantern quarry obsidian meadow"
        const val WRONG = "a different passphrase entirely"
        const val PANIC = "a panic phrase far from it"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        VaultKeyringSession.lock()
        root = File(ctx.filesDir, "destroy-${System.nanoTime()}").apply { mkdirs() }
        blobs = File(root, "blobs").apply { mkdirs() }
        store = BlobStore(blobs, File(root, "incoming"))
        vault = VaultLifecycle(File(root, "vault.slots"), store)
        assertTrue(vault.create(PASS.toByteArray()).opened)
    }

    @After
    fun tearDown() {
        VaultLifecycle.lockNow()
        VaultKeyringSession.lock()
        root.deleteRecursively()
    }

    // --- a destination that can refuse one named file -------------------------

    /**
     * [UnvaultTest]'s fake, with the one behaviour this file needs: refusing a chosen
     * file while the rest go through. A run that fails on *everything* would not test
     * D2 — the interesting state is the partial one, where destroying would be the
     * mistake.
     */
    private class PickyDestination(private val refuse: String) : ExportDestination {
        val files = LinkedHashMap<String, ByteArray>()

        override fun create(name: String, mime: String, dateTaken: Long?): ExportEntry {
            if (name == refuse) throw IllegalStateException("destination refused $name")
            files[name] = ByteArray(0)
            return ExportEntry(Uri.parse("fake:///$name"), name)
        }

        override fun openOutput(entry: ExportEntry): OutputStream =
            object : ByteArrayOutputStream() {
                override fun close() { files[entry.name] = toByteArray(); super.close() }
            }

        override fun openAppend(entry: ExportEntry): OutputStream =
            object : ByteArrayOutputStream() {
                override fun close() {
                    files[entry.name] = (files[entry.name] ?: ByteArray(0)) + toByteArray()
                    super.close()
                }
            }

        override fun openInput(entry: ExportEntry): InputStream =
            ByteArrayInputStream(files[entry.name] ?: ByteArray(0))

        override fun find(name: String): ExportEntry? =
            if (files.containsKey(name)) ExportEntry(Uri.parse("fake:///$name"), name) else null

        override fun finish(entry: ExportEntry) = Unit
        override fun discard(entry: ExportEntry) { files.remove(entry.name) }
        override fun sweep(): Int = 0
    }

    /** A folder on disk behaving as a mirror destination — `MirrorDeviceTest`'s shape. */
    private class LocalTarget(private val dir: File) : CloudMirror.Target {
        init { dir.mkdirs() }
        private fun fileOf(uri: Uri) = File(uri.path!!)
        override fun names(): Map<String, RemoteEntry> =
            dir.listFiles().orEmpty().filter { it.isFile }.associate {
                it.name to RemoteEntry(Uri.fromFile(it), it.length(), it.lastModified())
            }
        override fun create(name: String, mime: String, dateTaken: Long?): ExportEntry {
            val f = File(dir, name); f.createNewFile()
            return ExportEntry(Uri.fromFile(f), name)
        }
        override fun openOutput(entry: ExportEntry): OutputStream = fileOf(entry.uri).outputStream()
        override fun openInput(entry: ExportEntry): InputStream = fileOf(entry.uri).inputStream()
        override fun overwrite(uri: Uri): OutputStream = fileOf(uri).outputStream()
        override fun discard(entry: ExportEntry) { fileOf(entry.uri).delete() }
    }

    private fun indexId() = VaultSession.metadataBlobId()!!.copyOf()
    private fun vmk() = VaultSession.vmk()!!

    private fun addItem(name: String, size: Int): ByteArray {
        val content = ByteArray(size).also(rng::nextBytes)
        val written = store.write(ByteArrayInputStream(content), vmk())
        MetadataDb.commit(store, indexId(), vmk()) { db ->
            db.items += VaultItem(
                listOf(written.blobId), written.plaintextLength, null, name,
                null, null, 0L, emptyList(),
            )
        }
        return content
    }

    private fun itemsLeft() = MetadataDb.load(store, indexId(), vmk()).items

    // --- D2: the return, and what a failure leaves ---------------------------

    /**
     * The rule, stated as the state it protects: a return that could not write one file
     * out leaves that file in the vault, and the vault is therefore not destroyable yet.
     */
    @Test
    fun aReturnThatFailsOnOneItemLeavesExactlyThatItemInTheVault() {
        addItem("keeper.jpg", 4_000)
        addItem("goes.jpg", 4_000)

        val dest = PickyDestination(refuse = "keeper.jpg")
        val result = Unvaulter(store, dest, ExportManifest.openAt(dest))
            .exportAll(indexId(), vmk())

        assertEquals("one item could not be written out", 1, result.failed)
        assertEquals("the other one did", 1, result.exported)

        val left = itemsLeft()
        assertEquals("the vault keeps exactly what did not come out", 1, left.size)
        assertEquals("keeper.jpg", left.first().filename)
        assertTrue(
            "an index with rows left is what forbids the destroy — those are the only copy",
            left.isNotEmpty(),
        )
        // And the blob behind it is still on disk, which is the whole point.
        assertTrue(store.fileOf(left.first().blobIds.first()).exists())
    }

    /**
     * A complete return empties the index, and only then is the vault removable.
     *
     * The empty index **is** the statement that everything came out — `Unvaulter` removes
     * a row only after verifying it — which is why the app tests the index rather than a
     * worker's failure count.
     */
    @Test
    fun aCompleteReturnEmptiesTheIndexAndThenTheVaultGoes() {
        val a = addItem("one.jpg", 5_000)
        val b = addItem("two.jpg", 6_000)
        val position = VaultKeyringSession.openVaultPosition()

        val dest = PickyDestination(refuse = "nothing-is-refused")
        val result = Unvaulter(store, dest, ExportManifest.openAt(dest))
            .exportAll(indexId(), vmk())

        assertEquals(2, result.exported)
        assertEquals(0, result.failed)
        assertTrue("everything came out, so the index is empty", itemsLeft().isEmpty())

        // The files are readable at the destination, as ordinary bytes.
        assertTrue(dest.files["one.jpg"]!!.contentEquals(a))
        assertTrue(dest.files["two.jpg"]!!.contentEquals(b))

        assertTrue("and only now is the vault removed", VaultKeyringSession.removeVault(store, position, vmk().copyOf()))
        assertNull("its entry is gone from the keyring", VaultKeyringSession.vaults()
            .firstOrNull { it.position == position })
    }

    // --- D3: the backup goes with the vault -----------------------------------

    /**
     * A vault's destination is readable without opening it, which is what lets the
     * confirmation say the backup goes and lets the destroy actually empty it.
     */
    @Test
    fun aVaultsDestinationIsReadableWithoutOpeningIt() {
        val position = VaultKeyringSession.openVaultPosition()
        val folder = File(root, "remote").apply { mkdirs() }
        MetadataDb.commit(store, indexId(), vmk()) { db ->
            CloudMirror.setDestination(db, Uri.fromFile(folder))
        }
        // **`leaveVault`, not `lockNow`.** `lockNow` locks both levels, and with the
        // keyring locked there is no key to read a destination with — correctly. The
        // state a destroy actually happens in is the vault list's: keyring open, vault
        // closed, which is what leaving a vault produces. The key is the gate's.
        val key = vmk().copyOf()
        VaultKeyringSession.leaveVault()

        assertFalse("the precondition: the vault is closed", VaultSession.isUnlocked)
        assertTrue("and the keyring is not", VaultKeyringSession.isOpen)
        val found = VaultKeyringSession.destinationAt(store, position, key)
        key.fill(0)

        assertNotNull("a destroy must be able to find the backup it is about to empty", found)
        assertEquals(Uri.fromFile(folder).toString(), found)
    }

    @Test
    fun aVaultWithNoDestinationReportsNone() {
        val position = VaultKeyringSession.openVaultPosition()
        val key = vmk().copyOf()
        VaultKeyringSession.leaveVault()
        assertNull(VaultKeyringSession.destinationAt(store, position, key))
        key.fill(0)
    }

    /**
     * Emptying the backup takes the app's files and leaves everything else — the guard
     * that keeps a destroy from wiping a folder the user also keeps documents in.
     */
    @Test
    fun destroyingTheBackupLeavesFilesTheAppDidNotPutThereAlone() {
        addItem("one.jpg", 4_000)
        val folder = File(root, "remote").apply { mkdirs() }
        val names = CloudMirror.vaultNames(
            MetadataDb.load(store, indexId(), vmk()),
            indexId(),
            VaultKeyringSession.keyringBlobId(),
        )
        VaultLifecycle.lockNow()
        CloudMirror.runOn(LocalTarget(folder), blobs, names)
        val theirs = File(folder, "holiday.jpg").apply { writeBytes(ByteArray(16) { 9 }) }

        val removed = CloudMirror.destroyOn(LocalTarget(folder))

        assertTrue("the vault's blobs went", removed > 0)
        assertTrue("a file the app did not put there survives a destroy", theirs.exists())
        assertEquals(
            "and nothing of the vault's is left",
            setOf(theirs.name),
            folder.listFiles().orEmpty().map { it.name }.toSet(),
        )
    }

    // --- the destroy-only outcome ---------------------------------------------

    @Test
    fun destroyingWithoutReturningWritesNothingOutsideTheApp() {
        addItem("one.jpg", 4_000)
        addItem("two.jpg", 4_000)
        val position = VaultKeyringSession.openVaultPosition()
        val before = root.walkTopDown().filter { it.isFile }.count()

        assertTrue(VaultKeyringSession.removeVault(store, position, vmk().copyOf()))

        assertNull(VaultKeyringSession.vaults().firstOrNull { it.position == position })
        assertTrue(
            "a destroy removes files and creates none",
            root.walkTopDown().filter { it.isFile }.count() < before,
        )
        // The export target enum exists; nothing here reached for it.
        assertEquals(2, ExportTarget.entries.size)
    }

    // --- `protected-vault-destroy-gate` ------------------------------------------

    private fun protectedVault(name: String = "Papers"): Int =
        VaultKeyringSession.createVault(store, name) { p, set -> VaultKeyringSession.entryPassphraseKek(pass(OWN), p, set)!! }!!

    /**
     * A protected vault destroyed with the key its gate unsealed loses everything it held —
     * D2. Before this change its key was not reachable from the keyring, so its items, index and
     * capture store stayed behind as blobs while the dialog said it could not be recovered.
     */
    @Test
    fun aProtectedVaultDestroyedWithItsKeyLosesItsBlobsIndexAndCaptureStore() {
        VaultKeyringSession.leaveVault()
        val position = protectedVault()
        val folder = File(root, "remote").apply { mkdirs() }
        val kek = VaultKeyringSession.entryPassphraseKek(pass(OWN), position, VaultKeyringSession.vaults().single { it.position == position }.kdfSet)!!
        assertNotNull(VaultKeyringSession.openVaultWithPassphrase(position, kek))
        kek.fill(0)
        val blob = addItem("one.jpg", 4_000).let { itemsLeft().single().blobIds.single() }
        val index = indexId()
        MetadataDb.commit(store, index, vmk()) { db -> CloudMirror.setDestination(db, Uri.fromFile(folder)) }
        // Its own capture store, as its first capture leaves one.
        val captureKey = VaultKeys.captureStoreKey(vmk())
        val captureStoreId = VaultKeys.metadataBlobId(captureKey)
        MetadataDb.empty().save(store, captureStoreId, captureKey)
        captureKey.fill(0)
        VaultKeyringSession.leaveVault()
        assertTrue(store.fileOf(captureStoreId).exists())

        val key = vault.destroyKey(position, pass(OWN))
        assertNotNull("its own passphrase passes the gate", key)
        assertEquals(
            "its backup is named before the choice, as an unprotected vault's is",
            Uri.fromFile(folder).toString(),
            VaultKeyringSession.destinationAt(store, position, key!!),
        )
        assertTrue(VaultKeyringSession.removeVault(store, position, key))
        key.fill(0)

        assertNull("off the list", VaultKeyringSession.vaults().firstOrNull { it.position == position })
        assertFalse("its item's blob is deleted", store.fileOf(blob).exists())
        assertFalse("its index is deleted", store.fileOf(index).exists())
        assertFalse("its capture store is deleted", store.fileOf(captureStoreId).exists())
    }

    /**
     * The gate — D1. The passphrase that opens the vault, and nothing else: a protected vault
     * refuses the list's, an unprotected one refuses a wrong passphrase and the panic phrase,
     * and the right one returns that vault's key.
     */
    @Test
    fun destroyKeyTakesOnlyThePassphraseThatOpensTheVault() {
        val listPosition = VaultKeyringSession.openVaultPosition()
        val listVmk = vmk().copyOf()
        VaultKeyringSession.leaveVault()
        assertEquals(VaultLifecycle.PanicResult.DONE, vault.setPanic(pass(PASS), pass(PANIC)))
        val protectedPosition = protectedVault()

        assertNull("the list's passphrase does not open a protected vault", vault.destroyKey(protectedPosition, pass(PASS)))
        assertNull("nor does a wrong one", vault.destroyKey(protectedPosition, pass(WRONG)))
        assertNull("a wrong passphrase", vault.destroyKey(listPosition, pass(WRONG)))
        assertNull("the panic phrase is refused as a wrong passphrase", vault.destroyKey(listPosition, pass(PANIC)))
        assertNull("an empty position", vault.destroyKey(15, pass(PASS)))
        assertTrue("nothing was removed", VaultKeyringSession.vaults().size == 2)

        val started = SystemClock.elapsedRealtime()
        val own = vault.destroyKey(protectedPosition, pass(OWN))
        Log.i(TAG, "the gate (own passphrase) took ${SystemClock.elapsedRealtime() - started} ms")
        val ownVmk = VaultKeyringSession.entryPassphraseKek(pass(OWN), protectedPosition, VaultKeys.RELEASED_KDF_PARAMS.lastIndex)!!
            .let { k -> VaultKeyringSession.vaultKeyForKit(protectedPosition, k).also { k.fill(0) } }
        assertArrayEquals("its own passphrase returns its key", ownVmk, own)

        val listStarted = SystemClock.elapsedRealtime()
        val list = vault.destroyKey(listPosition, pass(PASS))
        Log.i(TAG, "the gate (list passphrase) took ${SystemClock.elapsedRealtime() - listStarted} ms")
        assertArrayEquals("the list's passphrase returns an unprotected vault's key", listVmk, list)
        listOf(listVmk, ownVmk!!, own!!, list!!).forEach { it.fill(0) }
    }

    /** D5. The return opens its target from a proven key, leaving whatever was open first. */
    @Test
    fun openVaultWithKeyOpensTheTargetAndLeavesThePreviousVault() {
        val first = VaultKeyringSession.openVaultPosition()
        val previous = vmk()
        val position = protectedVault()
        val key = vault.destroyKey(position, pass(OWN))!!

        val opened = VaultKeyringSession.openVaultWithKey(position, key)
        assertNotNull(opened)
        assertTrue("the previous vault's key was zeroed", previous.all { it == 0.toByte() })
        assertEquals("the target is the open vault", position, VaultKeyringSession.openVaultPosition())
        assertArrayEquals("on the key it was given", key, VaultSession.vmk())
        assertTrue("and its index reads", runCatching { MetadataDb.load(store, indexId(), vmk()) }.isSuccess)
        key.fill(0)
        assertTrue("the session holds its own copy", VaultSession.vmk()!!.any { it != 0.toByte() })
        assertNull("an empty position opens nothing", VaultKeyringSession.openVaultWithKey(15, ByteArray(32)))
        assertTrue(first != position)
    }

    /**
     * `per-vault-intruder-capture`. Destroying a vault removes its enrolment, its queued captures
     * with their sealed blobs and its notices from the keyring's capture store; and, its key in
     * hand as the open vault's, its own capture store's index and blobs.
     */
    @Test
    fun destroyRemovesTheVaultsEnrolmentQueueAndCaptureStore() {
        VaultKeyringSession.leaveVault()
        val kek = ByteArray(32) { 7 }
        val position = VaultKeyringSession.createVault(store, "Private") { _, _ -> kek.copyOf() }!!
        val id = enrolVaultCapture(store, position, kek, setOf(Capture.Mode.PHOTO, Capture.Mode.AUDIO))
        queueVaultCapture(store, id, Capture.Mode.PHOTO, capturePhotoBytes(), System.currentTimeMillis() - 5_000)

        val opened = VaultKeyringSession.openVaultWithPassphrase(position, kek)!!
        withKeyringCaptureStore(store) { CaptureIngest.ingestVault(store, it, opened) }
        val (db, _) = vaultCaptureStore(store, opened)!!
        val held = db.items.flatMap { it.blobIds } + db.thumbPacks.map { it.id } + listOfNotNull(db.logBlobId)
        val storeId = VaultKeys.captureStoreKey(opened.vmk).let { k -> VaultKeys.metadataBlobId(k).also { k.fill(0) } }
        val queued = queueVaultCapture(store, id, Capture.Mode.AUDIO, ByteArray(3_000) { 1 })
        val key = VaultKeyringSession.captureStoreKey()!!
        try {
            CaptureIngest.recordPermissions(
                store, key,
                listOf(CapturePermissions.Transition(TamperLog.PERMISSION_MICROPHONE, TamperLog.PERMISSION_LOST, 1_000L)),
                homeModes = emptySet(),
            )
        } finally {
            key.fill(0)
        }
        assertEquals(1, keyringCaptureStore(store).vaultNotices.size)
        assertTrue(store.fileOf(storeId).exists())

        assertTrue(VaultKeyringSession.removeVault(store, position, opened.vmk.copyOf()))

        val ring = keyringCaptureStore(store)
        assertNull("no enrolment", CaptureStore.enrolment(ring, id))
        assertTrue("no queued capture", ring.vaultPending.isEmpty())
        assertTrue("no queued notice", ring.vaultNotices.isEmpty())
        assertFalse("the queued blob is deleted", store.fileOf(queued).exists())
        assertFalse("the capture store's index is deleted", store.fileOf(storeId).exists())
        held.forEach { assertFalse("the capture store's blobs are deleted", store.fileOf(it).exists()) }
    }
}
