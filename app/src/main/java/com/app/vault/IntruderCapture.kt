package com.app.vault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The camera and microphone half of intruder capture — P2-4.
 *
 * Everything here is silent and best-effort: called from a background thread at a
 * *failed* unlock, it opens the front camera and/or the microphone, produces one
 * clip, and hands the raw bytes back. No preview surface is ever created, no
 * notification is posted, and no shutter the app can suppress is played. A missing
 * camera, a denied permission, or a timeout returns null — an intruder capture that
 * cannot be taken is not an error the owner's device should surface.
 *
 * **Nothing here touches disk.** The photo path reads JPEG bytes out of an
 * [ImageReader] in memory. The audio and video paths stream the recorder's output
 * through a [ParcelFileDescriptor] pipe into a bounded buffer — never a file, never
 * `MediaStore`, never the cache (I3). The container formats are chosen for exactly
 * that: `AAC_ADTS` and `MPEG_2_TS` are streamable, where MP4 would need a seekable
 * output to write its trailing `moov` atom and therefore a temp file.
 *
 * The bytes this returns are plaintext for the length of one function call on one
 * thread; [Capture.seal] turns them into a sealed blob immediately after, and the
 * caller zeroes them. They are never handed to any other component in the clear.
 */
object IntruderCapture {

    private const val TAG = "IntruderCapture"

    /** Short by design — an intruder clip, not a recording. */
    private const val AUDIO_MS = 4_000L
    private const val VIDEO_MS = 4_000L

    /** Bounded so a stuck recorder cannot fill memory. Well above a 4 s clip. */
    private const val MAX_CLIP_BYTES = 8 * 1024 * 1024

    private const val PHOTO_WIDTH = 1280
    private const val PHOTO_HEIGHT = 960
    private const val VIDEO_WIDTH = 640
    private const val VIDEO_HEIGHT = 480

    private const val OPEN_TIMEOUT_MS = 5_000L

    /**
     * True when the front camera exists. A device without one cannot photograph or
     * film an intruder; the enrolment screen reads this so a mode that can never fire
     * is not offered as if it could.
     */
    fun hasFrontCamera(context: Context): Boolean = frontCameraId(context) != null

    fun cameraPermitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun audioPermitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Takes one capture for [mode], returning its raw bytes, or null if it could not
     * be taken. Rechecks the permission itself, so a mode whose permission was
     * revoked after enrolment simply captures nothing.
     */
    fun capture(context: Context, mode: Capture.Mode): ByteArray? = runCatching {
        when (mode) {
            Capture.Mode.PHOTO ->
                if (cameraPermitted(context)) capturePhoto(context) else null
            Capture.Mode.AUDIO ->
                if (audioPermitted(context)) captureAudio() else null
            Capture.Mode.VIDEO ->
                if (cameraPermitted(context) && audioPermitted(context)) captureVideo(context)
                else null
        }
    }.onFailure { Log.i(TAG, "capture for $mode did not complete: ${it.javaClass.simpleName}") }
        .getOrNull()

    // --- photo, Camera2 still into an ImageReader ------------------------------

    private fun capturePhoto(context: Context): ByteArray? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = frontCameraId(context) ?: return null

        val bg = HandlerThread("capture-photo").apply { start() }
        val handler = Handler(bg.looper)
        val reader = ImageReader.newInstance(PHOTO_WIDTH, PHOTO_HEIGHT, ImageFormat.JPEG, 1)

        var bytes: ByteArray? = null
        val done = CountDownLatch(1)

        reader.setOnImageAvailableListener({ r ->
            runCatching {
                r.acquireLatestImage()?.use { image ->
                    val buf = image.planes[0].buffer
                    bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                }
            }
            done.countDown()
        }, handler)

        var device: CameraDevice? = null
        try {
            @Suppress("MissingPermission")   // the caller checked; capture() rechecks
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    camera.createCaptureSession(
                        listOf(reader.surface),
                        object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            override fun onConfigured(
                                session: android.hardware.camera2.CameraCaptureSession
                            ) {
                                val request = camera.createCaptureRequest(
                                    CameraDevice.TEMPLATE_STILL_CAPTURE
                                ).apply {
                                    addTarget(reader.surface)
                                    // `intruder-capture-record`. Without this every
                                    // capture was stored at the lens's raw mounting
                                    // angle — typically 270° on a front camera — and
                                    // every intruder photograph came out sideways.
                                    //
                                    // The same function the in-app camera uses, so the
                                    // two cannot disagree about the front lens's sign.
                                    // Metadata, never a rotated bitmap: baking the
                                    // angle in would mean decoding and re-encoding a
                                    // frame to move a number that fits in the header.
                                    set(
                                        CaptureRequest.JPEG_ORIENTATION,
                                        VaultCamera.jpegOrientation(
                                            sensorOrientation = sensorOrientation(context),
                                            // **Zero, and that is a decision.** This
                                            // fires from a background thread at a failed
                                            // unlock: there is no activity on screen
                                            // whose rotation could be read, and a stale
                                            // value from a window that is not showing
                                            // would be worse than the common case. A
                                            // phone being unlocked is held upright far
                                            // more often than not.
                                            deviceRotation = 0,
                                            front = true,
                                        ),
                                    )
                                }
                                session.capture(request.build(), null, handler)
                            }

                            override fun onConfigureFailed(
                                session: android.hardware.camera2.CameraCaptureSession
                            ) = done.countDown()
                        },
                        handler,
                    )
                }

                override fun onDisconnected(camera: CameraDevice) { camera.close(); done.countDown() }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); done.countDown()
                }
            }, handler)

            done.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            device?.close()
            reader.close()
            bg.quitSafely()
        }
        return bytes
    }

    // --- audio, MediaRecorder over a pipe --------------------------------------

    private fun captureAudio(): ByteArray? = recordToPipe(AUDIO_MS, prepare = { recorder, writeFd ->
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setOutputFile(writeFd)
    })

    // --- video, Camera2 feeding MediaRecorder over a pipe ----------------------

    private fun captureVideo(context: Context): ByteArray? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = frontCameraId(context) ?: return null

        val bg = HandlerThread("capture-video").apply { start() }
        val handler = Handler(bg.looper)

        var device: CameraDevice? = null
        return try {
            recordToPipe(VIDEO_MS, prepare = { recorder, writeFd ->
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                // MPEG-2 TS streams: no seekable output, so no temp file. MP4 writes
                // its moov atom at stop by seeking, which a pipe cannot do.
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS)
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
                recorder.setVideoFrameRate(24)
                recorder.setOutputFile(writeFd)
            }, start = { recorder ->
                // The recorder's input surface must be fed by the camera before
                // start(), so the camera is opened and its repeating request wired
                // here, between prepare() and start().
                val surface = recorder.surface
                val opened = CountDownLatch(1)
                @Suppress("MissingPermission")
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        camera.createCaptureSession(
                            listOf(surface),
                            object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                                override fun onConfigured(
                                    session: android.hardware.camera2.CameraCaptureSession
                                ) {
                                    val request = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_RECORD
                                    ).apply { addTarget(surface) }
                                    session.setRepeatingRequest(request.build(), null, handler)
                                    opened.countDown()
                                }

                                override fun onConfigureFailed(
                                    session: android.hardware.camera2.CameraCaptureSession
                                ) = opened.countDown()
                            },
                            handler,
                        )
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close(); opened.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close(); opened.countDown()
                    }
                }, handler)
                opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            })
        } finally {
            device?.close()
            bg.quitSafely()
        }
    }

    /**
     * Drives a [MediaRecorder] whose output is the write end of a pipe, draining the
     * read end into a bounded in-memory buffer. Records for [durationMs], stops, and
     * returns the bytes.
     */
    private fun recordToPipe(
        durationMs: Long,
        prepare: (MediaRecorder, java.io.FileDescriptor) -> Unit,
        start: (MediaRecorder) -> Unit = {},
    ): ByteArray? {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        val collected = ByteArrayOutputStream()
        val drain = thread(name = "capture-drain") {
            ParcelFileDescriptor.AutoCloseInputStream(readSide).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (collected.size() + n <= MAX_CLIP_BYTES) collected.write(buf, 0, n)
                }
            }
        }

        @Suppress("DEPRECATION")   // minSdk 30 predates MediaRecorder(Context)
        val recorder = MediaRecorder()
        try {
            prepare(recorder, writeSide.fileDescriptor)
            recorder.prepare()
            start(recorder)
            recorder.start()
            Thread.sleep(durationMs)
        } catch (e: Exception) {
            Log.i(TAG, "recorder failed: ${e.javaClass.simpleName}")
            runCatching { recorder.reset() }
            recorder.release()
            runCatching { writeSide.close() }
            drain.join()
            return null
        }

        runCatching { recorder.stop() }
        recorder.release()
        // Closing the write end ends the read loop; the drain thread finishes.
        runCatching { writeSide.close() }
        drain.join()

        val bytes = collected.toByteArray()
        return bytes.takeIf { it.isNotEmpty() }
    }

    // --- blank and silent — `per-vault-intruder-capture` D10 ----------------------

    /** No pixel brighter than this luma, out of 255, is a blank frame. */
    private const val BLANK_LUMA = 16

    /** No decoded 16-bit sample larger than this in magnitude is a silent clip. */
    private const val SILENT_SAMPLE = 2

    /**
     * Whether [media], as [capture] returned it for [mode], came out blank (photo, video) or
     * silent (audio). Read from the bytes in memory — no file is written. Undecidable reads as
     * not blank: the label states a result, so it is only set on one.
     *
     * The ceiling: a photo taken in a pocket is black too. The label says what came out, never why.
     */
    fun isBlank(mode: Capture.Mode, media: ByteArray): Boolean = runCatching {
        when (mode) {
            Capture.Mode.PHOTO -> android.graphics.BitmapFactory.decodeByteArray(
                media, 0, media.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 16 },
            )?.let(::dark) ?: false
            Capture.Mode.VIDEO -> android.media.MediaMetadataRetriever().run {
                try {
                    setDataSource(BytesSource(media))
                    getFrameAtTime(1_000_000L)?.let(::dark) ?: false
                } finally {
                    release()
                }
            }
            Capture.Mode.AUDIO -> silent(media)
        }
    }.getOrDefault(false)

    private fun dark(bitmap: android.graphics.Bitmap): Boolean {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        return px.none { c ->
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            (299 * r + 587 * g + 114 * b) / 1000 > BLANK_LUMA
        }
    }

    /** Decodes the AAC clip to PCM and looks for one sample above near-zero. */
    private fun silent(media: ByteArray): Boolean {
        val extractor = android.media.MediaExtractor()
        var codec: android.media.MediaCodec? = null
        try {
            extractor.setDataSource(BytesSource(media))
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            var decodedAny = false
            var idle = 0
            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val pcm = codec.getOutputBuffer(outIndex)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    while (pcm.hasRemaining()) {
                        decodedAny = true
                        if (kotlin.math.abs(pcm.get().toInt()) > SILENT_SAMPLE) return false
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIndex == android.media.MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && ++idle > 50) {
                    // Half a second with nothing out after the last input: a decoder that never
                    // signals the end is not waited on for ever.
                    break
                }
            }
            return decodedAny
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    /** [media] as a seekable source in memory — a decoder reads it without a file. */
    private class BytesSource(private val bytes: ByteArray) : android.media.MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = bytes.size.toLong()
        override fun close() = Unit
    }

    // --- helpers ---------------------------------------------------------------

    /**
     * How far the front lens is mounted from upright, or 0 where the device will not
     * say — `intruder-capture-record`.
     *
     * Zero rather than a guess: a wrong angle turns every capture the wrong way, and
     * an absent one leaves them as they were before this existed.
     */
    internal fun sensorOrientation(context: Context): Int {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return 0
        val id = frontCameraId(context) ?: return 0
        return runCatching {
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        }.getOrDefault(0)
    }

    private fun frontCameraId(context: Context): String? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        return runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
        }.getOrNull()
    }
}
