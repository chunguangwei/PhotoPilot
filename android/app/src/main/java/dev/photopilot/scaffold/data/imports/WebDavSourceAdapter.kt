package dev.photopilot.scaffold.data.imports

import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.logic.NasPath
import dev.photopilot.scaffold.data.net.WebDavClient
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class WebDavSourceAdapter(
    private val client: WebDavClient,
    private val rootPath: String,
    override val sourceFingerprint: String,
    private val pageSize: Int = 100,
    private val recursive: Boolean = false,
) : SourceAdapter {

    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    private data class EnumState(val dirs: List<String>, val skip: Int)

    override suspend fun enumerate(scopeJson: String, cursor: String?): EnumerationPage {
        val scopeDirs = scopeCollections(scopeJson)
        var state = parseCursor(cursor, scopeDirs)
        val items = mutableListOf<EnumeratedAsset>()
        while (items.size < pageSize && state.dirs.isNotEmpty()) {
            val dir = state.dirs.first()
            val entries = client.propfind(dir, depth = 1)
            val children = entries.mapNotNull { toChild(dir, it) }
            val subdirs = if (recursive) {
                children
                    .filter { it.isCollection }
                    .sortedBy { it.relPath }
            } else {
                emptyList()
            }
            val files = children
                .filter { !it.isCollection && it.extension in LocalFilesSourceAdapter.MEDIA_EXTENSIONS }
                .sortedBy { it.relPath }
            val emit = files.drop(state.skip).take(pageSize - items.size)
            emit.forEach { child ->
                items.add(
                    EnumeratedAsset(
                        remoteKey = child.relPath,
                        stableId = null,
                        revision = revisionOf(child),
                        byteSize = child.size,
                        takenAt = null,
                    ),
                )
            }
            val consumed = state.skip + emit.size
            state = if (consumed < files.size) {
                state.copy(skip = consumed)
            } else {
                EnumState(
                    dirs = state.dirs.drop(1) + subdirs.map { it.relPath },
                    skip = 0,
                )
            }
        }
        val nextCursor = if (state.dirs.isEmpty()) null else stateToCursor(state)
        return EnumerationPage(
            items = items,
            nextCursor = nextCursor,
            totalKnown = null,
        )
    }

    override fun openAsset(remoteKey: String): InputStream =
        client.openGet(remoteKey).stream

    private fun scopeCollections(scopeJson: String): List<String> {
        val scope = JsonMini.parseObject(scopeJson)
        val collections = scope["directories"] as? List<*> ?: return emptyList()
        return collections.mapNotNull { (it as? String)?.let(NasPath::normalize) }
    }

    private fun parseCursor(cursor: String?, initialDirs: List<String>): EnumState {
        val state = NasEnumCursor.parse(cursor, initialDirs)
        return EnumState(dirs = state.dirs, skip = state.skip)
    }

    private fun stateToCursor(state: EnumState): String =
        NasEnumCursor.format(NasEnumCursor.State(dirs = state.dirs, skip = state.skip))

    private data class Child(
        val relPath: String,
        val isCollection: Boolean,
        val extension: String,
        val etag: String?,
        val size: Long?,
        val lastModified: String?,
    )

    private fun toChild(dir: String, entry: WebDavClient.DavEntry): Child? {
        val decoded = WebDavClient.decodeHref(entry.href).removePrefix("/")
        val prefix = if (rootPath.isEmpty()) "" else "$rootPath/"
        if (!decoded.startsWith(prefix)) return null
        val rel = decoded.removePrefix(prefix)
        if (rel.isEmpty() || rel == dir) return null
        return Child(
            relPath = rel,
            isCollection = entry.isCollection,
            extension = rel.substringAfterLast('.', "").lowercase(),
            etag = entry.etag,
            size = entry.size,
            lastModified = entry.lastModified,
        )
    }

    private fun revisionOf(child: Child): String {
        child.etag?.let { return it }
        val mtime = child.lastModified?.let(::parseHttpDate) ?: 0L
        return "$mtime:${child.size ?: 0L}"
    }

    private fun parseHttpDate(value: String): Long? =
        runCatching {
            SimpleDateFormat(HTTP_DATE_PATTERN, Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("GMT") }
                .parse(value)
                ?.time
        }.getOrNull()

    companion object {
        const val CURSOR_PREFIX = "queue:"
        const val HTTP_DATE_PATTERN = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
        const val WEBDAV_CAPABILITY_FLAGS = 83L
    }
}
