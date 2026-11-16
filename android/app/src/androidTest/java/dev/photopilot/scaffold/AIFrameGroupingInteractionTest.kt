package dev.photopilot.scaffold

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.ui.aiframes.AIFrameView
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AIFrameGroupingInteractionTest {
    @Test fun localSourcesShareAGroupAndNASRemainDistinctWithOptionalFilters() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val db = env.database
        val prefix = "group-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val types = listOf(SourceType.LOCAL_PHOTOS, SourceType.LOCAL_FILES, SourceType.SMB, SourceType.WEBDAV)
        val sources = types.mapIndexed { index, type ->
            SourceEntity("$prefix-$index", type, "$prefix NAS $index", null, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, null, now, now)
        }
        sources.forEach { source ->
            db.sourceDao().upsert(source)
            db.collectionDao().upsert(CollectionEntity(source.id, null, source.id, "QA album", "same", CollectionType.SOURCE_FOLDER, source.id, now, now))
        }
        env.settings.setOnboardingCompleted(true)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lateinit var view: AIFrameView
        try {
            instrumentation.runOnMainSync {
                view = AIFrameView(activity, env, scope, null, {}, {})
                activity.setContentView(view)
                val draft = mapOf("previewReady" to "true", "previewNames" to "[\"Retained name\"]", "folders" to "[\"folder\"]")
                val restoring = AIFrameView(activity, env, scope, null, {}, {}, restoredDraft = draft)
                assertEquals(draft, restoring.draftState())
            }
            withTimeout(15000) {
                while (descendants(view).filterIsInstance<TextView>().none { it.text.toString() == sources.last().displayName }) delay(100)
            }
            instrumentation.runOnMainSync {
                val labels = descendants(view).filterIsInstance<TextView>()
                assertEquals(1, labels.count { it.text.toString() in listOf("本机目录", "On this device") })
                assertTrue(labels.any { it.text.toString() == sources[2].displayName })
                assertTrue(labels.any { it.text.toString() == sources[3].displayName })
                assertFalse(descendants(view).filterIsInstance<EditText>().any { it.hint?.toString()?.contains("Frame names") == true || it.hint?.toString()?.contains("相框名称，每行") == true })
                val optional = labels.first { it.text.toString() in listOf("更多筛选条件（可选）", "More filters (optional)") }
                val threshold = descendants(view).filterIsInstance<EditText>().first { it.text.toString().toFloatOrNull() != null }
                assertFalse(threshold.isShown)
                optional.performClick()
                assertTrue(threshold.isShown)
                val identityMethod = view.javaClass.getDeclaredMethod("formIdentity").apply { isAccessible = true }
                val identity = identityMethod.invoke(view) as String
                val job = Job()
                for ((field, value) in mapOf(
                    "previewJob" to job,
                    "activePreviewIdentity" to identity,
                    "restoredPreviewIdentity" to identity,
                    "restoredPreviewNames" to listOf("Retained name"),
                    "restoredPreviewChoices" to listOf(setOf("retained-photo")),
                )) {
                    view.javaClass.getDeclaredField(field).apply { isAccessible = true }.set(view, value)
                }
                val retained = view.draftState()
                assertEquals("true", retained["previewReady"])
                assertEquals("Retained name", org.json.JSONArray(retained.getValue("previewNames")).getString(0))
                val category = descendants(view).filterIsInstance<EditText>().first {
                    it.hint?.toString()?.contains("Category descriptions") == true || it.hint?.toString()?.contains("类别描述") == true
                }
                category.setText("A different category")
                val changed = view.draftState()
                assertEquals("false", changed["previewReady"])
                assertEquals(0, org.json.JSONArray(changed.getValue("previewNames")).length())
                assertEquals(0, org.json.JSONArray(changed.getValue("previewChoices")).length())
                view.javaClass.getDeclaredField("restoredPreviewIdentity").apply { isAccessible = true }.set(view, identityMethod.invoke(view))
                labels.first { it.text.toString() in listOf("取消分类", "Cancel classification") }.performClick()
                assertEquals("false", view.draftState()["previewReady"])
                job.cancel()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            scope.cancel()
            sources.forEach { source ->
                db.collectionDao().getBySource(source.id).forEach { db.collectionDao().delete(it) }
                db.sourceDao().delete(source)
            }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
