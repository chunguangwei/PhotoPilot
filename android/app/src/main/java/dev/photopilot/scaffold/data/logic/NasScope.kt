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

    fun sourceScope(settingsJson: String?, scopesNewestFirst: List<String>): String? {
        val settings = JsonMini.parseObject(settingsJson)
        if (settings.containsKey("directories")) {
            val directories = settings["directories"] as? List<*> ?: return null
            if (directories.any { it !is String }) return null
            return normalizeDirectories(directories.filterIsInstance<String>())?.let(::scopeJson)
        }
        return latestSourceScope(scopesNewestFirst)
    }

    fun latestSourceScope(scopesNewestFirst: List<String>): String? =
        scopesNewestFirst.firstOrNull { JsonMini.parseObject(it)["frameId"] == null }

    fun scopeJson(directories: List<String>): String =
        JsonMini.toJson(mapOf("directories" to directories))
}
