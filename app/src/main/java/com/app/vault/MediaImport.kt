package com.app.vault

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

/** What one `importAll` call did. A failed item does not discard the batch. */
class ImportResult(
    val imported: List<VaultItem>,
    val failures: List<Pair<Uri, String>>,
    /** Items whose display name the provider did not supply, so one was made up. */
    val synthesisedNames: List<String>
)

/**
 * Files in — P1-6.
 *
 * The only route permitted by I1: `PickVisualMedia` and `OpenDocument` grant read
 * access to exactly the items the user chose, for as long as it takes to read
 * them, with nothing in the manifest. Every alternative needs `READ_MEDIA_*`,
 * which `:app:checkDebugPermissions` fails the build on.
 *
 * The source stream goes straight into the blob envelope. It is never copied to
 * a cache file, a temp file, or a `ByteArray` first — that would be plaintext
 * user content on disk (I3) and would fail on any video worth storing.
 */
class MediaImport(
    private val resolver: ContentResolver,
    private val store: BlobStore,
    private val db: MetadataDb,
    /**
     * Thumbnail packs (§8). Optional so the P1-6 tests that only care about
     * import still construct this without one; when absent, thumbnails are left
     * null and [Thumbnails.backfill] fills them in on first view.
     */
    private val thumbs: ThumbnailStore? = null,
    /**
     * Remove image metadata on the way in — `exif-strip-choice`.
     *
     * Passed rather than read from `GridPreference` because this class holds a
     * `ContentResolver` and no `Context`, and because the caller is the only one that
     * knows whether this import is the user's own choice or the decoy flow's. What a strip
     * removes, or null to store the file as it was given.
     */
    private val strip: Set<PhotoDetail>? = null,
) {

    /**
     * Imports every URI, then saves the index **once**.
     *
     * `MetadataDb.save` rewrites the whole index, so a save per item would rewrite
     * it once per photo. One unreadable URI is reported and skipped; the rest of
     * the batch still lands.
     */
    fun importAll(
        uris: List<Uri>,
        vmk: ByteArray,
        dbId: ByteArray,
        /** The folder these land in, or null. The shipping path is `ImportWorker`. */
        folderId: ByteArray? = null,
    ): ImportResult {
        val imported = mutableListOf<VaultItem>()
        val failures = mutableListOf<Pair<Uri, String>>()
        val synthesised = mutableListOf<String>()

        for (uri in uris) {
            try {
                val source = SourceMetadata.describe(resolver, uri)
                if (source.nameWasSynthesised) synthesised += source.name
                imported += importOne(uri, source, vmk, folderId)
            } catch (e: Exception) {
                failures += uri to (e.message ?: e::class.java.simpleName)
            }
        }

        if (imported.isNotEmpty()) {
            // Thumbnails before the index save, so one save records both the
            // items and their pack references. Generation failing leaves the
            // item with a null thumb and the backfill path to pick it up.
            val withThumbs = thumbs?.let {
                Thumbnails.generateAll(store, it, db, imported, vmk)
            } ?: imported

            db.items += withThumbs
            // P2-9. This takes the index as a parameter, so the caller owns the
            // transaction and this save is the caller's to make. No product code
            // calls this path any more — the UI imports through `ImportWorker` —
            // and `IndexCommitScanTest` exempts it by name for that reason. If it
            // is ever wired back in, the caller wraps it in `MetadataDb.commit`.
            db.save(store, dbId, vmk)
            return ImportResult(withThumbs, failures, synthesised)
        }
        return ImportResult(imported, failures, synthesised)
    }

    private fun importOne(
        uri: Uri,
        source: SourceInfo,
        vmk: ByteArray,
        folderId: ByteArray?,
    ): VaultItem {
        // writeItem, not write: this path predates WorkManager (P1-6) and is kept
        // only for the direct-import tests, but it must produce the same on-disk
        // shape as the shipping path or it would write blobs over the cap.
        // `exif-strip-choice`. The filter is a stream between the source and the envelope,
        // never a staged file — `Pipe` is what joins a producer that writes to a stream with a
        // consumer that reads one, which is the same reason the receive flow uses it.
        val removed = strip?.takeIf { ExifPolicy.strippable(ExportDestination.mimeOf(source.name)) }
        val written = resolver.openInputStream(uri)?.use { input ->
            if (removed != null) {
                Pipe.between({ sink -> ExifPolicy.copyStripped(input, sink, removed) }) {
                    store.writeItem(it, vmk)
                }
            } else {
                store.writeItem(input, vmk)
            }
        } ?: error("no stream for $uri")

        return VaultItem(
            blobIds = written.blobIds,
            plaintextLength = written.plaintextLength,
            thumb = null,          // P1-8
            filename = source.name,
            // P2-13. Written for the first time; the field has existed since P1-5.
            album = null,
            folderId = folderId,
            createdAt = source.createdAt,
            importedAt = System.currentTimeMillis(),
            tags = emptyList()
        )
    }

    /**
     * One query for everything. A null projection is deliberate: asking for
     * `DATE_TAKEN` by name throws on providers that do not have it, and
     * `OpenDocument` URIs frequently do not.
     */
    private fun describe(uri: Uri): SourceInfo {
        var name: String? = null
        var createdAt: Long? = null

        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.stringOrNull(OpenableColumns.DISPLAY_NAME)

                // DATE_TAKEN is milliseconds; DATE_MODIFIED is seconds. Absent
                // means absent — never substitute the import time, or every
                // exported file carries a capture date of whenever it was imported.
                createdAt = c.longOrNull(MediaStore.MediaColumns.DATE_TAKEN)
                    ?: c.longOrNull(MediaStore.MediaColumns.DATE_MODIFIED)?.times(1000)
            }
        }

        val synthesised = name == null
        return SourceInfo(
            name = name ?: (uri.lastPathSegment ?: "import-${System.currentTimeMillis()}"),
            createdAt = createdAt,
            nameWasSynthesised = synthesised
        )
    }

    private fun android.database.Cursor.stringOrNull(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }

    private fun android.database.Cursor.longOrNull(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 }
            ?.let { if (isNull(it)) null else getLong(it) }
            ?.takeIf { it > 0 }
}
