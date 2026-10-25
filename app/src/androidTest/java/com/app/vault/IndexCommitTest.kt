package com.app.vault

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
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2-9's regression test: concurrent commits to the index do not lose or corrupt.
 *
 * **What this replaced.** Before the fix, the same seven threads doing what seven
 * `ImportWorker`s do produced, measured on this emulator:
 *
 * ```
 * threads=7  saved_ok=3  save_threw=4  recorded_in_index=-1
 * INDEX UNREADABLE: EOFException
 * ```
 *
 * Four saves threw `could not move blob into place` and the index was left
 * **unparseable**. `BlobStore` stages every write at `incoming/<blobId>.tmp`, and
 * the index's blob ID is fixed, so concurrent savers interleaved bytes into one
 * staging file and renamed the mixture into place. On a real device the same race
 * degraded more quietly — seven imports, one item recorded — which is how it was
 * reported.
 *
 * **Why P2-8's suite passed while this was broken.** Its fixture appended items
 * from the test thread, sequentially, and only then launched the app. It exercised
 * the pipeline thoroughly and the concurrency not at all. A fixture built by hand
 * cannot find a bug in how the app builds one, which is why this test drives the
 * commit path itself rather than constructing an index to look at.
 */
@RunWith(AndroidJUnit4::class)
class IndexCommitTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray
    private lateinit var indexId: ByteArray

    @Before
    fun setUp() {
        dir = File(ctx.cacheDir, "indexcommit-${System.nanoTime()}")
        store = BlobStore(File(dir, "blobs"), File(dir, "incoming"))
        vmk = ByteArray(32).also { SecureRandom().nextBytes(it) }
        indexId = ByteArray(16).also { SecureRandom().nextBytes(it) }
        MetadataDb.empty().save(store, indexId, vmk)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun item(i: Int): VaultItem {
        val written = store.write(ByteArrayInputStream(ByteArray(1024) { i.toByte() }), vmk)
        return VaultItem(
            blobIds = listOf(written.blobId),
            plaintextLength = written.plaintextLength,
            thumb = null,
            filename = "item$i.jpg",
            album = null,
            createdAt = null,
            importedAt = System.currentTimeMillis(),
            tags = emptyList(),
        )
    }

    /** Seven concurrent appends — the reported batch size — record seven items. */
    @Test
    fun aConcurrentBatchRecordsEveryItem() {
        val n = 7
        val start = CountDownLatch(1)
        val done = CountDownLatch(n)
        val failures = AtomicInteger(0)

        repeat(n) { i ->
            Thread {
                try {
                    val prepared = item(i)
                    start.await()
                    MetadataDb.commit(store, indexId, vmk) { db -> db.items += prepared }
                } catch (e: Throwable) {
                    failures.incrementAndGet()
                } finally {
                    done.countDown()
                }
            }.start()
        }

        start.countDown()
        done.await()

        assertEquals("no commit may throw", 0, failures.get())
        val recorded = MetadataDb.load(store, indexId, vmk).items.size
        assertEquals(
            "items in the index must equal the commits that succeeded",
            n,
            recorded,
        )
    }

    /** The index still parses after a heavier concurrent load. */
    @Test
    fun concurrentCommitsNeverCorruptTheIndex() {
        val n = 16
        val start = CountDownLatch(1)
        val done = CountDownLatch(n)

        repeat(n) { i ->
            Thread {
                val prepared = item(i)
                start.await()
                runCatching { MetadataDb.commit(store, indexId, vmk) { db -> db.items += prepared } }
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await()

        // Loading is the assertion: an unparseable index throws, and an index that
        // will not parse is a vault that will not open.
        val db = MetadataDb.load(store, indexId, vmk)
        assertEquals(n, db.items.size)
        assertEquals(
            "every item is distinct — no append overwrote another",
            n,
            db.items.map { it.itemId }.toSet().size,
        )
    }

    /** Each commit observes what the previous one wrote. */
    @Test
    fun eachCommitSeesThePreviousOnesWrite() {
        val n = 8
        val observed = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val done = CountDownLatch(n)

        repeat(n) { i ->
            Thread {
                val prepared = item(i)
                MetadataDb.commit(store, indexId, vmk) { db ->
                    observed += db.items.size
                    db.items += prepared
                }
                done.countDown()
            }.start()
        }
        done.await()

        assertEquals(
            "each commit must have seen a distinct, growing index",
            (0 until n).toList(),
            observed.sorted(),
        )
    }

    /** A block that throws releases the guard and leaves the previous index readable. */
    @Test
    fun aThrowingCommitReleasesTheGuard() {
        MetadataDb.commit(store, indexId, vmk) { db -> db.items += item(0) }

        val boom = runCatching {
            MetadataDb.commit<Unit>(store, indexId, vmk) { error("commit blew up") }
        }
        assertTrue("the failure must surface", boom.isFailure)

        // The guard is free and the previously saved index is intact.
        MetadataDb.commit(store, indexId, vmk) { db -> db.items += item(1) }
        assertEquals(2, MetadataDb.load(store, indexId, vmk).items.size)
    }

    /**
     * The successor to adoption — `blob-ownership-by-record`, D4.
     *
     * A blob is recorded **before** it can be stranded, so the window a sweep used to
     * clean up afterwards is closed at the moment the blob is sealed rather than at the
     * next unlock. This is the import path's exact shape: one commit that records, then
     * the kill, then the commit that creates the row and takes the record back out.
     */
    @Test
    fun anInterruptedImportLeavesItsBlobsAlreadyRecorded() {
        val prepared = item(0)

        // What `ImportWorker` does the moment the content blobs are sealed — and then
        // nothing else, which is the kill.
        MetadataDb.commit(store, indexId, vmk) { db -> db.ownedChaff += prepared.blobIds }

        val stranded = MetadataDb.load(store, indexId, vmk)
        assertTrue("no row can reference them yet", stranded.items.isEmpty())
        prepared.blobIds.forEach { id ->
            assertTrue(
                "a blob sealed before the row that names it must already be recorded",
                stranded.ownedChaff.any { it.contentEquals(id) },
            )
            assertTrue("and it is still on disk", store.fileOf(id).exists())
        }

        // The commit that creates the row moves them out of the record, in the one
        // transaction that makes them the item's.
        MetadataDb.commit(store, indexId, vmk) { db ->
            db.items += prepared
            db.ownedChaff.removeAll { id -> prepared.blobIds.any { it.contentEquals(id) } }
        }

        val completed = MetadataDb.load(store, indexId, vmk)
        assertEquals(1, completed.items.size)
        prepared.blobIds.forEach { id ->
            assertTrue(
                "the item must reference every blob it owns",
                completed.items.single().blobIds.any { it.contentEquals(id) },
            )
            assertTrue(
                "a completed import must leave none of its blobs in owned chaff",
                completed.ownedChaff.none { it.contentEquals(id) },
            )
        }
    }

    /**
     * Nothing scans — `blob-ownership-by-record`, D1.
     *
     * A sealed blob this vault never recorded is left exactly where it is. It is not
     * adopted, because the store cannot tell it from another vault's content, and the
     * old sweep's answer to that question was a guess with a vault on the other side of
     * it. Untracked is the correct resting state, not a gap.
     */
    @Test
    fun anUntrackedSealedBlobIsLeftAlone() {
        val stray = store.write(ByteArrayInputStream(ByteArray(2048) { 9 }), vmk).blobId
        MetadataDb.commit(store, indexId, vmk) { db -> db.items += item(0) }

        val db = MetadataDb.load(store, indexId, vmk)
        assertTrue(
            "a blob this vault never recorded must stay untracked",
            db.ownedChaff.none { it.contentEquals(stray) },
        )
        assertTrue("and must be left on disk", store.fileOf(stray).exists())
    }

    /**
     * A nested commit reuses the open index instead of reloading.
     *
     * This is the `TamperLog.append`-inside-`ImportWorker` shape. A non-reentrant
     * guard deadlocks here; a reentrant one that *reloaded* would read the
     * pre-mutation index from disk and discard the outer caller's work — the same
     * lost update, one level in.
     */
    @Test
    fun aNestedCommitDoesNotDiscardTheOuterWork() {
        MetadataDb.commit(store, indexId, vmk) { outer ->
            outer.items += item(0)
            MetadataDb.commit(store, indexId, vmk) { inner ->
                assertTrue("the nested commit must see the outer's work", inner.items.size == 1)
                inner.items += item(1)
            }
        }
        assertEquals(2, MetadataDb.load(store, indexId, vmk).items.size)
    }
}
