package com.app.vault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * The image editor — `viewer-chrome-and-editor`.
 *
 * Two things are asserted here and they are the two the spec is about.
 *
 * **The operations do what they say**, which needs a real `Bitmap`, `Canvas` and
 * `ColorMatrix` and therefore a device — `ImageEdits` is pure of the vault but not of
 * the framework.
 *
 * **I3 holds through the editor's pipeline.** The bytes are read into memory, decoded,
 * transformed, re-encoded and written back encrypted, and private storage is walked
 * after every step. This drives the same primitives in the same order as
 * `MainActivity.saveEdit` — `ScreenChromeTest` holds the other half of that claim by
 * asserting the editor's source names no file API at all, which is what makes
 * "the same primitives" a fact rather than a hope.
 */
@RunWith(AndroidJUnit4::class)
class EditorTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private lateinit var indexId: ByteArray
    private val rng = SecureRandom()

    private companion object {
        const val PASS = "correcthorsebattery"
        const val QUALITY = 92
    }

    private fun pass() = PASS.toByteArray(Charsets.UTF_8)
    private fun vmk() = VaultSession.vmk()!!

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "editor-${System.nanoTime()}").apply { mkdirs() }
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        vault = VaultLifecycle(File(root, "vault.slots"), store)
        assertTrue("fixture vault must open", vault.create(pass()).opened)
        indexId = VaultSession.metadataBlobId()!!.copyOf()
    }

    @After
    fun tearDown() {
        VaultLifecycle.lockNow()
        root.deleteRecursively()
    }

    // --- fixtures -------------------------------------------------------------

    /** A photograph-shaped JPEG: bands, so a crop is visibly a different picture. */
    private fun jpeg(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(30, 60, 120))
        val paint = Paint()
        for (i in 0 until 8) {
            paint.color = Color.rgb(i * 30, 255 - i * 20, 90)
            canvas.drawRect(i * w / 8f, 0f, (i + 1) * w / 8f, h.toFloat(), paint)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun bitmap(w: Int = 400, h: Int = 200): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.rgb(200, 40, 40))
        }

    private fun privateFiles(): List<File> =
        listOf(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir)
            .flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }

    /** A JPEG's own magic, so "no plaintext image" is a real check and not a guess. */
    private fun looksLikeAJpeg(bytes: ByteArray): Boolean =
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()

    private fun assertNoPlaintextImage(after: String) {
        privateFiles().forEach { file ->
            // Only the fixture's own store lives under `root`; everything else in
            // private storage belongs to the app and is checked the same way.
            val head = file.inputStream().use { s -> ByteArray(minOf(16, file.length().toInt())).also { s.read(it) } }
            assertTrue(
                "a plaintext JPEG reached ${file.absolutePath} after $after",
                !looksLikeAJpeg(head),
            )
        }
    }

    // --- 1. an empty stack is the identity ------------------------------------

    @Test
    fun anEmptyStackReturnsTheSameBitmap() {
        val source = bitmap()

        assertSame(
            "an unedited save must not re-encode a copy of the same picture",
            source,
            ImageEdits.apply(source, ImageEdits.Stack()),
        )
        assertTrue(ImageEdits.Stack().isEmpty)
    }

    // --- 2. the operations do what they say -----------------------------------

    @Test
    fun aCropTakesTheFractionItNames() {
        val source = bitmap(400, 200)

        val half = ImageEdits.apply(
            source,
            ImageEdits.Stack(crop = ImageEdits.Crop(0f, 0f, 0.5f, 1f)),
        )

        assertEquals(200, half.width)
        assertEquals(200, half.height)
    }

    /** A crop dragged past an edge or inverted is repaired, not a defect. */
    @Test
    fun anInvertedCropIsSaned() {
        val saned = ImageEdits.Crop(left = 0.8f, right = 0.2f, top = 1.4f, bottom = -0.3f).sane()

        assertEquals(0.2f, saned.left, 0.001f)
        assertEquals(0.8f, saned.right, 0.001f)
        assertEquals(0f, saned.top, 0.001f)
        assertEquals(1f, saned.bottom, 0.001f)
    }

    /**
     * A straighten insets rather than growing, and leaves no empty corner.
     *
     * **This assertion is the inverse of the one it replaces, and that is the change.**
     * `outputSize` used to return the box that holds the turned rectangle's corners,
     * so straightening by a few degrees produced transparent wedges and the user was
     * directed to the crop tool to remove them by hand. No editor works that way.
     */
    @Test
    fun straighteningLeavesNoEmptyCorners() {
        val flat = ImageEdits.outputSize(400f, 200f, 0f)
        assertEquals(400 to 200, flat)

        val turned = ImageEdits.outputSize(400f, 200f, 10f)
        assertTrue("a straighten must not grow the box: $turned", turned.first < 400)
        assertTrue("nor the height: $turned", turned.second < 200)

        // The claim that matters is about pixels, not about the size. An opaque
        // source straightened must produce an output with no transparent pixel in it.
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
        }
        val straightened = ImageEdits.apply(source, ImageEdits.Stack(straighten = 7f))
        val corners = listOf(
            0 to 0,
            straightened.width - 1 to 0,
            0 to straightened.height - 1,
            straightened.width - 1 to straightened.height - 1,
        )
        corners.forEach { (x, y) ->
            assertTrue(
                "corner ($x, $y) is transparent; the straighten did not inset",
                android.graphics.Color.alpha(straightened.getPixel(x, y)) == 255,
            )
        }
    }

    /**
     * The preview and the save are one construction — `conventional-image-editor`.
     *
     * The editor no longer calls [ImageEdits.apply] to preview; it asks for the matrix
     * and draws with it, which is what stopped a bitmap being allocated on every frame
     * of every slider drag. That is only safe while both paths build the picture from
     * the same two functions, and this is the assertion that they do. If someone adds
     * a "fast preview" path later, this fails.
     */
    @Test
    fun thePreviewAndTheSaveBuildTheSamePicture() {
        val source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        for (x in 0 until 120) {
            for (y in 0 until 80) {
                source.setPixel(x, y, android.graphics.Color.rgb(x * 2, y * 3, 40))
            }
        }
        val stack = ImageEdits.Stack(
            crop = ImageEdits.Crop(0.1f, 0.1f, 0.9f, 0.8f),
            straighten = 4f,
            quarterTurns = 1,
            flipHorizontal = true,
            filter = ImageEdits.Filter.Warm,
            filterIntensity = 0.6f,
            brightness = 0.2f,
        )

        val saved = ImageEdits.apply(source, stack)

        // What the preview does, at the same scale so the pixels are comparable.
        val plan = ImageEdits.geometry(source.width, source.height, stack)
        val drawn = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        ImageEdits.drawOnto(android.graphics.Canvas(drawn), source, stack, plan.matrix)

        assertEquals(saved.width, drawn.width)
        assertEquals(saved.height, drawn.height)
        var differing = 0
        for (x in 0 until saved.width step 3) {
            for (y in 0 until saved.height step 3) {
                if (saved.getPixel(x, y) != drawn.getPixel(x, y)) differing++
            }
        }
        assertEquals("the preview and the save disagree about $differing pixels", 0, differing)
    }

    /** A filter's strength blends towards the unfiltered picture, reaching it at zero. */
    @Test
    fun aFilterAtZeroStrengthIsTheUnfilteredPicture() {
        val full = ImageEdits.colourMatrix(ImageEdits.Stack(filter = ImageEdits.Filter.Mono))
        val none = ImageEdits.colourMatrix(
            ImageEdits.Stack(filter = ImageEdits.Filter.Mono, filterIntensity = 0f)
        )
        assertTrue("a filter at full strength tints", full != null)
        assertEquals("a filter at no strength does not", null, none)

        val half = ImageEdits.colourMatrix(
            ImageEdits.Stack(filter = ImageEdits.Filter.Mono, filterIntensity = 0.5f)
        )
        assertTrue("and half strength is neither", half != null)
    }

    @Test
    fun aFilterAndTheSlidersBuildOneMatrix() {
        assertEquals(null, ImageEdits.colourMatrix(ImageEdits.Stack()))
        assertTrue(ImageEdits.colourMatrix(ImageEdits.Stack(brightness = 0.4f)) != null)
        assertTrue(ImageEdits.colourMatrix(ImageEdits.Stack(filter = ImageEdits.Filter.Mono)) != null)
        // A filter is a position on the same matrix, so the two compose rather than
        // one replacing the other.
        assertTrue(
            ImageEdits.colourMatrix(
                ImageEdits.Stack(filter = ImageEdits.Filter.Warm, saturation = -0.5f)
            ) != null
        )
    }

    @Test
    fun markupChangesThePicture() {
        val source = bitmap(200, 200)
        val marked = ImageEdits.apply(
            source,
            ImageEdits.Stack(
                strokes = listOf(
                    ImageEdits.Stroke(
                        colour = Color.WHITE,
                        width = 0.1f,
                        points = listOf(0.1f to 0.1f, 0.9f to 0.9f),
                    )
                )
            ),
        )

        assertNotEquals("a stroke must reach the pixels", source, marked)
        assertEquals(Color.WHITE, marked.getPixel(100, 100))
    }

    // --- 3. I3 through the whole pipeline -------------------------------------

    /**
     * Read, decode, transform, encode, store — and nothing plaintext on disk at any
     * point along the way.
     *
     * The steps are `MainActivity.saveEdit`'s, in its order. The assertion runs after
     * each one rather than only at the end, because "no artifact afterwards" is a
     * weaker claim than "no artifact ever": a temporary that is written and deleted
     * has still existed.
     */
    @Test
    fun theEditorsPipelineWritesNoPlaintextAtAnyStep() {
        val source = jpeg(1_200, 800)
        val written = store.write(ByteArrayInputStream(source), vmk())
        assertNoPlaintextImage("the fixture was stored")

        val item = VaultItem(
            blobIds = listOf(written.blobId),
            plaintextLength = written.plaintextLength,
            thumb = null,
            filename = "photo.jpg",
            album = null,
            createdAt = 1_600_000_000_000L,
            importedAt = 1_700_000_000_000L,
            tags = emptyList(),
        )

        val decoded = store.reader(item.blobIds, vmk(), item.plaintextLength).use { r ->
            val bytes = r.readRange(0, item.plaintextLength.toInt())
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        assertNoPlaintextImage("the item was decoded")

        val stack = ImageEdits.Stack(
            crop = ImageEdits.Crop(0.1f, 0.1f, 0.9f, 0.9f),
            straighten = 3f,
            filter = ImageEdits.Filter.Warm,
            brightness = 0.2f,
            saturation = -0.3f,
            strokes = listOf(
                ImageEdits.Stroke(points = listOf(0.2f to 0.2f, 0.8f to 0.6f))
            ),
        )
        val edited = ImageEdits.apply(decoded, stack)
        assertNoPlaintextImage("the stack was applied")

        val jpeg = ByteArrayOutputStream()
            .also { edited.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            .toByteArray()
        assertTrue("the encode must produce a JPEG", looksLikeAJpeg(jpeg))
        assertNoPlaintextImage("the result was encoded")

        val claimed = mutableListOf<ByteArray>()
        val copy = store.writeItem(ByteArrayInputStream(jpeg), vmk(), claimed)
        jpeg.fill(0)
        assertNoPlaintextImage("the copy was stored")

        assertTrue("the copy must hold bytes", copy.plaintextLength > 0)
    }

    /**
     * An abandoned edit leaves nothing behind.
     *
     * The editor holds its stack in composition and its bitmaps in memory; there is
     * no draft, no autosave and no cache entry, which is what makes a lock or a
     * process death safe rather than merely tidy. **A real SIGKILL cannot be raised
     * from inside the process under test**, so what is asserted is the property that
     * makes death survivable: at no point does the edit exist anywhere but in memory.
     */
    @Test
    fun anAbandonedEditLeavesNothingOnDisk() {
        val source = jpeg(800, 600)
        val written = store.write(ByteArrayInputStream(source), vmk())
        val item = VaultItem(
            blobIds = listOf(written.blobId),
            plaintextLength = written.plaintextLength,
            thumb = null,
            filename = "photo.jpg",
            album = null,
            createdAt = null,
            importedAt = 1L,
            tags = emptyList(),
        )
        MetadataDb.commit(store, indexId, vmk()) { db -> db.items += item; db }

        // Everything an editing session does, up to the moment before a save.
        val decoded = store.reader(item.blobIds, vmk(), item.plaintextLength).use { r ->
            val bytes = r.readRange(0, item.plaintextLength.toInt())
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        ImageEdits.apply(decoded, ImageEdits.Stack(brightness = 0.5f))
        ImageEdits.apply(decoded, ImageEdits.Stack(crop = ImageEdits.Crop(0.2f, 0.2f, 0.8f, 0.8f)))

        // The lock, which is where an editor with a draft would have written one.
        VaultLifecycle.lockNow()
        assertNoPlaintextImage("the vault locked with an edit in flight")

        assertTrue("the fixture vault must reopen", vault.unlockAndOpen(pass()))
        val back = MetadataDb.load(store, indexId, vmk()).items.single()
        assertEquals("nothing may have been committed", 1, MetadataDb.load(store, indexId, vmk()).items.size)
        assertArrayEquals(item.blobIds.single(), back.blobIds.single())
    }

    /** The original reads back byte-identical after a copy has been saved beside it. */
    @Test
    fun savingACopyLeavesTheOriginalUntouched() {
        val source = jpeg(600, 400)
        val written = store.write(ByteArrayInputStream(source), vmk())
        val original = VaultItem(
            blobIds = listOf(written.blobId),
            plaintextLength = written.plaintextLength,
            thumb = null,
            filename = "photo.jpg",
            album = null,
            createdAt = 1_600_000_000_000L,
            importedAt = 1_700_000_000_000L,
            tags = emptyList(),
            folderId = ByteArray(16) { 0x51 },
        )
        MetadataDb.commit(store, indexId, vmk()) { db -> db.items += original; db }

        val decoded = store.reader(original.blobIds, vmk(), original.plaintextLength).use { r ->
            val bytes = r.readRange(0, original.plaintextLength.toInt())
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        val edited = ImageEdits.apply(decoded, ImageEdits.Stack(filter = ImageEdits.Filter.Mono))
        val bytes = ByteArrayOutputStream()
            .also { edited.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            .toByteArray()
        val copyWritten = store.writeItem(ByteArrayInputStream(bytes), vmk(), mutableListOf())

        MetadataDb.commit(store, indexId, vmk()) { db ->
            val name = VaultCamera.uniqueFilename(
                db.items.mapTo(HashSet()) { it.filename },
                original.filename,
            )
            db.items += VaultItem(
                blobIds = copyWritten.blobIds,
                plaintextLength = copyWritten.plaintextLength,
                thumb = null,
                filename = name,
                album = null,
                createdAt = original.createdAt,
                importedAt = System.currentTimeMillis(),
                tags = emptyList(),
                folderId = original.folderId,
            )
            db
        }

        val db = MetadataDb.load(store, indexId, vmk())
        assertEquals("the copy is beside the original, not in place of it", 2, db.items.size)

        val back = db.items.first { it.filename == "photo.jpg" }
        val readBack = store.reader(back.blobIds, vmk(), back.plaintextLength).use { r ->
            r.readRange(0, back.plaintextLength.toInt())
        }
        assertArrayEquals("the original's bytes must be untouched", source, readBack)

        val copy = db.items.first { it.filename != "photo.jpg" }
        assertNotEquals("the copy must not collide with the original's name", "photo.jpg", copy.filename)
        assertEquals("the copy keeps the capture date — it is the same photograph",
            original.createdAt, copy.createdAt)
        assertArrayEquals("and lands in the same folder", original.folderId, copy.folderId)
        assertTrue("and records its own arrival", copy.importedAt > original.importedAt)
    }
}
