package dev.photopilot.scaffold

import android.app.Instrumentation
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.photopilot.scaffold.ui.sources.SourcesView
import java.util.Locale

class SourcesLayoutInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            var failure: Throwable? = null
            runOnMainSync {
                try {
                    verifyActionLabelsRemainVisible()
                } catch (error: Throwable) {
                    failure = error
                }
            }
            failure?.let { throw it }
            result.putString("stream", "Sources action layout passed: zh/en, narrow/wide, 100%/200%, callbacks\n")
            finish(-1, result)
        } catch (failure: Throwable) {
            result.putString("stream", "FAILED: ${failure.message}\n")
            finish(0, result)
        }
    }

    private fun verifyActionLabelsRemainVisible() {
        for (language in listOf("en", "zh")) {
            for (fontScale in listOf(1f, 2f)) {
                val config = Configuration(targetContext.resources.configuration)
                config.setLocale(Locale(language))
                config.fontScale = fontScale
                val context = targetContext.createConfigurationContext(config)
                for (widthDp in listOf(320, 432, 1200)) {
                    val view = SourcesView(context)
                    view.renderNasSources(listOf(SourcesView.NasSourceModel("synthetic", "Review source", "Connected")))
                    val width = (widthDp * context.resources.displayMetrics.density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, 2400)
                    var called = ""
                    view.onNasDisconnect = { called = it }
                    val labels = descendants(view).filterIsInstance<TextView>().filter { it.text.toString() in listOf(context.getString(R.string.sources_sync_now), context.getString(R.string.common_edit), context.getString(R.string.sources_disconnect)) }
                    check(labels.size == 3)
                    for (label in labels) {
                        val textLayout = checkNotNull(label.layout)
                        val textHeight = textLayout.getLineBottom(textLayout.lineCount - 1)
                        val available = label.height - label.compoundPaddingTop - label.compoundPaddingBottom
                        check(textHeight <= available) { "$language font=$fontScale width=$widthDp ${label.text}: textHeight=$textHeight available=$available lines=${textLayout.lineCount}" }
                        val row = label.parent as LinearLayout
                        val textBottom = label.top + label.compoundPaddingTop + textHeight
                        check(textBottom <= row.height) { "$language font=$fontScale width=$widthDp ${label.text}: textBottom=$textBottom clipped by rowHeight=${row.height}" }
                        val card = row.parent as LinearLayout
                        val mirror = card.getChildAt(card.indexOfChild(row) + 1)
                        check(row.top + textBottom <= mirror.top) { "$language font=$fontScale width=$widthDp ${label.text}: overlaps following control" }
                        check((0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 })
                        check(label.height >= context.resources.getDimensionPixelSize(R.dimen.pp_touch_target))
                    }
                    labels.single { it.text.toString() == context.getString(R.string.sources_disconnect) }.performClick()
                    check(called == "synthetic")
                }
            }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
