package dev.photopilot.scaffold.data.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.photopilot.scaffold.R
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

class UpdateDownloadService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val versionName = intent.getStringExtra(EXTRA_VERSION_NAME).orEmpty()
        val totalSize = intent.getLongExtra(EXTRA_TOTAL_SIZE, 0L)
        if (wakeLock != null) {
            return START_NOT_STICKY
        }
        publish(UpdateState.Downloading(0L, totalSize))
        startForeground(
            NOTIFICATION_ID,
            progressNotification(0, totalSize),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
        lock.acquire(WAKELOCK_TIMEOUT_MS)
        wakeLock = lock
        thread(name = "update-download") {
            try {
                runDownload(url, versionName, totalSize)
            } finally {
                runCatching { if (lock.isHeld) lock.release() }
                wakeLock = null
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun runDownload(url: String, versionName: String, totalSize: Long) {
        val dir = File(cacheDir, UPDATES_DIR)
        val partFile = File(dir, PART_FILE_NAME)
        val delays = RETRY_DELAYS_MS
        var attempt = 0
        while (true) {
            try {
                UpdateDownloader.download(url, partFile, totalSize) { downloaded, total ->
                    publish(UpdateState.Downloading(downloaded, total))
                    notifyProgress(downloaded, total)
                }
                break
            } catch (error: IOException) {
                if (attempt >= delays.size) {
                    publish(UpdateState.Failed)
                    return
                }
                try {
                    Thread.sleep(delays[attempt])
                } catch (interrupted: InterruptedException) {
                    publish(UpdateState.Failed)
                    return
                }
                attempt++
            }
        }
        val length = partFile.length()
        if (totalSize > 0L && !ResumeLogic.isComplete(length, totalSize)) {
            publish(UpdateState.Failed)
            return
        }
        val apkFile = File(dir, APK_FILE_NAME)
        apkFile.delete()
        if (!partFile.renameTo(apkFile)) {
            publish(UpdateState.Failed)
            return
        }
        publish(UpdateState.Completed(apkFile.absolutePath, versionName))
        notifyCompleted(apkFile)
    }

    private fun progressNotification(downloaded: Long, total: Long): Notification {
        val percent = if (total > 0L) (downloaded * 100L / total).toInt() else 0
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.update_downloading))
            .setContentText(getString(R.string.update_progress, percent))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, total <= 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notifyProgress(downloaded: Long, total: Long) {
        notifyIfAllowed(NOTIFICATION_ID, progressNotification(downloaded, total))
    }

    private fun notifyCompleted(apkFile: File) {
        val install = PendingIntent.getActivity(
            this,
            0,
            UpdateInstaller.installIntent(this, apkFile),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.update_ready))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(install)
            .setAutoCancel(true)
            .build()
        notifyIfAllowed(COMPLETED_NOTIFICATION_ID, notification)
    }

    private fun notifyIfAllowed(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    companion object {
        const val CHANNEL_ID = "updates"
        const val EXTRA_URL = "url"
        const val EXTRA_VERSION_NAME = "versionName"
        const val EXTRA_TOTAL_SIZE = "totalSize"
        const val UPDATES_DIR = "updates"
        const val PART_FILE_NAME = "update.apk.part"
        const val APK_FILE_NAME = "update.apk"
        private const val NOTIFICATION_ID = 4101
        private const val COMPLETED_NOTIFICATION_ID = 4102
        private const val WAKELOCK_TAG = "photopilot:update-download"
        private const val WAKELOCK_TIMEOUT_MS = 30L * 60L * 1000L
        private val RETRY_DELAYS_MS = longArrayOf(1_000L, 4_000L, 15_000L)

        @Volatile
        var latestState: UpdateState = UpdateState.Idle
            private set

        @Volatile
        var progressListener: ((UpdateState) -> Unit)? = null

        fun resetState() {
            latestState = UpdateState.Idle
        }

        fun startIntent(context: Context, url: String, versionName: String, totalSize: Long): Intent =
            Intent(context, UpdateDownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_VERSION_NAME, versionName)
                .putExtra(EXTRA_TOTAL_SIZE, totalSize)

        internal fun publish(state: UpdateState) {
            latestState = state
            progressListener?.invoke(state)
        }
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val downloaded: Long, val total: Long) : UpdateState
    data class Completed(val apkPath: String, val versionName: String) : UpdateState
    data object Failed : UpdateState
}
