package com.app.vault

import android.content.Context
import com.app.vault.ui.BiometricLayout
import java.io.ByteArrayInputStream

/**
 * Turning pending intruder captures into capture store items — P2-4,
 * `intruder-captures-at-home` D3.
 *
 * Runs when the destination keyring opens, against that keyring's [CaptureStore], never a
 * vault. Standalone rather than a method on the activity so it can be driven by a test with no
 * UI, and because it references no Android type but `Context` (for the pending queue in
 * `SharedPreferences`).
 *
 * Only the destination opens the captures: a store whose capture public key does not match the
 * stored destination leaves every capture pending — the same "another key opens nothing" shape
 * the log and the blob store already have. Returns how many were taken in.
 */
object CaptureIngest {

    fun ingest(
        context: Context,
        store: BlobStore,
        vault: OpenVault,
    ): Int {
        val kp = VaultKeys.captureKeypair(vault.vmk)
        try {
            val destination = Capture.destinationKey(context) ?: return 0
            if (!destination.contentEquals(kp.publicKey)) return 0

            // P2-9. One commit for the whole drain. This runs at unlock, which is
            // exactly when a resumed import may be committing too — the same lost
            // update as the import path, with a rarer trigger.
            var count = 0
            MetadataDb.commit(store, vault.metadataBlobId, vault.vmk) { db ->
            for (mode in Capture.Mode.entries) {
                for (p in Capture.drainPending(context, mode)) {
                    val sealed = runCatching { store.readSealed(p.blobId, p.sealedLength) }.getOrNull()
                        ?: continue
                    val plain = Capture.open(kp.secretKey, kp.publicKey, sealed) ?: continue
                    try {
                        // `cover-fingerprint-dots` D6a. The stage record leads the plaintext;
                        // one this build did not write is not a capture of ours, and goes with
                        // its pending blob.
                        val record = CaptureRecord.read(plain)
                        if (record == null) {
                            store.delete(p.blobId)
                            continue
                        }
                        val media = ByteArrayInputStream(plain, CaptureRecord.BYTES, plain.size - CaptureRecord.BYTES)
                        val written = store.writeItem(media, vault.vmk)
                        // The moment the capture was taken names the item and dates it; the
                        // ingest is only when it was imported.
                        val takenAt = record.takenAt
                        db.items += VaultItem(
                            blobIds = written.blobIds,
                            plaintextLength = written.plaintextLength,
                            thumb = null,
                            filename = filename(mode, takenAt),
                            album = null,
                            folderId = null,
                            createdAt = takenAt,
                            importedAt = System.currentTimeMillis(),
                            tags = emptyList(),
                        )
                        // The sealed pending blob is now a proper encrypted item; its
                        // opaque form is no longer needed.
                        store.delete(p.blobId)
                        // One entry per capture, into the store's own log. The
                        // explicit-argument append, not the session one: the store is
                        // never the session's vault, so the session form would write to
                        // whatever vault is open, or nowhere.
                        //
                        // Stamped with the moment taken and carrying the stage, so the log says
                        // when and how far, not when the keyring was next opened.
                        TamperLog.append(
                            store, db, vault.metadataBlobId, vault.vmk, TamperLog.INTRUDER_CAPTURED,
                            payload = record.stage.payload(),
                            timestamp = takenAt,
                        )
                        count++
                    } finally {
                        plain.fill(0)
                    }
                }
            }
            }
            return count
        } finally {
            kp.zero()
        }
    }

    /**
     * A vault's queued captures and notices, taken in at its opening — `per-vault-intruder-capture`
     * D4. [keyringStore] is the keyring's capture store, where they wait; [vault] is the vault
     * just opened, whose capture store key opens them. Returns how many captures landed.
     *
     * **The vault's store commits first**, then one keyring-store commit removes the rows and the
     * sealed blobs go. An interruption between the two leaves a capture queued *and* held, and the
     * next opening skips one whose moment taken and mode the store already holds. Never neither.
     */
    fun ingestVault(store: BlobStore, keyringStore: OpenVault, vault: OpenVault): Int {
        if (!store.fileOf(keyringStore.metadataBlobId).exists()) return 0
        val queue = MetadataDb.load(store, keyringStore.metadataBlobId, keyringStore.vmk)
        val pending = queue.vaultPending.filter { it.indexBlobId.contentEquals(vault.metadataBlobId) }
        val notices = queue.vaultNotices.filter { it.indexBlobId.contentEquals(vault.metadataBlobId) }
        if (pending.isEmpty() && notices.isEmpty()) return 0

        val key = VaultKeys.captureStoreKey(vault.vmk)
        val kp = VaultKeys.captureKeypair(key)
        try {
            val own = CaptureStore.open(store, key)
            var count = 0
            MetadataDb.commit(store, own.metadataBlobId, key) { db ->
                for (p in pending) {
                    val sealed = runCatching { store.readSealed(p.blobId, p.sealedLength) }.getOrNull() ?: continue
                    val plain = Capture.open(kp.secretKey, kp.publicKey, sealed) ?: continue
                    try {
                        val record = CaptureRecord.read(plain) ?: continue
                        if (db.items.any { it.createdAt == record.takenAt && CaptureLog.modeOf(it.filename) == p.mode }) continue
                        val media = ByteArrayInputStream(plain, CaptureRecord.BYTES, plain.size - CaptureRecord.BYTES)
                        val written = store.writeItem(media, key)
                        db.items += VaultItem(
                            blobIds = written.blobIds,
                            plaintextLength = written.plaintextLength,
                            thumb = null,
                            filename = filename(p.mode, record.takenAt),
                            album = null,
                            folderId = null,
                            createdAt = record.takenAt,
                            importedAt = System.currentTimeMillis(),
                            tags = emptyList(),
                        )
                        TamperLog.append(
                            store, db, own.metadataBlobId, key, TamperLog.INTRUDER_CAPTURED,
                            payload = record.stage.payload(),
                            timestamp = record.takenAt,
                        )
                        count++
                    } finally {
                        plain.fill(0)
                    }
                }
                if (notices.isNotEmpty()) {
                    val logged = db.logBlobId?.let { TamperLog.scan(store, it, key).entries }.orEmpty()
                    for (n in notices) {
                        val payload = TamperLog.Permission.permissionPayload(n.permission, n.state)
                        if (logged.any { it.event == TamperLog.CAPTURE_PERMISSION && it.timestamp == n.at && it.payload.contentEquals(payload) }) continue
                        TamperLog.append(store, db, own.metadataBlobId, key, TamperLog.CAPTURE_PERMISSION, payload = payload, timestamp = n.at)
                    }
                }
            }
            MetadataDb.commit(store, keyringStore.metadataBlobId, keyringStore.vmk) { db ->
                db.vaultPending.removeAll { row -> pending.any { it.blobId.contentEquals(row.blobId) } }
                db.vaultNotices.removeAll { row -> notices.any { sameNotice(it, row) } }
            }
            pending.forEach { store.delete(it.blobId) }
            return count
        } finally {
            kp.zero()
            key.fill(0)
        }
    }

    /**
     * A vault's whole capture timeline, copied into the keyring's capture store — the removal of its
     * own passphrase, `vault-passphrase-manage` D4 step 2. [vmk] is the vault's key, which finds its
     * store; [keyringStoreKey] is the keyring's capture store key, whose store is made if absent.
     * Both stay the caller's. Returns how many captures were copied.
     *
     * Each held capture is re-encrypted under the home store's key with the same filename and moment
     * taken; each `0x41` and `0x47` entry keeps its timestamp and payload. `0x48` is not carried: it
     * describes modes being switched off. **Repeatable:** an item whose moment and mode home already
     * holds, and an entry whose event and timestamp home's log already holds, are skipped, so a removal
     * interrupted after this and run again leaves each capture home once.
     *
     * Unseen stays unseen: a capture past the vault's seen mark is imported at home now. A seen one
     * is dated at or below home's seen mark, so it does not badge the drawer.
     */
    fun moveVaultHome(store: BlobStore, keyringStoreKey: ByteArray, vmk: ByteArray): Int {
        val key = VaultKeys.captureStoreKey(vmk)
        try {
            val ownId = VaultKeys.metadataBlobId(key)
            if (!store.fileOf(ownId).exists()) return 0
            val own = MetadataDb.load(store, ownId, key)
            val carried = own.logBlobId?.let { TamperLog.scan(store, it, key).entries }.orEmpty()
                .filter { it.event == TamperLog.INTRUDER_CAPTURED || it.event == TamperLog.INTRUDER_LEFT }
            val captures = own.items.filter { it.filename.startsWith(CaptureLog.PREFIX) }
            if (carried.isEmpty() && captures.isEmpty()) return 0

            val ownSeen = own.capturesSeenAt ?: 0L
            val home = CaptureStore.open(store, keyringStoreKey)
            var count = 0
            MetadataDb.commit(store, home.metadataBlobId, keyringStoreKey) { db ->
                val homeSeen = db.capturesSeenAt ?: 0L
                for (item in captures) {
                    val mode = CaptureLog.modeOf(item.filename)
                    if (db.items.any { it.createdAt == item.createdAt && CaptureLog.modeOf(it.filename) == mode }) continue
                    val written = MemoIngest.reseal(store, item.blobIds, key, item.plaintextLength, keyringStoreKey)
                    db.items += VaultItem(
                        blobIds = written.blobIds,
                        plaintextLength = written.plaintextLength,
                        thumb = null,
                        filename = item.filename,
                        album = null,
                        folderId = null,
                        createdAt = item.createdAt,
                        importedAt = if (item.importedAt > ownSeen) System.currentTimeMillis() else minOf(item.importedAt, homeSeen),
                        tags = emptyList(),
                    )
                    count++
                }
                val logged = db.logBlobId?.let { TamperLog.scan(store, it, keyringStoreKey).entries }.orEmpty()
                for (e in carried) {
                    if (logged.any { it.event == e.event && it.timestamp == e.timestamp }) continue
                    TamperLog.append(store, db, home.metadataBlobId, keyringStoreKey, e.event, payload = e.payload, timestamp = e.timestamp)
                }
                db
            }
            return count
        } finally {
            key.fill(0)
        }
    }

    /**
     * The permission changes the app noticed, recorded at the keyring's opening —
     * `per-vault-intruder-capture` D8. Each becomes a `0x48` in the keyring store's log **only if**
     * one of [homeModes] needs that permission, so the home timeline never states that a vault is
     * enrolled; and a [VaultNotice] for each enrolled vault whose modes need it.
     *
     * A change already in the log, or already queued for a vault, is not added twice; a vault's
     * opening skips one its own log already holds, so re-queueing one it took in is harmless.
     */
    fun recordPermissions(
        store: BlobStore,
        captureStoreKey: ByteArray,
        transitions: List<CapturePermissions.Transition>,
        homeModes: Set<Capture.Mode>,
    ) {
        if (transitions.isEmpty()) return
        val home = transitions.filter { CapturePermissions.modesNeeding(it.permission, homeModes).isNotEmpty() }
        val id = VaultKeys.metadataBlobId(captureStoreKey)
        if (home.isEmpty() && !store.fileOf(id).exists()) return
        val keyringStore = CaptureStore.open(store, captureStoreKey)
        MetadataDb.commit(store, id, captureStoreKey) { db ->
            if (home.isNotEmpty()) {
                val logged = db.logBlobId?.let { TamperLog.scan(store, it, captureStoreKey).entries }.orEmpty()
                for (t in home) {
                    val payload = TamperLog.Permission.permissionPayload(t.permission, t.state)
                    if (logged.any { it.event == TamperLog.CAPTURE_PERMISSION && it.timestamp == t.at && it.payload.contentEquals(payload) }) continue
                    TamperLog.append(store, db, keyringStore.metadataBlobId, captureStoreKey, TamperLog.CAPTURE_PERMISSION, payload = payload, timestamp = t.at)
                }
            }
            for (e in db.vaultCaptures.toList()) {
                for (t in transitions) {
                    if (CapturePermissions.modesNeeding(t.permission, e.modes).isEmpty()) continue
                    val notice = VaultNotice(e.indexBlobId.copyOf(), t.permission, t.state, t.at)
                    if (db.vaultNotices.none { sameNotice(it, notice) }) db.vaultNotices += notice
                }
            }
            db
        }
    }

    private fun sameNotice(a: VaultNotice, b: VaultNotice) =
        a.indexBlobId.contentEquals(b.indexBlobId) && a.permission == b.permission && a.state == b.state && a.at == b.at

    private fun filename(mode: Capture.Mode, at: Long): String {
        val ext = when (mode) {
            Capture.Mode.PHOTO -> "jpg"
            Capture.Mode.AUDIO -> "aac"
            Capture.Mode.VIDEO -> "ts"
        }
        return CaptureLog.PREFIX + "$at.$ext"
    }
}

/**
 * The capture store's log as the Intruder capture screen's rows — `intruder-captures-at-home` D4.
 *
 * **Built from the log and not from a second record.** The `0x41` entries are already
 * append-only, timestamped and tamper-evident; the timeline is one row per entry, so a capture
 * that has since left keeps its line, and its `0x47` entry says where it went.
 *
 * Its own object, and pure, so the JVM suite drives it: nothing here loads libsodium.
 */
object CaptureLog {

    /** One line of the timeline. [item] is the held capture, null once it has [left]. */
    class Row(
        val takenAt: Long,
        val stage: Int,
        val flags: Int,
        val cover: Int,
        /** Off the held item's extension, or the `0x47` entry's once it has left; null if neither says. */
        val mode: Capture.Mode?,
        val item: VaultItem?,
        val left: TamperLog.Left?,
        /** A `0x48` row's change — `per-vault-intruder-capture` D8. Null on a capture's row. */
        val permission: TamperLog.Permission? = null,
        /** For a `0x48` row, the timeline's enabled modes that permission serves, in order. */
        val permissionModes: List<Capture.Mode> = emptyList(),
    ) {
        /** The capture came out blank or silent — [CaptureRecord.FLAG_BLANK]. */
        val blank: Boolean get() = permission == null && flags and CaptureRecord.FLAG_BLANK != 0
    }

    /**
     * The rows, newest first. [items] are the store's items; each entry is matched to the item
     * carrying its millisecond, each item to one entry at most.
     *
     * A `0x48` entry is a row of its own — `per-vault-intruder-capture` D8 — naming those of
     * [enabledModes], the timeline's own, that its permission serves.
     */
    fun rows(entries: List<LogEntry>, items: List<VaultItem>, enabledModes: Set<Capture.Mode> = emptySet()): List<Row> {
        val permissions = entries.filter { it.event == TamperLog.CAPTURE_PERMISSION }.mapNotNull { e ->
            TamperLog.Permission.readPermission(e.payload)?.let { p ->
                Row(e.timestamp, 0, 0, CaptureRecord.COVER_NONE, null, null, null, p, CapturePermissions.modesNeeding(p.permission, enabledModes))
            }
        }
        return (captureRows(entries, items) + permissions).sortedByDescending { it.takenAt }
    }

    private fun captureRows(entries: List<LogEntry>, items: List<VaultItem>): List<Row> {
        val captures = entries.filter { it.event == TamperLog.INTRUDER_CAPTURED }.sortedByDescending { it.timestamp }
        val lefts = entries.filter { it.event == TamperLog.INTRUDER_LEFT }
            .mapNotNull { TamperLog.Left.readLeft(it.payload) }
            .associateBy { it.takenAt }
        val held = matchItems(captures, items)
        return captures.map { e ->
            // An entry with no payload reads as the passphrase, the only stage that fired before
            // stages were recorded — `cover-fingerprint-dots` D6a.
            val p = e.payload
            val (stage, flags, cover) = if (p.size < 3) {
                Triple(CaptureRecord.STAGE_PASSPHRASE, 0, CaptureRecord.COVER_NONE)
            } else {
                Triple(p[0].toInt() and 0xFF, p[1].toInt() and 0xFF, p[2].toInt() and 0xFF)
            }
            val item = held[e]
            val left = if (item == null) lefts[e.timestamp] else null
            Row(e.timestamp, stage, flags, cover, item?.let { modeOf(it.filename) } ?: left?.mode, item, left)
        }
    }

    /** Where the row's attempt failed, in the app's words. */
    fun where(words: Words, row: Row, layout: BiometricLayout): String =
        BiometricWrap.captureWhere(words, row.stage, row.flags, row.cover, layout)

    /**
     * Each entry's item, matched on the millisecond in the name, which [CaptureIngest] puts there
     * and nothing else uses. Nearest pairs first, so two captures of one attempt — a photo and an
     * audio clip seconds apart — each find their own file, and an entry whose item has left never
     * borrows a neighbour's.
     */
    private fun matchItems(captures: List<LogEntry>, items: List<VaultItem>): Map<LogEntry, VaultItem> {
        val named = items.filter { it.filename.startsWith(PREFIX) }
        val pairs = captures.flatMap { e -> named.map { Triple(e, it, kotlin.math.abs(stampOf(it.filename) - e.timestamp)) } }
            .filter { it.third <= NAME_TOLERANCE_MS }
            .sortedBy { it.third }
        val out = HashMap<LogEntry, VaultItem>()
        val taken = HashSet<VaultItem>()
        for ((e, item, _) in pairs) {
            if (e in out || item in taken) continue
            out[e] = item
            taken += item
        }
        return out
    }

    /** The millisecond a capture's filename carries, or 0 when it carries none. */
    private fun stampOf(filename: String): Long =
        filename.removePrefix(PREFIX).substringBefore('.').toLongOrNull() ?: 0L

    /** The mode, read off the extension [CaptureIngest] gives the item. */
    fun modeOf(name: String): Capture.Mode? = when {
        name.endsWith(".jpg") -> Capture.Mode.PHOTO
        name.endsWith(".aac") -> Capture.Mode.AUDIO
        name.endsWith(".ts") -> Capture.Mode.VIDEO
        else -> null
    }

    /** What every capture's filename begins with. */
    const val PREFIX = "intruder-"

    /**
     * How far a capture's filename may sit from its entry's timestamp and still be the same
     * capture. Both carry the moment taken, so the gap is 0; the tolerance is far below the
     * interval between two captures.
     */
    private const val NAME_TOLERANCE_MS = 5_000L
}
