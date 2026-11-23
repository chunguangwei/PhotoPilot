package dev.photopilot.scaffold.ui.remote

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.ViewTreeObserver
import android.widget.ImageView
import dev.photopilot.scaffold.data.remote.PlaybackItem
import dev.photopilot.scaffold.data.remote.RemoteMediaRepository
import kotlinx.coroutines.*

class RemoteThumbnailView(
    context: Context,
    private val media: RemoteMediaRepository,
    private val item: PlaybackItem,
    private val targetSize: Int = 192,
    private val priority: Int = 0,
) : ImageView(context) {
    var onUnavailable: (() -> Unit)? = null
    var onLoaded: (() -> Unit)? = null
    var onFailed: ((String) -> Unit)? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var loaded = false
    private var failedAt = 0L
    private var failures = 0
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { loadVisible() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { loadVisible() }

    init {
        scaleType = ScaleType.CENTER_CROP
        contentDescription = item.filename
        setBackgroundColor(context.getColor(dev.photopilot.scaffold.R.color.pp_surface_panel))
    }

    fun showPlaceholder(bitmap: android.graphics.Bitmap) {
        if (!loaded) setImageBitmap(bitmap)
    }

    private fun backoffMillis(): Long = (2000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(30000L)

    private fun loadVisible() {
        val visible = getGlobalVisibleRect(Rect()) && isShown
        if (!visible) { job?.cancel(); job = null; return }
        if (loaded || job?.isActive == true) return
        if (failures > 0 && SystemClock.elapsedRealtime() - failedAt < backoffMillis()) return
        job = scope?.launch {
            val result = runCatching { media.bitmap(item, targetSize, targetSize, priority) }
            if (!isActive || !isAttachedToWindow) return@launch
            val bitmap = result.getOrNull()
            if (bitmap != null && media.valid(item)) {
                failures = 0
                scaleType = if (targetSize > 192) ScaleType.FIT_CENTER else ScaleType.CENTER_CROP
                setImageBitmap(bitmap)
                loaded = true
                onLoaded?.invoke()
                return@launch
            }
            val error = result.exceptionOrNull()
            if (error is CancellationException) return@launch
            failures++
            failedAt = SystemClock.elapsedRealtime()
            val code = error?.message?.takeIf { it.matches(Regex("[A-Z_]+")) } ?: if (error == null) "REMOTE_UNAVAILABLE" else "REMOTE_LOAD_FAILED"
            android.util.Log.w("PhotoPilotThumb", "thumbnail failed code=$code type=${error?.javaClass?.simpleName ?: "null"} attempt=$failures")
            if (drawable == null) {
                scaleType = ScaleType.CENTER
                setImageResource(android.R.drawable.ic_menu_report_image)
            }
            onFailed?.invoke(code)
            if (code == "REMOTE_REVOKED" || code == "REMOTE_UNAVAILABLE") onUnavailable?.invoke()
            postDelayed({ loadVisible() }, backoffMillis())
        }
    }

    fun retryNow() {
        failures = 0
        failedAt = 0
        loadVisible()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        post { loadVisible() }
        scope?.launch {
            while (isAttachedToWindow) {
                delay(1000)
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
