package dev.photopilot.scaffold.data.repo

import androidx.room.withTransaction
import dev.photopilot.scaffold.data.db.AccessState
import dev.photopilot.scaffold.data.db.AssetReadiness
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.AssetOriginEntity
import dev.photopilot.scaffold.data.db.FrameSelectionMode
import dev.photopilot.scaffold.data.db.LocalTombstoneEntity
import dev.photopilot.scaffold.data.db.OriginState
import dev.photopilot.scaffold.data.db.SourceAssetEntity
import dev.photopilot.scaffold.data.aiframes.AIPreviewCheckpoint
import dev.photopilot.scaffold.data.aiframes.AIStateStore
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.remote.RemotePaths
import java.io.File
import java.io.IOException
import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import dev.photopilot.scaffold.data.db.RetentionPolicy
import dev.photopilot.scaffold.data.db.SourceEntity
import dev.photopilot.scaffold.data.db.SourceStatus
import dev.photopilot.scaffold.data.db.SourceType
import dev.photopilot.scaffold.data.imports.MediaStoreSourceAdapter
import dev.photopilot.scaffold.data.imports.PickedFilesSourceAdapter
import dev.photopilot.scaffold.data.logic.NasSourceLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class LocalSourceRegistry(
    private val db: PhotoPilotDatabase,
    private val originalsDir: File,
    private val coordinator: dev.photopilot.scaffold.data.imports.ImportCoordinator? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {

    var onConfigurationRemoved: (suspend () -> Unit)? = null

    suspend fun prepareSync(sourceId: String, epoch: Long, status: SourceStatus, hasCredentials: Boolean, now: Long): SourceEntity? = withContext(Dispatchers.IO) {
        db.withTransaction {
            val current = syncSource(sourceId, epoch, status) ?: return@withTransaction null
            val next = if (hasCredentials) SourceStatus.ACTIVE else SourceStatus.NEEDS_AUTH
            if (current.status == next) current else current.copy(status = next, updatedAt = now).also { db.sourceDao().update(it) }
        }
    }

    suspend fun recordSyncError(sourceId: String, epoch: Long, status: SourceStatus, authenticationFailed: Boolean, now: Long): Boolean = withContext(Dispatchers.IO) {
        db.withTransaction {
            val current = syncSource(sourceId, epoch, status) ?: return@withTransaction false
            if (authenticationFailed) db.sourceDao().update(current.copy(status = SourceStatus.NEEDS_AUTH, updatedAt = now))
            db.settingsKvDao().upsert(dev.photopilot.scaffold.data.db.SettingsKvEntity("nas.connection_error.$sourceId", "true", now, now))
            true
        }
    }

    suspend fun clearSyncError(sourceId: String, epoch: Long, status: SourceStatus): Boolean = withContext(Dispatchers.IO) {
        db.withTransaction {
            syncSource(sourceId, epoch, status) ?: return@withTransaction false
            db.settingsKvDao().get("nas.connection_error.$sourceId")?.let { db.settingsKvDao().delete(it) }
            true
        }
    }

    private suspend fun syncSource(sourceId: String, epoch: Long, status: SourceStatus): SourceEntity? = db.sourceDao().getById(sourceId)?.takeIf {
        it.epoch == epoch && it.status == status && it.status != SourceStatus.DISCONNECTED && it.type in setOf(SourceType.WEBDAV, SourceType.SMB)
    }

    suspend fun setMirrorDelete(sourceId: String, enabled: Boolean, now: Long) =
        withContext(Dispatchers.IO) {
            db.withTransaction {
                val source = db.sourceDao().getById(sourceId) ?: return@withTransaction
                db.sourceDao().update(
                    source.copy(
                        settingsJson = NasSourceLifecycle.mergeSettings(
                            source.settingsJson,
                            mirrorDelete = enabled,
                        ),
                        updatedAt = now,
                    ),
                )
                if (enabled) {
                    coordinator?.resetMissingCounts(sourceId)
                }
            }
        }

    suspend fun ensureLocalPhotos(): SourceEntity =
        ensure(SourceType.LOCAL_PHOTOS, MediaStoreSourceAdapter.FINGERPRINT)

    suspend fun ensureLocalFiles(): SourceEntity =
        ensure(SourceType.LOCAL_FILES, PickedFilesSourceAdapter.FINGERPRINT)

    suspend fun ensureWebDav(
        endpointKey: String,
        displayName: String,
        capabilityFlags: Long,
        settingsJson: String? = null,
    ): SourceEntity =
        ensure(SourceType.WEBDAV, endpointKey, displayName, capabilityFlags, settingsJson)

    suspend fun ensureNasSource(
        type: SourceType,
        endpointKey: String,
        displayName: String,
        capabilityFlags: Long,
        settingsJson: String? = null,
    ): SourceEntity =
        ensure(type, endpointKey, displayName, capabilityFlags, settingsJson)

    suspend fun disconnect(
        sourceId: String,
        keepCopies: Boolean,
        now: Long,
        removeConfiguration: Boolean = !keepCopies,
    ) = withContext(Dispatchers.IO) {
        val filesToDelete = mutableListOf<File>()
        db.withTransaction {
            val source = db.sourceDao().getById(sourceId) ?: return@withTransaction
            db.sourceDao().update(source.copy(status = SourceStatus.DISCONNECTED, epoch = source.epoch + if (source.status == SourceStatus.DISCONNECTED) 0 else 1, updatedAt = now))
            db.importJobDao().getBySource(sourceId).forEach { db.importJobDao().delete(it) }
            if (!removeConfiguration) {
                db.assetOriginDao().markDetachedBySource(sourceId, now)
                LibraryVersionRepository(db.settingsKvDao()).bump(now)
                return@withTransaction
            }
            val sourceAssets = db.sourceAssetDao().getBySource(sourceId)
            val sourceAssetIds = sourceAssets.map { it.id }.toSet()
            val origins = sourceAssets.flatMap { db.assetOriginDao().getBySourceAssets(listOf(it.id)) }
            val retained = mutableMapOf<String, String>()
            for (assetId in origins.map { it.assetId }.distinct()) {
                val asset = db.assetDao().getById(assetId) ?: continue
                val allOrigins = db.assetOriginDao().getByAsset(assetId)
                val otherOrigins = allOrigins.filter { it.sourceAssetId !in sourceAssetIds }
                if (keepCopies) {
                    if (File(originalsDir, asset.originalRelPath).isFile) {
                        val localSource = ensureLocalFiles()
                        val localId = "retained-${asset.id}"
                        db.sourceAssetDao().upsert(
                            SourceAssetEntity(
                                id = localId,
                                sourceId = localSource.id,
                                remoteKey = localId,
                                stableId = null,
                                revision = asset.contentHash,
                                byteSize = asset.byteSize,
                                remoteTakenAt = asset.capturedAt,
                                lastSeenAt = now,
                                missingScanCount = 0,
                                accessState = AccessState.ACCESSIBLE,
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                        val existingOrigin = db.assetOriginDao().getByAssetAndSourceAsset(asset.id, localId)
                        if (existingOrigin == null) {
                            val sourceOrigins = allOrigins.filter { it.sourceAssetId in sourceAssetIds }
                            val origin = sourceOrigins.firstOrNull { it.policyState == dev.photopilot.scaffold.data.db.PolicyState.ALLOWED } ?: sourceOrigins.first()
                            db.assetOriginDao().upsert(origin.copy(sourceAssetId = localId, originState = OriginState.ACTIVE, updatedAt = now))
                        }
                    }
                } else if (otherOrigins.isEmpty()) {
                    db.localTombstoneDao().upsert(
                        LocalTombstoneEntity(
                            id = "tomb-${asset.id}", contentHash = asset.contentHash,
                            sourceFingerprint = endpointKeyOf(source), remoteId = null,
                            byteSize = asset.byteSize, head64kHash = null,
                            reason = AssetRepository.REASON_USER_DELETE, createdAt = now, updatedAt = now,
                        ),
                    )
                    db.assetDao().delete(asset)
                    filesToDelete.add(File(originalsDir, asset.originalRelPath))
                }
            }
            if (keepCopies) {
                val copyEpoch = source.epoch - if (source.status == SourceStatus.DISCONNECTED) 1 else 0
                val originsByRemote = origins.groupBy { it.sourceAssetId }
                val sourceFolders = db.collectionDao().getBySource(sourceId).filter { it.type == dev.photopilot.scaffold.data.db.CollectionType.SOURCE_FOLDER }.map { it.id }
                val folderMembers = sourceFolders.chunked(400).flatMap { db.collectionAssetDao().getByCollections(it) }.map { it.assetId }.toSet()
                for (remote in sourceAssets) {
                    if (db.localTombstoneDao().getByFingerprintAndRemoteId(endpointKeyOf(source).orEmpty(), remote.remoteKey).isNotEmpty()) continue
                    val copies = originsByRemote[remote.id].orEmpty().mapNotNull { origin ->
                        db.assetDao().getById(origin.assetId)?.takeIf {
                            it.deletedAt == null && it.readiness == AssetReadiness.READY && File(originalsDir, it.originalRelPath).isFile
                        }?.let { origin to it }
                    }
                    val versions = copies.associate { (_, asset) ->
                        asset.id to db.settingsKvDao().get(dev.photopilot.scaffold.data.remote.RemoteMediaRepository.provenanceKey(asset.id, remote.id))
                    }
                    val current = copies.filter { (_, asset) ->
                        val version = JsonMini.parseObject(versions[asset.id]?.valueJson)
                        version["revision"] == remote.revision && version["contentHash"] == asset.contentHash &&
                            (version["epoch"] as? Number)?.toLong() == copyEpoch
                    }.sortedWith(compareByDescending<Pair<AssetOriginEntity, AssetEntity>> { it.first.originState == OriginState.ACTIVE }
                        .thenByDescending { it.first.importedAt }.thenBy { it.second.id }).firstOrNull()
                    val folderCopy = copies.filter { it.second.id in folderMembers && versions[it.second.id] == null }.singleOrNull()
                    val legacy = copies.singleOrNull()?.takeIf { versions[it.second.id] == null && originsByRemote[remote.id].orEmpty().map { origin -> origin.assetId }.distinct().size == 1 }
                    (current ?: folderCopy ?: legacy)?.let { retained[remote.id] = it.second.id }
                }
            }
            preserveFrameSelections(sourceId, sourceAssets, retained, now)
            origins.forEach {
                db.assetOriginDao().delete(it)
                db.settingsKvDao().get(dev.photopilot.scaffold.data.remote.RemoteMediaRepository.provenanceKey(it.assetId, it.sourceAssetId))?.let { row -> db.settingsKvDao().delete(row) }
            }
            for (asset in sourceAssets) db.settingsKvDao().get("reference.asset.${asset.id}")?.let { db.settingsKvDao().delete(it) }
            val collections = db.collectionDao().getBySource(sourceId)
            val remaining = collections.toMutableList()
            while (remaining.isNotEmpty()) {
                val parents = remaining.mapNotNull { it.parentId }.toSet()
                val leaves = remaining.filter { it.id !in parents }
                check(leaves.isNotEmpty())
                for (leaf in leaves) {
                    db.collectionDao().getAll().filter { it.parentId == leaf.id && it.sourceId != sourceId }.forEach {
                        db.collectionDao().update(it.copy(parentId = null, updatedAt = now))
                    }
                    db.collectionDao().delete(leaf)
                    db.settingsKvDao().get("reference.membership.${leaf.id}")?.let { db.settingsKvDao().delete(it) }
                }
                remaining.removeAll(leaves.toSet())
            }
            db.sourceDao().delete(source)
            db.settingsKvDao().get("nas.connection_error.$sourceId")?.let { db.settingsKvDao().delete(it) }
            LibraryVersionRepository(db.settingsKvDao()).bump(now)
        }
        FrameRepository.sharedCache.clear()
        var failure: Exception? = null
        if (removeConfiguration) {
            try { onConfigurationRemoved?.invoke() }
            catch (error: Exception) { failure = error }
        }
        for (file in filesToDelete) {
            if (file.exists() && !file.delete()) {
                val error = IOException("LOCAL_COPY_DELETE_FAILED")
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private suspend fun preserveFrameSelections(
        sourceId: String,
        sourceAssets: List<SourceAssetEntity>,
        retained: Map<String, String>,
        now: Long,
    ) {
        val remoteIds = sourceAssets.map { it.id }.toSet()
        val collections = db.collectionDao().getAll().associateBy { it.id }
        val aiState = AIStateStore(db)
        AIPreviewCheckpoint.removeAssets(db, remoteIds.map { "remote:$it" }.toSet())
        for (frame in db.frameDao().getAll()) {
            val selections = db.frameSelectionDao().getByFrame(frame.id)
            val related = selections.filter { it.sourceAssetId in remoteIds }
            val rules = db.frameDirectoryRuleDao().getByFrame(frame.id).filter { collections[it.collectionId]?.sourceId == sourceId }
            val config = aiState.get(frame.id)
            val settings = JsonMini.parseObject(frame.settingsJson).toMutableMap()
            val coverRef = (settings["remoteCoverRef"] as? String)?.removePrefix("remote:")
            val aiRelated = config != null && (config.collections.any { collections[it]?.sourceId == sourceId } || config.members.any { it.removePrefix("remote:") in remoteIds })
            if (related.isEmpty() && rules.isEmpty() && !aiRelated && coverRef !in remoteIds) continue
            if (coverRef in remoteIds) {
                settings.remove("remoteCoverRef")
                db.frameDao().update(frame.copy(coverAssetId = retained[coverRef], settingsJson = JsonMini.toJson(settings), updatedAt = now))
            }
            val replacements = related.mapNotNull { selection -> retained[selection.sourceAssetId]?.let { selection to it } }
            related.forEach { db.frameSelectionDao().delete(it) }
            for (rule in rules) {
                val folder = collections[rule.collectionId]?.relPath.orEmpty()
                sourceAssets.filter { RemotePaths.contains(folder, it.remoteKey, rule.recursive) }.forEach { remote ->
                    val assetId = retained[remote.id] ?: return@forEach
                    if (related.none { it.sourceAssetId == remote.id }) {
                        db.frameSelectionDao().upsertResolved(frame.id, assetId, null, FrameSelectionMode.INCLUDE, null, now)
                    }
                }
            }
            for ((selection, assetId) in replacements.sortedBy { it.first.mode == FrameSelectionMode.EXCLUDE }) {
                val existing = db.frameSelectionDao().getByRef(frame.id, assetId)
                if (existing?.mode != FrameSelectionMode.EXCLUDE) {
                    db.frameSelectionDao().upsertRaw(selection.copy(assetId = assetId, sourceAssetId = null, refKey = assetId, updatedAt = now))
                }
            }
            config?.let { config ->
                val removed = remoteIds.map { "remote:$it" }.toSet()
                val confirmedAssets = retained.values.toSet()
                val confirmedAliases = confirmedAssets + confirmedAssets.flatMap { assetId ->
                    db.assetOriginDao().getByAsset(assetId).map { "remote:${it.sourceAssetId}" }
                }
                val releasedOwnership = removed + confirmedAliases
                val remainingCollections = config.collections.filter { collections[it]?.sourceId != sourceId }
                aiState.put(config.copy(
                    revision = config.revision + 1,
                    collections = remainingCollections,
                    autoUpdate = config.autoUpdate && remainingCollections.isNotEmpty(),
                    members = config.members - releasedOwnership,
                    processed = config.processed - releasedOwnership,
                    reviews = config.reviews - releasedOwnership,
                    status = if (remainingCollections.isEmpty()) "complete" else config.status,
                ))
            }
            db.frameDao().bumpSelectionVersion(frame.id, now)
        }
    }

    private fun endpointKeyOf(source: SourceEntity): String? =
        source.endpointKey ?: "${source.type.wire}:${source.id}"

    private suspend fun ensure(
        type: SourceType,
        endpointKey: String,
        displayName: String = type.wire,
        capabilityFlags: Long = 0L,
        settingsJson: String? = null,
    ): SourceEntity =
        withContext(Dispatchers.IO) {
            db.sourceDao().getAll()
                .firstOrNull { it.type == type && it.endpointKey == endpointKey }
                ?.let { existing ->
                    if (NasSourceLifecycle.shouldReactivate(existing.status.wire)) {
                        val reactivated = existing.copy(
                            status = SourceStatus.ACTIVE,
                            epoch = existing.epoch + 1,
                            settingsJson = settingsJson ?: existing.settingsJson,
                            updatedAt = nowMillis(),
                        )
                        db.sourceDao().update(reactivated)
                        return@withContext reactivated
                    }
                    if (settingsJson != null && settingsJson != existing.settingsJson) {
                        val updated = existing.copy(settingsJson = settingsJson, updatedAt = nowMillis())
                        db.sourceDao().update(updated)
                        return@withContext updated
                    }
                    return@withContext existing
                }
            val now = nowMillis()
            val entity = SourceEntity(
                id = idGen(),
                type = type,
                displayName = displayName,
                endpointKey = endpointKey,
                credentialRef = null,
                accountKey = null,
                capabilityFlags = capabilityFlags,
                retentionPolicy = RetentionPolicy.USER_MANAGED,
                status = SourceStatus.ACTIVE,
                epoch = 1L,
                lastSyncAt = null,
                settingsJson = settingsJson,
                createdAt = now,
                updatedAt = now,
            )
            db.sourceDao().upsert(entity)
            entity
        }
}
