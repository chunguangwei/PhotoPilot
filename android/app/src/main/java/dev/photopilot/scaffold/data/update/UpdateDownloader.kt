package dev.photopilot.scaffold.data.update

import dev.photopilot.scaffold.data.net.SimpleHttpsGet
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object UpdateDownloader {

    const val BUFFER_SIZE = 64 * 1024

    fun download(
        url: String,
        partFile: File,
        expectedSize: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        DownloadIdentity.prepare(partFile, url, expectedSize)
        if (ResumeLogic.isComplete(partFile.length(), expectedSize)) {
            onProgress(partFile.length(), expectedSize)
            return
        }
        val resumeFrom = partFile.length()
        val connection = open(url, resumeFrom)
        try {
            val status = connection.responseCode
            when (ResumeLogic.decide(resumeFrom, expectedSize, status)) {
                ResumeLogic.Decision.APPEND ->
                    streamBody(connection, partFile, resumeFrom, expectedSize, onProgress)
                ResumeLogic.Decision.RESTART -> {
                    partFile.delete()
                    if (status == ResumeLogic.HTTP_OK) {
                        streamBody(connection, partFile, 0L, expectedSize, onProgress)
                    } else {
                        connection.disconnect()
                        downloadFresh(url, partFile, expectedSize, onProgress)
                    }
                }
                ResumeLogic.Decision.RETRY ->
                    throw IOException("unexpected status $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFresh(
        url: String,
        partFile: File,
        expectedSize: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        val connection = open(url, 0L)
        try {
            val status = connection.responseCode
            if (status != ResumeLogic.HTTP_OK) throw IOException("unexpected status $status")
            streamBody(connection, partFile, 0L, expectedSize, onProgress)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, resumeFrom: Long): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = SimpleHttpsGet.CONNECT_TIMEOUT_MS
        connection.readTimeout = SimpleHttpsGet.READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", UpdateChecker.USER_AGENT)
        connection.setRequestProperty("Accept", "application/octet-stream")
        if (resumeFrom > 0L) {
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        return connection
    }

    private fun streamBody(
        connection: HttpURLConnection,
        partFile: File,
        base: Long,
        expectedSize: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) {
        partFile.parentFile?.mkdirs()
        val total = when {
            expectedSize > 0L -> expectedSize
            connection.contentLength > 0 -> base + connection.contentLength
            else -> 0L
        }
        var downloaded = base
        connection.inputStream.use { input ->
            FileOutputStream(partFile, base > 0L).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    downloaded += n
                    onProgress(downloaded, total)
                }
                output.fd.sync()
            }
        }
        if (expectedSize > 0L && downloaded != expectedSize) {
            throw IOException("truncated body: $downloaded/$expectedSize")
        }
    }
}
