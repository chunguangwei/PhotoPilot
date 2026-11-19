package dev.photopilot.scaffold.data.remote

import android.content.Context
import androidx.room.withTransaction
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.imports.*
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.repo.LibraryVersionRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*

class RemoteMediaRepository(
    private val context: Context,
    private val db: PhotoPilotDatabase,
    private val adapterFor: (SourceEntity) -> SourceAdapter?,
    private val networkAllowed: () -> Boolean,
) {
    fun isMetadataSource(source: SourceEntity): Boolean = source.type in setOf(SourceType.SMB, SourceType.WEBDAV) ||
        (source.type in setOf(SourceType.LOCAL_PHOTOS, SourceType.LOCAL_FILES) && JsonMini.parseObject(source.settingsJson)["referenceOnly"] == true)

    var onCatalogUpdated: ((String) -> Unit)? = null
    var localReferences: dev.photopilot.scaffold.data.references.LocalReferenceRepository? = null
    suspend fun isLocalReference(item: PlaybackItem): Boolean {
        val source = item.remote?.let { db.sourceDao().getById(it.sourceId) } ?: return false
        return source.type in setOf(SourceType.LOCAL_PHOTOS, SourceType.LOCAL_FILES) && JsonMini.parseObject(source.settingsJson)["referenceOnly"] == true
    }
    suspend fun displayName(asset: SourceAssetEntity): String = localReferences?.displayName(asset) ?: asset.remoteKey.substringAfterLast('/')
    fun allowsNetwork() = networkAllowed()
    val cache = RemoteFileCache(File(context.cacheDir, "remote-media"))
    private val decoded = object : android.util.LruCache<String, android.graphics.Bitmap>(33554432) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.allocationByteCount
    }
    private val readGate = RemoteRequestGate(2)
    private val authorityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authorities = java.util.concurrent.ConcurrentHashMap<String, PlaybackItem>()
    private val observer = object : androidx.room.InvalidationTracker.Observer("source", "source_asset", "asset", "asset_origin", "local_tombstone") {
        override fun onInvalidated(tables: Set<String>) {
            authorityScope.launch {
                if (authorities.values.any { !valid(it) }) { cache.clear(force = true); decoded.evictAll(); authorities.clear() }
            }
        }
    }
    init { db.invalidationTracker.addObserver(observer) }
    suspend fun bitmap(item: PlaybackItem, width: Int, height: Int, priority: Int = 0): android.graphics.Bitmap? {
        if (!valid(item)) return null
        authorities[item.id] = item
        val key = "${item.id}:${item.sourceEpoch}:${item.remote?.revision}:${item.local?.contentHash}:$width:$height"
        if (item.referenceCacheable) decoded.get(key)?.let { return it }
        val bitmap = if (isLocalReference(item)) {
            readGate.acquire(priority)
            try { localReferences?.bitmap(requireNotNull(item.remote), width, height) }
            finally { readGate.release() }
        } else read(item, priority) { file, orientation -> dev.photopilot.scaffold.data.render.BitmapRenderer.decodeToTarget(file, width, height, orientation) }
        if (!valid(item)) return null
        if (item.referenceCacheable && bitmap != null && bitmap.allocationByteCount <= 33554432) decoded.put(key, bitmap)
        return bitmap
    }
    fun close() { authorityScope.cancel(); db.invalidationTracker.removeObserver(observer); cache.clear(force = true); decoded.evictAll() }
    fun trimMemory(level: Int) {
        decoded.evictAll()
    }
    private val originals = File(context.filesDir, "originals")
    suspend fun item(sourceAssetId: String, allowRetainedCopy: Boolean = true): PlaybackItem? {
        val remote = db.sourceAssetDao().getById(sourceAssetId) ?: return null
        val source = db.sourceDao().getById(remote.sourceId) ?: return null
        val origins = db.assetOriginDao().getBySourceAssets(listOf(remote.id))
        if (origins.any { it.policyState != PolicyState.ALLOWED }) return null
        val assets = origins.mapNotNull { db.assetDao().getById(it.assetId) }
        if (assets.any { it.privacyExcluded || it.deletedAt != null }) return null
        if (db.localTombstoneDao().getByFingerprintAndRemoteId(source.endpointKey.orEmpty(), remote.remoteKey).isNotEmpty()) return null
        if (source.type in setOf(SourceType.LOCAL_PHOTOS, SourceType.LOCAL_FILES) && JsonMini.parseObject(source.settingsJson)["referenceOnly"] == true) {
            if (source.status != SourceStatus.ACTIVE || remote.accessState != AccessState.ACCESSIBLE || localReferences?.valid(remote) != true) return null
            return PlaybackItem(remote = remote, sourceEpoch = source.epoch, referenceDisplayName = localReferences?.displayName(remote), referenceCacheable = localReferences?.cacheable(remote) == true)
        }
        if (source.type !in setOf(SourceType.SMB, SourceType.WEBDAV)) return null
        if (remote.remoteKey.substringAfterLast('.').lowercase() !in LocalFilesSourceAdapter.MEDIA_EXTENSIONS) return null
        val local = assets.sortedWith(compareBy<AssetEntity> { asset -> origins.first { it.assetId == asset.id }.originState != OriginState.ACTIVE }.thenBy { it.id }).firstOrNull { asset ->
            val origin = origins.first { it.assetId == asset.id }
            val provenance = JsonMini.parseObject(db.settingsKvDao().get(provenanceKey(asset.id, remote.id))?.valueJson)
            val verifiedCurrent = origin.originState == OriginState.ACTIVE &&
                provenance["revision"] == remote.revision && (provenance["epoch"] as? Number)?.toLong() == source.epoch && provenance["contentHash"] == asset.contentHash
            val retainedCopy = allowRetainedCopy && source.retentionPolicy == RetentionPolicy.USER_MANAGED && origin.originState == OriginState.DETACHED
            asset.readiness == AssetReadiness.READY && File(originals, asset.originalRelPath).isFile &&
                origin.policyState == PolicyState.ALLOWED && (verifiedCurrent || retainedCopy) &&
                db.localTombstoneDao().getByContentHash(asset.contentHash).isEmpty()
        }
        if (local == null && (source.status != SourceStatus.ACTIVE || remote.accessState != AccessState.ACCESSIBLE)) return null
        val retained = local != null && origins.first { it.assetId == local.id }.originState == OriginState.DETACHED
        return PlaybackItem(local, remote, source.epoch, localIsRetained = retained)
    }
    suspend fun valid(item: PlaybackItem): Boolean {
        val remote = item.remote ?: return item.local?.let { asset ->
            val current = db.assetDao().getById(asset.id)
            current != null && current.readiness == AssetReadiness.READY && File(originals, current.originalRelPath).isFile && !current.privacyExcluded && current.deletedAt == null && current.contentHash == asset.contentHash &&
                db.assetOriginDao().allowedAssetIds(listOf(asset.id)).isNotEmpty()
        } == true
        val fresh = this.item(remote.id, allowRetainedCopy = item.local != null) ?: return false
        return fresh.sourceEpoch == item.sourceEpoch && fresh.remote?.revision == remote.revision &&
            (item.local == null || fresh.local?.id == item.local.id && fresh.local.contentHash == item.local.contentHash)
    }
    suspend fun <T> read(item: PlaybackItem, priority: Int = 1, block: (File, Int) -> T): T = withContext(Dispatchers.IO) {
        readGate.acquire(priority)
        try {
            if (!valid(item)) throw IOException("REMOTE_REVOKED")
            val local = item.local
            if (local != null && File(originals, local.originalRelPath).isFile) {
                val result = block(File(originals, local.originalRelPath), local.exifOrientation.toInt())
                if (!valid(item)) throw IOException("REMOTE_REVOKED")
                return@withContext result
            }
            val remote = requireNotNull(item.remote)
            val source = db.sourceDao().getById(remote.sourceId) ?: throw IOException("REMOTE_REVOKED")
            if (source.status != SourceStatus.ACTIVE || source.epoch != item.sourceEpoch || remote.accessState != AccessState.ACCESSIBLE) throw IOException("REMOTE_REVOKED")
            val adapter = adapterFor(source) ?: throw IOException("REMOTE_AUTH_REQUIRED")
            authorities[item.id] = item
            val authority = { runBlocking { valid(item) } }
            cache.acquire("${source.id}:${source.epoch}:${remote.id}:${remote.revision}", remote.byteSize, authority) {
                if (!networkAllowed()) throw NasNetworkBlockedException()
                NasNetworkPolicy.guardedStream(adapter.openAsset(remote.remoteKey), networkAllowed)
            }.use { lease ->
                val contentHash = dev.photopilot.scaffold.data.logic.ContentHash.stream(lease.file.inputStream()).contentHash
                if (db.localTombstoneDao().getByContentHash(contentHash).isNotEmpty()) {
                    cache.clear(force = true)
                    decoded.evictAll()
                    throw IOException("REMOTE_REVOKED")
                }
                val orientation = runCatching { android.media.ExifInterface(lease.file).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1).let(dev.photopilot.scaffold.data.logic.ExifOrientation::normalize)
                val result = block(lease.file, orientation)
                if (!valid(item)) throw IOException("REMOTE_REVOKED")
                result
            }
        } finally { readGate.release() }
    }
    suspend fun members(sourceId: String, folder: String, recursive: Boolean): List<SourceAssetEntity> {
        val source = db.sourceDao().getById(sourceId) ?: return emptyList()
        if (source.type in setOf(SourceType.LOCAL_PHOTOS, SourceType.LOCAL_FILES) && JsonMini.parseObject(source.settingsJson)["referenceOnly"] == true) {
            return localReferences?.members(sourceId, folder, recursive).orEmpty()
        }
        val normalized = RemotePaths.normalize(folder) ?: return emptyList()
        val prefix = if (normalized.isEmpty()) "" else "$normalized/"
        val result = mutableListOf<SourceAssetEntity>()
        var offset = 0
        do {
            val page = db.sourceAssetDao().browseFolder(sourceId, prefix, recursive, 256, offset)
            result.addAll(page.filter { RemotePaths.contains(normalized, it.remoteKey, recursive) })
            offset += page.size
        } while (page.size == 256)
        return result
    }
    private suspend fun indexFolder(source: SourceEntity, folder: String, now: Long) {
        var parentId: String? = null
        var relPath = ""
        val segments = listOf("") + folder.split('/').filter { it.isNotEmpty() }
        for (segment in segments) {
            if (segment.isNotEmpty()) relPath = if (relPath.isEmpty()) segment else "$relPath/$segment"
            val existing = db.collectionDao().getBySourceAndPath(source.id, relPath)
            val id = existing?.id ?: UUID.randomUUID().toString()
            db.collectionDao().upsert(CollectionEntity(
                id = id, parentId = parentId, sourceId = source.id,
                name = if (relPath.isEmpty()) source.displayName else segment,
                relPath = relPath, type = CollectionType.SOURCE_FOLDER, sortKey = relPath,
                createdAt = existing?.createdAt ?: now, updatedAt = now,
            ))
            parentId = id
        }
    }
    suspend fun index(sourceId: String, scopeJson: String, adapter: SourceAdapter? = null, expectedEpoch: Long? = null) = withContext(Dispatchers.IO) {
        val source = db.sourceDao().getById(sourceId) ?: throw IOException("REMOTE_REVOKED")
        if (expectedEpoch != null && source.epoch != expectedEpoch) throw IOException("REMOTE_REVOKED")
        val reader = adapter ?: adapterFor(source) ?: throw IOException("REMOTE_AUTH_REQUIRED")
        val scanStarted = System.currentTimeMillis()
        val roots = (JsonMini.parseObject(scopeJson)["directories"] as? List<*>)?.mapNotNull { (it as? String)?.let(RemotePaths::normalize) }.orEmpty()
        db.withTransaction {
            val current = db.sourceDao().getById(sourceId) ?: throw IOException("REMOTE_REVOKED")
            if (current.epoch != source.epoch || current.status != SourceStatus.ACTIVE) throw IOException("REMOTE_REVOKED")
            for (root in roots) indexFolder(source, root, scanStarted)
        }
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            if (!networkAllowed()) throw NasNetworkBlockedException()
            val page = reader.enumerate(scopeJson, cursor)
            db.withTransaction {
                val current = db.sourceDao().getById(sourceId)
                if (current?.epoch != source.epoch || current.status != SourceStatus.ACTIVE) throw IOException("REMOTE_REVOKED")
                val now = System.currentTimeMillis()
                page.items.forEach { metadata ->
                    val path = RemotePaths.normalize(metadata.remoteKey) ?: return@forEach
                    val existing = db.sourceAssetDao().getByRemoteKey(sourceId, metadata.remoteKey)
                    val sourceAssetId = existing?.id ?: UUID.randomUUID().toString()
                    seen.add(sourceAssetId)
                    db.sourceAssetDao().upsert(SourceAssetEntity(sourceAssetId, sourceId, metadata.remoteKey, metadata.stableId, metadata.revision, metadata.byteSize, metadata.takenAt, now, 0, AccessState.ACCESSIBLE, existing?.createdAt ?: now, now))
                    indexFolder(source, path.substringBeforeLast('/', ""), now)
                }
                LibraryVersionRepository(db.settingsKvDao()).bump(now)
            }
            cursor = page.nextCursor
        } while (cursor != null)
        db.withTransaction {
            val current = db.sourceDao().getById(sourceId) ?: throw IOException("REMOTE_REVOKED")
            if (current.epoch != source.epoch || current.status != SourceStatus.ACTIVE) throw IOException("REMOTE_REVOKED")
            val recursive = dev.photopilot.scaffold.data.logic.NasSourceLifecycle.recursiveFromSettings(source.settingsJson)
            for (root in roots) for (old in members(sourceId, root, recursive)) {
                if (old.id !in seen && old.lastSeenAt < scanStarted) db.sourceAssetDao().update(old.copy(accessState = AccessState.MISSING, updatedAt = System.currentTimeMillis()))
            }
            db.sourceDao().update(current.copy(lastSyncAt = System.currentTimeMillis()))
            LibraryVersionRepository(db.settingsKvDao()).bump(System.currentTimeMillis())
        }
        onCatalogUpdated?.invoke(sourceId)
    }
    companion object {
        fun provenanceKey(assetId: String, sourceAssetId: String) = "remote.provenance.$assetId.$sourceAssetId"
    }
}
