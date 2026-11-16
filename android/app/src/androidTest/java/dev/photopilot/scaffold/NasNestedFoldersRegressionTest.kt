package dev.photopilot.scaffold

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.ui.nas.NasDirectoryPickerView
import dev.photopilot.scaffold.ui.remote.RemoteFrameView
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NasNestedFoldersRegressionTest {
    @Test fun selectingSourceFolderIncludesNestedPhotosByDefaultButPreservesEditedScope() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val picker = NasDirectoryPickerView(instrumentation.targetContext)
            assertTrue(descendants(picker).filterIsInstance<CheckBox>().single().isChecked)
            picker.preselect(setOf("family"), false)
            assertFalse(descendants(picker).filterIsInstance<CheckBox>().single().isChecked)
        }
    }

    @Test fun oneTimeFolderSelectionHonorsSubfoldersWithoutFuturePhotoRule() = verifySelection(true)

    @Test fun emptyParentSelectsPhotosFromDeeperFolders() = verifySelection(false)

    private fun verifySelection(withRootPhoto: Boolean) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val db = env.database
        val id = "nested-selection-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val source = SourceEntity(id, SourceType.SMB, "Nested fixture", null, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true,\"recursive\":true}", now, now)
        db.sourceDao().upsert(source)
        for (path in listOf("", "family", "family/year")) {
            db.collectionDao().upsert(CollectionEntity(id = "$id/$path", parentId = null, sourceId = id, name = path.ifEmpty { "Root" }, relPath = path, type = CollectionType.SOURCE_FOLDER, sortKey = path, createdAt = now, updatedAt = now))
        }
        val paths = listOfNotNull("cover.png".takeIf { withRootPhoto }, "family/photo.png", "family/year/photo.png")
        for ((index, path) in paths.withIndex()) {
            db.sourceAssetDao().upsert(SourceAssetEntity("$id-$index", id, path, null, "1", 1, null, now, 0, AccessState.ACCESSIBLE, now, now))
        }
        env.settings.setOnboardingCompleted(true)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        lateinit var view: RemoteFrameView
        try {
            instrumentation.runOnMainSync {
                view = RemoteFrameView(activity, env, null, mapOf("sourceId" to id, "firstUse" to "true"))
                activity.setContentView(view)
            }
            withTimeout(15000) { while (descendants(view).filterIsInstance<CheckBox>().none { it.text.toString() in listOf("Include subfolders", "包含子目录") }) delay(100) }
            instrumentation.runOnMainSync {
                descendants(view).filterIsInstance<CheckBox>().first { it.text.toString() in listOf("Include subfolders", "包含子目录") }.isChecked = true
                descendants(view).filterIsInstance<TextView>().first { it.text.toString() in listOf("Select this folder", "选择此目录照片") }.performClick()
            }
            withTimeout(15000) { while (org.json.JSONArray(view.draftState().getValue("selected")).length() == 0) delay(100) }
            assertEquals("Nested descendants must be selected without enabling future additions", paths.size, org.json.JSONArray(view.draftState().getValue("selected")).length())
            assertEquals(0, org.json.JSONArray(view.draftState().getValue("rules")).length())
            val draft = view.draftState()
            instrumentation.runOnMainSync {
                view = RemoteFrameView(activity, env, null, draft)
                activity.setContentView(view)
            }
            withTimeout(15000) { while (descendants(view).filterIsInstance<CheckBox>().none { it.text.toString() in listOf("Include subfolders", "包含子目录") }) delay(100) }
            instrumentation.runOnMainSync {
                assertTrue(descendants(view).filterIsInstance<CheckBox>().first { it.text.toString() in listOf("Include subfolders", "包含子目录") }.isChecked)
            }
            assertTrue(db.importJobDao().getBySource(id).isEmpty())
            assertTrue(db.assetOriginDao().getBySourceAssets(db.sourceAssetDao().getBySource(id).map { it.id }).isEmpty())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            db.collectionDao().getBySource(id).sortedByDescending { it.relPath?.length ?: 0 }.forEach { db.collectionDao().delete(it) }
            db.sourceDao().delete(source)
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
