package com.app.vault

import java.io.File

/**
 * The passphrase gate in front of "Unvault Everything" — P1-13.
 *
 * **A real derivation, not a session check.** `VaultSession.isUnlocked` is true
 * whenever the grid is on screen, so gating on it would make re-entry theatre.
 * This runs the same ~460 ms Argon2id an unlock runs, against the open keyring's
 * home slot, and requires what it opens to be the session's — so the passphrase
 * must open *this* list or vault, not merely some keyring, and never the panic phrase.
 *
 * **Never biometrics and never a device credential**, structurally: this file
 * references no `BiometricPrompt`, no `KeyStore`, no `BiometricWrap`, and a JVM
 * scan asserts that. A biometric — a finger, a face, an iris, whichever this
 * device has — is something a sleeping owner has taken from them, and the one
 * irreversible action in this app is the last place that should be possible.
 */
object ExportAuth {

    /**
     * Whether [passphrase] opens the currently open vault.
     *
     * [passphrase] is zeroed here on every path. There is deliberately no
     * `String` overload.
     *
     * [captureStore] — `intruder-captures-at-home` D9: the export's source is the open
     * keyring's capture store, which is on screen with no vault open. The same derivation and
     * the same comparison; only the precondition is the keyring's rather than a vault's.
     */
    fun confirms(slotsFile: File, passphrase: ByteArray, captureStore: Boolean = false): Boolean {
        // `vault-keyring`. **What a slot yields is a keyring key now**, so the
        // comparison that used to be "the same vault" is "the same keyring". It is the
        // same question the gate always asked — is this the passphrase that opens what
        // is on screen — one level up, and it is still answered against a real
        // derivation rather than a session flag.
        if (if (captureStore) !VaultKeyringSession.isOpen else !VaultSession.isUnlocked) {
            passphrase.fill(0)
            return false
        }

        // `protected-vault-destroy-gate`. **The home slot only.** The panic slot seals this same
        // keyring, so trying every slot and comparing keys let the panic phrase authorise an
        // export. Asked exactly as Edit vault and the kit view ask it.
        return VaultLifecycle.confirmsListPassphrase(slotsFile, passphrase)
    }

    /**
     * Whether [passphrase] opens the open vault — `protected-vault-destroy-gate` D8.
     *
     * For a vault with its own passphrase, that passphrase and only that one: the entry's KEK
     * is derived under the set it records, the entry unsealed with it, and the result must be
     * the session's key. The list's passphrase cannot do that, so it is refused. Any other vault
     * takes [confirms], which asks only the keyring's home slot, so the panic phrase is refused.
     * One derivation either way.
     *
     * [confirms] itself stays the list's question for the decoy flow, which needs exactly that.
     * [passphrase] is zeroed here on every path, and so are the KEK and the unsealed copy.
     */
    fun confirmsOpenVault(slotsFile: File, passphrase: ByteArray): Boolean {
        val position = VaultKeyringSession.openVaultPosition()
        val entry = VaultKeyringSession.vaults().firstOrNull { it.position == position }
        if (entry?.hasOwnPassphrase != true) return confirms(slotsFile, passphrase)

        val kek = try {
            VaultKeyringSession.entryPassphraseKek(passphrase, position, entry.kdfSet)
        } finally {
            passphrase.fill(0)
        } ?: return false
        return try {
            VaultKeyringSession.withVaultKey(position, kek) { vmk, _ ->
                // Length-checked content compare, [holdsKey]'s; both operands are 32 bytes.
                VaultSession.vmk()?.contentEquals(vmk) == true
            } == true
        } finally {
            kek.fill(0)
        }
    }
}
