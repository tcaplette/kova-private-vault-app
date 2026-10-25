package com.app.vault

/**
 * The panic phrase — P2-3, reshaped by `panic-phrase`.
 *
 * A panic phrase is a credential the user sets, sealed into a slot of its own that holds
 * the same keyring key and blob ID as the keyring's own slot. Typed at the lock screen it
 * opens that keyring in the ordinary eight-slot pass — one derivation, the same screen,
 * the same cost — and `VaultLifecycle.unlock` recognises it afterwards by the slot that
 * opened being the one the keyring's sealed record names as its panic slot.
 *
 * **Why not the marker it replaced.** P2-3's credential was any passphrase with `//` on
 * the end. The marker was a constant, so anyone who knew the app could strip it from a
 * credential they were handed, and it was visible in the credential itself. A phrase the
 * user chose carries nothing that says what it is.
 *
 * **Why this was not done at P2-3.** A panic slot cannot be told from an ordinary one
 * without a stored flag, and at P2-3 a slot opened a vault whose index had nowhere to put
 * one. Since `vault-keyring` a slot opens a keyring, and the keyring's sealed record
 * already carries keyring-level fields — the panic record is two more of them.
 *
 * What is left here is the one rule enrolment applies that needs no key: how close a
 * panic phrase may be to the passphrase it sits beside.
 */
object Panic {

    /**
     * Whether [a] and [b] are equal or one single-character insertion, deletion or
     * substitution apart — the typo distance `panic-pin` forbids between a panic phrase
     * and the passphrase entered beside it.
     *
     * Byte-wise, for P1-10's reason: the passphrase reaches the app as bytes and stops
     * being a `String` at the earliest boundary. A multi-byte character edited is
     * several bytes edited, so this is stricter than a character count, never looser.
     */
    fun withinOneEdit(a: ByteArray, b: ByteArray): Boolean {
        if (a.size == b.size) {
            var differences = 0
            for (i in a.indices) if (a[i] != b[i] && ++differences > 1) return false
            return true
        }
        val (short, long) = if (a.size < b.size) a to b else b to a
        if (long.size - short.size != 1) return false
        // One insertion into [short] gives [long]: skip at most one byte of [long].
        var i = 0
        var j = 0
        var skipped = false
        while (i < short.size) {
            if (short[i] == long[j]) {
                i++
                j++
            } else {
                if (skipped) return false
                skipped = true
                j++
            }
        }
        return true
    }
}
