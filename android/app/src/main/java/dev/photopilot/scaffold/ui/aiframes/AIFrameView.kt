package dev.photopilot.scaffold.ui.aiframes

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.aiframes.*
import dev.photopilot.scaffold.data.db.FrameEntity
import dev.photopilot.scaffold.data.repo.SelectionRefEdit
import kotlinx.coroutines.*

class AIFrameView(
    context: Context,
    private val environment: AppEnvironment,
    private val scope: CoroutineScope,
    private val existingFrame: FrameEntity?,
    private val onClose: () -> Unit,
    private val onChanged: () -> Unit,
    private val restoredDraft: Map<String, String>? = null,
) : LinearLayout(context) {
    var onManageModels: ((String) -> Unit)? = null
    private var draftId = restoredDraft?.get("draftId") ?: java.util.UUID.randomUUID().toString()
    private var shownTaskGeneration: Long? = null
    private var shownLibraryVersion: Long? = null
    var onLocalPhotos: (() -> Unit)? = null
    var onLocalFiles: (() -> Unit)? = null
    var onLocalFolder: (() -> Unit)? = null
    var onConnect: (() -> Unit)? = null
    private val chinese = resources.configuration.locales[0].language == "zh"
    private fun words(zh: String, en: String) = if (chinese) zh else en
    private val column = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
    private val contentScroll = ScrollView(context)
    private val footer = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
    private val modelSection = dev.photopilot.scaffold.ui.widget.DisclosureSection(context, words("AI 模型", "AI model"))
    private var editSelectedFolders: TextView? = null
    private val folderSummary = TextView(context)
    private var sourceReload: Job? = null
    private val modelPicker = Spinner(context)
    private val sourcePicker = Spinner(context)
    private var acknowledgedPreviewTimeout: Pair<String, Long>? = null
    private var submittingPreview = false
    private val processing = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val status = TextView(context).apply {
        textSize = 14f
        setTextColor(context.getColor(dev.photopilot.scaffold.R.color.pp_text_secondary))
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        visibility = View.GONE
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }
        })
    }
    private val modelStatus = TextView(context)
    private val folders = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val collectionChecks = linkedMapOf<String, CheckBox>()
    private val recursive = CheckBox(context).apply { text = words("包含子目录", "Include subfolders"); isChecked = true }
    private val automatic = CheckBox(context).apply { text = words("持续自动分类（默认仅本次）", "Automatically classify future imports (off by default)") }
    private val descriptions = input(words("类别描述，每行一个类别", "Category descriptions, one per line"), false)
    private val includes = input(words("包含描述，每行一条（可选）", "Include descriptions, one per line (optional)"), false)
    private val excludes = input(words("排除描述，每行一条（可选）", "Exclude descriptions, one per line (optional)"), false)
    private val allIncludes = CheckBox(context).apply { text = words("需符合全部包含描述", "Require all include descriptions"); isChecked = true }
    private val threshold = input(words("相似度阈值", "Similarity threshold"), true).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText("0.20") }
    private var nameOverrides = emptyList<String>()
    private val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val trackedFrames = mutableListOf<String>()
    private var activePreviewIdentity: String? = null
    private var pendingPreview: List<AIPreview>? = null
    private var previewIdentity: String? = null
    private var previewRevision = 0L
    private var restoredPreviewIdentity: String? = null
    private var restoredPreviewNames: List<String>? = null
    private var savedRuleIdentity: String? = null
    private var restoredPreviewMatches: List<Set<String>>? = null
    private var restoredPreviewChoices: List<Set<String>>? = null
    private var previewNames = emptyList<EditText>()
    private var previewChosen = emptyList<MutableSet<String>>()
    private var downloadButton: TextView? = null
    private var cancelDownloadButton: TextView? = null
    private var deleteModelButton: TextView? = null
    private var startButton: TextView? = null
    private var confirmPreviewButton: TextView? = null
    private var savingPreview = false
    private var cancelButton: TextView? = null
    private var resumeButton: TextView? = null

    private var polling: Job? = null
    private var initialized = false
    private var previousModelId: String? = null
    private var retiredModelNotice = false

    init {
        setBackgroundColor(context.getColor(dev.photopilot.scaffold.R.color.pp_surface_canvas))
        orientation = VERTICAL
        addView(dev.photopilot.scaffold.ui.widget.formHeader(context, words("AI 创建相框", "Create AI frame"), onClose))
        contentScroll.addView(column)
        addView(contentScroll, LayoutParams(-1, 0, 1f))
        label(words("选择照片", "Choose photos"), true)
        label(words("直接使用本机原照片或 NAS，照片仅在此设备上分类。", "Choose device photos or NAS folders. Classification stays on this device."))
        val sourceActions = LinearLayout(context)
        sourceActions.addView(dev.photopilot.scaffold.ui.widget.secondaryButton(context, dev.photopilot.scaffold.R.string.source_local_photos_title).apply {
            text = words("照片库", "Photo Library")
            setOnClickListener { onLocalPhotos?.invoke() }
        }, LayoutParams(0, -2, 1f))
        sourceActions.addView(dev.photopilot.scaffold.ui.widget.secondaryButton(context, dev.photopilot.scaffold.R.string.source_local_files_choose_folder).apply {
            text = words("本机目录", "Device folder")
            setOnClickListener { onLocalFolder?.invoke() }
        }, LayoutParams(0, -2, 1f))
        column.addView(sourceActions)
        button(words("选择照片文件", "Choose photo files")) { onLocalFiles?.invoke() }
        folderSummary.textSize = 15f
        folderSummary.maxLines = 3
        folderSummary.ellipsize = android.text.TextUtils.TruncateAt.END
        folderSummary.setTextColor(context.getColor(dev.photopilot.scaffold.R.color.pp_text_secondary))
        column.addView(LinearLayout(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(folderSummary, LayoutParams(0, -2, 1f))
            editSelectedFolders = dev.photopilot.scaffold.ui.widget.secondaryButton(context, dev.photopilot.scaffold.R.string.common_done).apply {
                text = words("编辑已选", "Edit selected")
                visibility = View.GONE
                setOnClickListener { showSelectedFolders() }
            }
            addView(editSelectedFolders, LayoutParams(-2, -2))
        })
        button(words("选择已连接相册 / NAS", "Choose connected albums / NAS")) { chooseFolders() }
        column.addView(recursive)
        label(words("想展示什么照片？", "What photos would you like to show?"), true)
        label(words("例如家人、宠物、海边，每行一个类别。预览后可修改名称和选片。", "For example, family, pets or the beach, one category per line. Edit names and photo selections in the preview."))
        column.addView(descriptions)
        modelPicker.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, environment.aiFrames.models.models.map { if (chinese) it.chinese else it.english })
        modelSection.body.addView(modelPicker)
        modelSection.body.addView(modelStatus)
        modelSection.body.addView(dev.photopilot.scaffold.ui.widget.secondaryButton(context, dev.photopilot.scaffold.R.string.common_settings).apply {
            text = words("管理 / 下载模型", "Manage / download models")
            setOnClickListener { onManageModels?.invoke(selectedModel().id) }
        })
        column.addView(modelSection, LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val advancedStart = column.childCount
        label(words("包含描述（每行一条，可选）", "Include descriptions (one per line, optional)"))
        column.addView(includes)
        label(words("排除描述（每行一条，可选）", "Exclude descriptions (one per line, optional)"))
        column.addView(excludes)
        column.addView(allIncludes)
        label(words("匹配严格度（余弦相似度）", "Matching strictness (cosine similarity)"))
        column.addView(threshold)
        val advanced = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        while (column.childCount > advancedStart) {
            val child = column.getChildAt(advancedStart)
            column.removeView(child)
            advanced.addView(child)
        }
        lateinit var filterToggle: TextView
        filterToggle = button(words("更多筛选条件（可选）", "More filters (optional)")) {
            val expanded = advanced.visibility != View.VISIBLE
            advanced.visibility = if (expanded) View.VISIBLE else View.GONE
            filterToggle.text = if (expanded) words("收起筛选条件", "Hide filters") else words("更多筛选条件（可选）", "More filters (optional)")
        }
        column.addView(advanced)
        if (existingFrame != null) label(words("请在相框照片编辑中重新加入已排除照片。", "Use the frame photo editor to include previously excluded photos."))
        column.addView(automatic)
        automatic.setOnCheckedChangeListener { _, enabled ->
            if (initialized && existingFrame != null) scope.launch {
                environment.aiFrames.setAutomatic(existingFrame.id, enabled)
                previewRevision = withContext(Dispatchers.IO) {
                    environment.aiFrames.tasks.refreshFrameRevision(draftId)
                    environment.aiFrames.state.get(existingFrame.id)?.revision ?: 0
                }
                status.text = words("自动分类设置已保存", "Automatic classification setting saved")
                onChanged()
            }
        }
        if (existingFrame != null) button(words("保存设置（不扫描）", "Save settings without scanning"), primary = true) { saveSettings() }
        startButton = button(words("扫描并预览", "Scan and preview"), primary = true) { previewAndShow() }
        column.removeView(startButton)
        footer.addView(processing, LayoutParams(-1, dp(6)))
        footer.addView(status, LayoutParams(-1, -2))
        footer.addView(startButton, LayoutParams(-1, -2))
        addView(footer, LayoutParams(-1, -2))
        cancelButton = button(words("暂停并查看结果", "Pause and view results")) {
            if (environment.aiFrames.tasks.running(draftId)) environment.aiFrames.tasks.pause(draftId)
            else trackedFrames.forEach(environment.aiFrames::pause)
        }
        resumeButton = button(words("继续分类", "Continue classification")) {
            status.text = words("正在继续分类…", "Resuming classification…")
            if (trackedFrames.isNotEmpty()) {
                pendingPreview = null
                previewIdentity = null
                environment.aiFrames.tasks.cached(draftId)?.takeIf { it.reason == "model_queue_timeout" }?.let {
                    acknowledgedPreviewTimeout = draftId to it.generation
                }
                trackedFrames.forEach(environment.aiFrames::resume)
            } else {
                val task = environment.aiFrames.tasks.cached(draftId)
                if (task != null && task.collections.toSet() != collectionChecks.filterValues { it.isChecked }.keys) {
                    previewAndShow()
                } else scope.launch { environment.aiFrames.tasks.continueTask(draftId) }
            }
            updateActions()
        }
        for (action in listOfNotNull(cancelButton, resumeButton)) {
            column.removeView(action)
            footer.addView(action, LayoutParams(-1, -2))
        }
        column.addView(results)
        modelPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initialized && previousModelId != null && previousModelId != selectedModel().id) threshold.setText(selectedModel().defaultThreshold.toString())
                previousModelId = selectedModel().id
                updateModelStatus()
            }
        }
        scope.launch {
            val configuration = withContext(Dispatchers.IO) { existingFrame?.let { environment.aiFrames.state.get(it.id) } }
            populateCollections(configuration?.collections.orEmpty().toSet())
            if (configuration != null) {
                restoreModelSelection(configuration.modelId, configuration.rule.threshold.toString())
                descriptions.setText(configuration.rule.description)
                includes.setText(configuration.rule.includes.joinToString("\n"))
                excludes.setText(configuration.rule.excludes.joinToString("\n"))
                nameOverrides = listOfNotNull(existingFrame?.name)
                allIncludes.isChecked = configuration.rule.requireAll
                recursive.isChecked = configuration.recursive
                automatic.isChecked = configuration.autoUpdate
                trackedFrames.add(configuration.frameId)
                savedRuleIdentity = formIdentity()
            }
            if (configuration == null) threshold.setText(selectedModel().defaultThreshold.toString())
            val lastDraft = restoredDraft ?: if (existingFrame == null) withContext(Dispatchers.IO) { environment.aiFrames.lastPreviewDraft() } else null
            val pendingDraft = if (restoredDraft == null && lastDraft != null) {
                lastDraft["draftId"]?.let { environment.aiFrames.tasks.record(it)?.draft }?.takeIf { it.isNotEmpty() } ?: lastDraft
            } else lastDraft
            pendingDraft?.let { draft ->
                draftId = draft["draftId"] ?: draftId
                descriptions.setText(draft["descriptions"] ?: descriptions.text)
                includes.setText(draft["includes"] ?: includes.text)
                excludes.setText(draft["excludes"] ?: excludes.text)
                nameOverrides = draft["names"]?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: nameOverrides
                recursive.isChecked = draft["recursive"]?.toBooleanStrictOrNull() ?: recursive.isChecked
                automatic.isChecked = draft["automatic"]?.toBooleanStrictOrNull() ?: automatic.isChecked
                allIncludes.isChecked = draft["allIncludes"]?.toBooleanStrictOrNull() ?: allIncludes.isChecked
                val selectedFolders = draft["folders"]?.let { org.json.JSONArray(it) }?.let(AIConfiguration::strings).orEmpty()
                collectionChecks.forEach { (id, check) -> check.isChecked = id in selectedFolders }
                restoreModelSelection(draft["modelId"], draft["threshold"])
                sourcePicker.setSelection(draft["source"]?.toIntOrNull()?.coerceIn(0, 2) ?: 0)
            }
            initialized = true
            updateFolderSummary()
            updateModelStatus()
            if (pendingDraft?.get("previewReady") == "true") {
                restoredPreviewNames = org.json.JSONArray(pendingDraft["previewNames"] ?: "[]").let(AIConfiguration::strings)
                val choices = org.json.JSONArray(pendingDraft["previewChoices"] ?: "[]")
                restoredPreviewChoices = (0 until choices.length()).map { AIConfiguration.strings(choices.getJSONArray(it)).toSet() }
                restoredPreviewMatches = pendingDraft["previewMatches"]?.let { raw ->
                    val matches = org.json.JSONArray(raw)
                    (0 until matches.length()).map { AIConfiguration.strings(matches.getJSONArray(it)).toSet() }
                }
                restoredPreviewIdentity = formIdentity()
            }
            refreshProgress()
        }
    }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    fun reloadCollections(addedSourceId: String? = null) {
        sourceReload?.cancel()
        sourceReload = scope.launch {
            val selected = collectionChecks.filterValues { it.isChecked }.keys.toMutableSet()
            val addedSource = withContext(Dispatchers.IO) {
                addedSourceId?.let { environment.database.sourceDao().getById(it) }
            }
            val localSource = addedSource?.type in setOf(
                dev.photopilot.scaffold.data.db.SourceType.LOCAL_FILES,
                dev.photopilot.scaffold.data.db.SourceType.LOCAL_PHOTOS,
            )
            if (localSource && addedSource != null) {
                val root = withContext(Dispatchers.IO) {
                    environment.database.collectionDao().getBySourceAndPath(addedSource.id, "")
                }
                root?.let { selected.add(it.id) }
            }
            populateCollections(selected)
            updateFolderSummary()
            if (addedSource != null && !localSource) chooseFolders()
        }
    }

    private suspend fun populateCollections(selected: Set<String>) {
        val collections = withContext(Dispatchers.IO) { environment.database.collectionDao().getAll() }
        val sources = withContext(Dispatchers.IO) { environment.database.sourceDao().getAll().associateBy { it.id } }
        collectionChecks.clear()
        folders.removeAllViews()
        for ((sourceId, rows) in collections.groupBy { it.sourceId?.takeIf { id -> sources[id]?.type in setOf(dev.photopilot.scaffold.data.db.SourceType.SMB, dev.photopilot.scaffold.data.db.SourceType.WEBDAV) } }) {
            folders.addView(TextView(context).apply {
                text = sources[sourceId]?.displayName ?: words("本机目录", "On this device")
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(8), dp(12), dp(8), dp(4))
                setAccessibilityHeading(true)
            })
            for (row in rows) {
                val path = row.relPath?.takeIf { it.isNotBlank() }
                val deviceFolder = sources[row.sourceId]?.type == dev.photopilot.scaffold.data.db.SourceType.LOCAL_FILES
                val fullName = when {
                    sourceId != null -> path ?: row.name
                    deviceFolder && path != null -> listOfNotNull(sources[row.sourceId]?.displayName, path).joinToString("/")
                    else -> row.name
                }
                val shortName = if (sourceId == null && !deviceFolder) row.name else fullName.substringAfterLast('/')
                val check = CheckBox(context).apply {
                    text = if (shortName == fullName) shortName else shortName + "\n" + fullName.substringBeforeLast('/').takeLast(48)
                    contentDescription = fullName
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    minHeight = dp(48)
                    isChecked = row.id in selected
                    setOnCheckedChangeListener { _, _ -> updateFolderSummary() }
                }
                collectionChecks[row.id] = check
                folders.addView(check)
            }
        }
    }

    private fun updateFolderSummary() {
        val selected = collectionChecks.values.filter { it.isChecked }
        val count = selected.size
        editSelectedFolders?.visibility = if (count > 0) View.VISIBLE else View.GONE
        folderSummary.text = if (count == 0) words("请选择本机相册或 NAS 目录", "Choose a device album or NAS folder")
            else words("已选 $count 个相册 / 目录", "$count albums / folders selected") + "\n" +
                selected.takeLast(3).joinToString(" · ") { it.text.toString().substringBefore('\n') }
    }

    private fun showSelectedFolders() {
        val selected = collectionChecks.values.filter { it.isChecked }
        if (selected.isEmpty()) return
        val keep = BooleanArray(selected.size) { true }
        val names = selected.map { it.contentDescription ?: it.text }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(words("编辑已选目录", "Edit selected folders"))
            .setMultiChoiceItems(names, keep) { _, index, checked -> keep[index] = checked }
            .setNegativeButton(words("取消", "Cancel"), null)
            .setPositiveButton(words("完成", "Done")) { _, _ ->
                selected.forEachIndexed { index, folder -> folder.isChecked = keep[index] }
                updateFolderSummary()
                updateActions()
            }
            .show()
    }

    private fun chooseFolders() {
        if (!initialized || !isAttachedToWindow || !isShown) return
        (folders.parent as? android.view.ViewGroup)?.removeView(folders)
        val content = LinearLayout(context).apply { orientation = VERTICAL }
        if (collectionChecks.isEmpty()) content.addView(TextView(context).apply {
            text = words("暂无已连接相册。返回后选择照片库、文件目录或连接 NAS。", "No connected albums. Choose Photo Library, files or connect a NAS to continue.")
            setPadding(dp(16), dp(12), dp(16), dp(12))
        })
        content.addView(folders)
        val scroll = ScrollView(context).apply { addView(content) }
        val dialog = AlertDialog.Builder(context).setTitle(words("选择相册 / 目录", "Choose albums / folders"))
            .setView(scroll).setPositiveButton(words("完成", "Done")) { _, _ -> updateFolderSummary() }
            .setNeutralButton(words("连接 NAS", "Connect NAS")) { _, _ -> onConnect?.invoke() }
            .create()
        dialog.setOnDismissListener { (folders.parent as? android.view.ViewGroup)?.removeView(folders) }
        dialog.show()
        dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * .72f).toInt())
    }

    fun draftState(): Map<String, String> {
        if (!initialized && restoredDraft != null) return restoredDraft.toMap()
        val identity = formIdentity()
        val currentPreview = pendingPreview != null && previewIdentity == identity
        val currentRestore = restoredPreviewIdentity == identity
        val scanningCurrent = environment.aiFrames.tasks.running(draftId) && activePreviewIdentity == identity
        val names = if (currentRestore) restoredPreviewNames.orEmpty() else if (currentPreview) previewNames.map { it.text.toString() } else emptyList()
        val choices = if (currentRestore) restoredPreviewChoices.orEmpty() else if (currentPreview) previewChosen else emptyList()
        val matches = if (currentRestore) restoredPreviewMatches else if (currentPreview) pendingPreview?.map { it.matches.keys } else null
        return mapOf(
        "draftId" to draftId, "descriptions" to descriptions.text.toString(), "includes" to includes.text.toString(),
        "excludes" to excludes.text.toString(), "names" to nameOverrides.joinToString("\n"), "threshold" to threshold.text.toString(),
        "recursive" to recursive.isChecked.toString(), "automatic" to automatic.isChecked.toString(), "allIncludes" to allIncludes.isChecked.toString(),
        "folders" to org.json.JSONArray(collectionChecks.filterValues { it.isChecked }.keys.toList()).toString(),
        "modelId" to selectedModel().id, "source" to sourcePicker.selectedItemPosition.toString(),
        "previewReady" to (currentPreview || currentRestore || scanningCurrent).toString(),
        "previewNames" to org.json.JSONArray(names).toString(),
        "previewChoices" to org.json.JSONArray(choices.map { org.json.JSONArray(it.toList()) }).toString(),
        "previewMatches" to org.json.JSONArray(matches.orEmpty().map { org.json.JSONArray(it.toList()) }).toString(),
        )
    }
    private fun restoreModelSelection(modelId: String?, savedThreshold: String?) {
        val models = environment.aiFrames.models.models
        val savedIndex = models.indexOfFirst { it.id == modelId }
        retiredModelNotice = modelId != null && savedIndex < 0
        val index = if (savedIndex >= 0) savedIndex else models.indexOfFirst { it.runtime == "onnx" }.coerceAtLeast(0)
        val model = models[index]
        previousModelId = model.id
        modelPicker.setSelection(index)
        threshold.setText(if (savedIndex >= 0) savedThreshold ?: model.defaultThreshold.toString() else model.defaultThreshold.toString())
    }
    private fun selectedModel() = environment.aiFrames.models.models[modelPicker.selectedItemPosition.coerceAtLeast(0)]
    private fun updateModelStatus() { modelSection.setSummary(if (chinese) selectedModel().chinese else selectedModel().english); modelStatus.text = if (!environment.aiFrames.models.supported(selectedModel())) words("此设备不支持标准模型，请选择紧凑版。", "Choose the compact model on this device.") else if (environment.aiFrames.models.ready(selectedModel())) words("模型已就绪，可离线分类", "Model ready for offline classification") else words("需要下载模型", "Model download required"); modelStatus.append(" · %.1f MB".format(selectedModel().bytes / 1e6)); if (retiredModelNotice) modelStatus.append("\n" + words("旧模型已替换，请复核新模型与匹配严格度后保存。", "The previous model was replaced. Review the new model and matching strictness before saving.")); updateActions() }
    private fun input(hint: String, single: Boolean) = EditText(context).apply { this.hint = hint; setSingleLine(single); setTextColor(context.getColor(dev.photopilot.scaffold.R.color.pp_text_primary)) }
    private fun label(text: String, heading: Boolean = false) { column.addView(TextView(context).apply { this.text = text; textSize = if (heading) 21f else 15f; setPadding(0, dp(if (heading) 12 else 4), 0, dp(8)); setAccessibilityHeading(heading); setTextColor(context.getColor(if (heading) dev.photopilot.scaffold.R.color.pp_text_primary else dev.photopilot.scaffold.R.color.pp_text_secondary)) }) }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit): TextView {
        val button = if (primary) dev.photopilot.scaffold.ui.widget.primaryButton(context, dev.photopilot.scaffold.R.string.common_done) else dev.photopilot.scaffold.ui.widget.secondaryButton(context, dev.photopilot.scaffold.R.string.common_done)
        button.text = text
        button.setOnClickListener { action() }
        column.addView(button, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = resources.getDimensionPixelSize(dev.photopilot.scaffold.R.dimen.pp_space_8) })
        return button
    }
    private fun updateActions() {
        val downloading = false
        val scanning = environment.aiFrames.tasks.running(draftId)
        val savedScanning = trackedFrames.any(environment.aiFrames::isRunning)
        modelPicker.isEnabled = !downloading && !scanning && !savingPreview
        sourcePicker.isEnabled = !downloading && !savingPreview
        listOf(descriptions, includes, excludes, threshold, allIncludes, recursive, automatic).forEach { it.isEnabled = !savingPreview }
        val ready = environment.aiFrames.models.ready(selectedModel())
        val supported = environment.aiFrames.models.supported(selectedModel())
        modelSection.setSummary((if (chinese) selectedModel().chinese else selectedModel().english) + " · " + if (downloading) modelStatus.text.toString() else if (ready) words("就绪", "Ready") else words("需要下载", "Download required"))
        downloadButton?.visibility = if (ready || downloading) View.GONE else View.VISIBLE
        deleteModelButton?.visibility = if (ready && !downloading && !scanning && !savingPreview) View.VISIBLE else View.GONE
        downloadButton?.isEnabled = !downloading && !scanning && !savingPreview && !ready && environment.aiFrames.models.supported(selectedModel())
        cancelDownloadButton?.visibility = if (downloading) View.VISIBLE else View.GONE
        deleteModelButton?.isEnabled = ready && !downloading && !scanning && !savingPreview
        val classificationActive = submittingPreview || scanning || savedScanning
        processing.visibility = if (classificationActive) View.VISIBLE else View.GONE
        startButton?.isEnabled = !downloading && !classificationActive && !savingPreview && initialized
        val currentPreview = pendingPreview != null && previewIdentity == formIdentity()
        startButton?.visibility = if (classificationActive || currentPreview) View.GONE else View.VISIBLE
        confirmPreviewButton?.visibility = if (currentPreview) View.VISIBLE else View.GONE
        confirmPreviewButton?.isEnabled = currentPreview && !savingPreview
        if (pendingPreview != null && !currentPreview && !savingPreview) {
            val message = words("规则已修改，请重新预览", "Rules changed. Run preview again")
            if (status.text.toString() != message) status.text = message
        }
        val useSavedRules = trackedFrames.isNotEmpty() && savedRuleIdentity == formIdentity()
        startButton?.text = when {
            !supported -> words("选择兼容模型", "Choose compatible model")
            ready && useSavedRules -> words("继续相框分类", "Resume frame classification")
            ready -> words("扫描并预览", "Scan and preview")
            else -> words("前往下载模型", "Manage model download")
        }
        startButton?.setOnClickListener {
            when {
                !supported -> modelSection.setExpanded(true)
                ready && useSavedRules -> resumeButton?.performClick()
                ready -> previewAndShow()
                else -> onManageModels?.invoke(selectedModel().id)
            }
        }
        cancelButton?.visibility = if (scanning || savedScanning) View.VISIBLE else View.GONE
        cancelButton?.isEnabled = !savingPreview
        listOfNotNull(downloadButton, deleteModelButton, startButton).forEach { it.alpha = if (it.isEnabled) 1f else .4f }
        val scopeChanged = environment.aiFrames.tasks.cached(draftId)?.let {
            it.kind == "preview" && it.status != "saved" && it.collections.toSet() != collectionChecks.filterValues { check -> check.isChecked }.keys
        } == true
        resumeButton?.visibility = if (trackedFrames.isEmpty() && !scopeChanged && environment.aiFrames.tasks.cached(draftId)?.status in listOf("queued", "running", "user_paused", "system_paused", "failed", "waiting", "needs_resume") && !scanning && !savedScanning) View.VISIBLE else View.GONE
        resumeButton?.text = if (trackedFrames.isEmpty()) words("继续预览", "Continue preview")
            else words("继续相框分类", "Resume frame classification")
        resumeButton?.isEnabled = !savingPreview
    }
    private fun lines(input: EditText) = input.text.toString().lines().map(String::trim).filter(String::isNotEmpty)
    private fun formIdentity(): String = listOf(selectedModel().id, collectionChecks.filterValues { it.isChecked }.keys.joinToString(), recursive.isChecked, descriptions.text, includes.text, excludes.text, allIncludes.isChecked, threshold.text).joinToString("|" )
    private fun rules(): List<AIRule>? {
        val limit = threshold.text.toString().toFloatOrNull()
        val categories = lines(descriptions)
        if (collectionChecks.none { it.value.isChecked } || categories.isEmpty() || categories.size > 10 || limit == null || limit !in 0f..1f || (existingFrame != null && categories.size != 1)) {
            status.text = words("请选择目录、类别和0–1相似度。每行一个类别，编辑已有相框时保留一个类别。", "Choose folders, categories and a similarity from 0 to 1. Use one category per line, or one category when editing an existing frame.")
            return null
        }
        return try { categories.map { AIRule(it, lines(includes), lines(excludes), allIncludes.isChecked, limit) } } catch (_: IllegalArgumentException) {
            status.text = words("描述过长或过多，请使用最多10个类别，每条描述最多500个字符。", "Descriptions are too long or numerous. Use up to 10 categories and 500 characters per description.")
            null
        }
    }
    private fun saveSettings() {
        val rule = rules()?.singleOrNull() ?: return
        val frame = existingFrame ?: return
        scope.launch {
            environment.aiFrames.configure(frame.id, selectedModel(), collectionChecks.filterValues { it.isChecked }.keys.toList(), recursive.isChecked, automatic.isChecked, rule)
            nameOverrides.firstOrNull()?.let { environment.frameManager.renameFrame(frame.id, it) }
            pendingPreview = null
            retiredModelNotice = false
            updateModelStatus()
            if (frame.id !in trackedFrames) trackedFrames.add(frame.id)
            savedRuleIdentity = formIdentity()
            status.text = words("设置已保存", "Settings saved")
            onChanged()
            updateActions()
        }
    }
    private fun previewAndShow() {
        if (environment.aiFrames.tasks.cached(draftId)?.status == "saved") {
            if (existingFrame == null) {
                scope.launch { environment.aiFrames.tasks.continueTask(draftId) }
                return
            }
            draftId = java.util.UUID.randomUUID().toString()
        }
        val rules = rules() ?: return
        if (!environment.aiFrames.models.ready(selectedModel())) { status.text = words("请先下载模型", "Download the model first"); return }
        val model = selectedModel()
        val sources = collectionChecks.filterValues { it.isChecked }.keys.toList()
        val identity = formIdentity()
        if (restoredPreviewIdentity != identity) {
            restoredPreviewNames = null
            restoredPreviewChoices = null
            restoredPreviewMatches = null
        }
        previewNames = emptyList()
        previewChosen = emptyList()
        val recursiveSelection = recursive.isChecked
        activePreviewIdentity = identity
        pendingPreview = null
        results.removeAllViews()
        submittingPreview = true
        status.text = words("正在准备分类…", "Preparing classification…")
        scope.launch {
            try {
                val draft = draftState()
                withContext(Dispatchers.IO) {
                    if (existingFrame == null) environment.aiFrames.savePreviewDraft(draft)
                    environment.aiFrames.tasks.startPreview(draftId, model, sources, recursiveSelection, rules, existingFrame?.id, draft)
                }
                shownTaskGeneration = null
                refreshProgress()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                status.text = errorText(failure)
            } finally {
                submittingPreview = false
                updateActions()
            }
        }
        updateActions()
    }
    private fun errorText(failure: Exception): String = when {
        failure.message?.contains("description_too_long") == true -> words("描述过长，请缩短或选择标准模型。", "Description is too long. Shorten it or select the standard model.")
        failure.message == "preview_paused" -> words("预览已暂停。回到前台、电量与温度允许后可继续，已完成进度会保留。", "Preview paused. Resume in the foreground when battery and temperature allow. Completed work is saved.")
        failure.message in listOf("preview_invalidated", "preview_superseded") -> words("照片或配置已更新，请继续预览。", "Photos or configuration changed. Run preview again.")
        failure.message == "model_missing" -> words("请下载模型以继续分类", "Download the model to continue classification")
        failure.message == "runtime_unavailable" -> words("此设备无法运行标准模型，请选择紧凑版模型。", "This device cannot run the standard model. Choose the compact model.")
        else -> words("操作失败，请检查模型并重试。", "Operation failed. Check the model and retry.")
    }
    private fun showPreview(previews: List<AIPreview>) {
        confirmPreviewButton?.let { footer.removeView(it) }
        results.removeAllViews()
        val progress = previews.first()
        status.text = if (progress.isComplete) words("预览完成 · ${progress.scannedCount} / ${progress.totalCount}，确认后保存相框。", "Preview complete · ${progress.scannedCount} / ${progress.totalCount}. Confirm to save frames.")
            else words("已暂停 · 已扫描 ${progress.scannedCount} / ${progress.totalCount}。可保存所有类别相框（含 0 张照片），后续明确继续分类。", "Paused · ${progress.scannedCount} / ${progress.totalCount} scanned. Save all category frames, including empty ones, then explicitly continue later.")
        results.addView(TextView(context).apply { text = words("匹配照片默认选中，待确认照片可手动加入。失败与未扫描照片保留旧结果。", "Matches are selected by default. You can add review photos manually. Failed and unscanned photos keep their previous results.") })
        environment.aiFrames.tasks.cached(draftId)?.reason?.let { reason ->
            results.addView(TextView(context).apply { text = aiPauseExplanation(reason, chinese) })
        }
        previewNames = previews.mapIndexed { index, preview ->
            val suggested = preview.suggestedNames(chinese)
            val name = input(words("相框名称", "Frame name"), true).apply { setText(restoredPreviewNames?.getOrNull(index) ?: nameOverrides.getOrNull(index) ?: suggested.firstOrNull() ?: preview.rule.description.take(60)) }
            results.addView(TextView(context).apply { text = preview.rule.description + " · " + words("匹配 ${preview.matches.size}，待确认 ${preview.reviews.size}，失败 ${preview.failed}，复用 ${preview.reusedAssetCount}", "${preview.matches.size} matches, ${preview.reviews.size} to review, ${preview.failed} failed, ${preview.reusedAssetCount} resumed"); textSize = 17f })
            results.addView(TextView(context).apply { text = words("相框名称（可修改）", "Frame name (editable)") })
            results.addView(name)
            if (suggested.isNotEmpty()) results.addView(dev.photopilot.scaffold.ui.widget.secondaryButton(context, dev.photopilot.scaffold.R.string.common_done).apply { text = words("建议名称", "Suggested names"); setOnClickListener { AlertDialog.Builder(context).setItems(suggested.toTypedArray()) { _, choice -> name.setText(suggested[choice]) }.show() } })
            results.addView(dev.photopilot.scaffold.ui.widget.secondaryButton(context, dev.photopilot.scaffold.R.string.common_done).apply { text = words("选择 / 预览照片", "Select / preview photos"); setOnClickListener { selectPreviewPhotos(preview, index) } })
            if (preview.isComplete && preview.matches.isEmpty() && preview.reviews.isEmpty()) results.addView(TextView(context).apply { text = words("没有匹配照片，请调整规则后重新预览。", "No photos matched. Adjust the rules and preview again.") })
            name
        }
        previewChosen = previews.mapIndexed { index, preview ->
            val available = preview.matches.keys + preview.reviews.keys
            val previousChoices = restoredPreviewChoices?.getOrNull(index)
            val previousMatches = restoredPreviewMatches?.getOrNull(index)
            when {
                previousChoices == null -> preview.matches.keys.toMutableSet()
                previousMatches == null -> previousChoices.intersect(available).toMutableSet()
                else -> (previousChoices.intersect(available) + (preview.matches.keys - previousMatches)).toMutableSet()
            }
        }
        val draftWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { environment.aiFrames.tasks.rememberDraft(draftId, draftState()) }
        }
        previewNames.forEach { it.addTextChangedListener(draftWatcher) }
        restoredPreviewNames = null
        restoredPreviewChoices = null
        restoredPreviewMatches = null
        restoredPreviewIdentity = null
        confirmPreviewButton?.let(footer::removeView)
        confirmPreviewButton = dev.photopilot.scaffold.ui.widget.primaryButton(context, dev.photopilot.scaffold.R.string.common_done).apply {
            text = if (existingFrame == null) words("确认创建相框", "Confirm frame creation") else words("确认保存结果", "Confirm results")
            isEnabled = true
            setOnClickListener {
                if (savingPreview) return@setOnClickListener
                if (formIdentity() != previewIdentity) { status.text = words("规则已修改，请重新预览", "Rules changed. Run preview again"); return@setOnClickListener }
                savingPreview = true
                isEnabled = false
                status.text = words("正在保存相框…", "Saving frames…")
                updateActions()
                val confirmedNames = previewNames.map { it.text.toString() }
                val confirmedChoices = previewChosen.map { it.toSet() }
                val automaticSelection = automatic.isChecked
                val revision = previewRevision
                scope.launch {
                    try {
                        val saved = withContext(Dispatchers.IO) { environment.aiFrames.tasks.confirm(draftId, confirmedNames, confirmedChoices, automaticSelection) }
                        trackedFrames.addAll(saved.filter { it !in trackedFrames })
                        savedRuleIdentity = formIdentity()
                        pendingPreview = null
                        retiredModelNotice = false
                        status.text = words("结果已保存", "Results saved")
                        updateModelStatus()
                        results.removeAllViews()
                        onChanged()
                        if (existingFrame == null) onClose() else refreshProgress()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { status.text = errorText(failure) }
                    finally { savingPreview = false; updateActions() }
                }
            }
        }
        footer.addView(confirmPreviewButton, LayoutParams(-1, -2))
        results.post {
            if (isAttachedToWindow && isShown) results.requestRectangleOnScreen(android.graphics.Rect(0, 0, results.width, minOf(results.height, dp(160))), true)
        }
    }
    private fun selectPreviewPhotos(preview: AIPreview, index: Int) {
        val ids = (preview.matches.keys + preview.reviews.keys).toList()
        openResultPicker(ids, previewChosen[index]) { selected ->
            check(pendingPreview?.getOrNull(index) === preview) { "preview_superseded" }
            previewChosen[index].apply { clear(); addAll(selected) }
            environment.aiFrames.tasks.rememberDraft(draftId, draftState(), immediate = true)
            updateActions()
        }
    }

    private suspend fun resultPhoto(id: String): dev.photopilot.scaffold.data.remote.PlaybackItem? =
        if (id.startsWith("remote:")) environment.remoteMedia.item(id.removePrefix("remote:"))
        else environment.database.assetDao().getById(id)?.let { dev.photopilot.scaffold.data.remote.PlaybackItem(local = it) }

    private fun openResultPicker(ids: List<String>, initial: Set<String>, save: suspend (Set<String>) -> Unit) {
        val selected = initial.intersect(ids.toSet()).toMutableSet()
        val pageSize = 24
        val pageCount = ((ids.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        var page = 0
        var loading: Job? = null
        var saving = false
        val body = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        val summary = TextView(context)
        val grid = LinearLayout(context).apply { orientation = VERTICAL }
        val scroll = ScrollView(context).apply { addView(grid) }
        body.addView(summary)
        val selectionActions = LinearLayout(context)
        val pageActions = LinearLayout(context)
        fun action(row: LinearLayout, title: String): TextView =
            dev.photopilot.scaffold.ui.widget.secondaryButton(context, dev.photopilot.scaffold.R.string.common_done).apply {
                text = title
                row.addView(this, LayoutParams(0, -2, 1f))
            }
        val selectPage = action(selectionActions, words("全选本页", "Select page"))
        val selectAll = action(selectionActions, words("选取全部", "Select all"))
        val previous = action(pageActions, words("上一页", "Previous"))
        val next = action(pageActions, words("下一页", "Next"))
        body.addView(selectionActions)
        body.addView(scroll, LayoutParams(-1, dp(290)))
        body.addView(pageActions)
        val dialog = AlertDialog.Builder(context).setTitle(words("选择分类照片", "Select classified photos"))
            .setView(body).setNegativeButton(words("取消", "Cancel"), null)
            .setPositiveButton(words("确认保存", "Save selection"), null).create()
        val checks = linkedMapOf<String, CheckBox>()
        fun refreshSummary() {
            summary.text = words("已选 ${selected.size} / ${ids.size} 张 · 第 ${page + 1} / $pageCount 页",
                "${selected.size} / ${ids.size} selected · Page ${page + 1} / $pageCount")
        }
        fun loadPage() {
            loading?.cancel()
            grid.removeAllViews()
            checks.clear()
            refreshSummary()
            previous.isEnabled = page > 0
            next.isEnabled = page + 1 < pageCount
            selectPage.isEnabled = false
            val pageIds = ids.drop(page * pageSize).take(pageSize)
            loading = scope.launch {
                val photos = withContext(Dispatchers.IO) { pageIds.mapNotNull { resultPhoto(it) } }
                if (!dialog.isShowing) return@launch
                if (photos.isEmpty()) grid.addView(TextView(context).apply {
                    text = words("本页没有可查看的照片", "No available photos on this page")
                })
                for (batch in photos.chunked(3)) {
                    val row = LinearLayout(context)
                    for (photo in batch) {
                        val tile = LinearLayout(context).apply { orientation = VERTICAL }
                        val check = CheckBox(context).apply {
                            contentDescription = photo.filename
                            isChecked = photo.id in selected
                            setOnCheckedChangeListener { _, checked ->
                                if (checked) selected.add(photo.id) else selected.remove(photo.id)
                                refreshSummary()
                            }
                        }
                        val image = dev.photopilot.scaffold.ui.remote.RemoteThumbnailView(context, environment.remoteMedia, photo).apply {
                            setOnClickListener { if (check.isEnabled && !saving) check.toggle() }
                            onUnavailable = { check.isEnabled = false }
                        }
                        tile.addView(image, LayoutParams(-1, dp(80)))
                        tile.addView(check)
                        row.addView(tile, LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                        checks[photo.id] = check
                    }
                    repeat(3 - batch.size) { row.addView(View(context), LayoutParams(0, 1, 1f)) }
                    grid.addView(row)
                }
                selectPage.isEnabled = checks.isNotEmpty()
                scroll.scrollTo(0, 0)
            }
        }
        selectPage.setOnClickListener { if (!saving) checks.values.filter { it.isEnabled }.forEach { it.isChecked = true } }
        selectAll.isEnabled = ids.isNotEmpty()
        selectAll.setOnClickListener {
            if (!saving) {
                selected.addAll(ids)
                checks.values.forEach { check ->
                    if (check.isEnabled) check.isChecked = true
                }
                refreshSummary()
            }
        }
        previous.setOnClickListener { if (!saving && page > 0) { page--; loadPage() } }
        next.setOnClickListener { if (!saving && page + 1 < pageCount) { page++; loadPage() } }
        dialog.setOnDismissListener { loading?.cancel() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            if (saving) return@setOnClickListener
            saving = true
            button.isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            dialog.setCancelable(false)
            val snapshot = selected.toSet()
            scope.launch {
                try {
                    save(snapshot)
                    dialog.dismiss()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    summary.text = errorText(failure)
                } finally {
                    saving = false
                    button.isEnabled = true
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                    dialog.setCancelable(true)
                }
            }
        }
        loadPage()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        polling = scope.launch { while (isActive) { refreshProgress(); delay(1500) } }
    }
    override fun onDetachedFromWindow() {
        polling?.cancel()
        sourceReload?.cancel()
        environment.aiFrames.tasks.rememberDraft(draftId, draftState(), immediate = true)
        super.onDetachedFromWindow()
    }
    private suspend fun refreshProgress() {
        if (!initialized) return
        val libraryVersion = withContext(Dispatchers.IO) { dev.photopilot.scaffold.data.repo.LibraryVersionRepository(environment.database.settingsKvDao()).current() ?: 0L }
        if (pendingPreview != null && shownLibraryVersion != libraryVersion) {
            restoredPreviewNames = previewNames.map { it.text.toString() }
            restoredPreviewChoices = previewChosen.map { it.toSet() }
            restoredPreviewMatches = pendingPreview?.map { it.matches.keys.toSet() }
            restoredPreviewIdentity = formIdentity()
            shownTaskGeneration = null
        }
        val task = withContext(Dispatchers.IO) { environment.aiFrames.tasks.record(draftId) }
        if (task?.status == "saved") trackedFrames.addAll(task.frameIds.filter { it !in trackedFrames })
        if (task?.status in listOf("deleted", "deleting", "model_missing")) {
            pendingPreview = null
            previewIdentity = null
            shownTaskGeneration = null
            results.removeAllViews()
            updateModelStatus()
        }
        if (task != null && task.rules == rules() && task.modelId == selectedModel().id && task.collections.toSet() == collectionChecks.filterValues { it.isChecked }.keys && task.recursive == recursive.isChecked) {
            if (environment.aiFrames.tasks.running(draftId)) {
                status.text = when {
                    task.status == "queued" -> words("等待模型可用…", "Waiting for the model…")
                    task.total == 0L -> words("正在加载模型并准备照片…", "Loading the model and preparing photos…")
                    else -> words("分类中 ${task.done} / ${task.total}", "Classifying ${task.done} / ${task.total}")
                }
            }
            else if (shownTaskGeneration != task.generation && task.status != "saved") {
                val requestedDraft = draftId
                val requestedIdentity = formIdentity()
                val requestedGeneration = task.generation
                val previews = withContext(Dispatchers.IO) { environment.aiFrames.tasks.previewResult(requestedDraft) }
                val latest = environment.aiFrames.tasks.cached(requestedDraft)
                val currentLibraryVersion = withContext(Dispatchers.IO) { dev.photopilot.scaffold.data.repo.LibraryVersionRepository(environment.database.settingsKvDao()).current() ?: 0L }
                if (currentLibraryVersion != libraryVersion || !isAttachedToWindow || draftId != requestedDraft || formIdentity() != requestedIdentity || latest?.generation != requestedGeneration || latest.status in listOf("deleted", "deleting", "saved", "model_missing")) {
                    status.text = words("配置或任务已变更，请重新预览。", "Configuration or task changed. Run preview again.")
                    updateActions()
                    return
                }
                if (previews != null) {
                    pendingPreview = previews
                    previewIdentity = requestedIdentity
                    previewRevision = task.frameRevision
                    shownTaskGeneration = requestedGeneration
                    shownLibraryVersion = libraryVersion
                    showPreview(previews)
                }
            }
        }
        if (task != null && !environment.aiFrames.tasks.running(draftId) && pendingPreview == null && task.status in listOf("failed", "waiting", "system_paused", "needs_resume")) {
            status.text = aiPauseExplanation(task.reason, chinese)
        }
        updateActions()
        if (environment.aiFrames.tasks.running(draftId) || pendingPreview != null) return
        if (task?.reason == "model_queue_timeout" && task.status == "failed" &&
            acknowledgedPreviewTimeout != (draftId to task.generation) &&
            trackedFrames.none(environment.aiFrames::isRunning)) return
        val configurations = withContext(Dispatchers.IO) { trackedFrames.mapNotNull { environment.aiFrames.state.get(it) } }
        if (configurations.isEmpty()) return
        status.text = configurations.joinToString("\n") { c ->
            val state = if (environment.aiFrames.models.models.none { it.id == c.modelId && it.revision == c.modelRevision }) words("旧模型已替换，请选择新模型并重新预览", "The previous model was replaced. Select a model and preview again") else if (c.userPaused) words("手动暂停，待继续", "Paused by you, ready to continue") else when (c.status) { "queued", "running" -> if (!environment.aiFrames.isRunning(c.frameId)) words("待手动继续", "Ready to resume") else if (c.status == "queued") words("等待模型", "Waiting for the model") else words("分类中", "Classifying"); "complete" -> words("已完成", "Complete"); "cancelled" -> words("已取消，可继续", "Cancelled, resumable"); "paused" -> words("已暂停", "Paused"); "model_missing" -> words("请下载模型以继续分类", "Download the model to continue classification"); "runtime_unavailable" -> words("此设备无法运行标准模型，请选择紧凑版模型。", "This device cannot run the standard model. Choose the compact model."); "failed" -> words("失败，请重试", "Failed, retry available"); else -> words("待开始", "Ready") }
            "${c.rule.description} · $state · " + (c.pauseReason?.let { aiPauseExplanation(it, chinese) + " " }.orEmpty()) + words("已处理 ${c.completed}，入选 ${c.members.size}，待确认 ${c.reviews.size}，失败 ${c.failed}", "${c.completed} processed, ${c.members.size} matched, ${c.reviews.size} to review, ${c.failed} failed")
        }
        if (configurations.any { environment.aiFrames.isRunning(it.frameId) }) return
        results.removeAllViews()
        for (configuration in configurations) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            row.addView(TextView(context).apply { text = configuration.rule.description; textSize = 19f })
            if (configuration.members.isEmpty() && configuration.status == "complete") row.addView(TextView(context).apply { text = words("没有匹配照片，可调整描述或严格度后重新扫描。", "No photos matched. Adjust descriptions or similarity and scan again.") })
            val suggestions = AINameSuggestions.names(configuration, chinese)
            if (suggestions.isNotEmpty()) row.addView(dev.photopilot.scaffold.ui.widget.secondaryButton(context, dev.photopilot.scaffold.R.string.common_done).apply {
                text = words("建议名称", "Suggested names")
                setOnClickListener { AlertDialog.Builder(context).setTitle(text).setItems(suggestions.toTypedArray()) { _, index ->
                    val field = EditText(context).apply { setText(suggestions[index]) }
                    AlertDialog.Builder(context).setTitle(words("编辑名称", "Edit name")).setView(field).setPositiveButton(words("保存", "Save")) { _, _ -> scope.launch { environment.frameManager.renameFrame(configuration.frameId, field.text.toString().trim().ifEmpty { suggestions[index] }); onChanged() } }.setNegativeButton(words("取消", "Cancel"), null).show()
                }.show() }
            })
            row.addView(dev.photopilot.scaffold.ui.widget.secondaryButton(context, dev.photopilot.scaffold.R.string.common_done).apply { text = words("预览匹配 / 待确认照片", "Preview matches / review photos"); setOnClickListener { preview(configuration) } })
            results.addView(row)
        }
    }
    private fun preview(configuration: AIConfiguration) {
        val initial = configuration.members
        val ids = (initial + configuration.reviews.keys).toList()
        openResultPicker(ids, initial) { selected ->
            withContext(Dispatchers.IO) {
                val frame = requireNotNull(environment.database.frameDao().getById(configuration.frameId))
                fun ref(id: String) = if (id.startsWith("remote:")) SelectionRefEdit(null, id.removePrefix("remote:"))
                    else SelectionRefEdit(id, null)
                val additions = (selected - initial).mapNotNull { id ->
                    resultPhoto(id)?.takeIf { environment.remoteMedia.valid(it) }?.let { ref(id) }
                }
                val removals = (initial - selected).map(::ref)
                if (additions.isNotEmpty() || removals.isNotEmpty()) {
                    val result = environment.frames.save(frame.id, frame.selectionVersion, addIncludes = additions, addExcludes = removals)
                    check(result is dev.photopilot.scaffold.data.logic.SelectionSaveResult.Ok)
                }
            }
            onChanged()
            status.text = words("选片已保存", "Selection saved")
        }
    }
}
