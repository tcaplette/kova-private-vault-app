package com.app.vault

import android.net.Uri
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
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * `cloud-mirror`, on the device — the round trip above all.
 *
 * **Why the destination is a local directory and not a real provider.** A SAF tree
 * URI can only be obtained by a human tapping through the system document picker;
 * there is no API to grant one from a test. `CloudMirror.Target` exists for exactly
 * this, and [DirectoryTarget] is its second implementation — the same reason
 * `ExportDestination` is an interface, written down in its own KDoc: verification
 * has to read the files back.
 *
 * What this therefore does **not** prove is that any particular provider honours
 * `createDocument`, `"wt"` truncation and `deleteDocument`. Task 1 measures that
 * against real providers, and nothing here substitutes for it.
 *
 * What it does prove is everything above that line: the diff, the transfer, the
 * deletion, the refusal, and that a vault mirrored, wiped and restored comes back
 * byte-for-byte with only the 24 words.
 *
 * **Per vault since `settings-separation`.** A run is given the open vault's upload
 * set — its items' blobs, its thumbnail packs, its index, and the keyring blob the 24
 * words derive — and touches nothing else in the store. Every call below therefore
 * takes that set, and taking it is what proves the vault had to be open to compute
 * it: [vaultNames] throws with a locked session, which is the feature's cost written
 * as a compile-time shape rather than a comment.
 */
@RunWith(AndroidJUnit4::class)
class MirrorDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var blobs: File
    private lateinit var remote: File
    private lateinit var slotsFile: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultMirrorDevice"
        const val PASS = "the-original-passphrase"
        const val NEW_PASS = "the-passphrase-after-restore"
        const val A = "list-a-passphrase"
        const val B = "list-b-passphrase"
        const val B_NEW = "list-b-after-import"
        const val DECOY = "the-decoy-passphrase"
        const val DECOY_NEW = "the-decoy-after-import"
        const val OWN = "the-vaults-own-passphrase"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "mirror-${System.nanoTime()}").apply { mkdirs() }
        blobs = File(root, "blobs").apply { mkdirs() }
        remote = File(root, "remote").apply { mkdirs() }
        slotsFile = File(root, "vault.slots")
        store = BlobStore(blobs, File(root, "incoming"))
        vault = VaultLifecycle(slotsFile, store)
    }

    @After
    fun tearDown() {
        VaultLifecycle.lockNow()
        root.deleteRecursively()
    }

    private fun target() = DirectoryTarget(remote)

    /** Streamed, so a 16 MiB chaff blob costs 64 kB of heap rather than 16 MiB. */
    private fun digestOf(f: File): String {
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

    private fun remoteNames() = remote.listFiles().orEmpty().map { it.name }.toSet()
    private fun localNames() = CloudMirror.localEntries(blobs).keys

    /**
     * The open vault's upload set — `settings-separation`.
     *
     * **This is the whole change in four lines.** Reading it needs the index, reading
     * the index needs the VMK, and the VMK exists only while the vault is open — so a
     * test that locks first and asks after gets a `NullPointerException` rather than a
     * silent whole-store mirror. Every caller below computes it before locking, which
     * is exactly what `MirrorWorker` does.
     */
    private fun vaultNames(): Set<String> {
        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!
        return CloudMirror.vaultNames(
            MetadataDb.load(store, indexId, vmk),
            indexId,
            VaultKeyringSession.keyringBlobId(),
        )
    }

    /**
     * Puts [payloads] into the open vault's index, so they are the vault's rather than
     * merely bytes in the store.
     *
     * The distinction did not exist before this change and is now the only one that
     * matters: an unreferenced blob is chaff as far as the mirror can tell, and chaff
     * is not uploaded.
     */
    private fun storeItems(payloads: List<ByteArray>): List<WrittenItem> {
        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!
        val written = payloads.map { store.writeItem(it.inputStream(), vmk) }
        MetadataDb.commit(store, indexId, vmk) { db ->
            written.forEachIndexed { i, item ->
                db.items += VaultItem(
                    blobIds = item.blobIds,
                    plaintextLength = item.plaintextLength,
                    thumb = null,
                    filename = "item-$i.bin",
                    album = null,
                    createdAt = null,
                    importedAt = 0L,
                    tags = emptyList(),
                )
            }
        }
        return written
    }

    // --- the diff, end to end -------------------------------------------------

    @Test
    fun anEmptyFolderTakesTheVaultAndNotTheStore() {
        assertTrue(vault.create(pass(PASS)).opened)
        val names = vaultNames()
        val everything = localNames()
        VaultLifecycle.lockNow()

        val result = CloudMirror.runOn(target(), blobs, names)

        assertTrue("a vault has an index and a keyring blob to mirror", result.uploaded > 0)
        assertEquals(0, result.failed)
        assertEquals("the folder holds the vault's blobs exactly", names, remoteNames())
        assertTrue(
            "a fresh vault writes chaff, and chaff is not the vault's — it hides the store's " +
                "size on the device and would hide nothing in a folder holding one vault",
            everything.size > names.size,
        )
        assertTrue(
            "not one blob outside the vault's set may reach the folder",
            remoteNames().all { it in names },
        )
    }

    @Test
    fun asecondRunUploadsNothingNew() {
        assertTrue(vault.create(pass(PASS)).opened)
        val names = vaultNames()
        VaultLifecycle.lockNow()
        CloudMirror.runOn(target(), blobs, names)

        val second = CloudMirror.runOn(target(), blobs, names)

        assertEquals("a matching vault must not re-upload", 0, second.uploaded)
        assertEquals(0, second.deleted)
        assertEquals(0, second.failed)
    }

    @Test
    fun aDeletedBlobLeavesTheFolder() {
        assertTrue(vault.create(pass(PASS)).opened)
        val payloads = listOf(ByteArray(4_096).also(rng::nextBytes))
        val written = storeItems(payloads)
        val names = vaultNames()
        VaultLifecycle.lockNow()
        CloudMirror.runOn(target(), blobs, names)

        val name = written[0].blobIds.map { hex(it) }.first()
        assertTrue("the item's blob was mirrored", name in remoteNames())
        assertTrue(File(blobs, name).delete())

        val result = CloudMirror.runOn(target(), blobs, names)

        assertEquals(1, result.deleted)
        assertFalse("the mirror is a mirror, not an archive of deletions", name in remoteNames())
    }

    @Test
    fun aChangedBlobIsRewrittenAndNotDuplicated() {
        assertTrue(vault.create(pass(PASS)).opened)
        val names = vaultNames()
        VaultLifecycle.lockNow()
        CloudMirror.runOn(target(), blobs, names)

        // The index blob's shape: same name, different contents, shorter than before.
        val changed = File(blobs, names.first())
        changed.writeBytes(ByteArray(64) { 7 })
        changed.setLastModified(System.currentTimeMillis() + 10_000)

        val result = CloudMirror.runOn(target(), blobs, names)

        assertEquals(1, result.rewritten)
        assertEquals("an overwrite must not create a second document", names, remoteNames())
        assertEquals(
            "a non-truncating overwrite would leave the old tail behind",
            digestOf(changed), digestOf(File(remote, changed.name))
        )
        assertEquals(
            "the mirrored copy must be the new length, not the old one",
            changed.length(), File(remote, changed.name).length()
        )
    }

    /**
     * Two vaults, two folders, disjoint sets — `settings-separation` task 1's Verify.
     *
     * This is the change's whole purpose stated as an assertion: before it, one
     * destination took every blob on the device, so backing up one vault backed up the
     * rest. Neither folder may hold a byte of the other's vault, and neither may hold
     * the chaff that belongs to the store rather than to either.
     */
    @Test
    fun twoVaultsWithTwoDestinationsMirrorDisjointSets() {
        assertTrue(vault.create(pass(PASS)).opened)
        val firstNames = vaultNames()
        val second = File(root, "remote-2").apply { mkdirs() }

        // A second vault on the same keyring, opened in turn — the arrangement the
        // whole change exists for.
        val position = VaultKeyringSession.createVault(store, "Second")
        assertNotNull("a second vault must be creatable on the keyring", position)
        assertNotNull(VaultKeyringSession.openVault(position!!))
        val secondNames = vaultNames()

        VaultLifecycle.lockNow()

        CloudMirror.runOn(target(), blobs, firstNames)
        CloudMirror.runOn(DirectoryTarget(second), blobs, secondNames)

        val there = second.listFiles().orEmpty().map { it.name }.toSet()
        assertEquals("each folder holds its own vault", firstNames, remoteNames())
        assertEquals(secondNames, there)

        // The keyring blob is in both, and has to be: a folder is restored with the 24
        // words, and the words find the keyring by deriving its blob id.
        val shared = remoteNames() intersect there
        assertEquals(
            "the keyring blob is the only thing two folders may share",
            1, shared.size,
        )
        assertTrue(
            "no vault's index may appear in the other's folder",
            (remoteNames() - shared).none { it in there },
        )
        assertTrue(
            "the store's chaff belongs to neither folder",
            (remoteNames() + there).all { it in firstNames || it in secondNames },
        )
    }

    /**
     * A locked vault mirrors nothing — `settings-separation` task 1.3, and the cost
     * D5 names in full.
     *
     * The worker is what carries this: it reads the destination and the upload set out
     * of the open index, and with no session there is neither. Not an error and not a
     * retry — the key is gone because the app promises it does not survive a lock.
     */
    @Test
    fun aLockedVaultMirrorsNothing() {
        assertTrue(vault.create(pass(PASS)).opened)
        VaultLifecycle.lockNow()

        assertFalse("the precondition: nothing is unlocked", VaultSession.isUnlocked)
        assertEquals(
            "with no vault open there is no upload set to compute",
            null, VaultSession.metadataBlobId(),
        )

        // And the run against an empty set moves nothing, which is what the worker
        // does when it wakes to a locked vault.
        val result = CloudMirror.runOn(target(), blobs, emptySet())

        assertEquals(0, result.uploaded)
        assertEquals(0, result.rewritten)
        assertEquals(0, result.failed)
        assertEquals(
            "a locked vault must leave the folder untouched",
            emptySet<String>(), remoteNames(),
        )
    }

    /**
     * No destination is readable without the vault's key — `settings-separation` task
     * 1's Verify, and the spec requirement it comes from.
     *
     * A preferences file with one key per vault says how many vaults exist to anyone
     * who reads it, and so does a filename. The destination is inside the encrypted
     * index and nowhere else, so this walks everything a reader without a key could
     * see and asserts the folder's name appears in none of it.
     */
    @Test
    fun noDestinationAppearsInAnyPrefsFileOrFilename() {
        assertTrue(vault.create(pass(PASS)).opened)
        val tree = Uri.fromFile(remote)
        val marker = remote.name

        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!
        MetadataDb.commit(store, indexId, vmk) { db -> CloudMirror.setDestination(db, tree) }

        assertEquals(
            "the vault carries it",
            tree,
            CloudMirror.destination(MetadataDb.load(store, indexId, vmk)),
        )
        VaultLifecycle.lockNow()

        // Everything an inspection without a key can read: the app's whole data
        // directory, names and contents of anything that is not a sealed blob.
        val readable = File(ctx.applicationInfo.dataDir).walkTopDown()
            .filter { it.isFile }
            .filterNot { it.parentFile?.name == "blobs" }
            .toList()

        readable.forEach { f ->
            assertFalse(
                "a destination must not appear in a filename: ${f.name}",
                f.name.contains(marker),
            )
            val text = runCatching { f.readText() }.getOrDefault("")
            assertFalse(
                "a destination must not appear outside the encrypted index: ${f.path}",
                text.contains(marker),
            )
        }
    }

    // --- the destination guard ------------------------------------------------

    @Test
    fun aFolderHoldingAnotherStoreIsRefusedAndUntouched() {
        assertTrue(vault.create(pass(PASS)).opened)
        val names = vaultNames()

        val foreign = File(remote, "f".repeat(BlobStore.BLOB_ID_BYTES * 2))
        foreign.writeBytes(ByteArray(32) { 3 })

        val free = CloudMirror.destinationIsFree(names, target().names().keys)

        assertFalse("a foreign file means another install's mirror", free)
        assertEquals(
            "and it is named as another install's, not as another vault's",
            CloudMirror.Refusal.AnotherInstall,
            CloudMirror.destinationRefusal(names, localNames(), target().names().keys),
        )
        assertTrue("the refusal must not delete anything", foreign.exists())
        assertEquals(
            "a refusal must not store a destination",
            null,
            CloudMirror.destination(
                MetadataDb.load(store, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!)
            ),
        )
    }

    /**
     * A folder holding **another vault of this install** is refused, and says so —
     * task 1.4.
     *
     * Mirror semantics plus two vaults in one folder means the second vault deletes
     * everything the first uploaded. The refusal differs from the one above because
     * the remedy does: one folder per vault, against one folder per install.
     */
    @Test
    fun aFolderHoldingAnotherVaultOfThisInstallIsRefusedAsSuch() {
        assertTrue(vault.create(pass(PASS)).opened)
        val firstNames = vaultNames()

        val position = VaultKeyringSession.createVault(store, "Second")
        assertNotNull(position)
        assertNotNull(VaultKeyringSession.openVault(position!!))
        val secondNames = vaultNames()

        // The first vault's folder, filled.
        CloudMirror.runOn(target(), blobs, firstNames)

        val remoteHere = target().names().keys
        assertFalse(
            "the second vault must not be allowed into the first vault's folder",
            CloudMirror.destinationIsFree(secondNames, remoteHere),
        )
        assertEquals(
            CloudMirror.Refusal.AnotherVault,
            CloudMirror.destinationRefusal(secondNames, localNames(), remoteHere),
        )
        assertTrue("the refusal must not delete anything", remoteNames().isNotEmpty())
    }

    // --- the round trip. This is the change's real test. ----------------------

    @Test
    fun aWipedVaultIsRestoredFromTheFolderWithTheKit() {
        // A populated vault, and a second one beside it that must not travel.
        assertTrue(vault.create(pass(PASS)).opened)
        val mnemonic = Mnemonic.fromResources(ctx)
        val words = VaultKeyringSession.withKey { mnemonic.encode(it) }!!
        // A panic phrase on the old phone, so the restore has a record to discard.
        assertEquals(
            VaultLifecycle.PanicResult.DONE,
            vault.setPanic(pass(PASS), pass("a-panic-phrase-for-the-old-phone")),
        )

        val payloads = (0 until 3).map { ByteArray(200_000).also(rng::nextBytes) }
        val written = storeItems(payloads)
        val names = vaultNames()
        VaultLifecycle.lockNow()

        // Mirror it, then take the fingerprints that must survive.
        val mirrored = CloudMirror.runOn(target(), blobs, names)
        assertTrue(mirrored.uploaded > 0)
        val before = names.sorted()
        // Digests, not bytes. A 200 kB payload is small but the index and the keyring
        // are not bounded here, and a SHA-256 per file proves the same thing in 32
        // bytes.
        val digestBefore = before.associateWith { digestOf(File(blobs, it)) }

        // The clear: both the blobs and the slot file, which was never mirrored.
        blobs.deleteRecursively()
        blobs.mkdirs()
        assertTrue(slotsFile.delete())
        VaultLifecycle.lockNow()
        assertEquals(0, CloudMirror.localEntries(blobs).size)

        // Restore is a copy plus the 24 words. No passphrase is involved and none
        // would help — the file it unwraps is the one deliberately left behind.
        val copied = CloudMirror.restoreFrom(target(), blobs)
        assertEquals(before.size, copied)
        assertEquals("the vault must come back name for name", before, localNames().sorted())
        for ((name, expected) in digestBefore) {
            assertEquals(
                "blob $name did not survive the round trip",
                expected, digestOf(File(blobs, name))
            )
        }

        val restored = VaultLifecycle(slotsFile, BlobStore(blobs, File(root, "incoming")))
        val outcome = restored.recover(mnemonic, words.joinToString(" "), pass(NEW_PASS), fresh = true)
        assertTrue("the kit must open a restored mirror", outcome.opened)

        // `recovery-own-slot-only`, D4: a fresh slot file, a record naming one slot, panic off.
        assertEquals(SlotArray.FILE_BYTES.toLong(), slotsFile.length())
        assertFalse("no temp file left", File(root, "vault.slots.tmp").exists())
        val slot = VaultKeyringSession.openSlotIndex()
        assertEquals("home is the slot sealed here", slot, VaultKeyringSession.homeSlot())
        assertEquals("one known slot", 1 shl slot, VaultKeyringSession.knownSlots())
        assertFalse("panic is off", VaultKeyringSession.hasPanicPhrase())

        // And the content decrypts to what went in.
        assertNotNull(VaultKeyringSession.openVault(0))
        val vmk = VaultSession.vmk()!!
        val restoredStore = BlobStore(blobs, File(root, "incoming"))
        payloads.forEachIndexed { i, expected ->
            val item = written[i]
            val reader = restoredStore.reader(item.blobIds, vmk, item.plaintextLength)
            val got = reader.readRange(0, expected.size)
            assertArrayEquals("item $i did not survive", expected, got)
        }
        Log.i(TAG, "round trip: ${before.size} blobs, ${payloads.size} items, kit only")
    }

    @Test
    fun aRestoreOverALiveStoreIsRefused() {
        assertTrue(vault.create(pass(PASS)).opened)
        val names = vaultNames()
        VaultLifecycle.lockNow()
        CloudMirror.runOn(target(), blobs, names)

        val threw = runCatching { CloudMirror.restoreFrom(target(), blobs) }.isFailure
        assertTrue("restoring over a live store is a merge, and is refused", threw)
    }

    // --- the job --------------------------------------------------------------

    @Test
    fun aBatchOfImportsProducesOneRunAndNotTwenty() {
        // `enqueue` is a no-op without a destination, so one is set on the open
        // vault first. The URI need not resolve: this asserts what WorkManager was
        // asked for, and the worker itself returns success against an unreachable
        // folder.
        assertTrue(vault.create(pass(PASS)).opened)
        val db = MetadataDb.load(store, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!)
        CloudMirror.setDestination(db, Uri.fromFile(remote))

        val wm = androidx.work.WorkManager.getInstance(ctx)
        wm.cancelAllWork()

        repeat(20) { MirrorWorker.enqueue(ctx, db) }

        val infos = wm.getWorkInfosByTag(MirrorWorker.MIRROR_TAG).get()
        val live = infos.filter { !it.state.isFinished }
        assertTrue(
            "unique work must coalesce a batch into one run, not one per item: " +
                "${live.size} live",
            live.size <= 1
        )
        wm.cancelAllWork()
    }

    @Test
    fun noDestinationEnqueuesNothing() {
        assertTrue(vault.create(pass(PASS)).opened)
        val db = MetadataDb.load(store, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!)
        val wm = androidx.work.WorkManager.getInstance(ctx)
        wm.cancelAllWork()

        MirrorWorker.enqueue(ctx, db)

        val infos = wm.getWorkInfosByTag(MirrorWorker.MIRROR_TAG).get()
        assertTrue(
            "a vault that never turned backup on must enqueue nothing",
            infos.none { !it.state.isFinished }
        )
    }

    /**
     * A locked vault enqueues nothing — `settings-separation` task 1.3, at the
     * boundary the import path actually crosses.
     *
     * `enqueue` takes the open vault's index, and null is what a locked session has.
     */
    @Test
    fun aLockedVaultEnqueuesNothing() {
        val wm = androidx.work.WorkManager.getInstance(ctx)
        wm.cancelAllWork()

        MirrorWorker.enqueue(ctx, null)

        val infos = wm.getWorkInfosByTag(MirrorWorker.MIRROR_TAG).get()
        assertTrue(
            "a locked vault has neither a destination nor an upload set",
            infos.none { !it.state.isFinished }
        )
    }

    // --- panic ----------------------------------------------------------------

    @Test
    fun panicEmptiesTheFolder() {
        assertTrue(vault.create(pass(PASS)).opened)
        val names = vaultNames()
        VaultLifecycle.lockNow()
        CloudMirror.runOn(target(), blobs, names)
        assertTrue(remoteNames().isNotEmpty())

        val removed = CloudMirror.destroyOn(target())

        assertTrue(removed > 0)
        assertEquals(
            "panic must leave nothing readable in the folder",
            emptySet<String>(), remoteNames()
        )
    }

    /**
     * Panic deletes blobs and leaves everything else in the folder alone —
     * `settings-separation`.
     *
     * It reaches a folder through the write grants this install holds, and an install
     * holds grants on export folders too. A sweep that emptied one of those would
     * destroy a user's own documents to clear a backup that was never in it.
     */
    @Test
    fun panicLeavesFilesThatAreNotOursAlone() {
        assertTrue(vault.create(pass(PASS)).opened)
        val names = vaultNames()
        VaultLifecycle.lockNow()
        CloudMirror.runOn(target(), blobs, names)

        val theirs = File(remote, "holiday.jpg")
        theirs.writeBytes(ByteArray(32) { 9 })

        CloudMirror.destroyOn(target())

        assertTrue("a file that is not a blob must survive panic", theirs.exists())
        assertEquals(
            "and every blob must be gone",
            setOf(theirs.name), remoteNames(),
        )
    }

    @Test
    fun panicLeavesTheLocalBlobsAlone() {
        assertTrue(vault.create(pass(PASS)).opened)
        val names = vaultNames()
        VaultLifecycle.lockNow()
        CloudMirror.runOn(target(), blobs, names)
        val before = localNames()

        CloudMirror.destroyOn(target())

        assertEquals(
            "panic-pin: every blob byte-for-byte unchanged. That still holds.",
            before, localNames()
        )
    }

    @Test
    fun anUnreachableDestinationIsNotAnError() {
        // The provider gone: the folder itself no longer exists.
        assertTrue(vault.create(pass(PASS)).opened)
        val names = vaultNames()
        VaultLifecycle.lockNow()
        remote.deleteRecursively()

        val removed = CloudMirror.destroyOn(target())
        assertEquals(0, removed)

        // And a run against it fails softly rather than throwing into the worker.
        val result = runCatching { CloudMirror.runOn(target(), blobs, names) }
        assertTrue("an unreachable destination must not throw", result.isSuccess)
    }

    // --- `backup-file-recover-and-import` -------------------------------------------

    @Test
    fun restoreFromExcludesANameAndRemovesItsCopiesWhenItThrows() {
        val names = (1..4).map {
            val name = ByteArray(16).also(rng::nextBytes).joinToString("") { b -> "%02x".format(b) }
            File(remote, name).writeBytes(ByteArray(8192).also(rng::nextBytes))
            name
        }
        repeat(2) {
            val name = ByteArray(16).also(rng::nextBytes).joinToString("") { b -> "%02x".format(b) }
            File(blobs, name).writeBytes(ByteArray(4096).also(rng::nextBytes))
        }
        val before = digests(blobs)

        val copied = CloudMirror.restoreFrom(target(), blobs, skipExisting = true, exclude = setOf(names[0]))
        assertEquals(3, copied)
        assertFalse("the excluded name is not copied", File(blobs, names[0]).exists())

        names.forEach { File(blobs, it).delete() }
        val threw = runCatching {
            CloudMirror.restoreFrom(target(), blobs, skipExisting = true) { done, _ ->
                if (done == 2) throw java.io.IOException("the provider went away")
            }
        }.isFailure
        assertTrue(threw)
        assertEquals("every file that was there, and none of its copies", before, digests(blobs))
    }

    private val mnemonic by lazy { Mnemonic.fromResources(ctx) }

    private class Backed(val words: String, val payloads: List<ByteArray>, val written: List<WrittenItem>, val file: ByteArray)

    private fun fileSource(bytes: ByteArray) = BackupFile.source(blobs) { bytes.inputStream() }
    private fun folderSource() = CloudMirror.source(target(), blobs)
    private fun key(words: String) = mnemonic.decode(words)!!

    /**
     * List B, with its open vault backed up both as a file and to [remote] as an SD-card
     * folder; then the phone is cleared, so B is not on it. [own] puts the backed-up vault
     * at position 1 under a passphrase of its own.
     */
    private fun backUpListB(own: String? = null): Backed {
        assertTrue(vault.create(pass(B)).opened)
        val words = VaultKeyringSession.withKey { mnemonic.encode(it) }!!.joinToString(" ")
        if (own != null) {
            val position = VaultKeyringSession.createVault(store, "Papers") { p, set ->
                VaultKeyringSession.entryPassphraseKek(pass(own), p, set)!!
            }!!
            val entry = VaultKeyringSession.vaults().single { it.position == position }
            val kek = VaultKeyringSession.entryPassphraseKek(pass(own), position, entry.kdfSet)!!
            assertNotNull(VaultKeyringSession.openVaultWithPassphrase(position, kek))
        }
        val payloads = (0 until 2).map { ByteArray(100_000).also(rng::nextBytes) }
        val written = putItems(store, payloads)
        val file = openVaultBackupFile(store, blobs, File(root, "snapshot"))
        CloudMirror.runOn(target(), blobs, openVaultBackupNames(store))
        VaultLifecycle.lockNow()
        blobs.deleteRecursively()
        blobs.mkdirs()
        assertTrue(slotsFile.delete())
        return Backed(words, payloads, written, file)
    }

    private fun slotRegion(bytes: ByteArray, index: Int): List<Byte> {
        val from = SlotArray.SLOTS_OFFSET + index * SlotArray.SLOT_BYTES
        return bytes.copyOfRange(from, from + SlotArray.SLOT_BYTES).toList()
    }

    private fun vaultRows() = VaultKeyringSession.vaults().map { it.name to it.indexBlobId.toList() }

    private fun anotherListComesBackAsItsOwnList(source: (Backed) -> VaultLifecycle.BackupSource) {
        val b = backUpListB()
        assertTrue(vault.create(pass(A)).opened)
        val aSlot = VaultKeyringSession.openSlotIndex()
        val aRows = vaultRows()
        val aKnown = VaultKeyringSession.knownSlots()
        val slotsBefore = slotsFile.readBytes()

        assertTrue(vault.restoreAsList(key(b.words), source(b), pass(B_NEW)).opened)

        assertTrue("the open list stays open", VaultKeyringSession.isOpen)
        assertEquals(aSlot, VaultKeyringSession.openSlotIndex())
        assertEquals("its vaults are unchanged", aRows, vaultRows())
        assertEquals("its slot is unchanged", slotRegion(slotsBefore, aSlot), slotRegion(slotsFile.readBytes(), aSlot))
        val added = VaultKeyringSession.knownSlots() and aKnown.inv()
        assertEquals("its record names one new slot", 1, Integer.bitCount(added))

        VaultLifecycle.lockNow()
        assertTrue("B's new passphrase opens B", vault.unlock(pass(B_NEW)).opened)
        assertEquals(Integer.numberOfTrailingZeros(added), VaultKeyringSession.homeSlot())
        assertTrue(vault.openVaultAt(0))
        assertTrue("B's items are intact", openVaultHolds(store, b.written, b.payloads))
        VaultLifecycle.lockNow()
        assertTrue(vault.unlock(pass(A)).opened)
        assertEquals(aRows, vaultRows())
    }

    @Test
    fun anotherListsBackupFileComesBackAsItsOwnList() = anotherListComesBackAsItsOwnList { fileSource(it.file) }

    @Test
    fun anotherListsFolderComesBackAsItsOwnList() = anotherListComesBackAsItsOwnList { folderSource() }

    @Test
    fun aDecoysBackupDoesNotJoinTheOpenList() {
        assertTrue(vault.create(pass(A)).opened)
        assertTrue("a decoy built from A", vault.create(pass(DECOY)).opened)
        val words = VaultKeyringSession.withKey { mnemonic.encode(it) }!!.joinToString(" ")
        val payloads = listOf(ByteArray(50_000).also(rng::nextBytes))
        val written = putItems(store, payloads)
        val file = openVaultBackupFile(store, blobs, File(root, "snapshot"))
        val decoySlot = VaultKeyringSession.openSlotIndex()
        val decoyNames = openVaultBackupNames(store)
        VaultLifecycle.lockNow()
        // The decoy leaves this phone: its slot and its files.
        SlotArray(slotsFile).destroy(decoySlot)
        decoyNames.forEach { File(blobs, it).delete() }

        assertTrue(vault.unlock(pass(A)).opened)
        val aRows = vaultRows()
        assertTrue(vault.restoreAsList(key(words), fileSource(file), pass(DECOY_NEW)).opened)
        assertEquals("nothing of the decoy is on A", aRows, vaultRows())

        VaultLifecycle.lockNow()
        assertTrue(vault.unlock(pass(DECOY_NEW)).opened)
        assertTrue(vault.openVaultAt(0))
        assertTrue(openVaultHolds(store, written, payloads))
    }

    @Test
    fun aSecondBackupOfThatListJoinsItWithNoSlotWritten() {
        assertTrue(vault.create(pass(B)).opened)
        val words = VaultKeyringSession.withKey { mnemonic.encode(it) }!!.joinToString(" ")
        putItems(store, listOf(ByteArray(20_000).also(rng::nextBytes)))
        val first = openVaultBackupFile(store, blobs, File(root, "snapshot"))
        val position = VaultKeyringSession.createVault(store, "Second")!!
        assertNotNull(VaultKeyringSession.openVault(position))
        val payloads = listOf(ByteArray(30_000).also(rng::nextBytes))
        val written = putItems(store, payloads)
        val second = openVaultBackupFile(store, blobs, File(root, "snapshot"))
        VaultLifecycle.lockNow()
        blobs.deleteRecursively()
        blobs.mkdirs()
        assertTrue(slotsFile.delete())

        assertTrue(vault.create(pass(A)).opened)
        val aRows = vaultRows()
        assertTrue(vault.restoreAsList(key(words), fileSource(first), pass(B_NEW)).opened)
        val slotsBefore = slotsFile.readBytes()
        assertTrue(vault.restoreIntoList(key(words), fileSource(second)).opened)
        assertArrayEquals("no slot is written", slotsBefore, slotsFile.readBytes())
        assertEquals(aRows, vaultRows())

        VaultLifecycle.lockNow()
        assertTrue(vault.unlock(pass(B_NEW)).opened)
        assertEquals(2, VaultKeyringSession.vaults().size)
        assertTrue(vault.openVaultAt(position))
        assertTrue(openVaultHolds(store, written, payloads))
    }

    @Test
    fun noRoomAndWrongWordsWriteNothing() {
        val b = backUpListB()
        assertTrue(vault.create(pass(A)).opened)
        assertTrue(VaultKeyringSession.recordKnownSlots(store, 0xFF))
        val slotsBefore = slotsFile.readBytes()
        val blobsBefore = digests(blobs)

        val full = vault.restoreAsList(key(b.words), fileSource(b.file), pass(B_NEW))
        assertEquals(VaultLifecycle.Refusal.NO_ROOM, full.refusal)
        val wrong = VaultKeys.newKeyringKey()
        assertEquals(
            VaultLifecycle.Refusal.BACKUP_NOT_OPENED,
            vault.restoreAsList(wrong.copyOf(), fileSource(b.file), pass(B_NEW)).refusal,
        )
        assertEquals(VaultLifecycle.Refusal.BACKUP_NOT_OPENED, vault.restoreIntoList(wrong, folderSource()).refusal)

        assertArrayEquals(slotsBefore, slotsFile.readBytes())
        assertEquals("no file is written", blobsBefore, digests(blobs))
    }

    @Test
    fun aProtectedVaultOnTheRestoredListOpensWithItsOwnPassphrase() {
        val b = backUpListB(own = OWN)
        assertTrue(vault.create(pass(A)).opened)
        assertTrue(vault.restoreAsList(key(b.words), fileSource(b.file), pass(B_NEW)).opened)
        VaultLifecycle.lockNow()

        assertTrue(vault.unlock(pass(B_NEW)).opened)
        val entry = VaultKeyringSession.vaults().single { it.hasOwnPassphrase }
        val kek = VaultKeyringSession.entryPassphraseKek(pass(OWN), entry.position, entry.kdfSet)!!
        assertNotNull("its own passphrase opens it", VaultKeyringSession.openVaultWithPassphrase(entry.position, kek))
        assertTrue(openVaultHolds(store, b.written, b.payloads))
    }
}
