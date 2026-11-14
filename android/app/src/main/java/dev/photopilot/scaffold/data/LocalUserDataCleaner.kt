package dev.photopilot.scaffold.data

import java.io.File
import java.io.IOException

object LocalUserDataCleaner {
    fun clear(filesDir: File, cacheDir: File, delete: (File) -> Boolean = { it.deleteRecursively() }) {
        listOf(
            File(filesDir, "originals"),
            File(filesDir, "credentials"),
            File(filesDir, "music"),
            File(filesDir, "ai-models"),
            File(filesDir, "ai-validation"),
            File(filesDir, "ai-real-inference.json"),
            File(cacheDir, "picked"),
            File(cacheDir, "import-tmp"),
            File(cacheDir, "diagnostics"),
            File(cacheDir, "ai-inference"),
            File(filesDir.parentFile, "code_cache/ai-runtime"),
        ).forEach { target ->
            if (target.exists() && (!delete(target) || target.exists())) {
                throw IOException("Local data removal failed")
            }
        }
    }
}
