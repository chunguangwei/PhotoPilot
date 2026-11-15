package dev.photopilot.scaffold

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.imports.MediaStoreSourceAdapter
import dev.photopilot.scaffold.data.references.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class LocalReferenceRepositoryInstrumentedTest {
    private class FixtureAccess : LocalReferenceAccess {
        val entries = linkedMapOf<String, LocalReferenceEntry>()
        var permission = true
        var persistent = true
        var decodes = 0
        val grants = linkedSetOf<String>()
        val released = mutableSetOf<String>()
        var releaseFailure: String? = null
        var revokeDuringRelease = false
        val deniedUris = mutableSetOf<String>()
        var rawProviderDenial = false
        var onDecode: (() -> Unit)? = null
        override fun photos(): List<LocalReferenceEntry> { checkPermission(); return entries.values.toList() }
        override fun tree(uri: String): List<LocalReferenceEntry> { checkPermission(); return entries.values.toList() }
        override fun document(uri: String, key: String): LocalReferenceEntry? {
            checkPermission()
            if (uri in deniedUris) {
                if (rawProviderDenial) throw SecurityException("Synthetic provider denial")
                throw LocalReferenceException("LOCAL_REFERENCE_PERMISSION_REQUIRED")
            }
            return entries[uri]?.copy(key = key)
        }
        override fun photo(uri: String, key: String): LocalReferenceEntry? = document(uri, key)
        override fun persist(uri: String, flags: Int) {
            if (!persistent) throw LocalReferenceException("LOCAL_REFERENCE_DURABLE_ACCESS_REQUIRED")
            grants.add(uri)
        }
        override fun persistedReads(): Set<String> = grants.toSet()
        override fun release(uri: String) {
            if (revokeDuringRelease) { grants.remove(uri); throw SecurityException("Synthetic grant revocation") }
            if (releaseFailure == uri) throw SecurityException("Synthetic release failure")
            grants.remove(uri)
            released.add(uri)
        }
        override fun covers(grant: String, uri: String): Boolean = grant == uri || uri.startsWith("$grant/document/")
        override fun decode(uri: String, width: Int, height: Int): Bitmap {
            checkPermission()
            decodes++
            onDecode?.invoke()
            return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        }
        private fun checkPermission() { if (!permission) throw LocalReferenceException("LOCAL_REFERENCE_PERMISSION_REQUIRED") }
    }
    private suspend fun isolated(block: suspend (LocalReferenceRepository, PhotoPilotDatabase, FixtureAccess, File) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "local-reference-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(root, name)
        }
        val db = DatabaseFactory.builder(context).build()
        val access = FixtureAccess()
        try { block(LocalReferenceRepository(context, db, access), db, access, root) }
        finally { db.close(); root.deleteRecursively() }
    }
    @Test fun indexingStoresReferencesWithoutOriginalsAssetsOrImportJobs() = runBlocking {
        isolated { repo, db, access, root ->
            val uri = "content://local-reference-fixture/document/one"
            access.entries[uri] = LocalReferenceEntry(uri, "one", "1:40", 40, displayName = "Fixture.jpg")
            val source = repo.indexFiles(listOf(Uri.parse(uri)), 0)
            val asset = db.sourceAssetDao().getBySource(source.id).single()
            assertTrue(repo.valid(asset))
            assertEquals("Fixture.jpg", repo.displayName(asset))
            assertEquals(0, access.decodes)
            assertTrue(db.assetDao().timelineOrder().isEmpty())
            assertTrue(db.importJobDao().getBySource(source.id).isEmpty())
            assertFalse(File(root, "originals").exists())
            val repeated = repo.indexFiles(listOf(Uri.parse(uri)), 0)
            assertEquals(source.id, repeated.id)
            assertEquals(asset.id, db.sourceAssetDao().getBySource(source.id).single().id)
        }
    }
    @Test fun pickingMoreFilesPreservesOldReferencesAndLatestPickerScope() = runBlocking {
        isolated { repo, db, access, _ ->
            val first = "content://local-reference-fixture/document/one"
            val second = "content://local-reference-fixture/document/two"
            access.entries[first] = LocalReferenceEntry(first, first, "1:1", 1)
            access.entries[second] = LocalReferenceEntry(second, second, "1:1", 1)
            val source = repo.indexFiles(listOf(Uri.parse(first)), 0)
            repo.indexFiles(listOf(Uri.parse(second)), 0)
            assertEquals(2, repo.members(source.id, "", false).size)
            assertTrue(db.sourceAssetDao().getBySource(source.id).all { it.accessState == AccessState.ACCESSIBLE })
            val settings = dev.photopilot.scaffold.data.logic.JsonMini.parseObject(db.sourceDao().getById(source.id)!!.settingsJson)
            assertEquals(1, (settings["lastReferenceAssetIds"] as List<*>).size)
        }
    }
    @Test fun photosReuseLegacyIdentityAndAlbumMembership() = runBlocking {
        isolated { repo, db, access, _ ->
            val source = SourceEntity("old", SourceType.LOCAL_PHOTOS, "Photos", MediaStoreSourceAdapter.FINGERPRINT, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, null, 1, 1)
            db.sourceDao().upsert(source)
            db.sourceAssetDao().upsert(SourceAssetEntity("old-asset", source.id, "7", null, "old", 2, null, 1, 0, AccessState.ACCESSIBLE, 1, 1))
            val original = AssetEntity("private-original", "hash", 2, AssetFormat.JPEG, 2, 2, 1, OrientationClass.SQUARE, null, CapturedAtSource.UNKNOWN, 1, "legacy.jpg", "Fixture.jpg", AssetReadiness.READY, null, true, null, deletedAt = null, createdAt = 1, updatedAt = 1)
            db.assetDao().upsert(original)
            val origin = AssetOriginEntity(original.id, "old-asset", 1, PolicyState.ALLOWED, OriginState.ACTIVE, 1, 1)
            db.assetOriginDao().upsert(origin)
            val uri = "content://local-reference-fixture/images/7"
            access.entries[uri] = LocalReferenceEntry(uri, "7", "new", 2, folders = mapOf("albums/one" to "One", "albums/two" to "Two"))
            val updated = repo.indexPhotos()
            assertEquals(source.id, updated.id)
            assertEquals(2L, updated.epoch)
            assertEquals("old-asset", repo.members(source.id, "albums/one", false).single().id)
            assertEquals("old-asset", repo.members(source.id, "albums/two", false).single().id)
            assertTrue(repo.members(source.id, "albums/on", true).isEmpty())
            assertEquals(original, db.assetDao().getById(original.id))
            assertEquals(origin, db.assetOriginDao().getByAsset(original.id).single())
            assertFalse(repo.valid(db.sourceAssetDao().getById("old-asset")!!))
            assertNull(repo.bitmap(db.sourceAssetDao().getById("old-asset")!!, 100, 100))
            assertEquals(0, access.decodes)
        }
    }
    @Test fun changedRevokedAndMidDecodeRevokedReferencesNeverReturnBitmap() = runBlocking {
        isolated { repo, db, access, _ ->
            val uri = "content://local-reference-fixture/document/one"
            access.entries[uri] = LocalReferenceEntry(uri, uri, "1:2", 2)
            val source = repo.indexFiles(listOf(Uri.parse(uri)), 0)
            val asset = db.sourceAssetDao().getBySource(source.id).single()
            access.entries[uri] = access.entries.getValue(uri).copy(revision = "2:2")
            assertFalse(repo.valid(asset))
            assertNull(repo.bitmap(asset, 100, 100))
            repo.refresh(source.id)
            val refreshed = db.sourceAssetDao().getById(asset.id)!!
            access.onDecode = { access.permission = false }
            assertNull(repo.bitmap(refreshed, 100, 100))
            assertEquals(1, access.decodes)
            assertFalse(repo.valid(refreshed))
        }
    }
    @Test fun treeMembershipRespectsDirectChildrenAndDirectoryBoundaries() = runBlocking {
        isolated { repo, db, access, _ ->
            val root = "content://local-reference-fixture/tree/root"
            for ((index, folder) in listOf("", "a", "a/b", "ab").withIndex()) {
                val uri = "content://local-reference-fixture/document/$index"
                access.entries[uri] = LocalReferenceEntry(uri, uri, "1", 1, folders = mapOf(folder to folder))
            }
            val source = repo.indexTree(Uri.parse(root), 0)
            assertEquals(1, repo.members(source.id, "", false).size)
            assertEquals(4, repo.members(source.id, "", true).size)
            assertEquals(1, repo.members(source.id, "a", false).size)
            assertEquals(2, repo.members(source.id, "a", true).size)
            assertTrue(repo.members(source.id, "../a", true).isEmpty())
            val id = db.sourceAssetDao().getBySource(source.id).first { it.remoteKey.endsWith("/1") }.id
            access.entries[access.entries.keys.first { it.endsWith("/1") }] = access.entries.values.first { it.key.endsWith("/1") }.copy(folders = mapOf("renamed" to "Renamed"))
            repo.refresh(source.id)
            assertEquals(id, repo.members(source.id, "renamed", false).single().id)
            assertTrue(repo.members(source.id, "a", false).isEmpty())
        }
    }
    @Test fun startupRefreshRecordsRevocationWithoutBlockingTheLibrary() = runBlocking {
        isolated { repo, db, access, _ ->
            val uri = "content://local-reference-fixture/document/one"
            access.entries[uri] = LocalReferenceEntry(uri, uri, "1", 1)
            val source = repo.indexFiles(listOf(Uri.parse(uri)), 0)
            val asset = db.sourceAssetDao().getBySource(source.id).single()
            access.permission = false
            repo.refreshAll()
            assertEquals(SourceStatus.NEEDS_AUTH, db.sourceDao().getById(source.id)!!.status)
            assertEquals(AccessState.UNAUTHORIZED, db.sourceAssetDao().getById(asset.id)!!.accessState)
            assertFalse(repo.valid(asset))
            access.permission = true
            repo.refresh(source.id)
            assertTrue(repo.valid(db.sourceAssetDao().getById(asset.id)!!))
        }
    }
    @Test fun unknownProviderVersionRemainsReadableButNeverCacheableAcrossRefresh() = runBlocking {
        isolated { repo, db, access, _ ->
            val uri = "content://local-reference-fixture/document/unknown-version"
            access.entries[uri] = LocalReferenceEntry(uri, uri, "null:null", null, versionKnown = false)
            val source = repo.indexFiles(listOf(Uri.parse(uri)), 0)
            val asset = db.sourceAssetDao().getBySource(source.id).single()
            assertTrue(repo.valid(asset))
            assertFalse(repo.cacheable(asset))
            repo.bitmap(asset, 100, 100)?.recycle()
            assertEquals(1, access.decodes)
            repo.refresh(source.id)
            val refreshed = db.sourceAssetDao().getById(asset.id)!!
            assertNotEquals(asset.revision, refreshed.revision)
            assertFalse(repo.valid(asset))
            assertTrue(repo.valid(refreshed))
            assertFalse(repo.cacheable(refreshed))
        }
    }
    @Test fun unsupportedDurableGrantIsVisibleAndCreatesNoSource() = runBlocking {
        isolated { repo, db, access, _ ->
            access.persistent = false
            try {
                repo.indexFiles(listOf(Uri.parse("content://local-reference-fixture/document/one")), 0)
                fail("expected durable grant failure")
            } catch (error: LocalReferenceException) { assertEquals("LOCAL_REFERENCE_DURABLE_ACCESS_REQUIRED", error.code) }
            assertTrue(db.sourceDao().getAll().isEmpty())
        }
    }
    @Test fun failedMultiSelectionReleasesOnlyNewUnusedGrants() = runBlocking {
        isolated { repo, _, access, _ ->
            val old = "content://fixture/document/old"
            val added = "content://fixture/document/added"
            val invalid = "content://fixture/document/invalid"
            access.entries[old] = LocalReferenceEntry(old, old, "1", 1)
            access.entries[added] = LocalReferenceEntry(added, added, "1", 1)
            repo.indexFiles(listOf(Uri.parse(old)), 0)
            assertThrows(LocalReferenceException::class.java) { runBlocking { repo.indexFiles(listOf(Uri.parse(added), Uri.parse(invalid)), 0) } }
            assertEquals(setOf(old), access.grants)
            assertEquals(setOf(added, invalid), access.released)
        }
    }

    @Test fun committedRemovalPreservesSharedTreeGrantsThenReleasesUnusedReferences() = runBlocking {
        isolated { repo, db, access, root ->
            val tree = "content://fixture/tree/root"
            val child = "$tree/document/child"
            access.entries[child] = LocalReferenceEntry(child, child, "1", 1, folders = mapOf("" to "Root"))
            val treeSource = repo.indexTree(Uri.parse(tree), 0)
            val filesSource = repo.indexFiles(listOf(Uri.parse(child)), 0)
            val registry = dev.photopilot.scaffold.data.repo.LocalSourceRegistry(db, File(root, "originals"))
            registry.onConfigurationRemoved = { repo.releaseUnusedGrants() }
            registry.disconnect(treeSource.id, true, 1, removeConfiguration = true)
            assertTrue(tree in access.grants)
            assertTrue(repo.valid(db.sourceAssetDao().getBySource(filesSource.id).single()))
            registry.disconnect(filesSource.id, true, 2, removeConfiguration = true)
            assertTrue(access.grants.isEmpty())
            repo.indexFiles(listOf(Uri.parse(child)), 0)
            db.clearAllTables()
            repo.releaseUnusedGrants()
            assertTrue(access.grants.isEmpty())
        }
    }

    @Test fun hidingPureReferencePersistsAcrossRefreshAndClearsFrameAndAIOwnership() = runBlocking {
        isolated { repo, db, access, _ ->
            val uri = "content://fixture/document/private"
            access.entries[uri] = LocalReferenceEntry(uri, uri, "1", 1)
            val source = repo.indexFiles(listOf(Uri.parse(uri)), 0)
            val asset = db.sourceAssetDao().getBySource(source.id).single()
            val frame = dev.photopilot.scaffold.data.repo.FrameManager(db).createFrame("Hidden fixture")
            db.frameSelectionDao().upsertResolved(frame.id, null, asset.id, FrameSelectionMode.INCLUDE, null, 1)
            db.frameDao().update(frame.copy(settingsJson = "{\"remoteCoverRef\":\"${asset.id}\"}"))
            val state = dev.photopilot.scaffold.data.aiframes.AIStateStore(db)
            state.put(dev.photopilot.scaffold.data.aiframes.AIConfiguration(frame.id, 1, "fixture", "1", emptyList(), false, false, dev.photopilot.scaffold.data.aiframes.AIRule("fixture", threshold = 0.5f), members = setOf("remote:${asset.id}")))
            repo.hide(asset.id, 2)
            repo.refresh(source.id)
            assertTrue(repo.members(source.id, "", true).isEmpty())
            assertFalse(repo.valid(asset))
            assertNull(repo.bitmap(asset, 100, 100))
            assertTrue(db.frameSelectionDao().getByFrame(frame.id).isEmpty())
            assertFalse(dev.photopilot.scaffold.data.logic.JsonMini.parseObject(db.frameDao().getById(frame.id)?.settingsJson).containsKey("remoteCoverRef"))
            assertTrue(state.get(frame.id)!!.members.isEmpty())
            assertEquals(0, access.decodes)
        }
    }
    @Test fun cleanupContinuesAfterFailureAndConcurrentRevocationIsIdempotent() = runBlocking {
        isolated { repo, db, access, _ ->
            val first = "content://fixture/document/first"
            val second = "content://fixture/document/second"
            for (uri in listOf(first, second)) access.entries[uri] = LocalReferenceEntry(uri, uri, "1", 1)
            repo.indexFiles(listOf(Uri.parse(first), Uri.parse(second)), 0)
            db.clearAllTables()
            access.releaseFailure = first
            assertThrows(SecurityException::class.java) { runBlocking { repo.releaseUnusedGrants() } }
            assertEquals(setOf(first), access.grants)
            assertTrue(second in access.released)
            access.revokeDuringRelease = true
            repo.releaseUnusedGrants()
            assertTrue(access.grants.isEmpty())
        }
    }
    @Test fun revokingOneFileDoesNotDisableOtherAuthorizedFiles() = runBlocking {
        isolated { repo, db, access, _ ->
            val first = "content://fixture/document/revoked"
            val second = "content://fixture/document/retained"
            for (uri in listOf(first, second)) access.entries[uri] = LocalReferenceEntry(uri, uri, "1", 1)
            val source = repo.indexFiles(listOf(Uri.parse(first), Uri.parse(second)), 0)
            access.deniedUris.add(first)
            for (rawDenial in listOf(false, true)) {
                access.rawProviderDenial = rawDenial
                repo.refresh(source.id)
                assertEquals(SourceStatus.ACTIVE, db.sourceDao().getById(source.id)?.status)
                val items = db.sourceAssetDao().getBySource(source.id)
                assertEquals(AccessState.UNAUTHORIZED, items.single { it.remoteKey == first }.accessState)
                assertFalse(repo.valid(items.single { it.remoteKey == first }))
                assertTrue(repo.valid(items.single { it.remoteKey == second }))
                assertEquals(listOf(second), repo.members(source.id, "", false).map { it.remoteKey })
            }
        }
    }
}
