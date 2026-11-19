package dev.photopilot.scaffold.data.aiframes

import android.os.PowerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class AIInferencePacer(
    private val thermalStatus: () -> Int,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val rest: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun <T> run(operation: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val start = clock()
        val result = operation()
        val elapsed = (clock() - start).coerceAtLeast(0)
        val thermal = thermalStatus()
        val recovery = when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> (elapsed * 3).coerceIn(2000, 10000)
            thermal >= PowerManager.THERMAL_STATUS_MODERATE -> elapsed.coerceIn(500, 5000)
            thermal >= PowerManager.THERMAL_STATUS_LIGHT -> (elapsed / 2).coerceIn(200, 3000)
            else -> (elapsed / 4).coerceIn(50, 1000)
        }
        rest(recovery)
        return result
    }
}

internal fun shouldPauseForThermalStatus(status: Int): Boolean = status >= PowerManager.THERMAL_STATUS_CRITICAL
