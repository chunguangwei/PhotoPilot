package dev.photopilot.scaffold.data.logic

object PlaybackSettingsEdit {

    data class Draft(
        val holdSeconds: Int,
        val transition: String,
        val transitionSeconds: Double,
        val fitMode: String,
        val clockEnabled: Boolean,
        val clockPosition: String,
        val clockHour24: Boolean?,
        val dateEnabled: Boolean,
        val keepAwakeWhenPaused: Boolean,
        val restEnabled: Boolean,
        val restStartMinute: Int?,
        val restEndMinute: Int?,
        val brightness: Double?,
        val qualityLevel: String,
        val solidColor: String? = null,
        val musicEnabled: Boolean = false,
        val musicSource: String = FramePlaybackSettings.MUSIC_SOURCE_BUILTIN,
        val musicTrackId: String? = null,
        val musicLocalPath: String? = null,
        val musicLocalTitle: String? = null,
    )

    enum class Error {
        HOLD_INVALID,
        HOLD_OUT_OF_RANGE,
        TRANSITION_SECONDS_INVALID,
        REST_WINDOW_INVALID,
    }

    fun validate(draft: Draft): Error? {
        if (!PlaybackTiming.isValidHoldSeconds(draft.holdSeconds.toDouble())) {
            return if (draft.holdSeconds < PlaybackTiming.HOLD_MIN_SECONDS ||
                draft.holdSeconds > PlaybackTiming.HOLD_MAX_SECONDS
            ) {
                Error.HOLD_OUT_OF_RANGE
            } else {
                Error.HOLD_INVALID
            }
        }
        if (draft.transition != PlaybackTiming.TRANSITION_NONE &&
            !PlaybackTiming.isValidTransitionSeconds(draft.transitionSeconds)
        ) {
            return Error.TRANSITION_SECONDS_INVALID
        }
        if (draft.restEnabled) {
            val start = draft.restStartMinute
            val end = draft.restEndMinute
            if (start == null || end == null || start == end) {
                return Error.REST_WINDOW_INVALID
            }
        }
        return null
    }

    fun merge(originalJson: String?, draft: Draft): String {
        val root = JsonMini.parseObject(originalJson).toMutableMap()
        root["holdSeconds"] = draft.holdSeconds.toDouble()
        root["transition"] = draft.transition
        root["transitionSeconds"] = draft.transitionSeconds
        root["fitMode"] = draft.fitMode
        val clock = (root["clock"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
        clock["enabled"] = draft.clockEnabled
        clock["position"] = draft.clockPosition
        clock["hour24"] = draft.clockHour24
        root["clock"] = clock
        val date = (root["date"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
        date["enabled"] = draft.dateEnabled
        root["date"] = date
        root["keepAwakeWhenPaused"] = draft.keepAwakeWhenPaused
        val rest = (root["rest"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
        rest["enabled"] = draft.restEnabled
        rest["startMinute"] = draft.restStartMinute?.toDouble()
        rest["endMinute"] = draft.restEndMinute?.toDouble()
        root["rest"] = rest
        root["brightness"] = draft.brightness
        root["qualityLevel"] = draft.qualityLevel
        root["solidColor"] = draft.solidColor
        val music = (root["music"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
        music["enabled"] = draft.musicEnabled
        music["source"] = draft.musicSource
        music["trackId"] = draft.musicTrackId
        music["localPath"] = draft.musicLocalPath
        music["localTitle"] = draft.musicLocalTitle
        root["music"] = music
        return JsonMini.toJson(root)
    }
}
