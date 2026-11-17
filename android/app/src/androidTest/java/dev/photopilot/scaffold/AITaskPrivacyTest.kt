package dev.photopilot.scaffold

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.aiframes.*
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.repo.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AITaskPrivacyTest {
    @Test fun hiddenPhotoIsRemovedFromRestoredPartialPreviewWhileOtherProgressAndDraftSurvive() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = DatabaseFactory.builder(context).build()
        var service = AIFrameService(context, db)
        val id = "task-privacy-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val model = service.models.models.single { it.runtime == "onnx" }
        val rule = AIRule("Synthetic photos", threshold = model.defaultThreshold)
        val ids = listOf("$id-a", "$id-b")
        val files = ids.map { File(context.filesDir, "originals/$it.png") }
        db.sourceDao().upsert(SourceEntity(id, SourceType.LOCAL_FILES, "Synthetic album", null, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, null, now, now))
        db.collectionDao().upsert(CollectionEntity(id, null, id, "Synthetic album", null, CollectionType.SOURCE_FOLDER, id, now, now))
        ids.forEachIndexed { index, assetId ->
            val file = files[index]
            file.parentFile!!.mkdirs()
            val bitmap = Bitmap.createBitmap(16 + index, 16, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val hash = dev.photopilot.scaffold.data.logic.ContentHash.sha256Hex(file.readBytes())
            db.sourceAssetDao().upsert(SourceAssetEntity(assetId, id, "$assetId.png", null, "1", file.length(), null, now, 0, AccessState.ACCESSIBLE, now, now))
            db.assetDao().upsert(AssetEntity(assetId, hash, file.length(), AssetFormat.PNG, (16 + index).toLong(), 16, 1, OrientationClass.SQUARE, null, CapturedAtSource.UNKNOWN, now, "$assetId.png", "Synthetic.png", AssetReadiness.READY, null, false, null, deletedAt = null, createdAt = now, updatedAt = now))
            db.assetOriginDao().upsert(AssetOriginEntity(assetId, assetId, now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
            db.collectionAssetDao().upsert(CollectionAssetEntity(id, assetId, now, now))
        }
        var frames = emptyList<String>()
        try {
            val checkpoint = AIPreviewCheckpoint.open(db, model, listOf(id), false, listOf(rule), null, id)
            val candidates = service.mediaCandidates(listOf(id), false)
            ids.forEach { assetId ->
                checkpoint.assets[assetId] = AICachedAsset(candidates.getValue(assetId).fingerprint, listOf(AICachedCategory(AIMatch.MATCH, .5f)), emptyMap())
                checkpoint.saveAsset(assetId)
            }
            val draft = mapOf("draftId" to id, "previewNames" to "[\"My retained name\"]")
            val record = AITaskRecord(id, "preview", model.id, model.revision, status = "user_paused", userPaused = true, done = 2, total = 2, collections = listOf(id), recursive = false, rules = listOf(rule), categoryIds = listOf("stable-category"), draft = draft)
            AppSettingsRepository(db.settingsKvDao()).putRaw("ai.task.$id", record.json().toString())
            assertEquals(ids.toSet(), requireNotNull(service.tasks.previewResult(id)).single().matches.keys)
            AssetRepository(db, LibraryVersionRepository(db.settingsKvDao())).hideAssetGlobally(ids.first(), AssetRepository.REASON_PRIVACY_HIDE, now + 1)
            val restored = requireNotNull(service.tasks.previewResult(id)).single()
            assertFalse("Hidden media cannot remain in an in-memory draft result", ids.first() in restored.matches)
            assertEquals("Unrelated successful checkpoints remain reusable", setOf(ids.last()), restored.matches.keys)
            service.shutdown()
            service = AIFrameService(context, db)
            val reopened = requireNotNull(service.tasks.previewResult(id)).single()
            assertEquals(setOf(ids.last()), reopened.matches.keys)
            val retained = requireNotNull(service.tasks.record(id))
            assertTrue(retained.userPaused)
            assertEquals(draft, retained.draft)
            assertEquals(listOf("stable-category"), retained.categoryIds)
            frames = service.tasks.confirm(id, listOf("My retained name"), listOf(ids.toSet()), true)
            assertEquals(setOf(ids.last()), requireNotNull(service.state.get(frames.single())).members)
            assertTrue(requireNotNull(service.state.get(frames.single())).userPaused)
        } finally {
            service.shutdown()
            frames.forEach { FrameManager(db).deleteFrame(it) }
            AIPreviewCheckpoint.delete(db, id)
            db.settingsKvDao().deleteKey("ai.task.$id")
            ids.forEach { assetId -> db.assetDao().getById(assetId)?.let { db.assetDao().delete(it) } }
            files.forEach(File::delete)
            db.collectionDao().getById(id)?.let { db.collectionDao().delete(it) }
            db.sourceDao().getById(id)?.let { db.sourceDao().delete(it) }
            db.close()
        }
    }
}
