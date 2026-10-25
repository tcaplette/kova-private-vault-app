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
import java.security.SecureRandom
import kotlin.system.measureNanoTime

/**
 * The half of P2-16's Verify that needs a device — P2-16.
 *
 * `SortOrderTest` answers "each ordering is correct across a corpus with duplicate
 * dates and missing `createdAt`" on the JVM. This answers "**and survives
 * lock/unlock**", which is about `SharedPreferences` and therefore about a real
 * `Context`.
 *
 * It also measures what a name ordering costs over a corpus larger than any
 * fixture elsewhere, because collation keys are the one part of this change with a
 * price and the result line quotes a number rather than an adjective.
 *
 * **Test-only preference.** The stored ordering is restored in [tearDown] to
 * whatever it was, so nothing this writes outlives it.
 */
@RunWith(AndroidJUnit4::class)
class SortPreferenceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private lateinit var indexId: ByteArray
    private var restoreSort: VaultSort = VaultSort.Default
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultSortTest"
        const val PASS = "correcthorsebattery"
    }

    private fun pass() = PASS.toByteArray(Charsets.UTF_8)
    private fun vmk() = VaultSession.vmk()!!

    @Before fun setUp() {
        VaultLifecycle.lockNow()
        restoreSort = GridPreference.sort(ctx)
        root = File(ctx.filesDir, "sort-${System.nanoTime()}").apply { mkdirs() }
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        vault = VaultLifecycle(File(root, "vault.slots"), store)
        assertTrue("fixture vault must open", vault.create(pass()).opened)
        indexId = VaultSession.metadataBlobId()!!.copyOf()
    }

    @After fun tearDown() {
        GridPreference.setSort(ctx, restoreSort)
        VaultLifecycle.lockNow()
        root.deleteRecursively()
    }

    private fun addItem(name: String, createdAt: Long?, importedAt: Long): VaultItem {
        val content = ByteArray(512).also { rng.nextBytes(it) }
        val blob = store.write(ByteArrayInputStream(content), vmk())
        val item = VaultItem(
            blobIds = listOf(blob.blobId),
            plaintextLength = blob.plaintextLength,
            thumb = null,
            filename = name,
            album = null,
            createdAt = createdAt,
            importedAt = importedAt,
            tags = emptyList(),
        )
        MetadataDb.commit(store, indexId, vmk()) { db -> db.items += item; db }
        return item
    }

    private fun live() = MetadataDb.load(store, indexId, vmk()).items.filter { it.deletedAt == null }

    // --- 1. the Verify's second clause ---------------------------------------

    @Test fun theChosenOrderingSurvivesLockAndUnlock() {
        addItem("bravo.jpg", createdAt = 2_000L, importedAt = 10L)
        addItem("alpha.jpg", createdAt = 1_000L, importedAt = 20L)

        GridPreference.setSort(ctx, VaultSort.NameAZ)
        assertEquals(VaultSort.NameAZ, GridPreference.sort(ctx))
        assertEquals(
            listOf("alpha.jpg", "bravo.jpg"),
            GridPreference.sort(ctx).order(live()).map { it.filename },
        )

        VaultLifecycle.lockNow()
        assertTrue("the fixture vault must reopen", vault.unlockAndOpen(pass()))

        assertEquals(
            "the ordering did not survive the lock",
            VaultSort.NameAZ,
            GridPreference.sort(ctx),
        )
        assertEquals(
            listOf("alpha.jpg", "bravo.jpg"),
            GridPreference.sort(ctx).order(live()).map { it.filename },
        )
    }

    /**
     * Every ordering round-trips, and the stored value is the enum's **name**.
     *
     * The name and not the ordinal is the whole of task 3.1: an ordinal makes the
     * stored value depend on the declaration order, so inserting an ordering in
     * the middle in a later build would silently re-point every device's saved
     * choice at a different one. This reads the raw preference to prove it.
     */
    @Test fun everyOrderingRoundTripsAndIsStoredByName() {
        val raw = ctx.getSharedPreferences("grid", android.content.Context.MODE_PRIVATE)
        VaultSort.entries.forEach { chosen ->
            GridPreference.setSort(ctx, chosen)
            assertEquals("$chosen did not read back", chosen, GridPreference.sort(ctx))
            assertEquals(
                "the ordering must be stored by name, never by ordinal",
                chosen.name,
                raw.getString("sort", null),
            )
        }
    }

    @Test fun anUnwrittenPreferenceIsTheShippedOrdering() {
        ctx.getSharedPreferences("grid", android.content.Context.MODE_PRIVATE)
            .edit().remove("sort").commit()
        assertEquals(
            "a device that has never chosen must get the ordering the app shipped with",
            VaultSort.Newest,
            GridPreference.sort(ctx),
        )
    }

    /** A value this build does not know must not stop the grid opening. */
    @Test fun anUnrecognisedStoredValueReadsAsTheDefault() {
        ctx.getSharedPreferences("grid", android.content.Context.MODE_PRIVATE)
            .edit().putString("sort", "SortedByVibes").commit()
        assertEquals(VaultSort.Default, GridPreference.sort(ctx))
    }

    /** The density and the ordering are two keys and must not disturb each other. */
    @Test fun theOrderingAndTheDensityAreIndependent() {
        GridPreference.setColumns(ctx, 4)
        GridPreference.setSort(ctx, VaultSort.FirstAdded)
        assertEquals(4, GridPreference.columns(ctx))
        assertEquals(VaultSort.FirstAdded, GridPreference.sort(ctx))

        GridPreference.setColumns(ctx, 2)
        assertEquals("choosing a density changed the ordering", VaultSort.FirstAdded, GridPreference.sort(ctx))
    }

    // --- 2. the trash keeps its own order -------------------------------------

    @Test fun theTrashIsNotReordered() {
        val a = addItem("zulu.jpg", createdAt = 3_000L, importedAt = 10L)
        val b = addItem("alpha.jpg", createdAt = 1_000L, importedAt = 20L)
        GridPreference.setSort(ctx, VaultSort.NameAZ)

        VaultDeletion.delete(store, indexId, vmk(), a, now = 100L)
        VaultDeletion.delete(store, indexId, vmk(), b, now = 200L)

        // What MainActivity builds for the trash: deletion time descending, and it
        // never consults the preference.
        val trashed = MetadataDb.load(store, indexId, vmk()).items
            .filter { it.deletedAt != null }
            .sortedByDescending { it.deletedAt }

        assertEquals(
            "the trash must be ordered by when things were deleted, whatever the grid's ordering",
            listOf("alpha.jpg", "zulu.jpg"),
            trashed.map { it.filename },
        )
    }

    // --- 3. what a name ordering costs ----------------------------------------

    /**
     * What ordering a large corpus costs.
     *
     * **This test found a real defect and is the reason it was fixed.** The first
     * version of `order` read [VaultItem.itemId] inside the comparator, and
     * `itemId` formatted sixteen bytes with `String.format` on every read: a
     * 5 000-item name ordering took **13.9 s**. Precomputing the keys once per item
     * brought it to 542 ms — and revealed that the remaining cost was `itemId`
     * itself, now paid n times instead of n log n. Replacing its hex with a table
     * lookup brought the worst case to **20 ms**.
     *
     * **The bar is not a frame budget**, and the first version of this test was
     * wrong to use one. Ordering runs once per `(items, sort)` change and is
     * memoised — on opening a vault, on adding an item, on choosing an ordering —
     * never per frame and never per keystroke. What matters is that it is not
     * perceptible at that moment and that a regression of the kind above fails the
     * build, so the bar is 100 ms for a corpus larger than any real vault: five
     * times the measurement, and a twenty-seventh of what the defect cost.
     */
    @Test fun orderingALargeCorpusIsNotPerceptible() {
        val corpus = (1..5_000).map { n ->
            VaultItem(
                blobIds = listOf(ByteArray(16) { (n * 31).toByte() }),
                plaintextLength = 1L,
                thumb = null,
                filename = "IMG_%05d.jpg".format((n * 7919) % 100_000),
                album = null,
                createdAt = if (n % 3 == 0) null else (n * 1_000L),
                importedAt = n.toLong(),
                tags = emptyList(),
            )
        }

        // Warm the JIT and the collator before measuring.
        repeat(3) { VaultSort.entries.forEach { it.order(corpus) } }

        val worst = VaultSort.entries.maxOf { sort ->
            val ns = measureNanoTime { sort.order(corpus) }
            Log.i(TAG, "$sort over ${corpus.size} items: ${ns / 1000}us")
            ns
        }

        val ms = worst / 1_000_000.0
        Log.i(TAG, "worst ordering over ${corpus.size} items: ${"%.2f".format(ms)}ms")
        assertTrue(
            "ordering ${corpus.size} items took ${"%.2f".format(ms)}ms; over 100ms means the " +
                "per-comparison cost has come back",
            ms < 100.0,
        )
    }
}
