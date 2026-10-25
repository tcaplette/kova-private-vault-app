package com.app.vault

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `remove-originals-on-import` — the picker-to-MediaStore conversion, and the reason it
 * exists: Android accepts the converted item and refuses the picker URI.
 *
 * The rows are real MediaStore rows this test inserts; the picker URIs are built in the
 * shape MediaProvider gives local items. That the real picker hands out this shape is
 * checked end to end with the picker itself, not here.
 */
@RunWith(AndroidJUnit4::class)
class OriginalRemovalDeviceTest {

    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    private val created = mutableListOf<Uri>()
    private val user = Process.myUid() / 100_000

    @After fun tearDown() {
        created.forEach { runCatching { resolver.delete(it, null, null) } }
        created.clear()
    }

    private fun insert(collection: Uri, name: String, mime: String, dir: String): Long {
        val uri = resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            },
        )!!
        resolver.openOutputStream(uri)!!.use { it.write(ByteArray(512) { i -> i.toByte() }) }
        created += uri
        return ContentUris.parseId(uri)
    }

    private fun picker(id: Any, user: Any = this.user, authority: String = LOCAL, root: String = "picker") =
        Uri.parse("content://media/$root/$user/$authority/media/$id")

    @Test
    fun aLocalPhotoMapsToItsImagesRow() {
        val id = insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "original-removal.png", "image/png", "Pictures/VaultTest",
        )
        assertEquals(
            Uri.parse("content://media/external/images/media/$id"),
            OriginalRemoval.mediaStoreUri(picker(id), "image/png"),
        )
    }

    @Test
    fun aLocalVideoMapsToItsVideoRow() {
        val id = insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "original-removal.mp4", "video/mp4", "Movies/VaultTest",
        )
        assertEquals(
            Uri.parse("content://media/external/video/media/$id"),
            OriginalRemoval.mediaStoreUri(picker(id), "video/mp4"),
        )
    }

    @Test
    fun androidAcceptsTheConvertedItemAndRefusesThePickerUri() {
        val photo = insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "original-removal-a.png", "image/png", "Pictures/VaultTest",
        )
        val video = insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "original-removal-b.mp4", "video/mp4", "Movies/VaultTest",
        )
        val mapped = listOf(
            OriginalRemoval.mediaStoreUri(picker(photo), "image/png")!!,
            OriginalRemoval.mediaStoreUri(picker(video), "video/mp4")!!,
        )
        assertNotNull(MediaStore.createDeleteRequest(resolver, mapped))
        assertThrows(IllegalArgumentException::class.java) {
            MediaStore.createDeleteRequest(resolver, listOf(picker(photo)))
        }
    }

    @Test
    fun everythingElseMapsToNothing() {
        val cloud = "com.google.android.apps.photos.cloudpicker"
        listOf(
            picker(42, authority = cloud) to "image/jpeg",
            picker(42, user = user + 10) to "image/jpeg",
            picker("abc") to "image/jpeg",
            picker(42, root = "picker_get_content") to "image/jpeg",
            Uri.parse("content://com.android.providers.media.documents/document/image%3A42") to "image/jpeg",
            Uri.parse("content://media/external/images/media/42") to "image/jpeg",
            picker(42) to "application/pdf",
            picker(42) to null,
        ).forEach { (uri, mime) ->
            assertNull("$uri ($mime)", OriginalRemoval.mediaStoreUri(uri, mime))
        }
    }

    private companion object {
        const val LOCAL = "com.android.providers.media.photopicker"
    }
}
