package dev.photopilot.scaffold.data.logic

import java.util.Locale

data class QueueAsset(
    val id: String,
    val capturedAt: Long?,
    val importedAt: Long,
    val manualOrder: Long?,
    val addedAt: Long,
)

data class MonthBuckets(
    val bucketOrder: List<String>,
    val buckets: Map<String, List<String>>,
    val noCapturedTime: List<String>,
)

object PlaybackQueue {

    const val ORDER_CAPTURED_ASC = "captured_asc"
    const val ORDER_MANUAL = "manual"
    const val ORDER_RANDOM = "random"

    fun buildQueue(assets: List<QueueAsset>, orderMode: String, randomSeed: Long): List<String> =
        when (orderMode) {
            ORDER_MANUAL -> manualOrder(assets)
            ORDER_RANDOM -> shuffle(assets.map { it.id }, randomSeed)
            else -> capturedAsc(assets)
        }

    fun capturedAsc(assets: List<QueueAsset>): List<String> =
        assets.sortedWith(
            compareBy<QueueAsset> { it.capturedAt == null }
                .thenBy { it.capturedAt ?: 0L }
                .thenBy { it.id },
        ).map { it.id }

    fun manualOrder(assets: List<QueueAsset>): List<String> =
        assets.sortedWith(
            compareBy<QueueAsset> { it.manualOrder == null }
                .thenBy { it.manualOrder ?: 0L }
                .thenBy { it.capturedAt == null }
                .thenBy { it.capturedAt ?: 0L }
                .thenBy { it.id },
        ).map { it.id }

    fun browseOrder(assets: List<QueueAsset>): List<String> =
        assets.sortedWith(
            compareBy<QueueAsset> { it.capturedAt ?: it.importedAt }
                .thenBy { it.id },
        ).map { it.id }

    fun monthBuckets(assets: List<QueueAsset>): MonthBuckets {
        val capturedById = assets.associate { it.id to it.capturedAt }
        val buckets = sortedMapOf<String, MutableList<String>>()
        val noCaptured = mutableListOf<String>()
        for (asset in assets) {
            val captured = asset.capturedAt
            if (captured == null) {
                noCaptured.add(asset.id)
            } else {
                buckets.getOrPut(monthKey(captured)) { mutableListOf() }.add(asset.id)
            }
        }
        val ordered = buckets.mapValues { (_, ids) ->
            ids.distinct().sortedWith(compareBy({ capturedById[it] ?: 0L }, { it }))
        }
        return MonthBuckets(
            bucketOrder = ordered.keys.toList(),
            buckets = ordered,
            noCapturedTime = noCaptured.distinct().sorted(),
        )
    }

    internal fun monthKey(epochMillis: Long): String {
        val days = Math.floorDiv(epochMillis, 86_400_000L)
        val z = days + 719_468L
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val month = mp + if (mp < 10) 3 else -9
        val year = if (month <= 2) y + 1 else y
        return String.format(Locale.US, "%04d-%02d", year, month)
    }

    fun shuffle(ids: List<String>, seed: Long): List<String> {
        val result = ids.toMutableList()
        val random = SplitMix64(seed)
        for (i in result.size - 1 downTo 1) {
            val j = java.lang.Long.remainderUnsigned(random.next(), (i + 1).toLong()).toInt()
            val tmp = result[i]
            result[i] = result[j]
            result[j] = tmp
        }
        return result
    }
}

class SplitMix64(seed: Long) {
    private var state: Long = seed

    fun next(): Long {
        state += -7_046_029_254_386_353_131L
        var z = state
        z = (z xor (z ushr 30)) * -4_658_895_280_553_007_687L
        z = (z xor (z ushr 27)) * -7_723_592_293_110_705_685L
        return z xor (z ushr 31)
    }
}
