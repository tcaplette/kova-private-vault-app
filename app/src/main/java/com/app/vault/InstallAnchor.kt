package com.app.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator

/**
 * The one signal that survives an app-data clear — P2-5.
 *
 * An app cannot watch its own data being cleared: it is force-stopped and
 * everything it owns in `filesDir` and `SharedPreferences` is gone. The one store
 * an app controls that a clear does **not** reliably remove is the Android
 * Keystore — clearing app data leaves the app's key aliases in place on the devices
 * where that holds, which is the well-known cause of orphaned-key crashes after a
 * clear. So a key alias present while the app's own data is absent is the signature
 * of a clear rather than a first run.
 *
 * The key is **never used to encrypt anything.** Its presence or absence is the
 * entire signal, which is why the payload it could protect does not exist.
 *
 * **I7 containment.** `KeystoreSurfaceTest` confines Keystore references to the
 * files that legitimately need them. This is the third — the anchor is not on the
 * passphrase path (`biometric-unlock`'s spec-level rule still holds; the five core
 * files are Keystore-free and `PassphrasePathTest` still proves it), it is a
 * launch-time device check. The test's allowlist widens by one, with this reason.
 */
object InstallAnchor {

    private const val KEYSTORE = "AndroidKeyStore"
    const val ALIAS = "vault.anchor"

    private fun keystore(): KeyStore =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** Whether the anchor is present. A `false` from a broken Keystore is safe: the caller reads it as a first run and creates one. */
    fun exists(): Boolean =
        runCatching { keystore().containsAlias(ALIAS) }.getOrDefault(false)

    /**
     * Creates the anchor if it is absent. A plain AES key with no authentication
     * requirement, so it can be created and checked with no biometric and no
     * prompt — only its existence matters, never its use.
     */
    fun createIfAbsent() {
        if (exists()) return
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        generator.generateKey()
    }

    /** Test-only: removes the anchor so a test can stage a first run or a clear. */
    fun deleteForTest() {
        runCatching { keystore().deleteEntry(ALIAS) }
    }
}
