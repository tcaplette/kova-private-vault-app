package com.app.vault

import android.net.Uri
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a scheduled job is actually working on — held in memory, never written.
 *
 * **Why this is not durable.** WorkManager persists a request's input `Data` and
 * its tags to `no_backup/androidx.work.workdb`, and Gate 1 read all of it back
 * with `adb shell run-as`: an import's source URI (which on the `pickDocuments`
 * path carries the **filename**), its destination blob ID, and the metadata index
 * blob ID. Three consequences, all bad. A filename is recoverable. A blob ID
 * written down beside the import that created it is provably real content — the
 * exact ambiguity `FORMAT.md` §6's chaff exists to create. And the index blob ID
 * is §10(8)'s leak handed over in a single seizure instead of costing an attacker
 * repeated snapshots.
 *
 * Export and archive carried the same shapes: the index ID again, the SAF tree URI
 * (a folder the user named), and the archive's destination filename.
 *
 * **Nothing is lost by not persisting any of it.** P1-8.5 measured that a picker
 * URI cannot be reopened after process death — `SecurityException` — which is *why*
 * P1-8.6 keeps the process alive with a foreground service. Work that loses its
 * process is already lost, so the durability WorkManager offers here could never be
 * used. All it did was leave a record of what was imported and where it went.
 *
 * [ArchivePassphrase] reached this conclusion first, for the passphrase alone. This
 * is the same argument applied to everything else that identifies something.
 *
 * Keyed by the work request's own id, which WorkManager hands the worker, so
 * nothing has to be persisted to find the entry again.
 */
object WorkJobs {

    /**
     * [blobIds] is **mutable and grows as the import runs** — an item larger than
     * `BlobStore.SPLIT_PLAINTEXT` spans several blobs, and each is added here
     * before it is written. That serves two purposes: a retry in this process
     * resumes from the sealed ones, and the unlock sweep can tell the in-flight
     * partial from an orphan.
     *
     * Copy-on-write because those two run on different threads: the worker appends
     * while an unlock sweep reads. Appends are one per 16 MiB blob and reads one per
     * unlock, which is the case copy-on-write is for (`concurrent-import-failure` D3).
     */
    class ImportJob(
        val uri: Uri,
        val blobIds: CopyOnWriteArrayList<ByteArray>,
        val indexId: ByteArray,
        /**
         * P2-2. Set only by the decoy flow. `assignedCreatedAt` is used **only if
         * the source has no capture date of its own** — the pipeline prefers the
         * source, so a real date can never be replaced by a mistake here.
         *
         * In memory beside the URI and the blob ID, for the reason this whole
         * class exists: WorkManager writes its input `Data` to disk, and a date
         * written down next to the item it belongs to is one more thing a seizure
         * recovers.
         */
        val assignedCreatedAt: Long? = null,
        val assignedImportedAt: Long? = null,
        /**
         * The folder this import was started in, or null for the home screen —
         * P2-13, `folder-system`.
         *
         * In memory for this class's reason, and the sharpest case of it yet. An
         * `itemId` in `work.db` is a blob ID — provably real content, which is the
         * ambiguity `FORMAT.md` §6's chaff exists to create. A **folder** is a
         * sentence the user wrote about what the items *are*, and a reader of
         * `no_backup/androidx.work.workdb` learns it without opening one blob.
         *
         * The identity is held to the same rule as the name it replaced: sixteen
         * bytes beside a count still says this vault has folders and how full they
         * are, which is a fact about its shape.
         *
         * `AlbumScanTest` fails the build for one reaching input `Data`.
         */
        val folderId: ByteArray? = null,
    )
    /**
     * [selection] is P2-11's scoped export: the `itemId`s to export, or null for
     * the whole vault.
     *
     * It is here rather than in the request's input `Data` for this class's whole
     * reason, and more sharply than the index ID beside it. An `itemId` **is a
     * blob ID in hex** — the first blob of the item. A list of them written to
     * `no_backup/androidx.work.workdb` is a list of blobs that are provably real
     * content, one row per selected item, which is exactly the ambiguity
     * `FORMAT.md` §6's chaff exists to create.
     */
    class ExportJob(
        val indexId: ByteArray,
        val tree: Uri?,
        /**
         * `exif-strip-choice`. What the strip removes, or null for an export that preserves.
         * Captured when the export starts, so a change on the Photo details sheet during a long
         * export cannot split one export across two definitions of stripped.
         */
        val strip: Set<PhotoDetail>?,
        val selection: Set<String>? = null,
        /**
         * `intruder-captures-at-home` D9. The source is the keyring's capture store, whose key
         * the worker derives from the open keyring rather than reading the open vault's.
         */
        val captureStore: Boolean = false,
        /**
         * `per-vault-intruder-capture` D6. The source is the open vault's capture store, keyed from
         * the session's VMK.
         */
        val vaultCaptureStore: Boolean = false,
    )
    class ArchiveJob(val target: Uri)

    private val jobs = ConcurrentHashMap<UUID, Any>()

    fun register(id: UUID, job: Any) {
        jobs[id] = job
    }

    inline fun <reified T> get(id: UUID): T? = raw(id) as? T

    fun raw(id: UUID): Any? = jobs[id]

    /**
     * Called when work reaches a final state. **Not on a retry** — a cancelled
     * import stays resumable, and its entry has to survive to be resumed.
     */
    fun clear(id: UUID) {
        jobs.remove(id)
    }

    /**
     * Blob IDs, hex, that a live import claims. `MainActivity.sweepPartials` reads this.
     *
     * Each job's list is read through one snapshot, so a worker claiming another blob
     * meanwhile can neither fail this nor tear it.
     */
    fun claimedBlobIds(): Set<String> =
        jobs.values.filterIsInstance<ImportJob>()
            .flatMap { job -> job.blobIds.toArray(arrayOf<ByteArray>()).map { id -> id.joinToString("") { "%02x".format(it) } } }
            .toSet()

    /** Test-only: an empty table is what every process death produces. */
    fun clearAllForTest() {
        jobs.clear()
    }
}
