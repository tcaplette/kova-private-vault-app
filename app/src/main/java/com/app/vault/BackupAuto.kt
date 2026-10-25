package com.app.vault

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Backup without a button — `backup-to-cloud-apps` D10, D11.
 *
 * **Every change starts one.** Every change to a vault — an import, a capture, a note, an
 * edit, a delete, a rename — saves its index, so [MetadataDb.onSaved] is the one place that
 * sees them all. A burst of changes becomes one run: a folder waits [FOLDER_QUIET_MS] of
 * quiet, a cloud file (which is rewritten whole) [FILE_QUIET_MS].
 *
 * **Locking hands the last one over.** A change made just before a lock would otherwise
 * wait for the next time the vault is opened. So [beforeLock], which runs inside the lock
 * while the vault's key still exists, prepares that run and hands it to the worker with
 * nothing the worker needs a key for: the folder's names, or a backup file whose table is
 * already sealed. The key is gone when the lock finishes; the upload carries on.
 */
object BackupAuto {

    private const val TAG = "VaultBackupAuto"

    /** A folder copies only what changed, so it can follow the person closely. */
    const val FOLDER_QUIET_MS = 15_000L

    /** A cloud file is the whole vault each time, so it waits for the person to pause. */
    const val FILE_QUIET_MS = 5 * 60_000L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    /** Whether a change is waiting for its run. [beforeLock] hands it over rather than dropping it. */
    @Volatile
    var pending = false
        private set

    private val fire = Runnable {
        pending = false
        appContext?.let { MirrorWorker.enqueueAfterChange(it) }
    }

    /** Wires the hooks. Called once, from `VaultApplication`. */
    fun attach(context: Context) {
        appContext = context.applicationContext
        MetadataDb.onSaved = { db -> changed(db) }
        VaultSession.beforeClose = { beforeLock() }
        // Anything a killed process left behind is a copy of ciphertext nobody needs.
        BackupFile.dropSnapshot(File(context.filesDir, SNAPSHOTS))
    }

    /** An index was saved. Starts the quiet period for a vault that has somewhere to go. */
    private fun changed(db: MetadataDb) {
        val destination = CloudMirror.destination(db) ?: return
        val quiet = if (CloudMirror.isFile(destination)) FILE_QUIET_MS else FOLDER_QUIET_MS
        pending = true
        main.removeCallbacks(fire)
        main.postDelayed(fire, quiet)
    }

    /**
     * As the vault's key is about to go — a lock, leaving for the list, switching vaults —
     * design D11. Hands the waiting run to the worker in a form that needs no key, or does
     * nothing when no change is waiting. The keyring's key is still present: the keyring's
     * lock leaves the open vault before it clears its own.
     */
    private fun beforeLock() {
        if (!pending) return
        val context = appContext ?: return
        val vmk = VaultSession.vmk() ?: return
        val indexId = VaultSession.metadataBlobId() ?: return
        main.removeCallbacks(fire)
        pending = false
        runCatching {
            val blobsDir = File(context.filesDir, "blobs")
            val store = BlobStore(blobsDir, File(context.filesDir, "incoming"))
            val db = MetadataDb.load(store, indexId, vmk)
            val destination = CloudMirror.destination(db) ?: return
            val keyringId = VaultKeyringSession.keyringBlobId()
            // `per-vault-intruder-capture`. The vault's own capture store travels with it.
            val captures = CloudMirror.captureStoreOf(store, vmk)
            val names = CloudMirror.vaultNames(db, indexId, keyringId, captures)
            val id = UUID.randomUUID().toString()
            val job = if (CloudMirror.isFile(destination)) {
                // An unlock's chaff rotation saves the index too. The whole vault is not
                // uploaded for that: only when something the person would call a change is
                // newer than the last completed write. The provider is not asked — this runs
                // inside a lock — so the file's size is taken as it was written.
                val digest = BackupFile.contentDigest(db)
                val local = CloudMirror.localEntries(blobsDir).filterKeys { it in names }
                val status = BackupFile.status(local, BackupFile.readRecord(store, vmk), -1L, hex(indexId), digest)
                if (status.pending == 0) return
                val dir = snapshotDir(context, id)
                val files = BackupFile.snapshot(blobsDir, names, CloudMirror.mutableNames(db, indexId, keyringId, captures), dir)
                val key = VaultKeyringSession.backupKey() ?: return
                Job.File(destination, BackupFile.prepare(files, key), digest, hex(indexId), dir)
            } else {
                Job.Folder(destination, names)
            }
            jobs[id] = job
            MirrorWorker.enqueueJob(context, id)
        }.onFailure { Log.w(TAG, "the run at lock could not be prepared", it) }
    }

    // --- handed-over runs -----------------------------------------------------------

    /** A run prepared at lock. Nothing in either holds a vault key. */
    sealed class Job {
        class Folder(val tree: android.net.Uri, val names: Set<String>) : Job()

        class File(
            val uri: android.net.Uri,
            val prepared: BackupFile.Prepared,
            val indexDigest: ByteArray,
            /** Which vault's record to write when it next opens. Hex, in memory only. */
            val vault: String,
            val snapshotDir: java.io.File,
        ) : Job()
    }

    private val jobs = ConcurrentHashMap<String, Job>()

    fun take(id: String): Job? = jobs.remove(id)

    /**
     * A file written after its vault locked: the record needs the vault's key, so it waits
     * here, in memory, for that vault to open again ([applyPendingRecord]). A process that dies
     * first loses it, and the next status reads the backup as behind and rewrites it — the
     * cost of never keeping a key past a lock.
     */
    private val pendingRecords = ConcurrentHashMap<String, BackupFile.Record>()

    fun recordLater(vault: String, record: BackupFile.Record) {
        pendingRecords[vault] = record
    }

    /** Writes a record held for [indexId]'s vault, now that its key is back. */
    fun applyPendingRecord(store: BlobStore, indexId: ByteArray, vmk: ByteArray) {
        val record = pendingRecords.remove(hex(indexId)) ?: return
        runCatching { BackupFile.writeRecord(store, vmk, record) }
            .onFailure { Log.w(TAG, "a held backup record could not be written", it) }
    }

    private const val SNAPSHOTS = "backup-snapshot"

    fun snapshotDir(context: Context, id: String) = File(File(context.filesDir, SNAPSHOTS), id)
}
