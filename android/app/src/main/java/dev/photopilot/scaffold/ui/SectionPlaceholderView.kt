package dev.photopilot.scaffold.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import dev.photopilot.scaffold.R

class SectionPlaceholderView(context: Context, titleRes: Int) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        val title = TextView(context).apply {
            text = resources.getString(titleRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_title))
            setTextColor(context.getColor(R.color.pp_text_primary))
            gravity = Gravity.CENTER
            setAccessibilityHeading(true)
        }
        addView(
            title,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.pp_content_inset)
                marginEnd = resources.getDimensionPixelSize(R.dimen.pp_content_inset)
            },
        )
    }
}
