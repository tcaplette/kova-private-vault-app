package com.app.vault

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * One vault's blobs, mirrored into a folder the user's cloud provider syncs —
 * `cloud-mirror`, as `settings-separation` rewrote it.
 *
 * **The destination belongs to a vault, not to the install.** It lives in that
 * vault's encrypted index ([MetadataDb.mirrorDestination]), so it is knowable only
 * while that vault is open. A preferences file with one key per vault would say how
 * many vaults exist to anyone who reads it, which is the one fact the app protects.
 *
 * **Which blobs are the vault's needs no new record.** The open index already lists
 * them — every item's `blobIds`, the thumbnail packs, and the index blob itself —
 * so nothing is written to disk to answer the question. The mapping is still on no
 * file anywhere; the *folders* are the mapping now, and that is stated on the backup
 * screen rather than hidden.
 *
 * **What this file gave up, and it was real.** Until `settings-separation` it named
 * no vault symbol at all, and a mirror run completed with every vault locked. Both
 * ended here: selecting one vault's blobs requires its index, which requires its
 * key. A vault that is never opened is never backed up, and the backup row says so.
 *
 * **What is never uploaded.** `vault.slots` and `vault.bio` are not in `blobs/` and
 * are never named here. The slot file is the Argon2id target; a copy of it at a
 * provider is a passphrase grind with no attempt limit and no time limit, against a
 * file that cannot be recalled. It stays on the device, which is why a restore needs
 * the 24-word kit rather than the passphrase — see `VaultLifecycle.recover`, which
 * rebuilds the slot array from CSPRNG and never reads the old one.
 *
 * **No upload ledger.** What is already there is answered by listing the
 * destination. A stored set of uploaded blob ids would be new persisted state that
 * can drift from what is actually at the far end.
 */
object CloudMirror {

    private const val TAG = "CloudMirror"


    /** §5's filenames: 16 bytes of blob id, lowercase hex. */
    private const val BLOB_NAME_LENGTH = BlobStore.BLOB_ID_BYTES * 2

    /** What one run has to do, decided before anything is opened. */
    class Plan(
        val upload: List<String>,
        val overwrite: List<String>,
        val delete: List<String>,
    ) {
        val empty: Boolean get() = upload.isEmpty() && overwrite.isEmpty() && delete.isEmpty()
    }

    /**
     * One file, reduced to the two facts the diff needs — either side.
     *
     * Deliberately not [RemoteEntry]: that one carries a `Uri`, and a `Uri` in the
     * planner's signature would drag Android into the one part of this feature that
     * can be decided by arithmetic. [plan] is a pure function over two of these
     * maps and is tested on the JVM with no emulator and no provider.
     */
    class Entry(val size: Long, val modified: Long)

    /**
     * The whole decision, as a pure function — no SAF, no Android, no I/O.
     *
     * Three sets fall out of two listings:
     *  - **upload**: local names the destination does not have.
     *  - **delete**: destination names that are not local any more. This is what
     *    makes the folder a mirror rather than an archive of everything the user
     *    chose to delete. Nothing here consults the trash, and nothing needs to:
     *    a trashed item's blobs are still in `blobs/`, so the undo window is
     *    honoured by construction.
     *  - **overwrite**: present on both sides but not identical.
     *
     * The third one is the only interesting case. Content blobs are immutable and
     * bucket-padded, so they match forever after their first upload. The index
     * blob is not: `MetadataDb.save` writes back to the **same blob id** with new
     * ciphertext, so its name is stable while its contents are not. This file
     * cannot tell which name is the index — that would need the VMK — so it asks
     * the question that does not require knowing: has this file changed?
     *
     * Size answers it whenever the index crosses a 64 KiB chunk boundary. Inside a
     * boundary the size is unchanged and only the modification time moves, so a
     * local file stamped later than its copy is taken as changed.
     *
     * ponytail: size-or-newer-mtime, not a content hash. A hash would need the
     * remote bytes read back on every run, which is a download of the whole store
     * to decide it needs no upload. The known ceiling is clock skew — a provider
     * stamping server time far ahead of a slow device clock would let a changed
     * index look current until its size next moves. If that is ever observed,
     * the upgrade is a per-run marker file holding the last run's local clock
     * reading, not a hash.
     */
    fun plan(local: Map<String, Entry>, remote: Map<String, Entry>): Plan {
        val upload = mutableListOf<String>()
        val overwrite = mutableListOf<String>()

        for ((name, here) in local) {
            val there = remote[name]
            when {
                there == null -> upload += name
                // A provider that reports neither size nor time has told us
                // nothing, so the file is rewritten rather than assumed current.
                there.size < 0 || there.modified < 0 -> overwrite += name
                there.size != here.size -> overwrite += name
                here.modified > there.modified -> overwrite += name
            }
        }

        val delete = remote.keys.filter { it !in local }

        return Plan(upload, overwrite, delete)
    }

    /**
     * Where a folder stands against this vault — `backup-screen-status-first` D1.
     *
     * [pending] is how many files the next run would upload, rewrite or delete: zero
     * means the folder holds exactly this vault. [lastCopied] is the newest time the
     * provider reports for any file there, or null where it reports none. It is when
     * the folder last received something, which is the only "last backup" this app
     * can see without keeping a record of its own.
     */
    class Status(val pending: Int, val lastCopied: Long?)

    /**
     * [plan]'s answer counted rather than carried out. Derived from the two listings
     * a run would make, so the screen can say "up to date" without a stored timestamp
     * — `cloud-mirror` forbids new persisted state, and a stored time would say when
     * the app last tried, not whether the folder matches.
     */
    fun status(local: Map<String, Entry>, remote: Map<String, Entry>): Status {
        val plan = plan(local, remote)
        return Status(
            pending = plan.upload.size + plan.overwrite.size + plan.delete.size,
            lastCopied = remote.values.map { it.modified }.filter { it >= 0 }.maxOrNull(),
        )
    }

    /**
     * Every blob on disk, by name.
     *
     * `blobs/` only. `incoming/` is its **sibling**, not its child, so a partial
     * cannot be listed here — and the length filter is the assert that keeps that
     * true if a later refactor moves the staging directory inside. A partial is
     * unpadded and distinctly named, which makes it the exact size signal §5's
     * padding exists to destroy; it must never leave the device.
     */
    fun localEntries(blobsDir: File): Map<String, Entry> =
        blobsDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.length == BLOB_NAME_LENGTH && it.name.all(::isHex) }
            .associate { it.name to Entry(it.length(), it.lastModified()) }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f'

    /** Why a chosen folder cannot be used. Null is not one of these — see [destinationRefusal]. */
    enum class Refusal {
        /** It already holds blobs of another vault on this device. */
        AnotherVault,

        /** It already holds files this install did not put there. */
        AnotherInstall,
    }

    /**
     * Why this destination cannot be mirrored into, or null when it is free —
     * `cloud-mirror` D9, widened by `settings-separation`.
     *
     * Mirror semantics plus two vaults sharing one folder means the second vault
     * deletes everything the first uploaded — the same failure two *installs*
     * sharing one folder already had, one level in. So the test is no longer "is
     * this file one of ours" but "is this file one of **this vault's**", and a
     * folder holding anything else is refused.
     *
     * The two refusals are told apart by the local store: a foreign name that is
     * nonetheless a blob on this device is another vault of this install, and one
     * that is not is another install entirely. The sentences differ because the
     * remedies do.
     *
     * No marker file and no install id is written to the provider: the folder's
     * existing contents answer the question, and a file whose only job is to say
     * "this vault was here" is the mapping this app refuses to write down.
     */
    fun destinationRefusal(
        vaultNames: Set<String>,
        localNames: Set<String>,
        remote: Set<String>,
    ): Refusal? {
        val foreign = remote.filterNot { it in vaultNames }
        if (foreign.isEmpty()) return null
        return if (foreign.any { it in localNames }) Refusal.AnotherVault else Refusal.AnotherInstall
    }

    /** [destinationRefusal] as the boolean the picker's guard reads. */
    fun destinationIsFree(vaultNames: Set<String>, remote: Set<String>): Boolean =
        remote.all { it in vaultNames }

    // --- the destination ------------------------------------------------------

    /** The folder this vault mirrors into, or null where backup was never turned on. */
    fun destination(db: MetadataDb): Uri? = db.mirrorDestination?.let(Uri::parse)

    /**
     * Points this vault at [tree]. The caller writes the index — this only sets the
     * field, so the write rides out inside whatever commit is already open.
     */
    fun setDestination(db: MetadataDb, tree: Uri) {
        db.mirrorDestination = tree.toString()
    }

    /**
     * Turns this vault's mirroring off. The files already at the destination are
     * left where they are — this is the user withdrawing the app's access, not
     * asking for their backup to be destroyed. Panic is the thing that deletes.
     */
    fun clearDestination(context: Context, db: MetadataDb) {
        destination(db)?.let { releaseGrant(context, it) }
        db.mirrorDestination = null
    }

    /** Hands the persistable grant back, best effort. */
    fun releaseGrant(context: Context, tree: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                tree,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /**
     * The blob names this vault's index references — the upload set.
     *
     * Four sources and no fifth: every item's blobs, the thumbnail packs, this
     * vault's own index blob, and the keyring blob. The last one is not the vault's
     * and is here on purpose — a restore is the 24 words plus one folder, and the
     * words find the keyring by deriving its blob id (`VaultKeys.keyringBlobId`).
     * Without it in the folder there is nothing for them to open and the restore
     * this feature exists for cannot happen.
     *
     * **Chaff and the tamper log are not in it.** Chaff hides how much of the store
     * is real *on the device*; at a destination that holds one vault it would hide
     * nothing and cost the user every byte of it. The log is this vault's own record
     * and is rebuilt, not restored.
     */
    fun vaultNames(
        db: MetadataDb,
        metadataBlobId: ByteArray,
        keyringBlobId: ByteArray?,
        /** `per-vault-intruder-capture`. The vault's own capture store, where it has one. */
        captureStore: CaptureStoreIndex? = null,
    ): Set<String> {
        val names = mutableSetOf<String>()
        db.items.forEach { item -> item.blobIds.forEach { names += hex(it) } }
        db.thumbPacks.forEach { names += hex(it.id) }
        names += hex(metadataBlobId)
        keyringBlobId?.let { names += hex(it) }
        captureStore?.let { c ->
            c.db.items.forEach { item -> item.blobIds.forEach { names += hex(it) } }
            c.db.thumbPacks.forEach { names += hex(it.id) }
            names += hex(c.id)
            // Unlike a vault's own log, a capture store's is its timeline: the record, carried.
            c.db.logBlobId?.let { names += hex(it) }
        }
        return names
    }

    /** A vault's capture store's index and its blob ID — `per-vault-intruder-capture`. */
    class CaptureStoreIndex(val id: ByteArray, val db: MetadataDb)

    /**
     * The open vault's capture store, read under the capture store key its [vmk] derives, or null
     * where the vault has none. A store that will not read is backed up as though absent.
     */
    fun captureStoreOf(store: BlobStore, vmk: ByteArray): CaptureStoreIndex? {
        val key = VaultKeys.captureStoreKey(vmk)
        return try {
            val id = VaultKeys.metadataBlobId(key)
            if (!store.fileOf(id).exists()) null
            else runCatching { CaptureStoreIndex(id, MetadataDb.load(store, id, key)) }.getOrNull()
        } finally {
            key.fill(0)
        }
    }

    // --- where a backup can go — `backup-to-cloud-apps` ----------------------------

    /**
     * One row of the "Back up to" list — design D1. A cloud app gets one backup file; a
     * removable volume gets the folder mirror.
     */
    sealed class Destination(val label: String) {
        /** An app publishing a `DocumentsProvider`. [icon] is its own, drawn at 96 px. */
        class CloudApp(label: String, val authority: String, val icon: android.graphics.Bitmap?) : Destination(label)

        /** A mounted SD card or USB drive. [intent] opens the folder picker at its root. */
        class Volume(label: String, val intent: android.content.Intent) : Destination(label)
    }

    /**
     * The platform's own document providers, by package — none of them is somewhere else
     * to keep a copy. The phone's storage and its downloads and media views are refused
     * (a copy there is lost with the phone); the Files app's archive browser, MTP for an
     * attached camera, bug reports and system traces are not backup destinations at all.
     * Measured on the emulator: without this the list read Drive, Files, MTP Host.
     */
    private val PLATFORM_PACKAGES = setOf(
        "com.android.externalstorage",
        "com.android.providers.downloads",
        "com.android.providers.media",
        "com.google.android.providers.media.module",
        "com.android.providers.media.module",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.android.mtp",
        "com.android.shell",
        "com.android.traceur",
    )

    /**
     * What this device can back up to — cloud apps first, then removable volumes.
     *
     * Found, not listed: every app publishing a `DocumentsProvider`, by its own label and
     * icon, so the list is whatever the person has — Drive, Dropbox, Proton Drive,
     * Nextcloud — and names no partner. Accounts are not shown: reading a provider's roots
     * needs `MANAGE_DOCUMENTS`, so the person picks the account in the provider's own sheet.
     */
    fun destinations(context: Context): List<Destination> {
        val pm = context.packageManager
        val clouds = pm.queryIntentContentProviders(
            android.content.Intent(android.provider.DocumentsContract.PROVIDER_INTERFACE), 0,
        ).mapNotNull { info ->
            val provider = info.providerInfo ?: return@mapNotNull null
            if (provider.packageName in PLATFORM_PACKAGES) return@mapNotNull null
            if (provider.packageName == context.packageName) return@mapNotNull null
            Destination.CloudApp(
                label = provider.applicationInfo.loadLabel(pm).toString(),
                authority = provider.authority,
                icon = runCatching { bitmapOf(provider.applicationInfo.loadIcon(pm)) }.getOrNull(),
            )
        }.distinctBy { it.authority }.sortedBy { it.label.lowercase() }

        val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
        val volumes = storage.storageVolumes
            .filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
            .map { Destination.Volume(it.getDescription(context), it.createOpenDocumentTreeIntent()) }

        return clouds + volumes
    }

    private fun bitmapOf(drawable: android.graphics.drawable.Drawable): android.graphics.Bitmap {
        val size = 96
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    /**
     * "SD card" rather than the storage provider's "External Storage" — a removable
     * volume's tree id starts with its UUID, which names the volume the list offered.
     */
    private fun volumeName(context: Context, tree: Uri): String? = runCatching {
        if (tree.authority != "com.android.externalstorage.documents") return@runCatching null
        val uuid = android.provider.DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
        context.getSystemService(android.os.storage.StorageManager::class.java).storageVolumes
            .firstOrNull { it.uuid == uuid }?.getDescription(context)
    }.getOrNull()

    /** What the provider reports for a backup file — `backup-to-cloud-apps` D5. */
    class FileInfo(val modified: Long, val size: Long, val name: String?)

    /**
     * The backup file's modified time, size and name, or null where it cannot be queried —
     * deleted, revoked, signed out. Queries a provider, so never on the main thread.
     */
    fun fileInfo(context: Context, uri: Uri): FileInfo? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                android.provider.DocumentsContract.Document.COLUMN_SIZE,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) null
            else FileInfo(
                modified = if (c.isNull(0)) -1L else c.getLong(0),
                size = if (c.isNull(1)) -1L else c.getLong(1),
                name = if (c.isNull(2)) null else c.getString(2),
            )
        }
    }.getOrNull()

    /** "Google Drive · backup.bin" — the provider app's label and the file's name. */
    fun fileLabel(context: Context, uri: Uri, name: String?): String {
        val app = runCatching {
            val pm = context.packageManager
            uri.authority?.let { pm.resolveContentProvider(it, 0) }
                ?.applicationInfo?.loadLabel(pm)?.toString()
        }.getOrNull()
        return listOfNotNull(app, name).joinToString(" · ").ifEmpty { context.getString(R.string.kit_backup_file) }
    }

    /**
     * Whether a destination is a cloud app's single backup file rather than a folder —
     * design D2. The one stored URI says which by its shape, so no field is added.
     */
    fun isFile(uri: Uri): Boolean = !android.provider.DocumentsContract.isTreeUri(uri)

    /**
     * Whether a folder is on the phone's own shared storage — design D2. Such a folder is
     * refused: a copy there is lost with the phone.
     */
    fun isInternalStorage(tree: Uri): Boolean =
        tree.authority == "com.android.externalstorage.documents" &&
            runCatching { android.provider.DocumentsContract.getTreeDocumentId(tree) }
                .getOrNull()?.startsWith("primary:") == true

    /**
     * The folder as the user would name it — "Drive › Vault backup" —
     * `backup-screen-status-first` D4. The provider app's label and the folder's
     * display name, each best effort; the last element of the tree's document id when
     * the folder cannot be read. Queries a provider, so never on the main thread.
     *
     * Where package visibility hides the provider's app, the label is the folder's
     * name alone — no `<queries>` entry is added for a label.
     */
    fun folderLabel(context: Context, tree: Uri): String {
        val folder = runCatching {
            val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                tree, android.provider.DocumentsContract.getTreeDocumentId(tree),
            )
            context.contentResolver.query(
                doc, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val app = volumeName(context, tree) ?: runCatching {
            val pm = context.packageManager
            tree.authority?.let { pm.resolveContentProvider(it, 0) }
                ?.applicationInfo?.loadLabel(pm)?.toString()
        }.getOrNull()
        // The tree's last segment is a document id — `primary:Documents/Vault backup` on
        // local storage — so only its final path element is a name a person would use.
        val fallback = tree.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
        return listOfNotNull(app, folder ?: fallback).joinToString(" › ")
    }

    /**
     * The blobs rewritten under their own ID — `backup-to-cloud-apps` D3. Only the index and
     * the keyring are saved in place; every other blob — thumbnail packs included, which
     * `ThumbnailStore.flush` replaces under a new ID — is written once. A backup file copies
     * these two aside before sealing its table, which keeps the copy inside a lock small.
     */
    @Suppress("UNUSED_PARAMETER")
    fun mutableNames(
        db: MetadataDb,
        metadataBlobId: ByteArray,
        keyringBlobId: ByteArray?,
        /** `per-vault-intruder-capture`. Its index and log are saved in place too. */
        captureStore: CaptureStoreIndex? = null,
    ): Set<String> {
        val names = mutableSetOf(hex(metadataBlobId))
        keyringBlobId?.let { names += hex(it) }
        captureStore?.let { c ->
            names += hex(c.id)
            c.db.logBlobId?.let { names += hex(it) }
        }
        return names
    }

    // --- where a mirror lives -------------------------------------------------

    /**
     * The folder a mirror is written to, behind an interface — for the reason
     * `ExportDestination` is one: **verification has to read the files back.**
     *
     * The round trip is this change's real test — mirror a store, clear the app,
     * restore, recover with the kit, compare plaintext — and a tree URI can only be
     * obtained by a human tapping through the system picker. A test that cannot run
     * is worse than an interface, and this is the same trade `ExportDestination`
     * already made and documented.
     *
     * Two implementations, so it is not an abstraction over one thing:
     * [SafDestination] in the app, and a local directory in `MirrorDeviceTest`.
     */
    interface Target {
        fun names(): Map<String, RemoteEntry>
        fun create(name: String, mime: String, dateTaken: Long?): ExportEntry
        fun openOutput(entry: ExportEntry): java.io.OutputStream
        fun openInput(entry: ExportEntry): java.io.InputStream
        fun overwrite(uri: Uri): java.io.OutputStream
        fun discard(entry: ExportEntry)
    }

    // --- the run --------------------------------------------------------------

    /** What a run did, for the screen. Counts only; nothing here names a blob. */
    class Result(val uploaded: Int, val rewritten: Int, val deleted: Int, val failed: Int)

    /**
     * One pass: list both sides, plan, then move the bytes.
     *
     * A failure on one file does not abandon the run. The diff is recomputed from
     * scratch next time, so anything missed is simply outstanding again — there is
     * no queue to get out of step and nothing to resume.
     */
    fun run(
        context: Context,
        tree: Uri,
        blobsDir: File,
        names: Set<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result = runOn(SafDestination(context, tree), blobsDir, names, onProgress)

    /**
     * [run]'s body, against any [Target]. The round-trip test drives this one.
     *
     * [names] is the open vault's upload set — see [vaultNames]. Everything else in
     * `blobs/` belongs to another vault, to the chaff, or to the log, and none of it
     * goes to this folder.
     */
    fun runOn(
        destination: Target,
        blobsDir: File,
        names: Set<String>,
        /**
         * `backup-screen-status-first` D9. Files handled so far, failures included,
         * against the plan's size — what the screen's determinate bar draws. Called on
         * this thread; the caller posts it wherever it draws.
         */
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val local = localEntries(blobsDir).filterKeys { it in names }
        val remote = destination.names()
        val plan = plan(local, remote.mapValues { Entry(it.value.size, it.value.modified) })

        var uploaded = 0
        var rewritten = 0
        var deleted = 0
        var failed = 0

        val total = plan.upload.size + plan.overwrite.size + plan.delete.size
        var done = 0
        onProgress(0, total)

        for (name in plan.upload) {
            // A half-written document under a real blob's name is worse than an
            // absent one: the next diff sees the name present and, if the size
            // happens to match, never retries it. So a failed upload is deleted.
            val entry = runCatching {
                destination.create(name, "application/octet-stream", null)
            }.getOrNull()
            if (entry == null) {
                failed++
                onProgress(++done, total)
                continue
            }
            val ok = runCatching {
                File(blobsDir, name).inputStream().use { input ->
                    destination.openOutput(entry).use { output -> input.copyTo(output) }
                }
            }.isSuccess
            if (ok) {
                uploaded++
            } else {
                failed++
                destination.discard(entry)
            }
            onProgress(++done, total)
        }

        for (name in plan.overwrite) {
            val target = remote[name]?.uri
            if (target != null) {
                val ok = runCatching {
                    File(blobsDir, name).inputStream().use { input ->
                        destination.overwrite(target).use { output -> input.copyTo(output) }
                    }
                }.isSuccess
                if (ok) rewritten++ else failed++
            }
            onProgress(++done, total)
        }

        for (name in plan.delete) {
            val target = remote[name]?.uri
            if (target != null) {
                val ok = runCatching {
                    destination.discard(ExportEntry(target, name))
                    true
                }.getOrDefault(false)
                if (ok) deleted++ else failed++
            }
            onProgress(++done, total)
        }

        Log.i(TAG, "mirror run: +$uploaded ~$rewritten -$deleted !$failed")
        return Result(uploaded, rewritten, deleted, failed)
    }

    /**
     * Empties the destination — `panic-pin`.
     *
     * Panic overwrites slot arrays and leaves blobs alone, which is correct while
     * the blobs die with the phone. With a mirror they do not: the keyring's 24
     * words still open the cloud copy, so panic without this stops destroying what
     * it promises to destroy.
     *
     * Best effort by construction. A destination that cannot be reached returns 0
     * and reports nothing — the slot wipe is the part that must succeed, and a
     * panic unlock that stalled or errored on a dead provider would be
     * distinguishable from an ordinary one, which `panic-pin` forbids outright.
     */
    fun destroy(context: Context, tree: Uri): Int {
        // `backup-to-cloud-apps` D8. A single-document grant is a backup file by
        // construction — nothing else in the app persists one (`BackupGrantScanTest`) — so
        // the file itself goes. Best effort, as the folder sweep is.
        if (isFile(tree)) {
            return if (runCatching {
                    android.provider.DocumentsContract.deleteDocument(context.contentResolver, tree)
                }.getOrDefault(false)) 1 else 0
        }
        return destroyOn(SafDestination(context, tree))
    }

    /**
     * [destroy]'s body, against any [Target].
     *
     * **Blob-shaped names only.** Panic reaches a folder through the grants this
     * install holds, and an install holds grants on export folders too — a sweep
     * that deleted everything in one of those would destroy a user's own documents
     * to empty a backup that was never there. A 32-character lowercase-hex name is
     * ours; nothing else in the folder is touched.
     */
    fun destroyOn(destination: Target): Int {
        var removed = 0
        runCatching {
            for ((name, entry) in destination.names()) {
                if (name.length != BLOB_NAME_LENGTH || !name.all(::isHex)) continue
                runCatching {
                    destination.discard(ExportEntry(entry.uri, name))
                    removed++
                }
            }
        }
        return removed
    }

    /**
     * Copies a mirror's blobs into `blobs/` — `cloud-mirror` restore. Nothing here opens
     * what it copies; the 24 words turn it back into a keyring. [onProgress] is [runOn]'s, over the
     * folder's blob-named files — `backup-screen-status-first` D9.
     *
     * `backup-file-recover-and-import`, D6. A throw partway — a folder that cannot be
     * listed, a provider that goes away — deletes every file this call copied and rethrows,
     * because the store may hold another list's files; a single blob that fails to copy is
     * removed where it failed.
     */
    fun restoreFrom(
        source: Target,
        blobsDir: File,
        /**
         * `backup-to-cloud-apps` D13: a second vault onto a store that already has one. Names
         * already present — the shared keyring above all — are left as they are.
         */
        skipExisting: Boolean = false,
        /**
         * Names not copied — the keyring, which a restore reads from a side directory rather
         * than letting it land in the store (`backup-file-recover-and-import`, D3).
         */
        exclude: Set<String> = emptySet(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        require(skipExisting || localEntries(blobsDir).isEmpty()) { "the blob store is not empty" }
        blobsDir.mkdirs()
        val copied = mutableListOf<File>()
        try {
            val blobs = source.names().filterKeys { it.length == BLOB_NAME_LENGTH && it.all(::isHex) && it !in exclude }
            var done = 0
            onProgress(0, blobs.size)
            for ((name, entry) in blobs) {
                val target = File(blobsDir, name)
                if (skipExisting && target.exists()) {
                    onProgress(++done, blobs.size)
                    continue
                }
                val ok = runCatching {
                    source.openInput(ExportEntry(entry.uri, name)).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }.isSuccess
                if (ok) copied += target else target.delete()
                onProgress(++done, blobs.size)
            }
        } catch (e: Throwable) {
            copied.forEach { it.delete() }
            throw e
        }
        Log.i(TAG, "restored ${copied.size} blob(s)")
        return copied.size
    }

    /**
     * An SD-card backup folder as `VaultLifecycle` takes one — `backup-file-recover-and-import`,
     * D3. It opens when it holds the words' keyring blob. Unpacking copies that keyring into the
     * side directory first — so a folder whose keyring cannot be read puts nothing in the
     * store — and then every other blob, with nothing already there overwritten.
     */
    fun source(target: Target, blobsDir: File): VaultLifecycle.BackupSource =
        VaultLifecycle.BackupSource(
            opens = { key -> hex(VaultKeys.keyringBlobId(key)) in target.names() },
            unpack = { key, sideDir, progress ->
                val keyring = hex(VaultKeys.keyringBlobId(key))
                val entry = target.names()[keyring]
                if (entry == null) false else {
                    sideDir.mkdirs()
                    target.openInput(ExportEntry(entry.uri, keyring)).use { input ->
                        File(sideDir, keyring).outputStream().use { input.copyTo(it) }
                    }
                    restoreFrom(target, blobsDir, skipExisting = true, exclude = setOf(keyring), onProgress = progress)
                    true
                }
            },
        )
}
