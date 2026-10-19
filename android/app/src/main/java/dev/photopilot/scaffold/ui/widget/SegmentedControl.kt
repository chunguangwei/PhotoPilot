package dev.photopilot.scaffold.ui.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.photopilot.scaffold.R

internal class SegmentedControl(
    context: Context,
    private val textSizeRes: Int = R.dimen.pp_text_caption,
    private val autoSize: Boolean = false,
) : ViewGroup(context) {

    private val segments = mutableListOf<TextView>()
    private var selectedIndex = -1
    private var onSelect: ((Int) -> Unit)? = null
    private val gap = resources.getDimensionPixelSize(R.dimen.pp_space_4)
    private val minSegmentHeight = (SEGMENT_HEIGHT_DP * resources.displayMetrics.density).toInt()
    private var columns = 1
    private var rowHeight = 0

    init {
        val inset = resources.getDimensionPixelSize(R.dimen.pp_space_4)
        setPadding(inset, inset, inset, inset)
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.pp_segmented_track))
            cornerRadius = resources.getDimension(R.dimen.pp_radius_thumbnail)
        }
    }

    fun setItems(labels: List<CharSequence>, selected: Int, listener: (Int) -> Unit) {
        removeAllViews()
        segments.clear()
        onSelect = listener
        val res = resources
        labels.forEachIndexed { index, label ->
            val segment = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(textSizeRes))
                if (autoSize) {
                    setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, TypedValue.COMPLEX_UNIT_SP)
                }
                maxLines = 1
                gravity = Gravity.CENTER
                val padH = res.getDimensionPixelSize(R.dimen.pp_space_8)
                setPadding(padH, 0, padH, 0)
                contentDescription = label
                isClickable = true
                isFocusable = true
                accessibilityDelegate = object : AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = android.widget.RadioButton::class.java.name
                        info.isCheckable = true
                        info.isChecked = host.isSelected
                    }
                }
                setOnClickListener { select(index, notify = true) }
            }
            segments += segment
            addView(segment)
        }
        select(selected, notify = false)
        requestLayout()
    }

    fun setSelected(index: Int) = select(index, notify = false)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val count = childCount
        if (count == 0) {
            setMeasuredDimension(
                resolveSize(paddingLeft + paddingRight, widthMeasureSpec),
                resolveSize(paddingTop + paddingBottom, heightMeasureSpec),
            )
            return
        }
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var widest = 0
        for (index in 0 until count) {
            val child = getChildAt(index)
            child.measure(unspecified, unspecified)
            widest = maxOf(widest, child.measuredWidth)
        }
        columns = if (available <= 0) {
            count
        } else {
            var fit = count
            while (fit > 1 && widest * fit + gap * (fit - 1) > available) fit--
            fit
        }
        val rows = (count + columns - 1) / columns
        rowHeight = minSegmentHeight
        for (index in 0 until count) {
            val row = index / columns
            val inRow = minOf(columns, count - row * columns)
            val width = if (available > 0) {
                ((available - gap * (inRow - 1)) / inRow).coerceAtLeast(1)
            } else {
                widest
            }
            val child = getChildAt(index)
            child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), unspecified)
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        val heightSpec = MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
        for (index in 0 until count) {
            val child = getChildAt(index)
            child.measure(
                MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                heightSpec,
            )
        }
        val width = paddingLeft + paddingRight +
            (if (available > 0) available else widest * columns + gap * (columns - 1))
        val height = paddingTop + paddingBottom + rowHeight * rows + gap * (rows - 1)
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val count = childCount
        if (count == 0) return
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var index = 0
        var row = 0
        while (index < count) {
            val inRow = minOf(columns, count - row * columns)
            val top = paddingTop + row * (rowHeight + gap)
            var offset = 0
            for (k in 0 until inRow) {
                val child = getChildAt(index + k)
                val childWidth = child.measuredWidth
                val left = if (rtl) {
                    width - paddingRight - offset - childWidth
                } else {
                    paddingLeft + offset
                }
                child.layout(left, top, left + childWidth, top + child.measuredHeight)
                offset += childWidth + gap
            }
            index += inRow
            row++
        }
    }

    private fun select(index: Int, notify: Boolean) {
        if (index !in segments.indices) return
        selectedIndex = index
        val res = resources
        val pillRadius = res.getDimension(R.dimen.pp_radius_thumbnail) - res.getDimension(R.dimen.pp_space_4)
        segments.forEachIndexed { i, segment ->
            val active = i == index
            segment.isSelected = active
            segment.setTextColor(context.getColor(if (active) R.color.pp_text_primary else R.color.pp_text_secondary))
            segment.background = if (active) {
                GradientDrawable().apply {
                    setColor(context.getColor(R.color.pp_segmented_pill))
                    cornerRadius = pillRadius
                }
            } else {
                null
            }
            segment.elevation = if (active) res.displayMetrics.density else 0f
        }
        if (notify) onSelect?.invoke(index)
    }

    private companion object {
        const val SEGMENT_HEIGHT_DP = 40f
    }
}
