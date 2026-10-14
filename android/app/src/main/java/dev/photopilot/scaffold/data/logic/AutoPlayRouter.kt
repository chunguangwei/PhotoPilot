package dev.photopilot.scaffold.data.logic

object AutoPlayRouter {

    fun decideFrame(
        coldStart: Boolean,
        onboardingCompleted: Boolean,
        hasPlayedOnce: Boolean,
        autoPlayEnabled: Boolean,
        lastFrameId: String?,
    ): String? =
        if (coldStart && onboardingCompleted && hasPlayedOnce && autoPlayEnabled) {
            lastFrameId
        } else {
            null
        }
}
