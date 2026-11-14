package dev.photopilot.scaffold

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.net.NasTlsPolicy
import dev.photopilot.scaffold.data.remote.PlaybackItem
import dev.photopilot.scaffold.data.repo.SelectionRefEdit
import dev.photopilot.scaffold.ui.player.PlayerEngine
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RemotePlayerCancellationTest {
    @Test fun manualPreviousCancelsSlowNextWithoutWaitingForItsResponse() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val db = env.database
        val id = "prefetch-cancel-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val bytes = ByteArrayOutputStream().also { Bitmap.createBitmap(27, 29, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val server = ServerSocket(0, 16, java.net.InetAddress.getByName("10.0.2.15"))
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val bStarted = CountDownLatch(1)
        val bClosed = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        serverScope.launch {
            while (isActive) {
                val client = try { server.accept() } catch (_: Exception) { break }
                launch {
                    client.use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        val path = input.readLine().orEmpty().split(' ').getOrNull(1).orEmpty().substringAfterLast('/')
                        while (!input.readLine().isNullOrEmpty()) Unit
                        requested.add(path)
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.flush()
                        if (path == "B.png") {
                            bStarted.countDown()
                            serverScope.launch { runCatching { if (socket.getInputStream().read() < 0) bClosed.countDown() } }
                            releaseB.await()
                        }
                        runCatching { output.write(bytes); output.flush() }
                    }
                }
            }
        }
        env.tofuPins.addCleartextHost(NasTlsPolicy.hostPortKey("10.0.2.15", server.localPort))
        env.credentialStore.save(id, JsonMini.toJson(mapOf("protocol" to "http", "host" to "10.0.2.15", "port" to server.localPort, "path" to "", "username" to "", "password" to "")))
        db.sourceDao().upsert(SourceEntity(id, SourceType.WEBDAV, "Cancellation fixture", id, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true}", now, now))
        val ids = (1..4).map { "$id-$it" }
        ids.forEachIndexed { index, remoteId -> db.sourceAssetDao().upsert(SourceAssetEntity(remoteId, id, "${'A' + index}.png", null, "1", bytes.size.toLong(), null, now, 0, AccessState.ACCESSIBLE, now, now)) }
        val frame = env.frameManager.createFrame("Cancellation fixture")
        db.frameDao().update(frame.copy(orderMode = FrameOrderMode.MANUAL, settingsJson = "{\"mediaStorageMode\":\"remote\",\"holdSeconds\":60,\"transition\":\"none\"}"))
        env.frames.save(frame.id, 0, addIncludes = ids.map { SelectionRefEdit(null, it) }, manualOrderRefs = ids)
        val aShown = CountDownLatch(1)
        val dShown = CountDownLatch(1)
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var engine: PlayerEngine? = null
        val listener = object : PlayerEngine.Listener {
            override fun onStateChanged(state: String) = Unit
            override fun onShowAsset(asset: PlaybackItem, decoded: PlayerEngine.DecodedAsset, transitionMs: Long, direction: Int, position: Int) {
                if (asset.remote?.remoteKey == "A.png") aShown.countDown()
                if (asset.remote?.remoteKey == "D.png") dShown.countDown()
            }
            override fun onShowVideo(asset: PlaybackItem, path: String, blurred: Bitmap?, edgeColor: Int?) = Unit
            override fun onClearTextures() = Unit
            override fun onShowSafeImage() = Unit
            override fun onQueueInfo(size: Int) = Unit
            override fun onDecodeSkipped() = Unit
            override fun onKeepScreenOn(keep: Boolean) = Unit
        }
        try {
            val current = requireNotNull(db.frameDao().getById(frame.id))
            instrumentation.runOnMainSync { engine = PlayerEngine(context, env, current, listener, uiScope); engine?.start(false) }
            assertTrue(aShown.await(5, TimeUnit.SECONDS))
            assertTrue(bStarted.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { engine?.userPrev() }
            assertTrue("Manual previous must bypass obsolete slow prefetch", dShown.await(3, TimeUnit.SECONDS))
            assertTrue("Abandoned next request must be closed", bClosed.await(2, TimeUnit.SECONDS))
            assertFalse(requested.contains("C.png"))
            assertEquals(1, requested.count { it == "B.png" })
        } finally {
            instrumentation.runOnMainSync { engine?.stop(true) }
            uiScope.cancel(); releaseB.countDown(); server.close(); serverScope.coroutineContext[Job]?.cancelAndJoin()
            env.credentialStore.delete(id)
            env.frameManager.deleteFrame(frame.id)
            db.sourceDao().getById(id)?.let { db.sourceDao().delete(it) }
        }
        Unit
    }
}
