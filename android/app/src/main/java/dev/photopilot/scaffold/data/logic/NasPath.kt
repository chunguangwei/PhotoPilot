package dev.photopilot.scaffold.data.logic

object NasPath {

    const val MAX_LENGTH = 1024

    fun normalize(input: String): String? {
        if (input.length > MAX_LENGTH) return null
        if (input.any { it.code < 0x20 }) return null
        val segments = ArrayDeque<String>()
        for (segment in input.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeLast()
                else -> segments.addLast(segment)
            }
        }
        return segments.joinToString("/")
    }
}
