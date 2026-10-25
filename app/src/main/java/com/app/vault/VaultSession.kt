package com.app.vault

/**
 * Where the VMK lives while the vault is unlocked — in memory, in this process,
 * and nowhere else.
 *
 * This exists because WorkManager persists a worker's input `Data` to its own
 * database in `filesDir`, in the clear. Passing the VMK that way would write the
 * vault master key next to the vault.
 *
 * The consequence is deliberate and is the honest one: an import cannot continue
 * unattended after the process dies. Work that wakes with no key waits for the
 * user to unlock. An import that *could* resume after a reboot would mean the key
 * survived the reboot, which is exactly what this app promises does not happen.
 */
object VaultSession {

    @Volatile
    private var vault: OpenVault? = null

    val isUnlocked: Boolean get() = vault != null

    fun unlocked(vault: OpenVault) {
        this.vault = vault
    }

    /** The VMK, or null when locked. Callers must not retain it. */
    fun vmk(): ByteArray? = vault?.vmk

    fun metadataBlobId(): ByteArray? = vault?.metadataBlobId

    /**
     * The open vault itself, for the one caller that must hand it on whole —
     * P1-9's biometric wrap, which seals the same three fields a slot holds.
     *
     * It carries the slot index, which `VaultLifecycle` is careful never to
     * return: a wrong passphrase and a passphrase for an empty slot must stay
     * indistinguishable, and naming the matched slot breaks that. Passing the
     * object through to be sealed is not naming it — nothing may render it, log
     * it, or branch on it. The session held the index in every earlier version
     * too, inside the array it copied from; this only stops throwing it away.
     */
    fun openVault(): OpenVault? = vault

    /**
     * `backup-to-cloud-apps` D11. Runs as a vault's key is about to go — on every path that
     * drops it: a lock, leaving for the list, switching vaults. Set once by `BackupAuto`, which
     * hands a waiting backup to the worker while the key still exists. A hook that fails does
     * not stop the key going.
     */
    @Volatile
    var beforeClose: (() -> Unit)? = null

    fun lock() {
        if (vault != null) runCatching { beforeClose?.invoke() }
        vault?.vmk?.fill(0)
        vault?.metadataBlobId?.fill(0)
        vault = null
    }
}
