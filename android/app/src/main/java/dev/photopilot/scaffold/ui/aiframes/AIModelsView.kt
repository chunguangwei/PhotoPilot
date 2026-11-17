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

class AIModelsView(context: Context, private val environment: AppEnvironment, private val focusModel: String? = null, onBack: () -> Unit) : LinearLayout(context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scroll = ScrollView(context)
    private val column = LinearLayout(context).apply { orientation = VERTICAL }
    private val zh get() = resources.configuration.locales[0].language == "zh"
    private fun words(en: String, cn: String) = if (zh) cn else en
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val updateRows = mutableListOf<suspend () -> Unit>()
    private var poll: Job? = null
    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        addView(formHeader(context, words("AI model management", "AI 模型管理"), onBack))
        scroll.addView(column)
        addView(scroll, LayoutParams(-1, 0, 1f))
        column.setPadding(dp(16), dp(8), dp(16), dp(24))
        column.addView(TextView(context).apply { text = words("Downloads continue when leaving this page. Pause them explicitly to stop. Photos are processed on this device.", "离开页面后下载仍会继续，可手动暂停。照片仅在此设备处理。") })
        for (model in environment.aiFrames.models.models) addModel(model)
    }
    private fun addModel(model: AIModel) {
        val card = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, dp(16), 0, dp(16)) }
        val title = TextView(context).apply { text = if (zh) model.chinese else model.english; textSize = 21f; isAccessibilityHeading = true }
        card.addView(title)
        card.addView(TextView(context).apply { text = words("%.1f MB · local photo classifier", "%.1f MB · 本地照片分类模型").format(model.bytes / 1e6) })
        val status = TextView(context)
        card.addView(status)
        val source = Spinner(context).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf(words("Automatic source", "自动下载源"), words("Domestic source", "国内下载源"), words("International source", "国际下载源"))) }
        card.addView(TextView(context).apply { text = words("Download source", "下载源") })
        card.addView(source)
        val metered = Switch(context).apply { text = words("Allow mobile / metered network", "允许移动网络或按流量计费网络") }
        card.addView(metered)
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
            metered.isChecked = record?.allowMetered == true
            preferencesLoaded = true
        }
        fun savePreferences() {
            if (!preferencesLoaded) return
            val chosenSource = listOf("auto", "domestic", "international")[source.selectedItemPosition.coerceAtLeast(0)]
            val allowMetered = metered.isChecked
            scope.launch { withContext(Dispatchers.IO) { environment.aiFrames.tasks.saveDownloadPreferences(model, chosenSource, allowMetered) } }
        }
        source.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { savePreferences() }
        }
        metered.setOnCheckedChangeListener { _, _ -> savePreferences() }
        action.setOnClickListener {
            scope.launch {
                val chosenSource = listOf("auto", "domestic", "international")[source.selectedItemPosition]
                val allowMetered = metered.isChecked
                withContext(Dispatchers.IO) { environment.aiFrames.tasks.startDownload(model, chosenSource, allowMetered) }
            }
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
            metered.isEnabled = !running && !deleting
        }
        column.addView(card, LayoutParams(-1, -2))
        if (model.id == focusModel) scroll.post { scroll.smoothScrollTo(0, card.top); title.requestFocus(); title.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        poll = scope.launch { while (isActive) { updateRows.forEach { it() }; delay(700) } }
    }
    override fun onDetachedFromWindow() { poll?.cancel(); scope.cancel(); super.onDetachedFromWindow() }
}
