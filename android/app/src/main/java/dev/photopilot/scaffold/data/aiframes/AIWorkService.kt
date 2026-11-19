package dev.photopilot.scaffold.data.aiframes

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import dev.photopilot.scaffold.MainActivity
import dev.photopilot.scaffold.data.AppEnvironment
import kotlinx.coroutines.*
import java.util.UUID

class AIWorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = UUID.randomUUID().toString()
    private var wakeLock: PowerManager.WakeLock? = null
    private var coordinator: AITaskCoordinator? = null
    private var aiFrames: AIFrameService? = null
    private var initialization: Job? = null
    private var notificationLoop: Job? = null
    private var latestStartId = 0
    private var pausePending = false
    private var timedOut = false
    private var restoring = false
    private val chinese get() = dev.photopilot.scaffold.ui.UiPreferenceOverrides.wrap(this).resources.configuration.locales[0].language == "zh"
    private fun words(en: String, zh: String) = if (chinese) zh else en

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(dev.photopilot.scaffold.ui.UiPreferenceOverrides.wrap(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, words("AI tasks", "AI 任务"), NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(state: AINotificationState? = null): Notification {
        val destination = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("aiTaskId", state?.target)
            .putExtra("aiModelId", state?.modelId)
        val open = PendingIntent.getActivity(this, 0, destination, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, AIWorkService::class.java).setAction("pause"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pauseAction = Notification.Action.Builder(null, words("Pause", "暂停"), pause).build()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(state?.title?.ifEmpty { words("AI tasks", "AI 任务") } ?: words("AI tasks", "AI 任务"))
            .setContentText(state?.progress.orEmpty())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(pauseAction)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (timedOut && coordinator?.systemStopped != false) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        timedOut = false
        restoring = restoring || intent == null
        pausePending = pausePending || intent?.action == "pause"
        try {
            startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: RuntimeException) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val tasks = coordinator
        if (tasks != null) {
            tasks.attachService(owner)
            consumePause()
            ensureNotificationLoop(tasks)
        } else if (initialization?.isActive != true) {
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val frames = AppEnvironment.shared(applicationContext).aiFrames
                    aiFrames = frames
                    coordinator = frames.tasks
                    frames.tasks.attachService(owner)
                    ensureNotificationLoop(frames.tasks)
                    try {
                        if (pausePending) consumePause()
                        else {
                            frames.tasks.recover()
                            if (restoring) frames.resumeAutomatic().join()
                        }
                    } finally {
                        restoring = false
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    stopSelfResult(latestStartId)
                }
            }
            initialization = job
            job.start()
        }
        return START_STICKY
    }

    private fun consumePause() {
        if (!pausePending) return
        val frames = aiFrames ?: return
        pausePending = false
        scope.launch {
            for (row in frames.db.settingsKvDao().getByPrefix("ai.task.")) {
                val task = runCatching { AITaskRecord.parse(org.json.JSONObject(row.valueJson)) }.getOrNull() ?: continue
                if (frames.tasks.running(task.id) || task.mayRecover) frames.tasks.pause(task.id).join()
            }
            for (configuration in frames.state.all()) {
                if (frames.isRunning(configuration.frameId) || configuration.status in listOf("queued", "running", "paused", "system_paused", "waiting")) {
                    frames.pause(configuration.frameId)
                }
            }
        }
    }

    private fun ensureNotificationLoop(tasks: AITaskCoordinator) {
        if (notificationLoop?.isActive == true) return
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "photopilot:ai-work")
                .apply {
                    setReferenceCounted(false)
                    acquire(WAKE_TIMEOUT)
                }
        }
        notificationLoop = scope.launch {
            var lastRenew = SystemClock.elapsedRealtime()
            var emptyTicks = 0
            while (isActive && !timedOut) {
                emptyTicks = if (tasks.hasActiveWork || initialization?.isActive == true) 0 else emptyTicks + 1
                if (emptyTicks >= 3) {
                    if (stopSelfResult(latestStartId)) break
                    emptyTicks = 0
                }
                val state = tasks.notificationState(chinese)
                getSystemService(NotificationManager::class.java).notify(ID, notification(state))
                if (tasks.hasActiveWork && (wakeLock?.isHeld != true || SystemClock.elapsedRealtime() - lastRenew >= WAKE_RENEW)) {
                    wakeLock?.acquire(WAKE_TIMEOUT)
                    lastRenew = SystemClock.elapsedRealtime()
                }
                delay(1000)
            }
        }
    }

    private fun stopForSystem(reason: String) {
        timedOut = true
        coordinator?.systemStop(reason, owner)
        initialization?.cancel()
        notificationLoop?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForSystem("timeout")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            stopForSystem("memory")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        coordinator?.releaseService(owner)
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "ai-work"
        private const val ID = 4301
        private const val WAKE_TIMEOUT = 10 * 60 * 1000L
        private const val WAKE_RENEW = 5 * 60 * 1000L
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, AIWorkService::class.java)) }
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, AIWorkService::class.java))
        }
    }
}
