package com.app.vault

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * I7's structural half — P1-8.5 task 4.6.
 *
 * `VaultLifecycleTest` proves the passphrase path *works* with no Keystore entry.
 * That is behaviour, and behaviour can be satisfied by a path that touches the
 * Keystore and happens not to need it yet. This proves the path never references
 * it at all, which is what makes P1-9's Verify — wipe the Keystore, passphrase
 * unlock unchanged — mean something.
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class PassphrasePathTest {

    private val onPath = listOf(
        "VaultLifecycle.kt", "VaultUnlock.kt", "VaultKeys.kt", "SlotArray.kt", "VaultSession.kt",
    )

    private val forbidden = listOf("KeyStore", "BiometricPrompt", "AndroidKeyStore", "androidx.biometric")

    private fun sources(): List<File> {
        var dir: File? = File(".").absoluteFile
        val rel = "src/main/java/com/app/vault"
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isDirectory }
                ?.let { d -> return onPath.map { File(d, it) } }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate the source package from ${File(".").absolutePath}")
    }

    @Test
    fun everyFileOnThePassphrasePathExists() {
        // Without this the scan below would pass vacuously if a file were renamed
        // — the failure mode every check in this repo is built to avoid.
        val missing = sources().filterNot { it.isFile }.map { it.name }
        assertTrue("passphrase-path sources not found: $missing", missing.isEmpty())
    }

    @Test
    fun thePassphrasePathReferencesNoHardwareKeyStore() {
        val offenders = sources().flatMap { f ->
            val code = f.readText()
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//.*"""), "")
            forbidden.filter { code.contains(it) }.map { "${f.name} -> $it" }
        }
        println("scanned ${sources().size} files on the passphrase path")
        assertTrue(
            "I7: the passphrase path must work with no Keystore involvement, and P1-9's Verify " +
                "depends on it never having touched one. Found: $offenders",
            offenders.isEmpty()
        )
    }
}
