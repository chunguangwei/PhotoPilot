package dev.photopilot.scaffold.ui.library

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.render.BitmapRenderer
import java.io.File
import java.util.concurrent.Executors

class ThumbnailLoader(
    private val originalsDir: File,
    cacheEntries: Int,
) {

    interface Callback {
        fun onLoaded(key: String, bitmap: Bitmap?)
    }

    private val cache = LruCache<String, Bitmap>(cacheEntries.coerceAtLeast(1))
    private val main = Handler(Looper.getMainLooper())

    fun load(asset: AssetEntity, sizePx: Int, key: String, callback: Callback) {
        val cached = cache.get(key)
        if (cached != null) {
            callback.onLoaded(key, cached)
            return
        }
        sharedExecutor.execute {
            val file = File(originalsDir, asset.originalRelPath)
            val bitmap = if (file.isFile) {
                runCatching {
                    if (asset.mediaKind == dev.photopilot.scaffold.data.db.MediaKind.VIDEO) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            val frame = retriever.getFrameAtTime(FIRST_FRAME_TIME_US)
                            if (frame == null) {
                                null
                            } else {
                                val scale = maxOf(frame.width, frame.height).toFloat() / sizePx
                                if (scale > 1f) {
                                    android.graphics.Bitmap.createScaledBitmap(
                                        frame,
                                        (frame.width / scale).toInt().coerceAtLeast(1),
                                        (frame.height / scale).toInt().coerceAtLeast(1),
                                        true,
                                    )
                                } else {
                                    frame
                                }
                            }
                        } finally {
                            retriever.release()
                        }
                    } else {
                        BitmapRenderer.decodeToTarget(file, sizePx, sizePx, asset.exifOrientation.toInt())
                    }
                }.getOrNull()
            } else {
                null
            }
            if (bitmap != null) {
                cache.put(key, bitmap)
            }
            main.post { callback.onLoaded(key, bitmap) }
        }
    }

    companion object {
        const val DECODE_THREADS = 2
        const val FIRST_FRAME_TIME_US = 500_000L

        private val sharedExecutor = Executors.newFixedThreadPool(DECODE_THREADS)

        fun cacheKey(assetId: String, sizePx: Int): String = "$assetId@$sizePx"
    }
}
