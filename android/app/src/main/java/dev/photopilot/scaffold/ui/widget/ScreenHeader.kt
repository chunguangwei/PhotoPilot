package dev.photopilot.scaffold.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.photopilot.scaffold.R

internal fun backButton(context: Context, onBack: () -> Unit): LinearLayout =
    LinearLayout(context).apply {
        val res = resources
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val touch = res.getDimensionPixelSize(R.dimen.pp_touch_target)
        minimumWidth = touch
        minimumHeight = touch
        isClickable = true
        isFocusable = true
        contentDescription = res.getString(R.string.common_back)
        setOnClickListener { onBack() }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_back_24)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.pp_text_primary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val iconSize = res.getDimensionPixelSize(R.dimen.pp_icon_button)
        addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
        val label = TextView(context).apply {
            text = res.getString(R.string.common_back)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
    }

internal fun screenHeader(context: Context, titleRes: Int, onBack: () -> Unit): LinearLayout =
    LinearLayout(context).apply {
        val res = resources
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = res.getDimensionPixelSize(R.dimen.pp_header_height)
        addView(
            backButton(context, onBack),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val title = TextView(context).apply {
            text = res.getString(titleRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_heading))
            setTextColor(context.getColor(R.color.pp_text_primary))
            setAccessibilityHeading(true)
        }
        addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
    }
