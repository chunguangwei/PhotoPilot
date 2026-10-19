package dev.photopilot.scaffold.ui.player

import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.FrameEntity
import dev.photopilot.scaffold.data.db.FrameOrderMode
import dev.photopilot.scaffold.data.logic.FramePlaybackSettings
import dev.photopilot.scaffold.data.logic.MusicCatalog
import dev.photopilot.scaffold.data.logic.PlaybackSettingsEdit
import dev.photopilot.scaffold.data.logic.PlaybackTiming
import dev.photopilot.scaffold.data.render.BitmapRenderer
import dev.photopilot.scaffold.ui.widget.dropdownField
import dev.photopilot.scaffold.ui.widget.primaryButton
import dev.photopilot.scaffold.ui.widget.screenHeader
import dev.photopilot.scaffold.ui.widget.secondaryButton
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class PlaybackSettingsView(context: Context) : LinearLayout(context) {

    var onBack: ((Boolean) -> Unit)? = null
    var onSave: ((PlaybackSettingsEdit.Draft, FrameOrderMode) -> Unit)? = null

    private var initialDraft: PlaybackSettingsEdit.Draft? = null
    private var initialOrder: FrameOrderMode = FrameOrderMode.CAPTURED_ASC
    private var reduceMotion = false

    private var holdPresetIndex = 1
    private var holdCustomMinutes = false
    private var transitionIndex = 1
    private var transitionTenths = 8
    private var fitIndex = 0
    private var clockEnabled = true
    private var dateEnabled = true
    private var positionIndex = 2
    private var hourFormatIndex = 0
    private var orderIndex = 0
    private var brightnessFollow = true
    private var brightnessPercent = 50
    private var previewOriginalBrightness: Float? = null
    private var keepAwakePaused = true
    private var restEnabled = false
    private lateinit var restRow: LinearLayout
    private var restStartMinute: Int? = null
    private var restEndMinute: Int? = null
    private var restDisplayIndex = 1
    private var qualityIndex = 0
    private var solidIndex = 0
    private var musicEnabled = false
    private var musicSource = FramePlaybackSettings.MUSIC_SOURCE_BUILTIN
    private var musicTrackId: String? = null
    private var musicLocalPath: String? = null
    private var musicLocalTitle: String? = null
    var onManagePhotos: (() -> Unit)? = null
    var onPickMusic: (() -> Unit)? = null
    var weatherReady = true
        set(value) {
            field = value
            updateWeatherSetupHint()
        }
    var onWeatherSetup: (() -> Unit)? = null

    private lateinit var holdCustomInput: EditText
    private lateinit var holdCustomSection: LinearLayout
    private lateinit var holdUnitControl: dev.photopilot.scaffold.ui.widget.SegmentedControl
    private lateinit var holdError: TextView
    private lateinit var transitionSeek: SeekBar
    private lateinit var transitionValue: TextView
    private lateinit var transitionDisabledNote: TextView
    private lateinit var transitionError: TextView
    private lateinit var reduceMotionNote: TextView
    private lateinit var cropWarning: TextView
    private lateinit var brightnessSeek: SeekBar
    private lateinit var restStartButton: TextView
    private lateinit var restEndButton: TextView
    private lateinit var restDisplayRow: LinearLayout
    private lateinit var restDisplayButton: dev.photopilot.scaffold.ui.widget.DropdownField
    private lateinit var weatherSetupHint: LinearLayout
    private lateinit var restError: TextView
    private lateinit var solidRow: dev.photopilot.scaffold.ui.widget.SegmentedControl
    private lateinit var musicButton: dev.photopilot.scaffold.ui.widget.DropdownField
    private lateinit var previewFrame: FrameLayout
    private lateinit var previewBackground: ImageView
    private lateinit var previewImage: ImageView

    private lateinit var transitionButton: dev.photopilot.scaffold.ui.widget.DropdownField
    private lateinit var fitButton: dev.photopilot.scaffold.ui.widget.DropdownField
    private lateinit var previewImageB: ImageView
    private var previewBitmapB: Bitmap? = null
    private var isDemoRunning = false
    private var previewBitmap: Bitmap? = null
    private var previewBlurred: Bitmap? = null
    private var previewEdge: Int? = null
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var contentColumn: LinearLayout
    private lateinit var actionBar: LinearLayout
    private lateinit var saveButton: TextView
    private lateinit var cancelButton: TextView

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        isClickable = true
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)

        val headerRow = screenHeader(context, R.string.playback_title) { onBack?.invoke(hasChanges()) }
        headerRow.setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_8), inset, 0)
        addView(headerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(context)
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(inset, 0, inset, inset)
        }
        contentColumn = column
        buildContent(column)
        scroll.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bottomBar = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_12), inset, res.getDimensionPixelSize(R.dimen.pp_space_16))
            setBackgroundColor(context.getColor(R.color.pp_surface_panel))
        }
        val save = primaryButton(context, R.string.common_save).apply {
            setOnClickListener { attemptSave() }
        }
        val cancel = secondaryButton(context, R.string.common_cancel).apply {
            setOnClickListener { onBack?.invoke(hasChanges()) }
        }
        bottomBar.addView(save, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        bottomBar.addView(
            cancel,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        actionBar = bottomBar
        saveButton = save
        cancelButton = cancel
        addView(bottomBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun applyWidthTier(width: Int) {
        if (width <= 0) return
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)
        val gap = res.getDimensionPixelSize(R.dimen.pp_space_8)
        val maxContent = (CONTENT_MAX_WIDTH_DP * res.displayMetrics.density).toInt()
        val sidePadding = maxOf(inset, (width - maxContent) / 2)
        if (contentColumn.paddingLeft != sidePadding) {
            contentColumn.setPadding(sidePadding, 0, sidePadding, inset)
        }
        if (actionBar.paddingLeft != sidePadding) {
            actionBar.setPadding(
                sidePadding,
                res.getDimensionPixelSize(R.dimen.pp_space_12),
                sidePadding,
                res.getDimensionPixelSize(R.dimen.pp_space_16),
            )
        }
        val sideBySide = width / res.displayMetrics.density >= WIDE_ACTION_MIN_WIDTH_DP
        val desired = if (sideBySide) HORIZONTAL else VERTICAL
        if (actionBar.orientation == desired && actionBar.childCount == 2) {
            if (actionBar.getChildAt(0) === (if (sideBySide) cancelButton else saveButton)) return
        }
        actionBar.removeAllViews()
        actionBar.orientation = desired
        if (sideBySide) {
            actionBar.addView(cancelButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            actionBar.addView(
                saveButton,
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = gap },
            )
        } else {
            actionBar.addView(saveButton, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            actionBar.addView(
                cancelButton,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = gap },
            )
        }
    }

    fun bind(frame: FrameEntity) {
        val s = FramePlaybackSettings.parse(frame.settingsJson)
        reduceMotion = reduceMotionEnabled()
        holdPresetIndex = HOLD_PRESETS.indexOf(s.holdSeconds)
        holdCustomMinutes = false
        if (holdPresetIndex < 0) {
            holdPresetIndex = HOLD_CUSTOM_INDEX
            if (s.holdSeconds >= 60 && s.holdSeconds % 60 == 0) {
                holdCustomMinutes = true
                holdCustomInput.setText((s.holdSeconds / 60).toString())
            } else {
                holdCustomInput.setText(s.holdSeconds.toString())
            }
        }
        holdUnitControl.setSelected(if (holdCustomMinutes) 1 else 0)
        transitionIndex = TRANSITIONS.indexOf(s.transition).coerceAtLeast(0)
        transitionTenths = (s.transitionSeconds * 10).toInt().coerceIn(2, 30)
        fitIndex = when (s.fitMode) {
            FramePlaybackSettings.FIT_COLOR -> 1
            FramePlaybackSettings.FILL_CROP -> 2
            else -> 0
        }
        clockEnabled = s.clockEnabled
        dateEnabled = s.dateEnabled
        positionIndex = POSITIONS.indexOf(s.clockPosition).coerceAtLeast(0)
        hourFormatIndex = when (s.clockHour24) {
            true -> 1
            false -> 2
            null -> 0
        }
        orderIndex = when (frame.orderMode) {
            FrameOrderMode.RANDOM -> 1
            FrameOrderMode.MANUAL -> 2
            else -> 0
        }
        initialOrder = frame.orderMode
        brightnessFollow = s.brightness == null
        brightnessPercent = ((s.brightness ?: 0.5f) * 100).toInt().coerceIn(0, 100)
        keepAwakePaused = s.keepAwakeWhenPaused
        restEnabled = s.restEnabled
        restStartMinute = s.restStartMinute
        restEndMinute = s.restEndMinute
        restDisplayIndex = REST_DISPLAYS.indexOf(s.restDisplay).coerceAtLeast(0)
        qualityIndex = when (s.qualityLevel) {
            FramePlaybackSettings.QUALITY_BALANCED -> 1
            FramePlaybackSettings.QUALITY_LOW -> 2
            else -> 0
        }
        solidIndex = when (s.solidColor) {
            FramePlaybackSettings.SOLID_BLACK -> 1
            FramePlaybackSettings.SOLID_WARM_WHITE -> 2
            else -> 0
        }
        musicEnabled = s.musicEnabled
        musicSource = s.musicSource
        musicTrackId = s.musicTrackId
        musicLocalPath = s.musicLocalPath
        musicLocalTitle = s.musicLocalTitle
        if (reduceMotion) {
            reduceMotionNote.visibility = View.VISIBLE
        }
        initialDraft = currentDraft()
        refreshAll()
    }

    fun bindPreview(asset: AssetEntity?, file: File?, nextAsset: AssetEntity?, nextFile: File?) {
        if (asset == null || file == null) return
        executor.execute {
            val bitmap = runCatching {
                BitmapRenderer.decodeToTarget(file, PREVIEW_DECODE_PX, PREVIEW_DECODE_PX, asset.exifOrientation.toInt())
            }.getOrNull()
            val blurred = bitmap?.let { runCatching { BitmapRenderer.blurBackground(it) }.getOrNull() }
            val edge = bitmap?.let {
                runCatching {
                    BitmapRenderer.edgeColor(it)?.let(android.graphics.Color::parseColor)
                }.getOrNull()
            }
            val nextBitmap = if (nextAsset != null && nextFile != null) {
                runCatching {
                    BitmapRenderer.decodeToTarget(
                        nextFile, PREVIEW_DECODE_PX, PREVIEW_DECODE_PX, nextAsset.exifOrientation.toInt(),
                    )
                }.getOrNull()
            } else {
                null
            }
            post {
                previewBitmap = bitmap
                previewBlurred = blurred
                previewEdge = edge
                previewBitmapB = nextBitmap
                renderPreview()
            }
        }
    }

    fun showError(error: PlaybackSettingsEdit.Error) {
        clearErrors()
        when (error) {
            PlaybackSettingsEdit.Error.HOLD_INVALID -> {
                holdError.text = resources.getString(R.string.playback_invalid_number)
                holdError.visibility = View.VISIBLE
            }
            PlaybackSettingsEdit.Error.HOLD_OUT_OF_RANGE -> {
                holdError.text = resources.getString(
                    R.string.playback_out_of_range,
                    PlaybackTiming.HOLD_MIN_SECONDS,
                    PlaybackTiming.HOLD_MAX_SECONDS,
                )
                holdError.visibility = View.VISIBLE
            }
            PlaybackSettingsEdit.Error.TRANSITION_SECONDS_INVALID -> {
                transitionError.text = resources.getString(R.string.playback_invalid_number)
                transitionError.visibility = View.VISIBLE
            }
            PlaybackSettingsEdit.Error.REST_WINDOW_INVALID -> {
                restError.text = resources.getString(R.string.settings_rest_invalid)
                restError.visibility = View.VISIBLE
            }
        }
    }

    fun hasChanges(): Boolean {
        val initial = initialDraft ?: return false
        return currentDraft() != initial || orderIndexToMode(orderIndex) != initialOrder
    }

    fun currentDraft(): PlaybackSettingsEdit.Draft = PlaybackSettingsEdit.Draft(
        holdSeconds = currentHoldSeconds(),
        transition = TRANSITIONS[transitionIndex],
        transitionSeconds = transitionTenths / 10.0,
        fitMode = when (fitIndex) {
            1 -> FramePlaybackSettings.FIT_COLOR
            2 -> FramePlaybackSettings.FILL_CROP
            else -> FramePlaybackSettings.FIT_BLUR
        },
        clockEnabled = clockEnabled,
        clockPosition = POSITIONS[positionIndex],
        clockHour24 = when (hourFormatIndex) {
            1 -> true
            2 -> false
            else -> null
        },
        dateEnabled = dateEnabled,
        keepAwakeWhenPaused = keepAwakePaused,
        restEnabled = restEnabled,
        restStartMinute = restStartMinute,
        restEndMinute = restEndMinute,
        restDisplay = REST_DISPLAYS[restDisplayIndex],
        brightness = if (brightnessFollow) null else brightnessPercent / 100.0,
        qualityLevel = when (qualityIndex) {
            1 -> FramePlaybackSettings.QUALITY_BALANCED
            2 -> FramePlaybackSettings.QUALITY_LOW
            else -> FramePlaybackSettings.QUALITY_AUTO
        },
        solidColor = when (solidIndex) {
            1 -> FramePlaybackSettings.SOLID_BLACK
            2 -> FramePlaybackSettings.SOLID_WARM_WHITE
            else -> null
        },
        musicEnabled = musicEnabled,
        musicSource = musicSource,
        musicTrackId = musicTrackId,
        musicLocalPath = musicLocalPath,
        musicLocalTitle = musicLocalTitle,
    )
    fun musicSelection(): MusicCatalog.Selection = MusicCatalog.Selection(
        enabled = musicEnabled,
        source = musicSource,
        trackId = musicTrackId,
        localPath = musicLocalPath,
        localTitle = musicLocalTitle,
    )

    fun updateMusic(selection: MusicCatalog.Selection) {
        musicEnabled = selection.enabled
        musicSource = selection.source
        musicTrackId = selection.trackId
        musicLocalPath = selection.localPath
        musicLocalTitle = selection.localTitle
        refreshMusicState()
    }


    private fun currentHoldSeconds(): Int {
        if (holdPresetIndex in HOLD_PRESETS.indices) return HOLD_PRESETS[holdPresetIndex]
        val raw = holdCustomInput.text.toString().trim().toDoubleOrNull() ?: return -1
        val seconds = if (holdCustomMinutes) raw * 60.0 else raw
        return seconds.toInt()
    }

    private fun updateHoldCustomVisibility() {
        val custom = holdPresetIndex == HOLD_CUSTOM_INDEX
        holdCustomSection.visibility = if (custom) View.VISIBLE else View.GONE
    }

    private fun attemptSave() {
        clearErrors()
        val seconds = currentHoldSeconds()
        if (seconds < 0) {
            holdError.text = resources.getString(R.string.playback_invalid_number)
            holdError.visibility = View.VISIBLE
            return
        }
        onSave?.invoke(currentDraft(), orderIndexToMode(orderIndex))
    }

    private fun clearErrors() {
        holdError.visibility = View.GONE
        transitionError.visibility = View.GONE
        restError.visibility = View.GONE
    }

    private fun orderIndexToMode(index: Int): FrameOrderMode = when (index) {
        1 -> FrameOrderMode.RANDOM
        2 -> FrameOrderMode.MANUAL
        else -> FrameOrderMode.CAPTURED_ASC
    }

    private fun buildContent(column: LinearLayout) {
        addLabel(column, R.string.playback_preview, 0)
        previewFrame = FrameLayout(context).apply {
            setBackgroundColor(context.getColor(R.color.pp_surface_rest))
        }
        previewBackground = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        previewImage = ImageView(context).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        previewImageB = ImageView(context).apply {
            alpha = 0f
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        previewFrame.addView(previewBackground, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        previewFrame.addView(previewImage, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        previewFrame.addView(previewImageB, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        column.addView(
            previewFrame,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        val managePhotos = secondaryButton(context, R.string.playback_manage_photos).apply {
            setOnClickListener { onManagePhotos?.invoke() }
        }
        column.addView(
            managePhotos,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )

        addLabel(column, R.string.playback_hold_duration, R.dimen.pp_space_24)
        addHelp(column, R.string.playback_hold_duration_help)
        column.addView(
            optionGroup(
                HOLD_PRESETS.map { R.string.format_seconds_short } + R.string.common_custom,
                holdPresetIndex,
            ) { index ->
                holdPresetIndex = index
                updateHoldCustomVisibility()
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        holdCustomSection = LinearLayout(context).apply { orientation = VERTICAL }
        addHelp(holdCustomSection, R.string.playback_hold_custom_title)
        val customRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            dev.photopilot.scaffold.ui.widget.KeyboardDismiss.keepInputOnTouch(this)
        }
        holdCustomInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            dev.photopilot.scaffold.ui.widget.KeyboardDismiss.configureDone(this)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            setHintTextColor(context.getColor(R.color.pp_text_secondary))
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.pp_touch_target)
            val padH = resources.getDimensionPixelSize(R.dimen.pp_space_16)
            setPadding(padH, 0, padH, 0)
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.pp_surface_panel))
                cornerRadius = resources.getDimension(R.dimen.pp_radius_group)
            }
        }
        customRow.addView(holdCustomInput, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        holdUnitControl = optionGroup(
            listOf(R.string.playback_unit_seconds, R.string.playback_unit_minutes),
            0,
        ) { index -> holdCustomMinutes = index == 1 }
        customRow.addView(
            holdUnitControl,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        holdCustomSection.addView(
            customRow,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addHelp(holdCustomSection, R.string.playback_hold_custom_help)
        holdError = addError(holdCustomSection)
        column.addView(
            holdCustomSection,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        addLabel(column, R.string.playback_transition, R.dimen.pp_space_24)
        transitionButton = dropdownField(context, R.string.playback_transition).apply {
            setOnClickListener { anchor -> showTransitionMenu(anchor) }
        }
        column.addView(
            transitionButton,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        addLabel(column, R.string.playback_transition_duration, R.dimen.pp_space_12)
        transitionSeek = SeekBar(context).apply {
            max = TRANSITION_SEEK_MAX
            progress = transitionTenths - TRANSITION_TENTHS_MIN
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    transitionTenths = progress + TRANSITION_TENTHS_MIN
                    updateTransitionValue()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        column.addView(transitionSeek, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        transitionValue = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        column.addView(transitionValue, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addHelp(column, R.string.playback_transition_range)
        transitionDisabledNote = addHelp(column, R.string.playback_transition_duration_disabled)
        reduceMotionNote = addHelp(column, R.string.settings_reduce_motion_note)
        reduceMotionNote.visibility = View.GONE
        transitionError = addError(column)

        addLabel(column, R.string.playback_fit_mode, R.dimen.pp_space_24)
        fitButton = dropdownField(context, R.string.playback_fit_mode).apply {
            setOnClickListener { anchor -> showFitModeMenu(anchor) }
        }
        column.addView(
            fitButton,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        solidRow = optionGroup(
            listOf(
                R.string.playback_solid_auto,
                R.string.playback_solid_black,
                R.string.playback_solid_warm_white,
            ),
            solidIndex,
        ) { index ->
            solidIndex = index
            renderPreview()
        }
        solidRow.visibility = View.GONE
        column.addView(solidRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        cropWarning = addHelp(column, R.string.playback_fit_crop_warning)
        cropWarning.visibility = View.GONE

        addSwitch(column, R.string.playback_clock, clockEnabled) { clockEnabled = it }
        addSwitch(column, R.string.playback_date, dateEnabled) { dateEnabled = it }
        addLabel(column, R.string.playback_overlay_position, R.dimen.pp_space_12)
        column.addView(
            optionGroup(
                listOf(
                    R.string.playback_position_top_leading,
                    R.string.playback_position_top_center,
                    R.string.playback_position_top_trailing,
                    R.string.playback_position_bottom_leading,
                    R.string.playback_position_bottom_center,
                    R.string.playback_position_bottom_trailing,
                ),
                positionIndex,
                singleRow = false,
            ) { positionIndex = it },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addLabel(column, R.string.playback_hour_format, R.dimen.pp_space_12)
        column.addView(
            optionGroup(
                listOf(
                    R.string.settings_appearance_system,
                    R.string.playback_hour_24,
                    R.string.playback_hour_12,
                ),
                hourFormatIndex,
            ) { hourFormatIndex = it },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        addLabel(column, R.string.playback_order, R.dimen.pp_space_24)
        column.addView(
            optionGroup(
                listOf(
                    R.string.playback_order_captured,
                    R.string.playback_order_random,
                    R.string.playback_order_manual,
                ),
                orderIndex,
            ) { orderIndex = it },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        addLabel(column, R.string.playback_brightness, R.dimen.pp_space_24)
        addSwitch(column, R.string.settings_appearance_system, brightnessFollow) { follow ->
            brightnessFollow = follow
            brightnessSeek.isEnabled = !follow
            if (follow) {
                cancelBrightnessPreview()
            } else {
                applyBrightnessPreview(brightnessSeek.progress)
            }
        }
        brightnessSeek = SeekBar(context).apply {
            max = BRIGHTNESS_SEEK_MAX
            progress = brightnessPercent
            isEnabled = !brightnessFollow
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    brightnessPercent = progress
                    if (fromUser && !brightnessFollow) {
                        applyBrightnessPreview(progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        column.addView(brightnessSeek, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addHelp(column, R.string.playback_brightness_restore_help)
        addSwitch(column, R.string.playback_keep_awake_paused, keepAwakePaused) { keepAwakePaused = it }

        addSwitch(column, R.string.settings_rest_window, restEnabled) { enabled ->
            restEnabled = enabled
            refreshRestState()
        }
        restRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        restStartButton = timeButton(R.string.settings_rest_start) {
            pickTime(restStartMinute) { minute ->
                restStartMinute = minute
                refreshRestState()
            }
        }
        restEndButton = timeButton(R.string.settings_rest_end) {
            pickTime(restEndMinute) { minute ->
                restEndMinute = minute
                refreshRestState()
            }
        }
        restRow.addView(restStartButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        restRow.addView(
            restEndButton,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
        column.addView(restRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        restDisplayRow = LinearLayout(context).apply { orientation = VERTICAL }
        val restDisplayLabel = TextView(context).apply {
            text = resources.getString(R.string.settings_rest_display)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
        }
        restDisplayRow.addView(
            restDisplayLabel,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
        restDisplayButton = dropdownField(context, R.string.settings_rest_display).apply {
            setOnClickListener { anchor -> showRestDisplayMenu(anchor) }
        }
        restDisplayRow.addView(
            restDisplayButton,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        weatherSetupHint = LinearLayout(context).apply { orientation = VERTICAL }
        weatherSetupHint.addView(
            TextView(context).apply {
                text = resources.getString(R.string.weather_disabled_notice)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_meta))
                setTextColor(context.getColor(R.color.pp_status_warning))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        weatherSetupHint.addView(
            secondaryButton(context, R.string.weather_enable_and_choose_city).apply {
                setOnClickListener { onWeatherSetup?.invoke() }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        restDisplayRow.addView(weatherSetupHint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.addView(restDisplayRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addHelp(column, R.string.settings_rest_help)
        restError = addError(column)

        addLabel(column, R.string.playback_quality, R.dimen.pp_space_24)
        column.addView(
            optionGroup(
                listOf(
                    R.string.playback_quality_auto,
                    R.string.playback_quality_balanced,
                    R.string.playback_quality_low,
                ),
                qualityIndex,
            ) { qualityIndex = it },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        addLabel(column, R.string.playback_music_title, R.dimen.pp_space_24)
        addHelp(column, R.string.playback_music_help)
        musicButton = dropdownField(context, R.string.playback_music_title).apply {
            setOnClickListener { onPickMusic?.invoke() }
        }
        column.addView(
            musicButton,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )

    }

    private fun refreshAll() {
        transitionSeek.progress = transitionTenths - TRANSITION_TENTHS_MIN
        brightnessSeek.progress = brightnessPercent
        brightnessSeek.isEnabled = !brightnessFollow
        updateHoldCustomVisibility()
        refreshFitState()
        refreshTransitionState()
        refreshRestState()
        refreshMusicState()
        renderPreview()
    }

    private fun refreshMusicState() {
        musicButton.text = MusicCatalog.displayName(context, musicSelection())
    }

    private fun refreshFitState() {
        fitButton.text = resources.getString(FIT_LABELS[fitIndex])
        cropWarning.visibility = if (fitIndex == 2) View.VISIBLE else View.GONE
        solidRow.visibility = if (fitIndex == 1) View.VISIBLE else View.GONE
    }

    private fun showFitModeMenu(anchor: View) {
        val field = anchor as? dev.photopilot.scaffold.ui.widget.DropdownField
        val menu = android.widget.PopupMenu(context, anchor)
        FIT_LABELS.forEachIndexed { index, labelRes ->
            menu.menu.add(0, index, index, resources.getString(labelRes))
                .setOnMenuItemClickListener {
                    if (index != fitIndex) {
                        fitIndex = index
                        refreshFitState()
                        renderPreview()
                    }
                    true
                }
        }
        field?.setExpanded(true)
        menu.setOnDismissListener { field?.setExpanded(false) }
        menu.show()
    }

    private fun refreshTransitionState() {
        val off = transitionIndex == 0
        transitionSeek.isEnabled = !off
        updateTransitionValue()
        transitionDisabledNote.visibility = if (off) View.VISIBLE else View.GONE
        transitionButton.isEnabled = !reduceMotion
        transitionButton.text = resources.getString(TRANSITION_LABELS[transitionIndex])
    }

    private fun showTransitionMenu(anchor: View) {
        val field = anchor as? dev.photopilot.scaffold.ui.widget.DropdownField
        val menu = android.widget.PopupMenu(context, anchor)
        TRANSITION_LABELS.forEachIndexed { index, labelRes ->
            menu.menu.add(0, index, index, resources.getString(labelRes))
                .setOnMenuItemClickListener {
                    if (!reduceMotion && index != transitionIndex) {
                        transitionIndex = index
                        refreshTransitionState()
                        playTransitionDemo()
                    }
                    true
                }
        }
        field?.setExpanded(true)
        menu.setOnDismissListener { field?.setExpanded(false) }
        menu.show()
    }

    private fun playTransitionDemo() {
        val bitmap = previewBitmap ?: return
        if (isDemoRunning) return
        var transition = TRANSITIONS[transitionIndex]
        if (transition == PlaybackTiming.TRANSITION_RANDOM) {
            transition = PlaybackTiming.randomTransitionPool.random()
        }
        val outgoing = previewImage
        val incoming = previewImageB
        outgoing.animate().cancel()
        incoming.animate().cancel()
        if (transition == PlaybackTiming.TRANSITION_NONE) {
            outgoing.alpha = 0f
            outgoing.animate().alpha(1f).setDuration(200).start()
            return
        }
        val demo = previewBitmapB ?: bitmap
        val width = (if (previewFrame.width > 0) previewFrame.width else resources.displayMetrics.widthPixels).toFloat()
        isDemoRunning = true
        incoming.bringToFront()
        incoming.setImageBitmap(demo)
        incoming.translationX = if (
            transition == PlaybackTiming.TRANSITION_SLIDE || transition == PlaybackTiming.TRANSITION_WIPE
        ) {
            width
        } else {
            0f
        }
        incoming.alpha = if (
            transition == PlaybackTiming.TRANSITION_SLIDE || transition == PlaybackTiming.TRANSITION_WIPE
        ) {
            1f
        } else {
            0f
        }
        when (transition) {
            PlaybackTiming.TRANSITION_ZOOM -> {
                incoming.scaleX = 1.08f
                incoming.scaleY = 1.08f
            }
            PlaybackTiming.TRANSITION_RIPPLE -> {
                incoming.scaleX = 0.9f
                incoming.scaleY = 0.9f
            }
            else -> {
                incoming.scaleX = 1f
                incoming.scaleY = 1f
            }
        }
        val duration = (transitionTenths * 100L).coerceAtLeast(250L)
        incoming.animate()
            .alpha(1f)
            .translationX(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(duration)
            .withEndAction {
                incoming.alpha = 0f
                incoming.translationX = 0f
                incoming.scaleX = 1f
                incoming.scaleY = 1f
                isDemoRunning = false
                renderPreview()
            }
            .start()
    }

    private fun updateTransitionValue() {
        transitionValue.text = resources.getString(
            R.string.format_seconds_decimal, String.format(Locale.US, "%.1f", transitionTenths / 10.0),
        )
    }

    private fun refreshRestState() {
        val enabled = restEnabled
        restRow.visibility = if (enabled) VISIBLE else GONE
        restStartButton.isEnabled = enabled
        restEndButton.isEnabled = enabled
        restStartButton.text = timeButtonText(R.string.settings_rest_start, restStartMinute)
        restEndButton.text = timeButtonText(R.string.settings_rest_end, restEndMinute)
        restDisplayRow.visibility = if (enabled) VISIBLE else GONE
        restDisplayButton.text = resources.getString(REST_DISPLAY_LABELS[restDisplayIndex])
        updateWeatherSetupHint()
    }

    fun updateWeatherSetupHint() {
        if (!::weatherSetupHint.isInitialized) return
        val wantsWeather = REST_DISPLAYS[restDisplayIndex] == FramePlaybackSettings.REST_DISPLAY_CLOCK_WEATHER
        weatherSetupHint.visibility =
            if (restEnabled && wantsWeather && !weatherReady) VISIBLE else GONE
    }

    private fun showRestDisplayMenu(anchor: View) {
        val field = anchor as? dev.photopilot.scaffold.ui.widget.DropdownField
        val menu = android.widget.PopupMenu(context, anchor)
        REST_DISPLAY_LABELS.forEachIndexed { index, labelRes ->
            menu.menu.add(0, index, index, resources.getString(labelRes))
                .setOnMenuItemClickListener {
                    if (index != restDisplayIndex) {
                        restDisplayIndex = index
                        refreshRestState()
                    }
                    true
                }
        }
        field?.setExpanded(true)
        menu.setOnDismissListener { field?.setExpanded(false) }
        menu.show()
    }

    private fun renderPreview() {
        val bitmap = previewBitmap ?: return
        previewImage.animate().cancel()
        previewImage.alpha = 1f
        previewImage.translationX = 0f
        previewImage.scaleX = 1f
        previewImage.scaleY = 1f
        previewImageB.animate().cancel()
        previewImageB.alpha = 0f
        previewImageB.translationX = 0f
        previewImageB.scaleX = 1f
        previewImageB.scaleY = 1f
        previewImage.scaleType = if (fitIndex == 2) {
            ImageView.ScaleType.CENTER_CROP
        } else {
            ImageView.ScaleType.FIT_CENTER
        }
        previewImage.setImageBitmap(bitmap)
        when (fitIndex) {
            0 -> previewBackground.setImageBitmap(previewBlurred)
            1 -> previewBackground.setImageDrawable(
                android.graphics.drawable.ColorDrawable(solidPreviewColor()),
            )
            else -> previewBackground.setImageDrawable(null)
        }
    }

    private fun solidPreviewColor(): Int =
        when (solidIndex) {
            1 -> context.getColor(R.color.pp_surface_rest)
            2 -> context.getColor(R.color.pp_surface_canvas)
            else -> previewEdge ?: context.getColor(R.color.pp_surface_rest)
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) {
            applyWidthTier(w)
            val previewWidth = (w - contentColumn.paddingLeft - contentColumn.paddingRight).coerceAtLeast(1)
            val lp = previewFrame.layoutParams
            val target = previewWidth * PREVIEW_HEIGHT_NUM / PREVIEW_HEIGHT_DEN
            if (lp.height != target) {
                lp.height = target
                previewFrame.layoutParams = lp
            }
        }
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

    private fun timeButton(labelRes: Int, onClick: () -> Unit): TextView =
        secondaryButton(context, labelRes).apply {
            setOnClickListener { onClick() }
        }

    private fun timeButtonText(labelRes: Int, minute: Int?): String {
        val label = resources.getString(labelRes)
        val value = minute?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "--:--"
        return "$label: $value"
    }

    private fun reduceMotionEnabled(): Boolean =
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f

    private fun applyBrightnessPreview(progress: Int) {
        val activity = context as? android.app.Activity ?: return
        val attrs = activity.window.attributes
        if (previewOriginalBrightness == null) {
            previewOriginalBrightness = attrs.screenBrightness
        }
        attrs.screenBrightness = (progress / 100.0).toFloat().coerceIn(0f, 1f)
        activity.window.attributes = attrs
    }

    fun cancelBrightnessPreview() {
        val original = previewOriginalBrightness ?: return
        val activity = context as? android.app.Activity ?: return
        val attrs = activity.window.attributes
        attrs.screenBrightness = original
        activity.window.attributes = attrs
        previewOriginalBrightness = null
    }

    fun commitBrightnessPreview() {
        previewOriginalBrightness = null
    }

    private fun addLabel(parent: LinearLayout, textRes: Int, marginRes: Int) {
        val res = resources
        val view = TextView(context).apply {
            text = res.getString(textRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
        }
        parent.addView(
            view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (marginRes != 0) topMargin = res.getDimensionPixelSize(marginRes)
            },
        )
    }

    private fun addHelp(parent: LinearLayout, textRes: Int): TextView {
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
        return view
    }

    private fun addError(parent: LinearLayout): TextView {
        val res = resources
        val view = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_status_error))
            visibility = View.GONE
        }
        parent.addView(
            view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_4)
            },
        )
        return view
    }

    private fun addSwitch(parent: LinearLayout, textRes: Int, checked: Boolean, onToggle: (Boolean) -> Unit) {
        val res = resources
        val view = Switch(context).apply {
            text = res.getString(textRes)
            isChecked = checked
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
        parent.addView(
            view,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
    }

    private fun optionGroup(
        labelResList: List<Int>,
        selected: Int,
        singleRow: Boolean = true,
        onSelect: (Int) -> Unit,
    ): dev.photopilot.scaffold.ui.widget.SegmentedControl {
        val res = resources
        val labels = labelResList.mapIndexed { index, labelRes ->
            res.getString(labelRes, *slotArgs(labelRes, index))
        }
        return dev.photopilot.scaffold.ui.widget.SegmentedControl(
            context,
            textSizeRes = R.dimen.pp_text_meta,
            autoSize = true,
            singleRow = singleRow,
        ).apply { setItems(labels, selected, onSelect) }
    }

    private fun slotArgs(labelRes: Int, index: Int): Array<Any> =
        if (labelRes == R.string.format_seconds_short && index < HOLD_PRESETS.size) {
            arrayOf(HOLD_PRESETS[index])
        } else {
            emptyArray()
        }

    private companion object {
        val HOLD_PRESETS = listOf(10, 15, 30)
        const val HOLD_CUSTOM_INDEX = 3
        val FIT_LABELS = listOf(
            R.string.playback_fit_blur,
            R.string.playback_fit_color,
            R.string.playback_fill_crop,
        )
        val TRANSITIONS = listOf(
            PlaybackTiming.TRANSITION_NONE,
            PlaybackTiming.TRANSITION_RANDOM,
            PlaybackTiming.TRANSITION_CROSSFADE,
            PlaybackTiming.TRANSITION_SLIDE,
            PlaybackTiming.TRANSITION_WIPE,
            PlaybackTiming.TRANSITION_ZOOM,
            PlaybackTiming.TRANSITION_RIPPLE,
        )
        val TRANSITION_LABELS = listOf(
            R.string.playback_transition_none,
            R.string.playback_transition_random,
            R.string.playback_transition_crossfade,
            R.string.playback_transition_slide,
            R.string.playback_transition_wipe,
            R.string.playback_transition_zoom,
            R.string.playback_transition_ripple,
        )
        val POSITIONS = listOf(
            FramePlaybackSettings.POSITION_TOP_LEADING,
            FramePlaybackSettings.POSITION_TOP_CENTER,
            FramePlaybackSettings.POSITION_TOP_TRAILING,
            FramePlaybackSettings.POSITION_BOTTOM_LEADING,
            FramePlaybackSettings.POSITION_BOTTOM_CENTER,
            FramePlaybackSettings.POSITION_BOTTOM_TRAILING,
        )
        val REST_DISPLAYS = listOf(
            FramePlaybackSettings.REST_DISPLAY_DIM,
            FramePlaybackSettings.REST_DISPLAY_CLOCK,
            FramePlaybackSettings.REST_DISPLAY_CLOCK_WEATHER,
            FramePlaybackSettings.REST_DISPLAY_BLACK,
        )
        val REST_DISPLAY_LABELS = listOf(
            R.string.settings_rest_display_dim,
            R.string.settings_rest_display_clock,
            R.string.settings_rest_display_clock_weather,
            R.string.settings_rest_display_black,
        )
        const val TRANSITION_TENTHS_MIN = 2
        const val CONTENT_MAX_WIDTH_DP = 560f
        const val WIDE_ACTION_MIN_WIDTH_DP = 600f
        const val TRANSITION_SEEK_MAX = 28
        const val BRIGHTNESS_SEEK_MAX = 100
        const val PREVIEW_DECODE_PX = 512
        const val PREVIEW_HEIGHT_NUM = 9
        const val PREVIEW_HEIGHT_DEN = 16
    }
}
