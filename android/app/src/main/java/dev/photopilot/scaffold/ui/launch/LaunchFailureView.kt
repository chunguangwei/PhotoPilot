package dev.photopilot.scaffold.ui.launch

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.widget.primaryButton
import dev.photopilot.scaffold.ui.widget.secondaryButton

class LaunchFailureView(context: Context) : ScrollView(context) {

    var onRetry: (() -> Unit)? = null
    var onExportDiagnostics: (() -> Unit)? = null

    init {
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        isFillViewport = true
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(inset, inset, inset, inset)
        }
        val title = TextView(context).apply {
            text = res.getString(R.string.launch_migration_failed_title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_heading))
            setTextColor(context.getColor(R.color.pp_text_primary))
            gravity = Gravity.CENTER
            setAccessibilityHeading(true)
        }
        val body = TextView(context).apply {
            text = res.getString(R.string.launch_migration_failed_body)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            gravity = Gravity.CENTER
        }
        val retry = primaryButton(context, R.string.common_retry).apply {
            setOnClickListener { onRetry?.invoke() }
        }
        val export = secondaryButton(context, R.string.settings_export_diagnostics).apply {
            setOnClickListener { onExportDiagnostics?.invoke() }
        }
        column.addView(
            title,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        column.addView(
            body,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        column.addView(
            retry,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_32)
            },
        )
        column.addView(
            export,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}
