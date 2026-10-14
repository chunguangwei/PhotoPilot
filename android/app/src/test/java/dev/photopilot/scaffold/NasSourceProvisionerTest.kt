package dev.photopilot.scaffold

import dev.photopilot.scaffold.data.repo.NasSourceProvisioner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NasSourceProvisionerTest {

    private class Recorder {
        val events = mutableListOf<String>()
    }

    private fun provisioner(recorder: Recorder, failAt: String? = null) = NasSourceProvisioner(
        saveCredential = { _, _ ->
            if (failAt == "credential") error("credential write failed")
            recorder.events += "credential"
        },
        ensureSource = { _, _, _, _ ->
            if (failAt == "source") error("source write failed")
            recorder.events += "source"
            "source-1"
        },
        createJob = { _, _ ->
            if (failAt == "job") error("job create failed")
            recorder.events += "job"
            "job-1"
        },
        registerAndStart = { recorder.events += "start" },
        markOnboardingCompleted = { recorder.events += "onboarding" },
    )

    private fun provisionAll(p: NasSourceProvisioner): String? = runBlocking {
        p.provision("fp", "{}", "nas", 83L, "{}", "{}")
    }

    @Test
    fun credentialPersistsBeforeSourceAndJob() {
        val recorder = Recorder()
        assertEquals("job-1", provisionAll(provisioner(recorder)))
        assertEquals(listOf("credential", "source", "job", "start", "onboarding"), recorder.events)
    }

    @Test
    fun onboardingMarkedOnlyAfterJobCreated() {
        val recorder = Recorder()
        runCatching { provisionAll(provisioner(recorder, failAt = "job")) }
        assertEquals(listOf("credential", "source"), recorder.events)
        assertFalse(recorder.events.contains("onboarding"))
    }

    @Test
    fun credentialFailureAbortsBeforeSourceRow() {
        val recorder = Recorder()
        val result = runCatching { provisionAll(provisioner(recorder, failAt = "credential")) }
        assertNull(result.getOrNull())
        assertEquals(emptyList<String>(), recorder.events)
    }

    @Test
    fun sourceFailureLeavesNoJob() {
        val recorder = Recorder()
        val result = runCatching { provisionAll(provisioner(recorder, failAt = "source")) }
        assertNull(result.getOrNull())
        assertEquals(listOf("credential"), recorder.events)
    }

    @Test
    fun jobFailureLeavesNoStartedDriver() {
        val recorder = Recorder()
        val result = runCatching { provisionAll(provisioner(recorder, failAt = "job")) }
        assertNull(result.getOrNull())
        assertEquals(listOf("credential", "source"), recorder.events)
    }
}
