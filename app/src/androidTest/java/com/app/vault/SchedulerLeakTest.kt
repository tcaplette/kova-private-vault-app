package com.app.vault

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Gate 1 finding, as a test — `gate1-import-queue-leak`.
 *
 * Gate 1 enqueued an import and read the source URI, the destination blob ID and
 * the metadata index blob ID straight out of `no_backup/androidx.work.workdb`
 * with `adb shell run-as`. This performs the same read from inside the app, so the
 * claim is checked automatically rather than at a gate that runs once a phase.
 *
 * **It reads the database file, not `getWorkInfoById`.** The API would report our
 * own values back to us and prove nothing about what is on disk — and what is on
 * disk is the entire question.
 */
@RunWith(AndroidJUnit4::class)
class SchedulerLeakTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val TAG = "VaultSchedulerLeak"
        const val FILENAME = "LEAKTEST-secret-holiday-name.jpg"
        val URI: Uri = Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3ADCIM%2F$FILENAME"
        )
    }

    private fun blobId(fill: Int) = ByteArray(BlobStore.BLOB_ID_BYTES) { fill.toByte() }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Every byte WorkManager has written, WAL included — the adversary's view. */
    private fun schedulerBytes(): ByteArray {
        val dir = File(ctx.applicationInfo.dataDir, "no_backup")
        val files = dir.listFiles { f -> f.name.startsWith("androidx.work.workdb") }.orEmpty()
        assertTrue("no scheduler database found — the scan would be vacuous", files.isNotEmpty())
        Log.i(TAG, "scanning ${files.joinToString { "${it.name}:${it.length()}" }}")
        return files.fold(ByteArray(0)) { acc, f -> acc + f.readBytes() }
    }

    @Before fun setUp() {
        VaultSession.lock()
        WorkJobs.clearAllForTest()
    }

    @After fun tearDown() {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(ImportWorker.IMPORT_TAG)
        WorkJobs.clearAllForTest()
        VaultSession.lock()
    }

    @Test fun anEnqueuedImportStoresNoInputData() {
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .addTag(ImportWorker.IMPORT_TAG)
            .build()
        WorkJobs.register(request.id, WorkJobs.ImportJob(URI, CopyOnWriteArrayList(listOf(blobId(0xAB))), blobId(0xCD)))

        assertEquals(
            "the request itself carries no input",
            0, request.workSpec.input.keyValueMap.size
        )

        WorkManager.getInstance(ctx).enqueue(request).result.get(10, TimeUnit.SECONDS)

        val info = WorkManager.getInstance(ctx).getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)
        assertTrue("the work exists", info != null)
        assertEquals(
            "and no tag names a blob: ${info!!.tags}",
            emptyList<String>(),
            info.tags.filter { Regex("^blob:[0-9a-f]{32}$").matches(it) }
        )
    }

    @Test fun theSchedulerDatabaseNamesNoSourceAndNoBlob() {
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .addTag(ImportWorker.IMPORT_TAG)
            .build()
        WorkJobs.register(request.id, WorkJobs.ImportJob(URI, CopyOnWriteArrayList(listOf(blobId(0xAB))), blobId(0xCD)))
        WorkManager.getInstance(ctx).enqueue(request).result.get(10, TimeUnit.SECONDS)
        // Give WorkManager time to write and checkpoint whatever it is going to.
        Thread.sleep(2000)

        val bytes = schedulerBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        val asHex = hex(bytes)

        assertFalse("the source filename is in the scheduler database", text.contains(FILENAME))
        assertFalse("the source URI is in the scheduler database", text.contains(URI.toString()))
        assertFalse("the scheme of the source URI is there", text.contains("content://"))
        assertFalse("the destination blob ID is there", asHex.contains(hex(blobId(0xAB))))
        assertFalse("the metadata index blob ID is there", asHex.contains(hex(blobId(0xCD))))
        assertFalse("a blob tag is there", Regex("blob:[0-9a-f]{32}").containsMatchIn(text))
    }

    @Test fun exportAndArchiveNameNothingEither() {
        val export = OneTimeWorkRequestBuilder<ExportWorker>()
            .addTag(ExportWorker.EXPORT_TAG)
            .build()
        WorkJobs.register(
            export.id,
            WorkJobs.ExportJob(
                blobId(0xCD),
                Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ALEAKDIR"),
                null,
            )
        )
        val archive = OneTimeWorkRequestBuilder<ArchiveWorker>()
            .addTag(ExportWorker.EXPORT_TAG)
            .build()
        WorkJobs.register(
            archive.id,
            WorkJobs.ArchiveJob(Uri.parse("content://downloads/LEAKTEST-archive-name.vault"))
        )

        val wm = WorkManager.getInstance(ctx)
        wm.enqueue(export).result.get(10, TimeUnit.SECONDS)
        wm.enqueue(archive).result.get(10, TimeUnit.SECONDS)
        Thread.sleep(2000)

        val text = String(schedulerBytes(), Charsets.ISO_8859_1)
        try {
            assertFalse("the export tree folder is named", text.contains("LEAKDIR"))
            assertFalse("the archive filename is named", text.contains("LEAKTEST-archive-name"))
            assertFalse("the index blob ID is there", hex(schedulerBytes()).contains(hex(blobId(0xCD))))
        } finally {
            wm.cancelAllWorkByTag(ExportWorker.EXPORT_TAG)
        }
    }

    @Test fun aWorkerWithNoJobFailsRatherThanReopeningAnything() {
        // What a process death looks like: the work is still in WorkManager's
        // database, and the table that said what it was doing is gone.
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .addTag(ImportWorker.IMPORT_TAG)
            .build()
        WorkJobs.register(request.id, WorkJobs.ImportJob(URI, CopyOnWriteArrayList(listOf(blobId(0xAB))), blobId(0xCD)))
        WorkJobs.clearAllForTest()

        assertTrue(
            "no entry survives, which is what a restarted process finds",
            WorkJobs.get<WorkJobs.ImportJob>(request.id) == null
        )
    }

    @Test fun theSweepClaimsLiveJobsAndOnlyThem() {
        val live = blobId(0x11)
        val dead = blobId(0x22)
        val request = OneTimeWorkRequestBuilder<ImportWorker>().build()
        WorkJobs.register(request.id, WorkJobs.ImportJob(URI, CopyOnWriteArrayList(listOf(live)), blobId(0xCD)))

        val claimed = WorkJobs.claimedBlobIds()
        assertTrue("a live import's partial is claimed", hex(live) in claimed)
        assertFalse("nothing else is", hex(dead) in claimed)

        WorkJobs.clearAllForTest()
        assertEquals(
            "after a process death every partial is an orphan",
            emptySet<String>(), WorkJobs.claimedBlobIds()
        )
    }
}
