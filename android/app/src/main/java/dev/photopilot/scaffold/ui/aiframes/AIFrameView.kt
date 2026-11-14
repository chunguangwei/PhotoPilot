package dev.photopilot.scaffold.ui.aiframes

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.text.InputType
import android.view.View
import android.widget.*
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.aiframes.*
import dev.photopilot.scaffold.data.db.FrameEntity
import dev.photopilot.scaffold.data.repo.SelectionRefEdit
import java.io.File
import kotlinx.coroutines.*

class AIFrameView(
    context: Context,
    private val environment: AppEnvironment,
    private val scope: CoroutineScope,
    private val existingFrame: FrameEntity?,
    private val onClose: () -> Unit,
    private val onChanged: () -> Unit,
    private val restoredDraft: Map<String, String>? = null,
) : ScrollView(context) {
    private val chinese = resources.configuration.locales[0].language == "zh"
    private fun words(zh: String, en: String) = if (chinese) zh else en
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(resources.getDimensionPixelSize(dev.photopilot.scaffold.R.dimen.pp_space_24), resources.getDimensionPixelSize(dev.photopilot.scaffold.R.dimen.pp_space_16), resources.getDimensionPixelSize(dev.photopilot.scaffold.R.dimen.pp_space_24), resources.getDimensionPixelSize(dev.photopilot.scaffold.R.dimen.pp_space_24)) }
    private val modelPicker = Spinner(context)
    private val sourcePicker = Spinner(context)
    private val status = TextView(context)
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
    private val names = input(words("相框名称，每行一个（可编辑）", "Frame names, one per line (editable)"), false)
    private val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val trackedFrames = mutableListOf<String>()
    private var download: Job? = null
    private var previewJob: Job? = null
    private var pendingPreview: List<AIPreview>? = null
    private var previewIdentity: String? = null
    private var previewRevision = 0L
    private var previewNames = emptyList<EditText>()
    private var previewChosen = emptyList<MutableSet<String>>()
    private var downloadButton: TextView? = null
    private var cancelDownloadButton: TextView? = null
    private var deleteModelButton: TextView? = null
    private var startButton: TextView? = null
    private var cancelButton: TextView? = null
    private var resumeButton: TextView? = null

    private var polling: Job? = null
    private var initialized = false
    private var previousModelId: String? = null
    private var retiredModelNotice = false

    init {
        setBackgroundColor(context.getColor(dev.photopilot.scaffold.R.color.pp_surface_canvas))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        button(words("返回", "Back")) { onClose() }
        label(words("AI 分类相框", "AI frames"), true)
        label(words("照片和提示词仅在设备本地处理。先下载模型。相似度用于筛选，边界结果需确认。", "Photos and prompts are processed on this device. Download a model first. Borderline similarity results need review."))
        column.addView(modelPicker)
        modelPicker.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, environment.aiFrames.models.models.map { if (chinese) it.chinese else it.english })
        column.addView(sourcePicker)
        sourcePicker.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf(words("自动下载源", "Automatic download source"), words("国内下载源", "Domestic source"), words("国际下载源", "International source")))
        column.addView(modelStatus)
        downloadButton = button(words("下载 / 重试模型", "Download / retry model")) { confirmDownload() }
        cancelDownloadButton = button(words("取消下载", "Cancel download")) { download?.cancel() }
        deleteModelButton = button(words("删除此模型", "Delete selected model")) {
            val model = selectedModel()
            scope.launch { withContext(Dispatchers.IO) { environment.aiFrames.deleteModel(model) }; updateModelStatus() }
        }
        label(words("来源相册 / 目录", "Source albums / folders"), true)
        column.addView(folders)
        column.addView(recursive)
        label(words("类别描述（每行一个类别）", "Categories (one per line)"))
        label(words("请使用简短类别、包含或排除描述。分类器无法执行任意复杂逻辑。", "Use short category, include or exclude descriptions. The classifier supports simple semantic matching."))
        column.addView(descriptions)
        label(words("包含描述（每行一条，可选）", "Include descriptions (one per line, optional)"))
        column.addView(includes)
        label(words("排除描述（每行一条，可选）", "Exclude descriptions (one per line, optional)"))
        column.addView(excludes)
        column.addView(allIncludes)
        label(words("匹配严格度（余弦相似度）", "Matching strictness (cosine similarity)"))
        column.addView(threshold)
        label(words("相框名称（每行一个，可修改）", "Frame names (one per line, editable)"))
        column.addView(names)
        if (existingFrame != null) label(words("请在相框照片编辑中重新加入已排除照片。", "Use the frame photo editor to include previously excluded photos."))
        column.addView(automatic)
        automatic.setOnCheckedChangeListener { _, enabled ->
            if (initialized && existingFrame != null) scope.launch {
                previewJob?.takeIf { it.isActive }?.cancel()
                environment.aiFrames.setAutomatic(existingFrame.id, enabled)
                previewRevision = withContext(Dispatchers.IO) { environment.aiFrames.state.get(existingFrame.id)?.revision ?: 0 }
                status.text = words("自动分类设置已保存", "Automatic classification setting saved")
                onChanged()
            }
        }
        if (existingFrame != null) button(words("保存设置（不扫描）", "Save settings without scanning"), primary = true) { saveSettings() }
        startButton = button(words("扫描并预览", "Scan and preview"), primary = true) { previewAndShow() }
        cancelButton = button(words("取消分类", "Cancel classification")) {
            previewJob?.cancel()
            pendingPreview = null
            results.removeAllViews()
            trackedFrames.forEach(environment.aiFrames::cancel)
            updateActions()
        }
        resumeButton = button(words("继续 / 重新扫描", "Resume / scan again")) { trackedFrames.forEach(environment.aiFrames::scan) }
        column.addView(status)
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
            val collections = withContext(Dispatchers.IO) { environment.database.collectionDao().getAll() }
            for (collection in collections) {
                val check = CheckBox(context).apply { text = collection.name; isChecked = collection.id in configuration?.collections.orEmpty() }
                collectionChecks[collection.id] = check
                folders.addView(check)
            }
            if (collections.isEmpty()) label(words("请先在照片库导入相册或目录。", "Import an album or folder in Library first."))
            if (configuration != null) {
                restoreModelSelection(configuration.modelId, configuration.rule.threshold.toString())
                descriptions.setText(configuration.rule.description)
                includes.setText(configuration.rule.includes.joinToString("\n"))
                excludes.setText(configuration.rule.excludes.joinToString("\n"))
                names.setText(existingFrame?.name)
                allIncludes.isChecked = configuration.rule.requireAll
                recursive.isChecked = configuration.recursive
                automatic.isChecked = configuration.autoUpdate
                trackedFrames.add(configuration.frameId)
            }
            if (configuration == null) threshold.setText(selectedModel().defaultThreshold.toString())
            val pendingDraft = restoredDraft ?: if (existingFrame == null) withContext(Dispatchers.IO) { environment.aiFrames.lastPreviewDraft() } else null
            pendingDraft?.let { draft ->
                descriptions.setText(draft["descriptions"] ?: descriptions.text)
                includes.setText(draft["includes"] ?: includes.text)
                excludes.setText(draft["excludes"] ?: excludes.text)
                names.setText(draft["names"] ?: names.text)
                recursive.isChecked = draft["recursive"]?.toBooleanStrictOrNull() ?: recursive.isChecked
                automatic.isChecked = draft["automatic"]?.toBooleanStrictOrNull() ?: automatic.isChecked
                allIncludes.isChecked = draft["allIncludes"]?.toBooleanStrictOrNull() ?: allIncludes.isChecked
                val selectedFolders = draft["folders"]?.let { org.json.JSONArray(it) }?.let(AIConfiguration::strings).orEmpty()
                collectionChecks.forEach { (id, check) -> check.isChecked = id in selectedFolders }
                restoreModelSelection(draft["modelId"], draft["threshold"])
                sourcePicker.setSelection(draft["source"]?.toIntOrNull()?.coerceIn(0, 2) ?: 0)
            }
            initialized = true
            updateModelStatus()
            refreshProgress()
        }
    }
    fun draftState(): Map<String, String> = mapOf(
        "descriptions" to descriptions.text.toString(), "includes" to includes.text.toString(),
        "excludes" to excludes.text.toString(), "names" to names.text.toString(), "threshold" to threshold.text.toString(),
        "recursive" to recursive.isChecked.toString(), "automatic" to automatic.isChecked.toString(), "allIncludes" to allIncludes.isChecked.toString(),
        "folders" to org.json.JSONArray(collectionChecks.filterValues { it.isChecked }.keys.toList()).toString(),
        "modelId" to selectedModel().id, "source" to sourcePicker.selectedItemPosition.toString(),
    )
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
    private fun updateModelStatus() { modelStatus.text = if (!environment.aiFrames.models.supported(selectedModel())) words("此设备不支持标准模型，请选择紧凑版。", "Choose the compact model on this device.") else if (environment.aiFrames.models.ready(selectedModel())) words("模型已就绪，可离线分类", "Model ready for offline classification") else words("需要下载模型", "Model download required"); modelStatus.append(" · %.1f MB".format(selectedModel().bytes / 1e6)); if (retiredModelNotice) modelStatus.append("\n" + words("旧模型已替换，请复核新模型与匹配严格度后保存。", "The previous model was replaced. Review the new model and matching strictness before saving.")); updateActions() }
    private fun input(hint: String, single: Boolean) = EditText(context).apply { this.hint = hint; setSingleLine(single); setTextColor(context.getColor(dev.photopilot.scaffold.R.color.pp_text_primary)) }
    private fun label(text: String, heading: Boolean = false) { column.addView(TextView(context).apply { this.text = text; textSize = if (heading) 21f else 15f; setPadding(0, resources.getDimensionPixelSize(dev.photopilot.scaffold.R.dimen.pp_space_16), 0, resources.getDimensionPixelSize(dev.photopilot.scaffold.R.dimen.pp_space_12)); setAccessibilityHeading(heading); setTextColor(context.getColor(if (heading) dev.photopilot.scaffold.R.color.pp_text_primary else dev.photopilot.scaffold.R.color.pp_text_secondary)) }) }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit): TextView {
        val button = if (primary) dev.photopilot.scaffold.ui.widget.primaryButton(context, dev.photopilot.scaffold.R.string.common_done) else dev.photopilot.scaffold.ui.widget.secondaryButton(context, dev.photopilot.scaffold.R.string.common_done)
        button.text = text
        button.setOnClickListener { action() }
        column.addView(button, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = resources.getDimensionPixelSize(dev.photopilot.scaffold.R.dimen.pp_space_8) })
        return button
    }
    private fun updateActions() {
        val downloading = download?.isActive == true
        val scanning = previewJob?.isActive == true
        val savedScanning = trackedFrames.any(environment.aiFrames::isRunning)
        modelPicker.isEnabled = !downloading && !scanning
        sourcePicker.isEnabled = !downloading
        val ready = environment.aiFrames.models.ready(selectedModel())
        downloadButton?.isEnabled = !downloading && !scanning && !ready && environment.aiFrames.models.supported(selectedModel())
        cancelDownloadButton?.visibility = if (downloading) View.VISIBLE else View.GONE
        deleteModelButton?.isEnabled = ready && !downloading && !scanning
        startButton?.isEnabled = ready && !downloading && !scanning && environment.aiFrames.models.supported(selectedModel())
        cancelButton?.visibility = if (scanning || savedScanning || pendingPreview != null) View.VISIBLE else View.GONE
        listOfNotNull(downloadButton, deleteModelButton, startButton).forEach { it.alpha = if (it.isEnabled) 1f else .4f }
        resumeButton?.visibility = if (trackedFrames.isNotEmpty() && !scanning && !savedScanning) View.VISIBLE else View.GONE
    }
    private fun confirmDownload() {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        if (capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true) {
            AlertDialog.Builder(context).setMessage(words("模型下载约 %.1f MB，是否使用当前网络？", "Download approximately %.1f MB over this network?").format(selectedModel().bytes / 1e6)).setNegativeButton(words("取消", "Cancel"), null).setPositiveButton(words("下载", "Download")) { _, _ -> downloadModel(true) }.show()
        } else downloadModel(false)
    }
    private fun downloadModel(allowMetered: Boolean) {
        if (download?.isActive == true) return
        val model = selectedModel()
        val source = listOf("auto", "domestic", "international")[sourcePicker.selectedItemPosition]
        download = scope.launch {
            try {
                withContext(Dispatchers.IO) { environment.aiFrames.models.download(model, source, allowMetered) { bytes, total -> post { modelStatus.text = words("下载中", "Downloading") + " ${bytes * 100 / total}%" } } }
                updateModelStatus()
                environment.aiFrames.resumeAutomatic()
            } catch (_: CancellationException) { modelStatus.text = words("下载已取消，可继续", "Download cancelled, resume available") }
            catch (_: Exception) { modelStatus.text = words("下载失败，请检查网络、下载源与可用空间后重试", "Download failed. Check network, source and free space, then retry") }
            finally { post { updateActions() } }
        }
        updateActions()
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
            lines(names).firstOrNull()?.let { environment.frameManager.renameFrame(frame.id, it) }
            pendingPreview = null
            retiredModelNotice = false
            updateModelStatus()
            if (frame.id !in trackedFrames) trackedFrames.add(frame.id)
            status.text = words("设置已保存", "Settings saved")
            onChanged()
            updateActions()
        }
    }
    private fun previewAndShow() {
        val rules = rules() ?: return
        if (!environment.aiFrames.models.ready(selectedModel())) { status.text = words("请先下载模型", "Download the model first"); return }
        val model = selectedModel()
        val sources = collectionChecks.filterValues { it.isChecked }.keys.toList()
        val identity = formIdentity()
        pendingPreview = null
        results.removeAllViews()
        previewJob = scope.launch {
            try {
                if (existingFrame == null) withContext(Dispatchers.IO) { environment.aiFrames.savePreviewDraft(draftState()) }
                previewRevision = withContext(Dispatchers.IO) { existingFrame?.let { environment.aiFrames.state.get(it.id)?.revision } ?: 0 }
                val previews = withContext(Dispatchers.IO) { environment.aiFrames.preview(model, sources, recursive.isChecked, rules, existingFrame?.id) { count, total -> post { status.text = words("预览分类中", "Classifying preview") + " $count / $total" } } }
                if (formIdentity() != identity) { status.text = words("规则已修改，请重新预览", "Rules changed. Run preview again"); return@launch }
                pendingPreview = previews
                previewIdentity = identity
                showPreview(previews)
            } catch (_: CancellationException) { status.text = words("预览已取消", "Preview cancelled") }
            catch (failure: Exception) { status.text = errorText(failure) }
            finally { post { updateActions() } }
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
        results.removeAllViews()
        status.text = words("预览完成，确认后保存相框。边界照片默认不勾选，失败照片保留旧结果，可重试。", "Preview complete. Confirm to save frames. Borderline photos are unchecked. Failed photos keep their previous results and can be retried.")
        previewNames = previews.mapIndexed { index, preview ->
            val suggested = preview.suggestedNames(chinese)
            val name = input(words("相框名称", "Frame name"), true).apply { setText(lines(names).getOrNull(index) ?: suggested.firstOrNull() ?: preview.rule.description.take(60)) }
            results.addView(TextView(context).apply { text = preview.rule.description + " · " + words("匹配 ${preview.matches.size}，待确认 ${preview.reviews.size}，失败 ${preview.failed}，复用 ${preview.reusedAssetCount}", "${preview.matches.size} matches, ${preview.reviews.size} to review, ${preview.failed} failed, ${preview.reusedAssetCount} resumed"); textSize = 17f })
            results.addView(name)
            if (suggested.isNotEmpty()) results.addView(dev.photopilot.scaffold.ui.widget.secondaryButton(context, dev.photopilot.scaffold.R.string.common_done).apply { text = words("建议名称", "Suggested names"); setOnClickListener { AlertDialog.Builder(context).setItems(suggested.toTypedArray()) { _, choice -> name.setText(suggested[choice]) }.show() } })
            results.addView(dev.photopilot.scaffold.ui.widget.secondaryButton(context, dev.photopilot.scaffold.R.string.common_done).apply { text = words("选择 / 预览照片", "Select / preview photos"); setOnClickListener { selectPreviewPhotos(preview, index) } })
            if (preview.matches.isEmpty() && preview.reviews.isEmpty()) results.addView(TextView(context).apply { text = words("没有匹配照片，请调整规则后重新预览。", "No photos matched. Adjust the rules and preview again.") })
            name
        }
        previewChosen = previews.map { it.matches.keys.toMutableSet() }
        results.addView(dev.photopilot.scaffold.ui.widget.primaryButton(context, dev.photopilot.scaffold.R.string.common_done).apply {
            text = if (existingFrame == null) words("确认创建相框", "Confirm frame creation") else words("确认保存结果", "Confirm results")
            isEnabled = existingFrame != null || previews.any { it.matches.isNotEmpty() || it.reviews.isNotEmpty() }
            setOnClickListener {
                if (formIdentity() != previewIdentity) { status.text = words("规则已修改，请重新预览", "Rules changed. Run preview again"); return@setOnClickListener }
                isEnabled = false
                scope.launch {
                    try {
                        val saved = withContext(Dispatchers.IO) { environment.aiFrames.savePreviews(previews, previewNames.map { it.text.toString() }, previewChosen.map { it.toSet() }, automatic.isChecked, existingFrame?.id, previewRevision) }
                        trackedFrames.addAll(saved.filter { it !in trackedFrames })
                        pendingPreview = null
                        retiredModelNotice = false
                        updateModelStatus()
                        results.removeAllViews()
                        onChanged()
                        refreshProgress()
                    } catch (failure: Exception) { isEnabled = true; status.text = errorText(failure) }
                }
            }
        })
    }
    private fun selectPreviewPhotos(preview: AIPreview, index: Int) {
        val ids = (preview.matches.keys + preview.reviews.keys).toList()
        scope.launch {
            val entities = withContext(Dispatchers.IO) { ids.chunked(400).flatMap { environment.database.assetDao().getByIds(it) }.associateBy { it.id } }
            val labels = ids.map { id -> (if (id in preview.matches) words("匹配", "Match") else words("待确认", "Review")) + " · %.3f".format(preview.matches[id] ?: preview.reviews[id]) + " · " + (entities[id]?.originalFileName ?: id.take(8)) }.toTypedArray()
            AlertDialog.Builder(context).setTitle(words("选择照片（显示相似度）", "Select photos (similarity scores)"))
                .setMultiChoiceItems(labels, ids.map { it in previewChosen[index] }.toBooleanArray()) { _, which, checked -> if (checked) previewChosen[index].add(ids[which]) else previewChosen[index].remove(ids[which]) }
                .setNeutralButton(words("查看图片", "View images")) { _, _ -> previewImages(preview, ids, index) }
                .setPositiveButton(words("完成", "Done"), null).show()
        }
    }
    private fun previewImages(preview: AIPreview, ids: List<String>, category: Int, page: Int = 0) {
        scope.launch {
            val assets = withContext(Dispatchers.IO) { environment.database.assetDao().getByIds(ids.drop(page * 12).take(12)) }
            val images = mutableListOf<android.graphics.Bitmap>()
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            for (asset in assets) {
                val image = ImageView(context).apply { adjustViewBounds = true; maxHeight = (240 * resources.displayMetrics.density).toInt() }
                val bitmap = withContext(Dispatchers.IO) { runCatching { AIImageDecoder.decode(File(context.filesDir, "originals/${asset.originalRelPath}"), asset.exifOrientation.toInt(), 256) }.getOrNull() }
                bitmap?.let { images.add(it); image.setImageBitmap(it) }
                list.addView(image)
                list.addView(CheckBox(context).apply { text = "%.3f".format(preview.matches[asset.id] ?: preview.reviews[asset.id]); isChecked = asset.id in previewChosen[category]; setOnCheckedChangeListener { _, checked -> if (checked) previewChosen[category].add(asset.id) else previewChosen[category].remove(asset.id) } })
            }
            val builder = AlertDialog.Builder(context).setTitle(words("预览图片", "Preview photos")).setView(ScrollView(context).apply { addView(list) }).setPositiveButton(words("完成", "Done"), null)
            if ((page + 1) * 12 < ids.size) builder.setNeutralButton(words("下一页", "Next page")) { _, _ -> previewImages(preview, ids, category, page + 1) }
            builder.create().apply { setOnDismissListener { images.forEach { it.recycle() } }; show() }
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        polling = scope.launch { while (isActive) { refreshProgress(); delay(1500) } }
    }
    override fun onDetachedFromWindow() { polling?.cancel(); download?.cancel(); previewJob?.cancel(); super.onDetachedFromWindow() }
    private suspend fun refreshProgress() {
        updateActions()
        if (previewJob?.isActive == true || pendingPreview != null) return
        val configurations = withContext(Dispatchers.IO) { trackedFrames.mapNotNull { environment.aiFrames.state.get(it) } }
        status.text = configurations.joinToString("\n") { c ->
            val state = if (environment.aiFrames.models.models.none { it.id == c.modelId && it.revision == c.modelRevision }) words("旧模型已替换，请选择新模型并重新预览", "The previous model was replaced. Select a model and preview again") else when (c.status) { "running" -> words("分类中", "Classifying"); "complete" -> words("已完成", "Complete"); "cancelled" -> words("已取消，可继续", "Cancelled, resumable"); "paused" -> words("已暂停", "Paused"); "model_missing" -> words("请下载模型以继续分类", "Download the model to continue classification"); "runtime_unavailable" -> words("此设备无法运行标准模型，请选择紧凑版模型。", "This device cannot run the standard model. Choose the compact model."); "failed" -> words("失败，请重试", "Failed, retry available"); else -> words("待开始", "Ready") }
            "${c.rule.description} · $state · " + words("已处理 ${c.completed}，入选 ${c.members.size}，待确认 ${c.reviews.size}，失败 ${c.failed}", "${c.completed} processed, ${c.members.size} matched, ${c.reviews.size} to review, ${c.failed} failed")
        }
        if (configurations.any { it.status == "running" }) return
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
        scope.launch {
            val ids = (configuration.members + configuration.reviews.keys).take(100)
            val assets = withContext(Dispatchers.IO) { environment.database.assetDao().getByIds(ids) }
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            for (asset in assets) {
                val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val image = ImageView(context).apply { adjustViewBounds = true; maxHeight = (240 * resources.displayMetrics.density).toInt() }
                row.addView(image)
                scope.launch {
                    val bitmap = withContext(Dispatchers.IO) { runCatching { AIImageDecoder.decode(File(context.filesDir, "originals/${asset.originalRelPath}"), asset.exifOrientation.toInt(), 256) }.getOrNull() }
                    image.setImageBitmap(bitmap)
                }
                row.addView(TextView(context).apply { text = configuration.rule.description + (configuration.reviews[asset.id]?.let { " · " + words("相似度", "Similarity") + " %.3f".format(it) } ?: "") })
                row.addView(dev.photopilot.scaffold.ui.widget.secondaryButton(context, dev.photopilot.scaffold.R.string.common_done).apply {
                    val review = asset.id in configuration.reviews
                    text = if (review) words("确认加入", "Confirm inclusion") else words("移除此照片", "Remove photo")
                    setOnClickListener { scope.launch {
                        val frame = withContext(Dispatchers.IO) { environment.database.frameDao().getById(configuration.frameId) } ?: return@launch
                        val edit = SelectionRefEdit(asset.id, null)
                        environment.frames.save(frame.id, frame.selectionVersion, addIncludes = if (review) listOf(edit) else emptyList(), addExcludes = if (review) emptyList() else listOf(edit))
                        isEnabled = false
                        onChanged()
                    } }
                })
                list.addView(row)
            }
            AlertDialog.Builder(context).setTitle(words("预览（最多100张）", "Preview (up to 100 photos)")).setView(ScrollView(context).apply { addView(list) }).setPositiveButton(words("完成", "Done"), null).show()
        }
    }
}
