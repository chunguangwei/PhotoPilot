package dev.photopilot.scaffold.data.imports

import dev.photopilot.scaffold.data.db.AccessState
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.AssetFormat
import dev.photopilot.scaffold.data.db.AssetOriginEntity
import dev.photopilot.scaffold.data.db.AssetReadiness
import dev.photopilot.scaffold.data.db.CapturedAtSource
import dev.photopilot.scaffold.data.db.CollectionAssetEntity
import dev.photopilot.scaffold.data.db.CollectionEntity
import dev.photopilot.scaffold.data.db.CollectionType
import dev.photopilot.scaffold.data.db.ImportItemEntity
import dev.photopilot.scaffold.data.db.ImportItemState
import dev.photopilot.scaffold.data.db.ImportJobEntity
import dev.photopilot.scaffold.data.db.ImportJobState
import dev.photopilot.scaffold.data.db.LocalTombstoneEntity
import dev.photopilot.scaffold.data.db.MediaKind
import dev.photopilot.scaffold.data.db.OrientationClass
import dev.photopilot.scaffold.data.db.OriginState
import dev.photopilot.scaffold.data.db.PolicyState
import dev.photopilot.scaffold.data.db.SourceAssetEntity
import dev.photopilot.scaffold.data.db.SourceStatus
import dev.photopilot.scaffold.data.logic.ImportBatchNaming
import dev.photopilot.scaffold.data.logic.ImportJobAggregate
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.logic.NasDeletionJudge
import dev.photopilot.scaffold.data.logic.SourceFingerprints
import dev.photopilot.scaffold.data.logic.TombstoneIdentity
import dev.photopilot.scaffold.data.logic.TombstoneMatchTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class ImportCoordinator(
    private val scope: CommitScope,
    private val store: ImportStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {

    companion object {
        val RETRY_BACKOFF_MS = longArrayOf(1_000L, 4_000L, 15_000L)
        const val MAX_RETRY = 3
        const val CURSOR_DONE = "__done__"

        const val ERR_CANCELLED_BY_EPOCH = "CANCELLED_BY_EPOCH"
        const val ERR_CANCELLED_BY_USER = "CANCELLED_BY_USER"
        const val ERR_SKIPPED_BY_TOMBSTONE = "SKIPPED_BY_TOMBSTONE"
        const val ERR_SRC_UNAVAILABLE = "SRC_UNAVAILABLE"
        const val REASON_MIRROR_DELETE = "mirror_delete"

        fun importBatchCollectionId(jobId: String): String = "import_batch_$jobId"
    }

    suspend fun createJob(
        sourceId: String,
        scopeJson: String,
        totalKnown: Long? = null,
    ): String {
        val now = clock()
        val source = requireNotNull(scope.source(sourceId)) { "source not found" }
        require(source.status != SourceStatus.DISCONNECTED) { "source disconnected" }
        val jobId = idGen()
        scope.transaction {
            scope.insertJob(
                ImportJobEntity(
                    id = jobId,
                    sourceId = sourceId,
                    scopeJson = scopeJson,
                    epochAtCreate = source.epoch,
                    cursor = null,
                    totalKnown = totalKnown,
                    countsJson = countsJson(emptyMap()),
                    state = ImportJobState.RUNNING,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            scope.upsertCollection(
                CollectionEntity(
                    id = importBatchCollectionId(jobId),
                    parentId = null,
                    sourceId = sourceId,
                    name = ImportBatchNaming.nameOf(now),
                    relPath = null,
                    type = CollectionType.IMPORT_BATCH,
                    sortKey = ImportBatchNaming.sortKeyOf(now),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        return jobId
    }

    suspend fun enumerate(jobId: String, adapter: SourceAdapter): Unit = withContext(Dispatchers.IO) {
        var cursor: String? = requireNotNull(scope.job(jobId)).cursor
        if (cursor == CURSOR_DONE) {
            recalc(jobId, ImportJobAggregate.Signal.NONE)
            return@withContext
        }
        while (true) {
            val page = adapter.enumerate(scopeJsonOf(jobId), cursor)
            scope.transaction {
                for (candidate in page.items) {
                    upsertEnumerated(jobId, candidate)
                }
                val job = requireNotNull(scope.job(jobId))
                scope.updateJob(
                    job.copy(
                        cursor = page.nextCursor ?: CURSOR_DONE,
                        totalKnown = page.totalKnown ?: job.totalKnown,
                        updatedAt = clock(),
                    ),
                )
            }
            cursor = page.nextCursor ?: break
            recalc(jobId, ImportJobAggregate.Signal.NONE)
        }
        recalc(jobId, ImportJobAggregate.Signal.NONE)
    }

    data class StagedPayload(
        val tempRelPath: String,
        val contentHash: String,
        val head64kHash: String?,
        val bytesWritten: Long,
    )

    sealed interface StageOutcome {
        data class Staged(val payload: StagedPayload) : StageOutcome
        data class Skipped(val tier: TombstoneMatchTier) : StageOutcome
    }

    suspend fun stage(itemId: String, adapter: SourceAdapter): StageOutcome = withContext(Dispatchers.IO) {
        val item = requireNotNull(scope.item(itemId)) { "item not found" }
        require(
            item.state == ImportItemState.QUEUED ||
                item.state == ImportItemState.FAILED && item.retryAt != null,
        ) { "item not stageable: ${item.state.wire}" }
        val sourceAsset = requireNotNull(scope.sourceAsset(item.sourceAssetId)) { "source_asset not found" }
        val fingerprint = adapter.sourceFingerprint
        val now = clock()
        transition(item, ImportItemState.DOWNLOADING, now)

        val tempRelPath = "item-$itemId.part"
        val staged = store.stage(
            tempRelPath,
            adapter.openAsset(sourceAsset.remoteKey),
            sourceAsset.byteSize,
        )
        transition(
            requireNotNull(scope.item(itemId)).copy(
                tempRelPath = tempRelPath,
                bytesDone = staged.bytesWritten,
                bytesTotal = staged.bytesWritten,
            ),
            ImportItemState.VERIFYING,
            now,
        )

        val tier = scope.tombstoneTierMatch(
            TombstoneIdentity(
                contentHash = staged.contentHash,
                sourceFingerprint = fingerprint,
                remoteId = sourceAsset.remoteKey,
                byteSize = staged.bytesWritten,
                head64kHash = staged.head64kHash,
            ),
        )
        if (tier == TombstoneMatchTier.NONE) {
            StageOutcome.Staged(
                StagedPayload(
                    tempRelPath = tempRelPath,
                    contentHash = staged.contentHash,
                    head64kHash = staged.head64kHash,
                    bytesWritten = staged.bytesWritten,
                ),
            )
        } else {
            store.discardStaged(tempRelPath)
            finishSkipped(requireNotNull(scope.item(itemId)), now)
            StageOutcome.Skipped(tier)
        }
    }

    data class AssetCommitMetadata(
        val format: AssetFormat,
        val pixelWidth: Long,
        val pixelHeight: Long,
        val exifOrientation: Long,
        val capturedAt: Long?,
        val capturedAtSource: CapturedAtSource,
        val originalFileName: String?,
        val mediaKind: MediaKind = MediaKind.IMAGE,
        val durationMs: Long? = null,
    )

    sealed interface CommitOutcome {
        data class Imported(val assetId: String, val merged: Boolean) : CommitOutcome
        data object SkippedByTombstone : CommitOutcome
        data object CanceledByEpoch : CommitOutcome
    }

    suspend fun commit(
        itemId: String,
        staged: StagedPayload,
        metadata: AssetCommitMetadata,
    ): CommitOutcome = withContext(Dispatchers.IO) {
        val item = requireNotNull(scope.item(itemId)) { "item not found" }
        require(item.state == ImportItemState.VERIFYING) { "item not in verifying" }
        val job = requireNotNull(scope.job(item.jobId)) { "job not found" }
        val sourceAsset = requireNotNull(scope.sourceAsset(item.sourceAssetId)) { "source_asset not found" }
        val now = clock()

        transition(item, ImportItemState.COMMITTING, now)

        val source = scope.source(job.sourceId)
        val epochStale = source == null || source.epoch != job.epochAtCreate
        val existing = if (epochStale) null else scope.existingAssetByHash(staged.contentHash)
        val plan = CommitPlanner.plan(
            epochStale = epochStale,
            tombstoneTier = if (epochStale) {
                TombstoneMatchTier.NONE
            } else {
                scope.tombstoneTierMatch(
                    TombstoneIdentity(
                        contentHash = staged.contentHash,
                        sourceFingerprint = source?.let(SourceFingerprints::of),
                        remoteId = sourceAsset.remoteKey,
                        byteSize = staged.bytesWritten,
                        head64kHash = staged.head64kHash,
                    ),
                )
            },
            existingAssetId = existing?.id,
            newAssetId = idGen,
        )

        when (plan) {
            CommitPlan.CancelByEpoch -> {
                store.discardStaged(staged.tempRelPath)
                transition(item, ImportItemState.CANCELED, now, failureCode = ERR_CANCELLED_BY_EPOCH)
                recalc(job.id, ImportJobAggregate.Signal.NONE)
                CommitOutcome.CanceledByEpoch
            }

            CommitPlan.SkipByTombstone -> {
                store.discardStaged(staged.tempRelPath)
                finishSkipped(item, now)
                CommitOutcome.SkippedByTombstone
            }

            is CommitPlan.MergeExisting -> {
                store.discardStaged(staged.tempRelPath)
                applyInTransaction(job, item, sourceAsset, plan.assetId, now) { }
                recalc(job.id, ImportJobAggregate.Signal.NONE)
                CommitOutcome.Imported(plan.assetId, merged = true)
            }

            is CommitPlan.CreateNew -> {
                val relPath = "${plan.assetId}.${metadata.format.wire}"
                store.commitStaged(staged.tempRelPath, relPath)
                applyInTransaction(job, item, sourceAsset, plan.assetId, now) {
                    scope.insertAsset(
                        AssetEntity(
                            id = plan.assetId,
                            contentHash = staged.contentHash,
                            byteSize = staged.bytesWritten,
                            format = metadata.format,
                            pixelWidth = metadata.pixelWidth,
                            pixelHeight = metadata.pixelHeight,
                            exifOrientation = metadata.exifOrientation,
                            orientationClass = orientationOf(metadata.pixelWidth, metadata.pixelHeight),
                            capturedAt = metadata.capturedAt,
                            capturedAtSource = metadata.capturedAtSource,
                            importedAt = now,
                            originalRelPath = relPath,
                            originalFileName = metadata.originalFileName,
                            readiness = AssetReadiness.READY,
                            failureCode = null,
                            privacyExcluded = false,
                            edgeColor = null,
                            mediaKind = metadata.mediaKind,
                            durationMs = metadata.durationMs,
                            deletedAt = null,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }
                recalc(job.id, ImportJobAggregate.Signal.NONE)
                CommitOutcome.Imported(plan.assetId, merged = false)
            }
        }
    }

    suspend fun processItem(
        itemId: String,
        adapter: SourceAdapter,
        metadata: AssetCommitMetadata,
    ): CommitOutcome {
        return when (val outcome = stage(itemId, adapter)) {
            is StageOutcome.Staged -> commit(itemId, outcome.payload, metadata)
            is StageOutcome.Skipped -> CommitOutcome.SkippedByTombstone
        }
    }

    suspend fun fail(itemId: String, errorCode: String, retryable: Boolean) {
        val item = requireNotNull(scope.item(itemId)) { "item not found" }
        require(item.state !in ImportJobAggregate.terminalItemStates) { "item already terminal" }
        val now = clock()
        val attempts = item.retryCount.toInt()
        val withRetry = retryable && attempts < MAX_RETRY
        scope.updateItem(
            item.copy(
                state = ImportItemState.FAILED,
                retryCount = if (withRetry) item.retryCount + 1 else item.retryCount,
                retryAt = if (withRetry) now + RETRY_BACKOFF_MS[attempts] else null,
                failureCode = errorCode,
                updatedAt = now,
            ),
        )
        recalc(item.jobId, ImportJobAggregate.Signal.NONE)
    }

    suspend fun signal(jobId: String, signal: ImportJobAggregate.Signal) {
        val now = clock()
        when (signal) {
            ImportJobAggregate.Signal.USER_CANCEL -> {
                scope.itemsForJob(jobId)
                    .filter { it.state !in ImportJobAggregate.terminalItemStates }
                    .forEach { transition(it, ImportItemState.CANCELED, now, failureCode = ERR_CANCELLED_BY_USER) }
            }

            ImportJobAggregate.Signal.USER_PAUSE -> {
                scope.itemsForJob(jobId)
                    .filter { it.state !in ImportJobAggregate.terminalItemStates }
                    .forEach { transition(it, ImportItemState.PAUSED, now, failureCode = null) }
            }

            ImportJobAggregate.Signal.FATAL_ERROR -> {
                scope.itemsForJob(jobId)
                    .filter { it.state !in ImportJobAggregate.terminalItemStates }
                    .forEach {
                        scope.updateItem(
                            it.copy(
                                state = ImportItemState.FAILED,
                                retryAt = null,
                                failureCode = ERR_SRC_UNAVAILABLE,
                                updatedAt = now,
                            ),
                        )
                    }
            }

            ImportJobAggregate.Signal.AUTO_PAUSE,
            ImportJobAggregate.Signal.NONE,
            -> Unit
        }
        recalc(jobId, signal)
    }

    suspend fun resume(jobId: String) {
        val now = clock()
        scope.itemsForJob(jobId)
            .filter { it.state == ImportItemState.PAUSED }
            .forEach { transition(it, ImportItemState.QUEUED, now, failureCode = null) }
        recalc(jobId, ImportJobAggregate.Signal.NONE)
    }

    private suspend fun applyInTransaction(
        job: ImportJobEntity,
        item: ImportItemEntity,
        sourceAsset: SourceAssetEntity,
        assetId: String,
        now: Long,
        insertNewAsset: suspend () -> Unit,
    ) {
        scope.transaction {
            val source = scope.source(job.sourceId)
                ?: throw IllegalStateException("epoch stale at commit")
            if (source.epoch != job.epochAtCreate) throw IllegalStateException("epoch stale at commit")

            insertNewAsset()

            if (scope.findOrigin(assetId, sourceAsset.id) == null) {
                scope.insertOrigin(
                    AssetOriginEntity(
                        assetId = assetId,
                        sourceAssetId = sourceAsset.id,
                        importedAt = now,
                        policyState = PolicyState.ALLOWED,
                        originState = OriginState.ACTIVE,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            scope.repointSelections(sourceAsset.id, assetId, now)
            scope.upsertCollectionAsset(
                CollectionAssetEntity(
                    collectionId = importBatchCollectionId(job.id),
                    assetId = assetId,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            mapToSourceFolder(job, sourceAsset, assetId, now)
            scope.bumpLibraryVersion(now)
            transition(item, ImportItemState.IMPORTED, now)
        }
    }

    private suspend fun mapToSourceFolder(
        job: ImportJobEntity,
        sourceAsset: SourceAssetEntity,
        assetId: String,
        now: Long,
    ) {
        val scopeDirs = scopeDirectories(job.scopeJson)
        if (scopeDirs.isEmpty()) return
        val fileDir = sourceAsset.remoteKey.substringBeforeLast('/', "")
        val targetDir = scopeDirs
            .filter { it.isEmpty() || fileDir == it || fileDir.startsWith("$it/") }
            .maxByOrNull { it.length }
            ?: return
        val existing = scope.collectionByPath(job.sourceId, targetDir)
        val collection = existing ?: CollectionEntity(
            id = idGen(),
            parentId = null,
            sourceId = job.sourceId,
            name = targetDir.substringAfterLast('/').ifEmpty { "/" },
            relPath = targetDir,
            type = CollectionType.SOURCE_FOLDER,
            sortKey = targetDir,
            createdAt = now,
            updatedAt = now,
        )
        if (existing == null) {
            scope.upsertCollection(collection)
        }
        scope.upsertCollectionAsset(
            CollectionAssetEntity(
                collectionId = collection.id,
                assetId = assetId,
                createdAt = now,
                updatedAt = now,
            ),
        )
        scope.originsForSourceAsset(sourceAsset.id).forEach { origin ->
            if (origin.assetId != assetId) {
                scope.deleteCollectionAsset(collection.id, origin.assetId)
            }
        }
    }

    sealed interface SyncFinalizeOutcome {
        data object NoMissing : SyncFinalizeOutcome
        data class MissingMarked(val count: Int) : SyncFinalizeOutcome
        data class Deleted(val count: Int) : SyncFinalizeOutcome
        data class PendingConfirmation(val count: Int) : SyncFinalizeOutcome
    }

    suspend fun finalizeSync(jobId: String, mirrorDelete: Boolean): SyncFinalizeOutcome {
        val job = scope.job(jobId) ?: return SyncFinalizeOutcome.NoMissing
        val now = clock()
        val scopeDirs = scopeDirectories(job.scopeJson)
        if (scopeDirs.isNotEmpty()) {
            scope.collectionsForSource(job.sourceId)
                .filter { it.type == CollectionType.SOURCE_FOLDER }
                .forEach { collection ->
                    scope.remoteKeysForCollection(collection.id).forEach { (assetId, remoteKey) ->
                        val dir = remoteKey.substringBeforeLast('/', "")
                        val inScope = scopeDirs.any {
                            it.isEmpty() || dir == it || dir.startsWith("$it/")
                        }
                        if (!inScope) {
                            scope.deleteCollectionAsset(collection.id, assetId)
                        }
                    }
                }
        }
        val all = scope.sourceAssetsForSource(job.sourceId)
        val missing = all.filter { it.lastSeenAt < job.createdAt }
        if (missing.isEmpty()) return SyncFinalizeOutcome.NoMissing
        if (!mirrorDelete) {
            missing.forEach {
                scope.upsertSourceAsset(
                    it.copy(accessState = AccessState.MISSING, updatedAt = now),
                )
            }
            return SyncFinalizeOutcome.MissingMarked(missing.size)
        }
        missing.forEach {
            if (it.missingScanCount == 0L) {
                scope.upsertSourceAsset(it.copy(missingScanCount = 1, updatedAt = now))
            } else {
                scope.upsertSourceAsset(it.copy(missingScanCount = it.missingScanCount + 1))
            }
        }
        val tracked = scope.sourceAssetsForSource(job.sourceId)
        val candidates = tracked.filter { mirrorEligible(it, now) }
        if (candidates.isEmpty()) return SyncFinalizeOutcome.MissingMarked(missing.size)
        if (
            NasDeletionJudge.judgeRatio(candidates.size, tracked.size) ==
            NasDeletionJudge.Verdict.NEEDS_CONFIRMATION
        ) {
            return SyncFinalizeOutcome.PendingConfirmation(candidates.size)
        }
        return SyncFinalizeOutcome.Deleted(executeMirrorDeletions(job.sourceId, candidates, now))
    }

    suspend fun executeMirrorDeletion(sourceId: String): Int {
        val now = clock()
        val candidates = scope.sourceAssetsForSource(sourceId)
            .filter { mirrorEligible(it, now) }
        return executeMirrorDeletions(sourceId, candidates, now)
    }

    private fun mirrorEligible(sourceAsset: SourceAssetEntity, now: Long): Boolean =
        sourceAsset.missingScanCount >= NasDeletionJudge.MIN_MISSING_SCANS &&
            now - sourceAsset.updatedAt >= NasDeletionJudge.MIN_INTERVAL_MS

    suspend fun resetMissingCounts(sourceId: String) {
        val now = clock()
        scope.sourceAssetsForSource(sourceId).forEach {
            if (it.missingScanCount != 0L) {
                scope.upsertSourceAsset(it.copy(missingScanCount = 0L, updatedAt = now))
            }
        }
    }

    private suspend fun executeMirrorDeletions(
        sourceId: String,
        candidates: List<SourceAssetEntity>,
        now: Long,
    ): Int {
        if (candidates.isEmpty()) return 0
        val fingerprint = scope.source(sourceId)?.let(SourceFingerprints::of)
        var deleted = 0
        for (sourceAsset in candidates) {
            for (origin in scope.originsForSourceAsset(sourceAsset.id)) {
                val asset = scope.assetById(origin.assetId) ?: continue
                if (asset.readiness == AssetReadiness.DELETED) continue
                scope.writeTombstone(
                    LocalTombstoneEntity(
                        id = "tomb-${asset.id}",
                        contentHash = asset.contentHash,
                        sourceFingerprint = fingerprint,
                        remoteId = sourceAsset.remoteKey,
                        byteSize = asset.byteSize,
                        head64kHash = null,
                        reason = REASON_MIRROR_DELETE,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                scope.softDeleteAsset(asset.id, now)
                deleted += 1
            }
        }
        if (deleted > 0) {
            scope.bumpLibraryVersion(now)
        }
        return deleted
    }

    private suspend fun scopeDirectories(scopeJson: String): List<String> {
        val scope = JsonMini.parseObject(scopeJson)
        val dirs = scope["directories"] as? List<*> ?: return emptyList()
        return dirs.mapNotNull { it as? String }
    }

    private suspend fun upsertEnumerated(jobId: String, candidate: EnumeratedAsset) {
        val job = requireNotNull(scope.job(jobId))
        val now = clock()
        val existing = scope.findSourceAsset(job.sourceId, candidate.remoteKey)
        val sourceAssetId = existing?.id ?: idGen()
        scope.upsertSourceAsset(
            SourceAssetEntity(
                id = sourceAssetId,
                sourceId = job.sourceId,
                remoteKey = candidate.remoteKey,
                stableId = candidate.stableId,
                revision = candidate.revision,
                byteSize = candidate.byteSize,
                remoteTakenAt = candidate.takenAt,
                lastSeenAt = now,
                missingScanCount = 0L,
                accessState = AccessState.ACCESSIBLE,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        if (existing == null) {
            scope.insertItem(
                ImportItemEntity(
                    id = idGen(),
                    jobId = jobId,
                    sourceAssetId = sourceAssetId,
                    state = ImportItemState.QUEUED,
                    tempRelPath = null,
                    retryCount = 0L,
                    retryAt = null,
                    failureCode = null,
                    bytesDone = 0L,
                    bytesTotal = candidate.byteSize,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } else if (existing.revision != candidate.revision) {
            val queued = scope.itemsForJob(jobId).firstOrNull { it.sourceAssetId == existing.id }
            if (queued == null) {
                scope.insertItem(
                    ImportItemEntity(
                        id = idGen(),
                        jobId = jobId,
                        sourceAssetId = existing.id,
                        state = ImportItemState.QUEUED,
                        tempRelPath = null,
                        retryCount = 0L,
                        retryAt = null,
                        failureCode = null,
                        bytesDone = 0L,
                        bytesTotal = candidate.byteSize,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            } else if (queued.state in ImportJobAggregate.terminalItemStates) {
                scope.updateItem(
                    queued.copy(
                        state = ImportItemState.QUEUED,
                        failureCode = null,
                        retryAt = null,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    private suspend fun finishSkipped(item: ImportItemEntity, now: Long) {
        transition(item, ImportItemState.SKIPPED, now, failureCode = ERR_SKIPPED_BY_TOMBSTONE)
        recalc(item.jobId, ImportJobAggregate.Signal.NONE)
    }

    private suspend fun transition(
        item: ImportItemEntity,
        state: ImportItemState,
        now: Long,
        failureCode: String? = item.failureCode,
    ) {
        scope.updateItem(item.copy(state = state, failureCode = failureCode, updatedAt = now))
    }

    private suspend fun recalc(jobId: String, signal: ImportJobAggregate.Signal) {
        val job = scope.job(jobId) ?: return
        val items = scope.itemsForJob(jobId)
        val counts = ImportJobAggregate.countByState(items.map { it.state })
        val derived = ImportJobAggregate.derive(
            items.map { it.state },
            signal,
            enumerationComplete = job.cursor == CURSOR_DONE,
        )
        scope.updateJob(
            job.copy(
                state = derived,
                countsJson = countsJson(counts),
                updatedAt = clock(),
            ),
        )
    }

    private fun countsJson(counts: Map<ImportItemState, Int>): String {
        val body = counts.entries
            .sortedBy { it.key.wire }
            .joinToString(",") { "\"${it.key.wire}\":${it.value}" }
        return "{$body}"
    }

    private suspend fun scopeJsonOf(jobId: String): String =
        requireNotNull(scope.job(jobId)) { "job not found" }.scopeJson

    private fun orientationOf(width: Long, height: Long): OrientationClass = when {
        width == height -> OrientationClass.SQUARE
        width > height -> OrientationClass.LANDSCAPE
        else -> OrientationClass.PORTRAIT
    }
}
