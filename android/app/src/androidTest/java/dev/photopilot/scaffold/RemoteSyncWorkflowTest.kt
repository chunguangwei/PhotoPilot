package dev.photopilot.scaffold

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.imports.*
import dev.photopilot.scaffold.data.remote.*
import dev.photopilot.scaffold.data.repo.SelectionRefEdit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteSyncWorkflowTest {
    @Test fun scopedSyncPreservesSourceReferenceAndRehydratesReleasedOriginal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val env = AppEnvironment.shared(context)
        val db = env.database
        val id = "remote-sync-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val bytes = ByteArrayOutputStream().also { Bitmap.createBitmap(31, 25, Bitmap.Config.ARGB_8888).apply { eraseColor(id.hashCode()) }.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val opened = mutableListOf<String>()
        val adapter = object : SourceAdapter {
            override val sourceFingerprint = id
            override suspend fun enumerate(scopeJson: String, cursor: String?) = EnumerationPage(listOf(EnumeratedAsset("chosen.png", null, "1", bytes.size.toLong(), null), EnumeratedAsset("unselected.png", null, "1", bytes.size.toLong(), null)), null, 2)
            override fun openAsset(remoteKey: String) = ByteArrayInputStream(bytes).also { opened.add(remoteKey) }
        }
        db.sourceDao().upsert(SourceEntity(id, SourceType.SMB, "Fixture", id, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true}", now, now))
        val media = RemoteMediaRepository(context, db, { adapter }, { true })
        media.index(id, "{\"directories\":[\"\"]}", adapter)
        val remote = db.sourceAssetDao().getBySource(id).single { it.remoteKey == "chosen.png" }
        val frame = env.frameManager.createFrame("Scoped remote test")
        val copies = RemoteLocalCopies(context, env, { adapter })
        var assetId: String? = null
        var legacyFrameId: String? = null
        try {
            env.frames.save(frame.id, 0, addIncludes = listOf(SelectionRefEdit(null, remote.id)))
            assertEquals("Legacy missing mode remains playable on demand", 1, env.frames.playbackItems(frame.id).size)
            assertEquals(1, env.frames.resolve(frame.id).playable.size)
            val selected = requireNotNull(db.frameDao().getById(frame.id))
            val frameScope = dev.photopilot.scaffold.data.logic.JsonMini.toJson(mapOf("frameId" to frame.id, "selectionVersion" to selected.selectionVersion))
            assertFalse(RoomCommitScope(db).frameScopeValid(frameScope))
            assertTrue(RoomCommitScope(db).frameScopeValid("{\"directories\":[\"\"]}"))
            copies.sync(frame.id)
            copies.metadataUpdated(id)
            kotlinx.coroutines.delay(750)
            assertTrue("Missing mode never opens originals for background copies", opened.isEmpty())
            assertFalse(copies.isRunning(frame.id))
            assertTrue(db.importJobDao().getBySource(id).isEmpty())
            assertTrue(db.assetOriginDao().getBySourceAssets(listOf(remote.id)).isEmpty())
            db.frameDao().update(selected.copy(settingsJson = "{\"mediaStorageMode\":\"local\"}"))
            assertTrue(RoomCommitScope(db).frameScopeValid(frameScope))
            copies.sync(frame.id)
            assertEquals(listOf("chosen.png"), opened)
            val origin = db.assetOriginDao().getBySourceAssets(listOf(remote.id)).single()
            assetId = origin.assetId
            assertEquals(remote.id, db.frameSelectionDao().getByFrame(frame.id).single().sourceAssetId)
            assertNotNull(db.settingsKvDao().get(RemoteMediaRepository.provenanceKey(origin.assetId, remote.id)))
            val asset = requireNotNull(db.assetDao().getById(origin.assetId))
            val original = File(context.filesDir, "originals/${asset.originalRelPath}")
            assertTrue(original.isFile)
            val legacyFrame = env.frameManager.createFrame("Retained legacy copy")
            legacyFrameId = legacyFrame.id
            env.frames.save(legacyFrame.id, 0, addIncludes = listOf(SelectionRefEdit(asset.id, null)))
            env.localSources.disconnect(id, keepCopies = true, now = System.currentTimeMillis())
            assertEquals(OriginState.DETACHED, db.assetOriginDao().getByAssetAndSourceAsset(asset.id, remote.id)?.originState)
            val retained = env.frames.playbackItems(frame.id)
            assertEquals("Explicitly retained local copy remains playable while disconnected", 1, retained.size)
            assertEquals(asset.id, retained.single().local?.id)
            val opensBeforeOfflineRead = opened.size
            assertTrue(media.read(retained.single()) { localFile, _ -> localFile.isFile })
            assertEquals(opensBeforeOfflineRead, opened.size)
            assertEquals(listOf(asset.id), env.frames.buildPlayQueue(legacyFrame.id))
            env.frameManager.deleteFrame(legacyFrame.id)
            legacyFrameId = null
            env.localSources.ensureNasSource(SourceType.SMB, id, "Fixture", 0)
            copies.sync(frame.id)
            assertEquals(OriginState.ACTIVE, db.assetOriginDao().getByAssetAndSourceAsset(asset.id, remote.id)?.originState)
            assertEquals(asset.id, env.frames.playbackItems(frame.id).single().local?.id)
            val trash = File(context.filesDir, "remote-trash/${asset.id}").apply { parentFile!!.mkdirs() }
            val intent = SettingsKvEntity("remote.cleanup.${asset.id}", dev.photopilot.scaffold.data.logic.JsonMini.toJson(mapOf("assetId" to asset.id, "path" to asset.originalRelPath)), now, now)
            db.settingsKvDao().upsert(intent)
            assertTrue(original.renameTo(trash))
            copies.recover()
            assertTrue(original.isFile)
            assertFalse(trash.exists())
            assertNull(db.settingsKvDao().get(intent.key))
            db.frameSelectionDao().deleteByRef(frame.id, remote.id)
            db.frameSelectionDao().upsertResolved(frame.id, asset.id, null, FrameSelectionMode.INCLUDE, 1, now)
            val current = requireNotNull(db.frameDao().getById(frame.id))
            env.frames.migrateNasSelections(frame.id, current.selectionVersion)
            assertEquals(remote.id, db.frameSelectionDao().getByFrame(frame.id).single().sourceAssetId)
            db.frameDao().update(current.copy(settingsJson = "{\"mediaStorageMode\":\"remote\"}"))
            assertEquals(asset.byteSize, copies.release(frame.id, false))
            assertFalse(original.exists())
            assertEquals(AssetReadiness.PENDING, db.assetDao().getById(asset.id)?.readiness)
            db.frameDao().update(current.copy(settingsJson = "{\"mediaStorageMode\":\"local\"}"))
            copies.sync(frame.id)
            assertTrue(original.exists())
            assertEquals(asset.id, db.assetOriginDao().getBySourceAssets(listOf(remote.id)).single().assetId)
            assertEquals(AssetReadiness.READY, db.assetDao().getById(asset.id)?.readiness)
            assertTrue(opened.all { it == "chosen.png" })
        } finally {
            copies.shutdown()
            media.close()
            legacyFrameId?.let { env.frameManager.deleteFrame(it) }
            env.frameManager.deleteFrame(frame.id)
            assetId?.let { db.assetDao().getById(it)?.let { asset -> File(context.filesDir, "originals/${asset.originalRelPath}").delete(); db.assetDao().delete(asset) } }
            db.collectionDao().getBySource(id).sortedByDescending { it.relPath?.length ?: 0 }.forEach { db.collectionDao().delete(it) }
            db.sourceDao().getById(id)?.let { db.sourceDao().delete(it) }
        }
        Unit
    }
}
