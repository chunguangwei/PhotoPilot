package dev.photopilot.scaffold

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.CheckBox
import android.widget.ScrollView
import dev.photopilot.scaffold.data.aiframes.AIPreview
import dev.photopilot.scaffold.data.aiframes.AIRule
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
            db.collectionDao().upsert(CollectionEntity(source.id, null, source.id, "QA album", if (source.type == SourceType.LOCAL_FILES) "" else "same", CollectionType.SOURCE_FOLDER, source.id, now, now))
        }
        db.collectionDao().upsert(CollectionEntity("$prefix-child", sources[1].id, sources[1].id, "Trips", "Camera/Trips", CollectionType.SOURCE_FOLDER, "Camera/Trips", now, now))
        db.collectionDao().upsert(CollectionEntity("$prefix-photo-album", null, sources[0].id, "Camera photos", "albums/123456", CollectionType.SOURCE_FOLDER, "albums/123456", now, now))
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
                while (!(view.javaClass.getDeclaredField("initialized").apply { isAccessible = true }.get(view) as Boolean)) delay(100)
            }
            instrumentation.runOnMainSync {
                val folders = view.javaClass.getDeclaredField("folders").apply { isAccessible = true }.get(view) as View
                val labels = (descendants(view) + descendants(folders)).filterIsInstance<TextView>()
                var pickedFolder = false
                view.onLocalFolder = { pickedFolder = true }
                view.onLocalFiles = { fail("Folder action must not open the file picker") }
                labels.single { it.text.toString() in listOf("本机目录", "Device folder") && it.isClickable }.performClick()
                assertTrue(pickedFolder)
                assertEquals(1, labels.count { it.text.toString() in listOf("本机目录", "On this device") })
                assertTrue(labels.any { it.text.toString() == sources[2].displayName })
                assertTrue(labels.any { it.text.toString() == sources[3].displayName })
                val localChild = labels.filterIsInstance<CheckBox>().single { it.contentDescription?.toString() == "${sources[1].displayName}/Camera/Trips" }
                assertTrue(localChild.text.toString().contains("Camera"))
                assertTrue(labels.filterIsInstance<CheckBox>().any { it.text.toString() == "Camera photos" })
                assertFalse(labels.filterIsInstance<CheckBox>().any { it.text.toString().contains("123456") })
                assertFalse(descendants(view).filterIsInstance<EditText>().any { it.hint?.toString()?.contains("Frame names") == true || it.hint?.toString()?.contains("相框名称，每行") == true })
                val optional = labels.first { it.text.toString() in listOf("更多筛选条件（可选）", "More filters (optional)") }
                val threshold = descendants(view).filterIsInstance<EditText>().first { it.text.toString().toFloatOrNull() != null }
                assertFalse(threshold.isShown)
                optional.performClick()
                assertTrue(threshold.isShown)
                val identityMethod = view.javaClass.getDeclaredMethod("formIdentity").apply { isAccessible = true }
                val identity = identityMethod.invoke(view) as String
                for ((field, value) in mapOf(
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
                assertEquals("true", view.draftState()["previewReady"])
                val checks = view.javaClass.getDeclaredField("collectionChecks").apply { isAccessible = true }.get(view) as Map<*, *>
                (checks[sources[0].id] as CheckBox).isChecked = true
                val currentIdentity = identityMethod.invoke(view)
                val preview = AIPreview(env.aiFrames.models.models.first(), listOf(sources[0].id), true, AIRule("Family"), mapOf("retained-photo" to .8f), emptyMap(), emptyMap(), emptyMap(), 0)
                view.javaClass.getDeclaredField("pendingPreview").apply { isAccessible = true }.set(view, listOf(preview))
                view.javaClass.getDeclaredField("previewIdentity").apply { isAccessible = true }.set(view, currentIdentity)
                view.javaClass.getDeclaredMethod("showPreview", List::class.java).apply { isAccessible = true }.invoke(view, listOf(preview))
                view.javaClass.getDeclaredMethod("updateActions").apply { isAccessible = true }.invoke(view)
                val confirm = descendants(view).filterIsInstance<TextView>().single { it.text.toString() in listOf("确认创建相框", "Confirm frame creation") }
                assertFalse("AI preview confirmation must be fixed outside the form scroll", insideScroll(confirm))
                val status = view.javaClass.getDeclaredField("status").apply { isAccessible = true }.get(view) as View
                assertFalse("AI progress and validation feedback must stay visible", insideScroll(status))
                val scan = view.javaClass.getDeclaredField("startButton").apply { isAccessible = true }.get(view) as View
                assertFalse("Completed previews must offer confirmation as the primary action", scan.isShown)
                category.setText("Changed after preview")
                view.javaClass.getDeclaredMethod("updateActions").apply { isAccessible = true }.invoke(view)
                assertFalse(confirm.isShown)
                assertTrue(scan.isShown)
            }
            instrumentation.runOnMainSync { view.reloadCollections(sources[1].id) }
            withTimeout(10000) {
                while (true) {
                    var selected = false
                    instrumentation.runOnMainSync {
                        selected = org.json.JSONArray(view.draftState().getValue("folders")).let { array ->
                            (0 until array.length()).any { array.getString(it) == sources[1].id }
                        }
                    }
                    if (selected) break
                    delay(100)
                }
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
    private fun insideScroll(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent is ScrollView) return true
            parent = parent.parent
        }
        return false
    }
}
