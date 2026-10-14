package dev.photopilot.scaffold.data.logic

class ResolutionCache {

    data class Key(
        val frameId: String,
        val selectionVersion: Long,
        val sourceEpochsKey: String,
        val libraryVersion: Long,
    )

    private val entries = mutableMapOf<Key, SelectionResolution>()

    @Synchronized
    fun get(key: Key): SelectionResolution? = entries[key]

    @Synchronized
    fun put(key: Key, resolution: SelectionResolution) {
        entries[key] = resolution
    }

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        fun sourceEpochsKey(epochs: Map<String, Long>): String =
            epochs.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
    }
}
