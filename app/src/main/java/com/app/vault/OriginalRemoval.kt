package com.app.vault

import android.content.ContentUris
import android.net.Uri
import android.os.Process
import android.provider.MediaStore

/**
 * `remove-originals-on-import`. Which picked originals the gallery can be asked to
 * delete, and the words the prompt uses to ask.
 *
 * ***Android refuses the photo picker's own URIs.*** `MediaProvider.createRequest`
 * accepts `images/media/<id>` and `video/media/<id>` and throws on everything else,
 * `content://media/picker/…` included. A local picker item's id **is** its MediaStore
 * `_ID` — the local provider projects `_ID AS id` — so the request is built from that.
 * No media permission is involved: the system dialog is shown precisely because the
 * app holds none.
 */
object OriginalRemoval {

    /** The local provider's authority, as it appears in a picker URI's path. */
    private const val LOCAL_PICKER = "com.android.providers.media.photopicker"

    /** `createDeleteRequest` throws above this when targeting API 36. */
    const val MAX_PER_REQUEST = 2000

    val TITLE = R.string.originals_title
    val REMOVED = R.string.originals_removed

    /**
     * The MediaStore item behind a photo-picker URI, or null when there is not one this
     * app can name: a cloud item, another profile's item, any other shape, any other type.
     *
     * **Strict on purpose.** The picker URI's shape is MediaProvider's, not a public
     * contract, so a change to it must produce "not offered" — never a URI for some
     * other photograph.
     */
    fun mediaStoreUri(picked: Uri, mime: String?): Uri? {
        if (picked.scheme != "content" || picked.authority != MediaStore.AUTHORITY) return null
        val s = picked.pathSegments
        if (s.size != 5 || s[0] != "picker" || s[2] != LOCAL_PICKER || s[3] != "media") return null
        // A work-profile pick names that profile's MediaStore; the same number in ours
        // is a different photograph.
        // ponytail: PER_USER_RANGE is @hide and getIdentifier() is @SystemApi; the range
        // has been 100 000 since multi-user shipped.
        if (s[1] != (Process.myUid() / 100_000).toString()) return null
        if (s[4].isEmpty() || !s[4].all { it in '0'..'9' }) return null
        val id = s[4].toLongOrNull() ?: return null
        val collection = when {
            mime?.startsWith("image/") == true ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            mime?.startsWith("video/") == true ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else -> return null
        }
        return ContentUris.withAppendedId(collection, id)
    }

    /** The prompt's sentence. Counts the offered items by kind, then says what stays. */
    fun body(words: Words, photos: Int, videos: Int, unavailable: Int): String {
        val counted = listOfNotNull(
            words.plural(R.plurals.originals_photos, photos, photos).takeIf { photos > 0 },
            words.plural(R.plurals.originals_videos, videos, videos).takeIf { videos > 0 },
        )
        val what = if (counted.size == 2) words.get(R.string.list_and, counted[0], counted[1]) else counted.single()
        val stay = if (unavailable == 0) "" else " " + words.plural(R.plurals.originals_stay, unavailable, unavailable)
        return words.plural(R.plurals.originals_in_vault, photos + videos, what) + stay
    }

    /**
     * What the prompt is offering. Held in memory by the activity for the unlocked
     * session only; a second batch is added to it, never swapped in for it.
     */
    data class Offer(val photos: List<Uri>, val videos: List<Uri>, val unavailable: Int) {
        val uris get() = photos + videos
        val isEmpty get() = photos.isEmpty() && videos.isEmpty()
        fun body(words: Words) = body(words, photos.size, videos.size, unavailable)

        operator fun plus(other: Offer) =
            Offer(photos + other.photos, videos + other.videos, unavailable + other.unavailable)

        fun without(sent: Collection<Uri>) = Offer(photos - sent.toSet(), videos - sent.toSet(), unavailable)
    }
}
