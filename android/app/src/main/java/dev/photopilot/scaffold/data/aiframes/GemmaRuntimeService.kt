package dev.photopilot.scaffold.data.aiframes

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native runtime faults are confined to this private process. No library or frame state is mutated here. */
class GemmaRuntimeService : Service() {
    private var embedder: GemmaEmbedder? = null
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in 1..4 || reply == null) return super.onTransact(code, data, reply, flags)
            synchronized(this@GemmaRuntimeService) {
                try {
                    val result = when (code) {
                        1 -> { embedder?.close(); embedder = GemmaEmbedder(this@GemmaRuntimeService, File(requireNotNull(data.readString())), data.readInt() == 1); null }
                        2 -> requireNotNull(embedder).text(requireNotNull(data.readString()))
                        3 -> {
                            val bitmap = requireNotNull(BitmapFactory.decodeFile(requireNotNull(data.readString())))
                            try { requireNotNull(embedder).image(bitmap) } finally { bitmap.recycle() }
                        }
                        else -> { embedder?.close(); embedder = null; null }
                    }
                    reply.writeNoException()
                    reply.writeFloatArray(result)
                } catch (failure: Exception) { reply.writeException(if (failure.message == "description_too_long") IllegalArgumentException("description_too_long") else IllegalStateException("runtime_inference")) }
                catch (_: LinkageError) { reply.writeException(IllegalStateException("runtime_unavailable")) }
            }
            return true
        }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() { embedder?.close(); embedder = null; super.onDestroy() }
}

class RemoteGemmaEmbedder(private val context: Context, model: File, private val useGpu: Boolean = false) : AIEmbedder {
    private val connectionReady = CountDownLatch(1)
    @Volatile private var remote: IBinder? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { remote = service; connectionReady.countDown() }
        override fun onServiceDisconnected(name: ComponentName) { remote = null; connectionReady.countDown() }
        override fun onNullBinding(name: ComponentName) { connectionReady.countDown() }
    }
    init {
        bound = context.bindService(Intent(context, GemmaRuntimeService::class.java), connection, Context.BIND_AUTO_CREATE)
        try {
            if (!bound || !connectionReady.await(15, TimeUnit.SECONDS)) throw IOException("runtime_connection")
            transact(1, model.absolutePath)
        } catch (failure: Throwable) { close(); throw failure }
    }
    private fun transact(code: Int, value: String? = null): FloatArray {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            if (value != null) request.writeString(value)
            if (code == 1) request.writeInt(if (useGpu) 1 else 0)
            val service = remote ?: throw IOException("runtime_unavailable")
            if (!service.transact(code, request, response, 0)) throw IOException("runtime_unavailable")
            response.readException()
            return response.createFloatArray() ?: FloatArray(0)
        } catch (failure: android.os.RemoteException) { throw IOException("runtime_unavailable", failure) }
        finally { request.recycle(); response.recycle() }
    }
    override fun text(text: String): FloatArray = transact(2, text)
    override fun image(bitmap: Bitmap): FloatArray {
        val folder = File(context.cacheDir, "ai-inference").apply { mkdirs() }
        val file = File(folder, UUID.randomUUID().toString() + ".png")
        try {
            file.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return transact(3, file.absolutePath)
        } finally { file.delete() }
    }
    override fun close() {
        if (bound) {
            runCatching { if (remote?.isBinderAlive == true) transact(4) }
            context.unbindService(connection)
            bound = false
        }
        remote = null
    }
}
