package com.app.vault

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

/**
 * Recording a voice memo straight into the vault — `voice-memo`.
 *
 * **Nothing here touches disk in the clear.** The recorder writes into the write end of a pipe; a
 * thread on the other end reads it into [BlobStore.writeItem], which seals blobs as the recording
 * is produced. There is no file, no `MediaStore`, no cache, and no copy of the memo in memory —
 * which is also why there is no length cap.
 *
 * **Sealed under its own key, never the vault's** — `destination-settings-and-slideshow` D5. Every
 * memo gets a fresh random **memo key**, and its blobs are sealed under it exactly as an item's are
 * under the VMK. That is what lets a memo keep recording when the screen turns off: the vault locks
 * and its key is wiped as it always is, and the recorder never needed it. The memo key, the blob
 * IDs, the folder and the start time are sealed to the vault's capture public key in a pending
 * record ([MemoPending]), so a memo stopped while locked — or cut off by the process dying — joins
 * that vault, and only that vault, at its next unlock ([MemoIngest]). Saving while unlocked
 * re-seals the memo under the VMK into an ordinary item.
 *
 * **The recording outlives its screen.** It lives here, for the process, and the screen draws
 * whatever is here: the lock drops the screen and leaves the recording running, and an unlock of
 * the same vault draws it again with the clock still going. `MemoRecordingService` keeps the
 * process and its right to the microphone while no activity is on screen.
 *
 * **The container is forced, not preferred.** `AAC_ADTS`, for the reason [IntruderCapture] records:
 * ADTS is self-framing and streams, where MP4 finalises by seeking backwards and needs a seekable,
 * plaintext file. It is also why a killed recording plays up to its last whole frame.
 */
object VoiceMemo {

    private const val TAG = "VoiceMemo"

    /** `VaultCamera`'s stamp, for its reasons: sortable, device zone, `Locale.ROOT` digits. */
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    private val rng = SecureRandom()

    /**
     * The memo's name. `.aac` and not a stored kind field: [VaultKind.kindOf] reads the extension,
     * so this is the whole of what makes a memo an audio item.
     */
    fun filename(at: Long): String =
        "memo-${STAMP.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))}.aac"

    /**
     * The one memo in hand — recording, or stopped and held for listen-back — or null. There is
     * never more than one: one microphone, and one memo screen.
     */
    @Volatile
    var current: Memo? = null
        private set

    /** Bumped at every change to [current] — started, stopped, saved, discarded, locked. */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    /**
     * Whether the memo screen is on screen with its vault open. Set by `MainActivity`. A Stop from
     * the notification keeps the memo key for listen-back only then; otherwise it zeroes it at once
     * and the memo waits, sealed, for its vault.
     */
    @Volatile
    var screenShowing = false

    /**
     * Starts a memo, or says why it could not. [publicKey] is the open vault's capture public key,
     * which the memo's record is sealed to; [folderId] is where it was started. Quality, channels
     * and the Bluetooth microphone are read now, and a change made while recording waits for the
     * next one.
     */
    @Synchronized
    fun start(
        context: Context,
        store: BlobStore,
        publicKey: ByteArray,
        folderId: ByteArray?,
        settings: MemoSettings,
    ): String? {
        if (current != null) return context.getString(R.string.memo_already_running)
        val key = ByteArray(MemoRecord.KEY_BYTES).also { rng.nextBytes(it) }
        val memo = Memo(MemoPending.newId(), publicKey.copyOf(), folderId?.copyOf(), System.currentTimeMillis(), key)
        val app = context.applicationContext
        // The record exists before the first byte does, so nothing is ever written that it does
        // not account for.
        memo.persist(app, length = -1L)

        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()
        if (pipe == null) {
            memo.forget(app, store)
            return context.getString(R.string.memo_could_not_record)
        }
        memo.write = pipe[1]
        memo.writer = thread(name = "vault-memo") {
            memo.written = runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input ->
                    store.writeItem(input, key, memo.claimed, claim = { memo.persist(app, length = -1L) })
                }
            }.onFailure { Log.w(TAG, "memo write failed: ${it.javaClass.simpleName}") }.getOrNull()
        }

        val audio = app.getSystemService(AudioManager::class.java)
        val route = if (settings.bluetooth && audio != null) BluetoothRoute(audio) else null
        val device = route?.acquire()
        if (!memo.session.start(pipe[1], settings.quality, settings.stereo, device)) {
            route?.release()
            memo.closeWrite()
            memo.forget(app, store)
            return context.getString(R.string.memo_mic_failed)
        }
        memo.route = route
        memo.startSampling()
        current = memo
        _changes.value++
        return null
    }

    /**
     * Stops the memo recording now. **Blocking** — it waits for the sealed write to finish — so
     * never on the main thread. With [keepKey] the memo stays in hand for listen-back; without it
     * the memo key is zeroed here and the memo waits, sealed, for its vault's next unlock.
     *
     * Returns the stopped memo, or null when there was nothing to keep — no memo, or one too short
     * to have produced audio, which is deleted with its record.
     */
    @Synchronized
    fun stop(context: Context, store: BlobStore, keepKey: Boolean): Memo? {
        val memo = current ?: return null
        if (!memo.recording) return memo
        memo.recording = false
        memo.session.stop()
        memo.stoppedMs = memo.session.elapsedAtStop
        memo.route?.release()
        memo.route = null
        memo.closeWrite()
        memo.writer?.join()
        val app = context.applicationContext
        val written = memo.written
        if (written == null || written.plaintextLength == 0L) {
            memo.forget(app, store)
            current = null
            _changes.value++
            return null
        }
        memo.persist(app, length = written.plaintextLength)
        if (!keepKey) {
            memo.zeroKey()
            current = null
        }
        _changes.value++
        return memo
    }

    /**
     * Throws the memo in hand away: the recording stops if it is still going, and every blob it
     * wrote and its record are deleted. Blocking, like [stop].
     */
    @Synchronized
    fun discard(context: Context, store: BlobStore) {
        val memo = current ?: return
        if (memo.recording) {
            memo.recording = false
            memo.session.stop()
            memo.route?.release()
            memo.closeWrite()
            memo.writer?.join()
        }
        memo.forget(context.applicationContext, store)
        current = null
        _changes.value++
    }

    /**
     * The held memo, read under its memo key and sealed again under [vmk] — the item's blobs.
     * Blocking. The memo stays in hand until [saved] says the index took it, so a lock or a failed
     * commit in between leaves it pending rather than lost.
     */
    @Synchronized
    fun reseal(store: BlobStore, vmk: ByteArray): Pair<Memo, WrittenItem>? {
        val memo = current?.takeIf { !it.recording } ?: return null
        val written = memo.written ?: return null
        val key = memo.key() ?: return null
        // Only the vault it was sealed to: a memo is never re-sealed into another vault.
        val kp = VaultKeys.captureKeypair(vmk)
        val ours = try {
            kp.publicKey.contentEquals(memo.publicKey)
        } finally {
            kp.zero()
        }
        if (!ours) return null
        return memo to MemoIngest.reseal(store, written.blobIds, key, written.plaintextLength, vmk)
    }

    /** The index holds [memo] now: its memo-key blobs and its record go, and so does its key. */
    @Synchronized
    fun saved(context: Context, store: BlobStore, memo: Memo) {
        memo.forget(context.applicationContext, store)
        if (current === memo) current = null
        _changes.value++
    }

    /**
     * The vault is locking. A memo still recording is not touched — it never used the vault's key.
     * A memo stopped and held for listen-back loses its key here and waits, sealed, for the vault's
     * next unlock: a lock never discards a memo, and never leaves its key behind.
     */
    @Synchronized
    fun lock() {
        val memo = current ?: return
        if (memo.recording) return
        memo.zeroKey()
        current = null
        _changes.value++
    }

    /**
     * Whether a Bluetooth headset's microphone records this memo: the setting is on and one of the
     * phone's inputs is a headset's — `memos-settings`. Pure, for the JVM.
     */
    fun useBluetooth(setting: Boolean, inputTypes: List<Int>): Boolean =
        setting && inputTypes.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    /** One memo, from Record to Save, Discard or its vault's next unlock. */
    class Memo internal constructor(
        /** Its pending record's name. */
        val pendingId: String,
        /** The capture public key of the vault it belongs to. */
        val publicKey: ByteArray,
        val folderId: ByteArray?,
        /** Wall-clock start: what names and dates the item. */
        val startedAt: Long,
        key: ByteArray,
    ) {
        private var key: ByteArray? = key

        /** The recent levels the live strip draws, and the whole memo's for the recorded waveform. */
        val live = LevelRing(LIVE_CAPACITY)
        val overview = WaveformOverview()

        /** How many levels have landed — what tells a canvas to draw again. */
        @Volatile
        var readings = 0
            private set

        @Volatile
        var recording = true
            internal set

        /** The sealed write under the memo key, once the recording has stopped. */
        @Volatile
        var written: WrittenItem? = null
            internal set

        internal val claimed: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        internal val session = Session()
        internal var write: ParcelFileDescriptor? = null
        internal var writer: Thread? = null
        internal var route: BluetoothRoute? = null
        @Volatile internal var stoppedMs = -1L
        private var sampler: Thread? = null

        /** Milliseconds recorded: still counting while recording, fixed once stopped. */
        fun elapsedMs(): Long = if (stoppedMs >= 0) stoppedMs else session.elapsedMs()

        /**
         * The memo key, for listen-back — the array itself, so zeroing it at a lock leaves the
         * player nothing to read with. Null once zeroed.
         */
        @Synchronized
        fun key(): ByteArray? = key

        /** The blobs it has claimed, as lowercase hex — what the partial sweep must leave. */
        fun pendingBlobHex(): Set<String> =
            synchronized(claimed) { claimed.mapTo(HashSet()) { id -> id.joinToString("") { "%02x".format(it) } } }

        @Synchronized
        internal fun zeroKey() {
            key?.fill(0)
            key = null
        }

        /** Seals the record as it stands to [publicKey], replacing the last one. */
        internal fun persist(context: Context, length: Long) {
            val k = key() ?: return
            val ids = synchronized(claimed) { claimed.map { it.copyOf() } }
            MemoPending.write(context, pendingId, publicKey, MemoRecord.Record(k, startedAt, length, folderId, ids))
        }

        /** Deletes every blob the memo wrote and its record, and zeroes its key. */
        internal fun forget(context: Context, store: BlobStore) {
            MemoIngest.forget(store, synchronized(claimed) { claimed.toList() })
            MemoPending.remove(context, pendingId)
            zeroKey()
        }

        internal fun closeWrite() {
            write?.let { runCatching { it.close() } }
            write = null
        }

        /**
         * `getMaxAmplitude` reports the peak *since the last call*, so it is read here on a steady
         * cadence — also while no screen is drawing it, so a memo whose screen came back after an
         * unlock has the whole of its waveform.
         */
        internal fun startSampling() {
            sampler = thread(name = "vault-memo-level", isDaemon = true) {
                while (recording) {
                    val level = session.level()
                    live.add(level)
                    overview.add(level)
                    readings++
                    try {
                        Thread.sleep(SAMPLE_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    /**
     * One recorder: the microphone through `AudioRecord`, encoded to AAC-LC by `MediaCodec`, framed
     * as ADTS onto the pipe. [stop] is safe to call twice.
     *
     * **Not `MediaRecorder`, because it does not record what it is asked to.** It clamps the bitrate
     * and the channel count to the device's `media_profiles.xml` without saying so — the emulator
     * caps AAC at 96 kbps and one channel, and real phones each at their own — so *Highest* and
     * *Stereo* would have been words on a row. `MediaCodec` is not clamped by that profile, and the
     * platform's own AAC encoder takes 8 to 960 kbps and up to six channels on every device. The
     * output is the same self-framing ADTS stream as before, so storage, listen-back, ingest and a
     * killed recording's recovery are unchanged.
     *
     * **The bitrate is stated rather than defaulted** — [RecordingQuality], per channel. Mono unless
     * stereo was asked for and the microphone gives two; a device that refuses two records one, and
     * the memo still succeeds. A Bluetooth headset records at the headset's own call quality, mono,
     * so neither quality nor stereo is applied to it.
     */
    class Session {

        private var record: AudioRecord? = null
        private var codec: MediaCodec? = null
        private var worker: Thread? = null
        @Volatile private var stopping = false
        private val peak = java.util.concurrent.atomic.AtomicInteger(0)
        private var startedAt = 0L

        /** Channels this session is recording: 2 only when asked for and taken. */
        var channels = 0
            private set

        /** The elapsed time at the moment [stop] ran. */
        var elapsedAtStop = 0L
            private set

        /** Milliseconds since [start], or 0 when nothing is recording. */
        fun elapsedMs(): Long =
            if (record == null) 0L else SystemClock.elapsedRealtime() - startedAt

        /** The loudest sample since the last call, 0..1, against the 16-bit ceiling. */
        fun level(): Float = (peak.getAndSet(0).coerceIn(0, 32_767) / 32_767f)

        /**
         * Starts recording into [write]. False when the hardware refused, which the screen says
         * rather than swallowing. The descriptor is the caller's: closing it is what ends the
         * encrypting read on the other side, and this never closes it.
         */
        @SuppressLint("MissingPermission")   // the caller only starts with RECORD_AUDIO granted
        fun start(
            write: ParcelFileDescriptor,
            quality: RecordingQuality = RecordingQuality.Standard,
            stereo: Boolean = false,
            /** A Bluetooth headset's microphone, already routed; null for the phone's. */
            device: AudioDeviceInfo? = null,
        ): Boolean {
            if (record != null) return false
            val q = if (device != null) RecordingQuality.Standard else quality
            val wanted = if (stereo && device == null) 2 else 1
            for (ch in listOf(wanted, 1).distinct()) {
                val mask = if (ch == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                val min = AudioRecord.getMinBufferSize(q.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                var rec: AudioRecord? = null
                var enc: MediaCodec? = null
                try {
                    rec = AudioRecord(
                        MediaRecorder.AudioSource.MIC, q.sampleRate, mask,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(min, PCM_BYTES * ch * 4),
                    )
                    if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("not initialised")
                    if (device != null) rec.preferredDevice = device
                    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, q.sampleRate, ch).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        // The quality is per channel: stereo is two of them, at about twice the size.
                        setInteger(MediaFormat.KEY_BIT_RATE, q.bitRate * ch)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_BYTES * ch)
                    }
                    enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    enc.start()
                    rec.startRecording()
                    if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("not recording")
                    record = rec
                    codec = enc
                    channels = ch
                    stopping = false
                    startedAt = SystemClock.elapsedRealtime()
                    val out = java.io.FileOutputStream(write.fileDescriptor)
                    worker = thread(name = "vault-memo-encode") { encode(rec, enc, out, q.sampleRate, ch) }
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "could not start recording with $ch channel(s)", e)
                    runCatching { rec?.release() }
                    runCatching { enc?.release() }
                }
            }
            return false
        }

        /**
         * Reads the microphone into the encoder and the encoder's frames onto the pipe, each behind
         * its seven-byte ADTS header, until [stop] — then ends the stream, drains what is left, and
         * lets both go.
         */
        private fun encode(rec: AudioRecord, enc: MediaCodec, out: java.io.OutputStream, sampleRate: Int, ch: Int) {
            val pcm = ByteArray(PCM_BYTES * ch)
            val info = MediaCodec.BufferInfo()
            val frame = ByteArray(8_192)
            var ended = false
            // Each input's time from the samples before it, so the encoder sees time move on.
            var samples = 0L
            try {
                while (true) {
                    if (!ended) {
                        val inIndex = enc.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            if (stopping) {
                                enc.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                ended = true
                            } else {
                                val n = rec.read(pcm, 0, pcm.size)
                                if (n > 0) {
                                    meter(pcm, n)
                                    enc.getInputBuffer(inIndex)!!.apply { clear(); put(pcm, 0, n) }
                                }
                                val at = samples * 1_000_000L / sampleRate
                                samples += n.coerceAtLeast(0) / (2 * ch)
                                enc.queueInputBuffer(inIndex, 0, n.coerceAtLeast(0), at, 0)
                            }
                        }
                    }
                    val outIndex = enc.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (outIndex >= 0) {
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0) {
                            val size = info.size
                            val buf = if (size + ADTS_BYTES <= frame.size) frame else ByteArray(size + ADTS_BYTES)
                            adtsHeader(buf, size + ADTS_BYTES, sampleRate, ch)
                            enc.getOutputBuffer(outIndex)!!.apply { position(info.offset); get(buf, ADTS_BYTES, size) }
                            out.write(buf, 0, size + ADTS_BYTES)
                        }
                        enc.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                out.flush()
            } catch (e: Exception) {
                // A pipe closed under it — the memo was discarded — ends it the same way.
                Log.w(TAG, "memo encoding ended: ${e.javaClass.simpleName}")
            } finally {
                pcm.fill(0)
                frame.fill(0)
                runCatching { rec.stop() }
                runCatching { rec.release() }
                runCatching { enc.stop() }
                runCatching { enc.release() }
            }
        }

        /** Keeps the loudest 16-bit sample since [level] last read it. */
        private fun meter(pcm: ByteArray, n: Int) {
            var loudest = 0
            var i = 0
            while (i + 1 < n) {
                val v = abs((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8))
                if (v > loudest) loudest = v
                i += 2
            }
            peak.accumulateAndGet(loudest) { a, b -> maxOf(a, b) }
        }

        /**
         * Stops recording and waits for the last frame to reach the pipe. Idempotent. A recording
         * stopped before the encoder produced a frame writes nothing, and the caller finds out from
         * the zero-length write.
         */
        @Synchronized
        fun stop() {
            val rec = record ?: return
            elapsedAtStop = elapsedMs()
            record = null
            codec = null
            stopping = true
            worker?.join(STOP_WAIT_MS)
            worker = null
            // The encoder lets the microphone go as it ends; this is for one that never drained.
            runCatching { rec.release() }
        }
    }

    /**
     * An ADTS header for one AAC-LC frame of [frameLength] bytes, header included — ISO 14496-3:
     * the syncword, MPEG-4, no CRC, the sampling-frequency index and the channel configuration.
     * Pure, so the JVM suite holds the bits.
     */
    fun adtsHeader(into: ByteArray, frameLength: Int, sampleRate: Int, channels: Int) {
        val freq = ADTS_RATES.indexOf(sampleRate).also { require(it >= 0) { "no ADTS index for $sampleRate" } }
        val profile = 1   // AAC LC, as audio object type 2 minus one
        into[0] = 0xFF.toByte()
        into[1] = 0xF1.toByte()
        into[2] = ((profile shl 6) or (freq shl 2) or (channels shr 2)).toByte()
        into[3] = (((channels and 3) shl 6) or (frameLength shr 11)).toByte()
        into[4] = ((frameLength shr 3) and 0xFF).toByte()
        into[5] = (((frameLength and 7) shl 5) or 0x1F).toByte()
        into[6] = 0xFC.toByte()
    }

    /** ADTS's sampling-frequency table, in index order. */
    private val ADTS_RATES = listOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350)

    private const val ADTS_BYTES = 7

    /** PCM per channel per read: 1024 samples, one AAC frame's worth, 16-bit. */
    private const val PCM_BYTES = 2_048

    private const val TIMEOUT_US = 10_000L

    /** How long [Session.stop] waits for the encoder to drain — a frame is 21ms. */
    private const val STOP_WAIT_MS = 5_000L

    /**
     * A Bluetooth headset's microphone, held for one recording and let go at its end —
     * `memos-settings`. `setCommunicationDevice` from API 31, `startBluetoothSco` on 30. Neither
     * is held past the recording: the platform keeps a communication device for the life of the
     * process that asked, so leaving it set would hold every call's audio on the headset.
     */
    internal class BluetoothRoute(private val audio: AudioManager) {
        private var held = false

        /** The headset's microphone, routed — or null with no headset, and the phone's is used. */
        fun acquire(): AudioDeviceInfo? {
            val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
            if (!useBluetooth(true, inputs.map { it.type })) return null
            val input = inputs.first { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val out = audio.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?: return null
                    if (!audio.setCommunicationDevice(out)) return null
                } else {
                    @Suppress("DEPRECATION")
                    audio.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    audio.isBluetoothScoOn = true
                }
                held = true
                input
            }.getOrNull()
        }

        fun release() {
            if (!held) return
            held = false
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audio.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    audio.isBluetoothScoOn = false
                    @Suppress("DEPRECATION")
                    audio.stopBluetoothSco()
                }
            }
        }
    }
}

/**
 * The most recent readings, newest first — the live strip's memory. Fixed at [capacity], so a long
 * memo holds exactly what a short one does.
 */
class LevelRing(val capacity: Int) {
    private val levels = FloatArray(capacity)
    private var next = 0

    /** How many readings are held, up to [capacity]. */
    var size = 0
        private set

    fun add(level: Float) {
        levels[next] = level
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    /** The reading [back] steps before the newest; 0 is the newest. */
    fun recent(back: Int): Float = levels[(next - 1 - back + capacity * 2) % capacity]

    fun clear() {
        size = 0
        next = 0
    }
}

/**
 * The whole recording in [buckets] bars — `voice-memo-layout` D3, the recorded waveform's memory.
 *
 * Each bucket keeps the loudest reading it has seen. When every bucket is full, neighbours merge in
 * pairs and each bucket then stands for twice as many readings, so the array never grows: a
 * forty-minute memo holds what a forty-second one does.
 */
class WaveformOverview(buckets: Int = OVERVIEW_BUCKETS) {
    private val levels = FloatArray(buckets)
    private var used = 0
    private var perBucket = 1
    private var inLast = 0

    /** The fixed size of the backing array. Exposed so a test can hold it to that. */
    val capacity: Int get() = levels.size

    @Synchronized
    fun add(level: Float) {
        if (used == 0 || inLast == perBucket) {
            if (used == levels.size) {
                val half = levels.size / 2
                for (i in 0 until half) levels[i] = max(levels[2 * i], levels[2 * i + 1])
                levels.fill(0f, half)
                used = half
                perBucket *= 2
            }
            levels[used] = 0f
            used++
            inLast = 0
        }
        levels[used - 1] = max(levels[used - 1], level)
        inLast++
    }

    @Synchronized
    fun bars(): FloatArray = levels.copyOf(used)

    @Synchronized
    fun clear() {
        levels.fill(0f)
        used = 0
        perBucket = 1
        inLast = 0
    }
}

/** The level loop's cadence. */
const val SAMPLE_MS = 100L

/** The recorded waveform's bar count — D3. Even, so pairs merge cleanly. */
const val OVERVIEW_BUCKETS = 96

/**
 * The live strip's ring. Enough slots for the widest hero this app draws (a tablet sideways, at
 * 5dp a slot); a narrower screen draws the newest ones that fit.
 */
const val LIVE_CAPACITY = 512
