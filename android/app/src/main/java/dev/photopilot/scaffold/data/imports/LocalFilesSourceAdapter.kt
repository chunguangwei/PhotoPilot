package dev.photopilot.scaffold.data.imports

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class LocalFilesSourceAdapter(
    private val root: File,
    override val sourceFingerprint: String,
    private val pageSize: Int = 50,
) : SourceAdapter {

    init {
        require(root.isDirectory) { "root must be a directory" }
        require(pageSize > 0) { "pageSize must be positive" }
    }

    override suspend fun enumerate(scopeJson: String, cursor: String?): EnumerationPage {
        val all = listFiles()
        val offset = when {
            cursor == null -> 0
            cursor == ImportCoordinator.CURSOR_DONE -> null
            cursor.startsWith(CURSOR_PREFIX) -> cursor.removePrefix(CURSOR_PREFIX).toIntOrNull()?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("invalid cursor")
            else -> throw IllegalArgumentException("invalid cursor")
        }
        if (offset == null || offset >= all.size) {
            return EnumerationPage(items = emptyList(), nextCursor = null, totalKnown = all.size.toLong())
        }
        val page = all.subList(offset, minOf(offset + pageSize, all.size))
        val next = (offset + pageSize).takeIf { it < all.size }?.let { "$CURSOR_PREFIX$it" }
        return EnumerationPage(
            items = page.map { file ->
                EnumeratedAsset(
                    remoteKey = relativeKey(file),
                    stableId = null,
                    revision = "${file.lastModified()}:${file.length()}",
                    byteSize = file.length(),
                    takenAt = null,
                )
            },
            nextCursor = next,
            totalKnown = all.size.toLong(),
        )
    }

    override fun openAsset(remoteKey: String): InputStream {
        val target = File(root, remoteKey.replace('\\', '/'))
        val canonicalRoot = root.canonicalPath
        val canonicalTarget = target.canonicalPath
        require(canonicalTarget.startsWith("$canonicalRoot${File.separator}")) {
            "remote_key escapes source root"
        }
        return FileInputStream(target)
    }

    private fun listFiles(): List<File> {
        val out = mutableListOf<File>()
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children.sortedBy { it.name }) {
                if (child.isDirectory) {
                    walk(child)
                } else if (child.extension.lowercase() in MEDIA_EXTENSIONS) {
                    out.add(child)
                }
            }
        }
        walk(root)
        return out
    }

    private fun relativeKey(file: File): String {
        val rootPath = root.canonicalPath
        val filePath = file.canonicalPath
        return filePath.removePrefix("$rootPath${File.separator}").replace(File.separatorChar, '/')
    }

    companion object {
        const val CURSOR_PREFIX = "offset:"
        val MEDIA_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "bmp", "tif", "tiff",
            "dng", "cr2", "nef", "arw", "raf", "orf", "rw2",
        )
    }
}
