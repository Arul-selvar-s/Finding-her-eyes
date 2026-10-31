package app.facefiles

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.facefiles.domain.FaceScanControl
import app.facefiles.domain.FaceScanProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The scan itself runs in-process; this service only keeps the process alive (with a notification). */
object FaceScanHolder {
    @Volatile var control: FaceScanControl? = null
}

class FaceScanService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null
    private var seenRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val control = FaceScanHolder.control
        val first = notification("Finding faces…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, first)
        }
        if (control == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        job?.cancel()
        job = scope.launch {
            control.progress.collect { p ->
                if (p.running) seenRunning = true
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text(p)))
                if (p.finished || p.error != null || (seenRunning && !p.running)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun text(p: FaceScanProgress): String = when {
        p.error != null -> p.error
        p.running -> "${p.processed}/${p.total} files · ${p.facesFound} faces"
        else -> "Done · ${p.facesFound} faces"
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Face scan", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("FaceFiles")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

    private companion object {
        const val CHANNEL_ID = "face_scan"
        const val NOTIFICATION_ID = 1
    }
}
