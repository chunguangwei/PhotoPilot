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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RemotePlayerTest {
    @Test fun actualPlayerFetchesCurrentAndNextOnlyAndClearsRevokedSource() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val db = env.database
        val id = "remote-player-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val bytes = ByteArrayOutputStream().also { Bitmap.createBitmap(23, 19, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val server = ServerSocket(0, 16, java.net.InetAddress.getByName("10.0.2.15"))
        val requests = AtomicInteger()
        val serverJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val client = try { server.accept() } catch (_: Exception) { break }
                client.use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val line = input.readLine().orEmpty()
                    while (!input.readLine().isNullOrEmpty()) Unit
                    if (line.startsWith("GET ")) requests.incrementAndGet()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }
        env.tofuPins.addCleartextHost(NasTlsPolicy.hostPortKey("10.0.2.15", server.localPort))
        env.credentialStore.save(id, JsonMini.toJson(mapOf("protocol" to "http", "host" to "10.0.2.15", "port" to server.localPort, "path" to "", "username" to "", "password" to "")))
        db.sourceDao().upsert(SourceEntity(id, SourceType.WEBDAV, "Local fixture", id, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true}", now, now))
        val frame = env.frameManager.createFrame("Player fixture")
        val remoteIds = (1..4).map { "$id-$it" }
        remoteIds.forEach { remoteId -> db.sourceAssetDao().upsert(SourceAssetEntity(remoteId, id, "$remoteId.png", null, "1", bytes.size.toLong(), null, now, 0, AccessState.ACCESSIBLE, now, now)) }
        db.frameDao().update(frame.copy(settingsJson = "{\"mediaStorageMode\":\"remote\",\"holdSeconds\":60,\"transition\":\"none\"}"))
        env.frames.save(frame.id, 0, addIncludes = remoteIds.map { SelectionRefEdit(null, it) })
        val shown = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val current = requireNotNull(db.frameDao().getById(frame.id))
        var engine: PlayerEngine? = null
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        val listener = object : PlayerEngine.Listener {
            override fun onStateChanged(state: String) = Unit
            override fun onShowAsset(asset: PlaybackItem, decoded: PlayerEngine.DecodedAsset, transitionMs: Long, direction: Int, position: Int) { shown.countDown() }
            override fun onShowVideo(asset: PlaybackItem, path: String, blurred: Bitmap?, edgeColor: Int?) = Unit
            override fun onClearTextures() { if (shown.count == 0L) cleared.countDown() }
            override fun onShowSafeImage() = Unit
            override fun onQueueInfo(size: Int) = Unit
            override fun onMediaLoadError(code: String) { failures.add(code) }
            override fun onDecodeSkipped() = Unit
            override fun onKeepScreenOn(keep: Boolean) = Unit
        }
        try {
            instrumentation.runOnMainSync { engine = PlayerEngine(context, env, current, listener, uiScope); engine?.start(false) }
            val displayed = shown.await(10, TimeUnit.SECONDS)
            assertTrue("first remote image displayed; requests=${requests.get()} network=${env.remoteMedia.allowsNetwork()} queue=${env.frames.buildPlayQueue(frame.id).size} state=${engine?.currentState()} errors=$failures", displayed)
            withTimeout(5000) { while (requests.get() < 2) delay(25) }
            delay(300)
            assertEquals("current and next only", 2, requests.get())
            val source = requireNotNull(db.sourceDao().getById(id))
            server.close()
            serverJob.cancelAndJoin()
            assertTrue(env.localSources.recordSyncError(id, source.epoch, source.status, false, System.currentTimeMillis()))
            assertEquals(source, db.sourceDao().getById(id))
            val firstId = env.frames.buildPlayQueue(frame.id).first()
            val cached = env.frames.playbackItems(frame.id).single { it.id == firstId }
            assertNotNull("Transient sync error must preserve offline temporary-cache playback", env.remoteMedia.bitmap(cached, 640, 640))
            db.sourceDao().update(source.copy(epoch = 2, status = SourceStatus.DISCONNECTED))
            assertFalse(env.remoteMedia.valid(cached))
            assertTrue("revoked image cleared", cleared.await(5, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { engine?.stop(true) }
            uiScope.cancel(); server.close(); serverJob.cancelAndJoin()
            env.credentialStore.delete(id)
            env.frameManager.deleteFrame(frame.id)
            db.settingsKvDao().get("nas.connection_error.$id")?.let { db.settingsKvDao().delete(it) }
            db.sourceDao().getById(id)?.let { db.sourceDao().delete(it) }
        }
        Unit
    }
}
