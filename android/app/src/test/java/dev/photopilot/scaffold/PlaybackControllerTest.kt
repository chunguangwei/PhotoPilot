package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.PlaybackController
import dev.photopilot.scaffold.data.logic.PlaybackEvent
import dev.photopilot.scaffold.data.logic.PlaybackTiming
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControllerTest {

    private fun stringList(array: org.json.JSONArray): List<String> =
        (0 until array.length()).map { array.getString(it) }

    @Test
    fun stateMachineContractVectors() {
        val vector = Vectors.load("playback-state.json")
        val cases = vector.getJSONArray("cases")
        assertTrue("expected at least 34 vectors", cases.length() >= 34)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val id = case.getString("id")
            val eventJson = case.getJSONObject("event")
            val event = PlaybackEvent(
                type = eventJson.getString("type"),
                nextReady = if (eventJson.has("next_ready")) eventJson.getBoolean("next_ready") else null,
                queueIntact = if (eventJson.has("queue_intact")) eventJson.getBoolean("queue_intact") else null,
                playableRemaining = if (eventJson.has("playable_remaining")) eventJson.getBoolean("playable_remaining") else null,
                category = eventJson.optString("category").takeIf { it.isNotEmpty() },
                limitReached = if (eventJson.has("limit_reached")) eventJson.getBoolean("limit_reached") else null,
            )
            val outcome = PlaybackController.reduce(case.getString("state"), event)
            val expected = case.getJSONObject("expected")
            assertEquals(id, expected.getString("state"), outcome.state)
            assertEquals(id, stringList(expected.getJSONArray("effects")), outcome.effects)
        }
    }

    @Test
    fun decodeFailLimitContractVectors() {
        val cases = Vectors.load("playback-state.json").getJSONArray("decode_fail_limit_cases")
        assertTrue("expected at least 4 vectors", cases.length() >= 4)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            assertEquals(
                case.getString("id"),
                case.getInt("expected"),
                PlaybackController.decodeFailLimit(case.getInt("queue_length")),
            )
        }
    }

    @Test
    fun timingContractVectors() {
        val cases = Vectors.load("playback-timing.json").getJSONArray("cases")
        assertTrue("expected at least 26 vectors", cases.length() >= 26)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val id = case.getString("id")
            val expected = case.getJSONObject("expected")
            when (case.getString("scenario")) {
                "schedule" -> {
                    val nextTransition = PlaybackTiming.nextTransitionAt(
                        case.getLong("t0"),
                        case.getInt("hold_seconds"),
                    )
                    val nextStable = PlaybackTiming.nextStableAt(
                        case.getLong("t0"),
                        case.getInt("hold_seconds"),
                        case.getString("transition"),
                        case.getDouble("transition_seconds"),
                    )
                    assertEquals(id, expected.getLong("next_transition_at"), nextTransition)
                    assertEquals(id, expected.getLong("next_stable_at"), nextStable)
                }
                "pause_resume" -> {
                    val nextTransition = PlaybackTiming.nextTransitionAt(
                        case.getLong("t0"),
                        case.getInt("hold_seconds"),
                    )
                    val remaining = PlaybackTiming.remainingAtPause(nextTransition, case.getLong("pause_at"))
                    assertEquals(
                        id,
                        expected.getLong("next_transition_at"),
                        PlaybackTiming.resumeTransitionAt(case.getLong("resume_at"), remaining),
                    )
                }
                "validate" -> assertEquals(
                    id,
                    expected.getBoolean("valid"),
                    PlaybackTiming.isValidSettings(
                        case.getDouble("hold_seconds"),
                        case.getString("transition"),
                        case.getDouble("transition_seconds"),
                    ),
                )
                "rest" -> assertEquals(
                    id,
                    expected.getBoolean("active"),
                    PlaybackTiming.restActive(
                        case.getBoolean("enabled"),
                        case.getInt("start_minute"),
                        case.getInt("end_minute"),
                        case.getInt("now_minute"),
                    ),
                )
                else -> throw AssertionError("$id: unknown scenario")
            }
        }
    }
}
