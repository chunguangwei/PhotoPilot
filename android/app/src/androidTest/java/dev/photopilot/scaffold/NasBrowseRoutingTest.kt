package dev.photopilot.scaffold

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.ui.remote.RemoteFrameView
import dev.photopilot.scaffold.ui.sources.SourcesView
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NasBrowseRoutingTest {
    @Test fun sourceBrowseWithNoFramesOpensPhotosBeforeFramePicker() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "nas-browse-${UUID.randomUUID()}").apply { mkdirs() }
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = File(directory, name)
        }
        val db = DatabaseFactory.builder(isolatedContext).build()
        val env = AppEnvironment.create(isolatedContext, db)
        val now = System.currentTimeMillis()
        val source = SourceEntity("browse-fixture", SourceType.WEBDAV, "Browse fixture", "browse-fixture", null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true}", now, now)
        db.sourceDao().upsert(source)
        db.sourceAssetDao().upsert(SourceAssetEntity("browse-photo", source.id, "fixture.jpg", null, "fixture", 1, null, now, 0, AccessState.ACCESSIBLE, now, now))
        db.collectionDao().upsert(CollectionEntity("browse-folder", null, source.id, "Fixture folder", "", CollectionType.SOURCE_FOLDER, "", now, now))
        db.collectionDao().upsert(CollectionEntity("browse-child", "browse-folder", source.id, "Trips", "Trips", CollectionType.SOURCE_FOLDER, "", now, now))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            delay(6500)
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredField("environment").apply { isAccessible = true }.set(activity, env)
                val sources = MainActivity::class.java.getDeclaredMethod("buildSourcesView").apply { isAccessible = true }.invoke(activity) as SourcesView
                sources.renderNasSources(listOf(SourcesView.NasSourceModel(source.id, source.displayName, "Connected", catalogOnly = true)))
                descendants(sources).filterIsInstance<TextView>().single { it.text.toString() == context.getString(R.string.sources_browse_photos) }.performClick()
            }
            delay(700)
            lateinit var browser: RemoteFrameView
            instrumentation.runOnMainSync {
                browser = requireNotNull(descendants(activity.window.decorView).filterIsInstance<RemoteFrameView>().lastOrNull()) { "Browse photos must show the NAS browser before requesting a frame" }
                assertTrue(descendants(browser).filterIsInstance<TextView>().any { it.text.toString() == "fixture.jpg" })
                assertFalse(descendants(browser).filterIsInstance<android.widget.EditText>().any { it.isShown })
                val add = descendants(browser).filterIsInstance<TextView>().single { it.text.toString() == context.getString(R.string.library_add_to_frame) }
                assertFalse(add.isEnabled)
                descendants(browser).filterIsInstance<android.widget.CheckBox>().single { it.text.toString() == "fixture.jpg" }.performClick()
                assertTrue(add.isEnabled)
                add.performClick()
            }
            delay(200)
            assertTrue(instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(context.getString(R.string.import_pick_frame_new)).isNotEmpty())
            assertTrue(db.frameDao().getAll().isEmpty())
            instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(context.getString(R.string.common_cancel)).first().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.runOnMainSync {
                descendants(browser).filterIsInstance<TextView>().single { it.text.toString() == "Trips" }.performClick()
            }
            delay(200)
            instrumentation.runOnMainSync {
                descendants(browser).filterIsInstance<TextView>().single { it.text.toString() in setOf("Parent folder", "上级目录") }.performClick()
            }
            delay(200)
            instrumentation.runOnMainSync { browser.requestBack() }
            assertTrue(db.frameDao().getAll().isEmpty())
            instrumentation.runOnMainSync { assertFalse(descendants(activity.window.decorView).any { it === browser }) }
            val existing = env.frameManager.createFrame("Existing fixture")
            db.sourceAssetDao().upsert(SourceAssetEntity("existing-photo", source.id, "existing.jpg", null, "fixture", 1, null, now, 0, AccessState.ACCESSIBLE, now, now))
            db.frameSelectionDao().upsertResolved(existing.id, null, "existing-photo", FrameSelectionMode.INCLUDE, null, now)
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("openRemoteFrame", FrameEntity::class.java, Map::class.java).apply { isAccessible = true }.invoke(activity, existing, mapOf("sourceId" to source.id, "selected" to "[\"browse-photo\"]", "addSelected" to "true"))
            }
            delay(500)
            instrumentation.runOnMainSync {
                val editor = descendants(activity.window.decorView).filterIsInstance<RemoteFrameView>().last()
                assertEquals(2, org.json.JSONArray(editor.draftState().getValue("selected")).length())
                descendants(editor).filterIsInstance<TextView>().single { it.text.toString() in setOf("Save frame", "保存相框") }.performClick()
            }
            withTimeout(5000) {
                while (db.frameSelectionDao().getByFrame(existing.id).size != 2) delay(50)
            }
            assertEquals(setOf("existing-photo", "browse-photo"), db.frameSelectionDao().getByFrame(existing.id).map { it.sourceAssetId }.toSet())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            delay(100)
            env.remoteCopies.shutdown()
            env.remoteMedia.close()
            db.close()
            directory.deleteRecursively()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
