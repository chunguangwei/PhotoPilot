package dev.photopilot.scaffold.ui.settings

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.widget.secondaryButton

class LicensesView(context: Context) : LinearLayout(context) {

    var onBack: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        isClickable = true
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)
        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_8), inset, 0)
        }
        val back = secondaryButton(context, R.string.common_back).apply {
            setOnClickListener { onBack?.invoke() }
        }
        val title = TextView(context).apply {
            text = res.getString(R.string.settings_licenses)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_heading))
            setTextColor(context.getColor(R.color.pp_text_primary))
            setAccessibilityHeading(true)
        }
        headerRow.addView(back, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        headerRow.addView(
            title,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        addView(headerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(context)
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_8), inset, inset)
        }
        val notice = TextView(context).apply {
            text = res.getString(R.string.settings_license_notice)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        column.addView(
            notice,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        LICENSES.forEach { (name, license) ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
                gravity = Gravity.CENTER_VERTICAL
            }
            val nameView = TextView(context).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                setTextColor(context.getColor(R.color.pp_text_primary))
            }
            val licenseView = TextView(context).apply {
                text = license
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                setTextColor(context.getColor(R.color.pp_text_secondary))
                gravity = Gravity.END
            }
            row.addView(nameView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(licenseView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            column.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val musicNote = TextView(context).apply {
            text = res.getString(R.string.licenses_music_note)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        column.addView(
            musicNote,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        scroll.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private companion object {
        val LICENSES = listOf(
            "Room" to "Apache-2.0",
            "Kotlin" to "Apache-2.0",
            "SQLite" to "Public Domain",
            "SMBJ" to "Apache-2.0",
            "slf4j-api" to "MIT",
            "Bouncy Castle" to "Bouncy Castle License",
            "mbassador" to "MIT",
            "ASN.1" to "Apache-2.0",
        )
    }
}
