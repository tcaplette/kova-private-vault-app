package com.app.vault

import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.encoder.Encoder as QrEncoder
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The two directions a pairing code travels — P4-4, `FORMAT.md` §13.
 *
 * `zxing:core` is a plain Java JAR with no `AndroidManifest.xml`, which is why it
 * could be cleared against I1 with a measured delta of zero, and why everything in
 * this file runs unchanged on the JVM: the module grid and the decoder are both
 * testable without an emulator.
 *
 * **No `Bitmap` anywhere.** The obvious build renders the code into a bitmap and
 * hands it to `Image`; this returns the module grid instead and the screen draws it
 * on a `Canvas`. That is less code, no allocation per recomposition, and crisp at
 * any size — a scaled bitmap of a QR code is the one image where resampling costs
 * scans.
 *
 * ***Bytes both ways, never text*** (`share-format-v2`). The payload is a 1,216-byte
 * key as raw binary. It goes in as ISO-8859-1, which maps each byte to one character
 * and is QR byte mode's own default, so no ECI header is written; it comes out of the
 * decoder's byte segments. `Result.text` is never read: zxing guesses a character set
 * for byte-mode text, and a guess turns arbitrary bytes into something else.
 */
object PairingQr {

    /**
     * The code's modules, `true` where a module is dark.
     *
     * Error correction **M** — 15% recovery, the level every messenger and wallet
     * uses for a code held up to another phone, and the one a screen's glare is set
     * against. For a pairing payload that is version 29, 133 modules a side. No
     * quiet zone is included: the screen draws the light margin around it, because
     * the margin is layout and the grid is data.
     */
    fun matrix(payload: ByteArray): Array<BooleanArray> {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "ISO-8859-1")
        val text = String(payload, Charsets.ISO_8859_1)
        val matrix = QrEncoder.encode(text, ErrorCorrectionLevel.M, hints).matrix
            ?: error("the encoder produced no matrix for a payload it accepted")
        return Array(matrix.height) { y ->
            BooleanArray(matrix.width) { x -> matrix[x, y] == 1.toByte() }
        }
    }

    /**
     * One camera frame's luminance plane, decoded to the code's raw bytes — or null,
     * which is what most frames are.
     *
     * The bytes are the code's byte segments joined in order. A code that decodes
     * but carries no byte segment — a numeric or alphanumeric one — returns an empty
     * array rather than null: it *was* a code, it is not a pairing code, and the
     * person scanning it is owed that sentence rather than silence.
     *
     * **No rotation handling, deliberately.** A QR code's finder patterns are
     * rotation-invariant and the decoder reads a sideways frame; rotating a
     * megabyte of luminance per frame would spend the budget fixing a problem the
     * decoder does not have.
     *
     * `QRCodeReader` rather than `MultiFormatReader` with a format hint: it is the
     * same restriction expressed by construction, and one fewer lookup per frame.
     */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): ByteArray? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        // A camera plane is padded to its stride; the decoder must be told the real
        // row length or every row after the first is offset and nothing ever reads.
        if (luma.size < rowStride * (height - 1) + width) return null
        val source = PlanarYUVLuminanceSource(
            luma, rowStride, height, 0, 0, width, height, false,
        )
        val result = runCatching {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)))
        }.getOrNull() ?: return null
        @Suppress("UNCHECKED_CAST")
        val segments = result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>
        return segments?.fold(ByteArray(0)) { all, segment -> all + segment } ?: ByteArray(0)
    }
}
