package dev.photopilot.scaffold.ui.sources

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.data.db.ImportJobState
import dev.photopilot.scaffold.data.repo.ImportProgressRepository

class SourcesView(context: Context) : ScrollView(context) {

    var onLocalPhotosClick: (() -> Unit)? = null
    var onLocalFilesClick: (() -> Unit)? = null
    var onNasClick: (() -> Unit)? = null
    var onJobStatusClick: (() -> Unit)? = null
    var onNasSync: ((String) -> Unit)? = null
    var onNasDisconnect: ((String) -> Unit)? = null
    var onNasReauth: ((String) -> Unit)? = null
    var onNasEdit: ((String) -> Unit)? = null
    var onNasMirrorToggle: ((String, Boolean) -> Unit)? = null

    private val jobStatusRow: LinearLayout
    private val jobStatusState: TextView
    private val nasSourcesColumn: LinearLayout

    init {
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, inset, inset, inset)
        }
        val title = TextView(context).apply {
            text = res.getString(R.string.sources_title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_heading))
            setTextColor(context.getColor(R.color.pp_text_primary))
            setAccessibilityHeading(true)
        }
        val add = TextView(context).apply {
            text = res.getString(R.string.sources_add)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        val photosRow = sourceRow(
            titleRes = R.string.source_local_photos_title,
            bodyRes = R.string.source_local_photos_body,
        ) { row -> showLocalSourceMenu(row) }
        val nasRow = sourceRow(
            titleRes = R.string.source_nas_title,
            bodyRes = R.string.source_nas_body,
        ) { onNasClick?.invoke() }
        nasSourcesColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        jobStatusRow = panelRow().apply {
            visibility = View.GONE
            setOnClickListener { onJobStatusClick?.invoke() }
            contentDescription = res.getString(R.string.import_title)
            val rowTitle = TextView(context).apply {
                text = res.getString(R.string.import_title)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                setTextColor(context.getColor(R.color.pp_text_primary))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            jobStatusState = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
                setTextColor(context.getColor(R.color.pp_text_secondary))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(rowTitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(
                jobStatusState,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = res.getDimensionPixelSize(R.dimen.pp_space_4)
                },
            )
        }
        val note = TextView(context).apply {
            text = res.getString(R.string.sources_import_vs_display)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        column.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.addView(
            add,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_4)
            },
        )
        column.addView(
            photosRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_24)
            },
        )
        column.addView(
            nasRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
        column.addView(
            nasSourcesColumn,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        column.addView(
            jobStatusRow,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_24)
            },
        )
        column.addView(
            note,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_24)
            },
        )
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun renderJobStatus(snapshot: ImportProgressRepository.Snapshot?) {
        if (snapshot == null || snapshot.state !in ACTIVE_JOB_STATES) {
            jobStatusRow.visibility = View.GONE
            return
        }
        jobStatusRow.visibility = View.VISIBLE
        jobStatusState.text = resources.getString(R.string.sources_status_active)
    }

    data class NasSourceModel(
        val sourceId: String,
        val displayName: String,
        val statusText: String,
        val needsReauth: Boolean = false,
        val isDisconnected: Boolean = false,
        val mirrorDelete: Boolean = false,
    )

    fun renderNasSources(models: List<NasSourceModel>) {
        nasSourcesColumn.removeAllViews()
        val res = resources
        models.forEach { model ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = res.getDimensionPixelSize(R.dimen.pp_space_12)
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(context.getColor(R.color.pp_surface_panel))
                    cornerRadius = res.getDimension(R.dimen.pp_radius_group)
                }
                if (model.needsReauth) {
                    isClickable = true
                    isFocusable = true
                    contentDescription = model.displayName
                    setOnClickListener { onNasReauth?.invoke(model.sourceId) }
                }
            }
            val name = TextView(context).apply {
                text = model.displayName
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                setTextColor(context.getColor(R.color.pp_text_primary))
            }
            val status = TextView(context).apply {
                text = model.statusText
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
                setTextColor(context.getColor(R.color.pp_text_secondary))
            }
            val actions = object : LinearLayout(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
                    val slot = if (childCount > 0) available / childCount else available
                    val fits = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ||
                        (0 until childCount).all { index ->
                            val label = getChildAt(index) as TextView
                            kotlin.math.ceil(label.paint.measureText(label.text.toString()).toDouble()).toInt() +
                                label.compoundPaddingLeft + label.compoundPaddingRight <= slot
                        }
                    orientation = if (fits) HORIZONTAL else VERTICAL
                    for (index in 0 until childCount) {
                        (getChildAt(index).layoutParams as LayoutParams).apply {
                            width = if (fits) 0 else LayoutParams.MATCH_PARENT
                            height = LayoutParams.WRAP_CONTENT
                            weight = if (fits) 1f else 0f
                        }
                    }
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                }
            }.apply {
                isBaselineAligned = false
            }
            val sync = TextView(context).apply {
                text = res.getString(R.string.sources_sync_now)
                contentDescription = text
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                setTextColor(context.getColor(R.color.pp_accent_action))
                minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { onNasSync?.invoke(model.sourceId) }
            }
            val disconnect = TextView(context).apply {
                text = res.getString(R.string.sources_disconnect)
                contentDescription = text
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                setTextColor(context.getColor(R.color.pp_status_error))
                minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { onNasDisconnect?.invoke(model.sourceId) }
            }
            actions.addView(sync, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            if (!model.isDisconnected) {
                val edit = TextView(context).apply {
                    text = res.getString(
                        if (model.needsReauth) R.string.sources_reconfigure else R.string.common_edit,
                    )
                    contentDescription = text
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                    setTextColor(context.getColor(R.color.pp_accent_action))
                    minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onNasEdit?.invoke(model.sourceId) }
                }
                actions.addView(edit, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
            actions.addView(disconnect, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            val mirror = android.widget.Switch(context).apply {
                text = res.getString(R.string.source_nas_mirror_delete)
                isChecked = model.mirrorDelete
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                setTextColor(context.getColor(R.color.pp_text_primary))
                minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
                setOnCheckedChangeListener { _, checked ->
                    onNasMirrorToggle?.invoke(model.sourceId, checked)
                }
            }
            card.addView(name, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            card.addView(status, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            card.addView(actions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            card.addView(mirror, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            nasSourcesColumn.addView(
                card,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
                },
            )
        }
    }

    private fun showLocalSourceMenu(anchor: View) {
        val menu = android.widget.PopupMenu(context, anchor)
        menu.menu.add(R.string.source_local_photos_title).setOnMenuItemClickListener {
            onLocalPhotosClick?.invoke()
            true
        }
        menu.menu.add(R.string.source_local_files_title).setOnMenuItemClickListener {
            onLocalFilesClick?.invoke()
            true
        }
        menu.show()
    }

    private fun sourceRow(titleRes: Int, bodyRes: Int, onClick: (View) -> Unit): LinearLayout =
        panelRow().apply {
            val res = resources
            contentDescription = res.getString(titleRes)
            setOnClickListener { onClick(this) }
            val rowTitle = TextView(context).apply {
                text = res.getString(titleRes)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                setTextColor(context.getColor(R.color.pp_text_primary))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val rowBody = TextView(context).apply {
                text = res.getString(bodyRes)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
                setTextColor(context.getColor(R.color.pp_text_secondary))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(rowTitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(
                rowBody,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = res.getDimensionPixelSize(R.dimen.pp_space_4)
                },
            )
        }

    private fun panelRow(): LinearLayout =
        LinearLayout(context).apply {
            val res = resources
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            val padH = res.getDimensionPixelSize(R.dimen.pp_space_16)
            val padV = res.getDimensionPixelSize(R.dimen.pp_space_12)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.pp_surface_panel))
                cornerRadius = res.getDimension(R.dimen.pp_radius_group)
            }
            isClickable = true
            isFocusable = true
        }

    private companion object {
        val ACTIVE_JOB_STATES = setOf(
            ImportJobState.RUNNING,
            ImportJobState.PAUSED,
            ImportJobState.PAUSED_AUTO,
            ImportJobState.PARTIAL_FAILED,
        )
    }
}
