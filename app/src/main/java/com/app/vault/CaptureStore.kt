package com.app.vault

import android.content.Context

/**
 * The keyring's capture store — `intruder-captures-at-home` D1.
 *
 * An index of its own under the keyring's capture store key, used exactly as a VMK is: so
 * `MetadataDb`, `TamperLog`, `Thumbnails`, the viewer and [CaptureIngest] work on it unchanged.
 * Listed on no keyring and never set as the session's vault.
 */
object CaptureStore {

    /**
     * The store under [captureStoreKey], created on first use: an empty index with a fresh
     * tamper log, saved at the ID the key derives. The key stays the caller's — the returned
     * [OpenVault] holds it, not a copy.
     */
    fun open(store: BlobStore, captureStoreKey: ByteArray): OpenVault {
        val id = VaultKeys.metadataBlobId(captureStoreKey)
        if (!store.fileOf(id).exists()) {
            MetadataDb.empty().apply { logBlobId = TamperLog.create(store) }.save(store, id, captureStoreKey)
        }
        return OpenVault(captureStoreKey, id, -1)
    }

    /**
     * What a keyring opening does with pending captures — D3. Only on the destination keyring;
     * any other leaves every capture pending. A keyring with nothing pending and no store gets no
     * store. Returns the store and how many captures landed, or null when nothing was done.
     */
    fun takeIn(context: Context, store: BlobStore, captureStoreKey: ByteArray): Pair<OpenVault, Int>? {
        if (!destinationIs(context, captureStoreKey)) return null
        val pending = Capture.Mode.entries.any { Capture.pending(context, it).isNotEmpty() }
        if (!pending && !store.fileOf(VaultKeys.metadataBlobId(captureStoreKey)).exists()) return null
        val vault = open(store, captureStoreKey)
        return vault to CaptureIngest.ingest(context, store, vault)
    }

    /**
     * Removes [items] from the store for [outcome] — moved (with the vault's name at that moment)
     * or deleted — `intruder-captures-at-home` D5, D10. Returns the committed index.
     *
     * [VaultDeletion.purge]'s order: one commit removes the rows, records their blobs as owned
     * chaff and appends one `0x47` per capture, so a kill before the unlink leaves nothing a scan
     * would have to find; then the blobs are unlinked and a second commit untracks them.
     */
    fun remove(store: BlobStore, vault: OpenVault, items: List<VaultItem>, outcome: Int, vaultName: String? = null): MetadataDb {
        val doomed = items.map { it.itemId }.toSet()
        val blobIds = items.flatMap { it.blobIds }
        MetadataDb.commit(store, vault.metadataBlobId, vault.vmk) { db ->
            db.items.removeAll { it.itemId in doomed }
            db.ownedChaff += blobIds
            items.forEach {
                TamperLog.append(
                    store, db, vault.metadataBlobId, vault.vmk, TamperLog.INTRUDER_LEFT,
                    TamperLog.Left.leftPayload(it.createdAt ?: 0L, outcome, CaptureLog.modeOf(it.filename), vaultName),
                )
            }
        }
        items.forEach { item ->
            item.blobIds.forEach { store.delete(it) }
            item.thumb?.let(ThumbnailCache::evict)
        }
        return MetadataDb.commit(store, vault.metadataBlobId, vault.vmk) { db ->
            db.ownedChaff.removeAll { id -> blobIds.any { it.contentEquals(id) } }
            db
        }
    }

    // --- a vault's own capture, held in the keyring's store — `per-vault-intruder-capture` D2 ---

    /** Per mode per vault, as the home queue's [Capture.PER_MODE_CAP]. */
    const val VAULT_PER_MODE_CAP = Capture.PER_MODE_CAP

    /** The enrolment of the vault whose index is [indexBlobId], or null when it has none. */
    fun enrolment(db: MetadataDb, indexBlobId: ByteArray): VaultEnrolment? =
        db.vaultCaptures.firstOrNull { it.indexBlobId.contentEquals(indexBlobId) }

    /**
     * Writes the vault's enrolment into the keyring's store — D5. Empty [modes] removes it; the
     * captures already queued stay queued and are taken in at the vault's next opening.
     */
    fun enrol(store: BlobStore, keyringStore: OpenVault, indexBlobId: ByteArray, publicKey: ByteArray, modes: Set<Capture.Mode>) {
        MetadataDb.commit(store, keyringStore.metadataBlobId, keyringStore.vmk) { db ->
            db.vaultCaptures.removeAll { it.indexBlobId.contentEquals(indexBlobId) }
            if (modes.isNotEmpty()) db.vaultCaptures += VaultEnrolment(indexBlobId.copyOf(), publicKey.copyOf(), modes)
            db
        }
    }

    /**
     * Queues a capture sealed to the vault whose index is [indexBlobId] — D3. Past
     * [VAULT_PER_MODE_CAP] for that vault and mode, the oldest is evicted by deleting its sealed
     * blob: no key opens it here, and none needs to. Other vaults' queues are untouched.
     */
    fun queue(store: BlobStore, keyringStore: OpenVault, indexBlobId: ByteArray, mode: Capture.Mode, blobId: ByteArray, sealedLength: Int) {
        val evicted = mutableListOf<ByteArray>()
        MetadataDb.commit(store, keyringStore.metadataBlobId, keyringStore.vmk) { db ->
            db.vaultPending += VaultPending(indexBlobId.copyOf(), mode, blobId, sealedLength)
            val mine = db.vaultPending.filter { it.mode == mode && it.indexBlobId.contentEquals(indexBlobId) }
            mine.take((mine.size - VAULT_PER_MODE_CAP).coerceAtLeast(0)).forEach {
                db.vaultPending.remove(it)
                evicted += it.blobId
            }
            db
        }
        evicted.forEach { store.delete(it) }
    }

    /**
     * Everything the keyring's store holds for the vault whose index is [indexBlobId] — its
     * enrolment, its queued captures with their sealed blobs, its queued notices — `per-vault-
     * intruder-capture`, a vault's captures go with it. A keyring with no store has nothing to forget.
     */
    fun forget(store: BlobStore, keyringStore: OpenVault, indexBlobId: ByteArray) {
        if (!store.fileOf(keyringStore.metadataBlobId).exists()) return
        val doomed = mutableListOf<ByteArray>()
        MetadataDb.commit(store, keyringStore.metadataBlobId, keyringStore.vmk) { db ->
            db.vaultCaptures.removeAll { it.indexBlobId.contentEquals(indexBlobId) }
            db.vaultPending.filter { it.indexBlobId.contentEquals(indexBlobId) }.forEach {
                db.vaultPending.remove(it)
                doomed += it.blobId
            }
            db.vaultNotices.removeAll { it.indexBlobId.contentEquals(indexBlobId) }
            db
        }
        doomed.forEach { runCatching { store.delete(it) } }
    }

    /**
     * A vault's own capture store, gone with the vault — its index, log, item blobs and thumbnail
     * packs. Needs the vault's key, so only where it is in hand; elsewhere the blobs remain as
     * unreadable ciphertext, as the vault's own do.
     */
    fun destroy(store: BlobStore, vmk: ByteArray) {
        val key = VaultKeys.captureStoreKey(vmk)
        try {
            val id = VaultKeys.metadataBlobId(key)
            if (!store.fileOf(id).exists()) return
            val db = MetadataDb.load(store, id, key)
            (db.items.flatMap { it.blobIds } + db.thumbPacks.map { it.id } + listOfNotNull(db.logBlobId))
                .forEach { runCatching { store.delete(it) } }
            store.delete(id)
        } finally {
            key.fill(0)
        }
    }

    /** Whether [captureStoreKey]'s keyring is the stored capture destination. */
    fun destinationIs(context: Context, captureStoreKey: ByteArray): Boolean {
        val destination = Capture.destinationKey(context) ?: return false
        val kp = VaultKeys.captureKeypair(captureStoreKey)
        return try {
            destination.contentEquals(kp.publicKey)
        } finally {
            kp.zero()
        }
    }
}
