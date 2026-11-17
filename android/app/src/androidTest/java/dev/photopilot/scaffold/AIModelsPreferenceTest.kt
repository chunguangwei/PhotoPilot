package dev.photopilot.scaffold

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.ui.aiframes.AIModelsView
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AIModelsPreferenceTest {
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
                activity.setContentView(AIModelsView(activity, env, model.id) {})
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
