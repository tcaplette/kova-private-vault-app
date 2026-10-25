package com.app.vault

import android.util.Log

/**
 * Turning one item — `viewer-chrome-and-editor`.
 *
 * **A rotation is a write to the index and to nothing else.** No blob is decrypted,
 * re-encoded or given a new identifier; `VaultItem.rotation` records the angle and
 * the two decode sites add it to whatever the item's own bytes say. The cost of the
 * alternative is why: re-encoding a JPEG spends a generation of lossy quality per
 * tap to change a number, and patching the EXIF tag inside the blob spends the same
 * rewrite while editing the file the user imported.
 *
 * [VaultRename]'s shape, and for its reasons: one item, through `MetadataDb.commit`
 * rather than a bare load/save, an item gone by another path skipped rather than
 * treated as an error, and a log line that counts and never names.
 */
object VaultRotate {

    private const val TAG = "VaultRotate"

    /** A whole turn. Angles are stored modulo this, so four quarter turns is zero. */
    private const val FULL_TURN = 360

    /** The one step a rotate control takes. Clockwise, `JPEG_ORIENTATION`'s direction. */
    const val QUARTER_TURN = 90

    /**
     * [item] turned [quarterTurns] quarter turns clockwise from where it is.
     *
     * Relative and not absolute, because the control is "turn this" and the caller
     * would otherwise have to read the current angle to add to it — a read the
     * commit is already doing, and one that would be stale by the time it was used.
     *
     * A negative count turns the other way; the result is normalised into
     * `0..270` either way, so no stored angle is ever negative and no reader has to
     * defend against one.
     */
    fun rotate(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        item: VaultItem,
        quarterTurns: Int = 1,
    ): MetadataDb {
        val db = MetadataDb.commit(store, indexId, vmk) { db ->
            val i = db.items.indexOfFirst { it.itemId == item.itemId }
            // Gone by another path while the viewer sat open: skipped, not an error.
            if (i >= 0) {
                val current = db.items[i]
                db.items[i] = current.withRotation(turned(current.rotation, quarterTurns))
            }
            db
        }

        Log.i(TAG, "rotated 1 item(s)")
        return db
    }

    /**
     * [from] turned [quarterTurns] quarter turns, normalised into `0..270`.
     *
     * Separate from [rotate] so the arithmetic — which is the only part that can be
     * wrong — is testable without a store, a key or an index.
     */
    fun turned(from: Int, quarterTurns: Int): Int =
        ((from + quarterTurns * QUARTER_TURN) % FULL_TURN + FULL_TURN) % FULL_TURN
}
