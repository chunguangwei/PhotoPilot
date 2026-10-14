package dev.photopilot.scaffold.ui.permissions

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.widget.primaryButton
import dev.photopilot.scaffold.ui.widget.secondaryButton

class PhotoPermissionOverlayView(context: Context) : ScrollView(context) {

    enum class State { PROMPT, GRANTED, PARTIAL, DENIED, CANCELLED }

    var onContinue: (() -> Unit)? = null
    var onRetry: (() -> Unit)? = null
    var onOpenSettings: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null

    var state: State = State.PROMPT
        private set

    private val column: LinearLayout

    init {
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        isFillViewport = true
        isClickable = true
        val inset = resources.getDimensionPixelSize(R.dimen.pp_content_inset)
        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(inset, inset, inset, inset)
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        render()
    }

    fun setState(newState: State) {
        state = newState
        render()
    }

    private fun render() {
        val res = resources
        column.removeAllViews()
        val title = TextView(context).apply {
            text = res.getString(R.string.source_local_photos_title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_heading))
            setTextColor(context.getColor(R.color.pp_text_primary))
            gravity = Gravity.CENTER
            setAccessibilityHeading(true)
        }
        column.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val space16 = res.getDimensionPixelSize(R.dimen.pp_space_16)
        val space12 = res.getDimensionPixelSize(R.dimen.pp_space_12)
        val space32 = res.getDimensionPixelSize(R.dimen.pp_space_32)
        when (state) {
            State.PROMPT -> {
                addMessage(R.string.permission_photos_usage, space16)
                addButton(primaryButton(context, R.string.common_continue), space32) { onContinue?.invoke() }
            }
            State.GRANTED -> {
                addMessage(R.string.permission_photos_usage, space16)
                addButton(primaryButton(context, R.string.common_continue), space32) { onDone?.invoke() }
            }
            State.PARTIAL -> {
                addMessage(R.string.permission_photos_limited_notice, space16)
                addMessage(R.string.error_auth_limited_body, space16)
                addButton(primaryButton(context, R.string.common_continue), space32) { onDone?.invoke() }
                addButton(secondaryButton(context, R.string.common_open_system_settings), space12) {
                    onOpenSettings?.invoke()
                }
            }
            State.DENIED -> {
                addMessage(R.string.error_auth_denied_body, space16)
                addButton(primaryButton(context, R.string.common_open_system_settings), space32) {
                    onOpenSettings?.invoke()
                }
                addButton(secondaryButton(context, R.string.common_back), space12) { onBack?.invoke() }
            }
            State.CANCELLED -> {
                addMessage(R.string.error_auth_cancelled_body, space16)
                addButton(primaryButton(context, R.string.common_retry), space32) { onRetry?.invoke() }
                addButton(secondaryButton(context, R.string.common_back), space12) { onBack?.invoke() }
            }
        }
    }

    private fun addMessage(textRes: Int, topMargin: Int) {
        val message = TextView(context).apply {
            text = resources.getString(textRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            gravity = Gravity.CENTER
        }
        column.addView(
            message,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                this.topMargin = topMargin
            },
        )
    }

    private fun addButton(button: View, topMargin: Int, action: () -> Unit) {
        button.setOnClickListener { action() }
        column.addView(
            button,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                this.topMargin = topMargin
            },
        )
    }
}
