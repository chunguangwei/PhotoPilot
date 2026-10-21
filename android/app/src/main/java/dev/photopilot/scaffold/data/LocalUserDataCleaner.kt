package dev.photopilot.scaffold.data

import java.io.File
import java.io.IOException

object LocalUserDataCleaner {
    fun clear(filesDir: File, cacheDir: File, delete: (File) -> Boolean = { it.deleteRecursively() }) {
        listOf(
            File(filesDir, "originals"),
            File(filesDir, "credentials"),
            File(filesDir, "music"),
            File(cacheDir, "picked"),
            File(cacheDir, "import-tmp"),
            File(cacheDir, "diagnostics"),
        ).forEach { target ->
            if (target.exists() && (!delete(target) || target.exists())) {
                throw IOException("Local data removal failed")
            }
        }
    }
}
