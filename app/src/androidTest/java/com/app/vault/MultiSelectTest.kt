package com.app.vault

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * P2-11 — an action on a selection touches the selection and nothing else.
 *
 * `OUTLINE.md`'s Verify is [selectingFiftyOfTwoHundredDeletesExactlyFifty], clause
 * for clause. The rest are the ways "scoped" gets written wrong: a set delete that
 * rewrites the index once per item, and a scoped export that walks rows nobody
 * selected.
 *
 * The gestures and the bar are in `MultiSelectDeviceTest`, which drives the real
 * activity — this file is about rows and blobs.
 */
@RunWith(AndroidJUnit4::class)
class MultiSelectTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: CountingStore
    private lateinit var vault: VaultLifecycle
    private lateinit var indexId: ByteArray
    private val rng = SecureRandom()

    private companion object { const val PASS = "correcthorsebattery" }

    private fun vmk() = VaultSession.vmk()!!

    /**
     * A store that counts writes to one blob — the index.
     *
     * The "one commit for fifty rows" claim is about **how many times the index is
     * rewritten**, and the only honest way to assert that is to count the writes.
     * Timing would measure the emulator.
     */
    private class CountingStore(dir: File, incoming: File) : BlobStore(dir, incoming) {
        var watching: ByteArray? = null
        var writes = 0

        override fun writeTo(blobId: ByteArray, input: InputStream, vmk: ByteArray): WrittenBlob {
            if (watching?.contentEquals(blobId) == true) writes++
            return super.writeTo(blobId, input, vmk)
        }
    }

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "multiselect-${System.nanoTime()}").apply { mkdirs() }
        store = CountingStore(File(root, "blobs"), File(root, "incoming"))
        vault = VaultLifecycle(File(root, "vault.slots"), store)
        assertTrue("fixture vault must open", vault.create(PASS.toByteArray()).opened)
        indexId = VaultSession.metadataBlobId()!!.copyOf()
    }

    @After
    fun tearDown() {
        VaultLifecycle.lockNow()
        ThumbnailCache.clear()
        root.deleteRecursively()
    }

    // --- fixtures -------------------------------------------------------------

    /** [count] items with known content, added in one commit so the fixture is not the test. */
    private fun addItems(count: Int, size: Int = 512): List<VaultItem> {
        val made = (1..count).map { n ->
            val content = ByteArray(size).also { rng.nextBytes(it) }
            val written = store.write(ByteArrayInputStream(content), vmk())
            VaultItem(
                blobIds = listOf(written.blobId),
                plaintextLength = written.plaintextLength,
                thumb = null,
                filename = "item-%03d.bin".format(n),
                album = null,
                createdAt = null,
                importedAt = n.toLong(),
                tags = emptyList(),
            )
        }
        MetadataDb.commit(store, indexId, vmk()) { db -> db.items += made }
        return made
    }

    private fun index() = MetadataDb.load(store, indexId, vmk())
    private fun blobCount() = store.allBlobIds().size

    // --- 1. the outline's Verify ---------------------------------------------

    @Test
    fun selectingFiftyOfTwoHundredDeletesExactlyFifty() {
        val all = addItems(200)
        // Every fourth item, so the selection is scattered through the ordering
        // rather than a contiguous run a naive implementation could get right by
        // accident.
        val selected = all.filterIndexed { i, _ -> i % 4 == 0 }
        assertEquals(50, selected.size)

        val before = index()
        val blobsBefore = blobCount()

        VaultDeletion.purge(store, indexId, vmk(), selected)

        val after = index()
        assertEquals("exactly 150 rows must remain", 150, after.items.size)

        val survivors = after.items.map { it.itemId }.toSet()
        selected.forEach { gone ->
            assertFalse("${gone.filename}: row survived", survivors.contains(gone.itemId))
            gone.blobIds.forEach { id ->
                assertFalse("${gone.filename}: blob still in the pool", store.fileOf(id).exists())
            }
        }
        (all - selected.toSet()).forEach { kept ->
            assertTrue("${kept.filename}: row was taken", survivors.contains(kept.itemId))
            kept.blobIds.forEach { id ->
                assertTrue("${kept.filename}: blob was taken", store.fileOf(id).exists())
            }
        }

        assertEquals(
            "the pool must lose exactly the selection's blobs",
            blobsBefore - selected.sumOf { it.blobIds.size },
            blobCount(),
        )
        assertEquals("chaff is not a delete's business", before.ownedChaff.size, after.ownedChaff.size)
        before.ownedChaff.forEachIndexed { i, id ->
            assertArrayEquals("a chaff blob was rewritten", id, after.ownedChaff[i])
        }

        // "…and the vault re-locks and re-opens clean."
        VaultLifecycle.lockNow()
        assertTrue("the vault must reopen", vault.unlockAndOpen(PASS.toByteArray()))
        val reopened = MetadataDb.load(store, VaultSession.metadataBlobId()!!, vmk())
        assertEquals("150 rows after a reopen", 150, reopened.items.size)
        // Every survivor still reads, which is what "clean" means for the rows
        // that were sharing the index with fifty deletions.
        reopened.items.forEach { item ->
            store.reader(item.blobIds, vmk(), item.plaintextLength).use { r ->
                assertEquals(item.plaintextLength.toInt(), r.readRange(0, item.plaintextLength.toInt()).size)
            }
        }
    }

    // --- 2. one commit, not fifty --------------------------------------------

    @Test
    fun fiftyDeletedRowsCostOneIndexWrite() {
        val all = addItems(60)
        val selected = all.take(50)

        store.watching = indexId
        store.writes = 0
        VaultDeletion.purge(store, indexId, vmk(), selected)

        // Two writes, not fifty: one commit removes all fifty rows and records their
        // blobs as owned chaff (`blob-ownership-by-record`), one later commit untracks
        // them once the files are gone. Neither scales with the selection size.
        assertEquals("fifty rows must cost two index writes, not fifty", 2, store.writes)
        assertEquals(10, index().items.size)
    }

    // --- 3. the scoped export -------------------------------------------------

    @Test
    fun aScopedExportTakesOnlyTheSelection() {
        val all = addItems(12, size = 2048)
        val selected = all.filterIndexed { i, _ -> i % 3 == 0 }
        assertEquals(4, selected.size)

        val destination = MemoryDestination()
        val result = Unvaulter(store, destination, ExportManifest.openAt(destination)).exportAll(
            indexId = indexId,
            vmk = vmk(),
            selection = selected.map { it.itemId }.toSet(),
        )

        assertEquals("only the selection is exported", 4, result.exported)
        assertEquals("the total counts the selection, not the vault", 4, result.total)
        assertEquals(0, result.failed)

        selected.forEach { gone ->
            assertTrue("${gone.filename} must be at the destination", destination.files.containsKey(gone.filename))
            gone.blobIds.forEach { assertFalse("its blob must be gone", store.fileOf(it).exists()) }
        }

        val remaining = index().items
        assertEquals("everything unselected stays in the vault", 8, remaining.size)
        (all - selected.toSet()).forEach { kept ->
            assertTrue(
                "${kept.filename} must not have been written out",
                !destination.files.containsKey(kept.filename),
            )
            assertTrue("${kept.filename} must still be readable", store.fileOf(kept.blobIds.first()).exists())
        }
    }

    @Test
    fun anUnscopedExportIsUnchanged() {
        addItems(6, size = 1024)
        val destination = MemoryDestination()
        val result = Unvaulter(store, destination, ExportManifest.openAt(destination)).exportAll(
            indexId = indexId,
            vmk = vmk(),
        )

        assertEquals("null selection is still the whole vault", 6, result.exported)
        assertEquals(0, index().items.size)
    }

    /**
     * A selection holding an identifier that is no longer in the index — the row
     * was deleted by another path while the selection sat open.
     */
    @Test
    fun anAlreadyDeletedSelectionEntryIsSkipped() {
        val all = addItems(5)
        VaultDeletion.purge(store, indexId, vmk(), listOf(all[2]))
        assertEquals(4, index().items.size)

        // The stale entry is still in the caller's list.
        VaultDeletion.purge(store, indexId, vmk(), listOf(all[0], all[2], all[4]))

        val left = index().items.map { it.itemId }
        assertEquals(2, left.size)
        assertTrue(left.contains(all[1].itemId))
        assertTrue(left.contains(all[3].itemId))
    }

    // --- an export destination that lives in a map ---------------------------

    private class MemoryDestination : ExportDestination {
        val files = LinkedHashMap<String, ByteArray>()

        override fun create(name: String, mime: String, dateTaken: Long?): ExportEntry {
            files[name] = ByteArray(0)
            return ExportEntry(Uri.parse("memory:///$name"), name)
        }

        override fun openOutput(entry: ExportEntry): OutputStream =
            object : ByteArrayOutputStream() {
                override fun close() {
                    files[entry.name] = toByteArray()
                    super.close()
                }
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
            if (files.containsKey(name)) ExportEntry(Uri.parse("memory:///$name"), name) else null

        override fun finish(entry: ExportEntry) = Unit
        override fun discard(entry: ExportEntry) { files.remove(entry.name) }
        override fun sweep(): Int = 0
    }
}
