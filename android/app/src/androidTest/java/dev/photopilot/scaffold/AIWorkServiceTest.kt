package dev.photopilot.scaffold

import android.Manifest
import android.app.ActivityManager
import android.app.LocaleManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.aiframes.AIWorkService
import dev.photopilot.scaffold.data.repo.AppSettingsRepository
import dev.photopilot.scaffold.ui.UiPreferenceOverrides
import dev.photopilot.scaffold.ui.aiframes.AIModelsView
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AIWorkServiceTest {
    @Test fun actualForegroundNotificationUsesAppLanguageRoutesToModelAndTimeoutStopsPromptly() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val keys = listOf(AppSettingsRepository.KEY_UI_LANGUAGE, AppSettingsRepository.KEY_ONBOARDING_COMPLETED, AppSettingsRepository.KEY_AUTO_PLAY)
        val previousSettings = keys.associateWith { env.database.settingsKvDao().get(it) }
        val localeManager = context.getSystemService(LocaleManager::class.java)
        val previousLocales = localeManager.applicationLocales
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }.trim()
        }
        val previousTimeout = shell("device_config get activity_manager data_sync_fgs_timeout_duration")
        val model = env.aiFrames.models.models.single { it.runtime == "onnx" }
        val taskId = "download:${model.id}"
        val taskKey = "ai.task.$taskId"
        val previousTask = env.database.settingsKvDao().get(taskKey)
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val leaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var activity: MainActivity? = null
        try {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            env.settings.setLanguage("zh")
            env.settings.setOnboardingCompleted(true)
            env.settings.setAutoPlayEnabled(false)
            UiPreferenceOverrides.update(context, language = "zh")
            instrumentation.runOnMainSync { localeManager.applicationLocales = LocaleList.forLanguageTags("zh-Hans") }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            delay(6500)
            shell("device_config put activity_manager data_sync_fgs_timeout_duration 2000")
            leaseScope.launch { env.aiFrames.models.withModelFiles(model) { entered.complete(Unit); gate.await() } }
            entered.await()
            env.aiFrames.tasks.startDownload(model, "auto", false)
            withTimeout(5000) { while (!env.aiFrames.tasks.serviceRunning) delay(50) }
            val notifications = context.getSystemService(NotificationManager::class.java)
            var foreground: android.service.notification.StatusBarNotification? = null
            withTimeout(4000) {
                while (foreground == null) {
                    foreground = notifications.activeNotifications.firstOrNull { it.id == 4301 && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == model.chinese }
                    delay(100)
                }
            }
            assertEquals(model.chinese, foreground?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            requireNotNull(foreground).notification.contentIntent.send()
            var modelPage: AIModelsView? = null
            withTimeout(4000) {
                while (modelPage == null) {
                    instrumentation.runOnMainSync { modelPage = WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<AIModelsView>().firstOrNull() }
                    delay(100)
                }
            }
            instrumentation.runOnMainSync {
                val title = descendants(requireNotNull(modelPage)).filterIsInstance<TextView>().first { it.text.toString() == model.chinese }
                assertTrue("Notification opens and scrolls to its focused model", title.getGlobalVisibleRect(android.graphics.Rect()))
            }
            shell("input keyevent KEYCODE_HOME")
            withTimeout(8000) { while (!env.aiFrames.tasks.systemStopped) delay(50) }
            withTimeout(3000) {
                while (context.getSystemService(ActivityManager::class.java).getRunningServices(100).any { it.service.className == AIWorkService::class.java.name }) delay(50)
            }
            assertFalse(env.aiFrames.tasks.serviceRunning)
            assertFalse(notifications.activeNotifications.any { it.id == 4301 })
            assertEquals("system_paused", env.aiFrames.tasks.record(taskId)?.status)
        } finally {
            if (previousTimeout.matches(Regex("[0-9]+"))) shell("device_config put activity_manager data_sync_fgs_timeout_duration $previousTimeout")
            else shell("device_config delete activity_manager data_sync_fgs_timeout_duration")
            env.aiFrames.tasks.pause(taskId)
            gate.complete(Unit)
            leaseScope.coroutineContext[Job]?.cancelAndJoin()
            AIWorkService.stop(context)
            instrumentation.runOnMainSync { activity?.finish(); localeManager.applicationLocales = previousLocales }
            for ((key, row) in previousSettings) {
                if (row == null) env.database.settingsKvDao().get(key)?.let { env.database.settingsKvDao().delete(it) }
                else env.database.settingsKvDao().upsert(row)
            }
            if (previousTask == null) env.database.settingsKvDao().get(taskKey)?.let { env.database.settingsKvDao().delete(it) }
            else env.database.settingsKvDao().upsert(previousTask)
        }
    }
    @Test fun repeatedStartsDuringEnvironmentInitializationAcquireOnlyOneWakeLock() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        delay(6500)
        AIWorkService.stop(context)
        delay(300)
        fun lockCount(): Int {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys power")
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                reader.readLines().count { it.contains("PARTIAL_WAKE_LOCK") && it.contains("photopilot:ai-work") }
            }
        }
        val ownerField = AppEnvironment::class.java.getDeclaredField("processOwner").apply { isAccessible = true }
        val originalOwner = ownerField.get(null)
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val delayedOwner = dev.photopilot.scaffold.data.ProcessResourceOwner(initScope) {
            entered.complete(Unit)
            gate.await()
            env
        }
        val workOwner = "service-reentry-fixture"
        val model = env.aiFrames.models.models.first()
        val pendingId = "service-initialization-pause-fixture"
        val pendingKey = "ai.task.$pendingId"
        val previousPending = env.database.settingsKvDao().get(pendingKey)
        val pending = dev.photopilot.scaffold.data.aiframes.AITaskRecord(pendingId, "download", model.id, model.revision, status = "queued")
        env.settings.putRaw(pendingKey, pending.json().toString())
        try {
            ownerField.set(null, delayedOwner)
            instrumentation.runOnMainSync {
                env.aiFrames.tasks.begin(workOwner, model)
                repeat(4) { AIWorkService.start(context) }
            }
            withTimeout(3000) { entered.await() }
            instrumentation.runOnMainSync { context.startForegroundService(Intent(context, AIWorkService::class.java).setAction("pause")) }
            delay(300)
            gate.complete(Unit)
            withTimeout(5000) { while (lockCount() == 0) delay(50) }
            assertEquals("All starts share one initializer, notification loop and wake owner", 1, lockCount())
            delay(500)
            assertTrue("Pause during initialization persists for queued recovered work", env.aiFrames.tasks.record(pendingId)?.userPaused == true)
        } finally {
            ownerField.set(null, originalOwner)
            gate.complete(Unit)
            env.aiFrames.tasks.end(workOwner)
            AIWorkService.stop(context)
            initScope.coroutineContext[Job]?.cancelAndJoin()
            if (previousPending == null) env.database.settingsKvDao().deleteKey(pendingKey) else env.database.settingsKvDao().upsert(previousPending)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
