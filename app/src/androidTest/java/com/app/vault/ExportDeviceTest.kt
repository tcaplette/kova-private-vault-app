package com.app.vault

import android.content.ContentUris
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * P1-13 against the real platform — `MediaStore` rows, `IS_PENDING`, `DATE_TAKEN`,
 * EXIF, and the passphrase gate.
 *
 * `UnvaultTest` covers the export logic through a fake destination; this covers
 * the parts only the device can answer.
 */
@RunWith(AndroidJUnit4::class)
class ExportDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var slotsFile: File
    private lateinit var store: BlobStore
    private lateinit var vmk: ByteArray
    private lateinit var indexId: ByteArray
    private lateinit var destination: MediaStoreDestination
    private val rng = SecureRandom()
    private val written = mutableListOf<ExportEntry>()

    private companion object {
        const val TAG = "VaultExportDevice"
        const val GOOD = "correcthorsebattery"
        const val OWN = "lantern quarry obsidian meadow"
        const val PANIC = "a panic phrase far from it"
    }

    @Before fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "exportdev-${System.nanoTime()}")
        root.mkdirs()
        slotsFile = File(root, "vault.slots")
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        VaultLifecycle(slotsFile, store).create(GOOD.toByteArray())
        vmk = VaultSession.vmk()!!
        indexId = VaultSession.metadataBlobId()!!.copyOf()
        destination = MediaStoreDestination(ctx)
    }

    @After fun tearDown() {
        // Announced test-only rows, removed at close. Nothing this test wrote may
        // outlive it in the device's gallery.
        written.forEach { destination.discard(it) }
        destination.find(ExportManifest.NAME)?.let { destination.discard(it) }
        destination.sweep()
        VaultLifecycle.lockNow()
        root.deleteRecursively()
    }

    private fun track(entry: ExportEntry) = entry.also { written += it }

    private fun addItem(name: String, size: Int, createdAt: Long? = null): ByteArray {
        val content = ByteArray(size).also { rng.nextBytes(it) }
        val blob = store.write(ByteArrayInputStream(content), vmk)
        val db = MetadataDb.load(store, indexId, vmk)
        db.items += VaultItem(
            listOf(blob.blobId), blob.plaintextLength, null, name, null, createdAt, 0, emptyList()
        )
        db.save(store, indexId, vmk)
        return content
    }

    /** A one-pixel JPEG carrying GPS and a camera model, so a strip has something to remove. */
    private fun jpegWithExif(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val raw = ByteArrayOutputStream().also {
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()

        val tmp = File(ctx.cacheDir, "exif-${System.nanoTime()}.jpg")
        tmp.writeBytes(raw)
        android.media.ExifInterface(tmp.absolutePath).apply {
            setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,0/1")
            setAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE, "0/1,7/1,0/1")
            setAttribute(android.media.ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            setAttribute(android.media.ExifInterface.TAG_MODEL, "SecretCamera 9000")
            setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL, "2014:05:13 09:53:20")
            saveAttributes()
        }
        return tmp.readBytes().also { tmp.delete() }
    }

    /** A JPEG with no EXIF date at all, which is what a screenshot looks like. */
    private fun jpegWithoutDate(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also {
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
    }

    private fun columnOf(entry: ExportEntry, column: String): String? =
        ctx.contentResolver.query(entry.uri, arrayOf(column), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    // --- 9.5 IS_PENDING --------------------------------------------------------

    @Test fun anEntryIsPendingUntilItIsFinished() {
        val entry = track(destination.create("pending-check.jpg", "image/jpeg", null))

        assertEquals("pending while writing", "1", columnOf(entry, MediaStore.MediaColumns.IS_PENDING))
        destination.openOutput(entry).use { it.write(ByteArray(64)) }
        assertEquals("still pending after the write", "1", columnOf(entry, MediaStore.MediaColumns.IS_PENDING))

        destination.finish(entry)
        assertEquals("visible only after finish", "0", columnOf(entry, MediaStore.MediaColumns.IS_PENDING))
        Log.i(TAG, "IS_PENDING held across the write and cleared at finish")
    }

    @Test fun aKilledExportLeavesNothingVisibleAndIsSweptLater() {
        // An entry created and never finished is exactly what a killed export
        // leaves behind.
        val orphan = destination.create("orphan.jpg", "image/jpeg", null)
        destination.openOutput(orphan).use { it.write(ByteArray(128)) }
        assertEquals("1", columnOf(orphan, MediaStore.MediaColumns.IS_PENDING))

        val swept = destination.sweep()
        assertTrue("the orphan must be swept", swept >= 1)
        assertNull("and be gone", columnOf(orphan, MediaStore.MediaColumns.IS_PENDING))
        Log.i(TAG, "swept $swept pending row(s)")
    }

    // --- 9.6 DATE_TAKEN --------------------------------------------------------

    /**
     * The date is set at insert, and the platform is measured rather than trusted.
     *
     * On the Pixel_8 AVD (API 36) `DATE_TAKEN` is **cleared** when `IS_PENDING`
     * drops and later updates to it are rejected outright — so this asserts what
     * the app controls (the value it supplies) and records what the platform did
     * with it, rather than asserting a column the app cannot write.
     */
    @Test fun theDateIsSuppliedAtInsertAndThePlatformDecides() {
        val taken = 1_400_000_000_000L
        val entry = track(destination.create("dated.jpg", "image/jpeg", taken))

        assertEquals(
            "the app supplies the item's stored createdAt",
            taken.toString(),
            columnOf(entry, MediaStore.MediaColumns.DATE_TAKEN)
        )

        destination.openOutput(entry).use { it.write(ByteArray(64)) }
        destination.finish(entry)
        Log.i(TAG, "after finish DATE_TAKEN=${columnOf(entry, MediaStore.MediaColumns.DATE_TAKEN)} (platform's choice)")
    }

    @Test fun anItemWithNoDateGetsNone() {
        val entry = track(destination.create("undated.jpg", "image/jpeg", null))
        assertNull(
            "nothing is invented for a source that offered no date",
            columnOf(entry, MediaStore.MediaColumns.DATE_TAKEN)
        )
        destination.openOutput(entry).use { it.write(ByteArray(64)) }
        destination.finish(entry)
    }

    /**
     * The durable half, and the one that actually satisfies "gallery dates match
     * originals": the date lives in the exported file's own EXIF, so it travels
     * with the file instead of living in one device's database.
     */
    @Test fun theDateIsStampedIntoTheFileWhenItCarriesNone() {
        val taken = 1_500_000_000_000L
        val plain = jpegWithoutDate()
        run {
            val db = MetadataDb.load(store, indexId, vmk)
            val blob = store.write(ByteArrayInputStream(plain), vmk)
            db.items += VaultItem(listOf(blob.blobId), blob.plaintextLength, null, "undatedphoto.jpg", null, taken, 0, emptyList())
            db.save(store, indexId, vmk)
        }

        val manifest = ExportManifest.openAt(destination)
        assertEquals(1, Unvaulter(store, destination, manifest, ctx).exportAll(indexId, vmk).exported)

        val entry = destination.find("undatedphoto.jpg").also { it?.let(::track) }
        assertNotNull("the file is at the destination", entry)
        ctx.contentResolver.openFileDescriptor(entry!!.uri, "r")!!.use { fd ->
            val stamped = android.media.ExifInterface(fd.fileDescriptor)
                .getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL)
            assertNotNull("a file with no date must be stamped with the vault's", stamped)
            Log.i(TAG, "stamped DateTimeOriginal=$stamped for createdAt=$taken")
        }
    }

    @Test fun aPhotoThatAlreadyHasItsDateIsLeftAlone() {
        val withDate = jpegWithExif()
        run {
            val db = MetadataDb.load(store, indexId, vmk)
            val blob = store.write(ByteArrayInputStream(withDate), vmk)
            db.items += VaultItem(listOf(blob.blobId), blob.plaintextLength, null, "hasdate.jpg", null, 999L, 0, emptyList())
            db.save(store, indexId, vmk)
        }

        val manifest = ExportManifest.openAt(destination)
        Unvaulter(store, destination, manifest, ctx).exportAll(indexId, vmk)

        val entry = destination.find("hasdate.jpg").also { it?.let(::track) }
        assertArrayEquals(
            "a photo carrying its own date stays byte-identical to its import",
            withDate,
            ctx.contentResolver.openInputStream(entry!!.uri)!!.use { it.readBytes() }
        )
    }

    @Test fun aFullExportPutsTheFileAtTheDestination() {
        val content = addItem("holiday.bin", 2000, createdAt = 1_500_000_000_000L)

        val manifest = ExportManifest.openAt(destination)
        Unvaulter(store, destination, manifest, ctx).exportAll(indexId, vmk)

        val entry = destination.find("holiday.bin").also { it?.let(::track) }
        assertNotNull("the file is at the destination", entry)
        assertArrayEquals(
            "byte-identical",
            content,
            ctx.contentResolver.openInputStream(entry!!.uri)!!.use { it.readBytes() }
        )
    }

    // --- 9.7 EXIF --------------------------------------------------------------

    @Test fun exifSurvivesADefaultExportAndGoesOnAStrip() {
        val jpeg = jpegWithExif()

        // Preserved: the bytes are the file, EXIF included. Nothing to implement.
        val kept = track(destination.create("kept.jpg", "image/jpeg", null))
        destination.openOutput(kept).use { it.write(jpeg) }
        destination.finish(kept)
        ctx.contentResolver.openFileDescriptor(kept.uri, "r")!!.use { fd ->
            val exif = android.media.ExifInterface(fd.fileDescriptor)
            assertNotNull("GPS must survive a default export", exif.getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE))
            assertEquals("SecretCamera 9000", exif.getAttribute(android.media.ExifInterface.TAG_MODEL))
        }

        // Stripped, through the same path the exporter uses.
        val taken = 1_600_000_000_000L
        run {
            val db = MetadataDb.load(store, indexId, vmk)
            val blob = store.write(ByteArrayInputStream(jpeg), vmk)
            db.items += VaultItem(listOf(blob.blobId), blob.plaintextLength, null, "stripped.jpg", null, taken, 0, emptyList())
            db.save(store, indexId, vmk)
        }

        val manifest = ExportManifest.openAt(destination)
        val result = Unvaulter(store, destination, manifest, ctx)
            .exportAll(indexId, vmk, strip = ExifPolicy.PRIVACY)
        assertEquals(1, result.exported)

        val stripped = destination.find("stripped.jpg").also { it?.let(::track) }
        assertNotNull(stripped)
        ctx.contentResolver.openFileDescriptor(stripped!!.uri, "r")!!.use { fd ->
            val exif = android.media.ExifInterface(fd.fileDescriptor)
            assertNull("GPS must be gone", exif.getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE))
            assertNull("camera model must be gone", exif.getAttribute(android.media.ExifInterface.TAG_MODEL))
        }
        // The date is kept deliberately — in the file, which is where it lasts.
        ctx.contentResolver.openFileDescriptor(stripped.uri, "r")!!.use { fd ->
            assertNotNull(
                "a strip must not remove the date",
                android.media.ExifInterface(fd.fileDescriptor)
                    .getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL)
            )
        }
        assertTrue(
            "the manifest says the file was edited",
            manifest.lines().any { it.contains("stripped.jpg") && it.contains("exif-edited") }
        )
        Log.i(TAG, "EXIF: preserved by default, GPS+model removed on strip, DATE_TAKEN kept")
    }

    // --- exif-strip-choice ------------------------------------------------------

    private fun seed(bytes: ByteArray, name: String, createdAt: Long?, rotation: Int = 0) {
        val db = MetadataDb.load(store, indexId, vmk)
        val blob = store.write(ByteArrayInputStream(bytes), vmk)
        db.items += VaultItem(
            listOf(blob.blobId), blob.plaintextLength, null, name, null, createdAt, 0, emptyList(),
            rotation = rotation,
        )
        db.save(store, indexId, vmk)
    }

    private fun exported(name: String): ExportEntry = destination.find(name).also { it?.let(::track) }!!

    private fun exifAt(entry: ExportEntry) =
        ctx.contentResolver.openFileDescriptor(entry.uri, "r")!!.use { android.media.ExifInterface(it.fileDescriptor) }

    private fun export(strip: Set<PhotoDetail>?) =
        Unvaulter(store, destination, ExportManifest.openAt(destination), ctx).exportAll(indexId, vmk, strip = strip)

    @Test fun removingTheDateLeavesNoDateAndStampsNone() {
        seed(jpegWithExif(), "nodate.jpg", 1_600_000_000_000L)
        assertEquals(1, export(PhotoDetail.entries.toSet()).exported)
        val exif = exifAt(exported("nodate.jpg"))
        assertNull("the file's own date is gone", exif.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNull("and the vault's was not written back", exif.getAttribute(android.media.ExifInterface.TAG_DATETIME))
        assertNull(exif.getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE))
    }

    @Test fun theStoredTurnIsComposedIntoTheExport() {
        seed(jpegWithoutDate(), "turned.jpg", null, rotation = 90)
        assertEquals(1, export(null).exported)
        assertEquals(
            android.media.ExifInterface.ORIENTATION_ROTATE_90,
            exifAt(exported("turned.jpg")).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 0),
        )
    }

    private fun bitmapAs(format: android.graphics.Bitmap.CompressFormat): ByteArray =
        ByteArrayOutputStream().also {
            android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888)
                .apply { eraseColor(android.graphics.Color.rgb(30, 90, 160)) }
                .compress(format, 100, it)
        }.toByteArray()

    /** A screenshot is a PNG with no date, and it is the file the stamp exists for. */
    @Test fun aScreenshotIsStampedWithTheVaultsDate() {
        seed(bitmapAs(android.graphics.Bitmap.CompressFormat.PNG), "screenshot.png", 1_500_000_000_000L)
        assertEquals(1, export(ExifPolicy.PRIVACY).exported)
        val entry = exported("screenshot.png")
        assertNotNull(exifAt(entry).getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNotNull(
            "and it still decodes",
            ctx.contentResolver.openInputStream(entry.uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) },
        )
    }

    @Test fun aWebpIsStampedWithTheVaultsDate() {
        seed(bitmapAs(android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS), "downloaded.webp", 1_500_000_000_000L)
        assertEquals(1, export(ExifPolicy.PRIVACY).exported)
        val entry = exported("downloaded.webp")
        assertNotNull(exifAt(entry).getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNotNull(
            "and it still decodes",
            ctx.contentResolver.openInputStream(entry.uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) },
        )
    }

    /**
     * ***The defect this change found.*** `ExifInterface.saveAttributes()` copied the whole
     * exported photograph into `File.createTempFile`, which is the app's cache. Nothing may be
     * created there while a photograph is stripped, stamped and turned on its way out, and no
     * private directory may hold its plaintext afterwards.
     */
    @Test fun nothingIsStagedWhileAPhotographIsEdited() {
        val photo = jpegWithExif()
        seed(photo, "scanned.jpg", 1_600_000_000_000L, rotation = 90)
        val created = java.util.Collections.synchronizedList(mutableListOf<String>())
        val watcher = object : android.os.FileObserver(
            ctx.cacheDir,
            android.os.FileObserver.CREATE or android.os.FileObserver.MOVED_TO,
        ) {
            override fun onEvent(event: Int, path: String?) {
                path?.let { created += it }
            }
        }
        watcher.startWatching()
        try {
            assertEquals(1, export(ExifPolicy.PRIVACY).exported)
            Thread.sleep(300)
        } finally {
            watcher.stopWatching()
        }
        assertTrue("nothing was created in the cache during the export: $created", created.isEmpty())

        val needle = photo.copyOfRange(photo.size - 40, photo.size - 8)
        listOf(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir).forEach { root ->
            root.walkTopDown().filter { it.isFile && it.length() < 8_000_000 }.forEach { f ->
                val bytes = runCatching { f.readBytes() }.getOrNull() ?: return@forEach
                var hit = false
                outer@ for (i in 0..bytes.size - needle.size) {
                    for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
                    hit = true
                    break
                }
                assertFalse("${f.path} holds the photograph's plaintext", hit)
            }
        }
        Log.i(TAG, "edited export: no cache file created, no plaintext in private directories")
    }

    // --- 9.4 collisions, against the real provider ------------------------------

    @Test fun aRealCollisionSuffixesAndTheNameIsReadBack() {
        val first = track(destination.create("clash.jpg", "image/jpeg", null))
        destination.openOutput(first).use { it.write(ByteArray(32) { 1 }) }
        destination.finish(first)

        val second = track(destination.create("clash.jpg", "image/jpeg", null))
        destination.openOutput(second).use { it.write(ByteArray(32) { 2 }) }
        destination.finish(second)

        assertFalse("the second must not reuse the first's name", first.name == second.name)
        assertArrayEquals(
            "the first file is untouched",
            ByteArray(32) { 1 },
            ctx.contentResolver.openInputStream(first.uri)!!.use { it.readBytes() }
        )
        Log.i(TAG, "collision: '${first.name}' then '${second.name}'")
    }

    // --- 9.8 re-authentication --------------------------------------------------

    @Test fun exportRequiresTheRightPassphrase() {
        assertTrue("the session is open", VaultSession.isUnlocked)

        assertFalse(
            "an unlocked session is not authorisation",
            ExportAuth.confirms(slotsFile, "wrongwrongwrong".toByteArray())
        )
        assertTrue(
            "the vault's own passphrase is",
            ExportAuth.confirms(slotsFile, GOOD.toByteArray())
        )
    }

    @Test fun reAuthDerivesRatherThanCheckingTheSession() {
        // A real derivation and a real trial decrypt: the same work an unlock
        // does. Asserted on the derivation count, not a stopwatch.
        val slots = SlotArray(slotsFile)
        val outcome = VaultUnlock.unlock(GOOD.toByteArray(), slots)
        assertEquals("one derivation", 1, outcome.derivations)
        assertEquals("every slot tried", SlotArray.SLOT_COUNT, outcome.slotsAttempted)
        outcome.vault?.vmk?.fill(0)
    }

    @Test fun theExportPassphraseIsZeroed() {
        val secret = GOOD.toByteArray()
        ExportAuth.confirms(slotsFile, secret)
        assertArrayEquals("zeroed on success", ByteArray(secret.size), secret)

        val wrong = "wrongwrongwrong".toByteArray()
        ExportAuth.confirms(slotsFile, wrong)
        assertArrayEquals("and on failure", ByteArray(wrong.size), wrong)
    }

    @Test fun aLockedVaultAuthorisesNothing() {
        VaultLifecycle.lockNow()
        assertFalse(
            "with no session there is nothing to authorise against",
            ExportAuth.confirms(slotsFile, GOOD.toByteArray())
        )
    }

    // --- `protected-vault-destroy-gate` D8: the passphrase that opens the open vault ------------

    /** A vault with its own passphrase [OWN], made on the setup's keyring and left open. */
    private fun openProtectedVault(): Int {
        val p = VaultKeyringSession.createVault(store, "Papers") { at, set ->
            VaultKeyringSession.entryPassphraseKek(OWN.toByteArray(), at, set)!!
        }!!
        val kek = VaultKeyringSession.entryPassphraseKek(
            OWN.toByteArray(), p, VaultKeyringSession.vaults().single { it.position == p }.kdfSet,
        )!!
        assertNotNull(VaultKeyringSession.openVaultWithPassphrase(p, kek))
        kek.fill(0)
        return p
    }

    @Test fun aProtectedOpenVaultTakesOnlyItsOwnPassphrase() {
        openProtectedVault()
        assertFalse("the vault list's passphrase is refused", ExportAuth.confirmsOpenVault(slotsFile, GOOD.toByteArray()))
        assertFalse("a wrong one is refused", ExportAuth.confirmsOpenVault(slotsFile, "wrongwrongwrong".toByteArray()))
        val own = OWN.toByteArray()
        assertTrue("its own passphrase authorises", ExportAuth.confirmsOpenVault(slotsFile, own))
        assertArrayEquals("and is zeroed", ByteArray(own.size), own)
    }

    @Test fun anUnprotectedOpenVaultTakesTheListsAndRefusesThePanicPhrase() {
        val lifecycle = VaultLifecycle(slotsFile, store)
        assertEquals(VaultLifecycle.PanicResult.DONE, lifecycle.setPanic(GOOD.toByteArray(), PANIC.toByteArray()))
        if (!VaultSession.isUnlocked) assertNotNull(VaultKeyringSession.openVault(0))
        assertTrue("the list's passphrase opens it", ExportAuth.confirmsOpenVault(slotsFile, GOOD.toByteArray()))
        assertFalse("the panic phrase opens a different keyring", ExportAuth.confirmsOpenVault(slotsFile, PANIC.toByteArray()))
    }

    @Test fun theDecoysCheckStillTakesTheListsPassphraseInsideAProtectedVault() {
        openProtectedVault()
        assertTrue(
            "the decoy reopens the list with this passphrase, so it stays the list's",
            ExportAuth.confirms(slotsFile, GOOD.toByteArray()),
        )
    }
}
