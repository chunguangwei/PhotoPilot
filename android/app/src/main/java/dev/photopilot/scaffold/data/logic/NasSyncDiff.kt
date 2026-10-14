package dev.photopilot.scaffold.data.logic

object NasSyncDiff {

    enum class DiffAction(val wire: String) {
        ADDED("added"),
        CHANGED("changed"),
        UNCHANGED("unchanged"),
        MARK_SOURCE_REMOVED("mark_source_removed"),
        DELETION_CANDIDATE("deletion_candidate"),
    }

    fun diff(
        old: Map<String, String?>,
        new: Map<String, String?>,
        mirrorDelete: Boolean,
    ): Map<String, DiffAction> {
        val out = LinkedHashMap<String, DiffAction>()
        for ((key, revision) in new) {
            out[key] = when {
                key !in old -> DiffAction.ADDED
                old[key] == revision -> DiffAction.UNCHANGED
                else -> DiffAction.CHANGED
            }
        }
        for (key in old.keys) {
            if (key !in new) {
                out[key] = if (mirrorDelete) {
                    DiffAction.DELETION_CANDIDATE
                } else {
                    DiffAction.MARK_SOURCE_REMOVED
                }
            }
        }
        return out
    }
}
