package dev.photopilot.scaffold.ui.settings

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.app.TimePickerDialog
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.widget.primaryButton
import dev.photopilot.scaffold.ui.widget.secondaryButton
import java.util.Calendar
import java.util.Locale

class HolidaySettingsView(context: Context) : LinearLayout(context) {

    data class RuleRow(
        val id: String,
        val frameId: String,
        val name: String,
        val startDate: String,
        val endDate: String,
        val startTime: Int?,
        val endTime: Int?,
        val position: Long,
        val enabled: Boolean,
    )

    data class FrameOption(val id: String, val name: String)

    data class Draft(
        val id: String?,
        val name: String,
        val startDate: String?,
        val endDate: String?,
        val startTime: Int?,
        val endTime: Int?,
        val frameId: String?,
        val enabled: Boolean,
    )

    var onBack: (() -> Unit)? = null
    var onRequestAdd: (() -> Unit)? = null
    var onRequestEdit: ((RuleRow) -> Unit)? = null
    var onToggleEnabled: ((RuleRow, Boolean) -> Unit)? = null
    var onDeleteRule: ((RuleRow) -> Unit)? = null
    var onSaveDraft: ((Draft, Boolean) -> Unit)? = null

    private val listColumn: LinearLayout
    private val emptyLine: TextView
    private val formScroll: ScrollView
    private val nameInput: EditText
    private val startButton: TextView
    private val endButton: TextView
    private val startTimeButton: TextView
    private val endTimeButton: TextView
    private val clearTimeButton: TextView
    private val frameButton: TextView
    private val enabledSwitch: Switch

    private var draft: Draft = blankDraft()
    private var editorFrames: List<FrameOption> = emptyList()
    private var bindingForm = false

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
            setOnClickListener {
                if (formScroll.visibility == VISIBLE) closeEditor() else onBack?.invoke()
            }
        }
        val title = TextView(context).apply {
            text = res.getString(R.string.holiday_title)
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

        val listScroll = ScrollView(context)
        listColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_8), inset, inset)
        }
        emptyLine = TextView(context).apply {
            text = res.getString(R.string.holiday_empty)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        listColumn.addView(
            emptyLine,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        listScroll.addView(
            listColumn,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(listScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        formScroll = ScrollView(context)
        formScroll.visibility = GONE
        val formColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_8), inset, inset)
        }
        nameInput = EditText(context).apply {
            hint = res.getString(R.string.holiday_name)
            setSingleLine(true)
            dev.photopilot.scaffold.ui.widget.KeyboardDismiss.configureDone(this)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        formColumn.addView(
            nameInput,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addLabel(formColumn, R.string.holiday_date_range)
        startButton = dateButton(R.string.holiday_date_start) { pickDate(draft.startDate) { day ->
            draft = draft.copy(startDate = day)
            bindDates()
        } }
        endButton = dateButton(R.string.holiday_date_end) { pickDate(draft.endDate) { day ->
            draft = draft.copy(endDate = day)
            bindDates()
        } }
        formColumn.addView(
            horizontalPair(startButton, endButton),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_4)
            },
        )
        addLabel(formColumn, R.string.holiday_time_range)
        addHelp(formColumn, R.string.holiday_time_help)
        startTimeButton = dateButton(R.string.holiday_date_start) { pickTime(draft.startTime) { minute ->
            draft = draft.copy(startTime = minute)
            bindTimes()
        } }
        endTimeButton = dateButton(R.string.holiday_date_end) { pickTime(draft.endTime) { minute ->
            draft = draft.copy(endTime = minute)
            bindTimes()
        } }
        clearTimeButton = secondaryButton(context, R.string.common_none).apply {
            setOnClickListener {
                draft = draft.copy(startTime = null, endTime = null)
                bindTimes()
            }
        }
        formColumn.addView(
            horizontalPair(startTimeButton, endTimeButton),
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_4)
            },
        )
        formColumn.addView(
            clearTimeButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_4)
            },
        )
        addLabel(formColumn, R.string.holiday_frame)
        frameButton = secondaryButton(context, R.string.holiday_frame_placeholder).apply {
            setOnClickListener { pickFrame() }
        }
        formColumn.addView(
            frameButton,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_4)
            },
        )
        enabledSwitch = Switch(context).apply {
            text = res.getString(R.string.holiday_enabled)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            setOnCheckedChangeListener { _, isChecked ->
                if (!bindingForm) draft = draft.copy(enabled = isChecked)
            }
        }
        formColumn.addView(
            enabledSwitch,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        val save = primaryButton(context, R.string.common_save).apply {
            setOnClickListener { attemptSave() }
        }
        formColumn.addView(
            save,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        formScroll.addView(
            formColumn,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(formScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun renderRules(rules: List<RuleRow>) {
        val res = resources
        while (listColumn.childCount > 1) {
            listColumn.removeViewAt(listColumn.childCount - 1)
        }
        emptyLine.visibility = if (rules.isEmpty()) VISIBLE else GONE
        rules.forEach { rule ->
            val panel = LinearLayout(context).apply {
                orientation = VERTICAL
                val pad = res.getDimensionPixelSize(R.dimen.pp_space_16)
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(context.getColor(R.color.pp_surface_panel))
                    cornerRadius = res.getDimension(R.dimen.pp_radius_group)
                }
            }
            val topRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            }
            val nameView = TextView(context).apply {
                text = rule.name
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                setTextColor(context.getColor(R.color.pp_text_primary))
                isClickable = true
                isFocusable = true
                setOnClickListener { onRequestEdit?.invoke(rule) }
            }
            val toggle = Switch(context).apply {
                isChecked = rule.enabled
                contentDescription = rule.name
                setOnCheckedChangeListener { _, isChecked -> onToggleEnabled?.invoke(rule, isChecked) }
            }
            topRow.addView(nameView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            topRow.addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            panel.addView(topRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            val metaView = TextView(context).apply {
                text = rangeText(rule)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
                setTextColor(context.getColor(R.color.pp_text_secondary))
            }
            panel.addView(metaView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            val deleteView = TextView(context).apply {
                text = res.getString(R.string.common_delete)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                setTextColor(context.getColor(R.color.pp_status_error))
                minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener { onDeleteRule?.invoke(rule) }
            }
            panel.addView(deleteView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            listColumn.addView(
                panel,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
                },
            )
        }
        val addRow = TextView(context).apply {
            text = res.getString(R.string.holiday_add)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_accent_action))
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { onRequestAdd?.invoke() }
        }
        listColumn.addView(
            addRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
    }

    fun openEditor(rule: RuleRow?, frames: List<FrameOption>) {
        editorFrames = frames
        draft = rule?.let {
            Draft(
                id = it.id,
                name = it.name,
                startDate = it.startDate,
                endDate = it.endDate,
                startTime = it.startTime,
                endTime = it.endTime,
                frameId = it.frameId,
                enabled = it.enabled,
            )
        } ?: blankDraft()
        bindingForm = true
        nameInput.setText(draft.name)
        enabledSwitch.isChecked = draft.enabled
        bindingForm = false
        bindDates()
        bindTimes()
        bindFrame()
        listColumn.visibility = GONE
        listScrollHidden(true)
        formScroll.visibility = VISIBLE
    }

    fun closeEditor() {
        formScroll.visibility = GONE
        listScrollHidden(false)
        listColumn.visibility = VISIBLE
    }

    fun showConflict(ruleName: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.holiday_conflict_title)
            .setMessage(resources.getString(R.string.holiday_conflict_body, ruleName))
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.holiday_conflict_confirm) { _, _ ->
                onSaveDraft?.invoke(currentDraft(), true)
            }
            .show()
    }

    private fun listScrollHidden(hidden: Boolean) {
        val scroll = listColumn.parent as? ScrollView ?: return
        scroll.visibility = if (hidden) GONE else VISIBLE
    }
    private fun blankDraft(): Draft {
        val today = currentDate()
        return Draft(
            id = null,
            name = "",
            startDate = today,
            endDate = today,
            startTime = null,
            endTime = null,
            frameId = null,
            enabled = true,
        )
    }

    private fun attemptSave() {
        val candidate = currentDraft()
        val timeWindowValid =
            (candidate.startTime == null && candidate.endTime == null) ||
                (candidate.startTime != null && candidate.endTime != null && candidate.startTime != candidate.endTime)
        val valid = candidate.name.isNotBlank() &&
            candidate.startDate != null &&
            candidate.endDate != null &&
            candidate.frameId != null &&
            candidate.endDate!! >= candidate.startDate!! &&
            timeWindowValid
        if (!valid) {
            android.widget.Toast.makeText(context, R.string.holiday_invalid, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        onSaveDraft?.invoke(candidate, false)
    }

    private fun currentDraft(): Draft = draft.copy(name = nameInput.text.toString().trim())

    private fun rangeText(rule: RuleRow): String {
        val dates = "${rule.startDate} → ${rule.endDate}"
        val time = if (rule.startTime != null && rule.endTime != null) {
            " · " + timeValue(rule.startTime) + "–" + timeValue(rule.endTime)
        } else {
            ""
        }
        return dates + time
    }

    private fun bindDates() {
        startButton.text = labelled(resources.getString(R.string.holiday_date_start), draft.startDate)
        endButton.text = labelled(resources.getString(R.string.holiday_date_end), draft.endDate)
    }

    private fun bindTimes() {
        startTimeButton.text = labelled(resources.getString(R.string.holiday_date_start), draft.startTime?.let(::timeValue))
        endTimeButton.text = labelled(resources.getString(R.string.holiday_date_end), draft.endTime?.let(::timeValue))
    }

    private fun bindFrame() {
        val name = editorFrames.firstOrNull { it.id == draft.frameId }?.name
        frameButton.text = name ?: resources.getString(R.string.holiday_frame_placeholder)
    }

    private fun labelled(label: String, value: String?): String = "$label: ${value ?: "—"}"

    private fun timeValue(minute: Int): String =
        String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60)

    private fun currentDate(): String {
        val calendar = Calendar.getInstance()
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun pickDate(current: String?, onPicked: (String) -> Unit) {
        val calendar = Calendar.getInstance()
        current?.let { value ->
            val parts = value.split("-")
            if (parts.size == 3) {
                calendar.set(
                    parts[0].toIntOrNull() ?: calendar.get(Calendar.YEAR),
                    (parts[1].toIntOrNull() ?: calendar.get(Calendar.MONTH) + 1) - 1,
                    parts[2].toIntOrNull() ?: calendar.get(Calendar.DAY_OF_MONTH),
                )
            }
        }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                onPicked(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun pickTime(current: Int?, onPicked: (Int) -> Unit) {
        val base = current ?: 0
        TimePickerDialog(
            context,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            base / 60,
            base % 60,
            android.text.format.DateFormat.is24HourFormat(context),
        ).show()
    }

    private fun pickFrame() {
        if (editorFrames.isEmpty()) {
            android.widget.Toast.makeText(context, R.string.holiday_invalid, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.holiday_frame)
            .setItems(editorFrames.map { it.name }.toTypedArray()) { _, which ->
                draft = draft.copy(frameId = editorFrames[which].id)
                bindFrame()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun dateButton(labelRes: Int, onClick: () -> Unit): TextView =
        secondaryButton(context, labelRes).apply {
            setOnClickListener { onClick() }
        }

    private fun horizontalPair(left: TextView, right: TextView): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(left, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            right,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.pp_space_4)
            },
        )
        return row
    }

    private fun addLabel(parent: LinearLayout, textRes: Int) {
        val res = resources
        val view = TextView(context).apply {
            text = res.getString(textRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
        }
        parent.addView(
            view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
    }

    private fun addHelp(parent: LinearLayout, textRes: Int) {
        val res = resources
        val view = TextView(context).apply {
            text = res.getString(textRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        parent.addView(
            view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_4)
            },
        )
    }
}
