package dev.photopilot.scaffold

import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridView
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.data.db.FrameOrderMode
import dev.photopilot.scaffold.ui.frames.FrameEditView
import java.util.Locale

class FrameEditorLayoutInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val failures = mutableListOf<String>()
        runOnMainSync {
            for (language in listOf("zh", "en")) {
                for (font in listOf(1f, 1.45f, 2f)) {
                    try { verifyLayout(language, font) } catch (error: Throwable) {
                        failures += "$language font=$font: ${error.stackTraceToString()}"
                    }
                }
            }
        }
        val result = Bundle()
        result.putString("stream", if (failures.isEmpty()) "Frame editor layout passed: zh/en, 100%/145%/200%, portrait and short landscape grid plus reachable Save/Back\n" else "FAILED: ${failures.joinToString("\n")}\n")
        finish(if (failures.isEmpty()) -1 else 0, result)
    }

    private fun verifyLayout(language: String, font: Float) {
        val config = Configuration(targetContext.resources.configuration).apply {
            setLocale(Locale(language))
            fontScale = font
        }
        val context = targetContext.createConfigurationContext(config)
        val view = FrameEditView(context, targetContext.cacheDir)
        view.bind(FrameEditView.Model("Synthetic", FrameOrderMode.CAPTURED_ASC, emptySet(), emptySet(), emptyMap(), false))
        for ((width, height) in listOf(1080 to 2440, 2440 to 1080, 2440 to 900, 1080 to 2440)) {
            fun layout() {
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
            }
            layout()
            val grid = descendants(view).filterIsInstance<GridView>().single()
            check(grid.height >= (80 * context.resources.displayMetrics.density).toInt()) { "Grid collapsed: ${grid.width}x${grid.height} at ${width}x$height" }
            val save = descendants(view).filterIsInstance<TextView>().single { it.text.toString() == context.getString(R.string.common_save) }
            val back = descendants(view).filterIsInstance<TextView>().first { it.text.toString().contains(context.getString(R.string.common_back)) }
            val scroll = generateSequence(save.parent) { it.parent }.filterIsInstance<ScrollView>().firstOrNull()
            scroll?.isSmoothScrollingEnabled = false
            scroll?.let { it.scrollTo(0, it.getChildAt(0).height) }
            layout()
            check(visibleInside(save, view)) { "Save cannot be reached at ${width}x$height" }
            scroll?.scrollTo(0, 0)
            layout()
            check(visibleInside(back, view)) { "Back cannot be reached at ${width}x$height" }
        }
    }

    private fun visibleInside(child: View, root: ViewGroup): Boolean {
        val rect = Rect(0, 0, child.width, child.height)
        root.offsetDescendantRectToMyCoords(child, rect)
        return rect.width() > 0 && rect.height() > 0 && rect.top >= 0 && rect.bottom <= root.height && rect.left >= 0 && rect.right <= root.width
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
