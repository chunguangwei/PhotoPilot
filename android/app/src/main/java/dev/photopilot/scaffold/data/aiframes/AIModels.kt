package dev.photopilot.scaffold.data.aiframes

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class AIModelFile(val name: String, val size: Long, val sha256: String, val international: List<String>, val domestic: List<String>)
data class AIModel(val id: String, val revision: String, val runtime: String, val english: String, val chinese: String, val files: List<AIModelFile>, val defaultThreshold: Float = .20f) {
    val bytes: Long get() = files.sumOf { it.size }
}

class AIModelManager(private val context: Context) {
    val lock = Mutex()
    private val root = File(context.filesDir, "ai-models").apply { mkdirs() }
    val models: List<AIModel> by lazy {
        val json = JSONObject(context.assets.open("ai-models.json").bufferedReader().use { it.readText() })
        require(json.getInt("formatVersion") == 1)
        val array = json.getJSONArray("models")
        (0 until array.length()).map { index ->
            val model = array.getJSONObject(index)
            val files = model.getJSONArray("files")
            AIModel(model.getString("id"), model.getString("revision"), model.getString("runtime"), model.getJSONObject("displayName").getString("en"), model.getJSONObject("displayName").getString("zh-Hans"),
                (0 until files.length()).map { fileIndex ->
                    val file = files.getJSONObject(fileIndex)
                    val name = file.getString("name")
                    require(name.matches(Regex("[A-Za-z0-9_.-]+")))
                    val hash = file.getString("sha256")
                    require(hash.matches(Regex("[a-f0-9]{64}")))
                    val size = file.getLong("size")
                    require(size > 0)
                    AIModelFile(name, size, hash, AIConfiguration.strings(file.getJSONObject("urls").getJSONArray("international")), AIConfiguration.strings(file.getJSONObject("urls").getJSONArray("domestic")))
                }, model.optDouble("defaultThreshold", .20).toFloat())
        }.also { require(it.map { model -> model.id }.distinct().size == it.size) }
    }
    fun supported(model: AIModel): Boolean = model.runtime != "litertlm" || android.os.Process.is64Bit()
    fun directory(model: AIModel): File = File(root, "${model.id}-${model.revision}").apply { mkdirs() }
    fun ready(model: AIModel): Boolean = model.files.all { File(directory(model), it.name).let { file -> file.isFile && file.length() == it.size && File(directory(model), it.name + ".verified").readTextOrNull() == it.sha256 } }
    fun verify(model: AIModel): Boolean = model.files.all { file ->
        val artifact = File(directory(model), file.name)
        val valid = artifact.isFile && artifact.length() == file.size && hash(artifact) == file.sha256
        if (!valid) File(directory(model), file.name + ".verified").delete()
        valid
    }
    suspend fun delete(model: AIModel) = lock.withLock {
        val folder = directory(model)
        folder.deleteRecursively()
        File(context.codeCacheDir, "ai-runtime/${folder.name}").deleteRecursively()
        Unit
    }

    suspend fun download(model: AIModel, source: String, allowMetered: Boolean = false, progress: (Long, Long) -> Unit) = lock.withLock {
        require(supported(model)) { "runtime_unavailable" }
        var installed = 0L
        for (file in model.files) {
            currentCoroutineContext().ensureActive()
            val destination = File(directory(model), file.name)
            if (destination.isFile && destination.length() == file.size && hash(destination) == file.sha256) {
                atomicWrite(File(directory(model), file.name + ".verified"), file.sha256)
                installed += file.size
                continue
            }
            val urls = when (source) { "domestic" -> file.domestic; "international" -> file.international; else -> file.domestic + file.international }
            require(urls.isNotEmpty()) { "source_unavailable" }
            var lastFailure: Exception? = null
            var done = false
            for (url in urls) {
                currentCoroutineContext().ensureActive()
                try {
                    transfer(model, file, url, allowMetered) { bytes -> progress(installed + bytes, model.bytes) }
                    done = true
                    break
                } catch (failure: IOException) { lastFailure = failure }
            }
            if (!done) throw lastFailure ?: IOException("download")
            installed += file.size
        }
    }

    private suspend fun transfer(model: AIModel, file: AIModelFile, url: String, allowMetered: Boolean, progress: (Long) -> Unit) {
        checkNetwork(allowMetered)
        require(URL(url).protocol == "https")
        val folder = directory(model)
        val part = File(folder, file.name + ".part")
        val identity = File(folder, file.name + ".resume")
        val saved = try { identity.readTextOrNull()?.let { JSONObject(it) } } catch (_: org.json.JSONException) { identity.delete(); part.delete(); null }
        val validIdentity = saved?.optString("url") == url && saved.optString("sha256") == file.sha256 && saved.optString("etag").isNotEmpty()
        var offset = if (validIdentity && part.length() < file.size) part.length() else 0L
        if (offset == 0L) part.delete()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
        if (offset > 0L) {
            connection.setRequestProperty("Range", "bytes=$offset-")
            connection.setRequestProperty("If-Range", saved!!.getString("etag"))
        }
        try {
            val status = connection.responseCode
            if (status !in listOf(200, 206)) throw IOException("http_$status")
            if (status == 200) offset = 0
            if (status == 206 && (connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") != true || connection.getHeaderField("ETag") != saved?.optString("etag"))) {
                part.delete(); identity.delete()
                throw IOException("range_identity")
            }
            val reclaimable = if (offset == 0L) part.length() else 0L
            if (folder.usableSpace + reclaimable < file.size - offset + 64 * 1024) throw IOException("storage")
            val etag = connection.getHeaderField("ETag").orEmpty()
            atomicWrite(identity, JSONObject().put("url", url).put("etag", etag).put("sha256", file.sha256).toString())
            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, offset > 0).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var count = offset
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        checkNetwork(allowMetered)
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > file.size) throw IOException("size")
                        output.write(buffer, 0, read)
                        progress(count)
                    }
                    output.fd.sync()
                }
            }
            if (part.length() != file.size || hash(part) != file.sha256) { part.delete(); identity.delete(); throw IOException("integrity") }
            currentCoroutineContext().ensureActive()
            val destination = File(folder, file.name)
            if (!part.renameTo(destination)) throw IOException("install")
            atomicWrite(File(folder, file.name + ".verified"), file.sha256)
            identity.delete()
        } finally { connection.disconnect() }
    }
    private fun checkNetwork(allowMetered: Boolean) {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val network = manager.getNetworkCapabilities(manager.activeNetwork) ?: throw IOException("offline")
        if (!allowMetered && !network.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) throw IOException("metered")
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(128 * 1024); while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun atomicWrite(file: File, text: String) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(text.toByteArray()); atomic.finishWrite(output) } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
    private fun File.readTextOrNull() = if (isFile) readText() else null
}
