package dev.photopilot.scaffold.data.logic

data class FramePlaybackSettings(
    val holdSeconds: Int = 10,
    val transition: String = PlaybackTiming.TRANSITION_CROSSFADE,
    val transitionSeconds: Double = 0.8,
    val fitMode: String = FIT_BLUR,
    val clockEnabled: Boolean = true,
    val clockHour24: Boolean? = null,
    val dateEnabled: Boolean = true,
    val keepAwakeWhenPaused: Boolean = true,
    val restEnabled: Boolean = false,
    val restStartMinute: Int? = null,
    val restEndMinute: Int? = null,
    val brightness: Float? = null,
    val clockPosition: String = POSITION_BOTTOM_LEADING,
    val qualityLevel: String = QUALITY_AUTO,
    val solidColor: String? = null,
    val musicEnabled: Boolean = false,
    val musicSource: String = MUSIC_SOURCE_BUILTIN,
    val musicTrackId: String? = null,
    val musicLocalPath: String? = null,
    val musicLocalTitle: String? = null,
) {

    fun restActiveAt(minuteOfDay: Int, overrideActive: Boolean): Boolean {
        if (overrideActive) return false
        val start = restStartMinute ?: return false
        val end = restEndMinute ?: return false
        return PlaybackTiming.restActive(restEnabled, start, end, minuteOfDay)
    }

    companion object {
        const val FIT_BLUR = "fit_blur"
        const val FIT_COLOR = "fit_color"
        const val FILL_CROP = "fill_crop"

        const val POSITION_TOP_LEADING = "top_leading"
        const val POSITION_TOP_TRAILING = "top_trailing"
        const val POSITION_BOTTOM_LEADING = "bottom_leading"
        const val POSITION_BOTTOM_TRAILING = "bottom_trailing"

        const val QUALITY_AUTO = "auto"
        const val QUALITY_BALANCED = "balanced"
        const val QUALITY_LOW = "low"

        const val SOLID_BLACK = "black"
        const val SOLID_WARM_WHITE = "warm_white"

        const val MUSIC_SOURCE_BUILTIN = "builtin"
        const val MUSIC_SOURCE_LOCAL = "local"

        fun parse(json: String?): FramePlaybackSettings {
            val root = JsonMini.parseObject(json)
            val clock = root["clock"] as? Map<*, *>
            val date = root["date"] as? Map<*, *>
            val rest = root["rest"] as? Map<*, *>
            val music = root["music"] as? Map<*, *>
            return FramePlaybackSettings(
                holdSeconds = (root["holdSeconds"] as? Double)?.toInt() ?: 10,
                transition = root["transition"] as? String ?: PlaybackTiming.TRANSITION_CROSSFADE,
                transitionSeconds = root["transitionSeconds"] as? Double ?: 0.8,
                fitMode = root["fitMode"] as? String ?: FIT_BLUR,
                clockEnabled = clock?.get("enabled") as? Boolean ?: true,
                clockHour24 = clock?.get("hour24") as? Boolean,
                clockPosition = clock?.get("position") as? String ?: POSITION_BOTTOM_LEADING,
                dateEnabled = date?.get("enabled") as? Boolean ?: true,
                keepAwakeWhenPaused = root["keepAwakeWhenPaused"] as? Boolean ?: true,
                restEnabled = rest?.get("enabled") as? Boolean ?: false,
                restStartMinute = (rest?.get("startMinute") as? Double)?.toInt(),
                restEndMinute = (rest?.get("endMinute") as? Double)?.toInt(),
                brightness = (root["brightness"] as? Double)?.toFloat(),
                qualityLevel = root["qualityLevel"] as? String ?: QUALITY_AUTO,
                solidColor = root["solidColor"] as? String,
                musicEnabled = music?.get("enabled") as? Boolean ?: false,
                musicSource = music?.get("source") as? String ?: MUSIC_SOURCE_BUILTIN,
                musicTrackId = music?.get("trackId") as? String,
                musicLocalPath = music?.get("localPath") as? String,
                musicLocalTitle = music?.get("localTitle") as? String,
            )
        }
    }
}
