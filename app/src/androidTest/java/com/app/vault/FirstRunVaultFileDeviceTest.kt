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
 * `switch-builds-without-losing-vaults` — a `.vault` file starting the list on an install
 * with none (`archive-export`, "A `.vault` file can start a vault list on an install with
 * none").
 *
 * "Device A" is the build being left, "device B" the fresh install of the other one: a second
 * install root with its own slot file and store. Nothing passes between them but the file.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunVaultFileDeviceTest {

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
        const val PASS = "correcthorsebattery"
        const val FILE_PASS = "the-file-passphrase"
        const val NEW_PASS = "a-different-passphrase"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before fun setUp() {
        VaultLifecycle.lockNow()
        val stamp = System.nanoTime()
        deviceA = File(ctx.filesDir, "firstrun-a-$stamp").apply { mkdirs() }
        deviceB = File(ctx.filesDir, "firstrun-b-$stamp").apply { mkdirs() }
        storeA = BlobStore(File(deviceA, "blobs"), File(deviceA, "incoming"))
        storeB = BlobStore(File(deviceB, "blobs"), File(deviceB, "incoming"))
        vaultA = VaultLifecycle(File(deviceA, "vault.slots"), storeA)
        vaultB = VaultLifecycle(File(deviceB, "vault.slots"), storeB)
        archive = File(ctx.cacheDir, "firstrun-$stamp.vault")
    }

    @After fun tearDown() {
        VaultLifecycle.lockNow()
        deviceA.deleteRecursively()
        deviceB.deleteRecursively()
        archive.delete()
    }

    private fun addItem(name: String, size: Int): ByteArray {
        val vmk = VaultSession.vmk()!!
        val id = VaultSession.metadataBlobId()!!
        val content = ByteArray(size).also { rng.nextBytes(it) }
        val blob = storeA.write(ByteArrayInputStream(content), vmk)
        val db = MetadataDb.load(storeA, id, vmk)
        db.items += VaultItem(listOf(blob.blobId), blob.plaintextLength, null, name, "Album", null, 42, emptyList())
        db.save(storeA, id, vmk)
        return content
    }

    /** A filled vault on device A, saved as [archive]. Returns its key, index blob ID and items. */
    private fun saveFilledVault(): Triple<ByteArray, ByteArray, Map<String, ByteArray>> {
        assertTrue(vaultA.create(pass(PASS)).opened)
        val contents = linkedMapOf(
            "photo.jpg" to addItem("photo.jpg", 40_000),
            "clip.mp4" to addItem("clip.mp4", 300_000),
            "notes.bin" to addItem("notes.bin", 1_000),
        )
        val vmk = VaultSession.vmk()!!.copyOf()
        val id = VaultSession.metadataBlobId()!!.copyOf()
        val db = MetadataDb.load(storeA, id, vmk)
        archive.outputStream().use { VaultArchive.write(it, vmk, db, storeA, pass(FILE_PASS)) }
        VaultLifecycle.lockNow()
        return Triple(vmk, id, contents)
    }

    private fun readOnB(passphrase: String = FILE_PASS): VaultLifecycle.HeldArchive? =
        archive.inputStream().use { vaultB.readArchiveForFirstList(it, pass(passphrase)) }

    private fun blobsOnB() = File(deviceB, "blobs").listFiles().orEmpty()
    private fun incomingOnB() = File(deviceB, "incoming").listFiles().orEmpty()

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

    @Test fun aListCreatedFromAFileHoldsTheSameVault() {
        val (vmkA, idA, contents) = saveFilledVault()

        val held = readOnB()
        assertNotNull("the file opens on a device with no list", held)
        assertEquals("the item count is the file's", contents.size, held!!.items)
        assertFalse("reading writes no slot file", vaultB.vaultExists())

        val outcome = vaultB.create(pass(NEW_PASS), firstVaultName = "Family", firstVaultKey = held.vmk)
        held.vmk.fill(0)
        assertTrue("the list is created around it", outcome.opened)

        val entries = VaultKeyringSession.vaults()
        assertEquals("one vault on the new list", 1, entries.size)
        assertEquals("under the name given on step 1", "Family", entries.single().name)

        val vmkB = VaultSession.vmk()!!
        val idB = VaultSession.metadataBlobId()!!
        assertArrayEquals("the same key", vmkA, vmkB)
        assertArrayEquals("the same index blob ID", idA, idB)

        val dbB = MetadataDb.load(storeB, idB, vmkB)
        assertEquals("every item arrived", contents.size, dbB.items.size)
        contents.forEach { (name, plaintext) ->
            val item = dbB.items.first { it.filename == name }
            assertArrayEquals("$name must be byte-identical", plaintext, readBack(storeB, item, vmkB))
        }

        // It opens with the passphrase set in onboarding, like any first list.
        VaultLifecycle.lockNow()
        val again = VaultLifecycle(File(deviceB, "vault.slots"), storeB)
        assertTrue("the onboarding passphrase opens the list", again.unlock(pass(NEW_PASS)).opened)
        assertTrue("and the vault on it", again.openVaultAt(VaultKeyringSession.vaults().single().position))
        assertArrayEquals(vmkA, VaultSession.vmk())
    }

    @Test fun theListGetsItsOwnInstallChaffAndNoNewIndex() {
        saveFilledVault()
        val held = readOnB()!!
        val afterRead = blobsOnB().map { it.name }.toSet()

        assertTrue(vaultB.create(pass(NEW_PASS), firstVaultKey = held.vmk).opened)
        held.vmk.fill(0)
        val added = blobsOnB().map { it.name }.toSet() - afterRead

        // The keyring blob, plus the install's own reserve — and no index, creation chaff or log.
        assertEquals(VaultLifecycle.INSTALL_CHAFF_BLOBS + 1, added.size)
        assertTrue("nothing the file brought was replaced", blobsOnB().map { it.name }.containsAll(afterRead))
    }

    @Test fun aWrongPassphraseLeavesTheStoreEmpty() {
        saveFilledVault()
        assertNull(readOnB("not-the-file-passphrase"))
        assertEquals("no blob", 0, blobsOnB().size)
        assertFalse("no slot file", vaultB.vaultExists())
    }

    @Test fun discardingBeforeCreationLeavesTheStoreEmpty() {
        saveFilledVault()
        val held = readOnB()!!
        assertTrue("the read wrote blobs", blobsOnB().isNotEmpty())

        vaultB.discardHeld(held)
        assertEquals("no blob", 0, blobsOnB().size)
        assertTrue("the held key is zeroed", held.vmk.all { it == 0.toByte() })
        assertFalse("no slot file", vaultB.vaultExists())
    }

    @Test fun aFileIsNotReadWhereAListExists() {
        saveFilledVault()
        assertTrue(vaultB.create(pass(NEW_PASS)).opened)
        VaultLifecycle.lockNow()
        val before = blobsOnB().map { it.name }.toSet()

        assertNull("a list is here: the drawer's import is the way in", readOnB())
        assertEquals(before, blobsOnB().map { it.name }.toSet())
    }

    @Test fun theSweepEmptiesBothDirectoriesWithNoSlotFile() {
        saveFilledVault()
        assertNotNull(readOnB())
        File(deviceB, "incoming/partial").writeBytes(ByteArray(64))
        assertTrue(blobsOnB().isNotEmpty())

        // The process ended before "Create vault"; the next launch sweeps.
        val relaunched = VaultLifecycle(File(deviceB, "vault.slots"), storeB)
        assertTrue(relaunched.sweepWithoutList())
        assertEquals("the store is empty", 0, blobsOnB().size)
        assertEquals("incoming is empty", 0, incomingOnB().size)
    }

    @Test fun theSweepTouchesNothingWhenASlotFileExists() {
        assertTrue(vaultB.create(pass(NEW_PASS)).opened)
        VaultLifecycle.lockNow()
        File(deviceB, "incoming/partial").writeBytes(ByteArray(64))
        val blobs = blobsOnB().map { it.name }.toSet()
        val slots = File(deviceB, "vault.slots").readBytes()

        assertFalse(vaultB.sweepWithoutList())
        assertEquals(blobs, blobsOnB().map { it.name }.toSet())
        assertEquals(1, incomingOnB().size)
        assertArrayEquals(slots, File(deviceB, "vault.slots").readBytes())
    }
}
