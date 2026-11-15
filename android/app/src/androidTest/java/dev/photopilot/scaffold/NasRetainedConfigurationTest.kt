package dev.photopilot.scaffold

import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.aiframes.AIConfiguration
import dev.photopilot.scaffold.data.aiframes.AIRule
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.repo.SelectionRefEdit
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NasRetainedConfigurationTest {
    @Test fun removingConfigurationRepointsCopiesSelectionsCoverAndAIOwnership() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val env = AppEnvironment.shared(context)
        val db = env.database
        val id = "retained-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val source = env.localSources.ensureNasSource(SourceType.WEBDAV, id, "Retained fixture", 0)
        val remote = SourceAssetEntity(id, source.id, "nested/photo.jpg", null, "1", 1, null, now, 0, AccessState.ACCESSIBLE, now, now)
        val asset = AssetEntity(id, id, 1, AssetFormat.JPEG, 1, 1, 1, OrientationClass.SQUARE, null, CapturedAtSource.UNKNOWN, now, "$id.jpg", null, AssetReadiness.READY, null, false, null, deletedAt = null, createdAt = now, updatedAt = now)
        val file = File(context.filesDir, "originals/${asset.originalRelPath}").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val frame = env.frameManager.createFrame("Retained fixture")
        val directoryFrame = env.frameManager.createFrame("Directory fixture")
        val otherSource = env.localSources.ensureNasSource(SourceType.SMB, "$id-other", "Other AI fixture", 0)
        val otherCollection = "$id-other-collection"
        val otherRemote = "$id-other-remote"
        try {
            db.sourceAssetDao().upsert(remote)
            db.sourceAssetDao().upsert(remote.copy(id = "$id-missing", remoteKey = "nested/missing.jpg"))
            db.assetDao().upsert(asset)
            db.assetOriginDao().upsert(AssetOriginEntity(id, remote.id, now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
            db.collectionDao().upsert(CollectionEntity(id, null, source.id, "Nested", "nested", CollectionType.SOURCE_FOLDER, "nested", now, now))
            db.frameDirectoryRuleDao().upsert(FrameDirectoryRuleEntity(directoryFrame.id, id, true, true, now, now))
            env.frames.save(frame.id, 0, addIncludes = listOf(SelectionRefEdit(null, remote.id), SelectionRefEdit(null, "$id-missing")))
            val selected = requireNotNull(db.frameDao().getById(frame.id))
            db.frameDao().update(selected.copy(settingsJson = "{\"mediaStorageMode\":\"local\",\"remoteCoverRef\":\"$id\"}"))
            env.aiFrames.state.put(AIConfiguration(frame.id, 1, "fixture", "1", listOf(id), true, true, AIRule("fixture", threshold = 0.5f), members = setOf("remote:$id", "remote:$id-missing")))
            db.sourceAssetDao().upsert(remote.copy(id = otherRemote, sourceId = otherSource.id, remoteKey = "other.jpg"))
            db.collectionDao().upsert(CollectionEntity(otherCollection, null, otherSource.id, "Other", "", CollectionType.SOURCE_FOLDER, "", now, now))
            db.frameSelectionDao().upsertResolved(directoryFrame.id, null, otherRemote, FrameSelectionMode.INCLUDE, null, now)
            env.aiFrames.state.put(AIConfiguration(directoryFrame.id, 1, "fixture", "1", listOf(id, otherCollection), true, true, AIRule("fixture", threshold = 0.5f), members = setOf("remote:$id", "remote:$otherRemote")))
            env.localSources.disconnect(source.id, true, now)
            env.localSources.disconnect(source.id, true, now + 1, removeConfiguration = true)
            assertNull(db.sourceDao().getById(source.id))
            assertTrue(file.isFile)
            val origin = db.assetOriginDao().getByAsset(id).single()
            assertEquals(OriginState.ACTIVE, origin.originState)
            val localSourceId = requireNotNull(db.sourceAssetDao().getById(origin.sourceAssetId)).sourceId
            assertEquals(SourceType.LOCAL_FILES, db.sourceDao().getById(localSourceId)?.type)
            assertEquals(listOf(id), db.frameSelectionDao().getByFrame(frame.id).map { it.assetId })
            assertEquals(listOf(id), env.frames.buildPlayQueue(frame.id))
            assertEquals(listOf(id), env.frames.buildPlayQueue(directoryFrame.id))
            assertEquals(id, db.frameDao().getById(frame.id)?.coverAssetId)
            assertFalse(JsonMini.parseObject(db.frameDao().getById(frame.id)?.settingsJson).containsKey("remoteCoverRef"))
            assertTrue(env.aiFrames.state.get(frame.id)?.members.orEmpty().isEmpty())
            assertTrue(env.aiFrames.state.get(frame.id)?.collections.orEmpty().isEmpty())
            assertFalse(env.aiFrames.state.get(frame.id)!!.resumeAutomatically)
            val remainingAI = env.aiFrames.state.get(directoryFrame.id)!!
            assertEquals(listOf(otherCollection), remainingAI.collections)
            assertTrue(remainingAI.autoUpdate)
            assertEquals(setOf("remote:$otherRemote"), remainingAI.members)
            assertTrue(id !in remainingAI.members)
            env.aiFrames.resumeAutomatic()
            kotlinx.coroutines.delay(300)
            assertEquals(listOf(id), env.frames.buildPlayQueue(frame.id))
            assertEquals(id, db.frameSelectionDao().getByFrame(frame.id).single().assetId)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        } finally {
            env.frameManager.deleteFrame(frame.id)
            env.frameManager.deleteFrame(directoryFrame.id)
            db.collectionDao().getById(otherCollection)?.let { db.collectionDao().delete(it) }
            db.sourceDao().getById(otherSource.id)?.let { db.sourceDao().delete(it) }
            val localOrigins = db.assetOriginDao().getByAsset(id)
            db.assetDao().getById(id)?.let { db.assetDao().delete(it) }
            localOrigins.forEach { origin -> db.sourceAssetDao().getById(origin.sourceAssetId)?.let { db.sourceAssetDao().delete(it) } }
            db.collectionDao().getBySource(source.id).forEach { db.collectionDao().delete(it) }
            db.sourceDao().getById(source.id)?.let { db.sourceDao().delete(it) }
            file.delete()
        }
        Unit
    }
    @Test fun currentVersionCopyIsChosenForSelectionCoverAndDirectory() = runBlocking { checkCurrentVersionCopy(true) }

    @Test fun legacyCurrentFolderMembershipSelectsTheRightVersionWithoutProvenance() = runBlocking { checkCurrentVersionCopy(false) }

    private suspend fun checkCurrentVersionCopy(writeProvenance: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val env = AppEnvironment.shared(context)
        val db = env.database
        val id = "version-copy-" + UUID.randomUUID()
        val source = env.localSources.ensureNasSource(SourceType.WEBDAV, id, "Versions fixture", 0, "{\"catalogOnly\":true}")
        val now = System.currentTimeMillis()
        val files = mutableListOf<File>()
        val assets = mutableListOf<AssetEntity>()
        val frame = env.frameManager.createFrame("Current version fixture")
        val directoryFrame = env.frameManager.createFrame("Current directory fixture")
        try {
            for ((suffix, color) in listOf("a-current" to android.graphics.Color.BLUE, "z-old" to android.graphics.Color.RED)) {
                val assetId = "$id-$suffix"
                val file = File(context.filesDir, "originals/$assetId.png").apply { parentFile!!.mkdirs() }
                file.outputStream().use { output -> android.graphics.Bitmap.createBitmap(3, 2, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
                files.add(file)
                val hash = file.inputStream().use { dev.photopilot.scaffold.data.logic.ContentHash.stream(it).contentHash }
                val asset = AssetEntity(assetId, hash, file.length(), AssetFormat.PNG, 3, 2, 1, OrientationClass.LANDSCAPE, null, CapturedAtSource.UNKNOWN, now, "$assetId.png", null, AssetReadiness.READY, null, false, null, deletedAt = null, createdAt = now, updatedAt = now)
                assets.add(asset)
                db.assetDao().upsert(asset)
            }
            val current = assets.first()
            db.sourceAssetDao().upsert(SourceAssetEntity(id, source.id, "photo.png", null, "v2", current.byteSize, null, now, 0, AccessState.ACCESSIBLE, now, now))
            for ((index, asset) in assets.withIndex()) {
                db.assetOriginDao().upsert(AssetOriginEntity(asset.id, id, now + index, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
                val version = if (index == 0) "v2" else "v1"
                if (writeProvenance) db.settingsKvDao().upsert(SettingsKvEntity(dev.photopilot.scaffold.data.remote.RemoteMediaRepository.provenanceKey(asset.id, id), JsonMini.toJson(mapOf("revision" to version, "epoch" to source.epoch, "contentHash" to asset.contentHash)), now, now))
            }
            db.collectionDao().upsert(CollectionEntity(id, null, source.id, "Versions", "", CollectionType.SOURCE_FOLDER, "", now, now))
            db.collectionAssetDao().upsert(CollectionAssetEntity(id, current.id, now, now))
            db.frameSelectionDao().upsertResolved(frame.id, null, id, FrameSelectionMode.INCLUDE, null, now)
            db.frameDao().update(frame.copy(settingsJson = "{\"remoteCoverRef\":\"$id\",\"mediaStorageMode\":\"local\"}"))
            db.frameDirectoryRuleDao().upsert(FrameDirectoryRuleEntity(directoryFrame.id, id, true, true, now, now))
            env.localSources.disconnect(source.id, true, now)
            env.localSources.disconnect(source.id, true, now + 1, removeConfiguration = true)
            assertEquals(listOf(current.id), env.frames.buildPlayQueue(frame.id))
            assertEquals(listOf(current.id), env.frames.buildPlayQueue(directoryFrame.id))
            assertEquals(current.id, db.frameDao().getById(frame.id)?.coverAssetId)
            assertTrue(files.all { it.isFile })
            assertEquals(android.graphics.Color.BLUE, env.remoteMedia.bitmap(env.frames.playbackItems(frame.id).single(), 32, 32)!!.getPixel(0, 0))
        } finally {
            env.frameManager.deleteFrame(frame.id)
            env.frameManager.deleteFrame(directoryFrame.id)
            for (asset in assets) {
                val origins = db.assetOriginDao().getByAsset(asset.id)
                db.assetDao().getById(asset.id)?.let { db.assetDao().delete(it) }
                origins.forEach { db.sourceAssetDao().getById(it.sourceAssetId)?.let { remote -> if (remote.sourceId != source.id) db.sourceAssetDao().delete(remote) } }
            }
            db.collectionDao().getBySource(source.id).forEach { db.collectionDao().delete(it) }
            db.sourceDao().getById(source.id)?.let { db.sourceDao().delete(it) }
            files.forEach { it.delete() }
        }
        Unit
    }
}
