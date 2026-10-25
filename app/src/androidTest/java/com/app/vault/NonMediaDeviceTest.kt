package com.app.vault

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * P2-18's Verify: an mp3, a PDF and a `.zip`.
 *
 * `VaultKindTest` answers the classification without a device. This answers the
 * three clauses `OUTLINE.md` puts on the task and only a device can settle: **the
 * mp3 plays through the same decrypting `VaultDataSource` as video**, and **the
 * other two export byte-identical**. The third is this change's own claim rather
 * than the outline's — that a vault holding a document stops decrypting it at every
 * unlock — and it is measured here rather than asserted, because a cost nobody
 * measures is a cost that comes back.
 *
 * **The mp3 is synthesised, and that is not a shortcut.** Android has no MP3
 * *encoder* — only a decoder — so an on-device fixture is either a checked-in
 * binary or valid frames built by hand. These are real MPEG-1 Layer III frames with
 * silent main data: a decoder plays them, which is exactly what is under test. The
 * PDF and the zip are likewise the real thing, header and all, because the point is
 * what the app does with bytes it did not make.
 */
@RunWith(AndroidJUnit4::class)
class NonMediaDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private lateinit var root: File
    private lateinit var store: BlobStore
    private lateinit var vault: VaultLifecycle
    private lateinit var indexId: ByteArray
    private lateinit var destination: MediaStoreDestination
    private val exported = mutableListOf<String>()
    private val rng = SecureRandom()

    private companion object {
        const val TAG = "VaultNonMedia"
        const val PASS = "correcthorsebattery"
        const val MP3 = "song.mp3"
        const val PDF = "statement.pdf"
        const val ZIP = "archive.zip"
    }

    private fun pass() = PASS.toByteArray(Charsets.UTF_8)
    private fun vmk() = VaultSession.vmk()!!

    @Before
    fun setUp() {
        VaultLifecycle.lockNow()
        root = File(ctx.filesDir, "nonmedia-${System.nanoTime()}").apply { mkdirs() }
        store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        vault = VaultLifecycle(File(root, "vault.slots"), store)
        assertTrue("fixture vault must open", vault.create(pass()).opened)
        indexId = VaultSession.metadataBlobId()!!.copyOf()
        destination = MediaStoreDestination(ctx)
    }

    @After
    fun tearDown() {
        exported.forEach { name -> destination.find(name)?.let { destination.discard(it) } }
        destination.find(ExportManifest.NAME)?.let { destination.discard(it) }
        destination.sweep()
        VaultLifecycle.lockNow()
        root.deleteRecursively()
    }

    // --- the Verify -----------------------------------------------------------

    @Test
    fun anMp3PlaysThroughTheDecryptingDataSource() {
        val (item, plain) = addItem(MP3, silentMp3())
        assertEquals("the fixture is not audio to this app", VaultKind.AUDIO, VaultKind.kindOf(MP3))

        // I3, while it plays and not only afterwards — the shape `PlaybackTest`
        // established. A design that staged a decrypted copy and removed it at the
        // end would pass a scan taken after playback.
        val stop = AtomicBoolean(false)
        var passes = 0
        val hits = mutableListOf<String>()
        val scanner = thread {
            while (!stop.get()) {
                passes++
                privateFiles().forEach { f ->
                    if (FileScan.contains(f, plain.copyOfRange(0, 64))) hits += f.path
                }
            }
        }

        val player = newPlayer(item)
        try {
            val ready = CountDownLatch(1)
            val failure = arrayOfNulls<PlaybackException>(1)
            instrumentation.runOnMainSync {
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) ready.countDown()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        failure[0] = error
                        ready.countDown()
                    }
                })
                player.prepare()
                player.playWhenReady = true
            }
            assertTrue("the player never became ready", ready.await(30, TimeUnit.SECONDS))
            assertEquals("the player errored: ${failure[0]?.errorCodeName}", null, failure[0])

            var duration = 0L
            instrumentation.runOnMainSync { duration = player.duration }
            assertTrue("no duration came out of the decrypted stream", duration > 0)

            Thread.sleep(1_500)
            var position = 0L
            instrumentation.runOnMainSync { position = player.currentPosition }
            assertTrue("playback did not advance", position > 0)
            Log.i(TAG, "audio: ${duration} ms declared, position ${position} ms after 1.5 s")
        } finally {
            instrumentation.runOnMainSync { player.release() }
            stop.set(true)
            scanner.join(10_000)
        }

        privateFiles().forEach { f ->
            if (FileScan.contains(f, plain.copyOfRange(0, 64))) hits += "post: ${f.path}"
        }
        assertTrue("the scanner never ran", passes > 0)
        assertTrue("plaintext audio found on disk: $hits", hits.isEmpty())
    }

    @Test
    fun aDocumentAndAnArchiveExportByteIdentical() {
        val (_, pdf) = addItem(PDF, pdfBytes())
        val (_, zip) = addItem(ZIP, zipBytes())
        exported += listOf(PDF, ZIP)

        assertEquals(VaultKind.FILE, VaultKind.kindOf(PDF))
        assertEquals(VaultKind.FILE, VaultKind.kindOf(ZIP))

        val manifest = ExportManifest.openAt(destination)
        val result = Unvaulter(store, destination, manifest, ctx).exportAll(indexId, vmk())
        assertEquals("an item failed to export", 0, result.failed)

        listOf(PDF to pdf, ZIP to zip).forEach { (name, content) ->
            val entry = destination.find(name)
            assertNotNull("$name never reached the destination", entry)
            val landed = destination.openInput(entry!!).use { it.readBytes() }
            assertArrayEquals("$name did not export byte-identical", content, landed)
            Log.i(TAG, "$name exported ${landed.size} B byte-identical")
        }
    }

    /**
     * The cost this change removes, measured.
     *
     * Before P2-18 `Thumbnails.backfill` selected on `thumb == null` alone, so a
     * document was decrypted **whole** and handed to `BitmapFactory` at every
     * unlock, forever. The counting store makes that visible: a backfill pass over
     * a vault holding only documents must read nothing at all.
     */
    @Test
    fun aDocumentIsNotDecryptedByEveryUnlock() {
        val counting = ReadCountingStore(File(root, "blobs"), File(root, "incoming"))
        val (pdf, _) = addItem(PDF, pdfBytes(), into = counting)
        val (zip, _) = addItem(ZIP, zipBytes(), into = counting)

        // **Only these two items' blobs.** The index is read through the same call
        // — `MetadataDb.load` goes through `reader` — so an unqualified counter
        // counts the commit itself and says nothing about the documents. This is
        // the question being asked: was any of *their content* decrypted.
        val thumbs = ThumbnailStore(counting)
        counting.watch(pdf.blobIds + zip.blobIds)
        counting.reads = 0
        val filled = MetadataDb.commit(counting, indexId, vmk()) { db ->
            Thumbnails.backfill(counting, thumbs, db, vmk())
        }

        assertEquals("a thumbnail was made for a document", 0, filled)
        assertEquals(
            "the backfill decrypted a document to try for a thumbnail it can never have",
            0,
            counting.reads,
        )
        Log.i(TAG, "backfill over two documents: ${counting.reads} item reads")
    }

    /** An audio item gets its length without a picture being decoded — D4. */
    @Test
    fun audioGetsALengthAndNoThumbnail() {
        val (item, _) = addItem(MP3, silentMp3())
        val made = Thumbnails.generate(store, item, vmk())

        assertEquals("audio produced a bitmap", null, made.webp)
        assertNotNull("audio produced no length", made.durationMs)
        assertTrue("the length is not plausible", made.durationMs!! > 0)
        Log.i(TAG, "audio length read without a frame: ${made.durationMs} ms")
    }

    // --- fixtures --------------------------------------------------------------

    private fun addItem(
        name: String,
        content: ByteArray,
        into: BlobStore = store,
    ): Pair<VaultItem, ByteArray> {
        val written = into.writeItem(ByteArrayInputStream(content), vmk())
        val item = VaultItem(
            blobIds = written.blobIds,
            plaintextLength = written.plaintextLength,
            thumb = null,
            filename = name,
            album = null,
            createdAt = 1_400_000_000_000L,
            importedAt = 1_400_000_000_000L,
            tags = emptyList(),
            deletable = true,
        )
        MetadataDb.commit(into, indexId, vmk()) { db -> db.items += item; db }
        return item to content
    }

    /**
     * Real MPEG-1 Layer III frames, 128 kbit/s at 44.1 kHz, with silent main data.
     *
     * `0xFF 0xFB` is the sync word plus MPEG-1 Layer III with no CRC; `0x90` is
     * 128 kbit/s at 44.1 kHz; the frame is 417 bytes. A decoder reads these as
     * silence, which is all this fixture has to be — what is under test is the
     * decrypting stream underneath, not the audio coming out of it.
     */
    private fun silentMp3(frames: Int = 200): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(frames) {
            val frame = ByteArray(417)
            frame[0] = 0xFF.toByte()
            frame[1] = 0xFB.toByte()
            frame[2] = 0x90.toByte()
            frame[3] = 0x00
            out.write(frame)
        }
        return out.toByteArray()
    }

    /** A real, openable one-page PDF — header, object table, trailer. */
    private fun pdfBytes(): ByteArray {
        val body = buildString {
            append("%PDF-1.4\n")
            append("1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n")
            append("2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n")
            append("3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n")
            append("trailer<</Root 1 0 R>>\n")
            append("%%EOF\n")
        }
        return body.toByteArray(Charsets.US_ASCII)
    }

    /** A real zip: local header, central directory, end record — written by the platform. */
    private fun zipBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("payload.bin"))
            zip.write(ByteArray(8192).also { rng.nextBytes(it) })
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun privateFiles(): List<File> =
        listOf(ctx.filesDir, ctx.cacheDir, ctx.noBackupFilesDir)
            .flatMap { it.walkTopDown().filter { f -> f.isFile }.toList() }

    private fun newPlayer(item: VaultItem): ExoPlayer {
        var player: ExoPlayer? = null
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(ctx).build().apply {
                val factory = VaultDataSource.Factory(store, item.blobIds, vmk(), item.plaintextLength)
                setMediaSource(
                    ProgressiveMediaSource.Factory(factory).createMediaSource(
                        MediaItem.fromUri(VaultDataSource.uriFor(item.blobIds.first()))
                    )
                )
            }
        }
        return player!!
    }

    /**
     * Counts item reads. `ItemTrashTest`'s counting-store idiom, pointed at the
     * other direction: "nothing was decrypted" is a claim about reads, and timing
     * would measure the emulator instead.
     */
    private class ReadCountingStore(dir: File, incoming: File) : BlobStore(dir, incoming) {
        var reads = 0
        private var watched = emptySet<String>()

        fun watch(blobIds: List<ByteArray>) {
            watched = blobIds.map { it.joinToString("") { b -> "%02x".format(b) } }.toSet()
        }

        override fun reader(
            blobIds: List<ByteArray>,
            vmk: ByteArray,
            plaintextLength: Long,
            context: VaultKeys.BlobContext,
        ): Reader {
            val hex = blobIds.map { it.joinToString("") { b -> "%02x".format(b) } }
            if (hex.any { it in watched }) reads++
            return super.reader(blobIds, vmk, plaintextLength, context)
        }
    }
}
