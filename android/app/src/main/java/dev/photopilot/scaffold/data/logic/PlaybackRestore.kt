package dev.photopilot.scaffold.data.logic

object PlaybackRestore {
    data class Result(val queue: List<String>, val index: Int, val remainingHoldMs: Long?)

    fun resolve(live: List<String>, saved: List<String>, savedIndex: Int, remaining: Long): Result {
        val allowed = live.toSet()
        val queue = (saved.filter { it in allowed } + live).distinct()
        val anchor = saved.getOrNull(savedIndex)
        val index = queue.indexOf(anchor).takeIf { it >= 0 } ?: 0
        return Result(queue, index, remaining.takeIf { it > 0L && anchor in allowed })
    }
}
