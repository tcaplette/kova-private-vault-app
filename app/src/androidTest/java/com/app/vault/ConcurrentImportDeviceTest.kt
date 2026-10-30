package com.app.vault

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * `concurrent-import-failure` — a batch of imports at once, through the real worker.
 *
 * On 2026-10-30 two photos were picked together and one did not land: its worker
 * failed 34 ms in while its twin, started in the same millisecond, succeeded. Nothing
 * said why. This drives what that batch drove — one `ImportWorker` per item, all at
 * once, against one open vault — and adds the two halves of the fix the batch alone
 * cannot show: a live read failure is rerun rather than lost, and the unlock sweep
 * reads live claims while workers are adding to them.
 *
 * **A real vault in the app's own `filesDir`**, because the worker builds its store
 * there and nowhere else, like `SortDeviceTest`. Removed in [tearDown] with every
 * source this test made.
 */
@RunWith(AndroidJUnit4::class)
class ConcurrentImportDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = ctx.contentResolver
    private val wm get() = WorkManager.getInstance(ctx)
    private val slots get() = File(ctx.filesDir, "vault.slots")
    private val blobsDir get() = File(ctx.filesDir, "blobs")
    private val incoming get() = File(ctx.filesDir, "incoming")
    private val shares get() = File(ctx.filesDir, "shares")

    private var created = false
    private lateinit var store: BlobStore
    private lateinit var indexId: ByteArray
    private val mediaRows = mutableListOf<Uri>()
    private val shareFiles = mutableListOf<File>()

    /** Every failed attempt the worker reported, as step and attempt number. */
    private val failures = Collections.synchronizedList(mutableListOf<Pair<ImportStep, Int>>())

    private companion object {
        const val TAG = "VaultConcurrentImport"
        const val PASS = "concurrent import test passphrase"
        const val BATCH = 8
        const val ROUNDS = 20
    }

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        store = BlobStore(blobsDir, incoming)
        val lifecycle = VaultLifecycle(slots, store)
        assumeTrue("a vault already exists here; not overwriting it", !lifecycle.vaultExists())
        assertTrue("fixture vault must open", lifecycle.create(PASS.toByteArray()).opened)
        created = true
        indexId = VaultSession.metadataBlobId()!!.copyOf()
        ImportWorker.onAttemptFailed = { step, attempt -> failures += step to attempt }
    }

    @After
    fun tearDown() {
        ImportWorker.onAttemptFailed = null
        wm.cancelAllWork()
        WorkJobs.clearAllForTest()
        mediaRows.forEach { runCatching { resolver.delete(it, null, null) } }
        shareFiles.forEach { it.delete() }
        // The write-failure case replaces `incoming/` with a file; put the directory back.
        if (incoming.isFile) incoming.delete()
        if (!created) return
        MetadataDb.awaitQuiescent()
        VaultLifecycle.lockNow()
        slots.delete()
        blobsDir.deleteRecursively()
        incoming.deleteRecursively()
    }

    // --- fixtures ---------------------------------------------------------------

    private fun vmk() = VaultSession.vmk()!!

    private fun itemCount() = MetadataDb.load(store, indexId, vmk()).items.size

    private fun jpeg(seed: Int): ByteArray {
        val bmp = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(30 * seed % 256, 80, 160))
        val paint = Paint().apply { color = Color.rgb(250, 200 - 15 * seed % 180, 40) }
        canvas.drawCircle(600f, 450f, 100f + 30f * seed, paint)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            .toByteArray()
    }

    /** A real `MediaStore` photo — what the picker hands over, minus the picker. */
    private fun mediaPhoto(i: Int): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "concurrent-$i.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/VaultConcurrentImportTest")
        }
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values
        )!!
        resolver.openOutputStream(uri)!!.use { it.write(jpeg(i)) }
        mediaRows += uri
        return uri
    }

    /**
     * A file behind the app's own share provider. Deleting the file makes opening its URI
     * fail with the provider's real `FileNotFoundException`, and writing it back makes it
     * open again — a source whose readability the test controls without a fake.
     */
    private fun shareSource(name: String): Pair<File, Uri> {
        shares.mkdirs()
        val file = File(shares, name)
        shareFiles += file
        return file to FileProvider.getUriForFile(ctx, "${ctx.packageName}.shares", file)
    }

    private fun enqueue(uris: List<Uri>): List<OneTimeWorkRequest> {
        val requests = uris.map { uri ->
            OneTimeWorkRequestBuilder<ImportWorker>().addTag(ImportWorker.IMPORT_TAG).build().also {
                WorkJobs.register(it.id, WorkJobs.ImportJob(uri, CopyOnWriteArrayList(), indexId))
            }
        }
        // One call, so the batch starts together, as `MainActivity.importAll`'s does.
        wm.enqueue(requests).result.get(10, TimeUnit.SECONDS)
        return requests
    }

    private fun awaitFinished(ids: List<UUID>, timeoutMs: Long = 120_000): Map<UUID, WorkInfo.State> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val states = ids.associateWith { wm.getWorkInfoById(it).get(5, TimeUnit.SECONDS)!!.state }
            if (states.values.all { it.isFinished } || System.currentTimeMillis() > deadline) return states
            Thread.sleep(100)
        }
    }

    // --- 1. a batch at once, twenty times (D4) ------------------------------------

    @Test
    fun eightImportsAtOnceAllLandTwentyTimesOver() {
        val sources = (0 until BATCH).map { mediaPhoto(it) }
        val firstAttempt = ImportStep.values().associateWith { AtomicInteger() }
        ImportWorker.onAttemptFailed = { step, attempt ->
            failures += step to attempt
            if (attempt == 1) firstAttempt.getValue(step).incrementAndGet()
        }

        repeat(ROUNDS) { round ->
            val before = itemCount()
            val requests = enqueue(sources)
            val states = awaitFinished(requests.map { it.id })
            val added = itemCount() - before
            Log.i(TAG, "round ${round + 1}: ${states.values.count { it == WorkInfo.State.SUCCEEDED }} of $BATCH succeeded, $added added")
            assertTrue(
                "round ${round + 1}: every worker must succeed: ${states.values}",
                states.values.all { it == WorkInfo.State.SUCCEEDED },
            )
            assertEquals("round ${round + 1}: every item must be in the index", BATCH, added)
        }

        // The measurement: a step that fails under concurrency at all shows up here, even
        // when the reruns covered it.
        val table = firstAttempt.entries.joinToString { (step, n) -> "$step=${n.get()}" }
        Log.i(TAG, "first-attempt failures over ${ROUNDS * BATCH} imports: $table")
    }

    // --- 2. a live read failure is rerun (D2) -------------------------------------

    @Test
    fun aSourceThatFailsToOpenOnceIsImportedAndVerified() {
        val bytes = jpeg(3)
        val (file, uri) = shareSource("retry-once-${System.nanoTime()}.jpg")
        // Absent for the first attempt; written back the moment that attempt reports, so
        // the worker's first rerun finds it.
        ImportWorker.onAttemptFailed = { step, attempt ->
            failures += step to attempt
            if (attempt == 1) file.writeBytes(bytes)
        }

        val before = itemCount()
        val request = enqueue(listOf(uri)).single()
        val state = awaitFinished(listOf(request.id)).getValue(request.id)
        val info = wm.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS)!!

        Log.i(TAG, "failed once: $state after $failures")
        assertEquals("the first open failed, once", listOf(ImportStep.OPEN to 1), failures.toList())
        assertEquals("a rerun that opens must succeed — nothing reported to the user", WorkInfo.State.SUCCEEDED, state)
        assertEquals("the item is in the index", before + 1, itemCount())
        assertTrue("the stored copy verified against the source", info.outputData.getBoolean("deletable", false))
        assertNull("a finished import leaves no job", WorkJobs.get<WorkJobs.ImportJob>(request.id))
    }

    @Test
    fun aSourceThatFailsThreeTimesIsFinalAndLeavesNothing() {
        val (_, uri) = shareSource("retry-never-${System.nanoTime()}.jpg")
        val blobsBefore = blobsDir.list().orEmpty().toSet()
        val itemsBefore = itemCount()

        val request = enqueue(listOf(uri)).single()
        val state = awaitFinished(listOf(request.id)).getValue(request.id)

        Log.i(TAG, "never opens: $state after $failures")
        assertEquals(
            "three attempts, each failing to open",
            listOf(ImportStep.OPEN to 1, ImportStep.OPEN to 2, ImportStep.OPEN to 3),
            failures.toList(),
        )
        assertEquals("the third failure is final", WorkInfo.State.FAILED, state)
        assertEquals("nothing reached the index", itemsBefore, itemCount())
        assertEquals("nothing staged", emptyList<String>(), store.stagedPartials())
        assertEquals("nothing added to the pool", blobsBefore, blobsDir.list().orEmpty().toSet())
        assertNull("a final failure clears its job", WorkJobs.get<WorkJobs.ImportJob>(request.id))
    }

    @Test
    fun aWriteFailureIsFinalOnTheFirstAttempt() {
        val (file, uri) = shareSource("write-fails-${System.nanoTime()}.jpg")
        file.writeBytes(jpeg(5))
        val itemsBefore = itemCount()
        // A store that cannot stage: `incoming/` is a file, so the partial cannot be
        // created — a real I/O failure in the write, not a stand-in for one.
        assertTrue(incoming.deleteRecursively())
        assertTrue(incoming.createNewFile())

        val request = enqueue(listOf(uri)).single()
        val state = awaitFinished(listOf(request.id)).getValue(request.id)

        incoming.delete()
        incoming.mkdirs()
        Log.i(TAG, "write fails: $state after $failures")
        assertEquals("one attempt, failing at the write", listOf(ImportStep.WRITE to 1), failures.toList())
        assertEquals("a write failure is final at once", WorkInfo.State.FAILED, state)
        assertEquals("nothing reached the index", itemsBefore, itemCount())
        assertNull("a final failure clears its job", WorkJobs.get<WorkJobs.ImportJob>(request.id))
    }

    // --- 3. the sweep reads live claims (D3) --------------------------------------

    @Test
    fun theSweepRunsWhileEightWorkersClaimBlobsAndSparesThemAll() {
        // Just over one blob, so each import claims a second blob mid-run and the sweep
        // has appends to race. One file, eight URIs to it, eight jobs.
        val (file, _) = shareSource("sweep-race-${System.nanoTime()}.bin")
        val chunk = ByteArray(1 shl 20).also { java.security.SecureRandom().nextBytes(it) }
        file.outputStream().use { out -> repeat(17) { out.write(chunk) } }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.shares", file)

        val stop = AtomicBoolean(false)
        val sweeps = AtomicInteger()
        val swept = AtomicInteger()
        val thrown = Collections.synchronizedList(mutableListOf<Throwable>())
        val sweeper = Thread {
            val sweepStore = BlobStore(blobsDir, incoming)
            while (!stop.get()) {
                try {
                    swept.addAndGet(sweepStore.sweepUnclaimed { WorkJobs.claimedBlobIds() })
                    sweeps.incrementAndGet()
                } catch (t: Throwable) {
                    thrown += t
                }
            }
        }

        val before = itemCount()
        val requests = enqueue(List(BATCH) { uri })
        sweeper.start()
        val states = awaitFinished(requests.map { it.id }, timeoutMs = 150_000)
        stop.set(true)
        sweeper.join(10_000)

        Log.i(TAG, "sweep race: ${sweeps.get()} sweeps, ${swept.get()} swept, ${thrown.size} threw, states ${states.values}")
        assertTrue("the sweep never ran", sweeps.get() > 0)
        assertEquals("a sweep failed reading live claims: $thrown", 0, thrown.size)
        assertEquals("the sweep deleted a partial a live job claimed", 0, swept.get())
        assertTrue("every import landed: ${states.values}", states.values.all { it == WorkInfo.State.SUCCEEDED })
        assertEquals(BATCH, itemCount() - before)
        assertEquals("no import failed an attempt", emptyList<Pair<ImportStep, Int>>(), failures.toList())
    }
}
