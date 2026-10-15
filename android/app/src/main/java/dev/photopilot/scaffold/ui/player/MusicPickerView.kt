package dev.photopilot.scaffold.ui.player

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.data.logic.FramePlaybackSettings
import dev.photopilot.scaffold.data.logic.MusicCatalog
import dev.photopilot.scaffold.ui.widget.secondaryButton

class MusicPickerView(context: Context) : ScrollView(context) {

    var onBack: (() -> Unit)? = null
    var onSelect: ((MusicCatalog.Selection) -> Unit)? = null
    var onChooseLocal: (() -> Unit)? = null

    private val preview = MusicPlayer(context)
    private var selection = MusicCatalog.Selection(
        enabled = false,
        source = FramePlaybackSettings.MUSIC_SOURCE_BUILTIN,
        trackId = null,
        localPath = null,
        localTitle = null,
    )
    private var previewingKey: String? = null
    private val rowsColumn: LinearLayout

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
            text = res.getString(R.string.playback_music_picker_title)
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
        rowsColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(
            rowsColumn,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rebuildRows()
    }

    fun bind(current: MusicCatalog.Selection) {
        selection = current
        rebuildRows()
    }

    fun setLocalTrack(localPath: String, localTitle: String?) {
        selection = MusicCatalog.Selection(
            enabled = true,
            source = FramePlaybackSettings.MUSIC_SOURCE_LOCAL,
            trackId = null,
            localPath = localPath,
            localTitle = localTitle,
        )
        startPreview(localRowKey(localPath))
        onSelect?.invoke(selection)
        rebuildRows()
    }

    override fun onDetachedFromWindow() {
        previewingKey = null
        preview.release()
        super.onDetachedFromWindow()
    }

    private fun offRowKey(): String = "off"

    private fun builtinRowKey(trackId: String): String = "builtin:$trackId"

    private fun localRowKey(localPath: String): String = "local:$localPath"

    private fun selectedKey(): String = when {
        !selection.enabled -> offRowKey()
        selection.source == FramePlaybackSettings.MUSIC_SOURCE_LOCAL ->
            selection.localPath?.let { localRowKey(it) } ?: offRowKey()
        else -> selection.trackId?.let { builtinRowKey(it) } ?: offRowKey()
    }

    private fun onOffTap() {
        selection = selection.copy(enabled = false)
        endPreview()
        onSelect?.invoke(selection)
        rebuildRows()
    }

    private fun onTrackTap(track: MusicCatalog.Track) {
        val key = builtinRowKey(track.id)
        val alreadySelected = selection.enabled &&
            selection.source != FramePlaybackSettings.MUSIC_SOURCE_LOCAL &&
            selection.trackId == track.id
        if (alreadySelected && previewingKey == key) {
            endPreview()
            rebuildRows()
            return
        }
        selection = selection.copy(
            enabled = true,
            source = FramePlaybackSettings.MUSIC_SOURCE_BUILTIN,
            trackId = track.id,
        )
        startPreview(key)
        onSelect?.invoke(selection)
        rebuildRows()
    }

    private fun onLocalTap(localPath: String, localTitle: String?) {
        val key = localRowKey(localPath)
        val alreadySelected = selection.enabled &&
            selection.source == FramePlaybackSettings.MUSIC_SOURCE_LOCAL &&
            selection.localPath == localPath
        if (alreadySelected && previewingKey == key) {
            endPreview()
            rebuildRows()
            return
        }
        selection = selection.copy(
            enabled = true,
            source = FramePlaybackSettings.MUSIC_SOURCE_LOCAL,
            trackId = null,
            localPath = localPath,
            localTitle = localTitle,
        )
        startPreview(key)
        onSelect?.invoke(selection)
        rebuildRows()
    }

    private fun startPreview(key: String) {
        previewingKey = key
        preview.start(selection)
    }

    fun stopPreview() {
        previewingKey = null
        preview.pause()
    }

    private fun endPreview() {
        previewingKey = null
        preview.stop()
    }

    private fun rebuildRows() {
        val res = resources
        rowsColumn.removeAllViews()
        val selected = selectedKey()
        rowsColumn.addView(
            musicRow(res.getString(R.string.playback_music_off), selected == offRowKey()) { onOffTap() },
            rowParams(0),
        )
        rowsColumn.addView(sectionLabel(R.string.playback_music_builtin_section))
        MusicCatalog.TRACKS.forEach { track ->
            rowsColumn.addView(
                musicRow(res.getString(track.nameRes), selected == builtinRowKey(track.id)) { onTrackTap(track) },
                rowParams(R.dimen.pp_space_8),
            )
        }
        rowsColumn.addView(sectionLabel(R.string.playback_music_local_section))
        val localPath = selection.localPath
        if (localPath != null) {
            val localTitle = selection.localTitle ?: localPath
            rowsColumn.addView(
                musicRow(localTitle, selected == localRowKey(localPath)) {
                    onLocalTap(localPath, selection.localTitle)
                },
                rowParams(R.dimen.pp_space_8),
            )
        }
        rowsColumn.addView(
            musicRow(res.getString(R.string.playback_music_choose_local), false) {
                onChooseLocal?.invoke()
            },
            rowParams(R.dimen.pp_space_8),
        )
    }

    private fun rowParams(marginRes: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            if (marginRes != 0) topMargin = resources.getDimensionPixelSize(marginRes)
        }

    private fun sectionLabel(textRes: Int): TextView {
        val res = resources
        val view = TextView(context).apply {
            text = res.getString(textRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
        }
        view.layoutParams = LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
        }
        return view
    }

    private fun musicRow(title: String, selected: Boolean, onTap: () -> Unit): LinearLayout {
        val res = resources
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
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
            contentDescription = title
            setOnClickListener { onTap() }
        }
        val name = TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val check = TextView(context).apply {
            text = "✓"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_accent_action))
            visibility = if (selected) View.VISIBLE else View.GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(name, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(check, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        return row
    }
}
