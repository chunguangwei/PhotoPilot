package dev.photopilot.scaffold.data.references

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.ColorSpace
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import dev.photopilot.scaffold.data.remote.RemotePaths
import java.io.IOException
import kotlin.math.min
import kotlin.math.sqrt

class LocalReferenceException(val code: String, cause: Throwable? = null) : IOException(code, cause)

data class LocalReferenceEntry(
    val uri: String,
    val key: String,
    val revision: String,
    val size: Long?,
    val takenAt: Long? = null,
    val folders: Map<String, String> = emptyMap(),
    val displayName: String = "",
    val versionKnown: Boolean = true,
)

interface LocalReferenceAccess {
    fun photos(): List<LocalReferenceEntry>
    fun treeName(uri: String): String? = null
    fun tree(uri: String): List<LocalReferenceEntry>
    fun document(uri: String, key: String): LocalReferenceEntry?
    fun photo(uri: String, key: String): LocalReferenceEntry?
    fun persist(uri: String, flags: Int)
    fun persistedReads(): Set<String> = emptySet()
    fun release(uri: String) = Unit
    fun covers(grant: String, uri: String): Boolean = grant == uri
    fun decode(uri: String, width: Int, height: Int): Bitmap?
}

object LocalReferenceRules {
    val extensions = setOf("jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "bmp", "tif", "tiff", "dng", "cr2", "nef", "arw", "raf", "orf", "rw2")
    private val mimeTypes = setOf("image/jpeg", "image/jpg", "image/png", "image/gif", "image/heic", "image/heif", "image/webp", "image/bmp", "image/x-ms-bmp", "image/tiff", "image/dng", "image/x-adobe-dng", "image/x-canon-cr2", "image/x-nikon-nef", "image/x-sony-arw", "image/x-fuji-raf", "image/x-olympus-orf", "image/x-panasonic-rw2")
    fun supported(name: String, mime: String): Boolean {
        if (mime.startsWith("video/", true)) return false
        return mime.lowercase() in mimeTypes || (mime.isEmpty() || mime == "application/octet-stream") && name.substringAfterLast('.', "").lowercase() in extensions
    }
    fun size(width: Int, height: Int, requestedWidth: Int, requestedHeight: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && requestedWidth > 0 && requestedHeight > 0)
        val scale = min(1.0, min(min(requestedWidth.toDouble() / width, requestedHeight.toDouble() / height), sqrt(32.0 * 1024 * 1024 / (width.toDouble() * height * 4))))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }
}

class ResolverLocalReferenceAccess(private val context: Context) : LocalReferenceAccess {
    private val resolver get() = context.contentResolver
    private val photoProjection get() = buildList {
        addAll(listOf("_id", "_display_name", "mime_type", "_size", "date_modified", "datetaken", "bucket_id", "bucket_display_name"))
        if (Build.VERSION.SDK_INT >= 30) add("generation_modified")
    }.toTypedArray()

    private fun photosAllowed(): Boolean {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            Build.VERSION.SDK_INT >= 33 -> listOf("android.permission.READ_MEDIA_IMAGES")
            else -> listOf("android.permission.READ_EXTERNAL_STORAGE")
        }
        return permissions.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    override fun photos(): List<LocalReferenceEntry> {
        if (!photosAllowed()) throw LocalReferenceException("LOCAL_REFERENCE_PERMISSION_REQUIRED")
        val result = mutableListOf<LocalReferenceEntry>()
        val cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoProjection, "is_pending = 0", null, "_id ASC")
            ?: throw LocalReferenceException("LOCAL_REFERENCE_PROVIDER_UNAVAILABLE")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.string("_id")
                photoEntry(it, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toLong()).toString(), id)?.let(result::add)
            }
        }
        return result
    }

    override fun photo(uri: String, key: String): LocalReferenceEntry? {
        if (!photosAllowed()) throw LocalReferenceException("LOCAL_REFERENCE_PERMISSION_REQUIRED")
        return resolver.query(Uri.parse(uri), photoProjection, "is_pending = 0", null, null)?.use {
            if (it.moveToFirst()) photoEntry(it, uri, key) else null
        }
    }

    private fun photoEntry(cursor: Cursor, uri: String, key: String): LocalReferenceEntry? {
        if (!LocalReferenceRules.supported(cursor.string("_display_name"), cursor.string("mime_type"))) return null
        val bucket = cursor.string("bucket_id")
        val size = cursor.number("_size")
        val modified = cursor.number("date_modified")
        val generation = cursor.number("generation_modified")
        val bucketName = cursor.string("bucket_display_name").ifEmpty {
            context.getString(dev.photopilot.scaffold.R.string.source_local_photos_title)
        }
        return LocalReferenceEntry(
            uri = uri,
            key = key,
            revision = "${cursor.string("date_modified")}:${cursor.string("_size")}:${cursor.string("generation_modified")}",
            size = size,
            takenAt = cursor.number("datetaken")?.takeIf { it > 0 },
            folders = mapOf("albums/$bucket" to bucketName),
            displayName = cursor.string("_display_name"),
            versionKnown = (generation ?: 0) > 0 || (modified ?: 0) > 0 && size != null,
        )
    }

    override fun persist(uri: String, flags: Int) {
        val parsed = Uri.parse(uri)
        if (parsed.scheme != "content" || !DocumentsContract.isDocumentUri(context, parsed) && !DocumentsContract.isTreeUri(parsed)) {
            throw LocalReferenceException("LOCAL_REFERENCE_DURABLE_ACCESS_REQUIRED")
        }
        if (!durable(parsed)) {
            val required = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            if (flags and required != required) throw LocalReferenceException("LOCAL_REFERENCE_DURABLE_ACCESS_REQUIRED")
            try {
                resolver.takePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (error: SecurityException) {
                throw LocalReferenceException("LOCAL_REFERENCE_DURABLE_ACCESS_REQUIRED", error)
            }
        }
        if (!durable(parsed)) throw LocalReferenceException("LOCAL_REFERENCE_DURABLE_ACCESS_REQUIRED")
    }

    override fun persistedReads(): Set<String> = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()

    override fun release(uri: String) {
        try {
            resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (failure: SecurityException) {
            if (uri in persistedReads()) throw failure
        }
    }

    override fun covers(grant: String, uri: String): Boolean {
        val permission = Uri.parse(grant)
        val document = Uri.parse(uri)
        if (permission == document) return true
        return DocumentsContract.isTreeUri(permission) && DocumentsContract.isTreeUri(document) &&
            permission.authority == document.authority && DocumentsContract.getTreeDocumentId(permission) == DocumentsContract.getTreeDocumentId(document)
    }

    private fun durable(uri: Uri): Boolean = persistedReads().any { covers(it, uri.toString()) }

    override fun document(uri: String, key: String): LocalReferenceEntry? {
        val parsed = Uri.parse(uri)
        if (!durable(parsed)) throw LocalReferenceException("LOCAL_REFERENCE_PERMISSION_REQUIRED")
        return resolver.query(parsed, documentProjection, null, null, null)?.use {
            if (it.moveToFirst()) documentEntry(it, uri, key) else null
        }
    }

    override fun treeName(uri: String): String? {
        val root = Uri.parse(uri)
        if (!durable(root)) throw LocalReferenceException("LOCAL_REFERENCE_PERMISSION_REQUIRED")
        val document = DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
        return resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.string(DocumentsContract.Document.COLUMN_DISPLAY_NAME).takeIf(String::isNotEmpty) else null
        }
    }

    override fun tree(uri: String): List<LocalReferenceEntry> {
        val root = Uri.parse(uri)
        if (!durable(root)) throw LocalReferenceException("LOCAL_REFERENCE_PERMISSION_REQUIRED")
        val result = mutableListOf<LocalReferenceEntry>()
        val visited = mutableSetOf<String>()
        fun walk(id: String, path: String) {
            if (!visited.add(id)) throw LocalReferenceException("LOCAL_REFERENCE_INVALID_TREE")
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, id)
            val cursor = resolver.query(children, documentProjection, null, null, null) ?: throw LocalReferenceException("LOCAL_REFERENCE_PROVIDER_UNAVAILABLE")
            cursor.use {
                while (it.moveToNext()) {
                    val childId = it.string(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val name = it.string(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (name.isEmpty() || name.contains('/') || name.contains('\\') || name == "." || name == "..") throw LocalReferenceException("LOCAL_REFERENCE_INVALID_TREE")
                    val key = if (path.isEmpty()) name else "$path/$name"
                    if (RemotePaths.normalize(key) != key) throw LocalReferenceException("LOCAL_REFERENCE_INVALID_TREE")
                    if (it.string(DocumentsContract.Document.COLUMN_MIME_TYPE) == DocumentsContract.Document.MIME_TYPE_DIR) walk(childId, key)
                    else {
                        val documentUri = DocumentsContract.buildDocumentUriUsingTree(root, childId).toString()
                        documentEntry(it, documentUri, documentUri)?.copy(folders = mapOf(path to path.substringAfterLast('/')))?.let(result::add)
                    }
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(root), "")
        return result
    }

    private fun documentEntry(cursor: Cursor, uri: String, key: String): LocalReferenceEntry? {
        if (!LocalReferenceRules.supported(cursor.string(DocumentsContract.Document.COLUMN_DISPLAY_NAME), cursor.string(DocumentsContract.Document.COLUMN_MIME_TYPE))) return null
        val size = cursor.number(DocumentsContract.Document.COLUMN_SIZE)
        val modified = cursor.number(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return LocalReferenceEntry(uri, key, "$modified:$size", size, displayName = cursor.string(DocumentsContract.Document.COLUMN_DISPLAY_NAME), versionKnown = modified != null && modified > 0 && size != null)
    }

    override fun decode(uri: String, width: Int, height: Int): Bitmap? = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, Uri.parse(uri))) { decoder, info, _ ->
        val size = LocalReferenceRules.size(info.size.width, info.size.height, width, height)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSize(size.first, size.second)
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        decoder.setOnPartialImageListener { false }
    }

    private fun Cursor.string(column: String): String = getColumnIndex(column).let { if (it < 0 || isNull(it)) "" else getString(it) }
    private fun Cursor.number(column: String): Long? = getColumnIndex(column).let { if (it < 0 || isNull(it)) null else getLong(it) }

    companion object {
        private val documentProjection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    }
}
