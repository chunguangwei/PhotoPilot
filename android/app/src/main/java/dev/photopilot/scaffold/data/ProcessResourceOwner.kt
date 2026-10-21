package dev.photopilot.scaffold.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

class ProcessResourceOwner<T>(private val scope: CoroutineScope, private val factory: suspend () -> T) {
    private val lock = Any()
    private var pending: Deferred<T>? = null

    suspend fun get(): T {
        val task = synchronized(lock) {
            pending?.takeUnless { it.isCancelled } ?: scope.async { factory() }.also { pending = it }
        }
        return task.await()
    }

    suspend fun reset(cleanup: suspend (T) -> Unit): T {
        val task = synchronized(lock) {
            val previous = pending?.takeUnless { it.isCancelled }
            scope.async {
                cleanup(previous?.await() ?: factory())
                factory()
            }.also { pending = it }
        }
        return task.await()
    }
}
