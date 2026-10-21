package dev.photopilot.scaffold

import android.app.Instrumentation
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.photopilot.scaffold.ui.player.PlaybackSettingsView
import java.util.Locale

class PlaybackLayoutInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val failures = mutableListOf<String>()
        runOnMainSync {
            for (language in listOf("en", "zh")) {
                for (fontScale in listOf(1f, 2f)) {
                    for (widths in listOf(listOf(900), listOf(432, 900, 432, 900))) {
                        val case = "$language font=$fontScale widths=$widths"
                        try {
                            verifyLayout(language, fontScale, widths)
                        } catch (failure: Throwable) {
                            failures += "$case: ${failure.stackTraceToString()}"
                        }
                    }
                }
            }
        }
        if (failures.isEmpty()) {
            result.putString("stream", "Playback layout passed: zh/en, 100%/200%, first wide layout and narrow/wide cycles\n")
            finish(-1, result)
        } else {
            result.putString("stream", "FAILED: ${failures.joinToString("\n")}\n")
            finish(0, result)
        }
    }

    private fun verifyLayout(language: String, fontScale: Float, widths: List<Int>) {
        val config = Configuration(targetContext.resources.configuration)
        config.setLocale(Locale(language))
        config.fontScale = fontScale
        val context = targetContext.createConfigurationContext(config)
        val density = context.resources.displayMetrics.density
        val view = PlaybackSettingsView(context)
        val height = (1200 * density).toInt()
        for (widthDp in widths) {
            val width = (widthDp * density).toInt()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
            val buttons = descendants(view).filterIsInstance<TextView>()
            val save = buttons.single { it.text.toString() == context.getString(R.string.common_save) }
            val cancel = buttons.single { it.text.toString() == context.getString(R.string.common_cancel) }
            val bar = save.parent as LinearLayout
            check(cancel.parent === bar)
            check(bar.orientation == if (widthDp >= 600) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL)
            check(save.width > 0 && save.height > 0 && cancel.width > 0 && cancel.height > 0)
            if (widthDp >= 600) {
                check(cancel.right <= save.left) { "wide actions overlap" }
            } else {
                check(save.bottom <= cancel.top) { "narrow actions overlap" }
            }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
