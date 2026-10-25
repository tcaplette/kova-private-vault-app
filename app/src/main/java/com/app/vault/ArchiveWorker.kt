package com.app.vault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes a `.vault` archive — P1-14.
 *
 * A worker for the same reason `ExportWorker` is one: a multi-GB archive outlives
 * the screen, and P1-10's auto-lock has to know it is running. It shares
 * `ExportWorker.EXPORT_TAG` rather than adding a tag of its own — the suppression
 * rule is "long-running work the user can see in the shade", and this is that,
 * not a third kind.
 *
 * **No resumability.** The task does not ask for it, and an interrupted single
 * document is deleted and retried. Building resume for a file the user is
 * watching being written is speculative.
 */
class ArchiveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_PASSPHRASE = "passphrase"

        const val PROGRESS_DONE = "blobsDone"
        const val PROGRESS_TOTAL = "blobsTotal"
        const val PROGRESS_WAITING = "waitingForUnlock"

        private const val CHANNEL_ID = "archive"
        private const val NOTIFICATION_ID = 3
        private const val TAG = "VaultArchiveWork"

        /**
         * Only the destination travels in the worker's input.
         *
         * **The passphrase deliberately does not.** WorkManager persists input
         * `Data` to its own database inside `filesDir`, in the clear — the exact
         * thing `VaultSession` exists to prevent for the VMK, and a passphrase
         * there would be worse. It is handed over in memory through
         * [ArchivePassphrase] instead, and an archive whose process died before
         * the worker ran fails and is retried by the user rather than resurrecting
         * a secret from disk.
         */
        
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.notification_channel_archive), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.notification_working))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override suspend fun doWork(): Result {
        val vmk = VaultSession.vmk()
            ?: run {
                setProgress(workDataOf(PROGRESS_WAITING to true))
                return Result.retry()
            }
        val indexId = VaultSession.metadataBlobId() ?: return Result.failure()
        // The destination travels in memory too, beside the passphrase. Its
        // filename is one the user chose, and the reasoning above applies to it
        // unchanged — see [WorkJobs] for what Gate 1 found.
        val target = WorkJobs.get<WorkJobs.ArchiveJob>(id)?.target ?: return Result.failure()

        // Handed over in memory, taken once. See ArchivePassphrase for why it is
        // not in the worker's input.
        val passphrase = ArchivePassphrase.take() ?: return Result.failure()

        val store = BlobStore(File(applicationContext.filesDir, "blobs"))
        runCatching { setForeground(getForegroundInfo()) }

        TamperLog.append(store, TamperLog.EXPORT_STARTED)

        return withContext(Dispatchers.IO) {
            try {
                val db = MetadataDb.load(store, indexId, vmk)
                val count = applicationContext.contentResolver.openOutputStream(target)!!
                    .use { out ->
                        VaultArchive.write(out, vmk, db, store, passphrase) { done, total ->
                            setProgressAsync(
                                workDataOf(PROGRESS_DONE to done, PROGRESS_TOTAL to total)
                            )
                        }
                    }
                TamperLog.append(store, TamperLog.EXPORT_COMPLETED, TamperLog.countPayload(count))
                Log.i(TAG, "archive written: $count blobs")
                Result.success(workDataOf(PROGRESS_DONE to count, PROGRESS_TOTAL to count))
            } catch (e: Exception) {
                // An interrupted or failed archive leaves no partial document.
                Log.w(TAG, "archive failed", e)
                runCatching {
                    android.provider.DocumentsContract.deleteDocument(
                        applicationContext.contentResolver, target
                    )
                }
                Result.failure()
            } finally {
                passphrase.fill(0)
            }
        }
    }
}

/**
 * A one-shot hand-off for the archive passphrase.
 *
 * The passphrase cannot go in a worker's input `Data`: WorkManager persists that
 * to its own database inside `filesDir`, in the clear, which is precisely what
 * `VaultSession` exists to prevent for the VMK. So it is held in memory, taken
 * once by the worker, and cleared — and an archive whose process died before the
 * worker ran simply fails and is retried by the user, exactly as an import with
 * no key waits rather than inventing one.
 */
object ArchivePassphrase {

    @Volatile
    private var pending: ByteArray? = null

    @Synchronized
    fun offer(passphrase: ByteArray) {
        pending?.fill(0)
        pending = passphrase
    }

    /** Returns the pending passphrase and forgets it. */
    @Synchronized
    fun take(): ByteArray? {
        val p = pending
        pending = null
        return p
    }

    @Synchronized
    fun clear() {
        pending?.fill(0)
        pending = null
    }
}
