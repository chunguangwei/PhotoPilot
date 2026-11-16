package dev.photopilot.scaffold.ui.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.photopilot.scaffold.R

internal fun formHeader(context: Context, title: String, onBack: () -> Unit): LinearLayout =
    LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        val inset = resources.getDimensionPixelSize(R.dimen.pp_space_16)
        setPadding(inset, 0, inset, 0)
        minimumHeight = resources.getDimensionPixelSize(R.dimen.pp_header_height)
        addView(backButton(context, onBack))
        addView(TextView(context).apply {
            text = title
            textSize = 20f
            setTextColor(context.getColor(R.color.pp_text_primary))
            setAccessibilityHeading(true)
            setPadding(inset, 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

internal class DisclosureSection(context: Context, title: String) : LinearLayout(context) {
    val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val header = secondaryButton(context, R.string.common_details)
    private val heading = title
    private var summary = ""

    init {
        orientation = VERTICAL
        val inset = resources.getDimensionPixelSize(R.dimen.pp_space_12)
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.pp_surface_panel))
            cornerRadius = resources.getDimension(R.dimen.pp_radius_group)
        }
        header.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        header.setPadding(inset, 0, inset, 0)
        header.setOnClickListener { setExpanded(body.visibility != View.VISIBLE) }
        addView(header, LayoutParams(-1, -2))
        body.setPadding(inset, 0, inset, inset)
        addView(body, LayoutParams(-1, -2))
        setExpanded(false)
    }

    fun setSummary(value: String) {
        if (summary == value) return
        summary = value
        updateHeading()
    }

    fun setExpanded(expanded: Boolean) {
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        updateHeading()
    }

    private fun updateHeading() {
        header.text = heading + if (summary.isEmpty()) "" else " · $summary"
        val arrow = context.getDrawable(if (body.visibility == View.VISIBLE) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float)?.mutate()
        arrow?.setTint(context.getColor(R.color.pp_accent_action))
        header.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, arrow, null)
        header.compoundDrawablePadding = resources.getDimensionPixelSize(R.dimen.pp_space_8)
        val state = resources.getString(if (body.visibility == View.VISIBLE) R.string.a11y_expanded else R.string.a11y_collapsed)
        header.contentDescription = "${header.text}, $state"
    }
}
