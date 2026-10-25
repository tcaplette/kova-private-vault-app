package com.app.vault

import com.app.vault.ExifPolicy.Additions
import com.app.vault.ExifPolicy.Claim

/**
 * The EXIF block, neutralised in place — `exif-strip-choice`, design D2 and D3.
 *
 * ***Read by hand, never rewritten.*** The TIFF structure is walked — IFD0, the Exif, GPS and
 * Interop IFDs — and a keep map is built of the bytes the kept entries own. Removed entries are
 * dropped by compacting the kept ones within the same table, and then every byte outside the
 * keep map is zeroed. Nothing kept moves, so a kept value is byte-identical where it was, a kept
 * maker note's own offsets still resolve, and the block keeps its length — which is what lets
 * everything after it stay put.
 *
 * **Zeroing by keep map is the allowlist at the byte level.** Data owned by an entry the app does
 * not understand, or orphaned under a sub-IFD it does not know, is zeroed because nothing kept
 * claims it — not because the app found it and named it.
 *
 * **Adding moves nothing either.** A tag that exists is patched where it is; a tag that does not is
 * added by writing a new copy of its table — into space the neutraliser zeroed if a run is long
 * enough, at the end of the block otherwise — and repointing the one offset that named the old one.
 */
internal object ExifTiff {

    class Result(val tiff: ByteArray, val changed: Boolean, val additionsSkipped: Boolean)

    /** A block that cannot be trusted. The caller removes it, or zeroes it where length matters. */
    class Bad(message: String) : Exception(message)

    private enum class Kind { IFD0, EXIF, GPS, INTEROP }

    private fun claimOf(kind: Kind, tag: Int): Claim = when (kind) {
        Kind.IFD0 -> ExifPolicy.ifd0(tag)
        Kind.EXIF -> ExifPolicy.exifIfd(tag)
        Kind.GPS -> ExifPolicy.gpsIfd(tag)
        Kind.INTEROP -> ExifPolicy.interop(tag)
    }

    private fun subOf(kind: Kind, tag: Int): Kind? = when {
        kind == Kind.IFD0 && tag == ExifPolicy.TAG_EXIF_IFD -> Kind.EXIF
        kind == Kind.IFD0 && tag == ExifPolicy.TAG_GPS_IFD -> Kind.GPS
        kind == Kind.EXIF && tag == ExifPolicy.TAG_INTEROP_IFD -> Kind.INTEROP
        else -> null
    }

    private class Order(val little: Boolean) {
        fun u16(b: ByteArray, o: Int): Int {
            val x = b[o].toInt() and 0xFF
            val y = b[o + 1].toInt() and 0xFF
            return if (little) x or (y shl 8) else (x shl 8) or y
        }

        fun u32(b: ByteArray, o: Int): Long {
            val a = u16(b, o).toLong()
            val c = u16(b, o + 2).toLong()
            return if (little) a or (c shl 16) else (a shl 16) or c
        }

        fun put16(b: ByteArray, o: Int, v: Int) {
            if (little) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
            else { b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte() }
        }

        fun put32(b: ByteArray, o: Int, v: Long) {
            if (little) { put16(b, o, (v and 0xFFFF).toInt()); put16(b, o + 2, (v shr 16).toInt()) }
            else { put16(b, o, (v shr 16).toInt()); put16(b, o + 2, (v and 0xFFFF).toInt()) }
        }

        fun bytes16(v: Int) = ByteArray(2).also { put16(it, 0, v) }
        fun bytes32(v: Long) = ByteArray(4).also { put32(it, 0, v) }
    }

    private fun typeSize(type: Int): Int = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11, 13 -> 4
        5, 10, 12 -> 8
        else -> 0
    }

    private class Entry(val at: Int, val tag: Int, val type: Int, val count: Long) {
        val size: Long get() = typeSize(type).toLong() * count
    }

    private fun orderOf(b: ByteArray): Order {
        if (b.size < 8) throw Bad("shorter than a TIFF header")
        val order = when {
            b[0] == 'I'.code.toByte() && b[1] == 'I'.code.toByte() -> Order(true)
            b[0] == 'M'.code.toByte() && b[1] == 'M'.code.toByte() -> Order(false)
            else -> throw Bad("no byte order")
        }
        if (order.u16(b, 2) != 42) throw Bad("not TIFF")
        return order
    }

    private fun readIfd(b: ByteArray, o: Order, off: Long): List<Entry> {
        if (off < 8 || off + 2 > b.size) throw Bad("IFD outside the block")
        val at = off.toInt()
        val n = o.u16(b, at)
        if (at + 2L + 12L * n + 4 > b.size) throw Bad("IFD table outside the block")
        return List(n) { i ->
            val e = at + 2 + 12 * i
            Entry(e, o.u16(b, e), o.u16(b, e + 2), o.u32(b, e + 4))
        }
    }

    /** Where an out-of-line value lives, or null when it is inline or does not fit the block. */
    private fun outOfLine(b: ByteArray, o: Order, e: Entry): LongRange? {
        val off = o.u32(b, e.at + 8)
        if (off < 8 || off + e.size > b.size) return null
        return off until off + e.size
    }

    /**
     * [src] under [removed], then [additions]. [removed] null leaves every entry alone.
     *
     * Throws [Bad] when a strip meets a block it cannot trust. A pass that only adds never
     * throws: a block it cannot read is left as it was, and the additions are reported skipped.
     */
    fun edit(
        src: ByteArray,
        removed: Set<PhotoDetail>?,
        additions: Additions?,
        allowGrowth: Boolean,
        maxLength: Int,
    ): Result {
        val order = try {
            orderOf(src)
        } catch (e: Bad) {
            if (removed != null) throw e
            return Result(src, false, additions?.isEmpty == false)
        }
        var t = src.copyOf()
        val keep = if (removed != null) neutralise(src, t, order, removed) else BooleanArray(t.size) { true }

        var skipped = false
        if (additions != null && !additions.isEmpty) {
            // A copy, so an addition that cannot fit leaves the neutralised block as it was.
            val block = Block(t.copyOf(), keep.copyOf(), allowGrowth)
            val added = runCatching { add(block, order, removed, additions) }.getOrDefault(false)
            if (added && block.b.size <= maxLength) t = block.b else skipped = !added || block.b.size > maxLength
        }
        return Result(t, !t.contentEquals(src), skipped)
    }

    /** A minimal block for a file that has none, or null when there is nothing to add. */
    fun fresh(removed: Set<PhotoDetail>?, additions: Additions): ByteArray? {
        val stamp = additions.dateStamp?.takeIf { removed == null || PhotoDetail.DATE !in removed }
        val turn = additions.orientation?.invoke(1)?.takeIf { it != 1 }
        if (stamp == null && turn == null) return null

        val o = Order(true)
        val header = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00)
        val block = Block(header, BooleanArray(header.size) { true }, allowAppend = true)
        val ifd0 = mutableListOf<New>()
        if (turn != null) ifd0 += New(ExifPolicy.TAG_ORIENTATION, 3, 1, o.bytes16(turn))
        if (stamp != null) {
            val exif = writeIfd(block, o, emptyList(), listOf(dateEntry(ExifPolicy.TAG_DATETIME_ORIGINAL, stamp)))!!
            ifd0 += dateEntry(ExifPolicy.TAG_DATETIME, stamp)
            ifd0 += New(ExifPolicy.TAG_EXIF_IFD, 4, 1, o.bytes32(exif))
        }
        // Computed first: writing the table can grow the block, and `block.b` read before the
        // call would be the array it grew out of.
        val ifd0At = writeIfd(block, o, emptyList(), ifd0)!!
        o.put32(block.b, 4, ifd0At)
        return block.b
    }

    // ── The strip ─────────────────────────────────────────────────────────────

    private fun neutralise(src: ByteArray, t: ByteArray, o: Order, removed: Set<PhotoDetail>): BooleanArray {
        val keep = BooleanArray(src.size)
        fun mark(from: Long, length: Long) {
            for (i in from until minOf(from + length, src.size.toLong())) keep[i.toInt()] = true
        }
        mark(0, 8)
        val visited = HashSet<Long>()

        fun walk(off: Long, kind: Kind) {
            if (!visited.add(off)) throw Bad("IFD chain loops")
            val entries = readIfd(src, o, off)
            val kept = ArrayList<Entry>(entries.size)
            for (e in entries) {
                if (!ExifPolicy.survives(claimOf(kind, e.tag), removed)) continue
                if (typeSize(e.type) == 0) continue
                val value = if (e.size > 4) outOfLine(src, o, e) ?: continue else null
                val sub = subOf(kind, e.tag)
                if (sub != null) {
                    if ((e.type != 4 && e.type != 13) || e.count != 1L) continue
                    // A sub-IFD that cannot be read loses its pointer, not the whole block.
                    if (runCatching { walk(o.u32(src, e.at + 8), sub) }.isFailure) continue
                }
                kept += e
                value?.let { mark(it.first, it.last - it.first + 1) }
            }
            val at = off.toInt()
            o.put16(t, at, kept.size)
            kept.forEachIndexed { i, e -> System.arraycopy(src, e.at, t, at + 2 + 12 * i, 12) }
            // IFD1 is the thumbnail, and a thumbnail can still show what an edit took out.
            o.put32(t, at + 2 + 12 * kept.size, 0)
            mark(off, 2L + 12L * kept.size + 4)
        }

        walk(o.u32(src, 4), Kind.IFD0)
        for (i in t.indices) if (!keep[i]) t[i] = 0
        return keep
    }

    // ── The additions ─────────────────────────────────────────────────────────

    private class New(val tag: Int, val type: Int, val count: Long, val value: ByteArray)

    private fun dateEntry(tag: Int, stamp: String) =
        New(tag, 2, (stamp.length + 1).toLong(), stamp.toByteArray(Charsets.US_ASCII) + 0)

    /** The TIFF block being added to, and which of its bytes are spoken for. */
    private class Block(var b: ByteArray, var keep: BooleanArray, val allowAppend: Boolean) {

        /** A word-aligned run of [size] free bytes, reusing zeroed space before growing. */
        fun alloc(size: Int): Int? {
            var run = -1
            for (i in 8 until b.size) {
                if (keep[i]) { run = -1; continue }
                if (run < 0) run = i
                val start = if (run % 2 == 0) run else run + 1
                if (i - start + 1 >= size) return start.also { claim(it, size) }
            }
            if (!allowAppend) return null
            val start = (b.size + 1) and 1.inv()
            b = b.copyOf(start + size)
            keep = keep.copyOf(start + size)
            claim(start, size)
            return start
        }

        fun claim(from: Int, size: Int) {
            for (i in from until from + size) keep[i] = true
        }

        fun release(from: Int, size: Int) {
            for (i in from until from + size) { b[i] = 0; keep[i] = false }
        }
    }

    /** A new table holding [existing] plus [adds], in tag order. Returns its offset, or null if it will not fit. */
    private fun writeIfd(block: Block, o: Order, existing: List<Entry>, adds: List<New>): Long? {
        val raw = existing.map { it.tag to block.b.copyOfRange(it.at, it.at + 12) }.toMutableList()
        for (a in adds) {
            val entry = ByteArray(12)
            o.put16(entry, 0, a.tag)
            o.put16(entry, 2, a.type)
            o.put32(entry, 4, a.count)
            if (a.value.size <= 4) {
                a.value.copyInto(entry, 8)
            } else {
                val at = block.alloc(a.value.size) ?: return null
                a.value.copyInto(block.b, at)
                o.put32(entry, 8, at.toLong())
            }
            raw += a.tag to entry
        }
        raw.sortBy { it.first }
        val size = 2 + 12 * raw.size + 4
        val at = block.alloc(size) ?: return null
        o.put16(block.b, at, raw.size)
        raw.forEachIndexed { i, (_, e) -> e.copyInto(block.b, at + 2 + 12 * i) }
        o.put32(block.b, at + 2 + 12 * raw.size, 0)
        return at.toLong()
    }

    /** Applies [additions] to [block]. False when one would not fit, in which case the caller keeps the block from before. */
    private fun add(block: Block, o: Order, removed: Set<PhotoDetail>?, additions: Additions): Boolean {
        val ifd0Off = o.u32(block.b, 4)
        val ifd0 = readIfd(block.b, o, ifd0Off)
        val ifd0Adds = mutableListOf<New>()

        additions.orientation?.let { turn ->
            val existing = ifd0.firstOrNull { it.tag == ExifPolicy.TAG_ORIENTATION }
            if (existing == null) {
                turn(1).takeIf { it != 1 }?.let { ifd0Adds += New(ExifPolicy.TAG_ORIENTATION, 3, 1, o.bytes16(it)) }
            } else if (existing.type == 3 && existing.count == 1L) {
                o.put16(block.b, existing.at + 8, turn(o.u16(block.b, existing.at + 8)))
            }
        }

        val stamp = additions.dateStamp?.takeIf { removed == null || PhotoDetail.DATE !in removed }
        if (stamp != null) {
            val pointer = ifd0.firstOrNull { it.tag == ExifPolicy.TAG_EXIF_IFD && it.count == 1L }
            val exifOff = pointer?.let { o.u32(block.b, it.at + 8) }
            val exif = exifOff?.let { runCatching { readIfd(block.b, o, it) }.getOrNull() }
            if (exif == null || exif.none { it.tag == ExifPolicy.TAG_DATETIME_ORIGINAL }) {
                val moved = writeIfd(block, o, exif.orEmpty(), listOf(dateEntry(ExifPolicy.TAG_DATETIME_ORIGINAL, stamp)))
                    ?: return false
                if (exif != null) block.release(exifOff.toInt(), 2 + 12 * exif.size + 4)
                if (pointer != null) o.put32(block.b, pointer.at + 8, moved)
                else ifd0Adds += New(ExifPolicy.TAG_EXIF_IFD, 4, 1, o.bytes32(moved))
                if (ifd0.none { it.tag == ExifPolicy.TAG_DATETIME }) ifd0Adds += dateEntry(ExifPolicy.TAG_DATETIME, stamp)
            }
        }

        if (ifd0Adds.isNotEmpty()) {
            val moved = writeIfd(block, o, ifd0, ifd0Adds) ?: return false
            block.release(ifd0Off.toInt(), 2 + 12 * ifd0.size + 4)
            o.put32(block.b, 4, moved)
        }
        return true
    }
}
