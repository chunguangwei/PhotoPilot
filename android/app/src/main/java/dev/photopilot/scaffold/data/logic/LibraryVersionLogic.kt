package dev.photopilot.scaffold.data.logic

object LibraryVersionLogic {

    const val KEY = "library_version"

    fun parseStored(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        val unquoted = if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text.substring(1, text.length - 1)
        } else {
            text
        }
        return unquoted.toLongOrNull()
    }

    fun next(current: Long?): Long {
        val base = current ?: 0L
        require(base >= 0L) { "library_version must be non-negative" }
        return base + 1L
    }

    fun nextFromStored(raw: String?): Long = next(parseStored(raw))

    fun storedJson(version: Long): String = version.toString()
}
