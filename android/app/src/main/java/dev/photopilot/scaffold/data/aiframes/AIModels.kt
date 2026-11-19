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
import org.json.JSONObject

data class AIModelFile(val name: String, val size: Long, val sha256: String, val international: List<String>, val domestic: List<String>, val role: String = "model", val revision: String? = null)
data class AIModel(val id: String, val revision: String, val runtime: String, val english: String, val chinese: String, val files: List<AIModelFile>, val defaultThreshold: Float = .20f, val embeddingDimension: Int = 768) {
    val bytes: Long get() = files.sumOf { it.size }
    val preprocessingVersion: String get() = if (runtime == "onnx") "clip-bicubic-wordpiece-projection1" else "preprocess1"
}

class AIModelManager(
    private val context: Context,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private val connections = java.util.concurrent.ConcurrentHashMap<String, HttpURLConnection>()
    fun interruptDownload(modelId: String) {
        val connection = connections.remove(modelId) ?: return
        runCatching { connection.disconnect() }
    }
    private val inferenceLock = Mutex()
    private val modelLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun modelLock(model: AIModel) = modelLocks.getOrPut(model.id) { Mutex() }
    private suspend fun <T> tracedLock(
        lock: Mutex, name: String, task: String, timeoutMs: Long? = null, action: suspend () -> T,
    ): T {
        val owner = Any()
        var acquiredAt: Long? = null
        AIModelDiagnostics.waiting(context, name, task, "waiting")
        try {
            suspend fun acquire(): Boolean {
                lock.lock(owner)
                acquiredAt = android.os.SystemClock.elapsedRealtime()
                AIModelDiagnostics.acquired(context, name, task)
                return true
            }
            val acquired = if (timeoutMs == null) acquire() else kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { acquire() }
            if (acquired != true) {
                AIModelDiagnostics.waiting(context, name, task, "timeout")
                throw IOException("model_queue_timeout")
            }
            return action()
        } finally {
            if (lock.holdsLock(owner)) {
                AIModelDiagnostics.released(context, name, task, android.os.SystemClock.elapsedRealtime() - (acquiredAt ?: android.os.SystemClock.elapsedRealtime()))
                lock.unlock(owner)
            }
        }
    }

    private fun taskLabel(kind: String, model: AIModel) =
        "$kind/${java.util.UUID.randomUUID().toString().take(8)} model=${model.id}"

    suspend fun <T> withModelFiles(model: AIModel, action: suspend () -> T): T =
        withModelFiles(model, "maintenance", action)

    private suspend fun <T> withModelFiles(model: AIModel, kind: String, action: suspend () -> T): T =
        tracedLock(modelLock(model), "files:${model.id}", taskLabel(kind, model), action = action)

    suspend fun <T> lease(model: AIModel, action: suspend () -> T): T {
        val task = taskLabel("frame", model)
        return tracedLock(modelLock(model), "files:${model.id}", task) {
            tracedLock(inferenceLock, "inference", task, action = action)
        }
    }

    suspend fun <T> previewLease(model: AIModel, action: suspend () -> T): T {
        val task = taskLabel("preview", model)
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        return tracedLock(modelLock(model), "files:${model.id}", task, 30_000L) {
            val remaining = (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L)
            tracedLock(inferenceLock, "inference", task, remaining, action)
        }
    }
    fun downloadedBytes(model: AIModel): Long = model.files.sumOf { file ->
        val folder = directory(model)
        val readyFile = File(folder, file.name)
        if (readyFile.isFile && readyFile.length() == file.size && File(folder, file.name + ".verified").readTextOrNull() == file.sha256) file.size
        else File(folder, file.name + ".part").length().coerceIn(0, file.size)
    }
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
                    AIModelFile(name, size, hash, AIConfiguration.strings(file.getJSONObject("urls").getJSONArray("international")), AIConfiguration.strings(file.getJSONObject("urls").getJSONArray("domestic")), file.getString("role"), file.optString("revision").takeIf { it.isNotEmpty() })
                }, model.optDouble("defaultThreshold", .20).toFloat(), model.getInt("embeddingDimension"))
        }.also { require(it.map { model -> model.id }.distinct().size == it.size) }
    }
    fun supported(model: AIModel): Boolean =
        model.runtime != "litertlm" || (android.os.Process.is64Bit() && AIMemoryPolicy.supportsGeneration(context))
    fun directory(model: AIModel): File = File(root, "${model.id}-${model.revision}").apply { mkdirs() }
    fun ready(model: AIModel): Boolean = model.files.all { File(directory(model), it.name).let { file -> file.isFile && file.length() == it.size && File(directory(model), it.name + ".verified").readTextOrNull() == it.sha256 } }
    fun verify(model: AIModel): Boolean = model.files.all { file ->
        val artifact = File(directory(model), file.name)
        val marker = File(directory(model), file.name + ".verified")
        when {
            !artifact.isFile || artifact.length() != file.size -> {
                marker.delete()
                false
            }
            marker.readTextOrNull() == file.sha256 -> true
            hash(artifact) == file.sha256 -> {
                runCatching { marker.writeText(file.sha256) }
                true
            }
            else -> {
                marker.delete()
                false
            }
        }
    }
    suspend fun delete(model: AIModel) = withModelFiles(model, "delete") {
        val folder = directory(model)
        folder.deleteRecursively()
        File(context.codeCacheDir, "ai-runtime/${folder.name}").deleteRecursively()
        Unit
    }

    suspend fun download(model: AIModel, source: String, allowMetered: Boolean = false, shouldStop: () -> Boolean = { false }, progress: (Long, Long) -> Unit) = withModelFiles(model, "download") {
        require(supported(model)) { "runtime_unavailable" }
        var installed = 0L
        for (file in model.files) {
            ensureCanContinue(shouldStop)
            val destination = File(directory(model), file.name)
            if (destination.isFile && destination.length() == file.size && hash(destination) == file.sha256) {
                ensureCanContinue(shouldStop)
                atomicWrite(File(directory(model), file.name + ".verified"), file.sha256)
                installed += file.size
                continue
            }
            val partial = File(directory(model), file.name + ".part")
            if (partial.isFile && partial.length() == file.size && hash(partial) == file.sha256) {
                ensureCanContinue(shouldStop)
                if (!partial.renameTo(destination)) throw IOException("install")
                atomicWrite(File(directory(model), file.name + ".verified"), file.sha256)
                File(directory(model), file.name + ".resume").delete()
                installed += file.size
                progress(installed, model.bytes)
                continue
            }
            val urls = when (source) { "domestic" -> file.domestic; "international" -> file.international; else -> file.domestic + file.international }
            require(urls.isNotEmpty()) { "source_unavailable" }
            var lastFailure: Exception? = null
            var done = false
            for (url in urls) {
                currentCoroutineContext().ensureActive()
                try {
                    transfer(model, file, url, allowMetered, shouldStop) { bytes -> progress(installed + bytes, model.bytes) }
                    done = true
                    break
                } catch (failure: IOException) {
                    if (failure.message in listOf("system_pause", "offline", "metered", "REMOTE_LOW_SPACE")) throw failure
                    lastFailure = failure
                }
            }
            if (!done) throw lastFailure ?: IOException("download")
            installed += file.size
        }
    }

    private suspend fun transfer(model: AIModel, file: AIModelFile, url: String, allowMetered: Boolean, shouldStop: () -> Boolean, progress: (Long) -> Unit) {
        ensureCanContinue(shouldStop)
        checkNetwork(allowMetered)
        require(URL(url).protocol == "https")
        val folder = directory(model)
        val part = File(folder, file.name + ".part")
        val identity = File(folder, file.name + ".resume")
        val saved = try { identity.readTextOrNull()?.let { JSONObject(it) } } catch (_: org.json.JSONException) { identity.delete(); part.delete(); null }
        val validIdentity = saved?.optString("url") == url && saved.optString("sha256") == file.sha256 && saved.optString("etag").isNotEmpty()
        var offset = if (validIdentity && part.length() < file.size) part.length() else 0L
        if (offset == 0L) part.delete()
        val connection = connectionFactory(URL(url))
        connections[model.id] = connection
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
        if (offset > 0L) {
            connection.setRequestProperty("Range", "bytes=$offset-")
            connection.setRequestProperty("If-Range", saved!!.getString("etag"))
        }
        try {
            ensureCanContinue(shouldStop)
            val status = connection.responseCode
            if (status !in listOf(200, 206)) throw IOException("http_$status")
            if (status == 200) offset = 0
            if (status == 206 && (connection.getHeaderField("Content-Range")?.startsWith("bytes $offset-") != true || connection.getHeaderField("ETag") != saved?.optString("etag"))) {
                part.delete(); identity.delete()
                throw IOException("range_identity")
            }
            val etag = connection.getHeaderField("ETag").orEmpty()
            atomicWrite(identity, JSONObject().put("url", url).put("etag", etag).put("sha256", file.sha256).toString())
            dev.photopilot.scaffold.data.remote.DiskReservations.shared.reserve(file.size - offset, { folder.usableSpace }).use { claim ->
            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, offset > 0).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var count = offset
                    while (true) {
                        ensureCanContinue(shouldStop)
                        checkNetwork(allowMetered)
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        if (count > file.size) throw IOException("size")
                        ensureCanContinue(shouldStop)
                        claim.write(read) { output.write(buffer, 0, read) }
                        progress(count)
                    }
                    output.fd.sync()
                }
            }
            }
            ensureCanContinue(shouldStop)
            if (part.length() != file.size || hash(part) != file.sha256) { part.delete(); identity.delete(); throw IOException("integrity") }
            ensureCanContinue(shouldStop)
            val destination = File(folder, file.name)
            if (!part.renameTo(destination)) throw IOException("install")
            atomicWrite(File(folder, file.name + ".verified"), file.sha256)
            identity.delete()
        } finally {
            connections.remove(model.id, connection)
            runCatching { connection.disconnect() }
        }
    }
    private suspend fun ensureCanContinue(shouldStop: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (shouldStop()) throw IOException("system_pause")
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
