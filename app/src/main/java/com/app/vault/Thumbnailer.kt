package com.app.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a stored item into a thumbnail, without a file path existing anywhere —
 * `OUTLINE.md` P1-8's Verify, and I3.
 *
 * Every decoder here is handed an in-memory source over [BlobStore.Reader].
 * That is the whole reason this class exists: `MediaMetadataRetriever` and
 * `BitmapFactory` both have convenient path-taking overloads, and using either
 * would mean decrypting to disk.
 */
object Thumbnailer {

    /** Longest edge, sized for `DESIGN.md`'s 112dp grid cell at 3x. */
    const val MAX_EDGE = 384

    const val WEBP_QUALITY = 80

    /**
     * A frame is acceptable when its luma standard deviation clears this.
     *
     * **Variance, not brightness.** `getFrameAtTime(0)` returns black for a large
     * fraction of real video — fades from black, letterboxed intros, any codec
     * whose first sync sample precedes real content. A mean-luma test would
     * reject those and then accept a white flash, which is just as useless.
     *
     * Tuned against the P1-8 corpus and retunable at any time; it is a
     * heuristic, not a format constant.
     */
    const val VARIANCE_FLOOR = 8.0

    /** Where to look for a real frame, in order. Fractions are of duration. */
    private val CANDIDATE_FRACTIONS = listOf(0.05, 0.10, 0.25)
    private val CANDIDATE_FIXED_US = listOf(0L, 1_000_000L, 2_000_000L)

    private const val TAG = "VaultThumb"

    /** What frame selection actually did, so P1-8 can record it rather than assert it. */
    class FrameChoice(
        val bitmap: Bitmap?,
        val timestampUs: Long,
        val variance: Double,
        /**
         * The container's declared length, or null if it declared none — P2-8.
         *
         * This was always being read here to pick which frames to sample; it was
         * simply thrown away at the end of [chooseFrame]. Returning it costs
         * nothing and is the only place the value is available without opening a
         * second retriever over the encrypted blob.
         */
        val durationMs: Long? = null,
    )

    // --- images -------------------------------------------------------------

    /**
     * Decodes an image from [source] and encodes a WebP thumbnail.
     *
     * Two passes: `inJustDecodeBounds` to read the dimensions, then
     * `inSampleSize` so the full-size bitmap is never allocated. A 48 MP photo
     * decoded at full size is ~190 MB of heap for something that ends up 384 px
     * on its longest edge.
     */
    fun fromImage(source: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(source, 0, source.size, opts) ?: return null
        // The EXIF angle only. `VaultItem.rotation` is applied where the tile is
        // drawn, never here: a stored turn must not send the app back to the blob
        // store to rebuild a thumbnail that is already correct for what is inside
        // the item.
        val upright = oriented(decoded, exifDegrees(source))
        return try {
            encode(scale(upright))
        } finally {
            upright.recycle()
            if (upright !== decoded) decoded.recycle()
        }
    }

    /**
     * The angle [source]'s EXIF says the photograph was taken at.
     *
     * **`BitmapFactory` does not read this and never has.** It returns the pixel
     * buffer as encoded, which for a still off any phone camera is the sensor's own
     * orientation — landscape on nearly every device, whichever way the phone was
     * held. The angle that undoes it is written into the file as EXIF `Orientation`,
     * and until this was called nothing in the app read it: `VaultCamera` set
     * `JPEG_ORIENTATION` correctly, the tag survived storage and export intact, and
     * both of the app's own decoders ignored it. A landscape photograph came back
     * standing on its side.
     *
     * The same tag arrives on imported photographs, where it is the *only* record of
     * how the picture was held — so this is not a camera fix, it is the read that was
     * missing.
     *
     * **Rotation only, not mirroring.** EXIF can also encode a flip (values 2, 4, 5
     * and 7). No camera writes one — `JPEG_ORIENTATION` cannot express it, and the
     * front lens is mirrored in the sensor geometry rather than in the tag — so
     * honouring it would mean carrying a transform for a case this app cannot
     * produce. An imported file that has one decodes unflipped.
     *
     * **Separate from [oriented] since `viewer-chrome-and-editor`**, because the
     * angle an item is drawn at is this *plus* [VaultItem.rotation] and the addition
     * belongs to the caller. A single function that read the tag and turned the
     * bitmap could not express a stored turn at all.
     */
    fun exifDegrees(source: ByteArray): Int {
        // The platform `ExifInterface`, which has no `getRotationDegrees()` — that is
        // AndroidX's, and this is four lines against a dependency.
        val tag = runCatching {
            ExifInterface(ByteArrayInputStream(source))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return when (tag) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    /**
     * [bitmap] turned [degrees] clockwise.
     *
     * Returns [bitmap] itself when there is nothing to do, so the common case
     * allocates nothing; the caller recycles both only when they differ.
     *
     * Degrees rather than a source, so one function serves the EXIF angle
     * ([exifDegrees]), the stored angle ([VaultItem.rotation]), and — which is the
     * point — their sum.
     */
    fun oriented(bitmap: Bitmap, degrees: Int): Bitmap {
        val turn = ((degrees % 360) + 360) % 360
        if (turn == 0) return bitmap

        val matrix = Matrix().apply { postRotate(turn.toFloat()) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = max(width, height)
        while (longest / 2 >= MAX_EDGE) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    // --- video --------------------------------------------------------------

    /**
     * Extracts a frame that shows the video.
     *
     * Candidates are tried in order and the first to clear [VARIANCE_FLOOR]
     * wins. If none does, the highest-variance candidate is used — a dark video
     * gets its least-dark frame rather than a placeholder.
     */
    fun chooseFrame(source: MediaDataSource): FrameChoice {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source)
            // Null when the container declares nothing. `0L` below is the
            // arithmetic fallback for choosing candidate frames; `declared` is
            // what leaves this function, and it stays null so the grid can tell
            // "no duration" from "zero-length video".
            val declared = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
            val durationUs = (declared ?: 0L) * 1000

            val candidates = (CANDIDATE_FIXED_US + CANDIDATE_FRACTIONS.map { (durationUs * it).toLong() })
                .filter { durationUs == 0L || it < durationUs }
                .distinct()
                .ifEmpty { listOf(0L) }

            var best: FrameChoice? = null
            for (us in candidates) {
                val frame = runCatching {
                    retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull() ?: continue

                val variance = lumaStdDev(frame)
                Log.i(TAG, "candidate ${us / 1000} ms -> stddev %.2f".format(variance))

                if (variance >= VARIANCE_FLOOR) {
                    best?.bitmap?.recycle()
                    return FrameChoice(frame, us, variance, declared)
                }
                if (best == null || variance > best.variance) {
                    best?.bitmap?.recycle()
                    best = FrameChoice(frame, us, variance, declared)
                } else {
                    frame.recycle()
                }
            }
            best ?: FrameChoice(null, 0L, 0.0, declared)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * How long [source] runs, or null where the container declares nothing — P2-18.
     *
     * The same retriever [chooseFrame] uses, over the same `MediaDataSource`, asked
     * only for `METADATA_KEY_DURATION`. **No frame is extracted**, which is the
     * whole point for audio: there is no picture to find, and decoding one would be
     * work in service of nothing.
     *
     * Null rather than zero, for [FrameChoice.durationMs]'s reason — the grid draws
     * no label at all for "not known", and a zero would read as a broken item.
     */
    fun durationOf(source: MediaDataSource): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        } catch (e: Exception) {
            // A length that cannot be read is not a failure to store the item: it
            // is a label the grid then does not draw.
            Log.i(TAG, "no duration: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** A thumbnail and what else the same pass learned about the item — P2-8. */
    class VideoThumb(val webp: ByteArray?, val durationMs: Long?)

    /** [chooseFrame] plus encoding. Carries the duration out; see [FrameChoice.durationMs]. */
    fun fromVideo(source: MediaDataSource): VideoThumb {
        val choice = chooseFrame(source)
        val frame = choice.bitmap ?: return VideoThumb(null, choice.durationMs)
        return try {
            VideoThumb(encode(scale(frame)), choice.durationMs)
        } finally {
            frame.recycle()
        }
    }

    /**
     * The frame at [positionUs] exactly, scaled and encoded as [fromVideo]'s is —
     * `video-frame-thumbnail`. Null when no frame comes back.
     *
     * `OPTION_CLOSEST`, not [chooseFrame]'s `OPTION_CLOSEST_SYNC`: the user chose this
     * frame, and the nearest sync frame can be seconds away from it. No
     * [VARIANCE_FLOOR] either, for the same reason — a dark frame chosen is a dark
     * frame used. The floor governs the automatic choice only.
     */
    fun fromVideoAt(source: MediaDataSource, positionUs: Long): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source)
            val frame = retriever.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return null
            try {
                encode(scale(frame))
            } finally {
                frame.recycle()
            }
        } catch (e: Exception) {
            // A codec the retriever cannot decode. Nothing is written, and the
            // automatic thumbnail stays.
            Log.i(TAG, "no frame at ${positionUs / 1000} ms: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // --- shared -------------------------------------------------------------

    /**
     * Luma standard deviation over a downsampled grid.
     *
     * Sampled rather than exhaustive: a 4K frame is 8 million pixels and the
     * answer does not improve past a few thousand samples.
     */
    fun lumaStdDev(bitmap: Bitmap, samplesPerAxis: Int = 48): Double {
        val stepX = max(1, bitmap.width / samplesPerAxis)
        val stepY = max(1, bitmap.height / samplesPerAxis)

        var n = 0L
        var sum = 0.0
        var sumSq = 0.0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                val luma = 0.2126 * ((p shr 16) and 0xFF) +
                    0.7152 * ((p shr 8) and 0xFF) +
                    0.0722 * (p and 0xFF)
                n++
                sum += luma
                sumSq += luma * luma
                x += stepX
            }
            y += stepY
        }
        if (n == 0L) return 0.0
        val mean = sum / n
        return sqrt(max(0.0, sumSq / n - mean * mean))
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toDouble() / longest
        val w = max(1, (bitmap.width * ratio).toInt())
        val h = max(1, (bitmap.height * ratio).toInt())
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        // WEBP_LOSSY is platform-native — no encoder dependency, and roughly
        // half the bytes of JPEG at the same quality.
        bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, out)
        return out.toByteArray()
    }

    /**
     * A [MediaDataSource] over a blob. This is what keeps `MediaMetadataRetriever`
     * away from the filesystem — it pulls byte ranges and they are decrypted on
     * the way past, never landing anywhere.
     */
    class BlobDataSource(
        private val reader: BlobStore.Reader,
        private val length: Long
    ) : MediaDataSource() {

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= length) return -1
            val n = min(size.toLong(), length - position).toInt()
            if (n <= 0) return -1
            reader.readRange(position, n).copyInto(buffer, offset)
            return n
        }

        override fun getSize(): Long = length

        override fun close() = reader.close()
    }
}
