package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import kotlin.system.measureNanoTime

/**
 * P2-15 — a query over a real index.
 *
 * `SearchMatchTest` answers what a query matches, on the JVM and without a device.
 * This one answers the half that is about **rows**: the outline's second Verify
 * clause — a query matches nothing once the matching item is deleted — which is
 * true here because the grid's list is the live rows and the query narrows that.
 *
 * It also measures what a keystroke costs over a corpus larger than any fixture
 * elsewhere in the suite, because "in memory over the loaded index" is a
 * performance claim and the result line quotes a number rather than an adjective.
 */
@RunWith(AndroidJUnit4::class)
class SearchTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: CountingStore
    private lateinit var vault: VaultLifecycle
    private lateinit var indexId: ByteArray
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultSearchTest"
        const val PASS = "correcthorsebattery"
    }

    private fun pass() = PASS.toByteArray(Charsets.UTF_8)
    private fun vmk() = VaultSession.vmk()!!

    /** `ItemTrashTest`'s subclass: "a search commits nothing" is a claim about index writes. */
    private class CountingStore(dir: File, incoming: File) : BlobStore(dir, incoming) {
        var watching: ByteArray? = null
        var writes = 0

        override fun writeTo(blobId: ByteArray, input: InputStream, vmk: ByteArray): WrittenBlob {
            if (watching?.contentEquals(blobId) == true) writes++
            return super.writeTo(blobId, input, vmk)
        }
    }

    @Before fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "search-${System.nanoTime()}").apply { mkdirs() }
        store = CountingStore(File(root, "blobs"), File(root, "incoming"))
        vault = VaultLifecycle(File(root, "vault.slots"), store)
        assertTrue("fixture vault must open", vault.create(pass()).opened)
        indexId = VaultSession.metadataBlobId()!!.copyOf()
    }

    @After fun tearDown() {
        VaultLifecycle.lockNow()
        root.deleteRecursively()
    }

    // --- fixtures -------------------------------------------------------------

    private fun addItem(
        name: String,
        folder: String? = null,
        tags: List<String> = emptyList(),
        size: Int = 1024,
    ): VaultItem {
        val content = ByteArray(size).also { rng.nextBytes(it) }
        val blob = store.write(ByteArrayInputStream(content), vmk())
        // `folder-system`: a folder is a record, so the fixture makes one (or finds
        // the one it already made) and the row points at it.
        val folderId = folder?.let { wanted ->
            MetadataDb.commit(store, indexId, vmk()) { db ->
                (db.folders.firstOrNull { it.name == wanted }
                    ?: VaultFolder(MetadataDb.newFolderId(), null, wanted).also { db.folders += it })
                    .folderId
            }
        }
        val item = VaultItem(
            blobIds = listOf(blob.blobId),
            plaintextLength = blob.plaintextLength,
            thumb = null,
            filename = name,
            album = null,
            createdAt = null,
            importedAt = System.currentTimeMillis(),
            tags = tags,
            folderId = folderId,
        )
        MetadataDb.commit(store, indexId, vmk()) { db -> db.items += item; db }
        return item
    }

    private fun index() = MetadataDb.load(store, indexId, vmk())

    /**
     * The list the grid would show, then the query — the same order `MainActivity`
     * applies them in, which is what makes a trashed item unable to match.
     */
    private fun results(query: String): List<VaultItem> {
        val db = index()
        val live = db.items.filter { it.deletedAt == null }
        // The folder's *name* is what a query can match, and the row carries a
        // reference — so the caller resolves it, exactly as `MainActivity` does.
        val names = db.folders.associate { it.id to it.name }
        // Named, for the reason `MainActivity`'s call site carries: `textOf` sits
        // after `folderName`, so a trailing lambda answers the wrong question and the
        // folder stops matching while everything else still does.
        return VaultSearch.filter(
            live,
            query,
            folderName = { item -> item.folderId?.let { names[hex(it)] } },
        )
    }

    private fun names(query: String) = results(query).map { it.filename }.sorted()

    // --- 1. the outline's Verify ----------------------------------------------

    @Test fun aQueryMatchesOnEachOfTheThreeFields() {
        addItem("IMG_4471.jpg")
        addItem("holiday.png", folder = "Passports")
        // Nothing in the app writes tags — every writer passes emptyList() and no
        // task in OUTLINE.md assigns them. The field is real in the format and
        // round-trips, so a row written here is what proves the third field
        // matches; in the shipped app this query finds nothing until something
        // writes a tag.
        addItem("scan.pdf", tags = listOf("receipt", "2019"))
        addItem("unrelated.mp4")

        assertEquals("the filename did not match", listOf("IMG_4471.jpg"), names("4471"))
        assertEquals("the folder did not match", listOf("holiday.png"), names("passport"))
        assertEquals("the tag did not match", listOf("scan.pdf"), names("receipt"))
        assertEquals("a query matched something it should not", emptyList<String>(), names("nomatch"))
    }

    @Test fun theTagFieldRoundTripsThroughTheIndex() {
        val item = addItem("scan.pdf", tags = listOf("receipt"))
        VaultLifecycle.lockNow()
        assertTrue("the fixture vault must reopen", vault.unlockAndOpen(pass()))

        val reloaded = index().items.first { it.itemId == item.itemId }
        assertEquals("tags did not survive the lock", listOf("receipt"), reloaded.tags)
        assertEquals("the tag stopped matching after a reload", listOf("scan.pdf"), names("receipt"))
    }

    @Test fun aQueryMatchesNothingOnceTheMatchingItemIsDeleted() {
        val item = addItem("IMG_4471.jpg", folder = "Passports", tags = listOf("receipt"))
        addItem("other.png")

        assertEquals("the fixture does not match to begin with", listOf("IMG_4471.jpg"), names("4471"))

        VaultDeletion.delete(store, indexId, vmk(), item)

        // Every field it matched on, and the item is still in the index — the row
        // is in the trash, which is exactly the case the Verify names.
        assertEquals("a trashed item matched its filename", emptyList<String>(), names("4471"))
        assertEquals("a trashed item matched its folder", emptyList<String>(), names("passport"))
        assertEquals("a trashed item matched its tag", emptyList<String>(), names("receipt"))
        assertTrue(
            "the fixture should still be in the index, in the trash",
            index().items.any { it.itemId == item.itemId && it.deletedAt != null },
        )

        VaultDeletion.restore(store, indexId, vmk(), item)
        assertEquals("a restored item stopped matching", listOf("IMG_4471.jpg"), names("4471"))
        Log.i(TAG, "trashed row matched 0 of 3 fields, restored row matched again")
    }

    // --- 2. a search changes nothing ------------------------------------------

    @Test fun searchingCommitsNothing() {
        addItem("IMG_4471.jpg", folder = "Passports")
        addItem("other.png")
        val blobsBefore = store.allBlobIds().size
        val rowsBefore = index().items.map { it.itemId to it.filename }

        store.watching = indexId
        store.writes = 0

        // Every shape of query the UI can produce, including the empty one the
        // search opens with.
        listOf("", "   ", "4471", "passport 4471", "nothingmatches").forEach { results(it) }

        assertEquals("a search wrote the index", 0, store.writes)
        store.watching = null
        assertEquals("a search wrote a blob", blobsBefore, store.allBlobIds().size)
        assertEquals("a search changed a row", rowsBefore, index().items.map { it.itemId to it.filename })
    }

    @Test fun anEmptyQueryShowsEverythingRatherThanNothing() {
        addItem("a.jpg")
        addItem("b.png")
        addItem("c.mp4")
        assertEquals("an empty field is not a question", 3, results("").size)
        assertEquals("whitespace is not a question either", 3, results("   ").size)
    }

    // --- 3. what a keystroke costs --------------------------------------------

    /**
     * The claim is that filtering in memory is not visible between keystrokes.
     *
     * Measured over a corpus built in memory rather than written to the store —
     * the filter never touches a blob, so writing 5 000 of them would measure the
     * emulator's disk and not this. The threshold is deliberately loose: the
     * number in the result line is the measurement, and this asserts only that it
     * is nowhere near a frame.
     */
    @Test fun aQueryOverALargeCorpusCostsLessThanAFrame() {
        // `folder-system`: five thousand folders, so the name lookup the matcher now
        // depends on is measured at its worst — one folder per item rather than a
        // handful shared between them.
        val folders = (1..5_000).associate { n ->
            hex(ByteArray(16) { n.toByte() }) to if (n % 7 == 0) "Passports" else "Trip $n"
        }
        val corpus = (1..5_000).map { n ->
            VaultItem(
                blobIds = listOf(ByteArray(16) { n.toByte() }),
                plaintextLength = 1L,
                thumb = null,
                filename = "IMG_%05d.jpg".format(n),
                album = null,
                createdAt = null,
                importedAt = n.toLong(),
                tags = if (n % 11 == 0) listOf("receipt", "2019") else emptyList(),
                folderId = ByteArray(16) { n.toByte() },
            )
        }
        val nameOf: (VaultItem) -> String? = { item -> item.folderId?.let { folders[hex(it)] } }

        // Warm the JIT before measuring, or the first call measures compilation.
        repeat(5) { VaultSearch.filter(corpus, "passport 4471", nameOf) }

        // ***The best of several runs per query, and the worst across queries.***
        //
        // A single sample of wall-clock time on a shared emulator measures the scheduler
        // as much as the code: this asserted on one sample per query and failed twice in
        // full-suite runs at 141ms and 19.72ms, while passing in isolation every time.
        // Neither number was the cost of the query.
        //
        // The **minimum** is the honest estimate, because preemption can only make a
        // sample slower and never faster, and it is not a weaker guard: an algorithmic
        // regression raises the floor for every repetition, so the minimum moves with it.
        // The threshold is unchanged.
        val worst = listOf("i", "img", "passport", "passport 2019", "nothingmatchesthis")
            .maxOf { q ->
                val ns = (1..7).minOf { measureNanoTime { VaultSearch.filter(corpus, q, nameOf) } }
                Log.i(TAG, "query of ${VaultSearch.terms(q).size} term(s) over ${corpus.size} items: ${ns / 1000}us")
                ns
            }

        val ms = worst / 1_000_000.0
        Log.i(TAG, "worst query over ${corpus.size} items: ${"%.2f".format(ms)}ms")
        assertTrue(
            "a query over ${corpus.size} items took ${"%.2f".format(ms)}ms, which is visible between keystrokes",
            ms < 16.0,
        )
    }
}
