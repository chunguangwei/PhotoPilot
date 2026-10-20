package dev.photopilot.scaffold.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import dev.photopilot.scaffold.data.logic.SystemFeatureGates
import java.util.Locale

object UiPreferenceOverrides {
    private const val STORE = "photopilot.ui.preference.mirror"
    private const val APPEARANCE = "appearance"
    private const val LANGUAGE = "language"

    fun followsSystemAppearance(context: Context): Boolean =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getString(APPEARANCE, "system") == "system"

    fun wrap(base: Context): Context {
        val preferences = base.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val appearance = preferences.getString(APPEARANCE, "system")
        val language = preferences.getString(LANGUAGE, "system")
        val configuration = Configuration(base.resources.configuration)
        var changed = false
        if (!SystemFeatureGates.usesNativeAppearance(Build.VERSION.SDK_INT)) {
            val mode = when (appearance) {
                "light" -> Configuration.UI_MODE_NIGHT_NO
                "dark" -> Configuration.UI_MODE_NIGHT_YES
                else -> null
            }
            if (mode != null) {
                configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
                changed = true
            }
        }
        if (!SystemFeatureGates.usesNativeLanguage(Build.VERSION.SDK_INT)) {
            val locale = when (language) {
                "zh" -> Locale.SIMPLIFIED_CHINESE
                "en" -> Locale.ENGLISH
                else -> null
            }
            if (locale != null) {
                configuration.setLocales(LocaleList(locale))
                configuration.setLayoutDirection(locale)
                changed = true
            }
        }
        return if (changed) base.createConfigurationContext(configuration) else base
    }

    fun update(context: Context, appearance: String? = null, language: String? = null): Boolean {
        val preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val previousAppearance = preferences.getString(APPEARANCE, "system") ?: "system"
        val previousLanguage = preferences.getString(LANGUAGE, "system") ?: "system"
        val nextAppearance = appearance?.takeIf { it in setOf("system", "light", "dark") } ?: previousAppearance
        val nextLanguage = language?.takeIf { it in setOf("system", "zh", "en") } ?: previousLanguage
        if (nextAppearance == previousAppearance && nextLanguage == previousLanguage) return false
        check(preferences.edit().putString(APPEARANCE, nextAppearance).putString(LANGUAGE, nextLanguage).commit())
        return SystemFeatureGates.needsPreferenceRecreation(
            Build.VERSION.SDK_INT, previousAppearance, nextAppearance, previousLanguage, nextLanguage,
        )
    }
}
