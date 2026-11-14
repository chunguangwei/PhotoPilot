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
) {
    fun suggestedNames(chinese: Boolean): List<String> = AINameSuggestions.names(AIConfiguration("preview", 1, model.id, model.revision, collections, recursive, false, rule, themes = themes), chinese)
}

suspend fun AIFrameService.preview(
    model: AIModel,
    collections: List<String>,
    recursive: Boolean,
    rules: List<AIRule>,
    frameId: String? = null,
    progress: (Int, Int) -> Unit,
): List<AIPreview> {
    check(!closed)
    require(collections.isNotEmpty() && rules.isNotEmpty() && rules.size <= 10)
    val job = requireNotNull(currentCoroutineContext()[kotlinx.coroutines.Job])
    previewJobs[job] = model.id
    try {
        return models.lock.withLock {
            if (!models.supported(model)) throw java.io.IOException("runtime_unavailable")
            require(models.verify(model)) { "model_integrity" }
            val checkpoint = AIPreviewCheckpoint.open(db, model, collections, recursive, rules, frameId)
            var dirty = false
            try {
                if (shouldPause()) throw java.io.IOException("preview_paused")
                val folder = models.directory(model)
                val adapter = createAIEmbedder(context, model, folder)
                adapter.use { engine ->
                    val descriptions = rules.map { engine.text(it.description) }
                    val includes = rules.map { rule -> rule.includes.map(engine::text) }
                    val excludes = rules.map { rule -> rule.excludes.map(engine::text) }
                    val themeVectors = AINameSuggestions.themes.mapValues { engine.text(it.value.first + " " + it.value.second) }
                    val candidates = mediaCandidates(collections, recursive)
                    val assetIds = candidates.keys.toList()
                    if (checkpoint.assets.keys.any { it !in assetIds }) {
                        checkpoint.assets.keys.retainAll(assetIds.toSet())
                        dirty = true
                    }
                    val matches = rules.map { linkedMapOf<String, Float>() }
                    val reviews = rules.map { linkedMapOf<String, Float>() }
                    val themes = rules.map { linkedMapOf<String, Double>() }
                    val fingerprints = linkedMapOf<String, String>()
                    val failed = linkedSetOf<String>()
                    var processed = 0
                    var reused = 0
                    var sinceSave = 0
                    for ((id, candidate) in candidates) {
                        currentCoroutineContext().ensureActive()
                        checkpoint.ensureCurrent()
                        if (shouldPause()) throw java.io.IOException("preview_paused")
                        val eligible = !excluded(candidate, frameId)
                        if (!eligible) {
                            dirty = checkpoint.assets.remove(id) != null || dirty
                        } else {
                            fingerprints[id] = candidate.fingerprint
                            val cached = checkpoint.assets[id]?.takeIf { it.contentHash == candidate.fingerprint }
                            val result = if (cached != null) {
                                reused++
                                cached
                            } else {
                                dirty = checkpoint.assets.remove(id) != null || dirty
                                try {
                                    val vector = mediaVector(candidate, engine, model)
                                    val categories = rules.indices.map { index ->
                                        val score = AIEmbedding.cosine(vector, descriptions[index])
                                        AICachedCategory(rules[index].evaluate(score, includes[index].map { AIEmbedding.cosine(vector, it) }, excludes[index].map { AIEmbedding.cosine(vector, it) }), score)
                                    }
                                    val themeScores = themeVectors.mapValues { AIEmbedding.cosine(vector, it.value).toDouble() }
                                    if (candidate.item.remote != null && remoteMedia?.valid(candidate.item) != true) throw java.io.IOException("REMOTE_REVOKED")
                                    AICachedAsset(candidate.fingerprint, categories, themeScores).also {
                                        checkpoint.assets[id] = it
                                        dirty = true
                                    }
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    failed.add(id)
                                    null
                                }
                            }
                            if (result != null) {
                                for (index in rules.indices) {
                                    when (result.categories[index].match) {
                                        AIMatch.MATCH -> {
                                            matches[index][id] = result.categories[index].score
                                            result.themes.forEach { (key, value) -> themes[index][key] = (themes[index][key] ?: 0.0) + value }
                                        }
                                        AIMatch.REVIEW -> reviews[index][id] = result.categories[index].score
                                        AIMatch.REJECT -> Unit
                                    }
                                }
                            }
                        }
                        processed++
                        sinceSave++
                        if (dirty && (sinceSave >= 8 || processed == 1)) {
                            checkpoint.save()
                            dirty = false
                            sinceSave = 0
                        }
                        progress(processed, assetIds.size)
                    }
                    rules.indices.map { index ->
                        AIPreview(model, collections, recursive, rules[index], matches[index], reviews[index], fingerprints, themes[index], failed.size, failed, reused)
                    }
                }
            } finally {
                if (dirty && !closed) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        try { checkpoint.save() } catch (_: java.io.IOException) { /* Invalidated work must stay invalidated. */ }
                    }
                }
            }
        }
    } finally {
        previewJobs.remove(job)
    }
}

/** Confirmation rechecks every asset in the same Room transaction that creates memberships. */
suspend fun AIFrameService.savePreviews(previews: List<AIPreview>, names: List<String>, chosen: List<Set<String>>, automatic: Boolean, existingFrameId: String? = null, expectedRuleRevision: Long = 0): List<String> {
    existingFrameId?.let { stop(it) }
    return db.withTransaction {
    require(previews.isNotEmpty() && previews.size == names.size && previews.size == chosen.size)
    require(existingFrameId == null || previews.size == 1)
    val frameIds = mutableListOf<String>()
    for (index in previews.indices) {
        val preview = previews[index]
        val previous = existingFrameId?.let { state.get(it) }
        require((previous?.revision ?: 0) == expectedRuleRevision) { "rule_conflict" }
        val oldFrame = existingFrameId?.let { requireNotNull(db.frameDao().getById(it)) }
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
            if (policyEligible && assetId in preview.failedAssetIDs) continue
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
        val processed = validFingerprints.filterKeys { it !in preview.failedAssetIDs }.mapValues { (_, hash) -> "$hash:${preview.model.revision}:${preview.model.preprocessingVersion}" }
        state.put(AIConfiguration(frame.id, (previous?.revision ?: 0) + 1, preview.model.id, preview.model.revision, preview.collections, preview.recursive, automatic, preview.rule, owned, processed, preview.reviews.filterKeys { it !in selectedIds && it in validFingerprints }, "complete", preview.fingerprints.size, preview.failed, preview.themes))
        db.frameDao().bumpSelectionVersion(frame.id, now)
        frameIds.add(frame.id)
    }
    if (existingFrameId == null) {
        listOf("ai.preview-draft", AIPreviewCheckpoint.LAST_CONFIG, AIPreviewCheckpoint.PREFIX + "new").forEach { key -> db.settingsKvDao().get(key)?.let { db.settingsKvDao().delete(it) } }
    }
    FrameRepository.sharedCache.clear()
    frameIds
}

}
