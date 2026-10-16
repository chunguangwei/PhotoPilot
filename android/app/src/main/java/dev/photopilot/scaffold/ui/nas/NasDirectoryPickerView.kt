package dev.photopilot.scaffold.ui.nas

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.ui.widget.primaryButton
import dev.photopilot.scaffold.ui.widget.screenHeader

class NasDirectoryPickerView(context: Context) : ScrollView(context) {

    var onBack: (() -> Unit)? = null
    var onLoadDirectory: ((String, (List<String>) -> Unit) -> Unit)? = null
    var onConfirm: ((Set<String>, Boolean) -> Unit)? = null
    var rootPath: String = ""

    private val selected = mutableSetOf<String>()
    private var recursive = false
    private var currentDir = ""
    private val pathLine: TextView
    private val parentRow: TextView
    private val listColumn: LinearLayout
    private val countLine: TextView
    private val confirmButton: View

    init {
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        isClickable = true
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_8), inset, inset)
        }
        val headerRow = screenHeader(context, R.string.source_nas_choose_folders) { onBack?.invoke() }
        column.addView(headerRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        pathLine = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        column.addView(
            pathLine,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        parentRow = TextView(context).apply {
            text = res.getString(R.string.common_back)
            contentDescription = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_accent_action))
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { ascend() }
        }
        column.addView(parentRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        listColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            listColumn,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.pp_surface_panel))
            val pad = res.getDimensionPixelSize(R.dimen.pp_space_16)
            setPadding(pad, res.getDimensionPixelSize(R.dimen.pp_space_12), pad, res.getDimensionPixelSize(R.dimen.pp_space_16))
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.pp_surface_panel))
                cornerRadius = res.getDimension(R.dimen.pp_radius_group)
            }
        }
        countLine = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
        }
        confirmButton = primaryButton(context, R.string.common_save).apply {
            setOnClickListener { if (selected.isNotEmpty()) onConfirm?.invoke(selected.toSet(), recursive) }
        }
        bottomBar.addView(countLine, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val recursiveToggle = android.widget.CheckBox(context).apply {
            text = res.getString(R.string.rule_recursive_toggle)
            contentDescription = text
            isChecked = false
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            setOnCheckedChangeListener { _, checked -> recursive = checked }
        }
        bottomBar.addView(
            recursiveToggle,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        bottomBar.addView(
            confirmButton,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        column.addView(
            bottomBar,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        refreshCount()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (listColumn.childCount == 0) {
            load(rootPath)
        }
    }

    private fun load(dir: String) {
        currentDir = dir
        pathLine.text = if (dir.isEmpty()) "/" else "/$dir"
        val atRoot = dir == rootPath
        parentRow.isEnabled = !atRoot
        parentRow.alpha = if (atRoot) DISABLED_ALPHA else 1f
        listColumn.removeAllViews()
        onLoadDirectory?.invoke(dir) { children ->
            renderChildren(children)
        }
    }

    private fun renderChildren(children: List<String>) {
        val res = resources
        listColumn.removeAllViews()
        children.forEach { child ->
            val full = if (currentDir.isEmpty()) child else "$currentDir/$child"
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
                isClickable = true
                isFocusable = true
                setOnClickListener { load(full) }
            }
            val toggle = CheckBox(context).apply {
                isChecked = selected.contains(full)
                minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
                contentDescription = child
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(full) else selected.remove(full)
                    refreshCount()
                }
            }
            val name = TextView(context).apply {
                text = child
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                setTextColor(context.getColor(R.color.pp_text_primary))
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            }
            val chevron = TextView(context).apply {
                text = "›"
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                setTextColor(context.getColor(R.color.pp_text_secondary))
                gravity = Gravity.CENTER
                minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
                minimumWidth = res.getDimensionPixelSize(R.dimen.pp_touch_target)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(toggle, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            row.addView(name, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(chevron, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            listColumn.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun ascend() {
        if (currentDir == rootPath) return
        load(currentDir.substringBeforeLast('/', ""))
    }

    private fun refreshCount() {
        val res = resources
        val count = selected.size
        countLine.text = res.getQuantityString(R.plurals.sources_folders_count, count, count)
        confirmButton.isEnabled = count > 0
        confirmButton.alpha = if (count > 0) 1f else DISABLED_ALPHA
    }

    private companion object {
        const val DISABLED_ALPHA = 0.5f
    }
}
