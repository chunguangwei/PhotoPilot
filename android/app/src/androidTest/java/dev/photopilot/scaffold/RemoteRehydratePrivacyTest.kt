package dev.photopilot.scaffold

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.imports.*
import dev.photopilot.scaffold.data.logic.ContentHash
import dev.photopilot.scaffold.data.repo.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteRehydratePrivacyTest {
    @Test fun hideDuringOriginalRestoreCannotBeUndone() = runBlocking { verifyRevocation(false) }
    @Test fun deleteDuringOriginalRestoreCannotBeUndone() = runBlocking { verifyRevocation(true) }

    private suspend fun verifyRevocation(deleting: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = DatabaseFactory.builder(context).build()
        val id = "rehydrate-privacy-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val bytes = ByteArrayOutputStream().also { Bitmap.createBitmap(if (deleting) 7 else 6, 5, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val hash = ContentHash.sha256Hex(bytes)
        val original = File(context.filesDir, "originals/$id.png")
        val temp = File(context.cacheDir, id).apply { mkdirs() }
        val manager = FrameManager(db)
        val assets = AssetRepository(db, LibraryVersionRepository(db.settingsKvDao()))
        db.sourceDao().upsert(SourceEntity(id, SourceType.SMB, "Restore privacy fixture", id, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true}", now, now))
        db.sourceAssetDao().upsert(SourceAssetEntity(id, id, "photo.png", null, "1", bytes.size.toLong(), null, now, 0, AccessState.ACCESSIBLE, now, now))
        db.assetDao().upsert(AssetEntity(id, hash, bytes.size.toLong(), AssetFormat.PNG, if (deleting) 7 else 6, 5, 1, OrientationClass.LANDSCAPE, null, CapturedAtSource.UNKNOWN, now, "$id.png", "photo.png", AssetReadiness.PENDING, null, false, null, deletedAt = null, createdAt = now, updatedAt = now))
        db.assetOriginDao().upsert(AssetOriginEntity(id, id, now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
        val frame = manager.createFrame("Restore privacy fixture")
        db.frameDao().update(frame.copy(settingsJson = org.json.JSONObject(frame.settingsJson).put("mediaStorageMode", "local").toString()))
        val repository = FrameRepository(db, LibraryVersionRepository(db.settingsKvDao()), original.parentFile!!)
        repository.save(frame.id, 0, addIncludes = listOf(SelectionRefEdit(id, null)))
        val realStore = LocalImportStore(original.parentFile!!, temp)
        val store = object : ImportStore by realStore {
            override fun commitStaged(tempRelPath: String, targetRelPath: String) {
                realStore.commitStaged(tempRelPath, targetRelPath)
                runBlocking {
                    if (deleting) assets.deleteAssetWithTombstone(id, AssetRepository.REASON_USER_DELETE, System.currentTimeMillis())
                    else assets.hideAssetGlobally(id, AssetRepository.REASON_PRIVACY_HIDE, System.currentTimeMillis())
                }
            }
        }
        val coordinator = ImportCoordinator(RoomCommitScope(db), store)
        val adapter = object : SourceAdapter {
            override val sourceFingerprint = id
            override suspend fun enumerate(scopeJson: String, cursor: String?) = EnumerationPage(listOf(EnumeratedAsset("photo.png", null, "1", bytes.size.toLong(), null)), null, 1)
            override fun openAsset(remoteKey: String) = ByteArrayInputStream(bytes)
        }
        try {
            val frameVersion = requireNotNull(db.frameDao().getById(frame.id)).selectionVersion
            val job = coordinator.createJob(id, dev.photopilot.scaffold.data.logic.JsonMini.toJson(mapOf("frameId" to frame.id, "selectionVersion" to frameVersion, "sourceRevisions" to mapOf(id to "1"), "directories" to emptyList<String>())))
            coordinator.enumerate(job, adapter)
            val item = db.importItemDao().getByJob(job).single()
            val staged = coordinator.stage(item.id, adapter) as ImportCoordinator.StageOutcome.Staged
            val outcome = coordinator.commit(item.id, staged.payload, ImportCoordinator.AssetCommitMetadata(AssetFormat.PNG, if (deleting) 7 else 6, 5, 1, null, CapturedAtSource.UNKNOWN, "photo.png"))
            val current = requireNotNull(db.assetDao().getById(id))
            if (deleting) { assertNotNull(current.deletedAt); assertEquals(AssetReadiness.DELETED, current.readiness) }
            else assertTrue(current.privacyExcluded)
            assertEquals(ImportCoordinator.CommitOutcome.SkippedByTombstone, outcome)
            assertTrue(repository.buildPlayQueue(frame.id).isEmpty())
            assertFalse(original.exists())
        } finally {
            manager.deleteFrame(frame.id)
            db.assetDao().getById(id)?.let { db.assetDao().delete(it) }
            db.collectionDao().getBySource(id).forEach { db.collectionDao().delete(it) }
            db.sourceDao().getById(id)?.let { db.sourceDao().delete(it) }
            db.localTombstoneDao().getByContentHash(hash).forEach { db.localTombstoneDao().delete(it) }
            original.delete(); temp.deleteRecursively(); db.close()
        }
    }
}
