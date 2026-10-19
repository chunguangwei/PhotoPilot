package dev.photopilot.scaffold.ui.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import dev.photopilot.scaffold.R

internal class DropdownField(context: Context) : FrameLayout(context) {

    private val label: TextView
    private val chevron: TextView

    var text: CharSequence
        get() = label.text
        set(value) {
            label.text = value
        }

    init {
        val res = resources
        minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
        val horizontal = res.getDimensionPixelSize(R.dimen.pp_space_16)
        setPadding(horizontal, 0, horizontal, 0)
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.pp_surface_panel))
            cornerRadius = res.getDimension(R.dimen.pp_radius_group)
        }
        isClickable = true
        isFocusable = true
        label = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            maxLines = 1
        }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START))
        chevron = TextView(context).apply {
            text = CHEVRON_COLLAPSED
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        addView(chevron, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END))
    }

    fun setExpanded(expanded: Boolean) {
        chevron.text = if (expanded) CHEVRON_EXPANDED else CHEVRON_COLLAPSED
    }

    fun setText(textRes: Int) {
        label.setText(textRes)
    }

    private companion object {
        const val CHEVRON_EXPANDED = "⌄"
        const val CHEVRON_COLLAPSED = "›"
    }
}

internal fun dropdownField(context: Context, textRes: Int): DropdownField =
    DropdownField(context).apply { setText(textRes) }
