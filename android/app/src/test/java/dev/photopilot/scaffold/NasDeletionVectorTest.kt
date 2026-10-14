package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.NasDeletionJudge
import org.junit.Assert.assertEquals
import org.junit.Test

class NasDeletionVectorTest {

    @Test
    fun allDeletionCasesMatch() {
        val cases = Vectors.load("nas-deletion.json").getJSONArray("cases")
        assertEquals(10, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val id = c.getString("id")
            val batchSize = c.getInt("batch_size")
            val expected = c.getString("expected_state")
            if (c.has("candidates")) {
                val verdict = NasDeletionJudge.judgeRatio(c.getInt("candidates"), batchSize)
                val wire = when (verdict) {
                    NasDeletionJudge.Verdict.NEEDS_CONFIRMATION -> "needs_confirmation"
                    NasDeletionJudge.Verdict.REMOVED -> "removed"
                    NasDeletionJudge.Verdict.PENDING -> "pending"
                }
                assertEquals(id, expected, wire)
                continue
            }
            var track = NasDeletionJudge.Track()
            var lastNowMs = 0L
            val events = c.getJSONArray("events")
            for (e in 0 until events.length()) {
                val event = events.getJSONObject(e)
                if (event.has("scan")) {
                    val scan = event.getJSONObject("scan")
                    lastNowMs = scan.getLong("now_ms")
                    val seen = scan.getJSONArray("seen").let { seenList ->
                        (0 until seenList.length()).any { seenList.getString(it) == "a.jpg" }
                    }
                    track = NasDeletionJudge.onScan(
                        track = track,
                        seen = seen,
                        complete = scan.getBoolean("complete"),
                        authOk = scan.getBoolean("auth_ok"),
                        nowMs = lastNowMs,
                    )
                }
            }
            assertEquals(id, c.getInt("expected_missing_count"), track.missingScanCount)
            if (expected == "cleared") {
                assertEquals(id, NasDeletionJudge.Track(), track)
                continue
            }
            val candidates = if (track.missingScanCount > 0) 1 else 0
            val verdict = NasDeletionJudge.judge(track, lastNowMs, candidates, batchSize)
            val wire = when (verdict) {
                NasDeletionJudge.Verdict.NEEDS_CONFIRMATION -> "needs_confirmation"
                NasDeletionJudge.Verdict.REMOVED -> "removed"
                NasDeletionJudge.Verdict.PENDING -> "pending"
            }
            assertEquals(id, expected, wire)
        }
    }
}
