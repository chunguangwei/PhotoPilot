package dev.photopilot.scaffold.ui.nas

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.widget.primaryButton

class NasLanNoticeView(context: Context) : ScrollView(context) {

    var onContinue: (() -> Unit)? = null

    init {
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        isClickable = true
        isFillViewport = true
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(inset, inset, inset, inset)
        }
        val title = TextView(context).apply {
            text = res.getString(R.string.permission_local_network_title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_heading))
            setTextColor(context.getColor(R.color.pp_text_primary))
            gravity = Gravity.CENTER
            setAccessibilityHeading(true)
        }
        val body = TextView(context).apply {
            text = res.getString(R.string.permission_local_network_body)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            gravity = Gravity.CENTER
        }
        val proceed = primaryButton(context, R.string.permission_local_network_continue).apply {
            setOnClickListener { onContinue?.invoke() }
        }
        column.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.addView(
            body,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        column.addView(
            proceed,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_32)
            },
        )
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}
