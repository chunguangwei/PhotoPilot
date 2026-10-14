package dev.photopilot.scaffold.data.imports

import dev.photopilot.scaffold.data.logic.NasPath
import dev.photopilot.scaffold.data.net.SmbShare
import java.io.InputStream

class SmbSourceAdapter(
    private val share: SmbShare,
    private val rootPath: String,
    override val sourceFingerprint: String,
    private val pageSize: Int = 100,
    private val recursive: Boolean = false,
) : SourceAdapter {

    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    override suspend fun enumerate(scopeJson: String, cursor: String?): EnumerationPage {
        val initialDirs = scopeDirectories(scopeJson)
        var state = NasEnumCursor.parse(cursor, initialDirs)
        val items = mutableListOf<EnumeratedAsset>()
        var dirs = state.dirs
        var skip = state.skip
        while (items.size < pageSize && dirs.isNotEmpty()) {
            val dir = dirs.first()
            val children = listChildren(dir)
            val subdirs = if (recursive) {
                children.filter { it.isCollection }.sortedBy { it.relPath }
            } else {
                emptyList()
            }
            val files = children
                .filter { !it.isCollection && it.extension in LocalFilesSourceAdapter.MEDIA_EXTENSIONS }
                .sortedBy { it.relPath }
            val emit = files.drop(skip).take(pageSize - items.size)
            emit.forEach { child ->
                items.add(
                    EnumeratedAsset(
                        remoteKey = child.relPath,
                        stableId = null,
                        revision = revisionOf(child.modifiedMs, child.size),
                        byteSize = child.size,
                        takenAt = null,
                    ),
                )
            }
            val consumed = skip + emit.size
            if (consumed < files.size) {
                skip = consumed
            } else {
                dirs = dirs.drop(1) + subdirs.map { it.relPath }
                skip = 0
            }
        }
        val nextCursor = if (dirs.isEmpty()) {
            null
        } else {
            NasEnumCursor.format(NasEnumCursor.State(dirs = dirs, skip = skip))
        }
        return EnumerationPage(items = items, nextCursor = nextCursor, totalKnown = null)
    }

    override fun openAsset(remoteKey: String): InputStream = share.openRead(remoteKey)

    private data class Child(
        val relPath: String,
        val isCollection: Boolean,
        val extension: String,
        val size: Long?,
        val modifiedMs: Long?,
    )

    private fun listChildren(dir: String): List<Child> =
        share.list(dir).mapNotNull { entry ->
            val rel = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
            val normalized = NasPath.normalize(rel) ?: return@mapNotNull null
            if (normalized.isEmpty() || normalized == dir) return@mapNotNull null
            Child(
                relPath = normalized,
                isCollection = entry.isCollection,
                extension = normalized.substringAfterLast('.', "").lowercase(),
                size = entry.size,
                modifiedMs = entry.modifiedMs,
            )
        }

    private fun scopeDirectories(scopeJson: String): List<String> {
        val scope = dev.photopilot.scaffold.data.logic.JsonMini.parseObject(scopeJson)
        val dirs = scope["directories"] as? List<*> ?: return emptyList()
        return dirs.mapNotNull { (it as? String)?.let(NasPath::normalize) }
    }

    private fun revisionOf(modifiedMs: Long?, size: Long?): String =
        "${modifiedMs ?: 0L}:${size ?: 0L}"

    companion object {
        const val SMB_CAPABILITY_FLAGS = 83L
    }
}
