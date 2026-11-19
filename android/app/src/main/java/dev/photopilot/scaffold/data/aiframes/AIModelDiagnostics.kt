package dev.photopilot.scaffold.data.aiframes

import android.content.Context
import android.os.SystemClock
import java.io.File

internal object AIModelDiagnostics {
    private const val MAX_BYTES = 48 * 1024
    private val holders = mutableMapOf<String, String>()

    @Synchronized
    fun event(context: Context, message: String) {
        runCatching {
            val file = File(context.filesDir, "ai-model-diagnostics.log")
            if (file.length() >= MAX_BYTES) {
                val previous = File(context.filesDir, "ai-model-diagnostics.previous.log")
                previous.delete()
                if (!file.renameTo(previous)) file.writeText("")
            }
            file.appendText("${System.currentTimeMillis()} +${SystemClock.elapsedRealtime()}ms $message\n")
        }
    }

    @Synchronized
    fun acquired(context: Context, lock: String, task: String) {
        holders[lock] = task
        event(context, "$task acquired $lock")
    }

    @Synchronized
    fun released(context: Context, lock: String, task: String, heldMs: Long) {
        holders.remove(lock)
        event(context, "$task released $lock held=${heldMs}ms")
    }

    @Synchronized
    fun waiting(context: Context, lock: String, task: String, event: String) {
        event(context, "$task $event $lock holder=${holders[lock] ?: "none"}")
    }

    @Synchronized
    fun snapshot(context: Context): String = buildString {
        appendLine("Active locks: ${holders.entries.joinToString()}")
        for (name in listOf("ai-model-diagnostics.previous.log", "ai-model-diagnostics.log")) {
            val file = File(context.filesDir, name)
            if (file.isFile) append(file.readText())
        }
    }
}
