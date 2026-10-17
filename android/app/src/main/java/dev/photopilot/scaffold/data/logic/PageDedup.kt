package dev.photopilot.scaffold.data.logic

object PageDedup {

    fun <T> filterNew(existing: List<T>, page: List<T>, key: (T) -> String): List<T> {
        val known = existing.mapTo(HashSet()) { key(it) }
        return page.filter { key(it) !in known }
    }
}
