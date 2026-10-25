package com.app.vault

import android.util.Log
import java.security.SecureRandom

/**
 * The open keyring, and the one vault open on it — `vault-keyring`.
 *
 * **Two levels of "unlocked" now exist and they are not the same thing.** A passphrase
 * opens a *keyring*: the names of the vaults it holds become readable and nothing else
 * does. Opening a vault from that list unwraps exactly one key, which
 * [VaultSession] then holds for everything downstream — every existing caller of
 * `VaultSession.vmk()` is untouched by this file, because what it is handed is still
 * one vault's key.
 *
 * **At most one vault key exists in memory.** [openVault] unwraps one entry;
 * [leaveVault] zeroes it. Nothing here ever unwraps the list, and
 * `VaultKeyring.parse` cannot: the entries come back sealed.
 *
 * **This file decides which slot layout an install is on.** A slot yields 32 bytes of
 * key and 16 of blob ID either way — a keyring under the layout this build writes, a
 * vault under the one before it. The file header cannot say which, because it is
 * shared by all eight slots and only the slot whose passphrase was given can ever be
 * migrated. So [resolve] tries the keyring reading, which fails *cryptographically*
 * on an old-layout slot because a keyring is sealed under its own blob context.
 */
object VaultKeyringSession {

    private const val TAG = "VaultKeyring"

    /** What a migrated vault is called until the user renames it. */
    const val MIGRATED_NAME = "My vault"

    private val rng = SecureRandom()

    @Volatile
    private var key: ByteArray? = null

    @Volatile
    private var blobId: ByteArray? = null

    @Volatile
    private var keyring: VaultKeyring.Keyring? = null

    /** The slot the keyring came from, for a re-seal. Never rendered — P2-1's rule. */
    @Volatile
    private var slotIndex: Int = -1

    /** Which entry is open, or -1. */
    @Volatile
    private var openPosition: Int = -1

    /**
     * The slot the open keyring came from, or -1 — for the one caller that must know
     * which slot it may **not** write: `VaultLifecycle.create`.
     *
     * `openVault()`'s terms apply unchanged and are the reason this is safe to expose:
     * nothing may render it, log it, or branch on it in UI. `MultiVaultScanTest` fails
     * the build if a slot index reaches a UI source, and that is what keeps this
     * honest rather than the accessor's name.
     *
     * **Why a reader rather than a passphrase.** Sealing a slot destroys whatever was
     * in it, and the only slot the app can ever be certain about is the one it is
     * currently holding a key for. Before this, that certainty existed but was spent:
     * the decoy screen made the user type the open vault's passphrase so `create`
     * could re-derive what the session already knew, which cost a derivation, cost the
     * user a confusing instruction, and made the guarantee a property of two unrelated
     * gates happening to line up rather than of one line of code.
     */
    fun openSlotIndex(): Int = if (keyring != null) slotIndex else -1

    /**
     * The open keyring's record of which slots it knows are occupied —
     * `slot-occupancy-map`, D5. 0 with no keyring open.
     *
     * **A bit pattern and never a count.** [openSlotIndex]'s terms apply unchanged and
     * for the same reason: `VaultLifecycle.create` is the one caller that may read it
     * as slots, nothing may render it, and `MultiVaultScanTest` fails the build if a UI
     * source starts naming slots. A count of the bits set here is a count of the
     * keyrings on the install, which is the one thing this format refuses to disclose —
     * so nothing derives one, here or above.
     *
     * **The open keyring's own slot is always in the answer**, whether or not the block
     * on disk says so. A keyring written before the record existed stores nothing there
     * and must still read as knowing its own slot and no other, so the union belongs
     * here rather than in each caller — a caller that forgot it would hand the open
     * keyring's own slot back as a candidate to seal.
     */
    fun knownSlots(): Int {
        val ring = keyring ?: return 0
        val own = if (slotIndex in 0 until SlotArray.SLOT_COUNT) 1 shl slotIndex else 0
        return ring.knownSlots or own
    }

    /**
     * Replaces the open keyring's record with [value] and re-seals the block.
     *
     * [markDecoyBuilt]'s shape exactly — a change to keyring data followed by one save
     * — and called for the same kind of reason: the creating keyring has to learn the
     * slot it just filled, or the next creation would be free to overwrite it.
     *
     * **Called only after the slot has been sealed** (D3). A record claiming a slot
     * that was never written costs the user a candidate; a record missing one costs
     * them a keyring, and the write order follows that asymmetry.
     *
     * False where there is no keyring open or the save did not land, and the caller
     * treats that as the record not having moved — which is exactly what it is.
     */
    fun recordKnownSlots(store: BlobStore, value: Int): Boolean {
        val ring = keyring ?: return false
        val keyringKey = key ?: return false
        val id = blobId ?: return false
        if (ring.knownSlots == (value and 0xFF)) return true

        val updated = VaultKeyring.withKnownSlots(ring, value)
        return runCatching {
            VaultKeyring.save(store, id, keyringKey, updated)
            keyring = updated
            true
        }.getOrElse {
            Log.w(TAG, "an occupancy record could not be written", it)
            false
        }
    }

    /**
     * The slot the open keyring's panic phrase opens, or -1 — `panic-phrase`.
     *
     * [openSlotIndex]'s terms: `VaultLifecycle` is the one caller that may read it as a
     * slot, and `MultiVaultScanTest` fails the build if a UI source names it. The UI asks
     * [hasPanicPhrase] instead, which is a yes or no and names no slot.
     */
    fun panicSlot(): Int = keyring?.panicSlot ?: -1

    /** The slot the open keyring's own passphrase opens, as its panic record states, or -1. */
    fun homeSlot(): Int = keyring?.homeSlot ?: -1

    /** Whether the open keyring has a panic phrase. The only form the UI may read. */
    fun hasPanicPhrase(): Boolean = panicSlot() >= 0

    /**
     * Replaces the open keyring's panic record and re-seals the block — [recordKnownSlots]'s
     * shape exactly. -1 is none. False where no keyring is open or the save did not land.
     */
    fun recordPanic(store: BlobStore, panicSlot: Int, homeSlot: Int): Boolean {
        val ring = keyring ?: return false
        val keyringKey = key ?: return false
        val id = blobId ?: return false
        if (ring.panicSlot == panicSlot && ring.homeSlot == homeSlot) return true

        val updated = VaultKeyring.withPanicRecord(ring, panicSlot, homeSlot)
        return runCatching {
            VaultKeyring.save(store, id, keyringKey, updated)
            keyring = updated
            true
        }.getOrElse {
            Log.w(TAG, "a panic record could not be written", it)
            false
        }
    }

    /** Views of the open keyring's own kit left — `recovery-kit-three-views`. 0 with none open. */
    fun listKitViewsLeft(): Int = keyring?.let { VaultKeyring.KIT_VIEWS - it.kitViewsUsed } ?: 0

    /**
     * Spends one view of the open keyring's own kit and saves it — D2. Returns the views left
     * **after** this one, or null when none were left or the save did not land, in which case
     * nothing was spent. The caller encodes the words only after a non-null return, so a kill
     * mid-view never gives the view back.
     */
    fun spendListKitView(store: BlobStore): Int? {
        val ring = keyring ?: return null
        if (ring.kitViewsUsed >= VaultKeyring.KIT_VIEWS) return null
        val used = ring.kitViewsUsed + 1
        return if (saveRing(store, VaultKeyring.withKitViewsUsed(ring, used))) VaultKeyring.KIT_VIEWS - used else null
    }

    /** [spendListKitView] for the protected vault at [position]'s own kit. Its count, not the list's. */
    fun spendVaultKitView(store: BlobStore, position: Int): Int? {
        val ring = keyring ?: return null
        val entry = ring.at(position)?.takeIf { it.hasOwnPassphrase } ?: return null
        if (entry.kitViewsUsed >= VaultKeyring.KIT_VIEWS) return null
        val used = entry.kitViewsUsed + 1
        return if (saveRing(store, VaultKeyring.withEntryKitViewsUsed(ring, position, used))) VaultKeyring.KIT_VIEWS - used else null
    }

    private fun saveRing(store: BlobStore, updated: VaultKeyring.Keyring): Boolean {
        val keyringKey = key ?: return false
        val id = blobId ?: return false
        return runCatching {
            VaultKeyring.save(store, id, keyringKey, updated)
            keyring = updated
            true
        }.getOrElse {
            Log.w(TAG, "a keyring change could not be written", it)
            false
        }
    }

    val isOpen: Boolean get() = keyring != null

    /** The vaults on the open keyring, by position. Names only — no key is unwrapped. */
    fun vaults(): List<VaultKeyring.Entry> = keyring?.vaults ?: emptyList()

    /**
     * Takes vaults onto a keyring from a copy of the same keyring found in a backup —
     * `backup-to-cloud-apps` D13, generalised by `backup-file-recover-and-import` D4.
     *
     * Each vault's backup carries the keyring as it was when that backup was written, so the
     * first vault restored may bring a keyring older than a second vault. That second backup's
     * copy lists it. An entry is sealed under a key derived from the keyring key **and its
     * position**, so it is taken only into the same position and only where that position
     * is free; an index already listed is not taken twice.
     *
     * The target is named by [targetKey] and [targetId]: the open keyring passes its own, and
     * a list on this phone that is not open — a backup's list brought back from Import or
     * Recover — passes the words' key. A target that is not open is loaded, changed and saved,
     * and nothing of it is held afterwards. [targetKey] stays the caller's.
     *
     * Returns how many vaults joined.
     */
    fun adoptEntries(store: BlobStore, incoming: BlobStore, targetKey: ByteArray, targetId: ByteArray): Int {
        val isOpen = blobId?.contentEquals(targetId) == true
        val ring = (if (isOpen) keyring else VaultKeyring.load(store, targetId, targetKey)) ?: return 0
        val theirs = VaultKeyring.load(incoming, targetId, targetKey) ?: return 0
        val entries = ring.entries.copyOf()
        var added = 0
        theirs.entries.forEachIndexed { position, entry ->
            if (entry == null || entries.getOrNull(position) != null) return@forEachIndexed
            if (entries.any { it?.indexBlobId?.contentEquals(entry.indexBlobId) == true }) return@forEachIndexed
            entries[position] = entry
            added++
        }
        if (added == 0) return 0
        val updated = VaultKeyring.Keyring(entries, ring.hasDecoy, ring.knownSlots, ring.panicSlot, ring.homeSlot, ring.kitViewsUsed)
        VaultKeyring.save(store, targetId, targetKey, updated)
        if (isOpen) keyring = updated
        return added
    }

    fun isFull(): Boolean = keyring?.isFull ?: true

    fun openVaultPosition(): Int = openPosition

    /**
     * Resolves what a passphrase opened, migrating an older install if that is what it
     * turns out to be, and holds the result.
     *
     * Returns false only when the slot opened something this build cannot read at all,
     * which the caller must treat exactly as a failed unlock — the outcome the whole
     * unlock path reports is "something opened" and nothing more.
     *
     * **[passphrase] is needed for the migration and only for it.** Re-sealing a slot
     * takes a KEK, and the one `VaultUnlock` derived has already been zeroed by the
     * time this runs. The array belongs to the caller, which zeroes it.
     */
    fun resolve(
        store: BlobStore,
        slots: SlotArray,
        passphrase: ByteArray,
        opened: OpenVault,
        /**
         * Called when a migration actually ran, so the caller can retire anything that
         * sealed the **old** slot payload — §4.1's biometric wrap is the only one.
         * Passed in rather than called, because re-sealing it touches a `KeyStore` and
         * this file references none (I7).
         */
        onMigrated: () -> Unit = {},
    ): Boolean {
        val existing = VaultKeyring.load(store, opened.metadataBlobId, opened.vmk)
        if (existing != null) {
            hold(opened.vmk.copyOf(), opened.metadataBlobId.copyOf(), existing, opened.slotIndex)
            return true
        }
        return migrate(store, slots, passphrase, opened).also { if (it) onMigrated() }
    }

    /**
     * Resolves a payload that came from the **biometric wrap**, and never migrates.
     *
     * §4.1's wrap seals the same 48 bytes a slot holds, so it has the same two
     * meanings — and unlike the passphrase path there is no passphrase here, so an
     * old-layout payload cannot be migrated: re-sealing a slot needs a KEK, and a
     * biometric unlock has none by construction (I7).
     *
     * Returns false for an old-layout wrap, which the caller must treat as a
     * biometric unlock that did not work rather than opening the vault it names.
     * **Opening it would put a vault outside every keyring on the install**, with no
     * entry, no name and no way to reach it again from the list.
     *
     * That state is not left to be discovered: a migration disables the wrap
     * ([migrate]), so the only way to reach this returning false is a wrap written
     * before the upgrade whose slot has not been migrated yet — one passphrase unlock
     * fixes it, and re-enrolling restores the fingerprint.
     */
    fun resolveFromWrap(store: BlobStore, opened: OpenVault): Boolean {
        val existing = VaultKeyring.load(store, opened.metadataBlobId, opened.vmk) ?: return false
        hold(opened.vmk.copyOf(), opened.metadataBlobId.copyOf(), existing, opened.slotIndex)
        return true
    }

    /**
     * An older install becomes a keyring holding the one vault it had.
     *
     * **The order is the whole safety argument.** The keyring blob is written and
     * durable *before* the slot is re-sealed to point at it. A kill before the re-seal
     * leaves an old-layout slot that still opens the vault directly and an orphaned
     * blob the sweeper collects; a kill after it leaves a slot naming a keyring that is
     * already there. There is no window in which a slot names a blob that does not
     * exist, which is the only failure that would lose a vault.
     *
     * Nothing is re-encrypted: the same VMK, the same index blob, the same identifiers.
     * The vault's contents are not read at all.
     */
    private fun migrate(
        store: BlobStore,
        slots: SlotArray,
        passphrase: ByteArray,
        opened: OpenVault,
    ): Boolean {
        val keyringKey = VaultKeys.newKeyringKey()
        // Derived, so 24 words find it — `VaultKeys.keyringBlobId`.
        val keyringId = VaultKeys.keyringBlobId(keyringKey)

        val migrated = VaultKeyring.withEntry(
            VaultKeyring.empty(),
            keyringKey,
            position = 0,
            name = MIGRATED_NAME,
            vmk = opened.vmk,
            indexBlobId = opened.metadataBlobId,
            appearance = VaultAppearance.defaultFor(0).pack(),
        )

        return runCatching {
            // 1. Durable first.
            VaultKeyring.save(store, keyringId, keyringKey, migrated)

            // 2. Only then does the slot stop pointing at the vault directly. The KEK
            //    is derived here because the unlock path zeroed its own.
            val kek = VaultKeys.deriveKek(passphrase, slots.installSalt(), slots.kdfParams())
            try {
                slots.seal(opened.slotIndex, kek, keyringKey, keyringId)
            } finally {
                kek.fill(0)
            }

            hold(keyringKey, keyringId, migrated, opened.slotIndex)
            // A count, never a name. One line, so a migration that happened is
            // visible in a bug report and what it migrated is not.
            Log.i(TAG, "migrated 1 vault into a keyring")
            true
        }.getOrElse { e ->
            Log.w(TAG, "a keyring migration did not complete", e)
            keyringKey.fill(0)
            false
        }
    }

    /**
     * Holds a keyring that was just created — `VaultLifecycle.create`'s one caller.
     *
     * Separate from [resolve] because creation already has every piece and has no
     * layout to resolve; routing it through a re-read would decrypt a blob written
     * two lines earlier to learn what it already knows.
     */
    fun holdCreated(
        keyringKey: ByteArray,
        keyringId: ByteArray,
        ring: VaultKeyring.Keyring,
        slot: Int,
    ) {
        hold(keyringKey.copyOf(), keyringId.copyOf(), ring, slot)
        openPosition = 0
    }

    /**
     * Holds a keyring recovered from its 24 words — `VaultLifecycle`'s recovery path.
     *
     * [holdCreated]'s shape, and one difference that matters: **no vault is opened.**
     * Creation has exactly one vault and opening it is unambiguous; a recovery may
     * have restored several, and choosing one of them would be inventing an answer to
     * a question the words did not ask.
     */
    fun holdRecovered(
        keyringKey: ByteArray,
        keyringId: ByteArray,
        ring: VaultKeyring.Keyring,
        slot: Int,
    ) {
        hold(keyringKey.copyOf(), keyringId.copyOf(), ring, slot)
    }

    private fun hold(
        keyringKey: ByteArray,
        keyringId: ByteArray,
        ring: VaultKeyring.Keyring,
        slot: Int,
    ) {
        key = keyringKey
        blobId = keyringId
        keyring = ring
        slotIndex = slot
        openPosition = -1
    }

    /**
     * Opens the vault at [position] and hands its key to [VaultSession].
     *
     * Any vault already open is left first, so the previous key is zeroed before the
     * next is unwrapped and the "one key in memory" claim holds across a switch.
     *
     * **This file both sets and clears the session, and the symmetry is the point.**
     * [leaveVault] zeroes what this hands over; a version where something else did the
     * setting would be a version where the two halves could drift apart, and the half
     * that drifts is the one that leaves a key behind.
     *
     * `VaultLifecycle.open` is still what runs the per-vault work — the chaff churn,
     * the intruder ingest, the tamper-log entry — and sets the same session again with
     * the same object, which is a no-op it does not need to know about.
     */
    /**
     * The open keyring's own blob id — `settings-separation`.
     *
     * A copy, for the reason every other accessor here returns one: the field is
     * zeroed on lock, and a caller holding the array itself would read zeroes at an
     * arbitrary later moment rather than null.
     *
     * The mirror needs it because a restore is the 24 words plus one folder, and the
     * words find the keyring by deriving exactly this id — so the folder has to hold
     * that blob or the words open nothing.
     */
    fun keyringBlobId(): ByteArray? = blobId?.copyOf()

    /**
     * The open keyring's backup key — `backup-to-cloud-apps`, `FORMAT.md` §14. Derived
     * here so the keyring key never leaves this object; the caller zeroes the result as
     * soon as the table of contents is sealed.
     */
    fun backupKey(): ByteArray? = key?.let { VaultKeys.backupKey(it) }

    /**
     * The open keyring's capture store key — `intruder-captures-at-home` D1. Derived here so
     * the keyring key never leaves this object. Null with no keyring open; the caller zeroes it.
     */
    fun captureStoreKey(): ByteArray? = key?.let { VaultKeys.captureStoreKey(it) }

    /**
     * Runs [block] over one vault's key **without opening it** — `intruder-captures-at-home`
     * D5, for moving a capture into a vault from the home screen.
     *
     * Unseals the entry at [position] — under the keyring entry key, or under [entryKek] when
     * the vault has its own passphrase — hands [block] its VMK and index blob ID, and zeroes the
     * VMK after. [VaultSession] is never set and [leaveVault] never called: no vault is open at
     * home, and none is left open. [entryKek] stays the caller's to zero.
     *
     * Null for an empty position, a wrong key, or an entry form [entryKek] does not match.
     */
    fun <T> withVaultKey(position: Int, entryKek: ByteArray?, block: (ByteArray, ByteArray) -> T): T? {
        val ring = keyring ?: return null
        val entry = ring.at(position) ?: return null
        if (entry.hasOwnPassphrase != (entryKek != null)) return null
        val vmk = if (entryKek != null) {
            VaultKeyring.unsealOwnPassphraseVaultKey(ring, entryKek, position)
        } else {
            VaultKeyring.unsealVaultKey(ring, key ?: return null, position)
        } ?: return null
        return try {
            block(vmk, entry.indexBlobId.copyOf())
        } finally {
            vmk.fill(0)
        }
    }

    fun openVault(position: Int): OpenVault? {
        val ring = keyring ?: return null
        val keyringKey = key ?: return null
        val entry = ring.at(position) ?: return null

        // `per-vault-passphrases`. **Refused rather than attempted.** The keyring key
        // cannot open an entry sealed under its own passphrase, so this would fail at
        // the AEAD anyway — but failing there would leave every caller unable to tell
        // "wrong key" from "you asked the wrong way", and one of those is a bug in the
        // caller. `VaultLifecycle.destroy` already handles a null here, so panic still
        // writes its entry to the first vault it *can* open, or none.
        if (entry.hasOwnPassphrase) return null

        leaveVault()

        val vmk = VaultKeyring.unsealVaultKey(ring, keyringKey, position) ?: return null
        openPosition = position
        return OpenVault(vmk, entry.indexBlobId.copyOf(), slotIndex)
            .also { VaultSession.unlocked(it) }
    }

    /**
     * The KEK a vault's own [passphrase] gives at [position] under parameter set [setIndex] —
     * `backup-file-recover-and-import`, D2.
     *
     * Derived here so the keyring key never leaves this object. Opening passes the set the
     * entry records; sealing passes `RELEASED_KDF_PARAMS.lastIndex`. Null with no keyring
     * open, or for a set this build does not know — a protected entry written before
     * keyring version 5 records none, and its own kit is what opens it.
     *
     * [passphrase] stays the caller's; the result is the caller's to zero.
     */
    fun entryPassphraseKek(passphrase: ByteArray, position: Int, setIndex: Int): ByteArray? {
        val keyringKey = key ?: return null
        val params = VaultKeys.RELEASED_KDF_PARAMS.getOrNull(setIndex) ?: return null
        return VaultKeys.entryPassphraseKek(passphrase, keyringKey, position, params)
    }

    /**
     * Opens a vault sealed under its **own** passphrase — `per-vault-passphrases`.
     *
     * [openVault]'s twin for the other entry form, and the same act in every respect
     * that matters downstream: the vault it leaves open is an ordinary [OpenVault] and
     * every caller of `VaultSession.vmk()` is untouched.
     *
     * [entryKek] comes from [entryPassphraseKek] and stays the caller's to zero.
     *
     * Null for a wrong passphrase, null for an entry that has no passphrase of its own,
     * and null for a position that holds nothing. **The vault being left is left
     * first**, before the attempt, so a wrong passphrase does not leave the previous
     * vault's key sitting in memory behind a screen the user has walked away from.
     */
    fun openVaultWithPassphrase(position: Int, entryKek: ByteArray): OpenVault? {
        val ring = keyring ?: return null
        val entry = ring.at(position) ?: return null
        if (!entry.hasOwnPassphrase) return null

        leaveVault()

        val vmk = VaultKeyring.unsealOwnPassphraseVaultKey(ring, entryKek, position) ?: return null
        openPosition = position
        return OpenVault(vmk, entry.indexBlobId.copyOf(), slotIndex)
            .also { VaultSession.unlocked(it) }
    }

    /**
     * Opens the vault at [position] with a key already proven for it —
     * `protected-vault-destroy-gate` D5.
     *
     * [openVault]'s twin for "Give my files back, then destroy" from the list: the destroy
     * dialog's gate unsealed [vmk] with a real derivation, so the vault opens without asking
     * again, whichever passphrase protects it. [vmk] is copied **before** the open vault is
     * left, so a caller passing the session's own key does not hand over zeroes. [vmk] stays
     * the caller's. Null for a position that holds nothing.
     */
    fun openVaultWithKey(position: Int, vmk: ByteArray): OpenVault? {
        val entry = keyring?.at(position) ?: return null
        val copy = vmk.copyOf()
        leaveVault()
        openPosition = position
        return OpenVault(copy, entry.indexBlobId.copyOf(), slotIndex)
            .also { VaultSession.unlocked(it) }
    }

    /**
     * Leaves the open vault for the list: its key is zeroed, its thumbnails are gone,
     * and the keyring stays open.
     *
     * The thumbnail cache is cleared here for the same reason [lock] clears it —
     * decrypted pixels from a vault the user has left are decrypted pixels from a
     * vault that is closed.
     */
    fun leaveVault() {
        if (openPosition < 0 && !VaultSession.isUnlocked) return
        VaultSession.lock()
        ThumbnailCache.clear()
        openPosition = -1
    }

    /**
     * Adds a vault to the open keyring and writes it. Null when the keyring is full.
     *
     * **This seals no slot, and that is the point** — `per-vault-passphrases`. Creating
     * a vault used to mean sealing a slot chosen at random from the ones the user had
     * not proven occupied, which could overwrite a vault the app cannot see. An entry
     * on a keyring that is already open can overwrite nothing, so there is nothing to
     * prove and no list of passphrases to collect.
     *
     * [deriveEntryKek] is how a vault gets a passphrase of its own. Null — the default
     * — seals under the keyring entry key. Supplied, it is called **with the position this
     * vault landed on and the parameter set it is sealed under**, both chosen here, and
     * returns the KEK [entryPassphraseKek] gives for them.
     *
     * The KEK the lambda returns is zeroed here. The passphrase behind it is the
     * caller's.
     *
     * [appearance] is what the creation screen chose; null is `VaultAppearance.defaultFor` the
     * position this vault lands on.
     *
     * @return the position, and whether it was sealed under its own passphrase.
     */
    fun createVault(
        store: BlobStore,
        name: String,
        appearance: VaultAppearance? = null,
        deriveEntryKek: ((position: Int, setIndex: Int) -> ByteArray)? = null,
    ): Int? {
        val ring = keyring ?: return null
        val keyringKey = key ?: return null
        val id = blobId ?: return null
        val position = ring.firstFree() ?: return null

        val look = (appearance ?: VaultAppearance.defaultFor(position)).pack()
        val vmk = VaultKeys.newVmk()
        var entryKek: ByteArray? = null
        return try {
            val indexId = VaultKeys.metadataBlobId(vmk)
            MetadataDb.empty().save(store, indexId, vmk)

            val updated = if (deriveEntryKek == null) {
                VaultKeyring.withEntry(ring, keyringKey, position, name, vmk, indexId, look)
            } else {
                val set = VaultKeys.RELEASED_KDF_PARAMS.lastIndex
                entryKek = deriveEntryKek(position, set)
                VaultKeyring.withProtectedEntry(
                    ring, entryKek!!, position, name, vmk, indexId, set, look,
                )
            }
            VaultKeyring.save(store, id, keyringKey, updated)
            keyring = updated
            position
        } catch (e: Throwable) {
            Log.w(TAG, "a vault could not be created", e)
            null
        } finally {
            vmk.fill(0)
            entryKek?.fill(0)
        }
    }

    /**
     * The key of the vault just created at [position], for its recovery kit —
     * `per-vault-passphrases`.
     *
     * A protected vault's kit carries **that vault's** key, because the keyring's key
     * does not open it and its kit therefore cannot recover it. The kit has to be
     * issued in the flow that set the passphrase, so this hands the key back to that
     * flow using the KEK it has in hand.
     *
     * The caller owns the result and zeroes it once the words are on screen.
     */
    fun vaultKeyForKit(position: Int, entryKek: ByteArray): ByteArray? {
        val ring = keyring ?: return null
        return VaultKeyring.unsealOwnPassphraseVaultKey(ring, entryKek, position)
    }

    /**
     * Puts an imported vault on the open keyring — `vault-keyring`.
     *
     * Unlike [createVault] this generates nothing: the key and the index came in the
     * archive and are already on disk. Null when the keyring is full, and the caller
     * discards what it wrote rather than leaving blobs nothing can reach.
     */
    fun addImported(
        store: BlobStore,
        name: String,
        vmk: ByteArray,
        indexBlobId: ByteArray,
    ): Int? {
        val ring = keyring ?: return null
        val keyringKey = key ?: return null
        val id = blobId ?: return null
        val position = ring.firstFree() ?: return null

        return runCatching {
            val updated = VaultKeyring.withEntry(
                ring, keyringKey, position, name, vmk, indexBlobId, VaultAppearance.defaultFor(position).pack(),
            )
            VaultKeyring.save(store, id, keyringKey, updated)
            keyring = updated
            position
        }.getOrNull()
    }

    /**
     * Puts a vault recovered from **its own** 24 words onto the open keyring —
     * `per-vault-passphrases`.
     *
     * [addImported]'s sibling with the proof in front of it. The words carry a key and
     * nothing else, so the index has to be found from that key and has to open, or those
     * words did not name a vault *here* whatever they name elsewhere. That check is the
     * whole difference: an archive arrived with its blobs, and these words did not.
     *
     * The vault joins the list sealed under the **keyring** key, not under a passphrase
     * of its own. Recovering is how someone gets back in after losing that passphrase,
     * so re-sealing it under the credential they just proved they no longer have would
     * hand them a vault they still cannot open.
     *
     * Null for every failure — an index that is absent, one that will not open, a full
     * keyring — because the screen above reports one outcome for all of them.
     *
     * [vmk] stays the caller's and is zeroed by the caller.
     */
    fun recoverVaultOntoKeyring(store: BlobStore, name: String, vmk: ByteArray): Int? {
        if (keyring == null) return null

        val indexBlobId = VaultKeys.metadataBlobId(vmk)
        return try {
            runCatching { MetadataDb.load(store, indexBlobId, vmk) }.getOrNull()
                ?: return null
            addImported(store, name, vmk, indexBlobId)
        } finally {
            indexBlobId.fill(0)
        }
    }

    /**
     * Points the vault at [position] at [indexBlobId] — `index-pointer-recovery`.
     *
     * [editVault]'s shape exactly, and that similarity is the argument for doing the
     * repair here rather than on the unlock path: editing an entry needs the **keyring
     * key**, which this session holds for as long as the keyring is open, and not the
     * passphrase. An edit has never asked for one and neither does this.
     *
     * Called when a vault's recorded pointer stops naming an index and
     * [MetadataDb.findIndex] finds the real one. False where there is no session or no
     * such vault, so a caller that cannot repair learns it rather than assuming.
     */
    fun relinkIndex(store: BlobStore, position: Int, indexBlobId: ByteArray): Boolean {
        val ring = keyring ?: return false
        val keyringKey = key ?: return false
        val id = blobId ?: return false
        if (ring.at(position) == null) return false

        val updated = VaultKeyring.relinked(ring, position, indexBlobId)
        return runCatching {
            VaultKeyring.save(store, id, keyringKey, updated)
            keyring = updated
            true
        }.getOrDefault(false)
    }

    /**
     * Replaces one entry's name and appearance in **one** save — Edit vault, D8. Either both
     * change or neither does. Nothing inside the vault changes, and a vault with its own
     * passphrase is not asked for it: this is keyring data, under the keyring key.
     */
    fun editVault(store: BlobStore, position: Int, name: String, appearance: VaultAppearance): Boolean {
        val ring = keyring ?: return false
        val keyringKey = key ?: return false
        val id = blobId ?: return false
        if (ring.at(position) == null) return false

        val updated = VaultKeyring.edited(ring, position, name, appearance.pack())
        return runCatching {
            VaultKeyring.save(store, id, keyringKey, updated)
            keyring = updated
            true
        }.getOrDefault(false)
    }

    /**
     * Gives the unprotected vault at [position] a passphrase of its own — `vault-passphrase-manage`,
     * D1. The VMK is unsealed under the keyring entry key and re-sealed under the KEK
     * [deriveEntryKek] returns for this position and `RELEASED_KDF_PARAMS.lastIndex`, in one save.
     * Nothing inside the vault is rewritten.
     *
     * False — and nothing written — where no keyring is open, the entry is already protected, or
     * the save did not land. The KEK [deriveEntryKek] returns is zeroed here on every path; a caller
     * that needs it afterwards (the kit) keeps its own copy.
     */
    fun addVaultPassphrase(
        store: BlobStore,
        position: Int,
        deriveEntryKek: (position: Int, setIndex: Int) -> ByteArray,
    ): Boolean {
        val ring = keyring ?: return false
        val keyringKey = key ?: return false
        if (ring.at(position)?.hasOwnPassphrase != false) return false
        val vmk = VaultKeyring.unsealVaultKey(ring, keyringKey, position) ?: return false
        var newKek: ByteArray? = null
        return try {
            val set = VaultKeys.RELEASED_KDF_PARAMS.lastIndex
            newKek = deriveEntryKek(position, set)
            saveRing(store, VaultKeyring.protected(ring, newKek, position, vmk, set))
        } catch (e: Throwable) {
            Log.w(TAG, "a vault passphrase could not be added", e)
            false
        } finally {
            vmk.fill(0)
            newKek?.fill(0)
        }
    }

    /**
     * Re-seals the protected vault at [position] under a new passphrase — D1. [currentKek] must
     * open it; the new KEK comes from [deriveEntryKek] under `RELEASED_KDF_PARAMS.lastIndex`, which
     * the entry then records. One save. The VMK, and so the kit, do not change.
     *
     * False — and nothing written, and [deriveEntryKek] never called — for a wrong [currentKek] or an
     * unprotected entry. Both KEKs are zeroed here on every path.
     */
    fun changeVaultPassphrase(
        store: BlobStore,
        position: Int,
        currentKek: ByteArray,
        deriveEntryKek: (position: Int, setIndex: Int) -> ByteArray,
    ): Boolean {
        var vmk: ByteArray? = null
        var newKek: ByteArray? = null
        return try {
            val ring = keyring ?: return false
            if (ring.at(position)?.hasOwnPassphrase != true) return false
            vmk = VaultKeyring.unsealOwnPassphraseVaultKey(ring, currentKek, position) ?: return false
            val set = VaultKeys.RELEASED_KDF_PARAMS.lastIndex
            newKek = deriveEntryKek(position, set)
            saveRing(store, VaultKeyring.protected(ring, newKek, position, vmk, set))
        } catch (e: Throwable) {
            Log.w(TAG, "a vault passphrase could not be changed", e)
            false
        } finally {
            vmk?.fill(0)
            newKek?.fill(0)
            currentKek.fill(0)
        }
    }

    /**
     * Re-seals the protected vault at [position] under the keyring entry key, so the list opens it
     * on tap — D1. [currentKek] must open it. One save; the kit-view count is carried (D3).
     *
     * **The last step of a removal, never the first** — D4. `VaultLifecycle.removeVaultPassphrase`
     * moves the vault's captures home before calling this, so a kill before it leaves the vault
     * protected and its passphrase working. False — nothing written — for a wrong [currentKek] or an
     * unprotected entry. [currentKek] is zeroed here on every path.
     */
    fun removeVaultPassphrase(store: BlobStore, position: Int, currentKek: ByteArray): Boolean {
        var vmk: ByteArray? = null
        return try {
            val ring = keyring ?: return false
            val keyringKey = key ?: return false
            if (ring.at(position)?.hasOwnPassphrase != true) return false
            vmk = VaultKeyring.unsealOwnPassphraseVaultKey(ring, currentKek, position) ?: return false
            saveRing(store, VaultKeyring.unprotected(ring, keyringKey, position, vmk))
        } catch (e: Throwable) {
            Log.w(TAG, "a vault passphrase could not be removed", e)
            false
        } finally {
            vmk?.fill(0)
            currentKek.fill(0)
        }
    }

    /**
     * Takes back the listed entry at [position] for a vault recovered from its own words —
     * `protected-vault-destroy-gate` D6. Re-sealed under the keyring entry key, so the list opens
     * it on tap, and given [name], in **one** save; its position, appearance and kit count are
     * kept. The held keyring changes only once the save lands. [vmk] stays the caller's.
     */
    fun retakeEntry(store: BlobStore, position: Int, vmk: ByteArray, name: String): Boolean {
        val ring = keyring ?: return false
        val keyringKey = key ?: return false
        val entry = ring.at(position) ?: return false
        return runCatching {
            VaultKeyring.edited(VaultKeyring.unprotected(ring, keyringKey, position, vmk), position, name, entry.appearance)
        }.getOrNull()?.let { saveRing(store, it) } ?: false
    }

    /**
     * Whether a decoy has been built from the open keyring — `VaultKeyring.withDecoy`.
     *
     * False with no keyring open, which is the honest answer rather than a cached one:
     * the bit lives in the sealed block, so nothing can read it without the key that
     * opened it, and the settings sheet on a locked install has no vault list to draw
     * beside it either.
     */
    fun hasDecoy(): Boolean = keyring?.hasDecoy == true

    /**
     * Records that a decoy was built from this keyring, and re-seals the block.
     *
     * [editVault]'s shape exactly — the only kinds of write this file does are a
     * change to keyring data followed by one save — and idempotent through
     * `VaultKeyring.withDecoy`, so a second call after a save that already landed
     * rewrites nothing.
     *
     * Called on the way **back** from the decoy, when the user's own keyring is open
     * again. It cannot be called any earlier: while the decoy is being built the
     * session holds the decoy's keyring, and writing the bit then would put it in the
     * one place it must never appear.
     */
    fun markDecoyBuilt(store: BlobStore): Boolean {
        val ring = keyring ?: return false
        val keyringKey = key ?: return false
        val id = blobId ?: return false
        if (ring.hasDecoy) return true

        val updated = VaultKeyring.withDecoy(ring)
        return runCatching {
            VaultKeyring.save(store, id, keyringKey, updated)
            keyring = updated
            true
        }.getOrDefault(false)
    }

    /**
     * The mirror destination of the vault at [position], without opening it —
     * `delete-vault` D3, `protected-vault-destroy-gate` D2.
     *
     * Destroying a vault has to destroy its backup, and a backup that a vault carries
     * inside its own encrypted index cannot be found any other way. [vmk] is the key the
     * destroy dialog's gate unsealed, so a vault with its own passphrase names its backup
     * exactly as an unprotected one does. No session is opened; [vmk] stays the caller's.
     *
     * Null where the vault has no destination or the index will not read.
     */
    fun destinationAt(store: BlobStore, position: Int, vmk: ByteArray): String? {
        val entry = keyring?.at(position) ?: return null
        return try {
            MetadataDb.load(store, entry.indexBlobId, vmk).mirrorDestination
        } catch (e: Throwable) {
            Log.w(TAG, "a destination could not be read before removal", e)
            null
        }
    }

    /**
     * Removes the vault at [position] and destroys what it held — `protected-vault-destroy-gate`
     * D2.
     *
     * **The entry goes first.** Once it is gone the vault is unreachable, so a kill
     * part-way through the blob deletion leaves orphans the sweeper collects rather
     * than a listed vault whose contents are half gone.
     *
     * [vmk] is the vault's key, proven at the destroy dialog's gate, and it is required:
     * a removal that needs no key is how a vault with its own passphrase used to be removed
     * by whoever held the list's. It stays the caller's to zero.
     */
    fun removeVault(store: BlobStore, position: Int, vmk: ByteArray): Boolean {
        val ring = keyring ?: return false
        val keyringKey = key ?: return false
        val id = blobId ?: return false
        val entry = ring.at(position) ?: return false
        if (position == openPosition) leaveVault()

        val updated = VaultKeyring.without(ring, position)

        return runCatching {
            VaultKeyring.save(store, id, keyringKey, updated)
            keyring = updated

            // Best effort, and after the entry is gone: an item whose blob survives is
            // unreadable ciphertext, while an entry pointing at deleted blobs is a
            // vault that opens onto nothing.
            runCatching {
                MetadataDb.load(store, entry.indexBlobId, vmk).items
                    .flatMap { it.blobIds }
                    .forEach { blob -> runCatching { store.delete(blob) } }
            }
            runCatching { store.delete(entry.indexBlobId) }
            // `backup-to-cloud-apps`. The record of this vault's last backup file.
            runCatching { store.delete(VaultKeys.backupRecordBlobId(vmk)) }
            // `per-vault-intruder-capture`. The vault's enrolment, queued captures and notices
            // leave the keyring's capture store, and its own capture store goes with it.
            val captureKey = VaultKeys.captureStoreKey(keyringKey)
            try {
                runCatching {
                    CaptureStore.forget(store, OpenVault(captureKey, VaultKeys.metadataBlobId(captureKey), -1), entry.indexBlobId)
                }
            } finally {
                captureKey.fill(0)
            }
            runCatching { CaptureStore.destroy(store, vmk) }
            true
        }.getOrDefault(false)
    }

    /**
     * The payload a slot holds, for §4.1's biometric wrap — `vault-keyring`.
     *
     * **The wrap seals what a slot seals, and that is now a keyring reference.** It
     * always did: before this change a slot held a vault and the wrap held the same
     * vault, so `VaultSession.openVault()` was the right thing to hand it. Now a slot
     * holds a keyring, and a wrap holding a *vault* would open one vault directly,
     * outside every keyring on the install — with no entry, no name, and no way back
     * to it from the list.
     *
     * This is the one accessor that hands out the keyring key, and the wrap is the one
     * caller that must have it: sealing the slot's payload is what it is for. The
     * arrays are copies, and the caller owns them.
     */
    fun wrapPayload(): OpenVault? {
        val keyringKey = key ?: return null
        val id = blobId ?: return null
        return OpenVault(keyringKey.copyOf(), id.copyOf(), slotIndex)
    }

    /**
     * Runs [use] over the open keyring's key and returns what it produced.
     *
     * **A lambda and not an accessor**, [holdsKey]'s reason: the recovery kit needs
     * the key's bytes to encode 24 words from them, and handing the array out would
     * put the keyring key in a second place that then has to be zeroed by whoever
     * took it. The caller sees the bytes for the length of one call and keeps nothing.
     */
    fun <T> withKey(use: (ByteArray) -> T): T? = key?.let(use)

    /**
     * Whether [candidate] is the open keyring's own key — `ExportAuth`'s question.
     *
     * **A comparison and not an accessor.** The export gate has to know that the
     * passphrase just typed opens what is on screen, which needs the two keys compared
     * and nothing else; handing the key out to be compared elsewhere would put it in a
     * second place for no gain. Content-compares two 32-byte arrays, which is what it
     * did before this change when the thing being compared was a VMK.
     */
    fun holdsKey(candidate: ByteArray): Boolean {
        val held = key ?: return false
        return held.contentEquals(candidate)
    }

    /**
     * Seals [plain] under the open keyring — `screen-off-lock-all-screens`, D5. Null when no
     * keyring is open. Nonce first, then ciphertext and tag.
     */
    fun sealHeld(plain: ByteArray): ByteArray? {
        val keyringKey = key ?: return null
        val sealKey = VaultKeys.heldKitKey(keyringKey)
        return try {
            val nonce = ByteArray(HELD_NONCE_BYTES).also { rng.nextBytes(it) }
            val ciphertext = ByteArray(plain.size + HELD_TAG_BYTES)
            val sealed = Sodium.lazy.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                ciphertext, longArrayOf(0),
                plain, plain.size.toLong(),
                null, 0L,
                null, nonce, sealKey,
            )
            if (sealed) nonce + ciphertext else null
        } finally {
            sealKey.fill(0)
        }
    }

    /**
     * Opens what [sealHeld] sealed, under the keyring open **now**. Null when no keyring is
     * open or when this is not the keyring that sealed it — the two are not told apart.
     */
    fun openHeld(sealed: ByteArray): ByteArray? {
        val keyringKey = key ?: return null
        if (sealed.size < HELD_NONCE_BYTES + HELD_TAG_BYTES) return null
        val sealKey = VaultKeys.heldKitKey(keyringKey)
        return try {
            val nonce = sealed.copyOfRange(0, HELD_NONCE_BYTES)
            val ciphertext = sealed.copyOfRange(HELD_NONCE_BYTES, sealed.size)
            val plain = ByteArray(ciphertext.size - HELD_TAG_BYTES)
            val opened = Sodium.lazy.cryptoAeadXChaCha20Poly1305IetfDecrypt(
                plain, longArrayOf(0),
                null,
                ciphertext, ciphertext.size.toLong(),
                null, 0L,
                nonce, sealKey,
            )
            if (opened) {
                plain
            } else {
                plain.fill(0)
                null
            }
        } finally {
            sealKey.fill(0)
        }
    }

    private const val HELD_NONCE_BYTES = 24
    private const val HELD_TAG_BYTES = 16

    /** Everything this file holds, gone. Called by lock and by nothing else. */
    fun lock() {
        leaveVault()
        key?.fill(0)
        blobId?.fill(0)
        key = null
        blobId = null
        keyring = null
        slotIndex = -1
        openPosition = -1
    }
}
