package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.remote.RemoteRequestGate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RemotePriorityTest {
    @Test fun playbackRunsBeforeQueuedThumbnailAndCancelledWaiterIsRemoved() = runBlocking {
        val gate = RemoteRequestGate(1)
        gate.acquire(0)
        val order = mutableListOf<String>()
        val thumbnail = launch(start = CoroutineStart.UNDISPATCHED) { gate.acquire(0); order.add("thumbnail"); gate.release() }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { gate.acquire(3); order.add("cancelled"); gate.release() }
        cancelled.cancelAndJoin()
        val playback = launch(start = CoroutineStart.UNDISPATCHED) { gate.acquire(2); order.add("playback"); gate.release() }
        gate.release()
        playback.join(); thumbnail.join()
        assertEquals(listOf("playback", "thumbnail"), order)
    }
}
