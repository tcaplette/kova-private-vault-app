package com.app.vault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.annotation.StringRes
import java.io.FileDescriptor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * A width and a height, so the size policy is arithmetic rather than an Android type.
 *
 * `android.util.Size` would be the obvious carrier and it is not usable here: in a
 * JVM unit test every method on it throws `Stub!`, and the size policy is exactly
 * the part of a camera that can be tested without one.
 */
data class Dim(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height.toLong()
    val aspect: Double get() = if (height == 0) 0.0 else width.toDouble() / height.toDouble()
}

/**
 * A rectangle in sensor coordinates, for [Dim]'s reason exactly.
 *
 * `android.graphics.Rect` is the obvious carrier and it is a stub in a JVM unit
 * test. Zoom and tap-to-focus are geometry — a crop rectangle and a metering
 * rectangle — and geometry is the half of a camera that can be got wrong silently:
 * a sign error in the rotation maps a tap to the opposite corner of the frame, and
 * on a device that reads as "focus does nothing". It is converted to a `Rect` at the
 * one call site that hands it to the framework.
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * The user-facing camera — P2-17.
 *
 * `IntruderCapture` (P2-4) is the other half of the same hardware and the reason
 * this one is short: it already proved that a photograph can be taken and a video
 * recorded **without any file existing anywhere** — JPEG bytes out of an
 * [ImageReader] in memory, and a [MediaRecorder] streamed through a pipe. This adds
 * the half that faces the owner: a preview, a shutter, a lens, and a recording that
 * ends when they say so.
 *
 * **Camera2, and neither CameraX nor an intent.** `ACTION_IMAGE_CAPTURE` writes to a
 * `Uri` this app supplies, which is a plaintext file in `filesDir` for the length of
 * the round trip, and hands the frame to another process first. CameraX would be
 * less code for the preview and cannot record video at all under this app's rules:
 * its `Recorder` writes to a file, to `MediaStore`, or to a **seekable** file
 * descriptor, and a pipe is not seekable. Every CameraX video route ends at a file
 * on disk, so the dependency would be paid for a path that then has to be abandoned.
 *
 * **`MPEG_2_TS`, for the same reason `IntruderCapture` gives.** MP4 writes its
 * trailing `moov` atom at `stop()` by seeking back over its own output, so an MP4
 * recorder needs a seekable output, which is a file. A transport stream has no
 * trailer. `.ts` is already in `VaultGrid.isVideo` and `Thumbnails.isVideo` because
 * intruder video captures are `.ts`, so nothing downstream changes to accept it.
 *
 * **No logging of a filename**, here or in the session below. A capture's name is a
 * timestamp of when the user was doing something private — the boundary P2-14
 * recorded, applied to the one file that also says where they were standing.
 */
object VaultCamera {

    enum class Mode { PHOTO, VIDEO }

    /** The still, capped so a 108 MP sensor cannot hand the encryption path a 30 MB snapshot. */
    const val STILL_CAP_PIXELS = 12_000_000L

    /** The recording, capped at 1080p. Above that a phone fills a vault in one afternoon. */
    const val VIDEO_CAP_PIXELS = 1920L * 1080L

    /** The preview never needs to be larger than the screen it is drawn on. */
    const val PREVIEW_CAP_PIXELS = 1920L * 1080L

    /**
     * The scan stream — P4-4.
     *
     * 720p-class, and small on purpose: the decoder binarises every pixel it is
     * given, a 50-character code is legible at a fraction of this, and the frames
     * arrive at thirty a second. Larger buys nothing and spends the budget on
     * every one of them.
     */
    const val SCAN_CAP_PIXELS = 1280L * 720L

    /** How far from the target aspect ratio still counts as the same shape. */
    const val ASPECT_SLACK = 0.01

    // --- the pure half ---------------------------------------------------------

    /**
     * The JPEG orientation for a frame taken at [deviceRotation] degrees on a sensor
     * mounted at [sensorOrientation] degrees.
     *
     * The front lens subtracts because its sensor is mirrored; the back lens adds.
     * The pair is why this is a function rather than one line at the call site: a
     * sign error here is a photograph that is upright on one lens and upside down on
     * the other, which is the bug nobody notices until they switch lenses.
     *
     * Applied as `JPEG_ORIENTATION` on a still and `setOrientationHint` on a
     * recording — **metadata, never a rotated bitmap.** Rotating pixels would mean
     * decoding a frame this app has no reason to decode, and re-encoding it would
     * cost a generation of quality for a number that fits in the header.
     */
    fun jpegOrientation(sensorOrientation: Int, deviceRotation: Int, front: Boolean): Int =
        if (front) {
            (sensorOrientation - deviceRotation + 360) % 360
        } else {
            (sensorOrientation + deviceRotation) % 360
        }

    /** `Surface.ROTATION_*` as degrees. */
    fun rotationDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
        // Compile-time constants, so this stays testable off a device: they inline
        // rather than calling into `android.jar`, where every method is a stub.
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /**
     * The sensor rectangle that fills the frame at [zoom] — `camera-conventions`.
     *
     * Digital zoom is a crop: the sensor is told to read a smaller centred rectangle
     * and scale it up, which is what `SCALER_CROP_REGION` means and what every phone
     * camera does below its optical range. 1f is the whole active array.
     *
     * Clamped to [max] here rather than at the call site, because the ceiling is a
     * property of the device and the gesture does not know it — a pinch past the end
     * should stop at the end, not ask for a crop the camera will reject.
     */
    fun cropFor(active: Box, zoom: Float, max: Float): Box {
        val z = zoom.coerceIn(1f, max.coerceAtLeast(1f))
        val w = (active.width / z).toInt().coerceAtLeast(1)
        val h = (active.height / z).toInt().coerceAtLeast(1)
        val left = active.left + (active.width - w) / 2
        val top = active.top + (active.height - h) / 2
        return Box(left, top, left + w, top + h)
    }

    /**
     * Where a tap on the preview lands on the sensor — `camera-conventions`.
     *
     * [nx] and [ny] are the tap in preview space, 0..1 from its top-left. The sensor
     * is mounted at [sensorOrientation] degrees relative to the device's upright, so
     * the preview is the sensor turned — and the tap has to be turned back before it
     * means anything to `CONTROL_AF_REGIONS`.
     *
     * **The front lens is mirrored before it is turned.** The preview shows you a
     * mirror, as every phone camera does, so a tap on the left of the picture is the
     * right of the sensor. Getting this pair wrong is the classic focus bug: correct
     * on the back lens, opposite corner on the front, and invisible to anyone testing
     * with one lens.
     *
     * The rectangle is [fraction] of the current crop, centred on the tap and clamped
     * inside it — a metering rectangle outside the crop region is undefined, and a
     * point rather than an area gives the auto-focus nothing to measure.
     */
    fun meteringBox(
        nx: Float,
        ny: Float,
        crop: Box,
        sensorOrientation: Int,
        front: Boolean,
        fraction: Float = 0.15f,
    ): Box {
        val px = (if (front) 1f - nx else nx).coerceIn(0f, 1f)
        val py = ny.coerceIn(0f, 1f)
        // Turn the preview point back into the sensor's own frame.
        val (sx, sy) = when (((sensorOrientation % 360) + 360) % 360) {
            90 -> py to 1f - px
            180 -> 1f - px to 1f - py
            270 -> 1f - py to px
            else -> px to py
        }

        val half = (fraction.coerceIn(0.02f, 1f) / 2f)
        val hw = (crop.width * half).toInt().coerceAtLeast(1)
        val hh = (crop.height * half).toInt().coerceAtLeast(1)
        val cx = crop.left + (sx * crop.width).toInt()
        val cy = crop.top + (sy * crop.height).toInt()
        // Clamped by moving the box inside the crop rather than by shrinking it: a
        // metering rectangle that gets smaller near an edge measures less exactly
        // where the user pointed most deliberately.
        val left = (cx - hw).coerceIn(crop.left, crop.right - hw * 2)
        val top = (cy - hh).coerceIn(crop.top, crop.bottom - hh * 2)
        return Box(left, top, left + hw * 2, top + hh * 2)
    }

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

    /**
     * `photo-20260820-142530.jpg`, `video-20260820-142530.ts`.
     *
     * Zero-padded and fixed width, which is not decoration: the grid's sort menu says
     * name ordering is alphabetical and not numeric, and padding is what keeps camera
     * names sorting by name in the same sequence they sort by date. P2-16 sorts names by
     * collation and not numerically, so a name whose digits were not padded would
     * put `photo 10` before `photo 2` — the cost P2-16 accepted for hand-typed names
     * and refused for these.
     *
     * The device's own zone, because the date on the file should be the date the
     * user remembers, and `Locale.ROOT` so a device language cannot change the
     * digits.
     */
    fun filename(mode: Mode, at: Long): String {
        val stamp = STAMP.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
        return when (mode) {
            Mode.PHOTO -> "photo-$stamp.jpg"
            Mode.VIDEO -> "video-$stamp.ts"
        }
    }

    /**
     * [base], or the first `name (n).ext` that [taken] does not already hold.
     *
     * Two shutter taps inside one second produce one name. P2-14 declined to make
     * filenames unique in general and that stands — this is not uniqueness, it is
     * the same suffixing an export already does when two items share a name, reused
     * so a burst of captures does not read as one photograph listed twice.
     */
    fun uniqueFilename(taken: Set<String>, base: String): String {
        if (base !in taken) return base
        var n = 2
        while (true) {
            val candidate = ExportDestination.suffixed(base, n)
            if (candidate !in taken) return candidate
            n++
        }
    }

    /**
     * The largest of [sizes] at or below [capPixels] whose shape is closest to
     * [aspect], or null when [sizes] is empty.
     *
     * Shape first, then size: a preview that does not share the still's aspect ratio
     * either stretches the picture or shows the user a frame that is not the one
     * they are about to take. Hardcoding `IntruderCapture`'s 1280×960 would be
     * simpler and wrong — that size is chosen for a clip nobody looks at.
     *
     * Falls back to the smallest size available when everything is over the cap,
     * because a camera that refuses to open is worse than one that opens large.
     */
    fun pickSize(sizes: List<Dim>, aspect: Double, capPixels: Long): Dim? {
        if (sizes.isEmpty()) return null
        val allowed = sizes.filter { it.pixels <= capPixels }
        if (allowed.isEmpty()) return sizes.minByOrNull { it.pixels }
        val closest = allowed.minOf { abs(it.aspect - aspect) }
        return allowed
            .filter { abs(it.aspect - aspect) <= closest + ASPECT_SLACK }
            .maxByOrNull { it.pixels }
    }

    // --- what the interface asks before it offers anything ---------------------

    /**
     * True when this device has any camera at all.
     *
     * Where this is false the grid's Camera action is still drawn, unusable, its label
     * saying "Not on this device" — `unavailable-capability-states`.
     */
    fun available(context: Context): Boolean = cameraIds(context).isNotEmpty()

    fun permitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun audioPermitted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** True when both lenses exist, so the switch is offered only where it does something. */
    fun hasBothLenses(context: Context): Boolean =
        facing(context, front = false) != null && facing(context, front = true) != null

    internal fun cameraIds(context: Context): List<String> = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.cameraIdList.toList()
    }.getOrDefault(emptyList())

    /** The ID of the [front] or back lens, or null where that lens does not exist. */
    internal fun facing(context: Context, front: Boolean): String? = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val want = if (front) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want
        }
    }.getOrNull()

    /** The lens to open: the one asked for, or whatever this device has. */
    internal fun cameraId(context: Context, front: Boolean): String? =
        facing(context, front) ?: cameraIds(context).firstOrNull()
}

/**
 * One open camera behind one screen — P2-17.
 *
 * Owns the device, the session, the still reader and the recorder, and nothing
 * else: it produces JPEG bytes and it writes a recording into a file descriptor it
 * is handed. **It never touches the vault.** What becomes of the bytes is
 * `MainActivity`'s, which is where the VMK is, and this way the whole of the
 * hardware can be driven by a test that stores nothing.
 *
 * Every camera call runs on one [HandlerThread]; every callback out is posted to the
 * main thread, because the caller is a composition. Nothing here blocks the caller.
 *
 * **The session has two shapes and no more.** At rest it carries the preview and the
 * still reader together, so switching between photo and video changes nothing about
 * the hardware — only what the shutter does. Recording swaps the still reader for
 * the recorder's surface, and stopping swaps it back. A lens change is a different
 * device and reopens.
 */
class VaultCameraSession(private val context: Context) {

    /** Told what went wrong, on the main thread, in words the screen can print. */
    var onProblem: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("vault-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var recorder: MediaRecorder? = null

    /**
     * The scan stream's reader — P4-4. Present only while [scan] is in force, and
     * the JPEG reader is not a target of the session while it is.
     */
    private var analysis: ImageReader? = null
    private var onFrame: ((ByteArray, Int, Int, Int) -> Unit)? = null
    private var scanSize: Dim? = null

    private var previewSurface: Surface? = null
    private var front = false
    private var opening = false

    /** Set while the recorder is running, so stop and close know what to tear down. */
    @Volatile
    var recording = false
        private set

    private var stillSize: Dim? = null
    private var videoSize: Dim? = null
    private var sensorOrientation = 0

    /** The sensor's full readable rectangle, and the zoom ceiling it supports. */
    private var activeArray: Box? = null
    private var maxZoom = 1f

    /** The live repeating request, kept so zoom and focus can change it in place. */
    private var repeating: CaptureRequest.Builder? = null
    private var zoom = 1f
    private var metering: Box? = null

    /**
     * Restores continuous auto-focus a few seconds after a tap.
     *
     * A tap means "focus *there*", and holding that lock for the rest of the session
     * means the camera never focuses again while the user walks to the next thing.
     * Every phone camera goes back to continuous, and this is the timer that does it.
     */
    private val unlockFocus = Runnable {
        metering = null
        repeating?.let { b ->
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            b.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            b.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            runCatching { session?.setRepeatingRequest(b.build(), null, handler) }
        }
    }

    // --- lifecycle -------------------------------------------------------------

    /**
     * The preview's surface, from the [SurfaceHolder] callbacks. Null when it goes
     * away, which closes the session — a session holding a destroyed surface throws
     * on the next request.
     */
    fun surface(surface: Surface?) {
        handler.post {
            previewSurface = surface
            if (surface == null) closeSession() else configure()
        }
    }

    /** Which lens. Reopens, because the other lens is a different device. */
    fun lens(front: Boolean) {
        handler.post {
            if (this.front == front) return@post
            this.front = front
            zoom = 1f
            metering = null
            repeating = null
            closeSession()
            device?.close()
            device = null
            openInternal()
        }
    }

    /**
     * Scan mode — P4-4, and the only thing this class gained for pairing.
     *
     * The preview stays exactly what it is; a small `YUV_420_888` reader joins it
     * as a **repeating** target, and every frame's luminance plane goes to
     * [onFrame] on the camera thread, with its row stride, because a camera plane
     * is padded and a decoder told the wrong row length reads nothing.
     *
     * **The in-flight guard is `acquireLatestImage` and nothing else.** The decode
     * runs on this same handler, so a slow frame blocks the listener and the
     * others queue in the reader; taking the latest and dropping the rest is what
     * makes that a dropped frame rather than a growing backlog. A dropped frame
     * costs 33 ms of a scan that lasts as long as someone holds a phone up.
     */
    fun scan(onFrame: (ByteArray, Int, Int, Int) -> Unit) {
        handler.post {
            this.onFrame = onFrame
            configure()
        }
    }

    /** Leaves scan mode and puts the still reader back. */
    fun stopScan() {
        handler.post {
            onFrame = null
            analysis?.close()
            analysis = null
            configure()
        }
    }

    /**
     * The still's shape for [front]'s lens, read without opening anything, so the
     * screen can shape its preview before the first frame arrives.
     */
    fun measure(front: Boolean): Dim? = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = VaultCamera.cameraId(context, front) ?: return null
        val map = manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        VaultCamera.pickSize(
            map.getOutputSizes(ImageFormat.JPEG).map { Dim(it.width, it.height) },
            aspect = 4.0 / 3.0,
            capPixels = VaultCamera.STILL_CAP_PIXELS,
        )
    }.getOrNull()

    fun open() {
        if (!VaultCamera.permitted(context)) return
        handler.post { openInternal() }
    }

    private fun openInternal() {
        if (device != null || opening) return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = VaultCamera.cameraId(context, front)
        if (id == null) {
            problem(R.string.camera_none)
            return
        }
        opening = true
        try {
            val chars = manager.getCameraCharacteristics(id)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?.let { Box(it.left, it.top, it.right, it.bottom) }
            // A device that reports no digital zoom gets 1f, and the pinch then has
            // nowhere to go — which is correct, not a failure to handle.
            maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            zoom = 1f
            metering = null
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) {
                opening = false
                problem(R.string.camera_no_sizes)
                return
            }

            val still = VaultCamera.pickSize(
                map.getOutputSizes(ImageFormat.JPEG).map { Dim(it.width, it.height) },
                aspect = 4.0 / 3.0,
                capPixels = VaultCamera.STILL_CAP_PIXELS,
            )
            if (still == null) {
                opening = false
                problem(R.string.camera_no_still)
                return
            }
            stillSize = still

            videoSize = VaultCamera.pickSize(
                map.getOutputSizes(MediaRecorder::class.java).map { Dim(it.width, it.height) },
                aspect = 16.0 / 9.0,
                capPixels = VaultCamera.VIDEO_CAP_PIXELS,
            ) ?: still

            scanSize = VaultCamera.pickSize(
                map.getOutputSizes(ImageFormat.YUV_420_888).map { Dim(it.width, it.height) },
                aspect = 4.0 / 3.0,
                capPixels = VaultCamera.SCAN_CAP_PIXELS,
            )

            reader?.close()
            reader = ImageReader.newInstance(still.width, still.height, ImageFormat.JPEG, 2)

            @Suppress("MissingPermission")   // checked in open(), and again by the framework
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    device = camera
                    configure()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    opening = false
                    camera.close()
                    device = null
                    problem(R.string.camera_taken)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    opening = false
                    camera.close()
                    device = null
                    problem(R.string.camera_not_opened)
                }
            }, handler)
        } catch (e: Exception) {
            opening = false
            Log.w(TAG, "open failed: ${e.javaClass.simpleName}")
            problem(R.string.camera_not_opened)
        }
    }

    /** Everything closed, in the order that does not leave a surface behind a session. */
    fun close() {
        handler.post {
            stopRecordingInternal()
            closeSession()
            device?.close()
            device = null
            reader?.close()
            reader = null
            analysis?.close()
            analysis = null
            onFrame = null
            previewSurface = null
        }
        handler.post { thread.quitSafely() }
    }

    // --- the still -------------------------------------------------------------

    /**
     * One frame, as JPEG bytes, on the main thread.
     *
     * The bytes are the caller's from that moment: they are plaintext, and the
     * caller both writes them into the store and zeroes them.
     */
    fun takePhoto(deviceRotation: Int, onJpeg: (ByteArray) -> Unit) {
        handler.post {
            val camera = device ?: return@post
            val active = session ?: return@post
            val imageReader = reader ?: return@post
            if (recording) return@post

            imageReader.setOnImageAvailableListener({ r ->
                val image = runCatching { r.acquireNextImage() }.getOrNull()
                    ?: return@setOnImageAvailableListener
                val bytes = try {
                    val buffer = image.planes[0].buffer
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                } finally {
                    image.close()
                }
                r.setOnImageAvailableListener(null, null)
                main.post { onJpeg(bytes) }
            }, handler)

            try {
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    .apply {
                        addTarget(imageReader.surface)
                        set(
                            CaptureRequest.JPEG_ORIENTATION,
                            VaultCamera.jpegOrientation(sensorOrientation, deviceRotation, front),
                        )
                        set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                        )
                        // **The photograph is what the preview showed.** Without the
                        // same crop the still comes back at 1× — the user frames at
                        // 4×, presses the shutter, and gets a wide shot of something
                        // else. The metering goes with it for the same reason.
                        tune(this)
                    }
                active.capture(request.build(), null, handler)
            } catch (e: Exception) {
                Log.w(TAG, "capture failed: ${e.javaClass.simpleName}")
                imageReader.setOnImageAvailableListener(null, null)
                problem(R.string.camera_photo_failed)
            }
        }
    }

    // --- the recording ---------------------------------------------------------

    /**
     * Starts recording into [output], the write end of a pipe the caller owns.
     * [onStarted] arrives on the main thread with whether it began.
     *
     * `prepare()` before the session, because the recorder's input surface does not
     * exist until then; `start()` after the repeating request, because the encoder
     * must be fed before it is asked to encode. That order is `IntruderCapture`'s and
     * it is not stylistic.
     *
     * A denied microphone records **silent video**: no audio source is set and the
     * recording proceeds. Refusing to record would be the app deciding that a video
     * with no sound is not worth having.
     */
    fun startRecording(output: FileDescriptor, deviceRotation: Int, onStarted: (Boolean) -> Unit) {
        handler.post {
            val camera = device
            val preview = previewSurface
            val size = videoSize
            if (camera == null || preview == null || size == null || recording) {
                main.post { onStarted(false) }
                return@post
            }

            @Suppress("DEPRECATION")   // minSdk 30 predates MediaRecorder(Context)
            val rec = MediaRecorder()
            val withAudio = VaultCamera.audioPermitted(context)
            try {
                if (withAudio) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                // A transport stream, for the reason VaultCamera's KDoc gives: MP4
                // seeks back over its own output at stop, and a pipe cannot seek.
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_2_TS)
                rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                if (withAudio) {
                    rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    rec.setAudioEncodingBitRate(128_000)
                    rec.setAudioSamplingRate(44_100)
                }
                rec.setVideoSize(size.width, size.height)
                rec.setVideoFrameRate(30)
                rec.setVideoEncodingBitRate((size.pixels * 4).toInt())
                rec.setOrientationHint(
                    VaultCamera.jpegOrientation(sensorOrientation, deviceRotation, front)
                )
                rec.setOutputFile(output)
                rec.prepare()
            } catch (e: Exception) {
                Log.w(TAG, "recorder prepare failed: ${e.javaClass.simpleName}")
                runCatching { rec.reset() }
                rec.release()
                problem(R.string.camera_recording_refused)
                main.post { onStarted(false) }
                return@post
            }

            recorder = rec
            closeSession()
            configureSession(camera, listOf(preview, rec.surface), CameraDevice.TEMPLATE_RECORD) { ok ->
                if (!ok) {
                    releaseRecorder()
                    configure()
                    main.post { onStarted(false) }
                } else if (runCatching { rec.start() }.isSuccess) {
                    recording = true
                    active = this
                    main.post { onStarted(true) }
                } else {
                    releaseRecorder()
                    problem(R.string.camera_recording_refused)
                    configure()
                    main.post { onStarted(false) }
                }
            }
        }
    }

    /**
     * Stops the recording and returns the hardware to its resting state. [onStopped]
     * arrives on the main thread **after** the recorder has released the pipe, which
     * is what ends the caller's stream.
     */
    fun stopRecording(onStopped: () -> Unit = {}) {
        handler.post {
            stopRecordingInternal()
            configure()
            main.post(onStopped)
        }
    }

    /**
     * Stops the recording and waits for the recorder to let go of the pipe. For the one
     * caller that cannot wait for the screen to leave: the lock, which must end the
     * stream before it zeroes the key the writer is encrypting under. Returns once
     * released, or after a bounded wait if the camera thread is wedged.
     */
    fun stopRecordingNow() {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            stopRecordingInternal()
            configure()
            done.countDown()
        }
        done.await(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun stopRecordingInternal() {
        val rec = recorder ?: return
        if (active === this) active = null
        if (recording) runCatching { rec.stop() }
        recording = false
        releaseRecorder()
        closeSession()
    }

    private fun releaseRecorder() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
    }

    // --- zoom and focus --------------------------------------------------------

    /** How far this lens will zoom. 1f on a device that offers none. */
    fun zoomCeiling(): Float = maxZoom.coerceAtLeast(1f)

    /**
     * Zoom by [scale] relative to where it is — the number a pinch gesture reports.
     *
     * Relative rather than absolute because that is what the gesture gives, and
     * converting in the screen would put the ceiling in two places. Returns the level
     * settled on, so the screen can print it.
     */
    fun pinch(scale: Float, then: (Float) -> Unit = {}) {
        handler.post {
            val ceiling = maxZoom.coerceAtLeast(1f)
            val next = (zoom * scale).coerceIn(1f, ceiling)
            if (next != zoom) {
                zoom = next
                repeat()
            }
            main.post { then(next) }
        }
    }

    /** Back to 1×. A lens change is a different sensor and its own crop. */
    fun resetZoom() {
        handler.post {
            if (zoom == 1f) return@post
            zoom = 1f
            repeat()
        }
    }

    /**
     * Focus and expose for the point the user tapped, in preview coordinates.
     *
     * Auto-focus is *triggered* rather than left to settle: continuous focus is
     * already running, and without a trigger the new region is only a hint the camera
     * may act on at its own pace. The trigger is a one-shot capture; the repeating
     * request carries the regions afterwards so they survive the next frame.
     *
     * Returns to continuous a few seconds later — see [unlockFocus].
     */
    fun focusAt(nx: Float, ny: Float) {
        handler.post {
            val camera = device ?: return@post
            val active = session ?: return@post
            val array = activeArray ?: return@post
            if (recording) return@post

            val crop = VaultCamera.cropFor(array, zoom, maxZoom)
            metering = VaultCamera.meteringBox(nx, ny, crop, sensorOrientation, front)

            val builder = repeating ?: return@post
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            tune(builder)
            runCatching {
                active.setRepeatingRequest(builder.build(), null, handler)
                // The trigger must not stay in the repeating request: a request that
                // asks to start focusing on every frame never finishes focusing.
                val once = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    previewSurface?.let { addTarget(it) }
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    tune(this)
                }
                active.capture(once.build(), null, handler)
            }

            handler.removeCallbacks(unlockFocus)
            handler.postDelayed(unlockFocus, FOCUS_HOLD_MS)
        }
    }

    /** The crop and the metering, onto any request that is about to be sent. */
    private fun tune(builder: CaptureRequest.Builder) {
        val array = activeArray ?: return
        val crop = VaultCamera.cropFor(array, zoom, maxZoom)
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(crop.left, crop.top, crop.right, crop.bottom))
        metering?.let { box ->
            val region = arrayOf(
                MeteringRectangle(
                    Rect(box.left, box.top, box.right, box.bottom),
                    MeteringRectangle.METERING_WEIGHT_MAX,
                ),
            )
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, region)
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, region)
        }
    }

    /** The live request, re-sent with whatever [tune] now says. */
    private fun repeat() {
        val builder = repeating ?: return
        tune(builder)
        runCatching { session?.setRepeatingRequest(builder.build(), null, handler) }
    }

    // --- session plumbing ------------------------------------------------------

    /** The resting session: the preview and the still reader, ready for a shutter tap. */
    private fun configure() {
        val camera = device ?: return
        val preview = previewSurface ?: return
        val imageReader = reader ?: return
        if (recording) return
        closeSession()
        val frames = onFrame
        if (frames == null) {
            configureSession(camera, listOf(preview, imageReader.surface), CameraDevice.TEMPLATE_PREVIEW) {}
            return
        }
        // Scan mode. The JPEG reader is not a target at all: a session carrying a
        // stream nothing reads costs the sensor's time on every frame, and there
        // is no shutter on this screen to want it.
        val size = scanSize ?: stillSize ?: return
        val scanReader = analysis ?: ImageReader.newInstance(
            size.width, size.height, ImageFormat.YUV_420_888, 2,
        ).also { analysis = it }
        scanReader.setOnImageAvailableListener({ r ->
            val image = runCatching { r.acquireLatestImage() }.getOrNull()
                ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val luma = ByteArray(buffer.remaining()).also { buffer.get(it) }
                frames(luma, image.width, image.height, plane.rowStride)
            } finally {
                image.close()
            }
        }, handler)
        configureSession(camera, listOf(preview, scanReader.surface), CameraDevice.TEMPLATE_PREVIEW) {}
    }

    private fun configureSession(
        camera: CameraDevice,
        targets: List<Surface>,
        template: Int,
        then: (Boolean) -> Unit,
    ) {
        try {
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                targets.map { OutputConfiguration(it) },
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        session = configured
                        val ok = runCatching {
                            val request = camera.createCaptureRequest(template).apply {
                                // The preview is the first target in both shapes. The
                                // still reader is not a repeating target; the
                                // recorder's surface is, which is the whole
                                // difference between them.
                                addTarget(targets.first())
                                // The still reader is not a repeating target; the
                                // recorder's surface is, and so is P4-4's scan
                                // reader — a stream that is not repeated delivers
                                // one frame when something asks, and nothing asks
                                // while a code is being held up to the lens.
                                if (template == CameraDevice.TEMPLATE_RECORD || onFrame != null) {
                                    targets.drop(1).forEach { addTarget(it) }
                                }
                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                )
                                // The zoom survives a reconfigure. A session is rebuilt
                                // when the surface changes or a recording starts and
                                // stops, and a frame that snapped back to 1× in the
                                // middle of filming would be the camera undoing the
                                // user's pinch for reasons only it can see.
                                tune(this)
                            }
                            repeating = request
                            configured.setRepeatingRequest(request.build(), null, handler)
                        }.isSuccess
                        then(ok)
                    }

                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        problem(R.string.camera_preview_failed)
                        then(false)
                    }
                },
            )
            camera.createCaptureSession(config)
        } catch (e: Exception) {
            Log.w(TAG, "session failed: ${e.javaClass.simpleName}")
            problem(R.string.camera_preview_failed)
            then(false)
        }
    }

    private fun closeSession() {
        runCatching { session?.close() }
        session = null
    }

    private fun problem(@StringRes message: Int) {
        main.post { onProblem?.invoke(context.getString(message)) }
    }

    companion object {
        /** The session with a recording in progress, for [stopRecordingNow]. */
        @Volatile
        var active: VaultCameraSession? = null

        private const val TAG = "VaultCamera"

        /** How long a tapped focus is held before continuous takes over again. */
        private const val FOCUS_HOLD_MS = 4_000L
    }
}
