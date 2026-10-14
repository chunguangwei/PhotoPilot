package dev.photopilot.scaffold.ui.nas

import android.content.Context
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.widget.primaryButton
import dev.photopilot.scaffold.ui.widget.secondaryButton
import java.util.Date

class NasCertView(context: Context) : ScrollView(context) {

    var onTrust: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    fun bind(fingerprint: String, issuer: String?, validUntilMs: Long?, changed: Boolean) {
        column.removeAllViews()
        val res = resources
        val title = TextView(context).apply {
            text = res.getString(if (changed) R.string.cert_changed_title else R.string.cert_title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_heading))
            setTextColor(context.getColor(R.color.pp_text_primary))
            gravity = Gravity.CENTER
            setAccessibilityHeading(true)
        }
        val body = TextView(context).apply {
            text = res.getString(if (changed) R.string.cert_changed_body else R.string.cert_body)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            gravity = Gravity.CENTER
        }
        column.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.addView(
            body,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        addRow(R.string.cert_fingerprint, fingerprint, mono = true)
        issuer?.let { addRow(R.string.cert_issuer, it, mono = false) }
        validUntilMs?.let {
            addTextLine(
                res.getString(R.string.cert_valid_until, DateFormat.getDateFormat(context).format(Date(it))),
            )
        }
        val trust = primaryButton(context, R.string.cert_trust_once).apply {
            setOnClickListener { onTrust?.invoke() }
        }
        val cancel = secondaryButton(context, R.string.common_cancel).apply {
            setOnClickListener { onCancel?.invoke() }
        }
        column.addView(
            trust,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_32)
            },
        )
        column.addView(
            cancel,
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
        val inset = resources.getDimensionPixelSize(R.dimen.pp_content_inset)
        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(inset, inset, inset, inset)
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun addRow(labelRes: Int, value: String, mono: Boolean) {
        val res = resources
        val label = TextView(context).apply {
            text = res.getString(labelRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        val valueView = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_primary))
            if (mono) typeface = android.graphics.Typeface.MONOSPACE
        }
        column.addView(
            label,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        column.addView(valueView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun addTextLine(text: String) {
        val res = resources
        val view = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        column.addView(
            view,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
    }
}
