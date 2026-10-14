package dev.photopilot.scaffold.ui.welcome

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.widget.primaryButton
import dev.photopilot.scaffold.ui.widget.secondaryButton

class WelcomeView(context: Context) : ScrollView(context) {

    var onPrimary: (() -> Unit)? = null
    var onSecondary: (() -> Unit)? = null

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
            text = res.getString(R.string.welcome_title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_title))
            setTextColor(context.getColor(R.color.pp_text_primary))
            gravity = Gravity.CENTER
            setAccessibilityHeading(true)
        }
        val body = TextView(context).apply {
            text = res.getString(R.string.welcome_body)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            gravity = Gravity.CENTER
        }
        val primary = primaryButton(context, R.string.welcome_primary_action).apply {
            setOnClickListener { onPrimary?.invoke() }
        }
        val secondary = secondaryButton(context, R.string.welcome_secondary_action).apply {
            setOnClickListener { onSecondary?.invoke() }
        }
        val mark = dev.photopilot.scaffold.ui.widget.BrandMarkView(context)
        val markSize = (112 * res.displayMetrics.density).toInt()
        column.addView(mark, LinearLayout.LayoutParams(markSize, markSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = res.getDimensionPixelSize(R.dimen.pp_space_24)
        })
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
            primary,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_32)
            },
        )
        column.addView(
            secondary,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}
