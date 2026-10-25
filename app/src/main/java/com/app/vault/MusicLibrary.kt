package com.app.vault

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size

/**
 * The device's own audio, read for the music player cover — `music-recorder-covers`.
 *
 * **This is the one place in the app that reads `MediaStore`, and it only reads.** The
 * cover is a music player, so it opens onto the music that is on the phone; a player
 * whose first screen asks you to go and find your music folder is not what any shipped
 * player does, and the first screen is the one an observer is most likely to see.
 *
 * ***Nothing here touches the vault.*** No blob, no index, no key, no `filesDir`. It
 * reads a public content provider and hands back rows. The vault's own media path —
 * `BlobStore` and the media3 `DataSource` over it — is a different mechanism for
 * different bytes and the two never meet.
 *
 * ***And nothing is copied.*** Playback is handed the `content://` URI, so the audio
 * is never read into a file of ours. There is no cache directory here to leak and no
 * plaintext copy of anything.
 *
 * **No logging**, for every other cover file's reason: the fact that this app has a
 * music player at all is a fact about the user's identity choice.
 */
object MusicLibrary {

    /**
     * The grant this needs on this device.
     *
     * `READ_MEDIA_AUDIO` exists from API 33. minSdk is 30, so 30–32 reach the same
     * rows through the legacy storage permission, which the manifest caps at 32 so it
     * is never requested on a platform that has the narrow one.
     */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasAccess(context: Context): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * One playable track.
     *
     * A plain data class of what a row shows, so the screen never holds a `Cursor`
     * and the list can be rebuilt without touching the provider again.
     */
    data class Track(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val albumId: Long,
        val durationMs: Long,
    ) {
        val uri: Uri
            get() = ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                id,
            )
    }

    /** An album, and the tracks on it in the order they were queried. */
    data class Album(val id: Long, val name: String, val artist: String, val tracks: List<Track>)

    /** An artist, and everything of theirs on the device. */
    data class Artist(val name: String, val tracks: List<Track>)

    private val PROJECTION = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
    )

    /**
     * Every music track on the device, by title.
     *
     * **`IS_MUSIC != 0` and not everything the provider holds**: notifications,
     * ringtones and alarms are audio rows too, and a player that lists the system's
     * ringtones among the songs is a player nobody has ever used.
     *
     * Blocking, and called off the main thread by [MusicCoverState] — a library of a
     * few thousand rows is milliseconds, but "a few thousand" is not a promise anyone
     * can make about somebody else's phone.
     *
     * Returns empty rather than throwing on a provider that will not answer. The
     * caller's question is what to draw, and a crash inside a cover is the loudest
     * possible tell.
     */
    fun tracks(context: Context): List<Track> {
        if (!hasAccess(context)) return emptyList()
        return try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            Track(
                                id = cursor.getLong(id),
                                title = cursor.getString(title) ?: context.getString(UNKNOWN_TITLE),
                                artist = cursor.getString(artist)?.takeUnless { it == MEDIA_UNKNOWN }
                                    ?: context.getString(UNKNOWN_ARTIST),
                                album = cursor.getString(album)?.takeUnless { it == MEDIA_UNKNOWN }
                                    ?: context.getString(UNKNOWN_ALBUM),
                                albumId = cursor.getLong(albumId),
                                durationMs = cursor.getLong(duration),
                            )
                        )
                    }
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * The same rows grouped by album, by album name.
     *
     * Grouped in memory rather than queried again: the album and artist tabs are
     * views of the tracks already read, and three queries over one table would be
     * three chances for them to disagree with each other.
     */
    fun albums(tracks: List<Track>): List<Album> =
        tracks.groupBy { it.albumId }
            .map { (id, rows) ->
                Album(id, rows.first().album, rows.first().artist, rows)
            }
            .sortedBy { it.name.lowercase() }

    /** The same rows grouped by artist, by artist name. */
    fun artists(tracks: List<Track>): List<Artist> =
        tracks.groupBy { it.artist }
            .map { (name, rows) -> Artist(name, rows) }
            .sortedBy { it.name.lowercase() }

    /**
     * Album art for a track, or null where the library has none.
     *
     * `loadThumbnail` on the track's own URI, which is the API that survived the
     * `ALBUM_ART` column's removal in Android 10 and the only one that works under
     * scoped storage. Null on anything thrown: a missing thumbnail is an ordinary
     * state that every player draws a placeholder for, and it is never an error.
     */
    fun artwork(context: Context, track: Track, sizePx: Int): Bitmap? = try {
        context.contentResolver.loadThumbnail(track.uri, Size(sizePx, sizePx), null)
    } catch (_: Exception) {
        null
    }

    /** What `MediaStore` writes when a file carries no tag. */
    private const val MEDIA_UNKNOWN = "<unknown>"

    private val UNKNOWN_TITLE = R.string.music_unknown_track
    private val UNKNOWN_ARTIST = R.string.music_unknown_artist
    private val UNKNOWN_ALBUM = R.string.music_unknown_album
}
