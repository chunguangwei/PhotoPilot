package dev.photopilot.scaffold.data.aiframes

import org.json.JSONArray
import org.json.JSONObject

data class AITaskRecord(
    val id: String,
    val kind: String,
    val modelId: String,
    val modelRevision: String,
    val status: String = "queued",
    val userPaused: Boolean = false,
    val source: String = "auto",
    val allowMetered: Boolean = false,
    val done: Long = 0,
    val total: Long = 0,
    val generation: Long = 1,
    val collections: List<String> = emptyList(),
    val recursive: Boolean = false,
    val rules: List<AIRule> = emptyList(),
    val frameId: String? = null,
    val frameRevision: Long = 0,
    val categoryIds: List<String> = emptyList(),
    val frameIds: List<String> = emptyList(),
    val draft: Map<String, String> = emptyMap(),
    val reason: String? = null,
) {
    val mayRecover get() = !userPaused && status in listOf("queued", "running", "system_paused", "waiting")
    fun json(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("modelId", modelId).put("modelRevision", modelRevision)
        .put("status", status).put("userPaused", userPaused).put("source", source).put("allowMetered", allowMetered)
        .put("done", done).put("total", total).put("generation", generation).put("collections", JSONArray(collections))
        .put("recursive", recursive).put("rules", JSONArray(rules.map(::ruleJson))).put("frameId", frameId).put("frameRevision", frameRevision)
        .put("categoryIds", JSONArray(categoryIds)).put("frameIds", JSONArray(frameIds)).put("draft", JSONObject(draft)).put("reason", reason)
    companion object {
        fun ruleJson(rule: AIRule) = JSONObject().put("description", rule.description).put("includes", JSONArray(rule.includes))
            .put("excludes", JSONArray(rule.excludes)).put("requireAll", rule.requireAll).put("threshold", rule.threshold.toDouble())
        fun parse(json: JSONObject): AITaskRecord {
            fun strings(key: String) = json.optJSONArray(key)?.let(AIConfiguration::strings).orEmpty()
            val array = json.optJSONArray("rules") ?: JSONArray()
            val rules = (0 until array.length()).map { index ->
                val rule = array.getJSONObject(index)
                AIRule(rule.getString("description"), AIConfiguration.strings(rule.getJSONArray("includes")), AIConfiguration.strings(rule.getJSONArray("excludes")), rule.getBoolean("requireAll"), rule.getDouble("threshold").toFloat())
            }
            val draft = json.optJSONObject("draft") ?: JSONObject()
            return AITaskRecord(
                id = json.getString("id"),
                kind = json.getString("kind"),
                modelId = json.getString("modelId"),
                modelRevision = json.getString("modelRevision"),
                status = json.optString("status", "queued"),
                userPaused = json.optBoolean("userPaused"),
                source = json.optString("source", "auto"),
                allowMetered = json.optBoolean("allowMetered"),
                done = json.optLong("done"),
                total = json.optLong("total"),
                generation = json.optLong("generation", 1),
                collections = strings("collections"),
                recursive = json.optBoolean("recursive"),
                rules = rules,
                frameId = json.optString("frameId").takeIf { it.isNotEmpty() },
                frameRevision = json.optLong("frameRevision"),
                categoryIds = strings("categoryIds"),
                frameIds = strings("frameIds"),
                draft = draft.keys().asSequence().associateWith { draft.getString(it) },
                reason = json.optString("reason").takeIf { it.isNotEmpty() },
            )
        }
    }
}
