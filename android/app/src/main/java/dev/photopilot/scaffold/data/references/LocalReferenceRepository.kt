package dev.photopilot.scaffold.data.references

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.withTransaction
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.imports.MediaStoreSourceAdapter
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.remote.RemotePaths
import dev.photopilot.scaffold.data.repo.LibraryVersionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

class LocalReferenceRepository(
    context: Context,
    private val db: PhotoPilotDatabase,
    private val access: LocalReferenceAccess = ResolverLocalReferenceAccess(context.applicationContext),
) {
    private val appContext = context.applicationContext

    fun isReference(source: SourceEntity): Boolean = source.type in setOf(SourceType.LOCAL_PHOTOS, SourceType.LOCAL_FILES) && settings(source)["referenceOnly"] == true

    suspend fun indexPhotos(): SourceEntity = withContext(Dispatchers.IO) {
        mutation.withLock { guarded { index(SourceType.LOCAL_PHOTOS, MediaStoreSourceAdapter.FINGERPRINT, access.photos(), null, true) } }
    }

    suspend fun indexFiles(uris: List<Uri>, grantFlags: Int): SourceEntity = withContext(Dispatchers.IO) {
        mutation.withLock {
            withGrants {
                if (uris.isEmpty()) throw LocalReferenceException("LOCAL_REFERENCE_EMPTY_SELECTION")
                val entries = uris.distinct().map { uri ->
                    access.persist(uri.toString(), grantFlags)
                    access.document(uri.toString(), uri.toString()) ?: throw LocalReferenceException("LOCAL_REFERENCE_UNSUPPORTED_OR_UNAVAILABLE")
                }
                index(SourceType.LOCAL_FILES, FILES_FINGERPRINT, entries, null, false)
            }
        }
    }

    suspend fun indexTree(uri: Uri, grantFlags: Int): SourceEntity = withContext(Dispatchers.IO) {
        mutation.withLock {
            withGrants {
                access.persist(uri.toString(), grantFlags)
                index(SourceType.LOCAL_FILES, "android.saf.tree:v1:${digest(uri.toString())}", access.tree(uri.toString()), uri.toString(), true, displayName = access.treeName(uri.toString()))
            }
        }
    }

    suspend fun releaseUnusedGrants() = withContext(Dispatchers.IO) {
        mutation.withLock { releaseUnused(access.persistedReads()) }
    }

    private suspend fun releaseUnused(grants: Set<String>) {
        val used = mutableSetOf<String>()
        for (source in db.sourceDao().getAll().filter(::isReference)) {
            (settings(source)["referenceTreeUri"] as? String)?.let(used::add)
            for (asset in db.sourceAssetDao().getBySource(source.id)) {
                (metadata(asset.id)["uri"] as? String)?.let(used::add)
            }
        }
        var failure: Exception? = null
        for (grant in grants.filter { grant -> used.none { access.covers(grant, it) } }) {
            try { access.release(grant) }
            catch (error: Exception) {
                if (grant in access.persistedReads()) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
            }
        }
        failure?.let { throw it }
    }

    private suspend fun <T> withGrants(block: suspend () -> T): T {
        val before = access.persistedReads()
        try {
            return guarded(block)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { releaseUnused(access.persistedReads() - before) }
                catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }

    suspend fun refresh(sourceId: String): SourceEntity = withContext(Dispatchers.IO) {
        mutation.withLock {
            val source = db.sourceDao().getById(sourceId) ?: throw LocalReferenceException("LOCAL_REFERENCE_SOURCE_MISSING")
            try {
                guarded {
                    if (source.type == SourceType.LOCAL_PHOTOS) return@guarded index(SourceType.LOCAL_PHOTOS, MediaStoreSourceAdapter.FINGERPRINT, access.photos(), null, true)
                    if (!isReference(source)) throw LocalReferenceException("LOCAL_REFERENCE_SOURCE_REQUIRED")
                    val tree = settings(source)["referenceTreeUri"] as? String
                    if (tree != null) index(source.type, source.endpointKey!!, access.tree(tree), tree, true, displayName = access.treeName(tree))
                    else {
                        val denied = mutableSetOf<String>()
                        val entries = db.sourceAssetDao().getBySource(sourceId).mapNotNull { asset ->
                            val metadata = metadata(asset.id)
                            val uri = metadata["uri"] as? String ?: return@mapNotNull null
                            try { access.document(uri, asset.remoteKey) }
                            catch (failure: SecurityException) {
                                denied.add(asset.id)
                                null
                            }
                            catch (failure: LocalReferenceException) {
                                if (!failure.code.contains("PERMISSION") && !failure.code.contains("DURABLE_ACCESS")) throw failure
                                denied.add(asset.id)
                                null
                            }
                        }
                        if (entries.isEmpty() && denied.isNotEmpty()) throw LocalReferenceException("LOCAL_REFERENCE_PERMISSION_REQUIRED")
                        val updated = index(source.type, source.endpointKey!!, entries, null, true, updatePickerScope = false)
                        for (assetId in denied) db.sourceAssetDao().getById(assetId)?.let {
                            db.sourceAssetDao().update(it.copy(accessState = AccessState.UNAUTHORIZED, updatedAt = System.currentTimeMillis()))
                        }
                        updated
                    }
                }
            } catch (error: LocalReferenceException) {
                db.withTransaction {
                    val current = db.sourceDao().getById(sourceId)
                    if (current != null) {
                        val denied = error.code.contains("PERMISSION") || error.code.contains("DURABLE_ACCESS")
                        val now = System.currentTimeMillis()
                        db.sourceDao().update(current.copy(status = if (denied) SourceStatus.NEEDS_AUTH else SourceStatus.UNAVAILABLE, updatedAt = now))
                        if (denied) db.sourceAssetDao().getBySource(sourceId).forEach { db.sourceAssetDao().update(it.copy(accessState = AccessState.UNAUTHORIZED, updatedAt = now)) }
                    }
                    LibraryVersionRepository(db.settingsKvDao()).bump(System.currentTimeMillis())
                }
                throw error
            }
        }
    }

    suspend fun refreshAll() {
        val sources = withContext(Dispatchers.IO) { db.sourceDao().getAll().filter { (isReference(it) || it.type == SourceType.LOCAL_PHOTOS) && it.status != SourceStatus.DISCONNECTED } }
        for (source in sources) {
            try { refresh(source.id) } catch (_: LocalReferenceException) { }
        }
    }

    suspend fun members(sourceId: String, folder: String, recursive: Boolean): List<SourceAssetEntity> = withContext(Dispatchers.IO) {
        val normalized = RemotePaths.normalize(folder) ?: return@withContext emptyList()
        val source = db.sourceDao().getById(sourceId) ?: return@withContext emptyList()
        if (!isReference(source) || source.status != SourceStatus.ACTIVE) return@withContext emptyList()
        val collections = db.collectionDao().getBySource(sourceId)
        val selected = collections.filter { collection ->
            val path = collection.relPath.orEmpty()
            path == normalized || recursive && (normalized.isEmpty() || path.startsWith("$normalized/"))
        }
        val ids = mutableSetOf<String>()
        for (collection in selected) {
            val membership = JsonMini.parseObject(db.settingsKvDao().get("reference.membership.${collection.id}")?.valueJson)
            if (membership["sourceId"] == sourceId && (membership["epoch"] as? Number)?.toLong() == source.epoch) {
                ids.addAll((membership["sourceAssetIds"] as? List<*>)?.filterIsInstance<String>().orEmpty())
            }
        }
        db.sourceAssetDao().getBySource(sourceId).filter {
            it.id in ids && it.accessState == AccessState.ACCESSIBLE &&
                db.localTombstoneDao().getByFingerprintAndRemoteId(source.endpointKey.orEmpty(), it.remoteKey).isEmpty()
        }
    }

    suspend fun displayName(asset: SourceAssetEntity): String = withContext(Dispatchers.IO) {
        metadata(asset.id)["displayName"] as? String ?: asset.remoteKey.substringAfterLast('/')
    }

    suspend fun hide(sourceAssetId: String, now: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val asset = requireNotNull(db.sourceAssetDao().getById(sourceAssetId))
            val source = requireNotNull(db.sourceDao().getById(asset.sourceId))
            require(isReference(source))
            db.localTombstoneDao().upsert(LocalTombstoneEntity(
                id = "reference-hidden-${asset.id}", contentHash = null,
                sourceFingerprint = source.endpointKey, remoteId = asset.remoteKey,
                byteSize = asset.byteSize, head64kHash = null,
                reason = dev.photopilot.scaffold.data.repo.AssetRepository.REASON_PRIVACY_HIDE,
                createdAt = now, updatedAt = now,
            ))
            val originals = db.assetOriginDao().getBySourceAssets(listOf(asset.id)).map { it.assetId }.distinct()
            val repository = dev.photopilot.scaffold.data.repo.AssetRepository(db, LibraryVersionRepository(db.settingsKvDao()))
            for (original in originals) repository.hideAssetGlobally(original, dev.photopilot.scaffold.data.repo.AssetRepository.REASON_PRIVACY_HIDE, now)
            dev.photopilot.scaffold.data.aiframes.AIPreviewCheckpoint.invalidateAll(db)
            db.settingsKvDao().getByPrefix("ai.vector.").forEach { db.settingsKvDao().delete(it) }
            val aiState = dev.photopilot.scaffold.data.aiframes.AIStateStore(db)
            val ref = "remote:${asset.id}"
            for (frame in db.frameDao().getAll()) {
                val selections = db.frameSelectionDao().getByFrame(frame.id).filter { it.sourceAssetId == asset.id }
                selections.forEach { db.frameSelectionDao().delete(it) }
                val configuration = aiState.get(frame.id)
                if (configuration != null && (ref in configuration.members || ref in configuration.processed || ref in configuration.reviews)) {
                    aiState.put(configuration.copy(revision = configuration.revision + 1, members = configuration.members - ref, processed = configuration.processed - ref, reviews = configuration.reviews - ref))
                }
                val frameSettings = JsonMini.parseObject(frame.settingsJson).toMutableMap()
                if (frameSettings["remoteCoverRef"] == asset.id) {
                    frameSettings.remove("remoteCoverRef")
                    db.frameDao().update(frame.copy(settingsJson = JsonMini.toJson(frameSettings), updatedAt = now))
                }
                if (selections.isNotEmpty() || frameSettings != JsonMini.parseObject(frame.settingsJson)) db.frameDao().bumpSelectionVersion(frame.id, now)
            }
            LibraryVersionRepository(db.settingsKvDao()).bump(now)
        }
        dev.photopilot.scaffold.data.repo.FrameRepository.sharedCache.clear()
    }

    suspend fun cacheable(asset: SourceAssetEntity): Boolean = withContext(Dispatchers.IO) {
        metadata(asset.id)["versionKnown"] == true && db.sourceAssetDao().getById(asset.id)?.revision == asset.revision
    }

    suspend fun valid(asset: SourceAssetEntity): Boolean = withContext(Dispatchers.IO) {
        try {
            val source = db.sourceDao().getById(asset.sourceId) ?: return@withContext false
            val latest = db.sourceAssetDao().getById(asset.id) ?: return@withContext false
            if (!isReference(source) || source.status != SourceStatus.ACTIVE || latest.revision != asset.revision || latest.accessState != AccessState.ACCESSIBLE) return@withContext false
            val fingerprint = source.endpointKey ?: return@withContext false
            if (db.localTombstoneDao().getByFingerprintAndRemoteId(fingerprint, asset.remoteKey).isNotEmpty()) return@withContext false
            for (origin in db.assetOriginDao().getBySourceAssets(listOf(asset.id))) {
                val original = db.assetDao().getById(origin.assetId) ?: continue
                if (origin.policyState != PolicyState.ALLOWED || original.privacyExcluded || original.deletedAt != null || original.readiness == AssetReadiness.DELETED) return@withContext false
                if (db.localTombstoneDao().getByContentHash(original.contentHash).isNotEmpty()) return@withContext false
            }
            val storedMetadata = metadata(asset.id)
            val uri = storedMetadata["uri"] as? String ?: return@withContext false
            val current = if (source.type == SourceType.LOCAL_PHOTOS) access.photo(uri, asset.remoteKey) else access.document(uri, asset.remoteKey)
            current != null && current.uri == uri && current.revision == (storedMetadata["observedRevision"] as? String ?: asset.revision) && current.size == asset.byteSize
        } catch (error: CancellationException) { throw error } catch (_: Exception) { false }
    }

    suspend fun bitmap(asset: SourceAssetEntity, width: Int, height: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (width <= 0 || height <= 0 || !valid(asset)) return@withContext null
        val uri = metadata(asset.id)["uri"] as? String ?: return@withContext null
        val decoded = try { access.decode(uri, width, height) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        if (decoded != null && (decoded.allocationByteCount > 32 * 1024 * 1024 || !valid(asset))) {
            decoded.recycle()
            null
        } else decoded
    }

    private suspend fun index(
        type: SourceType,
        fingerprint: String,
        entries: List<LocalReferenceEntry>,
        tree: String?,
        complete: Boolean,
        updatePickerScope: Boolean = true,
        displayName: String? = null,
    ): SourceEntity = db.withTransaction {
        val now = System.currentTimeMillis()
        val existing = db.sourceDao().getAll().firstOrNull { it.type == type && it.endpointKey == fingerprint }
        val previousSettings = existing?.let(::settings).orEmpty()
        val epoch = (existing?.epoch ?: 1L) + if (existing != null && (previousSettings["referenceOnly"] != true || existing.status != SourceStatus.ACTIVE)) 1 else 0
        val defaultName = displayName ?: appContext.getString(if (type == SourceType.LOCAL_PHOTOS) R.string.source_local_photos_title else R.string.source_local_files_title)
        val source = existing?.copy(
            displayName = existing.displayName.takeUnless { it == type.wire } ?: defaultName,
            status = SourceStatus.ACTIVE,
            epoch = epoch,
            lastSyncAt = now,
            updatedAt = now,
        ) ?: SourceEntity(
            id = UUID.randomUUID().toString(),
            type = type,
            displayName = defaultName,
            endpointKey = fingerprint,
            credentialRef = null,
            accountKey = null,
            capabilityFlags = 0,
            retentionPolicy = RetentionPolicy.USER_MANAGED,
            status = SourceStatus.ACTIVE,
            epoch = epoch,
            lastSyncAt = now,
            settingsJson = null,
            createdAt = now,
            updatedAt = now,
        )
        val nextSettings = previousSettings.toMutableMap().apply {
            put("referenceOnly", true)
            if (tree != null) put("referenceTreeUri", tree)
        }
        val configured = source.copy(settingsJson = JsonMini.toJson(nextSettings))
        if (existing == null) db.sourceDao().upsert(configured) else db.sourceDao().update(configured)
        val refreshGeneration = UUID.randomUUID().toString()
        val seen = mutableSetOf<String>()
        val folderMembers = linkedMapOf("" to mutableSetOf<String>())
        val names = mutableMapOf("" to source.displayName)
        for (entry in entries.distinctBy { it.key }) {
            val old = db.sourceAssetDao().getByRemoteKey(source.id, entry.key)
            val revision = if (entry.versionKnown) entry.revision else "${entry.revision}:refresh:$refreshGeneration"
            val asset = SourceAssetEntity(
                id = old?.id ?: UUID.randomUUID().toString(),
                sourceId = source.id,
                remoteKey = entry.key,
                stableId = old?.stableId,
                revision = revision,
                byteSize = entry.size,
                remoteTakenAt = entry.takenAt,
                lastSeenAt = now,
                missingScanCount = 0,
                accessState = AccessState.ACCESSIBLE,
                createdAt = old?.createdAt ?: now,
                updatedAt = now,
            )
            db.sourceAssetDao().upsert(asset)
            seen.add(asset.id)
            val folders = if (entry.folders.isNotEmpty()) entry.folders else {
                val parent = if (tree != null) entry.key.substringBeforeLast('/', "") else ""
                mapOf(parent to parent.substringAfterLast('/'))
            }
            if (tree == null) folderMembers.getValue("").add(asset.id)
            for ((folder, name) in folders) {
                val path = RemotePaths.normalize(folder) ?: throw LocalReferenceException("LOCAL_REFERENCE_INVALID_TREE")
                folderMembers.getOrPut(path) { mutableSetOf() }.add(asset.id)
                names[path] = name
                val parts = path.split('/').filter { it.isNotEmpty() }
                for (depth in 1 until parts.size) {
                    val ancestor = parts.take(depth).joinToString("/")
                    folderMembers.getOrPut(ancestor) { mutableSetOf() }
                    names.putIfAbsent(ancestor, parts[depth - 1])
                }
            }
            put("reference.asset.${asset.id}", mapOf("uri" to entry.uri, "displayName" to entry.displayName, "observedRevision" to entry.revision, "versionKnown" to entry.versionKnown), now)
        }
        if (complete) {
            db.sourceAssetDao().getBySource(source.id).filter { it.id !in seen }.forEach {
                db.sourceAssetDao().update(it.copy(accessState = AccessState.MISSING, missingScanCount = it.missingScanCount + 1, updatedAt = now))
            }
        } else {
            folderMembers.getValue("").addAll(db.sourceAssetDao().getBySource(source.id).filter { it.accessState == AccessState.ACCESSIBLE }.map { it.id })
        }
        for ((path, ids) in folderMembers.entries.sortedBy { it.key.length }) {
            val collection = db.collectionDao().getBySourceAndPath(source.id, path)
            val collectionId = collection?.id ?: UUID.randomUUID().toString()
            val parentId = if (path.isEmpty()) null else db.collectionDao().getBySourceAndPath(source.id, path.substringBeforeLast('/', ""))?.id
            db.collectionDao().upsert(CollectionEntity(collectionId, parentId, source.id, names[path].orEmpty().ifEmpty { source.displayName }, path, CollectionType.SOURCE_FOLDER, path, collection?.createdAt ?: now, now))
            put("reference.membership.$collectionId", mapOf("version" to 1, "sourceId" to source.id, "epoch" to epoch, "sourceAssetIds" to ids.toList()), now)
        }
        if (complete) {
            for (collection in db.collectionDao().getBySource(source.id).filter { it.relPath !in folderMembers }) {
                put("reference.membership.${collection.id}", mapOf("version" to 1, "sourceId" to source.id, "epoch" to epoch, "sourceAssetIds" to emptyList<String>()), now)
            }
        }
        if (updatePickerScope) {
            nextSettings["lastReferenceAssetIds"] = seen.toList()
            nextSettings["lastReferenceCollectionId"] = db.collectionDao().getBySourceAndPath(source.id, "")?.id
        }
        val updated = source.copy(settingsJson = JsonMini.toJson(nextSettings))
        db.sourceDao().update(updated)
        LibraryVersionRepository(db.settingsKvDao()).bump(now)
        updated
    }

    private fun settings(source: SourceEntity): Map<String, Any?> = JsonMini.parseObject(source.settingsJson)
    private suspend fun metadata(id: String): Map<String, Any?> = JsonMini.parseObject(db.settingsKvDao().get("reference.asset.$id")?.valueJson)
    private suspend fun put(key: String, value: Map<String, Any?>, now: Long) {
        val old = db.settingsKvDao().get(key)
        db.settingsKvDao().upsert(SettingsKvEntity(key, JsonMini.toJson(value), old?.createdAt ?: now, now))
    }
    private suspend fun <T> guarded(block: suspend () -> T): T = try { block() }
        catch (error: LocalReferenceException) { throw error }
        catch (error: CancellationException) { throw error }
        catch (error: SecurityException) { throw LocalReferenceException("LOCAL_REFERENCE_PERMISSION_REQUIRED", error) }
        catch (error: Exception) { throw LocalReferenceException("LOCAL_REFERENCE_PROVIDER_UNAVAILABLE", error) }

    companion object {
        const val FILES_FINGERPRINT = "android.saf.references:v1"
        private val mutation = Mutex()
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
