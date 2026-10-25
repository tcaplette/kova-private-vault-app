package com.app.vault

import android.content.Context
import androidx.annotation.StringRes
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.SecureRandom

/**
 * Sealing one item into a share container — P4-2.
 *
 * The send screen's engine and nothing else: it decides what may be shared, seals
 * it, and cleans up after itself. Everything about the *bytes* is `ShareContainer`'s
 * (`FORMAT.md` §12); everything about the *screen* is `ShareScreen`'s.
 *
 * **Containers live in `filesDir/shares/`, which is why I9 needs no amendment.** The
 * app writes no ciphertext outside `filesDir`; the messenger reads through a content
 * URI served from there. `cacheDir` was rejected for exactly that reason — it is not
 * `filesDir`, and I9 names `filesDir`.
 *
 * **Nothing here stores a code.** The caller generates one, hands it over, shows it
 * once, and drops it. A stored code would put both halves of every share the user
 * ever sent on the sender's own device, which is the property a fresh random share
 * key exists to prevent.
 */
object ShareSend {

    /** Where containers live. Swept, never accumulated — [sweep]. */
    const val DIRECTORY = "shares"

    /**
     * A container's file name: eight random lowercase hex characters and `.bin`.
     *
     * `.bin` is the generic extension for binary data and the type travels as
     * `application/octet-stream`, so neither the name a messenger shows nor the type
     * it records says anything about this app — `FORMAT.md` §12 makes the bytes say
     * nothing either.
     */
    internal fun containerName(): String = ByteArray(4).also { rng.nextBytes(it) }.hex() + ".bin"

    /**
     * The video length cap, in seconds — P4-2's scope, not the format's.
     *
     * The container carries any size §12 allows. This is the "short video" the
     * feature was asked for, and it is also what keeps a container inside what a
     * messenger will move.
     */
    const val VIDEO_CAP_SECONDS = 60

    private val rng = SecureRandom()

    fun directory(context: Context): File = File(context.filesDir, DIRECTORY)

    /**
     * Why this item cannot be shared, or null if it can.
     *
     * A **sentence**, not a boolean, because the screen states the reason rather
     * than withholding the action silently — a control that vanishes teaches
     * nothing. The caller decides where a refusal is a missing row (the trash, where
     * the item is waiting to be restored or destroyed) and where it is a message (an
     * item that happens not to qualify today).
     *
     * **No kind is refused here any more.** It used to reject the file kind; every
     * kind the vault holds can now be sealed, so what is left are the two conditions
     * that are about an item's *state* rather than about what it is.
     */
    fun refusal(words: Words, item: VaultItem): String? = refusalOf(item)?.let { words.get(it, VIDEO_CAP_SECONDS) }

    @StringRes
    private fun refusalOf(item: VaultItem): Int? = when {
        item.deletedAt != null -> R.string.share_refused_trash
        item.kind != VaultKind.VIDEO -> null
        // Null is not "no limit": it means the container declared no duration and
        // the cap cannot be checked. Sharing it unchecked would be a silent hole in
        // the one limit this feature has, so it is refused with its reason.
        item.durationMs == null ->
            R.string.share_refused_no_length
        item.durationMs > VIDEO_CAP_SECONDS * 1000L ->
            R.string.share_refused_too_long
        else -> null
    }

    fun canShare(item: VaultItem): Boolean = refusalOf(item) == null

    /**
     * The §12 prelude kind for a vault item. Total — every kind the vault holds has
     * one.
     *
     * `AUDIO` is the voice memo: it is the kind behind the **Memos** destination, and
     * it is what this app calls a recording.
     *
     * **`FILE` was added after the first four.** It was left out when the feature was
     * built, on the reading that the four named kinds were the whole set; the
     * direction is that anything the vault holds can be shared, so the return is
     * total and this function no longer refuses a kind. Nothing on either side of
     * the wire needed teaching: the seal streams a blob whatever is in it, and the
     * receive path builds its row from the display name, which is where a vault item
     * gets its kind from in the first place.
     */
    fun kindOf(item: VaultItem): ShareContainer.Kind = when (item.kind) {
        VaultKind.IMAGE -> ShareContainer.Kind.IMAGE
        VaultKind.VIDEO -> ShareContainer.Kind.VIDEO
        VaultKind.NOTE -> ShareContainer.Kind.NOTE
        VaultKind.AUDIO -> ShareContainer.Kind.VOICE_MEMO
        VaultKind.FILE -> ShareContainer.Kind.FILE
    }

    /**
     * Seals [item] into a fresh container and returns the file.
     *
     * **Streaming, and no plaintext file anywhere** — I3. The item's bytes go from
     * `BlobStore.Reader` into the encryptor through a stream that exists only in
     * memory, exactly as the export path reads them, and only ciphertext is written.
     *
     * A failure removes its own partial before rethrowing: a half-written container
     * is a file with no owner, and [sweep] should not be the only thing that knows
     * about it.
     *
     * [onProgress] reports plaintext bytes consumed against the total, for the
     * screen's progress bar. Called on the calling thread, which is not the main one.
     */
    fun seal(
        context: Context,
        store: BlobStore,
        item: VaultItem,
        vmk: ByteArray,
        policy: ShareContainer.Policy,
        codeWords: List<String>,
        stripMetadata: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = sealInto(context, store, item, vmk, stripMetadata, onProgress) { input, out, prelude ->
        ShareContainer.sealWithCode(input, out, prelude, policy, codeWords)
    }

    /**
     * The same seal, to a paired contact's 1,216-byte X-Wing public key — P4-5, §12
     * contact mode.
     *
     * **No code exists on this path.** `ShareCode.generate` is not called and
     * nothing is produced for the sender to pass on: the recipient's own keypair is
     * the key, which is the whole of what pairing bought.
     *
     * The recipient's key is read here and not held. Nothing about who this went to
     * survives the call — §7.19's no-history rule, which contact mode does not get
     * an exception to.
     */
    fun sealToContact(
        context: Context,
        store: BlobStore,
        item: VaultItem,
        vmk: ByteArray,
        policy: ShareContainer.Policy,
        recipientPublicKey: ByteArray,
        stripMetadata: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = sealInto(context, store, item, vmk, stripMetadata, onProgress) { input, out, prelude ->
        ShareContainer.sealToRecipient(input, out, prelude, policy, recipientPublicKey)
    }

    /**
     * Everything the two modes share, which is everything but one call.
     *
     * The streaming, the progress, the neutral naming, the directory and the
     * delete-on-failure are P4-2's and do not fork: a second copy of this body is a
     * second place for the note-body rule or the plaintext accounting to drift.
     */
    private fun sealInto(
        context: Context,
        store: BlobStore,
        item: VaultItem,
        vmk: ByteArray,
        stripMetadata: Boolean,
        onProgress: (Long, Long) -> Unit,
        write: (java.io.InputStream, java.io.OutputStream, ShareContainer.Prelude) -> Unit,
    ): File {
        check(canShare(item)) { "this item cannot be shared" }

        val dir = directory(context).also { it.mkdirs() }
        val file = File(dir, containerName())

        // A note's bytes are its text, exactly as the export path writes them — the
        // note blob is a document format, not the file the recipient should receive.
        val body = item.body?.let { VaultNote.read(store, item, vmk) }
        val displayName = if (body != null) {
            item.filename.substringBeforeLast('.') + VaultNote.EXTENSION
        } else {
            item.filename
        }
        val total = body?.let { it.toByteArray(Charsets.UTF_8).size.toLong() }
            ?: item.plaintextLength

        try {
            val mime = ExportDestination.mimeOf(displayName)
            val strip = stripMetadata && ExifPolicy.strippable(mime)
            plaintext(store, item, vmk, body, total, onProgress).use { input ->
                file.outputStream().buffered().use { out ->
                    val prelude = ShareContainer.Prelude(
                        kind = kindOf(item),
                        displayName = displayName,
                        mime = mime,
                    )
                    // `exif-strip-choice`. The filter is another stream in a chain that was
                    // already all streams — I3 is untouched, and `total` above counts bytes
                    // read off the blob rather than bytes sealed, so the progress bar still
                    // reaches its end on a stripped share.
                    if (strip) {
                        // The install's one selection. Whether to strip is the share
                        // screen's question; what a strip removes is not asked again here.
                        val removed = ExifPolicy.removed(context)
                        Pipe.between({ sink -> ExifPolicy.copyStripped(input, sink, removed) }) {
                            write(it, out, prelude)
                        }
                    } else {
                        write(input, out, prelude)
                    }
                }
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return file
    }

    /**
     * The item's plaintext as a stream, counting as it goes.
     *
     * `BlobStore.Reader` is chunk-addressed rather than a stream — it is built for
     * seeking, which a player needs and this does not — so one adapter stands between
     * them. It holds one chunk, which is the memory ceiling for a share of any size.
     */
    private fun plaintext(
        store: BlobStore,
        item: VaultItem,
        vmk: ByteArray,
        body: String?,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ): InputStream {
        if (body != null) {
            onProgress(0, total)
            return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)).also {
                onProgress(total, total)
            }
        }

        val reader = store.reader(item.blobIds, vmk, item.plaintextLength)
        return object : InputStream() {
            private val buf = ByteArray(BlobStore.CHUNK)
            private var chunk = 0L
            private var length = 0
            private var offset = 0
            private var consumed = 0L

            private fun fill(): Boolean {
                if (offset < length) return true
                if (chunk >= reader.chunkCount) return false
                length = reader.readChunk(chunk, buf)
                offset = 0
                chunk++
                return length > 0
            }

            override fun read(): Int {
                if (!fill()) return -1
                consumed++
                onProgress(consumed, total)
                return buf[offset++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!fill()) return -1
                val n = minOf(len, length - offset)
                buf.copyInto(b, off, offset, offset + n)
                offset += n
                consumed += n
                onProgress(consumed, total)
                return n
            }

            override fun close() = reader.close()
        }
    }

    /**
     * Writes a sealed container to where the person chose in the system's save dialog —
     * the route that keeps the sender's identity from the app it is then sent with
     * (`FORMAT.md` §10 item 51). Ciphertext only, to a location the user picked, which is
     * the archive's own I9 exception; nothing is decrypted.
     */
    fun saveTo(context: Context, container: File, destination: android.net.Uri) {
        val out = checkNotNull(context.contentResolver.openOutputStream(destination, "w")) {
            "the chosen location could not be written"
        }
        out.use { sink -> container.inputStream().use { it.copyTo(sink) } }
    }

    /**
     * Empties `shares/`.
     *
     * **A sweep rather than a callback, because Android offers no callback.**
     * `ACTION_SEND` reports nothing about completion, and a messenger may open the
     * content URI after the sending activity has already returned — deleting on
     * return truncates real sends. So a container lives until the send screen is
     * left or the vault is next unlocked, whichever comes first, and the window is
     * bounded by one lock cycle.
     */
    fun sweep(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }
}
