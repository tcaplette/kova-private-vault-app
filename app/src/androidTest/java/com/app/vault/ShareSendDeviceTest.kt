package com.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

/**
 * The send path against a real store — P4-2.
 *
 * What is device-only here: libsodium, a `BlobStore` with real files, and the
 * claim that no plaintext copy exists anywhere under `filesDir` while a seal is
 * running. The last one cannot be asserted from the JVM at all, and it is the one
 * I3 turns on.
 */
@RunWith(AndroidJUnit4::class)
class ShareSendDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray

    private val code = listOf("abandon", "ability", "able", "about")

    @Before fun setUp() {
        dir = File(ctx.filesDir, "share-send-test").apply { mkdirs() }
        store = BlobStore(dir)
        vmk = VaultKeys.newVmk()
        ShareSend.sweep(ctx)
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        ShareSend.sweep(ctx)
        vmk.fill(0)
    }

    private fun content(size: Int): ByteArray =
        ByteArray(size).also { Random(11).nextBytes(it) }

    private fun store(bytes: ByteArray, name: String, durationMs: Long? = null): VaultItem {
        val blob = store.write(ByteArrayInputStream(bytes), vmk)
        return VaultItem(
            blobIds = listOf(blob.blobId),
            plaintextLength = blob.plaintextLength,
            thumb = null,
            filename = name,
            album = null,
            createdAt = 0,
            importedAt = 0,
            tags = emptyList(),
            durationMs = durationMs,
        )
    }

    // ── Round trip ────────────────────────────────────────────────────────────

    @Test
    fun aSealedItemOpensWithItsCodeAndIsIdentical() {
        val plain = content(200_000)
        val item = store(plain, "holiday.jpg")

        val file = ShareSend.seal(
            ctx, store, item, vmk, ShareContainer.Policy.Unmanaged, code
        )

        val out = ByteArrayOutputStream()
        val opened = file.inputStream().use { ShareContainer.openWithCode(it, out, code) }

        assertArrayEquals(plain, out.toByteArray())
        assertEquals(ShareContainer.Kind.IMAGE, opened.kind)
        assertEquals("holiday.jpg", opened.displayName)
        assertEquals("image/jpeg", opened.mime)
    }

    /**
     * **Save to device writes the container itself** — `FORMAT.md` §10 item 51. The
     * destination here is a file URI in the cache, standing in for the document the
     * system's save dialog returns; `saveTo` takes either through the content resolver.
     */
    @Test
    fun aSavedContainerIsTheSealedFileByteForByteAndOpens() {
        val plain = content(90_000)
        val sealed = ShareSend.seal(ctx, store, store(plain, "harbour.jpg"), vmk, ShareContainer.Policy.Unmanaged, code)
        val saved = File(ctx.cacheDir, "saved-share.bin").also { it.delete() }
        try {
            ShareSend.saveTo(ctx, sealed, android.net.Uri.fromFile(saved))
            assertArrayEquals("ciphertext out, unchanged", sealed.readBytes(), saved.readBytes())
            val out = ByteArrayOutputStream()
            saved.inputStream().use { ShareContainer.openWithCode(it, out, code) }
            assertArrayEquals(plain, out.toByteArray())
        } finally {
            saved.delete()
        }
    }

    /**
     * ***A document seals and opens like anything else.*** The file kind was left
     * out of §12's prelude when the feature was built, so a PDF in the vault had no
     * share action at all. Nothing on the send path needed teaching for it — the
     * seal streams a blob whatever is in it — and this is what says so, end to end
     * and through the real MIME lookup rather than a hand-written prelude.
     */
    @Test
    fun aDocumentSealsAndOpensLikeAnythingElse() {
        val plain = content(50_000)
        val item = store(plain, "contract.pdf")

        val file = ShareSend.seal(
            ctx, store, item, vmk, ShareContainer.Policy.Unmanaged, code
        )

        val out = ByteArrayOutputStream()
        val opened = file.inputStream().use { ShareContainer.openWithCode(it, out, code) }

        assertArrayEquals(plain, out.toByteArray())
        assertEquals(ShareContainer.Kind.FILE, opened.kind)
        assertEquals("contract.pdf", opened.displayName)
        assertEquals("application/pdf", opened.mime)
    }

    @Test
    fun theChosenPolicyReachesTheContainer() {
        val item = store(content(5_000), "clip.mp4", durationMs = 10_000)
        val at = System.currentTimeMillis() + 86_400_000L

        val file = ShareSend.seal(
            ctx, store, item, vmk, ShareContainer.Policy.Expiry(at), code
        )
        val opened = file.inputStream().use {
            ShareContainer.openWithCode(it, ByteArrayOutputStream(), code)
        }
        assertEquals(at, (opened.policy as ShareContainer.Policy.Expiry).atEpochMillis)
    }

    @Test
    fun progressReachesTheTotal() {
        val item = store(content(200_000), "holiday.jpg")
        var last = 0L to 0L
        ShareSend.seal(ctx, store, item, vmk, ShareContainer.Policy.Unmanaged, code) { d, t ->
            last = d to t
        }
        assertEquals(item.plaintextLength, last.first)
        assertEquals(item.plaintextLength, last.second)
    }

    // ── I3 ────────────────────────────────────────────────────────────────────

    @Test
    fun noPlaintextCopyExistsUnderFilesDir() {
        // A distinctive marker that could only appear in a decrypted copy. The
        // sweep across every file under `filesDir` is the point: a staged
        // plaintext anywhere — cache, temp, the share directory itself — fails.
        val marker = "PLAINTEXT-MARKER-7f3a9c".toByteArray()
        // An image, deliberately: a `.txt` with no body is `VaultKind.FILE`, which
        // is correctly not shareable, and would fail this test for the wrong reason.
        val plain = marker + content(80_000)
        val item = store(plain, "marked.jpg")

        var foundDuringSeal = false
        ShareSend.seal(ctx, store, item, vmk, ShareContainer.Policy.Unmanaged, code) { _, _ ->
            if (!foundDuringSeal) foundDuringSeal = anyFileContains(ctx.filesDir, marker)
        }

        assertFalse("a plaintext copy existed while sealing", foundDuringSeal)
        assertFalse("a plaintext copy survived the seal", anyFileContains(ctx.filesDir, marker))
        assertFalse("a plaintext copy is in the cache", anyFileContains(ctx.cacheDir, marker))
    }

    // ── What the photograph carries ───────────────────────────────────────────

    /** A JPEG carrying location and camera identity, written by the platform. */
    private fun jpegWithGps(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(
            16, 16, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val raw = java.io.ByteArrayOutputStream().also {
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        val tmp = File(ctx.cacheDir, "share-exif-${System.nanoTime()}.jpg")
        tmp.writeBytes(raw)
        android.media.ExifInterface(tmp.absolutePath).apply {
            setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,0/1")
            setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(android.media.ExifInterface.TAG_MODEL, "SecretCamera 9000")
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "6")
            saveAttributes()
        }
        return tmp.readBytes().also { tmp.delete() }
    }

    /** The payload the recipient actually receives, read back out of the container. */
    private fun payloadOf(container: File): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ShareContainer.openWithCode(container.inputStream(), out, code)
        return out.toByteArray()
    }

    /**
     * ***The sender is asked, and the answer reaches the wire.*** A share is the one egress
     * that hands a readable file to another person, so the default removes the details; a
     * sender who wants them to travel says so and they do.
     */
    @Test
    fun theShareStripsByTheSendersChoiceAndKeepsTheTurnEitherWay() {
        val jpeg = jpegWithGps()
        val item = store(jpeg, "holiday.jpg")

        val strippedFile = ShareSend.seal(
            ctx, store, item, vmk, ShareContainer.Policy.Unmanaged, code, stripMetadata = true,
        )
        val stripped = payloadOf(strippedFile)
        val strippedExif = android.media.ExifInterface(ByteArrayInputStream(stripped))
        assertNull(
            "the recipient must not receive the sender's location",
            strippedExif.getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE),
        )
        assertNull(
            "nor the camera",
            strippedExif.getAttribute(android.media.ExifInterface.TAG_MODEL),
        )

        ShareSend.sweep(ctx)

        val wholeFile = ShareSend.seal(
            ctx, store, item, vmk, ShareContainer.Policy.Unmanaged, code, stripMetadata = false,
        )
        val whole = payloadOf(wholeFile)
        assertArrayEquals("sending it as it is must change nothing at all", jpeg, whole)

        // The turn is not metadata the sender is choosing about - it is which way up the
        // picture goes, and it survives either answer.
        assertEquals(
            6,
            android.media.ExifInterface(ByteArrayInputStream(stripped))
                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 0),
        )
    }

    /**
     * `exif-strip-choice`. Under Privacy — a new install's selection — the recipient gets the
     * date and nothing that says where it was taken or on what.
     */
    @Test
    fun aPrivacySealKeepsTheDateAndNothingThatSaysWhereOrOnWhat() {
        val tmp = File(ctx.cacheDir, "share-${System.nanoTime()}.jpg")
        tmp.writeBytes(
            ByteArrayOutputStream().also {
                android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
            }.toByteArray(),
        )
        android.media.ExifInterface(tmp.absolutePath).apply {
            setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,0/1")
            setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(android.media.ExifInterface.TAG_MODEL, "SecretCamera 9000")
            setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2014:05:13 09:53:20")
            saveAttributes()
        }
        val jpeg = tmp.readBytes().also { tmp.delete() }

        val payload = payloadOf(
            ShareSend.seal(ctx, store, store(jpeg, "dated.jpg"), vmk, ShareContainer.Policy.Unmanaged, code, stripMetadata = true),
        )
        val exif = android.media.ExifInterface(ByteArrayInputStream(payload))
        assertNull(exif.getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(android.media.ExifInterface.TAG_MODEL))
        assertEquals("2014:05:13 09:53:20", exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL))
    }

    /** A share the strip cannot reach is sealed untouched, whatever the screen asked. */
    @Test
    fun anItemThatCannotBeStrippedIsSealedUnchanged() {
        val bytes = content(4_000)
        val item = store(bytes, "notes.txt")
        val file = ShareSend.seal(
            ctx, store, item, vmk, ShareContainer.Policy.Unmanaged, code, stripMetadata = true,
        )
        assertArrayEquals(bytes, payloadOf(file))
    }

    private fun anyFileContains(root: File, needle: ByteArray): Boolean =
        root.walkTopDown().filter { it.isFile && it.length() < 4_000_000 }.any { f ->
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: return@any false
            indexOf(bytes, needle) >= 0
        }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ── Where containers live, and how they leave ─────────────────────────────

    @Test
    fun containersAreWrittenInsideFilesDirOnly() {
        val item = store(content(2_000), "a.jpg")
        val file = ShareSend.seal(ctx, store, item, vmk, ShareContainer.Policy.Unmanaged, code)

        // I9 — and the reason the OUTLINE's claim that P4-2 amends it was wrong.
        assertTrue(
            "the container is under filesDir",
            file.canonicalPath.startsWith(ctx.filesDir.canonicalPath),
        )
        assertEquals(ShareSend.directory(ctx).canonicalPath, file.parentFile!!.canonicalPath)
        assertTrue("a neutral name, nothing about the app", file.name.matches(Regex("[0-9a-f]{8}\\.bin")))
    }

    @Test
    fun theSweepEmptiesTheDirectory() {
        val item = store(content(2_000), "a.jpg")
        ShareSend.seal(ctx, store, item, vmk, ShareContainer.Policy.Unmanaged, code)
        ShareSend.seal(ctx, store, item, vmk, ShareContainer.Policy.Unmanaged, code)
        assertEquals(2, ShareSend.directory(ctx).listFiles()!!.size)

        ShareSend.sweep(ctx)
        assertEquals(0, ShareSend.directory(ctx).listFiles().orEmpty().size)
    }

    @Test
    fun twoSharesOfOneItemShareNothing() {
        // Fresh salt, fresh nonce prefix, fresh share ID per container: two
        // containers of the same bytes must not be byte-identical.
        val item = store(content(10_000), "a.jpg")
        val first = ShareSend.seal(ctx, store, item, vmk, ShareContainer.Policy.Unmanaged, code)
        val second = ShareSend.seal(ctx, store, item, vmk, ShareContainer.Policy.Unmanaged, code)
        assertFalse(first.readBytes().contentEquals(second.readBytes()))
    }

    @Test
    fun aRefusedItemIsNeverSealed() {
        val long = store(content(1_000), "film.mp4", durationMs = 120_000)
        val failed = runCatching {
            ShareSend.seal(ctx, store, long, vmk, ShareContainer.Policy.Unmanaged, code)
        }.isFailure
        assertTrue("an over-long video was sealed", failed)
        assertEquals(0, ShareSend.directory(ctx).listFiles().orEmpty().size)
    }

    // ── The manifest's side of the promise ────────────────────────────────────

    @Test
    fun theProviderIsNotExportedAndNoReceivingFilterIsRegistered() {
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(
            ctx.packageName,
            android.content.pm.PackageManager.GET_PROVIDERS,
        )
        val shares = info.providers.orEmpty().single { it.authority == "${ctx.packageName}.shares" }
        assertFalse("the share provider must not be exported", shares.exported)
        assertTrue("it must grant URI permissions", shares.grantUriPermissions)

        // Receiving is P4-3's. Nothing in this change may answer a send or a view.
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            setPackage(ctx.packageName)
        }
        assertTrue(
            "this app registers no ACTION_SEND receiver",
            pm.queryIntentActivities(send, 0).isEmpty(),
        )
    }
}
