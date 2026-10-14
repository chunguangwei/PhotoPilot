package dev.photopilot.scaffold.data.logic

object StorageThreshold {

    const val ONE_GB_BYTES = 1_073_741_824L
    const val FREE_FRACTION_PERCENT = 5L

    fun thresholdBytes(totalBytes: Long): Long =
        maxOf(ONE_GB_BYTES, totalBytes * FREE_FRACTION_PERCENT / 100L)

    fun isLow(freeBytes: Long, totalBytes: Long): Boolean =
        totalBytes > 0L && freeBytes < thresholdBytes(totalBytes)
}
