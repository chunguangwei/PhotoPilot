package dev.photopilot.scaffold.data.logic

object ByteSizeFormat {

    const val KB_BYTES = 1_024L
    const val MB_BYTES = 1_048_576L
    const val GB_BYTES = 1_073_741_824L

    enum class Unit { KB, MB, GB }

    fun unitOf(bytes: Long): Unit = when {
        bytes < MB_BYTES -> Unit.KB
        bytes < GB_BYTES -> Unit.MB
        else -> Unit.GB
    }

    fun valueOf(bytes: Long): Long = when (unitOf(bytes)) {
        Unit.KB -> maxOf(1L, (bytes + KB_BYTES - 1L) / KB_BYTES)
        Unit.MB -> bytes / MB_BYTES
        Unit.GB -> bytes / GB_BYTES
    }
}
