package dev.photopilot.scaffold.data.remote

import kotlinx.coroutines.CompletableDeferred

class RemoteRequestGate(private val capacity: Int = 2) {
    private data class Waiter(val priority: Int, val ready: CompletableDeferred<Unit>)
    private val lock = Any()
    private var active = 0
    private val waiting = mutableListOf<Waiter>()
    suspend fun acquire(priority: Int) {
        val waiter = synchronized(lock) {
            if (active < capacity) { active++; return }
            Waiter(priority, CompletableDeferred()).also { waiting.add(it) }
        }
        try { waiter.ready.await() }
        catch (error: Throwable) {
            synchronized(lock) {
                if (!waiting.remove(waiter)) release()
            }
            throw error
        }
    }
    fun release() = synchronized(lock) {
        val next = waiting.maxByOrNull { it.priority }
        if (next == null) active--
        else { waiting.remove(next); next.ready.complete(Unit) }
    }
}
