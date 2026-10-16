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
import dev.photopilot.scaffold.ui.widget.screenHeader
import dev.photopilot.scaffold.ui.widget.secondaryButton

class NasFormView(context: Context) : LinearLayout(context) {

    data class Form(
        val protocol: String,
        val host: String,
        val port: Int?,
        val path: String,
        val username: String,
        val password: String,
    )

    var onBack: (() -> Unit)? = null
    var onTest: ((Form) -> Unit)? = null
    var onSave: ((Form) -> Unit)? = null

    private var protocol = PROTOCOL_WEBDAV
    private var transport = TRANSPORT_HTTPS
    private val hostInput: EditText
    private val portInput: EditText
    private val pathInput: EditText
    private val usernameInput: EditText
    private val passwordInput: EditText
    private val passwordVisibleToggle: CheckBox
    private val statusLine: TextView
    private val securityLine: TextView
    private val portError: TextView
    private val testButton: View
    private val saveButton: View
    private var testPassed = false
    private var pendingSave = false
    private var pathLabel: TextView? = null

    private var protocolButtons: List<TextView> = emptyList()
    private var transportButtons: List<TextView> = emptyList()
    private var transportSection: LinearLayout? = null

    private fun paintProtocol() {
        paintOptions(protocolButtons) { tag -> (tag == PROTOCOL_SMB) == (protocol == PROTOCOL_SMB) }
        pathLabel?.setText(if (protocol == PROTOCOL_SMB) R.string.source_nas_share else R.string.source_nas_path)
    }

    private fun paintTransport() {
        paintOptions(transportButtons) { tag -> tag == transport }
    }

    private fun paintOptions(buttons: List<TextView>, isSelected: (String) -> Boolean) {
        buttons.forEach { button ->
            val selected = isSelected(button.tag as String)
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
        transport = if (protocol == PROTOCOL_WEBDAV_HTTP) TRANSPORT_HTTP else TRANSPORT_HTTPS
        hostInput.setText(host)
        portInput.setText(port?.toString() ?: "")
        pathInput.setText(path)
        transportSection?.visibility = if (protocol == PROTOCOL_SMB) View.GONE else View.VISIBLE
        paintProtocol()
        paintTransport()
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        isClickable = true
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)

        val headerRow = screenHeader(context, R.string.source_nas_title) { onBack?.invoke() }
        headerRow.setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_8), inset, 0)
        addView(headerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(context)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_8), inset, inset)
        }

        addLabel(column, R.string.source_nas_protocol)
        val protocolRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = mutableListOf<TextView>()
        listOf(
            res.getString(R.string.source_nas_protocol_webdav) to PROTOCOL_WEBDAV,
            res.getString(R.string.source_nas_protocol_smb) to PROTOCOL_SMB,
        )
            .forEachIndexed { index, (label, value) ->
            val button = optionButton(label, value) {
                if (value == PROTOCOL_SMB) {
                    protocol = PROTOCOL_SMB
                    portInput.setText("445")
                    transportSection?.visibility = View.GONE
                } else {
                    protocol = if (transport == TRANSPORT_HTTP) PROTOCOL_WEBDAV_HTTP else PROTOCOL_WEBDAV
                    portInput.setText(if (transport == TRANSPORT_HTTP) "80" else "443")
                    transportSection?.visibility = View.VISIBLE
                }
                markDirty()
                paintProtocol()
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

        val transportSectionView = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        transportSection = transportSectionView
        addLabel(transportSectionView, R.string.source_nas_transport)
        val transportRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val transportButtonList = mutableListOf<TextView>()
        listOf(
            res.getString(R.string.source_nas_transport_https) to TRANSPORT_HTTPS,
            res.getString(R.string.source_nas_transport_http) to TRANSPORT_HTTP,
        )
            .forEachIndexed { index, (label, value) ->
            val button = optionButton(label, value, R.dimen.pp_text_meta) {
                transport = value
                protocol = if (value == TRANSPORT_HTTP) PROTOCOL_WEBDAV_HTTP else PROTOCOL_WEBDAV
                portInput.setText(if (value == TRANSPORT_HTTP) "80" else "443")
                markDirty()
                paintTransport()
            }
            transportButtonList += button
            transportRow.addView(
                button,
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = res.getDimensionPixelSize(R.dimen.pp_space_8)
                },
            )
        }
        transportButtons = transportButtonList
        transportSectionView.addView(
            transportRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        column.addView(
            transportSectionView,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        paintTransport()

        addLabel(column, R.string.source_nas_host)
        hostInput = addInput(column, number = false)
        addLabel(column, R.string.source_nas_port)
        portInput = addInput(column, number = true)
        portInput.setText("443")
        portError = addError(column)
        pathLabel = addLabel(column, R.string.source_nas_path)
        pathInput = addInput(column, number = false)
        addLabel(column, R.string.source_nas_username)
        usernameInput = addInput(column, number = false)
        addLabel(column, R.string.source_nas_password)
        passwordInput = addInput(column, number = false)
        passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        passwordVisibleToggle = CheckBox(context).apply {
            KeyboardDismiss.keepInputOnTouch(this)
            text = res.getString(R.string.source_nas_show_password)
            contentDescription = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            setOnCheckedChangeListener { _, checked ->
                passwordInput.inputType = if (checked) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
        }
        column.addView(
            passwordVisibleToggle,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        column.addView(
            TextView(context).apply {
                text = res.getString(R.string.source_nas_guest_hint)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
                setTextColor(context.getColor(R.color.pp_text_secondary))
            },
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
            setOnClickListener { currentForm()?.let { pendingSave = false; markDirty(); onTest?.invoke(it) } }
        }
        saveButton = primaryButton(context, R.string.common_next).apply {
            setOnClickListener {
                val form = currentForm() ?: return@setOnClickListener
                if (testPassed) {
                    onSave?.invoke(form)
                } else {
                    pendingSave = true
                    onTest?.invoke(form)
                }
            }
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
        scroll.addView(column)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
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
        )
    }

    fun showTesting() {
        statusLine.visibility = View.VISIBLE
        statusLine.setTextColor(context.getColor(R.color.pp_text_secondary))
        statusLine.text = resources.getString(R.string.source_nas_testing)
    }

    fun showTestOk() {
        testPassed = true
        statusLine.visibility = View.VISIBLE
        statusLine.setTextColor(context.getColor(R.color.pp_status_success))
        statusLine.text = resources.getString(R.string.source_nas_test_ok)
        if (pendingSave) {
            pendingSave = false
            currentForm()?.let { onSave?.invoke(it) }
        }
    }

    fun setSecurityText(text: String) {
        securityLine.visibility = View.VISIBLE
        securityLine.text = text
    }

    fun showTestError(textRes: Int) {
        testPassed = false
        pendingSave = false
        statusLine.visibility = View.VISIBLE
        statusLine.setTextColor(context.getColor(R.color.pp_status_error))
        statusLine.text = resources.getString(textRes)
        securityLine.visibility = View.GONE
    }

    fun markDirty() {
        testPassed = false
        pendingSave = false
    }

    private fun optionButton(label: String, value: String, onSelect: () -> Unit): TextView =
        optionButton(label, value, R.dimen.pp_text_body, onSelect)

    private fun optionButton(label: String, value: String, textRes: Int, onSelect: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            contentDescription = label
            tag = value
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(textRes))
            gravity = Gravity.CENTER
            minimumHeight = resources.getDimensionPixelSize(R.dimen.pp_touch_target)
            isClickable = true
            isFocusable = true
            setOnClickListener { onSelect() }
        }

    private fun addLabel(parent: LinearLayout, textRes: Int): TextView {
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
        return label
    }

    private fun addInput(parent: LinearLayout, number: Boolean): EditText {
        val res = resources
        val input = EditText(context).apply {
            inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            setSingleLine(true)
            KeyboardDismiss.configureDone(this)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    markDirty()
                }
            })
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
        const val PROTOCOL_WEBDAV = "webdav"
        const val PROTOCOL_WEBDAV_HTTP = "webdav_http"
        const val PROTOCOL_SMB = "smb"
        const val TRANSPORT_HTTPS = "https"
        const val TRANSPORT_HTTP = "http"
    }
}
