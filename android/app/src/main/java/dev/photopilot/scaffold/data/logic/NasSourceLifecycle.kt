package dev.photopilot.scaffold.data.logic

object NasSourceLifecycle {

    const val STATUS_ACTIVE = "active"
    const val STATUS_DISCONNECTED = "disconnected"

    fun shouldReactivate(currentStatus: String): Boolean = currentStatus == STATUS_DISCONNECTED

    fun recursiveFromSettings(settingsJson: String?): Boolean =
        JsonMini.parseObject(settingsJson)["recursive"] as? Boolean ?: false

    fun mirrorDeleteFromSettings(settingsJson: String?): Boolean =
        JsonMini.parseObject(settingsJson)["mirrorDelete"] as? Boolean ?: false

    fun settingsJsonWithRecursive(recursive: Boolean): String =
        JsonMini.toJson(mapOf("recursive" to recursive))

    fun mergeSettings(
        settingsJson: String?,
        recursive: Boolean? = null,
        mirrorDelete: Boolean? = null,
    ): String {
        val root = JsonMini.parseObject(settingsJson).toMutableMap()
        recursive?.let { root["recursive"] = it }
        mirrorDelete?.let { root["mirrorDelete"] = it }
        return JsonMini.toJson(root)
    }

    fun pendingDeleteKey(sourceId: String): String = "nas.pending_delete:$sourceId"
}
