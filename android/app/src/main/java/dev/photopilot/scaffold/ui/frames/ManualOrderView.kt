package dev.photopilot.scaffold.ui.frames

import android.content.ClipData
import android.content.Context
import android.graphics.Outline
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.logic.GridGeometry
import dev.photopilot.scaffold.ui.library.ThumbnailLoader
import dev.photopilot.scaffold.ui.widget.screenHeader
import java.io.File

class ManualOrderView(
    context: Context,
    private val originalsDir: File,
) : LinearLayout(context) {

    var onBack: ((Boolean) -> Unit)? = null

    private val ordered = mutableListOf<AssetEntity>()
    private val newItemIds = mutableSetOf<String>()
    private var dirty = false
    private var cellSizePx = 0
    private var loader: ThumbnailLoader? = null
    private val gridView: GridView
    private val adapter: OrderAdapter
    private val hintLine: TextView
    private val appendedNotice: TextView
    private val excludedNotice: TextView

    private val gapPx = resources.getDimensionPixelSize(R.dimen.pp_grid_gap)
    private val cellMinPx = resources.getDimensionPixelSize(R.dimen.pp_grid_cell_min)
    private val cellMinCompactPx = resources.getDimensionPixelSize(R.dimen.pp_grid_cell_min_compact)
    private val compactBreakpointPx = resources.getDimensionPixelSize(R.dimen.pp_grid_compact_breakpoint)
    private val gridInsetPx = resources.getDimensionPixelSize(R.dimen.pp_content_inset_compact)

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        isClickable = true
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)

        val headerRow = screenHeader(context, R.string.order_manual_title) { onBack?.invoke(dirty) }
        headerRow.setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_8), inset, 0)
        addView(headerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        hintLine = TextView(context).apply {
            text = res.getString(R.string.order_manual_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        appendedNotice = TextView(context).apply {
            text = res.getString(R.string.order_new_appended)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            visibility = View.GONE
        }
        excludedNotice = TextView(context).apply {
            text = res.getString(R.string.order_excluded_order_preserved)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            visibility = View.GONE
        }
        listOf(hintLine, appendedNotice, excludedNotice).forEach { line ->
            addView(
                line,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = inset
                    marginEnd = inset
                    topMargin = res.getDimensionPixelSize(R.dimen.pp_space_4)
                },
            )
        }

        adapter = OrderAdapter()
        gridView = GridView(context).apply {
            adapter = this@ManualOrderView.adapter
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = gapPx
            verticalSpacing = gapPx
            clipToPadding = false
            setPadding(gridInsetPx, gapPx, gridInsetPx, gapPx)
        }
        addView(gridView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun currentOrder(): List<String> = ordered.map { it.id }

    fun bind(rows: List<Pair<AssetEntity, Long?>>, hasExcludes: Boolean) {
        ordered.clear()
        ordered.addAll(rows.map { it.first })
        newItemIds.clear()
        newItemIds.addAll(rows.filter { it.second == null }.map { it.first.id })
        dirty = false
        appendedNotice.visibility = if (newItemIds.isEmpty()) View.GONE else View.VISIBLE
        excludedNotice.visibility = if (hasExcludes) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    fun hasUnsavedChanges(): Boolean = dirty

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val columns = GridGeometry.columns(w - gridInsetPx * 2, gapPx, cellMinPx, cellMinCompactPx, compactBreakpointPx)
        if (gridView.numColumns != columns) gridView.numColumns = columns
        val cell = (w - gridInsetPx * 2 - gapPx * (columns - 1)) / columns
        if (cell != cellSizePx && cell > 0) {
            cellSizePx = cell
            val approxRows = (h / (cell + gapPx)) + 1
            loader = ThumbnailLoader(originalsDir, columns * approxRows * CACHE_ROWS_FACTOR)
            adapter.notifyDataSetChanged()
        }
    }

    private fun moveItem(from: Int, to: Int) {
        if (from == to || from !in ordered.indices || to !in ordered.indices) return
        val item = ordered.removeAt(from)
        ordered.add(to, item)
        dirty = true
        adapter.notifyDataSetChanged()
    }

    private fun showItemMenu(anchor: View, position: Int) {
        val menu = android.widget.PopupMenu(context, anchor)
        menu.menu.add(0, MENU_MOVE_START, 0, resources.getString(R.string.order_move_to_start))
        menu.menu.add(0, MENU_MOVE_END, 1, resources.getString(R.string.order_move_to_end))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_MOVE_START -> moveItem(position, 0)
                MENU_MOVE_END -> moveItem(position, ordered.size - 1)
            }
            true
        }
        menu.show()
    }

    private inner class OrderAdapter : BaseAdapter() {

        override fun getCount(): Int = ordered.size

        override fun getItem(position: Int): AssetEntity = ordered[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? OrderCellView) ?: OrderCellView(context)
            val asset = getItem(position)
            val size = cellSizePx.coerceAtLeast(1)
            val params = cell.layoutParams as? AbsListView.LayoutParams
            if (params == null) {
                cell.layoutParams = AbsListView.LayoutParams(size, size)
            } else {
                params.width = size
                params.height = size
            }
            cell.tag = position
            cell.bind(asset, size, newItemIds.contains(asset.id), loader)
            cell.setOnClickListener { showItemMenu(cell, position) }
            cell.setOnLongClickListener { view ->
                val data = ClipData.newPlainText("asset", asset.id)
                view.startDragAndDrop(data, View.DragShadowBuilder(view), position, 0)
                true
            }
            cell.setOnDragListener { view, event ->
                when (event.action) {
                    DragEvent.ACTION_DROP -> {
                        val from = event.localState as? Int ?: return@setOnDragListener false
                        val to = view.tag as? Int ?: return@setOnDragListener false
                        moveItem(from, to)
                        true
                    }
                    DragEvent.ACTION_DRAG_STARTED,
                    DragEvent.ACTION_DRAG_ENTERED,
                    DragEvent.ACTION_DRAG_EXITED,
                    DragEvent.ACTION_DRAG_LOCATION,
                    DragEvent.ACTION_DRAG_ENDED,
                    -> true
                    else -> false
                }
            }
            return cell
        }
    }

    private inner class OrderCellView(context: Context) : FrameLayout(context) {

        private val image: ImageView
        private val badge: TextView

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
            badge = TextView(context).apply {
                text = resources.getString(R.string.order_new_badge)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_meta))
                setTextColor(context.getColor(R.color.pp_text_on_photo))
                val padH = resources.getDimensionPixelSize(R.dimen.pp_space_8)
                val padV = resources.getDimensionPixelSize(R.dimen.pp_space_4)
                setPadding(padH, padV, padH, padV)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(context.getColor(R.color.pp_accent_brand))
                }
                visibility = View.GONE
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(
                badge,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.TOP or Gravity.START
                },
            )
            contentDescription = resources.getString(R.string.a11y_drag_handle)
        }

        fun bind(asset: AssetEntity, sizePx: Int, isNew: Boolean, thumbnailLoader: ThumbnailLoader?) {
            val key = ThumbnailLoader.cacheKey(asset.id, sizePx)
            image.tag = key
            image.setImageDrawable(null)
            badge.visibility = if (isNew) View.VISIBLE else View.GONE
            val activeLoader = thumbnailLoader ?: return
            activeLoader.load(
                asset,
                sizePx,
                key,
                object : ThumbnailLoader.Callback {
                    override fun onLoaded(loadedKey: String, bitmap: android.graphics.Bitmap?) {
                        if (image.tag == loadedKey && bitmap != null) {
                            image.setImageBitmap(bitmap)
                        }
                    }
                },
            )
        }
    }

    private companion object {
        const val MENU_MOVE_START = 1
        const val MENU_MOVE_END = 2
        const val CACHE_ROWS_FACTOR = 3
    }
}
