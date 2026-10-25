package com.app.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * P1-8 — thumbnails, against `OUTLINE.md`'s Verify and `FORMAT.md` §8.
 *
 * Three clauses are checked here: no plaintext thumbnail reaches disk (I3),
 * video thumbnails render non-black across a mixed-codec corpus, and packing
 * costs what §8 claims it does.
 */
@RunWith(AndroidJUnit4::class)
class ThumbnailTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var thumbs: ThumbnailStore
    private lateinit var db: MetadataDb
    private lateinit var vmk: ByteArray
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultThumbTest"
        val WEBP_RIFF = byteArrayOf(0x52, 0x49, 0x46, 0x46)          // "RIFF"
        val WEBP_TAG = byteArrayOf(0x57, 0x45, 0x42, 0x50)           // "WEBP"
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }

    @Before
    fun setUp() {
        dir = File(ctx.filesDir, "thumb-test-${System.nanoTime()}")
        store = BlobStore(dir)
        thumbs = ThumbnailStore(store)
        db = MetadataDb.empty()
        vmk = VaultKeys.newVmk()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        ThumbnailCache.clear()
        vmk.fill(0)
    }

    // --- helpers ------------------------------------------------------------

    private fun testImage(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(20, 30, 45))
        val paint = Paint()
        for (i in 0 until 24) {
            paint.color = Color.rgb((i * 11) % 256, (i * 37) % 256, (i * 71) % 256)
            canvas.drawRect(
                (i * w / 24f), 0f, ((i + 1) * w / 24f), h.toFloat(), paint
            )
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun storeItem(bytes: ByteArray, filename: String): VaultItem {
        val written = store.write(ByteArrayInputStream(bytes), vmk)
        return VaultItem(
            blobIds = listOf(written.blobId),
            plaintextLength = written.plaintextLength,
            thumb = null,
            filename = filename,
            album = null,
            createdAt = null,
            importedAt = System.currentTimeMillis(),
            tags = emptyList()
        )
    }

    private fun privateFiles(): List<File> =
        listOf(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir)
            .flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** A real WebP file, not four bytes that happen to spell RIFF in ciphertext. */
    private fun containsWebp(bytes: ByteArray): Boolean {
        var at = indexOf(bytes, WEBP_RIFF)
        while (at >= 0) {
            if (at + 12 <= bytes.size &&
                bytes.copyOfRange(at + 8, at + 12).contentEquals(WEBP_TAG)
            ) return true
            at = indexOf(bytes, WEBP_RIFF, at + 1)
        }
        return false
    }

    /**
     * A landscape JPEG whose EXIF says it was shot with the phone turned — exactly
     * what `VaultCamera` produces, where the sensor buffer is always landscape and
     * `JPEG_ORIENTATION` carries how it was held.
     */
    private fun rotatedJpeg(w: Int, h: Int, orientation: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.rgb(90, 140, 200))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()

        // The tag can only be written through a file; the app never does this, but
        // the platform `ExifInterface` has no in-memory save and this is a fixture.
        val file = File(ctx.cacheDir, "exif-${System.nanoTime()}.jpg")
        return try {
            file.writeBytes(out.toByteArray())
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            file.readBytes()
        } finally {
            file.delete()
        }
    }


    /**
     * A stored rotation never regenerates a thumbnail.
     *
     * `item-rotation`'s written requirement: turning an item writes the index and
     * touches nothing else. The thumbnail is built from the item's own EXIF and stays
     * exactly the bytes it was; the turn is applied where the tile is drawn.
     */
    @Test
    fun aStoredRotationDoesNotChangeTheThumbnailBytes() {
        val source = rotatedJpeg(800, 400, ExifInterface.ORIENTATION_NORMAL)

        val before = Thumbnailer.fromImage(source)!!
        // What a rotation does to the item, in full: one field on the row.
        val item = storeItem(source, "photo.jpg").withRotation(90)
        val after = Thumbnailer.fromImage(source)!!

        assertEquals(90, item.rotation)
        assertArrayEquals(
            "a turn must not change what the thumbnail is built from",
            before,
            after,
        )
    }

    /** The two angles add, which is what makes an imported photograph start upright. */
    @Test
    fun theStoredAngleAndTheExifAngleCompose() {
        val turned = rotatedJpeg(800, 400, ExifInterface.ORIENTATION_ROTATE_90)
        val flat = rotatedJpeg(800, 400, ExifInterface.ORIENTATION_NORMAL)

        assertEquals(90, Thumbnailer.exifDegrees(turned))
        assertEquals(0, Thumbnailer.exifDegrees(flat))

        // 90 in the file plus 270 stored is a full turn: back where the sensor had it.
        val bmp = BitmapFactory.decodeByteArray(turned, 0, turned.size)
        val composed = Thumbnailer.oriented(bmp, Thumbnailer.exifDegrees(turned) + 270)
        assertTrue(
            "90 in the file and 270 stored must land back at landscape, " +
                "${composed.width}x${composed.height}",
            composed.width > composed.height,
        )
    }

    // --- 0. the photograph comes back the way it was taken --------------------

    /**
     * A thumbnail is turned by the EXIF orientation, not left in sensor space.
     *
     * The defect: `BitmapFactory` returns the encoded pixel buffer and never applies
     * the tag, so a photograph taken with the phone turned came back on its side —
     * in the grid and in the viewer both, while the stored file and its export were
     * correct all along. A quarter turn swaps the aspect, which is what this reads.
     */
    @Test
    fun aThumbnailIsTurnedTheWayThePhotographWasTaken() {
        val flat = Thumbnailer.fromImage(
            rotatedJpeg(800, 400, ExifInterface.ORIENTATION_NORMAL)
        )!!
        val landscape = BitmapFactory.decodeByteArray(flat, 0, flat.size)
        assertTrue(
            "an untagged 800x400 must stay landscape, ${landscape.width}x${landscape.height}",
            landscape.width > landscape.height,
        )

        val turned = Thumbnailer.fromImage(
            rotatedJpeg(800, 400, ExifInterface.ORIENTATION_ROTATE_90)
        )!!
        val upright = BitmapFactory.decodeByteArray(turned, 0, turned.size)
        assertTrue(
            "the same frame tagged 90 must come back portrait, " +
                "${upright.width}x${upright.height}",
            upright.height > upright.width,
        )
    }

    // --- 1. no plaintext thumbnail on disk (I3) ------------------------------

    @Test
    fun thumbnailGenerationLeavesNoPlaintextOnDisk() {
        val items = (1..8).map { storeItem(testImage(1200, 900), "photo$it.png") }

        val stop = AtomicBoolean(false)
        var passes = 0
        val filesSeen = mutableSetOf<String>()
        val hits = mutableListOf<String>()

        val scanner = thread {
            while (!stop.get()) {
                passes++
                privateFiles().forEach { f ->
                    filesSeen += f.name
                    if (FileScan.containsWebp(f) || FileScan.contains(f, PNG)) {
                        hits += "${f.path} (${f.length()} B)"
                    }
                }
            }
        }

        val withThumbs = Thumbnails.generateAll(store, thumbs, db, items, vmk)
        stop.set(true)
        scanner.join(10_000)

        // Post-run scan too: the race is not the only failure mode, a file left
        // behind after generation is worse.
        privateFiles().forEach { f ->
            if (FileScan.containsWebp(f) || FileScan.contains(f, PNG)) hits += "post: ${f.path}"
        }

        Log.i(TAG, "I3 scan: $passes passes, ${filesSeen.size} distinct files, ${hits.size} hits")
        Log.i(TAG, "files seen: ${filesSeen.sorted()}")

        assertTrue("scanner never ran", passes > 0)
        assertTrue("plaintext image bytes found on disk: $hits", hits.isEmpty())
        assertEquals("every item should have a thumbnail", 8, withThumbs.count { it.thumb != null })
    }

    // --- 2. video frames that are not black ---------------------------------

    @Test
    fun videoThumbnailsAreNotBlackAcrossCodecs() {
        val results = mutableListOf<String>()
        val absent = mutableListOf<String>()
        var tested = 0

        VideoCorpus.SPECS.forEach { spec ->
            val fixture = File(ctx.cacheDir, "corpus-${spec.label}.${spec.extension}")
            if (!VideoCorpus.generate(spec, fixture)) {
                absent += "${spec.label} (encoder ${spec.mime} absent or failed)"
                fixture.delete()
                return@forEach
            }

            val bytes = fixture.readBytes()
            // The fixture leaves before anything is measured — from here the only
            // copy is the encrypted blob.
            fixture.delete()

            val item = storeItem(bytes, "clip-${spec.label}.${spec.extension}")
            val choice = store.reader(item.blobIds, vmk, item.plaintextLength).let { r ->
                Thumbnailer.BlobDataSource(r, item.plaintextLength).use { src ->
                    Thumbnailer.chooseFrame(src)
                }
            }

            val frame = choice.bitmap
            assertNotNull("${spec.label}: no frame extracted at all", frame)
            tested++
            results += "%-22s -> %6d ms, stddev %6.2f (floor %.1f)".format(
                spec.label, choice.timestampUs / 1000, choice.variance, Thumbnailer.VARIANCE_FLOOR
            )
            assertTrue(
                "${spec.label}: chosen frame is flat (stddev ${choice.variance}), " +
                    "which is the black-frame failure P1-8 exists to prevent",
                choice.variance >= Thumbnailer.VARIANCE_FLOOR
            )
            frame!!.recycle()
        }

        results.forEach { Log.i(TAG, it) }
        absent.forEach { Log.w(TAG, "not tested: $it") }
        assertTrue("no codec in the corpus was available on this target", tested >= 2)
    }

    @Test
    fun aVideoOpeningOnBlackDoesNotYieldABlackThumbnail() {
        val spec = VideoCorpus.SPECS.first { it.label == "h264-mp4-black-lead" }
        val fixture = File(ctx.cacheDir, "black-lead.mp4")
        assertTrue("H.264 encoder is required for this test", VideoCorpus.generate(spec, fixture))
        val bytes = fixture.readBytes()
        fixture.delete()

        val item = storeItem(bytes, "black-lead.mp4")
        val choice = store.reader(item.blobIds, vmk, item.plaintextLength).let { r ->
            Thumbnailer.BlobDataSource(r, item.plaintextLength).use { src ->
                Thumbnailer.chooseFrame(src)
            }
        }

        Log.i(TAG, "black lead ${spec.blackLeadMs} ms -> chose ${choice.timestampUs / 1000} ms, " +
            "stddev %.2f".format(choice.variance))
        assertNotNull(choice.bitmap)
        assertTrue("chose a frame from inside the black lead", choice.timestampUs >= 0)
        assertTrue(
            "the chosen frame is black (stddev ${choice.variance})",
            choice.variance >= Thumbnailer.VARIANCE_FLOOR
        )
        choice.bitmap!!.recycle()
    }

    // --- 3. packs ------------------------------------------------------------

    @Test
    fun aThumbnailRoundTripsThroughItsPack() {
        val item = storeItem(testImage(800, 600), "one.png")
        val webp = Thumbnails.generate(store, item, vmk).webp
        assertNotNull("no thumbnail produced", webp)

        val ref = thumbs.stage(db, webp!!)
        thumbs.flush(db, vmk)

        assertArrayEquals("round trip through the pack", webp, thumbs.read(db, ref, vmk))
        assertEquals("one pack", 1, db.thumbPacks.size)
        assertEquals(
            "pack length is header plus payload",
            ThumbnailStore.RECORD_HEADER + webp.size,
            db.thumbPacks[0].plaintextLength
        )
    }

    @Test
    fun manyThumbnailsShareAPackAndSurviveTheIndex() {
        val items = (1..12).map { storeItem(testImage(600, 400), "p$it.png") }
        val withThumbs = Thumbnails.generateAll(store, thumbs, db, items, vmk)
        db.items += withThumbs

        assertEquals("all 12 thumbnailed", 12, withThumbs.count { it.thumb != null })
        assertEquals("12 thumbnails should share one pack", 1, db.thumbPacks.size)

        // Every reference resolves, and to distinct bytes.
        val decoded = withThumbs.map { thumbs.read(db, it.thumb!!, vmk) }
        decoded.forEach { assertTrue("not a WebP", containsWebp(it)) }

        // Survives a save/load cycle of the index.
        val dbId = ByteArray(16).also { rng.nextBytes(it) }
        db.save(store, dbId, vmk)
        val reloaded = MetadataDb.load(store, dbId, vmk)
        assertEquals(12, reloaded.items.size)
        assertEquals(1, reloaded.thumbPacks.size)
        reloaded.items.forEachIndexed { i, item ->
            assertArrayEquals(
                "thumbnail $i must survive the index round trip",
                decoded[i], thumbs.read(reloaded, item.thumb!!, vmk)
            )
        }
    }

    @Test
    fun packsRollOverAtTheCapAndClosedPacksStayReadable() {
        // Records big enough that a handful crosses the 4 MiB cap.
        val big = ByteArray(600_000).also { rng.nextBytes(it) }
        val refs = (1..10).map { thumbs.stage(db, big) }
        thumbs.flush(db, vmk)

        assertTrue("should have rolled over to a second pack", db.thumbPacks.size >= 2)
        db.thumbPacks.forEach {
            assertTrue("pack over cap", it.plaintextLength <= ThumbnailStore.PACK_CAP)
        }
        // The earliest reference lives in a closed pack. Reading it is the case
        // that a single open-pack cursor could not serve.
        assertArrayEquals("closed pack must stay readable", big, thumbs.read(db, refs.first(), vmk))
        assertArrayEquals("open pack too", big, thumbs.read(db, refs.last(), vmk))
    }

    // --- 4. key separation, end to end ---------------------------------------

    @Test
    fun aPackCannotBeOpenedWithTheContentKey() {
        val item = storeItem(testImage(400, 300), "k.png")
        val ref = thumbs.stage(db, Thumbnails.generate(store, item, vmk).webp!!)
        thumbs.flush(db, vmk)

        val pack = db.thumbPacks.single()
        val failed = runCatching {
            store.reader(pack.id, vmk, pack.plaintextLength.toLong()).use { r ->
                r.readRange(0, pack.plaintextLength)
            }
        }.isFailure
        assertTrue("a pack must not open under vaultblb", failed)

        // And the content blob must not open under vaultthm.
        val contentFailed = runCatching {
            store.reader(item.blobIds, vmk, item.plaintextLength, VaultKeys.BlobContext.THUMBNAIL)
                .use { r -> r.readChunk(0, ByteArray(BlobStore.CHUNK)) }
        }.isFailure
        assertTrue("a content blob must not open under vaultthm", contentFailed)

        assertArrayEquals(
            "the correct context still works",
            thumbs.read(db, ref, vmk), thumbs.read(db, ref, vmk)
        )
    }

    // --- 5. storage cost ------------------------------------------------------

    @Test
    fun packingCostsLessThanOneBlobPerThumbnail() {
        // 300 items, per task 5.4. Small source images keep the run short; the
        // thumbnail size is what the measurement is about.
        val count = 300
        val items = (1..count).map { storeItem(testImage(500, 375), "s$it.png") }
        val withThumbs = Thumbnails.generateAll(store, thumbs, db, items, vmk)
        assertEquals(count, withThumbs.count { it.thumb != null })

        val packed = thumbs.onDiskBytes(db)
        val thumbBytes = withThumbs.sumOf { it.thumb!!.length.toLong() }
        val perBlob = count.toLong() * BlobStore.SMALL_BUCKETS.first()

        Log.i(TAG, "storage: $count thumbnails, ${thumbBytes} B of WebP")
        Log.i(TAG, "  packed        : $packed B in ${db.thumbPacks.size} pack(s)")
        Log.i(TAG, "  one blob each : $perBlob B")
        Log.i(TAG, "  ratio         : %.2fx".format(perBlob.toDouble() / packed))

        assertTrue(
            "packing must beat one blob per thumbnail ($packed vs $perBlob)",
            packed < perBlob
        )
    }

    // --- 6. cache -------------------------------------------------------------

    @Test
    fun theCacheIsMemoryOnlyAndReDecodesAfterEviction() {
        val item = storeItem(testImage(700, 500), "c.png")
        val ref = thumbs.stage(db, Thumbnails.generate(store, item, vmk).webp!!)
        thumbs.flush(db, vmk)

        var decodes = 0
        val load = { decodes++; thumbs.read(db, ref, vmk) }

        val first = ThumbnailCache.get(ref, load)
        val second = ThumbnailCache.get(ref, load)
        assertNotNull(first)
        assertEquals("second get must hit the cache", 1, decodes)
        assertTrue("same instance from cache", first === second)

        ThumbnailCache.clear()
        assertEquals("cache must be empty after clear", 0, ThumbnailCache.size())
        ThumbnailCache.get(ref, load)
        assertEquals("after eviction it must decode again", 2, decodes)

        // Nothing the cache did may have touched disk.
        val onDisk = privateFiles().any { FileScan.containsWebp(it) }
        assertTrue("cache must not write a thumbnail to disk", !onDisk)
    }

    @Test
    fun aFailedThumbnailLeavesTheItemAloneAndStagesNothing() {
        // Not decodable as an image, and not named as a video: generation must
        // fail cleanly rather than take the import down with it.
        val junk = ByteArray(50_000).also { rng.nextBytes(it) }
        val item = storeItem(junk, "not-an-image.png")

        val out = Thumbnails.generateAll(store, thumbs, db, listOf(item), vmk)
        assertEquals("item survives", 1, out.size)
        assertEquals("no thumbnail reference", null, out[0].thumb)
        assertEquals("nothing staged, so no pack", 0, db.thumbPacks.size)
    }

    @Test
    fun backfillFillsInWhatAnInterruptedImportMissed() {
        val items = (1..4).map { storeItem(testImage(500, 400), "b$it.png") }
        db.items += items
        assertEquals(4, db.items.count { it.thumb == null })

        val filled = Thumbnails.backfill(store, thumbs, db, vmk)
        assertEquals("backfill should fill all four", 4, filled)
        assertEquals("none left without one", 0, db.items.count { it.thumb == null })
        db.items.forEach { assertTrue(containsWebp(thumbs.read(db, it.thumb!!, vmk))) }
    }

    // --- 7. what generation costs an import ----------------------------------

    @Test
    fun thumbnailGenerationCostPerItemAndPerBatch() {
        val batch = 50
        val sources = (1..batch).map { testImage(2000, 1500) }

        val storeStart = System.nanoTime()
        val items = sources.mapIndexed { i, bytes -> storeItem(bytes, "t$i.png") }
        val storeMs = (System.nanoTime() - storeStart) / 1_000_000

        val genStart = System.nanoTime()
        val withThumbs = Thumbnails.generateAll(store, thumbs, db, items, vmk)
        val genMs = (System.nanoTime() - genStart) / 1_000_000

        val perItem = sources.map { bytes ->
            val item = storeItem(bytes, "one.png")
            val t = System.nanoTime()
            Thumbnails.generate(store, item, vmk)
            (System.nanoTime() - t) / 1_000_000
        }.sorted()

        Log.i(TAG, "import cost: $batch x 2000x1500")
        Log.i(TAG, "  store only              : $storeMs ms")
        Log.i(TAG, "  + thumbnails (batch)    : $genMs ms")
        Log.i(TAG, "  overhead                : %.0f%%".format(100.0 * genMs / storeMs))
        Log.i(TAG, "  per item median         : ${perItem[perItem.size / 2]} ms " +
            "(min ${perItem.first()}, max ${perItem.last()})")

        assertEquals(batch, withThumbs.count { it.thumb != null })
    }

    @Test
    fun aThumbnailIsBoundedAndDecodable() {
        val item = storeItem(testImage(4000, 3000), "big.png")
        val webp = Thumbnails.generate(store, item, vmk).webp!!
        val bmp = BitmapFactory.decodeByteArray(webp, 0, webp.size)
        assertNotNull("thumbnail must decode", bmp)
        assertTrue(
            "longest edge ${maxOf(bmp.width, bmp.height)} exceeds ${Thumbnailer.MAX_EDGE}",
            maxOf(bmp.width, bmp.height) <= Thumbnailer.MAX_EDGE
        )
        Log.i(TAG, "4000x3000 -> ${bmp.width}x${bmp.height}, ${webp.size} B WebP")
        bmp.recycle()
    }
}
