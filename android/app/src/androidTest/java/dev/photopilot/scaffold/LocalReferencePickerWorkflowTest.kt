package dev.photopilot.scaffold

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.aiframes.AIEmbedder
import dev.photopilot.scaffold.data.aiframes.mediaCandidates
import dev.photopilot.scaffold.data.aiframes.mediaVector
import dev.photopilot.scaffold.data.db.SettingsKvEntity
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.references.LocalReferenceRepository
import dev.photopilot.scaffold.ui.remote.RemoteFrameView
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalReferencePickerWorkflowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val key = "test.reference.native-picker"
    private var systemClicks = 0

    @Test fun prepareThroughSystemFilePickerAndSaveSelection() = runBlocking {
        val context = instrumentation.targetContext
        val language = InstrumentationRegistry.getArguments().getString("language") ?: "en"
        if (android.os.Build.VERSION.SDK_INT >= 33) context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(language)
        val env = AppEnvironment.shared(context)
        env.settings.setLanguage(language)
        val resolver = context.contentResolver
        val name = "PhotoPilot-reference-${System.currentTimeMillis()}.png"
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoPilotReferenceTests")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        resolver.openOutputStream(uri)!!.use { Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(218, 164, 87)); setPixel(0, 0, android.graphics.Color.BLUE); setPixel(3, 2, android.graphics.Color.GREEN) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        env.settings.setOnboardingCompleted(true)
        env.settings.putRaw("overlay_stack", "{\"stack\":[]}")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        delay(6500)
        val originals = File(context.filesDir, "originals").walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()
        try {
            val automation = instrumentation.uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            launchSystemPicker {
                MainActivity::class.java.getDeclaredMethod("launchFilePicker").apply { isAccessible = true }.invoke(activity)
            }
            touchSystemText("Search")
            await("System picker must expose search input") { editableSystemNode() != null }
            requireNotNull(editableSystemNode()).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name) })
            await("System picker must expose the generated photo") { systemNode(name) != null }
            selectSystemDocument(name)
            instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", "Selected the real system document target\n") })
            await("File selection must index the chosen document") {
                val source = env.database.sourceDao().getAll().firstOrNull { it.endpointKey == LocalReferenceRepository.FILES_FINGERPRINT }
                source != null && env.database.sourceAssetDao().getBySource(source.id).any { env.localReferences.displayName(it) == name }
            }
            val source = env.database.sourceDao().getAll().single { it.endpointKey == LocalReferenceRepository.FILES_FINGERPRINT }
            val selected = env.database.sourceAssetDao().getBySource(source.id).single { env.localReferences.displayName(it) == name }
            val metadata = JsonMini.parseObject(env.database.settingsKvDao().get("reference.asset.${selected.id}")?.valueJson)
            val documentUri = Uri.parse(metadata["uri"] as String)
            assertTrue(resolver.persistedUriPermissions.any { it.uri == documentUri && it.isReadPermission })
            assertTrue(env.database.importJobDao().getBySource(source.id).isEmpty())
            assertTrue(env.database.assetOriginDao().getBySourceAssets(listOf(selected.id)).isEmpty())
            assertEquals(originals, File(context.filesDir, "originals").walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet())
            assertFalse(descendants(activity.window.decorView).any { it is RemoteFrameView })
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("openRemoteFrame", dev.photopilot.scaffold.data.db.FrameEntity::class.java, Map::class.java).apply { isAccessible = true }.invoke(activity, null, mapOf("sourceId" to source.id))
            }
            await("Reference grid must show the selected file") {
                val editor = descendants(activity.window.decorView).filterIsInstance<RemoteFrameView>().lastOrNull()
                editor != null && descendants(editor).filterIsInstance<CheckBox>().any { it.text.toString() == name }
            }
            val editor = descendants(activity.window.decorView).filterIsInstance<RemoteFrameView>().last()
            val checkbox = descendants(editor).filterIsInstance<CheckBox>().single { it.text.toString() == name }
            val formName = descendants(editor).filterIsInstance<EditText>().first()
            val frameName = "Native SAF $name"
            instrumentation.runOnMainSync {
                formName.setText(frameName)
                assertTrue(descendants(activity.window.decorView).filterIsInstance<Switch>().none { it.isShown })
            }
            touch(checkbox)
            assertTrue(checkbox.isChecked)
            val row = checkbox.parent as ViewGroup
            touch(descendants(row).filterIsInstance<TextView>().single { it.text.toString() in setOf("Preview", "预览") })
            await("Reference preview must expose the cover and hide actions") { systemNode(context.getString(R.string.frames_set_cover)) != null && systemNode(context.getString(R.string.delete_hide_globally)) != null }
            capture("local-reference-detail-$language-${InstrumentationRegistry.getArguments().getString("variant") ?: "portrait"}")
            touchSystemText(context.getString(R.string.frames_set_cover))
            await("Set cover must update the reference draft") { editor.draftState()["coverRef"] == selected.id }
            val save = descendants(editor).filterIsInstance<TextView>().last { it.text.toString() in setOf("Save frame", "保存相框") }
            touch(save)
            await("Save frame must commit the source selection") { env.database.frameDao().getAll().any { it.name == frameName } }
            val frame = env.database.frameDao().getAll().single { it.name == frameName }
            assertEquals(listOf("remote:${selected.id}"), env.frames.buildPlayQueue(frame.id))
            assertEquals(selected.id, env.frames.coverItem(frame.id)?.sourceAssetId)
            val item = env.frames.playbackItems(frame.id).single()
            assertEquals(4, env.remoteMedia.bitmap(item, 32, 32)?.width)
            val collection = requireNotNull(env.database.collectionDao().getBySourceAndPath(source.id, ""))
            val candidate = requireNotNull(env.aiFrames.mediaCandidates(listOf(collection.id), false)[item.id])
            var decodedForAI = false
            val embedder = object : AIEmbedder {
                override fun text(text: String): FloatArray = error("Image path expected")
                override fun image(bitmap: Bitmap): FloatArray {
                    assertEquals(4, bitmap.width)
                    assertEquals(3, bitmap.height)
                    decodedForAI = true
                    return floatArrayOf(1f, 0f)
                }
                override fun close() = Unit
            }
            assertArrayEquals(floatArrayOf(1f, 0f), env.aiFrames.mediaVector(candidate, embedder, env.aiFrames.models.models.first()), 0f)
            assertTrue(decodedForAI)
            val now = System.currentTimeMillis()
            env.database.settingsKvDao().upsert(SettingsKvEntity(key, JsonMini.toJson(mapOf("sourceId" to source.id, "assetId" to selected.id, "frameId" to frame.id, "uri" to documentUri.toString(), "fixtureUri" to uri.toString(), "pid" to android.os.Process.myPid())), now, now))
        } finally {
            capture("reference-picker-before-finish")
            instrumentation.runOnMainSync { activity.finish() }
        }
        Unit
    }

    @Test fun prepareTreeThroughSystemPickerAndSaveSelection() = runBlocking {
        val context = instrumentation.targetContext
        val language = InstrumentationRegistry.getArguments().getString("language") ?: "en"
        if (android.os.Build.VERSION.SDK_INT >= 33) context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(language)
        val env = AppEnvironment.shared(context)
        env.settings.setLanguage(language)
        val resolver = context.contentResolver
        val name = "PhotoPilot-tree-${System.currentTimeMillis()}.png"
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoPilotReferenceTests")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        resolver.openOutputStream(uri)!!.use { Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(218, 164, 87)); setPixel(0, 0, android.graphics.Color.BLUE); setPixel(3, 2, android.graphics.Color.GREEN) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        env.settings.setOnboardingCompleted(true)
        env.settings.putRaw("overlay_stack", "{\"stack\":[]}")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        delay(6500)
        try {
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            launchSystemPicker {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                    putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Pictures/PhotoPilotReferenceTests"))
                }
                activity.startActivityForResult(intent, 43)
            }
            await("System tree picker must expose its confirmation") { systemNode("Use this folder") != null }
            touchSystemText("Use this folder")
            await("System tree picker must ask for folder access") { systemNode("Allow") != null }
            touchSystemText("Allow")
            await("Tree result must index the chosen folder") {
                val source = env.database.sourceDao().getAll().firstOrNull { it.endpointKey?.startsWith("android.saf.tree:v1:") == true }
                source != null && env.database.sourceAssetDao().getBySource(source.id).any { env.localReferences.displayName(it) == name }
            }
            val source = env.database.sourceDao().getAll().single { it.endpointKey?.startsWith("android.saf.tree:v1:") == true }
            val selected = env.database.sourceAssetDao().getBySource(source.id).single { env.localReferences.displayName(it) == name }
            val sourceSettings = JsonMini.parseObject(source.settingsJson)
            val grantUri = sourceSettings["referenceTreeUri"] as String
            assertTrue(resolver.persistedUriPermissions.any { it.uri.toString() == grantUri && it.isReadPermission })
            assertTrue(env.database.importJobDao().getBySource(source.id).isEmpty())
            assertTrue(env.database.assetOriginDao().getBySourceAssets(listOf(selected.id)).isEmpty())
            assertFalse(descendants(activity.window.decorView).any { it is RemoteFrameView })
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("openRemoteFrame", dev.photopilot.scaffold.data.db.FrameEntity::class.java, Map::class.java).apply { isAccessible = true }.invoke(activity, null, mapOf("sourceId" to source.id))
            }
            val members = env.remoteMedia.members(source.id, "", false)
            assertTrue("Selected tree root must expose the generated file", members.any { it.id == selected.id })
            for (page in members.chunked(20)) {
                val firstName = env.localReferences.displayName(page.first())
                await("Tree page must finish loading") { descendants(activity.window.decorView).filterIsInstance<CheckBox>().any { it.text.toString() == firstName } }
                if (page.any { it.id == selected.id }) break
                touch(descendants(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() in setOf("Next", "下一页") })
            }
            await("Tree selection grid must expose the generated image") { descendants(activity.window.decorView).filterIsInstance<CheckBox>().any { it.text.toString() == name } }
            val editor = descendants(activity.window.decorView).filterIsInstance<RemoteFrameView>().last()
            val checkbox = descendants(editor).filterIsInstance<CheckBox>().single { it.text.toString() == name }
            val frameName = "Native tree $name"
            instrumentation.runOnMainSync { descendants(editor).filterIsInstance<EditText>().first().setText(frameName) }
            touch(checkbox)
            assertTrue(checkbox.isChecked)
            touch(descendants(editor).filterIsInstance<TextView>().last { it.text.toString() in setOf("Save frame", "保存相框") })
            await("Tree selection must save a reference frame") { env.database.frameDao().getAll().any { it.name == frameName } }
            val frame = env.database.frameDao().getAll().single { it.name == frameName }
            assertEquals(listOf("remote:${selected.id}"), env.frames.buildPlayQueue(frame.id))
            assertEquals(4, env.remoteMedia.bitmap(env.frames.playbackItems(frame.id).single(), 32, 32)?.width)
            val metadata = JsonMini.parseObject(env.database.settingsKvDao().get("reference.asset.${selected.id}")?.valueJson)
            val now = System.currentTimeMillis()
            env.database.settingsKvDao().upsert(SettingsKvEntity("$key.tree", JsonMini.toJson(mapOf("sourceId" to source.id, "assetId" to selected.id, "frameId" to frame.id, "uri" to metadata["uri"], "grantUri" to grantUri, "fixtureUri" to uri.toString(), "pid" to android.os.Process.myPid())), now, now))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
        Unit
    }

    @Test fun verifyTreeGrantAcrossProcessRestartThenRevoke() = runBlocking { verifyRestartAndRevoke("$key.tree") }
    @Test fun verifyGrantAcrossProcessRestartThenRevokeAndDeleteOriginal() = runBlocking { verifyRestartAndRevoke(key) }

    private suspend fun verifyRestartAndRevoke(stateKey: String) {
        val context = instrumentation.targetContext
        val language = InstrumentationRegistry.getArguments().getString("language") ?: "en"
        if (android.os.Build.VERSION.SDK_INT >= 33) context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(language)
        val env = AppEnvironment.shared(context)
        env.settings.setLanguage(language)
        val state = JsonMini.parseObject(env.database.settingsKvDao().get(stateKey)?.valueJson)
        org.junit.Assume.assumeTrue("Requires a real system-picker preparation", state.isNotEmpty())
        val preparedPid = (state["pid"] as? Number)?.toInt()
        org.junit.Assume.assumeTrue("Run verification in a separate restarted app process", preparedPid != null && preparedPid != android.os.Process.myPid())
        instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", "Verified process restart $preparedPid -> ${android.os.Process.myPid()}\n") })
        val sourceId = state["sourceId"] as String
        val frameId = state["frameId"] as String
        val uri = Uri.parse(state["uri"] as String)
        val fixture = Uri.parse(state["fixtureUri"] as String)
        val grantUri = Uri.parse(state["grantUri"] as? String ?: state["uri"] as String)
        var fixtureDeleted = false
        try {
            assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == grantUri && it.isReadPermission })
            val queue = env.frames.buildPlayQueue(frameId)
            assertEquals(listOf("remote:${state["assetId"]}"), queue)
            val item = env.frames.playbackItems(frameId).single()
            assertEquals(4, env.remoteMedia.bitmap(item, 32, 32)?.width)
            context.contentResolver.releasePersistableUriPermission(grantUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertFalse(env.remoteMedia.valid(item))
            assertNull(env.remoteMedia.bitmap(item, 32, 32))
            assertTrue(env.frames.buildPlayQueue(frameId).isEmpty())
            deleteFixture(fixture)
            fixtureDeleted = true
            assertNull(env.remoteMedia.item(state["assetId"] as String))
        } finally {
            env.frameManager.deleteFrame(frameId)
            env.localSources.disconnect(sourceId, true, System.currentTimeMillis(), removeConfiguration = true)
            if (!fixtureDeleted) deleteFixture(fixture)
            env.database.settingsKvDao().get(stateKey)?.let { env.database.settingsKvDao().delete(it) }
        }
        Unit
    }

    private fun deleteFixture(uri: Uri) {
        require(uri.authority == "media" && uri.path.orEmpty().matches(Regex("/external(?:_primary)?/images/media/[0-9]+")))
        val result = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("content delete --uri $uri")).use { it.readBytes().toString(Charsets.UTF_8) }
        check(!result.contains("Exception") && !result.contains("Error")) { result }
    }

    private fun capture(name: String) {
        SystemClock.sleep(500)
        val directory = File(instrumentation.targetContext.cacheDir, "source-fix-evidence").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private suspend fun await(label: String, predicate: suspend () -> Boolean) {
        try { withTimeout(20000) { while (!predicate()) delay(100) } }
        catch (failure: TimeoutCancellationException) { throw AssertionError(label, failure) }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun launchSystemPicker(action: () -> Unit) {
        val automation = instrumentation.uiAutomation
        automation.executeAndWaitForEvent(
            { instrumentation.runOnMainSync(action) },
            { event -> event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName?.toString()?.endsWith(".documentsui") == true },
            10000,
        ).recycle()
        automation.waitForIdle(300, 5000)
    }

    private fun editableSystemNode(): android.view.accessibility.AccessibilityNodeInfo? {
        fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if (node.isEditable) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it) }?.let { return it }
            return null
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }
    private fun systemNode(text: String): android.view.accessibility.AccessibilityNodeInfo? {
        fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if (!node.isEditable && (node.text?.toString()?.equals(text, true) == true || node.contentDescription?.toString()?.startsWith(text, true) == true)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { find(it) }?.let { return it }
            }
            return null
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }
    private suspend fun selectSystemDocument(name: String) {
        requireNotNull(systemNode(name)).performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        SystemClock.sleep(500)
        var node = requireNotNull(systemNode(name))
        while (!node.isLongClickable && node.parent != null) node = node.parent
        if (!node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            val bounds = Rect().also { requireNotNull(systemNode(name)).getBoundsInScreen(it) }
            val x = bounds.centerX()
            val y = bounds.bottom - maxOf(1, bounds.height() / 5)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input swipe $x $y $x $y 800")).use { it.readBytes() }
        }
        await("Selected document must expose confirmation") { systemNode("Open") != null || systemNode("Select") != null }
        touchSystemText(if (systemNode("Select") != null) "Select" else "Open")
    }
    private fun touchSystemText(text: String) {
        val target = requireNotNull(systemNode(text))
        val bounds = Rect().also { target.getBoundsInScreen(it) }
        var node = target
        while (!node.isClickable && node.parent != null) node = node.parent
        if (!node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
            val y = bounds.bottom - maxOf(1, bounds.height() / 5)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} $y")).use { it.readBytes() }
        }
        SystemClock.sleep(500)
        capture("system-after-click-${++systemClicks}")
    }
    private fun touch(view: View) {
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true) }
        SystemClock.sleep(350)
        val visible = Rect()
        instrumentation.runOnMainSync { assertTrue("Control must be visible", view.getGlobalVisibleRect(visible)) }
        touchAt(visible.exactCenterX(), visible.exactCenterY())
    }
    private fun touchAt(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time + 60, MotionEvent.ACTION_UP, x, y, 0))
        instrumentation.waitForIdleSync()
    }
}
