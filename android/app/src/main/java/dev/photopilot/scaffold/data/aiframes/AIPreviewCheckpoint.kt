package dev.photopilot.scaffold.data.aiframes

import androidx.room.withTransaction
import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import dev.photopilot.scaffold.data.repo.AppSettingsRepository
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class AICachedCategory(val match: AIMatch, val score: Float)
internal data class AICachedAsset(val contentHash: String, val categories: List<AICachedCategory>, val themes: Map<String, Double>) {
    fun json(): JSONObject = JSONObject().put("hash", contentHash)
        .put("categories", JSONArray(categories.map { JSONObject().put("match", it.match.name).put("score", it.score.toDouble()) }))
        .put("themes", JSONObject(themes))

    companion object {
        fun parse(json: JSONObject, categoryCount: Int): AICachedAsset {
            val categories = json.getJSONArray("categories")
            require(categories.length() == categoryCount)
            val scores = (0 until categories.length()).map { index ->
                val item = categories.getJSONObject(index)
                val score = item.getDouble("score").toFloat()
                require(score.isFinite() && score in -1f..1f)
                AICachedCategory(AIMatch.valueOf(item.getString("match")), score)
            }
            val themes = json.getJSONObject("themes")
            val values = themes.keys().asSequence().associateWith { key ->
                themes.getDouble(key).also { require(it.isFinite() && it in -1.0..1.0 && key in AINameSuggestions.themes) }
            }
            return AICachedAsset(json.getString("hash"), scores, values)
        }
    }
}

/** A Room row records preview work without creating frames or frame selections. */
internal class AIPreviewCheckpoint private constructor(
    private val db: PhotoPilotDatabase,
    private val key: String,
    private val configuration: JSONObject,
    private val identity: String,
    private val owner: String,
    private val generation: Long,
    val assets: MutableMap<String, AICachedAsset>,
) {
    suspend fun save() = db.withTransaction {
        val settings = AppSettingsRepository(db.settingsKvDao())
        if (generation(db) != generation) throw IOException("preview_invalidated")
        val current = settings.rawValue(key)?.let { JSONObject(it) }
        if (current?.optString("owner") != owner) throw IOException("preview_superseded")
        val values = JSONObject()
        assets.forEach { (id, value) -> values.put(id, value.json()) }
        settings.putRaw(key, JSONObject().put("version", 1).put("identity", identity).put("owner", owner)
            .put("generation", generation).put("configuration", configuration).put("assets", values).toString())
    }
    suspend fun ensureCurrent() {
        if (generation(db) != generation) throw IOException("preview_invalidated")
    }
    companion object {
        const val PREFIX = "ai.preview-cache."
        private const val GENERATION = "ai.preview-generation"
        const val LAST_CONFIG = "ai.last-preview-config"

        suspend fun generation(db: PhotoPilotDatabase): Long = AppSettingsRepository(db.settingsKvDao()).rawValue(GENERATION)?.toLongOrNull() ?: 0

        suspend fun invalidateAll(db: PhotoPilotDatabase) {
            val settings = AppSettingsRepository(db.settingsKvDao())
            settings.putRaw(GENERATION, (generation(db) + 1).toString())
            db.settingsKvDao().getByPrefix(PREFIX).forEach { db.settingsKvDao().delete(it) }
        }
        suspend fun deleteModel(db: PhotoPilotDatabase, modelId: String) {
            for (row in db.settingsKvDao().getByPrefix(PREFIX)) {
                val model = runCatching { JSONObject(row.valueJson).getJSONObject("configuration").getString("modelId") }.getOrNull()
                if (model == modelId) db.settingsKvDao().delete(row)
            }
        }
        suspend fun open(db: PhotoPilotDatabase, model: AIModel, collections: List<String>, recursive: Boolean, rules: List<AIRule>, frameId: String?): AIPreviewCheckpoint = db.withTransaction {
            val settings = AppSettingsRepository(db.settingsKvDao())
            val config = JSONObject().put("modelId", model.id).put("modelRevision", model.revision)
                .put("runtime", "${model.runtime}:litert0.17.1:onnx1.23.2:${model.preprocessingVersion}")
                .put("collections", JSONArray(collections.sorted())).put("recursive", recursive)
                .put("rules", JSONArray(rules.map { rule -> JSONObject().put("description", rule.description).put("includes", JSONArray(rule.includes)).put("excludes", JSONArray(rule.excludes)).put("all", rule.requireAll).put("threshold", rule.threshold.toDouble()) }))
            val identity = MessageDigest.getInstance("SHA-256").digest(config.toString().toByteArray()).joinToString("") { "%02x".format(it) }
            val key = PREFIX + (frameId ?: "new")
            val generation = generation(db)
            val cached = runCatching {
                val json = JSONObject(settings.rawValue(key) ?: "{}")
                require(json.getInt("version") == 1 && json.getString("identity") == identity && json.getLong("generation") == generation)
                val values = json.getJSONObject("assets")
                values.keys().asSequence().associateWith { AICachedAsset.parse(values.getJSONObject(it), rules.size) }.toMutableMap()
            }.getOrElse { mutableMapOf() }
            val owner = UUID.randomUUID().toString()
            val values = JSONObject()
            cached.forEach { (id, value) -> values.put(id, value.json()) }
            settings.putRaw(key, JSONObject().put("version", 1).put("identity", identity).put("owner", owner).put("generation", generation).put("configuration", config).put("assets", values).toString())
            if (frameId == null) settings.putRaw(LAST_CONFIG, config.toString())
            AIPreviewCheckpoint(db, key, config, identity, owner, generation, cached)
        }
    }
}
