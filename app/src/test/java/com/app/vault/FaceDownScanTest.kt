package com.app.vault

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The face-down decision stays free of the device — P2-20.
 *
 * `FaceDown.kt` holds the threshold, the stillness band, the hold and the latch,
 * and `FaceDownTest` drives all four from synthetic streams. That only stays
 * possible while the file imports no Android type: one `SensorManager` constant or
 * one `SystemClock` call pulled in for convenience makes the whole of P2-20's
 * hardest Verify clause — "a pocket-shaped false positive does not fire" —
 * emulator-only, and it would go back to being checked once.
 *
 * No logging either. Nothing about the pose of a user's phone belongs in logcat,
 * and the listener's own single line is enough to see the trigger work.
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class FaceDownScanTest {

    private fun sourceDir(): File {
        var dir: File? = File(".").canonicalFile
        val rel = "src/main/java/com/app/vault"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the source package from ${File(".").absolutePath}")
    }

    private fun stripped(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    private fun decision(): String {
        val file = File(sourceDir(), "FaceDown.kt")
        assertTrue("FaceDown.kt is missing; update this test", file.isFile)
        return stripped(file.readText())
    }

    @Test
    fun theDecisionImportsNoAndroidType() {
        val source = decision()

        assertTrue(
            "FaceDown.kt imports an Android type — see this test's KDoc",
            !source.contains("import android."),
        )
        assertTrue(
            "FaceDown.kt names an Android type without importing it",
            !Regex("""\bandroid\.\w""").containsMatchIn(source),
        )
    }

    @Test
    fun theDecisionLogsNothing() {
        val source = decision()

        assertTrue(
            "FaceDown.kt writes a log line",
            !Regex("""\bLog\.[dviwe]\(""").containsMatchIn(source),
        )
        assertTrue("FaceDown.kt prints", !Regex("""\bprintln\(""").containsMatchIn(source))
    }
}
