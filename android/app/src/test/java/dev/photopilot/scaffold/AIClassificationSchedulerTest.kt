package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.aiframes.AIClassificationScheduler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AIClassificationSchedulerTest {
    @Test fun previewReservesItsSlotBeforeTheActiveFrameReleasesExecution() = runBlocking {
        val scheduler = AIClassificationScheduler()
        val releaseFrame = CompletableDeferred<Unit>()
        val frame = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.runFrame { releaseFrame.await() }
        }
        var entered = false
        val preview = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.runPreview { entered = true }
        }
        assertTrue(scheduler.previewPending)
        assertFalse(entered)
        val duplicate = runCatching { scheduler.runPreview { fail("Duplicate preview started") } }
        assertEquals("preview_already_active", duplicate.exceptionOrNull()?.message)
        releaseFrame.complete(Unit)
        frame.join()
        preview.join()
        assertTrue(entered)
        assertFalse(scheduler.previewPending)
    }
}
