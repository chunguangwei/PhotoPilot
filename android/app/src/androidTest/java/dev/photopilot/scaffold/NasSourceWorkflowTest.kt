package dev.photopilot.scaffold

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.net.NasTlsPolicy
import dev.photopilot.scaffold.ui.AppRootView
import dev.photopilot.scaffold.ui.nas.NasFormView
import dev.photopilot.scaffold.ui.sources.SourcesView
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NasSourceWorkflowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun storageModeSwitchesAndDisconnectedDeleteUseRealButtons() = runBlocking {
        val context = instrumentation.targetContext
        val language = InstrumentationRegistry.getArguments().getString("language") ?: "en"
        val variant = InstrumentationRegistry.getArguments().getString("variant") ?: "portrait"
        if (android.os.Build.VERSION.SDK_INT >= 33) context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(language)
        val env = AppEnvironment.shared(context)
        env.settings.setLanguage(language)
        val id = "nas-ui-" + UUID.randomUUID()
        val bytes = ByteArrayOutputStream().also { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val server = ServerSocket(0, 16, java.net.InetAddress.getByName("10.0.2.15"))
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        workers.launch {
            while (isActive) {
                val connection = try { server.accept() } catch (_: Exception) { break }
                launch { connection.use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val method = reader.readLine().orEmpty().substringBefore(' ')
                    var length = 0
                    while (true) {
                        val line = reader.readLine().orEmpty()
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { reader.read() }
                    val body = if (method == "PROPFIND") """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/photo.png</d:href><d:propstat><d:prop><d:getcontentlength>${bytes.size}</d:getcontentlength><d:getetag>v1</d:getetag><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""".toByteArray() else bytes
                    val status = if (method == "PROPFIND") "207 Multi-Status" else "200 OK"
                    socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                } }
            }
        }
        val port = server.localPort
        val endpoint = "webdav_http://10.0.2.15:$port"
        env.tofuPins.addCleartextHost(NasTlsPolicy.hostPortKey("10.0.2.15", port))
        env.credentialStore.save(endpoint, JsonMini.toJson(mapOf("protocol" to "webdav_http", "host" to "10.0.2.15", "port" to port, "path" to "", "username" to "", "password" to "")))
        val source = env.localSources.ensureNasSource(SourceType.WEBDAV, endpoint, id, 0, "{\"catalogOnly\":true,\"directories\":[\"\"],\"recursive\":true}")
        env.settings.setOnboardingCompleted(true)
        env.settings.putRaw("overlay_stack", "{\"stack\":[]}")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        delay(6500)
        try {
            suspend fun editStorage(local: Boolean) {
                instrumentation.runOnMainSync {
                    MainActivity::class.java.getDeclaredMethod("openNasEdit", String::class.java).apply { isAccessible = true }.invoke(activity, source.id)
                }
                await { descendants(activity.window.decorView).any { it is NasFormView } }
                val form = descendants(activity.window.decorView).filterIsInstance<NasFormView>().last()
                assertEquals(local, form.currentForm()?.catalogOnly)
                touch(text(activity, if (local) R.string.source_nas_storage_local else R.string.source_nas_storage_remote))
                assertEquals(!local, form.currentForm()?.catalogOnly)
                val storage = text(activity, R.string.source_nas_storage_local)
                instrumentation.runOnMainSync { storage.requestRectangleOnScreen(Rect(0, 0, storage.width, storage.height), true) }
                SystemClock.sleep(350)
                capture("nas-storage-$language-$variant")
                touch(text(activity, R.string.common_next))
                await { descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.source_nas_choose_folders) } }
                val save = text(activity, R.string.common_save)
                instrumentation.runOnMainSync { save.requestRectangleOnScreen(Rect(0, 0, save.width, save.height), true) }
                instrumentation.runOnMainSync {
                    var parent = save.parent
                    while (parent != null && parent !is android.widget.ScrollView) parent = parent.parent
                    (parent as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN)
                }
                SystemClock.sleep(350)
                capture("nas-directory-$language-$variant")
                touch(save)
                await { JsonMini.parseObject(env.database.sourceDao().getById(source.id)?.settingsJson)["catalogOnly"] == !local }
            }
            editStorage(true)
            await { env.database.importJobDao().getBySource(source.id).any { it.state == ImportJobState.COMPLETED } }
            assertTrue(env.database.sourceAssetDao().getBySource(source.id).isNotEmpty())
            assertTrue(env.database.assetOriginDao().getBySourceAssets(env.database.sourceAssetDao().getBySource(source.id).map { it.id }).isNotEmpty())
            await { descendants(activity.window.decorView).none { it is NasFormView || it is dev.photopilot.scaffold.ui.nas.NasDirectoryPickerView } }
            editStorage(false)
            assertTrue(env.database.importJobDao().getBySource(source.id).none { it.state == ImportJobState.RUNNING })
            env.localSources.disconnect(source.id, true, System.currentTimeMillis())
            assertNotNull(env.credentialStore.load(endpoint))
            instrumentation.runOnMainSync {
                val view = MainActivity::class.java.getDeclaredMethod("buildSourcesView").apply { isAccessible = true }.invoke(activity) as SourcesView
                val root = MainActivity::class.java.getDeclaredField("root").apply { isAccessible = true }.get(activity) as AppRootView
                root.pushOverlay(view)
                MainActivity::class.java.getDeclaredMethod("refreshNasSources", SourcesView::class.java).apply { isAccessible = true }.invoke(activity, view)
            }
            await { descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.player_more) } }
            touch(text(activity, R.string.player_more))
            await { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(activity.getString(R.string.sources_remove_configuration))?.isNotEmpty() == true }
            val remove = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(activity.getString(R.string.sources_remove_configuration)).last()
            val removeBounds = Rect().also { remove.getBoundsInScreen(it) }
            touchAt(removeBounds.exactCenterX(), removeBounds.exactCenterY())
            await { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(activity.getString(R.string.common_delete))?.isNotEmpty() == true }
            capture("nas-delete-$language-$variant")
            val node = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(activity.getString(R.string.common_delete)).last()
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            touchAt(bounds.exactCenterX(), bounds.exactCenterY())
            await { env.database.sourceDao().getById(source.id) == null }
            assertNull(env.credentialStore.load(endpoint))
            await { descendants(activity.window.decorView).filterIsInstance<TextView>().none { it.text.toString() == "10.0.2.15:$port" } }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            val remotes = env.database.sourceAssetDao().getBySource(source.id)
            val originals = env.database.assetOriginDao().getBySourceAssets(remotes.map { it.id }).map { it.assetId }
            env.database.importJobDao().getBySource(source.id).forEach { env.runner.cancel(it.id) }
            env.localSources.disconnect(source.id, true, System.currentTimeMillis(), removeConfiguration = true)
            originals.forEach { assetId -> env.database.assetDao().getById(assetId)?.let { asset -> File(context.filesDir, "originals/${asset.originalRelPath}").delete(); env.database.assetDao().delete(asset) } }
            env.credentialStore.delete(endpoint)
            server.close()
            workers.coroutineContext[Job]?.cancelAndJoin()
        }
        Unit
    }

    private fun capture(name: String) {
        SystemClock.sleep(500)
        val directory = File(instrumentation.targetContext.cacheDir, "source-fix-evidence").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private var waitNumber = 0
    private suspend fun await(predicate: suspend () -> Boolean) {
        val number = ++waitNumber
        try {
            withTimeout(15000) { while (!predicate()) delay(100) }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("NAS source workflow wait $number", timeout)
        }
    }
    private fun text(activity: Activity, resource: Int): View = descendants(activity.window.decorView).filterIsInstance<TextView>().last { it.text.toString() == activity.getString(resource) }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun touch(view: View) {
        instrumentation.runOnMainSync { view.performClick() }
        instrumentation.waitForIdleSync()
    }
    private fun touchAt(x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time + 60, MotionEvent.ACTION_UP, x, y, 0))
        instrumentation.waitForIdleSync()
    }
}
