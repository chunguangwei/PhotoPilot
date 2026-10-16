package dev.photopilot.scaffold.ui.weather

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.data.net.PlaceResult
import dev.photopilot.scaffold.ui.widget.primaryButton
import dev.photopilot.scaffold.ui.widget.screenHeader

class WeatherCitySearchView(context: Context) : LinearLayout(context) {

    var onBack: (() -> Unit)? = null
    var onSearch: ((String) -> Unit)? = null
    var onSelect: ((PlaceResult) -> Unit)? = null
    var onSelectCurrentLocation: (() -> Unit)? = null

    private val statusLine: TextView
    private val resultsColumn: LinearLayout
    private val queryInput: EditText

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        isClickable = true
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)
        val headerRow = screenHeader(context, R.string.weather_choose_city) { onBack?.invoke() }
        headerRow.setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_8), inset, 0)
        addView(headerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(context)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_8), inset, inset)
        }

        queryInput = EditText(context).apply {
            hint = res.getString(R.string.weather_search_city)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
            setHintTextColor(context.getColor(R.color.pp_text_secondary))
            setSingleLine(true)
        }
        column.addView(
            queryInput,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        val searchButton = primaryButton(context, R.string.common_search).apply {
            setOnClickListener {
                val query = queryInput.text.toString().trim()
                if (query.isNotEmpty()) onSearch?.invoke(query)
            }
        }
        column.addView(
            searchButton,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
        val locationRow = TextView(context).apply {
            text = res.getString(R.string.weather_use_current_location)
            contentDescription = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_accent_action))
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { onSelectCurrentLocation?.invoke() }
        }
        column.addView(locationRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val locationHelp = TextView(context).apply {
            text = res.getString(R.string.weather_location_help)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        column.addView(locationHelp, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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
        resultsColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(resultsColumn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroll.addView(column)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun showSearching() {
        statusLine.visibility = View.VISIBLE
        statusLine.text = resources.getString(R.string.common_loading)
    }

    fun showError(textRes: Int) {
        statusLine.visibility = View.VISIBLE
        statusLine.setTextColor(context.getColor(R.color.pp_status_error))
        statusLine.text = resources.getString(textRes)
    }

    fun showResults(results: List<PlaceResult>) {
        val res = resources
        statusLine.visibility = View.GONE
        resultsColumn.removeAllViews()
        if (results.isEmpty()) {
            showError(R.string.weather_unavailable)
            return
        }
        results.forEach { place ->
            val row = LinearLayout(context).apply {
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
                contentDescription = "${place.name}, ${place.region}"
                setOnClickListener { onSelect?.invoke(place) }
                val name = TextView(context).apply {
                    text = place.name
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                    setTextColor(context.getColor(R.color.pp_text_primary))
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val region = TextView(context).apply {
                    text = place.region
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
                    setTextColor(context.getColor(R.color.pp_text_secondary))
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                addView(name, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(region, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            resultsColumn.addView(
                row,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
                },
            )
        }
    }

    fun currentQuery(): String = queryInput.text.toString().trim()
}
