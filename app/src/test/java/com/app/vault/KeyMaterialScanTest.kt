package com.app.vault

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two properties that are easy to state, easy to check, and easy to lose — P1-10.
 *
 * 1. **No key or passphrase is ever a `String`.** A `String` cannot be zeroed and
 *    lives until GC, which is why `VaultKeys` has no `String` overload. The rule
 *    only holds as long as nobody adds a convenience one, so it is checked rather
 *    than trusted.
 * 2. **Nothing in the app is selectable.** A `SelectionContainer` anywhere makes
 *    the text inside it copyable to a clipboard every app can read.
 *
 * Both scans assert **negatively** as well: they are run against a fixture that
 * violates each rule, so a scan that silently stopped matching fails here rather
 * than passing forever. JVM scope — it reads source, so no emulator is involved.
 */
class KeyMaterialScanTest {

    /** Parameter names that mean "this is secret". */
    private val secretParams = listOf("passphrase", "vmk", "kek", "dek", "key", "subkey")

    private fun sources(): List<File> {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/java/com/app/vault"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { d -> return d.walkTopDown().filter { it.extension == "kt" }.toList() }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the source package from ${File(".").absolutePath}")
    }

    private fun stripped(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    /** `name: String` where `name` is one of the secret parameter names. */
    private fun stringTypedSecrets(code: String): List<String> =
        Regex("""\b(\w+)\s*:\s*String\b""").findAll(stripped(code))
            .map { it.groupValues[1] }
            .filter { name -> secretParams.any { name.lowercase().contains(it) } }
            .toList()

    @Test
    fun theSourcesExist() {
        assertTrue("found no app sources to scan", sources().size > 10)
    }

    @Test
    fun noKeyOrPassphraseIsTypedAsString() {
        val offenders = sources().flatMap { f ->
            stringTypedSecrets(f.readText()).map { "${f.name} -> $it: String" }
        }
        assertTrue(
            "key material must arrive as ByteArray — a String cannot be zeroed and survives " +
                "until GC. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theStringScanActuallyMatches() {
        // Without this the scan above passes just as happily when its regex stops
        // matching anything at all.
        assertTrue(
            "the scan no longer detects a String-typed secret",
            stringTypedSecrets("fun deriveKek(passphrase: String, salt: ByteArray) {}").isNotEmpty()
        )
        assertTrue(
            "the scan must not fire on ordinary text parameters",
            stringTypedSecrets("fun label(filename: String) {}").isEmpty()
        )
    }

    @Test
    fun nothingInTheAppIsSelectable() {
        val offenders = sources()
            .filter { stripped(it.readText()).contains("SelectionContainer") }
            .map { it.name }
        assertTrue(
            "selectable text can be copied to a clipboard every app on the device can read. " +
                "Found in: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theSelectionScanActuallyMatches() {
        assertTrue(
            "the selection scan no longer detects a SelectionContainer",
            stripped("SelectionContainer { Text(secret) }").contains("SelectionContainer")
        )
        assertTrue(
            "a commented-out mention must not count",
            !stripped("// SelectionContainer { Text(secret) }").contains("SelectionContainer")
        )
    }
}
