package dev.photopilot.scaffold.data.imports

import dev.photopilot.scaffold.data.logic.JsonMini

object NasEnumCursor {

    data class State(val dirs: List<String>, val skip: Int)

    fun parse(cursor: String?, initialDirs: List<String>): State {
        if (cursor == null) {
            return State(dirs = initialDirs, skip = 0)
        }
        val state = JsonMini.parseObject(cursor.removePrefix(WebDavSourceAdapter.CURSOR_PREFIX))
        val dirs = (state["dirs"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val skip = (state["skip"] as? Double)?.toInt() ?: 0
        return State(dirs = dirs, skip = skip)
    }

    fun format(state: State): String =
        WebDavSourceAdapter.CURSOR_PREFIX + JsonMini.toJson(
            mapOf("dirs" to state.dirs, "skip" to state.skip.toDouble()),
        )
}
