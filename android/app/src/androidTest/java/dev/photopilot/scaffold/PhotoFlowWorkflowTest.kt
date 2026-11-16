package dev.photopilot.scaffold

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.db.SourceEntity
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.repo.SelectionRefEdit
import dev.photopilot.scaffold.ui.frames.FrameEditView
import dev.photopilot.scaffold.ui.remote.RemoteFrameView
import java.io.File
import kotlinx.coroutines.*
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.junit.Assert.*
import org.junit.Test

class PhotoFlowWorkflowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var env: AppEnvironment

    private suspend fun fixture(firstUse: Boolean, body: suspend (MainActivity, SourceEntity, List<String>, List<String>) -> Unit) {
        env = AppEnvironment.shared(context)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        InstrumentationRegistry.getArguments().getString("language")?.let { env.settings.setLanguage(it) }
        val names = (1..3).map { "PhotoFlow-${System.currentTimeMillis()}-$it.png" }
        val uris = names.map { name ->
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoPilotFlowTests")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
            context.contentResolver.openOutputStream(uri)!!.use { stream ->
                Bitmap.createBitmap(24, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(214, 175, 112)) }.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        }
        val originalFrames = env.database.frameDao().getAll().map { it.id }.toSet()
        if (firstUse) assertTrue("Dedicated test device must have no frames", originalFrames.isEmpty())
        env.settings.setOnboardingCompleted(!firstUse)
        env.settings.putRaw("overlay_stack", "{\"stack\":[]}")
        val source = env.localReferences.indexPhotos()
        val rows = env.database.sourceAssetDao().getBySource(source.id)
        val ids = names.map { name -> rows.single { env.localReferences.displayName(it) == name }.id }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        delay(6500)
        try { body(activity, source, ids, names) }
        finally {
            instrumentation.runOnMainSync { activity.finish() }
            env.database.frameDao().getAll().filter { it.id !in originalFrames }.forEach { env.frameManager.deleteFrame(it.id) }
            uris.forEach { context.contentResolver.delete(it, null, null) }
            env.settings.setOnboardingCompleted(true)
        }
    }

    @Test fun firstUseExactSelectionDoubleConfirmPlaysDirectly() = runBlocking {
        fixture(true) { activity, source, ids, names ->
            connect(activity, source, ids.take(2))
            val view = awaitEditor(activity)
            await { descendants(view).filterIsInstance<CheckBox>().any { it.text.toString() == names.first() } }
            assertEquals(ids.take(2), jsonList(view.draftState().getValue("selected")))
            assertFalse(descendants(view).filterIsInstance<CheckBox>().any { it.text.toString() == names.last() })
            assertTrue(descendants(view).filterIsInstance<EditText>().isEmpty())
            assertTrue(env.database.frameDao().getAll().isEmpty())
            val photo = descendants(view).filterIsInstance<CheckBox>().single { it.text.toString() == names.first() }
            assertTrue(photo.isChecked)
            touch(photo)
            touch(photo)
            val texts = descendants(view).filterIsInstance<TextView>().map { it.text.toString() }
            assertFalse(texts.any { it.contains("MiB") || it in setOf("Refresh metadata", "刷新照片索引", "Select this folder", "选择此目录照片", "Connect WebDAV / SMB", "连接 WebDAV / SMB", "Choose photo library", "选择本机相册", "Choose files or folder", "选择文件或目录") })
            assertEquals(1, texts.count { it in setOf("Add photos", "添加照片") })
            capture("first-use-scoped-photos")
            instrumentation.runOnMainSync { activity.onBackPressed() }
            clickDialog(listOf("Cancel", "取消"))
            assertSame(view, activityField(activity, "remoteFrameView"))
            assertTrue(env.database.frameDao().getAll().isEmpty())
            val save = descendants(view).filterIsInstance<TextView>().single { it.text.toString() in setOf("Confirm and play", "确认并播放") }
            touch(save, twice = true)
            await { env.database.frameDao().getAll().size == 1 && activityField(activity, "playerView") != null }
            val frame = env.database.frameDao().getAll().single()
            assertEquals(ids.take(2).map { "remote:$it" }.toSet(), env.frames.buildPlayQueue(frame.id).toSet())
            assertTrue(env.settings.isOnboardingCompleted())
            await {
                val player = activityField(activity, "playerView") as? View
                player != null && descendants(player).filterIsInstance<android.widget.ImageView>().any { it.drawable is android.graphics.drawable.BitmapDrawable }
            }
            delay(1000)
            capture("first-use-direct-player")
        }
    }

    @Test fun managementConnectionAndCanceledPickerDoNotCreateFrame() = runBlocking {
        fixture(false) { activity, source, ids, _ ->
            val before = env.database.frameDao().getAll().size
            connect(activity, source, ids)
            delay(300)
            assertNull(activityField(activity, "remoteFrameView"))
            assertEquals(before, env.database.frameDao().getAll().size)
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }.invoke(activity, 42, 0, null)
            }
            assertNull(activityField(activity, "remoteFrameView"))
            capture("management-connection-return")
        }
    }

    @Test fun targetAdditionRetainsDraftAcrossPickerAndConcurrentMetadata() = runBlocking {
        fixture(false) { activity, source, ids, names ->
            val frame = env.frameManager.createFrame("Original")
            env.frames.save(frame.id, 0, addIncludes = listOf(SelectionRefEdit(null, ids.first())))
            val baseline = requireNotNull(env.database.frameDao().getById(frame.id))
            instrumentation.runOnMainSync { openRemote(activity, baseline, mapOf("sourceId" to source.id)) }
            val view = awaitEditor(activity)
            await { jsonList(view.draftState().getValue("selected")).contains(ids.first()) }
            instrumentation.runOnMainSync { descendants(view).filterIsInstance<EditText>().single().setText("Retained draft") }
            connect(activity, source, listOf(ids.last()))
            assertSame(view, activityField(activity, "remoteFrameView"))
            assertEquals(setOf(ids.first(), ids.last()), jsonList(view.draftState().getValue("selected")).toSet())
            assertEquals("Retained draft", view.draftState()["name"])
            val concurrent = requireNotNull(env.database.frameDao().getById(frame.id))
            env.database.frameDao().update(concurrent.copy(settingsJson = "{\"clockStyle\":\"digital\"}", name = "Concurrent"))
            await { descendants(view).filterIsInstance<CheckBox>().any { it.text.toString() == names.last() } }
            capture("target-add-retained-draft")
            touch(descendants(view).filterIsInstance<TextView>().single { it.text.toString() in setOf("Save frame", "保存相框") })
            await { env.database.frameDao().getById(frame.id)?.name == "Retained draft" }
            assertEquals(setOf("remote:${ids.first()}", "remote:${ids.last()}"), env.frames.buildPlayQueue(frame.id).toSet())
            assertEquals("digital", JsonMini.parseObject(env.database.frameDao().getById(frame.id)?.settingsJson)["clockStyle"])
        }
    }

    @Test fun failedFirstSaveRollsBackAndDraftCanRetry() = runBlocking {
        fixture(true) { activity, source, ids, names ->
            connect(activity, source, listOf(ids.first()))
            val view = awaitEditor(activity)
            await { descendants(view).filterIsInstance<CheckBox>().any { it.text.toString() == names.first() } }
            env.database.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER photoflow_fail BEFORE INSERT ON frame_selection BEGIN SELECT RAISE(ABORT, 'fixture failure'); END")
            try {
                touch(descendants(view).filterIsInstance<TextView>().single { it.text.toString() in setOf("Confirm and play", "确认并播放") })
                await { descendants(view).filterIsInstance<TextView>().any { it.text.toString().startsWith("Unable to load") || it.text.toString().startsWith("加载失败") } }
                assertTrue(env.database.frameDao().getAll().isEmpty())
                assertEquals(listOf(ids.first()), jsonList(view.draftState().getValue("selected")))
                capture("failed-save-retained-draft")
            } finally { env.database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS photoflow_fail") }
            touch(descendants(view).filterIsInstance<TextView>().single { it.text.toString() in setOf("Confirm and play", "确认并播放") })
            await { env.database.frameDao().getAll().size == 1 }
        }
    }

    @Test fun dirtyLocalEditorRequiresDecisionBeforeCrossEditorAndBack() = runBlocking {
        fixture(false) { activity, _, _, _ ->
            val frame = env.frameManager.createFrame("Gate fixture")
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("openFrameEditor", FrameEntity::class.java, Map::class.java).apply { isAccessible = true }.invoke(activity, frame, null)
            }
            await { activityField(activity, "editorModel") != null }
            val editor = activityField(activity, "frameEditView") as FrameEditView
            instrumentation.runOnMainSync {
                descendants(editor).filterIsInstance<EditText>().single().setText("Unsaved draft")
                editor.onRemotePhotos?.invoke()
            }
            delay(300)
            assertNull(activityField(activity, "remoteFrameView"))
            assertEquals("Unsaved draft", editor.currentState().name)
            assertEquals("Gate fixture", env.database.frameDao().getById(frame.id)?.name)
            clickDialog(listOf("Cancel", "取消"))
            instrumentation.runOnMainSync { activity.onBackPressed() }
            delay(300)
            assertSame(editor, activityField(activity, "frameEditView"))
            capture("dirty-cross-editor-and-back")
            clickDialog(listOf("Cancel", "取消"))
            instrumentation.runOnMainSync { editor.onRemotePhotos?.invoke() }
            delay(300)
            clickDialog(listOf("Save", "保存"))
            await { activityField(activity, "remoteFrameView") != null }
            assertEquals("Unsaved draft", env.database.frameDao().getById(frame.id)?.name)
            assertEquals(1, env.database.frameDao().getAll().size)
        }
    }

    @Test fun cleanNewEditorReferenceSaveReopensTheSavedTarget() = runBlocking {
        fixture(false) { activity, source, ids, names ->
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("openFrameEditor", FrameEntity::class.java, Map::class.java).apply { isAccessible = true }.invoke(activity, null, null)
            }
            await { activityField(activity, "editorModel") != null }
            val editor = activityField(activity, "frameEditView") as FrameEditView
            assertFalse(editor.hasChanges())
            instrumentation.runOnMainSync { editor.onRemotePhotos?.invoke() }
            val reference = awaitEditor(activity)
            connect(activity, source, listOf(ids.first()))
            await { descendants(reference).filterIsInstance<CheckBox>().any { it.text.toString() == names.first() } }
            touch(descendants(reference).filterIsInstance<TextView>().single { it.text.toString() in setOf("Save frame", "保存相框") })
            await { activityField(activity, "remoteFrameView") == null && activityField(activity, "frameEditView") !== editor && activityField(activity, "editorModel") != null }
            val saved = env.database.frameDao().getAll().single()
            val reopened = activityField(activity, "frameEditView") as FrameEditView
            assertEquals(saved.name, reopened.currentState().name)
            instrumentation.runOnMainSync { reopened.onSave?.invoke(reopened.currentState()) }
            await { activityField(activity, "frameEditView") == null }
            assertEquals(listOf(saved.id), env.database.frameDao().getAll().map { it.id })
            assertEquals(listOf("remote:${ids.first()}"), env.frames.buildPlayQueue(saved.id))
        }
    }

    @Test fun untouchedManualOrderRestorationAndNameSaveRetainConcurrentOrder() = runBlocking {
        fixture(false) { activity, _, _, _ ->
            val now = System.currentTimeMillis()
            val assetIds = (1..2).map { "flow-order-$now-$it" }
            for (id in assetIds) {
                val file = File(context.filesDir, "originals/$id.png").apply { parentFile!!.mkdirs() }
                file.outputStream().use { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff000000.toInt() or (id.hashCode() and 0xffffff)) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
                env.database.assetDao().upsert(AssetEntity(id, dev.photopilot.scaffold.data.logic.ContentHash.sha256Hex(file.readBytes()), file.length(), AssetFormat.PNG, 16, 16, 1, OrientationClass.SQUARE, null, CapturedAtSource.UNKNOWN, now, "$id.png", "$id.png", AssetReadiness.READY, null, false, null, deletedAt = null, createdAt = now, updatedAt = now))
            }
            var manualFrameId: String? = null
            try {
                val frame = env.frameManager.createFrame("Manual baseline")
                manualFrameId = frame.id
                env.database.frameDao().update(frame.copy(orderMode = FrameOrderMode.MANUAL))
                env.frames.save(frame.id, 0, addIncludes = assetIds.map { SelectionRefEdit(it, null) }, manualOrderRefs = assetIds)
                val baseline = requireNotNull(env.database.frameDao().getById(frame.id))
                instrumentation.runOnMainSync {
                    MainActivity::class.java.getDeclaredMethod("openFrameEditor", FrameEntity::class.java, Map::class.java).apply { isAccessible = true }.invoke(activity, baseline, null)
                }
                await { activityField(activity, "editorModel") != null }
                val editor = activityField(activity, "frameEditView") as FrameEditView
                lateinit var untouchedDraft: Map<String, String>
                instrumentation.runOnMainSync {
                    assertFalse("Initial bind must be clean", editor.hasChanges())
                    untouchedDraft = editor.draftState()
                    editor.restoreDraft(untouchedDraft)
                    assertFalse("Restoring an untouched manual draft must remain clean", editor.hasChanges())
                    assertNull(editor.currentState().manualOrder)
                }
                env.frames.save(frame.id, baseline.selectionVersion, manualOrderRefs = assetIds.reversed())
                instrumentation.runOnMainSync {
                    descendants(editor).filterIsInstance<EditText>().single().setText("Renamed only")
                    editor.onSave?.invoke(editor.currentState())
                }
                await { env.database.frameDao().getById(frame.id)?.name == "Renamed only" }
                assertEquals(assetIds.reversed(), env.database.frameSelectionDao().getByFrame(frame.id).filter { it.mode == FrameSelectionMode.INCLUDE }.sortedBy { it.manualOrder }.map { it.refKey })
                instrumentation.runOnMainSync {
                    val model = FrameEditView.Model("Manual baseline", FrameOrderMode.MANUAL, assetIds.toSet(), emptySet(), emptyMap(), false, assetIds.reversed().mapIndexed { index, id -> id to (index + 1).toLong() })
                    val restored = FrameEditView(activity, File(context.filesDir, "originals"))
                    restored.bind(model)
                    restored.restoreDraft(untouchedDraft)
                    assertFalse(restored.hasChanges())
                    assertEquals(assetIds.reversed(), restored.currentManualOrder())
                    restored.setManualOrder(assetIds)
                    val changedDraft = restored.draftState()
                    restored.bind(model)
                    restored.restoreDraft(changedDraft)
                    assertTrue(restored.hasChanges())
                    assertEquals(assetIds, restored.currentState().manualOrder)
                }
            } finally {
                manualFrameId?.let { env.frameManager.deleteFrame(it) }
                assetIds.forEach { id -> env.database.assetDao().getById(id)?.let { env.database.assetDao().delete(it) }; File(context.filesDir, "originals/$id.png").delete() }
            }
        }
    }

    @Test fun connectedSourceRowChoosesExistingTargetAndExplicitNew() = runBlocking {
        fixture(false) { activity, source, ids, names ->
            val frame = env.frameManager.createFrame("Source target A")
            val container = descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.MainContainerView>().single()
            instrumentation.runOnMainSync { container.selectSection(dev.photopilot.scaffold.ui.MainSection.SOURCES) }
            await("Sources section loaded") { activityField(activity, "activeSourcesView") != null }
            val sources = activityField(activity, "activeSourcesView") as dev.photopilot.scaffold.ui.sources.SourcesView
            instrumentation.runOnMainSync { sources.onLocalReferenceClick?.invoke(source.id) }
            clickDialog(listOf(frame.name))
            val editor = awaitEditor(activity)
            openFlowAlbum(editor)
            await("Source album photos loaded") { descendants(editor).filterIsInstance<CheckBox>().any { it.text.toString() == names.first() } }
            assertEquals(source.id, editor.draftState()["sourceId"])
            touch(descendants(editor).filterIsInstance<CheckBox>().single { it.text.toString() == names.first() })
            touch(requireNotNull(descendants(editor).filterIsInstance<TextView>().singleOrNull { it.text.toString() in setOf("Save frame", "保存相框") }) { "Existing editor save missing: ${descendants(editor).filterIsInstance<TextView>().map { it.text }}" })
            await("Existing source target saved") { activityField(activity, "remoteFrameView") == null }
            assertEquals(listOf(frame.id), env.database.frameDao().getAll().map { it.id })
            assertEquals(listOf("remote:${ids.first()}"), env.frames.buildPlayQueue(frame.id))
            instrumentation.runOnMainSync { sources.onLocalReferenceClick?.invoke(source.id) }
            clickDialog(listOf(context.getString(R.string.import_pick_frame_new)))
            val newEditor = awaitEditor(activity)
            openFlowAlbum(newEditor)
            await("New source target album loaded") { descendants(newEditor).filterIsInstance<CheckBox>().any { it.text.toString() == names.last() } }
            assertEquals(source.id, newEditor.draftState()["sourceId"])
            touch(descendants(newEditor).filterIsInstance<CheckBox>().single { it.text.toString() == names.last() })
            touch(descendants(newEditor).filterIsInstance<TextView>().single { it.text.toString() in setOf("Confirm and play", "确认并播放") })
            await("Explicit new source frame saved") { env.database.frameDao().getAll().size == 2 }
            assertEquals(listOf("remote:${ids.first()}"), env.frames.buildPlayQueue(frame.id))
        }
    }

    @Test fun librarySourcesAndFolderCallbacksChooseTargetWithoutImplicitCreation() = runBlocking {
        fixture(false) { activity, source, ids, names ->
            val frame = env.frameManager.createFrame("Library target A")
            val container = descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.MainContainerView>().single()
            instrumentation.runOnMainSync { container.selectSection(dev.photopilot.scaffold.ui.MainSection.LIBRARY) }
            await("Library section loaded") { activityField(activity, "activeLibraryView") != null }
            val library = activityField(activity, "activeLibraryView") as dev.photopilot.scaffold.ui.library.LibraryView
            instrumentation.runOnMainSync { library.onBrowseSourcePhotos?.invoke() }
            clickDialog(listOf(frame.name))
            val generic = awaitEditor(activity)
            await("Generic source choices loaded") { descendants(generic).filterIsInstance<TextView>().any { it.text.toString() == source.displayName } }
            touch(descendants(generic).filterIsInstance<TextView>().first { it.text.toString() == source.displayName })
            clickDialog(listOf(source.displayName))
            openFlowAlbum(generic)
            await("Generic album photos loaded") { descendants(generic).filterIsInstance<CheckBox>().any { it.text.toString() == names.first() } }
            touch(descendants(generic).filterIsInstance<CheckBox>().single { it.text.toString() == names.first() })
            touch(descendants(generic).filterIsInstance<TextView>().single { it.text.toString() in setOf("Save frame", "保存相框") })
            await("Generic existing target saved") { activityField(activity, "remoteFrameView") == null }
            assertEquals(listOf(frame.id), env.database.frameDao().getAll().map { it.id })
            assertEquals(listOf("remote:${ids.first()}"), env.frames.buildPlayQueue(frame.id))
            val folder = env.database.collectionDao().getBySource(source.id).single { it.name == "PhotoPilotFlowTests" }
            touch(descendants(library).filterIsInstance<TextView>().single { it.text.toString() == context.getString(R.string.library_tab_folders) })
            await("Library folder rows loaded") { descendants(library).filterIsInstance<TextView>().any { it.text.toString() == folder.name } }
            val folderLabel = descendants(library).filterIsInstance<TextView>().first { it.text.toString() == folder.name }
            touch(folderLabel.parent as View)
            clickDialog(listOf(frame.name))
            val scoped = awaitEditor(activity)
            assertEquals(source.id, scoped.draftState()["sourceId"])
            assertEquals(folder.relPath, scoped.draftState()["folder"])
            await("Scoped folder photos loaded") { descendants(scoped).filterIsInstance<CheckBox>().any { it.text.toString() == names.last() } }
            touch(descendants(scoped).filterIsInstance<CheckBox>().single { it.text.toString() == names.last() })
            touch(descendants(scoped).filterIsInstance<TextView>().single { it.text.toString() in setOf("Save frame", "保存相框") })
            await("Scoped existing target saved") { activityField(activity, "remoteFrameView") == null }
            assertEquals(listOf(frame.id), env.database.frameDao().getAll().map { it.id })
            assertEquals(setOf("remote:${ids.first()}", "remote:${ids.last()}"), env.frames.buildPlayQueue(frame.id).toSet())
            instrumentation.runOnMainSync { library.onBrowseSourcePhotos?.invoke() }
            clickDialog(listOf(context.getString(R.string.import_pick_frame_new)))
            val explicitNew = awaitEditor(activity)
            assertEquals("", explicitNew.draftState()["folder"])
            assertEquals(1, env.database.frameDao().getAll().size)
            instrumentation.runOnMainSync { explicitNew.onBack?.invoke() }
        }
    }


    private suspend fun openFlowAlbum(editor: RemoteFrameView) {
        for (label in listOf("albums", "PhotoPilotFlowTests")) {
            await("Photo folder $label loaded") { descendants(editor).filterIsInstance<TextView>().any { it.text.toString() == label } }
            instrumentation.runOnMainSync { descendants(editor).filterIsInstance<TextView>().first { it.text.toString() == label }.performClick() }
        }
    }

    private suspend fun clickDialog(labels: List<String>) {
        fun node(): android.view.accessibility.AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            return labels.firstNotNullOfOrNull { label -> instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.text?.toString()?.equals(label, ignoreCase = true) == true && it.isClickable } }
        }
        await("Dialog choices $labels") { node() != null }
        requireNotNull(node()).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        instrumentation.waitForIdleSync()
    }

    private suspend fun connect(activity: MainActivity, source: SourceEntity, ids: List<String>) = withContext(Dispatchers.Main) {
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            MainActivity::class.java.getDeclaredMethod("finishSourceConnection", SourceEntity::class.java, List::class.java, kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }.invoke(activity, source, ids, continuation)
        }
    }
    private fun openRemote(activity: MainActivity, frame: FrameEntity?, draft: Map<String, String>) {
        MainActivity::class.java.getDeclaredMethod("openRemoteFrame", FrameEntity::class.java, Map::class.java).apply { isAccessible = true }.invoke(activity, frame, draft)
    }
    private fun activityField(activity: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private suspend fun awaitEditor(activity: MainActivity): RemoteFrameView {
        await { activityField(activity, "remoteFrameView") != null }
        return activityField(activity, "remoteFrameView") as RemoteFrameView
    }
    private fun jsonList(raw: String) = org.json.JSONArray(raw).let { values -> (0 until values.length()).map { values.getString(it) } }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private suspend fun await(message: String = "Photo-flow condition", predicate: suspend () -> Boolean) {
        try { withTimeout(15000) { while (!predicate()) delay(100) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(message, error) }
    }
    private fun touch(view: View, twice: Boolean = false) {
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true) }
        android.os.SystemClock.sleep(400)
        val rect = Rect()
        assertTrue("Control must have a visible touch area", view.getGlobalVisibleRect(rect))
        assertTrue("Control must have nonzero visible height", rect.height() > 0)
        repeat(if (twice) 2 else 1) {
            val now = android.os.SystemClock.uptimeMillis()
            instrumentation.sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, rect.centerX().toFloat(), rect.centerY().toFloat(), 0))
            instrumentation.sendPointerSync(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, rect.centerX().toFloat(), rect.centerY().toFloat(), 0))
        }
        instrumentation.waitForIdleSync()
    }
    private fun capture(name: String) {
        val directory = File(context.cacheDir, "photo-flow-evidence").apply { mkdirs() }
        File(directory, "$name-${InstrumentationRegistry.getArguments().getString("variant") ?: "portrait"}.png").outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
