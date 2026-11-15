package dev.photopilot.scaffold

import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.repo.LocalSourceRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NasSourceRemovalTest {
    @Test fun deletingDisconnectedSourcePreservesOtherOriginsAndNestedCollections() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "remove-" + UUID.randomUUID()
        val db = DatabaseFactory.builder(context).build()
        val now = System.currentTimeMillis()
        val originals = File(context.cacheDir, id).apply { mkdirs() }
        val registry = LocalSourceRegistry(db, originals)
        val source = registry.ensureNasSource(SourceType.WEBDAV, id, "Removal fixture", 0)
        val other = registry.ensureLocalFiles()
        val remote = SourceAssetEntity(id, source.id, "nested/photo.jpg", null, "1", 1, null, now, 0, AccessState.ACCESSIBLE, now, now)
        val local = remote.copy(id = "$id-local", sourceId = other.id, remoteKey = id)
        val asset = AssetEntity(id, id, 1, AssetFormat.JPEG, 1, 1, 1, OrientationClass.SQUARE, null, CapturedAtSource.UNKNOWN, now, "$id.jpg", null, AssetReadiness.READY, null, false, null, deletedAt = null, createdAt = now, updatedAt = now)
        val file = File(originals, asset.originalRelPath).apply { writeBytes(byteArrayOf(1)) }
        try {
            db.sourceAssetDao().upsert(remote)
            db.sourceAssetDao().upsert(local)
            db.assetDao().upsert(asset)
            db.assetOriginDao().upsert(AssetOriginEntity(id, remote.id, now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
            db.assetOriginDao().upsert(AssetOriginEntity(id, local.id, now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
            db.collectionDao().upsert(CollectionEntity("$id-root", null, source.id, "Root", "", CollectionType.SOURCE_FOLDER, "", now, now))
            db.collectionDao().upsert(CollectionEntity("$id-child", "$id-root", source.id, "Nested", "nested", CollectionType.SOURCE_FOLDER, "nested", now, now))
            registry.disconnect(source.id, true, now)
            registry.disconnect(source.id, false, now + 1)
            assertNull(db.sourceDao().getById(source.id))
            assertNotNull(db.assetDao().getById(asset.id))
            assertTrue(file.exists())
            assertEquals(listOf(local.id), db.assetOriginDao().getByAsset(id).map { it.sourceAssetId })
            assertTrue(db.collectionDao().getBySource(source.id).isEmpty())
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        } finally {
            db.assetDao().getById(id)?.let { db.assetDao().delete(it) }
            db.sourceAssetDao().getById(local.id)?.let { db.sourceAssetDao().delete(it) }
            db.collectionDao().getBySource(source.id).sortedByDescending { it.relPath?.length ?: 0 }.forEach { db.collectionDao().delete(it) }
            db.sourceDao().getById(source.id)?.let { db.sourceDao().delete(it) }
            originals.deleteRecursively()
            db.close()
        }
        Unit
    }
    @Test fun retainedSharedCopyHasAnIndependentPrivateOrigin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "shared-retained-" + UUID.randomUUID()
        val db = DatabaseFactory.builder(context).build()
        val now = System.currentTimeMillis()
        val originals = File(context.cacheDir, id).apply { mkdirs() }
        val registry = LocalSourceRegistry(db, originals)
        val first = registry.ensureNasSource(SourceType.WEBDAV, "$id-first", "First fixture", 0)
        val second = registry.ensureNasSource(SourceType.SMB, "$id-second", "Second fixture", 0)
        val asset = AssetEntity(id, id, 1, AssetFormat.JPEG, 1, 1, 1, OrientationClass.SQUARE, null, CapturedAtSource.UNKNOWN, now, "$id.jpg", null, AssetReadiness.READY, null, false, null, deletedAt = null, createdAt = now, updatedAt = now)
        val file = File(originals, asset.originalRelPath).apply { writeBytes(byteArrayOf(1)) }
        try {
            db.assetDao().upsert(asset)
            for (source in listOf(first, second)) {
                val remoteId = "$id-${source.id}"
                db.sourceAssetDao().upsert(SourceAssetEntity(remoteId, source.id, "photo.jpg", null, "1", 1, null, now, 0, AccessState.ACCESSIBLE, now, now))
                db.assetOriginDao().upsert(AssetOriginEntity(id, remoteId, now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
            }
            registry.disconnect(first.id, true, now, removeConfiguration = true)
            registry.disconnect(second.id, false, now + 1)
            assertTrue(file.isFile)
            assertNotNull(db.assetDao().getById(id))
            val origin = db.assetOriginDao().getByAsset(id).single()
            val privateSourceId = requireNotNull(db.sourceAssetDao().getById(origin.sourceAssetId)).sourceId
            assertEquals(SourceType.LOCAL_FILES, db.sourceDao().getById(privateSourceId)?.type)
            assertEquals(PolicyState.ALLOWED, origin.policyState)
        } finally {
            val retained = db.assetOriginDao().getByAsset(id)
            db.assetDao().getById(id)?.let { db.assetDao().delete(it) }
            retained.forEach { origin -> db.sourceAssetDao().getById(origin.sourceAssetId)?.let { db.sourceAssetDao().delete(it) } }
            for (source in listOf(first, second)) db.sourceDao().getById(source.id)?.let { db.sourceDao().delete(it) }
            originals.deleteRecursively()
            db.close()
        }
        Unit
    }
}
