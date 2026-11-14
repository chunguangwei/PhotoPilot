package dev.photopilot.scaffold

import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.aiframes.*
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.repo.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

class AIFrameWorkflowTest {
    @Test fun realPreviewIsReadOnlyAndConfirmationPreservesManualExclusion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = DatabaseFactory.builder(context).build()
        val service = AIFrameService(context, db)
        val manager = FrameManager(db)
        val prefix = "ai-workflow-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val fixture = File(context.filesDir, "ai-validation")
        val model = service.models.models.single { it.id == "multilingual-clip-vitb32-lite" }
        val directory = service.models.directory(model)
        for (file in model.files) {
            File(fixture, "multilingual-clip/${file.name}").copyTo(File(directory, file.name), overwrite = true)
            File(directory, file.name + ".verified").writeText(file.sha256)
        }
        val original = File(context.filesDir, "originals/$prefix.png").apply { parentFile!!.mkdirs() }
        File(fixture, "validation-cats.png").copyTo(original, overwrite = true)
        db.sourceDao().upsert(SourceEntity(prefix, SourceType.LOCAL_FILES, "Validation", null, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, null, now, now))
        db.sourceAssetDao().upsert(SourceAssetEntity(prefix, prefix, "validation.png", null, "1", original.length(), null, now, 0, AccessState.ACCESSIBLE, now, now))
        db.assetDao().upsert(AssetEntity(prefix, "validation-$prefix", original.length(), AssetFormat.PNG, 640, 480, 1, OrientationClass.LANDSCAPE, null, CapturedAtSource.UNKNOWN, now, "$prefix.png", "validation.png", AssetReadiness.READY, null, false, null, deletedAt = null, createdAt = now, updatedAt = now))
        db.assetOriginDao().upsert(AssetOriginEntity(prefix, prefix, now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
        db.collectionDao().upsert(CollectionEntity(prefix, null, prefix, "Validation samples", null, CollectionType.SOURCE_FOLDER, prefix, now, now))
        db.collectionAssetDao().upsert(CollectionAssetEntity(prefix, prefix, now, now))
        val baseline = manager.listFrames().map { it.id }.toSet()
        var created = emptyList<String>()
        try {
            val previews = service.preview(model, listOf(prefix), true, listOf(AIRule("a photo of a cat", threshold = model.defaultThreshold), AIRule("猫的照片", threshold = model.defaultThreshold))) { _, _ -> }
            assertEquals(baseline, manager.listFrames().map { it.id }.toSet())
            assertTrue(previews.all { prefix in it.matches })
            assertTrue(previews.all { it.suggestedNames(false).isNotEmpty() })
            created = service.savePreviews(previews, listOf("English cats", "中文猫"), previews.map { it.matches.keys }, automatic = false)
            assertEquals(2, created.size)
            assertTrue(created.all { service.state.get(it)?.autoUpdate == false })
            val frame = requireNotNull(db.frameDao().getById(created.first()))
            val repository = FrameRepository(db, LibraryVersionRepository(db.settingsKvDao()), File(context.filesDir, "originals"))
            repository.save(frame.id, frame.selectionVersion, addExcludes = listOf(SelectionRefEdit(prefix, null)))
            assertFalse(prefix in requireNotNull(service.state.get(frame.id)).members)
            val second = requireNotNull(db.frameDao().getById(created[1]))
            repository.save(second.id, second.selectionVersion, addIncludes = listOf(SelectionRefEdit(prefix, null)))
            val beforeReclassify = requireNotNull(service.state.get(second.id))
            assertFalse(prefix in beforeReclassify.members)
            val negativePreview = service.preview(model, listOf(prefix), true, listOf(AIRule("a photo of a beach", threshold = .9f))) { _, _ -> }
            assertTrue(negativePreview.single().matches.isEmpty())
            service.savePreviews(negativePreview, listOf("Manually maintained"), listOf(emptySet()), false, second.id, beforeReclassify.revision)
            assertEquals(FrameSelectionMode.INCLUDE, db.frameSelectionDao().getByFrame(second.id).single().mode)
            val failureFrame = service.savePreviews(listOf(previews[0]), listOf("Failure validation"), listOf(setOf(prefix)), false).single()
            created = created + failureFrame
            val failureState = requireNotNull(service.state.get(failureFrame))
            val failedPreview = previews[0].copy(matches = emptyMap(), reviews = emptyMap(), fingerprints = emptyMap(), failed = 1, failedAssetIDs = setOf(prefix))
            service.savePreviews(listOf(failedPreview), listOf("Failure validation"), listOf(emptySet()), false, failureFrame, failureState.revision)
            assertTrue(prefix in requireNotNull(service.state.get(failureFrame)).members)
            assertEquals(FrameSelectionMode.INCLUDE, db.frameSelectionDao().getByFrame(failureFrame).single().mode)
            val sourceExcludedFrame = service.savePreviews(listOf(previews[0]), listOf("Source exclusion validation"), listOf(setOf(prefix)), false).single()
            created = created + sourceExcludedFrame
            val sourceFrame = requireNotNull(db.frameDao().getById(sourceExcludedFrame))
            repository.save(sourceFrame.id, sourceFrame.selectionVersion, addExcludes = listOf(SelectionRefEdit(null, prefix)))
            assertFalse(prefix in requireNotNull(service.state.get(sourceFrame.id)).members)
            assertEquals(FrameSelectionMode.EXCLUDE, db.frameSelectionDao().getByFrame(sourceFrame.id).single().mode)
            val hiddenFrameId = service.savePreviews(listOf(previews[0]), listOf("Privacy validation"), listOf(setOf(prefix)), false).single()
            created = created + hiddenFrameId
            manager.setCover(hiddenFrameId, prefix)
            assertTrue(prefix in requireNotNull(service.state.get(hiddenFrameId)).members)
            AssetRepository(db, LibraryVersionRepository(db.settingsKvDao())).hideAssetGlobally(prefix, "validation", now)
            assertFalse(prefix in requireNotNull(service.state.get(hiddenFrameId)).members)
            assertFalse(prefix in requireNotNull(service.state.get(hiddenFrameId)).processed)
            assertNull(db.frameDao().getById(hiddenFrameId)?.coverAssetId)
            manager.setCover(hiddenFrameId, prefix)
            assertNull(repository.eligibleCover(prefix))
            assertEquals(FrameSelectionMode.INCLUDE, db.frameSelectionDao().getByFrame(second.id).single().mode)
            val beforeToggle = requireNotNull(service.state.get(frame.id))
            service.models.delete(model)
            service.configure(frame.id, model, listOf(prefix), true, true, beforeToggle.rule)
            assertTrue(requireNotNull(service.state.get(frame.id)).autoUpdate)
            assertEquals(beforeToggle.processed, requireNotNull(service.state.get(frame.id)).processed)
            service.configure(frame.id, model, listOf(prefix), true, false, beforeToggle.rule)
            assertFalse(requireNotNull(service.state.get(frame.id)).autoUpdate)
            assertEquals(FrameSelectionMode.EXCLUDE, db.frameSelectionDao().getByFrame(frame.id).single().mode)
        } finally {
            service.shutdown()
            created.forEach { manager.deleteFrame(it) }
            db.assetDao().getById(prefix)?.let { db.assetDao().delete(it) }
            db.collectionDao().getById(prefix)?.let { db.collectionDao().delete(it) }
            db.sourceDao().getById(prefix)?.let { db.sourceDao().delete(it) }
            original.delete()
            db.close()
        }
    }
}
