package dev.photopilot.scaffold

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.imports.*
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.repo.SelectionRefEdit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteScopeGuardTest {
    @Test fun modeChangeBeforeCommitCancelsAndMetadataRefreshRetainsReferences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val env = AppEnvironment.shared(context)
        val db = env.database
        val id = "remote-guard-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val payload = ByteArrayOutputStream().also { Bitmap.createBitmap(17, 21, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        db.sourceDao().upsert(SourceEntity(id, SourceType.SMB, "Guard fixture", id, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true}", now, now))
        val sourceAsset = SourceAssetEntity(id, id, "photo.png", null, "1", payload.size.toLong(), null, now, 0, AccessState.ACCESSIBLE, now, now)
        db.sourceAssetDao().upsert(sourceAsset)
        val frame = env.frameManager.createFrame("Guard fixture")
        db.frameDao().update(frame.copy(settingsJson = "{\"mediaStorageMode\":\"local\"}"))
        env.frames.save(frame.id, 0, addIncludes = listOf(SelectionRefEdit(null, id)))
        val current = requireNotNull(db.frameDao().getById(frame.id))
        val json = JsonMini.toJson(mapOf("frameId" to frame.id, "selectionVersion" to current.selectionVersion, "sourceRevisions" to mapOf(id to "1")))
        val adapter = object : SourceAdapter {
            override val sourceFingerprint = id
            override suspend fun enumerate(scopeJson: String, cursor: String?) = EnumerationPage(listOf(EnumeratedAsset("photo.png", null, "1", payload.size.toLong(), null)), null, 1)
            override fun openAsset(remoteKey: String) = ByteArrayInputStream(payload)
        }
        try {
            val job = env.coordinator.createJob(id, json)
            env.coordinator.enumerate(job, adapter)
            assertEquals(id, db.frameSelectionDao().getByFrame(frame.id).single().sourceAssetId)
            val item = db.importItemDao().getByJob(job).single()
            val staged = env.coordinator.stage(item.id, adapter) as ImportCoordinator.StageOutcome.Staged
            db.frameDao().update(current.copy(settingsJson = "{\"mediaStorageMode\":\"remote\"}"))
            val outcome = env.coordinator.commit(item.id, staged.payload, ImportCoordinator.AssetCommitMetadata(AssetFormat.PNG, 17, 21, 1, null, CapturedAtSource.UNKNOWN, "photo.png"))
            assertEquals(ImportCoordinator.CommitOutcome.CanceledByEpoch, outcome)
            assertTrue(db.assetOriginDao().getBySourceAssets(listOf(id)).isEmpty())
            assertEquals(id, db.frameSelectionDao().getByFrame(frame.id).single().sourceAssetId)
        } finally {
            env.frameManager.deleteFrame(frame.id)
            db.collectionDao().getBySource(id).forEach { db.collectionDao().delete(it) }
            db.sourceDao().getById(id)?.let { db.sourceDao().delete(it) }
        }
        Unit
    }
}
