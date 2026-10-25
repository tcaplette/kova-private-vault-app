package com.app.vault

import android.net.Uri
import android.util.Log
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
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * "Unvault Everything" — P1-13, against I6 and the task's Verify.
 *
 * Driven through [Unvaulter] and a fake destination rather than through
 * WorkManager, for the same reason `ImportPipelineTest` drives `ImportPipeline`:
 * the behaviour worth asserting is per item, and a fake destination can do the
 * one thing a real one cannot — accept a write and hand back different bytes.
 */
@RunWith(AndroidJUnit4::class)
class UnvaultTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private lateinit var vmk: ByteArray
    private lateinit var indexId: ByteArray
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultUnvault"
        const val GOOD = "correcthorsebattery"
    }

    // --- a destination that can misbehave on purpose --------------------------

    private class FakeDestination(
        /** Bytes handed back on read-back, overriding what was written. */
        var corruptReadBack: ByteArray? = null,
        var failCreate: Boolean = false,
    ) : ExportDestination {
        val files = LinkedHashMap<String, ByteArray>()
        val finished = mutableSetOf<String>()
        val discarded = mutableListOf<String>()
        var sweeps = 0

        override fun create(name: String, mime: String, dateTaken: Long?): ExportEntry {
            if (failCreate) throw IllegalStateException("destination refused")
            var free = name
            var n = 1
            while (files.containsKey(free)) {
                n++
                free = ExportDestination.suffixed(name, n)
            }
            files[free] = ByteArray(0)
            return ExportEntry(Uri.parse("fake:///$free"), free)
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
            ByteArrayInputStream(corruptReadBack ?: files[entry.name] ?: ByteArray(0))

        override fun find(name: String): ExportEntry? =
            if (files.containsKey(name)) ExportEntry(Uri.parse("fake:///$name"), name) else null

        override fun finish(entry: ExportEntry) { finished += entry.name }
        override fun discard(entry: ExportEntry) { discarded += entry.name; files.remove(entry.name) }
        override fun sweep(): Int { sweeps++; return 0 }
    }

    @Before fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "unvault-${System.nanoTime()}")
        root.mkdirs()
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        vault = VaultLifecycle(File(root, "vault.slots"), store)
        vault.create(GOOD.toByteArray())
        vmk = VaultSession.vmk()!!
        indexId = VaultSession.metadataBlobId()!!.copyOf()
    }

    @After fun tearDown() {
        VaultLifecycle.lockNow()
        root.deleteRecursively()
    }

    private fun manifestOn(dest: ExportDestination) = ExportManifest.openAt(dest)

    /** Adds an item with known content and returns its bytes. */
    private fun addItem(name: String, size: Int, createdAt: Long? = null): ByteArray {
        val content = ByteArray(size).also { rng.nextBytes(it) }
        val written = store.write(ByteArrayInputStream(content), vmk)
        val db = MetadataDb.load(store, indexId, vmk)
        db.items += VaultItem(
            listOf(written.blobId), written.plaintextLength, null, name, null, createdAt, 0, emptyList()
        )
        db.save(store, indexId, vmk)
        return content
    }

    private fun items() = MetadataDb.load(store, indexId, vmk).items

    // --- 9.1 the happy path ---------------------------------------------------

    @Test fun everythingLeavesAndTheVaultIsEmpty() {
        val a = addItem("one.jpg", 5000)
        val b = addItem("two.mp4", 200_000)

        val dest = FakeDestination()
        val result = Unvaulter(store, dest, manifestOn(dest)).exportAll(indexId, vmk)

        assertEquals("both exported", 2, result.exported)
        assertEquals("none failed", 0, result.failed)
        assertArrayEquals("one.jpg byte-identical", a, dest.files["one.jpg"])
        assertArrayEquals("two.mp4 byte-identical", b, dest.files["two.mp4"])
        assertEquals("the vault is empty", 0, items().size)
        assertTrue("both were finished", dest.finished.containsAll(listOf("one.jpg", "two.mp4")))
        Log.i(TAG, "exported ${result.exported}, vault now holds ${items().size}")
    }

    @Test fun theBlobsAreGoneAfterAVerifiedExport() {
        addItem("gone.jpg", 3000)
        val blobId = items().single().blobIds.first().copyOf()
        assertTrue(store.fileOf(blobId).exists())

        val dest = FakeDestination()
        Unvaulter(store, dest, manifestOn(dest)).exportAll(indexId, vmk)

        assertFalse("the encrypted copy must be deleted", store.fileOf(blobId).exists())
    }

    // --- 9.2 / 9.3 I6: nothing is deleted that did not round-trip --------------

    @Test fun aDamagedBlobIsNeverDeleted() {
        addItem("damaged.jpg", 100_000)
        val blobId = items().single().blobIds.first().copyOf()

        // Corrupt a chunk so decryption fails authentication.
        RandomAccessFile(store.fileOf(blobId), "rw").use { raf ->
            raf.seek(32)
            raf.write(ByteArray(64) { 0x7f })
        }

        val dest = FakeDestination()
        val result = Unvaulter(store, dest, manifestOn(dest)).exportAll(indexId, vmk)

        assertEquals("nothing exported", 0, result.exported)
        assertEquals("one failure", 1, result.failed)
        assertTrue("the encrypted copy must survive", store.fileOf(blobId).exists())
        assertEquals("and stay in the index", 1, items().size)
        assertTrue("the failure is in the manifest",
            manifestOn(dest).lines().any { it.startsWith("FAILED") && it.contains("damaged.jpg") })
    }

    @Test fun aWriteThatDidNotLandIsCaught() {
        addItem("silent-loss.jpg", 50_000)
        val blobId = items().single().blobIds.first().copyOf()

        // The destination accepts every byte and hands back something else. Only
        // a read-back catches this; hashing the outgoing stream cannot.
        val dest = FakeDestination(corruptReadBack = ByteArray(50_000))
        val result = Unvaulter(store, dest, manifestOn(dest)).exportAll(indexId, vmk)

        assertEquals("must not count as exported", 0, result.exported)
        assertTrue("the encrypted copy must survive", store.fileOf(blobId).exists())
        assertEquals("and stay in the index", 1, items().size)
        assertTrue("the written file is discarded", dest.discarded.contains("silent-loss.jpg"))
        Log.i(TAG, "read-back mismatch caught, blob retained")
    }

    @Test fun aDestinationThatRefusesLeavesEverythingAlone() {
        addItem("nowhere.jpg", 4000)
        val blobId = items().single().blobIds.first().copyOf()

        val dest = FakeDestination()
        val manifest = manifestOn(dest)
        dest.failCreate = true
        val result = Unvaulter(store, dest, manifest).exportAll(indexId, vmk)

        assertEquals(0, result.exported)
        assertTrue(store.fileOf(blobId).exists())
        assertEquals(1, items().size)
    }

    // --- 9.4 collisions --------------------------------------------------------

    @Test fun collisionsSuffixAndNeverOverwrite() {
        val first = addItem("holiday.jpg", 1000)
        val second = addItem("holiday.jpg", 2000)

        val dest = FakeDestination()
        val result = Unvaulter(store, dest, manifestOn(dest)).exportAll(indexId, vmk)

        assertEquals("both out", 2, result.exported)
        assertEquals(
            "a suffix, not an overwrite",
            setOf("holiday.jpg", "holiday (2).jpg", ExportManifest.NAME),
            dest.files.keys
        )
        val written = listOf(dest.files["holiday.jpg"]!!, dest.files["holiday (2).jpg"]!!)
        assertTrue("both files survive intact",
            written.any { it.contentEquals(first) } && written.any { it.contentEquals(second) })

        val manifest = manifestOn(dest).lines()
        assertTrue("the manifest names what was actually written",
            manifest.any { it.contains("holiday (2).jpg") })
    }

    @Test fun theSuffixHelperKeepsTheExtension() {
        assertEquals("a (2).jpg", ExportDestination.suffixed("a.jpg", 2))
        assertEquals("no-extension (3)", ExportDestination.suffixed("no-extension", 3))
        assertEquals(".hidden (2)", ExportDestination.suffixed(".hidden", 2))
    }

    // --- manifest --------------------------------------------------------------

    @Test fun theManifestListsEveryFileIncludingFailures() {
        addItem("fine-one.jpg", 1000)
        addItem("fine-two.jpg", 1000)
        addItem("broken.jpg", 40_000)
        val broken = items().first { it.filename == "broken.jpg" }.blobIds.first().copyOf()
        RandomAccessFile(store.fileOf(broken), "rw").use { it.seek(32); it.write(ByteArray(64)) }

        val dest = FakeDestination()
        val manifest = manifestOn(dest)
        Unvaulter(store, dest, manifest).exportAll(indexId, vmk)

        val lines = manifest.lines()
        assertEquals("one line per item", 3, lines.size)
        assertEquals("two successes", 2, lines.count { it.startsWith("OK") })
        assertEquals("one failure", 1, lines.count { it.startsWith("FAILED") })
        assertTrue("failures name a reason", lines.first { it.startsWith("FAILED") }.trim().split("\t").size >= 5)
        Log.i(TAG, "manifest:\n${lines.joinToString("\n")}")
    }

    @Test fun theManifestIsAppendedNotRewritten() {
        addItem("first.jpg", 1000)
        val dest = FakeDestination()
        val manifest = manifestOn(dest)
        Unvaulter(store, dest, manifest).exportAll(indexId, vmk)
        assertEquals(1, manifest.lines().size)

        // A second run appends to the same manifest rather than starting over —
        // which is what makes an interrupted export's record survive.
        addItem("second.jpg", 1000)
        Unvaulter(store, dest, manifest).exportAll(indexId, vmk)
        assertEquals("both runs are in one manifest", 2, manifest.lines().size)
    }

    // --- 9.10 resume -----------------------------------------------------------

    @Test fun anInterruptedExportResumesWithoutLossOrDuplication() {
        val contents = (1..6).associate { "file$it.jpg" to addItem("file$it.jpg", 20_000) }

        val dest = FakeDestination()
        val manifest = manifestOn(dest)

        // Stop after three, twice over — the Verify's "interrupted twice".
        var seen = 0
        val first = Unvaulter(store, dest, manifest)
            .exportAll(indexId, vmk, isStopped = { seen >= 3 }) { seen = it.exported }
        assertEquals("stopped part-way", 3, first.exported)
        assertEquals("the rest are still in the vault", 3, items().size)

        seen = 0
        val second = Unvaulter(store, dest, manifest)
            .exportAll(indexId, vmk, isStopped = { seen >= 2 }) { seen = it.exported }
        assertEquals(2, second.exported)
        assertEquals(1, items().size)

        val third = Unvaulter(store, dest, manifest).exportAll(indexId, vmk)
        assertEquals(1, third.exported)

        assertEquals("the vault is empty", 0, items().size)
        contents.forEach { (name, bytes) ->
            assertArrayEquals("$name must be present once and intact", bytes, dest.files[name])
        }
        assertEquals("every file listed exactly once", 6, manifest.lines().count { it.startsWith("OK") })
        Log.i(TAG, "resume: 3 + 2 + 1 exported across three runs, nothing duplicated")
    }

    @Test fun aFailedItemDoesNotStallTheRestOfTheRun() {
        addItem("broken.jpg", 40_000)
        val broken = items().single().blobIds.first().copyOf()
        RandomAccessFile(store.fileOf(broken), "rw").use { it.seek(32); it.write(ByteArray(64)) }
        addItem("fine.jpg", 1000)

        val dest = FakeDestination()
        val result = Unvaulter(store, dest, manifestOn(dest)).exportAll(indexId, vmk)

        assertEquals("the good one still leaves", 1, result.exported)
        assertEquals("the broken one is reported", 1, result.failed)
        assertEquals("and stays in the vault", 1, items().size)
        assertEquals("broken.jpg", items().single().filename)
    }

    // --- 9.11 the tamper log ---------------------------------------------------

    @Test fun anExportIsRecordedInTheLog() {
        addItem("logged.jpg", 1000)
        val logId = MetadataDb.load(store, indexId, vmk).logBlobId!!.copyOf()

        TamperLog.append(store, TamperLog.EXPORT_STARTED)
        val dest = FakeDestination()
        val result = Unvaulter(store, dest, manifestOn(dest)).exportAll(indexId, vmk)
        TamperLog.append(store, TamperLog.EXPORT_COMPLETED, TamperLog.countPayload(result.exported))

        val entries = TamperLog.scan(store, logId, vmk).entries
        assertEquals("one start", 1, entries.count { it.event == TamperLog.EXPORT_STARTED })
        val completed = entries.filter { it.event == TamperLog.EXPORT_COMPLETED }
        assertEquals("one completion", 1, completed.size)
        assertEquals("carrying the count", 1, completed.single().count)
    }

    // --- MIME and destination selection ----------------------------------------

    @Test fun mimeComesFromTheExtension() {
        assertEquals("image/jpeg", ExportDestination.mimeOf("a.jpg"))
        assertEquals("video/mp4", ExportDestination.mimeOf("a.mp4"))
        assertEquals("image/png", ExportDestination.mimeOf("PHOTO.PNG"))
        // The documented consequence: no extension means Downloads, not the
        // gallery. Present and correct, simply not in Photos.
        assertEquals("application/octet-stream", ExportDestination.mimeOf("no-extension"))
        assertEquals("application/octet-stream", ExportDestination.mimeOf("weird.zzz"))
    }

    // --- I3 --------------------------------------------------------------------

    @Test fun exportWritesNoPlaintextInsideTheApp() {
        val content = addItem("secret.jpg", 120_000)
        val needle = content.copyOfRange(0, 64)

        val dest = FakeDestination()
        Unvaulter(store, dest, manifestOn(dest)).exportAll(indexId, vmk)

        // I3: the destination is the one sanctioned plaintext output. Nothing may
        // appear in filesDir, cacheDir or noBackupFilesDir.
        listOf(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir).forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                assertFalse(
                    "plaintext found in ${f.absolutePath}",
                    f.readBytes().containsSlice(needle)
                )
            }
        }
        Log.i(TAG, "I3: no plaintext in filesDir, cacheDir or noBackupFilesDir")
    }

    @Test fun theManifestNeverStagesInsideTheApp() {
        addItem("named-file.jpg", 1000)
        val dest = FakeDestination()
        Unvaulter(store, dest, manifestOn(dest)).exportAll(indexId, vmk)

        // Staging the manifest in filesDir would put a plaintext list of filenames
        // in the app's own storage on every export. It lives at the destination.
        assertFalse(
            "a manifest must not be staged in filesDir",
            File(ctx.filesDir, ExportManifest.NAME).exists()
        )
        listOf(ctx.filesDir, ctx.cacheDir).forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                assertFalse(
                    "a filename leaked into ${f.absolutePath}",
                    f.readBytes().containsSlice("named-file.jpg".toByteArray())
                )
            }
        }
    }

    private fun ByteArray.containsSlice(needle: ByteArray): Boolean {
        if (needle.isEmpty() || size < needle.size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
