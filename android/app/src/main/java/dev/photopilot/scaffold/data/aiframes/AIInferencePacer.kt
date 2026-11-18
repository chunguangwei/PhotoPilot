package dev.photopilot.scaffold.data.aiframes

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
            thermal >= 2 -> elapsed.coerceIn(500, 5000)
            thermal >= 1 -> (elapsed / 2).coerceIn(200, 3000)
            else -> (elapsed / 4).coerceIn(50, 1000)
        }
        rest(recovery)
        return result
    }
}
