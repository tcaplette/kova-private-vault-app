package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One file reaches BouncyCastle — `share-format-v2`, `crypto-primitives`.
 *
 * The filtered dependency keeps only what `XWing.kt` loads. A second file importing
 * `org.bouncycastle` would either reach a class the filter dropped — a crash on the
 * device, not in a JVM test — or widen the filter, which is the road back to a second
 * Argon2id or AEAD in the APK. Either way it starts here.
 *
 * JVM scope: it reads source.
 */
class XWingSourceScanTest {

    private fun mainSources(): List<File> {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/java/com/app/vault"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { d -> return d.walkTopDown().filter { it.extension == "kt" }.toList() }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the source package from ${File(".").absolutePath}")
    }

    @Test
    fun xWingIsTheOnlyFileThatImportsBouncyCastle() {
        val sources = mainSources()
        assertTrue("the scan must read something", sources.size > 50)
        val importing = sources
            .filter { f -> f.readLines().any { it.trimStart().startsWith("import org.bouncycastle") } }
            .map { it.name }
        assertEquals(listOf("XWing.kt"), importing)
    }

    @Test
    fun nothingReachesBouncyCastleByItsFullName() {
        // An import is not the only way in: a fully-qualified reference needs none.
        val outside = mainSources()
            .filter { it.name != "XWing.kt" && it.readText().contains("org.bouncycastle") }
            .map { it.name }
        assertEquals(emptyList<String>(), outside)
    }
}
