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

internal class AIPreviewCheckpoint private constructor(
    private val db: PhotoPilotDatabase,
    private val key: String,
    private val configuration: JSONObject,
    private val identity: String,
    private val owner: String,
    private val generation: Long,
    val assets: MutableMap<String, AICachedAsset>,
    val failures: MutableMap<String, String>,
) {
    private suspend fun checkOwner() {
        if (generation(db) != generation) throw IOException("preview_invalidated")
        val current = db.settingsKvDao().get(key)?.valueJson?.let(::JSONObject)
        if (current?.optString("owner") != owner) throw IOException("preview_superseded")
    }

    suspend fun save() = db.withTransaction {
        checkOwner()
        writeHeader(db, key, configuration, identity, owner, generation)
    }

    suspend fun saveAsset(id: String) = db.withTransaction {
        checkOwner()
        writeAsset(db, key, identity, generation, id, assets[id], failures[id])
    }

    suspend fun prune(fingerprints: Map<String, String>) = db.withTransaction {
        checkOwner()
        val obsolete = assets.filter { (id, asset) -> fingerprints[id] != asset.contentHash }.keys + failures.filter { (id, fingerprint) -> fingerprints[id] != fingerprint }.keys
        for (id in obsolete) {
            assets.remove(id)
            failures.remove(id)
            db.settingsKvDao().deleteKey(assetKey(key, id))
        }
    }

    suspend fun ensureCurrent() { checkOwner() }

    companion object {
        const val PREFIX = "ai.preview-cache."
        private const val ITEM_PREFIX = "ai.preview-item."
        private const val GENERATION = "ai.preview-generation"
        const val LAST_CONFIG = "ai.last-preview-config"
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        internal fun itemsPrefix(headerKey: String) = ITEM_PREFIX + digest(headerKey) + "."
        private fun assetKey(headerKey: String, assetId: String) = itemsPrefix(headerKey) + digest(assetId)
        private fun upper(prefix: String) = prefix + "\uffff"

        suspend fun generation(db: PhotoPilotDatabase): Long = AppSettingsRepository(db.settingsKvDao()).rawValue(GENERATION)?.toLongOrNull() ?: 0

        suspend fun invalidateAll(db: PhotoPilotDatabase) {
            val settings = AppSettingsRepository(db.settingsKvDao())
            settings.putRaw(GENERATION, (generation(db) + 1).toString())
            db.settingsKvDao().deleteRange(PREFIX, upper(PREFIX))
            db.settingsKvDao().deleteRange(ITEM_PREFIX, upper(ITEM_PREFIX))
        }

        suspend fun removeAssets(db: PhotoPilotDatabase, ids: Set<String>) = db.withTransaction {
            val settings = AppSettingsRepository(db.settingsKvDao())
            for (row in db.settingsKvDao().getRange(PREFIX, upper(PREFIX))) {
                val header = runCatching { JSONObject(row.valueJson) }.getOrNull() ?: continue
                header.put("owner", UUID.randomUUID().toString())
                for (id in ids) {
                    header.optJSONObject("assets")?.remove(id)
                    header.optJSONObject("failures")?.remove(id)
                    db.settingsKvDao().deleteKey(assetKey(row.key, id))
                }
                settings.putRaw(row.key, header.toString())
            }
        }

        suspend fun delete(db: PhotoPilotDatabase, checkpointId: String) = db.withTransaction {
            val key = PREFIX + checkpointId
            val prefix = itemsPrefix(key)
            db.settingsKvDao().deleteRange(prefix, upper(prefix))
            db.settingsKvDao().deleteKey(key)
        }

        suspend fun deleteModel(db: PhotoPilotDatabase, modelId: String) {
            val headers = db.settingsKvDao().getRange(PREFIX, upper(PREFIX))
            for (row in headers) {
                val model = runCatching { JSONObject(row.valueJson).getJSONObject("configuration").getString("modelId") }.getOrNull()
                if (model == modelId) delete(db, row.key.removePrefix(PREFIX))
            }
        }

        private suspend fun writeHeader(db: PhotoPilotDatabase, key: String, config: JSONObject, identity: String, owner: String, generation: Long) {
            val json = JSONObject().put("version", 2).put("identity", identity).put("owner", owner)
                .put("generation", generation).put("configuration", config).put("assets", JSONObject())
            AppSettingsRepository(db.settingsKvDao()).putRaw(key, json.toString())
        }

        private suspend fun writeAsset(db: PhotoPilotDatabase, headerKey: String, identity: String, generation: Long, id: String, asset: AICachedAsset?, failure: String?) {
            val key = assetKey(headerKey, id)
            if (asset == null && failure == null) {
                db.settingsKvDao().deleteKey(key)
                return
            }
            val json = JSONObject().put("id", id).put("identity", identity).put("generation", generation)
            if (asset != null) json.put("asset", asset.json()) else json.put("failure", failure)
            AppSettingsRepository(db.settingsKvDao()).putRaw(key, json.toString())
        }

        suspend fun open(db: PhotoPilotDatabase, model: AIModel, collections: List<String>, recursive: Boolean, rules: List<AIRule>, frameId: String?, checkpointId: String? = null): AIPreviewCheckpoint = db.withTransaction {
            val settings = AppSettingsRepository(db.settingsKvDao())
            val config = JSONObject().put("modelId", model.id).put("modelRevision", model.revision)
                .put("runtime", "${model.runtime}:litert0.17.1:onnx1.23.2:${model.preprocessingVersion}")
                .put("collections", JSONArray(collections.sorted())).put("recursive", recursive)
                .put("rules", JSONArray(rules.map { rule -> JSONObject().put("description", rule.description).put("includes", JSONArray(rule.includes)).put("excludes", JSONArray(rule.excludes)).put("all", rule.requireAll).put("threshold", rule.threshold.toDouble()) }))
            val identity = digest(config.toString())
            val key = PREFIX + (checkpointId ?: frameId ?: "new")
            val prefix = itemsPrefix(key)
            val generation = generation(db)
            val header = runCatching { JSONObject(settings.rawValue(key) ?: "{}") }.getOrDefault(JSONObject())
            val compatible = header.optInt("version") in listOf(1, 2) && header.optString("identity") == identity && header.optLong("generation", -1) == generation
            val cached = mutableMapOf<String, AICachedAsset>()
            val failures = mutableMapOf<String, String>()
            if (!compatible) {
                db.settingsKvDao().deleteRange(prefix, upper(prefix))
            } else if (header.optInt("version") == 1) {
                val values = header.optJSONObject("assets") ?: JSONObject()
                for (id in values.keys()) {
                    runCatching { AICachedAsset.parse(values.getJSONObject(id), rules.size) }.getOrNull()?.let { cached[id] = it }
                }
                val failed = header.optJSONObject("failures") ?: JSONObject()
                for (id in failed.keys()) failures[id] = failed.getString(id)
                for (id in cached.keys + failures.keys) writeAsset(db, key, identity, generation, id, cached[id], failures[id])
            } else {
                for (row in db.settingsKvDao().getRange(prefix, upper(prefix))) {
                    val parsed = runCatching {
                        val json = JSONObject(row.valueJson)
                        require(json.getString("identity") == identity && json.getLong("generation") == generation)
                        val id = json.getString("id")
                        require(row.key == assetKey(key, id))
                        if (json.has("asset")) cached[id] = AICachedAsset.parse(json.getJSONObject("asset"), rules.size)
                        else failures[id] = json.getString("failure")
                    }.isSuccess
                    if (!parsed) db.settingsKvDao().delete(row)
                }
            }
            val owner = UUID.randomUUID().toString()
            writeHeader(db, key, config, identity, owner, generation)
            if (frameId == null) settings.putRaw(LAST_CONFIG, config.toString())
            AIPreviewCheckpoint(db, key, config, identity, owner, generation, cached, failures)
        }
    }
}
