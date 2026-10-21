package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.ProcessResourceOwner
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProcessResourceOwnerTest {
    @Test fun failedCleanupCanBeRetriedWithFreshResources() = runBlocking {
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var count = 0
            var removals = 0
            val owner = ProcessResourceOwner(process) { ++count }
            assertEquals(1, owner.get())
            val failure = java.io.IOException("synthetic cleanup failure")
            try {
                owner.reset { throw failure }
                fail("Cleanup failure must reach the caller")
            } catch (actual: java.io.IOException) {
                assertEquals(failure.message, actual.message)
            }

            assertEquals(3, owner.reset { resource ->
                assertEquals(2, resource)
                removals++
            })
            assertEquals(1, removals)
            assertEquals(3, owner.get())
        } finally { process.cancel() }
    }

    @Test fun sharedAccessRecoversAfterCleanupFailureWithoutHidingOriginalFailure() = runBlocking {
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var count = 0
            val owner = ProcessResourceOwner(process) { ++count }
            assertEquals(1, owner.get())
            try {
                owner.reset { throw java.io.IOException("synthetic cleanup failure") }
                fail("Cleanup failure must reach the caller")
            } catch (_: java.io.IOException) {
            }

            assertEquals(2, owner.get())
            assertEquals(2, owner.get())
        } finally { process.cancel() }
    }

    @Test fun recreatedAndConcurrentActivitiesShareOneInitialization() = runBlocking {
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val count = java.util.concurrent.atomic.AtomicInteger()
            val owner = ProcessResourceOwner(process) { count.incrementAndGet(); Any() }
            val results = (1..12).map { async { owner.get() } }.awaitAll()
            assertEquals(1, count.get())
            results.forEach { assertSame(results.first(), it) }
        } finally { process.cancel() }
    }

    @Test fun destroyedActivityDoesNotCancelProcessInitialization() = runBlocking {
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val count = java.util.concurrent.atomic.AtomicInteger()
            val owner = ProcessResourceOwner(process) {
                count.incrementAndGet()
                entered.complete(Unit)
                release.await()
                Any()
            }
            val oldActivity = async { owner.get() }
            entered.await()
            oldActivity.cancelAndJoin()
            release.complete(Unit)
            withTimeout(1_000) { owner.get() }
            assertEquals(1, count.get())
        } finally { process.cancel() }
    }

    @Test fun resetWaitsForOldDriverToStopBeforeNewActivityGetsEnvironment() = runBlocking {
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var count = 0
            val owner = ProcessResourceOwner(process) { ++count }
            assertEquals(1, owner.get())
            val stopping = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val reset = async {
                owner.reset { old ->
                    assertEquals(1, old)
                    stopping.complete(Unit)
                    stopped.await()
                }
            }
            stopping.await()
            val nextActivity = async { owner.get() }
            yield()
            assertFalse(nextActivity.isCompleted)
            stopped.complete(Unit)
            assertEquals(2, reset.await())
            assertEquals(2, nextActivity.await())
            assertEquals(2, count)
        } finally { process.cancel() }
    }
}
