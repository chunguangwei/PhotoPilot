package dev.photopilot.scaffold.data.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest

object DownloadIdentity {
    fun prepare(partFile: File, url: String, expectedSize: Long) {
        partFile.parentFile?.mkdirs()
        val identity = MessageDigest.getInstance("SHA-256")
            .digest("$url\n$expectedSize".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val marker = File(partFile.parentFile, partFile.name + ".identity")
        if (runCatching { marker.readText() }.getOrNull() == identity) return
        if (partFile.exists() && !partFile.delete()) throw IOException("cannot reset partial download")
        marker.writeText(identity)
    }
}
