package dev.photopilot.scaffold.data.repo

import androidx.room.withTransaction
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
    private val originalsDir: java.io.File,
    private val coordinator: dev.photopilot.scaffold.data.imports.ImportCoordinator? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {

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

    suspend fun disconnect(sourceId: String, keepCopies: Boolean, now: Long) = withContext(Dispatchers.IO) {
        val source = db.sourceDao().getById(sourceId) ?: return@withContext
        if (keepCopies) {
            db.assetOriginDao().markDetachedBySource(sourceId, now)
            db.importJobDao().getBySource(sourceId).forEach { db.importJobDao().delete(it) }
            db.sourceDao().update(
                source.copy(status = SourceStatus.DISCONNECTED, updatedAt = now),
            )
        } else {
            val sourceAssets = db.sourceAssetDao().getBySource(sourceId)
            val origins = if (sourceAssets.isEmpty()) {
                emptyList()
            } else {
                db.assetOriginDao().getBySourceAssets(sourceAssets.map { it.id })
            }
            val originCounts = origins.groupBy { it.assetId }.mapValues { it.value.size }
            val deletedAlready = mutableSetOf<String>()
            originCounts.keys.forEach { assetId ->
                if (db.assetDao().getById(assetId)?.deletedAt != null) {
                    deletedAlready += assetId
                }
            }
            val scope = dev.photopilot.scaffold.data.logic.BatchDeleteScope.deletable(
                memberAssetIds = originCounts.keys.toList(),
                originCountByAsset = originCounts,
                alreadyDeleted = deletedAlready,
            )
            scope.forEach { assetId ->
                db.assetDao().getById(assetId)?.let { asset ->
                    runCatching {
                        db.localTombstoneDao().upsert(
                            dev.photopilot.scaffold.data.db.LocalTombstoneEntity(
                                id = "tomb-${asset.id}",
                                contentHash = asset.contentHash,
                                sourceFingerprint = endpointKeyOf(source),
                                remoteId = null,
                                byteSize = asset.byteSize,
                                head64kHash = null,
                                reason = AssetRepository.REASON_USER_DELETE,
                                createdAt = now,
                                updatedAt = now,
                            ),
                        )
                        java.io.File(originalsDir, asset.originalRelPath).delete()
                        db.assetDao().delete(asset)
                    }
                }
            }
            db.sourceDao().delete(source)
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
