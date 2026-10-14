package dev.photopilot.scaffold.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout

class AppRootView(context: Context) : FrameLayout(context) {

    var baseView: View? = null
        private set

    private val overlays = ArrayDeque<View>()

    fun setBase(view: View) {
        overlays.forEach { removeView(it) }
        overlays.clear()
        baseView?.let { removeView(it) }
        baseView = view
        addView(
            view,
            0,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun pushOverlay(view: View) {
        overlays.addLast(view)
        addView(
            view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun popOverlay(): View? {
        val view = overlays.removeLastOrNull() ?: return null
        removeView(view)
        return view
    }

    fun removeOverlay(view: View): Boolean {
        if (!overlays.remove(view)) return false
        removeView(view)
        return true
    }

    fun topOverlay(): View? = overlays.lastOrNull()

    fun addFloat(view: View, params: LayoutParams) {
        addView(view, params)
    }

    fun removeFloat(view: View) {
        removeView(view)
    }
}
