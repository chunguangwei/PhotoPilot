package dev.photopilot.scaffold.ui.remote

import android.content.Context
import androidx.room.withTransaction
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.view.View
import android.widget.*
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.logic.FramePlaybackSettings
import dev.photopilot.scaffold.data.repo.SelectionRefEdit
import dev.photopilot.scaffold.data.logic.SelectionSaveResult
import dev.photopilot.scaffold.ui.widget.primaryButton as styledPrimary
import dev.photopilot.scaffold.ui.widget.secondaryButton as styledSecondary
import kotlinx.coroutines.*

class RemoteFrameView(context: Context, private val env: AppEnvironment, private var frame: FrameEntity?, private val restoredDraft: Map<String, String>? = null) : LinearLayout(context) {
    var onBack: (() -> Unit)? = null
    var onConnect: (() -> Unit)? = null
    var onLocalPhotos: (() -> Unit)? = null
    var onLocalFiles: (() -> Unit)? = null
    var onAddToFrame: ((Map<String, String>) -> Unit)? = null
    var onAISettings: (() -> Unit)? = null
    private var afterSave: (() -> Unit)? = null
    val savedFrame: FrameEntity? get() = frame
    private val browseOnly = restoredDraft?.get("browseOnly") == "true"
    private val firstUse = restoredDraft?.get("firstUse") == "true"
    private var loaded = false
    private var scopeIds = restoredDraft?.get("scopeIds")?.takeIf { it.isNotEmpty() }?.let { raw -> org.json.JSONArray(raw).let { values -> (0 until values.length()).map { values.getString(it) }.toSet() } }
    private val initialName = frame?.name
    private val initialMode = FramePlaybackSettings.requiresLocalCopies(frame?.settingsJson)
    private val initialCover = (JsonMini.parseObject(frame?.settingsJson)["remoteCoverRef"] as? String)
    private val zh = resources.configuration.locales[0].language == "zh"
    private val confirm = primaryButton(context, if (browseOnly) context.getString(dev.photopilot.scaffold.R.string.library_add_to_frame) else if (firstUse) text("Confirm and play", "确认并播放") else text("Save frame", "保存相框"))
    var onSaved: (() -> Unit)? = null
    private fun secondaryButton(context: Context, label: String) = styledSecondary(context, dev.photopilot.scaffold.R.string.common_close).apply { text = label }
    private fun primaryButton(context: Context, label: String) = styledPrimary(context, dev.photopilot.scaffold.R.string.common_done).apply { text = label }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, failure -> showError(failure) })
    private fun text(en: String, cn: String) = if (zh) cn else en
    private val manualOrder = mutableListOf<String>()
    private var manualOrderEdited = false
    private val selected = mutableSetOf<String>()
    private val initial = mutableSetOf<String>()
    private val privateAssets = mutableMapOf<String, AssetEntity>()
    private val privatePhotos = LinearLayout(context).apply { orientation = VERTICAL }
    private val name = EditText(context)
    private val sync = Switch(context)
    private val storageHint = TextView(context)
    private val status = TextView(context)
    private val feedback = TextView(context)
    private val directoryRules = mutableMapOf<String, dev.photopilot.scaffold.data.logic.SaveRuleRow>()
    private val folderRecursion = JsonMini.parseObject(restoredDraft?.get("folderRecursion")).mapNotNull { (key, value) -> (value as? Boolean)?.let { key to it } }.toMap().toMutableMap()
    private val initialRules = mutableSetOf<String>()
    private val initialRuleRows = mutableMapOf<String, dev.photopilot.scaffold.data.logic.SaveRuleRow>()
    private var availableSources = emptyList<SourceEntity>()
    private val sourceSelector = secondaryButton(context, text("Choose photo source", "选择照片来源")).apply {
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        val shape = android.graphics.drawable.GradientDrawable().apply {
            setColor(context.getColor(dev.photopilot.scaffold.R.color.pp_surface_panel))
            cornerRadius = resources.getDimension(dev.photopilot.scaffold.R.dimen.pp_radius_group)
        }
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(context.getColor(dev.photopilot.scaffold.R.color.pp_separator)), shape, null)
        val arrow = context.getDrawable(android.R.drawable.arrow_down_float)?.mutate()
        arrow?.setTint(context.getColor(dev.photopilot.scaffold.R.color.pp_accent_action))
        setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null)
        compoundDrawablePadding = dp(8)
    }
    private val storageSection = dev.photopilot.scaffold.ui.widget.DisclosureSection(context, text("Storage and offline", "存储与离线"))
    private val photos = LinearLayout(context)
    private val navigation = LinearLayout(context)
    private val previousPage = secondaryButton(context, text("Previous", "上一页"))
    private val nextPage = secondaryButton(context, text("Next", "下一页"))
    private data class PhotoPage(val assets: List<SourceAssetEntity>, val children: List<CollectionEntity>, val index: Int, val total: Int)
    private var sourceId: String? = restoredDraft?.get("sourceId")?.takeIf { it.isNotEmpty() }
    private var folder = restoredDraft?.get("folder").orEmpty()
    private var page = restoredDraft?.get("page")?.toIntOrNull() ?: 0
    private var pauseControl: TextView? = null
    private var resumeControl: TextView? = null
    private var releaseControl: TextView? = null
    private var clearControl: TextView? = null
    private var busy = false
    private var sourceLoad: Job? = null
    private var photoLoad: Job? = null
    private var sourceRequest = 0L
    private var photoRequest = 0L
    private var coverSourceAssetId: String? = restoredDraft?.get("coverRef")?.takeIf { it.isNotEmpty() }
        ?: (JsonMini.parseObject(frame?.settingsJson)["remoteCoverRef"] as? String)
    private var previewDialog: AlertDialog? = null
    private val pageSize = 20
    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(dev.photopilot.scaffold.R.color.pp_surface_canvas))
        addView(dev.photopilot.scaffold.ui.widget.formHeader(context, if (browseOnly) context.getString(dev.photopilot.scaffold.R.string.sources_browse_photos) else text(if (firstUse) "Choose photos" else "Frame photos", if (firstUse) "选择照片" else "相框照片")) { requestBack() })
        val scroll = ScrollView(context)
        val content = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        scroll.addView(content)
        addView(scroll, LayoutParams(-1, 0, 1f))
        fun add(view: View) { content.addView(view, LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }
        if (!firstUse && !browseOnly) add(TextView(context).apply { text = this@RemoteFrameView.text("Frame name", "相框名称") })
        name.setSingleLine(true)
        name.setText(restoredDraft?.get("name") ?: frame?.name ?: text("My frame", "我的相框")); if (!firstUse && !browseOnly) add(name)
        sync.text = text("Sync selected NAS photos locally for offline playback", "同步所选 NAS 照片到本地，支持离线播放")
        sync.isChecked = restoredDraft?.get("mode")?.let { it == "local" } ?: initialMode
        storageSection.body.addView(sync)
        storageSection.body.addView(storageHint)
        add(feedback)
        sourceSelector.setOnClickListener { chooseAdditionalSource() }
        add(sourceSelector)
        add(privatePhotos)
        photos.orientation = VERTICAL; add(photos)
        previousPage.setOnClickListener { page = (page - 1).coerceAtLeast(0); loadPhotos() }
        nextPage.setOnClickListener { page++; loadPhotos() }
        navigation.addView(previousPage, LayoutParams(0, -2, 1f))
        navigation.addView(nextPage, LayoutParams(0, -2, 1f))
        add(navigation)
        val aiSettings = secondaryButton(context, text("AI classification rules", "AI 分类规则")).apply {
            visibility = View.GONE
            setOnClickListener {
                val proceed = { onAISettings?.invoke(); Unit }
                if (hasChanges()) {
                    AlertDialog.Builder(context).setTitle(dev.photopilot.scaffold.R.string.playback_unsaved_title)
                        .setPositiveButton(dev.photopilot.scaffold.R.string.common_save) { _, _ -> afterSave = onAISettings; save() }
                        .setNeutralButton(dev.photopilot.scaffold.R.string.common_discard) { _, _ -> proceed() }
                        .setNegativeButton(dev.photopilot.scaffold.R.string.common_cancel, null).show()
                } else proceed()
            }
        }
        if (!firstUse && !browseOnly) add(aiSettings)
        if (!firstUse && !browseOnly) add(secondaryButton(context, text("Arrange selected photos", "调整所选照片顺序")).apply { setOnClickListener { arrangePhotos() } })
        if (!firstUse) storageSection.body.addView(secondaryButton(context, text("Clear temporary cache", "清理临时缓存")).also { clearControl = it }.apply { setOnClickListener { scope.launch { withContext(Dispatchers.IO) { env.remoteMedia.cache.clear() }; updateStatus() } } })
        if (!firstUse) storageSection.body.addView(secondaryButton(context, text("Pause local sync", "暂停本地同步")).also { pauseControl = it }.apply { setOnClickListener { frame?.let { env.remoteCopies.pause(it.id) }; updateStatus() } })
        if (!firstUse) storageSection.body.addView(secondaryButton(context, text("Resume local sync", "继续本地同步")).also { resumeControl = it }.apply { setOnClickListener { frame?.let { env.remoteCopies.start(it.id) }; updateStatus() } })
        if (!firstUse) storageSection.body.addView(secondaryButton(context, text("Release downloadable local copies", "释放可重新下载的本地副本")).also { releaseControl = it }.apply { setOnClickListener {
            val current = frame ?: return@setOnClickListener
            AlertDialog.Builder(context).setMessage(text("Verify remote versions and release exclusive NAS copies? Copies required by other local frames or imports will be retained.", "是否校验远端版本并释放本相框独占的 NAS 副本？其他本地相框或导入仍需使用的副本将保留。"))
                .setNegativeButton(text("Cancel", "取消"), null).setPositiveButton(text("Verify and release", "校验并释放")) { _, _ ->
                    scope.launch { runCatching { env.remoteCopies.release(current.id, verifyLegacy = true) }.onSuccess { feedback.text = text("Released $it bytes. Copies required by local selections, source imports or unverified versions were retained.", "已释放 $it 字节。本地选片、来源级导入仍需使用，或版本未验证的副本已保留。") }.onFailure { showError(it) } }
                }.show()
        } })
        if (!firstUse && !browseOnly) add(storageSection)
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            status.textSize = 14f
            status.setPadding(0, 0, 0, dp(8))
            addView(status, LayoutParams(-1, -2))
            addView(confirm.apply { isEnabled = false; setOnClickListener {
                if (browseOnly) onAddToFrame?.invoke(mapOf("sourceId" to sourceId.orEmpty(), "folder" to folder, "selected" to org.json.JSONArray(selected.toList()).toString(), "addSelected" to "true"))
                else save()
            } }, LayoutParams(-1, -2))
        }, LayoutParams(-1, -2))
        scope.launch {
            frame?.let { current ->
                val rows = withContext(Dispatchers.IO) { env.database.frameSelectionDao().getByFrame(current.id) }
                val aliases = withContext(Dispatchers.IO) { env.frames.nasSelectionAliases(current.id) }
                withContext(Dispatchers.IO) { env.database.frameDirectoryRuleDao().getByFrame(current.id) }.forEach { rule ->
                    directoryRules[rule.collectionId] = dev.photopilot.scaffold.data.logic.SaveRuleRow(rule.collectionId, rule.recursive, rule.includeFuture)
                    initialRules.add(rule.collectionId)
                    initialRuleRows[rule.collectionId] = directoryRules.getValue(rule.collectionId)
                }
                manualOrder.addAll(rows.filter { it.mode == FrameSelectionMode.INCLUDE }.sortedWith(compareBy({ it.manualOrder == null }, { it.manualOrder ?: 0 }, { it.addedAt })).mapNotNull { it.sourceAssetId ?: aliases[it.assetId] ?: it.assetId })
                val privateIds = rows.filter { it.mode == FrameSelectionMode.INCLUDE && it.assetId != null && it.assetId !in aliases }.mapNotNull { it.assetId }
                privateAssets.putAll(withContext(Dispatchers.IO) { env.database.assetDao().getByIds(privateIds).associateBy { it.id } })
                initial.addAll(rows.filter { it.mode == FrameSelectionMode.INCLUDE }.mapNotNull { it.sourceAssetId ?: aliases[it.assetId] ?: it.assetId }); selected.addAll(initial)
                if (sourceId == null) {
                    val location = withContext(Dispatchers.IO) {
                        val asset = rows.firstNotNullOfOrNull { row -> row.sourceAssetId?.let { env.database.sourceAssetDao().getById(it) } }
                            ?: aliases.values.firstNotNullOfOrNull { env.database.sourceAssetDao().getById(it) }
                        val collection = directoryRules.keys.firstNotNullOfOrNull { env.database.collectionDao().getById(it) }
                        val id = asset?.sourceId ?: collection?.sourceId
                        val source = id?.let { env.database.sourceDao().getById(it) }
                        val path = if (source?.type in setOf(SourceType.LOCAL_PHOTOS, SourceType.LOCAL_FILES)) ""
                            else asset?.remoteKey?.substringBeforeLast('/', "") ?: collection?.relPath.orEmpty()
                        id to path
                    }
                    sourceId = location.first
                    folder = location.second
                }
            }
            restoredDraft?.let { draft ->
                draft["manualOrder"]?.let { raw -> runCatching { org.json.JSONArray(raw) }.getOrNull() }?.let { values -> manualOrder.clear(); (0 until values.length()).forEach { manualOrder.add(values.getString(it)) } }
                manualOrderEdited = draft["manualOrderEdited"] == "true"
                draft["selected"]?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }?.let { values -> if (draft["addSelected"] != "true") selected.clear(); (0 until values.length()).forEach { selected.add(values.getString(it)) } }
                draft["rules"]?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }?.let { values ->
                    directoryRules.clear()
                    for (index in 0 until values.length()) {
                        val value = values.getJSONObject(index)
                        val id = value.getString("id")
                        directoryRules[id] = dev.photopilot.scaffold.data.logic.SaveRuleRow(id, value.optBoolean("recursive"), true)
                    }
                }
            }
            privateAssets.values.forEach { asset ->
                privatePhotos.addView(CheckBox(context).apply {
                    text = asset.originalFileName ?: asset.id
                    isChecked = asset.id in selected
                    minimumHeight = dp(48)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selected.add(asset.id) else selected.remove(asset.id)
                        updateStatus()
                    }
                })
            }
            val aiConfiguration = frame?.let { withContext(Dispatchers.IO) { env.aiFrames.state.get(it.id) } }
            aiSettings.visibility = if (aiConfiguration != null) View.VISIBLE else View.GONE
            if (aiConfiguration != null && selected.isEmpty()) {
                feedback.text = text(
                    "This frame has no selected photos yet. Open AI classification rules to check progress or choose photos manually.",
                    "这个相框还没有选中的照片。可在「AI 分类规则」查看进度，也可从来源手动选片。",
                )
            }
            loaded = true
            updateStatus()
            reloadSources()
        }
        scope.launch { while (isActive) { delay(500); if (isAttachedToWindow && !busy) updateStatus() } }
    }
    fun draftState(): Map<String, String> = mapOf(
        "browseOnly" to browseOnly.toString(),
        "name" to name.text.toString(), "coverRef" to coverSourceAssetId.orEmpty(), "mode" to if (sync.isChecked) "local" else "remote",
        "selected" to org.json.JSONArray(selected.toList()).toString(),
        "manualOrder" to org.json.JSONArray(manualOrder).toString(), "manualOrderEdited" to manualOrderEdited.toString(),
        "rules" to org.json.JSONArray(directoryRules.values.map { org.json.JSONObject().put("id", it.collectionId).put("recursive", it.recursive) }).toString(),
        "folderRecursion" to JsonMini.toJson(folderRecursion),
        "sourceId" to sourceId.orEmpty(), "folder" to folder, "page" to page.toString(),
        "firstUse" to firstUse.toString(), "scopeIds" to (scopeIds?.let { org.json.JSONArray(it.toList()).toString() } ?: ""),
    )
    private fun hasChanges(): Boolean = selected != initial || directoryRules != initialRuleRows ||
        name.text.toString().trim() != (initialName ?: text("My frame", "我的相框")) ||
        coverSourceAssetId != initialCover || sync.isChecked != initialMode || manualOrderEdited

    fun requestBack() {
        if (busy) return
        if (!loaded) return
        if (!browseOnly && hasChanges()) {
            AlertDialog.Builder(context).setTitle(dev.photopilot.scaffold.R.string.playback_unsaved_title)
                .setPositiveButton(dev.photopilot.scaffold.R.string.common_discard) { _, _ -> onBack?.invoke() }
                .setNegativeButton(dev.photopilot.scaffold.R.string.common_cancel, null).show()
        } else onBack?.invoke()
    }

    fun acceptSource(id: String, pickedIds: List<String>?) {
        photoRequest++
        photoLoad?.cancel()
        photos.removeAllViews()
        sourceId = id
        sourceSelector.text = if (firstUse) text("Add photos", "添加照片") else availableSources.firstOrNull { it.id == id }?.displayName ?: text("Choose photo source", "选择照片来源")
        folder = ""
        page = 0
        scopeIds = pickedIds?.toSet()
        pickedIds?.let { selected.addAll(it) }
        reloadSources()
    }

    private fun chooseAdditionalSource() {
        val labels = listOf(text("Choose device albums", "选择本机相册"), text("Choose files or folders", "选择文件或目录"), text("Connect NAS", "连接 NAS")) + availableSources.map { it.displayName } + listOfNotNull(sourceId?.let { text("Refresh current source", "刷新当前来源") })
        AlertDialog.Builder(context).setTitle(text("Add photos", "添加照片"))
            .setItems(labels.toTypedArray()) { _, index ->
                when (index) {
                    0 -> onLocalPhotos?.invoke()
                    1 -> onLocalFiles?.invoke()
                    2 -> onConnect?.invoke()
                    in 3 until 3 + availableSources.size -> acceptSource(availableSources[index - 3].id, null)
                    else -> refreshIndex()
                }
            }.setNegativeButton(dev.photopilot.scaffold.R.string.common_cancel, null).show()
    }

    private fun selectCurrentFolder() {
        val id = sourceId ?: return
        val requestedFolder = folder
        val recursive = folderRecursion["$id:$requestedFolder"] == true
        scope.launch {
            val rows = withContext(Dispatchers.IO) { env.remoteMedia.members(id, requestedFolder, recursive).filter { env.remoteMedia.item(it.id, allowRetainedCopy = false) != null } }
            if (sourceId != id || folder != requestedFolder) return@launch
            selected.addAll(rows.map { it.id })
            loadPhotos()
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun reloadSources() {
        val request = ++sourceRequest
        sourceLoad?.cancel()
        sourceLoad = scope.launch {
            val sources = withContext(Dispatchers.IO) { env.database.sourceDao().getAll().filter { env.remoteMedia.isMetadataSource(it) && it.status == SourceStatus.ACTIVE } }
            if (request != sourceRequest) return@launch
            availableSources = sources
            if (sources.none { it.id == sourceId }) { sourceId = sources.firstOrNull()?.id; folder = ""; page = 0 }
            sourceSelector.text = if (firstUse) text("Add photos", "添加照片") else sources.firstOrNull { it.id == sourceId }?.displayName ?: text("Choose photo source", "选择照片来源")
            if (sources.isEmpty()) feedback.text = text("Connect a photo source to choose photos.", "请连接照片来源后选片。")
            loadPhotos()
        }
    }
    private fun refreshIndex() {
        val id = sourceId ?: return
        if (busy) return
        busy = true
        status.text = text("Refreshing photo metadata…", "正在刷新照片索引…")
        scope.launch {
            runCatching {
                val source = withContext(Dispatchers.IO) { env.database.sourceDao().getById(id) }
                if (JsonMini.parseObject(source?.settingsJson)["referenceOnly"] == true) env.localReferences.refresh(id)
                else env.remoteMedia.index(id, JsonMini.toJson(mapOf("directories" to listOf(folder))))
            }.onFailure { showError(it) }
            busy = false; page = 0; loadPhotos()
        }
    }
    private fun loadPhotos() {
        val id = sourceId ?: return
        navigation.visibility = View.GONE
        val requestedFolder = folder
        val requestedPage = page
        val request = ++photoRequest
        photoLoad?.cancel()
        photoLoad = scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val members = env.remoteMedia.members(id, requestedFolder, false).filter { scopeIds?.contains(it.id) != false }
                val actualPage = requestedPage.coerceIn(0, ((members.size - 1).coerceAtLeast(0) / pageSize))
                val files = members.drop(actualPage * pageSize).take(pageSize)
                val children = env.database.collectionDao().getBySource(id).filter { it.relPath != requestedFolder && dev.photopilot.scaffold.data.remote.RemotePaths.contains(requestedFolder, it.relPath.orEmpty() + "/_", true) && it.relPath?.substringBeforeLast('/', "") == requestedFolder }
                PhotoPage(files, children, actualPage, members.size)
            }
            if (request != photoRequest || sourceId != id || folder != requestedFolder || page != requestedPage) return@launch
            page = loaded.index
            navigation.visibility = if (loaded.total > pageSize) View.VISIBLE else View.GONE
            previousPage.isEnabled = loaded.index > 0
            nextPage.isEnabled = (loaded.index + 1) * pageSize < loaded.total
            previousPage.alpha = if (previousPage.isEnabled) 1f else .4f
            nextPage.alpha = if (nextPage.isEnabled) 1f else .4f
            fun current() = request == photoRequest && sourceId == id && folder == requestedFolder && page == loaded.index
            val currentCollection = withContext(Dispatchers.IO) { env.database.collectionDao().getBySourceAndPath(id, requestedFolder) }
            if (!current()) return@launch
            val localSource = availableSources.firstOrNull { it.id == id }?.type in setOf(SourceType.LOCAL_PHOTOS, SourceType.LOCAL_FILES)
            photos.removeAllViews()
            if (!firstUse || scopeIds == null) photos.addView(TextView(context).apply {
                text = (if (localSource) currentCollection?.name else requestedFolder)?.takeIf { it.isNotBlank() } ?: this@RemoteFrameView.text("Root folder", "根目录")
                contentDescription = text
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(context.getColor(dev.photopilot.scaffold.R.color.pp_text_secondary))
            })
            if (folder.isNotEmpty()) photos.addView(secondaryButton(context, text("Parent folder", "上级目录")).apply { setOnClickListener { folder = folder.substringBeforeLast('/', ""); page = 0; loadPhotos() } })
            val folderOptions = if (scopeIds == null) dev.photopilot.scaffold.ui.widget.DisclosureSection(context, text("Folder selection options", "目录选片设置")).also { options ->
                options.body.addView(secondaryButton(context, text("Select this folder", "选择此目录照片")).apply { setOnClickListener { selectCurrentFolder() } })
                photos.addView(options)
            } else null
            currentCollection?.takeIf { scopeIds == null && !browseOnly }?.let { collection ->
                val recursionKey = "$id:$requestedFolder"
                folderRecursion.putIfAbsent(recursionKey, directoryRules[collection.id]?.recursive == true)
                val recursive = CheckBox(context).apply { text = this@RemoteFrameView.text("Include subfolders", "包含子目录"); isChecked = folderRecursion[recursionKey] == true }
                val future = CheckBox(context).apply {
                    text = this@RemoteFrameView.text("Include future photos in this folder", "包含此目录将来的照片")
                    isChecked = directoryRules[collection.id]?.includeFuture == true
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) directoryRules[collection.id] = dev.photopilot.scaffold.data.logic.SaveRuleRow(collection.id, recursive.isChecked, true)
                        else directoryRules.remove(collection.id)
                        folderOptions?.setSummary(if (checked) this@RemoteFrameView.text("Auto-add photos", "自动加入照片") else "")
                        updateStatus()
                    }
                }
                folderOptions?.setSummary(if (future.isChecked) text("Auto-add photos", "自动加入照片") else "")
                recursive.setOnCheckedChangeListener { _, checked ->
                    folderRecursion[recursionKey] = checked
                    directoryRules[collection.id]?.let { directoryRules[collection.id] = it.copy(recursive = checked) }
                    page = 0
                    loadPhotos()
                }
                folderOptions?.body?.addView(future)
                folderOptions?.body?.addView(recursive)
            }
            loaded.children.filter { scopeIds == null }.forEach { child ->
                photos.addView(secondaryButton(context, child.name).apply { setOnClickListener { folder = child.relPath.orEmpty(); page = 0; loadPhotos() } })
            }
            for (asset in loaded.assets) {
                val displayName = withContext(Dispatchers.IO) { env.remoteMedia.displayName(asset) }
                if (!current()) return@launch
                val row = LinearLayout(context).apply {
                    isBaselineAligned = false
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    minimumHeight = dp(56)
                }
                val check = CheckBox(context).apply {
                    text = displayName; isChecked = asset.id in selected
                    minimumHeight = dp(48)
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    contentDescription = text
                    setOnLongClickListener {
                        AlertDialog.Builder(context).setMessage(text).setPositiveButton(this@RemoteFrameView.text("Close", "关闭"), null).show()
                        true
                    }
                    setOnCheckedChangeListener { _, enabled -> if (enabled) selected.add(asset.id) else selected.remove(asset.id); updateStatus() }
                }
                val item = withContext(Dispatchers.IO) { env.remoteMedia.item(asset.id, allowRetainedCopy = false) }
                if (!current()) return@launch
                if (item != null) row.addView(RemoteThumbnailView(context, env.remoteMedia, item), LayoutParams(dp(56), dp(56)))
                check.isEnabled = item != null
                row.addView(check, LayoutParams(0, -2, 1f))
                row.addView(secondaryButton(context, text("Preview", "预览")).apply { setOnClickListener {
                    scope.launch {
                        runCatching {
                            val item = env.remoteMedia.item(asset.id, allowRetainedCopy = false) ?: error("REMOTE_REVOKED")
                            if (!env.remoteMedia.valid(item) || !isAttachedToWindow) error("REMOTE_REVOKED")
                            previewDialog?.dismiss()
                            val image = RemoteThumbnailView(context, env.remoteMedia, item, 640).apply {
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                minimumHeight = dp(240)
                                onUnavailable = { previewDialog?.setMessage(context.getString(dev.photopilot.scaffold.R.string.source_local_reference_unavailable)) }
                            }
                            val reference = env.remoteMedia.isLocalReference(item)
                            previewDialog = AlertDialog.Builder(context).setTitle(displayName).setView(image).setNeutralButton(if (browseOnly) null else context.getString(dev.photopilot.scaffold.R.string.frames_set_cover)) { _, _ ->
                                coverSourceAssetId = asset.id
                                feedback.text = context.getString(dev.photopilot.scaffold.R.string.frames_cover_set)
                            }.setPositiveButton(text("Close", "关闭"), null).apply {
                                if (reference) setNegativeButton(context.getString(dev.photopilot.scaffold.R.string.delete_hide_globally)) { _, _ -> confirmHideReference(asset.id) }
                            }.create().also { dialog ->
                                dialog.setOnDismissListener { if (previewDialog === dialog) previewDialog = null }
                                dialog.show()
                            }
                        }.onFailure { showError(it) }
                    }
                } }, LayoutParams(-2, -2))
                photos.addView(row)
            }
            updateStatus()
        }
    }
    private fun confirmHideReference(sourceAssetId: String) {
        AlertDialog.Builder(context)
            .setTitle(dev.photopilot.scaffold.R.string.delete_hide_globally)
            .setMessage(dev.photopilot.scaffold.R.string.delete_hide_globally_body)
            .setNegativeButton(dev.photopilot.scaffold.R.string.common_cancel, null)
            .setPositiveButton(dev.photopilot.scaffold.R.string.delete_hide_globally) { _, _ ->
                scope.launch {
                    try {
                        env.localReferences.hide(sourceAssetId, System.currentTimeMillis())
                        selected.remove(sourceAssetId)
                        initial.remove(sourceAssetId)
                        manualOrder.remove(sourceAssetId)
                        if (coverSourceAssetId == sourceAssetId) coverSourceAssetId = null
                        frame = frame?.let { current -> withContext(Dispatchers.IO) { env.database.frameDao().getById(current.id) } }
                        loadPhotos()
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        showError(failure)
                    }
                }
            }.show()
    }

    private fun updateStatus() {
        val count = selected.size
        val label = if (directoryRules.isEmpty()) text("$count photos selected", "已选 $count 张照片")
            else text("$count photos · ${directoryRules.size} folders", "$count 张照片 · ${directoryRules.size} 个目录")
        if (status.text.toString() != label) status.text = label
        confirm.isEnabled = loaded && !busy && (frame != null || selected.isNotEmpty() || directoryRules.isNotEmpty())
        if (firstUse || browseOnly) return
        val selection = selected.toSet()
        val rules = directoryRules.keys.toSet()
        val currentSource = sourceId
        scope.launch {
            val nasSources = withContext(Dispatchers.IO) {
                val referenced = selection.mapNotNull { env.database.sourceAssetDao().getById(it)?.sourceId }.toSet() +
                    rules.mapNotNull { env.database.collectionDao().getById(it)?.sourceId } + listOfNotNull(currentSource.takeIf { selection.isEmpty() })
                referenced.any { id -> env.database.sourceDao().getById(id)?.type in setOf(SourceType.SMB, SourceType.WEBDAV) }
            }
            if (selection != selected || rules != directoryRules.keys || currentSource != sourceId) return@launch
            storageSection.visibility = if (nasSources) View.VISIBLE else View.GONE
            storageSection.setSummary(if (sync.isChecked) text("Offline copies", "同步到本地") else text("Online playback", "在线播放"))
            val cache = env.remoteMedia.cache.bytesUsed() / 1048576
            val hint = text("128 MiB shared cache · $cache MiB used", "共用128 MiB临时缓存 · 已用 $cache MiB")
            if (storageHint.text.toString() != hint) storageHint.text = hint
            val localMode = FramePlaybackSettings.requiresLocalCopies(frame?.settingsJson)
            val running = frame?.let { env.remoteCopies.isRunning(it.id) } == true
            pauseControl?.visibility = if (localMode && running) View.VISIBLE else View.GONE
            resumeControl?.visibility = if (localMode && !running && frame != null) View.VISIBLE else View.GONE
            releaseControl?.visibility = if (!localMode && !running && frame != null) View.VISIBLE else View.GONE
            clearControl?.isEnabled = cache > 0 && !busy
            pauseControl?.isEnabled = !busy
            resumeControl?.isEnabled = !busy
            releaseControl?.isEnabled = !busy
        }
    }
    private fun arrangePhotos() {
        manualOrder.retainAll(selected)
        manualOrder.addAll(selected.filter { it !in manualOrder })
        if (manualOrder.isEmpty()) return
        scope.launch {
            val labels = withContext(Dispatchers.IO) { manualOrder.map { id -> privateAssets[id]?.originalFileName ?: env.database.sourceAssetDao().getById(id)?.let { env.remoteMedia.displayName(it) } ?: id } }
            AlertDialog.Builder(context).setTitle(text("Photo order", "照片顺序")).setItems(labels.toTypedArray()) { _, index ->
                AlertDialog.Builder(context).setTitle(labels[index]).setItems(arrayOf(text("Move earlier", "向前移动"), text("Move later", "向后移动"), text("Move to start", "移至开头"), text("Move to end", "移至末尾"))) { _, action ->
                    val target = when (action) { 0 -> (index - 1).coerceAtLeast(0); 1 -> (index + 1).coerceAtMost(manualOrder.lastIndex); 2 -> 0; else -> manualOrder.lastIndex }
                    manualOrder.add(target, manualOrder.removeAt(index))
                    manualOrderEdited = true
                    arrangePhotos()
                }.setNegativeButton(text("Close", "关闭"), null).show()
            }.setPositiveButton(text("Done", "完成"), null).show()
        }
    }
    private fun save() {
        if (browseOnly || busy) return
        if (frame == null && selected.isEmpty() && directoryRules.isEmpty()) { feedback.text = text("Select photos or a folder first.", "请先选择照片或目录。"); return }
        busy = true
        setEditingEnabled(this, false)
        feedback.text = text("Saving…", "正在保存…")
        val savedName = name.text.toString().trim()
        val localMode = sync.visibility == View.VISIBLE && sync.isChecked
        val selectedIds = selected.toSet()
        val rules = directoryRules.values.toList()
        manualOrder.retainAll(selectedIds)
        manualOrder.addAll(selectedIds.filter { it !in manualOrder })
        val order = if (manualOrderEdited) manualOrder.toList() else null
        scope.launch {
            runCatching {
                val saved = withContext(Dispatchers.IO) {
                    env.database.withTransaction {
                        for (id in selectedIds - initial) {
                            if (id !in privateAssets && env.remoteMedia.item(id, allowRetainedCopy = false) == null) throw java.io.IOException("REMOTE_REVOKED")
                        }
                        val current = frame ?: env.frameManager.createFrame(savedName.ifEmpty { text("My frame", "我的相框") })
                        val beforeMigration = requireNotNull(env.database.frameDao().getById(current.id))
                        env.frames.migrateNasSelections(current.id, beforeMigration.selectionVersion)
                        val latest = requireNotNull(env.database.frameDao().getById(current.id))
                        val settings = JsonMini.parseObject(latest.settingsJson).toMutableMap()
                        if (localMode != initialMode || frame == null || settings["mediaStorageMode"] !in setOf("local", "remote")) settings["mediaStorageMode"] = if (localMode) "local" else "remote"
                        val newCover = coverSourceAssetId?.takeIf { it != initialCover }
                        if (newCover != null && env.remoteMedia.item(newCover) == null) throw java.io.IOException("REMOTE_REVOKED")
                        env.database.frameDao().update(latest.copy(
                            name = if (savedName != initialName) savedName.ifEmpty { latest.name } else latest.name,
                            settingsJson = JsonMini.toJson(settings),
                            orderMode = if (order != null) FrameOrderMode.MANUAL else latest.orderMode,
                            updatedAt = System.currentTimeMillis(),
                        ))
                        if (newCover != null) env.frameManager.setCover(current.id, null, newCover)
                        val result = env.frames.save(current.id, latest.selectionVersion,
                            addIncludes = (selectedIds - initial).map { if (it in privateAssets) SelectionRefEdit(it, null) else SelectionRefEdit(null, it) },
                            removeRefs = (initial - selectedIds).map { if (it in privateAssets) SelectionRefEdit(it, null) else SelectionRefEdit(null, it) },
                            ruleChanges = rules.filter { it != initialRuleRows[it.collectionId] }.map { rule -> dev.photopilot.scaffold.data.logic.RuleChange(rule.collectionId, rule.recursive, rule.includeFuture, true, emptyList(), System.currentTimeMillis()) },
                            removeRules = (initialRules - directoryRules.keys).toList(),
                            manualOrderRefs = order,
                        )
                        if (result !is SelectionSaveResult.Ok) throw java.io.IOException("REMOTE_SELECTION_CONFLICT")
                        val now = System.currentTimeMillis()
                        env.database.settingsKvDao().upsert(SettingsKvEntity("remote.sync.${current.id}", JsonMini.toJson(mapOf("frameId" to current.id, "state" to if (settings["mediaStorageMode"] == "local") "running" else "paused", "done" to 0, "total" to selectedIds.size)), now, now))
                        requireNotNull(env.database.frameDao().getById(current.id))
                    }
                }
                frame = saved
                val current = saved
                runCatching {
                    if (FramePlaybackSettings.requiresLocalCopies(current.settingsJson)) env.remoteCopies.start(current.id) else env.remoteCopies.pause(current.id)
                }
            }.onSuccess {
                val continuation = afterSave
                afterSave = null
                if (continuation != null) continuation() else onSaved?.invoke()
            }.onFailure { afterSave = null; showError(it) }
            busy = false
            setEditingEnabled(this@RemoteFrameView, true)
        }
    }
    private val disabledControls = mutableMapOf<View, Boolean>()
    private fun setEditingEnabled(view: View, enabled: Boolean) {
        if (!enabled) { disabledControls[view] = view.isEnabled; view.isEnabled = false }
        else disabledControls.remove(view)?.let { view.isEnabled = it }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) setEditingEnabled(view.getChildAt(index), enabled)
        }
    }
    private fun showError(error: Throwable) {
        feedback.text = when {
            error.message?.contains("SELECTION_CONFLICT") == true -> text("Frame selection changed. Reopen its settings and try again.", "相框选片已变更，请重新打开设置后重试。")
            error.message?.contains("LOW_SPACE") == true -> text("Not enough free space. Keep 64 MiB available and retry.", "可用空间不足，请保留 64 MiB 后重试。")
            error.message?.contains("TOO_LARGE") == true -> text("This photo exceeds the temporary cache budget. Enable local sync.", "照片超过临时缓存限额，请开启本地同步。")
            error.message?.contains("SWITCH_MODE_FIRST") == true -> text("Turn off local sync and save before releasing copies.", "请先关闭本地同步并保存，再释放副本。")
            error.message?.contains("CACHE_BUSY") == true -> text("The temporary cache is busy. Retry after the current photo loads.", "临时缓存正忙，请待当前照片加载后重试。")
            error.message?.contains("REVOKED") == true -> text("This source or photo is no longer available.", "来源或照片已不可用。")
            else -> text("Unable to load. Check the network and source, then retry.", "加载失败，请检查网络和来源后重试。")
        }
    }
    override fun onDetachedFromWindow() { previewDialog?.dismiss(); previewDialog = null; scope.cancel(); super.onDetachedFromWindow() }
}
