package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app ships no photographs — P2-2, as a build failure.
 *
 * A bundled sample pack is the worst option available for a decoy: identical bytes
 * on every install, so anyone who has downloaded the app can prove a vault is a
 * decoy by hashing its contents — and P3-1 publishes the crypto core's source, so
 * "anyone" is the right word. The items in a decoy are the user's own photographs,
 * because those are the only plausible ones, and this asserts the alternative never
 * quietly arrives.
 *
 * JVM scope: it reads the source tree, which is what a release package is built
 * from.
 */
class ShippedContentScanTest {

    private companion object {
        /** What a photograph or a video arrives as. Not fonts, not vector icons, not text. */
        val CONTENT_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif",
            "mp4", "mov", "3gp", "mkv", "webm", "avi",
        )

        /**
         * Launcher iconography is the one raster-shaped exception any app has, and
         * this app currently has none — the launcher icon is a vector. If one is
         * ever added, it belongs here by name rather than by loosening the rule.
         */
        val PERMITTED = setOf<String>()
    }

    private fun moduleRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            listOf(File(dir, "src/main"), File(dir, "app/src/main")).firstOrNull { it.isDirectory }
                ?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate src/main from ${File(".").absolutePath}")
    }

    @Test
    fun noImageOrVideoAssetShipsWithTheApp() {
        val main = moduleRoot()
        assertTrue("scanned nothing", main.walkTopDown().count() > 5)

        val content = main.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension.lowercase() in CONTENT_EXTENSIONS }
            .filter { it.name !in PERMITTED }
            .map { it.relativeTo(main).path }
            .toList()

        assertEquals(
            "a decoy is made of the user's own photographs. Content that shipped with the app " +
                "would be byte-identical on every install, so a decoy built from it could be " +
                "proven by hash. Found: $content",
            emptyList<String>(), content
        )
    }

    @Test
    fun theScanWouldCatchOne() {
        // The negative proof: the rule is only worth having if it fires.
        assertTrue("jpg is content", "holiday.jpg".substringAfterLast('.') in CONTENT_EXTENSIONS)
        assertTrue("mp4 is content", "clip.mp4".substringAfterLast('.') in CONTENT_EXTENSIONS)
        assertTrue("a vector icon is not", "ic_play_circle.xml".substringAfterLast('.') !in CONTENT_EXTENSIONS)
        assertTrue("a font is not", "inter_variable.ttf".substringAfterLast('.') !in CONTENT_EXTENSIONS)
        assertTrue("a wordlist is not", "bip39_english.txt".substringAfterLast('.') !in CONTENT_EXTENSIONS)
    }
}
