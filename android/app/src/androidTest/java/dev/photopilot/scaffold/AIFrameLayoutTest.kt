package dev.photopilot.scaffold

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.CollectionEntity
import dev.photopilot.scaffold.data.db.CollectionType
import dev.photopilot.scaffold.ui.aiframes.AIFrameView
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

class AIFrameLayoutTest {
    @Test fun renderBilingualWorkflowWithSyntheticFolders() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val language = args.getString("language") ?: "en"
        val variant = args.getString("variant") ?: "portrait"
        val environment = AppEnvironment.shared(instrumentation.targetContext)
        environment.database.collectionDao().upsert(CollectionEntity("ai-validation-folder", null, null, "Validation samples", null, CollectionType.USER_FOLDER, "validation", 1, 1))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        SystemClock.sleep(700)
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var view: AIFrameView
        instrumentation.runOnMainSync {
            val configuration = Configuration(activity.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language)))
                fontScale = if (variant.contains("large")) 1.5f else 1f
                if (variant.contains("dark")) uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
            val themedContext = android.view.ContextThemeWrapper(activity, R.style.Theme_PhotoPilot).apply { applyOverrideConfiguration(configuration) }
            view = AIFrameView(themedContext, environment, uiScope, null, {}, {}, restoredDraft = mapOf("draftId" to java.util.UUID.randomUUID().toString(), "automatic" to "false", "folders" to "[]"))
            activity.setContentView(android.widget.FrameLayout(activity).apply { fitsSystemWindows = true; addView(view) })
        }
        withTimeout(15000) {
            var ready = false
            while (!ready) {
                instrumentation.runOnMainSync {
                    ready = view.javaClass.getDeclaredField("initialized").apply { isAccessible = true }.getBoolean(view)
                }
                if (!ready) delay(50)
            }
        }
        instrumentation.runOnMainSync {
            args.getString("model")?.let { modelId ->
                val index = environment.aiFrames.models.models.indexOfFirst { it.id == modelId }
                require(index >= 0)
                descendants(view).filterIsInstance<android.widget.Spinner>().first().setSelection(index)
            }
            val fields = descendants(view).filterIsInstance<EditText>()
            fields[0].setText(if (language == "zh") "猫和狗\n海边的日落" else "Cats and dogs\nSunset at the beach")
            val automatic = descendants(view).filterIsInstance<CheckBox>().first { it.text.contains(if (language == "zh") "持续" else "Automatically") }
            assertFalse(automatic.isChecked)
            descendants(view).filterIsInstance<CheckBox>().firstOrNull { it.text == "Validation samples" }?.isChecked = true
        }
        val folder = File(instrumentation.targetContext.cacheDir, "ai-ui").apply { mkdirs() }
        fun capture(section: String) {
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            File(folder, "$language-$variant-$section.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
        instrumentation.waitForIdleSync()
        val scroll = descendants(view).filterIsInstance<android.widget.ScrollView>().first()
        instrumentation.runOnMainSync { scroll.scrollTo(0, 0) }
        SystemClock.sleep(250)
        capture("models")
        instrumentation.runOnMainSync { scroll.scrollTo(0, (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0) / 2) }
        SystemClock.sleep(250)
        capture("categories")
        instrumentation.runOnMainSync { scroll.fullScroll(View.FOCUS_DOWN) }
        SystemClock.sleep(300)
        capture("rules")
        instrumentation.runOnMainSync { activity.finish() }
        uiScope.cancel()
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
