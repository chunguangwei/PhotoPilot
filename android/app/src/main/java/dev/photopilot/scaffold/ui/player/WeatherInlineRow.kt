package dev.photopilot.scaffold.ui.player

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.data.logic.WeatherInlineLayout
import kotlin.math.ceil
import kotlin.math.max

class WeatherInlineRow(context: Context, sizeRes: Int, private val centered: Boolean = false) : ViewGroup(context) {
    private val place = label(sizeRes).apply { textDirection = View.TEXT_DIRECTION_FIRST_STRONG }
    private val weather = label(sizeRes).apply { textDirection = View.TEXT_DIRECTION_LTR }
    private var widths = WeatherInlineLayout.Widths(0, 0, 0)
    private var rowBaseline = 0

    constructor(context: Context) : this(context, R.dimen.pp_text_meta)

    init {
        addView(place)
        addView(weather)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isScreenReaderFocusable = true
        visibility = GONE
    }

    fun render(placeText: String?, weatherText: String?, accessibility: String?) {
        place.text = placeText.orEmpty()
        place.visibility = if (placeText.isNullOrBlank()) GONE else VISIBLE
        weather.text = weatherText.orEmpty()
        weather.visibility = if (weatherText.isNullOrBlank()) GONE else VISIBLE
        contentDescription = accessibility ?: listOfNotNull(placeText, weatherText).joinToString(", ")
        visibility = if (weatherText.isNullOrBlank()) GONE else VISIBLE
        requestLayout()
    }

    private fun label(sizeRes: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(sizeRes))
        setTextColor(context.getColor(R.color.pp_text_on_photo))
        setShadowLayer(8f, 0f, 2f, 0x8C000000.toInt())
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unconstrained = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        place.measure(unconstrained, unconstrained)
        weather.measure(unconstrained, unconstrained)
        val placeWidth = if (place.visibility == GONE) 0 else place.measuredWidth
        val weatherWidth = if (weather.visibility == GONE) 0 else weather.measuredWidth
        val gap = resources.getDimensionPixelSize(R.dimen.pp_space_8)
        val desiredWidth = placeWidth + weatherWidth + if (placeWidth > 0 && weatherWidth > 0) gap else 0
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) desiredWidth
            else MeasureSpec.getSize(widthMeasureSpec)
        val prefix = weather.text.toString().substringBefore(" · ").trimEnd()
        val minimumWeather = ceil(weather.paint.measureText("$prefix…")).toInt() +
            weather.compoundPaddingLeft + weather.compoundPaddingRight
        widths = WeatherInlineLayout.allocate(available, placeWidth, weatherWidth, minimumWeather, gap)
        place.measure(MeasureSpec.makeMeasureSpec(widths.place, MeasureSpec.EXACTLY), unconstrained)
        weather.measure(MeasureSpec.makeMeasureSpec(widths.weather, MeasureSpec.EXACTLY), unconstrained)
        rowBaseline = max(if (place.visibility == GONE) 0 else place.baseline,
            if (weather.visibility == GONE) 0 else weather.baseline)
        val descent = max(if (place.visibility == GONE) 0 else place.measuredHeight - place.baseline,
            if (weather.visibility == GONE) 0 else weather.measuredHeight - weather.baseline)
        setMeasuredDimension(resolveSize(widths.place + widths.weather + widths.gap, widthMeasureSpec),
            resolveSize(rowBaseline + descent, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val used = widths.place + widths.weather + widths.gap
        val offset = if (centered) (width - used) / 2 else if (layoutDirection == LAYOUT_DIRECTION_RTL) width - used else 0
        fun position(child: View, start: Int, childWidth: Int) {
            val y = if (child.visibility == GONE) 0 else rowBaseline - child.baseline
            child.layout(start, y, start + childWidth, y + child.measuredHeight)
        }
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            position(weather, offset, widths.weather)
            position(place, offset + widths.weather + widths.gap, widths.place)
        } else {
            position(place, offset, widths.place)
            position(weather, offset + widths.place + widths.gap, widths.weather)
        }
    }
}
