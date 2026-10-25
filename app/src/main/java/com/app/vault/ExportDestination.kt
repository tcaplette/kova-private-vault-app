package com.app.vault

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.InputStream
import java.io.OutputStream

/**
 * One file on its way out, and where it actually landed.
 *
 * [dateTaken] rides along because it has to be applied **after** the entry stops
 * being pending — see `MediaStoreDestination.finish`.
 */
class ExportEntry(val uri: Uri, val name: String, val dateTaken: Long? = null)

/**
 * One file already at a SAF destination — `cloud-mirror` D4.
 *
 * [size] and [modified] are what let the mirror decide whether a file it already
 * uploaded still matches, without opening it and without a VMK. Either is `-1`
 * where the provider reports no value, which [CloudMirror] reads as "unknown, so
 * rewrite it" rather than as "unchanged".
 */
class RemoteEntry(val uri: Uri, val size: Long, val modified: Long)

/**
 * Where an export writes — P1-13.
 *
 * An interface rather than two branches inline because verification has to
 * **read the file back** from wherever it went (`FORMAT.md`-adjacent, but really
 * I6: nothing is deleted until its plaintext round-trips). A write-only
 * abstraction would let the verify step read the blob again instead of the file,
 * which proves the wrong thing.
 *
 * This is the one sanctioned plaintext output in the app. I3 carves it out by
 * name; nothing else here may write plaintext anywhere.
 */
interface ExportDestination {

    /**
     * Creates an entry under a name that does not collide, returning the name
     * actually assigned — providers rename underneath us, and the manifest has to
     * say what the user will find.
     */
    fun create(name: String, mime: String, dateTaken: Long?): ExportEntry

    fun openOutput(entry: ExportEntry): OutputStream

    /**
     * Appends to an existing entry — the manifest, and only the manifest.
     *
     * Exists so the manifest can be written a line at a time **at the
     * destination**. The obvious alternative, staging it in `filesDir` and
     * copying at the end, would put a plaintext list of filenames inside the
     * app's own storage on every export, which is exactly what I3 and Gate 1's
     * scan exist to catch.
     */
    fun openAppend(entry: ExportEntry): OutputStream

    /** Reads back what actually landed. The whole reason this is an interface. */
    fun openInput(entry: ExportEntry): InputStream

    /** Makes the entry visible. Called only after verification passes. */
    fun finish(entry: ExportEntry)

    /** Removes a failed entry. Never leaves a partial where a file should be. */
    fun discard(entry: ExportEntry)

    /**
     * An entry already at this destination under [name], or null.
     *
     * Exists for the manifest and nothing else. A retried worker gets its
     * original input `Data` back, so a manifest URI passed through it would be
     * null on every retry and each retry would start a second manifest — which is
     * the opposite of a record that survives interruption.
     */
    fun find(name: String): ExportEntry?

    /** Cleans up entries a previous, killed export left invisible. */
    fun sweep(): Int

    companion object {
        /**
         * MIME from the extension, because `VaultItem` carries a filename and no
         * MIME and adding one is a format change for a recoverable value.
         *
         * A file with no extension, or an unrecognised one, is
         * `application/octet-stream` and lands in Downloads rather than the
         * gallery — present, correct and hash-identical, simply not in Photos.
         * Fixing that means storing a MIME at import (P1-6), not here.
         */
        fun mimeOf(filename: String): String {
            val ext = filename.substringAfterLast('.', "").lowercase()
            // `notes-as-documents`. Android's map does not carry `md` on every API
            // level, and a note landing as `application/octet-stream` would export
            // as a file nothing offers to open — which is the opposite of the
            // property Markdown was chosen for.
            if (ext == "md") return "text/markdown"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }

        /** `name.ext` -> `name (2).ext`, so a collision suffixes and never overwrites. */
        fun suffixed(name: String, n: Int): String {
            val dot = name.lastIndexOf('.')
            return if (dot <= 0) "$name ($n)"
            else name.substring(0, dot) + " ($n)" + name.substring(dot)
        }
    }
}

/**
 * The default destination: the platform media collections.
 *
 * No permission is requested and none is needed — an app inserting its own rows
 * under scoped storage requires none, which is why this is the default and why
 * P0-2's permission gate stays untouched.
 *
 * **`IS_PENDING` is held for the whole write** and cleared only after
 * verification, so an export that is killed leaves rows the gallery does not
 * show. A user who cancels does not find half-written photographs in their
 * timeline.
 */
class MediaStoreDestination(context: Context) : ExportDestination {

    private val resolver: ContentResolver = context.contentResolver

    private companion object {
        const val FOLDER = "Vault"
        const val MAX_COLLISION_TRIES = 999
    }

    private fun collectionFor(mime: String): Uri = when {
        mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }

    private fun relativePathFor(mime: String): String = when {
        mime.startsWith("image/") -> "${android.os.Environment.DIRECTORY_PICTURES}/$FOLDER"
        mime.startsWith("video/") -> "${android.os.Environment.DIRECTORY_MOVIES}/$FOLDER"
        else -> "${android.os.Environment.DIRECTORY_DOWNLOADS}/$FOLDER"
    }

    override fun create(name: String, mime: String, dateTaken: Long?): ExportEntry {
        val collection = collectionFor(mime)
        val path = relativePathFor(mime)

        val free = freeName(collection, path, name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, free)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            // From the item's stored createdAt, which P1-6 took from the source's
            // DATE_TAKEN — itself MediaStore's reading of the file's EXIF. Null
            // stays null: a source that offered no date has none to restore.
            if (dateTaken != null) put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
        }

        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore refused the insert for $free")

        return ExportEntry(uri, actualName(uri) ?: free, dateTaken)
    }

    override fun openOutput(entry: ExportEntry): OutputStream =
        resolver.openOutputStream(entry.uri)
            ?: throw IllegalStateException("could not open ${entry.name} for writing")

    override fun openAppend(entry: ExportEntry): OutputStream =
        resolver.openOutputStream(entry.uri, "wa")
            ?: throw IllegalStateException("could not append to ${entry.name}")

    override fun openInput(entry: ExportEntry): InputStream =
        resolver.openInputStream(entry.uri)
            ?: throw IllegalStateException("could not read ${entry.name} back")

    /**
     * Makes the entry visible, then re-applies the date.
     *
     * **The order is the point, and it was found by a failing test.** Clearing
     * `IS_PENDING` makes MediaStore scan the file and re-derive its metadata from
     * the content, which wipes a `DATE_TAKEN` written at insert time — a file
     * whose bytes carry no EXIF date ends up with no date at all. Writing it again
     * after the scan is what makes the vault's recorded date win, which is what
     * "gallery dates match originals" requires.
     */
    override fun finish(entry: ExportEntry) {
        resolver.update(
            entry.uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null, null
        )
        if (entry.dateTaken != null) {
            resolver.update(
                entry.uri,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DATE_TAKEN, entry.dateTaken)
                },
                null, null
            )
        }
    }

    override fun discard(entry: ExportEntry) {
        runCatching { resolver.delete(entry.uri, null, null) }
    }

    /**
     * Searches every collection this destination writes to, not just the one a
     * name's extension suggests — a caller looking for a file it exported should
     * not have to know which of the three it landed in.
     */
    override fun find(name: String): ExportEntry? {
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to relativePathFor("image/"),
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to relativePathFor("video/"),
            MediaStore.Downloads.EXTERNAL_CONTENT_URI to relativePathFor("application/octet-stream"),
        ).forEach { (collection, path) ->
            runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    includePending(
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf(name, "$path%")
                    ),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        return ExportEntry(
                            android.content.ContentUris.withAppendedId(collection, c.getLong(0)),
                            name
                        )
                    }
                }
            }
        }
        return null
    }

    /**
     * Deletes rows this app owns that are still pending — a previous export died
     * mid-write. Same shape as `sweepPartials`, and for the same reason: the
     * evidence of an interrupted operation must not outlive it.
     */
    override fun sweep(): Int {
        var swept = 0
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        ).forEach { collection ->
            runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    includePending("${MediaStore.MediaColumns.IS_PENDING} = 1", null),
                    null
                )?.use { c ->
                    val idColumn = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (c.moveToNext()) {
                        val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(idColumn))
                        if (resolver.delete(uri, null, null) > 0) swept++
                    }
                }
            }
        }
        return swept
    }

    /** A name nothing at [path] already uses. Never an overwrite. */
    private fun freeName(collection: Uri, path: String, name: String): String {
        var candidate = name
        var n = 1
        while (exists(collection, path, candidate) && n < MAX_COLLISION_TRIES) {
            n++
            candidate = ExportDestination.suffixed(name, n)
        }
        return candidate
    }

    private fun exists(collection: Uri, path: String, name: String): Boolean =
        runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                includePending(
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf(name, "$path%")
                ),
                null
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)

    /**
     * Query args that include this app's own pending rows.
     *
     * A default query excludes pending items entirely, so a collision check that
     * skipped them would happily hand back a name a half-written export already
     * holds. `MediaStore.setIncludePending` is the older spelling and is
     * deprecated; minSdk is 30, so the query-argument form is available directly.
     */
    private fun includePending(selection: String, args: Array<String>?) =
        android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            if (args != null) {
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            }
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }

    private fun actualName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
}

/**
 * A directory tree the user granted through the system picker.
 *
 * No `IS_PENDING` equivalent exists here, so a failed write is deleted outright
 * rather than hidden and cleaned up later.
 */
class SafDestination(context: Context, private val tree: Uri) :
    ExportDestination, CloudMirror.Target {

    private val resolver: ContentResolver = context.contentResolver

    private val treeDocumentId: String = DocumentsContract.getTreeDocumentId(tree)
    private val dir: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocumentId)

    override fun create(name: String, mime: String, dateTaken: Long?): ExportEntry {
        // SAF assigns its own non-colliding name and reports it back. Nothing is
        // overwritten; `dateTaken` has no column here and rides in the file's own
        // metadata instead.
        val uri = DocumentsContract.createDocument(resolver, dir, mime, name)
            ?: throw IllegalStateException("could not create $name in the chosen folder")
        return ExportEntry(uri, actualName(uri) ?: name)
    }

    override fun openOutput(entry: ExportEntry): OutputStream =
        resolver.openOutputStream(entry.uri)
            ?: throw IllegalStateException("could not open ${entry.name} for writing")

    override fun openAppend(entry: ExportEntry): OutputStream =
        resolver.openOutputStream(entry.uri, "wa")
            ?: throw IllegalStateException("could not append to ${entry.name}")

    override fun openInput(entry: ExportEntry): InputStream =
        resolver.openInputStream(entry.uri)
            ?: throw IllegalStateException("could not read ${entry.name} back")

    override fun finish(entry: ExportEntry) = Unit

    override fun discard(entry: ExportEntry) {
        runCatching { DocumentsContract.deleteDocument(resolver, entry.uri) }
    }

    override fun find(name: String): ExportEntry? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocumentId)
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    return@use ExportEntry(
                        DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                        name
                    )
                }
            }
            null
        }
    }.getOrNull()

    /** Nothing to sweep: a failed write here is deleted at the time it fails. */
    override fun sweep(): Int = 0

    /**
     * Every child, in one query — `cloud-mirror` D4.
     *
     * [find] answers the same question for one name, and answering it n times is
     * n listings: the mirror's diff over a 10 000-blob store would be 10 000 full
     * child queries. This is the same cursor read once, so the diff is two set
     * operations over a map that already exists.
     *
     * Separate from [find] rather than replacing it: the export path looks up one
     * manifest by name and has no use for the rest of the folder.
     */
    override fun names(): Map<String, RemoteEntry> = listing().orEmpty()

    /**
     * [names], but saying when the folder could not be read — `backup-screen-status-first`
     * D2. Null where the query throws or the provider hands back no cursor: a revoked
     * grant, an uninstalled or signed-out provider. [names] folds that into an empty
     * map because the run and the panic sweep are best effort; the status line cannot,
     * because "nothing here yet" and "cannot see the folder" are different answers.
     */
    fun listing(): Map<String, RemoteEntry>? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocumentId)
        val found = mutableMapOf<String, RemoteEntry>()
        val cursor = resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null
        ) ?: return@runCatching null
        cursor.use { c ->
            while (c.moveToNext()) {
                found[c.getString(1)] = RemoteEntry(
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                    size = if (c.isNull(2)) -1L else c.getLong(2),
                    modified = if (c.isNull(3)) -1L else c.getLong(3),
                )
            }
        }
        found
    }.getOrNull()

    /**
     * Rewrites an existing document from byte zero — `cloud-mirror` D7.
     *
     * **`"wt"`, and the `t` is the whole point.** The index blob keeps its id while
     * its ciphertext changes (`MetadataDb.save` writes back to the same blob id),
     * so the mirror overwrites it on every run rather than creating a second
     * document — [create] would let SAF assign `<id> (1)` and the mirror would
     * carry two indexes, one of them stale.
     *
     * Plain `"w"` is not enough. It is not required to truncate, and on a provider
     * that does not, a shorter rewrite leaves the tail of the previous index behind
     * the new one: a file that authenticates for its real length and carries
     * garbage after it, which nothing downstream would notice.
     */
    override fun overwrite(uri: Uri): OutputStream =
        resolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("could not overwrite $uri")

    private fun actualName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
}
