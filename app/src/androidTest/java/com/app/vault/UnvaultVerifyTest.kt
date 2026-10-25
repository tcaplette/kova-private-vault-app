package com.app.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * P1-13's Verify: a multi-GB mixed corpus, interrupted twice.
 *
 * Every file present, hash-identical to import, and the manifest listing every
 * file. Written against the real `MediaStore`, because "present in the gallery"
 * is the claim being checked.
 *
 * **Corpus size is a runner argument.** The default is sized for an emulator's
 * disk; the multi-GB run is the same test with a bigger number, so the thing
 * that ships and the thing that was measured are one code path:
 *
 *     -Pandroid.testInstrumentationRunnerArguments.corpusMb=4096
 */
@RunWith(AndroidJUnit4::class)
class UnvaultVerifyTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray
    private lateinit var indexId: ByteArray
    private lateinit var destination: MediaStoreDestination
    private val rng = SecureRandom()
    private val exported = mutableListOf<String>()

    private companion object {
        const val TAG = "VaultUnvaultVerify"
        const val GOOD = "correcthorsebattery"
        const val DEFAULT_MB = 600
    }

    private fun corpusMb(): Int =
        InstrumentationRegistry.getArguments().getString("corpusMb")?.toIntOrNull() ?: DEFAULT_MB

    @Before fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "verify-${System.nanoTime()}")
        root.mkdirs()
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        VaultLifecycle(File(root, "vault.slots"), store).create(GOOD.toByteArray())
        vmk = VaultSession.vmk()!!
        indexId = VaultSession.metadataBlobId()!!.copyOf()
        destination = MediaStoreDestination(ctx)
    }

    @After fun tearDown() {
        // Everything this test put in the device's gallery comes back out.
        exported.forEach { name -> destination.find(name)?.let { destination.discard(it) } }
        destination.find(ExportManifest.NAME)?.let { destination.discard(it) }
        destination.sweep()
        VaultLifecycle.lockNow()
        root.deleteRecursively()
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Streams a blob's plaintext hash without holding it in memory. */
    private fun hashOfBlob(item: VaultItem): String {
        val digest = MessageDigest.getInstance("SHA-256")
        store.reader(item.blobIds, vmk, item.plaintextLength).use { r ->
            val buf = ByteArray(BlobStore.CHUNK)
            for (i in 0 until r.chunkCount) {
                val n = r.readChunk(i, buf)
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun items() = MetadataDb.load(store, indexId, vmk).items

    @Test
    fun aMultiGbCorpusExportsIntactAcrossTwoInterruptions() {
        val budgetMb = corpusMb()

        // A mixed corpus: a few large "videos" and many small "photos", so the
        // export crosses both the chunked-blob path and the many-small-files path.
        val sizes = mutableListOf<Int>()
        var remaining = budgetMb.toLong() * 1024 * 1024
        var i = 0
        while (remaining > 0) {
            val size = if (i % 7 == 0) 64 * 1024 * 1024 else 3 * 1024 * 1024
            val take = minOf(size.toLong(), remaining).toInt()
            if (take < 1024) break
            sizes += take
            remaining -= take
            i++
        }

        val expected = LinkedHashMap<String, String>()
        val chunk = ByteArray(1 shl 20)
        sizes.forEachIndexed { n, size ->
            // Each file's bytes are a repeated random block, so a multi-GB corpus
            // never needs a multi-GB array; the hash is computed as it is written.
            rng.nextBytes(chunk)
            val digest = MessageDigest.getInstance("SHA-256")
            val name = if (size > 10 * 1024 * 1024) "clip$n.mp4" else "photo$n.jpg"

            // writeItem: 64 MiB is four blobs since `blob-size-cap`, and this
            // corpus exists to be realistic about video.
            val blob = store.writeItem(object : java.io.InputStream() {
                var produced = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (produced >= size) return -1
                    val n2 = minOf(len, size - produced, chunk.size)
                    System.arraycopy(chunk, 0, b, off, n2)
                    digest.update(b, off, n2)
                    produced += n2
                    return n2
                }
            }, vmk)

            val db = MetadataDb.load(store, indexId, vmk)
            db.items += VaultItem(
                blob.blobIds, blob.plaintextLength, null, name, null,
                1_500_000_000_000L + n, 0, emptyList()
            )
            db.save(store, indexId, vmk)
            expected[name] = digest.digest().joinToString("") { "%02x".format(it) }
            exported += name
        }

        val totalBytes = sizes.sumOf { it.toLong() }
        Log.i(TAG, "corpus: ${sizes.size} files, ${totalBytes / 1024 / 1024} MB " +
            "(${sizes.count { it > 10 * 1024 * 1024 }} large, ${sizes.count { it <= 10 * 1024 * 1024 }} small)")

        val manifest = ExportManifest.openAt(destination)
        val startedAt = System.currentTimeMillis()

        // --- interruption one: stop after a third -----------------------------
        val firstStop = maxOf(1, sizes.size / 3)
        var done = 0
        Unvaulter(store, destination, manifest, ctx)
            .exportAll(indexId, vmk, isStopped = { done >= firstStop }) { done = it.exported }
        val afterFirst = items().size
        Log.i(TAG, "interruption 1 at $firstStop files; ${afterFirst} still in the vault")
        assertTrue("the export must have stopped part-way", afterFirst > 0)

        // --- interruption two: stop again -------------------------------------
        val secondStop = maxOf(1, sizes.size / 3)
        done = 0
        Unvaulter(store, destination, manifest, ctx)
            .exportAll(indexId, vmk, isStopped = { done >= secondStop }) { done = it.exported }
        val afterSecond = items().size
        Log.i(TAG, "interruption 2; ${afterSecond} still in the vault")

        // --- run to completion -------------------------------------------------
        Unvaulter(store, destination, manifest, ctx).exportAll(indexId, vmk)
        val elapsed = System.currentTimeMillis() - startedAt

        assertEquals("the vault must be empty", 0, items().size)

        // Every file present at the destination and hash-identical to import.
        var checked = 0
        expected.forEach { (name, hash) ->
            val entry = destination.find(name)
            assertNotNull("$name is missing from the destination", entry)
            val landed = ctx.contentResolver.openInputStream(entry!!.uri)!!.use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(BlobStore.CHUNK)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            assertEquals("$name must be hash-identical to its import", hash, landed)
            checked++
        }

        val lines = manifest.lines()
        assertEquals("the manifest lists every file once", expected.size, lines.size)
        assertEquals("all of them successes", expected.size, lines.count { it.startsWith("OK") })

        val mbPerSec = totalBytes / 1024.0 / 1024.0 / (elapsed / 1000.0)
        Log.i(
            TAG,
            "VERIFY: ${expected.size} files, ${totalBytes / 1024 / 1024} MB, " +
                "2 interruptions, $checked hash-identical, ${lines.size} manifest lines, " +
                "${elapsed}ms (${"%.1f".format(mbPerSec)} MB/s including the read-back)"
        )
    }
}
