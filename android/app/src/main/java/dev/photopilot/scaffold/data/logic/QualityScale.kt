package dev.photopilot.scaffold.data.logic

object QualityScale {

    fun factor(qualityLevel: String?): Double = when (qualityLevel) {
        FramePlaybackSettings.QUALITY_BALANCED -> 0.75
        FramePlaybackSettings.QUALITY_LOW -> 0.5
        else -> 1.0
    }

    fun scale(dimensionPx: Int, qualityLevel: String?): Int =
        (dimensionPx * factor(qualityLevel)).toInt().coerceAtLeast(1)
}
