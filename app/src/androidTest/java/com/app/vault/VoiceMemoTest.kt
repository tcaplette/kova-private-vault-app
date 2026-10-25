package com.app.vault

import android.Manifest
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.app.vault.ui.memoPlayer
import java.io.File
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * What a memo is encoded as, and what it must never leave behind — `voice-memo`.
 *
 * **The disk assertion is the point of this class.** A memo that records and plays
 * but leaves a plaintext temp file has failed at the only thing this app is for, and
 * that failure is silent — the memo works, the item appears, and the clear copy sits
 * in the cache until something else overwrites it. So the recording runs with the
 * app's writable directories photographed before and after.
 *
 * The encoder settings are asserted through the bytes rather than by reading the
 * constants back: an ADTS frame states its own sample rate and channel count in its
 * header, so the stream says what it is and the test does not have to take the
 * object's word for it.
 */
@RunWith(AndroidJUnit4::class)
class VoiceMemoTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The microphone, granted for the run.
     *
     * The app asks for this on its own screen; a test has no screen to ask on, and a
     * denied permission fails here as "the recorder would not start" — which looks
     * exactly like a broken encoder and is not one.
     *
     * Through `UiAutomation` rather than `GrantPermissionRule`: that rule lives in
     * `androidx.test:rules`, which this project does not depend on, and a dependency
     * is an I1 clearance run to save four lines.
     */
    @Before
    fun grantMicrophone() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO)
    }

    /** Every file under a directory, recursively, as paths. */
    private fun tree(dir: File): Set<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()

    @Test
    fun theFilenameMakesItAnAudioItem() {
        val name = VoiceMemo.filename(1_700_000_000_000L)
        assertTrue("a memo is named for what it is: $name", name.startsWith("memo-"))
        assertTrue("the extension is what makes it audio: $name", name.endsWith(".aac"))
        assertEquals(
            "`.aac` must classify as audio — that is the whole of the kind decision",
            VaultKind.AUDIO,
            VaultKind.kindOf(name),
        )
    }

    /**
     * A recording produces ADTS, and nothing in the clear on disk.
     *
     * `assumeTrue` is deliberately not used for the microphone: the emulator has a
     * virtual one and the permission is granted by the runner, so a failure here is a
     * real failure rather than an absent device.
     */
    @Test
    fun aRecordingIsAdtsAndLeavesNoPlaintextBehind() {
        val before = tree(ctx.filesDir) + tree(ctx.cacheDir)

        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]

        val collected = ByteArray(1 shl 16)
        var got = 0
        val reader = thread {
            ParcelFileDescriptor.AutoCloseInputStream(read).use { input ->
                while (got < collected.size) {
                    val n = input.read(collected, got, collected.size - got)
                    if (n <= 0) break
                    got += n
                }
                // Drain the rest so closing the write end is what ends this, not a
                // full buffer — otherwise the recorder blocks and stop() hangs.
                val sink = ByteArray(8192)
                while (input.read(sink) > 0) { /* discarded */ }
            }
        }

        val session = VoiceMemo.Session()
        assertTrue("the recorder must start on a device with a microphone", session.start(write))
        Thread.sleep(1_500L)
        assertTrue("the elapsed clock must run while recording", session.elapsedMs() >= 1_000L)
        session.stop()
        write.close()
        reader.join(10_000L)

        assertTrue("a second and a half of audio must produce bytes", got > 0)

        // ADTS syncword: eleven set bits opening every frame. The first two bytes of
        // the stream are the first frame's header, so this is the format saying what
        // it is rather than the test trusting the constant that asked for it.
        assertEquals("first byte of the ADTS syncword", 0xFF, collected[0].toInt() and 0xFF)
        assertEquals(
            "second byte carries the rest of the syncword",
            0xF0, collected[1].toInt() and 0xF0,
        )

        // Header bits 2-3 of byte 1 are MPEG version and layer; layer must be 00 for
        // ADTS. Sampling-frequency index sits in byte 2, bits 2-5: 4 is 44100.
        val samplingIndex = (collected[2].toInt() and 0x3C) shr 2
        assertEquals("44.1 kHz is sampling frequency index 4", 4, samplingIndex)

        // Channel configuration straddles byte 2 bit 0 and byte 3 bits 6-7.
        val channels = ((collected[2].toInt() and 0x01) shl 2) or
            ((collected[3].toInt() and 0xC0) shr 6)
        assertEquals("mono is channel configuration 1", 1, channels)

        val after = tree(ctx.filesDir) + tree(ctx.cacheDir)
        val appeared = after - before
        assertTrue(
            "recording must leave no file behind — the pipe is the whole of the " +
                "output path, and a plaintext temp file is the failure this format " +
                "was chosen to prevent. Found: $appeared",
            appeared.isEmpty(),
        )
    }

    /** Stopping twice is not an error, and the second stop must not throw. */
    @Test
    fun stoppingIsIdempotent() {
        val session = VoiceMemo.Session()
        session.stop()
        session.stop()
        assertEquals("a session that never started has no elapsed time", 0L, session.elapsedMs())
        assertEquals("and no level", 0f, session.level(), 0.0001f)
    }

    @Test
    fun aSessionRefusesToStartTwice() {
        val pipe = ParcelFileDescriptor.createPipe()
        val reader = thread {
            ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                val sink = ByteArray(8192)
                while (input.read(sink) > 0) { /* discarded */ }
            }
        }
        val session = VoiceMemo.Session()
        assertTrue(session.start(pipe[1]))
        assertFalse(
            "a second start would leak the first recorder and its microphone",
            session.start(pipe[1]),
        )
        session.stop()
        pipe[1].close()
        reader.join(5_000L)
    }

    /**
     * Listen-back — `voice-memo-layout`, D2. A memo recorded into a real sealed store
     * plays from its own blobs, reports its length, and seeks — with nothing written
     * outside the blob directory.
     *
     * **Two seek numbers, because they measure different things.** The player reports
     * the position it was asked for, so its readout says little about where the audio
     * actually resumed. The stream-level number does: ADTS frames are parsed out of the
     * decrypted memo, and the frame media3's constant-bitrate estimate lands on is
     * compared with the frame that really holds 2.000s. That is the error a listener
     * hears.
     */
    @UnstableApi
    @Test
    fun aStoppedMemoPlaysAndSeeksFromItsSealedBlobs() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val root = File(ctx.filesDir, "memo-listen-${System.nanoTime()}")
        val blobsDir = File(root, "blobs")
        val store = BlobStore(blobsDir)
        val vmk = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val before = tree(ctx.filesDir) + tree(ctx.cacheDir)

        try {
            // Recorded exactly as MainActivity records: a pipe, sealed as it arrives.
            val pipe = ParcelFileDescriptor.createPipe()
            var written: WrittenItem? = null
            val writer = thread {
                written = ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { store.writeItem(it, vmk) }
            }
            val session = VoiceMemo.Session()
            assertTrue("the recorder must start on the emulator's microphone", session.start(pipe[1]))
            Thread.sleep(3_000L)
            val recordedMs = session.elapsedMs()
            session.stop()
            pipe[1].close()
            writer.join(10_000L)
            val held = written
            assertTrue("three seconds of audio must seal into blobs", held != null && held.plaintextLength > 0)
            held!!

            // --- the stream-level seek error ----------------------------------------
            val plain = store.reader(held.blobIds, vmk, held.plaintextLength).use {
                it.readRange(0, held.plaintextLength.toInt())
            }
            val starts = mutableListOf<Int>()
            var at = 0
            while (at + 7 <= plain.size &&
                (plain[at].toInt() and 0xFF) == 0xFF && (plain[at + 1].toInt() and 0xF0) == 0xF0
            ) {
                starts += at
                val length = ((plain[at + 3].toInt() and 0x03) shl 11) or
                    ((plain[at + 4].toInt() and 0xFF) shl 3) or
                    ((plain[at + 5].toInt() and 0xE0) shr 5)
                if (length <= 0) break
                at += length
            }
            plain.fill(0)
            assertTrue("the memo must parse as ADTS frames end to end", starts.size > 10)
            val frameMs = 1024 * 1000.0 / 44_100
            val averageFrame = at.toDouble() / starts.size
            val estimatedByte = SEEK_TARGET_MS / frameMs * averageFrame
            val landedFrame = starts.indexOfLast { it <= estimatedByte }
            val streamErrorMs = abs(landedFrame * frameMs - SEEK_TARGET_MS)
            Log.i("VoiceMemoTest", "listen-back seek: stream error %.1f ms over %d frames, %.1f bytes/frame"
                .format(streamErrorMs, starts.size, averageFrame))
            assertTrue(
                "a constant-bitrate seek must land within ${MAX_ERROR_MS}ms of where it was asked: " +
                    "%.1f ms".format(streamErrorMs),
                streamErrorMs <= MAX_ERROR_MS,
            )

            // --- the player -------------------------------------------------------------
            lateinit var player: ExoPlayer
            inst.runOnMainSync { player = memoPlayer(ctx, store, held, vmk) }
            fun <T> onMain(read: (ExoPlayer) -> T): T {
                var value: T? = null
                inst.runOnMainSync { value = read(player) }
                @Suppress("UNCHECKED_CAST")
                return value as T
            }
            fun awaitReady() {
                val until = System.currentTimeMillis() + 10_000L
                while (onMain { it.playbackState } != Player.STATE_READY) {
                    assertTrue("the player must prepare from the sealed blobs", System.currentTimeMillis() < until)
                    assertTrue("the player must not error: ${onMain { it.playerError }}", onMain { it.playerError } == null)
                    Thread.sleep(50L)
                }
            }
            try {
                awaitReady()
                assertFalse("listen-back must not start by itself", onMain { it.playWhenReady })
                val duration = onMain { it.duration }
                assertTrue("the player must know the memo's length", duration != C.TIME_UNSET)
                Log.i("VoiceMemoTest", "listen-back length: player $duration ms, recorder $recordedMs ms")
                assertTrue(
                    "the length must agree with the recorder within ${MAX_ERROR_MS}ms: $duration vs $recordedMs",
                    abs(duration - recordedMs) <= MAX_ERROR_MS,
                )

                inst.runOnMainSync {
                    player.seekTo(SEEK_TARGET_MS.toLong())
                    player.play()
                }
                awaitReady()
                val position = onMain { it.currentPosition }
                Log.i("VoiceMemoTest", "listen-back seek: player reports $position ms for $SEEK_TARGET_MS")
                assertTrue(
                    "the player must seek, not restart: $position",
                    abs(position - SEEK_TARGET_MS) <= MAX_ERROR_MS,
                )
            } finally {
                inst.runOnMainSync { player.release() }
            }

            val appeared = (tree(ctx.filesDir) + tree(ctx.cacheDir)) - before
            val outside = appeared.filterNot { it.startsWith(blobsDir.absolutePath) }
            assertTrue(
                "listen-back must write nothing but the sealed blobs. Found: $outside",
                outside.isEmpty(),
            )
        } finally {
            vmk.fill(0)
            root.deleteRecursively()
        }
    }

    // --- `destination-settings-and-slideshow` D5: a memo under its own key -----------------

    /** A sandboxed store and index, and a vault key that exists only in this test. */
    private class Sandbox(ctx: android.content.Context) {
        val root = File(ctx.filesDir, "memo-key-${System.nanoTime()}")
        val store = BlobStore(File(root, "blobs"), File(root, "incoming"))
        val vmk: ByteArray = VaultKeys.newVmk()
        val indexId = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val vault = OpenVault(vmk, indexId, 0)
        val publicKey: ByteArray = VaultKeys.captureKeypair(vmk).publicKey

        init {
            MetadataDb.empty().save(store, indexId, vmk)
        }

        fun close() {
            vmk.fill(0)
            root.deleteRecursively()
        }
    }

    private fun recorded(box: Sandbox, seconds: Long, settings: MemoSettings = MemoSettings(), keepKey: Boolean = true): VoiceMemo.Memo? {
        assertEquals(null, VoiceMemo.start(ctx, box.store, box.publicKey, null, settings))
        Thread.sleep(seconds * 1_000L)
        return VoiceMemo.stop(ctx, box.store, keepKey)
    }

    private fun opens(store: BlobStore, ids: List<ByteArray>, key: ByteArray, length: Long): Boolean = runCatching {
        store.reader(ids, key, length).use { it.readRange(0, minOf(length, 4_096L).toInt()) }
    }.isSuccess

    /** A test that failed mid-recording leaves the process's recorder free for the next. */
    @org.junit.After
    fun forgetTheMemo() {
        if (VoiceMemo.current != null) {
            val scratch = File(ctx.filesDir, "memo-key-after")
            VoiceMemo.discard(ctx, BlobStore(File(scratch, "blobs"), File(scratch, "incoming")))
            scratch.deleteRecursively()
        }
        MemoPending.resetForTest(ctx)
    }

    /** The memo's blobs open under its memo key and not under the vault's. */
    @Test
    fun memoBlobsDoNotOpenUnderTheVaultKey() {
        val box = Sandbox(ctx)
        try {
            val memo = recorded(box, 2)!!
            val written = memo.written!!
            assertTrue("the memo key opens it", opens(box.store, written.blobIds, memo.key()!!, written.plaintextLength))
            assertFalse("the vault key does not", opens(box.store, written.blobIds, box.vmk, written.plaintextLength))
            VoiceMemo.discard(ctx, box.store)
        } finally {
            box.close()
        }
    }

    /** Save re-seals under the vault's key and leaves no memo-key blob and no record. */
    @Test
    fun saveResealsAndLeavesNoMemoKeyBlobOrRecord() {
        val box = Sandbox(ctx)
        try {
            val memo = recorded(box, 2)!!
            val old = memo.written!!
            val plain = box.store.reader(old.blobIds, memo.key()!!, old.plaintextLength).use {
                it.readRange(0, old.plaintextLength.toInt())
            }
            val (held, item) = VoiceMemo.reseal(box.store, box.vmk)!!
            VoiceMemo.saved(ctx, box.store, held)
            val again = box.store.reader(item.blobIds, box.vmk, item.plaintextLength).use {
                it.readRange(0, item.plaintextLength.toInt())
            }
            assertTrue("the audio is copied, not re-encoded", plain.contentEquals(again))
            old.blobIds.forEach {
                assertFalse("a memo-key blob remains", box.store.fileOf(it).exists())
                assertFalse("a memo-key partial remains", box.store.partialFileOf(it).exists())
            }
            assertTrue("the pending record remains", MemoPending.ids(ctx).isEmpty())
            assertEquals("nothing is held", null, VoiceMemo.current)
        } finally {
            box.close()
        }
    }

    /** A held memo is never re-sealed into a vault other than its own. */
    @Test
    fun aMemoIsNeverSavedIntoAnotherVault() {
        val mine = Sandbox(ctx)
        val other = Sandbox(ctx)
        try {
            recorded(mine, 2)!!
            assertEquals("another vault's key re-seals nothing", null, VoiceMemo.reseal(mine.store, other.vmk))
            assertTrue("its own does", VoiceMemo.reseal(mine.store, mine.vmk) != null)
            VoiceMemo.discard(ctx, mine.store)
        } finally {
            mine.close()
            other.close()
        }
    }

    /** Another vault's unlock opens nothing and leaves the memo pending; its own makes it an item. */
    @UnstableApi
    @Test
    fun aMemoStoppedLockedJoinsOnlyItsOwnVault() {
        val mine = Sandbox(ctx)
        val other = Sandbox(ctx)
        try {
            val memo = recorded(mine, 3, keepKey = false)!!
            assertEquals("the key is gone with the memo stopped locked", null, memo.key())
            assertEquals("nothing is held", null, VoiceMemo.current)

            assertEquals("another vault ingests nothing", 0, MemoIngest.ingest(ctx, mine.store, other.vault))
            assertEquals("the record is still pending", 1, MemoPending.ids(ctx).size)

            assertEquals(1, MemoIngest.ingest(ctx, mine.store, mine.vault))
            assertTrue("the record is gone", MemoPending.ids(ctx).isEmpty())
            val item = MetadataDb.load(mine.store, mine.indexId, mine.vmk).items.single()
            assertEquals(VaultKind.AUDIO, item.kind)
            assertEquals(memo.startedAt, item.createdAt)
            assertPlays(mine.store, WrittenItem(item.blobIds, item.plaintextLength), mine.vmk)
        } finally {
            mine.close()
            other.close()
        }
    }

    /**
     * A recording cut off by the process dying: its record says nothing of its length and its last
     * blob is still a partial. The next unlock keeps it up to its last whole chunk, and it plays.
     */
    @UnstableApi
    @Test
    fun aRecordFromAKilledRecordingIngestsToAPlayableItem() {
        val box = Sandbox(ctx)
        try {
            // Real audio, then the kill reproduced: sealed into a partial by a write that dies after
            // three chunks, and a record written mid-recording — no length.
            val memo = recorded(box, 8, MemoSettings(quality = RecordingQuality.Highest))!!
            val audio = box.store.reader(memo.written!!.blobIds, memo.key()!!, memo.written!!.plaintextLength).use {
                it.readRange(0, memo.written!!.plaintextLength.toInt())
            }
            VoiceMemo.discard(ctx, box.store)
            assertTrue("eight seconds at Highest is more than three chunks: ${audio.size}", audio.size > 3 * BlobStore.CHUNK)

            val key = ByteArray(MemoRecord.KEY_BYTES).also { SecureRandom().nextBytes(it) }
            val id = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val dying = object : java.io.InputStream() {
                var at = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (at >= 3 * BlobStore.CHUNK) throw java.io.IOException("killed")
                    val n = minOf(len, 3 * BlobStore.CHUNK - at)
                    System.arraycopy(audio, at, b, off, n)
                    at += n
                    return n
                }
            }
            runCatching { box.store.writeTo(id, dying, key, resume = false) }
            assertTrue("the kill left a partial", box.store.partialFileOf(id).exists())
            MemoPending.write(ctx, MemoPending.newId(), box.publicKey, MemoRecord.Record(key, 1_000L, -1L, null, listOf(id)))

            assertEquals(1, MemoIngest.ingest(ctx, box.store, box.vault))
            val item = MetadataDb.load(box.store, box.indexId, box.vmk).items.single()
            assertEquals("kept to its last whole chunk", 3L * BlobStore.CHUNK, item.plaintextLength)
            assertFalse("the partial is gone", box.store.partialFileOf(id).exists())
            assertPlays(box.store, WrittenItem(item.blobIds, item.plaintextLength), box.vmk)
        } finally {
            box.close()
        }
    }

    /** Highest records at 256 kbps and 48 kHz — read back with `MediaExtractor`. */
    @Test
    fun highestRecordsAt256KbpsAnd48KHz() {
        val box = Sandbox(ctx)
        try {
            val memo = recorded(box, 4, MemoSettings(quality = RecordingQuality.Highest))!!
            val w = memo.written!!
            val audio = box.store.reader(w.blobIds, memo.key()!!, w.plaintextLength).use { it.readRange(0, w.plaintextLength.toInt()) }
            VoiceMemo.discard(ctx, box.store)
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(object : android.media.MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= audio.size) return -1
                    val n = minOf(size.toLong(), audio.size - position).toInt()
                    System.arraycopy(audio, position.toInt(), buffer, offset, n)
                    return n
                }
                override fun getSize(): Long = audio.size.toLong()
                override fun close() {}
            })
            val format = extractor.getTrackFormat(0)
            assertEquals(48_000, format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE))
            assertEquals("mono unless stereo was asked for", 1, format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT))
            extractor.selectTrack(0)
            var last = 0L
            while (extractor.advance()) last = extractor.sampleTime
            extractor.release()
            val kbps = audio.size * 8.0 / (last / 1_000_000.0) / 1_000.0
            Log.i("VoiceMemoTest", "Highest: %.0f kbps over %.2f s".format(kbps, last / 1_000_000.0))
            assertTrue("about 256 kbps: %.0f".format(kbps), kbps in 200.0..300.0)
        } finally {
            box.close()
        }
    }

    /** Stereo asks for two channels and gets two, or falls back to one — and the recording succeeds. */
    @Test
    fun stereoRecordsTwoChannelsOrFallsBackToOne() {
        val pipe = ParcelFileDescriptor.createPipe()
        val collected = java.io.ByteArrayOutputStream()
        val reader = thread {
            ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                val buf = ByteArray(8192)
                while (true) { val n = input.read(buf); if (n <= 0) break; collected.write(buf, 0, n) }
            }
        }
        val session = VoiceMemo.Session()
        assertTrue("the recording succeeds either way", session.start(pipe[1], stereo = true))
        Thread.sleep(1_500L)
        session.stop()
        pipe[1].close()
        reader.join(5_000L)
        val head = collected.toByteArray()
        val channels = ((head[2].toInt() and 0x01) shl 2) or ((head[3].toInt() and 0xC0) shr 6)
        assertEquals("the stream says what the session recorded", session.channels, channels)
        Log.i("VoiceMemoTest", "stereo asked, ${session.channels} channel(s) recorded")
    }

    /** The Bluetooth choice: on and a headset present, and nothing else. */
    @Test
    fun theBluetoothMicrophoneIsUsedOnlyWhenOnAndPresent() {
        val sco = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        val builtIn = android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
        assertTrue(VoiceMemo.useBluetooth(true, listOf(builtIn, sco)))
        assertFalse("off", VoiceMemo.useBluetooth(false, listOf(builtIn, sco)))
        assertFalse("no headset: the phone's microphone, silently", VoiceMemo.useBluetooth(true, listOf(builtIn)))
    }

    @UnstableApi
    private fun assertPlays(store: BlobStore, item: WrittenItem, key: ByteArray) {
        val inst = InstrumentationRegistry.getInstrumentation()
        lateinit var player: ExoPlayer
        inst.runOnMainSync { player = memoPlayer(ctx, store, item, key) }
        try {
            val until = System.currentTimeMillis() + 10_000L
            var state = Player.STATE_IDLE
            while (state != Player.STATE_READY) {
                inst.runOnMainSync { state = player.playbackState }
                var error: Any? = null
                inst.runOnMainSync { error = player.playerError }
                assertEquals("the player must not error", null, error)
                assertTrue("the memo must prepare", System.currentTimeMillis() < until)
                Thread.sleep(50L)
            }
            var duration = 0L
            inst.runOnMainSync { duration = player.duration }
            assertTrue("it has a length: $duration", duration > 0)
        } finally {
            inst.runOnMainSync { player.release() }
        }
    }

    private companion object {
        /** Where the seek test asks to land, inside a three-second memo. */
        const val SEEK_TARGET_MS = 2_000.0

        /** How far a seek or a length may be off before a listener would notice. */
        const val MAX_ERROR_MS = 500
    }
}
