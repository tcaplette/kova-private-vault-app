package com.app.vault

import java.security.SecureRandom

/**
 * The list of vaults one master passphrase opens — `vault-keyring`.
 *
 * **Where a vault's key lives.** Until this, a slot held a vault master key directly
 * and one passphrase opened one vault. A slot now holds a *keyring* key and the
 * keyring's blob ID — the same 32 and 16 bytes at the same offsets — and the keyring
 * holds an entry per vault: its name, its own VMK, and the ID of its index.
 *
 * **Two layers of sealing, deliberately.** The keyring blob is sealed under the
 * keyring key. Inside it, each entry's VMK is sealed *again* under
 * [VaultKeys.keyringEntryKey], derived from the keyring key and the entry's position.
 * So [parse] yields the names and nothing else, and a vault key exists only between
 * [unsealVaultKey] and the caller zeroing it. The alternative — one seal over the
 * whole list — puts every vault's key in memory the moment the user unlocks, which is
 * the property this whole change otherwise weakens, so it is the one place not to
 * economise.
 *
 * **Every position is written, occupied or not.** The plaintext is a fixed
 * [CAPACITY] × [ENTRY_BYTES], so a keyring holding one vault and a keyring holding
 * sixteen are the same size in the store. The count is private from anyone holding
 * the ciphertext, which is the same argument §6 makes for every other blob's padding.
 *
 * **The position is the AAD of its own entry**, so an entry cannot be moved to another
 * position by anyone who cannot open it — `SlotArray`'s rule about the slot index, one
 * level in.
 *
 * The structure is bytes in, bytes out — [load] and [save] at the bottom are the only
 * two functions that know a store exists, and everything above them can be exercised
 * without one.
 */
object VaultKeyring {

    /**
     * How many vaults one keyring holds.
     *
     * **Sixteen, and it is a format constant.** Not eight, which would collide with
     * the slot count and invite the reading that an entry *is* a slot — it is not, and
     * the two numbers are unrelated. Raising it later is a format change and should
     * be, for the reason `FORMAT.md` fixes the slot count forever: a length that moves
     * is a length that says something.
     *
     * The ceiling is per keyring, not per install. A second master passphrase is a
     * second keyring.
     */
    const val CAPACITY = 16

    /**
     * The structure's own version, ahead of every entry.
     *
     * **8 since `vault-passphrase-manage`**, which keeps an entry's kit-view count whether or not
     * it is protected, so removing and re-adding a passphrase does not mint views — see
     * [ENTRY_KIT_VIEWS_OFFSET]. **7 since `vault-list-refresh`**, which gives each entry its
     * appearance — see [APPEARANCE_OFFSET]. **6 since `recovery-kit-three-views`**, which records how many times the keyring's kit and
     * each protected vault's kit have been viewed since creation — see [LIST_KIT_VIEWS_OFFSET] and
     * [ENTRY_KIT_VIEWS_OFFSET]. **5 since `backup-file-recover-and-import`**, which derives a protected entry's key from
     * the keyring rather than the install and records the parameter set it was sealed under —
     * see [KDF_SET_OFFSET]. **4 since `panic-phrase`**, which gave the keyring its panic slot
     * and its home slot — see [PANIC_SLOT_OFFSET]. **3 since `slot-occupancy-map`**, which gave the keyring a record of the slots it
     * knows to be occupied — see [KNOWN_SLOTS_OFFSET]. **2 since
     * `per-vault-passphrases`**, which gave an entry a second way to be sealed and one
     * byte to say which. Each older version means what it always meant, so [parse]
     * reads one without migrating it and the next write stores it as [VERSION] with nothing
     * about any entry changed. See [VERSION_UNPROTECTED], [VERSION_NO_KNOWN_SLOTS],
     * [VERSION_NO_PANIC_SLOT], [VERSION_INSTALL_BOUND], [VERSION_NO_KIT_VIEWS] and
     * [VERSION_NO_APPEARANCE] and [VERSION_UNPROTECTED_NO_KIT_VIEWS].
     */
    const val VERSION = 8

    /**
     * The version written before an unprotected entry kept its kit-view count —
     * `vault-passphrase-manage`, D3.
     *
     * Read, never written. In such a block the byte at [ENTRY_KIT_VIEWS_OFFSET] is CSPRNG filler on
     * every unprotected entry, so an unprotected entry reads 0 used; a protected one reads its count.
     */
    const val VERSION_UNPROTECTED_NO_KIT_VIEWS = 7

    /**
     * The version written before an entry recorded its appearance — `vault-list-refresh`.
     *
     * Read, never written. Every entry in such a block shows its letter with no colour: the
     * byte at [APPEARANCE_OFFSET] was the high byte of a name length that never exceeded
     * [MAX_NAME_BYTES], so it was 0 in every block that version wrote, and 0 is what it means.
     */
    const val VERSION_NO_APPEARANCE = 6

    /**
     * The version written before the keyring recorded kit views — `recovery-kit-three-views`.
     *
     * Read, never written. Such a block has viewed no kit since creation: the two bytes are the
     * CSPRNG filler that version wrote there, and 0 used is what it means.
     */
    const val VERSION_NO_KIT_VIEWS = 5

    /** How many times a kit can be viewed after creation — `recovery-kit-three-views`. */
    const val KIT_VIEWS = 3

    /**
     * The version written before a protected entry's key became portable —
     * `backup-file-recover-and-import`, D2.
     *
     * Read, never written. A protected entry in such a block was sealed under a key derived
     * from the install salt of the phone that wrote it, and records no parameter set, so its
     * own passphrase does not open it here; its own recovery kit does. The entry keeps its
     * form when the block is written back as [VERSION].
     */
    const val VERSION_INSTALL_BOUND = 4

    /**
     * The version written before the keyring recorded a panic slot — `panic-phrase`.
     *
     * Read, never written. Such a block has no panic phrase: offsets 188 and 189 are the
     * CSPRNG filler that version wrote there, and "none" is what it means.
     */
    const val VERSION_NO_PANIC_SLOT = 3

    /**
     * The version written before the keyring recorded which slots it knows about.
     *
     * Read, never written. Such a block knows its own slot and no other — that is what
     * the version *means*, not a default being guessed at, exactly as
     * [VERSION_UNPROTECTED] means every entry is unprotected.
     */
    const val VERSION_NO_KNOWN_SLOTS = 2

    /**
     * The version written before an entry could carry its own passphrase.
     *
     * Read, never written. Every entry in such a block is unprotected by definition,
     * because there was no other kind when it was written.
     */
    const val VERSION_UNPROTECTED = 1

    /**
     * The header byte's low seven bits — [VERSION] and [VERSION_UNPROTECTED] both fit.
     *
     * Seven and not eight since the header gained [DECOY_FLAG]. Nothing else about the
     * byte changed: a keyring with no decoy still serialises to exactly [VERSION], which
     * is what every block written before the flag existed already says.
     */
    private const val VERSION_MASK = 0x7F

    /**
     * The header byte's top bit: **a decoy has been built from this keyring.**
     *
     * ***Why the keyring holds this and nothing else does.*** The state has to survive
     * the process, be readable wherever the settings sheet is drawn, and be invisible
     * from inside the decoy — and only this block satisfies all three. A preference is
     * device-wide, so the decoy's own settings sheet would announce that a decoy exists
     * to exactly the person the decoy is being handed to. A vault's tamper log is
     * per-vault, so a second vault on the same keyring would offer to build a second
     * decoy for an install that already has one, and neither could be read at all from
     * the vault list, where no vault is open. The decoy is built with the master
     * passphrase's authorisation and stands in for the whole install, which is the
     * keyring's scope exactly.
     *
     * **A bit in the header rather than a byte of its own**, so [PLAINTEXT_BYTES] does
     * not move: the stored length must not vary, and a length that changed with a build
     * would say more about the install than the sealed block does. It sits inside the
     * sealed plaintext, so like the per-entry flag it says what it says only to someone
     * who has already opened this keyring.
     */
    private const val DECOY_FLAG = 0x80

    /** Longest vault name, in UTF-8 bytes. The field is fixed width whatever is in it. */
    const val MAX_NAME_BYTES = 94

    private const val VERSION_BYTES = 1
    private const val OCCUPIED_BYTES = 1
    private const val NAME_LENGTH_BYTES = 1
    private const val APPEARANCE_BYTES = 1
    private const val NAME_FIELD_BYTES = NAME_LENGTH_BYTES + APPEARANCE_BYTES + MAX_NAME_BYTES   // 96

    private const val NONCE_BYTES = 24
    private const val TAG_BYTES = 16
    private const val SEALED_KEY_BYTES = NONCE_BYTES + VaultKeys.VMK_BYTES + TAG_BYTES // 72

    /** One position: occupancy, name, sealed key, index ID, then filler to a fixed width. */
    const val ENTRY_BYTES = 192

    private const val OCCUPIED_OFFSET = 0
    private const val NAME_OFFSET = OCCUPIED_OFFSET + OCCUPIED_BYTES                  // 1

    /**
     * The entry's appearance, [VaultAppearance.pack]ed — `vault-list-refresh`, D1.
     *
     * **The old u16 name length's high byte.** Up to version 6, offset 1 was a little-endian
     * u16 length, and with names capped at [MAX_NAME_BYTES] its high byte was 0 in every keyring
     * ever written. Offset 1 is now a u8 length and this is that byte, so the name text, the name
     * field's width and every later offset stay where they were, and [PLAINTEXT_BYTES] does not
     * move. Written for an occupied position only.
     */
    private const val APPEARANCE_OFFSET = NAME_OFFSET + NAME_LENGTH_BYTES               // 2
    private const val NAME_TEXT_OFFSET = APPEARANCE_OFFSET + APPEARANCE_BYTES           // 3
    private const val SEALED_KEY_OFFSET = NAME_OFFSET + NAME_FIELD_BYTES              // 97
    private const val INDEX_ID_OFFSET = SEALED_KEY_OFFSET + SEALED_KEY_BYTES          // 169

    /**
     * Whether this entry is sealed under its own passphrase — `per-vault-passphrases`.
     *
     * **One byte out of the seven the entry already had spare**, so nothing about the
     * layout moves: [ENTRY_BYTES] is what it was, and therefore so is
     * [PLAINTEXT_BYTES] and the keyring blob's stored length, which `vault-keyring`
     * forbids varying with what the keyring holds.
     *
     * It sits inside the sealed plaintext, so it says what it says only to someone who
     * already opened the keyring.
     */
    private const val PROTECTED_OFFSET = INDEX_ID_OFFSET + VaultKeys.BLOB_ID_BYTES    // 185
    private const val PROTECTED_BYTES = 1
    private const val ENTRY_USED_BYTES = PROTECTED_OFFSET + PROTECTED_BYTES           // 186

    /** The whole plaintext. Constant, whatever the keyring holds. */
    const val PLAINTEXT_BYTES = VERSION_BYTES + CAPACITY * ENTRY_BYTES

    /**
     * The slots this keyring knows to be occupied — `slot-occupancy-map`, D1.
     *
     * **A keyring-level field that happens to live inside position 0's reserved tail.**
     * It is not part of the entry, it is written whether or not position 0 is occupied,
     * and clearing that entry does not clear it. That is the one wrinkle in the layout
     * and it is written down here rather than left to be discovered.
     *
     * **Why a reserved byte and not a new one at the end.** [PLAINTEXT_BYTES] must not
     * move: [load] reads exactly that many bytes and [parse] returns null on any other
     * length, so growing the plaintext makes every keyring blob already on disk
     * unparseable and needs a migration that rewrites a blob whose padded stored length
     * is itself part of the format (`FORMAT.md` §7). Stealing one of the six bytes the
     * entry already reserves costs none of that — same plaintext length, same stored
     * length, same size bucket.
     *
     * The value is **opaque in this file**. It is an 8-bit pattern here and nothing
     * more; only `VaultLifecycle` and `VaultKeyringSession` are permitted to read it as
     * slots, which is what `MultiVaultScanTest` enforces.
     */
    private const val KNOWN_SLOTS_OFFSET = VERSION_BYTES + ENTRY_USED_BYTES           // 187

    /**
     * **Resolved at first seal, not at class load.** `Sodium.lazy` builds a JNI binding
     * that only exists on a device, and holding it in an eagerly-initialised field made
     * merely *touching* this object fail off-device — which put [serialise] and [parse],
     * which are pure bytes and open nothing, out of reach of a JVM test. One `by lazy`
     * moves that cost to the first call that actually needs a cipher. Still one binding,
     * for the reason `Sodium` exists at all.
     */
    /**
     * The slot this keyring's panic phrase opens, and the slot its own passphrase opens —
     * `panic-phrase`, D2. Each is 0 for none, otherwise slot + 1.
     *
     * **Two more of position 0's reserved bytes**, beside [KNOWN_SLOTS_OFFSET] and for its
     * reason: [PLAINTEXT_BYTES] must not move. Opaque here exactly as the occupancy record
     * is — only `VaultLifecycle` and `VaultKeyringSession` read them as slots.
     *
     * The home slot is recorded because at a panic unlock the slot that opened is the
     * *panic* slot, and [Keyring.knownSlots] cannot tell this keyring's own slot from a
     * decoy's.
     */
    private const val PANIC_SLOT_OFFSET = KNOWN_SLOTS_OFFSET + 1                       // 188
    private const val HOME_SLOT_OFFSET = PANIC_SLOT_OFFSET + 1                         // 189

    /**
     * The parameter set a protected entry was sealed under, as its index in
     * `VaultKeys.RELEASED_KDF_PARAMS` plus one — `backup-file-recover-and-import`, D2.
     *
     * **Entry-relative 189, one byte past the three slot records**, which sit at 186–188 of
     * position 0 only; 189 is free in every position, so [PLAINTEXT_BYTES] does not move.
     * Written for a protected entry, 0 for an unprotected one, and left as filler on an
     * unoccupied position — [PROTECTED_OFFSET]'s rule.
     */
    private const val KDF_SET_OFFSET = ENTRY_USED_BYTES + 3                            // 189

    /**
     * How many times an entry's own kit has been viewed since creation, 0 to
     * [KIT_VIEWS] — `recovery-kit-three-views`, D1. Entry-relative 190, free in every position.
     * Written for every occupied entry since [VERSION] 8 — `vault-passphrase-manage`, D3 — so the
     * count survives the passphrase being removed and added again. An unprotected entry has no kit
     * of its own to show; the count waits there for the day it is protected again.
     */
    private const val ENTRY_KIT_VIEWS_OFFSET = KDF_SET_OFFSET + 1                      // 190

    /**
     * How many times this keyring's own kit has been viewed since creation — D1. A keyring-level
     * field in position 0's last spare byte, entry-relative 191, written at every save whether or
     * not that position is occupied, as [KNOWN_SLOTS_OFFSET] is.
     */
    private const val LIST_KIT_VIEWS_OFFSET = VERSION_BYTES + ENTRY_KIT_VIEWS_OFFSET + 1 // 192

    private val sodium by lazy { Sodium.lazy }
    private val rng = SecureRandom()

    /**
     * One vault, as the keyring knows it.
     *
     * [sealedKey] is the VMK **still sealed** — this type is what [parse] returns, and
     * a parsed keyring deliberately holds no usable key. [unsealVaultKey] is the only
     * way through, and it takes the keyring key the caller had to have anyway.
     */
    class Entry(
        val position: Int,
        val name: String,
        val sealedKey: ByteArray,
        val indexBlobId: ByteArray,
        /**
         * Whether [sealedKey] is sealed under this vault's **own** passphrase rather
         * than under the keyring entry key — `per-vault-passphrases`.
         *
         * Named for what it is rather than `protected`, which is a Kotlin modifier and
         * would have to be written in backticks at every use.
         *
         * The list reads this to draw the row as locked. Nothing else about the entry
         * differs: same field, same offset, same length either way.
         */
        val hasOwnPassphrase: Boolean = false,
        /**
         * Which `VaultKeys.RELEASED_KDF_PARAMS` set sealed a protected entry, or -1 —
         * [KDF_SET_OFFSET]. -1 on an unprotected entry, and on a protected one written
         * before [VERSION], which its passphrase therefore cannot open.
         */
        val kdfSet: Int = -1,
        /** Views of the entry's own kit since creation, carried while unprotected — [ENTRY_KIT_VIEWS_OFFSET]. */
        val kitViewsUsed: Int = 0,
        /** [VaultAppearance.pack]ed icon and colour — [APPEARANCE_OFFSET]. */
        val appearance: Int = 0,
    )

    /** A keyring's entries, by position. A null position is unoccupied. */
    class Keyring(
        val entries: Array<Entry?>,
        /**
         * Whether a decoy has been built from this keyring — [DECOY_FLAG].
         *
         * A property of the keyring and not of any entry: a decoy is a keyring of its
         * own in a slot of its own, and what this records is that the one holding it
         * already made one. Every function here that returns a modified keyring carries
         * it through unchanged, because adding, renaming, relinking or removing a vault
         * has nothing to say about it.
         */
        val hasDecoy: Boolean = false,
        /**
         * The slots this keyring knows to be occupied — [KNOWN_SLOTS_OFFSET].
         *
         * **Opaque here.** An 8-bit value this file stores and returns and never reads
         * as slots; interpreting it is `VaultLifecycle`'s and `VaultKeyringSession`'s,
         * and `MultiVaultScanTest` fails the build if it becomes anyone else's.
         *
         * Carried through unchanged by every function here that returns a modified
         * keyring, exactly as [hasDecoy] is: adding, renaming, relinking or removing a
         * vault has nothing to say about which slots hold keyrings.
         */
        val knownSlots: Int = 0,
        /**
         * The slot this keyring's panic phrase opens, or -1 — [PANIC_SLOT_OFFSET].
         * Opaque here, carried through unchanged like [knownSlots].
         */
        val panicSlot: Int = -1,
        /** The slot this keyring's own passphrase opens, or -1 — [HOME_SLOT_OFFSET]. */
        val homeSlot: Int = -1,
        /** Views of this keyring's own kit since creation — [LIST_KIT_VIEWS_OFFSET]. */
        val kitViewsUsed: Int = 0,
    ) {

        /** In position order, which is creation order — a list nobody has to sort. */
        val vaults: List<Entry> get() = entries.filterNotNull()

        val isFull: Boolean get() = entries.all { it != null }

        val isEmpty: Boolean get() = entries.all { it == null }

        fun at(position: Int): Entry? = entries.getOrNull(position)

        /** The first unoccupied position, or null when the keyring is full. */
        fun firstFree(): Int? = entries.indexOfFirst { it == null }.takeIf { it >= 0 }
    }

    fun empty(): Keyring = Keyring(arrayOfNulls(CAPACITY))

    /**
     * [keyring] with [vmk] sealed into [position] under [name].
     *
     * The VMK is sealed here and **not** retained: the returned keyring holds
     * ciphertext, exactly as a parsed one does, so there is no path where an entry
     * list carries a usable key around.
     *
     * The caller still owns [vmk] and still zeroes it.
     */
    fun withEntry(
        keyring: Keyring,
        keyringKey: ByteArray,
        position: Int,
        name: String,
        vmk: ByteArray,
        indexBlobId: ByteArray,
        appearance: Int = 0,
    ): Keyring {
        require(position in 0 until CAPACITY) { "entry position out of range" }
        require(vmk.size == VaultKeys.VMK_BYTES) { "VMK must be ${VaultKeys.VMK_BYTES} bytes" }
        require(indexBlobId.size == VaultKeys.BLOB_ID_BYTES) { "blob ID must be 16 bytes" }
        require(fits(name)) { "name is longer than $MAX_NAME_BYTES bytes" }

        val entries = keyring.entries.copyOf()
        // **[indexBlobId] is copied, not held.** Every caller of this is on a path that
        // zeroes its own arrays in a `finally` — `VaultLifecycle`'s import does, the
        // migration does — and an entry holding the caller's array is an entry whose
        // blob ID becomes sixteen zeroes the moment that `finally` runs. It cost an
        // imported vault its index before this line said `copyOf`.
        entries[position] = Entry(
            position, name, seal(keyringKey, position, vmk), indexBlobId.copyOf(), appearance = appearance,
        )
        return Keyring(entries, keyring.hasDecoy, keyring.knownSlots, keyring.panicSlot, keyring.homeSlot, keyring.kitViewsUsed)
    }

    /**
     * [keyring] with [vmk] sealed into [position] under **[entryKek]**, the key derived
     * from that vault's own passphrase — `per-vault-passphrases`.
     *
     * [withEntry]'s twin, and deliberately a separate function rather than a nullable
     * parameter on it: the two seal under different keys, and a caller that passed null
     * by accident would get a vault the master passphrase opens while the list told the
     * user otherwise. Two names cannot be confused that way.
     *
     * The keyring's key is **not** an input here and cannot open the result. That is
     * the whole of what the feature is: a second lock that the first credential does
     * not carry.
     *
     * [entryKek] and [vmk] both stay the caller's, and the caller zeroes both.
     */
    fun withProtectedEntry(
        keyring: Keyring,
        entryKek: ByteArray,
        position: Int,
        name: String,
        vmk: ByteArray,
        indexBlobId: ByteArray,
        /** The `VaultKeys.RELEASED_KDF_PARAMS` index [entryKek] was derived under. */
        kdfSet: Int,
        appearance: Int = 0,
    ): Keyring {
        require(position in 0 until CAPACITY) { "entry position out of range" }
        require(kdfSet in 0 until 0xFF) { "parameter set index out of range" }
        require(entryKek.size == VaultKeys.KEY_BYTES) { "entry KEK must be ${VaultKeys.KEY_BYTES} bytes" }
        require(vmk.size == VaultKeys.VMK_BYTES) { "VMK must be ${VaultKeys.VMK_BYTES} bytes" }
        require(indexBlobId.size == VaultKeys.BLOB_ID_BYTES) { "blob ID must be 16 bytes" }
        require(fits(name)) { "name is longer than $MAX_NAME_BYTES bytes" }

        val entries = keyring.entries.copyOf()
        entries[position] = Entry(
            position, name, sealUnder(entryKek, position, vmk), indexBlobId.copyOf(),
            hasOwnPassphrase = true, kdfSet = kdfSet, appearance = appearance,
        )
        return Keyring(entries, keyring.hasDecoy, keyring.knownSlots, keyring.panicSlot, keyring.homeSlot, keyring.kitViewsUsed)
    }

    /**
     * [keyring] with the existing entry at [position] re-sealed under **[entryKek]**, its own
     * passphrase's key — `vault-passphrase-manage`, D1. Used by Add and by Change.
     *
     * The VMK is the same one; only its seal moves. Name, appearance, index pointer and kit-view
     * count go through, so nothing inside the vault is rewritten. [withProtectedEntry]'s twin for
     * an entry that already exists, and [unprotected]'s opposite — two names, never a nullable key.
     *
     * [entryKek] and [vmk] stay the caller's, and the caller zeroes both.
     */
    fun protected(keyring: Keyring, entryKek: ByteArray, position: Int, vmk: ByteArray, kdfSet: Int): Keyring {
        val e = requireNotNull(keyring.at(position)) { "no entry at $position" }
        require(kdfSet in 0 until 0xFF) { "parameter set index out of range" }
        require(entryKek.size == VaultKeys.KEY_BYTES) { "entry KEK must be ${VaultKeys.KEY_BYTES} bytes" }
        require(vmk.size == VaultKeys.VMK_BYTES) { "VMK must be ${VaultKeys.VMK_BYTES} bytes" }
        val entries = keyring.entries.copyOf()
        entries[position] = Entry(
            position, e.name, sealUnder(entryKek, position, vmk), e.indexBlobId.copyOf(),
            hasOwnPassphrase = true, kdfSet = kdfSet, kitViewsUsed = e.kitViewsUsed, appearance = e.appearance,
        )
        return Keyring(entries, keyring.hasDecoy, keyring.knownSlots, keyring.panicSlot, keyring.homeSlot, keyring.kitViewsUsed)
    }

    /**
     * [keyring] with the existing entry at [position] re-sealed under the keyring entry key, so the
     * list's passphrase opens it again — Remove, `vault-passphrase-manage`, D1. [protected]'s
     * opposite, carrying the same fields; the kit-view count stays for a later Add (D3).
     *
     * [vmk] stays the caller's, and the caller zeroes it.
     */
    fun unprotected(keyring: Keyring, keyringKey: ByteArray, position: Int, vmk: ByteArray): Keyring {
        val e = requireNotNull(keyring.at(position)) { "no entry at $position" }
        require(vmk.size == VaultKeys.VMK_BYTES) { "VMK must be ${VaultKeys.VMK_BYTES} bytes" }
        val entries = keyring.entries.copyOf()
        entries[position] = Entry(
            position, e.name, seal(keyringKey, position, vmk), e.indexBlobId.copyOf(),
            kitViewsUsed = e.kitViewsUsed, appearance = e.appearance,
        )
        return Keyring(entries, keyring.hasDecoy, keyring.knownSlots, keyring.panicSlot, keyring.homeSlot, keyring.kitViewsUsed)
    }

    /**
     * [keyring] with the vault at [position] pointed at a different index blob —
     * `index-pointer-recovery`.
     *
     * [edited]'s shape and for the same reason: the sealed key and the own-passphrase
     * flag go through untouched, because relinking is a change to *keyring data* and
     * must not need — or disturb — whatever seals the vault. Only the one entry moves.
     *
     * Used when a vault's recorded pointer stops naming its index and the index is
     * found again in the pool. Every other route to this field writes it at creation
     * and never again.
     */
    fun relinked(keyring: Keyring, position: Int, indexBlobId: ByteArray): Keyring {
        val existing = keyring.at(position) ?: return keyring
        val entries = keyring.entries.copyOf()
        entries[position] = Entry(
            position, existing.name, existing.sealedKey, indexBlobId.copyOf(),
            existing.hasOwnPassphrase, existing.kdfSet, existing.kitViewsUsed, existing.appearance,
        )
        return Keyring(entries, keyring.hasDecoy, keyring.knownSlots, keyring.panicSlot, keyring.homeSlot, keyring.kitViewsUsed)
    }

    /**
     * [keyring] with [position]'s name and appearance replaced — Edit vault, D8. Nothing else
     * about the entry moves, and both halves land in the one keyring the caller saves once.
     */
    fun edited(keyring: Keyring, position: Int, name: String, appearance: Int): Keyring {
        val existing = keyring.at(position) ?: return keyring
        require(fits(name)) { "name is longer than $MAX_NAME_BYTES bytes" }
        val entries = keyring.entries.copyOf()
        // The sealed key and the flag go through untouched: an edit is a change to
        // keyring data and must not need — or disturb — whatever seals the vault.
        entries[position] = Entry(
            position, name, existing.sealedKey, existing.indexBlobId, existing.hasOwnPassphrase,
            existing.kdfSet, existing.kitViewsUsed, appearance,
        )
        return Keyring(entries, keyring.hasDecoy, keyring.knownSlots, keyring.panicSlot, keyring.homeSlot, keyring.kitViewsUsed)
    }

    /** [keyring] without [position]. The position becomes free for the next vault. */
    fun without(keyring: Keyring, position: Int): Keyring {
        val entries = keyring.entries.copyOf()
        entries.getOrNull(position) ?: return keyring
        entries[position] = null
        return Keyring(entries, keyring.hasDecoy, keyring.knownSlots, keyring.panicSlot, keyring.homeSlot, keyring.kitViewsUsed)
    }

    /**
     * [keyring] with its decoy recorded. Idempotent — a keyring that already carries
     * one comes back unchanged, because a decoy is built once and the bit is not a
     * count.
     */
    fun withDecoy(keyring: Keyring): Keyring =
        if (keyring.hasDecoy) keyring
        else Keyring(
            keyring.entries.copyOf(), hasDecoy = true, knownSlots = keyring.knownSlots,
            panicSlot = keyring.panicSlot, homeSlot = keyring.homeSlot, kitViewsUsed = keyring.kitViewsUsed,
        )

    /** Whether [name] fits the fixed field. Checked before an edit or a creation, never after. */
    fun fits(name: String): Boolean =
        name.toByteArray(Charsets.UTF_8).size in 1..MAX_NAME_BYTES

    /**
     * The vault key at [position], or null when the entry will not open.
     *
     * **The only way a vault key leaves a keyring.** The caller owns the result and
     * zeroes it when the vault is left — `vault-keyring`'s "at most one vault's key
     * exists in memory" is a statement about who calls this and when.
     */
    fun unsealVaultKey(keyring: Keyring, keyringKey: ByteArray, position: Int): ByteArray? {
        val entry = keyring.at(position) ?: return null
        val entryKey = VaultKeys.keyringEntryKey(keyringKey, position)
        return try {
            openUnder(entry, entryKey, position)
        } finally {
            entryKey.fill(0)
        }
    }

    /**
     * The vault key at [position] under a KEK derived from that vault's **own**
     * passphrase — `per-vault-passphrases`.
     *
     * Null for a wrong passphrase and null for an entry that is not protected, on the
     * same code path: an unprotected entry is sealed under a key this one is not, so it
     * fails AEAD authentication exactly as a wrong passphrase does. The caller never
     * has to ask which happened, and the answer is the same either way — this did not
     * open the vault.
     *
     * [entryKek] stays the caller's. The returned key is the caller's to zero, exactly
     * as [unsealVaultKey]'s is.
     */
    fun unsealOwnPassphraseVaultKey(
        keyring: Keyring,
        entryKek: ByteArray,
        position: Int,
    ): ByteArray? {
        val entry = keyring.at(position) ?: return null
        require(entryKek.size == VaultKeys.KEY_BYTES) { "entry KEK must be ${VaultKeys.KEY_BYTES} bytes" }
        return openUnder(entry, entryKek, position)
    }

    /** One AEAD open, shared so the two unseal paths cannot drift apart. */
    private fun openUnder(entry: Entry, key: ByteArray, position: Int): ByteArray? {
        val nonce = entry.sealedKey.copyOfRange(0, NONCE_BYTES)
        val ciphertext = entry.sealedKey.copyOfRange(NONCE_BYTES, entry.sealedKey.size)
        val vmk = ByteArray(VaultKeys.VMK_BYTES)
        val opened = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            vmk, longArrayOf(0),
            null,
            ciphertext, ciphertext.size.toLong(),
            aad(position), 1L,
            nonce, key,
        )
        return if (opened) {
            vmk
        } else {
            vmk.fill(0)
            null
        }
    }

    // --- bytes ----------------------------------------------------------------

    /**
     * The keyring as a fixed [PLAINTEXT_BYTES] block.
     *
     * **An unoccupied position is CSPRNG bytes**, not zeroes: this plaintext is sealed
     * before it reaches the store, so the filler never faces an attacker — but a block
     * of zeroes is the kind of thing a later change starts skipping to save space, and
     * skipping it is what makes the length talk. Random filler cannot be compressed
     * away by accident.
     */
    fun serialise(keyring: Keyring): ByteArray {
        val out = ByteArray(PLAINTEXT_BYTES).also { rng.nextBytes(it) }
        // Version in the low seven bits, the decoy bit on top. A keyring with no decoy
        // writes the same byte it always wrote.
        out[0] = (VERSION or if (keyring.hasDecoy) DECOY_FLAG else 0).toByte()

        for (position in 0 until CAPACITY) {
            val base = VERSION_BYTES + position * ENTRY_BYTES
            val entry = keyring.at(position)
            if (entry == null) {
                // Occupancy is the one byte that must be honest; the rest of the
                // position stays as the random it was filled with.
                out[base + OCCUPIED_OFFSET] = 0
                continue
            }
            out[base + OCCUPIED_OFFSET] = 1

            val name = entry.name.toByteArray(Charsets.UTF_8)
            out[base + NAME_OFFSET] = name.size.toByte()
            out[base + APPEARANCE_OFFSET] = entry.appearance.toByte()
            name.copyInto(out, base + NAME_TEXT_OFFSET)
            // The rest of the name field keeps its random filler, so two names of
            // different lengths leave no different trace inside the sealed block.

            entry.sealedKey.copyInto(out, base + SEALED_KEY_OFFSET)
            entry.indexBlobId.copyInto(out, base + INDEX_ID_OFFSET)
            // Written only for an occupied position, exactly as occupancy is. An
            // unoccupied position keeps the random filler it was given, so nothing
            // about it can be read as a claim either way.
            out[base + PROTECTED_OFFSET] = if (entry.hasOwnPassphrase) 1 else 0
            out[base + KDF_SET_OFFSET] = if (entry.hasOwnPassphrase && entry.kdfSet >= 0) (entry.kdfSet + 1).toByte() else 0
            out[base + ENTRY_KIT_VIEWS_OFFSET] = entry.kitViewsUsed.toByte()
        }
        // **After the loop and outside it** — [KNOWN_SLOTS_OFFSET] is a keyring-level
        // field that happens to sit in position 0's reserved tail, so it is written at
        // every version-3 save whether or not that position is occupied, and the
        // `continue` above must not be able to skip it.
        out[KNOWN_SLOTS_OFFSET] = (keyring.knownSlots and 0xFF).toByte()
        out[PANIC_SLOT_OFFSET] = slotByte(keyring.panicSlot)
        out[HOME_SLOT_OFFSET] = slotByte(keyring.homeSlot)
        out[LIST_KIT_VIEWS_OFFSET] = keyring.kitViewsUsed.toByte()
        return out
    }

    /**
     * A keyring from [bytes], or null when the block is not one.
     *
     * Null rather than an exception for the unlock path's reason: a keyring that will
     * not parse and a passphrase that opens nothing have to be the same outcome from
     * outside, and an exception carries a message that a caller might report.
     */
    fun parse(bytes: ByteArray): Keyring? {
        if (bytes.size != PLAINTEXT_BYTES) return null
        // Eight versions are readable and one is writable. A version 7 block wrote the kit-view
        // count for protected entries only, so an unprotected entry in it reads 0 used. A version 6 block predates the
        // appearance, so every entry in it shows its letter with no colour. A version 5 block predates the kit-view
        // record, so no kit in it has been viewed since creation. A version 4 block predates the
        // portable protected key, so a protected entry in it records no parameter set and
        // opens only with its own kit. A version 3 block predates the
        // panic record, so it has no panic phrase. A version 1 block predates
        // the second sealing form, so every entry in it is unprotected; a version 2
        // block predates the occupancy record, so it knows its own slot and no other —
        // that is what each version *means*, not a default being guessed at. Anything
        // else is refused rather than interpreted.
        val header = bytes[0].toInt() and 0xFF
        val version = header and VERSION_MASK
        if (version !in VERSION_UNPROTECTED..VERSION) return null

        val entries = arrayOfNulls<Entry>(CAPACITY)
        for (position in 0 until CAPACITY) {
            val base = VERSION_BYTES + position * ENTRY_BYTES
            if (bytes[base + OCCUPIED_OFFSET].toInt() != 1) continue

            // A u8 now; below version 7 the byte after it was the u16's high byte, always 0.
            val nameLength = bytes[base + NAME_OFFSET].toInt() and 0xFF
            if (nameLength !in 1..MAX_NAME_BYTES) return null
            if (version < VERSION_UNPROTECTED_NO_KIT_VIEWS && bytes[base + APPEARANCE_OFFSET].toInt() != 0) return null

            val nameFrom = base + NAME_TEXT_OFFSET
            val hasOwn = version >= VERSION_NO_KNOWN_SLOTS && bytes[base + PROTECTED_OFFSET].toInt() == 1
            entries[position] = Entry(
                position = position,
                name = String(bytes, nameFrom, nameLength, Charsets.UTF_8),
                sealedKey = bytes.copyOfRange(
                    base + SEALED_KEY_OFFSET,
                    base + SEALED_KEY_OFFSET + SEALED_KEY_BYTES,
                ),
                indexBlobId = bytes.copyOfRange(
                    base + INDEX_ID_OFFSET,
                    base + INDEX_ID_OFFSET + VaultKeys.BLOB_ID_BYTES,
                ),
                hasOwnPassphrase = hasOwn,
                // Read from version 5 and only for a protected entry: elsewhere the byte
                // is filler, or a 0 that means none.
                kdfSet = if (hasOwn && version >= VERSION_NO_KIT_VIEWS) (bytes[base + KDF_SET_OFFSET].toInt() and 0xFF) - 1 else -1,
                // Version 8 on, every occupied entry; 6 and 7, protected only; below 6 no kit
                // has been viewed.
                kitViewsUsed = when {
                    version > VERSION_UNPROTECTED_NO_KIT_VIEWS -> views(bytes[base + ENTRY_KIT_VIEWS_OFFSET])
                    hasOwn && version >= VERSION_NO_APPEARANCE -> views(bytes[base + ENTRY_KIT_VIEWS_OFFSET])
                    else -> 0
                },
                // Version 7 on. An icon or colour this build does not define reads as the
                // letter or no colour in that field alone — decoration never refuses a keyring.
                appearance = if (version >= VERSION_UNPROTECTED_NO_KIT_VIEWS) VaultAppearance.of(bytes[base + APPEARANCE_OFFSET].toInt()).pack() else 0,
            )
        }
        return Keyring(
            entries,
            hasDecoy = (header and DECOY_FLAG) != 0,
            // Read from version 3 on. At 1 and 2 the byte is the CSPRNG filler those
            // versions wrote there, and 0 — "I know of no slot" — is what they mean.
            knownSlots = if (version >= VERSION_NO_PANIC_SLOT) bytes[KNOWN_SLOTS_OFFSET].toInt() and 0xFF else 0,
            // Read from version 4 on, for the same reason: below it the bytes are filler.
            panicSlot = if (version >= VERSION_INSTALL_BOUND) slotOf(bytes[PANIC_SLOT_OFFSET]) else -1,
            homeSlot = if (version >= VERSION_INSTALL_BOUND) slotOf(bytes[HOME_SLOT_OFFSET]) else -1,
            kitViewsUsed = if (version >= VERSION_NO_APPEARANCE) views(bytes[LIST_KIT_VIEWS_OFFSET]) else 0,
        )
    }

    private fun views(stored: Byte): Int = (stored.toInt() and 0xFF).coerceAtMost(KIT_VIEWS)

    /** [keyring] with its own kit's view count replaced — D1. [withKnownSlots]'s shape. */
    fun withKitViewsUsed(keyring: Keyring, used: Int): Keyring =
        Keyring(keyring.entries.copyOf(), keyring.hasDecoy, keyring.knownSlots, keyring.panicSlot, keyring.homeSlot, used)

    /** [keyring] with the protected entry at [position]'s kit view count replaced — D1. */
    fun withEntryKitViewsUsed(keyring: Keyring, position: Int, used: Int): Keyring {
        val e = keyring.at(position) ?: return keyring
        val entries = keyring.entries.copyOf()
        entries[position] = Entry(position, e.name, e.sealedKey, e.indexBlobId, e.hasOwnPassphrase, e.kdfSet, used, e.appearance)
        return Keyring(entries, keyring.hasDecoy, keyring.knownSlots, keyring.panicSlot, keyring.homeSlot, keyring.kitViewsUsed)
    }

    /**
     * [keyring] with its record of occupied slots replaced by [value].
     *
     * [withDecoy]'s shape: a change to keyring data, nothing about any entry touched.
     * [value] is an opaque 8-bit pattern here — the caller decides what it means, and
     * only `VaultLifecycle` and `VaultKeyringSession` are permitted to.
     */
    fun withKnownSlots(keyring: Keyring, value: Int): Keyring =
        Keyring(keyring.entries.copyOf(), keyring.hasDecoy, value and 0xFF, keyring.panicSlot, keyring.homeSlot, keyring.kitViewsUsed)

    /**
     * [keyring] with its panic record replaced — `panic-phrase`. -1 is none. Opaque here,
     * [withKnownSlots]'s terms exactly.
     */
    fun withPanicRecord(keyring: Keyring, panicSlot: Int, homeSlot: Int): Keyring =
        Keyring(keyring.entries.copyOf(), keyring.hasDecoy, keyring.knownSlots, panicSlot, homeSlot, keyring.kitViewsUsed)

    private fun slotByte(slot: Int): Byte = if (slot in 0 until 0xFF) (slot + 1).toByte() else 0

    private fun slotOf(stored: Byte): Int =
        ((stored.toInt() and 0xFF) - 1).takeIf { it in 0 until SlotArray.SLOT_COUNT } ?: -1

    /** The entry's position, so an entry cannot be transplanted — `SlotArray`'s rule. */
    private fun aad(position: Int) = byteArrayOf(position.toByte())

    private fun seal(keyringKey: ByteArray, position: Int, vmk: ByteArray): ByteArray {
        val entryKey = VaultKeys.keyringEntryKey(keyringKey, position)
        try {
            return sealUnder(entryKey, position, vmk)
        } finally {
            entryKey.fill(0)
        }
    }

    /**
     * One AEAD seal, shared by both entry forms.
     *
     * The output is [SEALED_KEY_BYTES] whichever key sealed it — nonce, ciphertext and
     * tag are the same widths — which is what lets the flag be the only thing that
     * differs between a protected entry and a plain one.
     */
    private fun sealUnder(key: ByteArray, position: Int, vmk: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { rng.nextBytes(it) }
        val ciphertext = ByteArray(VaultKeys.VMK_BYTES + TAG_BYTES)
        val sealed = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            ciphertext, longArrayOf(0),
            vmk, vmk.size.toLong(),
            aad(position), 1L,
            null, nonce, key,
        )
        check(sealed) { "keyring entry seal failed" }
        return nonce + ciphertext
    }

    // --- the store ------------------------------------------------------------

    /**
     * The keyring at [blobId], or null when there is none there or it will not open.
     *
     * Read under the **keyring key**, not a VMK — this blob belongs to the keyring and
     * predates every vault on it. Otherwise it is an ordinary blob under the ordinary
     * envelope: same chunking, same bucket padding, same everything the store gives a
     * photograph.
     *
     * **Under [VaultKeys.BlobContext.KEYRING], which is what makes a null here mean
     * something.** An old-layout slot yields a VMK and an index blob ID in the same 48
     * bytes a new one yields a keyring key and a keyring ID; the app tells them apart
     * by trying this first. Because the index is sealed under a different context, an
     * index blob read as a keyring fails to decrypt rather than decrypting into
     * something that then has to be judged — the discrimination is cryptographic, not
     * a guess about bytes.
     *
     * Null for every failure, for [parse]'s reason: an install with no keyring, a
     * keyring that will not decrypt and a keyring that will not parse have to look the
     * same from the unlock path, which reports only whether something opened.
     */
    fun load(store: BlobStore, blobId: ByteArray, keyringKey: ByteArray): Keyring? {
        if (!store.fileOf(blobId).exists()) return null
        return runCatching {
            store.reader(
                blobId, keyringKey, PLAINTEXT_BYTES.toLong(), VaultKeys.BlobContext.KEYRING,
            ).use { reader ->
                parse(reader.readRange(0, PLAINTEXT_BYTES))
            }
        }.getOrNull()
    }

    /**
     * Replaces the keyring blob under the **same** ID.
     *
     * `writeTo` is a temp file, an fsync and a rename, so a kill mid-write leaves the
     * previous keyring intact — the property the index already depends on, and the one
     * that makes the migration's ordering safe.
     *
     * The plaintext is a constant length, so there is no length prefix: unlike the
     * index, this blob's size is not allowed to say anything about what it holds.
     */
    fun save(store: BlobStore, blobId: ByteArray, keyringKey: ByteArray, keyring: Keyring) {
        val plain = serialise(keyring)
        try {
            store.writeTo(
                blobId, java.io.ByteArrayInputStream(plain), keyringKey,
                resume = false, context = VaultKeys.BlobContext.KEYRING,
            )
        } finally {
            plain.fill(0)
        }
    }

    /** Compile-time-ish proof the layout fits the width it claims. */
    init {
        check(ENTRY_USED_BYTES <= ENTRY_BYTES) {
            "an entry needs $ENTRY_USED_BYTES bytes but the layout gives $ENTRY_BYTES"
        }
        // The occupancy record borrows a byte position 0 already reserves, so it must
        // land past what that entry uses and inside what it was given — the whole
        // reason [PLAINTEXT_BYTES] did not have to move.
        check(KNOWN_SLOTS_OFFSET in (VERSION_BYTES + ENTRY_USED_BYTES) until (VERSION_BYTES + ENTRY_BYTES)) {
            "the occupancy record at $KNOWN_SLOTS_OFFSET is not in position 0's reserved tail"
        }
        check(HOME_SLOT_OFFSET < VERSION_BYTES + ENTRY_BYTES) {
            "the panic record at $PANIC_SLOT_OFFSET is not in position 0's reserved tail"
        }
        // Entry-relative, and past position 0's three slot records (absolute 187–189).
        check(KDF_SET_OFFSET > HOME_SLOT_OFFSET - VERSION_BYTES && KDF_SET_OFFSET < ENTRY_BYTES) {
            "the parameter byte at $KDF_SET_OFFSET collides with the slot records or leaves the entry"
        }
        // The appearance took the old u16 length's high byte, so the name text and the field's
        // width are where they were and nothing past them moved.
        check(APPEARANCE_OFFSET == 2 && NAME_TEXT_OFFSET == 3 && NAME_FIELD_BYTES == 96 && SEALED_KEY_OFFSET == 97) {
            "the appearance byte moved the name field"
        }
        check(LIST_KIT_VIEWS_OFFSET == VERSION_BYTES + ENTRY_BYTES - 1) {
            "the list's kit-view byte at $LIST_KIT_VIEWS_OFFSET is not position 0's last byte"
        }
    }
}
