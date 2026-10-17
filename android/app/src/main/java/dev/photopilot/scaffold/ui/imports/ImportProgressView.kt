package dev.photopilot.scaffold.ui.imports

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.data.db.ImportJobState
import dev.photopilot.scaffold.data.logic.ByteSizeFormat
import dev.photopilot.scaffold.data.logic.ImportCounts
import dev.photopilot.scaffold.data.repo.ImportProgressRepository
import dev.photopilot.scaffold.ui.widget.primaryButton
import dev.photopilot.scaffold.ui.widget.secondaryButton

class ImportProgressView(context: Context) : ScrollView(context) {

    var onPauseClick: (() -> Unit)? = null
    var onResumeClick: (() -> Unit)? = null
    var onCancelClick: (() -> Unit)? = null
    var onRetryFailedClick: (() -> Unit)? = null
    var onCloseClick: (() -> Unit)? = null
    var onChooseContentClick: (() -> Unit)? = null
    var showSkippedNotice: Boolean = false

    private val statusLine: TextView
    private val progressBar: ProgressBar
    private val summaryLine: TextView
    private val sizeLine: TextView
    private val spaceNotice: TextView
    private val skippedNotice: TextView
    private val messageLine: TextView
    private val buttonColumn: LinearLayout
    private var lastKey: String? = null

    init {
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        isFillViewport = true
        isClickable = true
        keepScreenOn = true
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, inset, inset, inset)
        }
        val title = TextView(context).apply {
            text = res.getString(R.string.import_title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_heading))
            setTextColor(context.getColor(R.color.pp_text_primary))
            setAccessibilityHeading(true)
        }
        statusLine = textLine(R.dimen.pp_text_body, R.color.pp_text_secondary)
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
        summaryLine = textLine(R.dimen.pp_text_body, R.color.pp_text_primary)
        sizeLine = textLine(R.dimen.pp_text_meta, R.color.pp_text_secondary)
        spaceNotice = textLine(R.dimen.pp_text_body, R.color.pp_status_warning)
        skippedNotice = textLine(R.dimen.pp_text_body, R.color.pp_status_warning)
        messageLine = textLine(R.dimen.pp_text_body, R.color.pp_text_primary)
        messageLine.gravity = Gravity.CENTER
        buttonColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.addView(
            progressBar,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_24)
            },
        )
        column.addView(
            statusLine,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        column.addView(
            summaryLine,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        column.addView(
            sizeLine,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_4)
            },
        )
        column.addView(
            spaceNotice,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
        column.addView(
            skippedNotice,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
        column.addView(
            messageLine,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_24)
            },
        )
        column.addView(
            buttonColumn,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(snapshot: ImportProgressRepository.Snapshot?) {
        if (snapshot == null) return
        val key = listOf(
            snapshot.state,
            snapshot.counts,
            snapshot.totalKnown,
            snapshot.enumerationComplete,
            snapshot.bytesTotal,
            snapshot.hasLowSpaceFailure,
            showSkippedNotice,
        ).joinToString("|")
        if (key == lastKey) return
        lastKey = key

        val res = resources
        val imported = ImportCounts.imported(snapshot.counts)
        val failed = ImportCounts.failed(snapshot.counts)
        val finished = ImportCounts.terminal(snapshot.counts)
        val total = snapshot.totalKnown

        if (!snapshot.enumerationComplete) {
            statusLine.visibility = View.VISIBLE
            statusLine.text = res.getString(R.string.import_enumerating)
        } else if (total == null) {
            statusLine.visibility = View.VISIBLE
            statusLine.text = res.getString(R.string.import_preparing_unknown_total)
        } else {
            statusLine.visibility = View.GONE
        }

        if (total == null || !snapshot.enumerationComplete) {
            progressBar.isIndeterminate = true
        } else {
            progressBar.isIndeterminate = false
            progressBar.max = total.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            progressBar.progress = finished.coerceAtMost(progressBar.max)
        }

        summaryLine.text = res.getString(
            R.string.import_summary,
            imported,
            (total ?: finished.toLong()).coerceAtLeast(finished.toLong()).toInt(),
            imported,
            failed,
        )

        val bytes = snapshot.bytesTotal
        if (bytes != null && bytes > 0L) {
            sizeLine.visibility = View.VISIBLE
            val sizeText = when (ByteSizeFormat.unitOf(bytes)) {
                ByteSizeFormat.Unit.KB ->
                    res.getString(R.string.format_file_size_kb, ByteSizeFormat.valueOf(bytes).toInt())
                ByteSizeFormat.Unit.MB ->
                    res.getString(R.string.format_file_size_mb, ByteSizeFormat.valueOf(bytes).toInt())
                ByteSizeFormat.Unit.GB ->
                    res.getString(R.string.format_file_size_gb, ByteSizeFormat.valueOf(bytes).toInt())
            }
            sizeLine.text = res.getString(R.string.import_estimated_size, sizeText)
        } else if (snapshot.state == ImportJobState.RUNNING) {
            sizeLine.visibility = View.VISIBLE
            sizeLine.text = res.getString(R.string.import_size_calculating)
        } else {
            sizeLine.visibility = View.GONE
        }

        spaceNotice.visibility = if (snapshot.hasLowSpaceFailure) View.VISIBLE else View.GONE
        spaceNotice.text = res.getString(R.string.import_waiting_space)
        skippedNotice.visibility = if (showSkippedNotice) View.VISIBLE else View.GONE
        skippedNotice.text = res.getString(R.string.import_video_skipped)

        renderState(snapshot)
    }

    private fun renderState(snapshot: ImportProgressRepository.Snapshot) {
        val res = resources
        val space12 = res.getDimensionPixelSize(R.dimen.pp_space_12)
        buttonColumn.removeAllViews()
        when (snapshot.state) {
            ImportJobState.RUNNING -> {
                messageLine.visibility = View.GONE
                addButton(secondaryButton(context, R.string.import_pause), 0) { onPauseClick?.invoke() }
                addButton(secondaryButton(context, R.string.import_cancel_job), space12) {
                    onCancelClick?.invoke()
                }
            }
            ImportJobState.PAUSED,
            ImportJobState.PAUSED_AUTO,
            -> {
                messageLine.visibility = View.GONE
                addButton(primaryButton(context, R.string.import_resume), 0) { onResumeClick?.invoke() }
                addButton(secondaryButton(context, R.string.import_cancel_job), space12) {
                    onCancelClick?.invoke()
                }
            }
            ImportJobState.PARTIAL_FAILED -> {
                showMessage(R.string.import_partial_failed)
                addButton(primaryButton(context, R.string.import_retry_failed_only), 0) {
                    onRetryFailedClick?.invoke()
                }
                addButton(secondaryButton(context, R.string.import_cancel_job), space12) {
                    onCancelClick?.invoke()
                }
            }
            ImportJobState.FAILED -> {
                showMessage(R.string.import_partial_failed)
                addButton(primaryButton(context, R.string.common_retry), 0) { onRetryFailedClick?.invoke() }
                addButton(secondaryButton(context, R.string.common_done), space12) { onCloseClick?.invoke() }
            }
            ImportJobState.COMPLETED -> {
                val total = snapshot.totalKnown
                if (ImportCounts.imported(snapshot.counts) == 0 && total != null && total > 0L) {
                    messageLine.visibility = View.VISIBLE
                    messageLine.text = res.getString(R.string.import_no_new_items, total.toInt())
                } else {
                    showMessage(R.string.import_done)
                }
                addButton(primaryButton(context, R.string.frames_choose_content), 0) {
                    onChooseContentClick?.invoke()
                }
                addButton(secondaryButton(context, R.string.common_done), space12) { onCloseClick?.invoke() }
            }
            ImportJobState.CANCELED -> {
                messageLine.visibility = View.GONE
                addButton(primaryButton(context, R.string.common_done), 0) { onCloseClick?.invoke() }
            }
        }
    }

    private fun showMessage(textRes: Int) {
        messageLine.visibility = View.VISIBLE
        messageLine.text = resources.getString(textRes)
    }

    private fun addButton(button: View, topMargin: Int, action: () -> Unit) {
        button.setOnClickListener { action() }
        buttonColumn.addView(
            button,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                this.topMargin = topMargin
            },
        )
    }

    private fun textLine(sizeRes: Int, colorRes: Int): TextView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(sizeRes))
            setTextColor(context.getColor(colorRes))
        }
}
