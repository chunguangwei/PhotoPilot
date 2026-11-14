package dev.photopilot.scaffold

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.ImageView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.net.NasTlsPolicy
import dev.photopilot.scaffold.ui.remote.RemoteFrameView
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RemotePreviewRevocationTest {
    @Test fun shownPreviewClearsAfterSourceRevocation() = exercise(false)
    @Test fun delayedPreviewDoesNotDisplayAfterRevocation() = exercise(true)

    private fun exercise(delayed: Boolean) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val db = env.database
        val id = "preview-revoke-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val bytes = ByteArrayOutputStream().also { Bitmap.createBitmap(25, 25, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val server = ServerSocket(0, 16, java.net.InetAddress.getByName("10.0.2.15"))
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val opened = CountDownLatch(1)
        val release = CountDownLatch(if (delayed) 1 else 0)
        serverScope.launch {
            while (isActive) {
                val client = try { server.accept() } catch (_: Exception) { break }
                launch { client.use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    input.readLine()
                    while (!input.readLine().isNullOrEmpty()) Unit
                    opened.countDown()
                    release.await()
                    runCatching { socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes) }
                } }
            }
        }
        env.tofuPins.addCleartextHost(NasTlsPolicy.hostPortKey("10.0.2.15", server.localPort))
        env.credentialStore.save(id, JsonMini.toJson(mapOf("protocol" to "http", "host" to "10.0.2.15", "port" to server.localPort, "path" to "", "username" to "", "password" to "")))
        db.sourceDao().upsert(SourceEntity(id, SourceType.WEBDAV, "Preview fixture", id, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true}", now, now))
        db.sourceAssetDao().upsert(SourceAssetEntity("$id-photo", id, "preview.png", null, "1", bytes.size.toLong(), null, now, 0, AccessState.ACCESSIBLE, now, now))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        lateinit var view: RemoteFrameView
        fun previewImages(): List<ImageView> = WindowInspector.getGlobalWindowViews().filter { root -> root !== activity.window.decorView && descendants(root).filterIsInstance<TextView>().any { it.text.toString() == "preview.png" } }.flatMap(::descendants).filterIsInstance<ImageView>().filter { it.drawable != null && it.isShown }
        try {
            instrumentation.runOnMainSync {
                view = RemoteFrameView(activity, env, null, mapOf("sourceId" to id))
                activity.setContentView(view)
            }
            delay(500)
            instrumentation.runOnMainSync { descendants(view).filterIsInstance<TextView>().first { it.text.toString() == "Preview" || it.text.toString() == "预览" }.performClick() }
            assertTrue(opened.await(3, TimeUnit.SECONDS))
            if (!delayed) {
                var shown = false
                repeat(30) { delay(100); instrumentation.runOnMainSync { shown = previewImages().isNotEmpty() }; if (shown) return@repeat }
                assertTrue("Fixture preview must actually display", shown)
            }
            val source = requireNotNull(db.sourceDao().getById(id))
            db.sourceDao().update(source.copy(status = SourceStatus.DISCONNECTED, epoch = source.epoch + 1))
            release.countDown()
            delay(800)
            instrumentation.runOnMainSync { assertTrue("Revoked remote preview must clear every displayed bitmap", previewImages().isEmpty()) }
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { activity.finish() }
            server.close(); serverScope.coroutineContext[Job]?.cancelAndJoin()
            env.credentialStore.delete(id)
            db.sourceDao().getById(id)?.let { db.sourceDao().delete(it) }
        }
        Unit
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
