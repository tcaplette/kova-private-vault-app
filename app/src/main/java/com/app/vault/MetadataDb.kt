package com.app.vault

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Where a thumbnail lives — `FORMAT.md` §8.
 *
 * A thumbnail is a byte range inside a pack, not a blob of its own, so a blob ID
 * alone cannot address it. Packing exists because §5's smallest bucket is 64 KiB
 * and a thumbnail is ~20 kB: one blob each would spend 656 MB on a 10 000-item
 * library.
 */
/**
 * One thumbnail pack and how much plaintext it holds — `FORMAT.md` §8.
 *
 * The length is not optional bookkeeping. `BlobStore.Reader` sizes the final
 * chunk from the plaintext length, so reading *any* record out of a pack needs
 * the pack's true length; a reader told only "up to the end of my record" would
 * under-read the sealed chunk and fail authentication. The last entry is the
 * open pack; every earlier one is closed and never written again.
 */
class ThumbPack(val id: ByteArray, var plaintextLength: Int)

class ThumbRef(
    val packBlobId: ByteArray,
    val offset: Int,
    val length: Int
) {
    init {
        require(packBlobId.size == BlobStore.BLOB_ID_BYTES) { "pack blob ID must be 16 bytes" }
        require(offset >= 0 && length > 0) { "thumbnail range must be positive" }
    }
}

/** Lowercase hex digits, for [VaultItem.itemId] and [VaultFolder.id]. */
private val HEX = "0123456789abcdef".toCharArray()

/**
 * A 16-byte identity as a map key — `folder-system`.
 *
 * ***Not the hex string.*** Every per-item path in [VaultFolders] asks "which folder
 * is this in", and keying that by [hex] builds a `CharArray` and a 32-character
 * `String` **per item per pass** — on every grid recomposition and every keystroke of
 * a search. Measured on a Pixel_8 AVD over 5 000 items: a two-term query took
 * **20.3 ms**, which `SearchTest` fails as visible between keystrokes. This is the
 * same trap P2-16 found in `itemId`, one field along.
 *
 * The hash is taken once at construction, so a lookup costs a 16-byte compare and no
 * allocation beyond this object. Exact, not probabilistic: comparing a prefix as a
 * `Long` would be cheaper still and would put items in the wrong folder on a
 * collision, however rare — and "rare" is not a property worth having here.
 */
internal class IdKey(private val bytes: ByteArray) {
    private val hash = bytes.contentHashCode()
    override fun hashCode(): Int = hash
    override fun equals(other: Any?): Boolean =
        other is IdKey && bytes.contentEquals(other.bytes)
}

/**
 * [bytes] as lowercase hex.
 *
 * By table lookup and not `String.format` per byte — P2-16 measured that at 13.9 s
 * for a 5 000-item name ordering, because every comparator ends in [VaultItem.itemId].
 */
internal fun hex(bytes: ByteArray): String {
    val out = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}

/** One stored item. The first three fields are what `FORMAT.md` §7 requires. */
class VaultItem(
    /**
     * The item's blobs, in order — `FORMAT.md` §7.
     *
     * An item larger than [BlobStore.SPLIT_PLAINTEXT] spans several; blob *k*
     * holds the plaintext at offset *k × split*. A smaller item is a list of one,
     * which is what nearly every photograph is.
     *
     * **The order exists here and nowhere else.** No blob names another, so this
     * list is the only thing that reassembles the item — and losing it is losing
     * the item, which is why the archive enumerates every entry (§11).
     */
    val blobIds: List<ByteArray>,
    val plaintextLength: Long,
    val thumb: ThumbRef?,
    val filename: String,
    val album: String?,
    /** Null when the source offered no date. Never the import time — see P1-6. */
    val createdAt: Long?,
    val importedAt: Long,
    val tags: List<String>,
    /** True only after the stored blob was decrypted and matched the source (I6, P1-7). */
    val deletable: Boolean = false,
    /**
     * Video length in milliseconds — P2-8. **Null means "not known"**, exactly as
     * [createdAt] means "the source offered no date". Not zero, and not a
     * sentinel: the grid draws no duration label at all when this is null, and a
     * placeholder standing in for a value the app does not have reads to the user
     * as a broken app.
     *
     * Null for every image, and for any video imported before P2-8 or whose
     * container declared no duration.
     *
     * The value is not new work. `Thumbnailer.chooseFrame` has always extracted
     * `METADATA_KEY_DURATION` to pick which frame to sample; until P2-8 it threw
     * the number away.
     */
    val durationMs: Long? = null,
    /**
     * When this item was deleted, or null while it is live — P2-12.
     *
     * A trashed item is an ordinary row in every other respect: its blobs are
     * still referenced, still archived by nothing that sweeps, and still readable.
     * It leaves the grid, the viewer's paging order and the export walk, and it is
     * destroyed when [TrashPurge] finds it older than [TrashPurge.WINDOW_MS] or
     * the user destroys it from the trash.
     *
     * **This is not [deletable].** That flag means "the `MediaStore` source may be
     * deleted" (I6) and is about a file outside the vault entirely. Nothing reads
     * one where it means the other; `ItemTrashTest` asserts both survive a
     * trash-and-restore independently.
     */
    val deletedAt: Long? = null,
    /**
     * A note's text, or null on every item whose content is bytes — P2-21.
     *
     * **The body is the content.** A note has no blobs at all: its text lives in
     * this row, inside the metadata blob, which is why the feature costs no
     * envelope, no bucket, no chunking and no import pipeline. It is also why
     * `FORMAT.md` §10(38) exists — note text grows the one blob repeated snapshots
     * already identify (§10(8)).
     *
     * Bounded at [VaultNote.MAX_BODY_BYTES], because the index is an ordinary
     * pooled blob under §5 and is capped at 16 MiB like any other. A body is the
     * only field in this app that can grow it without bound.
     *
     * **Invariant: non-null exactly when [noteId] is.** Enforced where a note is
     * built ([VaultNote.create]), on load ([parse]), and by [itemId], which throws
     * rather than inventing an identity for a row that has neither.
     */
    val body: String? = null,
    /**
     * A note's identity — 16 random bytes, P2-21. Null on every other row.
     *
     * [itemId] is the first blob's ID in hex, and a note has no first blob. This
     * file's KDoc argues against a second identifier on the grounds that it is "a
     * second thing to keep consistent with the blob list" — **that argument does
     * not reach a note**, which has no blob list for it to disagree with. It is not
     * a second identity here; it is the only one.
     *
     * Drawn once at creation and carried through every [withBody], [withFilename],
     * [withAlbum] and [withDeletedAt]: an edit that changed it would deselect the
     * note, move it in the ordering and lose the viewer's position.
     *
     * Same width and same CSPRNG as a blob ID, so [itemId]'s hex is the same shape
     * and the same collision odds it has always been.
     */
    val noteId: ByteArray? = null,
    /**
     * The §5 envelope this item's blobs were written under — P2-25. Null means the
     * original envelope, which is every blob that exists today.
     *
     * **Nothing reads this yet, and that is deliberate.** The envelope has never
     * changed and may never, but it carries no header of its own — a header would
     * defeat the bucket padding §10(3) depends on — so a change to the chunk size
     * or the nonce construction would be undetectable in the blob itself. The
     * discriminator has to live here, and it has to be **per row**: old blobs would
     * stay on the original envelope while new ones were written under the new one
     * *in the same store*, so an index-level field would claim one envelope for a
     * store that has two, and the index version cannot stand in either — a blob
     * outlives the index that recorded it.
     *
     * Added now because after the first publish there is no install left to add it
     * to. It is a reserved discriminator, not dead weight: deleting it costs one
     * byte per row today and costs the ability to ever change the envelope.
     */
    val envelopeVersion: Int? = null,
    /**
     * The folder this item is in, or null for the home screen — `folder-system`.
     *
     * **A reference to a folder's identity, not its name.** P2-13 stored the name
     * here and derived the album list from it, which is why an album could not
     * exist before something carried its name and why renaming one would have had
     * to rewrite every item in it. A folder's existence is a record now
     * ([VaultFolder]), so this is 16 bytes pointing at one and a rename touches
     * nothing here.
     *
     * **A dangling reference reads as the home screen**, and that is deliberate:
     * an item is never invisible because a folder went away. `VaultFolders.delete`
     * reparents in the same commit that removes the record, so nothing should
     * dangle — this is what happens if something ever does.
     *
     * [album] is what this replaced. It is still parsed and still written, because
     * clearing a field the app can ignore means rewriting every row in the vault
     * for no gain, but nothing reads it after [migrateAlbums].
     */
    val folderId: ByteArray? = null,
    /**
     * How far this item is turned for display, clockwise — `viewer-chrome-and-editor`.
     *
     * One of 0, 90, 180 or 270, and **0 for every row written before this field
     * existed**, which is what absent has always meant here ([durationMs]'s rule,
     * one field along).
     *
     * **A rotation is a number, and storing it is the whole of turning an item.**
     * Nothing about the bytes changes: no blob is re-encoded, no envelope rewritten,
     * no identifier redrawn. `VaultCamera` already refuses to rotate pixels for the
     * cost — a generation of lossy quality for a value that fits in a header — and
     * this is the same refusal on the reading side. Patching the EXIF tag inside the
     * blob would be the same cost with none of the honesty: it edits the file the
     * user imported.
     *
     * **This is not the angle the item is drawn at.** That is this plus whatever the
     * item's own bytes say (`Thumbnailer.exifDegrees`), modulo 360 — so an imported
     * photograph starts upright and turns from there, which is what a rotate control
     * has to mean. The composition happens at the two decode sites and at export;
     * nothing here knows about EXIF.
     */
    val rotation: Int = 0,

    /**
     * A note's opening characters, for the grid — `notes-as-documents`.
     *
     * **This is the reason a note's text can leave the index.** A tile has to show
     * what the note says, and the index is the only thing already in memory when the
     * grid draws. Without a preview here, a screen of notes would decrypt every one
     * of them to draw itself.
     *
     * Null for anything that is not a note.
     */
    val preview: String? = null,

    /** Sorts ahead of everything else in the grid — `notes-as-documents`. */
    val pinned: Boolean = false,

    /**
     * A note's colour, from the palette folders already use.
     *
     * One palette and not a second: a separate set of swatches for notes would be
     * the same decision made twice. It tints the tile and never the editor — the
     * folder fills were derived to sit *below* a tile with no text on them, so their
     * contrast against body copy was never computed (DESIGN.md 4.1).
     */
    val colour: FolderColour = FolderColour.None,

    /**
     * ***Retained in the format, and read by nothing — `remove-note-attachments`.***
     *
     * This was v12's owning-note reference: "the whole of what makes it an attachment
     * rather than a photograph". Notes no longer carry attachments, so nothing consults
     * it — not the grid, not the trash, not search, not deletion, not export. An item
     * that still records an owner is an **ordinary item** and appears everywhere any
     * other item appears.
     *
     * **It is still parsed and still written back**, unchanged, so an index round trip
     * does not lose it. Removing it from the format would mean bumping the index version
     * and migrating every vault to express the absence of a value nothing reads — work
     * with no outcome. An index written by this build never sets it.
     */
    val ownerNoteId: ByteArray? = null,

    /**
     * Where this video's playback stopped, in milliseconds from its start —
     * `viewer-swipe-navigation`, index version 14.
     *
     * **Null means "from the beginning"**, [durationMs]'s rule one field along: absent on
     * every row written before the field existed and on every item never stopped part-way.
     * [VideoResume.keep] decides what is worth keeping; this only holds the answer.
     *
     * On the row rather than in a table beside it, so it goes wherever the item goes —
     * trash, rename, move, archive — with nothing to keep in step and nothing to prune
     * when the item is destroyed.
     */
    val resumeMs: Long? = null,

    /**
     * When a note's title or body was last saved by an edit — `destination-settings-and-slideshow`
     * D2, index version 16.
     *
     * **Null until the first edit**, [durationMs]'s rule: a note created and never edited has no
     * edit time, and Last edited orders it by [importedAt] instead. Colour, pin and folder are
     * not edits and carry this through unchanged.
     */
    val editedAt: Long? = null,
) {
    /**
     * The item's identity for UI keys, skip lists and equality — the first blob's
     * ID in hex.
     *
     * Named `itemId` and not `itemKey` because `KeyMaterialScanTest` forbids a
     * `String`-typed anything whose name contains "key", and it is right to: it
     * cannot tell a public identifier from a DEK, and the day it has to guess is
     * the day it stops catching a real one. Renaming was cheaper than an
     * exemption.
     *
     * The first blob is as good an identity as any: IDs are random and an item
     * never loses its first blob without losing the item. It is deliberately not
     * a new field, because a second identifier in the index is a second thing to
     * keep consistent with the blob list.
     */
    /**
     * The folder this item is in, as a map key — [IdKey].
     *
     * Computed once per row rather than per lookup: the level query and the search
     * both ask this of every item on every pass, and doing it by [hex] there is the
     * 20 ms [IdKey] documents.
     */
    internal val folderKey: IdKey? = folderId?.let { IdKey(it) }

    val itemId: String get() {
        // Hex by table lookup, not by `String.format` per byte — see [hex]. P2-16.
        //
        // This used to be `joinToString("") { "%02x".format(it) }`, which is
        // sixteen `Formatter` allocations and parses of "%02x" every time the
        // property is read. Nothing minded while it was read once per tile, and
        // then P2-16 put it in a comparator: ordering 5 000 items took **13.9 s**
        // on a Pixel_8 AVD. The output is identical — lowercase, zero-padded,
        // two characters per byte, which `SortOrderTest` asserts against the old
        // expression across every byte value including negative ones.
        // P2-21: a note has no first blob, so its 16-byte `noteId` stands in. The
        // `error` is the invariant's enforcement and not a defensive default — a
        // row with neither must fail loudly rather than hand back a plausible hex
        // string that nothing else in the app could tell from a real one.
        // `notes-as-documents` put the note's text in a blob, so a note now has
        // one — and reading the blob first would change every existing note's
        // identity the moment it migrated. The note ID comes first for that reason:
        // it is the thing that was always stable, and `VaultNote.update` matches on
        // it. For everything else there is no note ID and the first blob stands, as
        // it always has.
        val bytes = noteId
            ?: blobIds.firstOrNull()
            ?: error("item has neither a blob nor a note ID")
        return hex(bytes)
    }

    /**
     * What this item is — P2-21.
     *
     * **The structural check is first, deliberately.** An item carrying a [body] is
     * a note whatever its filename ends in. Deciding it from the extension instead
     * would mean renaming a note to `list.txt` silently converting it to a file
     * whose bytes do not exist — the outcome P2-14 fixed the extension in place to
     * prevent, arriving through the other door.
     *
     * This does **not** reintroduce the stored kind field `item-kinds` rejected. A
     * stored kind is a second source of truth about the same fact, which has to
     * agree with the filename through every rename and every archive round trip.
     * A body is not a claim about the item; it *is* the item.
     *
     * The four content kinds still come from [VaultKind.kindOf] and from exactly
     * the extension sets that shipped, so nothing already in a vault changes kind.
     */
    val kind: VaultKind get() =
        // A note used to be recognised by carrying a body. `notes-as-documents`
        // moved the text into a blob, so the note ID is what says so now — it is
        // present on every note ever written, before the migration and after it.
        if (noteId != null) VaultKind.NOTE else VaultKind.kindOf(filename)

    /** Same item with its thumbnail reference filled in (P1-8), and P2-8's duration. */
    fun withThumb(ref: ThumbRef, duration: Long? = durationMs) = VaultItem(
        blobIds, plaintextLength, ref, filename, album, createdAt, importedAt, tags, deletable,
        duration, deletedAt, body, noteId, envelopeVersion, folderId, rotation,
        preview, pinned, colour, ownerNoteId, resumeMs, editedAt
    )

    /**
     * Same item under [filename] — P2-14.
     *
     * A replacement rather than a mutation, like [withThumb] and [withAlbum]: the
     * rows are immutable, so a rename is a new row put back at the same index
     * inside the commit that renamed it.
     *
     * Only the name moves. The blob list, the length, the `ThumbRef` and the dates
     * are the ones passed through — the extension is preserved by
     * [VaultRename.normalise], so the stored thumbnail is still the right kind of
     * thumbnail and nothing needs regenerating.
     */
    fun withFilename(filename: String) = VaultItem(
        blobIds, plaintextLength, thumb, filename, album, createdAt, importedAt, tags, deletable,
        durationMs, deletedAt, body, noteId, envelopeVersion, folderId, rotation,
        preview, pinned, colour, ownerNoteId, resumeMs, editedAt
    )

    /**
     * Same item in [album], or in none when it is null — P2-13.
     *
     * A replacement rather than a mutation, like [withThumb] and [withDeletedAt]:
     * the rows are immutable, so a move is a new row put back at the same index
     * inside the commit that moved it.
     */
    fun withAlbum(album: String?) = VaultItem(
        blobIds, plaintextLength, thumb, filename, album, createdAt, importedAt, tags, deletable,
        durationMs, deletedAt, body, noteId, envelopeVersion, folderId, rotation,
        preview, pinned, colour, ownerNoteId, resumeMs, editedAt
    )

    /**
     * Same item in the folder [folderId] names, or on the home screen when it is
     * null — `folder-system`.
     *
     * A replacement rather than a mutation, like [withAlbum], which it replaces:
     * the rows are immutable, so a move is a new row put back at the same index
     * inside the commit that moved it. Nothing but the reference changes — not the
     * blobs, the thumbnail, the filename, the dates, the tags or the trash stamp,
     * which is `album-organisation`'s written requirement.
     */
    fun withFolder(folderId: ByteArray?) = VaultItem(
        blobIds, plaintextLength, thumb, filename, album, createdAt, importedAt, tags, deletable,
        durationMs, deletedAt, body, noteId, envelopeVersion, folderId, rotation,
        preview, pinned, colour, ownerNoteId, resumeMs, editedAt
    )

    /**
     * Same item, trashed at [at] or restored when [at] is null — P2-12.
     *
     * A replacement rather than a mutation, like [withThumb]: the rows are
     * immutable, so a stamp is a new row put back at the same index inside the
     * commit that stamped it.
     */
    fun withDeletedAt(at: Long?) = VaultItem(
        blobIds, plaintextLength, thumb, filename, album, createdAt, importedAt, tags, deletable,
        durationMs, at, body, noteId, envelopeVersion, folderId, rotation,
        preview, pinned, colour, ownerNoteId, resumeMs, editedAt
    )

    /**
     * Same item turned to [rotation] — `viewer-chrome-and-editor`.
     *
     * A replacement rather than a mutation, like [withFolder] and [withDeletedAt]:
     * the rows are immutable, so a turn is a new row put back at the same index
     * inside the commit that turned it.
     *
     * **Nothing but the angle changes** — not the blob list, the length, the
     * thumbnail reference, the filename, the dates, the tags or the trash stamp.
     * That is the whole claim a stored rotation makes: turning an item is a write to
     * the index and to nothing else.
     */
    fun withRotation(rotation: Int) = VaultItem(
        blobIds, plaintextLength, thumb, filename, album, createdAt, importedAt, tags, deletable,
        durationMs, deletedAt, body, noteId, envelopeVersion, folderId, rotation,
        preview, pinned, colour, ownerNoteId, resumeMs, editedAt
    )

    /**
     * Same note under [filename] with [body] — P2-21.
     *
     * A replacement rather than a mutation, like [withFilename] and [withAlbum]:
     * the rows are immutable, so an edit is a new row put back at the same index
     * inside the commit that edited it.
     *
     * **[noteId] is carried, never redrawn.** It is this item's identity: a fresh
     * one would deselect the note, move it under P2-16's `itemId` tiebreaker and
     * lose the viewer's position — an edit would read as a delete and an insert.
     *
     * [plaintextLength] follows the body's encoded size, so export's existing
     * length check stays a real check rather than a skipped branch.
     */
    /**
     * The same note with its text moved into [blobIds] — `notes-as-documents`.
     *
     * `body` is cleared and [preview] takes its place: from here the index says what
     * the note opens with and the blob holds the rest. [noteId] is carried through
     * untouched, which is what keeps the note's identity across the move — it is what
     * `itemId` reads, and what `VaultNote.update` matches on.
     */
    fun withNoteBlob(blobIds: List<ByteArray>, preview: String, length: Long = plaintextLength) =
        VaultItem(
            blobIds, length, thumb, filename, album, createdAt, importedAt, tags, deletable,
            durationMs, deletedAt, null, noteId, envelopeVersion, folderId, rotation,
            preview, pinned, colour, ownerNoteId, resumeMs, editedAt
        )

    /** Same item in [colour] — `notes-as-documents`. Tints the tile, never the editor. */
    fun withColour(colour: FolderColour) = VaultItem(
        blobIds, plaintextLength, thumb, filename, album, createdAt, importedAt, tags, deletable,
        durationMs, deletedAt, body, noteId, envelopeVersion, folderId, rotation,
        preview, pinned, colour, ownerNoteId, resumeMs, editedAt
    )

    /** Same item pinned or unpinned — `notes-as-documents`. */
    fun withPinned(pinned: Boolean) = VaultItem(
        blobIds, plaintextLength, thumb, filename, album, createdAt, importedAt, tags, deletable,
        durationMs, deletedAt, body, noteId, envelopeVersion, folderId, rotation,
        preview, pinned, colour, ownerNoteId, resumeMs, editedAt
    )

    /** Same note with a new preview — `notes-as-documents`. */
    fun withPreview(preview: String?) = VaultItem(
        blobIds, plaintextLength, thumb, filename, album, createdAt, importedAt, tags, deletable,
        durationMs, deletedAt, body, noteId, envelopeVersion, folderId, rotation,
        preview, pinned, colour, ownerNoteId, resumeMs, editedAt
    )

    fun withBody(filename: String, body: String) = VaultItem(
        blobIds, body.toByteArray(Charsets.UTF_8).size.toLong(), thumb, filename, album, createdAt,
        importedAt, tags, deletable, durationMs, deletedAt, body, noteId, envelopeVersion, folderId,
        rotation, preview, pinned, colour, ownerNoteId, resumeMs, editedAt
    )

    /**
     * Same item stopped at [resumeMs], or starting over when it is null —
     * `viewer-swipe-navigation`.
     *
     * **Nothing but the stopping point changes** — not the blobs, the length, the
     * thumbnail reference, the filename, the dates, the rotation or the folder. Watching a
     * video is a write to one field of its row and to nothing else.
     */
    /** Same note, edited at [at] — `destination-settings-and-slideshow` D2. Nothing else moves. */
    fun withEditedAt(at: Long?) = VaultItem(
        blobIds, plaintextLength, thumb, filename, album, createdAt, importedAt, tags, deletable,
        durationMs, deletedAt, body, noteId, envelopeVersion, folderId, rotation,
        preview, pinned, colour, ownerNoteId, resumeMs, at
    )

    fun withResume(resumeMs: Long?) = VaultItem(
        blobIds, plaintextLength, thumb, filename, album, createdAt, importedAt, tags, deletable,
        durationMs, deletedAt, body, noteId, envelopeVersion, folderId, rotation,
        preview, pinned, colour, ownerNoteId, resumeMs, editedAt
    )
}

/**
 * One stored folder — `folder-system`.
 *
 * **A folder exists because there is a record of it**, not because something is in
 * it. That is the whole change: P2-13 derived albums from the items, so an empty one
 * could not survive a redraw and a folder could not be made before it had contents.
 *
 * **[folderId] is 16 random bytes and never derived from the name.** A path string
 * would make a rename an O(n) rewrite of every descendant and every item inside them,
 * and would need a separator that user-typed names must not contain — and the names
 * already in vaults are arbitrary text. With an identity, a rename writes one record
 * and a move writes one field. Same width and same source as [VaultItem.noteId].
 *
 * **[parentId] is the nesting.** Null is the top level — the home screen. A folder
 * whose parent names no record, or whose parent chain does not terminate, is read as
 * top level rather than thrown out: the folders and everything in them stay reachable,
 * which is the same call [VaultItem.folderId] makes for a dangling reference.
 */
class VaultFolder(
    val folderId: ByteArray,
    /** The folder this one sits in. Null is the top level. */
    val parentId: ByteArray?,
    /** What the user typed. Trimmed, non-empty, and unique among its siblings. */
    val name: String,
    /** Drawn on the tile. Never the only thing identifying the folder — §6.4. */
    val colour: FolderColour = FolderColour.None,
    /**
     * An item within this folder, chosen by the user, drawn as the tile's image.
     *
     * Null is not "no picture": [VaultFolders.coverFor] falls back to the newest
     * live item within the folder or anything inside it. The stored choice is left
     * alone when its item is trashed, so restoring the item restores the cover.
     */
    val coverItemId: ByteArray? = null,
    /**
     * The destination this folder was made in — it is listed there and nowhere else.
     * Null is unscoped: a folder written before this field existed, and the captures
     * folder, are listed in every destination as they always were.
     */
    val destination: VaultDestination? = null,
) {
    /** Same folder under [name] — the rows are immutable, as [VaultItem]'s are. */
    fun withName(name: String) = VaultFolder(folderId, parentId, name, colour, coverItemId, destination)

    /** Same folder under [parentId]. The one field a move writes. */
    fun withParent(parentId: ByteArray?) = VaultFolder(folderId, parentId, name, colour, coverItemId, destination)

    /** Same folder in [colour]. */
    fun withColour(colour: FolderColour) = VaultFolder(folderId, parentId, name, colour, coverItemId, destination)

    /** Same folder showing [coverItemId], or falling back when it is null. */
    fun withCover(coverItemId: ByteArray?) =
        VaultFolder(folderId, parentId, name, colour, coverItemId, destination)

    /** The identity in hex, for UI keys and comparison — [VaultItem.itemId]'s shape. */
    val id: String get() = hex(folderId)

    /** This folder's identity as a map key — [IdKey], computed once. */
    internal val key: IdKey = IdKey(folderId)

    /** The folder holding this one, as a map key. Null at the top level. */
    internal val parentKey: IdKey? = parentId?.let { IdKey(it) }
}

/**
 * A vault enrolled in intruder capture — `per-vault-intruder-capture` D2. Held only in a
 * **keyring's** capture store, keyed by the vault's [indexBlobId], which the keyring entry carries
 * outside its sealed key: a wrong passphrase on the vault's sheet is captured with the keyring
 * open and the vault locked. [publicKey] is the vault capture store's capture public key.
 */
class VaultEnrolment(val indexBlobId: ByteArray, val publicKey: ByteArray, val modes: Set<Capture.Mode>)

/** A capture sealed to an enrolled vault, queued until it opens — D2, D3. */
class VaultPending(val indexBlobId: ByteArray, val mode: Capture.Mode, val blobId: ByteArray, val sealedLength: Int)

/**
 * A permission change queued for an enrolled vault, copied into its timeline as a `0x48` at its
 * next opening — D8. [permission] and [state] are `TamperLog`'s `PERMISSION_*`; [at] is when the
 * app noticed.
 */
class VaultNotice(val indexBlobId: ByteArray, val permission: Int, val state: Int, val at: Long)

/**
 * The vault's index — `FORMAT.md` §7.
 *
 * Not SQLCipher and not Room. §7 puts the index in the pooled blob store, SQLite
 * cannot operate on a file inside the blob envelope without decrypting it to disk
 * (I3), and once the whole file is rewritten per save, SQL's remaining advantages
 * are unusable. What is left is a serialised in-memory index.
 *
 * Mutated in memory, written only by [save]. A 500-item import pays for one write,
 * not 500.
 */
class MetadataDb private constructor(
    val items: MutableList<VaultItem>,
    /** Chaff this vault owns. `BlobStore.refreshChaff` only touches what it is given (§6). */
    val ownedChaff: MutableList<ByteArray>,
    /**
     * Every thumbnail pack this vault owns, in creation order (§8). The last is
     * the open one. Empty before the first thumbnail exists.
     */
    val thumbPacks: MutableList<ThumbPack> = mutableListOf(),
    /**
     * This vault's tamper log (§9). A blob, not a file with a name: a fixed
     * per-vault filename would make the number of log files the number of vaults.
     * Changes only when the log grows to the next bucket.
     */
    var logBlobId: ByteArray? = null,
    /**
     * Every folder in this vault — `folder-system`. Empty in a vault that has none,
     * which is every vault written before index version 9.
     *
     * Mutable for [items]' reason: the index is mutated in memory and written by
     * [save], so a folder created, renamed, moved, coloured or deleted is a change
     * to this list inside the commit that made it.
     */
    val folders: MutableList<VaultFolder> = mutableListOf(),
    /**
     * The folder this vault mirrors into, as a tree URI string, or null where
     * backup was never turned on — `settings-separation`.
     *
     * **In the index rather than in `SharedPreferences`.** A prefs file with one
     * key per vault says how many vaults exist to anyone who reads it, which is the
     * one fact I8 protects. Inside the index, a vault's destination is knowable only
     * while that vault is open — which is also what makes mirroring something an
     * open vault does rather than something the install does.
     *
     * A `String` and not a `Uri`: this file is parsed and serialised on the JVM
     * without Android, and `IndexVersionTest` runs there.
     *
     * `var` for [logBlobId]'s reason — it is mutated inside a commit, and the write
     * rides out with the index that commit saves.
     */
    var mirrorDestination: String? = null,
    /**
     * When the newest intruder capture the user has actually looked at arrived, or
     * null while none has been — `intruder-capture-record`.
     *
     * **A high-water mark and not a flag.** A boolean needs a writer at both ends and
     * goes wrong when a capture lands while the folder is open; a mark is idempotent
     * and takes one write. The badge shows while any capture in the folder is newer
     * than this.
     *
     * **In the index and not in preferences.** That a vault is holding unviewed
     * intruder captures is a fact about its contents, and preferences are readable
     * with `adb run-as` — the argument [WorkJobs] makes at length about the same class
     * of fact. Here it is under the vault's own key.
     *
     * `var` for [logBlobId]'s reason — it is mutated inside a commit, and the write
     * rides out with the index that commit saves.
     */
    var capturesSeenAt: Long? = null,
    /**
     * The vaults enrolled in intruder capture, their queued captures and their queued
     * permission notices — `per-vault-intruder-capture` D2. Populated only in a **keyring's**
     * capture store and empty in every other index: the queue has to be readable with the
     * keyring key alone while the vault it is for is locked.
     */
    val vaultCaptures: MutableList<VaultEnrolment> = mutableListOf(),
    val vaultPending: MutableList<VaultPending> = mutableListOf(),
    val vaultNotices: MutableList<VaultNotice> = mutableListOf(),
) {

    /**
     * The index was written by a **newer** build of the app — P2-25.
     *
     * Its own type, and not the `IllegalStateException` every other parse failure
     * raises, because the two mean opposite things. A corrupt or truncated index is
     * damage. This is a rollback: a release pulled back after a staged rollout,
     * running against data the newer build wrote — the moment a corruption message
     * would read as data loss and be untrue. Nothing here is wrong with the vault
     * and nothing about it should be written, deleted or repaired.
     */
    /**
     * The blob an index pointer names is not an index — `index-pointer-recovery`.
     *
     * **A typed failure and not an `EOFException`.** A vault on a real device had a
     * pointer aimed at a 64 KiB blob; `load` read past the end of it, the exception
     * escaped on a background thread, and the process died on every launch — so its
     * owner never reached a screen that could tell them anything or offer them
     * anything. This is [NewerVersionException]'s shape for [NewerVersionException]'s
     * reason: the passphrase was right and the vault still did not open, which is a
     * thing the app has to be able to say.
     *
     * The numbers travel with it so a log line names which blob and how short it was.
     */
    class NotAnIndexException(val blobId: String, val size: Long, val needed: Long) : Exception(
        "blob $blobId is $size B, too small to be an index (needs at least $needed B)"
    )

    class NewerVersionException(val found: Int, val understood: Int) : Exception(
        "index version $found was written by a newer version of the app (this build reads up to $understood)"
    )

    /**
     * `backup-to-cloud-apps` D10. Told of every save, with the index just saved. Set once by
     * `BackupAuto`; null in tests and before the app is attached.
     */
    companion object {
        @Volatile
        var onSaved: ((MetadataDb) -> Unit)? = null

        /**
         * **7 since P2-21**, which appends a note's body and its identifier.
         *
         * The bumps are two kinds, and the difference is why one rejects old data
         * and the others do not.
         *
         * *3 → 4, `blob-size-cap`.* An item went from carrying one blob ID to
         * carrying a **list**. The first field of every item changed meaning, so a
         * version-3 index parses into garbage blob IDs and reads as an empty vault
         * rather than failing — silently, which is the worst outcome available. It
         * is rejected outright.
         *
         * *4 → 5, P2-8.* One optional field is appended after the last existing
         * one. A version-4 record is a version-5 record that stops early, so it is
         * **read**, with the duration absent. Existing vaults keep opening; the
         * videos in them simply carry no duration, and the grid draws no label
         * where there is no value.
         *
         * Rejecting a v4 index here would destroy vaults to avoid writing three
         * lines, and the reason v3 was rejected — silent misparsing — does not
         * apply to an appended field.
         *
         * *5 → 6, P2-12* and *6 → 7, P2-21.* The same shape as 4 → 5 — optional
         * fields appended after the last existing one, read where present and
         * absent where the record stops early.
         *
         * *7 → 8, P2-25.* One more appended optional field, and the release the
         * floor moves for.
         *
         * *8 → 9, `folder-system`.* An appended optional field on the item — the
         * folder it is in — and a new **trailing section**, the folder table, after
         * the log blob. Both are read only at 9 and above; a version-8 index has
         * neither, which is exactly the input [migrateAlbums] expects. An appending
         * bump, so 8 is still read and the floor does not move.
         *
         * *9 → 10, `viewer-chrome-and-editor`.* One appended field on the item — how
         * far it is turned — read only at 10 and above. A version-9 record ends at
         * the folder reference and reads as unrotated, which is what every row
         * written before this held. An appending bump; the floor stays at 8.
         *
         * *10 → 11, `settings-separation`.* A new **trailing section** after the
         * folder table — the folder this vault mirrors into. Read only at 11 and
         * above; a version-10 index ends at the folder table and reads as a vault
         * with no destination, which is what every vault written before this held.
         * An appending bump; the floor stays at 8.
         *
         * *13 → 14, `viewer-swipe-navigation`.* One appended field on the item — where
         * its playback stopped — read only at 14 and above. A version-13 record ends at
         * the owner reference and reads as never stopped, which is "from the beginning".
         * An appending bump; the floor stays at 8.
         *
         * **The floor is 8 and it never rises again.** Read support is never
         * removed *after* publication; before it there is nothing to support. No
         * index below 8 will exist outside a developer's emulator, so the version
         * 4, 5, 6 and 7 read paths were branches no published vault could reach
         * and no fixture could ever cover — and a compatibility path that nothing
         * can exercise is the one that rots. They were deleted here, at the only
         * boundary where deleting them is free. From the first published release
         * onward every field is appended, optional, and read where present, and
         * this number is pinned by `IndexVersionTest`.
         */
        private const val VERSION = 17
        /**
         * The oldest index this build will open — the version the first published
         * release writes. See [VERSION]. **Never raise this.**
         */
        internal const val MIN_READABLE_VERSION = 8

        /**
         * The version the folder table and the item's folder reference appear at —
         * `folder-system`. Named rather than written as `9` at three read sites,
         * because the three have to agree and a bare number does not say why.
         */
        internal const val FOLDERS_VERSION = 9

        /**
         * The version at which a note's text moved out of the index and into its own
         * blob — `notes-as-documents`, and the version that carries [VaultItem.preview],
         * [VaultItem.pinned], [VaultItem.colour] and [VaultItem.ownerNoteId].
         *
         * Named for [FOLDERS_VERSION]'s reason: three read sites have to agree about
         * where a version-11 record ends, and a bare `12` at each of them does not say
         * why. A version-11 note still carries its text in `body`; [migrateNotes] is
         * what moves it.
         */
        internal const val NOTE_BLOB_VERSION = 12

        /**
         * The first version carrying [VaultItem.rotation] —
         * `viewer-chrome-and-editor`, [FOLDERS_VERSION]'s shape and read the same
         * way: guarded on the version rather than on a presence byte, because a
         * version-9 record ends before this field and reading a byte that is not
         * there is where a truncation message would come from for an index that is
         * not truncated.
         */
        internal const val ROTATION_VERSION = 10

        /**
         * The version carrying [mirrorDestination] — `settings-separation`, and
         * [FOLDERS_VERSION]'s shape a third time: a trailing section after every
         * section that existed before it, read only at 11 and above. A version-10
         * index ends at the folder table and reads as a vault with no destination,
         * which is what every vault written before this held.
         */
        internal const val MIRROR_VERSION = 11

        /**
         * The version carrying [capturesSeenAt] — `intruder-capture-record`, and
         * [FOLDERS_VERSION]'s shape a fourth time: a trailing section after every
         * section that existed before it, read only at 13 and above. A version-12
         * index ends at the destination and reads as a vault with nothing seen, which
         * is the right default for a vault written before the badge existed.
         */
        internal const val CAPTURES_SEEN_VERSION = 13

        /**
         * The first version carrying [VaultItem.resumeMs] — `viewer-swipe-navigation`,
         * [ROTATION_VERSION]'s shape: an appended field on the item, read only at 14 and
         * above, with a presence byte because absent is a value of its own.
         */
        internal const val RESUME_VERSION = 14

        /**
         * The first version carrying [VaultFolder.destination] — an appended field on
         * each folder record, read only at 15 and above. A version-14 folder ends at
         * its cover and reads as unscoped, which is listed everywhere, as it was.
         */
        internal const val FOLDER_DESTINATION_VERSION = 15

        /**
         * The first version carrying [VaultItem.editedAt] — `destination-settings-and-slideshow`,
         * [RESUME_VERSION]'s shape: an appended field on the item with a presence byte, read only
         * at 16 and above. A version-15 record ends at its stopping point and reads as never edited.
         */
        internal const val EDITED_VERSION = 16

        /**
         * The version carrying [vaultCaptures], [vaultPending] and [vaultNotices] —
         * `per-vault-intruder-capture`, [CAPTURES_SEEN_VERSION]'s shape: trailing sections after
         * every section before them, read only at 17 and above. A version-16 index ends at the
         * seen mark and reads with all three empty.
         */
        internal const val VAULT_CAPTURES_VERSION = 17

        /**
         * [folders] with every unreachable folder pulled up to the top level.
         *
         * A folder whose parent names no record, or whose parent chain loops or is
         * deeper than the table can be, is **reachable from nowhere**: every level
         * query walks down from the root, so such a folder and everything inside it
         * would exist in the index and be invisible on screen. Pulling it to the top
         * makes it reachable, which is the same call [VaultItem.folderId] makes for
         * a dangling reference — an index that survives is worth more than one that
         * is provably tidy.
         *
         * Nothing should ever reach this. `VaultFolders` refuses a move into a
         * folder's own subtree and reparents on delete inside the same commit, so
         * neither shape can be written by this app. It is here because a parse is
         * the wrong place to trust that.
         */
        private fun rooted(folders: MutableList<VaultFolder>): MutableList<VaultFolder> {
            val byId = folders.associateBy { it.id }
            fun reachable(folder: VaultFolder): Boolean {
                var at = folder
                // Bounded by the table's size: a chain longer than every folder in
                // the vault has visited one twice, which is a loop.
                repeat(folders.size) {
                    val parent = at.parentId ?: return true
                    at = byId[hex(parent)] ?: return false
                }
                return false
            }
            return folders.mapTo(mutableListOf()) {
                if (reachable(it)) it else it.withParent(null)
            }
        }

        private const val LENGTH_PREFIX = 8

        /**
         * The width of a folder's identity — the width [VaultItem.noteId] already
         * uses, so the hex is the same shape and the collision odds are the ones
         * this app has always run.
         */
        internal const val ID_BYTES = 16

        /**
         * A folder's identity — 16 bytes from [SecureRandom], the same source and
         * the same width `VaultNote` draws a note's identity from.
         */
        internal fun newFolderId(): ByteArray =
            ByteArray(ID_BYTES).also { SecureRandom().nextBytes(it) }

        fun empty() = MetadataDb(mutableListOf(), mutableListOf())

        /**
         * The lock every index commit takes — P2-9.
         *
         * A plain monitor, so it is **reentrant on the same thread**. That is
         * load-bearing: `TamperLog.append` saves the index when the log grows, and
         * it runs inside `ImportWorker`'s commit. A non-reentrant lock there would
         * deadlock every import rather than fixing one.
         */
        private val commitLock = Any()

        /**
         * The index currently being committed on this thread, if any. Nested
         * commits reuse it instead of reloading — reloading inside an open commit
         * would read the pre-mutation index from disk and silently discard the
         * outer caller's work, which is the same lost update one level in.
         */
        private val open = ThreadLocal<MetadataDb?>()

        /**
         * Load the index, let [block] mutate it, save it — with every other
         * commit locked out.
         *
         * **This exists because the three steps done separately lose data.** The
         * index is one blob rewritten whole (§7), so two callers that each load,
         * append and save will overwrite one another. P2-9 measured seven
         * concurrent savers on device: three saves succeeded, four threw
         * `could not move blob into place`, and the index was left **unparseable**
         * — `BlobStore` stages a write at `incoming/<blobId>.tmp` and the index's
         * blob ID is fixed, so concurrent savers interleave bytes into one staging
         * file and rename the mixture into place. An index that will not parse is a
         * vault that will not open, and the recovery kit does not help: it derives
         * the index's blob *ID*, and the blob at that ID is the garbage.
         *
         * Everything that mutates the index belongs inside the block — items,
         * owned chaff, thumbnail pack staging, the tamper log's blob ID. Work that
         * does *not* touch the index — reading a source, encrypting, writing content
         * blobs, decoding a thumbnail — belongs **outside** it and stays parallel.
         * The guarded section should be load, mutate, save and nothing slower.
         *
         * **Bare `load` + `save` is legitimate in exactly three places**, all of
         * which have no concurrent writer by construction: creating a vault's first
         * index ([VaultLifecycle]), recovery, and archive import. Anywhere else it
         * is a defect, and `IndexCommitScanTest` fails the build for it.
         */
        /**
         * The one background thread that writes the index — [MainActivity]'s unlock
         * backfill, and nothing else.
         *
         * ***Held so it can be waited for.*** The backfill captures the blob ID and the
         * VMK of the unlock that started it, which is right in the app: they name the
         * vault it is backfilling, and a later unlock of a different vault has its own.
         * It is wrong across a test, because the index blob ID is **derived from vault key
         * material** and every fixture in the device suites uses the same passphrase — so
         * a fixture that deletes its vault and the next one that creates its own land on
         * the *same* ID under a *different* VMK, and a backfill still running from the
         * first writes the first vault's index over the second's. What the second test
         * then reads is a blob it cannot parse: `index is truncated`.
         */
        @Volatile
        private var backgroundWriter: Thread? = null

        /**
         * Runs [body] on the index's one background thread, replacing any previous one.
         *
         * A single slot and not a list: there is one such writer, started at unlock, and
         * a second unlock has already replaced the vault the first was writing for.
         */
        fun backgroundWrite(body: () -> Unit): Thread =
            kotlin.concurrent.thread(name = "index-backfill", block = body)
                .also { backgroundWriter = it }

        /**
         * Blocks until no commit is in flight, then returns.
         *
         * ***For teardown, and it exists because an index write can outlive the screen
         * that started it.*** [MainActivity.loadIndex] runs the thumbnail backfill on a
         * bare thread, which commits the index; nothing joins it. In the app that is
         * harmless — commits serialise on [commitLock] and `BlobStore` renames a staged
         * file into place, so the blob is whole or absent, never half.
         *
         * It is not harmless to a test that deletes `blobs/` and `incoming/` when it
         * finishes. The staging file goes out from under a live commit, the rename lands
         * a partial index, and **the index blob ID is derived from the vault key** — so
         * every test using the same passphrase has the same ID, and the wreckage is
         * waiting for whichever test runs next. That is the `index is truncated` failure
         * that hit two different tests in two different full-suite runs and passed on
         * every re-run in isolation.
         *
         * Taking and releasing the lock is the whole of it: a commit already running
         * finishes first, and one that has not started cannot start until this returns
         * and the caller has deleted what it wanted to delete.
         *
         * ponytail: this closes the window, it does not lock it — a thread that begins a
         * commit *after* this returns still races the delete. Nothing in the app starts
         * one without an unlocked vault, which teardown has already locked, so the
         * remaining window needs a caller that does not exist. Give the backfill a
         * joinable handle if one ever does.
         */
        fun awaitQuiescent() {
            // ***Join first, then take the lock, and the order is the whole point.***
            // Taking the lock alone only waits out a commit that has already started; the
            // backfill thread spends its first instants before that call, and a teardown
            // that ran through the window deleted `blobs/` in front of a write that had
            // not begun. Joining closes it.
            //
            // Bounded rather than indefinite: a teardown that hangs for ever reports
            // nothing, and a backfill that has not finished in thirty seconds is a defect
            // the next test will surface more loudly than a stuck suite would.
            backgroundWriter?.takeIf { it.isAlive }?.join(30_000)
            backgroundWriter = null
            synchronized(commitLock) { }
        }

        fun <T> commit(
            store: BlobStore,
            blobId: ByteArray,
            vmk: ByteArray,
            block: (MetadataDb) -> T,
        ): T = synchronized(commitLock) {
            open.get()?.let { return@synchronized block(it) }

            val db = load(store, blobId, vmk)
            open.set(db)
            try {
                val result = block(db)
                // `notes-as-documents`. Before the save and inside the same guarded
                // section, so a note's text lands in a blob and the row naming it
                // lands in the same index write. Costs nothing once every note has
                // moved: it walks the items and finds no body.
                db.migrateNotes(store, vmk)
                db.save(store, blobId, vmk)
                result
            } finally {
                open.remove()
            }
        }

        /**
         * Reads the index from its blob. A blob that does not exist yields an
         * empty index — that is a vault with nothing in it, not an error.
         *
         * The index is self-describing (§7): chunk 0 is always one full chunk,
         * and its first 8 bytes give the serialised length. Every other blob's
         * length lives in this index; this one's has nowhere else to go.
         */
        // **Under [commitLock].** The read opens the blob twice — chunk 0 for the length,
        // then the whole — and a commit renaming a new index in between hands it the old
        // length over the new bytes: `index is truncated`. `MainActivity.loadIndex` reloads
        // as each import lands, so without the lock it raced every import's save. The
        // monitor is reentrant, so [commit]'s own call from inside the lock is unaffected.
        fun load(store: BlobStore, blobId: ByteArray, vmk: ByteArray): MetadataDb {
            synchronized(commitLock) {
                val file = store.fileOf(blobId)
                if (!file.exists()) return empty()

                // **The floor, checked before the read that assumes it.** Chunk 0 is read
                // by telling the reader this blob holds a full chunk — true of every index
                // this app writes, false of every other blob, and unchecked until
                // `index-pointer-recovery`. One sealed chunk plus the nonce prefix is
                // 65568 B, past the smallest rung of §5's ladder, so an index can never
                // occupy a 64 KiB blob and a 64 KiB blob is never an index. `BigIndexTest`
                // asserts that floor from an empty index up to several chunks.
                val needed = (BlobStore.PREFIX_BYTES + BlobStore.STRIDE).toLong()
                if (file.length() < needed) {
                    throw NotAnIndexException(hex(blobId), file.length(), needed)
                }

                val head = ByteArray(BlobStore.CHUNK)
                store.reader(blobId, vmk, BlobStore.CHUNK.toLong()).use {
                    it.readChunk(0, head)
                }
                val length = ByteBuffer.wrap(head, 0, LENGTH_PREFIX)
                    .order(ByteOrder.LITTLE_ENDIAN).long
                require(length >= 0) { "index length is negative" }

                val padded = paddedSize(length)
                val plain = ByteArray(padded.toInt())
                store.reader(blobId, vmk, padded).use { r ->
                    val buf = ByteArray(BlobStore.CHUNK)
                    for (i in 0 until r.chunkCount) {
                        val n = r.readChunk(i, buf)
                        buf.copyInto(plain, (i * BlobStore.CHUNK).toInt(), 0, n)
                    }
                }

                return parse(plain.copyOfRange(LENGTH_PREFIX, LENGTH_PREFIX + length.toInt()))
            }
        }

        /**
         * This vault's index, found among the blobs in [store] — `index-pointer-recovery`.
         *
         * **Every byte of a vault is still in the pool when only the pointer is wrong**,
         * so total loss is not the honest outcome — it is the outcome of not looking.
         * A device arrived with a slot aimed at a 64 KiB blob and a vault full of
         * photographs and notes sitting untouched beside it.
         *
         * **Authentication is what makes this exact rather than a heuristic.** A blob
         * that is not this vault's cannot forge its tag, so a candidate that decrypts
         * under [vmk] is *this vault's* — chaff, another vault's index and a stranger's
         * blob all fail at step two and are never parsed. What decryption alone does not
         * separate is this vault's own **content** blobs, which are sealed the same way:
         * a photograph's first eight bytes are image data, so it is the length prefix
         * and the version byte that rule it out.
         *
         * The four gates, in order, each cheaper than the one after it:
         *
         * 1. **Size.** Below one sealed chunk plus the prefix it cannot be an index and
         *    is never opened. That alone skipped six of that device's sixty-four files.
         * 2. **The tag.** Chunk 0 must decrypt under this vault's key.
         * 3. **The length prefix.** It must be positive and must not claim more than the
         *    blob can hold — which also bounds the allocation the next step makes, so a
         *    photograph claiming a petabyte costs nothing.
         * 4. **The parse.** [load] itself, which is the real thing rather than a
         *    resemblance to it.
         *
         * Largest first: an index is usually among the bigger blobs, and a hit ends the
         * walk.
         *
         * **It writes nothing.** A search that finds nothing has changed nothing, which
         * matters more here than anywhere — an unopenable vault must not be made worse
         * by being looked at.
         *
         * @throws NewerVersionException when the index found was written by a newer
         *   build. That is still this vault's index and is reported as itself; passing
         *   over it in silence would tell its owner their vault was gone when it is not.
         */
        fun findIndex(store: BlobStore, vmk: ByteArray): ByteArray? {
            val floor = (BlobStore.PREFIX_BYTES + BlobStore.STRIDE).toLong()
            var newer: NewerVersionException? = null

            store.allBlobIds()
                .map { it to store.fileOf(it).length() }
                .filter { (_, size) -> size >= floor }
                .sortedByDescending { (_, size) -> size }
                .forEach { (id, size) ->
                    val head = ByteArray(BlobStore.CHUNK)
                    val opened = runCatching {
                        store.reader(id, vmk, BlobStore.CHUNK.toLong()).use { it.readChunk(0, head) }
                    }.isSuccess
                    if (!opened) return@forEach

                    val length = ByteBuffer.wrap(head, 0, LENGTH_PREFIX)
                        .order(ByteOrder.LITTLE_ENDIAN).long
                    if (length <= 0) return@forEach
                    // Before `load`, which allocates `paddedSize(length)`. A blob that
                    // is not an index has arbitrary bytes here, and one of them will
                    // eventually read as an enormous length.
                    if (paddedSize(length) > size) return@forEach

                    try {
                        load(store, id, vmk)
                        return id
                    } catch (e: NewerVersionException) {
                        newer = e
                    } catch (_: Exception) {
                        // Decrypted and this vault's, but not an index: a photograph
                        // whose opening bytes happened to read as a plausible length.
                    }
                }

            newer?.let { throw it }
            return null
        }

        private fun paddedSize(length: Long): Long {
            val total = LENGTH_PREFIX + length
            val chunks = (total + BlobStore.CHUNK - 1) / BlobStore.CHUNK
            return chunks.coerceAtLeast(1) * BlobStore.CHUNK
        }

        /**
         * `internal` rather than private so version handling can be tested directly
         * on the JVM — P2-8, and P2-25's floor and ceiling. Pure bytes in, index
         * out; it needs no device.
         */
        internal fun parse(bytes: ByteArray): MetadataDb = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val version = input.readByte().toInt()
                // Too old and too new are not one failure — P2-25. Above the
                // ceiling is the rollback case: a release pulled back after a
                // staged rollout, reading data the newer build wrote, at the
                // moment a corruption message would read as data loss and be
                // untrue. It gets its own type so the UI can say what happened.
                if (version > VERSION) throw NewerVersionException(version, VERSION)
                check(version >= MIN_READABLE_VERSION) { "unknown index version $version" }

                val items = MutableList(input.readInt()) {
                    VaultItem(
                        blobIds = List(input.readInt()) { readBytes(input) },
                        plaintextLength = input.readLong(),
                        thumb = if (input.readByte().toInt() == 1) {
                            ThumbRef(readBytes(input), input.readInt(), input.readInt())
                        } else {
                            null
                        },
                        filename = readString(input),
                        album = if (input.readByte().toInt() == 1) readString(input) else null,
                        createdAt = if (input.readByte().toInt() == 1) input.readLong() else null,
                        importedAt = input.readLong(),
                        tags = List(input.readInt()) { readString(input) },
                        deletable = input.readByte().toInt() == 1,
                        // Appended at versions 5, 6 and 7 and unconditional since
                        // P2-25 raised the floor to 8: every readable record carries
                        // all four presence bytes. The version guards that used to
                        // stand here covered indices 4..6, which no published release
                        // ever wrote.
                        durationMs = if (input.readByte().toInt() == 1) input.readLong() else null,
                        deletedAt = if (input.readByte().toInt() == 1) input.readLong() else null,
                        body = if (input.readByte().toInt() == 1) readString(input) else null,
                        noteId = if (input.readByte().toInt() == 1) readBytes(input) else null,
                        // Appended at version 8 — P2-25 — and read the same way. A
                        // record whose presence byte is 0 holds the original
                        // envelope, which is every blob written so far.
                        envelopeVersion = if (input.readByte().toInt() == 1) input.readInt() else null,
                        // Appended at version 9 — `folder-system`. Guarded on the
                        // version and not merely on a presence byte, because a
                        // version-8 record ends at the field above: reading a byte
                        // that is not there is where a truncation message would
                        // come from, for an index that is not truncated.
                        folderId = if (version >= FOLDERS_VERSION) {
                            if (input.readByte().toInt() == 1) readBytes(input) else null
                        } else {
                            null
                        },
                        // Appended at version 10 — `viewer-chrome-and-editor`, and
                        // guarded for [FOLDERS_VERSION]'s reason. A version-9 record
                        // ends above and reads as unrotated, which is what absent has
                        // always meant here.
                        rotation = if (version >= ROTATION_VERSION) input.readInt() else 0,
                        // Appended at version 12 — `notes-as-documents`, guarded for
                        // [FOLDERS_VERSION]'s reason. A version-11 record ends above,
                        // and a note written then still carries its text in `body`
                        // until [migrateNotes] moves it.
                        preview = if (version >= NOTE_BLOB_VERSION) {
                            if (input.readByte().toInt() == 1) readString(input) else null
                        } else {
                            null
                        },
                        pinned = version >= NOTE_BLOB_VERSION && input.readByte().toInt() == 1,
                        colour = if (version >= NOTE_BLOB_VERSION) {
                            FolderColour.of(input.readInt())
                        } else {
                            FolderColour.None
                        },
                        ownerNoteId = if (version >= NOTE_BLOB_VERSION) {
                            if (input.readByte().toInt() == 1) readBytes(input) else null
                        } else {
                            null
                        },
                        // Appended at version 14 — `viewer-swipe-navigation`, guarded
                        // for [FOLDERS_VERSION]'s reason. A version-13 record ends above
                        // and reads as never stopped, which is "from the beginning".
                        resumeMs = if (version >= RESUME_VERSION) {
                            if (input.readByte().toInt() == 1) input.readLong() else null
                        } else {
                            null
                        },
                        editedAt = if (version >= EDITED_VERSION) {
                            if (input.readByte().toInt() == 1) input.readLong() else null
                        } else {
                            null
                        },
                    )
                }
                // P2-21's invariant, checked once on the way in rather than at every
                // read of `itemId`. A row with no blobs and no note ID has no
                // identity at all, and a parse that let it through would turn a
                // corrupt index into a crash somewhere in the grid.
                items.forEach {
                    check(it.blobIds.isNotEmpty() || it.noteId != null) {
                        "index has an item with neither a blob nor a note ID"
                    }
                    // A note is its note ID. Before version 12 it also carried its
                    // text here, and `body != null` implied a note; after it, the
                    // text is in a blob and `body` is null on a migrated row. So the
                    // implication only runs one way now — a body without a note ID is
                    // still a corrupt row, a note ID without a body is the ordinary
                    // shape from version 12 onward.
                    check(it.body == null || it.noteId != null) {
                        "index has a body on an item that is not a note"
                    }
                }
                val chaff = MutableList(input.readInt()) { readBytes(input) }
                val packs = MutableList(input.readInt()) {
                    ThumbPack(readBytes(input), input.readInt())
                }
                val logBlobId = if (input.readByte().toInt() == 1) readBytes(input) else null

                // The folder table, appended at version 9 after every section that
                // existed before it. Absent below 9, which is what makes 8 → 9 an
                // appending bump rather than a rejecting one.
                val folders = if (version >= FOLDERS_VERSION) {
                    MutableList(input.readInt()) {
                        VaultFolder(
                            folderId = readBytes(input),
                            parentId = if (input.readByte().toInt() == 1) readBytes(input) else null,
                            name = readString(input),
                            colour = FolderColour.of(input.readInt()),
                            coverItemId =
                                if (input.readByte().toInt() == 1) readBytes(input) else null,
                            destination = if (version >= FOLDER_DESTINATION_VERSION) {
                                readString(input).let { name ->
                                    VaultDestination.values().firstOrNull { it.name == name }
                                }
                            } else {
                                null
                            },
                        )
                    }
                } else {
                    mutableListOf()
                }

                // Appended at version 11 — `settings-separation` — after the folder
                // table, and guarded on the version for [FOLDERS_VERSION]'s reason:
                // a version-10 record ends above, and reading a byte that is not
                // there is where a truncation message would come from for an index
                // that is not truncated.
                val mirrorDestination = if (version >= MIRROR_VERSION) {
                    if (input.readByte().toInt() == 1) readString(input) else null
                } else {
                    null
                }

                // Appended at version 13 — `intruder-capture-record` — after the
                // destination, and guarded for the reason above it.
                val capturesSeenAt = if (version >= CAPTURES_SEEN_VERSION) {
                    if (input.readByte().toInt() == 1) input.readLong() else null
                } else {
                    null
                }

                // Appended at version 17 — `per-vault-intruder-capture` — after the seen
                // mark, and guarded for the reason above it.
                val vaultCaptures = mutableListOf<VaultEnrolment>()
                val vaultPending = mutableListOf<VaultPending>()
                val vaultNotices = mutableListOf<VaultNotice>()
                if (version >= VAULT_CAPTURES_VERSION) {
                    repeat(input.readInt()) {
                        vaultCaptures += VaultEnrolment(readBytes(input), readBytes(input), modesOf(input.readByte().toInt()))
                    }
                    repeat(input.readInt()) {
                        vaultPending += VaultPending(readBytes(input), modeOf(input.readByte().toInt()), readBytes(input), input.readInt())
                    }
                    repeat(input.readInt()) {
                        vaultNotices += VaultNotice(readBytes(input), input.readByte().toInt(), input.readByte().toInt(), input.readLong())
                    }
                }

                // The length prefix promised exactly this many bytes, so anything
                // left over means the index was not what it claimed. Without this,
                // a truncated index parses happily out of the zero padding —
                // zero-length arrays, empty strings, absent optionals are all
                // valid-looking — and yields garbage items instead of failing.
                check(input.read() == -1) { "index has trailing bytes" }

                MetadataDb(
                    items, chaff, packs, logBlobId, rooted(folders), mirrorDestination,
                    capturesSeenAt, vaultCaptures, vaultPending, vaultNotices,
                ).also { if (version < FOLDERS_VERSION) it.migrateAlbums() }
            }
        } catch (e: EOFException) {
            throw IllegalStateException("index is truncated", e)
        } catch (e: NegativeArraySizeException) {
            throw IllegalStateException("index is corrupt", e)
        }

        private fun readBytes(input: DataInputStream): ByteArray =
            ByteArray(input.readInt()).also { input.readFully(it) }

        /** A mode bitmask, bit = [Capture.Mode.ordinal]. */
        private fun modesOf(bits: Int): Set<Capture.Mode> =
            Capture.Mode.entries.filter { bits and (1 shl it.ordinal) != 0 }.toSet()

        private fun modeOf(ordinal: Int): Capture.Mode =
            Capture.Mode.entries.getOrNull(ordinal) ?: error("index names capture mode $ordinal")

        /** Length-prefixed UTF-8, not writeUTF: that caps at 64 KB and mangles emoji. */
        private fun readString(input: DataInputStream): String =
            String(readBytes(input), Charsets.UTF_8)
    }

    /**
     * Serialises and replaces the index blob atomically, under the **same** blob
     * ID. A moving ID would mean rewriting the slot, which needs a KEK that
     * `VaultUnlock` has already zeroed.
     *
     * **Call this through [commit] unless you are one of the enumerated
     * exceptions.** A bare `load` / mutate / `save` from two threads is the P2-9
     * bug — see [commit] for what it costs.
     */
    fun save(store: BlobStore, blobId: ByteArray, vmk: ByteArray) {
        store.writeTo(blobId, ByteArrayInputStream(plaintext()), vmk)
        // After the write and never before — see [retired].
        retired.forEach { store.delete(it) }
        retired.clear()
        // `backup-to-cloud-apps` D10. Every change to a vault ends here, so this is where
        // backup learns of it. A listener that fails never fails a save.
        onSaved?.let { runCatching { it(this) } }
    }

    /**
     * Blobs this index has stopped naming, deleted once it is saved — `video-frame-thumbnail`.
     *
     * A thumbnail pack is never rewritten in place: [ThumbnailStore.flush] writes a successor
     * under a new blob ID and swaps it into this index. Until the index naming the successor
     * is durable, the predecessor is the only pack the index **on disk** can read, so it waits
     * here and [save] deletes it after the write. In memory only: a process that dies first
     * leaves the successor as an untracked blob that nothing records
     * (`blob-ownership-by-record`) — left alone rather than adopted, per `FORMAT.md` §6.
     */
    internal val retired = mutableListOf<ByteArray>()

    /**
     * The index blob's plaintext — §7's 8-byte little-endian length prefix, the
     * serialised index, zero-padded to whole 64 KiB chunks.
     *
     * `internal` and separate from [save] since P2-12: `VaultArchive` seals a
     * *filtered* index into the archive without ever writing it to disk, and it
     * must produce exactly the same shape [load] reads back. Building that shape in
     * two places is how the two would come to disagree.
     */
    internal fun plaintext(): ByteArray {
        val body = serialise()
        val plain = ByteArray(paddedSize(body.size.toLong()).toInt())
        ByteBuffer.wrap(plain, 0, LENGTH_PREFIX)
            .order(ByteOrder.LITTLE_ENDIAN).putLong(body.size.toLong())
        body.copyInto(plain, LENGTH_PREFIX)
        return plain
    }

    /**
     * Moves a version-11 note's text out of the index and into its own blob —
     * `notes-as-documents`. Returns how many were moved.
     *
     * **Not in [parse], and that is the difference between this and [migrateAlbums].**
     * The album migration runs in memory and rides out with the next commit; this one
     * has to *write a blob*, so it needs a store and a key that `parse` does not have
     * and should not be given. It runs from [commit] instead, immediately before the
     * save that commit was going to make anyway — so a migrated note costs one blob
     * write on top of a write that was already happening.
     *
     * **Opening a vault to look at it still writes nothing.** A read-only session
     * never reaches [commit], so a vault that is only browsed keeps its version-11
     * rows and reads them out of `body` exactly as before. That rule is
     * [migrateAlbums]'s and it is not relaxed here.
     *
     * The filename's extension moves with the text, because a note carrying Markdown
     * under a `.txt` name would be lying about itself to the one thing that decides
     * what an item is. The stem — the title the user typed — is untouched.
     */
    fun migrateNotes(store: BlobStore, vmk: ByteArray): Int {
        var moved = 0
        items.forEachIndexed { i, item ->
            val text = item.body ?: return@forEachIndexed
            val written = store.writeItem(
                java.io.ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
                vmk,
            )
            val renamed = if (item.filename.endsWith(VaultNote.LEGACY_EXTENSION)) {
                item.filename.removeSuffix(VaultNote.LEGACY_EXTENSION) + VaultNote.EXTENSION
            } else {
                item.filename
            }
            items[i] = item
                .withFilename(renamed)
                .withNoteBlob(written.blobIds, VaultNote.previewOf(text), written.plaintextLength)
            moved++
        }
        return moved
    }

    /**
     * Turns this index's albums into folders — `folder-system`.
     *
     * Called once, by [parse], on an index written before version 9. Every distinct
     * album string becomes one top-level folder with the default colour and no
     * cover, and every item carrying that string is put in it.
     *
     * **Trashed items are migrated too.** A trashed item keeps its album so that
     * restoring it returns it where it was (`album-organisation`); skipping them
     * here would restore an item to the home screen instead, months later, with
     * nothing to explain it.
     *
     * **Nothing is written by this.** It runs in memory and rides out with the next
     * index commit, whatever makes it. Opening a vault to look at it must not write
     * to it — a read-only session that rewrites the index is a modification the user
     * did not make, at the exact moment they were being careful.
     *
     * The legacy [VaultItem.album] is left as it was written. Clearing it would
     * rewrite every row in the vault to remove a field the app can simply stop
     * reading.
     */
    private fun migrateAlbums() {
        val names = items.mapNotNull { it.album }.distinct()
        if (names.isEmpty()) return

        val made = names.associateWith { name ->
            VaultFolder(folderId = newFolderId(), parentId = null, name = name)
        }
        folders.addAll(made.values)

        for (i in items.indices) {
            val album = items[i].album ?: continue
            items[i] = items[i].withFolder(made.getValue(album).folderId)
        }
    }

    /**
     * This index with [items] in place of its own — P2-12.
     *
     * Everything else is shared by reference on purpose: the chaff, the packs and
     * the log belong to the vault, not to the item list, and an archive carries
     * them whole. The copy is never saved to the vault's own blob.
     *
     * **[mirrorDestination] is deliberately not carried** — `settings-separation`.
     * A destination is a persisted SAF grant on *this* device, so it means nothing
     * on the install that opens the archive; and an archive is a file the user hands
     * to someone else, which is not a place to write the name of their backup
     * folder. An imported vault starts with no destination, as a new one does.
     */
    internal fun withItems(items: List<VaultItem>) =
        MetadataDb(items.toMutableList(), ownedChaff, thumbPacks, logBlobId, folders)

    /**
     * This index with [folders] in place of its own — `folder-system`, [withItems]'
     * shape for the same reason: an archive seals a filtered index without writing
     * it, and the folder table travels whole with it.
     */
    internal fun withFolders(folders: List<VaultFolder>) =
        MetadataDb(items, ownedChaff, thumbPacks, logBlobId, folders.toMutableList())

    internal fun serialise(): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { o ->
            o.writeByte(VERSION)

            o.writeInt(items.size)
            items.forEach { item ->
                o.writeInt(item.blobIds.size)
                item.blobIds.forEach { writeBytes(o, it) }
                o.writeLong(item.plaintextLength)
                val t = item.thumb
                if (t != null) {
                    o.writeByte(1); writeBytes(o, t.packBlobId); o.writeInt(t.offset); o.writeInt(t.length)
                } else {
                    o.writeByte(0)
                }
                writeString(o, item.filename)
                if (item.album != null) {
                    o.writeByte(1); writeString(o, item.album)
                } else {
                    o.writeByte(0)
                }
                if (item.createdAt != null) {
                    o.writeByte(1); o.writeLong(item.createdAt)
                } else {
                    o.writeByte(0)
                }
                o.writeLong(item.importedAt)
                o.writeInt(item.tags.size)
                item.tags.forEach { writeString(o, it) }
                o.writeByte(if (item.deletable) 1 else 0)
                if (item.durationMs != null) {
                    o.writeByte(1); o.writeLong(item.durationMs)
                } else {
                    o.writeByte(0)
                }
                if (item.deletedAt != null) {
                    o.writeByte(1); o.writeLong(item.deletedAt)
                } else {
                    o.writeByte(0)
                }
                // P2-21, in the order `parse` reads them. `writeString` and not
                // `writeUTF`: that caps at 64 KB and mangles emoji, which is
                // exactly the shape a note body has.
                if (item.body != null) {
                    o.writeByte(1); writeString(o, item.body)
                } else {
                    o.writeByte(0)
                }
                if (item.noteId != null) {
                    o.writeByte(1); writeBytes(o, item.noteId)
                } else {
                    o.writeByte(0)
                }
                // Appended at version 8 — P2-25 — in the order `parse` reads it.
                if (item.envelopeVersion != null) {
                    o.writeByte(1); o.writeInt(item.envelopeVersion)
                } else {
                    o.writeByte(0)
                }
                // Appended at version 9 — `folder-system`. Unguarded here because
                // this always writes the current version; the guard is on the read.
                if (item.folderId != null) {
                    o.writeByte(1); writeBytes(o, item.folderId)
                } else {
                    o.writeByte(0)
                }
                // Appended at version 10 — `viewer-chrome-and-editor`. **The angle
                // itself, not a count of quarter turns and not an ordinal**: a later
                // build that inserted an angle would re-point every stored turn at a
                // different one, which is the mistake P2-16 avoided by storing the
                // sort by name. Unguarded here for the field above's reason.
                o.writeInt(item.rotation)
                // Version 12 — `notes-as-documents`. Appended after every field that
                // existed before it, which is what makes 11 -> 12 an appending bump.
                if (item.preview != null) {
                    o.writeByte(1); writeString(o, item.preview)
                } else {
                    o.writeByte(0)
                }
                o.writeByte(if (item.pinned) 1 else 0)
                o.writeInt(item.colour.ordinal)
                if (item.ownerNoteId != null) {
                    o.writeByte(1); writeBytes(o, item.ownerNoteId)
                } else {
                    o.writeByte(0)
                }
                // Version 14 — `viewer-swipe-navigation`, after every field before it
                // and in the order `parse` reads it.
                if (item.resumeMs != null) {
                    o.writeByte(1); o.writeLong(item.resumeMs)
                } else {
                    o.writeByte(0)
                }
                // Version 16 — `destination-settings-and-slideshow`, last, as `parse` reads it.
                if (item.editedAt != null) {
                    o.writeByte(1); o.writeLong(item.editedAt)
                } else {
                    o.writeByte(0)
                }
            }

            o.writeInt(ownedChaff.size)
            ownedChaff.forEach { writeBytes(o, it) }

            o.writeInt(thumbPacks.size)
            thumbPacks.forEach { writeBytes(o, it.id); o.writeInt(it.plaintextLength) }

            val log = logBlobId
            if (log != null) {
                o.writeByte(1); writeBytes(o, log)
            } else {
                o.writeByte(0)
            }

            // The folder table last — version 9's trailing section, in the order
            // `parse` reads it.
            o.writeInt(folders.size)
            folders.forEach { folder ->
                writeBytes(o, folder.folderId)
                val parent = folder.parentId
                if (parent != null) {
                    o.writeByte(1); writeBytes(o, parent)
                } else {
                    o.writeByte(0)
                }
                writeString(o, folder.name)
                o.writeInt(folder.colour.ordinal)
                val cover = folder.coverItemId
                if (cover != null) {
                    o.writeByte(1); writeBytes(o, cover)
                } else {
                    o.writeByte(0)
                }
                writeString(o, folder.destination?.name ?: "")
            }

            // The destination last — version 11's trailing section, in the order
            // `parse` reads it. Unguarded here because this always writes the
            // current version; the guard is on the read.
            val destination = mirrorDestination
            if (destination != null) {
                o.writeByte(1); writeString(o, destination)
            } else {
                o.writeByte(0)
            }

            // Version 13's trailing section, after the destination and in the order
            // `parse` reads it — `intruder-capture-record`.
            val seen = capturesSeenAt
            if (seen != null) {
                o.writeByte(1); o.writeLong(seen)
            } else {
                o.writeByte(0)
            }

            // Version 17's trailing sections, after the seen mark and in the order `parse`
            // reads them — `per-vault-intruder-capture` D2.
            o.writeInt(vaultCaptures.size)
            vaultCaptures.forEach { e ->
                writeBytes(o, e.indexBlobId); writeBytes(o, e.publicKey)
                o.writeByte(e.modes.fold(0) { bits, m -> bits or (1 shl m.ordinal) })
            }
            o.writeInt(vaultPending.size)
            vaultPending.forEach { p ->
                writeBytes(o, p.indexBlobId); o.writeByte(p.mode.ordinal); writeBytes(o, p.blobId); o.writeInt(p.sealedLength)
            }
            o.writeInt(vaultNotices.size)
            vaultNotices.forEach { n ->
                writeBytes(o, n.indexBlobId); o.writeByte(n.permission); o.writeByte(n.state); o.writeLong(n.at)
            }
        }
        return out.toByteArray()
    }

    private fun writeBytes(o: DataOutputStream, bytes: ByteArray) {
        o.writeInt(bytes.size)
        o.write(bytes)
    }

    private fun writeString(o: DataOutputStream, s: String) =
        writeBytes(o, s.toByteArray(Charsets.UTF_8))
}
