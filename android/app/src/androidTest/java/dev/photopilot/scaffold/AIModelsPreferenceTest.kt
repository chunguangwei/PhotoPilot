package dev.photopilot.scaffold

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.ui.aiframes.AIModelsView
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AIModelsPreferenceTest {
    @Test fun queuedModelFocusIgnoresControlsRemovedBySettingsRerender() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val prefs = context.getSharedPreferences("ui_state", android.content.Context.MODE_PRIVATE)
        val groupKey = "settings.section.collapsed.settings.ai_models"
        val previousCollapsed = if (prefs.contains(groupKey)) prefs.getBoolean(groupKey, true) else null
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        val model = dev.photopilot.scaffold.ui.settings.SettingsView.Model(
            appearance = "system", language = "system", autoPlay = false,
            wifiOnly = true, shareStatistics = false, reduceMotionActive = false,
            versionName = "test", storage = null, weatherEnabledDefault = false,
            weatherPlaceName = null, weatherUnitsIndex = 0,
            weatherObservedText = null, weatherAttributionText = null,
        )
        lateinit var settings: dev.photopilot.scaffold.ui.settings.SettingsView
        lateinit var removedModels: AIModelsView
        val rerendered = CompletableDeferred<Unit>()
        try {
            delay(6500)
            instrumentation.runOnMainSync {
                settings = dev.photopilot.scaffold.ui.settings.SettingsView(activity)
                settings.createAIModels = { AIModelsView(activity, env) }
                settings.render(model)
                activity.setContentView(settings)
                settings.focusAIModels(env.aiFrames.models.models.last().id)
                removedModels = descendants(settings).filterIsInstance<AIModelsView>().single()
                assertTrue(removedModels.isAttachedToWindow)
                val pendingField = AIModelsView::class.java.getDeclaredField("pendingFocus").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val pending = requireNotNull(pendingField.get(removedModels) as? (() -> Unit))
                pendingField.set(removedModels, null)
                pending()
                settings.postOnAnimation {
                    settings.render(model)
                    rerendered.complete(Unit)
                }
            }
            withTimeout(5000) { rerendered.await() }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val currentModels = descendants(settings).filterIsInstance<AIModelsView>().single()
                assertFalse("The queued target belongs to the removed panel", removedModels.isAttachedToWindow)
                assertNotSame(removedModels, currentModels)
                assertTrue("Rerendered model controls remain usable after draining the queued focus", currentModels.isAttachedToWindow)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            prefs.edit().apply {
                if (previousCollapsed == null) remove(groupKey) else putBoolean(groupKey, previousCollapsed)
            }.commit()
        }
    }

    @Test fun returningFromFocusedSettingsRefreshesOriginalModelPreferences() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val model = env.aiFrames.models.models.single { it.runtime == "onnx" }
        val taskId = "download:${model.id}"
        val previousTask = env.aiFrames.tasks.record(taskId)
        val uiPrefs = context.getSharedPreferences("ui_state", android.content.Context.MODE_PRIVATE)
        val uiKeys = listOf("settings.section.collapsed.settings.ai_models", "settings.section.collapsed.settings.network")
        val previousUI = uiKeys.associateWith { if (uiPrefs.contains(it)) uiPrefs.getBoolean(it, true) else null }
        val settingsKeys = listOf(
            dev.photopilot.scaffold.data.repo.AppSettingsRepository.KEY_ONBOARDING_COMPLETED,
            dev.photopilot.scaffold.data.repo.AppSettingsRepository.KEY_AUTO_PLAY,
            dev.photopilot.scaffold.data.repo.AppSettingsRepository.KEY_NETWORK_WIFI_ONLY,
            "overlay_stack",
        )
        val previousSettings = settingsKeys.associateWith { env.database.settingsKvDao().get(it) }
        env.settings.setOnboardingCompleted(true)
        env.settings.setAutoPlayEnabled(false)
        env.settings.setWifiOnly(true)
        env.settings.putRaw("overlay_stack", """{"stack":[]}""")
        env.aiFrames.tasks.saveDownloadPreferences(model, "auto", false)
        var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun modelControls(settings: dev.photopilot.scaffold.ui.settings.SettingsView): Pair<android.widget.Spinner, android.widget.Switch> {
            val title = descendants(settings).filterIsInstance<android.widget.TextView>().single { it.text.toString() in setOf(model.english, model.chinese) }
            val card = title.parent as android.view.View
            return descendants(card).filterIsInstance<android.widget.Spinner>().single() to descendants(card).filterIsInstance<android.widget.Switch>().single()
        }
        suspend fun await(condition: () -> Boolean) {
            withTimeout(10000) { while (!condition()) delay(50) }
        }
        try {
            delay(6500)
            var mainReady = false
            await {
                instrumentation.runOnMainSync {
                    val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .filterIsInstance<MainActivity>().lastOrNull()
                    if (resumed != null) {
                        activity = resumed
                        val container = descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.MainContainerView>().singleOrNull()
                        if (container != null) {
                            container.selectSection(dev.photopilot.scaffold.ui.MainSection.SETTINGS)
                            mainReady = true
                        }
                    }
                }
                mainReady
            }
            var base: dev.photopilot.scaffold.ui.settings.SettingsView? = null
            var loaded = false
            await {
                instrumentation.runOnMainSync {
                    base = descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.settings.SettingsView>().singleOrNull()
                    loaded = base?.let { descendants(it).any { child -> child is AIModelsView } } == true
                }
                loaded
            }
            instrumentation.runOnMainSync {
                requireNotNull(base).focusAIModels(model.id)
                MainActivity::class.java.getDeclaredMethod("openAIModels", String::class.java).apply { isAccessible = true }.invoke(activity, model.id)
            }
            var focused: dev.photopilot.scaffold.ui.settings.SettingsView? = null
            await {
                instrumentation.runOnMainSync {
                    focused = descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.settings.SettingsView>().lastOrNull { it !== base }
                    loaded = focused?.let { descendants(it).any { child -> child is AIModelsView } } == true
                }
                loaded
            }
            delay(400)
            instrumentation.runOnMainSync {
                val controls = modelControls(requireNotNull(focused))
                controls.first.setSelection(1)
                controls.second.isChecked = true
                val wifi = descendants(requireNotNull(focused)).filterIsInstance<android.widget.Switch>().single { it.text.toString() == activity.getString(R.string.settings_wifi_only) }
                val networkHeading = descendants(requireNotNull(focused)).filterIsInstance<android.widget.TextView>().single { it.text.toString() == activity.getString(R.string.settings_network) }
                if ((wifi.parent as android.view.View).visibility == android.view.View.GONE) (networkHeading.parent as android.view.View).performClick()
                assertTrue("The focused settings panel initially reflects Wi-Fi only", wifi.isChecked)
                wifi.performClick()
                assertFalse("The focused settings Wi-Fi switch changes immediately", wifi.isChecked)
            }
            await { env.aiFrames.tasks.cached(taskId)?.let { it.source == "domestic" && it.allowMetered } == true }
            withTimeout(10000) { while (env.settings.isWifiOnly()) delay(50) }
            instrumentation.runOnMainSync { activity.onBackPressed() }
            var preferencesRestored = false
            await {
                instrumentation.runOnMainSync {
                    val controls = modelControls(requireNotNull(base))
                    preferencesRestored = controls.first.selectedItemPosition == 1 && controls.second.isChecked
                }
                preferencesRestored
            }
            instrumentation.runOnMainSync {
                assertTrue("The original settings instance remains attached", requireNotNull(base).isAttachedToWindow)
                val controls = modelControls(requireNotNull(base))
                assertEquals("Returning refreshes the original panel's source", 1, controls.first.selectedItemPosition)
                assertTrue("Returning refreshes the original panel's mobile network choice", controls.second.isChecked)
                val wifi = descendants(requireNotNull(base)).filterIsInstance<android.widget.Switch>().single { it.text.toString() == activity.getString(R.string.settings_wifi_only) }
                assertFalse("Returning also refreshes the original panel's global Wi-Fi setting", wifi.isChecked)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            env.aiFrames.tasks.saveDownloadPreferences(model, previousTask?.source ?: "auto", previousTask?.allowMetered ?: false)
            uiPrefs.edit().apply {
                previousUI.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value) }
            }.commit()
            for ((key, row) in previousSettings) {
                if (row == null) env.database.settingsKvDao().get(key)?.let { env.database.settingsKvDao().delete(it) }
                else env.database.settingsKvDao().upsert(row)
            }
        }
    }

    @Test fun modelSectionExpandsInlineAndPersistsIndependently() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val prefs = context.getSharedPreferences("ui_state", android.content.Context.MODE_PRIVATE)
        val aiKey = "settings.section.collapsed.settings.ai_models"
        val networkKey = "settings.section.collapsed.settings.network"
        val oldAI = if (prefs.contains(aiKey)) prefs.getBoolean(aiKey, false) else null
        val oldNetwork = if (prefs.contains(networkKey)) prefs.getBoolean(networkKey, false) else null
        prefs.edit().remove(aiKey).putBoolean(networkKey, true).commit()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        delay(6500)
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun settings(): dev.photopilot.scaffold.ui.settings.SettingsView {
            val view = dev.photopilot.scaffold.ui.settings.SettingsView(activity)
            view.createAIModels = { AIModelsView(activity, env) }
            view.render(dev.photopilot.scaffold.ui.settings.SettingsView.Model(
                appearance = "system", language = "system", autoPlay = false,
                wifiOnly = true, shareStatistics = false, reduceMotionActive = false,
                versionName = "test", storage = null, weatherEnabledDefault = false,
                weatherPlaceName = null, weatherUnitsIndex = 0,
                weatherObservedText = null, weatherAttributionText = null,
            ))
            return view
        }
        try {
            instrumentation.runOnMainSync {
                var view = settings()
                activity.setContentView(view)
                var models = descendants(view).filterIsInstance<AIModelsView>().single()
                assertEquals("The new group starts collapsed", android.view.View.GONE, (models.parent as android.view.View).visibility)
                val heading = descendants(view).filterIsInstance<android.widget.TextView>().single { it.text.toString() == activity.getString(R.string.settings_ai_models) }
                (heading.parent as android.view.View).performClick()
                assertEquals(android.view.View.VISIBLE, (models.parent as android.view.View).visibility)
                assertFalse(prefs.getBoolean(aiKey, true))
                assertTrue("AI expansion cannot rewrite network expansion", prefs.getBoolean(networkKey, false))
                assertEquals(1, descendants(view).filterIsInstance<android.widget.ScrollView>().size)
                assertFalse("There is no second management navigation button", descendants(view).filterIsInstance<android.widget.TextView>().any { it.text.toString() in setOf("AI model management", "AI 模型管理") })
                view = settings()
                activity.setContentView(view)
                models = descendants(view).filterIsInstance<AIModelsView>().single()
                assertEquals("Expansion is remembered on return", android.view.View.VISIBLE, (models.parent as android.view.View).visibility)
                val restoredHeading = descendants(view).filterIsInstance<android.widget.TextView>().single { it.text.toString() == activity.getString(R.string.settings_ai_models) }
                (restoredHeading.parent as android.view.View).performClick()
                assertEquals(android.view.View.GONE, (models.parent as android.view.View).visibility)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            val edit = prefs.edit()
            if (oldAI == null) edit.remove(aiKey) else edit.putBoolean(aiKey, oldAI)
            if (oldNetwork == null) edit.remove(networkKey) else edit.putBoolean(networkKey, oldNetwork)
            edit.commit()
        }
    }

    @Test fun openingModelManagementPreservesSavedDownloadPreferencesWithoutWriting() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val model = env.aiFrames.models.models.single { it.runtime == "onnx" }
        env.aiFrames.tasks.saveDownloadPreferences(model, "domestic", true)
        val key = "ai.task.download:${model.id}"
        val before = requireNotNull(env.database.settingsKvDao().get(key))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        delay(6500)
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(AIModelsView(activity, env))
            }
            delay(1000)
            val after = requireNotNull(env.database.settingsKvDao().get(key))
            assertEquals("Opening settings must not rewrite the user's network choice", before.valueJson, after.valueJson)
            assertEquals("Initial spinner callbacks must not persist a new choice", before.updatedAt, after.updatedAt)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
