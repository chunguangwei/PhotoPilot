package dev.photopilot.scaffold.ui.remote

import android.content.Context
import android.graphics.Rect
import android.view.ViewTreeObserver
import android.widget.ImageView
import dev.photopilot.scaffold.data.remote.PlaybackItem
import dev.photopilot.scaffold.data.remote.RemoteMediaRepository
import kotlinx.coroutines.*

class RemoteThumbnailView(context: Context, private val media: RemoteMediaRepository, private val item: PlaybackItem, private val targetSize: Int = 192) : ImageView(context) {
    var onUnavailable: (() -> Unit)? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var loaded = false
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { loadVisible() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { loadVisible() }
    init { scaleType = ScaleType.CENTER_CROP; contentDescription = item.filename }
    private fun loadVisible() {
        val visible = getGlobalVisibleRect(Rect()) && isShown
        if (!visible) { job?.cancel(); job = null; return }
        if (loaded || job?.isActive == true) return
        job = scope?.launch {
            val bitmap = runCatching { media.bitmap(item, targetSize, targetSize) }.getOrNull()
            if (bitmap != null && media.valid(item) && isAttachedToWindow) { setImageBitmap(bitmap); loaded = true }
            else if (isAttachedToWindow) onUnavailable?.invoke()
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        post { loadVisible() }
        scope?.launch {
            while (isAttachedToWindow) {
                delay(250)
                if (loaded && !media.valid(item)) { setImageDrawable(null); loaded = false; job?.cancel(); onUnavailable?.invoke() }
            }
        }
    }
    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(scrollListener)
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        scope?.cancel()
        job = null
        setImageDrawable(null)
        loaded = false
        super.onDetachedFromWindow()
    }
}
