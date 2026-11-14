package dev.photopilot.scaffold

import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.aiframes.*
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.net.NasTlsPolicy
import dev.photopilot.scaffold.data.repo.SelectionRefEdit
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RemoteLocalAIIncrementalTest {
    @Test fun completedLocalFrameCopiesOnlyNewAISelectionsAndHonorsManualPause() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val env = AppEnvironment.shared(context)
        val db = env.database
        val id = "local-ai-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val fixture = File(context.filesDir, "ai-validation")
        val bytes = File(fixture, "validation-cats.png").readBytes()
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0, 16, java.net.InetAddress.getByName("10.0.2.15"))
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope.launch {
            while (isActive) {
                val client = try { server.accept() } catch (_: Exception) { break }
                launch { client.use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val name = input.readLine().orEmpty().split(' ').getOrNull(1).orEmpty().substringAfterLast('/')
                    while (!input.readLine().isNullOrEmpty()) Unit
                    requested.add(name)
                    runCatching { socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes) }
                } }
            }
        }
        env.tofuPins.addCleartextHost(NasTlsPolicy.hostPortKey("10.0.2.15", server.localPort))
        env.credentialStore.save(id, JsonMini.toJson(mapOf("protocol" to "http", "host" to "10.0.2.15", "port" to server.localPort, "path" to "", "username" to "", "password" to "")))
        db.sourceDao().upsert(SourceEntity(id, SourceType.WEBDAV, "Incremental fixture", id, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true}", now, now))
        db.collectionDao().upsert(CollectionEntity(id, null, id, "Fixture", "", CollectionType.SOURCE_FOLDER, "", now, now))
        suspend fun add(index: Int) { db.sourceAssetDao().upsert(SourceAssetEntity("$id-$index", id, "$index.png", null, "1", bytes.size.toLong(), null, now, 0, AccessState.ACCESSIBLE, now, now)) }
        suspend fun copied(index: Int) = db.assetOriginDao().getBySourceAssets(listOf("$id-$index")).isNotEmpty()
        suspend fun awaitCopy(index: Int) { withTimeout(10000) { while (!copied(index)) delay(100) } }
        val service = AIFrameService(context, db).also { it.remoteMedia = env.remoteMedia }
        val model = service.models.models.single { it.id == "multilingual-clip-vitb32-lite" }
        model.files.forEach { file -> File(fixture, "multilingual-clip/${file.name}").copyTo(File(service.models.directory(model), file.name), true); File(service.models.directory(model), file.name + ".verified").writeText(file.sha256) }
        val frame = env.frameManager.createFrame("Incremental local AI")
        try {
            db.frameDao().update(frame.copy(settingsJson = "{\"mediaStorageMode\":\"local\"}"))
            add(1)
            env.frames.save(frame.id, 0, addIncludes = listOf(SelectionRefEdit(null, "$id-1")))
            env.remoteCopies.sync(frame.id)
            awaitCopy(1)
            assertFalse(env.remoteCopies.isRunning(frame.id))
            add(2)
            suspend fun previewAndSave() {
                val previews = service.preview(model, listOf(id), false, listOf(AIRule("a photo of cats", threshold = model.defaultThreshold)), frame.id) { _, _ -> }
                assertTrue(previews.single().failed == 0)
                service.savePreviews(previews, listOf(frame.name), previews.map { it.matches.keys }, false, frame.id, service.state.get(frame.id)?.revision ?: 0)
            }
            previewAndSave()
            awaitCopy(2)
            assertEquals("Completed member must not be fetched again", 1, requested.count { it == "1.png" })
            assertEquals("New member has one preview GET and one local-copy GET", 2, requested.count { it == "2.png" })
            env.remoteCopies.pause(frame.id)
            delay(250)
            add(3)
            previewAndSave()
            delay(1200)
            assertFalse("AI confirmation must preserve manual sync pause", copied(3))
            assertEquals(1, requested.count { it == "3.png" })
            env.remoteCopies.start(frame.id)
            awaitCopy(3)
            assertEquals(2, requested.count { it == "3.png" })
            assertEquals(1, requested.count { it == "1.png" })
        } finally {
            env.remoteCopies.pause(frame.id)
            delay(100)
            service.shutdown()
            env.frameManager.deleteFrame(frame.id)
            val origins = db.assetOriginDao().getBySourceAssets((1..3).map { "$id-$it" })
            val assetIds = origins.map { it.assetId }.distinct()
            origins.forEach { db.assetOriginDao().delete(it) }
            db.collectionDao().getById(id)?.let { db.collectionDao().delete(it) }
            db.sourceDao().getById(id)?.let { db.sourceDao().delete(it) }
            assetIds.forEach { assetId -> if (db.assetOriginDao().getByAsset(assetId).isEmpty()) db.assetDao().getById(assetId)?.let { asset -> File(context.filesDir, "originals/${asset.originalRelPath}").delete(); db.assetDao().delete(asset) } }
            env.credentialStore.delete(id)
            server.close(); serverScope.coroutineContext[Job]?.cancelAndJoin()
        }
        Unit
    }
}
