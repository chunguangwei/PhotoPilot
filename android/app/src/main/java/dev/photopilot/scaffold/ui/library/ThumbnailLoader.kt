package dev.photopilot.scaffold.ui.library

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.render.BitmapRenderer
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class ThumbnailLoader(
    private val originalsDir: File,
    cacheEntries: Int,
) {

    interface Callback {
        fun onLoaded(key: String, bitmap: Bitmap?)
    }

    private val cache = sharedCache
    private val main = Handler(Looper.getMainLooper())

    fun peek(key: String): Bitmap? = cache.get(key)

    fun load(asset: AssetEntity, sizePx: Int, key: String, callback: Callback) {
        val cached = cache.get(key)
        if (cached != null) {
            callback.onLoaded(key, cached)
            return
        }
        val pending = inFlight.putIfAbsent(key, CompletableFuture())
        if (pending == null) {
            sharedExecutor.execute {
                val bitmap = decode(asset, sizePx)
                if (bitmap != null) cache.put(key, bitmap)
                inFlight.remove(key)?.complete(bitmap)
                main.post { callback.onLoaded(key, bitmap) }
            }
        } else {
            pending.whenComplete { bitmap, _ -> main.post { callback.onLoaded(key, bitmap) } }
        }
    }

    private fun decode(asset: AssetEntity, sizePx: Int): Bitmap? {
        val file = File(originalsDir, asset.originalRelPath)
        if (!file.isFile) return null
        return runCatching {
            if (asset.mediaKind == dev.photopilot.scaffold.data.db.MediaKind.VIDEO) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val frame = retriever.getFrameAtTime(FIRST_FRAME_TIME_US)
                    if (frame == null) null else fit(frame, sizePx)
                } finally {
                    retriever.release()
                }
            } else {
                BitmapRenderer.decodeToTarget(file, sizePx, sizePx, asset.exifOrientation.toInt())?.let { fit(it, sizePx) }
            }
        }.getOrNull()
    }

    private fun fit(bitmap: Bitmap, sizePx: Int): Bitmap {
        val scale = maxOf(bitmap.width, bitmap.height).toFloat() / sizePx
        if (scale <= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width / scale).toInt().coerceAtLeast(1),
            (bitmap.height / scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    companion object {
        const val DECODE_THREADS = 2
        const val FIRST_FRAME_TIME_US = 500_000L
        const val CACHE_BYTES = 24 * 1024 * 1024

        private val sharedCache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
        }
        private val inFlight = ConcurrentHashMap<String, CompletableFuture<Bitmap?>>()

        fun clearMemoryCaches() {
            sharedCache.evictAll()
        }

        private val sharedExecutor = Executors.newFixedThreadPool(DECODE_THREADS)

        fun cacheKey(assetId: String, sizePx: Int): String = "$assetId@$sizePx"
    }
}
