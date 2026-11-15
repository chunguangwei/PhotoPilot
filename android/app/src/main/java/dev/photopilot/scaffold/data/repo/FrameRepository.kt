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

    var remoteMedia: dev.photopilot.scaffold.data.remote.RemoteMediaRepository? = null

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

    suspend fun nasSelectionAliases(frameId: String): Map<String, String> {
        val sources = db.sourceDao().getAll().associateBy { it.id }
        val result = mutableMapOf<String, String>()
        for (assetId in (db.frameSelectionDao().getByFrame(frameId).mapNotNull { it.assetId } + listOfNotNull(db.frameDao().getById(frameId)?.coverAssetId)).distinct()) {
            val asset = db.assetDao().getById(assetId) ?: continue
            if (asset.privacyExcluded || asset.deletedAt != null || asset.mediaKind != dev.photopilot.scaffold.data.db.MediaKind.IMAGE) continue
            val origins = db.assetOriginDao().getByAsset(assetId)
            val candidates = origins.mapNotNull { origin -> db.sourceAssetDao().getById(origin.sourceAssetId)?.let { origin to it } }
            if (candidates.isEmpty() || candidates.size != origins.size || candidates.any { candidate ->
                val source = sources[candidate.second.sourceId]
                source?.type !in setOf(dev.photopilot.scaffold.data.db.SourceType.SMB, dev.photopilot.scaffold.data.db.SourceType.WEBDAV) && dev.photopilot.scaffold.data.logic.JsonMini.parseObject(source?.settingsJson)["referenceOnly"] != true
            }) continue
            val allowed = candidates.sortedBy { it.second.id }.firstOrNull { (origin, remote) ->
                origin.policyState == dev.photopilot.scaffold.data.db.PolicyState.ALLOWED && remoteMedia?.item(remote.id, allowRetainedCopy = false) != null
            } ?: continue
            result[assetId] = allowed.second.id
        }
        return result
    }

    suspend fun migrateNasSelections(frameId: String, baseVersion: Long, now: Long = System.currentTimeMillis()) = db.withTransaction {
        val frame = requireNotNull(db.frameDao().getById(frameId))
        require(frame.selectionVersion == baseVersion) { "REMOTE_SELECTION_CONFLICT" }
        val aliases = nasSelectionAliases(frameId)
        if (aliases.isEmpty()) return@withTransaction
        frame.coverAssetId?.let { aliases[it] }?.let { sourceId ->
            val settings = dev.photopilot.scaffold.data.logic.JsonMini.parseObject(frame.settingsJson).toMutableMap()
            settings["remoteCoverRef"] = sourceId
            db.frameDao().update(frame.copy(coverAssetId = null, settingsJson = dev.photopilot.scaffold.data.logic.JsonMini.toJson(settings), updatedAt = now))
        }
        val rows = db.frameSelectionDao().getByFrame(frameId)
        for ((sourceId, oldRows) in rows.filter { it.assetId in aliases }.groupBy { aliases.getValue(requireNotNull(it.assetId)) }) {
            val existing = rows.firstOrNull { it.sourceAssetId == sourceId }
            val grouped = oldRows + listOfNotNull(existing)
            val template = grouped.firstOrNull { it.mode == FrameSelectionMode.EXCLUDE } ?: grouped.minWith(compareBy({ it.manualOrder == null }, { it.manualOrder ?: 0 }, { it.addedAt }))
            oldRows.forEach { db.frameSelectionDao().deleteByRef(frameId, it.refKey) }
            db.frameSelectionDao().upsertRaw(template.copy(assetId = null, sourceAssetId = sourceId, refKey = sourceId, updatedAt = now))
        }
        val state = dev.photopilot.scaffold.data.aiframes.AIStateStore(db)
        state.get(frameId)?.let { configuration ->
            fun translated(id: String) = aliases[id]?.let { "remote:$it" } ?: id
            val excluded = db.frameSelectionDao().getByFrame(frameId).filter { it.mode == FrameSelectionMode.EXCLUDE }.map { "remote:${it.refKey}" }.toSet()
            state.put(configuration.copy(
                revision = configuration.revision + 1,
                members = configuration.members.map(::translated).toSet() - excluded,
                processed = configuration.processed.filterKeys { it !in aliases },
                reviews = configuration.reviews.mapKeys { translated(it.key) }.filterKeys { it !in excluded },
                status = "idle",
            ))
        }
    }

    suspend fun coverItem(frameId: String): dev.photopilot.scaffold.data.remote.PlaybackItem? {
        val frame = db.frameDao().getById(frameId) ?: return null
        val settings = dev.photopilot.scaffold.data.logic.JsonMini.parseObject(frame.settingsJson)
        val remoteRef = settings["remoteCoverRef"] as? String
        if (remoteRef != null) {
            val allowRetained = dev.photopilot.scaffold.data.logic.FramePlaybackSettings.parse(frame.settingsJson).mediaStorageMode == "local"
            remoteMedia?.item(remoteRef, allowRetainedCopy = allowRetained)?.let { return it }
        }
        frame.coverAssetId?.let { eligibleCover(it) }?.let { return dev.photopilot.scaffold.data.remote.PlaybackItem(local = it) }
        return playbackItems(frameId).let { items -> buildPlayQueue(frameId).firstOrNull()?.let { id -> items.firstOrNull { it.id == id } } }
    }

    suspend fun eligibleCover(assetId: String): dev.photopilot.scaffold.data.db.AssetEntity? = withIo {
        val asset = db.assetDao().getById(assetId) ?: return@withIo null
        val allowed = dev.photopilot.scaffold.data.aiframes.AIEligibility.allowed(asset.readiness.wire, asset.privacyExcluded, asset.deletedAt != null, asset.mediaKind.wire, db.assetOriginDao().allowedAssetIds(listOf(assetId)).isNotEmpty(), false)
        asset.takeIf { allowed && File(originalsDir, it.originalRelPath).isFile }
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
        if (remoteMedia == null) cache.get(cacheKey)?.let { return@withIo it }

        val skeleton = loadSkeleton(frameId)
        val firstPass = SelectionResolver.resolve(skeleton.copy(assets = emptyMap()))
        val candidateIds = firstPass.candidates
        val assets = if (candidateIds.isEmpty()) {
            emptyMap()
        } else {
            val entities = db.assetDao().getByIds(candidateIds).associateBy { it.id }
            val allowedIds = db.assetOriginDao().allowedAssetIds(candidateIds).toSet()
            val excludedSources = skeleton.selections.filter { it.mode == SelectionResolver.MODE_EXCLUDE }.mapNotNull { it.sourceAssetId }
            val blockedAliases = excludedSources.chunked(400).flatMap { db.assetOriginDao().getBySourceAssets(it) }.map { it.assetId }.toSet()
            candidateIds.mapNotNull { id ->
                entities[id]?.let { entity ->
                    id to ResolvableAsset(
                        id = id,
                        readiness = entity.readiness.wire,
                        privacyExcluded = entity.privacyExcluded,
                        deleted = entity.deletedAt != null,
                        fileExists = File(originalsDir, entity.originalRelPath).isFile,
                        policyAllowed = id in allowedIds && id !in blockedAliases,
                        mediaKind = entity.mediaKind.wire,
                    )
                }
            }.toMap()
        }
        val localResolution = SelectionResolver.resolve(skeleton.copy(assets = assets))
        val remote = remoteItems(frameId)
        val requiresLocalCopies = dev.photopilot.scaffold.data.logic.FramePlaybackSettings.requiresLocalCopies(frame.settingsJson)
        val ready = remote.filter { !requiresLocalCopies || it.local != null || remoteMedia?.isLocalReference(it) == true }.map { it.id }
        val referencedLocalIds = remote.flatMap { item ->
            listOfNotNull(item.local?.id) + db.assetOriginDao().getBySourceAssets(listOfNotNull(item.sourceAssetId)).map { it.assetId }
        }.toSet()
        val resolution = localResolution.copy(
            allowed = (localResolution.allowed + remote.map { it.id }).distinct(),
            playable = (localResolution.playable.filter { it !in referencedLocalIds } + ready).distinct(),
        )
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

            val aiStore = dev.photopilot.scaffold.data.aiframes.AIStateStore(db)
            val aiConfiguration = aiStore.get(frameId)
            val removedAIMembers = removeRefs.filter { (it.sourceAssetId?.let { id -> "remote:$id" } ?: it.assetId) in aiConfiguration?.members.orEmpty() }
            val effectiveExcludes = addExcludes + removedAIMembers
            val request = SelectionSaveRequest(
                addIncludes = addIncludes.map { AddInclude(ref = it.refKey, addedAt = now) },
                removeRefs = removeRefs.map { it.refKey },
                addExcludes = effectiveExcludes.map { it.refKey },
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
            for (edit in addIncludes + effectiveExcludes + removeRefs) {
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

            if (aiConfiguration != null) {
                val manualEdits = addIncludes + removeRefs + addExcludes
                val sourceRefs = manualEdits.mapNotNull { it.sourceAssetId }
                val referencedAssets = if (sourceRefs.isEmpty()) emptyList() else db.assetOriginDao().getBySourceAssets(sourceRefs).map { it.assetId }
                val manualRefs = (manualEdits.mapNotNull { it.assetId } + referencedAssets + sourceRefs.map { "remote:$it" }).toSet()
                val excludedSourceRefs = addExcludes.mapNotNull { it.sourceAssetId }
                if (excludedSourceRefs.isNotEmpty()) {
                    val blockedAssets = db.assetOriginDao().getBySourceAssets(excludedSourceRefs).map { it.assetId }.toSet()
                    for (assetId in aiConfiguration.members.intersect(blockedAssets)) {
                        val current = db.frameSelectionDao().getByFrame(frameId).firstOrNull { it.assetId == assetId && it.mode == FrameSelectionMode.INCLUDE }
                        if (current != null) db.frameSelectionDao().deleteByRef(frameId, current.refKey)
                    }
                }
                aiStore.put(aiConfiguration.copy(members = aiConfiguration.members - manualRefs, processed = aiConfiguration.processed - manualRefs, reviews = aiConfiguration.reviews - manualRefs))
            }
            db.frameDao().bumpSelectionVersion(frameId, now)
            cache.clear()
            result
        }
    }

    suspend fun playbackItems(frameId: String): List<dev.photopilot.scaffold.data.remote.PlaybackItem> = withIo {
        val frame = db.frameDao().getById(frameId) ?: return@withIo emptyList()
        val remote = remoteItems(frameId)
        val requiresLocalCopies = dev.photopilot.scaffold.data.logic.FramePlaybackSettings.requiresLocalCopies(frame.settingsJson)
        val includedRemote = remote.filter { !requiresLocalCopies || it.local != null || remoteMedia?.isLocalReference(it) == true }
        val localIds = resolve(frameId).playable.filter { !it.startsWith("remote:") }
        val remoteLocalIds = includedRemote.flatMap { item ->
            listOfNotNull(item.local?.id) + db.assetOriginDao().getBySourceAssets(listOfNotNull(item.sourceAssetId)).map { it.assetId }
        }.toSet()
        db.assetDao().getByIds(localIds).filter { it.id !in remoteLocalIds }.map { dev.photopilot.scaffold.data.remote.PlaybackItem(local = it) } + includedRemote
    }

    suspend fun buildPlayQueue(frameId: String): List<String> = withIo {
        val frame = db.frameDao().getById(frameId) ?: return@withIo emptyList()
        val selections = db.frameSelectionDao().getByFrame(frameId).associateBy { it.refKey }
        val items = playbackItems(frameId)
        PlaybackQueue.buildQueue(items.map { item ->
            val selection = selections[item.sourceAssetId ?: item.id]
            QueueAsset(item.id, item.capturedAt, item.importedAt, selection?.manualOrder, selection?.addedAt ?: item.importedAt)
        }, frame.orderMode.wire, frame.randomSeed)
    }

    suspend fun remoteItems(frameId: String): List<dev.photopilot.scaffold.data.remote.PlaybackItem> {
        val media = remoteMedia ?: return emptyList()
        val frame = db.frameDao().getById(frameId) ?: return emptyList()
        val allowRetained = dev.photopilot.scaffold.data.logic.FramePlaybackSettings.parse(frame.settingsJson).mediaStorageMode == "local"
        val selections = db.frameSelectionDao().getByFrame(frameId)
        val excluded = selections.filter { it.mode == FrameSelectionMode.EXCLUDE }.map { it.refKey }.toSet()
        val ids = selections.filter { it.mode == FrameSelectionMode.INCLUDE }.mapNotNull { it.sourceAssetId }.toMutableSet()
        for (rule in db.frameDirectoryRuleDao().getByFrame(frameId)) {
            val collection = db.collectionDao().getById(rule.collectionId) ?: continue
            val sourceId = collection.sourceId ?: continue
            for (asset in media.members(sourceId, collection.relPath.orEmpty(), rule.recursive)) {
                ids.add(asset.id)
            }
        }
        return ids.filter { it !in excluded }.mapNotNull { media.item(it, allowRetainedCopy = allowRetained) }.filter { item ->
            item.local?.id !in excluded && db.assetOriginDao().getBySourceAssets(listOfNotNull(item.sourceAssetId)).none { it.assetId in excluded }
        }
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
