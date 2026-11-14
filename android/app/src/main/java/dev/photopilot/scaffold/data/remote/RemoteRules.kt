package dev.photopilot.scaffold.data.remote

object RemotePaths {
    fun normalize(path: String): String? {
        if (path.any { it.code < 32 } || path.replace('\\', '/').split('/').any { it == "." || it == ".." }) return null
        return path.replace('\\', '/').split('/').filter { it.isNotEmpty() }.joinToString("/")
    }
    fun contains(folder: String, remoteKey: String, recursive: Boolean): Boolean {
        val root = normalize(folder) ?: return false
        val key = normalize(remoteKey) ?: return false
        if (key.isEmpty()) return false
        val parent = key.substringBeforeLast('/', "")
        return parent == root || recursive && (root.isEmpty() || parent.startsWith("$root/"))
    }
}

object RemoteEligibility {
    fun playable(mode: String, sourceStatus: String, accessState: String, policyAllowed: Boolean, privacyExcluded: Boolean, deleted: Boolean, localAvailable: Boolean, mediaKind: String): Boolean {
        if (!policyAllowed || privacyExcluded || deleted || mediaKind == "video") return false
        return localAvailable || mode == "remote" && sourceStatus == "active" && accessState == "accessible"
    }
}
