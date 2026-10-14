package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.logic.PlaybackQueue
import dev.photopilot.scaffold.data.logic.QueueAsset
import dev.photopilot.scaffold.data.logic.SplitMix64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueTest {

    private fun queueAssets(case: JSONObject): List<QueueAsset> {
        val array = case.getJSONArray("assets")
        return (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            QueueAsset(
                id = entry.getString("id"),
                capturedAt = if (entry.isNull("captured_at")) null else entry.getLong("captured_at"),
                importedAt = entry.getLong("imported_at"),
                manualOrder = if (entry.isNull("manual_order")) null else entry.getLong("manual_order"),
                addedAt = entry.getLong("added_at"),
            )
        }
    }

    private fun stringList(array: org.json.JSONArray): List<String> =
        (0 until array.length()).map { array.getString(it) }

    @Test
    fun contractVectors() {
        val cases = Vectors.load("playback-queue.json").getJSONArray("cases")
        assertTrue("expected at least 7 vectors", cases.length() >= 7)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val id = case.getString("id")
            val assets = queueAssets(case)
            when (case.optString("scenario", "queue")) {
                "browse" -> assertEquals(
                    id,
                    stringList(case.getJSONArray("expected")),
                    PlaybackQueue.browseOrder(assets),
                )
                "bucket" -> {
                    val expected = case.getJSONObject("expected")
                    val actual = PlaybackQueue.monthBuckets(assets)
                    assertEquals(id, stringList(expected.getJSONArray("bucket_order")), actual.bucketOrder)
                    assertEquals(
                        id,
                        stringList(expected.getJSONArray("no_captured_time")),
                        actual.noCapturedTime,
                    )
                    val expectedBuckets = expected.getJSONObject("buckets")
                    assertEquals(id, expectedBuckets.length(), actual.buckets.size)
                    for (key in expectedBuckets.keys()) {
                        assertEquals(
                            "$id bucket $key",
                            stringList(expectedBuckets.getJSONArray(key)),
                            actual.buckets[key],
                        )
                    }
                }
                else -> assertEquals(
                    id,
                    stringList(case.getJSONArray("expected")),
                    PlaybackQueue.buildQueue(assets, case.getString("order_mode"), case.getLong("random_seed")),
                )
            }
        }
    }

    @Test
    fun randomSameSeedReproducibleAndPermutation() {
        val ids = (1..50).map { "asset-$it" }
        val first = PlaybackQueue.shuffle(ids, 2024L)
        val second = PlaybackQueue.shuffle(ids, 2024L)
        assertEquals(first, second)
        assertEquals(ids.sorted(), first.sorted())
    }

    @Test
    fun splitMix64MatchesUnsignedBitPattern() {
        val random = SplitMix64(0L)
        assertEquals(-0x1DDF_57C6_84E2_3251L, random.next())
    }

    @Test
    fun monthKeyEpochBoundaries() {
        assertEquals("1970-01", PlaybackQueue.monthKey(0L))
        assertEquals("2024-03", PlaybackQueue.monthKey(1_710_504_000_000L))
        assertEquals("2023-12", PlaybackQueue.monthKey(1_704_063_600_000L))
        assertEquals("1969-12", PlaybackQueue.monthKey(-1L))
    }
}
