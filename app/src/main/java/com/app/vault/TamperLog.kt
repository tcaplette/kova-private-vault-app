package com.app.vault

import android.content.Context
import com.goterl.lazysodium.interfaces.Auth
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/** One entry read back out of the log. */
class LogEntry(
    val index: Int,
    val timestamp: Long,
    val event: Int,
    val payload: ByteArray
) {
    /** The 4 B little-endian count events carry, or 1 for an event with no payload. */
    val count: Int
        get() = if (payload.size >= 4) {
            ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        } else {
            1
        }
}

/**
 * What one forward pass over the log found — `FORMAT.md` §9.
 *
 * [firstAuthFailure] and [chainBreakAt] are separate because only the pair
 * separates tampering from the end of the log. A clean log fails to authenticate
 * from its end onward and never breaks the chain; an edited entry at *k* fails to
 * authenticate at *k*, **authenticates again after *k***, and breaks the chain
 * at *k*.
 */
class LogScan(
    val entries: List<LogEntry>,
    val appendIndex: Int,
    val firstAuthFailure: Int?,
    val chainBreakAt: Int?
) {
    val tampered: Boolean get() = chainBreakAt != null
}

/**
 * The tamper log — `FORMAT.md` §9.
 *
 * Fixed 296 B entries, each sealed under the log seal key with its own index as
 * AAD, each chained over the previous entry's MAC. Append-only: nothing here
 * rewrites or removes an entry, and the only write that touches an existing byte
 * is the growth copy, which reproduces entries verbatim.
 *
 * **The log is a blob in the pooled store**, not a file with a name. A fixed
 * per-vault filename would make the number of log files the number of vaults, and
 * one shared file cannot work at all — the end of the log is found by reading
 * until an entry fails to authenticate, and another vault's entry fails in
 * exactly that way. §2 records this.
 *
 * The log blob ID and the append index are cached here for the life of an unlock,
 * so an append does not re-read the index or rescan the log. [closed] drops them,
 * and `VaultLifecycle.lockNow` calls it.
 */
object TamperLog {

    // --- entry layout, §9 ------------------------------------------------------

    const val ENTRY_BYTES = 296
    private const val NONCE_BYTES = 24
    private const val CIPHERTEXT_BYTES = 240        // 224 B record + 16 B tag
    private const val RECORD_BYTES = 224
    private const val MAC_BYTES = Auth.BYTES        // 32
    const val PAYLOAD_MAX = 213

    /** P2-2. A vault quiet for a quarter says so on the next unlock. */
    const val STALE_DAYS = 90L

    private const val NONCE_OFFSET = 0
    private const val CIPHERTEXT_OFFSET = 24
    private const val MAC_OFFSET = 264

    private const val TS_OFFSET = 0
    private const val EVENT_OFFSET = 8
    private const val PAYLOAD_LEN_OFFSET = 9
    private const val PAYLOAD_OFFSET = 10

    /** §9: 1 MiB, 3542 entries. Grows to the next §5 bucket when full. */
    private const val START_BYTES = 1L shl 20

    private val GENESIS = "vault-log-genesis".toByteArray(Charsets.US_ASCII)

    // --- event codes, §9 -------------------------------------------------------

    const val VAULT_CREATED = 0x01
    const val UNLOCK_SUCCEEDED = 0x02
    const val UNLOCK_FAILED = 0x03
    const val IMPORT_COMPLETED = 0x04

    /** P1-13's. Defined because §9 defines it; deliberately never emitted here. */
    const val EXPORT_STARTED = 0x05

    /** P1-13's. Defined because §9 defines it; deliberately never emitted here. */
    const val EXPORT_COMPLETED = 0x06

    /**
     * `remove-originals-on-import`. Written by `MainActivity` when Android reports that
     * the user allowed it to delete gallery originals the vault holds verified copies
     * of. Payload: the number of items sent, 4 B little-endian.
     */
    const val SOURCE_DELETED = 0x07

    /** Written when the kit is shown at setup — P1-12. */
    const val RECOVERY_KIT_GENERATED = 0x08

    /** Written by the unlock tail when the open came from a mnemonic — P1-12. */
    const val RECOVERY_KIT_USED = 0x09

    const val BIOMETRIC_INVALIDATED = 0x0A
    const val CHAIN_VERIFICATION_FAILED = 0x0B

    /**
     * P2-3, and the first emitter of §9's `0x40`–`0x7F` Phase 2 range — which the
     * format reserved for exactly this, so nothing about §9 changes to admit it.
     *
     * Written **before** the first slot is destroyed, so a wipe interrupted halfway
     * still leaves the record. Deliberately absent from [digestOf]: that summary is
     * drawn on the grid the coerced user is being watched looking at, and a line
     * naming a destruction that just happened is the panic announcing itself.
     */
    const val PANIC_TRIGGERED = 0x40

    /**
     * P2-4, the second emitter of §9's `0x40`–`0x7F` Phase 2 range. Written once per
     * ingested intruder capture, at the keyring open that takes it into the capture
     * store — `intruder-captures-at-home` — never at capture time, because a failed
     * unlock holds no key to seal with. Written only to a **capture store's** log; a
     * vault's log never holds one. Timestamped with the moment taken; the payload is
     * the stage record's `stage ‖ flags ‖ cover`.
     *
     * **Absent from [digestOf]**, like [PANIC_TRIGGERED]: a count of captures tells
     * whoever compelled an unlock how many attempts they made. The owner learns of
     * captures from the home screen's badge.
     */
    const val INTRUDER_CAPTURED = 0x41

    /**
     * An intruder capture left the capture store — `intruder-captures-at-home` D6. Written
     * only to a capture store's log, so the timeline keeps the capture's line and says where
     * it went.
     *
     * Payload: the moment the capture was taken (8 bytes, little-endian) ‖ the outcome
     * ([LEFT_MOVED], [LEFT_EXPORTED], [LEFT_DELETED]) ‖ the mode (1 photo, 2 audio, 3 video,
     * 0 unknown), because the item that said it is gone ‖ for a move, the vault's name at
     * that moment as UTF-8, at most `VaultKeyring.MAX_NAME_BYTES`. The name and not a position,
     * so the line stays true of the event after the vault is renamed or removed.
     */
    const val INTRUDER_LEFT = 0x47

    const val LEFT_MOVED = 1
    const val LEFT_EXPORTED = 2
    const val LEFT_DELETED = 3

    /**
     * What a `0x47` entry records — see [INTRUDER_LEFT].
     *
     * The payload pair lives on this class's companion and not on [TamperLog] itself, so the
     * JVM suite can drive it: [TamperLog] loads libsodium when it initialises.
     */
    class Left(val takenAt: Long, val outcome: Int, val mode: Capture.Mode?, val vaultName: String?) {
        companion object {
            /** The `0x47` payload for a capture taken at [takenAt]. [vaultName] only for [LEFT_MOVED]. */
            fun leftPayload(takenAt: Long, outcome: Int, mode: Capture.Mode?, vaultName: String? = null): ByteArray {
                val name = vaultName?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                val out = ByteArray(10 + name.size)
                ByteBuffer.wrap(out, 0, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(takenAt)
                out[8] = outcome.toByte()
                out[9] = (mode?.let { it.ordinal + 1 } ?: 0).toByte()
                name.copyInto(out, 10)
                return out
            }

            /** A `0x47` payload read back, or null for one too short to be one. */
            fun readLeft(payload: ByteArray): Left? {
                if (payload.size < 10) return null
                val takenAt = ByteBuffer.wrap(payload, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
                val mode = Capture.Mode.entries.getOrNull((payload[9].toInt() and 0xFF) - 1)
                val name = if (payload.size > 10) String(payload, 10, payload.size - 10, Charsets.UTF_8) else null
                return Left(takenAt, payload[8].toInt() and 0xFF, mode, name)
            }
        }
    }

    /**
     * The camera or microphone permission was noticed lost or restored —
     * `per-vault-intruder-capture` D8. Written only to a capture store's log: the keyring's,
     * only when a home mode needs the permission; a vault's, from its queued notices at its
     * next opening. Timestamped with the moment the app **noticed**, which is all it can know.
     *
     * Payload: the permission ([PERMISSION_CAMERA], [PERMISSION_MICROPHONE]) ‖ the state
     * ([PERMISSION_LOST], [PERMISSION_RESTORED]). Two bytes, so the log's `count` reads 1.
     */
    const val CAPTURE_PERMISSION = 0x48

    const val PERMISSION_CAMERA = 1
    const val PERMISSION_MICROPHONE = 2
    const val PERMISSION_LOST = 0
    const val PERMISSION_RESTORED = 1

    /**
     * What a `0x48` entry records — see [CAPTURE_PERMISSION]. On a companion for the same reason
     * as [Left]'s: the JVM suite drives it without libsodium.
     */
    class Permission(val permission: Int, val state: Int) {
        companion object {
            fun permissionPayload(permission: Int, state: Int): ByteArray =
                byteArrayOf(permission.toByte(), state.toByte())

            /** A `0x48` payload read back, or null for one that is not one. */
            fun readPermission(payload: ByteArray): Permission? {
                if (payload.size != 2) return null
                val permission = payload[0].toInt() and 0xFF
                val state = payload[1].toInt() and 0xFF
                if (permission !in PERMISSION_CAMERA..PERMISSION_MICROPHONE) return null
                if (state !in PERMISSION_LOST..PERMISSION_RESTORED) return null
                return Permission(permission, state)
            }
        }
    }

    /**
     * P2-5's five device events, filling the low end of §9's reserved Phase 2 range.
     * Each is detected while the vault is locked and held in [pendingDeviceEvents]
     * until a vault opens (or, for a data clear that left none, is created), then
     * flushed like a failed-unlock count. All five are shown in the digest — the
     * owner is who a change in their device exists to inform.
     */
    const val DEV_OPTIONS_ENABLED = 0x42
    const val ROOT_STATUS_CHANGED = 0x43
    const val DATA_CLEAR_DETECTED = 0x44
    const val NEW_DEVICE_RESTORE = 0x45
    const val CLOCK_ROLLBACK = 0x46

    private val DEVICE_EVENTS = 0x42..0x46

    private val sodium = Sodium.lazy
    private val rng = SecureRandom()

    private val EMPTY = ByteArray(0)

    // --- session state ---------------------------------------------------------

    @Volatile
    private var logBlobId: ByteArray? = null

    @Volatile
    private var appendIndex: Int = 0

    /**
     * Failed unlocks, held in the process because there is no key to write them
     * with — the seal key derives from the VMK and a failed unlock produces none.
     * Flushed as one entry when a vault next opens. §10 item 15 records the cost:
     * force-stop the app after each attempt and nothing is ever recorded.
     */
    @Volatile
    private var failedUnlocks: Int = 0

    @Volatile
    private var firstFailureAt: Long = 0

    /**
     * Detected in `MainActivity.onCreate`, which runs while the vault is locked —
     * so, like a failed unlock, it has no key to be written with and waits for the
     * next successful unlock.
     */
    @Volatile
    private var biometricInvalidated: Boolean = false

    /**
     * Set just before a recovered vault is opened — P1-12. Pending for the same
     * reason as the two above: the seal key derives from the VMK, and the moment
     * worth recording is the one before the vault is open.
     */
    @Volatile
    private var recovered: Boolean = false

    /**
     * P2-5. Device conditions detected at launch while the vault is locked — no key
     * yet, so they wait like a failed unlock. Flushed at the next [opened] and, for
     * the data-clear case that left no vault to open, at the next [created]. A `Set`,
     * because a launch that detects the same condition twice records it once.
     */
    private val pendingDeviceEvents = java.util.Collections.synchronizedSet(linkedSetOf<Int>())

    /**
     * What the last unlock found, for the grid to render. Held here rather than
     * threaded through `Outcome` because the biometric path and the passphrase
     * path share only `VaultLifecycle.open`, and `Outcome` must keep saying
     * nothing about which vault opened.
     */
    @Volatile
    var digest: List<String> = emptyList()
        private set

    /**
     * `app-languages`. The digest is written at unlock, deep in the lifecycle where no
     * screen is to hand, so the application's text is attached once at start — on 30–32
     * its resources carry the chosen language too (`AppLanguage.apply`).
     */
    private lateinit var appContext: Context

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    // --- lifecycle -------------------------------------------------------------

    /**
     * Allocates a vault's log: one CSPRNG blob at the 1 MiB bucket. No header and
     * no magic bytes — a log blob must be indistinguishable from chaff and from
     * another vault's content.
     */
    fun create(store: BlobStore): ByteArray =
        store.writeChaff(1, listOf(START_BYTES)).single()

    /**
     * A new vault's first entry. Not an unlock — nothing was opened — so this
     * writes `0x01` and nothing else, and primes the cache at index 0.
     */
    @Synchronized
    fun created(
        store: BlobStore,
        db: MetadataDb,
        metadataBlobId: ByteArray,
        vmk: ByteArray,
        /**
         * P2-2. The vault's claimed birthday, for a decoy. Entry 0 is the only
         * entry that can carry it — the log is append-only, so a history written
         * later cannot fix an origin written now, and a log whose first entry is
         * newer than its second is proof that a tool wrote it.
         */
        timestamp: Long = System.currentTimeMillis(),
    ) {
        logBlobId = db.logBlobId
        appendIndex = 0
        digest = emptyList()
        append(store, db, metadataBlobId, vmk, VAULT_CREATED, timestamp = timestamp)
        // P2-5. A data clear leaves no vault to open, so its event waits for the
        // first vault created afterward and lands here — the only log that exists.
        flushDeviceEvents(store, db, metadataBlobId, vmk)
    }

    /**
     * P2-5. Appends each pending device event, once, and clears the set. Called from
     * [opened] before the unlock-succeeded entry, and from [created] for the
     * data-clear case. A snapshot under the set's lock, so a concurrent record does
     * not lose an event or double-append one.
     */
    @Synchronized
    private fun flushDeviceEvents(store: BlobStore, db: MetadataDb, metadataBlobId: ByteArray, vmk: ByteArray) {
        val events = synchronized(pendingDeviceEvents) {
            pendingDeviceEvents.toList().also { pendingDeviceEvents.clear() }
        }
        events.forEach { append(store, db, metadataBlobId, vmk, it) }
    }

    /** Records a failed unlock. No key exists at this moment; see [flushFailures]. */
    @Synchronized
    fun recordFailedUnlock() {
        if (failedUnlocks == 0) firstFailureAt = System.currentTimeMillis()
        failedUnlocks++
    }

    @Synchronized
    fun pendingFailures(): Int = failedUnlocks

    /** Records an enrolment change that destroyed the biometric wrap. No key yet. */
    @Synchronized
    fun recordBiometricInvalidated() {
        biometricInvalidated = true
    }

    /** Records that this open came from a recovery kit — P1-12. */
    @Synchronized
    fun recordRecovery() {
        recovered = true
    }

    /**
     * P2-5. Notes a device condition found while locked. Range-checked here so a bad
     * caller fails now rather than at the flush, where the vault is open.
     */
    fun recordDeviceEvent(event: Int) {
        require(event in DEVICE_EVENTS) { "event $event is not a device event" }
        pendingDeviceEvents.add(event)
    }

    /** P2-5, test-only: what is waiting to be flushed. */
    fun pendingDeviceEvents(): Set<Int> = pendingDeviceEvents.toSet()

    /** P2-5, test-only: drop anything pending so one test does not leak into the next. */
    fun clearPendingDeviceEventsForTest() = pendingDeviceEvents.clear()

    /**
     * The unlock tail: scan, flush pending failures, record the unlock, and hand
     * back what happened since the previous one.
     *
     * Called from `VaultLifecycle.open`, which is the single path both the
     * passphrase and the biometric unlock go through — neither can open a vault
     * without being recorded.
     */
    @Synchronized
    fun opened(
        store: BlobStore,
        db: MetadataDb,
        metadataBlobId: ByteArray,
        vmk: ByteArray,
    ): List<String> {
        val id = db.logBlobId ?: return emptyList()
        logBlobId = id

        val scan = scan(store, id, vmk)
        appendIndex = scan.appendIndex

        // The pending counts go into the digest as well as into the log. They
        // describe the interval that ended with *this* unlock, and they are still
        // only in memory at this point — reading them back off the scan would
        // report them one unlock late.
        val deviceEvents = pendingDeviceEvents.toSet()
        digest = digestOf(scan, failedUnlocks, biometricInvalidated, deviceEvents = deviceEvents)

        // Once, not once per unlock. The break stays visible because every later
        // scan keeps finding it, not because the log keeps restating it.
        val breakAt = scan.chainBreakAt
        val alreadyReported = scan.entries.any {
            it.event == CHAIN_VERIFICATION_FAILED && it.count == breakAt
        }
        if (breakAt != null && !alreadyReported) {
            append(store, db, metadataBlobId, vmk, CHAIN_VERIFICATION_FAILED, countPayload(breakAt))
        }
        if (biometricInvalidated) {
            append(store, db, metadataBlobId, vmk, BIOMETRIC_INVALIDATED)
            biometricInvalidated = false
        }
        if (recovered) {
            append(store, db, metadataBlobId, vmk, RECOVERY_KIT_USED)
            recovered = false
        }
        // Failures before the success they ended with.
        flushFailures(store, db, metadataBlobId, vmk)
        // P2-5. Before the unlock-succeeded entry, so a later scan reads them as
        // belonging to the interval that just closed and does not report them again.
        flushDeviceEvents(store, db, metadataBlobId, vmk)
        append(store, db, metadataBlobId, vmk, UNLOCK_SUCCEEDED)

        return digest
    }

    /** At most one entry per burst, so a held-down attacker cannot fill the log. */
    @Synchronized
    private fun flushFailures(store: BlobStore, db: MetadataDb, metadataBlobId: ByteArray, vmk: ByteArray) {
        if (failedUnlocks == 0) return

        val payload = ByteArray(12)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(failedUnlocks)
            .putLong(firstFailureAt)

        append(store, db, metadataBlobId, vmk, UNLOCK_FAILED, payload)
        failedUnlocks = 0
        firstFailureAt = 0
    }

    /** Drops the cached log ID and append index. `VaultLifecycle.lockNow` calls this. */
    @Synchronized
    fun closed() {
        logBlobId = null
        appendIndex = 0
        digest = emptyList()
    }

    // --- append ----------------------------------------------------------------

    /**
     * Appends one event using the session's open vault. A locked vault has no key,
     * so this is a no-op — that is the correct behaviour, not a swallowed error.
     *
     * ponytail: process-wide lock on the log; per-vault locking matters only when
     * P2-1 opens two vaults at once.
     */
    /**
     * **Deliberately not `@Synchronized` — P2-9.** The overload it delegates to is,
     * and adding it here inverts a lock order into a deadlock:
     *
     * - this path would take TamperLog's monitor, then `MetadataDb`'s commit lock
     * - `ImportWorker` takes the commit lock, then calls the db overload, which
     *   takes TamperLog's monitor
     *
     * ABBA, and it hangs both threads. Every caller now reaches TamperLog's monitor
     * *through* the commit lock, in one order, so there is nothing to invert.
     */
    fun append(
        store: BlobStore,
        event: Int,
        payload: ByteArray = EMPTY,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val vmk = VaultSession.vmk() ?: return
        val metadataBlobId = VaultSession.metadataBlobId() ?: return
        if (logBlobId == null) return

        // P2-9. Through the guard: this overload loads and saves the index, so
        // two appends — or an append and an import commit — raced the same way the
        // import path did. Reentrant, so an append nested inside a caller's commit
        // reuses that commit's index rather than deadlocking or reloading.
        MetadataDb.commit(store, metadataBlobId, vmk) { db ->
            append(store, db, metadataBlobId, vmk, event, payload, timestamp)
        }
    }

    /** Same, for the callers that already hold the index and must not reload it. */
    @Synchronized
    fun append(
        store: BlobStore,
        db: MetadataDb,
        metadataBlobId: ByteArray,
        vmk: ByteArray,
        event: Int,
        payload: ByteArray = EMPTY,
        /**
         * P2-2, and the wall clock for everyone else. The default is the only
         * value any ordinary path uses; the decoy flow is the one caller that
         * supplies one, for the vault-created entry of the vault it makes and the
         * unlock history it writes after it.
         */
        timestamp: Long = System.currentTimeMillis(),
    ) {
        require(
            event in 0x01..0x3F || event == PANIC_TRIGGERED || event == INTRUDER_CAPTURED ||
                event == INTRUDER_LEFT || event == CAPTURE_PERMISSION || event in DEVICE_EVENTS
        ) {
            "event $event is outside the Phase 1 range and is not one of the Phase 2 codes that exist"
        }
        require(payload.size <= PAYLOAD_MAX) { "payload must be at most $PAYLOAD_MAX bytes" }

        var id = db.logBlobId ?: return
        var file = store.fileOf(id)
        if (!file.exists()) return

        // A cold caller — `ImportWorker` after the cache was dropped, a test
        // appending without unlocking — must not write at a stale index, because
        // index 0 is a real entry and appending over it destroys it.
        val cached = logBlobId
        if (cached == null || !cached.contentEquals(id)) {
            appendIndex = scan(store, id, vmk).appendIndex
            logBlobId = id
        }

        if (appendIndex >= (file.length() / ENTRY_BYTES).toInt()) {
            id = grow(store, id)
            db.logBlobId = id
            db.save(store, metadataBlobId, vmk)
            logBlobId = id
            file = store.fileOf(id)
        }

        val sealKey = VaultKeys.logSealKey(vmk)
        val macKey = VaultKeys.logMacKey(vmk)
        val record = ByteArray(RECORD_BYTES).also { rng.nextBytes(it) }
        try {
            ByteBuffer.wrap(record, TS_OFFSET, 8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(timestamp)
            record[EVENT_OFFSET] = event.toByte()
            record[PAYLOAD_LEN_OFFSET] = payload.size.toByte()
            payload.copyInto(record, PAYLOAD_OFFSET)

            val index = appendIndex
            val entry = ByteArray(ENTRY_BYTES)
            val nonce = ByteArray(NONCE_BYTES).also { rng.nextBytes(it) }
            nonce.copyInto(entry, NONCE_OFFSET)

            val ciphertext = ByteArray(CIPHERTEXT_BYTES)
            val sealed = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                ciphertext, longArrayOf(0),
                record, RECORD_BYTES.toLong(),
                aad(index), 8L,
                null, nonce, sealKey
            )
            check(sealed) { "log entry seal failed" }
            ciphertext.copyInto(entry, CIPHERTEXT_OFFSET)

            val prevMac = previousMac(file, index, macKey)
            chain(prevMac, nonce, ciphertext, macKey).copyInto(entry, MAC_OFFSET)

            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(index.toLong() * ENTRY_BYTES)
                raf.write(entry)
                raf.fd.sync()
            }
            appendIndex = index + 1
        } finally {
            record.fill(0)
            sealKey.fill(0)
            macKey.fill(0)
        }
    }

    /**
     * Copies the log to the next bucket. Nothing is re-sealed: an entry's AEAD is
     * bound to its index and its chain MAC to its predecessor, and a straight copy
     * preserves both.
     */
    private fun grow(store: BlobStore, oldId: ByteArray): ByteArray {
        val old = store.fileOf(oldId)
        val next = BlobStore.bucketFor(old.length() + 1)
        val newId = store.writeChaff(1, listOf(next)).single()

        RandomAccessFile(store.fileOf(newId), "rw").use { out ->
            FileInputStream(old).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
            out.fd.sync()
        }
        store.delete(oldId)
        return newId
    }

    // --- scan ------------------------------------------------------------------

    /**
     * One forward pass over the whole blob — §9's two checks.
     *
     * The whole capacity is scanned, not just up to the first authentication
     * failure: a middle entry that was zeroed authenticates nowhere, and the only
     * thing that distinguishes it from the end of the log is that entries *after*
     * it still authenticate.
     *
     * ponytail: full-capacity scan, ~3542 AEAD opens at the starting size and
     * milliseconds against a ~460 ms Argon2id derivation on the same unlock. If a
     * 16 MiB log ever costs real time, stop at a run of failures long enough to
     * bound a plausible deletion.
     */
    fun scan(store: BlobStore, blobId: ByteArray, vmk: ByteArray): LogScan {
        val file = store.fileOf(blobId)
        if (!file.exists()) return LogScan(emptyList(), 0, null, null)

        val capacity = (file.length() / ENTRY_BYTES).toInt()
        val sealKey = VaultKeys.logSealKey(vmk)
        val macKey = VaultKeys.logMacKey(vmk)

        val entries = ArrayList<LogEntry>()
        var firstAuthFailure: Int? = null
        var lastAuth = -1
        var firstChainMismatch: Int? = null

        try {
            var expected = chainGenesis(macKey)
            val entry = ByteArray(ENTRY_BYTES)
            val record = ByteArray(RECORD_BYTES)

            BufferedInputStream(FileInputStream(file), 1 shl 16).use { input ->
                for (index in 0 until capacity) {
                    if (!readFully(input, entry)) break

                    val nonce = entry.copyOfRange(NONCE_OFFSET, NONCE_OFFSET + NONCE_BYTES)
                    val ciphertext =
                        entry.copyOfRange(CIPHERTEXT_OFFSET, CIPHERTEXT_OFFSET + CIPHERTEXT_BYTES)
                    val storedMac = entry.copyOfRange(MAC_OFFSET, MAC_OFFSET + MAC_BYTES)

                    // Recomputed from the bytes on disk, never from the plaintext,
                    // so a break is reported even where an entry cannot be opened.
                    if (firstChainMismatch == null &&
                        !chain(expected, nonce, ciphertext, macKey).contentEquals(storedMac)
                    ) {
                        firstChainMismatch = index
                    }
                    expected = storedMac

                    val opened = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
                        record, longArrayOf(0),
                        null,
                        ciphertext, CIPHERTEXT_BYTES.toLong(),
                        aad(index), 8L,
                        nonce, sealKey
                    )

                    if (opened) {
                        lastAuth = index
                        entries += decode(index, record)
                    } else if (firstAuthFailure == null) {
                        firstAuthFailure = index
                    }
                }
            }
            record.fill(0)
        } finally {
            sealKey.fill(0)
            macKey.fill(0)
        }

        // Past the end every entry is CSPRNG, so a mismatch there is the pre-fill,
        // not a break. Only a mismatch at or before the last authenticating entry
        // is tampering.
        val breakAt = firstChainMismatch?.takeIf { it <= lastAuth }

        return LogScan(entries, lastAuth + 1, firstAuthFailure, breakAt)
    }

    // --- digest ----------------------------------------------------------------

    /**
     * What happened since the previous successful unlock, in sentences.
     *
     * Empty when nothing happened and the chain is intact — an empty state is not
     * a line of text saying nothing happened (`DESIGN.md` §6.5).
     */
    fun digestOf(
        scan: LogScan,
        pendingFailures: Int = 0,
        pendingBiometric: Boolean = false,
        now: Long = System.currentTimeMillis(),
        /** P2-5. Device events flushed at this unlock — see [opened]. */
        deviceEvents: Set<Int> = emptySet(),
        words: Words = appContext.words(),
    ): List<String> {
        val lines = mutableListOf<String>()

        if (scan.tampered) {
            lines += words.get(R.string.digest_altered, scan.chainBreakAt ?: 0)
        }

        val lastUnlock = scan.entries.indexOfLast { it.event == UNLOCK_SUCCEEDED }

        // P2-2's quarterly nudge, and deliberately not a decoy feature. A line that
        // appeared only in decoys would tell whoever is holding the phone which
        // vault they are looking at, and the other vault cannot nudge on its behalf
        // — P2-1 established that no vault can learn another exists. So: one rule,
        // every vault, about the vault being opened and nothing else. The owner
        // reads it as "use this one or it stops being plausible"; anyone else reads
        // a date they could have taken off the log anyway.
        //
        // Read from the scan the digest already performs. No timer, no scheduled
        // work, no notification: a notification about a vault is a notification
        // about a vault, on a lock screen, in front of whoever is holding it.
        if (lastUnlock >= 0) {
            val previous = scan.entries[lastUnlock].timestamp
            if (now - previous > STALE_DAYS * 86_400_000L) {
                lines += words.get(
                    R.string.digest_not_opened_since,
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(previous)),
                )
            }
        }
        val since = if (lastUnlock >= 0) scan.entries.drop(lastUnlock + 1) else scan.entries

        var failed = pendingFailures
        var imported = 0
        var deleted = 0
        var biometric = pendingBiometric
        var recoveries = 0

        // PANIC_TRIGGERED is deliberately not a case here. See its declaration:
        // the digest is read over the shoulder of whoever compelled the unlock.
        since.forEach { e ->
            when (e.event) {
                UNLOCK_FAILED -> failed += e.count
                IMPORT_COMPLETED -> imported += e.count
                SOURCE_DELETED -> deleted += e.count
                BIOMETRIC_INVALIDATED -> biometric = true
                RECOVERY_KIT_USED -> recoveries++
            }
        }
        if (recoveries > 0) {
            lines += words.plural(R.plurals.digest_recoveries, recoveries, recoveries)
        }

        // `intruder-capture-record`. **No capture line, and PANIC_TRIGGERED's reason
        // is now this one's too.** The digest is read over the shoulder of whoever is
        // holding the phone, and a count of captures tells a person who compelled an
        // unlock exactly how many attempts they made. P2-4 made this the exception to
        // the rule the panic entry established, and nothing revisited it until the
        // count was noticed being read by the wrong person.
        //
        // The owner is still told. `intruder-capture` badges the home screen's drawer
        // control and its Intruder capture row while captures are unseen, which says that something is there without
        // saying how much — and survives past the unlock that ingested, which this
        // line never did.

        if (failed > 0) lines += words.plural(R.plurals.digest_failed, failed, failed)
        // Items, not imports: one entry is one item, so a separate import count
        // would be the same number twice.
        if (imported > 0) lines += words.plural(R.plurals.digest_imported, imported, imported)
        if (deleted > 0) lines += words.plural(R.plurals.digest_deleted, deleted, deleted)
        if (biometric) lines += words.get(R.string.digest_biometric)

        // P2-5. One sentence per device event, in a fixed order so the digest reads
        // the same way each time. Counted from the flushed set, not the scan, so
        // each is reported exactly once.
        if (DEV_OPTIONS_ENABLED in deviceEvents) {
            lines += words.get(R.string.digest_dev_options)
        }
        if (ROOT_STATUS_CHANGED in deviceEvents) {
            lines += words.get(R.string.digest_root)
        }
        if (DATA_CLEAR_DETECTED in deviceEvents) {
            lines += words.get(R.string.digest_data_cleared)
        }
        if (NEW_DEVICE_RESTORE in deviceEvents) {
            lines += words.get(R.string.digest_new_device)
        }
        if (CLOCK_ROLLBACK in deviceEvents) {
            lines += words.get(R.string.digest_clock)
        }

        return lines
    }

    // --- internals -------------------------------------------------------------

    /** §9: the entry index as an 8 B little-endian `u64`. */
    private fun aad(index: Int) = ByteArray(8).also {
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(index.toLong())
    }

    private fun chainGenesis(macKey: ByteArray): ByteArray {
        val mac = ByteArray(MAC_BYTES)
        val ok = sodium.cryptoAuth(mac, GENESIS, GENESIS.size.toLong(), macKey)
        check(ok) { "log genesis MAC failed" }
        return mac
    }

    /** `MAC_i = crypto_auth(logMacKey, MAC_{i-1} ‖ nonce_i ‖ ciphertext_i)`. */
    private fun chain(
        prevMac: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        macKey: ByteArray
    ): ByteArray {
        val input = ByteArray(MAC_BYTES + NONCE_BYTES + CIPHERTEXT_BYTES)
        prevMac.copyInto(input, 0)
        nonce.copyInto(input, MAC_BYTES)
        ciphertext.copyInto(input, MAC_BYTES + NONCE_BYTES)

        val mac = ByteArray(MAC_BYTES)
        val ok = sodium.cryptoAuth(mac, input, input.size.toLong(), macKey)
        check(ok) { "log chain MAC failed" }
        return mac
    }

    /**
     * The MAC an append chains over: the **stored** MAC of the previous entry, so
     * an append after a detected break continues from what is on disk rather than
     * silently reasserting a history that is gone.
     */
    private fun previousMac(file: File, index: Int, macKey: ByteArray): ByteArray {
        if (index == 0) return chainGenesis(macKey)

        val mac = ByteArray(MAC_BYTES)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek((index - 1).toLong() * ENTRY_BYTES + MAC_OFFSET)
            raf.readFully(mac)
        }
        return mac
    }

    private fun decode(index: Int, record: ByteArray): LogEntry {
        val timestamp = ByteBuffer.wrap(record, TS_OFFSET, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val event = record[EVENT_OFFSET].toInt() and 0xFF
        val length = (record[PAYLOAD_LEN_OFFSET].toInt() and 0xFF).coerceAtMost(PAYLOAD_MAX)
        return LogEntry(
            index,
            timestamp,
            event,
            record.copyOfRange(PAYLOAD_OFFSET, PAYLOAD_OFFSET + length)
        )
    }

    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var read = 0
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    /** The 4 B little-endian count the counting events carry. */
    fun countPayload(count: Int) = ByteArray(4).also {
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(count)
    }
}
