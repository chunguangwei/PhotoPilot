package dev.photopilot.scaffold.data.aiframes

object AISourceScope {
    fun expand(roots: List<String>, recursive: Boolean, parents: Map<String, String?>): Set<String> {
        val result = roots.filter { it in parents }.toMutableSet()
        if (!recursive) return result
        val children = parents.entries.groupBy({ it.value }, { it.key })
        val queue = ArrayDeque(result)
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            for (child in children[parent].orEmpty()) {
                if (result.add(child)) queue.add(child)
            }
        }
        return result
    }
}
