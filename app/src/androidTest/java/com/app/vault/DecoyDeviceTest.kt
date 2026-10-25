package com.app.vault

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom
import kotlin.random.Random

/**
 * P2-2 on a device — the dates as they land in a real index, and the log as it
 * reads back.
 *
 * The generator's properties are asserted on the JVM in `DecoyTest`, over thousands
 * of sets. What needs a device is everything after that: that a source's own
 * capture date survives the import, that an assigned one reaches the index, that the
 * log a decoy carries never runs backwards, and that the flow leaves nothing behind
 * that says it ran.
 *
 * The steps are driven directly rather than through the screen — create with an
 * origin, write the history, import with assigned dates — because that is the
 * sequence `MainActivity.createDecoy` performs, and an instrumented test can hold it
 * without an Activity.
 */
@RunWith(AndroidJUnit4::class)
class DecoyDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = ctx.contentResolver
    private lateinit var root: File
    private lateinit var slotsFile: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private val rng = SecureRandom()
    private val created = mutableListOf<Uri>()
    private val cacheFiles = mutableListOf<File>()

    private companion object {
        const val TAG = "DecoyDeviceTest"
        const val REAL = "the-real-vault-passphrase"
        const val DECOY = "the-decoy-vault-passphrase"
        const val DECOY_NAME = "Holiday photos"
    }

    private fun pass(s: String) = s.toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "decoy-${System.nanoTime()}").apply { mkdirs() }
        slotsFile = File(root, "vault.slots")
        store = BlobStore(File(root, "blobs"))
        vault = VaultLifecycle(slotsFile, store)
    }

    @After
    fun tearDown() {
        VaultLifecycle.lockNow()
        created.forEach { runCatching { resolver.delete(it, null, null) } }
        created.clear()
        cacheFiles.forEach { it.delete() }
        cacheFiles.clear()
        root.deleteRecursively()
    }

    /**
     * A source in shared storage, with the capture date the resolver actually
     * reports for it.
     *
     * **This device will not hold a backdated capture date.** `DATE_TAKEN` supplied
     * at insert is replaced with the insertion time; written by update it comes back
     * null; and a JPEG carrying a real `DateTimeOriginal` is scanned to the scan
     * time anyway. So the fixture does not pretend: a "dated" source is one the
     * store reports a date for, whatever that date is, and the property under test —
     * the source's own date wins over an assigned one — is asserted against what the
     * app will actually see.
     *
     * The months-of-spread half of the Verify comes from undated sources, which is
     * the path that assigns, and is what the generator's own JVM tests cover
     * exhaustively.
     */
    private fun source(name: String, dated: Boolean): Pair<Uri, Long?> {
        if (!dated) {
            // A plain file, not a store row. Every MediaStore row reports a date —
            // `SourceMetadata` falls back to `DATE_MODIFIED` when `DATE_TAKEN` is
            // absent, and on this device both are the scan time — so the store
            // cannot produce an undated source at all. A file URI reports a name
            // and a size and nothing else, which is exactly the case that gets a
            // date assigned.
            val f = File(ctx.cacheDir, "$name-${System.nanoTime()}").apply {
                writeBytes(ByteArray(2048).also(rng::nextBytes))
            }
            cacheFiles += f
            val uri = Uri.fromFile(f)
            assertEquals(
                "an undated source must report none", null,
                SourceMetadata.describe(resolver, uri).createdAt
            )
            return uri to null
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/VaultDecoyTest")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        )!!
        created += uri
        resolver.openOutputStream(uri)!!.use { it.write(jpeg()) }
        resolver.update(
            uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
        )

        val reported = SourceMetadata.describe(resolver, uri).createdAt
        assertTrue("the store reported no date at all", reported != null)
        return uri to reported
    }

    private fun jpeg(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        android.graphics.Bitmap
            .createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
            .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    private fun blobsDir() = File(root, "blobs")

    private fun logEntries(): List<LogEntry> {
        // `vault-keyring`, [index]'s reason: the log belongs to a vault.
        if (!VaultSession.isUnlocked) VaultKeyringSession.openVault(0)
        val vmk = VaultSession.vmk()!!
        val id = VaultSession.metadataBlobId()!!
        val db = MetadataDb.load(store, id, vmk)
        return TamperLog.scan(store, db.logBlobId!!, vmk).entries
    }

    /**
     * Ages the store, which is what an install that has existed for a while looks
     * like — §6's install chaff is written once and never rewritten, so its mtime is
     * the install date and it is the ceiling on any age a vault can claim.
     *
     * Without this a test store is seconds old, and [Decoy.origin] correctly refuses
     * to claim otherwise. `aFreshInstallCarriesNoHistory` asserts that case; the rest
     * are about a device someone has owned for a while.
     */
    private fun ageTheInstall(days: Long) {
        val at = System.currentTimeMillis() - days * Decoy.DAY
        blobsDir().listFiles().orEmpty().forEach { it.setLastModified(at) }
    }

    /**
     * The flow's own sequence: create with an origin, write the history, import the
     * planned items. Returns the origin it used.
     *
     * Runs on an install that already holds a vault, because that is the only way to
     * reach the flow — it is behind Settings, which is behind an unlocked vault.
     */
    private fun runDecoyFlow(
        sources: List<Uri>,
        preserve: List<ByteArray> = emptyList(),
    ): Long {
        if (!vault.vaultExists()) {
            assertTrue(vault.create(pass(REAL)).opened)
            VaultLifecycle.lockNow()
        }
        ageTheInstall(200)

        val startedAt = System.currentTimeMillis()
        val now = startedAt
        val window = Decoy.setupWindow(startedAt, now)
        val r = Random(4)

        val dates = sources.map {
            runCatching { SourceMetadata.describe(resolver, it).createdAt }.getOrNull()
        }
        val plan = Decoy.plan(dates, window, now, r)
        Log.i(TAG, "plan: kept=${plan.count { it is Decoy.Decision.Keep }} " +
            "assigned=${plan.count { it is Decoy.Decision.Assign }} " +
            "declined=${plan.count { it is Decoy.Decision.Decline }} dates=${dates.take(4)}")
        val origin = Decoy.origin(Decoy.installedAt(blobsDir(), now), window, now, r)

        val keep = if (preserve.isEmpty()) listOf(pass(REAL)) else preserve
        assertTrue(
            "creation must succeed",
            vault.create(pass(DECOY), keep, firstVaultName = DECOY_NAME, origin = origin).opened,
        )

        Decoy.unlockHistory(origin, window, r).forEach { at ->
            TamperLog.append(store, TamperLog.UNLOCK_SUCCEEDED, timestamp = at)
        }

        val vmk = VaultSession.vmk()!!
        val indexId = VaultSession.metadataBlobId()!!
        sources.forEachIndexed { i, uri ->
            val createdAt = plan[i].date ?: return@forEachIndexed
            val blobIds = mutableListOf<ByteArray>()
            val outcome = ImportPipeline(resolver, store).run(
                uri = uri,
                blobIds = blobIds,
                vmk = vmk,
                assignedCreatedAt = createdAt,
                assignedImportedAt = Decoy.assignedImportedAt(createdAt, origin, window, r),
            )
            if (outcome !is ImportOutcome.Done) {
                Log.w(TAG, "import $i did not complete: $outcome")
                return@forEachIndexed
            }
            val done = outcome
            val db = MetadataDb.load(store, indexId, vmk)
            db.items += done.item
            db.save(store, indexId, vmk)
        }
        return origin
    }

    private fun index(): MetadataDb {
        // `vault-keyring`. An unlock opens a keyring; the vault is opened from the
        // list. Every assertion below reads one vault's index, so it is opened here.
        if (!VaultSession.isUnlocked) VaultKeyringSession.openVault(0)
        return MetadataDb.load(store, VaultSession.metadataBlobId()!!, VaultSession.vmk()!!)
    }

    // --- the name -----------------------------------------------------------------

    /**
     * `decoy-flow-overhaul`. The decoy carries the name the user chose.
     *
     * This is the assertion that would have caught the old flow, which omitted
     * `firstVaultName` entirely and so shipped every decoy on every install under
     * `VaultKeyringSession.MIGRATED_NAME` — the placeholder for vaults inherited from
     * a pre-keyring install. A name identical on every install that ran the flow is
     * exactly the marker `decoy-tooling` forbids, and it was the first thing anyone
     * handed the device would have seen.
     */
    @Test
    fun theDecoyCarriesTheNameItWasGivenAndNeverThePlaceholder() {
        runDecoyFlow(listOf(source("a", true).first))

        val names = VaultKeyringSession.vaults().map { it.name }
        assertTrue("the chosen name is on the decoy's keyring: $names", DECOY_NAME in names)
        assertFalse(
            "the migration placeholder must not be what a decoy is called: $names",
            VaultKeyringSession.MIGRATED_NAME in names,
        )
    }

    /** The same validation every other vault creation applies, applied here. */
    @Test
    fun aBlankOrOversizedNameIsNotAValidDecoyName() {
        assertFalse("blank is refused", VaultKeyring.fits(""))
        assertFalse("blank after trimming is refused", VaultKeyring.fits("   ".trim()))
        assertFalse(
            "a name too long for the entry is refused",
            VaultKeyring.fits("x".repeat(VaultKeyring.MAX_NAME_BYTES + 1)),
        )
        assertTrue("an ordinary name is accepted", VaultKeyring.fits(DECOY_NAME))
    }

    // --- the Verify ---------------------------------------------------------------

    @Test
    fun theVerify_noTimestampInTheSetupWindowAndEightWeeksOfSpread() {
        val startedAt = System.currentTimeMillis()
        val window = Decoy.setupWindow(startedAt, startedAt)

        // 40 sources: two thirds carry a real capture date spread over months, the
        // rest carry none and are assigned one.
        // Undated sources, so every date in the finished index is one the flow
        // wrote — which is what the Verify is about.
        val fixtures = (0 until 40).map { source("undated-$it.png", false) }

        runDecoyFlow(fixtures.map { it.first })

        val items = index().items
        assertEquals("every planned item must be in the index", fixtures.size, items.size)

        val createdAts = items.mapNotNull { it.createdAt }
        val importedAts = items.map { it.importedAt }
        assertEquals("every item must carry a capture date", items.size, createdAts.size)

        Log.i(
            TAG,
            "spread ${Decoy.spreadWeeks(createdAts)} weeks, " +
                "created ${createdAts.min()}..${createdAts.max()}, " +
                "imported ${importedAts.min()}..${importedAts.max()}"
        )

        assertTrue(
            "a capture date landed in the setup window",
            Decoy.outsideWindow(createdAts, window)
        )
        assertTrue(
            "an import time landed in the setup window",
            Decoy.outsideWindow(importedAts, window)
        )
        assertTrue(
            "spread was only ${Decoy.spreadWeeks(createdAts)} weeks",
            Decoy.spreadIsEnough(createdAts)
        )
    }

    @Test
    fun aRealCaptureDateSurvivesTheImportAndAnAssignedOneReachesTheIndex() {
        assertTrue(vault.create(pass(REAL)).opened)
        val vmk = VaultSession.vmk()!!
        val assigned = System.currentTimeMillis() - 100 * Decoy.DAY

        val (dated, reported) = source("dated.jpg", true)
        val (undated, _) = source("undated.png", false)

        // Both imported with an assigned date offered. The source's own date must
        // win where there is one and fill in only where there is not: an index
        // claiming a date the file itself contradicts is the tell this whole
        // feature exists to avoid, and the ordering is what makes that structural
        // rather than a rule the caller is trusted to keep.
        val fromDated = (ImportPipeline(resolver, store).run(
            uri = dated, blobIds = mutableListOf(), vmk = vmk,
            assignedCreatedAt = assigned, assignedImportedAt = assigned,
        ) as ImportOutcome.Done).item
        val fromUndated = (ImportPipeline(resolver, store).run(
            uri = undated, blobIds = mutableListOf(), vmk = vmk,
            assignedCreatedAt = assigned, assignedImportedAt = assigned,
        ) as ImportOutcome.Done).item

        assertEquals("a real capture date must not be replaced", reported, fromDated.createdAt)
        assertEquals("an undated source takes the assigned one", assigned, fromUndated.createdAt)
        assertEquals("and the assigned import time is used", assigned, fromDated.importedAt)
    }

    @Test
    fun importNeverPrecedesCapture() {
        val sources = (0 until 6).map { source("item-$it.png", false).first }
        runDecoyFlow(sources)

        val items = index().items
        items.forEach {
            assertTrue(
                "imported ${it.importedAt} before captured ${it.createdAt}",
                it.importedAt >= it.createdAt!!
            )
        }
        assertTrue(
            "the import times must not all be one moment",
            items.map { it.importedAt }.distinct().size > 1
        )
    }

    // --- the log --------------------------------------------------------------------

    @Test
    fun theLogNeverRunsBackwards() {
        val origin = runDecoyFlow(listOf(source("one.png", false).first))

        val entries = logEntries()
        assertTrue("no history was written", entries.size > 1)
        assertEquals("entry 0 must be the creation", TamperLog.VAULT_CREATED, entries.first().event)
        assertEquals("entry 0 must carry the origin", origin, entries.first().timestamp)

        val stamps = entries.map { it.timestamp }
        assertEquals("the log must ascend", stamps.sorted(), stamps)
        assertEquals(
            "the creation must be the earliest thing in it",
            stamps.min(), entries.first().timestamp
        )

        val vmk = VaultSession.vmk()!!
        val db = MetadataDb.load(store, VaultSession.metadataBlobId()!!, vmk)
        assertFalse("the chain must verify", TamperLog.scan(store, db.logBlobId!!, vmk).tampered)
    }

    @Test
    fun theOriginIsNeverEarlierThanTheOldestBlob() {
        val origin = runDecoyFlow(listOf(source("one.png", false).first))
        val oldest = blobsDir().listFiles().orEmpty().filter { it.isFile }.minOf { it.lastModified() }
        // The install date is readable from a directory listing with no passphrase,
        // so a vault claiming to predate it is disproved by `ls`.
        assertTrue("origin $origin predates the oldest blob $oldest", origin >= oldest)
    }

    @Test
    fun ordinaryLoggingContinuesFromTheSyntheticHistory() {
        runDecoyFlow(listOf(source("one.png", false).first))
        val synthetic = logEntries().map { it.timestamp }
        VaultLifecycle.lockNow()

        // `vault-keyring`. The `UNLOCK_SUCCEEDED` entry belongs to a vault and is
        // written when one is opened — an unlock opens a keyring and writes nothing to
        // any vault's log, because at that point it has not read one.
        assertTrue(vault.unlockAndOpen(pass(DECOY)))
        val entries = logEntries()

        val added = entries.map { it.timestamp }.drop(synthetic.size)
        assertTrue("the real unlock must be recorded", added.isNotEmpty())
        assertTrue(
            "a wall-clock entry must follow the synthetic ones",
            added.all { it >= synthetic.max() }
        )
        assertEquals("still ascending", entries.map { it.timestamp }.sorted(), entries.map { it.timestamp })
    }

    @Test
    fun everyEventTypeAlreadyExisted() {
        runDecoyFlow(listOf(source("one.png", false).first))
        val events = logEntries().map { it.event }.toSet()
        assertTrue(
            "the flow must not invent an event type: $events",
            events.all { it == TamperLog.VAULT_CREATED || it == TamperLog.UNLOCK_SUCCEEDED }
        )
    }

    // --- the staleness line -----------------------------------------------------------

    @Test
    fun aQuietVaultSaysWhenItWasLastOpenedAndABusyOneDoesNot() {
        val origin = runDecoyFlow(listOf(source("one.png", false).first))
        val vmk = VaultSession.vmk()!!
        val db = MetadataDb.load(store, VaultSession.metadataBlobId()!!, vmk)
        val scan = TamperLog.scan(store, db.logBlobId!!, vmk)

        val lastUnlock = scan.entries.last { it.event == TamperLog.UNLOCK_SUCCEEDED }.timestamp
        val quiet = TamperLog.digestOf(scan, now = lastUnlock + 200 * Decoy.DAY)
        val busy = TamperLog.digestOf(scan, now = lastUnlock + 3 * Decoy.DAY)

        Log.i(TAG, "origin $origin, quiet digest: $quiet")
        assertTrue("a quiet vault must say so: $quiet", quiet.any { it.startsWith("Not opened since") })
        assertTrue("a vault in use must not: $busy", busy.none { it.startsWith("Not opened since") })
    }

    @Test
    fun theStalenessLineSaysNothingAboutDecoys() {
        val origin = runDecoyFlow(listOf(source("one.png", false).first))
        val vmk = VaultSession.vmk()!!
        val db = MetadataDb.load(store, VaultSession.metadataBlobId()!!, vmk)
        val scan = TamperLog.scan(store, db.logBlobId!!, vmk)
        val line = TamperLog.digestOf(scan, now = System.currentTimeMillis() + 200 * Decoy.DAY)
            .single { it.startsWith("Not opened since") }

        assertFalse("must not name the tooling", line.contains("decoy", ignoreCase = true))
        assertFalse("must not name another vault", line.contains("vault", ignoreCase = true))
        assertTrue("origin was $origin", origin > 0)
    }

    // --- no marker -----------------------------------------------------------------------

    @Test
    fun theFlowLeavesNothingBehindThatSaysItRan() {
        val before = root.listFiles().orEmpty().map { it.name }.toSet()
        runDecoyFlow((0 until 3).map { source("item-$it.png", false).first })
        val after = root.listFiles().orEmpty().map { it.name }.toSet()

        assertEquals("the flow must not add a file of its own", before + setOf("vault.slots"), after)
        assertTrue(
            "and nothing in shared preferences either",
            File(ctx.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
                .none { it.name.contains("decoy", ignoreCase = true) }
        )
    }

    @Test
    fun aFreshInstallCarriesNoHistoryRatherThanAnInventedOne() {
        // The store is seconds old here, deliberately: nothing is aged. A vault
        // cannot predate the app, the app cannot predate the directory listing, and
        // the honest answer is a vault created now.
        val now = System.currentTimeMillis()
        val window = Decoy.setupWindow(now, now)
        val origin = Decoy.origin(Decoy.installedAt(blobsDir(), now), window, now, Random(1))

        assertTrue("no age to claim, so the origin is the truth", origin >= now - Decoy.DAY)
        assertTrue(
            "and there is no room for a history",
            Decoy.unlockHistory(origin, window, Random(1)).isEmpty()
        )
    }

    @Test
    fun anInterruptedFlowLeavesOrdinaryItemsAndNoProgressRecord() {
        val sources = (0 until 4).map { source("item-$it.png", false).first }
        runDecoyFlow(sources.take(2))

        // Process death, as far as anything in memory is concerned. The flow keeps
        // its picks, its plan and its origin nowhere but Compose state, so there is
        // nothing to resume and nothing to find.
        WorkJobs.clearAllForTest()
        val before = root.listFiles().orEmpty().map { it.name }.toSet()

        VaultLifecycle.lockNow()
        assertTrue("the vault must still open", vault.unlockAndOpen(pass(DECOY)))

        val items = index().items
        assertEquals("what was imported is still there", 2, items.size)
        items.forEach {
            // Ordinary in every respect: the flow assigns dates, it does not mark
            // items. Nothing here says how the item arrived.
            assertTrue("an item must carry a real blob", it.blobIds.isNotEmpty())
            assertTrue("and a name", it.filename.isNotEmpty())
        }
        assertEquals("nothing new on disk", before, root.listFiles().orEmpty().map { it.name }.toSet())
        assertEquals("the unused sources were never touched", 4, sources.size)
    }

    @Test
    fun anotherVaultOnTheSameInstallIsUntouched() {
        assertTrue(vault.create(pass(REAL)).opened)
        val realVmk = VaultSession.vmk()!!.copyOf()
        val realId = VaultSession.metadataBlobId()!!.copyOf()
        val realDb = MetadataDb.load(store, realId, realVmk)
        val realLogId = realDb.logBlobId!!.copyOf()
        val realLogBefore = store.fileOf(realLogId).readBytes()
        val realIndexBefore = store.fileOf(realId).readBytes()
        VaultLifecycle.lockNow()

        runDecoyFlow((0 until 3).map { source("item-$it.png", false).first }, listOf(pass(REAL)))
        Log.i(TAG, "decoy created beside the real vault")

        assertArrayEquals2("the other vault's log", realLogBefore, store.fileOf(realLogId).readBytes())
        assertArrayEquals2("the other vault's index", realIndexBefore, store.fileOf(realId).readBytes())
        VaultLifecycle.lockNow()
        assertTrue("the other vault must still open", vault.unlockAndOpen(pass(REAL)))
        assertEquals("and still hold nothing of the decoy's", 0, index().items.size)
    }

    private fun assertArrayEquals2(what: String, expected: ByteArray, actual: ByteArray) {
        assertTrue("$what changed", expected.contentEquals(actual))
    }
}
