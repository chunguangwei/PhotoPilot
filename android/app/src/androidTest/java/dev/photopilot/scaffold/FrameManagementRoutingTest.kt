package dev.photopilot.scaffold

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.ui.remote.RemoteFrameView
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FrameManagementRoutingTest {
    @Test fun existingReferenceFrameOpensItsSelectionsThroughManageEntry() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val previousOverlay = env.settings.rawValue("overlay_stack")
        val token = "management-fixture-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val source = SourceEntity(token, SourceType.LOCAL_PHOTOS, "Management fixture", token, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"referenceOnly\":true}", now, now)
        env.database.sourceDao().upsert(source)
        val frame = env.frameManager.createFrame("Management fixture")
        repeat(55) { index ->
            val id = "$token-$index"
            env.database.sourceAssetDao().upsert(SourceAssetEntity(id, token, "$index.jpg", null, "fixture", 1, null, now, 0, AccessState.ACCESSIBLE, now, now))
            env.database.frameSelectionDao().upsertResolved(frame.id, null, id, FrameSelectionMode.INCLUDE, index.toLong(), now)
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            delay(6500)
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("openFrameEditor", FrameEntity::class.java, Map::class.java).apply { isAccessible = true }.invoke(activity, frame, mapOf("name" to frame.name, "selected" to "[]", "removedExcludes" to "[]", "rules" to "{}"))
            }
            var editor: RemoteFrameView? = null
            repeat(60) {
                instrumentation.runOnMainSync { editor = descendants(activity.window.decorView).filterIsInstance<RemoteFrameView>().lastOrNull() }
                if (editor != null) return@repeat
                delay(50)
            }
            assertNotNull("Manage must open the source-reference editor for a frame containing 55 source selections", editor)
            delay(500)
            instrumentation.runOnMainSync {
                assertEquals(55, org.json.JSONArray(requireNotNull(editor).draftState().getValue("selected")).length())
                assertTrue(descendants(requireNotNull(editor)).filterIsInstance<TextView>().any { it.text.toString().contains("55") })
            }
            assertEquals(55, env.database.frameSelectionDao().getByFrame(frame.id).size)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            env.settings.putRaw("overlay_stack", previousOverlay ?: "{\"stack\":[]}")
            env.frameManager.deleteFrame(frame.id)
            env.database.sourceDao().delete(source)
        }
    }

    @Test fun mixedSelectionsKeepPrivateRowsExclusionsRulesCoverAndManualOrderWhenSaved() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val previousOverlay = env.settings.rawValue("overlay_stack")
        val token = "mixed-management-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val source = SourceEntity(token, SourceType.WEBDAV, "Mixed fixture", token, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true}", now, now)
        env.database.sourceDao().upsert(source)
        val remote = "$token-remote"
        val excluded = "$token-excluded"
        for (id in listOf(remote, excluded)) env.database.sourceAssetDao().upsert(SourceAssetEntity(id, token, "$id.jpg", null, "fixture", 1, null, now, 0, AccessState.ACCESSIBLE, now, now))
        val privateId = "$token-private"
        val asset = AssetEntity(privateId, token, 1, AssetFormat.JPEG, 2, 2, 1, OrientationClass.SQUARE, null, CapturedAtSource.UNKNOWN, now, "$token.jpg", "Private fixture", AssetReadiness.READY, null, false, null, deletedAt = null, createdAt = now, updatedAt = now)
        env.database.assetDao().upsert(asset)
        val collection = CollectionEntity("$token-folder", null, token, "Fixture folder", "", CollectionType.SOURCE_FOLDER, "", now, now)
        env.database.collectionDao().upsert(collection)
        val created = env.frameManager.createFrame("Mixed fixture")
        val frame = created.copy(coverAssetId = privateId, orderMode = FrameOrderMode.MANUAL, settingsJson = "{\"mediaStorageMode\":\"remote\",\"fixtureSetting\":true}")
        env.database.frameDao().update(frame)
        env.aiFrames.state.put(dev.photopilot.scaffold.data.aiframes.AIConfiguration(frame.id, 1, "fixture", "fixture", listOf(collection.id), true, false, dev.photopilot.scaffold.data.aiframes.AIRule("Fixture", emptyList(), emptyList(), false, 0.5f)))
        env.database.frameSelectionDao().upsertResolved(frame.id, privateId, null, FrameSelectionMode.INCLUDE, 1, now)
        env.database.frameSelectionDao().upsertResolved(frame.id, null, remote, FrameSelectionMode.INCLUDE, 2, now)
        env.database.frameSelectionDao().upsertResolved(frame.id, null, excluded, FrameSelectionMode.EXCLUDE, null, now)
        env.database.frameDirectoryRuleDao().upsert(FrameDirectoryRuleEntity(frame.id, collection.id, true, true, now, now))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            delay(6500)
            lateinit var editor: RemoteFrameView
            var saved = false
            instrumentation.runOnMainSync {
                editor = RemoteFrameView(activity, env, frame, mapOf("manualOrder" to org.json.JSONArray(listOf(remote, privateId)).toString(), "manualOrderEdited" to "true"))
                editor.onSaved = { saved = true }
                activity.setContentView(editor)
            }
            delay(800)
            instrumentation.runOnMainSync {
                val selected = org.json.JSONArray(editor.draftState().getValue("selected"))
                assertEquals(setOf(privateId, remote), (0 until selected.length()).map { selected.getString(it) }.toSet())
                assertTrue(descendants(editor).filterIsInstance<android.widget.CheckBox>().any { it.text.toString() == "Private fixture" && it.isChecked })
                assertTrue(descendants(editor).filterIsInstance<TextView>().any { it.visibility == View.VISIBLE && it.text.toString() in setOf("AI classification rules", "AI 分类规则") })
                RemoteFrameView::class.java.getDeclaredMethod("save").apply { isAccessible = true }.invoke(editor)
            }
            withTimeout(5000) { while (!saved) delay(50) }
            val rows = env.database.frameSelectionDao().getByFrame(frame.id)
            assertEquals(FrameSelectionMode.EXCLUDE, rows.single { it.sourceAssetId == excluded }.mode)
            assertEquals(1L, rows.single { it.sourceAssetId == remote }.manualOrder)
            assertEquals(2L, rows.single { it.assetId == privateId }.manualOrder)
            assertEquals(listOf(true to true), env.database.frameDirectoryRuleDao().getByFrame(frame.id).map { it.recursive to it.includeFuture })
            val latest = requireNotNull(env.database.frameDao().getById(frame.id))
            assertEquals(privateId, latest.coverAssetId)
            assertEquals("remote", dev.photopilot.scaffold.data.logic.JsonMini.parseObject(latest.settingsJson)["mediaStorageMode"])
            assertEquals(true, dev.photopilot.scaffold.data.logic.JsonMini.parseObject(latest.settingsJson)["fixtureSetting"])
            saved = false
            instrumentation.runOnMainSync {
                editor = RemoteFrameView(activity, env, latest, mapOf("selected" to "[]"))
                editor.onSaved = { saved = true }
                activity.setContentView(editor)
            }
            delay(800)
            instrumentation.runOnMainSync { RemoteFrameView::class.java.getDeclaredMethod("save").apply { isAccessible = true }.invoke(editor) }
            withTimeout(5000) { while (!saved) delay(50) }
            assertEquals(listOf(excluded), env.database.frameSelectionDao().getByFrame(frame.id).map { it.refKey })
            assertEquals(1, env.database.frameDirectoryRuleDao().getByFrame(frame.id).size)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            env.settings.putRaw("overlay_stack", previousOverlay ?: "{\"stack\":[]}")
            env.frameManager.deleteFrame(frame.id)
            env.database.collectionDao().delete(collection)
            env.database.assetDao().delete(asset)
            env.database.sourceDao().delete(source)
            env.database.settingsKvDao().get("remote.sync.${frame.id}")?.let { env.database.settingsKvDao().delete(it) }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
