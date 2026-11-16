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
    var onLocalReferenceClick: ((String) -> Unit)? = null
    var onLocalReferenceRemove: ((String) -> Unit)? = null
    var onJobStatusClick: (() -> Unit)? = null
    var onNasBrowse: ((String) -> Unit)? = null
    var onNasSync: ((String) -> Unit)? = null
    var onNasDisconnect: ((String) -> Unit)? = null
    var onNasReauth: ((String) -> Unit)? = null
    var onNasEdit: ((String) -> Unit)? = null
    var onNasMirrorToggle: ((String, Boolean) -> Unit)? = null

    private val jobStatusRow: LinearLayout
    private val jobStatusState: TextView
    private val nasSourcesColumn: LinearLayout
    private val localReferencesColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var renderedNasModels: List<NasSourceModel>? = null
    private var renderedLocalModels: List<LocalReferenceModel>? = null

    init {
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        val res = resources
        val inset = res.getDimensionPixelSize(
            if (res.configuration.screenWidthDp < 600) R.dimen.pp_content_inset_compact else R.dimen.pp_content_inset,
        )
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
            titleRes = R.string.source_local_add,
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
        column.addView(localReferencesColumn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
        val catalogOnly: Boolean = false,
    )

    data class LocalReferenceModel(val sourceId: String, val displayName: String, val statusText: String)

    fun renderLocalReferences(models: List<LocalReferenceModel>) {
        if (renderedLocalModels == models) return
        renderedLocalModels = models
        localReferencesColumn.removeAllViews()
        for (model in models) {
            localReferencesColumn.addView(panelRow().apply {
                setOnClickListener { onLocalReferenceClick?.invoke(model.sourceId) }
                addView(TextView(context).apply {
                    text = model.displayName
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_body))
                    setTextColor(context.getColor(R.color.pp_text_primary))
                })
                addView(TextView(context).apply {
                    text = model.statusText
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_meta))
                    setTextColor(context.getColor(R.color.pp_text_secondary))
                })
                setOnLongClickListener {
                    showLocalReferenceMenu(this, model)
                    true
                }
                addView(sourceAction(R.string.player_more) { anchor ->
                    showLocalReferenceMenu(anchor, model)
                }.apply {
                    contentDescription = resources.getString(R.string.player_more) + ", " + model.displayName
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.pp_space_12)
            })
        }
    }

    fun renderNasSources(models: List<NasSourceModel>) {
        if (renderedNasModels == models) return
        renderedNasModels = models
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
            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val primaryLabel = when {
                model.isDisconnected || model.needsReauth -> R.string.sources_reconfigure
                else -> R.string.sources_browse_photos
            }
            actions.addView(sourceAction(primaryLabel) {
                when {
                    model.isDisconnected -> onNasEdit?.invoke(model.sourceId)
                    model.needsReauth -> onNasReauth?.invoke(model.sourceId)
                    else -> onNasBrowse?.invoke(model.sourceId)
                }
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(sourceAction(R.string.player_more) { anchor ->
                showNasMenu(anchor, model)
            }.apply {
                contentDescription = res.getString(R.string.player_more) + ", " + model.displayName
            }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = res.getDimensionPixelSize(R.dimen.pp_space_8)
            })
            card.addView(name, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            card.addView(status, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            card.addView(actions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            nasSourcesColumn.addView(
                card,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
                },
            )
        }
    }

    private fun sourceAction(labelRes: Int, action: (View) -> Unit): TextView = TextView(context).apply {
        text = resources.getString(labelRes)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_body))
        setTextColor(context.getColor(R.color.pp_accent_action))
        val target = resources.getDimensionPixelSize(R.dimen.pp_touch_target)
        minimumHeight = target
        minimumWidth = target
        gravity = Gravity.CENTER_VERTICAL
        val padding = resources.getDimensionPixelSize(R.dimen.pp_space_8)
        setPadding(padding, 0, padding, 0)
        isFocusable = true
        setOnClickListener(action)
    }

    private fun showLocalReferenceMenu(anchor: View, model: LocalReferenceModel) {
        val menu = android.widget.PopupMenu(context, anchor)
        menu.menu.add(R.string.sources_remove_configuration).setOnMenuItemClickListener {
            onLocalReferenceRemove?.invoke(model.sourceId)
            true
        }
        menu.show()
    }

    private fun showNasMenu(anchor: View, model: NasSourceModel) {
        val menu = android.widget.PopupMenu(context, anchor)
        if (!model.isDisconnected) {
            menu.menu.add(if (model.catalogOnly) R.string.sources_refresh_index else R.string.sources_sync_now)
                .setOnMenuItemClickListener {
                    onNasSync?.invoke(model.sourceId)
                    true
                }
            menu.menu.add(R.string.common_edit).setOnMenuItemClickListener {
                onNasEdit?.invoke(model.sourceId)
                true
            }
        }
        menu.menu.add(R.string.source_nas_mirror_delete).apply {
            isCheckable = true
            isChecked = model.mirrorDelete
            setOnMenuItemClickListener {
                onNasMirrorToggle?.invoke(model.sourceId, !model.mirrorDelete)
                true
            }
        }
        menu.menu.add(if (model.isDisconnected) R.string.sources_remove_configuration else R.string.sources_disconnect)
            .setOnMenuItemClickListener {
                onNasDisconnect?.invoke(model.sourceId)
                true
            }
        menu.show()
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
