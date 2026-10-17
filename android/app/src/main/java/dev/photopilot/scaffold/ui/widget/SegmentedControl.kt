package dev.photopilot.scaffold.ui.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.photopilot.scaffold.R

internal class SegmentedControl(
    context: Context,
    private val textSizeRes: Int = R.dimen.pp_text_caption,
    private val autoSize: Boolean = false,
) : LinearLayout(context) {

    private val segments = mutableListOf<TextView>()
    private var selectedIndex = -1
    private var onSelect: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_space_4)
        setPadding(inset, inset, inset, inset)
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.pp_segmented_track))
            cornerRadius = res.getDimension(R.dimen.pp_radius_thumbnail)
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
                minimumHeight = (SEGMENT_HEIGHT_DP * res.displayMetrics.density).toInt()
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
            addView(
                segment,
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = res.getDimensionPixelSize(R.dimen.pp_space_4)
                },
            )
        }
        select(selected, notify = false)
    }

    fun setSelected(index: Int) = select(index, notify = false)

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
