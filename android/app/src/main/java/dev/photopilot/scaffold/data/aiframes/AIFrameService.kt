package dev.photopilot.scaffold.data.aiframes

import android.content.Context
import androidx.room.withTransaction
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.repo.FrameRepository
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

class AIFrameService(internal val context: Context, internal val db: PhotoPilotDatabase) {
    var remoteMedia: dev.photopilot.scaffold.data.remote.RemoteMediaRepository? = null
    val models = AIModelManager(context)
    val state = AIStateStore(db)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val previewJobs = java.util.concurrent.ConcurrentHashMap<Job, String>()
    @Volatile internal var closed = false
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    @Volatile var foreground = true
    var onProgress: ((String) -> Unit)? = null

    suspend fun savePreviewDraft(draft: Map<String, String>) {
        dev.photopilot.scaffold.data.repo.AppSettingsRepository(db.settingsKvDao()).putRaw("ai.preview-draft", org.json.JSONObject(draft).toString())
    }
    suspend fun lastPreviewDraft(): Map<String, String>? {
        val raw = dev.photopilot.scaffold.data.repo.AppSettingsRepository(db.settingsKvDao()).rawValue("ai.preview-draft") ?: return null
        return runCatching { val json = org.json.JSONObject(raw); json.keys().asSequence().associateWith { json.getString(it) } }.getOrNull()
    }

    suspend fun setAutomatic(frameId: String, enabled: Boolean) {
        jobs[frameId]?.cancelAndJoin()
        db.withTransaction {
            val current = state.get(frameId) ?: return@withTransaction
            if (db.frameDao().getById(frameId) != null) state.put(current.copy(revision = current.revision + 1, autoUpdate = enabled, status = "complete"))
        }
    }

    suspend fun configure(frameId: String, model: AIModel, collections: List<String>, recursive: Boolean, automatic: Boolean, rule: AIRule) {
        require(collections.isNotEmpty())
        jobs[frameId]?.cancelAndJoin()
        db.withTransaction {
            require(db.frameDao().getById(frameId) != null)
            val previous = state.get(frameId)
            val sameRule = previous != null && previous.modelId == model.id && previous.modelRevision == model.revision && previous.collections == collections.distinct() && previous.recursive == recursive && previous.rule == rule
            state.put(AIConfiguration(frameId, (previous?.revision ?: 0) + 1, model.id, model.revision, collections.distinct(), recursive, automatic, rule,
                members = previous?.members.orEmpty(), processed = if (sameRule) previous.processed else emptyMap(), reviews = if (sameRule) previous.reviews else emptyMap(),
                status = if (sameRule) "complete" else "idle", completed = if (sameRule) previous.completed else 0, failed = if (sameRule) previous.failed else 0, themes = if (sameRule) previous.themes else emptyMap()))
        }
    }
    suspend fun deleteModel(model: AIModel) {
        val previews = previewJobs.filterValues { it == model.id }.keys.toList()
        previews.forEach { it.cancel() }
        state.all().filter { it.modelId == model.id }.forEach { jobs[it.frameId]?.cancelAndJoin() }
        previews.forEach { it.join() }
        models.delete(model)
        db.withTransaction {
            AIPreviewCheckpoint.deleteModel(db, model.id)
            state.all().filter { it.modelId == model.id }.forEach { state.put(it.copy(status = "model_missing")) }
        }
    }
    suspend fun stop(frameId: String) { jobs[frameId]?.cancelAndJoin() }
    fun isRunning(frameId: String): Boolean = jobs[frameId]?.isActive == true
    fun cancel(frameId: String) { jobs[frameId]?.cancel() }
    suspend fun shutdown() {
        closed = true
        val previews = previewJobs.keys.toList()
        previews.forEach { it.cancel() }
        scope.coroutineContext[Job]?.cancelAndJoin()
        previews.forEach { it.join() }
    }
    fun resumeAutomatic() {
        scope.launch {
            for (configuration in state.all()) {
                if (configuration.resumeAutomatically) scan(configuration.frameId)
            }
        }
    }
    fun scan(frameId: String) {
        synchronized(jobs) {
            if (jobs[frameId]?.isActive == true) return
            jobs[frameId] = scope.launch {
                val configuration = state.get(frameId) ?: return@launch
                try { runScan(configuration) }
                catch (cancelled: CancellationException) { withContext(NonCancellable) { status(frameId, "cancelled", configuration.revision) }; throw cancelled }
                catch (failure: Exception) { status(frameId, when (failure.message) { "runtime_unavailable" -> "runtime_unavailable"; "model_missing" -> "model_missing"; else -> "failed" }, configuration.revision) }
                catch (_: LinkageError) { status(frameId, "runtime_unavailable", configuration.revision) }
                finally { onProgress?.invoke(frameId) }
            }
        }
    }
    internal fun shouldPause(): Boolean = !foreground || lowBattery() || context.getSystemService(android.os.PowerManager::class.java).currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE

    private fun lowBattery(): Boolean {
        val battery = context.getSystemService(android.os.BatteryManager::class.java)
        val capacity = battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return capacity in 0..15 && !battery.isCharging
    }
    private suspend fun status(frameId: String, status: String, revision: Long) = db.withTransaction {
        state.get(frameId)?.let { if (it.revision == revision && db.frameDao().getById(frameId) != null) state.put(it.copy(status = status)) }
    }
    private suspend fun runScan(initial: AIConfiguration) {
        val frameId = initial.frameId
        if (shouldPause()) { status(frameId, "paused", initial.revision); return }
        val model = models.models.firstOrNull { it.id == initial.modelId && it.revision == initial.modelRevision } ?: throw java.io.IOException("model_missing")
        if (!models.supported(model)) throw java.io.IOException("runtime_unavailable")
        require(models.ready(model)) { "model_missing" }
        models.lock.withLock {
            require(models.verify(model)) { "model_integrity" }
            val directory = models.directory(model)
            val embedder = createAIEmbedder(context, model, directory)
            embedder.use { engine ->
                val description = engine.text(initial.rule.description)
                val includes = initial.rule.includes.map(engine::text)
                val excludes = initial.rule.excludes.map(engine::text)
                val themes = AINameSuggestions.themes.mapValues { engine.text(it.value.first + " " + it.value.second) }
                val candidates = mediaCandidates(initial.collections, initial.recursive)
                status(frameId, "running", initial.revision)
                for (id in candidates.keys + initial.members) {
                    currentCoroutineContext().ensureActive()
                    if (shouldPause()) { status(frameId, "paused", initial.revision); return }
                    if (db.frameDao().getById(frameId) == null) return
                    val configuration = state.get(frameId) ?: return
                    if (!AIJobGuard.canCommit(initial.revision, configuration.revision, initial.modelRevision, configuration.modelRevision, false)) return
                    val candidate = candidates[id]
                    val fingerprint = "${candidate?.fingerprint}:${model.revision}:${model.preprocessingVersion}"
                    val eligible = candidate != null && !excluded(candidate, frameId)
                    if (eligible && configuration.processed[id] == fingerprint) continue
                    var failed = false
                    var score = -1f
                    var themeScores = emptyMap<String, Double>()
                    val match = if (!eligible) AIMatch.REJECT else try {
                        val vector = mediaVector(requireNotNull(candidate), engine, model)
                        score = AIEmbedding.cosine(vector, description)
                        themeScores = themes.mapValues { AIEmbedding.cosine(vector, it.value).toDouble() }
                        initial.rule.evaluate(score, includes.map { AIEmbedding.cosine(vector, it) }, excludes.map { AIEmbedding.cosine(vector, it) })
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed = true; AIMatch.REJECT }
                    currentCoroutineContext().ensureActive()
                    db.withTransaction {
                        val latest = state.get(frameId) ?: return@withTransaction
                        if (!AIJobGuard.canCommit(initial.revision, latest.revision, initial.modelRevision, latest.modelRevision, false)) return@withTransaction
                        val frame = db.frameDao().getById(frameId) ?: return@withTransaction
                        val freshCandidate = mediaCandidates(initial.collections, initial.recursive)[id]
                        val stillAllowed = freshCandidate != null && candidate?.fingerprint == freshCandidate.fingerprint && !excluded(freshCandidate, frameId)
                        val aliases = freshCandidate?.let { selectionAliases(it) }.orEmpty() + id.removePrefix("remote:")
                        val existing = db.frameSelectionDao().getByFrame(frameId).firstOrNull { it.refKey in aliases }
                        val members = latest.members.toMutableSet()
                        val now = System.currentTimeMillis()
                        if (match == AIMatch.MATCH && stillAllowed && existing == null) {
                            db.frameSelectionDao().upsertResolved(frameId, id.takeUnless { it.startsWith("remote:") }, id.takeIf { it.startsWith("remote:") }?.removePrefix("remote:"), FrameSelectionMode.INCLUDE, null, now)
                            members.add(id)
                        } else if (AIMembershipPolicy.removeExisting(match, failed, stillAllowed) && id in members) {
                            if (existing?.mode == FrameSelectionMode.INCLUDE) db.frameSelectionDao().deleteByRef(frameId, existing.refKey)
                            members.remove(id)
                        }
                        val processed = if (!stillAllowed) latest.processed - id else if (failed) latest.processed else latest.processed + (id to fingerprint)
                        val reviews = latest.reviews.toMutableMap().apply { if (!failed || !stillAllowed) { remove(id); if (match == AIMatch.REVIEW && stillAllowed) put(id, score) } }
                        val accumulatedThemes = latest.themes.toMutableMap()
                        if (match == AIMatch.MATCH && stillAllowed) themeScores.forEach { (key, value) -> accumulatedThemes[key] = (accumulatedThemes[key] ?: 0.0) + value }
                        state.put(latest.copy(members = members, processed = processed, reviews = reviews, completed = latest.completed + 1, failed = latest.failed + if (failed) 1 else 0, themes = accumulatedThemes))
                        if (id.startsWith("remote:") && !dev.photopilot.scaffold.data.logic.JsonMini.parseObject(frame.settingsJson).containsKey("mediaStorageMode")) {
                            val settings = dev.photopilot.scaffold.data.logic.JsonMini.parseObject(frame.settingsJson).toMutableMap()
                            settings["mediaStorageMode"] = "remote"
                            db.frameDao().update(frame.copy(settingsJson = dev.photopilot.scaffold.data.logic.JsonMini.toJson(settings)))
                        }
                        db.frameDao().bumpSelectionVersion(frame.id, now)
                        FrameRepository.sharedCache.clear()
                    }
                    onProgress?.invoke(frameId)
                    yield()
                }
                db.withTransaction { state.get(frameId)?.let { if (it.revision == initial.revision) state.put(it.copy(status = "complete")) } }
            }
        }
    }
}
