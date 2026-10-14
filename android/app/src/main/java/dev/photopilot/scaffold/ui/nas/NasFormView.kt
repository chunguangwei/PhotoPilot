package dev.photopilot.scaffold.ui.nas

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.widget.KeyboardDismiss
import dev.photopilot.scaffold.ui.widget.primaryButton
import dev.photopilot.scaffold.ui.widget.secondaryButton

class NasFormView(context: Context) : ScrollView(context) {

    data class Form(
        val protocol: String,
        val host: String,
        val port: Int?,
        val path: String,
        val username: String,
        val password: String,
        val guest: Boolean,
    )

    var onBack: (() -> Unit)? = null
    var onTest: ((Form) -> Unit)? = null
    var onSave: ((Form) -> Unit)? = null

    private var protocol = PROTOCOL_WEBDAV
    private val hostInput: EditText
    private val portInput: EditText
    private val pathInput: EditText
    private val usernameInput: EditText
    private val passwordInput: EditText
    private val guestToggle: CheckBox
    private val passwordVisibleToggle: CheckBox
    private val statusLine: TextView
    private val securityLine: TextView
    private val portError: TextView
    private val testButton: View
    private val saveButton: View
    private var testPassed = false

    private var protocolButtons: List<TextView> = emptyList()

    private fun paintProtocol() {
        protocolButtons.forEach { button ->
            val selected = protocol == (button.tag as String)
            button.isSelected = selected
            button.setTextColor(
                context.getColor(if (selected) R.color.pp_accent_action else R.color.pp_text_primary),
            )
            button.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(context.getColor(R.color.pp_surface_panel))
                cornerRadius = resources.getDimension(R.dimen.pp_radius_thumbnail)
                if (selected) {
                    setStroke(2, context.getColor(R.color.pp_accent_action))
                }
            }
        }
    }

    fun prefill(protocol: String, host: String, port: Int?, path: String) {
        this.protocol = protocol
        hostInput.setText(host)
        portInput.setText(port?.toString() ?: "")
        pathInput.setText(path)
        paintProtocol()
    }

    init {
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        isClickable = true
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, inset, inset, inset)
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = secondaryButton(context, R.string.common_back).apply {
            setOnClickListener { onBack?.invoke() }
        }
        val title = TextView(context).apply {
            text = res.getString(R.string.source_nas_title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_heading))
            setTextColor(context.getColor(R.color.pp_text_primary))
            setAccessibilityHeading(true)
        }
        headerRow.addView(back, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        headerRow.addView(
            title,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        column.addView(headerRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addLabel(column, R.string.source_nas_protocol)
        val protocolRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = mutableListOf<TextView>()
        listOf(
            res.getString(R.string.source_nas_protocol_webdav_https) to PROTOCOL_WEBDAV,
            res.getString(R.string.source_nas_protocol_webdav_http) to PROTOCOL_WEBDAV_HTTP,
            res.getString(R.string.source_nas_protocol_smb) to PROTOCOL_SMB,
        )
            .forEachIndexed { index, (label, value) ->
            val button = TextView(context).apply {
                text = label
                contentDescription = label
                tag = value
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                gravity = Gravity.CENTER
                minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    protocol = value
                    portInput.setText(
                        when (protocol) {
                            PROTOCOL_SMB -> "445"
                            PROTOCOL_WEBDAV_HTTP -> "80"
                            else -> "443"
                        },
                    )
                    markDirty()
                    paintProtocol()
                }
            }
            buttons += button
            protocolRow.addView(
                button,
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = res.getDimensionPixelSize(R.dimen.pp_space_8)
                },
            )
        }
        protocolButtons = buttons
        column.addView(protocolRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        paintProtocol()

        addLabel(column, R.string.source_nas_host)
        hostInput = addInput(column, number = false)
        addLabel(column, R.string.source_nas_port)
        portInput = addInput(column, number = true)
        portInput.setText("443")
        portError = addError(column)
        addLabel(column, R.string.source_nas_share)
        pathInput = addInput(column, number = false)
        addLabel(column, R.string.source_nas_username)
        usernameInput = addInput(column, number = false)
        addLabel(column, R.string.source_nas_password)
        passwordInput = addInput(column, number = false)
        passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        guestToggle = CheckBox(context).apply {
            text = res.getString(R.string.source_nas_guest)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
        }
        column.addView(guestToggle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        passwordVisibleToggle = CheckBox(context).apply {
            KeyboardDismiss.keepInputOnTouch(this)
            text = res.getString(R.string.common_off)
            contentDescription = res.getString(R.string.source_nas_password)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            setOnCheckedChangeListener { _, checked ->
                passwordInput.inputType = if (checked) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                text = res.getString(if (checked) R.string.common_on else R.string.common_off)
            }
        }
        column.addView(
            passwordVisibleToggle,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        statusLine = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            visibility = View.GONE
        }
        column.addView(
            statusLine,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        securityLine = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            visibility = View.GONE
        }
        column.addView(
            securityLine,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_4)
            },
        )

        testButton = secondaryButton(context, R.string.source_nas_test_connection).apply {
            setOnClickListener { currentForm()?.let { markDirty(); onTest?.invoke(it) } }
        }
        saveButton = primaryButton(context, R.string.common_save).apply {
            isEnabled = false
            alpha = DISABLED_ALPHA
            setOnClickListener { if (testPassed) currentForm()?.let { onSave?.invoke(it) } }
        }
        column.addView(
            testButton,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_24)
            },
        )
        column.addView(
            saveButton,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun currentForm(): Form? {
        val host = hostInput.text.toString()
        val portText = portInput.text.toString().trim()
        val port = if (portText.isEmpty()) {
            null
        } else {
            portText.toIntOrNull()?.takeIf { it in 1..65535 }
        }
        if (portText.isNotEmpty() && port == null) {
            portError.text = resources.getString(R.string.playback_invalid_number)
            portError.visibility = View.VISIBLE
        } else {
            portError.visibility = View.GONE
        }
        return Form(
            protocol = protocol,
            host = host,
            port = port,
            path = pathInput.text.toString(),
            username = usernameInput.text.toString(),
            password = passwordInput.text.toString(),
            guest = guestToggle.isChecked,
        )
    }

    fun showTesting() {
        statusLine.visibility = View.VISIBLE
        statusLine.setTextColor(context.getColor(R.color.pp_text_secondary))
        statusLine.text = resources.getString(R.string.source_nas_testing)
    }

    fun showTestOk() {
        testPassed = true
        saveButton.isEnabled = true
        saveButton.alpha = 1f
        statusLine.visibility = View.VISIBLE
        statusLine.setTextColor(context.getColor(R.color.pp_status_success))
        statusLine.text = resources.getString(R.string.source_nas_test_ok)
    }

    fun setSecurityText(text: String) {
        securityLine.visibility = View.VISIBLE
        securityLine.text = text
    }

    fun showTestError(textRes: Int) {
        testPassed = false
        saveButton.isEnabled = false
        saveButton.alpha = DISABLED_ALPHA
        statusLine.visibility = View.VISIBLE
        statusLine.setTextColor(context.getColor(R.color.pp_status_error))
        statusLine.text = resources.getString(textRes)
        securityLine.visibility = View.GONE
    }

    fun markDirty() {
        testPassed = false
        saveButton.isEnabled = false
        saveButton.alpha = DISABLED_ALPHA
    }

    private fun addLabel(parent: LinearLayout, textRes: Int) {
        val res = resources
        val label = TextView(context).apply {
            text = res.getString(textRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        parent.addView(
            label,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
    }

    private fun addInput(parent: LinearLayout, number: Boolean): EditText {
        val res = resources
        val input = EditText(context).apply {
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            setSingleLine(true)
            KeyboardDismiss.configureDone(this)
        }
        parent.addView(input, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return input
    }

    private fun addError(parent: LinearLayout): TextView {
        val res = resources
        val view = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_status_error))
            visibility = View.GONE
        }
        parent.addView(view, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return view
    }

    private companion object {
        const val DISABLED_ALPHA = 0.5f
        const val PROTOCOL_WEBDAV = "webdav"
        const val PROTOCOL_WEBDAV_HTTP = "webdav_http"
        const val PROTOCOL_SMB = "smb"
    }
}
