package com.app.vault

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The export path is passphrase-only, and structurally so — P1-13.
 *
 * A fingerprint is something a sleeping owner has taken from them, and emptying
 * the vault is the one irreversible action in the app. Gating it on a passphrase
 * only means something if no biometric or key-store API can reach it, so that is
 * checked rather than trusted — the same shape as `PassphrasePathTest`'s claim
 * about the passphrase unlock path.
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class ExportPathScanTest {

    /** Files that make up the export path and must stay biometric-free. */
    private val exportSources = listOf(
        "ExportAuth.kt",
        "ExportDestination.kt",
        "ExportManifest.kt",
        "ExportWorker.kt",
        "Unvaulter.kt",
        "ui/ExportScreen.kt",
        // P1-14 — an archive is a way out of the vault too, and the same rule
        // applies: a fingerprint must not be able to produce one.
        "VaultArchive.kt",
        "ArchiveWorker.kt",
        "ui/ArchiveImportScreen.kt",
    )

    private val forbidden = listOf(
        "BiometricPrompt",
        "BiometricManager",
        "BiometricWrap",
        "KeyStore",
        "KeyGenParameterSpec",
        "setUserAuthenticationRequired",
        "DEVICE_CREDENTIAL",
    )

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

    private fun stripped(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    private fun offendersIn(code: String): List<String> =
        forbidden.filter { stripped(code).contains(it) }

    @Test
    fun theExportPathExists() {
        val missing = exportSources.filterNot { File(sourceDir(), it).isFile }
        assertTrue("export sources not found: $missing", missing.isEmpty())
    }

    @Test
    fun noPartOfTheExportPathTouchesBiometricsOrTheKeystore() {
        val offenders = exportSources.flatMap { name ->
            val f = File(sourceDir(), name)
            offendersIn(f.readText()).map { "$name -> $it" }
        }
        assertTrue(
            "emptying the vault must be reachable by passphrase alone. A biometric — of any " +
                "modality — can be taken from a sleeping owner; this is the one action where " +
                "that must not work. Found: $offenders",
            offenders.isEmpty()
        )
    }

    /**
     * `protected-vault-destroy-gate`. Destroying a vault is gated on the passphrase that opens it,
     * and the same rule holds there: the dialog, and the gate's derivation and the removal it
     * hands the key to, reference no biometric and no key store.
     */
    private val destroySources = listOf(
        "ui/VaultDestroyDialog.kt",
        "VaultLifecycle.kt",
        "VaultKeyringSession.kt",
    )

    @Test
    fun noPartOfTheDestroyGateTouchesBiometricsOrTheKeystore() {
        val missing = destroySources.filterNot { File(sourceDir(), it).isFile }
        assertTrue("destroy sources not found: $missing", missing.isEmpty())
        val offenders = destroySources.flatMap { name ->
            offendersIn(File(sourceDir(), name).readText()).map { "$name -> $it" }
        }
        assertTrue(
            "destroying a vault is irreversible and must be reachable by passphrase alone. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theScanActuallyMatches() {
        // Without this the scan above passes just as happily once its list stops
        // matching anything at all.
        assertTrue(
            "the scan no longer detects a biometric reference",
            offendersIn("val prompt = BiometricPrompt(activity, executor, callback)").isNotEmpty()
        )
        assertTrue(
            "the scan no longer detects a keystore reference",
            offendersIn("KeyStore.getInstance(\"AndroidKeyStore\")").isNotEmpty()
        )
        assertTrue(
            "the scan must not fire on ordinary export code",
            offendersIn("destination.openOutput(entry).use { out -> decryptTo(item, vmk, out) }").isEmpty()
        )
    }
}
