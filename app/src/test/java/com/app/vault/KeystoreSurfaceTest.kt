package com.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How much of the app is allowed to know the Keystore exists — P1-9 task 6.8.
 *
 * `PassphrasePathTest` proves the five passphrase-path files never mention it.
 * This proves the containment from the other side: only the wrap, the one Activity
 * that can show a prompt, and P2-5's install anchor do. The set is what makes
 * "biometrics are additive" (I7) a checkable claim rather than an intention — a
 * future change that reaches for a Keystore key from the import pipeline, the blob
 * store or the viewer fails here.
 *
 * **`InstallAnchor.kt` (P2-5) is the third.** The anchor is the one store that
 * survives an app-data clear, so it must be the Keystore; it is not on the
 * passphrase path, so `biometric-unlock`'s spec-level rule is untouched and the
 * five core files stay Keystore-free. The containment widened by one file, on
 * purpose, with the reason in `InstallAnchor`.
 *
 * **`Identity.kt` (P4-4) is the fourth.** The pairing identity must outlive any one
 * vault and be reachable with none open, so it cannot hang off a VMK; the wrap has
 * to be device-bound, which means the Keystore. It is not on the passphrase path
 * either — no unlock, no slot, no VMK reaches this file — so `PassphrasePathTest`
 * is unaffected and I7 still means what it says. One more file, the same reason
 * shape, written down here rather than discovered later.
 *
 * **Remote pairing adds no fifth** (`remote-pairing` D4). Its waiting sessions are
 * sealed under the identity's own key by code in `Identity.kt`; `Cpace.kt` and
 * `RemotePairing.kt` reach the store only through `Identity`, and the test below
 * holds them to it by name.
 *
 * JVM scope: it reads source, so no emulator is involved.
 */
class KeystoreSurfaceTest {

    private val allowed =
        setOf("BiometricWrap.kt", "MainActivity.kt", "InstallAnchor.kt", "Identity.kt")

    private val keystoreSymbols =
        listOf("KeyStore", "BiometricPrompt", "AndroidKeyStore", "androidx.biometric", "KeyGenParameterSpec")

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

    private fun stripped(f: File): String = f.readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//.*"""), "")

    @Test
    fun theSourcesExist() {
        // A scan over an empty list passes vacuously, which is the failure mode
        // every check in this repo is built to avoid.
        val sources = mainSources()
        assertTrue("found no app sources to scan", sources.size > 10)
    }

    @Test
    fun onlyTheWrapAndTheActivityKnowAboutTheKeystore() {
        val offenders = mainSources()
            .filterNot { it.name in allowed }
            .flatMap { f -> keystoreSymbols.filter { stripped(f).contains(it) }.map { "${f.name} -> $it" } }

        assertTrue(
            "biometrics are additive (I7): only ${allowed.joinToString()} may reference the " +
                "Keystore. Found: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theAllowedFilesActuallyUseIt() {
        // Otherwise the allowlist above rots into a permission for files that no
        // longer need it, and the containment quietly widens.
        val unused = allowed.filter { name ->
            mainSources().firstOrNull { it.name == name }
                ?.let { f -> keystoreSymbols.none { stripped(f).contains(it) } } ?: true
        }
        assertEquals("allowlisted files that no longer touch the Keystore: $unused", emptyList<String>(), unused)
    }

    @Test
    fun remotePairingReachesTheKeystoreOnlyThroughIdentity() {
        val pairing = mainSources().filter { it.name == "Cpace.kt" || it.name == "RemotePairing.kt" }
        assertEquals("both pairing files exist to be scanned", 2, pairing.size)
        val offenders = pairing.flatMap { f ->
            keystoreSymbols.filter { stripped(f).contains(it) }.map { "${f.name} -> $it" }
        }
        assertEquals(emptyList<String>(), offenders)
        val storeNamed = mainSources().filter { stripped(it).contains("\"pairing.bin\"") }.map { it.name }
        assertEquals("the session store is Identity.kt's file", listOf("Identity.kt"), storeNamed)
    }

    @Test
    fun theDeviceCredentialIsNowhereInTheApp() {
        // A device PIN is typically four digits. A vault that opens to one is a
        // vault with a four-digit passphrase, whatever the passphrase says — and
        // P1-13 fixes the same rule for export re-auth. This fails the build
        // rather than waiting for a review to notice the constant.
        val offenders = mainSources().filter { stripped(it).contains("DEVICE_CREDENTIAL") }.map { it.name }
        assertTrue(
            "DEVICE_CREDENTIAL must appear in no app source. Found in: $offenders",
            offenders.isEmpty()
        )
    }
}
