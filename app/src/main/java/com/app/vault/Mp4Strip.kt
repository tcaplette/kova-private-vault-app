package com.app.vault

import com.app.vault.ExifPolicy.Claim
import com.app.vault.ExifPolicy.In
import com.app.vault.ExifPolicy.Kept
import com.app.vault.ExifPolicy.Unclaimed

/**
 * A motion photo's video, its metadata neutralised in place — `exif-strip-choice`, design D7.
 *
 * `MediaMuxer.setLocation` stores coordinates "in udta box if the output format is
 * MUXER_OUTPUT_MPEG_4" (platform javadoc), so a video an Android camera muxes with location
 * carries them — and a motion photo's video is such a video, appended after the photograph.
 *
 * ***Walked as a stream, and nothing moves.*** The walker descends into the boxes that can hold
 * metadata and copies everything else — `mdat` above all — without holding it. A metadata leaf
 * claimed by a removed category, or by nothing, is neutralised where it is: its type becomes
 * `free`, which every MP4 reader skips, and its body is zeroed. Its size is unchanged, so no
 * offset in the file, and no length in the photograph's description of it, is disturbed.
 */
internal object Mp4Strip {

    class Bad(message: String) : Exception(message)

    private val CONTAINERS = setOf("moov", "trak", "mdia", "udta", "meta", "ilst")
    private val TIMES = setOf("mvhd", "tkhd", "mdhd")
    private val METADATA = setOf("udta", "meta", "ilst")
    private const val TOP = ""
    private const val HELD_CEILING = 64 * 1024

    private val location = In(PhotoDetail.LOCATION)
    private val camera = In(PhotoDetail.CAMERA)
    private val date = In(PhotoDetail.DATE)
    private val software = In(PhotoDetail.SOFTWARE)

    /** A `udta`/iTunes-style leaf by its four-character type. */
    internal fun leafClaim(type: String): Claim = when (type) {
        "©xyz" -> location
        "©mak", "©mod" -> camera
        "©day" -> date
        "©too", "©swr", "©enc" -> software
        "free", "skip", "hdlr" -> Kept
        else -> Unclaimed
    }

    /** A `meta/keys` entry by its reverse-DNS name. */
    internal fun keyClaim(name: String): Claim {
        val n = name.lowercase()
        return when {
            "location" in n -> location
            "creationdate" in n -> date
            "make" in n || "model" in n || "manufacturer" in n || "capture.fps" in n -> camera
            "software" in n || "com.android.version" in n || "encoder" in n -> software
            else -> Unclaimed
        }
    }

    /**
     * Copies one MP4 of [length] bytes, size for size. Returns whether anything changed.
     *
     * A box that overruns its parent stops the walk, and the rest of the item is zeroed: a
     * structure the walker cannot follow cannot be shown to carry no location.
     */
    fun copy(src: StripIn, out: StripOut, length: Long, removed: Set<PhotoDetail>): Boolean {
        val end = src.pos + length
        val walk = Walk(src, out, removed)
        try {
            walk.boxes(end, TOP)
        } catch (_: Bad) {
            walk.changed = true
            src.zeroTo(out, end - src.pos)
        }
        return walk.changed
    }

    private class Walk(val src: StripIn, val out: StripOut, val removed: Set<PhotoDetail>) {
        var changed = false

        /** The `keys` indices whose items this strip empties. */
        var removedKeys: Set<Int> = emptySet()

        fun boxes(end: Long, parent: String) {
            while (src.pos < end) {
                val start = src.pos
                if (end - start < 8) throw Bad("a box header runs past its parent")
                var header = src.bytes(8) ?: throw Bad("truncated")
                val size32 = be32(header, 0)
                val size = when (size32) {
                    1L -> {
                        val ext = src.bytes(8) ?: throw Bad("truncated")
                        header += ext
                        be64(ext, 0)
                    }
                    0L -> end - start
                    else -> size32
                }
                if (size < header.size || start + size > end) throw Bad("a box overruns its parent")
                val body = size - header.size
                val type = String(header, 4, 4, Charsets.ISO_8859_1)

                when {
                    type in CONTAINERS -> {
                        out.write(header)
                        if (type == "meta") metaPrelude()
                        boxes(start + size, type)
                    }
                    type in TIMES -> times(header, body)
                    parent == "meta" && type == "keys" -> keys(header, body)
                    // ***An `ilst` item keeps its type.*** A reader maps each item to a key by
                    // that type, and the platform's extractor treats an item it cannot map as a
                    // malformed file and stops before it reaches the tracks — measured: retyping
                    // one to `free` left a motion photo's video with no track at all. So the
                    // item's value is zeroed where it is, and a keyed item's name in `keys` too.
                    parent == "ilst" -> {
                        val gone = if (header[4] != 0xA9.toByte()) {
                            be32(header, 4).toInt() in removedKeys
                        } else {
                            !ExifPolicy.survives(leafClaim(type), removed)
                        }
                        if (gone) {
                            out.write(header)
                            zeroValues(start + size)
                        } else {
                            copy(header, body)
                        }
                    }
                    parent in METADATA -> {
                        if (ExifPolicy.survives(leafClaim(type), removed)) copy(header, body) else neutralise(header, body)
                    }
                    type == "uuid" && (parent == TOP || parent == "moov" || parent == "trak") ->
                        neutralise(header, body)
                    else -> copy(header, body)
                }
            }
        }

        /** An ISO `meta` is a full box with four bytes of version and flags; a QuickTime one is not. */
        fun metaPrelude() {
            val peek = src.peek(8) ?: return
            val qt = String(peek, 4, 4, Charsets.ISO_8859_1) == "hdlr"
            if (!qt) out.write(src.bytes(4) ?: throw Bad("truncated"))
        }

        fun copy(header: ByteArray, body: Long) {
            out.write(header)
            if (src.copyTo(out, body) < body) throw Bad("truncated")
        }

        fun neutralise(header: ByteArray, body: Long) {
            val retyped = header.copyOf()
            "free".toByteArray(Charsets.US_ASCII).copyInto(retyped, 4)
            out.write(retyped)
            if (src.zeroTo(out, body) < body) throw Bad("truncated")
            changed = true
        }

        fun times(header: ByteArray, body: Long) {
            if (body > HELD_CEILING) throw Bad("an implausible header box")
            val b = src.bytes(body.toInt()) ?: throw Bad("truncated")
            if (PhotoDetail.DATE in removed && b.isNotEmpty()) {
                val span = if (b[0].toInt() == 1) 4 until 20 else 4 until 12
                for (i in span) if (i < b.size && b[i] != 0.toByte()) { b[i] = 0; changed = true }
            }
            out.write(header)
            out.write(b)
        }

        /**
         * The key names, read before the items they index. A key this strip removes has its
         * name zeroed where it is, so a reader that still maps the index finds nothing it knows.
         */
        fun keys(header: ByteArray, body: Long) {
            if (body > HELD_CEILING) throw Bad("an implausible keys box")
            val b = src.bytes(body.toInt()) ?: throw Bad("truncated")
            val gone = HashSet<Int>()
            if (b.size >= 8) {
                val count = be32(b, 4)
                var at = 8
                var index = 1
                while (index <= count && at + 8 <= b.size) {
                    val keySize = be32(b, at).toInt()
                    if (keySize < 8 || at + keySize > b.size) break
                    val name = String(b, at + 8, keySize - 8, Charsets.UTF_8)
                    if (!ExifPolicy.survives(keyClaim(name), removed)) {
                        gone += index
                        for (i in at + 8 until at + keySize) b[i] = 0
                        changed = true
                    }
                    at += keySize
                    index++
                }
            }
            removedKeys = gone
            out.write(header)
            out.write(b)
        }

        /**
         * An item's value zeroed and its structure kept: each child box keeps its header, a
         * `data` box keeps its type and locale, and every byte of value becomes zero.
         */
        fun zeroValues(end: Long) {
            changed = true
            while (src.pos < end) {
                if (end - src.pos < 8) {
                    src.zeroTo(out, end - src.pos)
                    return
                }
                val head = src.bytes(8) ?: throw Bad("truncated")
                val size = be32(head, 0)
                if (size < 8 || src.pos - 8 + size > end) {
                    out.zeros(8)
                    src.zeroTo(out, end - src.pos)
                    return
                }
                out.write(head)
                var body = size - 8
                if (String(head, 4, 4, Charsets.ISO_8859_1) == "data" && body >= 8) {
                    if (src.copyTo(out, 8) < 8) throw Bad("truncated")
                    body -= 8
                }
                if (src.zeroTo(out, body) < body) throw Bad("truncated")
            }
        }
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun be64(b: ByteArray, o: Int): Long = (be32(b, o) shl 32) or be32(b, o + 4)
}
