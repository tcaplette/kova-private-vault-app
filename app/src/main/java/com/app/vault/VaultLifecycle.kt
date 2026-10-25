package com.app.vault

import android.util.Log
import java.io.File
import java.security.SecureRandom

/**
 * Creating a vault, opening one, closing one — P1-8.5.
 *
 * This is the piece P1-1 through P1-8 each assumed someone else would write.
 * `SlotArray`, `VaultKeys`, `VaultUnlock`, `BlobStore` and `MetadataDb` were all
 * built and tested, and nothing ever called them from product code: every test
 * constructed its own keys, so an app that could not open was never noticed.
 *
 * Deliberately free of Compose and of any Android UI type, so an instrumented
 * test can drive the whole lifecycle with no Activity — which is what makes the
 * I8 claims below assertable rather than eyeballed.
 *
 * **This is the passphrase path (I7).** No `KeyStore`, no `BiometricPrompt`, no
 * hardware-backed anything. P1-9 adds biometrics on top, and its Verify is that
 * wiping the Keystore leaves this working unchanged — which only means something
 * if this never touched it.
 */
class VaultLifecycle(
    private val slotsFile: File,
    private val store: BlobStore,
) {

    companion object {

        /**
         * Whether [passphrase] is the open list's own, proven against [slotsFile] — the instance
         * [confirmsListPassphrase]'s check, here so `ExportAuth` asks exactly the same question.
         * The slot the keyring records as its home, else the slot that opened it; the panic
         * phrase opens the panic slot, which seals the same keyring, so it is refused by asking
         * only the home slot. One derivation. [passphrase] is zeroed on every path.
         */
        fun confirmsListPassphrase(slotsFile: File, passphrase: ByteArray): Boolean {
            try {
                val home = VaultKeyringSession.homeSlot().takeIf { it >= 0 }
                    ?: VaultKeyringSession.openSlotIndex()
                if (home < 0 || !slotsFile.exists()) return false
                return opensThisKeyring(SlotArray(slotsFile), home, passphrase) == true
            } finally {
                passphrase.fill(0)
            }
        }

        /**
         * A newly protected vault's first enrolment — `new-vault-setup-flow`, D8. Exactly the write
         * the open vault makes from its own sheet: its capture store's public key and [modes], into
         * the keyring's capture store under [indexBlobId]. Nothing is written for no modes. Throws
         * when the write fails; the caller has already committed the vault and still shows its kit.
         * [vmk] stays the caller's; every key derived here is zeroed.
         */
        fun enrolFirst(store: BlobStore, indexBlobId: ByteArray, vmk: ByteArray, modes: Set<Capture.Mode>) {
            if (modes.isEmpty()) return
            val keyringStoreKey = checkNotNull(VaultKeyringSession.captureStoreKey())
            val vaultStoreKey = VaultKeys.captureStoreKey(vmk)
            try {
                val kp = VaultKeys.captureKeypair(vaultStoreKey)
                try {
                    CaptureStore.enrol(store, CaptureStore.open(store, keyringStoreKey), indexBlobId, kp.publicKey, modes)
                } finally {
                    kp.zero()
                }
            } finally {
                keyringStoreKey.fill(0)
                vaultStoreKey.fill(0)
            }
        }

        /**
         * Whether [secret] opens slot [index] into the open keyring: true, into something
         * else: false, not at all: null. One derivation; everything it produced is zeroed.
         */
        private fun opensThisKeyring(slots: SlotArray, index: Int, secret: ByteArray): Boolean? {
            val kek = VaultKeys.deriveKek(secret, slots.installSalt(), slots.kdfParams())
            val payload = try {
                slots.open(index, kek)
            } finally {
                kek.fill(0)
            } ?: return null
            val key = payload.copyOfRange(0, VaultKeys.VMK_BYTES)
            val id = payload.copyOfRange(VaultKeys.VMK_BYTES, payload.size)
            return try {
                VaultKeyringSession.holdsKey(key) &&
                    VaultKeyringSession.keyringBlobId()?.contentEquals(id) == true
            } finally {
                payload.fill(0)
                key.fill(0)
                id.fill(0)
            }
        }

        private const val TAG = "VaultLifecycle"

        /** `FORMAT.md` §6. Unowned, never refreshed. */
        const val INSTALL_CHAFF_BLOBS = 48

        /**
         * §6. Owned by the vault and recorded in its index, so the per-unlock
         * rewrite has something to rewrite from the first unlock — install chaff
         * is unowned, and owned chaff otherwise only accrues from imports.
         */
        const val CREATION_CHAFF_BLOBS = 8

        /**
         * §6, per import — **and per blob written, not per item.**
         *
         * `blob-size-cap` split large items across blobs, so one import can write
         * hundreds of same-sized files at one timestamp. Chaff sized per item
         * would leave that burst as the only group of simultaneous writes in the
         * store, which is a pattern where there used to be one large blob.
         *
         * 0–2 per blob, uniform, so the mean is one chaff blob per real blob. A
         * chaff blob averages 3.55 MiB (§6) against a 16 MiB real one, so a large
         * import costs about 22% on top. That is the price of the burst not
         * standing alone, and it is the only storage this change spends.
         *
         * **This is also the first implementation of §6's per-import chaff.** The
         * clause was in the format from P0-6 and no import path ever wrote it;
         * splitting is what made its absence matter.
         */
        const val IMPORT_CHAFF_MAX_PER_BLOB = 2

        /**
         * **A backstop, not the rule** — `passphrase-strength-meter`.
         *
         * The rule is [PassphraseStrength]: at least
         * [PassphraseStrength.MIN_CODE_POINTS] Unicode code points *and* an estimated
         * [PassphraseStrength.MIN_SCORE] — 10^10 guesses or more. It is enforced on the
         * screen, because that is the only place the typed text exists as text.
         *
         * Here a passphrase is a `ByteArray` and must stay one: rebuilding a `String`
         * from it to count code points would create an unzeroable copy of the secret,
         * which is the whole of P1-10's rule. Since every code point is at least one
         * UTF-8 byte, *n* code points implies at least *n* bytes, so this asserts the
         * necessary half of the floor at the point key material lands. It is satisfied by
         * every passphrase the screens accept and it rejects some they would have — that
         * asymmetry is what makes it a backstop.
         *
         * **It was 8, and 8 was wrong.** Argon2id at ~460 ms an attempt (P1-3) buys a
         * large multiplier over a fast hash and none at all over `password`, which falls
         * inside the first thousand guesses. The earlier note here claimed the app wanted
         * "no strength meter, no dictionary"; nobody decided that, and it is no longer
         * true. The one sentence of it worth keeping: composition rules make passphrases
         * worse, and NIST SP 800-63B-4 forbids them.
         */
        const val MIN_PASSPHRASE_BYTES = PassphraseStrength.MIN_CODE_POINTS

        /**
         * Runs inside [lockNow] while the vault's key still exists — `viewer-swipe-navigation`,
         * D7.
         *
         * `MainActivity` writes the session's video stopping points here; a lock that dropped
         * the key first would leave them nowhere to go. It runs on whatever thread locks —
         * auto-lock's background coroutine included — so what is registered must not touch a
         * view or a player. A hook that fails does not stop the lock: a vault left open because
         * a position would not write is the worse failure by far.
         */
        @Volatile
        var beforeLock: (() -> Unit)? = null

        /**
         * The lock, without needing a vault instance — P1-10.
         *
         * `lock()` never touched instance state, and the auto-lock policy runs
         * from `Application` where no `VaultLifecycle` exists. One implementation
         * with two callers beats two implementations that drift: a policy that
         * cleared the session but forgot the thumbnail cache would leave
         * decrypted pixels behind a locked screen.
         *
         * Still the mechanism only. Nothing here decides *when* — that is
         * `AutoLock`'s, and this file references no Android lifecycle type.
         */
        fun lockNow() {
            // Ahead of both levels below: the keyring's lock leaves the open vault first,
            // and the hook needs that vault's key. A keyring alone is enough to run it —
            // `screen-off-lock-all-screens`: a kit on the vault list is sealed under the
            // keyring's key, and no vault is open there.
            if (VaultSession.isUnlocked || VaultKeyringSession.isOpen) {
                runCatching { beforeLock?.invoke() }
                    .onFailure { Log.w(TAG, "before-lock hook failed: ${it.javaClass.simpleName}") }
            }
            // `vault-keyring`. **Both levels, and there is no lock that clears one.**
            // A lock that left the keyring open would leave every vault name decrypted
            // behind a lock screen, and the next import would land on a keyring the
            // user had already locked. The keyring's own lock leaves the open vault
            // first, so the two calls below are the vault twice rather than a gap.
            VaultKeyringSession.lock()
            VaultSession.lock()
            ThumbnailCache.clear()
            TamperLog.closed()
        }
    }

    /**
     * Why a creation was refused — P2-1. Never a slot index and never a count.
     *
     * [PASSPHRASE_IN_USE] says only what entering the same passphrase at the lock
     * screen would already have said. [NO_ROOM] is reachable only by a user who
     * has just proven every slot occupied by typing eight passphrases, so it
     * discloses nothing they did not supply.
     */
    enum class Refusal {
        PASSPHRASE_IN_USE,
        NO_ROOM,

        /**
         * `slot-occupancy-map`, D4. The open keyring was written before the occupancy
         * record existed and says it built a decoy, so it knows a second keyring is out
         * there and cannot say where.
         *
         * Names no slot and no count: it says a decoy exists, which is something only
         * someone who already opened this keyring could read, and the user already
         * knows because they built it.
         */
        DECOY_PASSPHRASE_NEEDED,

        /**
         * `backup-file-recover-and-import`. The words do not open the chosen backup. Nothing
         * was written.
         */
        BACKUP_NOT_OPENED,

        /**
         * The words opened the backup, and the list they are the key to is not on this phone.
         * Recover refuses with nothing written — with no list open it cannot choose a slot
         * safely (D5.3); Import takes it as the cue for the passphrase step (D4.5). The words'
         * holder already knows the list exists: the words opened its backup.
         */
        LIST_NOT_HERE,

        /** The backup ended early or failed its MAC. Nothing it wrote remains. */
        BACKUP_INCOMPLETE,
    }

    /**
     * A backup to restore from — a `backup.bin` or an SD-card folder —
     * `backup-file-recover-and-import`, D3.
     *
     * Two functions, so no Android type enters this file: `MainActivity` builds one over a
     * content URI and hands it in.
     */
    class BackupSource(
        /** Whether a keyring key opens this backup. Reads; writes nothing. */
        val opens: (keyringKey: ByteArray) -> Boolean,
        /**
         * Copies the backup's blobs into the store, leaving every file already there as it
         * is, and its copy of the keyring into the side directory rather than the store.
         * False for a backup that ended early or failed its check; a throw for one that could
         * not be read. Either way, every file it wrote is gone again (D6).
         */
        val unpack: (keyringKey: ByteArray, sideDir: File, onProgress: (Int, Int) -> Unit) -> Boolean,
    )

    /**
     * Where a restore's copy of a keyring waits until its vaults are adopted or it is put in
     * place. Beside the slot file, never in the store.
     */
    private val sideDir: File get() = File(slotsFile.parentFile, "restore-keyring")

    /** One slot as a bit in an occupancy record. -1 — no slot — is no bit. */
    private fun slotBit(slot: Int): Int =
        if (slot in 0 until SlotArray.SLOT_COUNT) 1 shl slot else 0

    /** What [create] and [unlock] report. Deliberately not the slot index. */
    class Outcome(
        val opened: Boolean,
        val derivations: Int,
        val slotsAttempted: Int,
        val refusal: Refusal? = null,
    )

    private val rng = SecureRandom()

    fun vaultExists(): Boolean = slotsFile.exists()

    // --- create --------------------------------------------------------------

    /**
     * Creates a vault and leaves it open — the first one, or another beside it.
     *
     * [passphrase] and every element of [preserve] are zeroed here on every path,
     * success or failure. Callers hand over ownership; there is no `String`
     * overload, so nobody can take the easy route by accident.
     *
     * **[preserve] is P2-1's safety for every vault except the one open.** A slot is
     * distinguishable from CSPRNG only to a KEK that opens it, so this cannot find
     * the vaults already here and therefore cannot avoid them either. It seals into
     * a slot that none of the supplied passphrases opened. A vault whose passphrase
     * is not supplied may be overwritten, which the screen says where it asks for
     * them — every design that removes that risk stores occupancy somewhere, and
     * anything a coerced passphrase can read is a count of vaults (I8).
     *
     * **[excludeSlot] is the exception, and it is the one that matters most.** The
     * app is already holding a key for the open keyring, so its slot is the one slot
     * occupancy is *not* unknowable for. Excluding it costs no derivation and needs
     * no passphrase. It was previously protected only because `createDecoy` happened
     * to require the same passphrase for an unrelated re-authorisation gate and fed
     * it into [preserve] — a guarantee that would have vanished silently the moment
     * anyone reordered that flow.
     *
     * Defaulted from the session rather than required of callers: every path through
     * *this* function must not clobber the open keyring, and a parameter each caller
     * has to remember is the accident this removes. Recovery does not come through here
     * at all — it reseals the recovered keyring's own recorded slot — and archive
     * import seals no slot.
     *
     * -1 means no exclusion, which is what a first run reports: no keyring is open, so
     * every slot is a candidate.
     *
     * [onProgress] reports chaff written out of total, because ~128 MiB is a
     * visible wait and a frozen first run reads as a broken app. A second vault
     * writes only its own owned chaff, so its total is much smaller.
     */
    fun create(
        passphrase: ByteArray,
        preserve: List<ByteArray> = emptyList(),
        /**
         * `vault-keyring`. What the vault created alongside the keyring is called.
         * Defaulted rather than required, because every existing caller creates the
         * one vault a new keyring starts with and only the list screen names it.
         */
        firstVaultName: String = VaultKeyringSession.MIGRATED_NAME,
        /**
         * P2-2. The timestamp the vault-created log entry carries, and the only
         * thing this touches — not the slot, not the chaff, not the index, not the
         * session, all of which happen now whatever this says. The decoy flow is
         * the one caller that supplies it, because entry 0 cannot be rewritten
         * later and a log whose first entry is newer than its second could not
         * have been written by any honest vault.
         */
        origin: Long? = null,
        /**
         * The slot that must not be written, or -1 for none. Defaulted to the open
         * keyring's — see the KDoc above.
         */
        excludeSlot: Int = VaultKeyringSession.openSlotIndex(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Outcome {
        require(passphrase.size >= MIN_PASSPHRASE_BYTES) {
            "passphrase must be at least $MIN_PASSPHRASE_BYTES bytes"
        }

        // §6's unowned reserve belongs to the install, not to a vault. A second
        // 48-blob burst at a later timestamp would be a second install-shaped
        // event in a store whose whole argument is that its blobs are not
        // attributable.
        val firstRun = !vaultExists()

        val slots = SlotArray(slotsFile)
        slots.initialiseIfAbsent()

        val vmk = VaultKeys.newVmk()
        // `initialiseIfAbsent` above wrote the creation constant on a first run and
        // left an existing header alone, so this reads the install's own parameters
        // either way — a second vault on an old install is sealed under what that
        // install records, not under this build's constant.
        val kek = VaultKeys.deriveKek(passphrase, slots.installSalt(), slots.kdfParams())
        // Derived, not random — P1-12. It is still sealed into the slot exactly
        // where a random ID was; deriving it is what lets 24 words find the index
        // on an install that holds nothing but restored blobs.
        val metadataBlobId = VaultKeys.metadataBlobId(vmk)

        val proven = VaultUnlock.provenSlots(preserve, slots)
        // What this call actually spent: one derivation per supplied passphrase
        // plus the one that seals, and all 8 slots attempted for each of them.
        val derivations = 1 + preserve.size
        val attempted = SlotArray.SLOT_COUNT * derivations

        try {
            // One passphrase must not open two vaults: `unlock` returns the lowest
            // match, so the other would be unreachable for good. The KEK derived
            // above is the one that would seal, so this costs no extra derivation.
            if (VaultUnlock.opensAnySlot(kek, slots)) {
                return Outcome(false, derivations, attempted, Refusal.PASSPHRASE_IN_USE)
            }

            // `slot-occupancy-map`. **What the open keyring knows it filled.** The
            // session folds its own slot in, so a keyring written before the record
            // existed answers with exactly that slot and the install behaves as it did
            // before. With nothing open this is 0 and every slot is a candidate.
            val ownSlot = VaultKeyringSession.openSlotIndex()
            val known = VaultKeyringSession.knownSlots()
            val provenBits = proven.fold(0) { acc, p -> acc or slotBit(p.vault.slotIndex) }

            // D4. An install written before the record existed knows a decoy is out
            // there — the bit says so — and cannot name its slot. That is the one case
            // the app can know about, so it is closed by refusing rather than by
            // sealing a slot chosen from a set that still contains the decoy's.
            //
            // A keyring recording no decoy is not refused on this ground: it has
            // nothing to be taught and a refusal it cannot satisfy is a dead end.
            if (VaultKeyringSession.hasDecoy() &&
                known == slotBit(ownSlot) &&
                (provenBits and known.inv()) == 0
            ) {
                return Outcome(false, derivations, attempted, Refusal.DECOY_PASSPHRASE_NEEDED)
            }

            // Uniform over what is neither known nor proven occupied. Not always slot
            // 0: the slots are indistinguishable without the key, and a fixed choice is
            // a pattern waiting to become an oracle.
            //
            // The open keyring's slot joins the set without having been proven, because
            // the session is holding its key — no derivation was spent on it and
            // `derivations` above does not count it.
            //
            // **There is no branch below that widens this set** (D2). A refusal the
            // user can act on by typing another passphrase is always better than a seal
            // that destroys a keyring whose key exists nowhere else.
            val occupied = known or provenBits or slotBit(excludeSlot)
            val free = (0 until SlotArray.SLOT_COUNT).filterNot { (occupied shr it) and 1 == 1 }
            if (free.isEmpty()) {
                return Outcome(false, derivations, attempted, Refusal.NO_ROOM)
            }
            val slotIndex = free[rng.nextInt(free.size)]

            // `vault-keyring`. **The slot holds a keyring, not this vault.** The 48
            // bytes are the same 48 bytes at the same offsets — a 32-byte key and a
            // 16-byte blob ID — so nothing about the slot region moves; what they name
            // is a keyring whose first entry is the vault being created here.
            val keyringKey = VaultKeys.newKeyringKey()
            // Derived, so the recovery kit's 24 words can find it — `keyringBlobId`.
            val keyringId = VaultKeys.keyringBlobId(keyringKey)
            // `slot-occupancy-map`. **Born knowing what its creator knew**, plus its
            // creator's slot and its own — so a keyring created from this one is never
            // free to overwrite its grandparent, with no passphrase supplied. On a
            // first run `known` is 0 and this is exactly the slot about to be sealed.
            //
            // `recovery-own-slot-only`, D3. **And its own slot as home**, in the same
            // write, so recovery always has exactly one slot to reseal.
            val keyring = VaultKeyring.withPanicRecord(
                VaultKeyring.withKnownSlots(
                    VaultKeyring.withEntry(
                        VaultKeyring.empty(), keyringKey, 0, firstVaultName, vmk, metadataBlobId,
                    ),
                    known or slotBit(slotIndex),
                ),
                -1, slotIndex,
            )
            // Durable before the slot names it, the migration's ordering for the
            // migration's reason: a slot pointing at a keyring that is not there is the
            // one failure that loses a vault.
            VaultKeyring.save(store, keyringId, keyringKey, keyring)
            slots.seal(slotIndex, kek, keyringKey, keyringId)

            // **Every existing record is written here and nowhere earlier** (D3). Up to
            // this line a failure or a refusal has left each one byte-for-byte as it
            // was; past it the slot is sealed and the writes are safe to make.
            //
            // The creator first: it filled a slot and must not offer it again — and it
            // keeps what the supplied passphrases proved, which is the half that closes
            // D4. A passphrase names a slot that demonstrably holds a keyring; throwing
            // that away at the end of the act would leave the open keyring free to
            // overwrite, at the next creation, exactly the keyring the user just typed a
            // passphrase to protect. For an install that predates the record, that
            // keyring is the decoy, and this is where its slot is recorded.
            VaultKeyringSession.recordKnownSlots(store, known or provenBits or slotBit(slotIndex))
            // Then teaching. `preserve` already cost a derivation per passphrase and
            // already opened every keyring those passphrases name, so each one is
            // updated here rather than only being spared this once — a passphrase the
            // user types once protects that keyring for good. A supplied passphrase
            // that opened nothing is not in `proven` and so teaches nothing, silently.
            teachProvenKeyrings(proven, slotIndex)

            val total = (if (firstRun) INSTALL_CHAFF_BLOBS else 0) + CREATION_CHAFF_BLOBS
            var written = 0

            // Unowned, and recorded nowhere. This is the reserve that keeps
            // unaccounted blobs ambiguous under coercion (§6). Once per install.
            if (firstRun) {
                repeat(INSTALL_CHAFF_BLOBS) {
                    store.writeChaff(1)
                    onProgress(++written, total)
                }
            }

            val owned = mutableListOf<ByteArray>()
            repeat(CREATION_CHAFF_BLOBS) {
                owned += store.writeChaff(1)
                onProgress(++written, total)
            }

            val db = MetadataDb.empty()
            db.ownedChaff += owned
            db.logBlobId = TamperLog.create(store)
            db.save(store, metadataBlobId, vmk)

            // The vault being left goes first, through the same lock the manual
            // action and the auto-lock policy call — otherwise its VMK is dropped
            // unzeroed and its decrypted thumbnails survive behind the new vault's
            // grid. Nothing is logged about the vault just created: the creating
            // vault's log recording a sibling is a disclosure that outlives the
            // session.
            if (VaultSession.isUnlocked) lockNow()

            VaultKeyringSession.holdCreated(keyringKey, keyringId, keyring, slotIndex)
            VaultSession.unlocked(OpenVault(vmk.copyOf(), metadataBlobId.copyOf(), slotIndex))
            TamperLog.created(
                store, db, metadataBlobId, vmk,
                timestamp = origin ?: System.currentTimeMillis(),
            )
            Log.i(TAG, "vault created: $total chaff blobs, ${owned.size} owned")
            return Outcome(opened = true, derivations = derivations, slotsAttempted = attempted)
        } finally {
            kek.fill(0)
            vmk.fill(0)
            passphrase.fill(0)
            proven.forEach {
                it.vault.vmk.fill(0)
                it.vault.metadataBlobId.fill(0)
            }
            preserve.forEach { it.fill(0) }
        }
    }

    /**
     * Adds [sealed] to the record of every keyring a supplied passphrase opened —
     * `slot-occupancy-map`, D3.
     *
     * A `ProvenVault`'s payload **is** a keyring reference on this layout: the 32 bytes
     * are its key and the 16 are its blob ID, which is what a slot holds. An install
     * still on the old layout yields a vault there instead, so the load fails and that
     * keyring is left alone — it has no record to teach and one unlock migrates it.
     *
     * Best effort, one keyring at a time, and never fatal: the slot is already sealed
     * by the time this runs, and a creation that succeeded must not be reported as
     * having failed because a record did not write. The cost of a miss is a candidate
     * slot at the next creation, not a keyring.
     */
    private fun teachProvenKeyrings(proven: List<ProvenVault>, sealed: Int) {
        proven.forEach { p ->
            recordInto(p.vault.metadataBlobId, p.vault.vmk) {
                it or slotBit(p.vault.slotIndex) or slotBit(sealed)
            }
        }
    }

    /**
     * Rewrites the occupancy record of the keyring at [blobId] to what [next] makes of
     * the one it already holds, and saves it only if that changed anything.
     */
    private fun recordInto(blobId: ByteArray, key: ByteArray, next: (Int) -> Int) {
        runCatching {
            val ring = VaultKeyring.load(store, blobId, key) ?: return@runCatching
            val updated = VaultKeyring.withKnownSlots(ring, next(ring.knownSlots))
            if (updated.knownSlots != ring.knownSlots) {
                VaultKeyring.save(store, blobId, key, updated)
            }
        }.onFailure { Log.w(TAG, "an occupancy record was not written", it) }
    }

    // --- panic phrase (`panic-phrase`) ---------------------------------------------

    /** What a panic-phrase operation did. Refusals name the field the screen puts them under. */
    enum class PanicResult {
        DONE,

        /** The vault list's passphrase did not open its own slot into this keyring. */
        WRONG_PASSPHRASE,

        /** The current panic phrase did not open the recorded panic slot. Nothing was written. */
        WRONG_PANIC_PHRASE,
        TOO_SHORT,

        /** Within one edit of the vault list's passphrase — a typo could trigger it. */
        TOO_CLOSE,

        /** The phrase already opens a slot. */
        IN_USE,
        NO_ROOM,

        /**
         * The recorded panic slot now opens into another keyring under this phrase, so it
         * is not this keyring's to write. The record was cleared and the slot left alone.
         */
        CLEARED,

        /** A record could not be written. What was sealed is still counted as occupied. */
        NOT_SAVED,
    }

    /**
     * Sets the open keyring's panic phrase — D1, D4.
     *
     * [passphrase] must open the keyring's own slot, which confirms it is the owner and
     * gives [Panic.withinOneEdit] the one passphrase it can compare. The phrase is sealed
     * into a free slot drawn as [create] draws one, holding this keyring's key and blob
     * ID, and **only then** are the records written — occupancy first, `slot-occupancy-map`
     * D3's asymmetry: a record missing a slot costs a keyring, one claiming it costs
     * nothing.
     *
     * Both arrays are zeroed on every path.
     */
    fun setPanic(passphrase: ByteArray, phrase: ByteArray): PanicResult {
        try {
            if (VaultKeyringSession.panicSlot() >= 0) return PanicResult.IN_USE
            val home = VaultKeyringSession.homeSlot().takeIf { it >= 0 }
                ?: VaultKeyringSession.openSlotIndex()
            if (home < 0) return PanicResult.WRONG_PASSPHRASE
            validatePhrase(passphrase, phrase)?.let { return it }

            val slots = SlotArray(slotsFile)
            if (opensThisKeyring(slots, home, passphrase) != true) return PanicResult.WRONG_PASSPHRASE

            val kek = VaultKeys.deriveKek(phrase, slots.installSalt(), slots.kdfParams())
            try {
                if (VaultUnlock.opensAnySlot(kek, slots)) return PanicResult.IN_USE

                // ponytail: no DECOY_PASSPHRASE_NEEDED guard as `create` has — it exists for
                // keyrings written before the occupancy record, and there is no installed base.
                val known = VaultKeyringSession.knownSlots() or slotBit(home)
                val free = (0 until SlotArray.SLOT_COUNT).filterNot { (known shr it) and 1 == 1 }
                if (free.isEmpty()) return PanicResult.NO_ROOM
                val slot = free[rng.nextInt(free.size)]

                sealThisKeyring(slots, slot, kek) ?: return PanicResult.NOT_SAVED
                if (!VaultKeyringSession.recordKnownSlots(store, known or slotBit(slot))) {
                    return PanicResult.NOT_SAVED
                }
                return if (VaultKeyringSession.recordPanic(store, slot, home)) PanicResult.DONE
                else PanicResult.NOT_SAVED
            } finally {
                kek.fill(0)
            }
        } finally {
            passphrase.fill(0)
            phrase.fill(0)
        }
    }

    /**
     * Replaces the panic phrase in the slot it already has — D5.
     *
     * [current] must open the recorded panic slot into this keyring before that slot is
     * written. A record can go stale — a keyring that never knew the slot may since have
     * sealed over it — and resealing blind would destroy that keyring.
     */
    fun changePanic(current: ByteArray, passphrase: ByteArray, phrase: ByteArray): PanicResult {
        try {
            val panicSlot = VaultKeyringSession.panicSlot()
            val home = VaultKeyringSession.homeSlot()
            if (panicSlot < 0 || home < 0) return PanicResult.CLEARED
            validatePhrase(passphrase, phrase)?.let { return it }

            val slots = SlotArray(slotsFile)
            if (opensThisKeyring(slots, home, passphrase) != true) return PanicResult.WRONG_PASSPHRASE
            proveCurrent(slots, panicSlot, home, current)?.let { return it }

            val kek = VaultKeys.deriveKek(phrase, slots.installSalt(), slots.kdfParams())
            try {
                if (VaultUnlock.opensAnySlot(kek, slots)) return PanicResult.IN_USE
                return if (sealThisKeyring(slots, panicSlot, kek) != null) PanicResult.DONE
                else PanicResult.NOT_SAVED
            } finally {
                kek.fill(0)
            }
        } finally {
            current.fill(0)
            passphrase.fill(0)
            phrase.fill(0)
        }
    }

    /**
     * Turns the panic phrase off — D5. The slot is overwritten with CSPRNG only once
     * [current] has proven it is this keyring's; it then leaves the occupancy record,
     * because it is free again.
     */
    fun clearPanic(current: ByteArray): PanicResult {
        try {
            val panicSlot = VaultKeyringSession.panicSlot()
            val home = VaultKeyringSession.homeSlot()
            if (panicSlot < 0) return PanicResult.CLEARED

            val slots = SlotArray(slotsFile)
            proveCurrent(slots, panicSlot, home, current)?.let { return it }

            slots.destroy(panicSlot)
            VaultKeyringSession.recordKnownSlots(
                store, VaultKeyringSession.knownSlots() and slotBit(panicSlot).inv(),
            )
            return if (VaultKeyringSession.recordPanic(store, -1, home)) PanicResult.DONE
            else PanicResult.NOT_SAVED
        } finally {
            current.fill(0)
        }
    }

    /**
     * Clears a forgotten panic phrase with the vault list's passphrase — D5.
     *
     * **No slot is written.** The slot this cannot prove stays in the occupancy record so
     * nothing seals over it; the forgotten phrase goes on opening this keyring, as an
     * ordinary credential nobody knows, for the cost of one slot.
     */
    fun forgetPanic(passphrase: ByteArray): PanicResult {
        try {
            val home = VaultKeyringSession.homeSlot()
            if (VaultKeyringSession.panicSlot() < 0 || home < 0) return PanicResult.CLEARED
            val slots = SlotArray(slotsFile)
            if (opensThisKeyring(slots, home, passphrase) != true) return PanicResult.WRONG_PASSPHRASE
            return if (VaultKeyringSession.recordPanic(store, -1, home)) PanicResult.DONE
            else PanicResult.NOT_SAVED
        } finally {
            passphrase.fill(0)
        }
    }

    /**
     * Whether [passphrase] is the open list's own — `recovery-kit-three-views`, D3. Proven
     * against the slot the keyring records as its home, else the slot that opened it; the
     * panic phrase opens the panic slot, not the home one, so it is refused. One derivation.
     * [passphrase] is zeroed on every path.
     */
    fun confirmsListPassphrase(passphrase: ByteArray): Boolean = confirmsListPassphrase(slotsFile, passphrase)

    /** How a passphrase act on Edit vault ended — `vault-passphrase-manage`. */
    enum class PassphraseAct { DONE, WRONG_PASSPHRASE, NOT_SAVED }

    /**
     * [addVaultPassphrase]'s result. [kitKey] is the vault's key for its kit on [PassphraseAct.DONE];
     * the caller zeroes it. [enrolled] is false when modes were chosen and their enrolment could not
     * be written after the re-seal — `new-vault-setup-flow`, D8.
     */
    class AddedPassphrase(val act: PassphraseAct, val kitKey: ByteArray? = null, val enrolled: Boolean = true)

    /**
     * Gives the vault at [position] a passphrase of its own — `vault-passphrase-manage`, D2. The
     * list's passphrase is proven first ([confirmsListPassphrase]: home slot, panic phrase refused),
     * then the entry is re-sealed under [newPassphrase], then — `new-vault-setup-flow`, D8 — the
     * vault's first enrolment is written with [modes] when there are any. The strength and match
     * checks are the caller's and run before this. Both arrays are zeroed on every path.
     */
    fun addVaultPassphrase(
        position: Int,
        listPassphrase: ByteArray,
        newPassphrase: ByteArray,
        modes: Set<Capture.Mode> = emptySet(),
    ): AddedPassphrase {
        var kek: ByteArray? = null
        try {
            if (!confirmsListPassphrase(listPassphrase)) return AddedPassphrase(PassphraseAct.WRONG_PASSPHRASE)
            val added = VaultKeyringSession.addVaultPassphrase(store, position) { chosen, set ->
                // A copy for the kit: the session zeroes the one it is handed.
                checkNotNull(VaultKeyringSession.entryPassphraseKek(newPassphrase, chosen, set)).also { kek = it.copyOf() }
            }
            if (!added) return AddedPassphrase(PassphraseAct.NOT_SAVED)
            val vmk = kek?.let { VaultKeyringSession.vaultKeyForKit(position, it) }
            // The vault is protected by now; a miss here is said, never undone (D8).
            val enrolled = modes.isEmpty() || runCatching {
                val indexId = VaultKeyringSession.vaults().first { it.position == position }.indexBlobId
                enrolFirst(store, indexId, checkNotNull(vmk), modes)
            }.onFailure { Log.w(TAG, "enrolment not saved: ${it.javaClass.simpleName}") }.isSuccess
            return AddedPassphrase(PassphraseAct.DONE, vmk, enrolled)
        } finally {
            listPassphrase.fill(0)
            newPassphrase.fill(0)
            kek?.fill(0)
        }
    }

    /**
     * Re-seals the protected vault at [position] under [newPassphrase], proving [current] first —
     * D2. The checks that need no key are the caller's. Both arrays are zeroed on every path.
     */
    fun changeVaultPassphrase(position: Int, current: ByteArray, newPassphrase: ByteArray): PassphraseAct {
        val kek = try {
            currentKek(position, current)
        } finally {
            current.fill(0)
        }
        try {
            kek ?: return PassphraseAct.WRONG_PASSPHRASE
            val changed = VaultKeyringSession.changeVaultPassphrase(store, position, kek) { chosen, set ->
                checkNotNull(VaultKeyringSession.entryPassphraseKek(newPassphrase, chosen, set))
            }
            return if (changed) PassphraseAct.DONE else PassphraseAct.NOT_SAVED
        } finally {
            kek?.fill(0)
            newPassphrase.fill(0)
        }
    }

    /**
     * Removes the protected vault at [position]'s own passphrase — D4, in its order: what is queued
     * for the vault is taken in, its timeline is copied home, its enrolment and queue are forgotten,
     * its capture store is destroyed, and **only then** is the entry re-sealed under the keyring key.
     * A kill before the last step leaves the vault protected with [current] still opening it, and a
     * repeat lands each capture home once. [current] is zeroed on every path.
     */
    fun removeVaultPassphrase(position: Int, current: ByteArray): PassphraseAct {
        val kek = try {
            currentKek(position, current)
        } finally {
            current.fill(0)
        }
        try {
            kek ?: return PassphraseAct.WRONG_PASSPHRASE
            val moved = VaultKeyringSession.withVaultKey(position, kek) { vmk, indexId ->
                moveCapturesHome(vmk, indexId)
            } ?: return PassphraseAct.NOT_SAVED
            if (!moved) return PassphraseAct.NOT_SAVED
            return if (VaultKeyringSession.removeVaultPassphrase(store, position, kek)) PassphraseAct.DONE
            else PassphraseAct.NOT_SAVED
        } finally {
            kek?.fill(0)
        }
    }

    /**
     * A protected vault's captures, moved home in D4's order: what is queued for the vault is taken
     * in, its timeline is copied home, its enrolment and queue are forgotten, and its capture store
     * is destroyed. Shared by [removeVaultPassphrase] and [recoverVault], which end in the same
     * unprotected entry. False when it did not complete; [vmk] stays the caller's.
     */
    private fun moveCapturesHome(vmk: ByteArray, indexId: ByteArray): Boolean {
        val homeKey = VaultKeyringSession.captureStoreKey() ?: return false
        return try {
            runCatching {
                val home = OpenVault(homeKey, VaultKeys.metadataBlobId(homeKey), -1)
                CaptureIngest.ingestVault(store, home, OpenVault(vmk, indexId, -1))
                CaptureIngest.moveVaultHome(store, homeKey, vmk)
                CaptureStore.forget(store, home, indexId)
                CaptureStore.destroy(store, vmk)
            }.onFailure { Log.w(TAG, "a vault's captures could not be moved home", it) }.isSuccess
        } finally {
            homeKey.fill(0)
        }
    }

    /**
     * The destroy dialog's gate — `protected-vault-destroy-gate` D1. Proves the passphrase that
     * opens the vault at [position] and returns that vault's key, which is what the destruction
     * and the return then run on.
     *
     * A vault with its own passphrase takes that passphrase alone; any other vault takes the
     * list's, proven against its home slot with the panic phrase refused
     * ([confirmsListPassphrase]). One derivation either way. Null for a wrong passphrase, the
     * panic phrase, an empty position, or a protected entry with no recorded parameter set.
     * [passphrase] and every KEK are zeroed on every path; the returned key is the caller's.
     */
    fun destroyKey(position: Int, passphrase: ByteArray): ByteArray? {
        try {
            val entry = VaultKeyringSession.vaults().firstOrNull { it.position == position } ?: return null
            if (!entry.hasOwnPassphrase) {
                if (!confirmsListPassphrase(passphrase)) return null
                return VaultKeyringSession.withVaultKey(position, null) { vmk, _ -> vmk.copyOf() }
            }
            val kek = currentKek(position, passphrase) ?: return null
            return try {
                VaultKeyringSession.withVaultKey(position, kek) { vmk, _ -> vmk.copyOf() }
            } finally {
                kek.fill(0)
            }
        } finally {
            passphrase.fill(0)
        }
    }

    /**
     * Puts a vault recovered from its own 24 words onto the open list — `protected-vault-destroy-gate`
     * D6. Where an entry already points at that vault's index, that entry is taken back rather than
     * a second one added, so no two entries ever name the same index:
     *
     * - an unprotected match takes [name] and keeps its appearance and key;
     * - a protected match proves [vmk] against its index, moves its captures home, and is then
     *   re-sealed under the keyring entry key with [name], in one save.
     *
     * Otherwise `VaultKeyringSession.recoverVaultOntoKeyring`, unchanged. Null for every failure;
     * the screen reports one outcome for all of them. [vmk] stays the caller's.
     */
    fun recoverVault(name: String, vmk: ByteArray): Int? {
        val indexId = VaultKeys.metadataBlobId(vmk)
        try {
            val listed = VaultKeyringSession.vaults().firstOrNull { it.indexBlobId.contentEquals(indexId) }
                ?: return VaultKeyringSession.recoverVaultOntoKeyring(store, name, vmk)
            if (!listed.hasOwnPassphrase) {
                val appearance = VaultAppearance.of(listed.appearance)
                return listed.position.takeIf { VaultKeyringSession.editVault(store, it, name, appearance) }
            }
            runCatching { MetadataDb.load(store, indexId, vmk) }.getOrNull() ?: return null
            if (!moveCapturesHome(vmk, indexId)) return null
            return listed.position.takeIf { VaultKeyringSession.retakeEntry(store, it, vmk, name) }
        } finally {
            indexId.fill(0)
        }
    }

    /**
     * The KEK [passphrase] gives the protected entry at [position], under the set it records, and
     * only if it opens that entry. Null for a wrong passphrase or an entry with none.
     */
    private fun currentKek(position: Int, passphrase: ByteArray): ByteArray? {
        val entry = VaultKeyringSession.vaults().firstOrNull { it.position == position && it.hasOwnPassphrase } ?: return null
        val kek = VaultKeyringSession.entryPassphraseKek(passphrase, position, entry.kdfSet) ?: return null
        if (VaultKeyringSession.withVaultKey(position, kek) { _, _ -> true } == true) return kek
        kek.fill(0)
        return null
    }

    /**
     * A later view of a kit — D2, D4. [words] is null when nothing is shown: [wrongPassphrase]
     * for a passphrase that does not open it, otherwise no view was left or the count could not
     * be saved, and nothing was spent. [left] is the views left after this one.
     */
    class KitView(val words: List<String>? = null, val left: Int = -1, val wrongPassphrase: Boolean = false)

    /**
     * The open list's own kit: the passphrase is checked, the view is spent and saved, and only
     * then are the words encoded — so a kill mid-view never gives the view back.
     */
    fun viewListKit(mnemonic: Mnemonic, passphrase: ByteArray): KitView {
        if (!confirmsListPassphrase(passphrase)) return KitView(wrongPassphrase = true)
        val left = VaultKeyringSession.spendListKitView(store) ?: return KitView()
        val words = VaultKeyringSession.withKey { mnemonic.encode(it) } ?: return KitView()
        return KitView(words, left)
    }

    /**
     * The protected vault at [position]'s own kit, with that vault's own passphrase — D3. The
     * VMK it unseals is what the words encode, and it is zeroed once they are. Spends that
     * vault's count and not the list's. [passphrase] is zeroed on every path.
     */
    fun viewVaultKit(mnemonic: Mnemonic, position: Int, passphrase: ByteArray): KitView {
        val entry = VaultKeyringSession.vaults().firstOrNull { it.position == position && it.hasOwnPassphrase }
        val kek = try {
            entry?.let { VaultKeyringSession.entryPassphraseKek(passphrase, position, it.kdfSet) }
        } finally {
            passphrase.fill(0)
        }
        val vmk = try {
            kek?.let { VaultKeyringSession.vaultKeyForKit(position, it) }
        } finally {
            kek?.fill(0)
        } ?: return KitView(wrongPassphrase = true)
        try {
            val left = VaultKeyringSession.spendVaultKitView(store, position) ?: return KitView()
            return KitView(mnemonic.encode(vmk), left)
        } finally {
            vmk.fill(0)
        }
    }

    /** The refusals that need no key, checked before any derivation is spent. */
    private fun validatePhrase(passphrase: ByteArray, phrase: ByteArray): PanicResult? = when {
        phrase.size < MIN_PASSPHRASE_BYTES -> PanicResult.TOO_SHORT
        Panic.withinOneEdit(phrase, passphrase) -> PanicResult.TOO_CLOSE
        else -> null
    }

    /**
     * Null when [current] proves [panicSlot] is this keyring's. Otherwise the refusal —
     * and where the slot opens into a different keyring, the record is cleared on the way
     * out, because the slot is not this keyring's to write.
     */
    private fun proveCurrent(
        slots: SlotArray,
        panicSlot: Int,
        home: Int,
        current: ByteArray,
    ): PanicResult? = when (opensThisKeyring(slots, panicSlot, current)) {
        true -> null
        null -> PanicResult.WRONG_PANIC_PHRASE
        false -> {
            VaultKeyringSession.recordPanic(store, -1, home)
            PanicResult.CLEARED
        }
    }

    /** Seals the open keyring's key and blob ID into [slot] under [kek]. Null with no keyring open. */
    private fun sealThisKeyring(slots: SlotArray, slot: Int, kek: ByteArray): Unit? {
        val id = VaultKeyringSession.keyringBlobId() ?: return null
        return try {
            VaultKeyringSession.withKey { key -> slots.seal(slot, kek, key, id) }
        } finally {
            id.fill(0)
        }
    }

    // --- recovery (P1-12) ------------------------------------------------------

    /**
     * Opens a keyring from its 24-word mnemonic and seals it under [newPassphrase].
     *
     * Works on an install that has never held this keyring: the words carry its key,
     * the key derives its blob ID, and the keyring names everything else. Without
     * [source] it cannot restore content — the blobs must already be back.
     *
     * Returns whether it opened and **nothing about why not**. A bad checksum, a
     * valid mnemonic for a keyring whose blobs are absent, and a valid mnemonic on an
     * empty install are one outcome: distinguishing them would say whether a keyring
     * was ever here. The one refusal is [Refusal.PASSPHRASE_IN_USE], which says only
     * what typing [newPassphrase] at the lock screen would already say — and, with a
     * [source], the backup refusals, which say nothing the words' holder cannot learn
     * from the backup the words just opened.
     *
     * **One slot is written, and it is the keyring's own** — `recovery-own-slot-only`,
     * D1. The keyring records its home slot, so the new passphrase is sealed there and
     * the forgotten one stops working; every other slot, and so every other list,
     * decoy and panic phrase, is left byte-for-byte as it was. Choosing the forgotten
     * passphrase again is allowed: it opens only the home slot.
     *
     * **[source] brings the vault's files back as well** — `backup-file-recover-and-import`,
     * D5. In order: the backup must open with the words ([Refusal.BACKUP_NOT_OPENED]); the
     * words' list must be on this phone ([Refusal.LIST_NOT_HERE]); the passphrase checks
     * pass ([checkRecovery]); and only then are the files copied, with nothing already here
     * overwritten, the backup's keyring entries adopted, and the home slot sealed
     * ([commitRecovery]). Every refusal comes before the first write.
     *
     * **[fresh] is the empty-phone restore** (D4), and `MainActivity` passes it only
     * when the blob store was empty. There is no other list to protect, and the slots the
     * keyring's record names were on another phone, so a new slot file is written and
     * renamed over the old one, and the record is replaced with the one slot sealed here.
     *
     * [onSealing] is called once the files are in and the passphrase is about to be sealed,
     * so the screen can say so. Every byte array handed in is zeroed here on every path.
     */
    fun recover(
        mnemonic: Mnemonic,
        phrase: String,
        newPassphrase: ByteArray,
        fresh: Boolean = false,
        source: BackupSource? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onSealing: () -> Unit = {},
    ): Outcome {
        require(newPassphrase.size >= MIN_PASSPHRASE_BYTES) {
            "passphrase must be at least $MIN_PASSPHRASE_BYTES bytes"
        }
        val failed = Outcome(opened = false, derivations = 0, slotsAttempted = 0)

        val vmk = mnemonic.decode(phrase)
        if (vmk == null) {
            newPassphrase.fill(0)
            return failed
        }

        // `vault-keyring`. **The words stand in for a keyring key now, not a vault
        // key**, so what they recover is the list and everything on it. One kit per
        // keyring rather than one per vault, which is the point: ten kits for ten
        // vaults would be the problem this change exists to remove, written down.
        val keyringId = VaultKeys.keyringBlobId(vmk)
        var check: Check? = null
        try {
            if (source != null && !source.opens(vmk)) return refused(Refusal.BACKUP_NOT_OPENED)

            val (recorded, slot) = if (fresh) {
                if (source != null) {
                    unpackInto(vmk, source, onProgress) { side ->
                        // Nothing to adopt into on an empty phone: the backup's keyring is the list.
                        File(side, hex(keyringId)).renameTo(store.fileOf(keyringId))
                    }?.let { return it }
                }
                // The keyring is the proof. If it is absent or will not open, these words
                // do not open anything *here*, whatever they open elsewhere.
                val keyring = VaultKeyring.load(store, keyringId, vmk) ?: return failed
                onSealing()
                sealFresh(vmk, keyringId, keyring, newPassphrase)
            } else {
                check = checkRecovery(vmk, keyringId, newPassphrase, withBackup = source != null)
                check.refusal?.let { return it }
                commitRecovery(vmk, keyringId, check, source, onProgress, onSealing)?.let { return it }
                (VaultKeyring.load(store, keyringId, vmk) ?: return failed) to check.home
            }

            // `vault-keyring`. The keyring is held open and **no vault is**: the
            // user lands on the list, exactly as they do after any other unlock.
            // A recovery that opened one vault would have to choose which, and
            // there is no answer to that when the words recovered several.
            VaultKeyringSession.holdRecovered(vmk, keyringId, recorded, slot)
            TamperLog.recordRecovery()
            Log.i(TAG, "keyring recovered from mnemonic: ${recorded.vaults.size} vault(s)")
            return Outcome(opened = true, derivations = 1, slotsAttempted = if (fresh) 0 else SlotArray.SLOT_COUNT)
        } finally {
            check?.kek?.fill(0)
            vmk.fill(0)
            keyringId.fill(0)
            newPassphrase.fill(0)
        }
    }

    /** What [checkRecovery] found: a refusal, or everything [commitRecovery] needs. */
    private class Check(
        val refusal: Outcome? = null,
        val home: Int = -1,
        val slots: SlotArray? = null,
        val kek: ByteArray? = null,
    )

    private fun refused(refusal: Refusal, derivations: Int = 0) =
        Outcome(opened = false, derivations = derivations, slotsAttempted = if (derivations > 0) SlotArray.SLOT_COUNT else 0, refusal = refusal)

    /**
     * [recover]'s checks — D5.3 and D5.4. **Writes nothing.** The words' list must be on
     * this phone with a home slot, and [newPassphrase] must open no slot but that one. The
     * KEK it derives is handed on in the result and zeroed by [recover].
     */
    private fun checkRecovery(key: ByteArray, id: ByteArray, newPassphrase: ByteArray, withBackup: Boolean): Check {
        val failed = Check(refusal = Outcome(opened = false, derivations = 0, slotsAttempted = 0))
        val keyring = VaultKeyring.load(store, id, key)
            ?: return if (withBackup) Check(refusal = refused(Refusal.LIST_NOT_HERE)) else failed
        // ponytail: no fallback for a keyring with no home slot — every keyring sealed since
        // `recovery-own-slot-only` records one, and there is no installed base.
        val home = keyring.homeSlot
        if (home !in 0 until SlotArray.SLOT_COUNT || !slotsFile.exists()) return failed
        val slots = SlotArray(slotsFile)
        val kek = VaultKeys.deriveKek(newPassphrase, slots.installSalt(), slots.kdfParams())
        if (VaultUnlock.opensAnySlot(kek, slots, except = home)) {
            kek.fill(0)
            return Check(refusal = refused(Refusal.PASSPHRASE_IN_USE, derivations = 1))
        }
        return Check(home = home, slots = slots, kek = kek)
    }

    /**
     * [recover]'s writes — D5.5. With a [source], its files come in first and its keyring's
     * vaults join the list here; then the home slot is sealed. Null when done, or the
     * refusal where the backup did not unpack — in which case no slot was written.
     */
    private fun commitRecovery(
        key: ByteArray,
        id: ByteArray,
        check: Check,
        source: BackupSource?,
        onProgress: (Int, Int) -> Unit,
        onSealing: () -> Unit,
    ): Outcome? {
        if (source != null) {
            unpackInto(key, source, onProgress) { side ->
                VaultKeyringSession.adoptEntries(store, BlobStore(side, File(side, "incoming")), key, id)
            }?.let { return it }
        }
        onSealing()
        check.slots!!.seal(check.home, check.kek!!, key, id)
        return null
    }

    /**
     * Unpacks [source] into the store with its keyring copy in [sideDir], runs [then] over
     * that directory, and removes it. Null on success, or the refusal: a backup that did
     * not unpack has already removed what it wrote.
     */
    private fun unpackInto(
        key: ByteArray,
        source: BackupSource,
        onProgress: (Int, Int) -> Unit,
        then: (File) -> Unit,
    ): Outcome? {
        val side = sideDir.also { it.deleteRecursively() }
        try {
            if (!source.unpack(key, side, onProgress)) return refused(Refusal.BACKUP_INCOMPLETE)
            then(side)
            return null
        } finally {
            side.deleteRecursively()
        }
    }

    /**
     * Brings a backup's vault back to its list, which is on this phone and not open —
     * `backup-file-recover-and-import`, D4.4.
     *
     * The backup must open with [keyringKey] and its list must be here; then its files are
     * copied with nothing already here overwritten, and its keyring's vaults join that list.
     * **No slot is written**, and nothing of that list is held open afterwards. Where the
     * list is not here, [Refusal.LIST_NOT_HERE] is Import's cue for [restoreAsList].
     *
     * Also serves the open list, whose key [VaultKeyringSession.adoptEntries] recognises.
     * [keyringKey] is zeroed here on every path.
     */
    fun restoreIntoList(
        keyringKey: ByteArray,
        source: BackupSource,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Outcome {
        val id = VaultKeys.keyringBlobId(keyringKey)
        try {
            if (!source.opens(keyringKey)) return refused(Refusal.BACKUP_NOT_OPENED)
            if (VaultKeyring.load(store, id, keyringKey) == null) return refused(Refusal.LIST_NOT_HERE)
            unpackInto(keyringKey, source, onProgress) { side ->
                VaultKeyringSession.adoptEntries(store, BlobStore(side, File(side, "incoming")), keyringKey, id)
            }?.let { return it }
            Log.i(TAG, "a backup's vault returned to its list")
            return Outcome(opened = true, derivations = 0, slotsAttempted = 0)
        } finally {
            keyringKey.fill(0)
            id.fill(0)
        }
    }

    /**
     * Brings another list's backup back **as that list** — `backup-file-recover-and-import`,
     * D1 and D4.5. Only with a list open, which is what makes a safe slot choosable.
     *
     * **Every refusal comes before the first write**: the backup must open with
     * [keyringKey], its list must not already be here, [newPassphrase] must open no slot
     * ([Refusal.PASSPHRASE_IN_USE]), and there must be a slot the open list neither holds
     * nor knows to be occupied ([Refusal.NO_ROOM]). Then, in order: the files come in with
     * nothing already here overwritten; the backup's keyring is saved into the store with
     * its record replaced — this slot as home, the open list's knowledge plus its slot and
     * this one as known (born knowing what its creator knew, as [create] does), panic off,
     * because the old phone's panic slot is not here; the slot is sealed; and only then does
     * the open list record it, `slot-occupancy-map` D3's order.
     *
     * The open list stays open and nothing of the restored list is held. Its 24 words do not
     * change. [onSealing] is called when the files are in. Every array is zeroed here.
     */
    fun restoreAsList(
        keyringKey: ByteArray,
        source: BackupSource,
        newPassphrase: ByteArray,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onSealing: () -> Unit = {},
    ): Outcome {
        val failed = Outcome(opened = false, derivations = 0, slotsAttempted = 0)
        val id = VaultKeys.keyringBlobId(keyringKey)
        var kek: ByteArray? = null
        try {
            if (newPassphrase.size < MIN_PASSPHRASE_BYTES) return failed
            if (!VaultKeyringSession.isOpen || !slotsFile.exists()) return failed
            if (!source.opens(keyringKey)) return refused(Refusal.BACKUP_NOT_OPENED)
            // Its list is here: that is [restoreIntoList]'s, and sealing a second slot for
            // it would give one list two passphrases.
            if (VaultKeyring.load(store, id, keyringKey) != null) return failed

            val slots = SlotArray(slotsFile)
            kek = VaultKeys.deriveKek(newPassphrase, slots.installSalt(), slots.kdfParams())
            if (VaultUnlock.opensAnySlot(kek, slots)) return refused(Refusal.PASSPHRASE_IN_USE, derivations = 1)
            // ponytail: no DECOY_PASSPHRASE_NEEDED guard as `create` has — it exists for
            // keyrings written before the occupancy record, and there is no installed base.
            val known = VaultKeyringSession.knownSlots() or slotBit(VaultKeyringSession.openSlotIndex())
            val free = (0 until SlotArray.SLOT_COUNT).filterNot { (known shr it) and 1 == 1 }
            if (free.isEmpty()) return refused(Refusal.NO_ROOM, derivations = 1)
            val slot = free[rng.nextInt(free.size)]

            var restored = false
            unpackInto(keyringKey, source, onProgress) { side ->
                val theirs = VaultKeyring.load(BlobStore(side, File(side, "incoming")), id, keyringKey)
                    ?: return@unpackInto
                onSealing()
                val recorded = VaultKeyring.withPanicRecord(
                    VaultKeyring.withKnownSlots(theirs, known or slotBit(slot)), -1, slot,
                )
                // Durable before the slot names it — the migration's ordering.
                VaultKeyring.save(store, id, keyringKey, recorded)
                slots.seal(slot, kek, keyringKey, id)
                restored = true
            }?.let { return it }
            if (!restored) return failed

            VaultKeyringSession.recordKnownSlots(store, known or slotBit(slot))
            Log.i(TAG, "a backup's list restored as its own list")
            return Outcome(opened = true, derivations = 1, slotsAttempted = SlotArray.SLOT_COUNT)
        } finally {
            kek?.fill(0)
            keyringKey.fill(0)
            id.fill(0)
            newPassphrase.fill(0)
        }
    }

    /**
     * [recover]'s empty-phone restore — D4. The new slot file is built beside the old
     * one, the keyring's record is replaced with the one slot sealed here, and only
     * then is the file renamed into place: `slot-occupancy-map` D3's order, because a
     * record claiming a slot costs nothing and a slot naming a record that is not
     * there costs a keyring.
     */
    private fun sealFresh(
        keyringKey: ByteArray,
        keyringId: ByteArray,
        keyring: VaultKeyring.Keyring,
        newPassphrase: ByteArray,
    ): Pair<VaultKeyring.Keyring, Int> {
        val tmp = File(slotsFile.parentFile, "${slotsFile.name}.tmp")
        try {
            tmp.delete()
            val slots = SlotArray(tmp)
            slots.initialiseIfAbsent()
            // Uniform, as `create` draws: a fixed choice is a pattern.
            val slot = rng.nextInt(SlotArray.SLOT_COUNT)
            val kek = VaultKeys.deriveKek(newPassphrase, slots.installSalt(), slots.kdfParams())
            try {
                slots.seal(slot, kek, keyringKey, keyringId)
            } finally {
                kek.fill(0)
            }
            // `panic-phrase`, D6. The panic slot the old phone's record named is not
            // on this phone, so the restored keyring comes back with panic off.
            val recorded = VaultKeyring.withPanicRecord(
                VaultKeyring.withKnownSlots(keyring, slotBit(slot)), -1, slot,
            )
            VaultKeyring.save(store, keyringId, keyringKey, recorded)
            check(tmp.renameTo(slotsFile)) { "could not move slot file into place" }
            return recorded to slot
        } finally {
            tmp.delete()
        }
    }

    /**
     * Imports a vault from a `.vault` archive into the open keyring — P1-14.
     *
     * The archive carries the blobs and the VMK, so the VMK is preserved, which is
     * what makes it the *same* vault: its recovery kit still opens it and its index
     * blob ID still resolves.
     *
     * `recovery-own-slot-only`, D5. **Only into an open keyring**, and it seals no
     * slot at all: the vault arrives on the list under the master passphrase already
     * accepted, which is the whole of what makes an archive portable rather than a
     * second thing to remember. The archive's own passphrase opened the file and does
     * not follow it in. With no keyring open this is the ordinary failure.
     *
     * One outcome for every failure: a wrong passphrase, a corrupt archive and a file
     * that was never an archive are indistinguishable. A full keyring is
     * [Refusal.NO_ROOM], with nothing written.
     */
    fun importArchive(
        archive: java.io.InputStream,
        archivePassphrase: ByteArray,
        /** `vault-keyring`. What the imported vault is called on the list it joins. */
        importedName: String = "Imported vault",
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Outcome {
        val failed = Outcome(opened = false, derivations = 0, slotsAttempted = 0)
        if (!VaultKeyringSession.isOpen) {
            archivePassphrase.fill(0)
            return failed
        }

        val imported = VaultArchive.read(archive, archivePassphrase, store, onProgress) ?: return failed

        val vmk = imported.vmk
        val metadataBlobId = VaultKeys.metadataBlobId(vmk)
        try {
            // The index has to be there and has to open, or the archive did not
            // carry a vault whatever else it carried.
            val db = runCatching { MetadataDb.load(store, metadataBlobId, vmk) }.getOrNull()
                ?: run {
                    imported.blobIds.forEach { store.delete(it) }
                    return failed
                }

            val position = VaultKeyringSession.addImported(store, importedName, vmk, metadataBlobId)
            if (position == null) {
                imported.blobIds.forEach { store.delete(it) }
                return Outcome(opened = false, derivations = 0, slotsAttempted = 0, refusal = Refusal.NO_ROOM)
            }

            // The device's **own** install chaff — §6 and §11. Owned chaff came in
            // the archive; copying another device's unowned reserve would give two
            // devices identical noise, which is a correlation where §6 wanted
            // ambiguity.
            repeat(INSTALL_CHAFF_BLOBS) { store.writeChaff(1) }
            Log.i(TAG, "vault imported from archive: ${db.items.size} items")
            return Outcome(opened = true, derivations = 0, slotsAttempted = 0)
        } finally {
            vmk.fill(0)
            metadataBlobId.fill(0)
        }
    }

    // --- unlock --------------------------------------------------------------

    /**
     * Attempts to open a vault.
     *
     * Returns whether it opened and nothing else about *why*. The slot index is
     * never returned and never logged: P2-1 requires a wrong passphrase and a
     * passphrase for an empty slot to be indistinguishable, and naming the slot
     * that matched breaks that before P2-1 is even written.
     *
     * The counts are carried through from [VaultUnlock] because they are the
     * evidence an early exit would break — a stopwatch cannot see microseconds
     * against a ~460 ms derivation.
     */
    fun unlock(
        passphrase: ByteArray,
        /**
         * P2-3. How to destroy the biometric wrap — `BiometricWrap.disable`, passed
         * in rather than called, so this file keeps referencing no `KeyStore` and
         * `PassphrasePathTest`'s source scan keeps passing (I7).
         */
        destroyWrap: () -> Unit = {},
        /**
         * `cloud-mirror`. How to destroy the mirrored store, passed in for the same
         * reason [destroyWrap] is: this file holds no Android types, and the mirror
         * lives behind a `DocumentsProvider`.
         *
         * Whatever is passed here **must return promptly** — see [destroy]. Panic's
         * cost may not diverge from an ordinary unlock's, so this schedules the
         * deletion; it does not wait for a provider to answer.
         */
        destroyMirror: () -> Unit = {},
        /**
         * `vault-keyring`. Called when this unlock migrated the install into a keyring.
         *
         * §4.1's wrap seals the slot's payload, and that payload's meaning changes at
         * a migration — so a wrap written before it names a vault that no slot points
         * at any more. It cannot be re-sealed here: writing one needs an authenticated
         * cipher and therefore a prompt, which an unlock in progress has no business
         * raising. It is retired instead, and the user re-enrols with one tap.
         */
        onMigrated: () -> Unit = {},
    ): Outcome {
        val slots = SlotArray(slotsFile)
        slots.initialiseIfAbsent()

        try {
            val result = VaultUnlock.unlock(passphrase, slots)
            val vault = result.vault

            // `vault-keyring`. A passphrase opens a **keyring**, not a vault: what
            // follows here resolves the 48 bytes the slot gave up into one, migrating
            // an older install on the way if that is what it is. Opening a vault is a
            // separate act, from the list, and it is where `open` now runs.
            //
            // A slot that opened but whose keyring cannot be read reports exactly what
            // a wrong passphrase reports. There is one outcome on this path and it has
            // always been "something opened"; a second kind of failure here would be a
            // new thing for an observer to tell apart.
            val resolved = vault != null &&
                VaultKeyringSession.resolve(store, slots, passphrase, vault, onMigrated)

            if (!resolved) {
                vault?.vmk?.fill(0)
                vault?.metadataBlobId?.fill(0)
                TamperLog.recordFailedUnlock()
            }

            // `panic-phrase`. **A panic unlock is recognised here, and only here** — by the
            // slot that opened being the one the resolved keyring names as its panic slot.
            // One byte compared on a record already in memory: the derivation, the eight
            // attempts and the outcome were the ordinary ones, so nothing before this line
            // could tell the two apart, and nothing after it reports which it was.
            //
            // Nothing is destroyed by a credential that opens nothing.
            val panic = resolved && vault != null &&
                VaultKeyringSession.panicSlot() == vault.slotIndex
            //
            // **After the keyring is resolved, which is a change of order** —
            // `vault-keyring`. The log entry this writes belongs to a *vault*, and at
            // this point in an unlock there is no longer one open: the keyring has to
            // be readable before a vault's log can be reached. The rule the old order
            // protected is unchanged and still holds, because it was never about the
            // resolve — the entry is still written **before** any slot is destroyed,
            // so a wipe interrupted halfway leaves the record of what was attempted.
            if (resolved && vault != null && panic) {
                destroy(slots, vault, destroyWrap, destroyMirror)
            }

            return Outcome(
                opened = resolved,
                derivations = result.derivations,
                slotsAttempted = result.slotsAttempted,
            )
        } finally {
            passphrase.fill(0)
        }
    }

    /**
     * P2-3's panic: everything but this keyring loses the way in.
     *
     * **Order matters and is asserted.** The log entry goes first, so a wipe
     * interrupted halfway still leaves the record of what was attempted. Then every
     * slot except the keyring's home slot and its panic slot is overwritten with
     * CSPRNG — `panic-phrase`: the slot that authenticated *is* the panic slot, so the
     * home slot has to come from the keyring's record or the owner's own passphrase
     * would be destroyed with the rest — then the
     * biometric wrap. Only afterwards does the ordinary [open] run, so what the user
     * sees is an unlock and nothing else.
     *
     * **Destruction is by exclusion, which is why it needs no list.** This cannot
     * enumerate the vaults it is destroying — nothing in this app can, since a slot
     * is distinguishable only to a KEK that opens it — and it does not need to: it
     * holds the one index to keep. `FORMAT.md` §10 records the consequence, which is
     * that a decoy handed over with a panic credential takes the real vault with it
     * whichever way round the user meant.
     *
     * **What survives, deliberately:** every blob. Wrapping material is what the
     * task says and what this destroys, so the vaults it closes still exist as
     * ciphertext and their 24-word kits still open them. That is how the owner gets
     * the real vault back afterwards, and it is stated on the enrolment screen
     * rather than left to be discovered.
     *
     * **The mirror is the exception, and it has to be** — `cloud-mirror`. That
     * survival argument holds only while the blobs die with the phone. A copy of
     * them in a folder someone's cloud provider syncs does not, and the keyring's
     * 24 words open it, so panic without [destroyMirror] destroys the wrapping on
     * this device and leaves the whole vault readable somewhere else. It is passed
     * in rather than called, exactly as [destroyWrap] is and for exactly the same
     * reason: no Android type appears in this file and `PassphrasePathTest` keeps
     * it that way (I7).
     *
     * The install salt is untouched and both kept slots survive, so the owner's
     * passphrase and the panic phrase both still open the surviving keyring. A panic
     * phrase that stopped working after one use would fail like a wrong passphrase the
     * second time it was typed, and that failure would say something had happened.
     */
    private fun destroy(
        slots: SlotArray,
        vault: OpenVault,
        destroyWrap: () -> Unit,
        destroyMirror: () -> Unit = {},
    ) {
        // Before anything else, and into the only log this app has keys for. The
        // explicit-argument append, not the session one: the session is not set
        // until `open` runs, which is after this on purpose — the record must
        // survive a wipe that is interrupted.
        //
        // **The log belongs to a vault, and a slot no longer names one** —
        // `vault-keyring`. The entry goes into the first vault on the keyring being
        // kept, which is the one an owner opening this credential would see first.
        // A keyring holding nothing has no log to write to and is wiped without one:
        // an empty keyring records nothing because there is nothing that records.
        val logTarget = VaultKeyringSession.openVault(0)
        if (logTarget != null) {
            val db = MetadataDb.load(store, logTarget.metadataBlobId, logTarget.vmk)
            TamperLog.append(
                store, db, logTarget.metadataBlobId, logTarget.vmk, TamperLog.PANIC_TRIGGERED,
            )
            // Left again immediately: panic opens no vault, it only writes one line.
            VaultKeyringSession.leaveVault()
        }

        // `cloud-mirror`, here: after the log entry and **before** the first slot
        // is overwritten, which is the existing rule about an interrupted wipe
        // applied to the one copy this device does not hold.
        //
        // The caller hands over something that *schedules* the deletion rather than
        // performing it. A provider that is slow, absent or hung would otherwise
        // stretch this unlock past an ordinary one, and `panic-pin` forbids the two
        // being distinguishable by cost — the slot wipe below is the part that must
        // succeed, and nothing here may stand in front of it.
        runCatching { destroyMirror() }

        val home = VaultKeyringSession.homeSlot()
        var destroyed = 0
        for (index in 0 until SlotArray.SLOT_COUNT) {
            if (index == vault.slotIndex || index == home) continue
            slots.destroy(index)
            destroyed++
        }

        // The wrap is VMK-wrapping material too, and it goes silently: no notice,
        // no digest line, nothing on the next launch that says it was ever there.
        runCatching { destroyWrap() }

        Log.i(TAG, "panic: $destroyed slots destroyed")
    }

    /**
     * Opens a vault from keys recovered somewhere other than the passphrase path
     * — P1-9's biometric wrap is the first such caller, P1-12's recovery kit will
     * be the second.
     *
     * This is the tail of [unlock] and nothing else, which is the point: an
     * unlock that skipped the §6 chaff churn would leave an install whose owner
     * uses biometrics quietly not refreshing chaff, and that difference is
     * visible in modification times. One path, not two.
     *
     * Takes an [OpenVault] and knows nothing about where it came from. That is
     * what keeps I7's structural half true — no `KeyStore`, no `Cipher`, no
     * `BiometricPrompt` anywhere in this file, which `PassphrasePathTest` asserts
     * on the source.
     */
    /** What [reopen] found. Two outcomes, because "no keyring" and "no vault" differ. */
    class Reopened(val keyringOpen: Boolean, val vault: OpenVault?)

    /**
     * Leaves whatever is open and reopens the keyring [passphrase] opens, at
     * [position] — the way back from a decoy.
     *
     * **This exists as a seam, and the reason is a bug that shipped.** Creating a
     * decoy opens its keyring and closes the one it was called from, so the flow has
     * to come back or the app is left inside the decoy — and during first run the
     * steps after it would then enrol the backup, the panic credential and the app
     * identity against the wrong vault. That defect lived entirely in `MainActivity`,
     * where nothing could reach it: `DecoyDeviceTest` drove a *copy* of the flow's
     * sequence written into the test, so it verified its own copy and reported green
     * while the real path was broken.
     *
     * So the round trip is here, in the one place a test can call the same code the
     * activity calls. [passphrase] is zeroed by [unlock] on every path.
     *
     * [position] below zero means the caller had no vault open, only a keyring, and
     * that is what it gets back.
     */
    fun reopen(passphrase: ByteArray, position: Int): Reopened {
        // The decoy is left through the ordinary lock, so "at most one vault's key in
        // memory" holds across the switch as it does everywhere else.
        lockNow()
        if (!unlock(passphrase).opened) return Reopened(keyringOpen = false, vault = null)
        val vault = if (position >= 0) VaultKeyringSession.openVault(position) else null
        return Reopened(keyringOpen = true, vault = vault)
    }

    fun open(
        vault: OpenVault,
        /**
         * `destination-settings-and-slideshow` D5. Memos finished while this vault was locked,
         * made items. Intruder captures are not taken in here: they go to the keyring's capture
         * store when the keyring opens (`intruder-captures-at-home` D3).
         */
        memos: (OpenVault) -> Int = { 0 },
    ) {
        VaultSession.unlocked(vault)

        // One load and one save for both: the churn rewrites the index anyway, and
        // a second load here would read the whole index twice per unlock.
        val db = MetadataDb.load(store, vault.metadataBlobId, vault.vmk)
        churnChaff(db, vault)

        // Before the digest. The session is already set, so the memo ingest can load, add
        // items and save through the open vault.
        val memosAdded = runCatching { memos(vault) }.getOrDefault(0)

        // The memo ingest loads and saves its own index, so a reload picks up what it wrote —
        // `db` here would otherwise be stale, and the log's own save would write back an index
        // without the memos. Only when something was added, so an ordinary unlock still loads
        // the index once.
        val current = if (memosAdded > 0) MetadataDb.load(store, vault.metadataBlobId, vault.vmk) else db
        TamperLog.opened(store, current, vault.metadataBlobId, vault.vmk)
    }

    /**
     * §6: one owned chaff blob rewritten per unlock, fresh content and fresh ID.
     *
     * Only chaff this vault recorded. Nothing scans the store to pick — the store
     * cannot tell chaff from real content or from another vault's blob, so a
     * chooser would be selecting at random between the three.
     */
    private fun churnChaff(db: MetadataDb, vault: OpenVault) {
        if (db.ownedChaff.isEmpty()) return

        val victim = db.ownedChaff[rng.nextInt(db.ownedChaff.size)]
        val fresh = store.refreshChaff(listOf(victim)).single()

        val i = db.ownedChaff.indexOfFirst { it.contentEquals(victim) }
        db.ownedChaff[i] = fresh
        db.save(store, vault.metadataBlobId, vault.vmk)
    }

    // --- lock ----------------------------------------------------------------

    /**
     * Closes the vault: the key goes, and so do the decrypted pixels.
     *
     * **Mechanism only.** Nothing here decides *when* to lock — no timeout, no
     * lifecycle observer, no lock-on-background. P1-10's `AutoLock` decides that,
     * from `Application`, and calls [lockNow] rather than reimplementing this.
     */
    fun lock() = lockNow()
}
