package dev.photopilot.scaffold

import android.os.PowerManager
import dev.photopilot.scaffold.data.aiframes.AIInferencePacer
import dev.photopilot.scaffold.data.aiframes.shouldPauseForThermalStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test

class AIInferencePacerTest {
    @Test fun severeThrottlingSlowsInferenceWhileCriticalStillPauses() = runBlocking {
        var status = PowerManager.THERMAL_STATUS_MODERATE
        val waits = mutableListOf<Long>()
        val pacer = AIInferencePacer({ status }, { 0L }, { waits.add(it) })
        pacer.run { Unit }
        status = PowerManager.THERMAL_STATUS_SEVERE
        pacer.run { Unit }
        assertTrue(waits[1] > waits[0])
        assertFalse(shouldPauseForThermalStatus(status))
        assertTrue(shouldPauseForThermalStatus(PowerManager.THERMAL_STATUS_CRITICAL))
        assertTrue(shouldPauseForThermalStatus(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }

    @Test fun warmDeviceGetsMoreRecoveryTimeBetweenInferences() = runBlocking {
        var clock = 0L
        var thermal = 0
        val waits = mutableListOf<Long>()
        val pacer = AIInferencePacer({ thermal }, { clock }, { waits.add(it) })
        assertEquals("first", pacer.run { clock += 400; "first" })
        thermal = 2
        assertEquals("second", pacer.run { clock += 400; "second" })
        assertTrue(waits[0] > 0)
        assertTrue(waits[1] > waits[0])
    }

    @Test fun longInferenceGetsProportionalRestRatherThanAConstantTinyYield() = runBlocking {
        var clock = 0L
        val waits = mutableListOf<Long>()
        val pacer = AIInferencePacer({ 0 }, { clock }, { waits.add(it) })
        pacer.run { clock += 100 }
        pacer.run { clock += 2000 }
        assertTrue(waits[1] > waits[0])
        assertTrue(waits[1] >= waits[0] * 5)
    }

    @Test fun coolingRestCanBeCancelledWithoutWaitingForAnotherInference() = runBlocking {
        var returned = false
        val pacer = AIInferencePacer({ 2 })
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            pacer.run { 1 }
            returned = true
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(returned)
    }
}
