package com.app.vault

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.SecureRandom

/**
 * P1-6 — import, against OUTLINE P1-6's Verify: import works and
 * `:app:check<Variant>Permissions` still passes.
 *
 * These use real `content://` URIs from MediaStore. A `file://` URI would
 * exercise `openInputStream` while skipping the provider, the temporary grant and
 * the column query — the three things that can actually break.
 */
@RunWith(AndroidJUnit4::class)
class MediaImportTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = ctx.contentResolver
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var db: MetadataDb
    private lateinit var vmk: ByteArray
    private lateinit var dbId: ByteArray
    private val created = mutableListOf<Uri>()
    private val rng = SecureRandom()

    private companion object { const val TAG = "VaultImport" }

    @Before fun setUp() {
        dir = File(ctx.filesDir, "import-test").apply { mkdirs() }
        store = BlobStore(dir)
        db = MetadataDb.empty()
        vmk = VaultKeys.newVmk()
        dbId = ByteArray(16).also { rng.nextBytes(it) }
    }

    @After fun tearDown() {
        created.forEach { runCatching { resolver.delete(it, null, null) } }
        created.clear()
        dir.deleteRecursively()
    }

    private fun importer() = MediaImport(resolver, store, db)

    /** Inserts real bytes into MediaStore and returns the content:// URI. */
    private fun mediaStoreFile(
        name: String,
        bytes: ByteArray,
        dateTakenMillis: Long? = null
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/VaultTest")
            dateTakenMillis?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
        }
        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values
        )!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        created += uri
        return uri
    }

    private fun readBack(item: VaultItem): ByteArray {
        store.reader(item.blobIds, vmk, item.plaintextLength).use { r ->
            val out = ByteArray(item.plaintextLength.toInt())
            val buf = ByteArray(BlobStore.CHUNK)
            var offset = 0
            for (i in 0 until r.chunkCount) {
                val n = r.readChunk(i, buf)
                buf.copyInto(out, offset, 0, n)
                offset += n
            }
            return out
        }
    }

    // --- group 3: the gate -----------------------------------------------------

    /**
     * ***The import path still asks for no media permission, and the one the app now
     * declares is the music player cover's*** — `music-recorder-covers`.
     *
     * The import reads what the photo picker hands it and nothing else; that is what
     * this has always asserted and it is unchanged. What changed is the app around it:
     * `READ_MEDIA_AUDIO` is declared for the cover that reads the device's audio
     * library, so the assertion names the one permission that may appear rather than
     * barring the prefix — and images, video and user-selected visual media stay out,
     * which is the half the import path actually depends on.
     */
    @Test fun theMergedManifestDeclaresNoMediaPermission() {
        val pm = ctx.packageManager
        val declared = pm.getPackageInfo(
            ctx.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS
        ).requestedPermissions.orEmpty().toList()

        Log.i(TAG, "declared permissions: $declared")
        assertTrue(
            "a visual media permission is declared: $declared",
            declared.none {
                it.startsWith("android.permission.READ_MEDIA_") &&
                    it != "android.permission.READ_MEDIA_AUDIO"
            }
        )
        assertTrue(
            "INTERNET is declared: $declared",
            declared.none { it == "android.permission.INTERNET" }
        )
    }

    // --- group 4a: files, not only media — `file-import` ------------------------

    /**
     * Inserts a document into MediaStore's Downloads collection and returns its URI.
     *
     * Not [mediaStoreFile], which inserts into Images and would have MediaStore reject a
     * PDF. The point of these tests is that the pipeline is indifferent to what it is
     * handed, so the source has to be somewhere a document can genuinely live.
     */
    private fun downloadFile(name: String, mime: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VaultTest")
        }
        val uri = resolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values,
        )!!
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        created += uri
        return uri
    }

    /**
     * ***A document imports byte-for-byte and lands on Files.***
     *
     * `file-import`'s headline: until it, the Files destination described documents in its
     * empty state and no control anywhere could put one there. The pipeline needed nothing
     * — this asserts that, so a future change cannot quietly make the import path
     * media-only again.
     */
    @Test fun aDocumentRoundTripsAndLandsOnFiles() {
        val source = ByteArray(7_000).also { rng.nextBytes(it) }
        val uri = downloadFile("vault-test-doc.pdf", "application/pdf", source)

        val result = importer().importAll(listOf(uri), vmk, dbId)

        assertEquals("failures: ${result.failures}", 0, result.failures.size)
        val item = result.imported.single()
        assertArrayEquals(source, readBack(item))
        assertEquals(VaultKind.FILE, item.kind)
        assertEquals(VaultDestination.Files, VaultDestination.of(item.kind))
    }

    /** Several documents in one pick all land; the picker is multi-select. */
    @Test fun severalDocumentsInOneBatchAllLand() {
        val names = listOf("vault-test-a.pdf", "vault-test-b.csv", "vault-test-c.zip")
        val uris = names.map { downloadFile(it, "application/octet-stream", ByteArray(128)) }

        val result = importer().importAll(uris, vmk, dbId)

        assertEquals("failures: ${result.failures}", 0, result.failures.size)
        assertEquals(names.size, result.imported.size)
        assertEquals(names.toSet(), result.imported.map { it.filename }.toSet())
        result.imported.forEach { assertEquals(VaultKind.FILE, it.kind) }
    }

    /**
     * ***A photograph picked from Files is an image, and lands on Media.***
     *
     * D3's deliberate cost. The picker is not narrowed to keep this from happening — a
     * user who wants a `.jpg` kept as a document would then be unable to add one — so the
     * grid says where the batch went instead. `VaultDestinationTest` holds the wording;
     * this holds the fact it is written about.
     */
    @Test fun aPhotographPickedAsAFileIsStillAnImage() {
        val uri = downloadFile("vault-test-photo.jpg", "image/jpeg", ByteArray(256))

        val item = importer().importAll(listOf(uri), vmk, dbId).imported.single()

        assertEquals(VaultKind.IMAGE, item.kind)
        assertEquals(VaultDestination.Media, VaultDestination.of(item.kind))
        assertEquals(
            "Added to Media.",
            landedElsewhere(ctx.words(), listOf(item), VaultDestination.Files),
        )
    }

    /** A folder is passed through for a file exactly as it is for a photograph. */
    @Test fun aDocumentLandsInTheFolderBeingViewed() {
        val folder = ByteArray(16).also { rng.nextBytes(it) }
        val uri = downloadFile("vault-test-infolder.pdf", "application/pdf", ByteArray(64))

        val item = importer()
            .importAll(listOf(uri), vmk, dbId, folderId = folder)
            .imported.single()

        assertArrayEquals(folder, item.folderId)
    }

    // --- group 4: import works -------------------------------------------------

    @Test fun aContentUriRoundTrips() {
        val source = ByteArray(9_000).also { rng.nextBytes(it) }
        val uri = mediaStoreFile("vault-test-round.png", source)

        val result = importer().importAll(listOf(uri), vmk, dbId)

        assertEquals("failures: ${result.failures}", 0, result.failures.size)
        val item = result.imported.single()
        assertArrayEquals(source, readBack(item))
        assertEquals(source.size.toLong(), item.plaintextLength)
    }

    @Test fun theDisplayNameIsRecorded() {
        val uri = mediaStoreFile("vault-test-name.png", ByteArray(64))
        val item = importer().importAll(listOf(uri), vmk, dbId).imported.single()
        assertEquals("vault-test-name.png", item.filename)
    }

    @Test fun aSourceDateIsRecorded() {
        val taken = 1_565_000_000_000L
        val uri = mediaStoreFile("vault-test-dated.png", ByteArray(64), dateTakenMillis = taken)

        val item = importer().importAll(listOf(uri), vmk, dbId).imported.single()
        assertNotNull("no date recorded", item.createdAt)
        Log.i(TAG, "date: set $taken, recorded ${item.createdAt}")
    }

    @Test fun anAbsentDateIsNullNotTheImportTime() {
        // A document URI from our own files dir exposes no date columns at all.
        val plain = File(dir, "no-date.bin").apply { writeBytes(ByteArray(128)) }
        val uri = Uri.fromFile(plain)

        val item = importer().importAll(listOf(uri), vmk, dbId).imported.single()
        assertNull("an absent date was filled in: ${item.createdAt}", item.createdAt)
        assertTrue("importedAt was not set", item.importedAt > 0)
    }

    @Test fun aFileLargerThanOneChunkRoundTrips() {
        val source = ByteArray(BlobStore.CHUNK * 3 + 777).also { rng.nextBytes(it) }
        val uri = mediaStoreFile("vault-test-big.png", source)

        val item = importer().importAll(listOf(uri), vmk, dbId).imported.single()
        assertArrayEquals(source, readBack(item))
    }

    @Test fun aBatchSavesTheIndexOnce() {
        // Counting files cannot tell one save from five — both leave six blobs.
        // Count writes to the index's own blob ID instead.
        val counting = object : BlobStore(dir) {
            var indexWrites = 0
            override fun writeTo(
                blobId: ByteArray,
                input: java.io.InputStream,
                vmk: ByteArray
            ): WrittenBlob {
                if (blobId.contentEquals(dbId)) indexWrites++
                return super.writeTo(blobId, input, vmk)
            }
        }
        val uris = (1..5).map { mediaStoreFile("vault-test-batch-$it.png", ByteArray(200 * it)) }

        MediaImport(resolver, counting, db).importAll(uris, vmk, dbId)

        assertEquals("index written more than once for the batch", 1, counting.indexWrites)
        assertEquals(5, MetadataDb.load(counting, dbId, vmk).items.size)
        Log.i(TAG, "batch of 5: index written ${counting.indexWrites}x")
    }

    @Test fun oneBadUriDoesNotDiscardTheBatch() {
        val good = mediaStoreFile("vault-test-good.png", ByteArray(500))
        val bad = Uri.parse("content://com.app.vault.nonexistent/42")

        val result = importer().importAll(listOf(good, bad), vmk, dbId)

        assertEquals(1, result.imported.size)
        assertEquals(1, result.failures.size)
        assertEquals(bad, result.failures.single().first)
        assertEquals(1, MetadataDb.load(store, dbId, vmk).items.size)
        Log.i(TAG, "batch: 1 imported, 1 failed (${result.failures.single().second})")
    }

    // --- group 5: I3 -----------------------------------------------------------

    @Test fun noPlaintextReachesDiskDuringOrAfterImport() {
        val marker = ByteArray(512).also { rng.nextBytes(it) }
        val source = ByteArray(BlobStore.CHUNK + 4096).also {
            rng.nextBytes(it)
            marker.copyInto(it, 2048)
        }
        val uri = mediaStoreFile("vault-test-i3.png", source)

        val hits = mutableListOf<String>()
        val seen = linkedSetOf<String>()
        val scanning = java.util.concurrent.atomic.AtomicBoolean(true)
        val scanner = Thread {
            while (scanning.get()) scan(marker, hits, seen)
        }.also { it.start() }

        importer().importAll(listOf(uri), vmk, dbId)

        scanning.set(false)
        scanner.join(5_000)
        scan(marker, hits, seen)

        val survivors = dir.listFiles()!!.map { it.name }
        Log.i(
            TAG,
            "I3 scan: ${seen.size} files seen, ${hits.size} plaintext hits — $seen | " +
                "after import: $survivors"
        )
        assertTrue("plaintext found on disk: $hits", hits.isEmpty())
        // The scanner legitimately catches BlobStore's .tmp mid-write. That file
        // is ciphertext — the zero hits above cover it — and the requirement is
        // that no staging file *survives*, not that none is ever observable.
        assertTrue("a staging file survived: $survivors", survivors.none { it.endsWith(".tmp") })
    }

    private fun scan(needle: ByteArray, hits: MutableList<String>, seen: MutableSet<String>) {
        listOf(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir).forEach { root ->
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                seen += f.absolutePath.removePrefix(ctx.dataDir.absolutePath)
                runCatching {
                    if (indexOf(f.readBytes(), needle) >= 0) hits += f.absolutePath
                }
            }
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
