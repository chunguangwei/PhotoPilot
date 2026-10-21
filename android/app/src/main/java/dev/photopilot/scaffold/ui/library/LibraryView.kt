package dev.photopilot.scaffold.ui.library

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.AssetReadiness
import dev.photopilot.scaffold.data.db.CollectionEntity
import dev.photopilot.scaffold.data.db.SourceAssetEntity
import dev.photopilot.scaffold.data.db.SourceEntity
import dev.photopilot.scaffold.data.logic.CollectionDisplayName
import dev.photopilot.scaffold.data.logic.GridGeometry
import dev.photopilot.scaffold.data.logic.LibraryCriteria
import dev.photopilot.scaffold.data.logic.LibrarySort
import dev.photopilot.scaffold.data.logic.PageDedup
import dev.photopilot.scaffold.ui.widget.backButton
import dev.photopilot.scaffold.ui.widget.primaryButton
import java.io.File
import java.util.Date

class LibraryView(
    context: Context,
    private val originalsDir: File,
) : LinearLayout(context) {

    interface DataSource {
        fun fetchPhotos(criteria: LibraryCriteria, limit: Int, offset: Int, callback: (List<AssetEntity>) -> Unit)
        fun fetchCollectionPhotos(collectionId: String, limit: Int, offset: Int, callback: (List<AssetEntity>) -> Unit)
        fun fetchCollections(callback: (List<Pair<CollectionEntity, Long>>) -> Unit)
        fun fetchSources(callback: (List<SourceEntity>) -> Unit)
        fun fetchPending(callback: (List<SourceAssetEntity>) -> Unit)
    }

    var dataSource: DataSource? = null
    var onPhotoClick: ((AssetEntity) -> Unit)? = null
    var onAddToFrame: ((Set<String>) -> Unit)? = null

    private enum class Tab { PHOTOS, FOLDERS }

    private var tab = Tab.PHOTOS
    private var activeCollection: CollectionEntity? = null
    private val assets = mutableListOf<AssetEntity>()
    private val pendingItems = mutableListOf<SourceAssetEntity>()
    private var loading = false
    private var exhausted = false
    private var cellSizePx = 0
    private var loader: ThumbnailLoader? = null

    private var criteria = LibraryCriteria()
    private var selecting = false
    private val selectedIds = mutableSetOf<String>()

    private val selectToggle: TextView
    private val searchInput: EditText
    private val filterToggle: TextView
    private val sortToggle: TextView
    private val filterPanel: LinearLayout
    private val filterActiveRow: LinearLayout
    private val tabControl: dev.photopilot.scaffold.ui.widget.SegmentedControl
    private val backRow: LinearLayout
    private val gridView: GridView
    private val foldersScroll: ScrollView
    private val foldersColumn: LinearLayout
    private val emptyView: LinearLayout
    private val emptyTitle: TextView
    private val emptyBody: TextView
    private val selectionBar: LinearLayout
    private val selectionSummary: TextView
    private val addToFrameButton: View
    private val gridAdapter: GridAdapter

    private val gapPx = resources.getDimensionPixelSize(R.dimen.pp_grid_gap)
    private val cellMinPx = resources.getDimensionPixelSize(R.dimen.pp_grid_cell_min)
    private val cellMinCompactPx = resources.getDimensionPixelSize(R.dimen.pp_grid_cell_min_compact)
    private val compactBreakpointPx = resources.getDimensionPixelSize(R.dimen.pp_grid_compact_breakpoint)
    private val gridInsetPx = resources.getDimensionPixelSize(R.dimen.pp_content_inset_compact)
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)

        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val header = TextView(context).apply {
            text = res.getString(R.string.library_title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_heading))
            setTextColor(context.getColor(R.color.pp_text_primary))
            setAccessibilityHeading(true)
        }
        selectToggle = actionLabel(R.string.common_select) {
            setSelecting(!selecting)
        }
        headerRow.addView(header, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(selectToggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(
            headerRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = inset
                marginEnd = inset
                topMargin = inset
            },
        )

        val searchRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchInput = EditText(context).apply {
            hint = res.getString(R.string.library_search_placeholder)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            setHintTextColor(context.getColor(R.color.pp_text_secondary))
            setSingleLine(true)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    searchRunnable?.let { debounceHandler.removeCallbacks(it) }
                    val runnable = Runnable {
                        criteria = criteria.copy(search = s?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                        reloadAssets()
                        refreshFilterState()
                    }
                    searchRunnable = runnable
                    debounceHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
                }
            })
        }
        filterToggle = actionLabel(R.string.common_filter) {
            toggleFilterPanel()
        }
        sortToggle = actionLabel(R.string.common_sort) {
            showSortMenu(it)
        }
        searchRow.addView(searchInput, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(filterToggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        searchRow.addView(sortToggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(
            searchRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = inset
                marginEnd = inset
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )

        filterPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
        }
        filterActiveRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        addView(
            filterActiveRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = inset
                marginEnd = inset
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        addView(
            filterPanel,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = inset
                marginEnd = inset
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )

        backRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(
                backButton(context) { exitCollection() },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }
        addView(
            backRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = inset
                marginEnd = inset
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )

        tabControl = dev.photopilot.scaffold.ui.widget.SegmentedControl(
            context,
            textSizeRes = R.dimen.pp_text_body,
        ).apply {
            setItems(
                listOf(
                    res.getString(R.string.library_tab_photos),
                    res.getString(R.string.library_tab_folders),
                ),
                0,
            ) { index -> selectTab(if (index == 0) Tab.PHOTOS else Tab.FOLDERS) }
        }
        addView(
            tabControl,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = inset
                marginEnd = inset
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )

        gridAdapter = GridAdapter(res.getDimension(R.dimen.pp_radius_thumbnail))
        gridView = GridView(context).apply {
            adapter = gridAdapter
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = gapPx
            verticalSpacing = gapPx
            clipToPadding = false
            setPadding(gridInsetPx, gapPx, gridInsetPx, inset)
            setOnItemClickListener { _, _, position, _ ->
                if (position < pendingItems.size) return@setOnItemClickListener
                val asset = assets.getOrNull(position - pendingItems.size) ?: return@setOnItemClickListener
                if (selecting) {
                    toggleSelected(asset.id)
                } else {
                    onPhotoClick?.invoke(asset)
                }
            }
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

        foldersScroll = ScrollView(context).apply {
            visibility = View.GONE
        }
        foldersColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(inset, gapPx, inset, inset)
        }
        foldersScroll.addView(
            foldersColumn,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(foldersScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        emptyView = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        emptyTitle = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_heading))
            setTextColor(context.getColor(R.color.pp_text_primary))
            gravity = Gravity.CENTER
        }
        emptyBody = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            gravity = Gravity.CENTER
        }
        emptyView.addView(emptyTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        emptyView.addView(
            emptyBody,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        addView(emptyView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        selectionBar = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
            setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_12), inset, res.getDimensionPixelSize(R.dimen.pp_space_16))
            setBackgroundColor(context.getColor(R.color.pp_surface_panel))
        }
        selectionSummary = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addToFrameButton = primaryButton(context, R.string.library_add_to_frame).apply {
            setOnClickListener {
                if (selectedIds.isNotEmpty()) {
                    onAddToFrame?.invoke(selectedIds.toSet())
                }
            }
        }
        selectionBar.addView(selectionSummary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        selectionBar.addView(
            addToFrameButton,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
        addView(selectionBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        selectTab(Tab.PHOTOS)
        refreshFilterState()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val columns = GridGeometry.columns(w - gridInsetPx * 2, gapPx, cellMinPx, cellMinCompactPx, compactBreakpointPx)
        if (gridView.numColumns != columns) {
            gridView.numColumns = columns
        }
        val cell = (w - gridInsetPx * 2 - gapPx * (columns - 1)) / columns
        if (cell != cellSizePx && cell > 0) {
            cellSizePx = cell
            val approxRows = (h / (cell + gapPx)) + 1
            loader = ThumbnailLoader(originalsDir, columns * approxRows * CACHE_ROWS_FACTOR)
            gridAdapter.notifyDataSetChanged()
            if (assets.isEmpty() && !loading && !exhausted) {
                loadNextPage()
            }
        }
    }

    fun refresh() {
        if (tab == Tab.FOLDERS && activeCollection == null) {
            loadCollections()
        } else {
            reloadAssets()
        }
    }

    fun navigateBackWithin(): Boolean {
        if (selecting) {
            setSelecting(false)
            return true
        }
        if (activeCollection != null) {
            exitCollection()
            return true
        }
        return false
    }

    fun exitSelectionMode() {
        setSelecting(false)
    }

    private fun setSelecting(enabled: Boolean) {
        selecting = enabled
        if (!enabled) {
            selectedIds.clear()
        }
        selectToggle.text = resources.getString(
            if (enabled) R.string.common_cancel else R.string.common_select,
        )
        selectToggle.contentDescription = selectToggle.text
        selectionBar.visibility = if (enabled) View.VISIBLE else View.GONE
        gridAdapter.notifyDataSetChanged()
        updateSelectionSummary()
    }

    private fun toggleSelected(assetId: String) {
        if (!selectedIds.remove(assetId)) {
            selectedIds.add(assetId)
        }
        gridAdapter.notifyDataSetChanged()
        updateSelectionSummary()
    }

    private fun updateSelectionSummary() {
        val count = selectedIds.size
        val ready = selectedIds.count { id ->
            assets.any { it.id == id && it.readiness == AssetReadiness.READY }
        }
        selectionSummary.text = resources.getString(R.string.library_selection_summary, count, ready)
        selectionSummary.contentDescription = resources.getQuantityString(
            R.plurals.a11y_selection_summary, count, count,
        )
        val enabled = count > 0
        addToFrameButton.isEnabled = enabled
        addToFrameButton.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private fun selectTab(newTab: Tab) {
        tab = newTab
        activeCollection = null
        tabControl.setSelected(if (newTab == Tab.PHOTOS) 0 else 1)
        updateVisibility()
        refresh()
    }

    private fun enterCollection(collection: CollectionEntity) {
        activeCollection = collection
        updateVisibility()
        reloadAssets()
    }

    private fun exitCollection() {
        activeCollection = null
        updateVisibility()
        if (tab == Tab.FOLDERS) loadCollections() else reloadAssets()
    }

    private fun updateVisibility() {
        val inCollection = activeCollection != null
        backRow.visibility = if (inCollection) View.VISIBLE else View.GONE
        gridView.visibility = if (tab == Tab.PHOTOS || inCollection) View.VISIBLE else View.GONE
        foldersScroll.visibility = if (tab == Tab.FOLDERS && !inCollection) View.VISIBLE else View.GONE
        emptyView.visibility = View.GONE
    }

    private fun reloadAssets() {
        assets.clear()
        pendingItems.clear()
        exhausted = false
        loading = false
        gridAdapter.notifyDataSetChanged()
        val source = dataSource
        if (source != null && tab == Tab.PHOTOS && activeCollection == null && !criteria.isFiltered()) {
            source.fetchPending { pending ->
                pendingItems.clear()
                pendingItems.addAll(pending)
                gridAdapter.notifyDataSetChanged()
                updateEmptyState()
            }
        }
        loadNextPage()
    }

    private fun loadNextPage() {
        if (loading || exhausted || cellSizePx <= 0) return
        val source = dataSource ?: return
        loading = true
        val offset = assets.size
        val collection = activeCollection
        val callback: (List<AssetEntity>) -> Unit = { page ->
            loading = false
            if (page.size < PAGE_SIZE) exhausted = true
            assets.addAll(PageDedup.filterNew(assets, page) { it.id })
            gridAdapter.notifyDataSetChanged()
            updateEmptyState()
        }
        if (collection != null) {
            source.fetchCollectionPhotos(collection.id, PAGE_SIZE, offset, callback)
        } else {
            source.fetchPhotos(criteria, PAGE_SIZE, offset, callback)
        }
    }

    private fun toggleFilterPanel() {
        if (filterPanel.visibility == View.VISIBLE) {
            filterPanel.visibility = View.GONE
            return
        }
        filterPanel.visibility = View.VISIBLE
        buildFilterPanel()
    }

    private fun buildFilterPanel() {
        filterPanel.removeAllViews()
        addFilterSectionLabel(R.string.library_filter_source)
        val sourceChips = LinearLayout(context).apply { orientation = VERTICAL }
        filterPanel.addView(sourceChips, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        dataSource?.fetchSources { sources ->
            sourceChips.removeAllViews()
            sources.forEach { source ->
                sourceChips.addView(
                    filterChip(source.displayName, criteria.sourceIds.contains(source.id)) { on ->
                        criteria = criteria.copy(
                            sourceIds = if (on) criteria.sourceIds + source.id else criteria.sourceIds - source.id,
                        )
                        reloadAssets()
                        refreshFilterState()
                    },
                )
            }
        }
        addFilterSectionLabel(R.string.library_filter_orientation)
        filterPanel.addView(
            chipRow(
                listOf(
                    Triple("landscape", R.string.library_orientation_landscape, criteria.orientations.contains("landscape")),
                    Triple("portrait", R.string.library_orientation_portrait, criteria.orientations.contains("portrait")),
                    Triple("square", R.string.library_orientation_square, criteria.orientations.contains("square")),
                ),
            ) { wire, on ->
                criteria = criteria.copy(
                    orientations = if (on) criteria.orientations + wire else criteria.orientations - wire,
                )
                reloadAssets()
                refreshFilterState()
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addFilterSectionLabel(R.string.library_filter_status)
        filterPanel.addView(
            chipRow(
                listOf(
                    Triple("ready", R.string.library_status_ready, criteria.statuses.contains("ready")),
                    Triple("pending", R.string.library_status_pending, criteria.statuses.contains("pending")),
                    Triple("failed", R.string.library_status_failed, criteria.statuses.contains("failed")),
                ),
            ) { wire, on ->
                criteria = criteria.copy(
                    statuses = if (on) criteria.statuses + wire else criteria.statuses - wire,
                )
                reloadAssets()
                refreshFilterState()
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addFilterSectionLabel(R.string.library_filter_time)
        filterPanel.addView(
            filterChip(resources.getString(R.string.library_filter_no_date), criteria.noDateOnly) { on ->
                criteria = criteria.copy(noDateOnly = on)
                reloadAssets()
                refreshFilterState()
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    private fun addFilterSectionLabel(textRes: Int) {
        val res = resources
        val label = TextView(context).apply {
            text = res.getString(textRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        filterPanel.addView(
            label,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
    }

    private fun chipRow(
        chips: List<Triple<String, Int, Boolean>>,
        onToggle: (String, Boolean) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        chips.forEachIndexed { index, (wire, labelRes, active) ->
            row.addView(
                filterChip(resources.getString(labelRes), active) { on -> onToggle(wire, on) },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = resources.getDimensionPixelSize(R.dimen.pp_space_8)
                },
            )
        }
        return row
    }

    private fun filterChip(text: String, active: Boolean, onToggle: (Boolean) -> Unit): TextView =
        TextView(context).apply {
            val res = resources
            this.text = text
            contentDescription = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            gravity = Gravity.CENTER
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            isClickable = true
            isFocusable = true
            var on = active
            fun paint() {
                isSelected = on
                background = GradientDrawable().apply {
                    setColor(context.getColor(R.color.pp_surface_panel))
                    cornerRadius = res.getDimension(R.dimen.pp_radius_thumbnail)
                    if (on) {
                        setStroke(indicatorHeightPx() * 2, context.getColor(R.color.pp_accent_action))
                    }
                }
                setTextColor(context.getColor(if (on) R.color.pp_accent_action else R.color.pp_text_primary))
            }
            paint()
            setOnClickListener {
                on = !on
                paint()
                onToggle(on)
            }
        }

    private fun refreshFilterState() {
        filterActiveRow.removeAllViews()
        if (!criteria.isFiltered()) {
            filterActiveRow.visibility = View.GONE
            return
        }
        filterActiveRow.visibility = View.VISIBLE
        val res = resources
        val active = TextView(context).apply {
            text = res.getString(R.string.library_filter_active)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_accent_action))
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
        }
        val clear = TextView(context).apply {
            text = res.getString(R.string.common_clear_filter)
            contentDescription = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_accent_action))
            gravity = Gravity.CENTER
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            val padH = res.getDimensionPixelSize(R.dimen.pp_space_8)
            setPadding(padH, 0, padH, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                criteria = criteria.copy(
                    search = null,
                    sourceIds = emptySet(),
                    orientations = emptySet(),
                    statuses = emptySet(),
                    noDateOnly = false,
                )
                searchInput.setText("")
                reloadAssets()
                refreshFilterState()
                if (filterPanel.visibility == View.VISIBLE) buildFilterPanel()
            }
        }
        filterActiveRow.addView(active, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        filterActiveRow.addView(clear, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun showSortMenu(anchor: View) {
        val res = resources
        val menu = PopupMenu(context, anchor)
        SORTS.forEachIndexed { index, (_, labelRes) ->
            menu.menu.add(0, index, index, res.getString(labelRes))
        }
        menu.setOnMenuItemClickListener { item ->
            criteria = criteria.copy(sort = SORTS[item.itemId].first)
            reloadAssets()
            true
        }
        menu.show()
    }

    private fun collectionDisplayName(collection: CollectionEntity): String =
        CollectionDisplayName.display(
            collection.name,
            collection.type,
            resources.getString(R.string.library_import_batch_name),
        )

    private fun updateEmptyState() {
        val showGridContent = tab == Tab.PHOTOS || activeCollection != null
        val showEmpty = showGridContent && assets.isEmpty() && pendingItems.isEmpty() && exhausted
        if (showEmpty) {
            if (criteria.isFiltered()) {
                emptyTitle.text = resources.getString(R.string.library_search_no_result)
                emptyBody.text = ""
            } else {
                emptyTitle.text = resources.getString(R.string.library_empty_title)
                emptyBody.text = resources.getString(R.string.library_empty_body)
            }
        }
        emptyView.visibility = if (showEmpty) View.VISIBLE else View.GONE
        if (showGridContent) {
            gridView.visibility = if (showEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun loadCollections() {
        val source = dataSource ?: return
        source.fetchCollections { collections ->
            foldersColumn.removeAllViews()
            val res = resources
            collections.forEach { (collection, count) ->
                val displayName = collectionDisplayName(collection)
                val row = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
                    isClickable = true
                    isFocusable = true
                    contentDescription = displayName
                    setOnClickListener { enterCollection(collection) }
                    val name = TextView(context).apply {
                        text = displayName
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                        setTextColor(context.getColor(R.color.pp_text_primary))
                        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    val members = TextView(context).apply {
                        text = count.toString()
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
                        setTextColor(context.getColor(R.color.pp_text_secondary))
                        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                    addView(members, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                }
                foldersColumn.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                val divider = View(context).apply {
                    setBackgroundColor(context.getColor(R.color.pp_separator))
                }
                foldersColumn.addView(
                    divider,
                    LayoutParams(LayoutParams.MATCH_PARENT, SEPARATOR_SIZE_PX),
                )
            }
            emptyTitle.text = resources.getString(R.string.library_empty_title)
            emptyBody.text = resources.getString(R.string.library_empty_body)
            emptyView.visibility = if (collections.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun actionLabel(textRes: Int, onClick: (View) -> Unit): TextView =
        TextView(context).apply {
            val res = resources
            text = res.getString(textRes)
            contentDescription = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_accent_action))
            gravity = Gravity.CENTER
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            val padH = res.getDimensionPixelSize(R.dimen.pp_space_8)
            setPadding(padH, 0, padH, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick(it) }
        }

    private fun indicatorHeightPx(): Int =
        (INDICATOR_HEIGHT_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private inner class GridAdapter(private val radiusPx: Float) : BaseAdapter() {

        override fun getCount(): Int = pendingItems.size + assets.size

        override fun getItem(position: Int): Any =
            if (position < pendingItems.size) pendingItems[position] else assets[position - pendingItems.size]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? CellView) ?: CellView(context, radiusPx)
            val size = cellSizePx.coerceAtLeast(1)
            val params = cell.layoutParams as? AbsListView.LayoutParams
            if (params == null) {
                cell.layoutParams = AbsListView.LayoutParams(size, size)
            } else {
                params.width = size
                params.height = size
            }
            when (val item = getItem(position)) {
                is SourceAssetEntity -> cell.bindPending(item)
                is AssetEntity -> cell.bind(item, size, loader, selecting && selectedIds.contains(item.id))
            }
            return cell
        }
    }

    private inner class CellView(context: Context, radiusPx: Float) : FrameLayout(context) {

        private val image: ImageView
        private val failedLabel: TextView
        private lateinit var pendingBadge: TextView
        private val border: View
        private val badge: ImageView

        init {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                }
            }
            clipToOutline = true
            setBackgroundColor(context.getColor(R.color.pp_surface_panel))
            image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            failedLabel = TextView(context).apply {
                text = resources.getString(R.string.library_status_failed)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_meta))
                setTextColor(context.getColor(R.color.pp_status_error))
                gravity = Gravity.CENTER
                visibility = View.GONE
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            border = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(indicatorHeightPx() * 2, context.getColor(R.color.pp_accent_action))
                    cornerRadius = radiusPx
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
            addView(failedLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            pendingBadge = TextView(context).apply {
                text = resources.getString(R.string.library_pending_badge)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_meta))
                setTextColor(context.getColor(R.color.pp_text_secondary))
                gravity = Gravity.CENTER
                visibility = View.GONE
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(pendingBadge, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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

        fun bind(asset: AssetEntity, sizePx: Int, thumbnailLoader: ThumbnailLoader?, selected: Boolean) {
            val key = ThumbnailLoader.cacheKey(asset.id, sizePx)
            tag = key
            image.setImageDrawable(null)
            failedLabel.visibility = View.GONE
            pendingBadge.visibility = View.GONE
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
                        if (tag != loadedKey) return
                        if (bitmap != null) {
                            image.setImageBitmap(bitmap)
                            failedLabel.visibility = View.GONE
                        } else {
                            image.setImageDrawable(null)
                            failedLabel.visibility = View.VISIBLE
                        }
                    }
                },
            )
        }

        fun bindPending(sourceAsset: SourceAssetEntity) {
            tag = "pending:${sourceAsset.id}"
            image.setImageDrawable(null)
            failedLabel.visibility = View.GONE
            border.visibility = View.GONE
            badge.visibility = View.GONE
            pendingBadge.visibility = View.VISIBLE
            isSelected = false
            contentDescription = resources.getString(R.string.a11y_photo_pending_download)
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
        const val SEARCH_DEBOUNCE_MS = 300L
        const val DISABLED_ALPHA = 0.5f
        const val INDICATOR_HEIGHT_DP = 2f
        const val SEPARATOR_SIZE_PX = 1

        val SORTS = listOf(
            LibrarySort.CAPTURED_DESC to R.string.library_sort_captured_desc,
            LibrarySort.CAPTURED_ASC to R.string.library_sort_captured_asc,
            LibrarySort.IMPORTED_DESC to R.string.library_sort_imported_desc,
            LibrarySort.NAME_ASC to R.string.library_sort_name_asc,
        )
    }
}
