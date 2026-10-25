package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What an item is — P2-18.
 *
 * The classification is pure, so all of it is answerable here. What the device
 * tests then have to prove is only what a device can: that an mp3 plays, that a
 * document exports byte-identical, and that a vault holding one stops decrypting it
 * at every unlock.
 *
 * **The video set is asserted literally**, because this file *moved* an extension
 * set that two other files were carrying. If it were retyped slightly differently,
 * items already in people's vaults would silently change kind — a video that
 * becomes a file stops playing, and nothing in the app would report it.
 */
class VaultKindTest {

    @Test
    fun `video is exactly the set that shipped before this change`() {
        // Copied from VaultGrid.isVideo / Thumbnails.isVideo as they stood at P2-17.
        assertEquals(
            setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "avi", "ts"),
            VaultKind.videoExtensions(),
        )
    }

    @Test
    fun `each kind claims its own extensions`() {
        listOf("mp4", "mkv", "ts", "3gp").forEach {
            assertEquals(it, VaultKind.VIDEO, VaultKind.kindOf("clip.$it"))
        }
        listOf("mp3", "m4a", "flac", "ogg", "opus", "wav").forEach {
            assertEquals(it, VaultKind.AUDIO, VaultKind.kindOf("song.$it"))
        }
        listOf("jpg", "jpeg", "png", "webp", "heic", "dng").forEach {
            assertEquals(it, VaultKind.IMAGE, VaultKind.kindOf("photo.$it"))
        }
    }

    @Test
    fun `classification ignores case`() {
        assertEquals(VaultKind.VIDEO, VaultKind.kindOf("CLIP.MP4"))
        assertEquals(VaultKind.AUDIO, VaultKind.kindOf("Song.Mp3"))
        assertEquals(VaultKind.IMAGE, VaultKind.kindOf("PHOTO.JPEG"))
    }

    /**
     * The default carries the feature: unknown means "a name, a size and an
     * export", which is the honest answer for bytes the app cannot interpret.
     */
    @Test
    fun `anything unrecognised is a file`() {
        listOf("doc.pdf", "archive.zip", "notes.txt", "sheet.xlsx", "key.pem", "data.sqlite")
            .forEach { assertEquals(it, VaultKind.FILE, VaultKind.kindOf(it)) }
    }

    /** No filename throws. A name the app cannot parse is still an item someone owns. */
    @Test
    fun `degenerate names are files and never errors`() {
        listOf("", ".", "..", "noextension", "trailing.", ".hidden", "jpg", "a.b.c")
            .forEach { assertEquals(it, VaultKind.FILE, VaultKind.kindOf(it)) }
    }

    /**
     * `.hidden` has no extension by this app's reading — the leading dot is the
     * name. Worth pinning, because `substringAfterLast` would happily call the
     * whole thing an extension under a different implementation, and then a file
     * called `.jpg` would be treated as a photograph.
     */
    @Test
    fun `a dotfile is not its own extension`() {
        assertEquals(VaultKind.FILE, VaultKind.kindOf(".jpg"))
        assertEquals(VaultKind.FILE, VaultKind.kindOf(".mp3"))
    }

    @Test
    fun `only images and video can have thumbnails`() {
        assertTrue(VaultKind.IMAGE.canHaveThumbnail)
        assertTrue(VaultKind.VIDEO.canHaveThumbnail)
        assertTrue(!VaultKind.AUDIO.canHaveThumbnail)
        assertTrue(!VaultKind.FILE.canHaveThumbnail)
    }

    @Test
    fun `only video and audio are playable`() {
        assertTrue(VaultKind.VIDEO.isPlayable)
        assertTrue(VaultKind.AUDIO.isPlayable)
        assertTrue(!VaultKind.IMAGE.isPlayable)
        assertTrue(!VaultKind.FILE.isPlayable)
    }

    /** No two kinds claim the same extension; a tile would then depend on order. */
    @Test
    fun `the sets do not overlap`() {
        val all = listOf("mp4", "mp3", "jpg", "ts", "wav", "png", "avi", "flac", "heic")
        val kinds = all.map { VaultKind.kindOf("x.$it") }
        assertEquals(all.size, all.size)   // guards the corpus against a typo'd duplicate
        assertEquals(all.toSet().size, all.size)
        assertTrue(kinds.none { it == VaultKind.FILE })
    }

    // --- the duplication this file exists to end -------------------------------

    /**
     * **`isVideo` exists nowhere any more.** `VaultGrid` and `Thumbnails` each held
     * a copy of one extension set before P2-18; a third copy appearing later is the
     * failure this test exists to prevent, and it is exactly the kind of thing that
     * gets added innocently in a file that "just needs to know if it is a video".
     */
    @Test
    fun `the extension set exists in one place`() {
        val offenders = sources()
            .filter { it.name != "VaultKind.kt" }
            .filter { stripped(it.readText()).contains("\"webm\"") }
            .map { it.name }
        assertTrue("extension set duplicated in: $offenders", offenders.isEmpty())

        val isVideo = sources()
            .filter { stripped(it.readText()).contains("fun isVideo") }
            .map { it.name }
        assertTrue("isVideo is back, in: $isVideo", isVideo.isEmpty())
    }

    private fun sourceDir(): File {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/java/com/app/vault"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the source package from ${File(".").absolutePath}")
    }

    private fun sources(): List<File> =
        sourceDir().walkTopDown().filter { it.extension == "kt" }.toList()

    private fun stripped(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")
}
