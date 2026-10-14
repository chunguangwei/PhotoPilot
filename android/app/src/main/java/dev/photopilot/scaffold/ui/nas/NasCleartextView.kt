package dev.photopilot.scaffold.ui.nas

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.widget.primaryButton
import dev.photopilot.scaffold.ui.widget.secondaryButton

class NasCleartextView(context: Context) : ScrollView(context) {

    var onAllowPrivate: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null

    fun bind(privateEligible: Boolean) {
        val res = resources
        val body = TextView(context).apply {
            text = res.getString(
                if (privateEligible) R.string.cleartext_body else R.string.cleartext_public_blocked,
            )
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            gravity = Gravity.CENTER
        }
        column.addView(
            body,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        if (privateEligible) {
            val allow = primaryButton(context, R.string.cleartext_allow_private_only).apply {
                setOnClickListener { onAllowPrivate?.invoke() }
            }
            column.addView(
                allow,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = res.getDimensionPixelSize(R.dimen.pp_space_32)
                },
            )
        }
        val back = secondaryButton(context, R.string.common_back).apply {
            setOnClickListener { onBack?.invoke() }
        }
        column.addView(
            back,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
    }

    private val column: LinearLayout

    init {
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        isClickable = true
        isFillViewport = true
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)
        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(inset, inset, inset, inset)
        }
        val title = TextView(context).apply {
            text = res.getString(R.string.cleartext_title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_heading))
            setTextColor(context.getColor(R.color.pp_text_primary))
            gravity = Gravity.CENTER
            setAccessibilityHeading(true)
        }
        column.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}
