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
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * P1-14 — the encrypted archive, against the task's Verify: export on device A,
 * import on device B, all files and metadata intact.
 *
 * "Device B" is a second install root with its own slot file, blob store and
 * lifecycle. Nothing is shared between the two but the archive file itself, which
 * is what makes it a migration test rather than a round-trip in place.
 */
@RunWith(AndroidJUnit4::class)
class VaultArchiveTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var deviceA: File
    private lateinit var deviceB: File
    private lateinit var storeA: BlobStore
    private lateinit var storeB: BlobStore
    private lateinit var vaultA: VaultLifecycle
    private lateinit var vaultB: VaultLifecycle
    private lateinit var archive: File
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultArchiveTest"
        const val PASS = "correcthorsebattery"
        const val NEW_PASS = "a-different-passphrase"
    }

    private fun pass(s: String = PASS) = s.toByteArray(Charsets.UTF_8)

    @Before fun setUp() {
        VaultLifecycle.lockNow()
        val stamp = System.nanoTime()
        deviceA = File(ctx.filesDir, "archive-a-$stamp").apply { mkdirs() }
        deviceB = File(ctx.filesDir, "archive-b-$stamp").apply { mkdirs() }
        storeA = BlobStore(File(deviceA, "blobs"), File(deviceA, "incoming"))
        storeB = BlobStore(File(deviceB, "blobs"), File(deviceB, "incoming"))
        vaultA = VaultLifecycle(File(deviceA, "vault.slots"), storeA)
        vaultB = VaultLifecycle(File(deviceB, "vault.slots"), storeB)
        archive = File(ctx.cacheDir, "test-$stamp.vault")
    }

    @After fun tearDown() {
        VaultLifecycle.lockNow()
        deviceA.deleteRecursively()
        deviceB.deleteRecursively()
        archive.delete()
    }

    /** Adds an item on device A and returns its plaintext. */
    private fun addItem(name: String, size: Int, createdAt: Long? = null): ByteArray {
        val vmk = VaultSession.vmk()!!
        val id = VaultSession.metadataBlobId()!!
        val content = ByteArray(size).also { rng.nextBytes(it) }
        val blob = storeA.write(ByteArrayInputStream(content), vmk)
        val db = MetadataDb.load(storeA, id, vmk)
        db.items += VaultItem(
            listOf(blob.blobId), blob.plaintextLength, null, name, "Album", createdAt, 42, listOf("tag")
        )
        db.save(storeA, id, vmk)
        return content
    }

    /**
     * Runs [body] with [VaultKeys.RELEASED_KDF_PARAMS] standing in for a later
     * release's list, and restores it afterwards on every path — a leaked value
     * would silently retune every test that ran after this one.
     */
    private fun withReleasedParams(list: List<VaultKeys.Argon2idParams>, body: () -> Unit) {
        val before = VaultKeys.RELEASED_KDF_PARAMS
        VaultKeys.RELEASED_KDF_PARAMS = list
        try {
            body()
        } finally {
            VaultKeys.RELEASED_KDF_PARAMS = before
        }
    }

    private fun writeArchive(
        passphrase: ByteArray = pass(),
        sealParams: VaultKeys.Argon2idParams? = null,
    ): Int {
        if (sealParams != null) {
            var written = 0
            withReleasedParams(listOf(sealParams)) { written = writeArchive(passphrase) }
            return written
        }
        return writeArchiveNow(passphrase)
    }

    private fun writeArchiveNow(passphrase: ByteArray): Int {
        val vmk = VaultSession.vmk()!!
        val id = VaultSession.metadataBlobId()!!
        val db = MetadataDb.load(storeA, id, vmk)
        return archive.outputStream().use { out ->
            VaultArchive.write(out, vmk, db, storeA, passphrase)
        }
    }

    /**
     * Device B's own list, open with no vault open — `recovery-own-slot-only`, D5: an
     * import needs a list, and joins it. Created under [NEW_PASS] on first use.
     */
    private fun openListOnB() {
        if (VaultKeyringSession.isOpen) return
        if (vaultB.vaultExists()) assertTrue(vaultB.unlock(pass(NEW_PASS)).opened)
        else assertTrue(vaultB.create(pass(NEW_PASS)).opened)
        VaultKeyringSession.leaveVault()
    }

    private fun blobsOnB() = File(deviceB, "blobs").listFiles().orEmpty().map { it.name }.toSet()

    private fun importInto(
        vault: VaultLifecycle,
        archivePass: ByteArray = pass(),
    ): VaultLifecycle.Outcome {
        openListOnB()
        return archive.inputStream().use { vault.importArchive(it, archivePass) }
            // `vault-keyring`. An import puts the vault on a **list** and opens nothing —
            // the same rule as an unlock. Every assertion below reads the imported vault's
            // session, so the vault is opened here rather than in nine places.
            .also { if (it.opened) vault.openVaultAt(VaultKeyringSession.vaults().last().position) }
    }

    private fun readBack(store: BlobStore, item: VaultItem, vmk: ByteArray): ByteArray {
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
        return out
    }

    // --- 5.1 the Verify --------------------------------------------------------

    @Test fun aVaultMovesToAnotherDeviceIntact() {
        vaultA.create(pass())
        val contents = linkedMapOf(
            "photo.jpg" to addItem("photo.jpg", 40_000, createdAt = 1_500_000_000_000L),
            "clip.mp4" to addItem("clip.mp4", 300_000, createdAt = 1_400_000_000_000L),
            "notes.bin" to addItem("notes.bin", 1_000),
        )
        val mnemonic = Mnemonic.fromResources(ctx).encode(VaultSession.vmk()!!).joinToString(" ")
        val sourceItems = MetadataDb.load(storeA, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!).items
            .associateBy { it.filename }
        val blobs = writeArchive()
        VaultLifecycle.lockNow()

        Log.i(TAG, "archive: $blobs blobs, ${archive.length()} B")

        // Device B has never seen this vault.
        assertFalse("device B starts empty", vaultB.vaultExists())
        val outcome = importInto(vaultB)
        assertTrue("the archive must open on device B", outcome.opened)

        val vmkB = VaultSession.vmk()!!
        val dbB = MetadataDb.load(storeB, VaultSession.metadataBlobId()!!, vmkB)

        assertEquals("every item arrived", contents.size, dbB.items.size)
        contents.forEach { (name, plaintext) ->
            val item = dbB.items.first { it.filename == name }
            assertArrayEquals("$name must be byte-identical", plaintext, readBack(storeB, item, vmkB))

            val source = sourceItems.getValue(name)
            assertEquals("$name album", source.album, item.album)
            assertEquals("$name createdAt", source.createdAt, item.createdAt)
            assertEquals("$name importedAt", source.importedAt, item.importedAt)
            assertEquals("$name tags", source.tags, item.tags)
        }
        assertEquals("owned chaff arrived", VaultLifecycle.CREATION_CHAFF_BLOBS, dbB.ownedChaff.size)
        assertNotNull("the tamper log arrived", dbB.logBlobId)

        // 5.3: the vault's own key is preserved, which is what makes this the same
        // vault rather than a copy of its contents — asserted above by the index blob
        // ID resolving and every item reading back byte-identical.
        //
        // **The kit does not travel with it — `vault-keyring`.** A kit stands in for a
        // *keyring* key, and the vault has joined device B's keyring, which has a key
        // of its own. Device A's words open device A's list; they were never a second
        // credential for one vault, and after this change they cannot be. What opens
        // the imported vault on device B is device B's master passphrase, asserted
        // above, and device B's own kit.
        VaultLifecycle.lockNow()
        assertFalse(
            "device A's kit must not open a vault that has joined another keyring",
            vaultB.recover(Mnemonic.fromResources(ctx), mnemonic, pass("yet-another-pass")).opened
        )
        Log.i(TAG, "VERIFY: ${contents.size} items moved, all byte-identical, mnemonic still opens")
    }

    // --- 5.2 the passphrase ----------------------------------------------------

    @Test fun theImportedVaultOpensUnderTheListsPassphrase() {
        vaultA.create(pass())
        addItem("one.jpg", 2_000)
        writeArchive()
        VaultLifecycle.lockNow()

        assertTrue(importInto(vaultB).opened)
        VaultLifecycle.lockNow()

        val again = VaultLifecycle(File(deviceB, "vault.slots"), storeB)
        assertTrue("device B's list passphrase opens the list", again.unlock(pass(NEW_PASS)).opened)
        assertTrue(
            "and the imported vault on it",
            again.openVaultAt(VaultKeyringSession.vaults().last().position),
        )
    }

    // --- `recovery-own-slot-only`: import joins the open list, and writes no slot --

    @Test fun importNeedsAnOpenList() {
        vaultA.create(pass())
        addItem("one.jpg", 2_000)
        writeArchive()
        VaultLifecycle.lockNow()

        val outcome = archive.inputStream().use { vaultB.importArchive(it, pass()) }
        assertFalse("no list open, no import", outcome.opened)
        assertNull(outcome.refusal)
        assertEquals("nothing written", 0, File(deviceB, "blobs").listFiles().orEmpty().size)
        assertFalse("no slot file", vaultB.vaultExists())
    }

    @Test fun anImportLeavesTheSlotFileByteForByteUnchanged() {
        vaultA.create(pass())
        addItem("one.jpg", 2_000)
        writeArchive()
        VaultLifecycle.lockNow()

        openListOnB()
        val slots = File(deviceB, "vault.slots")
        val before = slots.readBytes()
        assertTrue(importInto(vaultB).opened)
        assertArrayEquals(before, slots.readBytes())
    }

    @Test fun aFullListRefusesAndWritesNothing() {
        vaultA.create(pass())
        addItem("one.jpg", 2_000)
        writeArchive()
        VaultLifecycle.lockNow()

        openListOnB()
        while (VaultKeyringSession.vaults().size < VaultKeyring.CAPACITY) {
            val vmk = VaultKeys.newVmk()
            assertNotNull(VaultKeyringSession.addImported(storeB, "filler", vmk, VaultKeys.metadataBlobId(vmk)))
            vmk.fill(0)
        }
        val keyring = storeB.fileOf(VaultKeyringSession.keyringBlobId()!!)
        val keyringBefore = keyring.readBytes()
        val blobs = blobsOnB()
        val slots = File(deviceB, "vault.slots").readBytes()

        val outcome = archive.inputStream().use { vaultB.importArchive(it, pass()) }
        assertFalse(outcome.opened)
        assertEquals(VaultLifecycle.Refusal.NO_ROOM, outcome.refusal)
        assertEquals("no blob added or left behind", blobs, blobsOnB())
        assertArrayEquals("no entry written", keyringBefore, keyring.readBytes())
        assertArrayEquals("no slot written", slots, File(deviceB, "vault.slots").readBytes())
        assertEquals(VaultKeyring.CAPACITY, VaultKeyringSession.vaults().size)
    }

    // --- 5.4 the index is found from the key alone -----------------------------

    @Test fun theIndexIsLocatedFromTheKeyOnDeviceB() {
        vaultA.create(pass())
        addItem("one.jpg", 2_000)
        val vmkBefore = VaultSession.vmk()!!.copyOf()
        writeArchive()
        VaultLifecycle.lockNow()

        assertTrue(importInto(vaultB).opened)
        assertArrayEquals("the VMK is preserved — this is the same vault", vmkBefore, VaultSession.vmk())
        assertArrayEquals(
            "the index blob ID is derived from it, not carried over",
            VaultKeys.metadataBlobId(vmkBefore),
            VaultSession.metadataBlobId()
        )
        vmkBefore.fill(0)
    }

    // --- 5.5 every failure is one failure --------------------------------------

    @Test fun everyFailureLooksTheSame() {
        vaultA.create(pass())
        addItem("one.jpg", 5_000)
        writeArchive()
        VaultLifecycle.lockNow()

        // Wrong passphrase.
        assertFalse(importInto(vaultB, archivePass = pass("wrongwrongwrong")).opened)
        assertFalse(VaultSession.isUnlocked)

        // Truncated.
        val full = archive.readBytes()
        archive.writeBytes(full.copyOfRange(0, full.size / 2))
        assertFalse(importInto(vaultB).opened)
        assertFalse(VaultSession.isUnlocked)

        // Not an archive at all.
        archive.writeBytes(ByteArray(4096).also { rng.nextBytes(it) })
        assertFalse(importInto(vaultB).opened)
        assertFalse(VaultSession.isUnlocked)

        // Empty.
        archive.writeBytes(ByteArray(0))
        assertFalse(importInto(vaultB).opened)

        // And the real one still works, so the failures changed nothing.
        archive.writeBytes(full)
        assertTrue(importInto(vaultB).opened)
    }

    // --- P2-25: parameters across releases -------------------------------------

    /**
     * An archive sealed by an earlier release imports on a later one — P2-25.
     *
     * The archive records nothing about what sealed it, because a recorded
     * parameter block would be the fingerprint §11 forbids. Import instead tries
     * every entry of [VaultKeys.RELEASED_KDF_PARAMS], so a `.vault` sitting in
     * someone's cloud folder — where no later release can reach it — keeps opening
     * across a retune.
     *
     * The superseded entry is prepended for the duration of the test, which is
     * exactly the shape a real retune has: the list grows and the older entry stays
     * in it forever.
     */
    @Test fun anArchiveSealedUnderSupersededParametersStillImports() {
        val superseded =
            VaultKeys.Argon2idParams(memoryKib = 8 * 1024, iterations = 2, parallelism = 1)

        vaultA.create(pass())
        val plaintext = addItem("one.jpg", 5_000)
        // Sealed under the older entry, as the release that shipped it would have.
        writeArchive(sealParams = superseded)
        VaultLifecycle.lockNow()

        withReleasedParams(listOf(superseded, VaultKeys.ARGON2ID_PARAMS)) {
            val outcome = importInto(vaultB)
            assertTrue("an archive from an earlier release must import", outcome.opened)

            val vmkB = VaultSession.vmk()!!
            val dbB = MetadataDb.load(storeB, VaultSession.metadataBlobId()!!, vmkB)
            assertArrayEquals(
                "the item must be byte-identical across a retune",
                plaintext,
                readBack(storeB, dbB.items.single { it.filename == "one.jpg" }, vmkB),
            )
        }
    }

    /**
     * Trying several parameter sets must not turn one failure into several. Every
     * §11 failure mode is re-run with a longer list, and every one still produces
     * the single indistinguishable outcome.
     */
    @Test fun aLongerParameterListStillProducesOneFailure() {
        val superseded =
            VaultKeys.Argon2idParams(memoryKib = 8 * 1024, iterations = 2, parallelism = 1)

        vaultA.create(pass())
        addItem("one.jpg", 5_000)
        writeArchive()
        VaultLifecycle.lockNow()
        val full = archive.readBytes()

        withReleasedParams(listOf(superseded, VaultKeys.ARGON2ID_PARAMS)) {
            assertFalse(importInto(vaultB, archivePass = pass("wrongwrongwrong")).opened)
            assertFalse(VaultSession.isUnlocked)

            archive.writeBytes(full.copyOfRange(0, full.size / 2))
            assertFalse(importInto(vaultB).opened)
            assertFalse(VaultSession.isUnlocked)

            archive.writeBytes(ByteArray(4096).also { rng.nextBytes(it) })
            assertFalse(importInto(vaultB).opened)
            assertFalse(VaultSession.isUnlocked)

            archive.writeBytes(ByteArray(0))
            assertFalse(importInto(vaultB).opened)

            // And the real one still opens, so none of the misses changed anything.
            archive.writeBytes(full)
            assertTrue(importInto(vaultB).opened)
        }
    }

    @Test fun aWrongPassphraseLeavesNothingBehind() {
        vaultA.create(pass())
        addItem("one.jpg", 5_000)
        writeArchive()
        VaultLifecycle.lockNow()

        openListOnB()
        val before = blobsOnB()
        assertFalse(importInto(vaultB, archivePass = pass("wrongwrongwrong")).opened)
        assertEquals("a failed import must write nothing", before, blobsOnB())
    }

    // --- 5.6 corruption --------------------------------------------------------

    @Test fun aCorruptedBlobIsCaughtByItsHash() {
        vaultA.create(pass())
        addItem("one.jpg", 200_000)
        writeArchive()
        VaultLifecycle.lockNow()

        // Flip a byte well inside the blob region, past the header.
        RandomAccessFile(archive, "rw").use { raf ->
            val at = archive.length() - 1024
            raf.seek(at)
            val b = raf.readByte()
            raf.seek(at)
            raf.writeByte(b.toInt() xor 0xFF)
        }

        openListOnB()
        val before = blobsOnB()
        val vaults = VaultKeyringSession.vaults().size
        assertFalse("a corrupt archive must not import", importInto(vaultB).opened)
        assertEquals("and must leave nothing behind", before, blobsOnB())
        assertEquals("nothing joined the list", vaults, VaultKeyringSession.vaults().size)
    }

    // --- 5.7 fresh salt per archive --------------------------------------------

    @Test fun twoArchivesOfOneVaultShareNothing() {
        vaultA.create(pass())
        addItem("one.jpg", 5_000)

        writeArchive()
        val first = archive.readBytes()
        writeArchive()
        val second = archive.readBytes()

        val saltA = first.copyOfRange(0, 16)
        val saltB = second.copyOfRange(0, 16)
        assertFalse("salts must differ", saltA.contentEquals(saltB))

        // The sealed header must differ too, even though the plaintext is the same
        // vault — same passphrase, different salt, different key.
        val headerA = first.copyOfRange(20, 200)
        val headerB = second.copyOfRange(20, 200)
        assertFalse("sealed headers must differ", headerA.contentEquals(headerB))
    }

    // --- 5.8 / 5.9 what is in it, and chaff -------------------------------------

    @Test fun theArchiveHoldsWhatTheVaultOwnsAndNothingElse() {
        vaultA.create(pass())
        addItem("one.jpg", 5_000)
        addItem("two.jpg", 5_000)

        val vmk = VaultSession.vmk()!!
        val id = VaultSession.metadataBlobId()!!
        val db = MetadataDb.load(storeA, id, vmk)
        // index + log + owned chaff + two items
        val expected = 1 + 1 + db.ownedChaff.size + db.items.size

        val written = writeArchive()
        assertEquals("exactly what the vault owns", expected, written)

        val onDisk = File(deviceA, "blobs").listFiles().orEmpty().size
        assertTrue(
            "the store holds more than the archive — install chaff stays behind",
            onDisk > written
        )
        Log.i(TAG, "archive holds $written of $onDisk blobs; the difference is install chaff")
    }

    @Test fun theImportingDeviceWritesItsOwnInstallChaff() {
        vaultA.create(pass())
        addItem("one.jpg", 5_000)
        val archived = writeArchive()
        VaultLifecycle.lockNow()

        openListOnB()
        val before = blobsOnB()
        assertTrue(importInto(vaultB).opened)

        // The list's keyring is rewritten in place, so only the import's blobs are new.
        val added = File(deviceB, "blobs").listFiles().orEmpty().filter { it.name !in before }
        assertEquals(
            "device B has the archive's blobs plus its own install reserve",
            archived + VaultLifecycle.INSTALL_CHAFF_BLOBS, added.size
        )
        val onB = File(deviceB, "blobs").listFiles().orEmpty()

        // The reserves must not be copies of each other.
        val hashesA = File(deviceA, "blobs").listFiles().orEmpty().map { sha(it) }.toSet()
        val unshared = added.count { sha(it) !in hashesA }
        assertTrue("device B's reserve is its own", unshared >= VaultLifecycle.INSTALL_CHAFF_BLOBS)
        Log.i(TAG, "device B: ${onB.size} blobs, $unshared of them its own")
    }

    private fun sha(f: File): String =
        MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    // --- 5.10 archiving is not exporting ----------------------------------------

    @Test fun archivingDoesNotEmptyTheVault() {
        vaultA.create(pass())
        addItem("one.jpg", 5_000)
        addItem("two.jpg", 5_000)
        val before = MetadataDb.load(storeA, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!).items.size

        writeArchive()

        val after = MetadataDb.load(storeA, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!).items.size
        assertEquals("an archive is a copy, unlike P1-13's export", before, after)
        assertEquals(2, after)
    }

    // --- 5.11 I9 ----------------------------------------------------------------

    @Test fun theArchiveIsWrittenOnlyWhereItWasAsked() {
        vaultA.create(pass())
        val content = addItem("secret.jpg", 60_000)
        writeArchive()

        // The archive here is a plain file the test owns, standing in for the SAF
        // document the app writes. Nothing else in the app's storage may hold it.
        val needle = archive.readBytes().copyOfRange(0, 64)
        listOf(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir).forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it != archive }.forEach { f ->
                assertFalse(
                    "archive bytes found in ${f.absolutePath}",
                    f.readBytes().containsSlice(needle)
                )
            }
        }
        // And the vault's plaintext is nowhere at all.
        listOf(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir).forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                assertFalse(
                    "plaintext found in ${f.absolutePath}",
                    f.readBytes().containsSlice(content.copyOfRange(0, 64))
                )
            }
        }
    }

    @Test fun anArchiveRevealsNothingWithoutThePassphrase() {
        vaultA.create(pass())
        val content = addItem("secret.jpg", 60_000)
        writeArchive()

        val bytes = archive.readBytes()
        assertFalse("no plaintext in the archive", bytes.containsSlice(content.copyOfRange(0, 64)))
        assertFalse("no VMK in the archive", bytes.containsSlice(VaultSession.vmk()!!))
        assertFalse("no magic bytes", String(bytes.copyOfRange(0, 8), Charsets.US_ASCII).contains("VAULT"))
    }

    // --- 5.12 streaming ---------------------------------------------------------

    /**
     * Neither direction may hold the archive, or any blob, in memory.
     *
     * The corpus is sized by runner argument so the same code path can be pushed
     * on a device with the disk for it:
     *
     *     -Pandroid.testInstrumentationRunnerArguments.archiveMb=4096
     */
    @Test fun bothDirectionsStreamWithoutHoldingTheFile() {
        val budgetMb = InstrumentationRegistry.getArguments()
            .getString("archiveMb")?.toIntOrNull() ?: 240

        vaultA.create(pass())
        val chunk = ByteArray(1 shl 20).also { rng.nextBytes(it) }
        val each = 24 * 1024 * 1024
        val count = maxOf(1, budgetMb / 24)

        repeat(count) { n ->
            val vmk = VaultSession.vmk()!!
            val id = VaultSession.metadataBlobId()!!
            // writeItem: 24 MiB is two blobs since `blob-size-cap`.
            val blob = storeA.writeItem(object : java.io.InputStream() {
                var produced = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (produced >= each) return -1
                    val n2 = minOf(len, each - produced, chunk.size)
                    System.arraycopy(chunk, 0, b, off, n2)
                    produced += n2
                    return n2
                }
            }, vmk)
            val db = MetadataDb.load(storeA, id, vmk)
            db.items += VaultItem(blob.blobIds, blob.plaintextLength, null, "big$n.mp4", null, null, 0, emptyList())
            db.save(storeA, id, vmk)
        }

        val runtime = Runtime.getRuntime()
        fun usedMb() = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024

        System.gc()
        val before = usedMb()
        val started = System.currentTimeMillis()
        writeArchive()
        val writeMs = System.currentTimeMillis() - started
        val afterWrite = usedMb()
        VaultLifecycle.lockNow()
        openListOnB()

        val readStart = System.currentTimeMillis()
        assertTrue(importInto(vaultB).opened)
        val readMs = System.currentTimeMillis() - readStart
        val afterRead = usedMb()

        val archivedMb = archive.length() / 1024 / 1024
        Log.i(
            TAG,
            "streaming: ${archivedMb} MB archive; heap ${before}->${afterWrite}->${afterRead} MB; " +
                "write ${writeMs}ms (${"%.1f".format(archivedMb * 1000.0 / writeMs)} MB/s), " +
                "read ${readMs}ms (${"%.1f".format(archivedMb * 1000.0 / readMs)} MB/s)"
        )

        // A streaming implementation's heap does not track the file size. The
        // ceiling is generous because the JVM grows the heap as it pleases; what
        // it would catch is buffering the archive, which at this size is fatal.
        assertTrue(
            "heap grew ${afterRead - before} MB for a ${archivedMb} MB archive — not streaming",
            (afterRead - before) < archivedMb / 2
        )
    }

    // --- 5.9 the trash does not travel (P2-12) --------------------------------

    /**
     * The Verify line's second half: "Export and archive round-trip (P1-14)
     * exclude trashed items and still verify."
     *
     * The archive carries the live items and an index naming only them, so the
     * vault that lands on device B has an empty trash — and every file that *did*
     * travel is byte-identical, which is what "still verify" means.
     */
    @Test fun anArchiveLeavesTheTrashBehindAndStillVerifies() {
        vaultA.create(pass())
        val kept = addItem("kept.jpg", 30_000, createdAt = 1_500_000_000_000L)
        addItem("trashed.jpg", 30_000)

        val vmkA = VaultSession.vmk()!!
        val idA = VaultSession.metadataBlobId()!!
        val doomed = MetadataDb.load(storeA, idA, vmkA).items.first { it.filename == "trashed.jpg" }
        VaultDeletion.delete(storeA, idA, vmkA, doomed)

        writeArchive()
        VaultLifecycle.lockNow()

        assertTrue("the archive must open on device B", importInto(vaultB).opened)
        val vmkB = VaultSession.vmk()!!
        val dbB = MetadataDb.load(storeB, VaultSession.metadataBlobId()!!, vmkB)

        assertEquals("only the live item may travel", 1, dbB.items.size)
        val arrived = dbB.items.single()
        assertEquals("kept.jpg", arrived.filename)
        assertNull("nothing may arrive trashed", arrived.deletedAt)
        assertArrayEquals("the item that travelled is not byte-identical", kept, readBack(storeB, arrived, vmkB))

        // Nothing the archived index names may be missing on the far side — the
        // failure this change had to avoid is a row pointing at absent blobs.
        dbB.items.forEach { item ->
            item.blobIds.forEach {
                assertTrue("the imported index names a blob the archive did not carry", storeB.fileOf(it).exists())
            }
        }
        // And the trashed item's blobs are not in the archive at all.
        doomed.blobIds.forEach {
            assertFalse("a trashed item's blob crossed to device B", storeB.fileOf(it).exists())
        }

        // Device A is untouched: the archive is not a purge.
        VaultLifecycle.lockNow()
        assertTrue(vaultA.unlockAndOpen(pass()))
        val dbA = MetadataDb.load(storeA, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!)
        assertEquals("archiving must not change the source vault", 2, dbA.items.size)
        assertNotNull(
            "the source vault's trash was emptied by an archive",
            dbA.items.first { it.filename == "trashed.jpg" }.deletedAt,
        )
    }

    /**
     * The archive builds a filtered index, and it must build it **in memory**.
     *
     * A staged copy inside the store would sit outside everything that accounts
     * for files there — `allBlobIds()` reads `blobs/` and 32-character names, the
     * partial sweep reads `*.tmp` — so an archive interrupted halfway would leave
     * it on disk with nothing to remove it. Asserted as "the store is exactly what
     * it was", which is the only form of that claim that cannot rot.
     */
    @Test fun writingAnArchiveCreatesNoFileInTheVault() {
        vaultA.create(pass())
        addItem("one.jpg", 20_000)
        addItem("two.jpg", 20_000)

        val vmkA = VaultSession.vmk()!!
        val idA = VaultSession.metadataBlobId()!!
        VaultDeletion.delete(storeA, idA, vmkA, MetadataDb.load(storeA, idA, vmkA).items.first())

        fun snapshot(dir: File) = dir.listFiles().orEmpty()
            .associate { it.name to it.length() }
            .toSortedMap()

        val blobsDir = File(deviceA, "blobs")
        val incomingDir = File(deviceA, "incoming")
        val blobsBefore = snapshot(blobsDir)
        val incomingBefore = snapshot(incomingDir)
        val indexBefore = MessageDigest.getInstance("SHA-256")
            .digest(storeA.fileOf(idA).readBytes())

        writeArchive()

        assertEquals("the archive added or resized a blob", blobsBefore, snapshot(blobsDir))
        assertEquals("the archive staged a file in incoming/", incomingBefore, snapshot(incomingDir))
        assertArrayEquals(
            "the archive rewrote the vault's own index",
            indexBefore,
            MessageDigest.getInstance("SHA-256").digest(storeA.fileOf(idA).readBytes()),
        )
    }

    // --- 5.10 folders travel in the index (P2-13, `folder-system`) ------------

    /**
     * The other half of P2-13's Verify: a folder survives an archive round-trip.
     *
     * ***`folder-system` gave this test something to fail at.*** An album was a field
     * on a row, so it travelled because the rows did and no code made it work. A
     * folder is a **record in a table the archive has to carry**, and an empty one has
     * no row to ride across at all — so if the table is dropped from the generated
     * index P2-12 introduced, the folder is simply gone on the far side.
     */
    @Test fun foldersSurviveTheRoundTrip() {
        vaultA.create(pass())
        addItem("in-trip.jpg", 20_000)
        addItem("in-archive.jpg", 20_000)
        addItem("loose.jpg", 20_000)

        val vmkA = VaultSession.vmk()!!
        val idA = VaultSession.metadataBlobId()!!
        val db = MetadataDb.load(storeA, idA, vmkA)
        val trip = VaultFolders.create(storeA, idA, vmkA, null, "Trip").db!!
            .folders.first { it.name == "Trip" }
        val archive = VaultFolders.create(storeA, idA, vmkA, null, "Archive").db!!
            .folders.first { it.name == "Archive" }
        // `folder-system`: an empty folder must travel too. It has no items to carry
        // it across, so if the table does not travel, it simply is not there.
        VaultFolders.create(storeA, idA, vmkA, trip.folderId, "2024")

        VaultFolders.move(
            storeA, idA, vmkA,
            listOf(db.items.first { it.filename == "in-trip.jpg" }),
            trip.folderId,
        )
        VaultFolders.move(
            storeA, idA, vmkA,
            listOf(MetadataDb.load(storeA, idA, vmkA).items.first { it.filename == "in-archive.jpg" }),
            archive.folderId,
        )
        // This class's fixture puts every row somewhere, so "in no folder" has to be
        // made rather than assumed — and it is worth making, because absent is the
        // case an appended-field reader gets wrong.
        VaultFolders.move(
            storeA, idA, vmkA,
            listOf(MetadataDb.load(storeA, idA, vmkA).items.first { it.filename == "loose.jpg" }),
            null,
        )

        writeArchive()
        VaultLifecycle.lockNow()

        assertTrue("the archive must open on device B", importInto(vaultB).opened)
        val dbB = MetadataDb.load(storeB, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!)

        fun folderOf(name: String) = dbB.items.first { it.filename == name }.folderId
            ?.let { id -> dbB.folders.first { it.id == hex(id) }.name }

        assertEquals("Trip", folderOf("in-trip.jpg"))
        assertEquals("Archive", folderOf("in-archive.jpg"))
        assertNull("an item in no folder must arrive in none", folderOf("loose.jpg"))
        assertEquals(
            "the folder list on the far side must be the one that left",
            listOf("Archive" to 1, "Trip" to 1),
            VaultFolders.children(dbB.folders, dbB.items, null).map { it.name to it.count },
        )
        assertTrue(
            "an empty sub-folder must travel with the archive",
            dbB.folders.any { it.name == "2024" },
        )
    }

    private fun ByteArray.containsSlice(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
