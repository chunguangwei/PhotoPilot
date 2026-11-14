package dev.photopilot.scaffold

import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.aiframes.*
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.imports.*
import dev.photopilot.scaffold.data.remote.*
import dev.photopilot.scaffold.data.repo.*
import java.io.File
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteAIWorkflowTest {
    @Test fun realRemotePreviewAndConfirmUseCanonicalRefsWithoutLocalImport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = DatabaseFactory.builder(context).build()
        val id = "remote-ai-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val fixture = File(context.filesDir, "ai-validation")
        val bytes = File(fixture, "validation-cats.png").readBytes()
        var opened = 0
        val adapter = object : SourceAdapter {
            override val sourceFingerprint = id
            override suspend fun enumerate(scopeJson: String, cursor: String?) = EnumerationPage(listOf(EnumeratedAsset("cats.png", null, "1", bytes.size.toLong(), null)), null, 1)
            override fun openAsset(remoteKey: String) = ByteArrayInputStream(bytes).also { opened++ }
        }
        db.sourceDao().upsert(SourceEntity(id, SourceType.SMB, "AI fixture", id, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true}", now, now))
        val media = RemoteMediaRepository(context, db, { adapter }, { true })
        media.index(id, "{\"directories\":[\"\"]}", adapter)
        val collection = db.collectionDao().getBySource(id).single()
        val remote = db.sourceAssetDao().getBySource(id).single()
        val service = AIFrameService(context, db).also { it.remoteMedia = media }
        val model = service.models.models.single { it.id == "multilingual-clip-vitb32-lite" }
        model.files.forEach { file -> File(fixture, "multilingual-clip/${file.name}").copyTo(File(service.models.directory(model), file.name), true); File(service.models.directory(model), file.name + ".verified").writeText(file.sha256) }
        var frames = emptyList<String>()
        val legacyId = "$id-legacy"
        try {
            val before = db.frameDao().getAll().size
            val previews = service.preview(model, listOf(collection.id), false, listOf(AIRule("a photo of cats", threshold = model.defaultThreshold), AIRule("猫的照片", threshold = model.defaultThreshold))) { _, _ -> }
            assertEquals(before, db.frameDao().getAll().size)
            assertTrue(previews.all { it.failed == 0 && "remote:${remote.id}" in it.matches })
            assertEquals(1, opened)
            frames = service.savePreviews(previews, listOf("Remote cats", "远端猫"), previews.map { it.matches.keys }, false)
            assertTrue(frames.all { db.frameSelectionDao().getByFrame(it).single().sourceAssetId == remote.id })
            assertTrue(frames.all { "remote:${remote.id}" in requireNotNull(service.state.get(it)).members })
            assertTrue(db.assetOriginDao().getBySourceAssets(listOf(remote.id)).isEmpty())
            val repo = FrameRepository(db, LibraryVersionRepository(db.settingsKvDao()), File(context.filesDir, "originals")).also { it.remoteMedia = media }
            val frame = requireNotNull(db.frameDao().getById(frames.first()))
            repo.save(frame.id, frame.selectionVersion, addExcludes = listOf(SelectionRefEdit(null, remote.id)))
            assertTrue(requireNotNull(service.state.get(frame.id)).members.isEmpty())
            assertTrue(repo.buildPlayQueue(frame.id).isEmpty())
            val again = service.preview(model, listOf(collection.id), false, listOf(AIRule("cats", threshold = model.defaultThreshold)), frame.id) { _, _ -> }
            assertTrue(again.single().matches.isEmpty())
            db.assetDao().upsert(AssetEntity(legacyId, dev.photopilot.scaffold.data.logic.ContentHash.sha256Hex(legacyId.toByteArray()), bytes.size.toLong(), AssetFormat.PNG, 16, 16, 1, OrientationClass.SQUARE, null, CapturedAtSource.UNKNOWN, now, "$legacyId.png", "old-copy.png", AssetReadiness.PENDING, null, false, null, deletedAt = null, createdAt = now, updatedAt = now))
            db.assetOriginDao().upsert(AssetOriginEntity(legacyId, remote.id, now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
            val beforeAlias = requireNotNull(db.frameDao().getById(frame.id))
            repo.save(frame.id, beforeAlias.selectionVersion, removeRefs = listOf(SelectionRefEdit(null, remote.id)), addExcludes = listOf(SelectionRefEdit(legacyId, null)))
            val historicalExclude = service.preview(model, listOf(collection.id), false, listOf(AIRule("a photo of cats", threshold = model.defaultThreshold)), frame.id) { _, _ -> }
            assertTrue("A released historical Asset exclusion must protect its remote alias", historicalExclude.single().matches.isEmpty())
            service.savePreviews(historicalExclude, listOf("Protected alias"), listOf(emptySet()), false, frame.id, requireNotNull(service.state.get(frame.id)).revision)
            assertFalse(db.frameSelectionDao().getByFrame(frame.id).any { it.sourceAssetId == remote.id && it.mode == FrameSelectionMode.INCLUDE })
            assertEquals(FrameSelectionMode.EXCLUDE, db.frameSelectionDao().getByRef(frame.id, legacyId)?.mode)
        } finally {
            service.shutdown(); media.close()
            frames.forEach { FrameManager(db).deleteFrame(it) }
            db.assetDao().getById(legacyId)?.let { db.assetDao().delete(it) }
            db.collectionDao().getBySource(id).forEach { db.collectionDao().delete(it) }
            db.sourceDao().getById(id)?.let { db.sourceDao().delete(it) }
            db.close()
        }
        Unit
    }
}
