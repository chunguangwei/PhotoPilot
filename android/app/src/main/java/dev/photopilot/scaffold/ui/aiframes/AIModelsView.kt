package dev.photopilot.scaffold.ui.aiframes

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.*
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.aiframes.*
import dev.photopilot.scaffold.ui.widget.*
import kotlinx.coroutines.*

class AIModelsView(context: Context, private val environment: AppEnvironment) : LinearLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val modelTitles = mutableMapOf<String, TextView>()
    private val zh get() = resources.configuration.locales[0].language == "zh"
    private fun words(en: String, cn: String) = if (zh) cn else en
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val updateRows = mutableListOf<suspend () -> Unit>()
    private var poll: Job? = null
    private var pendingFocus: (() -> Unit)? = null
    init {
        orientation = VERTICAL
        addView(TextView(context).apply { text = words("Downloads continue when leaving this page. Pause them explicitly to stop. Photos are processed on this device.", "离开页面后下载仍会继续，可手动暂停。照片仅在此设备处理。") })
        for (model in environment.aiFrames.models.models) addModel(model)
        addView(secondaryButton(context, R.string.common_done).apply {
            text = words("Export AI diagnostics", "导出 AI 诊断日志")
            setOnClickListener {
                scope.launch {
                    val exporter = dev.photopilot.scaffold.data.diagnostics.DiagnosticsExporter(context)
                    val report = withContext(Dispatchers.IO) {
                        exporter.exportJson(null, null) + "\n" + AIModelDiagnostics.snapshot(context)
                    }
                    context.startActivity(android.content.Intent.createChooser(exporter.buildShareIntent(report), text))
                }
            }
        })
    }
    private fun addModel(model: AIModel) {
        val card = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, dp(16), 0, dp(16)) }
        val title = TextView(context).apply { text = if (zh) model.chinese else model.english; textSize = 16f; setTextColor(context.getColor(R.color.pp_text_primary)); isAccessibilityHeading = true }
        card.addView(title)
        card.addView(TextView(context).apply { text = words("%.1f MB · local photo classifier", "%.1f MB · 本地照片分类模型").format(model.bytes / 1e6) })
        val status = TextView(context).apply { setTextColor(context.getColor(R.color.pp_text_secondary)) }
        card.addView(status)
        val source = Spinner(context).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf(words("Automatic source", "自动下载源"), words("Domestic source", "国内下载源"), words("International source", "国际下载源"))) }
        card.addView(TextView(context).apply { text = words("Download source", "下载源") })
        card.addView(source)
        val action = primaryButton(context, R.string.common_continue)
        val pause = secondaryButton(context, R.string.common_cancel).apply { text = words("Pause download", "暂停下载") }
        val delete = secondaryButton(context, R.string.common_delete).apply { text = words("Delete model", "删除模型") }
        card.addView(action, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        card.addView(pause, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        card.addView(delete, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val taskId = "download:${model.id}"
        var preferencesLoaded = false
        scope.launch {
            val record = withContext(Dispatchers.IO) { environment.aiFrames.tasks.record(taskId) }
            source.setSelection(when (record?.source) { "domestic" -> 1; "international" -> 2; else -> 0 })
            preferencesLoaded = true
        }
        fun savePreferences() {
            if (!preferencesLoaded) return
            val chosenSource = listOf("auto", "domestic", "international")[source.selectedItemPosition.coerceAtLeast(0)]
            scope.launch { withContext(Dispatchers.IO) { environment.aiFrames.tasks.saveDownloadPreferences(model, chosenSource, false) } }
        }
        source.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { savePreferences() }
        }
        action.setOnClickListener {
            val chosenSource = listOf("auto", "domestic", "international")[source.selectedItemPosition.coerceAtLeast(0)]
            fun download(allowMetered: Boolean) {
                scope.launch {
                    withContext(Dispatchers.IO) { environment.aiFrames.tasks.startDownload(model, chosenSource, allowMetered) }
                }
            }
            val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            if (capabilities != null && !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                val remaining = (model.bytes - environment.aiFrames.models.downloadedBytes(model)).coerceAtLeast(0) / 1e6
                AlertDialog.Builder(context)
                    .setTitle(words("Download on this network?", "使用当前网络下载？"))
                    .setMessage(words("This network may charge for data. About %.1f MB remains.", "当前网络可能按流量计费，预计还需下载 %.1f MB。").format(remaining))
                    .setNegativeButton(R.string.common_cancel, null)
                    .setPositiveButton(R.string.common_continue) { _, _ -> download(true) }
                    .show()
            } else download(false)
        }
        pause.setOnClickListener { environment.aiFrames.tasks.pause(taskId) }
        delete.setOnClickListener {
            AlertDialog.Builder(context).setMessage(words("Delete this model and its partial download? Photos and frames are retained.", "是否删除此模型和下载断点？照片与相框会保留。"))
                .setNegativeButton(R.string.common_cancel, null).setPositiveButton(R.string.common_delete) { _, _ -> scope.launch { withContext(Dispatchers.IO) { environment.aiFrames.tasks.deleteModel(model) } } }.show()
        }
        updateRows.add {
            val task = withContext(Dispatchers.IO) { environment.aiFrames.tasks.record(taskId) }
            val bytes = environment.aiFrames.models.downloadedBytes(model)
            val deleting = environment.aiFrames.tasks.isDeleting(model.id)
            val ready = !deleting && environment.aiFrames.models.ready(model)
            val running = environment.aiFrames.tasks.running(taskId)
            status.text = when {
                !environment.aiFrames.models.supported(model) -> words("This device cannot run the standard model. Choose the compact model.", "此设备不支持标准模型，请选择轻量版。")
                deleting -> words("Deleting model…", "正在删除模型…")
                ready -> words("Ready", "就绪")
                task?.status in listOf("system_paused", "waiting") -> aiPauseExplanation(task?.reason, zh)
                task?.userPaused == true -> words("Paused · %.1f / %.1f MB", "已暂停 · %.1f / %.1f MB").format(bytes / 1e6, model.bytes / 1e6)
                task?.status == "failed" -> words("Download failed. Check source, network and free storage, then retry.", "下载失败，请检查下载源、网络及可用存储后重试。")
                running -> words("Downloading · %.1f / %.1f MB", "下载中 · %.1f / %.1f MB").format(bytes / 1e6, model.bytes / 1e6)
                else -> words("Download required", "需要下载")
            }
            action.text = if (bytes > 0) words("Continue / retry download", "继续 / 重试下载") else words("Download", "下载")
            action.visibility = if (ready || running) View.GONE else View.VISIBLE
            action.isEnabled = !deleting && environment.aiFrames.models.supported(model)
            pause.visibility = if (running) View.VISIBLE else View.GONE
            delete.visibility = if (ready || bytes > 0 || deleting || task?.status in listOf("failed", "user_paused", "system_paused")) View.VISIBLE else View.GONE
            delete.isEnabled = !deleting
            source.isEnabled = !running && !deleting
        }
        addView(card, LayoutParams(-1, -2))
        modelTitles[model.id] = title
    }
    fun focusModel(modelId: String?, scrollHost: ScrollView) {
        val title = modelTitles[modelId] ?: modelTitles.values.firstOrNull() ?: return
        pendingFocus = {
            postOnAnimation {
                post {
                    if (!title.isAttachedToWindow || !scrollHost.isAttachedToWindow) return@post
                    var ancestor = title.parent
                    while (ancestor != null && ancestor !== scrollHost) ancestor = ancestor.parent
                    if (ancestor !== scrollHost) return@post
                    val bounds = android.graphics.Rect(0, 0, title.width, title.height)
                    scrollHost.offsetDescendantRectToMyCoords(title, bounds)
                    scrollHost.scrollTo(0, bounds.top)
                    title.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
                }
            }
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        poll?.cancel()
        poll = if (isVisible) scope.launch {
            while (isActive) {
                updateRows.forEach { it() }
                pendingFocus?.let { focus ->
                    pendingFocus = null
                    focus()
                }
                delay(700)
            }
        } else null
    }

    override fun onDetachedFromWindow() {
        poll?.cancel()
        scope.cancel()
        super.onDetachedFromWindow()
    }
}
