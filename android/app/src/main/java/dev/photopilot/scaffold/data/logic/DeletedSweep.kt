package dev.photopilot.scaffold.data.logic

object DeletedSweep {

    const val RETENTION_MS = 24L * 60L * 60L * 1000L

    fun isExpired(deletedAt: Long, now: Long): Boolean = now - deletedAt > RETENTION_MS
}
