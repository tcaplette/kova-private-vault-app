package com.app.vault

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretStream
import com.sun.jna.NativeLong
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import kotlin.math.roundToInt

/**
 * P0-3 — crypto spike, reduced to the library chosen in P0-4 (lazysodium).
 * Candidate (b) — argon2kt + Tink — was measured, lost, and removed; its numbers
 * are recorded in OUTLINE.md under P0-3.
 *
 * Kept past the decision because the Argon2id constant is tuned per device and
 * P1-2 has to re-run this on real hardware. Spike code; deleted at Gate 0.
 *
 * Run:  ./gradlew :app:connectedPlayDebugAndroidTest
 * Read: adb logcat -s VaultSpike:I   (the report is also written to filesDir)
 */
@RunWith(AndroidJUnit4::class)
class CryptoSpikeBenchmark {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val report = StringBuilder()

    private companion object {
        const val TAG = "VaultSpike"
        const val CHUNK = 64 * 1024
        const val M_KIB = 64 * 1024          // 64 MiB
        const val T_COST = 3
        const val TARGET_MS = 500
        const val WARMUP = 2
        const val RUNS = 5
        const val MAX_PLAINTEXT = 2L * 1024 * 1024 * 1024
    }

    @Test
    fun measureLazysodium() {
        header()

        val plaintext = generatePlaintext()
        try {
            val kdf = argon2id(T_COST)
            line("Argon2id m=64MiB t=$T_COST p=1 : ${kdf.median} ms  (runs: ${kdf.all})")
            // ponytail: libsodium's crypto_pwhash fixes parallelism at 1 lane and
            // exposes no knob, so p=4 is unreachable. Recorded, not worked around.

            val stream = secretStreamThroughput(plaintext)
            line("secretstream XChaCha20-Poly1305 @64KB : ${stream.first} MB/s enc, ${stream.second} MB/s dec")

            tune { t -> argon2id(t).median }
        } finally {
            plaintext.delete()
        }

        val out = File(ctx.filesDir, "p0-3-report.txt").apply { writeText(report.toString()) }
        Log.i(TAG, "\n$report")
        Log.i(TAG, "report written to $out")
    }

    // --- measurements ----------------------------------------------------------

    private class Timing(val all: List<Long>) {
        val median: Long get() = all.sorted()[all.size / 2]
    }

    private fun argon2id(t: Int): Timing {
        val ls = LazySodiumAndroid(SodiumAndroid())
        val salt = ByteArray(PwHash.SALTBYTES).also { SecureRandom().nextBytes(it) }
        val pw = "correct horse battery staple".toByteArray()
        val out = ByteArray(32)
        return time {
            ls.cryptoPwHash(
                out, out.size, pw, pw.size, salt,
                t.toLong(), NativeLong(M_KIB.toLong() * 1024), PwHash.Alg.PWHASH_ALG_ARGON2ID13
            )
        }
    }

    private fun secretStreamThroughput(plaintext: File): Pair<Long, Long> {
        val ls = LazySodiumAndroid(SodiumAndroid())
        val key = ByteArray(SecretStream.KEYBYTES).also { SecureRandom().nextBytes(it) }
        val cipherFile = File(ctx.filesDir, "spike-cipher.bin")

        val encMs = millis {
            val state = SecretStream.State()
            val header = ByteArray(SecretStream.HEADERBYTES)
            ls.cryptoSecretStreamInitPush(state, header, key)
            FileInputStream(plaintext).use { input ->
                FileOutputStream(cipherFile).use { output ->
                    output.write(header)
                    val buf = ByteArray(CHUNK)
                    val cipher = ByteArray(CHUNK + SecretStream.ABYTES)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        val tag =
                            if (n < CHUNK) SecretStream.TAG_FINAL else SecretStream.TAG_MESSAGE
                        ls.cryptoSecretStreamPush(state, cipher, null, buf, n.toLong(), tag)
                        output.write(cipher, 0, n + SecretStream.ABYTES)
                    }
                }
            }
        }

        val decMs = millis {
            val state = SecretStream.State()
            FileInputStream(cipherFile).use { input ->
                val header = ByteArray(SecretStream.HEADERBYTES)
                input.read(header)
                ls.cryptoSecretStreamInitPull(state, header, key)
                val cipher = ByteArray(CHUNK + SecretStream.ABYTES)
                val msg = ByteArray(CHUNK)
                val tag = ByteArray(1)
                while (true) {
                    val n = input.read(cipher)
                    if (n <= 0) break
                    ls.cryptoSecretStreamPull(state, msg, tag, cipher, n.toLong())
                }
            }
        }

        cipherFile.delete()
        return mbPerSec(plaintext.length(), encMs) to mbPerSec(plaintext.length(), decMs)
    }

    /**
     * Scale t toward the target, re-measuring each round. A single scale from one
     * baseline is not enough: run-to-run spread here is wide enough that the first
     * estimate lands nowhere near the target.
     */
    private fun tune(measure: (Int) -> Long) {
        val low = (TARGET_MS * 0.85).toLong()
        val high = (TARGET_MS * 1.15).toLong()
        var t = T_COST
        var ms = measure(t)
        val trail = mutableListOf("t=$t:${ms}ms")

        repeat(6) {
            if (ms in low..high) return@repeat
            val next = ((t * TARGET_MS) / ms.toDouble()).roundToInt().coerceAtLeast(1)
            if (next == t) return@repeat
            t = next
            ms = measure(t)
            trail += "t=$t:${ms}ms"
        }
        line("tuned: m=64MiB t=$t p=1 -> $ms ms (target $TARGET_MS ms) via ${trail.joinToString(" -> ")}")
    }

    // --- plumbing --------------------------------------------------------------

    private fun generatePlaintext(): File {
        val usable = ctx.filesDir.usableSpace
        // plaintext + ciphertext + headroom
        val size = minOf(MAX_PLAINTEXT, usable / 3)
        line("usable space in filesDir: ${usable / 1024 / 1024} MB")
        line("plaintext test file: ${size / 1024 / 1024} MB")

        val f = File(ctx.filesDir, "spike-plain.bin")
        val rng = SecureRandom()
        val buf = ByteArray(1 shl 20)
        FileOutputStream(f).use { out ->
            var written = 0L
            while (written < size) {
                rng.nextBytes(buf)
                val n = minOf(buf.size.toLong(), size - written).toInt()
                out.write(buf, 0, n)
                written += n
            }
        }
        return f
    }

    private inline fun time(block: () -> Unit): Timing {
        repeat(WARMUP) { block() }
        return Timing((1..RUNS).map { millis(block) })
    }

    private inline fun millis(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun mbPerSec(bytes: Long, ms: Long): Long =
        if (ms == 0L) -1 else (bytes / 1024 / 1024) * 1000 / ms

    private fun header() {
        line("=== P0-3 crypto spike (lazysodium) ===")
        line("device: ${Build.MANUFACTURER} ${Build.MODEL}")
        line("soc: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}  (${Build.SUPPORTED_ABIS.joinToString()})")
        line("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        line("emulator: ${Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk", true)}")
        line("chunk: ${CHUNK / 1024} KB")
        line("")
    }

    private fun line(s: String) {
        report.appendLine(s)
    }
}
