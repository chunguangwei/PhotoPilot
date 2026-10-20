package dev.photopilot.scaffold.data.logic

object SystemFeatureGates {

    const val APPEARANCE_MIN_SDK = 29
    const val LANGUAGE_MIN_SDK = 29

    fun canApplyAppearance(sdkInt: Int): Boolean = sdkInt >= APPEARANCE_MIN_SDK

    fun canApplyLanguage(sdkInt: Int): Boolean = sdkInt >= LANGUAGE_MIN_SDK

    fun usesNativeAppearance(sdkInt: Int): Boolean = sdkInt >= 31

    fun usesNativeLanguage(sdkInt: Int): Boolean = sdkInt >= 33

    fun needsPreferenceRecreation(
        sdkInt: Int,
        previousAppearance: String,
        appearance: String,
        previousLanguage: String,
        language: String,
    ): Boolean = (!usesNativeAppearance(sdkInt) && previousAppearance != appearance) ||
        (!usesNativeLanguage(sdkInt) && previousLanguage != language)
}
