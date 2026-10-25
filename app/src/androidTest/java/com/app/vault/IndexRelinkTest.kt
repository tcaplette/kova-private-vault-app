package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
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
 * The fault a real device arrived with — `index-pointer-recovery`.
 *
 * A vault's keyring entry pointed at a 64 KiB blob instead of its index. `load` read
 * past the end of it, the `EOFException` escaped on a background thread, and the process
 * died on every launch — with the whole vault still sitting in the pool.
 *
 * These are that vault: built, broken the same way, and opened again.
 */
@RunWith(AndroidJUnit4::class)
class IndexRelinkTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var device: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private val rng = SecureRandom()

    private fun pass() = "correcthorsebattery".toByteArray(Charsets.UTF_8)

    @Before fun setUp() {
        VaultLifecycle.lockNow()
        device = File(ctx.filesDir, "relink-${System.nanoTime()}").apply { mkdirs() }
        store = BlobStore(File(device, "blobs"), File(device, "incoming"))
        vault = VaultLifecycle(File(device, "vault.slots"), store)
    }

    @After fun tearDown() {
        VaultLifecycle.lockNow()
        device.deleteRecursively()
    }

    private fun vmk() = VaultSession.vmk()!!
    private fun indexId() = VaultSession.metadataBlobId()!!

    /** A vault with three items in it, left unlocked. */
    private fun aVaultWithItems(): ByteArray {
        vault.create(pass())
        repeat(3) { n ->
            val written = store.writeItem(ByteArrayInputStream(ByteArray(4_000) { n.toByte() }), vmk())
            MetadataDb.commit(store, indexId(), vmk()) { db ->
                db.items += VaultItem(
                    written.blobIds, written.plaintextLength, null, "photo$n.jpg",
                    null, null, 1L, emptyList(),
                )
                db
            }
        }
        return indexId().copyOf()
    }

    /** A blob the size chaff and a keyring land on — what the device's pointer named. */
    private fun aSmallBlob(): ByteArray {
        val written = store.writeItem(ByteArrayInputStream(ByteArray(200) { 7 }), vmk())
        val id = written.blobIds.single()
        assertEquals("the premise: a 64 KiB blob", 64L * 1024, store.fileOf(id).length())
        return id
    }

    /**
     * ***The whole point.*** The pointer is moved to a blob that is not an index, and
     * the vault opens anyway — every item present, nothing rebuilt, nothing lost.
     */
    @Test
    fun aVaultWhosePointerNoLongerNamesItsIndexIsFoundAndOpened() {
        val realIndex = aVaultWithItems()
        val decoy = aSmallBlob()
        val position = VaultKeyringSession.openVaultPosition()
        assertTrue("the premise: a vault is open", position >= 0)

        // Break it exactly as the device was broken.
        assertTrue(VaultKeyringSession.relinkIndex(store, position, decoy))
        assertEquals(
            "the premise: the entry now names the wrong blob",
            decoy.toList(),
            VaultKeyringSession.vaults()[position].indexBlobId.toList(),
        )

        // Reading it must now be the typed failure and not an EOF.
        val failure = try { MetadataDb.load(store, decoy, vmk()); null } catch (e: Throwable) { e }
        assertTrue(
            "expected NotAnIndexException, got ${failure?.javaClass?.simpleName}",
            failure is MetadataDb.NotAnIndexException,
        )

        // The search finds the real one, and relinking restores the vault.
        val found = MetadataDb.findIndex(store, vmk())
        assertNotNull("the index must be findable", found)
        assertEquals(realIndex.toList(), found!!.toList())
        assertTrue(VaultKeyringSession.relinkIndex(store, position, found))

        val back = MetadataDb.load(store, found, vmk())
        assertEquals("every item must come back", 3, back.items.size)
    }

    /** And the repair holds: the entry keeps the corrected pointer. */
    @Test
    fun theRepairSurvivesAKeyringReload() {
        val realIndex = aVaultWithItems()
        val decoy = aSmallBlob()
        val position = VaultKeyringSession.openVaultPosition()

        VaultKeyringSession.relinkIndex(store, position, decoy)
        VaultKeyringSession.relinkIndex(store, position, MetadataDb.findIndex(store, vmk())!!)

        VaultLifecycle.lockNow()
        assertTrue(vault.unlock(pass()).opened)
        assertEquals(
            "the corrected pointer must have been written, not just held",
            realIndex.toList(),
            VaultKeyringSession.vaults()[position].indexBlobId.toList(),
        )
    }

    /** A search that finds nothing must not write. */
    @Test
    fun aFailedSearchLeavesTheKeyringExactlyAsItWas() {
        vault.create(pass())
        val position = VaultKeyringSession.openVaultPosition()
        val entryBefore = VaultKeyringSession.vaults()[position].indexBlobId.toList()

        // An empty vault's index exists, so break the search by looking with a key
        // that opens nothing in this store.
        val stranger = VaultKeys.newVmk()
        assertNull("a stranger's key must find nothing", MetadataDb.findIndex(store, stranger))

        assertEquals(
            "nothing may be written on the way to reporting a failure",
            entryBefore,
            VaultKeyringSession.vaults()[position].indexBlobId.toList(),
        )
    }

    /** Only the vault being repaired is touched. */
    @Test
    fun relinkingOneVaultLeavesTheOthersAlone() {
        vault.create(pass())
        VaultKeyringSession.createVault(store, "Second")
        val entries = VaultKeyringSession.vaults()
        assertTrue("the premise: two vaults", entries.size >= 2)
        val other = entries[1].indexBlobId.toList()

        VaultKeyringSession.relinkIndex(store, 0, ByteArray(16) { 3 })

        assertEquals(other, VaultKeyringSession.vaults()[1].indexBlobId.toList())
    }
}
