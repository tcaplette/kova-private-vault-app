package com.app.vault

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlin.math.min

/**
 * ExoPlayer reading straight out of the blob store — the P0-5 construction,
 * rebuilt as product code after the spike was deleted at Gate 0.
 *
 * This is the only reason playback can satisfy I3. Every other route hands the
 * player a path or a `content://` URI, which means the plaintext has to exist
 * somewhere first. Here the player pulls byte ranges and they are decrypted on
 * the way past, never landing.
 *
 * Seek is cheap because §5's chunks decrypt independently: jumping to a position
 * decrypts the chunks that contain it, not everything before it. P0-5 measured
 * 38–66 ms and found no growth with offset.
 */
@UnstableApi
class VaultDataSource(
    private val store: BlobStore,
    /**
     * Every blob of the item, in order. A seek past the split boundary lands in a
     * later blob and the reader opens only that one — playback of a 4 GB video
     * never holds 256 file handles.
     */
    private val blobIds: List<ByteArray>,
    private val vmk: ByteArray,
    private val plaintextLength: Long,
) : BaseDataSource(/* isNetwork = */ false) {

    private var reader: BlobStore.Reader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val r = store.reader(blobIds, vmk, plaintextLength)
        reader = r
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            plaintextLength - dataSpec.position
        } else {
            min(dataSpec.length, plaintextLength - dataSpec.position)
        }

        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT

        val r = reader ?: throw IllegalStateException("read before open")
        val n = min(length.toLong(), remaining).toInt()
        r.readRange(position, n).copyInto(buffer, offset)

        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val r = reader
        reader = null
        uri = null
        if (r != null) {
            r.close()
            transferEnded()
        }
    }

    /** Hands ExoPlayer a factory bound to one item. */
    class Factory(
        private val store: BlobStore,
        private val blobIds: List<ByteArray>,
        private val vmk: ByteArray,
        private val plaintextLength: Long,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            VaultDataSource(store, blobIds, vmk, plaintextLength)
    }

    companion object {
        /**
         * The player needs a URI even though nothing resolves it — the data
         * source ignores it entirely. Naming the scheme after the app makes it
         * obvious in a log that no file was involved.
         */
        fun uriFor(blobId: ByteArray): Uri =
            Uri.parse("vault://blob/" + blobId.joinToString("") { "%02x".format(it) })
    }
}
