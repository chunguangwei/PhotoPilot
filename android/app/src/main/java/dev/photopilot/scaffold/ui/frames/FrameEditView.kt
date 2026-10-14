package dev.photopilot.scaffold.ui.frames

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.CollectionEntity
import dev.photopilot.scaffold.data.db.FrameOrderMode
import dev.photopilot.scaffold.data.logic.GridGeometry
import dev.photopilot.scaffold.data.logic.RuleUiState
import dev.photopilot.scaffold.data.logic.SaveRuleRow
import dev.photopilot.scaffold.ui.library.ThumbnailLoader
import dev.photopilot.scaffold.ui.widget.primaryButton
import dev.photopilot.scaffold.ui.widget.secondaryButton
import java.io.File
import java.util.Date

class FrameEditView(
    context: Context,
    private val originalsDir: File,
) : LinearLayout(context) {

    data class Model(
        val name: String,
        val orderMode: FrameOrderMode,
        val includedAssetIds: Set<String>,
        val excludedRefs: Set<String>,
        val dynamicRules: Map<String, SaveRuleRow>,
        val showAllPending: Boolean,
        val persistedManualOrder: List<Pair<String, Long?>> = emptyList(),
    )

    data class State(
        val name: String,
        val selectedAssetIds: Set<String>,
        val removedExcludes: Set<String>,
        val ruleStates: Map<String, RuleUiState>,
        val manualOrder: List<String>? = null,
    )

    interface DataSource {
        fun fetchPhotos(limit: Int, offset: Int, callback: (List<AssetEntity>) -> Unit)
        fun fetchCollections(callback: (List<Pair<CollectionEntity, Long>>) -> Unit)
        fun fetchAssetsByIds(refs: List<String>, callback: (Map<String, AssetEntity>) -> Unit)
    }

    var dataSource: DataSource? = null
    var onBack: ((Boolean) -> Unit)? = null
    var onSave: ((State) -> Unit)? = null
    var onManualOrder: (() -> Unit)? = null
    var onConfirmDynamic: ((confirmed: (Boolean) -> Unit) -> Unit)? = null

    private var model: Model? = null
    private var initialRuleStates: Map<String, RuleUiState> = emptyMap()
    private val selectedAssetIds = mutableSetOf<String>()
    private val removedExcludes = mutableSetOf<String>()
    private val ruleStates = mutableMapOf<String, RuleUiState>()
    private var manualOrderWorking: MutableList<String>? = null
    private var manualOrderDirty = false
    private val assets = mutableListOf<AssetEntity>()
    private var loading = false
    private var exhausted = false
    private var cellSizePx = 0
    private var loader: ThumbnailLoader? = null

    private val nameInput: EditText
    private val tabPhotos: TextView
    private val tabFolders: TextView
    private val tabPhotosIndicator: View
    private val tabFoldersIndicator: View
    private val gridView: GridView
    private val foldersScroll: ScrollView
    private val foldersColumn: LinearLayout
    private val noticeLine: TextView
    private val manualEntry: TextView
    private val summaryLine: TextView
    private val gridAdapter: PhotosAdapter
    private var inFoldersTab = false

    private val gapPx = resources.getDimensionPixelSize(R.dimen.pp_grid_gap)
    private val cellMinPx = resources.getDimensionPixelSize(R.dimen.pp_grid_cell_min)

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        isClickable = true
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)

        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_8), inset, 0)
        }
        val back = secondaryButton(context, R.string.common_back).apply {
            setOnClickListener { onBack?.invoke(hasChanges()) }
        }
        val title = TextView(context).apply {
            text = res.getString(R.string.library_choose_title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_heading))
            setTextColor(context.getColor(R.color.pp_text_primary))
            setAccessibilityHeading(true)
        }
        headerRow.addView(back, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        headerRow.addView(
            title,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        addView(headerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        nameInput = EditText(context).apply {
            hint = res.getString(R.string.frames_name_placeholder)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            setHintTextColor(context.getColor(R.color.pp_text_secondary))
            setSingleLine(true)
            dev.photopilot.scaffold.ui.widget.KeyboardDismiss.configureDone(this)
        }
        addView(
            nameInput,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = inset
                marginEnd = inset
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )

        val tint = context.getColorStateList(R.color.nav_item_tint)
        val tabRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        tabPhotos = tabLabel(R.string.library_tab_photos, tint)
        tabPhotosIndicator = tabIndicator()
        tabFolders = tabLabel(R.string.library_tab_folders, tint)
        tabFoldersIndicator = tabIndicator()
        tabRow.addView(tabSlot(tabPhotos, tabPhotosIndicator) { selectTab(false) }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        tabRow.addView(tabSlot(tabFolders, tabFoldersIndicator) { selectTab(true) }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(
            tabRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = inset
                marginEnd = inset
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )

        noticeLine = TextView(context).apply {
            text = res.getString(R.string.player_all_pending_title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_status_warning))
            visibility = View.GONE
        }
        addView(
            noticeLine,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = inset
                marginEnd = inset
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )

        manualEntry = TextView(context).apply {
            text = res.getString(R.string.order_manual_title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_accent_action))
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener { onManualOrder?.invoke() }
        }
        addView(
            manualEntry,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = inset
                marginEnd = inset
            },
        )

        gridAdapter = PhotosAdapter()
        gridView = GridView(context).apply {
            adapter = gridAdapter
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = gapPx
            verticalSpacing = gapPx
            clipToPadding = false
            setPadding(inset, gapPx, inset, gapPx)
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
                override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                    if (total > 0 && first + visible >= total - visible) {
                        loadNextPage()
                    }
                }
            })
        }
        addView(gridView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        foldersScroll = ScrollView(context).apply { visibility = View.GONE }
        foldersColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(inset, gapPx, inset, inset)
        }
        foldersScroll.addView(
            foldersColumn,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(foldersScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bottomBar = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_12), inset, res.getDimensionPixelSize(R.dimen.pp_space_16))
            setBackgroundColor(context.getColor(R.color.pp_surface_panel))
        }
        summaryLine = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
        }
        val note = TextView(context).apply {
            text = res.getString(R.string.library_selection_note_static)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        val save = primaryButton(context, R.string.common_save).apply {
            setOnClickListener { onSave?.invoke(currentState()) }
        }
        bottomBar.addView(summaryLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        bottomBar.addView(
            note,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_4)
            },
        )
        bottomBar.addView(
            save,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
        addView(bottomBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        selectTab(false)
    }

    fun bind(model: Model) {
        this.model = model
        nameInput.setText(model.name)
        selectedAssetIds.clear()
        selectedAssetIds.addAll(model.includedAssetIds)
        removedExcludes.clear()
        ruleStates.clear()
        model.dynamicRules.forEach { (cid, row) ->
            ruleStates[cid] = RuleUiState(checked = true, recursive = row.recursive, dynamic = true)
        }
        initialRuleStates = ruleStates.toMap()
        manualOrderDirty = false
        manualOrderWorking = if (model.orderMode == FrameOrderMode.MANUAL) {
            val persisted = model.persistedManualOrder.map { it.first }
            (persisted + model.includedAssetIds.filter { it !in persisted }).toMutableList()
        } else {
            null
        }
        noticeLine.visibility = if (model.showAllPending) View.VISIBLE else View.GONE
        manualEntry.visibility = if (model.orderMode == FrameOrderMode.MANUAL) View.VISIBLE else View.GONE
        updateSummary()
        assets.clear()
        exhausted = false
        loading = false
        gridAdapter.notifyDataSetChanged()
        loadNextPage()
        if (inFoldersTab) loadFolders()
    }

    fun currentState(): State = State(
        name = nameInput.text.toString().trim().ifEmpty { model?.name.orEmpty() },
        selectedAssetIds = selectedAssetIds.toSet(),
        removedExcludes = removedExcludes.toSet(),
        ruleStates = ruleStates.toMap(),
        manualOrder = manualOrderWorking?.toList(),
    )

    fun currentManualOrder(): List<String>? = manualOrderWorking?.toList()

    fun setManualOrder(orderedIds: List<String>) {
        manualOrderWorking = orderedIds.toMutableList()
        manualOrderDirty = true
    }

    fun hasChanges(): Boolean {
        val current = model ?: return false
        if (nameInput.text.toString().trim() != current.name) return true
        if (selectedAssetIds != current.includedAssetIds) return true
        if (removedExcludes.isNotEmpty()) return true
        if (manualOrderDirty) return true
        return ruleStates.toMap() != initialRuleStates
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = resources.getDimensionPixelSize(R.dimen.pp_content_inset)
        val columns = GridGeometry.columns(w - inset * 2, gapPx, cellMinPx)
        if (gridView.numColumns != columns) gridView.numColumns = columns
        val cell = (w - inset * 2 - gapPx * (columns - 1)) / columns
        if (cell != cellSizePx && cell > 0) {
            cellSizePx = cell
            val approxRows = (h / (cell + gapPx)) + 1
            loader = ThumbnailLoader(originalsDir, columns * approxRows * CACHE_ROWS_FACTOR)
            gridAdapter.notifyDataSetChanged()
            loadNextPage()
        }
    }

    private fun selectTab(folders: Boolean) {
        inFoldersTab = folders
        tabPhotos.isSelected = !folders
        tabFolders.isSelected = folders
        tabPhotosIndicator.visibility = if (folders) View.INVISIBLE else View.VISIBLE
        tabFoldersIndicator.visibility = if (folders) View.VISIBLE else View.INVISIBLE
        gridView.visibility = if (folders) View.GONE else View.VISIBLE
        foldersScroll.visibility = if (folders) View.VISIBLE else View.GONE
        if (folders) loadFolders()
    }

    private fun loadNextPage() {
        if (loading || exhausted || cellSizePx <= 0) return
        val source = dataSource ?: return
        loading = true
        source.fetchPhotos(PAGE_SIZE, assets.size) { page ->
            loading = false
            if (page.size < PAGE_SIZE) exhausted = true
            assets.addAll(page)
            gridAdapter.notifyDataSetChanged()
        }
    }

    private fun loadFolders() {
        val source = dataSource ?: return
        source.fetchCollections { collections ->
            buildFoldersList(collections)
        }
    }

    private fun buildFoldersList(collections: List<Pair<CollectionEntity, Long>>) {
        val res = resources
        foldersColumn.removeAllViews()
        collections.forEach { (collection, count) ->
            foldersColumn.addView(
                folderRow(collection, count),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
                },
            )
        }
        val current = model ?: return
        if (current.excludedRefs.isNotEmpty()) {
            foldersColumn.addView(
                excludedPanel(),
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = res.getDimensionPixelSize(R.dimen.pp_space_24)
                },
            )
        }
    }

    private fun folderRow(collection: CollectionEntity, count: Long): View {
        val res = resources
        val state = ruleStates[collection.id] ?: RuleUiState(checked = false, recursive = false, dynamic = false)
        ruleStates[collection.id] = state
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.pp_surface_panel))
                cornerRadius = res.getDimension(R.dimen.pp_radius_group)
            }
            val pad = res.getDimensionPixelSize(R.dimen.pp_space_12)
            setPadding(pad, pad, pad, pad)
        }
        val mainToggle = checkBox("${collection.name} ($count)", state.checked) { checked ->
            val current = ruleStates[collection.id] ?: return@checkBox
            ruleStates[collection.id] = current.copy(checked = checked)
            loadFolders()
        }
        container.addView(mainToggle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (state.checked) {
            val recursiveToggle = checkBox(res.getString(R.string.rule_recursive_toggle), state.recursive) { checked ->
                val current = ruleStates[collection.id] ?: return@checkBox
                ruleStates[collection.id] = current.copy(recursive = checked)
            }
            val dynamicToggle = checkBox(res.getString(R.string.rule_dynamic_toggle), state.dynamic) { checked ->
                val current = ruleStates[collection.id] ?: return@checkBox
                if (checked) {
                    val confirm = onConfirmDynamic
                    if (confirm != null) {
                        confirm { confirmed ->
                            if (confirmed) {
                                val latest = ruleStates[collection.id] ?: return@confirm
                                ruleStates[collection.id] = latest.copy(dynamic = true)
                            }
                            loadFolders()
                        }
                    } else {
                        ruleStates[collection.id] = current.copy(dynamic = true)
                    }
                } else {
                    ruleStates[collection.id] = current.copy(dynamic = false)
                }
            }
            val indent = res.getDimensionPixelSize(R.dimen.pp_space_24)
            container.addView(
                recursiveToggle,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = indent
                },
            )
            container.addView(
                dynamicToggle,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = indent
                },
            )
        }
        return container
    }

    private fun excludedPanel(): View {
        val res = resources
        val current = model ?: return LinearLayout(context)
        val remaining = current.excludedRefs - removedExcludes
        val panel = LinearLayout(context).apply { orientation = VERTICAL }
        val countText = res.getQuantityString(
            R.plurals.rule_excluded_count, remaining.size, remaining.size,
        )
        val header = TextView(context).apply {
            text = "${res.getString(R.string.rule_manage_excluded)} ($countText)"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
        }
        panel.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val refs = remaining.toList()
        dataSource?.fetchAssetsByIds(refs) { resolved ->
            refs.forEach { ref ->
                panel.addView(
                    excludeRow(ref, resolved[ref]),
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
                    },
                )
            }
        }
        return panel
    }

    private fun excludeRow(ref: String, asset: AssetEntity?): View {
        val res = resources
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
        }
        val label = TextView(context).apply {
            text = asset?.capturedAt?.let {
                DateFormat.getDateFormat(context).format(Date(it))
            } ?: res.getString(R.string.library_status_pending)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        val remove = secondaryButton(context, R.string.common_remove).apply {
            setOnClickListener {
                removedExcludes += ref
                loadFolders()
            }
        }
        row.addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(remove, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun checkBox(text: String, checked: Boolean, onToggle: (Boolean) -> Unit): CheckBox =
        CheckBox(context).apply {
            this.text = text
            this.isChecked = checked
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            minimumHeight = resources.getDimensionPixelSize(R.dimen.pp_touch_target)
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }

    private fun updateSummary() {
        val ready = selectedAssetIds.size
        summaryLine.text = resources.getString(
            R.string.library_selection_summary, selectedAssetIds.size, ready,
        )
    }

    private fun tabLabel(textRes: Int, tint: android.content.res.ColorStateList): TextView =
        TextView(context).apply {
            text = resources.getString(textRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_body))
            setTextColor(tint)
            gravity = Gravity.CENTER
            minimumHeight = resources.getDimensionPixelSize(R.dimen.pp_touch_target)
            isDuplicateParentStateEnabled = true
        }

    private fun tabIndicator(): View =
        View(context).apply {
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.pp_accent_action))
            }
            visibility = View.INVISIBLE
        }

    private fun tabSlot(label: TextView, indicator: View, onClick: () -> Unit): LinearLayout =
        LinearLayout(context).apply {
            orientation = VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = label.text
            setOnClickListener { onClick() }
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(
                indicator,
                LayoutParams(LayoutParams.MATCH_PARENT, indicatorHeightPx()),
            )
        }

    private fun indicatorHeightPx(): Int =
        (INDICATOR_HEIGHT_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private inner class PhotosAdapter : BaseAdapter() {

        override fun getCount(): Int = assets.size

        override fun getItem(position: Int): AssetEntity = assets[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? SelectCellView) ?: SelectCellView(context)
            val asset = getItem(position)
            val size = cellSizePx.coerceAtLeast(1)
            cell.layoutParams = AbsListView.LayoutParams(size, size)
            cell.bind(asset, size, selectedAssetIds.contains(asset.id), loader)
            cell.setOnClickListener {
                if (!selectedAssetIds.remove(asset.id)) {
                    selectedAssetIds.add(asset.id)
                    manualOrderWorking?.let { working ->
                        if (asset.id !in working) {
                            working.add(asset.id)
                            manualOrderDirty = true
                        }
                    }
                } else {
                    manualOrderWorking?.remove(asset.id)
                }
                cell.bind(asset, size, selectedAssetIds.contains(asset.id), loader)
                updateSummary()
            }
            return cell
        }
    }

    private inner class SelectCellView(context: Context) : FrameLayout(context) {

        private val image: ImageView
        private val border: View
        private val badge: ImageView

        init {
            val radius = resources.getDimension(R.dimen.pp_radius_thumbnail)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
            setBackgroundColor(context.getColor(R.color.pp_surface_panel))
            image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            border = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(indicatorHeightPx() * 2, context.getColor(R.color.pp_accent_action))
                    cornerRadius = radius
                }
                visibility = View.GONE
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            badge = ImageView(context).apply {
                setImageDrawable(context.getDrawable(R.drawable.ic_check_24))
                val pad = resources.getDimensionPixelSize(R.dimen.pp_space_4)
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(context.getColor(R.color.pp_accent_action))
                    shape = GradientDrawable.OVAL
                }
                visibility = View.GONE
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(border, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            val badgeSize = resources.getDimensionPixelSize(R.dimen.pp_space_32)
            addView(
                badge,
                LayoutParams(badgeSize, badgeSize).apply {
                    gravity = Gravity.TOP or Gravity.END
                    val margin = resources.getDimensionPixelSize(R.dimen.pp_space_4)
                    setMargins(margin, margin, margin, margin)
                },
            )
        }

        fun bind(asset: AssetEntity, sizePx: Int, selected: Boolean, thumbnailLoader: ThumbnailLoader?) {
            val key = ThumbnailLoader.cacheKey(asset.id, sizePx)
            tag = key
            image.setImageDrawable(null)
            border.visibility = if (selected) View.VISIBLE else View.GONE
            badge.visibility = if (selected) View.VISIBLE else View.GONE
            isSelected = selected
            contentDescription = buildString {
                append(cellDescription(asset))
                if (selected) {
                    append(", ")
                    append(resources.getString(R.string.a11y_photo_selected))
                }
            }
            val activeLoader = thumbnailLoader ?: return
            activeLoader.load(
                asset,
                sizePx,
                key,
                object : ThumbnailLoader.Callback {
                    override fun onLoaded(loadedKey: String, bitmap: android.graphics.Bitmap?) {
                        if (tag == loadedKey && bitmap != null) {
                            image.setImageBitmap(bitmap)
                        }
                    }
                },
            )
        }

        private fun cellDescription(asset: AssetEntity): String {
            val taken = asset.capturedAt
            return if (taken != null) {
                resources.getString(
                    R.string.a11y_photo_cell,
                    DateFormat.getDateFormat(context).format(Date(taken)),
                )
            } else {
                resources.getString(R.string.a11y_photo_cell_no_date)
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 120
        const val CACHE_ROWS_FACTOR = 3
        const val INDICATOR_HEIGHT_DP = 2f
    }
}
