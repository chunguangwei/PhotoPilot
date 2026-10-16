package dev.photopilot.scaffold.data.repo

import android.content.Context
import androidx.room.withTransaction
import dev.photopilot.scaffold.data.db.DatabaseFactory
import dev.photopilot.scaffold.data.db.FrameDirectoryRuleEntity
import dev.photopilot.scaffold.data.db.FrameSelectionEntity
import dev.photopilot.scaffold.data.db.FrameSelectionMode
import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import dev.photopilot.scaffold.data.logic.AddInclude
import dev.photopilot.scaffold.data.logic.DirectoryRuleRef
import dev.photopilot.scaffold.data.logic.FrameSelectionLogic
import dev.photopilot.scaffold.data.logic.PlaybackQueue
import dev.photopilot.scaffold.data.logic.QueueAsset
import dev.photopilot.scaffold.data.logic.ResolvableAsset
import dev.photopilot.scaffold.data.logic.ResolutionCache
import dev.photopilot.scaffold.data.logic.RuleChange
import dev.photopilot.scaffold.data.logic.SaveIncludeRow
import dev.photopilot.scaffold.data.logic.SaveRuleRow
import dev.photopilot.scaffold.data.logic.SelectionRef
import dev.photopilot.scaffold.data.logic.SelectionResolution
import dev.photopilot.scaffold.data.logic.SelectionResolveInput
import dev.photopilot.scaffold.data.logic.SelectionResolver
import dev.photopilot.scaffold.data.logic.SelectionSaveInput
import dev.photopilot.scaffold.data.logic.SelectionSavePlanner
import dev.photopilot.scaffold.data.logic.SelectionSaveRequest
import dev.photopilot.scaffold.data.logic.SelectionSaveResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SelectionRefEdit(val assetId: String?, val sourceAssetId: String?) {
    val refKey: String get() = FrameSelectionLogic.refKey(assetId, sourceAssetId)
}

class FrameRepository(
    private val db: PhotoPilotDatabase,
    private val libraryVersion: LibraryVersionRepository,
    private val originalsDir: File,
    private val cache: ResolutionCache = sharedCache,
) {

    companion object {
        val sharedCache = ResolutionCache()

        fun create(context: Context): FrameRepository {
            val db = DatabaseFactory.builder(context).build()
            val originalsDir = File(
                context.getDatabasePath(DatabaseFactory.DATABASE_NAME).parentFile,
                "originals",
            )
            return FrameRepository(db, LibraryVersionRepository(db.settingsKvDao()), originalsDir)
        }
    }

    suspend fun resolve(frameId: String): SelectionResolution = withIo {
        val frame = db.frameDao().getById(frameId)
            ?: throw IllegalArgumentException("frame not found: $frameId")
        val sources = db.sourceDao().getAll()
        val cacheKey = ResolutionCache.Key(
            frameId = frameId,
            selectionVersion = frame.selectionVersion,
            sourceEpochsKey = ResolutionCache.sourceEpochsKey(sources.associate { it.id to it.epoch }),
            libraryVersion = libraryVersion.current() ?: 0L,
        )
        cache.get(cacheKey)?.let { return@withIo it }

        val skeleton = loadSkeleton(frameId)
        val firstPass = SelectionResolver.resolve(skeleton.copy(assets = emptyMap()))
        val candidateIds = firstPass.candidates
        val assets = if (candidateIds.isEmpty()) {
            emptyMap()
        } else {
            val entities = db.assetDao().getByIds(candidateIds).associateBy { it.id }
            val allowedIds = db.assetOriginDao().allowedAssetIds(candidateIds).toSet()
            candidateIds.mapNotNull { id ->
                entities[id]?.let { entity ->
                    id to ResolvableAsset(
                        id = id,
                        readiness = entity.readiness.wire,
                        privacyExcluded = entity.privacyExcluded,
                        deleted = entity.deletedAt != null,
                        fileExists = File(originalsDir, entity.originalRelPath).isFile,
                        policyAllowed = id in allowedIds,
                        mediaKind = entity.mediaKind.wire,
                    )
                }
            }.toMap()
        }
        val resolution = SelectionResolver.resolve(skeleton.copy(assets = assets))
        cache.put(cacheKey, resolution)
        resolution
    }

    suspend fun save(
        frameId: String,
        baseVersion: Long,
        addIncludes: List<SelectionRefEdit> = emptyList(),
        removeRefs: List<SelectionRefEdit> = emptyList(),
        addExcludes: List<SelectionRefEdit> = emptyList(),
        ruleChanges: List<RuleChange> = emptyList(),
        removeRules: List<String> = emptyList(),
        manualOrderRefs: List<String>? = null,
        now: Long = System.currentTimeMillis(),
    ): SelectionSaveResult = withIo {
        db.withTransaction {
            val frame = db.frameDao().getById(frameId)
                ?: throw IllegalArgumentException("frame not found: $frameId")
            val selections = db.frameSelectionDao().getByFrame(frameId)
            val rules = db.frameDirectoryRuleDao().getByFrame(frameId)

            val request = SelectionSaveRequest(
                addIncludes = addIncludes.map { AddInclude(ref = it.refKey, addedAt = now) },
                removeRefs = removeRefs.map { it.refKey },
                addExcludes = addExcludes.map { it.refKey },
                ruleChanges = ruleChanges,
                removeRules = removeRules,
            )
            val result = SelectionSavePlanner.plan(
                SelectionSaveInput(
                    currentVersion = frame.selectionVersion,
                    baseVersion = baseVersion,
                    orderMode = frame.orderMode.wire,
                    existingIncludes = selections
                        .filter { it.mode == FrameSelectionMode.INCLUDE }
                        .map { SaveIncludeRow(it.refKey, it.manualOrder, it.addedAt) },
                    existingExcludes = selections
                        .filter { it.mode == FrameSelectionMode.EXCLUDE }
                        .map { it.refKey },
                    existingRules = rules.map { SaveRuleRow(it.collectionId, it.recursive, it.includeFuture) },
                    request = request,
                ),
            )
            if (result !is SelectionSaveResult.Ok) return@withTransaction result

            val refEdits = mutableMapOf<String, SelectionRefEdit>()
            for (selection in selections) {
                refEdits[selection.refKey] = SelectionRefEdit(selection.assetId, selection.sourceAssetId)
            }
            for (edit in addIncludes + addExcludes + removeRefs) {
                refEdits[edit.refKey] = edit
            }
            for (change in ruleChanges) {
                if (!change.includeFuture) {
                    for (member in change.members) {
                        refEdits.putIfAbsent(member, SelectionRefEdit(assetId = member, sourceAssetId = null))
                    }
                }
            }

            val finalIncludes = result.includeRows.associateBy { it.ref }
            val finalExcludes = result.excludeRows.toSet()
            for (selection in selections) {
                val obsolete = when (selection.mode) {
                    FrameSelectionMode.INCLUDE -> selection.refKey !in finalIncludes
                    FrameSelectionMode.EXCLUDE -> selection.refKey !in finalExcludes
                }
                if (obsolete) db.frameSelectionDao().deleteByRef(frameId, selection.refKey)
            }
            val existingByRef = selections.associateBy { it.refKey }
            for (row in result.includeRows) {
                upsertSelectionRow(frameId, row.ref, FrameSelectionMode.INCLUDE, row.manualOrder, row.addedAt, refEdits, existingByRef, now)
            }
            for (ref in result.excludeRows) {
                val addedAt = existingByRef[ref]?.addedAt ?: now
                upsertSelectionRow(frameId, ref, FrameSelectionMode.EXCLUDE, null, addedAt, refEdits, existingByRef, now)
            }

            val finalRules = result.ruleRows.associateBy { it.collectionId }
            for (rule in rules) {
                if (rule.collectionId !in finalRules) db.frameDirectoryRuleDao().delete(rule)
            }
            val existingRulesByCollection = rules.associateBy { it.collectionId }
            for (row in result.ruleRows) {
                val existing = existingRulesByCollection[row.collectionId]
                db.frameDirectoryRuleDao().upsert(
                    FrameDirectoryRuleEntity(
                        frameId = frameId,
                        collectionId = row.collectionId,
                        recursive = row.recursive,
                        includeFuture = row.includeFuture,
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    ),
                )
            }

            if (manualOrderRefs != null && frame.orderMode.wire == PlaybackQueue.ORDER_MANUAL) {
                val sequence = manualOrderRefs.withIndex()
                    .associate { it.value to (it.index + 1).toLong() }
                val current = db.frameSelectionDao().getByFrame(frameId)
                    .filter { it.mode == FrameSelectionMode.INCLUDE }
                val tail = current.filter { it.refKey !in sequence }.sortedBy { it.addedAt }
                for (row in current) {
                    val order = sequence[row.refKey]
                        ?: (sequence.size + tail.indexOf(row) + 1).toLong()
                    if (row.manualOrder != order) {
                        db.frameSelectionDao().upsertRaw(
                            row.copy(manualOrder = order, updatedAt = now),
                        )
                    }
                }
            }

            db.frameDao().bumpSelectionVersion(frameId, now)
            cache.clear()
            result
        }
    }

    suspend fun buildPlayQueue(frameId: String): List<String> = withIo {
        val frame = db.frameDao().getById(frameId)
            ?: throw IllegalArgumentException("frame not found: $frameId")
        val resolution = resolve(frameId)
        if (resolution.playable.isEmpty()) return@withIo emptyList()
        val entities = db.assetDao().getByIds(resolution.playable).associateBy { it.id }
        val selections = db.frameSelectionDao().getByFrame(frameId).associateBy { it.refKey }
        val queueAssets = resolution.playable.mapNotNull { id ->
            entities[id]?.let { entity ->
                QueueAsset(
                    id = id,
                    capturedAt = entity.capturedAt,
                    importedAt = entity.importedAt,
                    manualOrder = selections[id]?.manualOrder,
                    addedAt = selections[id]?.addedAt ?: entity.importedAt,
                )
            }
        }
        PlaybackQueue.buildQueue(queueAssets, frame.orderMode.wire, frame.randomSeed)
    }

    private suspend fun upsertSelectionRow(
        frameId: String,
        ref: String,
        mode: FrameSelectionMode,
        manualOrder: Long?,
        addedAt: Long,
        refEdits: Map<String, SelectionRefEdit>,
        existingByRef: Map<String, FrameSelectionEntity>,
        now: Long,
    ) {
        val edit = refEdits[ref] ?: SelectionRefEdit(assetId = ref, sourceAssetId = null)
        db.frameSelectionDao().upsertRaw(
            FrameSelectionEntity(
                frameId = frameId,
                assetId = edit.assetId,
                sourceAssetId = edit.sourceAssetId,
                mode = mode,
                manualOrder = manualOrder,
                addedAt = addedAt,
                refKey = ref,
                createdAt = existingByRef[ref]?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun loadSkeleton(frameId: String): SelectionResolveInput {
        val selections = db.frameSelectionDao().getByFrame(frameId).map {
            SelectionRef(assetId = it.assetId, sourceAssetId = it.sourceAssetId, mode = it.mode.wire)
        }
        val rules = db.frameDirectoryRuleDao().getByFrame(frameId).map {
            DirectoryRuleRef(collectionId = it.collectionId, recursive = it.recursive)
        }
        val children = mutableMapOf<String, MutableList<String>>()
        for (collection in db.collectionDao().getAll()) {
            val parent = collection.parentId ?: continue
            children.getOrPut(parent) { mutableListOf() }.add(collection.id)
        }
        val ruledIds = rules.map { it.collectionId }
        val membership = if (ruledIds.isEmpty()) {
            emptyMap()
        } else {
            db.collectionAssetDao().getByCollections(collectSubtree(ruledIds, children))
                .groupBy({ it.collectionId }, { it.assetId })
        }
        return SelectionResolveInput(
            selections = selections,
            rules = rules,
            collectionChildren = children.mapValues { it.value.toList() },
            collectionAssets = membership,
            assets = emptyMap(),
        )
    }

    private fun collectSubtree(roots: List<String>, children: Map<String, List<String>>): List<String> {
        val visited = linkedSetOf<String>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            queue.addAll(children[current].orEmpty())
        }
        return visited.toList()
    }

    private suspend fun <T> withIo(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { block() }
}
