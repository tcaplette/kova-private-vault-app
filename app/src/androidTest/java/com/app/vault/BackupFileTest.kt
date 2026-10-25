package com.app.vault

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
 * The backup file, sealed and opened with real Sodium — `backup-to-cloud-apps`, `FORMAT.md` §14.
 *
 * On the device for `VaultArchiveTest`'s reason: the AEAD is libsodium's, and libsodium is
 * a native library.
 */
@RunWith(AndroidJUnit4::class)
class BackupFileTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val rng = SecureRandom()
    private lateinit var root: File
    private lateinit var blobs: File
    private lateinit var snapshot: File
    private lateinit var restored: File

    @Before fun setUp() {
        root = File(ctx.cacheDir, "backup-file-${System.nanoTime()}").apply { mkdirs() }
        blobs = File(root, "blobs").apply { mkdirs() }
        snapshot = File(root, "snapshot")
        restored = File(root, "restored")
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun key() = ByteArray(32).also { rng.nextBytes(it) }

    private fun blob(size: Int): String {
        val name = ByteArray(16).also { rng.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        File(blobs, name).writeBytes(ByteArray(size).also { rng.nextBytes(it) })
        return name
    }

    private fun written(names: Set<String>, key: ByteArray, mutable: Set<String> = emptySet()): ByteArray {
        val files = BackupFile.snapshot(blobs, names, mutable, snapshot)
        return ByteArrayOutputStream().also { BackupFile.write(it, files, key) }.toByteArray()
            .also { BackupFile.dropSnapshot(snapshot) }
    }

    @Test
    fun aFileRoundTripsByteForByte() {
        val names = setOf(blob(65536), blob(131072), blob(65536))
        val k = key()
        val bytes = written(names, k.copyOf())

        val input = ByteArrayInputStream(bytes)
        val opened = BackupFile.open(input, k)
        assertNotNull(opened)
        assertEquals(names, opened!!.entries.map { it.name }.toSet())
        assertEquals(3, BackupFile.unpack(input, opened, restored))
        names.forEach { assertArrayEquals(File(blobs, it).readBytes(), File(restored, it).readBytes()) }
        assertEquals(
            "the file is exactly the size the status expects",
            BackupFile.expectedSize(3, 65536L + 131072L + 65536L), bytes.size.toLong(),
        )
    }

    /**
     * The index is saved under its own name while a backup runs. Copied aside first, the
     * bytes streamed are the bytes hashed, and the file still restores.
     */
    @Test
    fun aBlobRewrittenMidBackupStillRestores() {
        val index = blob(65536)
        val other = blob(65536)
        val k = key()
        val files = BackupFile.snapshot(blobs, setOf(index, other), setOf(index), snapshot)
        val before = File(blobs, index).readBytes()
        File(blobs, index).writeBytes(ByteArray(65536).also { rng.nextBytes(it) })
        val bytes = ByteArrayOutputStream().also { BackupFile.write(it, files, k.copyOf()) }.toByteArray()
        BackupFile.dropSnapshot(snapshot)

        val input = ByteArrayInputStream(bytes)
        val opened = BackupFile.open(input, k)!!
        assertEquals(2, BackupFile.unpack(input, opened, restored))
        assertArrayEquals("the index as it was when the table was written", before, File(restored, index).readBytes())
    }

    /** A second vault's backup onto a store that already has the keyring: it is left alone. */
    @Test
    fun anExistingBlobIsLeftAloneWhenRestoringASecondVault() {
        val shared = blob(65536)
        val own = blob(65536)
        val k = key()
        val bytes = written(setOf(shared, own), k.copyOf())
        val kept = ByteArray(65536).also { rng.nextBytes(it) }
        restored.mkdirs()
        File(restored, shared).writeBytes(kept)
        val input = ByteArrayInputStream(bytes)
        assertEquals("only the vault's own blob is written", 1, BackupFile.unpack(input, BackupFile.open(input, k)!!, restored, skipExisting = true))
        assertArrayEquals("the keyring already here is not replaced", kept, File(restored, shared).readBytes())
        assertArrayEquals(File(blobs, own).readBytes(), File(restored, own).readBytes())
    }

    @Test
    fun theWriterConsumesItsKey() {
        val k = key()
        written(setOf(blob(65536)), k)
        assertArrayEquals("the key is zeroed once the table is sealed", ByteArray(32), k)
    }

    @Test
    fun otherWordsOpenNothingAndWriteNothing() {
        val bytes = written(setOf(blob(65536)), key())
        assertNull(BackupFile.open(ByteArrayInputStream(bytes), key()))
        assertFalse(restored.exists() && restored.list()!!.isNotEmpty())
    }

    @Test
    fun aFlippedByteInABlobIsCaught() {
        val k = key()
        val bytes = written(setOf(blob(65536)), k.copyOf())
        bytes[bytes.size - 10] = (bytes[bytes.size - 10].toInt() xor 1).toByte()
        val input = ByteArrayInputStream(bytes)
        val opened = BackupFile.open(input, k)!!
        assertEquals("the file's MAC catches it", -1, BackupFile.unpack(input, opened, restored))
        assertEquals("the bad blob is not left behind", 0, restored.list()!!.size)
    }

    @Test
    fun onlyTheNamedBlobsGoIn() {
        val wanted = blob(65536)
        // Stand-ins for what must never leave: a slot file and a wrap beside the blobs,
        // and a blob of another vault inside them.
        val slots = ByteArray(4096).also { rng.nextBytes(it) }.also { File(root, "vault.slots").writeBytes(it) }
        val other = blob(65536)
        val bytes = written(setOf(wanted), key())
        val hay = bytes.toList()
        fun contains(needle: ByteArray) = java.util.Collections.indexOfSubList(hay, needle.take(64)) >= 0
        assertFalse("the slot file is not in the backup", contains(slots))
        assertFalse("another vault's blob is not in the backup", contains(File(blobs, other).readBytes()))
    }

    @Test
    fun nothingBeforeTheBlobsSaysHowManyThereAre() {
        val few = written(setOf(blob(65536)), key())
        val many = written((1..40).map { blob(65536) }.toSet(), key())
        fun tableLength(b: ByteArray) = java.nio.ByteBuffer.wrap(b, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals("one blob and forty share a table length", tableLength(few), tableLength(many))
    }

    // --- `backup-file-recover-and-import`, D3 and D6 -----------------------------------

    @Test
    fun everyRealBackupPassesIsBackup() {
        // One blob, forty, and enough that the table crosses into a second step.
        listOf(1, 40, 3000).forEach { n ->
            val names = (1..n).map { blob(if (n > 40) 16 else 65536) }.toSet()
            val bytes = written(names, key())
            assertTrue("$n blobs", BackupFile.isBackup(bytes.copyOf(BackupFile.HEAD_BYTES)))
        }
    }

    /** A `.vault`'s head: a fresh salt, a sealed-header length, then its nonce — `FORMAT.md` §11. */
    @Test
    fun syntheticVaultHeadsDoNot() {
        val random = java.util.Random(0x5EED)
        var passed = 0
        repeat(10_000) {
            val head = ByteArray(BackupFile.HEAD_BYTES).also(random::nextBytes)
            java.nio.ByteBuffer.wrap(head, 16, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(40 + 56 * random.nextInt(20_000) + 16)
            if (BackupFile.isBackup(head)) passed++
        }
        assertEquals(0, passed)
    }

    @Test
    fun anUnpackThatThrowsMidwayLeavesThePreExistingFilesAndNoneOfItsOwn() {
        val names = (1..4).map { blob(65536) }.toSet()
        val k = key()
        val bytes = written(names, k.copyOf())
        restored.mkdirs()
        val before = (1..3).associate {
            val name = ByteArray(16).also(rng::nextBytes).joinToString("") { b -> "%02x".format(b) }
            val content = ByteArray(4096).also(rng::nextBytes)
            File(restored, name).writeBytes(content)
            name to content
        }

        // Fails two blobs in, as a provider that goes away would.
        val cut = bytes.size - 2 * 65536 - 32
        val input = object : java.io.InputStream() {
            var pos = 0
            override fun read(): Int = if (pos >= cut) throw java.io.IOException("gone") else bytes[pos++].toInt() and 0xFF
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= cut) throw java.io.IOException("gone")
                val n = minOf(len, cut - pos)
                bytes.copyInto(b, off, pos, pos + n)
                pos += n
                return n
            }
        }
        val opened = BackupFile.open(input, k)!!
        val threw = runCatching { BackupFile.unpack(input, opened, restored, skipExisting = true) }.isFailure

        assertTrue("the read failure is rethrown", threw)
        assertEquals("only what was there before remains", before.keys, restored.list()!!.toSet())
        before.forEach { (name, content) -> assertArrayEquals(content, File(restored, name).readBytes()) }
    }

    // --- `per-vault-intruder-capture` ------------------------------------------------------

    /**
     * A vault holding captures in its own capture store, backed up and restored onto a clean
     * store with its words: its timeline holds the same captures.
     */
    @Test
    fun aBackedUpVaultWithCapturesRestoresThem() {
        val store = BlobStore(blobs, File(root, "incoming"))
        val lifecycle = VaultLifecycle(File(root, "vault.slots"), store)
        val passphrase = "correct horse battery staple".toByteArray()
        try {
            assertTrue(lifecycle.create(passphrase.copyOf()).opened)
            VaultKeyringSession.leaveVault()
            val kek = ByteArray(32) { 7 }
            val position = VaultKeyringSession.createVault(store, "Private") { _, _ -> kek.copyOf() }!!
            val id = enrolVaultCapture(store, position, kek, setOf(Capture.Mode.PHOTO))
            val photo = capturePhotoBytes()
            val takenAt = System.currentTimeMillis() - 30_000
            queueVaultCapture(store, id, Capture.Mode.PHOTO, photo, takenAt)
            val opened = VaultKeyringSession.openVaultWithPassphrase(position, kek)!!
            withKeyringCaptureStore(store) { CaptureIngest.ingestVault(store, it, opened) }
            val (before, beforeLog) = vaultCaptureStore(store, opened)!!

            val bytes = openVaultBackupFile(store, blobs, snapshot)
            val input = ByteArrayInputStream(bytes)
            val file = BackupFile.open(input, VaultKeyringSession.backupKey()!!)!!
            assertTrue(BackupFile.unpack(input, file, restored) > 0)

            // A clean store holding only what the backup carried, opened with the vault's key.
            val clean = BlobStore(restored, File(root, "incoming-restored"))
            val (after, afterLog) = vaultCaptureStore(clean, opened)!!
            assertEquals(before.items.map { it.createdAt }, after.items.map { it.createdAt })
            assertEquals(
                "the timeline is the same",
                CaptureLog.rows(beforeLog, before.items).map { it.takenAt to it.stage },
                CaptureLog.rows(afterLog, after.items).map { it.takenAt to it.stage },
            )
            assertEquals(takenAt, CaptureLog.rows(afterLog, after.items).single().takenAt)
            val key = VaultKeys.captureStoreKey(opened.vmk)
            val item = after.items.single()
            val readBack = clean.reader(item.blobIds, key, item.plaintextLength).use { it.readRange(0, item.plaintextLength.toInt()) }
            key.fill(0)
            assertArrayEquals("the photograph itself came back", photo, readBack)
        } finally {
            lockKeyringAndVault()
        }
    }
}
