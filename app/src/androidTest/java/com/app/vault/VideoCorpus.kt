package com.app.vault

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Generates the mixed-codec video corpus P1-8's Verify needs, on device.
 *
 * ByteBuffer input rather than a Surface: surface input needs EGL, and feeding
 * NV12 frames straight in avoids the whole GL stack for what is ultimately a
 * test fixture.
 *
 * A codec that is not present on the target is **recorded as absent**, not
 * skipped silently. "HEVC was not tested because the emulator has no HEVC
 * encoder" is a result; a corpus that quietly shrinks to one codec is not.
 */
object VideoCorpus {

    private const val TAG = "VaultCorpus"
    const val WIDTH = 640
    const val HEIGHT = 480
    const val FPS = 24
    private const val BITRATE = 2_000_000

    class Spec(
        val label: String,
        val mime: String,
        val container: Int,
        val extension: String,
        /** Milliseconds of pure black before real content starts. */
        val blackLeadMs: Int,
        val durationMs: Int = 3000,
    )

    val SPECS = listOf(
        Spec("h264-mp4", MediaFormat.MIMETYPE_VIDEO_AVC, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4", 0),
        Spec("h264-mp4-black-lead", MediaFormat.MIMETYPE_VIDEO_AVC, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4", 1500),
        Spec("hevc-mp4", MediaFormat.MIMETYPE_VIDEO_HEVC, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "mp4", 0),
        Spec("vp9-webm", MediaFormat.MIMETYPE_VIDEO_VP9, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "webm", 0),
        Spec("vp8-webm", MediaFormat.MIMETYPE_VIDEO_VP8, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "webm", 0),
    )

    fun encoderFor(mime: String): String? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            ?.name

    /**
     * Writes one video to [out]. Returns false when the target has no encoder
     * for [spec], which the caller records.
     */
    fun generate(
        spec: Spec,
        out: File,
        /**
         * Seconds between sync frames. `video-frame-thumbnail` sets this past the clip's
         * end, so the only sync frame is the first and an exact-frame read is
         * distinguishable from a nearest-sync one.
         */
        keyFrameIntervalS: Int = 1,
        /** The container's rotation tag, as a phone held upright writes one: 90 for portrait. */
        orientationHint: Int = 0,
        /** NV12 content for frame n. The moving bars, unless a test needs other pixels. */
        fill: (ByteBuffer, Int) -> Unit = { buf, n -> fillFrame(buf, n, spec.blackLeadMs * FPS / 1000) },
    ): Boolean {
        val encoderName = encoderFor(spec.mime) ?: run {
            Log.w(TAG, "${spec.label}: no encoder for ${spec.mime} on this target")
            return false
        }

        val codec = MediaCodec.createByCodecName(encoderName)
        val colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

        val format = MediaFormat.createVideoFormat(spec.mime, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalS)
        }

        val muxer = MediaMuxer(out.absolutePath, spec.container)
        if (orientationHint != 0) muxer.setOrientationHint(orientationHint)
        var track = -1
        var muxing = false

        return try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val totalFrames = spec.durationMs * FPS / 1000
            val info = MediaCodec.BufferInfo()
            var frame = 0

            while (true) {
                if (frame <= totalFrames) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        if (frame == totalFrames) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, ptsOf(frame),
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        } else {
                            fill(buf, frame)
                            codec.queueInputBuffer(inIndex, 0, frameSize(), ptsOf(frame), 0)
                        }
                        frame++
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                    }
                    outIndex >= 0 -> {
                        val encoded = codec.getOutputBuffer(outIndex)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                            info.size > 0 && muxing
                        ) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(track, encoded, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "${spec.label}: generation failed - ${e.message}")
            false
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxing) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    /** One flat NV12 colour — video-range [y], chroma [u] and [v]. */
    fun flat(buf: ByteBuffer, y: Int, u: Int, v: Int) {
        buf.clear()
        buf.put(ByteArray(WIDTH * HEIGHT) { y.toByte() })
        buf.put(ByteArray(WIDTH * HEIGHT / 2) { i -> (if (i % 2 == 0) u else v).toByte() })
    }

    private fun ptsOf(frame: Int) = frame * 1_000_000L / FPS

    private fun frameSize() = WIDTH * HEIGHT * 3 / 2

    /**
     * NV12: a full-size Y plane then interleaved UV at quarter resolution.
     *
     * Black lead frames are Y=16 flat (video-range black) with neutral chroma.
     * Content frames carry moving diagonal bars, which give the luma variance
     * frame selection is looking for.
     */
    private fun fillFrame(buf: ByteBuffer, frame: Int, blackFrames: Int) {
        buf.clear()
        val y = ByteArray(WIDTH * HEIGHT)
        val uv = ByteArray(WIDTH * HEIGHT / 2)

        if (frame < blackFrames) {
            y.fill(16)
            uv.fill(128.toByte())
        } else {
            val phase = frame * 7
            for (row in 0 until HEIGHT) {
                val base = row * WIDTH
                for (col in 0 until WIDTH) {
                    val band = ((col + row + phase) / 24) % 2
                    y[base + col] = if (band == 0) 235.toByte() else 16
                }
            }
            for (i in uv.indices step 2) {
                uv[i] = (128 + ((i / 2 + frame) % 60) - 30).toByte()
                uv[i + 1] = (128 - ((i / 2 + frame) % 60) + 30).toByte()
            }
        }
        buf.put(y)
        buf.put(uv)
    }
}
