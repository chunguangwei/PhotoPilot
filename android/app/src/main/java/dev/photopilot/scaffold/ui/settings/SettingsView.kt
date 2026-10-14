package dev.photopilot.scaffold.ui.settings

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.photopilot.scaffold.R

class SettingsView(context: Context) : ScrollView(context) {

    data class StorageModel(
        val photosBytes: Long,
        val cacheBytes: Long,
        val freeBytes: Long,
        val lowStorage: Boolean,
    )

    data class Model(
        val appearance: String,
        val language: String,
        val autoPlay: Boolean,
        val wifiOnly: Boolean,
        val shareStatistics: Boolean,
        val reduceMotionActive: Boolean,
        val versionName: String,
        val storage: StorageModel?,
        val weatherEnabledDefault: Boolean,
        val weatherPlaceName: String?,
        val weatherUnitsIndex: Int,
        val weatherObservedText: String?,
        val weatherAttributionText: String?,
    )

    var onAppearance: ((Int) -> Unit)? = null
    var onLanguage: ((Int) -> Unit)? = null
    var onToggleAutoPlay: ((Boolean) -> Unit)? = null
    var onToggleWifiOnly: ((Boolean) -> Unit)? = null
    var onToggleShareStatistics: ((Boolean) -> Unit)? = null
    var onClearCache: (() -> Unit)? = null
    var onRestoreDeleted: (() -> Unit)? = null
    var onClearAll: (() -> Unit)? = null
    var onExportDiagnostics: (() -> Unit)? = null
    var onOpenAccessibilitySettings: (() -> Unit)? = null
    var onLicenses: (() -> Unit)? = null
    var onUpdate: (() -> Unit)? = null
    var onToggleWeatherDefault: ((Boolean) -> Unit)? = null
    var onWeatherUnits: ((Int) -> Unit)? = null
    var onWeatherChooseCity: (() -> Unit)? = null
    var onWeatherManualRefresh: (() -> Unit)? = null
    var onHoliday: (() -> Unit)? = null

    private lateinit var weatherError: TextView

    fun showWeatherError(show: Boolean) {
        if (::weatherError.isInitialized) {
            weatherError.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private val column: LinearLayout

    init {
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        val inset = resources.getDimensionPixelSize(R.dimen.pp_content_inset)
        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, inset, inset, inset)
        }
        addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    fun render(model: Model) {
        val res = resources
        column.removeAllViews()
        addText(column, R.string.settings_title, R.dimen.pp_text_heading, R.color.pp_text_primary, 0, heading = true)

        val appearanceGroup = section(R.string.settings_appearance)
        addOptionRow(
            appearanceGroup,
            listOf(
                R.string.settings_appearance_system,
                R.string.settings_appearance_light,
                R.string.settings_appearance_dark,
            ),
            APPEARANCE_VALUES.indexOf(model.appearance).coerceAtLeast(0),
        ) { onAppearance?.invoke(it) }

        val languageGroup = section(R.string.settings_language)
        addOptionRow(
            languageGroup,
            listOf(
                R.string.settings_language_system,
                R.string.settings_language_zh,
                R.string.settings_language_en,
            ),
            LANGUAGE_VALUES.indexOf(model.language).coerceAtLeast(0),
        ) { onLanguage?.invoke(it) }
        addHelp(languageGroup, R.string.settings_language_system_note)

        val a11yGroup = section(R.string.settings_accessibility)
        if (model.reduceMotionActive) {
            addHelp(a11yGroup, R.string.settings_reduce_motion_note)
        }
        addActionRow(a11yGroup, R.string.common_open_system_settings) { onOpenAccessibilitySettings?.invoke() }

        val storageGroup = section(R.string.settings_storage, expandedByDefault = false)
        model.storage?.let { storage ->
            addValueRow(storageGroup, R.string.settings_storage_photos, formatBytes(storage.photosBytes))
            addValueRow(storageGroup, R.string.settings_storage_cache, formatBytes(storage.cacheBytes))
            addTextValue(
                storageGroup,
                res.getString(R.string.settings_storage_free, formatBytes(storage.freeBytes)),
            )
            if (storage.lowStorage) {
                addText(storageGroup, R.string.settings_storage_low_title, R.dimen.pp_text_body, R.color.pp_status_warning, R.dimen.pp_space_8)
            }
        }
        addText(storageGroup, R.string.settings_storage_threshold, R.dimen.pp_text_body, R.color.pp_text_primary, R.dimen.pp_space_8)
        addHelp(storageGroup, R.string.settings_storage_threshold_help)
        addActionRow(storageGroup, R.string.delete_clear_cache) { onClearCache?.invoke() }
        addHelp(storageGroup, R.string.delete_clear_cache_body)
        addActionRow(storageGroup, R.string.delete_restore_deleted) { onRestoreDeleted?.invoke() }

        val networkGroup = section(R.string.settings_network)
        addSwitchRow(networkGroup, R.string.settings_wifi_only, model.wifiOnly) { onToggleWifiOnly?.invoke(it) }
        addHelp(networkGroup, R.string.settings_background_note)

        val playGroup = section(R.string.playback_title)
        addSwitchRow(playGroup, R.string.settings_auto_play, model.autoPlay) { onToggleAutoPlay?.invoke(it) }
        addActionButtonRow(playGroup, R.string.settings_holiday, R.string.common_settings) { onHoliday?.invoke() }

        val weatherGroup = section(R.string.weather_title, expandedByDefault = false)
        addSwitchRow(weatherGroup, R.string.weather_enable, model.weatherEnabledDefault) {
            onToggleWeatherDefault?.invoke(it)
        }
        addActionRow(weatherGroup, R.string.weather_choose_city) { onWeatherChooseCity?.invoke() }
        model.weatherPlaceName?.let { addTextValue(weatherGroup, it) }
        model.weatherObservedText?.let { addTextValue(weatherGroup, it) }
        model.weatherAttributionText?.let { addTextValue(weatherGroup, it) }
        addActionRow(weatherGroup, R.string.common_retry) { onWeatherManualRefresh?.invoke() }
        weatherError = TextView(context).apply {
            text = resources.getString(R.string.weather_unavailable)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_status_error))
            visibility = View.GONE
        }
        weatherGroup.addView(
            weatherError,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addText(weatherGroup, R.string.weather_units, R.dimen.pp_text_body, R.color.pp_text_primary, R.dimen.pp_space_8)
        addOptionRow(
            weatherGroup,
            listOf(
                R.string.settings_appearance_system,
                R.string.weather_unit_celsius,
                R.string.weather_unit_fahrenheit,
            ),
            model.weatherUnitsIndex,
        ) { onWeatherUnits?.invoke(it) }

        val privacyGroup = section(R.string.settings_privacy)
        addHelp(privacyGroup, R.string.settings_privacy_summary)
        addSwitchRow(privacyGroup, R.string.settings_share_statistics, model.shareStatistics) {
            onToggleShareStatistics?.invoke(it)
        }

        val diagnosticsGroup = section(R.string.settings_diagnostics)
        addHelp(diagnosticsGroup, R.string.settings_diagnostics_help)
        addActionRow(diagnosticsGroup, R.string.settings_export_diagnostics) { onExportDiagnostics?.invoke() }

        val dangerGroup = section(R.string.settings_danger)
        addHelp(dangerGroup, R.string.delete_clear_all_body)
        addActionRow(dangerGroup, R.string.delete_clear_all_title, destructive = true) { onClearAll?.invoke() }

        val aboutGroup = section(R.string.settings_about, collapsible = false)
        addValueRow(
            aboutGroup,
            R.string.settings_version_label,
            model.versionName,
        )
        addHelp(aboutGroup, R.string.settings_license_notice)
        addActionRow(aboutGroup, R.string.settings_licenses) { onLicenses?.invoke() }
        addActionRow(aboutGroup, R.string.settings_update) { onUpdate?.invoke() }
    }

    private fun formatBytes(bytes: Long): String {
        val res = resources
        val megabytes = bytes / BYTES_PER_MB
        return if (megabytes >= MB_PER_GB) {
            res.getString(R.string.format_file_size_gb, (megabytes / MB_PER_GB).toInt())
        } else {
            res.getString(R.string.format_file_size_mb, megabytes.coerceAtLeast(1L).toInt())
        }
    }

    private fun section(
        titleRes: Int,
        expandedByDefault: Boolean = false,
        collapsible: Boolean = true,
    ): LinearLayout = buildSection(titleRes, expandedByDefault, collapsible).second

    private fun addText(
        parent: LinearLayout,
        textRes: Int,
        sizeRes: Int,
        colorRes: Int,
        marginRes: Int,
        heading: Boolean = false,
    ) {
        val res = resources
        val view = TextView(context).apply {
            text = res.getString(textRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(sizeRes))
            setTextColor(context.getColor(colorRes))
            if (heading) setAccessibilityHeading(true)
        }
        parent.addView(
            view,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (marginRes != 0) topMargin = res.getDimensionPixelSize(marginRes)
            },
        )
    }

    private fun addHelp(parent: LinearLayout, textRes: Int) {
        addText(parent, textRes, R.dimen.pp_text_meta, R.color.pp_text_secondary, R.dimen.pp_space_4)
    }

    private fun addTextValue(parent: LinearLayout, value: String) {
        val res = resources
        val view = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            gravity = Gravity.CENTER_VERTICAL
        }
        parent.addView(view, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun addValueRow(parent: LinearLayout, labelRes: Int, value: String) {        val res = resources
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
        }
        val label = TextView(context).apply {
            text = res.getString(labelRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
        }
        val valueView = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            gravity = Gravity.END
        }
        row.addView(label, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(valueView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        parent.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun addActionRow(parent: LinearLayout, textRes: Int, destructive: Boolean = false, onClick: () -> Unit) {
        val res = resources
        val row = TextView(context).apply {
            text = res.getString(textRes)
            contentDescription = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(if (destructive) R.color.pp_status_error else R.color.pp_accent_action))
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        parent.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private fun addActionButtonRow(parent: LinearLayout, labelRes: Int, buttonRes: Int, onClick: () -> Unit) {
        val res = resources
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
        }
        val label = TextView(context).apply {
            text = res.getString(labelRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            gravity = Gravity.CENTER_VERTICAL
        }
        val button = dev.photopilot.scaffold.ui.widget.secondaryButton(context, buttonRes).apply {
            setOnClickListener { onClick() }
        }
        row.addView(label, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            button,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
        parent.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }


    private fun addSwitchRow(parent: LinearLayout, textRes: Int, checked: Boolean, onToggle: (Boolean) -> Unit) {
        val res = resources
        val view = Switch(context).apply {
            text = res.getString(textRes)
            isChecked = checked
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
        parent.addView(view, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun addOptionRow(parent: LinearLayout, labelResList: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
        val res = resources
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val slots = mutableListOf<LinearLayout>()
        val indicators = mutableListOf<View>()
        labelResList.forEachIndexed { index, labelRes ->
            val label = TextView(context).apply {
                text = res.getString(labelRes)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                setTextColor(context.getColorStateList(R.color.nav_item_tint))
                gravity = Gravity.CENTER
                minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
                isDuplicateParentStateEnabled = true
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val indicator = View(context).apply {
                background = GradientDrawable().apply {
                    setColor(context.getColor(R.color.pp_accent_action))
                }
                visibility = View.INVISIBLE
            }
            val slot = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                contentDescription = label.text
                addView(label, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(indicator, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, indicatorHeightPx()))
                setOnClickListener {
                    slots.forEachIndexed { i, s ->
                        s.isSelected = i == index
                        indicators[i].visibility = if (i == index) View.VISIBLE else View.INVISIBLE
                    }
                    onSelect(index)
                }
            }
            slot.isSelected = index == selected
            indicator.visibility = if (index == selected) View.VISIBLE else View.INVISIBLE
            slots += slot
            indicators += indicator
            row.addView(
                slot,
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = res.getDimensionPixelSize(R.dimen.pp_space_4)
                },
            )
        }
        parent.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun indicatorHeightPx(): Int =
        (INDICATOR_HEIGHT_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun sectionCollapsed(titleRes: Int, expandedByDefault: Boolean): Boolean {
        val key = sectionStateKey(titleRes)
        val prefs = context.getSharedPreferences(UI_STATE_PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(key)) {
            prefs.getBoolean(key, false)
        } else {
            !expandedByDefault
        }
    }

    private fun sectionStateKey(titleRes: Int): String =
        "settings.section.collapsed." + resources.getResourceEntryName(titleRes).lowercase()

    private fun buildSection(
        titleRes: Int,
        expandedByDefault: Boolean,
        collapsible: Boolean = true,
    ): Pair<TextView, LinearLayout> {
        val res = resources
        val collapsed = collapsible && sectionCollapsed(titleRes, expandedByDefault)
        val header = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = collapsible
            isFocusable = collapsible
            contentDescription = if (collapsible) {
                res.getString(titleRes) + ", " + res.getString(if (collapsed) R.string.a11y_collapsed else R.string.a11y_expanded)
            } else res.getString(titleRes)
            text = if (collapsible) sectionTitle(titleRes, !collapsed) else res.getString(titleRes)
        }
        column.addView(
            header,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_24)
            },
        )
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = res.getDimensionPixelSize(R.dimen.pp_space_16)
            setPadding(pad, res.getDimensionPixelSize(R.dimen.pp_space_12), pad, res.getDimensionPixelSize(R.dimen.pp_space_12))
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.pp_surface_panel))
                cornerRadius = res.getDimension(R.dimen.pp_radius_group)
            }
            visibility = if (collapsed) View.GONE else View.VISIBLE
        }
        column.addView(
            body,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
        if (collapsible) {
            header.setOnClickListener {
                val nowCollapsed = body.visibility != View.GONE
                body.visibility = if (nowCollapsed) View.GONE else View.VISIBLE
                header.text = sectionTitle(titleRes, !nowCollapsed)
                header.contentDescription = res.getString(titleRes) + ", " + res.getString(
                    if (nowCollapsed) R.string.a11y_collapsed else R.string.a11y_expanded,
                )
                context.getSharedPreferences(UI_STATE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(sectionStateKey(titleRes), nowCollapsed)
                    .apply()
            }
        }
        return header to body
    }

    private fun sectionTitle(titleRes: Int, expanded: Boolean): String =
        resources.getString(titleRes) + "  " + if (expanded) CHEVRON_EXPANDED else CHEVRON_COLLAPSED

    private companion object {
        val APPEARANCE_VALUES = listOf("system", "light", "dark")
        val LANGUAGE_VALUES = listOf("system", "zh", "en")
        const val BYTES_PER_MB = 1_048_576L
        const val MB_PER_GB = 1_024L
        const val INDICATOR_HEIGHT_DP = 2f
        const val UI_STATE_PREFS = "ui_state"
        const val CHEVRON_EXPANDED = "⌄"
        const val CHEVRON_COLLAPSED = "›"
    }
}
