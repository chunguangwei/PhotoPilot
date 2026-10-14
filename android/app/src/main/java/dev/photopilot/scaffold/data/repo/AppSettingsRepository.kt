package dev.photopilot.scaffold.data.repo

import dev.photopilot.scaffold.data.db.SettingsKvDao
import dev.photopilot.scaffold.data.db.SettingsKvEntity

class AppSettingsRepository(
    private val dao: SettingsKvDao,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun isOnboardingCompleted(): Boolean =
        dao.get(KEY_ONBOARDING_COMPLETED)?.valueJson == VALUE_TRUE

    suspend fun hasPlayedOnce(): Boolean =
        dao.get(KEY_PLAYED_ONCE)?.valueJson == VALUE_TRUE

    suspend fun isAutoPlayEnabled(): Boolean =
        dao.get(KEY_AUTO_PLAY)?.valueJson != VALUE_FALSE

    suspend fun lastFrameId(): String? =
        dao.get(KEY_LAST_FRAME_ID)?.valueJson?.takeIf { it.isNotEmpty() }

    suspend fun markPlayedOnce(frameId: String) {
        put(KEY_PLAYED_ONCE, VALUE_TRUE)
        put(KEY_LAST_FRAME_ID, frameId)
    }

    suspend fun appearance(): String = dao.get(KEY_UI_APPEARANCE)?.valueJson ?: VALUE_SYSTEM

    suspend fun setAppearance(value: String) = put(KEY_UI_APPEARANCE, value)

    suspend fun language(): String = dao.get(KEY_UI_LANGUAGE)?.valueJson ?: VALUE_SYSTEM

    suspend fun setLanguage(value: String) = put(KEY_UI_LANGUAGE, value)

    suspend fun isWifiOnly(): Boolean = dao.get(KEY_NETWORK_WIFI_ONLY)?.valueJson == VALUE_TRUE

    suspend fun setWifiOnly(enabled: Boolean) =
        put(KEY_NETWORK_WIFI_ONLY, if (enabled) VALUE_TRUE else VALUE_FALSE)

    suspend fun isShareStatisticsEnabled(): Boolean =
        dao.get(KEY_PRIVACY_SHARE_STATISTICS)?.valueJson == VALUE_TRUE

    suspend fun setShareStatisticsEnabled(enabled: Boolean) =
        put(KEY_PRIVACY_SHARE_STATISTICS, if (enabled) VALUE_TRUE else VALUE_FALSE)

    suspend fun setAutoPlayEnabled(enabled: Boolean) =
        put(KEY_AUTO_PLAY, if (enabled) VALUE_TRUE else VALUE_FALSE)

    suspend fun rawValue(key: String): String? = dao.get(key)?.valueJson

    suspend fun putRaw(key: String, value: String) = put(key, value)

    suspend fun setOnboardingCompleted(completed: Boolean) {
        put(KEY_ONBOARDING_COMPLETED, if (completed) VALUE_TRUE else VALUE_FALSE)
    }

    private suspend fun put(key: String, value: String) {
        val now = nowMillis()
        dao.upsert(
            SettingsKvEntity(
                key = key,
                valueJson = value,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    companion object {
        const val KEY_ONBOARDING_COMPLETED = "onboarding.completed"
        const val KEY_PLAYED_ONCE = "playback.has_played_once"
        const val KEY_AUTO_PLAY = "playback.auto_play"
        const val KEY_LAST_FRAME_ID = "playback.last_frame_id"
        const val KEY_UI_APPEARANCE = "ui.appearance"
        const val KEY_UI_LANGUAGE = "ui.language"
        const val KEY_NETWORK_WIFI_ONLY = "network.wifi_only"
        const val KEY_PRIVACY_SHARE_STATISTICS = "privacy.share_statistics"
        const val VALUE_TRUE = "true"
        const val VALUE_FALSE = "false"
        const val VALUE_SYSTEM = "system"
        const val VALUE_LIGHT = "light"
        const val VALUE_DARK = "dark"
        const val VALUE_ZH = "zh"
        const val VALUE_EN = "en"
    }
}
