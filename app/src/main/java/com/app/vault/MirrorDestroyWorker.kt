package com.app.vault

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Empties the mirror, off the unlock path — `panic-pin` and `cloud-mirror` D8.
 *
 * **Why this is a worker and not four lines inside `destroy`.** `panic-pin`
 * requires that a panic unlock and an ordinary one fall inside the same measured
 * envelope. Deleting n documents through a `DocumentsProvider` is n IPC round
 * trips to another app, which on a signed-out, throttled or hung provider is
 * unbounded. Inline, that is a panic unlock that visibly takes longer than a
 * normal one — the single tell the whole feature is built to avoid. Enqueuing
 * returns in microseconds and the wipe below it proceeds immediately.
 *
 * **It works from the grants, not from a destination — `settings-separation`.**
 * A destination now lives inside its vault's encrypted index, and panic is typed at
 * the lock screen with no key to any of them, so there is nothing to look the
 * folders up in. What the app *can* enumerate without a key is the set of tree URIs
 * it holds persistable grants on, which is exactly the set of folders it can write
 * to — every mirror destination is in it by construction, because a mirror cannot
 * be written without one.
 *
 * That set also holds export folders, so the sweep deletes **blob-shaped names
 * only** (`CloudMirror.destroyOn`): a panic that wiped a user's documents folder to
 * empty a backup that was never in it would be a far worse failure than the one this
 * exists to prevent.
 *
 * **A single-document grant is a backup file, and it is deleted whole** —
 * `backup-to-cloud-apps` D8. Export folders are tree grants; the only single-document
 * grant the app ever persists is a cloud app's backup file, and `BackupGrantScanTest`
 * keeps that true. `CloudMirror.destroy` tells the two apart by the URI's shape.
 *
 * **It also releases the grants.** Once the mirror is gone the app must not quietly
 * refill it from a surviving vault on the next import: panic means the backup is
 * over, not that it restarts with less in it. The write access goes with the files,
 * and the destination recorded inside a destroyed vault's index dies with the vault.
 *
 * **Best effort, silently.** A destination that cannot be reached returns without
 * error, without a retry and without a notice. `panic-pin` forbids the app
 * reporting anything about a panic unlock, and that includes reporting that a
 * deletion failed — the person reading that screen is the person the credential
 * exists to deceive.
 *
 * Separate from [MirrorWorker] rather than a flag on it: one enqueues uploads and
 * the other destroys, and a single worker branching on an input would put those
 * two intentions one boolean apart.
 */
class MirrorDestroyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "mirrorDestroy"
        private const val TAG = "VaultMirrorDestroy"

        /**
         * Returns as soon as WorkManager holds the request — nothing here waits on
         * a provider. `REPLACE`, so a destroy supersedes an upload run that a
         * just-finished import may have left queued under its own name.
         */
        fun enqueue(context: Context) {
            if (context.contentResolver.persistedUriPermissions.isEmpty()) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<MirrorDestroyWorker>().build(),
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val trees = applicationContext.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission }
            .map { it.uri }

        var removed = 0
        for (tree in trees) {
            removed += runCatching { CloudMirror.destroy(applicationContext, tree) }.getOrElse {
                Log.w(TAG, "a folder could not be reached", it)
                0
            }
            // Whether or not the files went, this install stops writing there. A
            // retained grant would refill the folder on the next import.
            CloudMirror.releaseGrant(applicationContext, tree)
        }

        Log.i(TAG, "panic: $removed mirrored file(s) removed")
        // Always success. A retry would be a second attempt to reach a provider
        // that is not there, and there is no user-visible outcome either way.
        Result.success()
    }
}
