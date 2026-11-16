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
    val savedFrame: FrameEntity? get() = frame
    private val firstUse = restoredDraft?.get("firstUse") == "true"
    private var loaded = false
    private var scopeIds = restoredDraft?.get("scopeIds")?.takeIf { it.isNotEmpty() }?.let { raw -> org.json.JSONArray(raw).let { values -> (0 until values.length()).map { values.getString(it) }.toSet() } }
    private val initialName = frame?.name
    private val initialMode = FramePlaybackSettings.requiresLocalCopies(frame?.settingsJson)
    private val initialCover = (JsonMini.parseObject(frame?.settingsJson)["remoteCoverRef"] as? String)
    private val zh = resources.configuration.locales[0].language == "zh"
    private val confirm = primaryButton(context, if (firstUse) text("Confirm and play", "确认并播放") else text("Save frame", "保存相框"))
    var onSaved: (() -> Unit)? = null
    private fun secondaryButton(context: Context, label: String) = styledSecondary(context, dev.photopilot.scaffold.R.string.common_close).apply { text = label }
    private fun primaryButton(context: Context, label: String) = styledPrimary(context, dev.photopilot.scaffold.R.string.common_done).apply { text = label }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, failure -> showError(failure) })
    private fun text(en: String, cn: String) = if (zh) cn else en
    private val manualOrder = mutableListOf<String>()
    private var manualOrderEdited = false
    private val selected = mutableSetOf<String>()
    private val initial = mutableSetOf<String>()
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
    private val sourceList = LinearLayout(context)
    private val photos = LinearLayout(context)
    private val navigation = LinearLayout(context)
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
        setPadding(dp(16), dp(8), dp(16), dp(8))
        addView(secondaryButton(context, text("Back", "返回")).apply { setOnClickListener { requestBack() } })
        val scroll = ScrollView(context)
        val content = LinearLayout(context).apply { orientation = VERTICAL }
        scroll.addView(content)
        addView(scroll, LayoutParams(-1, 0, 1f))
        fun add(view: View) { content.addView(view, LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }
        add(TextView(context).apply { text = this@RemoteFrameView.text(if (firstUse) "Choose photos" else "Photo sources", if (firstUse) "选择照片" else "照片来源"); textSize = 22f })
        if (!firstUse) add(TextView(context).apply { text = this@RemoteFrameView.text("Frame name", "相框名称") })
        name.setText(restoredDraft?.get("name") ?: frame?.name ?: text("My frame", "我的相框")); if (!firstUse) add(name)
        sync.text = text("Sync selected NAS photos locally for offline playback", "同步所选 NAS 照片到本地，支持离线播放")
        sync.isChecked = restoredDraft?.get("mode")?.let { it == "local" } ?: initialMode
        if (!firstUse) add(sync)
        if (!firstUse) add(storageHint)
        add(status)
        add(feedback)
        if (!firstUse) {
            add(secondaryButton(context, text("Connect WebDAV / SMB", "连接 WebDAV / SMB")).apply { setOnClickListener { onConnect?.invoke() } })
            add(secondaryButton(context, text("Choose photo library", "选择照片库")).apply { setOnClickListener { onLocalPhotos?.invoke() } })
            add(secondaryButton(context, text("Choose files or folder", "选择文件或目录")).apply { setOnClickListener { onLocalFiles?.invoke() } })
            sourceList.orientation = VERTICAL; add(sourceList)
            add(secondaryButton(context, text("Refresh metadata", "刷新照片索引")).apply { setOnClickListener { refreshIndex() } })
        }
        photos.orientation = VERTICAL; add(photos)
        if (firstUse) {
            add(confirm.apply { isEnabled = false; setOnClickListener { save() } })
            add(secondaryButton(context, text("Add photos", "添加照片")).apply { setOnClickListener { chooseAdditionalSource() } })
        }
        navigation.addView(secondaryButton(context, text("Previous", "上一页")).apply { setOnClickListener { page = (page - 1).coerceAtLeast(0); loadPhotos() } }, LayoutParams(0, -2, 1f))
        navigation.addView(secondaryButton(context, text("Next", "下一页")).apply { setOnClickListener { page++; loadPhotos() } }, LayoutParams(0, -2, 1f))
        add(navigation)
        if (!firstUse) add(secondaryButton(context, text("Arrange selected photos", "调整所选照片顺序")).apply { setOnClickListener { arrangePhotos() } })
        if (!firstUse) add(secondaryButton(context, text("Clear temporary cache", "清理临时缓存")).also { clearControl = it }.apply { setOnClickListener { scope.launch { withContext(Dispatchers.IO) { env.remoteMedia.cache.clear() }; updateStatus() } } })
        if (!firstUse) add(secondaryButton(context, text("Pause local sync", "暂停本地同步")).also { pauseControl = it }.apply { setOnClickListener { frame?.let { env.remoteCopies.pause(it.id) }; updateStatus() } })
        if (!firstUse) add(secondaryButton(context, text("Resume local sync", "继续本地同步")).also { resumeControl = it }.apply { setOnClickListener { frame?.let { env.remoteCopies.start(it.id) }; updateStatus() } })
        if (!firstUse) add(secondaryButton(context, text("Release downloadable local copies", "释放可重新下载的本地副本")).also { releaseControl = it }.apply { setOnClickListener {
            val current = frame ?: return@setOnClickListener
            AlertDialog.Builder(context).setMessage(text("Verify remote versions and release exclusive NAS copies? Copies required by other local frames or imports will be retained.", "是否校验远端版本并释放本相框独占的 NAS 副本？其他本地相框或导入仍需使用的副本将保留。"))
                .setNegativeButton(text("Cancel", "取消"), null).setPositiveButton(text("Verify and release", "校验并释放")) { _, _ ->
                    scope.launch { runCatching { env.remoteCopies.release(current.id, verifyLegacy = true) }.onSuccess { feedback.text = text("Released $it bytes. Copies required by local selections, source imports or unverified versions were retained.", "已释放 $it 字节。本地选片、来源级导入仍需使用，或版本未验证的副本已保留。") }.onFailure { showError(it) } }
                }.show()
        } })
        if (!firstUse) add(confirm.apply { isEnabled = false; setOnClickListener { save() } })
        scope.launch {
            frame?.let { current ->
                val rows = withContext(Dispatchers.IO) { env.database.frameSelectionDao().getByFrame(current.id) }
                val aliases = withContext(Dispatchers.IO) { env.frames.nasSelectionAliases(current.id) }
                withContext(Dispatchers.IO) { env.database.frameDirectoryRuleDao().getByFrame(current.id) }.forEach { rule ->
                    directoryRules[rule.collectionId] = dev.photopilot.scaffold.data.logic.SaveRuleRow(rule.collectionId, rule.recursive, rule.includeFuture)
                    initialRules.add(rule.collectionId)
                    initialRuleRows[rule.collectionId] = directoryRules.getValue(rule.collectionId)
                }
                manualOrder.addAll(rows.filter { it.mode == FrameSelectionMode.INCLUDE && (it.sourceAssetId != null || it.assetId in aliases) }.sortedWith(compareBy({ it.manualOrder == null }, { it.manualOrder ?: 0 }, { it.addedAt })).mapNotNull { it.sourceAssetId ?: aliases[it.assetId] })
                initial.addAll(rows.filter { it.mode == FrameSelectionMode.INCLUDE }.mapNotNull { it.sourceAssetId ?: aliases[it.assetId] }); selected.addAll(initial)
            }
            restoredDraft?.let { draft ->
                draft["manualOrder"]?.let { raw -> runCatching { org.json.JSONArray(raw) }.getOrNull() }?.let { values -> manualOrder.clear(); (0 until values.length()).forEach { manualOrder.add(values.getString(it)) } }
                manualOrderEdited = draft["manualOrderEdited"] == "true"
                draft["selected"]?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }?.let { values -> selected.clear(); (0 until values.length()).forEach { selected.add(values.getString(it)) } }
                draft["rules"]?.let { runCatching { org.json.JSONArray(it) }.getOrNull() }?.let { values ->
                    directoryRules.clear()
                    for (index in 0 until values.length()) {
                        val value = values.getJSONObject(index)
                        val id = value.getString("id")
                        directoryRules[id] = dev.photopilot.scaffold.data.logic.SaveRuleRow(id, value.optBoolean("recursive"), true)
                    }
                }
            }
            loaded = true
            confirm.isEnabled = true
            reloadSources()
        }
        scope.launch { while (isActive) { delay(500); if (isAttachedToWindow && !busy) updateStatus() } }
    }
    fun draftState(): Map<String, String> = mapOf(
        "name" to name.text.toString(), "coverRef" to coverSourceAssetId.orEmpty(), "mode" to if (sync.isChecked) "local" else "remote",
        "selected" to org.json.JSONArray(selected.toList()).toString(),
        "manualOrder" to org.json.JSONArray(manualOrder).toString(), "manualOrderEdited" to manualOrderEdited.toString(),
        "rules" to org.json.JSONArray(directoryRules.values.map { org.json.JSONObject().put("id", it.collectionId).put("recursive", it.recursive) }).toString(),
        "folderRecursion" to JsonMini.toJson(folderRecursion),
        "sourceId" to sourceId.orEmpty(), "folder" to folder, "page" to page.toString(),
        "firstUse" to firstUse.toString(), "scopeIds" to (scopeIds?.let { org.json.JSONArray(it.toList()).toString() } ?: ""),
    )
    fun requestBack() {
        if (busy) return
        if (!loaded) return
        val dirty = selected != initial || directoryRules != initialRuleRows ||
            name.text.toString().trim() != (initialName ?: text("My frame", "我的相框")) ||
            coverSourceAssetId != initialCover || sync.isChecked != initialMode || manualOrderEdited
        if (dirty) {
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
        folder = ""
        page = 0
        scopeIds = pickedIds?.toSet()
        pickedIds?.let { selected.addAll(it) }
        reloadSources()
    }

    private fun chooseAdditionalSource() {
        val labels = listOf(text("Photo library", "照片库"), text("Files or folder", "文件或目录"), text("WebDAV / SMB", "WebDAV / SMB")) + availableSources.map { it.displayName }
        AlertDialog.Builder(context).setTitle(text("Add photos", "添加照片"))
            .setItems(labels.toTypedArray()) { _, index ->
                when (index) {
                    0 -> onLocalPhotos?.invoke()
                    1 -> onLocalFiles?.invoke()
                    2 -> onConnect?.invoke()
                    else -> acceptSource(availableSources[index - 3].id, null)
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
            sourceList.removeAllViews()
            sources.forEach { source -> sourceList.addView(secondaryButton(context, source.displayName).apply { setOnClickListener { sourceId = source.id; scopeIds = null; folder = ""; page = 0; loadPhotos() } }) }
            if (sourceId == null) sourceId = sources.firstOrNull()?.id
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
        navigation.visibility = if (!firstUse || scopeIds == null || requireNotNull(scopeIds).size > pageSize) View.VISIBLE else View.GONE
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
                Triple(files, children, actualPage)
            }
            if (request != photoRequest || sourceId != id || folder != requestedFolder || page != requestedPage) return@launch
            page = loaded.third
            fun current() = request == photoRequest && sourceId == id && folder == requestedFolder && page == loaded.third
            val currentCollection = withContext(Dispatchers.IO) { env.database.collectionDao().getBySourceAndPath(id, requestedFolder) }
            if (!current()) return@launch
            val localSource = availableSources.firstOrNull { it.id == id }?.type in setOf(SourceType.LOCAL_PHOTOS, SourceType.LOCAL_FILES)
            photos.removeAllViews()
            if (!firstUse || scopeIds == null) photos.addView(TextView(context).apply { text = (if (localSource) currentCollection?.name else requestedFolder)?.takeIf { it.isNotBlank() } ?: this@RemoteFrameView.text("Root folder", "根目录") })
            if (folder.isNotEmpty()) photos.addView(secondaryButton(context, text("Parent folder", "上级目录")).apply { setOnClickListener { folder = folder.substringBeforeLast('/', ""); page = 0; loadPhotos() } })
            if (scopeIds == null) photos.addView(secondaryButton(context, text("Select this folder", "选择此目录照片")).apply { setOnClickListener { selectCurrentFolder() } })
            currentCollection?.takeIf { scopeIds == null }?.let { collection ->
                val recursionKey = "$id:$requestedFolder"
                folderRecursion.putIfAbsent(recursionKey, directoryRules[collection.id]?.recursive == true)
                val recursive = CheckBox(context).apply { text = this@RemoteFrameView.text("Include subfolders", "包含子目录"); isChecked = folderRecursion[recursionKey] == true }
                val future = CheckBox(context).apply {
                    text = this@RemoteFrameView.text("Include future photos in this folder", "包含此目录将来的照片")
                    isChecked = directoryRules[collection.id]?.includeFuture == true
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) directoryRules[collection.id] = dev.photopilot.scaffold.data.logic.SaveRuleRow(collection.id, recursive.isChecked, true)
                        else directoryRules.remove(collection.id)
                    }
                }
                recursive.setOnCheckedChangeListener { _, checked ->
                    folderRecursion[recursionKey] = checked
                    directoryRules[collection.id]?.let { directoryRules[collection.id] = it.copy(recursive = checked) }
                }
                photos.addView(future); photos.addView(recursive)
            }
            loaded.second.filter { scopeIds == null }.forEach { child ->
                photos.addView(secondaryButton(context, child.name).apply { setOnClickListener { folder = child.relPath.orEmpty(); page = 0; loadPhotos() } })
            }
            for (asset in loaded.first) {
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
                            previewDialog = AlertDialog.Builder(context).setTitle(displayName).setView(image).setNeutralButton(context.getString(dev.photopilot.scaffold.R.string.frames_set_cover)) { _, _ ->
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
        if (firstUse) {
            status.text = text("${selected.size} selected", "已选 ${selected.size} 张")
            return
        }
        scope.launch {
            val nasSources = withContext(Dispatchers.IO) {
                val referenced = selected.mapNotNull { env.database.sourceAssetDao().getById(it)?.sourceId }.toSet() +
                    directoryRules.keys.mapNotNull { env.database.collectionDao().getById(it)?.sourceId } + listOfNotNull(sourceId.takeIf { selected.isEmpty() })
                referenced.any { id -> env.database.sourceDao().getById(id)?.type in setOf(SourceType.SMB, SourceType.WEBDAV) }
            }
            sync.visibility = if (nasSources && !firstUse) View.VISIBLE else View.GONE
            pauseControl?.visibility = sync.visibility
            resumeControl?.visibility = sync.visibility
            releaseControl?.visibility = sync.visibility
            clearControl?.visibility = sync.visibility
            storageHint.text = if (nasSources) text("Online playback uses a shared 128 MiB temporary cache. Source-level imports may also occupy storage.", "在线播放共用 128 MiB 临时缓存。来源级导入也可能占用存储。")
                else text("Uses your original photos directly. Keep access to the selected photos or folders enabled.", "直接使用本机原照片，请保留对已选照片或目录的访问权限。")
        }
        val localMode = FramePlaybackSettings.requiresLocalCopies(frame?.settingsJson)
        val running = frame?.let { env.remoteCopies.isRunning(it.id) } == true
        pauseControl?.isEnabled = localMode && running && !busy
        resumeControl?.isEnabled = localMode && !running && !busy
        releaseControl?.isEnabled = frame != null && !localMode && !running && !busy
        clearControl?.isEnabled = env.remoteMedia.cache.bytesUsed() > 0 && !busy
        if (firstUse) listOfNotNull(pauseControl, resumeControl, releaseControl, clearControl).forEach { it.visibility = View.GONE }
        listOfNotNull(pauseControl, resumeControl, releaseControl, clearControl).forEach { it.alpha = if (it.isEnabled) 1f else 0.4f }
        status.text = text("${selected.size} selected · Temporary cache ${env.remoteMedia.cache.bytesUsed() / 1048576} MiB", "已选 ${selected.size} 张 · 临时缓存 ${env.remoteMedia.cache.bytesUsed() / 1048576} MiB")
        scope.launch { frame?.let { env.remoteCopies.status(it.id)?.let { value -> status.append("\n$value") } } }
    }
    private fun arrangePhotos() {
        manualOrder.retainAll(selected)
        manualOrder.addAll(selected.filter { it !in manualOrder })
        if (manualOrder.isEmpty()) return
        scope.launch {
            val labels = withContext(Dispatchers.IO) { manualOrder.map { id -> env.database.sourceAssetDao().getById(id)?.let { env.remoteMedia.displayName(it) } ?: id } }
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
        if (busy) return
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
                            if (env.remoteMedia.item(id, allowRetainedCopy = false) == null) throw java.io.IOException("REMOTE_REVOKED")
                        }
                        val current = frame ?: env.frameManager.createFrame(savedName.ifEmpty { text("My frame", "我的相框") })
                        val beforeMigration = requireNotNull(env.database.frameDao().getById(current.id))
                        env.frames.migrateNasSelections(current.id, beforeMigration.selectionVersion)
                        val latest = requireNotNull(env.database.frameDao().getById(current.id))
                        val settings = JsonMini.parseObject(latest.settingsJson).toMutableMap()
                        if (localMode != initialMode || frame == null || settings["mediaStorageMode"] !in setOf("local", "remote")) settings["mediaStorageMode"] = if (localMode) "local" else "remote"
                        coverSourceAssetId?.takeIf { it != initialCover }?.let { ref ->
                            if (env.remoteMedia.item(ref) == null) throw java.io.IOException("REMOTE_REVOKED")
                            settings["remoteCoverRef"] = ref
                        }
                        env.database.frameDao().update(latest.copy(
                            name = if (savedName != initialName) savedName.ifEmpty { latest.name } else latest.name,
                            coverAssetId = latest.coverAssetId.takeUnless { coverSourceAssetId != null && coverSourceAssetId != initialCover },
                            settingsJson = JsonMini.toJson(settings),
                            orderMode = if (order != null) FrameOrderMode.MANUAL else latest.orderMode,
                            updatedAt = System.currentTimeMillis(),
                        ))
                        val result = env.frames.save(current.id, latest.selectionVersion,
                            addIncludes = (selectedIds - initial).map { SelectionRefEdit(null, it) },
                            removeRefs = (initial - selectedIds).map { SelectionRefEdit(null, it) },
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
            }.onSuccess { onSaved?.invoke() }.onFailure { showError(it) }
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
