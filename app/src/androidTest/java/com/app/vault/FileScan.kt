package com.app.vault

import java.io.File

/**
 * Searching private storage for plaintext, without reading a file into memory.
 *
 * The I3 scans used `File.readBytes()` until a real end-to-end run put a 331 MB
 * video in the blob store and every one of them OOMed. The bug was in the tests,
 * not the app — but a check that falls over as soon as the app holds real data
 * is a check that stops running exactly when it starts mattering.
 *
 * Chunked with a carry-over of `needle.size - 1` bytes, so a match straddling a
 * buffer boundary is still found.
 */
object FileScan {

    private const val BUFFER = 1 shl 20

    fun contains(file: File, needle: ByteArray): Boolean =
        scan(file) { buf, len -> indexOf(buf, len, needle) >= 0 }

    /**
     * A real WebP header: `RIFF`, four bytes of length, then `WEBP`. Checking
     * `RIFF` alone would match ciphertext roughly once per 4 GB scanned.
     */
    fun containsWebp(file: File): Boolean = scan(file) { buf, len ->
        var at = indexOf(buf, len, RIFF)
        while (at >= 0) {
            if (at + 12 <= len && regionMatches(buf, at + 8, WEBP)) return@scan true
            at = indexOf(buf, len, RIFF, at + 1)
        }
        false
    }

    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)

    private fun scan(file: File, test: (ByteArray, Int) -> Boolean): Boolean {
        if (!file.isFile) return false
        val carry = 16
        val buf = ByteArray(BUFFER + carry)
        var held = 0
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf, held, BUFFER)
                if (n <= 0) break
                val len = held + n
                if (test(buf, len)) return true
                held = minOf(carry, len)
                System.arraycopy(buf, len - held, buf, 0, held)
            }
        }
        return false
    }

    private fun regionMatches(buf: ByteArray, at: Int, needle: ByteArray): Boolean {
        for (j in needle.indices) if (buf[at + j] != needle[j]) return false
        return true
    }

    private fun indexOf(buf: ByteArray, len: Int, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..len - needle.size) {
            for (j in needle.indices) if (buf[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
