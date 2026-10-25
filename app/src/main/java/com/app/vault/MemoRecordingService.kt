package com.app.vault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import kotlin.concurrent.thread

/**
 * The recording's foreground service — `voice-memo`, `destination-settings-and-slideshow` D6.
 *
 * Android lets an app keep the microphone with nothing of it on screen only inside a foreground
 * service of type `microphone`, started while the app is visible — which it is, because the owner
 * just pressed Record. This owns nothing but that right and the notification: the recorder stays
 * in [VoiceMemo].
 *
 * **The notification** names no vault, folder or memo. *Recording*, the elapsed time counted by
 * the system's own chronometer, and one action, *Stop*. On the lock screen it says *Recording* and
 * nothing else. Tapping it launches the app as any launch does — the cover, then the unlock — and
 * bypasses neither. **What it cannot hide**: Android draws the application's label in the header
 * and shows its microphone indicator, whichever launcher identity is chosen.
 */
class MemoRecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Stop from the shade. Listen-back only if the memo screen is up with its vault open;
            // otherwise the memo key goes now and the memo waits, sealed, for its vault.
            thread(name = "vault-memo-stop") {
                // The pooled store `MainActivity` writes to; a `BlobStore` is its directories.
                val store = BlobStore(java.io.File(filesDir, "blobs"))
                VoiceMemo.stop(this, store, keepKey = VoiceMemo.screenShowing && VaultSession.vmk() != null)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        val memo = VoiceMemo.current
        if (memo == null || !memo.recording) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(memo), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    private fun notification(memo: VoiceMemo.Memo): Notification {
        // `app-languages` D4: the channel's name and the text in the app's language, on 30–32
        // too, where this service's resources start in the device's.
        AppLanguage.apply(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel_recording), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        // The enabled launcher entry, whichever identity it is — never the activity directly,
        // which would skip the cover.
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val open = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MemoRecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val started = System.currentTimeMillis() - memo.elapsedMs()
        val public = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(TITLE))
            .build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(getString(TITLE))
            .setWhen(started)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            // Android 11 already shows a service's notification at once; the deferral this
            // opts out of arrived in 12.
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stop), getString(R.string.notification_stop), stop).build(),
            )
            .build()
    }

    companion object {
        const val CHANNEL = "recording"
        const val NOTIFICATION_ID = 7_301
        const val ACTION_STOP = "com.app.vault.memo.STOP"
        val TITLE = R.string.notification_recording

        /** Started at Record, while the app is on screen — the only time Android allows it. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, MemoRecordingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MemoRecordingService::class.java))
        }
    }
}
