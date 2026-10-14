package dev.photopilot.scaffold.ui.navigation

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.MainSection

internal class NavItemView(
    context: Context,
    val section: MainSection,
    style: Style,
) : LinearLayout(context) {

    enum class Style { BOTTOM, RAIL, SIDEBAR }

    var onClick: ((MainSection) -> Unit)? = null

    init {
        val res = resources
        val tint = context.getColorStateList(R.color.nav_item_tint)
        val indicatorThickness =
            (INDICATOR_THICKNESS_DP * res.displayMetrics.density).toInt().coerceAtLeast(1)
        val touchTarget = res.getDimensionPixelSize(R.dimen.pp_touch_target)
        val space4 = res.getDimensionPixelSize(R.dimen.pp_space_4)
        val space8 = res.getDimensionPixelSize(R.dimen.pp_space_8)
        val space16 = res.getDimensionPixelSize(R.dimen.pp_space_16)

        isClickable = true
        isFocusable = true
        contentDescription = res.getString(section.titleRes)
        val ripple = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true) &&
            ripple.resourceId != 0
        ) {
            foreground = context.getDrawable(ripple.resourceId)
        }

        val indicatorStates = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_selected),
                ColorDrawable(context.getColor(R.color.pp_accent_action)),
            )
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        val indicator = View(context).apply {
            background = indicatorStates
            isDuplicateParentStateEnabled = true
        }
        val icon = ImageView(context).apply {
            setImageDrawable(context.getDrawable(section.iconRes))
            imageTintList = tint
            isDuplicateParentStateEnabled = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = TextView(context).apply {
            text = res.getString(section.titleRes)
            val labelSizeRes = if (style == Style.SIDEBAR) R.dimen.pp_text_body else R.dimen.pp_text_caption
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(labelSizeRes))
            setTextColor(tint)
            maxLines = 1
            isDuplicateParentStateEnabled = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        minimumHeight = touchTarget
        when (style) {
            Style.BOTTOM -> {
                orientation = VERTICAL
                val content = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER
                    isDuplicateParentStateEnabled = true
                    addView(icon)
                    addView(
                        label,
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                            topMargin = space4
                        },
                    )
                }
                addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
                addView(indicator, LayoutParams(LayoutParams.MATCH_PARENT, indicatorThickness))
            }
            Style.RAIL -> {
                orientation = HORIZONTAL
                addView(
                    indicator,
                    LayoutParams(indicatorThickness, LayoutParams.MATCH_PARENT).apply {
                        topMargin = space8
                        bottomMargin = space8
                    },
                )
                val content = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER
                    isDuplicateParentStateEnabled = true
                    addView(icon)
                    addView(
                        label,
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                            topMargin = space4
                        },
                    )
                }
                addView(content, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
            Style.SIDEBAR -> {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    indicator,
                    LayoutParams(indicatorThickness, LayoutParams.MATCH_PARENT).apply {
                        topMargin = space8
                        bottomMargin = space8
                    },
                )
                val content = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isDuplicateParentStateEnabled = true
                    setPadding(space16, 0, space16, 0)
                    addView(icon)
                    addView(
                        label,
                        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                            marginStart = space16
                        },
                    )
                }
                addView(content, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        setOnClickListener { onClick?.invoke(section) }
    }

    private companion object {
        const val INDICATOR_THICKNESS_DP = 2f
    }
}
