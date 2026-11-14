package dev.photopilot.scaffold.ui.settings

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.widget.secondaryButton
import java.util.concurrent.Executors

class AILicensesView(context: Context, private val onBack: () -> Unit) : LinearLayout(context) {
    private val chinese = resources.configuration.locales[0].language == "zh"
    private val executor = Executors.newSingleThreadExecutor()
    private val body = TextView(context).apply {
        textSize = 15f
        setTextColor(context.getColor(R.color.pp_text_primary))
        setTextIsSelectable(true)
        val inset = resources.getDimensionPixelSize(R.dimen.pp_space_16)
        setPadding(inset, inset, inset, inset)
        text = if (chinese) "正在加载许可…" else "Loading licenses…"
    }
    private val scroll = ScrollView(context).apply { addView(body) }
    private var pages = emptyList<String>()
    private var page = 0
    private val previous = secondaryButton(context, R.string.common_done).apply {
        text = if (chinese) "上一页" else "Previous page"
        isEnabled = false
        setOnClickListener { page--; render() }
    }
    private val next = secondaryButton(context, R.string.common_done).apply {
        text = if (chinese) "下一页" else "Next page"
        isEnabled = false
        setOnClickListener { page++; render() }
    }
    private val indicator = TextView(context)
    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        addView(secondaryButton(context, R.string.common_done).apply { text = if (chinese) "返回许可列表" else "Back to licenses"; setOnClickListener { onBack() } })
        addView(indicator)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val buttons = LinearLayout(context)
        buttons.addView(previous, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(next, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(buttons)
        executor.execute {
            val text = runCatching { context.assets.open("ai-third-party-notices.txt").bufferedReader(Charsets.UTF_8).use { it.readText() } }
            post {
                text.onSuccess { pages = it.chunked(8000); render() }.onFailure { body.text = if (chinese) "许可文件加载失败，请重新打开。" else "Could not load licenses. Reopen this page." }
            }
        }
    }
    private fun render() {
        body.text = pages.getOrElse(page) { "" }
        indicator.text = "${page + 1} / ${pages.size}"
        previous.isEnabled = page > 0
        next.isEnabled = page + 1 < pages.size
        scroll.scrollTo(0, 0)
    }
    override fun onDetachedFromWindow() { executor.shutdownNow(); super.onDetachedFromWindow() }
}
