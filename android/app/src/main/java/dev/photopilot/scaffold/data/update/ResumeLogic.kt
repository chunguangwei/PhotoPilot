package dev.photopilot.scaffold.data.update

object ResumeLogic {

    const val HTTP_OK = 200
    const val HTTP_PARTIAL = 206

    enum class Decision { APPEND, RESTART, RETRY }

    fun decide(partLength: Long, expectedSize: Long, statusCode: Int): Decision = when (statusCode) {
        HTTP_PARTIAL -> if (partLength > 0L && (expectedSize <= 0L || partLength < expectedSize)) {
            Decision.APPEND
        } else {
            Decision.RESTART
        }
        HTTP_OK -> Decision.RESTART
        else -> Decision.RETRY
    }

    fun isComplete(fileLength: Long, expectedSize: Long): Boolean =
        expectedSize > 0L && fileLength == expectedSize
}
