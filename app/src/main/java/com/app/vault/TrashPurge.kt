package com.app.vault

import android.util.Log

/**
 * The undo window, applied — P2-12.
 *
 * A trashed item is destroyed once its stamp is older than [WINDOW_MS]. The check
 * runs at unlock, in the background thread `MainActivity` already uses for the
 * partial sweep and orphan adoption, and **before** both: a purge interrupted
 * halfway leaves unreferenced blobs, and running ahead of adoption means they are
 * accounted for at the same unlock rather than the next one.
 *
 * **There is nowhere else it could run.** The index is a blob under the VMK, so a
 * scheduled job with no key can read nothing and would have to be handed one —
 * which is the opposite of every decision in this app. The window is therefore
 * "30 days, applied whenever you open the vault", which is what the trash screen
 * says.
 *
 * **Wall clock, not elapsed time.** No monotonic clock survives the device being
 * off. A clock moved backwards delays a purge and one moved forwards purges early;
 * P2-5 already detects and records clock regression, so the condition is visible
 * without this file adding anything. Refusing to purge when the clock looks wrong
 * would mean a trash that silently stops emptying, which is worse and unobservable.
 */
object TrashPurge {

    private const val TAG = "VaultTrash"

    /** 30 days. Retunable — it is a product decision, not a format one. */
    const val WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** The window in whole days, for the copy that has to say it out loud. */
    const val WINDOW_DAYS: Int = (WINDOW_MS / (24 * 60 * 60 * 1000)).toInt()

    /**
     * Destroys every item whose stamp is older than [WINDOW_MS].
     *
     * One [VaultDeletion.purge] for the whole expired set, so the index is written
     * once however many items expired together.
     *
     * @return how many were destroyed.
     */
    fun run(
        store: BlobStore,
        indexId: ByteArray,
        vmk: ByteArray,
        now: Long = System.currentTimeMillis(),
    ): Int {
        val expired = MetadataDb.load(store, indexId, vmk).items.filter {
            val at = it.deletedAt ?: return@filter false
            now - at > WINDOW_MS
        }
        if (expired.isEmpty()) return 0

        VaultDeletion.purge(store, indexId, vmk, expired)
        Log.i(TAG, "window expired for ${expired.size} item(s)")
        return expired.size
    }
}
