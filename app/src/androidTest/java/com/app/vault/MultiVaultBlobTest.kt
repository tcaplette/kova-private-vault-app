package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom

/**
 * One vault's blobs survive another vault's use — `blob-ownership-by-record`, 1.1/1.2.
 *
 * **The defect this exists for.** Ownership used to be decided by a scan: at every
 * unlock, a sweep listed the pool, called everything the open vault's index did not
 * reference an orphan, and adopted it as that vault's owned chaff. The store cannot
 * tell one vault's blob from another's — that is §5 and §6 working exactly as designed
 * — so "unreferenced by *this* index" swept in every blob belonging to every other
 * vault on the device. The per-unlock chaff churn then rewrote one of them, by ID,
 * with fresh random bytes. Opening vault one destroyed vault two's content, one blob
 * at a time, silently.
 *
 * So the two shapes below are the same assertion at two scopes: use one vault
 * repeatedly, and the other one's bytes must be exactly where they were. Ownership is
 * a record the vault wrote, never an inference from what is lying in the pool.
 *
 * **Byte-for-byte, and then read back.** A blob comparison alone would pass against a
 * store that had rewritten a blob with ciphertext of the same length, and an item read
 * alone would pass against a blob that had been quietly re-sealed. Both are asserted
 * because the failure being guarded against replaces bytes in place.
 */
@RunWith(AndroidJUnit4::class)
class MultiVaultBlobTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var slotsFile: File
    private lateinit var store: BlobStore
    private lateinit var lifecycle: VaultLifecycle
    private val rng = SecureRandom()

    private companion object {
        const val PASS = "the-first-lists-passphrase"
        const val OTHER_PASS = "the-second-lists-passphrase"

        const val FIRST = "Photographs"
        const val SECOND = "Receipts"
        const val OTHER = "Papers"

        /** Enough opens that a churn picking one owned blob per unlock would land. */
        const val UNLOCKS = 6

        const val ITEMS = 3
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        lockKeyringAndVault()
        root = File(ctx.filesDir, "multivaultblob-${System.nanoTime()}").apply { mkdirs() }
        slotsFile = File(root, "vault.slots")
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        lifecycle = VaultLifecycle(slotsFile, store)
    }

    @After
    fun tearDown() {
        lockKeyringAndVault()
        root.deleteRecursively()
    }

    // --- fixture ---------------------------------------------------------------

    /** What this vault holds: the rows, and the plaintext each one should read back. */
    private class Contents {
        val items = mutableListOf<VaultItem>()
        val plaintext = mutableMapOf<String, ByteArray>()
    }

    private fun fill(vault: OpenVault, count: Int = ITEMS): Contents {
        val contents = Contents()
        repeat(count) { i ->
            val content = ByteArray(4_096).also { rng.nextBytes(it) }
            val written = store.write(ByteArrayInputStream(content), vault.vmk)
            val item = VaultItem(
                blobIds = listOf(written.blobId),
                plaintextLength = written.plaintextLength,
                thumb = null,
                filename = "item$i.bin",
                album = null,
                createdAt = null,
                importedAt = (i + 1).toLong(),
                tags = emptyList(),
            )
            MetadataDb.commit(store, vault.metadataBlobId, vault.vmk) { db -> db.items += item }
            contents.items += item
            contents.plaintext[written.blobId.hex()] = content
        }
        return contents
    }

    private fun bytesOf(blobId: ByteArray): ByteArray = store.fileOf(blobId).readBytes()

    private fun sealedBytes(contents: Contents): Map<String, ByteArray> =
        contents.items.flatMap { it.blobIds }.associate { it.hex() to bytesOf(it) }

    /**
     * Every blob is the byte sequence it was, and every item reads back its plaintext
     * through the vault that owns it.
     */
    private fun assertIntact(vault: OpenVault, contents: Contents, before: Map<String, ByteArray>) {
        contents.items.forEach { item ->
            item.blobIds.forEach { id ->
                assertTrue("blob ${id.hex()} was deleted", store.fileOf(id).exists())
                assertArrayEquals(
                    "blob ${id.hex()} was rewritten under another vault's churn",
                    before[id.hex()], bytesOf(id),
                )
            }
            val read = store.reader(item.blobIds, vault.vmk, item.plaintextLength).use { reader ->
                reader.readRange(0, item.plaintextLength.toInt())
            }
            assertArrayEquals(
                "${item.filename} no longer reads back what was imported",
                contents.plaintext[item.blobIds.single().hex()], read,
            )
        }

        val db = MetadataDb.load(store, vault.metadataBlobId, vault.vmk)
        assertEquals("every row must still be in the index", contents.items.size, db.items.size)
    }

    /** Opens the vault at [position] the way an unlock does, then closes both levels. */
    private fun useVault(passphrase: String, position: Int = 0) {
        assertTrue(
            "the passphrase must still open its vault",
            lifecycle.unlockAndOpen(pass(passphrase), position = position),
        )
        lockKeyringAndVault()
    }

    // --- 1.1: two vaults on one keyring ----------------------------------------

    @Test
    fun repeatedUseOfOneVaultLeavesTheOtherVaultsBlobsAlone() {
        assertTrue(lifecycle.create(pass(PASS), firstVaultName = FIRST).opened)
        VaultKeyringSession.leaveVault()

        // The second vault on the same list, with the content that must survive.
        val secondPosition = VaultKeyringSession.createVault(store, SECOND)!!
        val second = VaultKeyringSession.openVault(secondPosition)!!
        lifecycle.open(second)
        val contents = fill(second)
        val before = sealedBytes(contents)
        lockKeyringAndVault()

        // The first vault, opened and closed over and over. Each open runs the real
        // per-unlock work — the chaff churn included, which is the thing that used to
        // rewrite whatever the sweep had just handed it.
        repeat(UNLOCKS) { useVault(PASS, position = 0) }

        assertTrue(lifecycle.unlock(pass(PASS)).opened)
        val reopened = VaultKeyringSession.openVault(secondPosition)!!
        lifecycle.open(reopened)
        assertIntact(reopened, contents, before)
    }

    // --- 1.2: two keyrings ------------------------------------------------------

    @Test
    fun repeatedUseOfOneKeyringLeavesAnotherKeyringsBlobsAlone() {
        assertTrue(lifecycle.create(pass(PASS), firstVaultName = FIRST).opened)
        VaultKeyringSession.leaveVault()

        // A second keyring, reachable only by its own passphrase, holding the content
        // that must survive. Nothing the first keyring does may reach it.
        assertTrue(lifecycle.create(pass(OTHER_PASS), firstVaultName = OTHER).opened)
        val other = VaultKeyringSession.openVault(0)!!
        val contents = fill(other)
        val before = sealedBytes(contents)
        lockKeyringAndVault()

        repeat(UNLOCKS) { useVault(PASS, position = 0) }

        assertTrue(lifecycle.unlock(pass(OTHER_PASS)).opened)
        val reopened = VaultKeyringSession.openVault(0)!!
        lifecycle.open(reopened)
        assertIntact(reopened, contents, before)
    }
}
