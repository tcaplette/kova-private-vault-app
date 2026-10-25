package com.app.vault

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

/** What the content resolver knows about a source before it is imported. */
class SourceInfo(
    val name: String,
    /** Null when the source offered no date. Never the import time — see P1-6. */
    val createdAt: Long?,
    val nameWasSynthesised: Boolean,
)

/**
 * Reading a source's name and capture date, in one place.
 *
 * **Extracted in P1-8.5 because there were two versions and they disagreed.**
 * P1-6's `MediaImport` read `DISPLAY_NAME` plus `DATE_TAKEN`/`DATE_MODIFIED`;
 * P1-7's `ImportPipeline` read only the name and hard-coded `createdAt = null`.
 * Both were correct against their own task's Verify, and nothing compared them.
 *
 * It mattered the moment P1-8.5 put the UI on the resumable path: P1-8's grid
 * orders by capture date, so the pipeline that survives a process kill was also
 * the one that threw the dates away.
 */
object SourceMetadata {

    fun describe(resolver: ContentResolver, uri: Uri): SourceInfo {
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

        return SourceInfo(
            name = name ?: (uri.lastPathSegment ?: "import-${System.currentTimeMillis()}"),
            createdAt = createdAt,
            nameWasSynthesised = name == null,
        )
    }

    /**
     * A null projection is deliberate: asking for `DATE_TAKEN` by name throws on
     * providers that do not have it, and `OpenDocument` URIs frequently do not.
     */
    private fun Cursor.stringOrNull(column: String): String? {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getString(i) else null
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val i = getColumnIndex(column)
        return if (i >= 0 && !isNull(i)) getLong(i) else null
    }
}
