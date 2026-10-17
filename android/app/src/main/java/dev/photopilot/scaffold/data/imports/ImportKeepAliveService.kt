package dev.photopilot.scaffold.data.imports

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import dev.photopilot.scaffold.R

class ImportKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.import_title), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wakeLock != null) {
            return START_NOT_STICKY
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.import_title))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (failure: RuntimeException) {
            stopSelf()
            return START_NOT_STICKY
        }
        val lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        lock.acquire(WAKELOCK_TIMEOUT_MS)
        wakeLock = lock
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "imports"
        private const val NOTIFICATION_ID = 4201
        private const val WAKELOCK_TAG = "photopilot:import-keepalive"
        private const val WAKELOCK_TIMEOUT_MS = 30L * 60L * 1000L

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, ImportKeepAliveService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ImportKeepAliveService::class.java))
        }
    }
}
