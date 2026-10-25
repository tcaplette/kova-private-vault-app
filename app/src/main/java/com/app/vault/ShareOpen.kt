package com.app.vault

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Opening a received share container — P4-3, and `FORMAT.md` §12's far end.
 *
 * ***The opened item is never a plaintext file, and that is I3, not a preference.***
 * The obvious build — decrypt the container to a file and point the viewer at it —
 * writes user content in the clear to `filesDir`, which I3 permits only as the
 * direct output of a user-initiated export. So the payload streams out of the
 * container and straight into [BlobStore.write], sealed under the open vault's VMK
 * before it lands, and the viewer reads it back through the same encrypted path
 * every other item uses. Nothing in this flow ever holds a decrypted file.
 *
 * **That is also the real reason the flow needs a vault open**, stronger than the
 * one `DESIGN.md` §7.20 gives for the import action: without a VMK there is no
 * I3-legal place to put the bytes at all.
 *
 * A blob written this way carries **no index row** until the user keeps the item.
 * That is `ImportPipeline`'s own ordering — blob first, row second — so a share the
 * user looks at and walks away from is a blob that gets deleted, and never an item
 * that briefly existed in their vault.
 *
 * **Policy lives here because here is the only place it can live.** There is no
 * server, so single-view is single-view exactly insofar as the recipient's copy of
 * this app declines to open it twice — the same class of promise WhatsApp's
 * view-once makes, said on screen in those words. Every policy decision is made
 * **after** the container authenticates, because §12 v2 carries the policy inside
 * the encryption: it cannot be read at all until the chunk tag covering it has
 * verified it.
 */
object ShareOpen {

    /** Where a picked container is staged while it is being opened. Swept, never kept. */
    const val DIRECTORY = "incoming-shares"

    /**
     * The consumed-share ledger — 16-byte share IDs, appended, never pruned.
     *
     * **Plaintext, and outside any vault.** It has to outlive every vault on the
     * device: a share opened before a vault was deleted stays opened, and sealing it
     * under a VMK would mean deleting a vault silently made spent shares openable
     * again. `FORMAT.md` §10 records what its length gives away.
     */
    const val LEDGER = "shares-seen"

    fun directory(context: Context): File = File(context.filesDir, DIRECTORY)

    /** What a received name becomes when nothing usable is left of it. */
    internal const val UNNAMED = "Shared item"

    /**
     * A received display name, made safe to store, show and export.
     *
     * ***The declared name is the one field a hostile sender writes and the app
     * then uses.*** `FORMAT.md` §12 says a display name is never a path, but that is
     * a promise the *sender* makes; this is the check the receiver performs, and
     * until it existed the name went from the container into `VaultItem.filename`
     * untouched — into `ExportDestination.create` at unvault time, and onto the
     * screen where `invoice\u202Egpj.exe` reads as `invoice.jpg`.
     *
     * **Repaired, not refused**, which is the opposite of [VaultRename.normalise]'s
     * rule and for the reason that rule gives. Refusing is right for a name the user
     * *typed*: a repair there produces a name that looks like what they typed and is
     * not. Nobody typed this one, so there is no such name to be confused with — and
     * throwing away an authentic container because the sender's phone called the
     * file something odd would be a failure the recipient cannot act on. Android's
     * own download path makes the same choice for server-supplied names.
     *
     * Four steps, each answering something the sender could put in the field:
     *
     * 1. **The basename**, so `../../x` is `x`. Both separators, because the two
     *    export destinations mangle them differently and neither is asked to.
     * 2. **Control and format characters removed** — that is `Character.CONTROL` and
     *    `Character.FORMAT`, which is where every bidi override and isolate lives
     *    (`U+202E`, `U+2066`…) along with NUL and the zero-width marks. A name is a
     *    line of text on a screen; nothing in either category belongs in one.
     * 3. **Cut to fit**, keeping the extension, because the extension is the app's
     *    only type information — [VaultKind] reads it, and a photograph that lost
     *    its `.jpg` arrives as a file the viewer will not render.
     * 4. **[UNNAMED] when nothing survives**, so a received item always has a name.
     */
    internal fun safeName(raw: String): String {
        val basename = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = basename
            .filterNot {
                val type = Character.getType(it)
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
            }
            .trim()

        val (stem, extension) = VaultRename.split(cleaned)
        // A bare dot is not an extension, and one that alone exceeds the limit is
        // not one either; dropping it costs the kind and keeps the name, where the
        // alternative keeps neither.
        val suffix = extension.takeIf { it.length in 2 until VaultRename.MAX_NAME } ?: ""
        val room = VaultRename.MAX_NAME - suffix.length

        return VaultRename.normalise(stem.take(room), suffix)
            ?: VaultRename.normalise(UNNAMED, suffix)
            ?: UNNAMED
    }

    private fun ledger(context: Context): File = File(context.filesDir, LEDGER)

    /**
     * Why a container will not open, when the reason is not the single failure: the
     * container authenticated and is being declined.
     */
    sealed class Refusal {
        /**
         * Chunk 0 authenticated and the prelude names a newer format — §12's one
         * distinguishable refusal. Not a policy: nothing was opened or recorded.
         */
        object NewerVersion : Refusal()

        /** Already in the ledger — §12 policy mode 1. */
        object AlreadyOpened : Refusal()

        /** Past its instant — §12 policy mode 2. */
        class Expired(val atEpochMillis: Long) : Refusal()
    }

    sealed class Outcome {
        /**
         * The item, sealed under the open vault's VMK and **not yet in the index**.
         * [keep] adds the row; [discard] removes the blob.
         */
        class Opened(
            val details: ShareContainer.Opened,
            val blobId: ByteArray,
            val plaintextLength: Long,
        ) : Outcome() {
            /**
             * The name to store, show and export the item under — [safeName] of what
             * the container declared.
             *
             * **Computed here so there is no unsafe path to the name.** Every
             * consumer reads this; `details.displayName` stays as the wire's own
             * word, which is what the format layer is for, and nothing outside this
             * file reads it.
             */
            val filename: String = safeName(details.displayName)

            /**
             * Whether this may become an ordinary vault item — `share-keep-gate`.
             *
             * **The one definition, and the only place the policy is asked this
             * question.** The screen, `MainActivity.keepShare` and [keep] all read
             * this property; none of them restates the condition, because three
             * copies of it is three places for a limited share to become keepable
             * and the drift would be invisible — nothing on screen would look wrong
             * and nothing would throw.
             *
             * A limit is refused rather than carried because **the vault has no
             * concept of a limited item**. `VaultItem` has no policy field, no
             * expiry, and nothing sweeps it; keeping a single-view share would not
             * store a weaker limit, it would store none. So the answer is unmanaged
             * or nothing.
             */
            val canKeep: Boolean = details.policy is ShareContainer.Policy.Unmanaged
        }

        class Refused(val refusal: Refusal) : Outcome()
    }

    /**
     * Copies the picked document into [directory] so it can be read after the
     * picker's grant is gone. Ciphertext in, ciphertext out — a container is not
     * user content in the clear, and I3 does not reach it. The staged name says
     * nothing about what was picked, because nothing about a pick is known yet.
     */
    fun stage(context: Context, uri: Uri): File {
        val dir = directory(context).also { it.mkdirs() }
        val file = File(dir, "picked")
        context.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "the picked document could not be read" }
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    /**
     * Opens [container] under [codeWords], sealing the payload into [store].
     *
     * Throws `ShareContainer.ShareFailure` for every cryptographic outcome — §12 v2
     * has one. A newer format is [Outcome.Refused] with [Refusal.NewerVersion], and a
     * policy refusal is [Outcome.Refused] too: neither is a failure, both are a
     * container that authenticated and is being declined.
     *
     * **The ledger is written only on success, and only for a single-view
     * container.** Recording before the open would let a mistyped word spend a
     * share; recording an unmanaged or expiring one would grow a file that answers
     * no question.
     */
    fun open(
        context: Context,
        container: File,
        codeWords: List<String>,
        store: BlobStore,
        vmk: ByteArray,
    ): Outcome = openInto(context, container, store, vmk) { input, sink ->
        ShareContainer.openWithCode(input, sink, codeWords)
    }

    /**
     * The same open, with this install's X-Wing identity seed instead of four words —
     * P4-5, §12 contact mode.
     *
     * **Nothing is typed on this path.** The recipient's own key is what opens it,
     * which is the whole of what pairing bought; a container sealed to somebody
     * else fails here exactly as a wrong code does, and says as little.
     *
     * [seed] is zeroed by this function, on the failure path as well as the success
     * path — the convention every key in `VaultKeys` follows.
     */
    fun openWithIdentity(
        context: Context,
        container: File,
        seed: ByteArray,
        store: BlobStore,
        vmk: ByteArray,
    ): Outcome = try {
        openInto(context, container, store, vmk) { input, sink ->
            ShareContainer.openWithSeed(input, sink, seed)
        }
    } finally {
        seed.fill(0)
    }

    /**
     * Whether the staged file was sealed to this install — §12's probe, run before
     * the recipient is asked anything. Writes nothing; [seed] is zeroed here.
     */
    fun probe(container: File, seed: ByteArray): ShareContainer.Probe = try {
        container.inputStream().buffered().use { ShareContainer.probeContact(it, seed) }
    } finally {
        seed.fill(0)
    }

    /**
     * Everything the two modes share, which is everything but the key recovery.
     *
     * The pipe, the policy gate, the ledger and the blob write have **one call
     * site each** on purpose: two copies of this body is two places for the
     * single-view rule to drift, and the drift would be invisible until somebody's
     * view-once share opened twice.
     */
    private fun openInto(
        context: Context,
        container: File,
        store: BlobStore,
        vmk: ByteArray,
        produceInto: (java.io.InputStream, java.io.OutputStream) -> ShareContainer.Opened,
    ): Outcome {
        // The payload is sealed as it is decrypted — one stream, two ciphers, no
        // plaintext file between them. `BlobStore.write` consumes an InputStream,
        // and `ShareContainer` produces to an OutputStream, so a pipe is what joins
        // them; it is bounded by the blob store's own chunking.
        var details: ShareContainer.Opened? = null
        val written = try {
            Pipe.between(
                produce = { sink ->
                    details = container.inputStream().buffered().use { input ->
                        produceInto(input, sink)
                    }
                },
                consume = { source -> store.write(source, vmk) },
            )
        } catch (_: ShareContainer.NewerShareVersion) {
            // Thrown after chunk 0 authenticated and before a byte of content
            // reached the sink — the same path every failure takes. No row, no
            // ledger entry: nothing is recorded and nothing is kept.
            return Outcome.Refused(Refusal.NewerVersion)
        }
        val opened = details ?: throw ShareContainer.ShareFailure()

        val policy = opened.policy
        val refusal = when {
            policy is ShareContainer.Policy.SingleView && isSpent(context, opened.shareId) ->
                Refusal.AlreadyOpened
            policy is ShareContainer.Policy.Expiry &&
                System.currentTimeMillis() >= policy.atEpochMillis ->
                Refusal.Expired(policy.atEpochMillis)
            else -> null
        }
        if (refusal != null) {
            // The blob was written to find out the container authenticated. It is
            // removed here rather than left for a sweep: nothing outside this
            // function knows it exists, so nothing else could clean it up.
            store.delete(written.blobId)
            return Outcome.Refused(refusal)
        }

        if (policy is ShareContainer.Policy.SingleView) spend(context, opened.shareId)
        return Outcome.Opened(opened, written.blobId, written.plaintextLength)
    }

    /**
     * Adds the index row that turns an opened share into an ordinary vault item.
     *
     * Blob first, row second — `ImportPipeline`'s ordering, so a share the user
     * walks away from is a blob to delete rather than an item that briefly existed.
     *
     * **A limited container is refused here, not kept with its limit attached** —
     * `share-keep-gate`. The vault has no concept of a limited item: `VaultItem`
     * carries no policy, no expiry, and nothing sweeps it. Keeping a single-view
     * share would not store a weaker limit, it would store none — the row would be
     * indistinguishable from an ordinary import, re-shareable under any policy the
     * recipient picks and exportable in the clear. So the only honest answers are
     * unmanaged or refuse, and this refuses.
     *
     * **The screen already hides the action, and this still checks.** A gate that
     * lives only in a composable is correct until a second call site appears, and
     * the bug that follows is silent: a share that was meant to be gone sits in a
     * vault with nothing on screen looking wrong and nothing throwing. One line
     * turns that into a crash during development. `ShareSend.seal` opens with the
     * same `check` against `canShare` for the same reason.
     */
    fun keep(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        outcome: Outcome.Opened,
    ) {
        check(outcome.canKeep) { "a share carrying a viewing limit cannot be kept" }
        MetadataDb.commit(store, indexId, vmk) { db ->
            db.items += VaultItem(
                blobIds = listOf(outcome.blobId),
                plaintextLength = outcome.plaintextLength,
                thumb = null,
                filename = outcome.filename,
                album = null,
                // The sender's clock is not evidence about when this was taken, and
                // a received item has no capture date of its own — `createdAt`'s
                // rule is that absent means absent.
                createdAt = null,
                importedAt = System.currentTimeMillis(),
                tags = emptyList(),
            )
        }
    }

    /** Removes an opened share's blob. Used when the user leaves without keeping it. */
    fun discard(store: BlobStore, outcome: Outcome.Opened) {
        store.delete(outcome.blobId)
    }

    /** Whether this device has already opened the single-view share [shareId]. */
    fun isSpent(context: Context, shareId: ByteArray): Boolean {
        val file = ledger(context)
        if (!file.isFile) return false
        val bytes = file.readBytes()
        var offset = 0
        while (offset + ShareContainer.SHARE_ID_BYTES <= bytes.size) {
            if (bytes.copyOfRange(offset, offset + ShareContainer.SHARE_ID_BYTES)
                    .contentEquals(shareId)
            ) {
                return true
            }
            offset += ShareContainer.SHARE_ID_BYTES
        }
        return false
    }

    /**
     * Records [shareId] as spent. Append-only and **never pruned** — a pruning rule
     * is a way for a spent share to become openable again, which is the one thing
     * this file exists to prevent. 16 bytes per view-once share reaches a kilobyte
     * after sixty of them.
     */
    private fun spend(context: Context, shareId: ByteArray) {
        require(shareId.size == ShareContainer.SHARE_ID_BYTES) { "a share ID is 16 bytes" }
        if (isSpent(context, shareId)) return
        java.io.FileOutputStream(ledger(context), true).use { it.write(shareId) }
    }

    /**
     * Empties the staging directory — the receiving half of P4-2's sweep, for its
     * reason: nothing about a share should outlive the flow that opened it. The
     * ledger is **not** touched.
     */
    fun sweep(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }
}
