package dev.photopilot.scaffold.data.net

import dev.photopilot.scaffold.data.logic.JsonMini

class TofuPinStore(
    private val getValue: suspend (String) -> String?,
    private val putValue: suspend (String, String) -> Unit,
) {

    data class Pin(val fingerprint: String, val firstSeenAt: Long, val confirmedAt: Long)

    suspend fun pinFor(hostPort: String): Pin? = pins()[hostPort]

    suspend fun pins(): Map<String, Pin> {
        val raw = getValue(KEY_TOFU_PINS) ?: return emptyMap()
        val root = JsonMini.parseObject(raw)
        return root.mapNotNull { (key, value) ->
            val map = value as? Map<*, *> ?: return@mapNotNull null
            val fingerprint = map["fingerprint"] as? String ?: return@mapNotNull null
            val firstSeen = (map["firstSeenAt"] as? Double)?.toLong() ?: return@mapNotNull null
            val confirmed = (map["confirmedAt"] as? Double)?.toLong() ?: return@mapNotNull null
            key to Pin(fingerprint, firstSeen, confirmed)
        }.toMap()
    }

    suspend fun savePin(hostPort: String, pin: Pin) {
        val current = pins().toMutableMap()
        current[hostPort] = pin
        val root = current.mapValues { (_, pin) ->
            mapOf(
                "fingerprint" to pin.fingerprint,
                "firstSeenAt" to pin.firstSeenAt.toDouble(),
                "confirmedAt" to pin.confirmedAt.toDouble(),
            )
        }
        putValue(KEY_TOFU_PINS, JsonMini.toJson(root))
    }

    suspend fun deletePin(hostPort: String) {
        val current = pins().toMutableMap()
        if (current.remove(hostPort) != null) {
            putValue(KEY_TOFU_PINS, JsonMini.toJson(current.mapValues { (_, pin) ->
                mapOf(
                    "fingerprint" to pin.fingerprint,
                    "firstSeenAt" to pin.firstSeenAt.toDouble(),
                    "confirmedAt" to pin.confirmedAt.toDouble(),
                )
            }))
        }
    }

    suspend fun cleartextHosts(): Set<String> {
        val raw = getValue(KEY_CLEARTEXT_HOSTS) ?: return emptySet()
        val parsed = runCatching { JsonMini.parseObject("{\"a\":$raw}")["a"] as? List<*> }.getOrNull()
        return parsed?.mapNotNull { it as? String }?.toSet() ?: emptySet()
    }

    suspend fun addCleartextHost(hostPort: String) {
        val current = cleartextHosts() + hostPort
        putValue(KEY_CLEARTEXT_HOSTS, JsonMini.toJson(current.sorted()))
    }

    companion object {
        const val KEY_TOFU_PINS = "nas.tofu_pins"
        const val KEY_CLEARTEXT_HOSTS = "nas.cleartext_hosts"
    }
}
