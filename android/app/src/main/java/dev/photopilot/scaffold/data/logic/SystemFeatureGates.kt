package dev.photopilot.scaffold.data.logic

object SystemFeatureGates {

    const val APPEARANCE_MIN_SDK = 31
    const val LANGUAGE_MIN_SDK = 33

    fun canApplyAppearance(sdkInt: Int): Boolean = sdkInt >= APPEARANCE_MIN_SDK

    fun canApplyLanguage(sdkInt: Int): Boolean = sdkInt >= LANGUAGE_MIN_SDK
}
