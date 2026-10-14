package dev.photopilot.scaffold.data.repo

class NasSourceProvisioner(
    private val saveCredential: suspend (String, String) -> Unit,
    private val ensureSource: suspend (String, String, Long, String) -> String,
    private val createJob: suspend (String, String) -> String,
    private val registerAndStart: (String) -> Unit,
    private val markOnboardingCompleted: suspend () -> Unit = {},
) {

    suspend fun provision(
        fingerprint: String,
        credentialJson: String,
        displayName: String,
        capabilityFlags: Long,
        settingsJson: String,
        scopeJson: String,
    ): String? {
        saveCredential(fingerprint, credentialJson)
        val sourceId = ensureSource(fingerprint, displayName, capabilityFlags, settingsJson)
        val jobId = createJob(sourceId, scopeJson)
        registerAndStart(jobId)
        markOnboardingCompleted()
        return jobId
    }
}
