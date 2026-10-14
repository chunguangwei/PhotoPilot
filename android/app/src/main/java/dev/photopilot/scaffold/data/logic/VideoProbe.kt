package dev.photopilot.scaffold.data.logic

object VideoProbe {

    const val VIDEO_MAX_DURATION_MS = 600_000L

    val VIDEO_EXTENSIONS = setOf("mp4", "mov")
    val RAW_EXTENSIONS = setOf("cr2", "nef", "dng", "arw", "raf", "orf", "rw2")

    fun rotationToExif(degrees: Long): Long = when (degrees) {
        0L -> 1L
        90L -> 6L
        180L -> 3L
        270L -> 8L
        else -> 1L
    }

    fun isVideoExtension(extension: String): Boolean =
        extension.lowercase() in VIDEO_EXTENSIONS

    fun isRawExtension(extension: String): Boolean =
        extension.lowercase() in RAW_EXTENSIONS

    fun isOverLimit(durationMs: Long?): Boolean =
        durationMs != null && durationMs > VIDEO_MAX_DURATION_MS

    fun parseContainerDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(value)
                ?.time
        }.getOrNull()
    }
}
