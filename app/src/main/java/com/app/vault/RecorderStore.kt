package com.app.vault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Where the voice recorder cover keeps its recordings — `music-recorder-covers`.
 *
 * ***Plaintext `.m4a` files in the cover's own directory, and that is deliberate.***
 * A voice recorder holding an encrypted blob would be the one file on the device that
 * makes no sense for the app it claims to be — I8's tell arriving through a different
 * door. It also costs nothing that was not already gone: anybody who can read this
 * directory has already read the package name, which defeats every cover by a shorter
 * route. `FORMAT.md` §10 records it in those words.
 *
 * ***Its own directory, and nothing of the vault's.*** `recordings/` sits beside
 * `FORMAT.md` §2's `vault.slots` and `blobs/` and touches neither. Nothing here opens
 * the vault, holds a key, or needs a session: a cover that wrote into the vault would
 * be a vault surface, which is the opposite of the point.
 *
 * ***Not `MediaStore`, and not external storage.*** A recorder that published to the
 * public `Music/Recordings` collection would put its files where a gallery or a file
 * manager surfaces them, and I3 draws a hard line at `MediaStore` for plaintext. App
 * private is also where Apple's Voice Memos and Samsung's recorder keep theirs, so it
 * is what the cover claims to be doing anyway.
 *
 * **Nothing is pruned.** Recordings accumulate until the user deletes them, which is
 * what a recorder does; the screen shows each file's size and the total so the ceiling
 * is visible rather than discovered.
 *
 * Nothing here logs. What is in this directory, and how much of it there is, is a fact
 * about the person holding the phone.
 */
object RecorderStore {

    private const val PREFS = "recorder_state"
    private const val DIR = "recordings"

    /** `.m4a` and not `.aac`: MP4/AAC is what every recorder on either store writes. */
    const val EXTENSION = "m4a"

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** One recording: a file, and the three facts a recorder's list row shows. */
    data class Recording(
        val name: String,
        val title: String,
        val createdAtMs: Long,
        val durationMs: Long,
        val sizeBytes: Long,
    )

    /**
     * The file a new recording will be written to.
     *
     * Named by a sortable stamp, `VaultCamera`'s for its reasons: sortable, the
     * device's zone, `Locale.ROOT` digits so the name does not change with the
     * language the phone is set to.
     */
    fun newFile(context: Context, nowMs: Long = System.currentTimeMillis()): File {
        val stamp = STAMP.format(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()))
        return File(dir(context), "$stamp.$EXTENSION")
    }

    /**
     * Every recording, newest first — the order every recorder lists them in.
     *
     * Driven by the **files**, with the preferences supplying titles and durations. A
     * file with no metadata entry still appears, named by its stamp: a recording that
     * vanished from the list because a preference write was lost would be a recorder
     * losing somebody's audio, which is the worst thing this cover could do.
     */
    fun recordings(context: Context): List<Recording> {
        val p = prefs(context)
        return dir(context).listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == EXTENSION }
            .map { file ->
                Recording(
                    name = file.name,
                    title = p.getString("${file.name}$KEY_TITLE", null) ?: defaultTitle(context, file),
                    createdAtMs = p.getLong("${file.name}$KEY_CREATED", file.lastModified()),
                    durationMs = p.getLong("${file.name}$KEY_DURATION", 0L),
                    sizeBytes = file.length(),
                )
            }
            .sortedByDescending { it.createdAtMs }
    }

    fun file(context: Context, recording: Recording): File = File(dir(context), recording.name)

    /** Record a finished recording's metadata. The file is already on disk. */
    fun add(context: Context, file: File, durationMs: Long, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit()
            .putString("${file.name}$KEY_TITLE", defaultTitle(context, file))
            .putLong("${file.name}$KEY_CREATED", nowMs)
            .putLong("${file.name}$KEY_DURATION", durationMs)
            .apply()
    }

    fun rename(context: Context, recording: Recording, title: String) {
        val clean = title.trim().ifEmpty { return }
        prefs(context).edit().putString("${recording.name}$KEY_TITLE", clean).apply()
    }

    /** Delete the audio and its metadata together. */
    fun delete(context: Context, recording: Recording) {
        file(context, recording).delete()
        prefs(context).edit()
            .remove("${recording.name}$KEY_TITLE")
            .remove("${recording.name}$KEY_CREATED")
            .remove("${recording.name}$KEY_DURATION")
            .apply()
    }

    /** What the list totals under itself, in bytes. */
    fun totalBytes(context: Context): Long = recordings(context).sumOf { it.sizeBytes }

    /**
     * `Recording 3`, numbered by what is already there — every recorder's default. In the
     * app's language when it is made; from then on it is the recording's own name.
     */
    private fun defaultTitle(context: Context, file: File): String =
        context.getString(R.string.recorder_default_title, file.nameWithoutExtension)

    private const val KEY_TITLE = ".title"
    private const val KEY_CREATED = ".created"
    private const val KEY_DURATION = ".duration"
}

/**
 * The capture itself — `music-recorder-covers`.
 *
 * ***`MediaRecorder` straight to a file, and this is deliberately the opposite of
 * [VoiceMemo].*** That one streams AAC-ADTS into a pipe because a memo may never touch
 * a plaintext file; this one **is** a plaintext file writer, so it gets MP4/AAC — the
 * container Apple's Voice Memos and Samsung's recorder both produce, which every other
 * app on the phone can open. A recorder whose files nothing else will play is a
 * recorder somebody looks at twice.
 *
 * 44.1 kHz mono at 96 kbps: about 0.7 MB a minute, and the quality a person expects
 * from the app they think this is.
 *
 * ***Uses `RECORD_AUDIO`, which this app already declares*** for intruder capture and
 * voice memos, and requests nothing itself. The grant is taken in identity setup,
 * inside the vault, because a system permission dialog carries the application label.
 */
class Recorder {

    private var recorder: MediaRecorder? = null
    private var startedAtMs = 0L
    private var accumulatedMs = 0L

    /** True between [start] and [stop], including while paused. */
    val active: Boolean get() = recorder != null

    var paused: Boolean = false
        private set

    /** Recorded milliseconds so far — paused time does not count. */
    fun elapsedMs(): Long =
        accumulatedMs + if (recorder != null && !paused) SystemClock.elapsedRealtime() - startedAtMs else 0L

    /**
     * The current input level, 0f..1f, for the meter.
     *
     * `maxAmplitude` is the delta since it was last asked, which is what makes it a
     * meter rather than a level: it is read on a frame timer by the screen. Square
     * root, because a linear amplitude bar barely moves for speech.
     */
    fun level(): Float {
        val rec = recorder ?: return 0f
        if (paused) return 0f
        val amplitude = try {
            rec.maxAmplitude
        } catch (_: Exception) {
            0
        }
        return (kotlin.math.sqrt(amplitude / MAX_AMPLITUDE.toDouble())).toFloat().coerceIn(0f, 1f)
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Begin writing to [file]. Returns whether capture actually started. */
    fun start(context: Context, file: File): Boolean {
        if (recorder != null) return false
        if (!hasPermission(context)) return false

        @Suppress("DEPRECATION") // minSdk 30 predates MediaRecorder(Context)
        val rec = MediaRecorder()
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(CHANNELS)
            rec.setAudioSamplingRate(SAMPLE_RATE)
            rec.setAudioEncodingBitRate(BIT_RATE)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            startedAtMs = SystemClock.elapsedRealtime()
            accumulatedMs = 0L
            paused = false
            true
        } catch (_: Exception) {
            runCatching { rec.release() }
            file.delete()
            false
        }
    }

    fun pause() {
        val rec = recorder ?: return
        if (paused) return
        runCatching { rec.pause() }
        accumulatedMs = elapsedMs()
        paused = true
    }

    fun resume() {
        val rec = recorder ?: return
        if (!paused) return
        runCatching { rec.resume() }
        startedAtMs = SystemClock.elapsedRealtime()
        paused = false
    }

    /**
     * Finish, and answer how long the recording was.
     *
     * `stop()` throws on a capture too short to have produced a frame — an ordinary
     * thing for a finger that brushed the control, not an error to surface. The
     * release still has to happen either way, and a zero-length file is deleted rather
     * than listed as a recording of nothing.
     */
    fun stop(file: File): Long {
        val rec = recorder ?: return 0L
        val duration = elapsedMs()
        runCatching { rec.stop() }
        runCatching { rec.release() }
        recorder = null
        paused = false
        accumulatedMs = 0L
        if (file.length() == 0L) {
            file.delete()
            return 0L
        }
        return duration
    }

    /** Abandon a capture and leave nothing behind — trigger setup's path. */
    fun discard(file: File) {
        val rec = recorder ?: return
        runCatching { rec.stop() }
        runCatching { rec.release() }
        recorder = null
        paused = false
        accumulatedMs = 0L
        file.delete()
    }

    private companion object {
        const val CHANNELS = 1
        const val SAMPLE_RATE = 44_100
        const val BIT_RATE = 96_000

        /** `maxAmplitude`'s ceiling for 16-bit PCM. */
        const val MAX_AMPLITUDE = 32_767
    }
}
