package dev.photopilot.scaffold

import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.aiframes.*
import dev.photopilot.scaffold.data.db.DatabaseFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AIModelTaskProtocolTest {
    @Test fun actualRangeTransferPausesRestoresAndCannotWriteBackAfterDeletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = DatabaseFactory.builder(context).build()
        val id = "model-http-fixture-" + UUID.randomUUID()
        val bytes = ByteArray(393233) { (it % 251).toByte() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val artifact = AIModelFile("model.bin", bytes.size.toLong(), hash, listOf("https://fixture.invalid/model"), emptyList())
        val model = AIModel(id, "fixture", "onnx", "Fixture model", "夹具模型", listOf(artifact))
        val server = ServerSocket(0, 16, InetAddress.getByName("10.0.2.15"))
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val firstRelease = CountDownLatch(1)
        val deleteRelease = CountDownLatch(1)
        val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<String?, String?>>())
        val count = AtomicInteger()
        serverScope.launch {
            while (isActive) {
                val client = try { server.accept() } catch (_: Exception) { break }
                launch {
                    client.use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        input.readLine()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = input.readLine()
                            if (line.isNullOrEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        requests.add(headers["range"] to headers["if-range"])
                        val request = count.incrementAndGet()
                        val offset = headers["range"]?.substringAfter("bytes=")?.substringBefore('-')?.toInt() ?: 0
                        val output = socket.getOutputStream()
                        val range = if (offset > 0) "Content-Range: bytes $offset-${bytes.lastIndex}/${bytes.size}\r\n" else ""
                        val status = if (offset > 0) "206 Partial Content" else "200 OK"
                        output.write("HTTP/1.1 $status\r\nContent-Length: ${bytes.size - offset}\r\nETag: \"fixture-v1\"\r\n${range}Connection: close\r\n\r\n".toByteArray())
                        val chunk = minOf(131072, bytes.size - offset)
                        output.write(bytes, offset, chunk)
                        output.flush()
                        if (request == 1) firstRelease.await()
                        if (request == 3) deleteRelease.await()
                        runCatching {
                            output.write(bytes, offset + chunk, bytes.size - offset - chunk)
                            output.flush()
                        }
                    }
                }
            }
        }
        fun service(): AIFrameService {
            val manager = AIModelManager(context) { url -> FixtureConnection(url, server.localPort) }
            manager.javaClass.getDeclaredField("models\$delegate").apply { isAccessible = true }.set(manager, lazyOf(listOf(model)))
            return AIFrameService(context, db, manager)
        }
        var active = service()
        val taskId = "download:$id"
        val folder = active.models.directory(model)
        suspend fun awaitPartial() {
            withTimeout(10000) { while (File(folder, "model.bin.part").length() < 65536) delay(25) }
        }
        try {
            active.tasks.startDownload(model, "international", true)
            awaitPartial()
            active.tasks.pause(taskId)
            withTimeout(3000) { while (active.tasks.running(taskId)) delay(25) }
            val partialLength = File(folder, "model.bin.part").length()
            assertTrue(partialLength in 65536L until bytes.size.toLong())
            assertTrue(requireNotNull(active.tasks.record(taskId)).userPaused)
            active.shutdown()
            active = service()
            active.tasks.recover()
            delay(150)
            assertEquals(1, requests.size)
            assertFalse(active.tasks.running(taskId))
            active.tasks.continueTask(taskId)
            withTimeout(10000) { while (active.tasks.record(taskId)?.status != "complete") delay(25) }
            assertEquals("bytes=$partialLength-", requests[1].first)
            assertEquals("\"fixture-v1\"", requests[1].second)
            assertArrayEquals(bytes, File(folder, "model.bin").readBytes())
            assertTrue(active.models.verify(model))
            active.models.delete(model)
            active.tasks.startDownload(model, "international", true)
            awaitPartial()
            withTimeout(3000) { active.tasks.deleteModel(model) }
            assertEquals("deleted", active.tasks.record(taskId)?.status)
            deleteRelease.countDown()
            delay(300)
            assertFalse(File(folder, "model.bin").exists())
            assertFalse(File(folder, "model.bin.part").exists())
            assertFalse(File(folder, "model.bin.verified").exists())
            active.tasks.continueTask(taskId)
            active.tasks.recover()
            assertFalse(active.tasks.running(taskId))
            assertEquals(3, requests.size)
        } finally {
            firstRelease.countDown()
            deleteRelease.countDown()
            active.shutdown()
            folder.deleteRecursively()
            server.close()
            serverScope.coroutineContext[Job]?.cancelAndJoin()
            db.settingsKvDao().get("ai.task.$taskId")?.let { db.settingsKvDao().delete(it) }
            db.close()
        }
    }
    private class FixtureConnection(url: URL, private val port: Int) : HttpURLConnection(url) {
        private val requestHeaders = linkedMapOf<String, String>()
        private val responseHeaders = mutableMapOf<String, String>()
        @Volatile private var socket: java.net.Socket? = null
        private var body: java.io.BufferedInputStream? = null
        private var status = -1
        override fun setRequestProperty(key: String, value: String) { requestHeaders[key] = value }
        override fun connect() {
            if (connected) return
            val connection = java.net.Socket()
            socket = connection
            connection.soTimeout = readTimeout
            connection.connect(java.net.InetSocketAddress("10.0.2.15", port), connectTimeout)
            val headers = requestHeaders.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
            connection.getOutputStream().write("GET /model HTTP/1.1\r\nHost: fixture\r\n${headers}Connection: close\r\n\r\n".toByteArray())
            val input = java.io.BufferedInputStream(connection.getInputStream())
            fun line(): String {
                val value = java.io.ByteArrayOutputStream()
                while (true) {
                    val next = input.read()
                    if (next < 0 || next == 10) break
                    if (next != 13) value.write(next)
                }
                return value.toString("UTF-8")
            }
            status = line().split(' ')[1].toInt()
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                responseHeaders[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
            }
            body = input
            connected = true
        }
        override fun getResponseCode(): Int { connect(); return status }
        override fun getHeaderField(name: String?): String? { connect(); return responseHeaders[name?.lowercase()] }
        override fun getInputStream(): java.io.InputStream { connect(); return requireNotNull(body) }
        override fun usingProxy() = false
        override fun disconnect() { socket?.close(); socket = null }
    }

}
