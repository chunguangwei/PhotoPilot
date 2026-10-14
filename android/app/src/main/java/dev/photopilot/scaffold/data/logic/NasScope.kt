package dev.photopilot.scaffold.data.logic

object NasScope {

    fun normalizeDirectories(input: List<String>): List<String>? {
        if (input.isEmpty()) return null
        val normalized = input.map { NasPath.normalize(it) ?: return null }
        val sorted = normalized.toSet().sorted()
        val out = mutableListOf<String>()
        for (dir in sorted) {
            val covered = out.any { ancestor ->
                ancestor.isEmpty() || dir == ancestor || dir.startsWith("$ancestor/")
            }
            if (!covered) {
                out.add(dir)
            }
        }
        if (out.isEmpty()) return null
        return out
    }

    fun scopeJson(directories: List<String>): String =
        JsonMini.toJson(mapOf("directories" to directories))
}
