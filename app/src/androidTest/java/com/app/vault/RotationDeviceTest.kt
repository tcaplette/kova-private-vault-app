package com.app.vault

import android.media.ExifInterface
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
import java.io.File
import java.security.SecureRandom

/**
 * Turning an item, against a real store — `viewer-chrome-and-editor`.
 *
 * `VaultRotateTest` answers the arithmetic and the row on the JVM. This answers the
 * two claims that need a store behind them: a turn is a write to the index and to
 * **nothing else**, and it survives a lock — plus the angle an export writes, which
 * is the only place the stored turn has to leave the app and be right about it.
 *
 * Its own store under `filesDir`, deleted in [tearDown]; it never touches a vault
 * that is already there.
 */
@RunWith(AndroidJUnit4::class)
class RotationDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private lateinit var indexId: ByteArray
    private val rng = SecureRandom()

    private companion object {
        const val PASS = "correcthorsebattery"
    }

    private fun pass() = PASS.toByteArray(Charsets.UTF_8)
    private fun vmk() = VaultSession.vmk()!!

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "rotate-${System.nanoTime()}").apply { mkdirs() }
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

    private fun addItem(name: String): VaultItem {
        val content = ByteArray(2_048).also { rng.nextBytes(it) }
        val blob = store.write(ByteArrayInputStream(content), vmk())
        val item = VaultItem(
            blobIds = listOf(blob.blobId),
            plaintextLength = blob.plaintextLength,
            thumb = ThumbRef(ByteArray(16) { 0x7A }, 8, 128),
            filename = name,
            album = null,
            createdAt = 1_600_000_000_000L,
            importedAt = 1_700_000_000_000L,
            tags = emptyList(),
        )
        MetadataDb.commit(store, indexId, vmk()) { db -> db.items += item; db }
        return item
    }

    private fun live() = MetadataDb.load(store, indexId, vmk()).items.single()

    // --- 1. a turn writes the index and nothing else --------------------------

    @Test
    fun aTurnChangesTheAngleAndLeavesEverythingElseAlone() {
        val before = addItem("photo.jpg")

        VaultRotate.rotate(store, indexId, vmk(), before)

        val after = live()
        assertEquals(90, after.rotation)
        assertEquals("the identity must not move", before.itemId, after.itemId)
        assertArrayEquals(before.blobIds.single(), after.blobIds.single())
        assertEquals(before.plaintextLength, after.plaintextLength)
        assertEquals(before.filename, after.filename)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(before.importedAt, after.importedAt)
        // The thumbnail reference is the whole of task 2.3's claim: a turn does not
        // send the app back to rebuild a thumbnail.
        assertNotNull(after.thumb)
        assertArrayEquals(before.thumb!!.packBlobId, after.thumb!!.packBlobId)
        assertEquals(before.thumb!!.offset, after.thumb!!.offset)
        assertEquals(before.thumb!!.length, after.thumb!!.length)
    }

    @Test
    fun fourTurnsThroughTheStoreReturnToTheStart() {
        val item = addItem("photo.jpg")

        repeat(4) { VaultRotate.rotate(store, indexId, vmk(), live()) }

        assertEquals(0, live().rotation)
        assertArrayEquals(item.blobIds.single(), live().blobIds.single())
    }

    @Test
    fun aTurnSurvivesLockAndUnlock() {
        addItem("photo.jpg")
        VaultRotate.rotate(store, indexId, vmk(), live(), quarterTurns = 2)
        assertEquals(180, live().rotation)

        VaultLifecycle.lockNow()
        assertTrue("the fixture vault must reopen", vault.unlockAndOpen(pass()))

        assertEquals("the turn did not survive the lock", 180, live().rotation)
    }

    /** Gone by another path while the viewer sat open: skipped, not an error. */
    @Test
    fun turningAnItemThatIsNoLongerThereIsNotAnError() {
        val ghost = addItem("photo.jpg")
        MetadataDb.commit(store, indexId, vmk()) { db -> db.items.clear(); db }

        VaultRotate.rotate(store, indexId, vmk(), ghost)

        assertTrue(MetadataDb.load(store, indexId, vmk()).items.isEmpty())
    }

    // --- 2. what an export writes ---------------------------------------------

    /**
     * The angle an exported file opens at is the tag inside it plus the stored turn.
     *
     * Without this the app shows one thing and the export another, which makes
     * "rotate" a lie the moment the file leaves the vault.
     */
    @Test
    fun anExportComposesTheStoredTurnIntoTheTag() {
        // A file that already knows it was taken turned, turned once more by the user.
        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_180,
            Unvaulter.composedOrientation(ExifInterface.ORIENTATION_ROTATE_90, 90),
        )
        // A file with no tag carries the stored turn alone.
        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_270,
            Unvaulter.composedOrientation(ExifInterface.ORIENTATION_NORMAL, 270),
        )
        // A turn that undoes the tag exports upright rather than at some third angle.
        assertEquals(
            ExifInterface.ORIENTATION_NORMAL,
            Unvaulter.composedOrientation(ExifInterface.ORIENTATION_ROTATE_270, 90),
        )
        // Four quarters is where it started, in the file as in the index.
        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_90,
            Unvaulter.composedOrientation(ExifInterface.ORIENTATION_ROTATE_90, 360),
        )
    }

    /** An unrotated item's tag is left exactly as it arrived. */
    @Test
    fun anUnrotatedItemsTagIsUntouched() {
        listOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_ROTATE_270,
        ).forEach { tag ->
            assertEquals(tag, Unvaulter.composedOrientation(tag, 0))
        }
    }
}
