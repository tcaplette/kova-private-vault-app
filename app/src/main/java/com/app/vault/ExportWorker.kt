package com.app.vault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Unvault Everything" — P1-13.
 *
 * One run empties the vault: per item, decrypt to the destination, read the file
 * **back**, hash-compare, and only then delete the encrypted copy. I6 names this
 * task — nothing is deleted until its plaintext round-trips.
 *
 * **The index is the resume state.** A verified item is removed from the index as
 * part of exporting it, so whatever is still in the index has not been exported.
 * An interrupted run needs no progress file and no reconciliation: starting again
 * continues it. That is the whole of "fully resumable".
 *
 * **Nothing here persists a key**, exactly as `ImportWorker` does not. The VMK
 * comes from [VaultSession]; a worker that wakes with the vault locked retries.
 *
 * `intruder-captures-at-home` D9. **The capture store is a second source.** Its key is derived
 * from the open keyring, the store's index is read and emptied exactly as a vault's, and each
 * verified capture's removal appends a `0x47` "exported" to the store's own log. A worker that
 * wakes with the keyring locked waits, as one does for a locked vault.
 */
class ExportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {

        const val PROGRESS_DONE = "itemsDone"
        const val PROGRESS_TOTAL = "itemsTotal"
        const val PROGRESS_FAILED = "itemsFailed"
        const val PROGRESS_WAITING = "waitingForUnlock"

        private const val CHANNEL_ID = "export"
        private const val NOTIFICATION_ID = 2
        private const val TAG = "VaultExport"

        /**
         * Tag on every export request.
         *
         * Lives here for the same reason `IMPORT_TAG` does: P1-10's auto-lock asks
         * WorkManager whether long-running work is in flight and has no activity
         * to ask. Without this tag in that query, a backgrounded export locks the
         * vault under itself and parks on "wait for the user".
         */
        const val EXPORT_TAG = "export"

    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.notification_channel_export), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.notification_working))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override suspend fun doWork(): Result {
        // In memory, never in the worker's input. Gate 1 read an index blob ID
        // straight out of WorkManager's database on the import path; this one is
        // the same value, and the SAF tree URI names a folder the user chose. See
        // [WorkJobs].
        val job = WorkJobs.get<WorkJobs.ExportJob>(id) ?: return Result.failure()
        // `per-vault-intruder-capture` D6. A vault's capture store is a third source, keyed from the
        // session's VMK; like the keyring's, its key is derived for this run.
        val captures = job.captureStore || job.vaultCaptureStore
        val vmk = (when {
            job.captureStore -> VaultKeyringSession.captureStoreKey()
            job.vaultCaptureStore -> VaultSession.vmk()?.let { VaultKeys.captureStoreKey(it) }
            else -> VaultSession.vmk()
        })
            ?: run {
                // Locked. Not a failure — there is no key, and none on disk to fall
                // back to. Wait for the user, exactly as an import does.
                setProgress(workDataOf(PROGRESS_WAITING to true))
                return Result.retry()
            }
        try {
            return export(job, vmk, captures)
        } finally {
            // The capture store key is derived for this run and is this run's to zero; a
            // vault's is the session's.
            if (captures) vmk.fill(0)
        }
    }

    private suspend fun export(job: WorkJobs.ExportJob, vmk: ByteArray, captures: Boolean): Result {
        val indexId = job.indexId
        val strip = job.strip
        val tree = job.tree
        // P2-11. Null for the whole vault. In memory like everything else here —
        // see [WorkJobs.ExportJob].
        val selection = job.selection

        val store = BlobStore(File(applicationContext.filesDir, "blobs"))
        val destination: ExportDestination =
            if (tree != null) SafDestination(applicationContext, tree)
            else MediaStoreDestination(applicationContext)

        runCatching { setForeground(getForegroundInfo()) }

        // A previous export that died left invisible pending rows. Clear them
        // before writing more, so the destination never accumulates them.
        val swept = destination.sweep()
        if (swept > 0) Log.i(TAG, "swept $swept pending row(s) from a previous export")

        // Found by name at the destination, so a retry appends to the manifest a
        // previous attempt started rather than opening a second one.
        val manifest = ExportManifest.openAt(destination)

        // A vault's log records its export. The capture store's records each capture leaving,
        // below, and the session form would write to whatever vault happens to be open.
        if (!captures) TamperLog.append(store, TamperLog.EXPORT_STARTED)

        val result = withContext(Dispatchers.IO) {
            Unvaulter(store, destination, manifest, applicationContext).exportAll(
                indexId = indexId,
                vmk = vmk,
                strip = strip,
                selection = selection,
                isStopped = { isStopped },
                onRemoved = { db, item ->
                    if (captures) {
                        TamperLog.append(
                            store, db, indexId, vmk, TamperLog.INTRUDER_LEFT,
                            TamperLog.Left.leftPayload(
                                item.createdAt ?: 0L, TamperLog.LEFT_EXPORTED, CaptureLog.modeOf(item.filename),
                            ),
                        )
                    }
                },
            ) { p ->
                setProgressAsync(
                    workDataOf(
                        PROGRESS_DONE to p.exported,
                        PROGRESS_TOTAL to p.total,
                        PROGRESS_FAILED to p.failed,
                    )
                )
            }
        }

        if (!captures) TamperLog.append(store, TamperLog.EXPORT_COMPLETED, TamperLog.countPayload(result.exported))
        Log.i(TAG, "export finished: ${result.exported} exported, ${result.failed} failed")

        return if (isStopped) Result.retry()
        else Result.success(
            workDataOf(
                PROGRESS_DONE to result.exported,
                PROGRESS_TOTAL to result.total,
                PROGRESS_FAILED to result.failed,
            )
        )
    }
}
