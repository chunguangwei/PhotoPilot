package dev.photopilot.scaffold.data.aiframes

import androidx.room.withTransaction
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.repo.FrameManager
import dev.photopilot.scaffold.data.repo.FrameRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock

data class AIPreview(
    val model: AIModel,
    val collections: List<String>,
    val recursive: Boolean,
    val rule: AIRule,
    val matches: Map<String, Float>,
    val reviews: Map<String, Float>,
    val fingerprints: Map<String, String>,
    val themes: Map<String, Double>,
    val failed: Int,
    val failedAssetIDs: Set<String> = emptySet(),
    val reusedAssetCount: Int = 0,
    val isComplete: Boolean = true,
    val scannedCount: Int = fingerprints.size,
    val totalCount: Int = fingerprints.size,
) {
    fun suggestedNames(chinese: Boolean): List<String> = AINameSuggestions.names(AIConfiguration("preview", 1, model.id, model.revision, collections, recursive, false, rule, themes = themes), chinese)
}

suspend fun AIFrameService.preview(
    model: AIModel,
    collections: List<String>,
    recursive: Boolean,
    rules: List<AIRule>,
    frameId: String? = null,
    checkpointId: String? = null,
    allowPartial: Boolean = false,
    stopRequested: () -> Boolean = { false },
    commitAllowed: () -> Boolean = { true },
    onLeaseAcquired: suspend () -> Unit = {},
    progress: (Int, Int) -> Unit,
): List<AIPreview> {
    check(!closed)
    require(collections.isNotEmpty() && rules.isNotEmpty() && rules.size <= 10)
    val job = requireNotNull(currentCoroutineContext()[kotlinx.coroutines.Job])
    previewJobs[job] = model.id
    try {
        return models.lease(model) {
            onLeaseAcquired()
            if (!models.supported(model)) throw java.io.IOException("runtime_unavailable")
            val checkpoint = AIPreviewCheckpoint.open(db, model, collections, recursive, rules, frameId, checkpointId)
            var dirty = false
            try {
                if (shouldPause() || stopRequested()) {
                    if (!allowPartial) throw java.io.IOException("preview_paused")
                    val candidates = mediaCandidates(collections, recursive)
                    val count = checkpoint.assets.size + checkpoint.failures.size
                    return@lease previewSnapshot(model, collections, recursive, rules, frameId, checkpoint, candidates, count, candidates.size, false, checkpoint.assets.size)
                }
                require(models.verify(model)) { "model_integrity" }
                val folder = models.directory(model)
                val adapter = createAIEmbedder(context, model, folder)
                adapter.use { engine ->
                    val descriptions = rules.map { textVector(engine, it.description) }
                    val includes = rules.map { rule -> rule.includes.map { textVector(engine, it) } }
                    val excludes = rules.map { rule -> rule.excludes.map { textVector(engine, it) } }
                    val themeVectors = AINameSuggestions.themes.mapValues { textVector(engine, it.value.first + " " + it.value.second) }
                    val candidates = mediaCandidates(collections, recursive)
                    val assetIds = candidates.keys.toSet()
                    checkpoint.prune(candidates.mapValues { it.value.fingerprint })
                    val reused = checkpoint.assets.size
                    val visited = (checkpoint.assets.keys + checkpoint.failures.keys).toMutableSet()
                    progress(visited.size, assetIds.size)
                    var completedAll = true
                    for ((id, candidate) in candidates) {
                        currentCoroutineContext().ensureActive()
                        checkpoint.ensureCurrent()
                        if (frameId != null && db.frameDao().getById(frameId) == null) throw java.io.IOException("frame_removed")
                        if (shouldPause() || stopRequested()) {
                            if (!allowPartial) throw java.io.IOException("preview_paused")
                            completedAll = visited.containsAll(assetIds)
                            break
                        }
                        if (excluded(candidate, frameId)) {
                            dirty = checkpoint.assets.remove(id) != null || dirty
                            dirty = checkpoint.failures.remove(id) != null || dirty
                        } else if (checkpoint.assets[id]?.contentHash != candidate.fingerprint) {
                            checkpoint.assets.remove(id)
                            checkpoint.failures.remove(id)
                            try {
                                val vector = mediaVector(candidate, engine, model)
                                val categories = rules.indices.map { index ->
                                    val score = AIEmbedding.cosine(vector, descriptions[index])
                                    AICachedCategory(rules[index].evaluate(score, includes[index].map { AIEmbedding.cosine(vector, it) }, excludes[index].map { AIEmbedding.cosine(vector, it) }), score)
                                }
                                val themeScores = themeVectors.mapValues { AIEmbedding.cosine(vector, it.value).toDouble() }
                                if (candidate.item.remote != null && remoteMedia?.valid(candidate.item) != true) throw java.io.IOException("REMOTE_REVOKED")
                                if (!commitAllowed()) throw java.io.IOException("preview_interrupted")
                                checkpoint.assets[id] = AICachedAsset(candidate.fingerprint, categories, themeScores)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                checkpoint.failures[id] = candidate.fingerprint
                            }
                            dirty = true
                        }
                        if (dirty) {
                            checkpoint.saveAsset(id)
                            dirty = false
                        }
                        visited.add(id)
                        progress(visited.size, assetIds.size)
                    }
                    previewSnapshot(model, collections, recursive, rules, frameId, checkpoint, candidates, visited.size, assetIds.size, completedAll, reused)
                }
            } finally {
                if (dirty && !closed) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        try { checkpoint.save() } catch (_: java.io.IOException) { }
                    }
                }
            }
        }
    } finally {
        previewJobs.remove(job)
        if (!closed && previewJobs.isEmpty() && !shouldPause()) resumeAutomatic(interruptedOnly = true)
    }
}

/** Confirmation rechecks every asset in the same Room transaction that creates memberships. */
suspend fun AIFrameService.savePreviews(previews: List<AIPreview>, names: List<String>, chosen: List<Set<String>>, automatic: Boolean, existingFrameId: String? = null, expectedRuleRevision: Long = 0, paused: Boolean? = null, draftId: String? = null, categoryIds: List<String> = previews.indices.map { it.toString() }): List<String> {
    existingFrameId?.let { stop(it) }
    return db.withTransaction {
    require(previews.isNotEmpty() && previews.size == names.size && previews.size == chosen.size)
    require(existingFrameId == null || previews.size == 1)
    require(categoryIds.size == previews.size && categoryIds.distinct().size == categoryIds.size)
    val bindingKey = draftId?.let { "ai.preview-bindings.$it" }
    val bindings = bindingKey?.let { db.settingsKvDao().get(it)?.valueJson }?.let { org.json.JSONObject(it) } ?: org.json.JSONObject()
    if (existingFrameId == null && categoryIds.all { bindings.has(it) }) {
        return@withTransaction categoryIds.map { key -> bindings.getString(key).also { require(db.frameDao().getById(it) != null) { "frame_removed" } } }
    }
    val frameIds = mutableListOf<String>()
    for (index in previews.indices) {
        val preview = previews[index]
        val boundId = existingFrameId ?: bindings.optString(categoryIds[index]).takeIf { it.isNotEmpty() }
        val previous = boundId?.let { state.get(it) }
        require(existingFrameId == null || (previous?.revision ?: 0) == expectedRuleRevision) { "rule_conflict" }
        val oldFrame = boundId?.let { requireNotNull(db.frameDao().getById(it)) { "frame_removed" } }
        val now = System.currentTimeMillis()
        val frame = oldFrame ?: FrameEntity(UUID.randomUUID().toString(), names[index].trim().ifEmpty { preview.rule.description.take(60) }, null, FrameManager.DEFAULT_SETTINGS_JSON, 0, FrameOrderMode.CAPTURED_ASC, kotlin.random.Random.nextLong(), now, now)
        val namedFrame = frame.copy(name = names[index].trim().ifEmpty { frame.name }, updatedAt = now)
        if (oldFrame == null) db.frameDao().upsert(namedFrame) else db.frameDao().update(namedFrame)
        val candidates = mediaCandidates(preview.collections, preview.recursive)
        val owned = previous?.members.orEmpty().toMutableSet()
        val selectionRows = db.frameSelectionDao().getByFrame(frame.id)
        val validFingerprints = preview.fingerprints.filter { (id, hash) -> candidates[id]?.fingerprint == hash }
        val selectedIds = chosen[index].intersect(preview.matches.keys + preview.reviews.keys)
        for (assetId in (selectedIds + owned + preview.matches.keys)) {
            val candidate = candidates[assetId]
            val rawId = assetId.removePrefix("remote:")
            val aliases = candidate?.let { selectionAliases(it) }.orEmpty() + rawId
            val current = selectionRows.firstOrNull { it.refKey == rawId } ?: selectionRows.firstOrNull { it.refKey in aliases }
            val excluded = candidate?.let { excluded(it, frame.id) } ?: true
            val policyEligible = candidate != null && !excluded
            if (policyEligible && (assetId in preview.failedAssetIDs || assetId !in preview.fingerprints)) continue
            if (policyEligible && candidate.fingerprint != preview.fingerprints[assetId] && assetId in preview.fingerprints) continue
            val eligible = policyEligible && candidate.fingerprint == preview.fingerprints[assetId]
            if (assetId in selectedIds && eligible) {
                if (assetId in preview.reviews) owned.remove(assetId)
                if (current == null) {
                    db.frameSelectionDao().upsertResolved(frame.id, assetId.takeUnless { it.startsWith("remote:") }, assetId.takeIf { it.startsWith("remote:") }?.removePrefix("remote:"), FrameSelectionMode.INCLUDE, null, now)
                    if (assetId in preview.matches) owned.add(assetId)
                }
            } else if (assetId in owned) {
                if (current?.mode == FrameSelectionMode.INCLUDE) db.frameSelectionDao().deleteByRef(frame.id, rawId)
                owned.remove(assetId)
            }
            if (policyEligible && assetId in preview.matches && assetId !in selectedIds && !excluded) {
                db.frameSelectionDao().upsertResolved(frame.id, assetId.takeUnless { it.startsWith("remote:") }, assetId.takeIf { it.startsWith("remote:") }?.removePrefix("remote:"), FrameSelectionMode.EXCLUDE, null, now)
                owned.remove(assetId)
            }
        }
        if (selectedIds.any { it.startsWith("remote:") }) {
            val settings = dev.photopilot.scaffold.data.logic.JsonMini.parseObject(namedFrame.settingsJson).toMutableMap()
            if (!settings.containsKey("mediaStorageMode")) settings["mediaStorageMode"] = "remote"
            db.frameDao().update(namedFrame.copy(settingsJson = dev.photopilot.scaffold.data.logic.JsonMini.toJson(settings)))
        }
        val sameRule = previous != null && previous.rule == preview.rule && previous.modelId == preview.model.id && previous.modelRevision == preview.model.revision && previous.collections == preview.collections && previous.recursive == preview.recursive
        val retainedProcessed = if (sameRule) previous.processed.filter { (id, fingerprint) -> id !in preview.failedAssetIDs && candidates[id]?.let { fingerprint == "${it.fingerprint}:${preview.model.revision}:${preview.model.preprocessingVersion}" } == true } else emptyMap()
        val processed = retainedProcessed + validFingerprints.filterKeys { it !in preview.failedAssetIDs }.mapValues { (_, hash) -> "$hash:${preview.model.revision}:${preview.model.preprocessingVersion}" }
        val preservedReviews = previous?.reviews.orEmpty().filterKeys { id ->
            val candidate = candidates[id]
            candidate != null && !excluded(candidate, frame.id) && (id !in validFingerprints || id in preview.failedAssetIDs)
        }
        val reviews = preservedReviews + preview.reviews.filterKeys { it !in selectedIds && it in validFingerprints }
        val changedSincePreview = candidates.any { (id, candidate) ->
            !excluded(candidate, frame.id) && validFingerprints[id] != candidate.fingerprint && id !in preview.failedAssetIDs
        }
        val userPaused = previous?.userPaused == true || paused == true || !preview.isComplete || changedSincePreview
        state.put(AIConfiguration(frame.id, (previous?.revision ?: 0) + 1, preview.model.id, preview.model.revision, preview.collections, preview.recursive, automatic, preview.rule, owned, processed, reviews, if (userPaused) "user_paused" else if (preview.isComplete) "complete" else "paused", processed.size, preview.failed, preview.themes, userPaused, preview.totalCount, if (userPaused) "user" else null))
        db.frameDao().bumpSelectionVersion(frame.id, now)
        frameIds.add(frame.id)
        bindings.put(categoryIds[index], frame.id)
    }
    if (existingFrameId == null) {
        val currentDraft = db.settingsKvDao().get("ai.preview-draft")?.valueJson?.let { runCatching { org.json.JSONObject(it).optString("draftId") }.getOrNull() }
        if (draftId == null || currentDraft == draftId) {
            db.settingsKvDao().deleteKey("ai.preview-draft")
            db.settingsKvDao().deleteKey(AIPreviewCheckpoint.LAST_CONFIG)
        }
        AIPreviewCheckpoint.delete(db, "new")
    }
    if (bindingKey != null) {
        val now = System.currentTimeMillis()
        db.settingsKvDao().upsert(SettingsKvEntity(bindingKey, bindings.toString(), now, now))
    }
    FrameRepository.sharedCache.clear()
    frameIds
}

}

internal suspend fun AIFrameService.cachedPreview(model: AIModel, collections: List<String>, recursive: Boolean, rules: List<AIRule>, frameId: String?, checkpointId: String, scanned: Int, total: Int, complete: Boolean): List<AIPreview> {
    val checkpoint = AIPreviewCheckpoint.open(db, model, collections, recursive, rules, frameId, checkpointId)
    val candidates = mediaCandidates(collections, recursive)
    checkpoint.prune(candidates.mapValues { it.value.fingerprint })
    return previewSnapshot(model, collections, recursive, rules, frameId, checkpoint, candidates, maxOf(scanned, checkpoint.assets.size + checkpoint.failures.size), maxOf(total, candidates.size), complete, checkpoint.assets.size)
}

private suspend fun AIFrameService.previewSnapshot(model: AIModel, collections: List<String>, recursive: Boolean, rules: List<AIRule>, frameId: String?, checkpoint: AIPreviewCheckpoint, candidates: Map<String, AIMedia>, scanned: Int, total: Int, complete: Boolean, reused: Int): List<AIPreview> {
    val excludedIds = candidates.values.filter { excluded(it, frameId) }.map { it.id }.toSet()
    val cached = checkpoint.assets.filter { (id, asset) -> candidates[id]?.fingerprint == asset.contentHash && id !in excludedIds }
    val failed = checkpoint.failures.filter { (id, fingerprint) -> candidates[id]?.fingerprint == fingerprint && id !in excludedIds }
    val visited = (cached.keys + failed.keys + excludedIds).size
    return rules.indices.map { index ->
        val matches = cached.filterValues { it.categories[index].match == AIMatch.MATCH }.mapValues { it.value.categories[index].score }
        val reviews = cached.filterValues { it.categories[index].match == AIMatch.REVIEW }.mapValues { it.value.categories[index].score }
        val themes = mutableMapOf<String, Double>()
        for (id in matches.keys) {
            for ((key, score) in cached.getValue(id).themes) themes[key] = (themes[key] ?: 0.0) + score
        }
        AIPreview(model, collections, recursive, rules[index], matches, reviews, cached.mapValues { it.value.contentHash } + failed, themes, failed.size, failed.keys,
            reusedAssetCount = reused, isComplete = complete && visited == candidates.size, scannedCount = visited, totalCount = candidates.size)
    }
}
