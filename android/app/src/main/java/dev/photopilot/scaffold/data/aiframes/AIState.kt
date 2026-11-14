package dev.photopilot.scaffold.data.aiframes

import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import dev.photopilot.scaffold.data.repo.AppSettingsRepository
import org.json.JSONArray
import org.json.JSONObject

data class AIConfiguration(
    val frameId: String,
    val revision: Long,
    val modelId: String,
    val modelRevision: String,
    val collections: List<String>,
    val recursive: Boolean,
    val autoUpdate: Boolean,
    val rule: AIRule,
    val members: Set<String> = emptySet(),
    val processed: Map<String, String> = emptyMap(),
    val reviews: Map<String, Float> = emptyMap(),
    val status: String = "idle",
    val completed: Int = 0,
    val failed: Int = 0,
    val themes: Map<String, Double> = emptyMap(),
) {
    val resumeAutomatically: Boolean get() = (autoUpdate && status != "cancelled") || status in listOf("running", "paused")

    fun json(): JSONObject = JSONObject().put("version", 1).put("frameId", frameId)
        .put("revision", revision).put("modelId", modelId).put("modelRevision", modelRevision)
        .put("collections", JSONArray(collections)).put("recursive", recursive).put("autoUpdate", autoUpdate)
        .put("description", rule.description).put("includes", JSONArray(rule.includes)).put("excludes", JSONArray(rule.excludes))
        .put("requireAll", rule.requireAll).put("threshold", rule.threshold.toDouble())
        .put("members", JSONArray(members.toList())).put("processed", JSONObject(processed)).put("reviews", JSONObject(reviews))
        .put("themes", JSONObject(themes)).put("status", status).put("completed", completed).put("failed", failed)

    companion object {
        fun parse(json: JSONObject): AIConfiguration {
            require(json.getInt("version") == 1)
            val processed = json.getJSONObject("processed")
            val reviews = json.getJSONObject("reviews")
            return AIConfiguration(
                json.getString("frameId"), json.getLong("revision"), json.getString("modelId"), json.getString("modelRevision"),
                strings(json.getJSONArray("collections")), json.getBoolean("recursive"), json.getBoolean("autoUpdate"),
                AIRule(json.getString("description"), strings(json.getJSONArray("includes")), strings(json.getJSONArray("excludes")), json.getBoolean("requireAll"), json.getDouble("threshold").toFloat()),
                strings(json.getJSONArray("members")).toSet(), processed.keys().asSequence().associateWith { processed.getString(it) },
                reviews.keys().asSequence().associateWith { reviews.getDouble(it).toFloat() }, json.getString("status"), json.getInt("completed"), json.getInt("failed"),
                json.optJSONObject("themes")?.let { theme -> theme.keys().asSequence().associateWith { theme.getDouble(it) } }.orEmpty(),
            )
        }
        fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
    }
}

/** All callers mutate this row inside the same Room transaction as frame selections. */
class AIStateStore(private val db: PhotoPilotDatabase) {
    private val settings = AppSettingsRepository(db.settingsKvDao())
    suspend fun get(frameId: String): AIConfiguration? = settings.rawValue("ai.frame.$frameId")?.let { runCatching { AIConfiguration.parse(JSONObject(it)) }.getOrNull() }
    suspend fun put(configuration: AIConfiguration) = settings.putRaw("ai.frame.${configuration.frameId}", configuration.json().toString())
    suspend fun removeAsset(assetId: String, now: Long) {
        AIPreviewCheckpoint.invalidateAll(db)
        for (frame in db.frameDao().getAll()) {
            val configuration = get(frame.id)
            if (configuration != null && (assetId in configuration.members || assetId in configuration.processed || assetId in configuration.reviews)) {
                if (assetId in configuration.members) {
                    val selection = db.frameSelectionDao().getByFrame(frame.id).firstOrNull { it.assetId == assetId }
                    if (selection?.mode == dev.photopilot.scaffold.data.db.FrameSelectionMode.INCLUDE) db.frameSelectionDao().deleteByRef(frame.id, selection.refKey)
                    db.frameDao().bumpSelectionVersion(frame.id, now)
                }
                put(configuration.copy(members = configuration.members - assetId, processed = configuration.processed - assetId, reviews = configuration.reviews - assetId, themes = emptyMap()))
            }
            if (frame.coverAssetId == assetId) {
                val current = db.frameDao().getById(frame.id) ?: continue
                db.frameDao().update(current.copy(coverAssetId = null, updatedAt = now))
            }
        }
        dev.photopilot.scaffold.data.repo.FrameRepository.sharedCache.clear()
    }
    suspend fun all(): List<AIConfiguration> = db.frameDao().getAll().mapNotNull { get(it.id) }
}
