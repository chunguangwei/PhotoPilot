package dev.photopilot.scaffold.data.aiframes

import kotlin.math.sqrt

enum class AIMatch { MATCH, REVIEW, REJECT }

data class AIRule(
    val description: String,
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val requireAll: Boolean = true,
    val threshold: Float = .20f,
) {
    init {
        require(description.isNotBlank() && description.codePointCount(0, description.length) <= 500)
        require(threshold.isFinite() && threshold in 0f..1f)
        require(includes.size <= 20 && excludes.size <= 20)
        require((includes + excludes).all { it.isNotBlank() && it.codePointCount(0, it.length) <= 500 })
    }

    fun evaluate(descriptionScore: Float, includeScores: List<Float>, excludeScores: List<Float>): AIMatch {
        if (includeScores.size != includes.size || excludeScores.size != excludes.size) return AIMatch.REJECT
        if ((listOf(descriptionScore) + includeScores + excludeScores).any { !it.isFinite() }) return AIMatch.REJECT
        if (excludeScores.any { it >= threshold }) return AIMatch.REJECT
        val includeScore = if (includeScores.isEmpty()) descriptionScore else if (requireAll) includeScores.min() else includeScores.max()
        val effective = minOf(descriptionScore, includeScore)
        return when {
            effective >= threshold -> AIMatch.MATCH
            effective >= threshold - .04f -> AIMatch.REVIEW
            else -> AIMatch.REJECT
        }
    }
}

object AIEmbedding {
    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.isNotEmpty() && a.size == b.size)
        require(a.all { it.isFinite() } && b.all { it.isFinite() })
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        require(aa > 0.0 && bb > 0.0)
        return (dot / sqrt(aa * bb)).toFloat().coerceIn(-1f, 1f)
    }
}

object AIEligibility {
    fun allowed(readiness: String, privacyExcluded: Boolean, deleted: Boolean, mediaKind: String, policyAllowed: Boolean, manuallyExcluded: Boolean) =
        readiness == "ready" && !privacyExcluded && !deleted && mediaKind == "image" && policyAllowed && !manuallyExcluded
}

object AIJobGuard {
    fun canCommit(jobRevision: Long, ruleRevision: Long, jobModelRevision: String, modelRevision: String, cancelled: Boolean) =
        !cancelled && jobRevision == ruleRevision && jobModelRevision == modelRevision
}

object AIMembershipPolicy {
    fun removeExisting(match: AIMatch, inferenceFailed: Boolean, eligible: Boolean): Boolean =
        !eligible || (!inferenceFailed && match != AIMatch.MATCH)
}
