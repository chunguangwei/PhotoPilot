package dev.photopilot.scaffold.data.logic

object NasDeletionJudge {

    const val MIN_INTERVAL_MS = 60_000L
    const val MIN_MISSING_SCANS = 2
    const val CONFIRM_THRESHOLD_PERCENT = 20

    data class Track(val missingScanCount: Int = 0, val firstMissingAtMs: Long? = null)

    enum class Verdict { PENDING, REMOVED, NEEDS_CONFIRMATION }

    fun onScan(
        track: Track,
        seen: Boolean,
        complete: Boolean,
        authOk: Boolean,
        nowMs: Long,
    ): Track {
        if (!complete || !authOk) return track
        if (seen) return Track()
        return Track(
            missingScanCount = track.missingScanCount + 1,
            firstMissingAtMs = track.firstMissingAtMs ?: nowMs,
        )
    }

    fun judgeRatio(totalCandidates: Int, batchSize: Int): Verdict =
        if (batchSize > 0 &&
            totalCandidates.toLong() * 100 > batchSize.toLong() * CONFIRM_THRESHOLD_PERCENT
        ) {
            Verdict.NEEDS_CONFIRMATION
        } else {
            Verdict.REMOVED
        }

    fun judgeItem(track: Track, evalNowMs: Long): Verdict {
        if (track.missingScanCount < MIN_MISSING_SCANS) return Verdict.PENDING
        val first = track.firstMissingAtMs ?: return Verdict.PENDING
        return if (evalNowMs - first >= MIN_INTERVAL_MS) Verdict.REMOVED else Verdict.PENDING
    }

    fun judge(track: Track, evalNowMs: Long, totalCandidates: Int, batchSize: Int): Verdict {
        if (judgeRatio(totalCandidates, batchSize) == Verdict.NEEDS_CONFIRMATION) {
            return Verdict.NEEDS_CONFIRMATION
        }
        return judgeItem(track, evalNowMs)
    }
}
