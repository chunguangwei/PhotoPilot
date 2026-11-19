package dev.photopilot.scaffold.data.aiframes

import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AIClassificationScheduler {
    private val execution = Mutex()
    private val preview = Mutex()
    val previewPending: Boolean get() = preview.isLocked

    suspend fun <T> runPreview(action: suspend () -> T): T {
        if (!preview.tryLock()) throw IOException("preview_already_active")
        try {
            return execution.withLock { action() }
        } finally {
            preview.unlock()
        }
    }

    suspend fun runFrame(action: suspend () -> Unit) {
        execution.withLock { action() }
    }
}
