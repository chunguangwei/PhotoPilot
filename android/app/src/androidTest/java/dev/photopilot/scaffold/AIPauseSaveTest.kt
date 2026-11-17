package dev.photopilot.scaffold

import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.aiframes.*
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.repo.FrameManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AIPauseSaveTest {
    @Test fun partialCategoriesSaveIdempotentlyAndOnlyExplicitContinueFinishesThem() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = DatabaseFactory.builder(context).build()
        val service = AIFrameService(context, db)
        val id = "pause-save-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val model = service.models.models.single { it.id == "multilingual-clip-vitb32-lite" }
        model.files.forEach { file -> File(context.filesDir, "ai-validation/multilingual-clip/${file.name}").copyTo(File(service.models.directory(model), file.name), true); File(service.models.directory(model), file.name + ".verified").writeText(file.sha256) }
        db.sourceDao().upsert(SourceEntity(id, SourceType.LOCAL_FILES, "Fixture", null, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, null, now, now))
        db.collectionDao().upsert(CollectionEntity(id, null, id, "Fixture", null, CollectionType.SOURCE_FOLDER, id, now, now))
        val assets = (1..3).map { "$id-$it" }
        for (assetId in assets) {
            val file = File(context.filesDir, "originals/$assetId.png").apply { parentFile!!.mkdirs() }
            File(context.filesDir, "ai-validation/validation-cats.png").copyTo(file, true)
            db.sourceAssetDao().upsert(SourceAssetEntity(assetId, id, "$assetId.png", null, "1", file.length(), null, now, 0, AccessState.ACCESSIBLE, now, now))
            db.assetDao().upsert(AssetEntity(assetId, "fixture-$assetId", file.length(), AssetFormat.PNG, 640, 480, 1, OrientationClass.LANDSCAPE, null, CapturedAtSource.UNKNOWN, now, "$assetId.png", null, AssetReadiness.READY, null, false, null, deletedAt = null, createdAt = now, updatedAt = now))
            db.assetOriginDao().upsert(AssetOriginEntity(assetId, assetId, now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
            db.collectionAssetDao().upsert(CollectionAssetEntity(id, assetId, now, now))
        }
        var frames = emptyList<String>()
        try {
            var pause = false
            val previews = service.preview(model, listOf(id), false, listOf(AIRule("a photo of a cat", threshold = model.defaultThreshold), AIRule("a photo of a beach", threshold = .95f)), checkpointId = id, allowPartial = true, stopRequested = { pause }) { count, _ -> if (count == 1) pause = true }
            assertTrue(previews.all { !it.isComplete && it.scannedCount == 1 && it.totalCount == 3 })
            frames = service.savePreviews(previews, listOf("Cats", "Still paused"), previews.map { it.matches.keys }, true, paused = true, draftId = id, categoryIds = listOf("cats", "beach"))
            assertEquals(2, frames.size)
            assertTrue(db.frameSelectionDao().getByFrame(frames[1]).isEmpty())
            assertTrue(frames.all { service.state.get(it)?.userPaused == true })
            val repeated = service.savePreviews(previews.reversed(), listOf("Renamed empty", "Renamed cats"), previews.reversed().map { it.matches.keys }, true, paused = true, draftId = id, categoryIds = listOf("beach", "cats"))
            assertEquals(frames.reversed(), repeated)
            service.resumeAutomatic()
            delay(300)
            assertTrue(frames.all { service.state.get(it)?.processed?.size == 1 })
            service.setAutomatic(frames[0], true)
            assertTrue(requireNotNull(service.state.get(frames[0])).userPaused)
            service.resume(frames[0])
            withTimeout(15000) { while (service.state.get(frames[0])?.status != "complete") delay(100) }
            assertEquals(3, requireNotNull(service.state.get(frames[0])).processed.size)
            assertEquals(3, requireNotNull(service.state.get(frames[0])).completed)
            assertTrue(requireNotNull(service.state.get(frames[1])).userPaused)
            val checkpointId = "$id-hard-stop"
            val reached = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val rules = listOf(AIRule("a photo of a cat", threshold = model.defaultThreshold))
            val worker = launch(Dispatchers.IO) {
                service.preview(model, listOf(id), false, rules, checkpointId = checkpointId) { count, _ ->
                    if (count == 2) {
                        reached.countDown()
                        release.await()
                    }
                }
            }
            try {
                assertTrue(reached.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val stored = db.settingsKvDao().getByPrefix(AIPreviewCheckpoint.PREFIX + checkpointId) + db.settingsKvDao().getByPrefix(AIPreviewCheckpoint.itemsPrefix(AIPreviewCheckpoint.PREFIX + checkpointId))
                val durable = stored.sumOf { row ->
                    val value = org.json.JSONObject(row.valueJson)
                    (value.optJSONObject("assets")?.length() ?: 0) + if (value.optJSONObject("asset") != null) 1 else 0
                }
                assertEquals("Every successful photo is durable before its progress is published", 2, durable)
                val recovered = AIPreviewCheckpoint.open(db, model, listOf(id), false, rules, null, checkpointId)
                assertEquals(2, recovered.assets.size)
            } finally {
                worker.cancel()
                release.countDown()
                worker.join()
            }
            val recovered = service.preview(model, listOf(id), false, rules, checkpointId = checkpointId) { _, _ -> }.single()
            assertEquals("The superseded writer cannot supply uncommitted work during restart", 2, recovered.reusedAssetCount)

        } finally {
            service.shutdown()
            frames.forEach { FrameManager(db).deleteFrame(it) }
            assets.forEach { assetId -> db.assetDao().getById(assetId)?.let { db.assetDao().delete(it) }; File(context.filesDir, "originals/$assetId.png").delete() }
            db.collectionDao().getById(id)?.let { db.collectionDao().delete(it) }
            db.sourceDao().getById(id)?.let { db.sourceDao().delete(it) }
            db.close()
        }
    }
}
