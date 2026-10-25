package com.app.vault

/**
 * What a passphrase opened — a key and the ID of the blob that key opens.
 *
 * **Two meanings since `vault-keyring`, and the slot cannot say which.** Under the
 * layout this build writes, [vmk] is a *keyring* key and [metadataBlobId] is the
 * *keyring's* blob ID. Under the layout before it, they are a vault master key and
 * that vault's index ID. The names are the older meaning and are kept because
 * twenty-four call sites read them and every one is still handed 32 bytes of key and
 * 16 bytes of blob ID — what differs is what the blob turns out to be.
 *
 * **The layout is decided per slot, not per file.** The version byte in the slot
 * file's header cannot carry it: that header is shared by all eight slots, and the
 * app can only ever migrate the one slot whose passphrase it was given, so a per-file
 * flag would claim one layout for a file holding two. `VaultKeyringSession` resolves
 * it by trying to read a keyring — which succeeds or fails cryptographically, because
 * a keyring is sealed under its own blob context.
 *
 * [slotIndex] exists so the slot can be resealed later. It must never reach the
 * UI: P2-1 requires a wrong passphrase and a passphrase for an empty slot to
 * produce identical output, and surfacing which slot matched breaks that.
 */
class OpenVault(
    val vmk: ByteArray,
    val metadataBlobId: ByteArray,
    val slotIndex: Int
)

/**
 * The result of an unlock attempt.
 *
 * [derivations] and [slotsAttempted] are the evidence for I8's behavioural half.
 * They are on the result rather than behind an observer interface because they
 * are the one thing a test can assert that an early exit would actually break —
 * the timing difference an early exit makes is microseconds against a ~500 ms
 * derivation, far beneath scheduler noise.
 *
 * The failure case carries them too, which is why this is one class and not a
 * sealed hierarchy: failure is exactly where the counts matter most.
 */
class UnlockOutcome(
    val vault: OpenVault?,
    val derivations: Int,
    val slotsAttempted: Int
) {
    val opened: Boolean get() = vault != null
}

/**
 * A vault a supplied passphrase proved it can open — P2-1.
 *
 * The passphrase is the caller's array, not a copy: the re-seal in
 * `VaultLifecycle` needs it again after the slot array has been rewritten under a
 * new install salt, and copying a secret to save a comment is not a trade. The
 * caller zeroes both this and [vault].
 */
class ProvenVault(val passphrase: ByteArray, val vault: OpenVault)

/**
 * Trial-decrypt unlock — `FORMAT.md` §4.
 *
 * One derivation, every slot attempted, result chosen afterwards.
 */
object VaultUnlock {

    /** Everything one KEK opens. All 8 slots, always, whatever matches. */
    private fun matchesFor(kek: ByteArray, slots: SlotArray, onSlot: () -> Unit): List<OpenVault> {
        val matches = ArrayList<OpenVault>(SlotArray.SLOT_COUNT)
        for (index in 0 until SlotArray.SLOT_COUNT) {
            val payload = slots.open(index, kek)
            onSlot()

            if (payload != null) {
                matches += OpenVault(
                    vmk = payload.copyOfRange(0, SlotArray.VMK_BYTES),
                    metadataBlobId = payload.copyOfRange(
                        SlotArray.VMK_BYTES,
                        SlotArray.PAYLOAD_BYTES
                    ),
                    slotIndex = index
                )
                payload.fill(0)
            }
            // No break, no early return, and deliberately not firstOrNull: that
            // is an early exit wearing a functional hat, and it is the most
            // likely thing a later "cleanup" would introduce here.
        }
        return matches
    }

    fun unlock(passphrase: ByteArray, slots: SlotArray): UnlockOutcome {
        var derivations = 0
        var slotsAttempted = 0

        // The file's parameters, never this build's constant — P2-25. An install
        // opens for life on what it was created with, which is what lets a later
        // release retune without stranding it.
        val kek = VaultKeys.deriveKek(passphrase, slots.installSalt(), slots.kdfParams())
        derivations++

        val matches = try {
            matchesFor(kek, slots) { slotsAttempted++ }
        } finally {
            kek.fill(0)
        }

        // Chosen after the loop. More than one slot can authenticate if the same
        // passphrase was sealed twice; the lowest index wins and nothing about
        // the collision is reported, because a count of matches is a count of
        // vaults (I8). Preventing the collision is P2-1's, at creation time —
        // see [opensAnySlot].
        val vault = matches.minByOrNull { it.slotIndex }
        matches.filter { it !== vault }.forEach {
            it.vmk.fill(0)
            it.metadataBlobId.fill(0)
        }

        return UnlockOutcome(vault, derivations, slotsAttempted)
    }

    /**
     * Which slots [passphrases] prove are occupied — P2-1.
     *
     * **This returns slot indices, which `unlock` refuses to.** The difference is
     * where they go: an unlock outcome is rendered, and naming the matched slot
     * would break the property that a wrong passphrase and a passphrase for an
     * empty slot are indistinguishable. This never leaves `VaultLifecycle`. It
     * exists so a seal can avoid a slot, which is the only way an app that cannot
     * enumerate its own vaults can decline to destroy one.
     *
     * One derivation and all 8 slots per passphrase, as `unlock` performs — this
     * is not a timing-sensitive path (the user asked for it and is watching a
     * progress indicator), but it shares the loop so the no-early-exit property
     * cannot drift between two copies of it.
     *
     * Duplicate entries are collapsed by slot: the same passphrase twice proves
     * one vault, not two.
     */
    fun provenSlots(passphrases: List<ByteArray>, slots: SlotArray): List<ProvenVault> {
        val proven = LinkedHashMap<Int, ProvenVault>()
        passphrases.forEach { passphrase ->
            val kek = VaultKeys.deriveKek(passphrase, slots.installSalt(), slots.kdfParams())
            val matches = try {
                matchesFor(kek, slots) { }
            } finally {
                kek.fill(0)
            }
            matches.forEach { vault ->
                val kept = proven.putIfAbsent(vault.slotIndex, ProvenVault(passphrase, vault))
                if (kept != null) {
                    vault.vmk.fill(0)
                    vault.metadataBlobId.fill(0)
                }
            }
        }
        return proven.values.toList()
    }

    /**
     * Whether [kek] opens anything at all — P2-1's collision check.
     *
     * Two vaults reachable by one passphrase leaves the higher-indexed one
     * permanently unreachable, because `unlock` returns the lowest match. Creation
     * is the only place that can prevent it, and this is what it asks. Nothing is
     * returned but the boolean; the matched vault is zeroed here.
     *
     * [except] is a slot a match does not count in — recovery's own home slot, which
     * the forgotten passphrase is allowed to open (`recovery-own-slot-only`, D1).
     */
    fun opensAnySlot(kek: ByteArray, slots: SlotArray, except: Int = -1): Boolean {
        val matches = matchesFor(kek, slots) { }
        matches.forEach {
            it.vmk.fill(0)
            it.metadataBlobId.fill(0)
        }
        return matches.any { it.slotIndex != except }
    }
}
