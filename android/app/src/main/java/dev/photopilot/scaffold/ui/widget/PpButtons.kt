package dev.photopilot.scaffold.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import dev.photopilot.scaffold.R

internal fun primaryButton(context: Context, textRes: Int): TextView =
    TextView(context).apply {
        val res = resources
        text = res.getString(textRes)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(context.getColor(R.color.pp_text_secondary), context.getColor(R.color.pp_surface_canvas)),
        ))
        gravity = Gravity.CENTER
        minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
        val horizontal = res.getDimensionPixelSize(R.dimen.pp_space_24)
        setPadding(horizontal, 0, horizontal, 0)
        fun shape(color: Int, outlined: Boolean = false) = GradientDrawable().apply {
            setColor(context.getColor(color))
            cornerRadius = res.getDimension(R.dimen.pp_radius_group)
            if (outlined) setStroke((res.displayMetrics.density * 2).toInt(), context.getColor(R.color.pp_text_secondary))
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(R.color.pp_surface_panel, outlined = true))
            addState(intArrayOf(android.R.attr.state_pressed), shape(R.color.pp_text_secondary))
            addState(intArrayOf(android.R.attr.state_focused), shape(R.color.pp_text_primary, outlined = true))
            addState(intArrayOf(), shape(R.color.pp_text_primary))
        }
        isClickable = true
        isFocusable = true
    }

internal fun secondaryButton(context: Context, textRes: Int): TextView =
    TextView(context).apply {
        val res = resources
        text = res.getString(textRes)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
        setTextColor(context.getColor(R.color.pp_accent_action))
        gravity = Gravity.CENTER
        minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
        val horizontal = res.getDimensionPixelSize(R.dimen.pp_space_24)
        setPadding(horizontal, 0, horizontal, 0)
        isClickable = true
        isFocusable = true
    }

internal fun dropdownField(context: Context, textRes: Int): TextView =
    TextView(context).apply {
        val res = resources
        text = res.getString(textRes)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
        setTextColor(context.getColor(R.color.pp_text_primary))
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
        val horizontal = res.getDimensionPixelSize(R.dimen.pp_space_16)
        setPadding(horizontal, 0, horizontal, 0)
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.pp_surface_panel))
            cornerRadius = res.getDimension(R.dimen.pp_radius_group)
        }
        isClickable = true
        isFocusable = true
    }
