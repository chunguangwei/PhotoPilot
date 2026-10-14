package dev.photopilot.scaffold.ui.frames

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.FrameEntity
import dev.photopilot.scaffold.ui.library.ThumbnailLoader
import dev.photopilot.scaffold.ui.widget.BrandMarkView
import java.io.File
import java.lang.ref.WeakReference

class FramesView(
    context: Context,
    originalsDir: File,
) : ScrollView(context) {

    data class CardModel(
        val frame: FrameEntity,
        val cover: AssetEntity?,
        val playable: Int,
        val pending: Int,
    )

    var onOpen: ((FrameEntity) -> Unit)? = null
    var onCreate: (() -> Unit)? = null
    var onRename: ((FrameEntity) -> Unit)? = null
    var onDuplicate: ((FrameEntity) -> Unit)? = null
    var onDelete: ((FrameEntity) -> Unit)? = null
    var onPlay: ((FrameEntity) -> Unit)? = null
    var onPlaybackSettings: ((FrameEntity) -> Unit)? = null

    private val contentColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val cardsColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val headerRow = LinearLayout(context)
    private val loader = ThumbnailLoader(originalsDir, COVER_CACHE_ENTRIES)
    private var cardViews = emptyList<View>()
    private var columnCount = 0
    private var currentCards = emptyList<CardModel>()
    private var currentLibraryEmpty = true
    private var headerHorizontal = false

    init {
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        addView(contentColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(cards: List<CardModel>, libraryEmpty: Boolean) {
        currentCards = cards.toList()
        currentLibraryEmpty = libraryEmpty
        val res = resources
        contentColumn.removeAllViews()
        headerRow.removeAllViews()
        val header = TextView(context).apply {
            text = res.getString(R.string.frames_title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_title))
            setTextColor(context.getColor(R.color.pp_text_primary))
            setAccessibilityHeading(true)
        }
        headerRow.addView(header)
        if (cards.isNotEmpty()) {
            headerRow.addView(actionButton(R.string.frames_create, false).apply {
                setOnClickListener { onCreate?.invoke() }
            })
        }
        contentColumn.addView(headerRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (cards.isEmpty()) {
            addText(contentColumn, R.string.frames_empty_title, R.dimen.pp_text_heading, R.color.pp_text_primary, R.dimen.pp_space_24)
            addText(contentColumn, R.string.frames_empty_body, R.dimen.pp_text_body, R.color.pp_text_secondary, R.dimen.pp_space_8)
        }
        if (libraryEmpty) {
            addText(contentColumn, R.string.frames_curate_hint_title, R.dimen.pp_text_body, R.color.pp_text_primary, R.dimen.pp_space_24)
            addText(contentColumn, R.string.frames_curate_hint_body, R.dimen.pp_text_body, R.color.pp_text_secondary, R.dimen.pp_space_8)
        }
        if (cards.isEmpty()) {
            contentColumn.addView(
                actionButton(R.string.frames_create, true).apply { setOnClickListener { onCreate?.invoke() } },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = res.getDimensionPixelSize(R.dimen.pp_space_24)
                },
            )
        }
        cardsColumn.removeAllViews()
        cardViews = cards.map { cardView(it) }
        contentColumn.addView(cardsColumn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        columnCount = 0
        updateLayout(width)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateLayout(MeasureSpec.getSize(widthMeasureSpec))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        render(currentCards, currentLibraryEmpty)
    }

    private fun updateLayout(widthPx: Int) {
        if (widthPx <= 0) return
        val res = resources
        val widthDp = widthPx / res.displayMetrics.density
        val inset = res.getDimensionPixelSize(
            if (widthDp < 600f) R.dimen.pp_content_inset_compact else R.dimen.pp_content_inset,
        )
        val vertical = res.getDimensionPixelSize(R.dimen.pp_space_24)
        if (contentColumn.paddingLeft != inset || contentColumn.paddingTop != vertical) {
            contentColumn.setPadding(inset, vertical, inset, vertical)
        }
        val availableDp = (widthPx - inset * 2) / res.displayMetrics.density
        val largeText = res.configuration.fontScale >= LARGE_TEXT_SCALE
        val horizontal = availableDp >= 480f && !largeText
        if (headerHorizontal != horizontal || columnCount == 0) {
            headerHorizontal = horizontal
            headerRow.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            headerRow.gravity = Gravity.CENTER_VERTICAL
            for (index in 0 until headerRow.childCount) {
                headerRow.getChildAt(index).layoutParams = LinearLayout.LayoutParams(
                    if (horizontal && index == 0) 0 else if (horizontal) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT,
                    if (horizontal && index == 0) 1f else 0f,
                ).apply {
                    if (index > 0) {
                        if (horizontal) marginStart = res.getDimensionPixelSize(R.dimen.pp_space_16)
                        else topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
                    }
                }
            }
        }
        val columns = if (availableDp >= 720f && !largeText) 2 else 1
        if (columns == columnCount) return
        columnCount = columns
        cardViews.forEach { (it.parent as? LinearLayout)?.removeView(it) }
        cardsColumn.removeAllViews()
        val gap = res.getDimensionPixelSize(R.dimen.pp_space_16)
        cardViews.chunked(columns).forEach { group ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEachIndexed { index, card ->
                row.addView(card, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = gap
                })
            }
            if (columns == 2 && group.size == 1) {
                row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = gap })
            }
            cardsColumn.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = gap
            })
        }
    }

    private fun cardView(model: CardModel): View {
        val res = resources
        val radius = res.getDimension(R.dimen.pp_radius_group)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(context.getColor(R.color.pp_surface_panel))
                cornerRadius = radius
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { onOpen?.invoke(model.frame) }
            isFocusable = false
        }
        card.addView(CoverView(context, model.cover, loader), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = res.getDimensionPixelSize(R.dimen.pp_space_16)
            setPadding(pad, pad, pad, pad)
        }
        val name = TextView(context).apply {
            text = model.frame.name
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_heading))
            setTextColor(context.getColor(R.color.pp_text_primary))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        details.addView(name, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        fun addCount(textValue: String, margin: Int) {
            details.addView(TextView(context).apply {
                text = textValue
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_meta))
                setTextColor(context.getColor(R.color.pp_text_secondary))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(margin)
            })
        }
        addCount(res.getQuantityString(R.plurals.frames_playable_count, model.playable, model.playable), R.dimen.pp_space_8)
        if (model.pending > 0) {
            addCount(res.getQuantityString(R.plurals.frames_pending_count, model.pending, model.pending), R.dimen.pp_space_4)
        }
        val play = actionButton(R.string.frames_play, true).apply {
            setOnClickListener { onPlay?.invoke(model.frame) }
            isEnabled = model.playable > 0
            alpha = if (isEnabled) 1f else DISABLED_ALPHA
        }
        val edit = actionButton(R.string.common_edit, false).apply {
            setOnClickListener { onOpen?.invoke(model.frame) }
        }
        val target = res.getDimensionPixelSize(R.dimen.pp_touch_target)
        val more = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_more)
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.pp_accent_action))
            contentDescription = res.getString(R.string.player_more)
            minimumWidth = target
            minimumHeight = target
            background = actionBackground(false)
            setOnClickListener { showCardMenu(it, model.frame) }
        }
        val largeText = res.configuration.fontScale >= LARGE_TEXT_SCALE
        val actions = LinearLayout(context).apply {
            orientation = if (largeText) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(play, edit, more).forEachIndexed { index, action ->
            actions.addView(action, LinearLayout.LayoutParams(
                if (largeText) LayoutParams.MATCH_PARENT else if (action === more) target else 0,
                if (action === more) target else LayoutParams.WRAP_CONTENT,
                if (!largeText && action !== more) 1f else 0f,
            ).apply {
                if (index > 0) {
                    if (largeText) topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
                    else marginStart = res.getDimensionPixelSize(R.dimen.pp_space_8)
                }
            })
        }
        details.addView(actions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = res.getDimensionPixelSize(R.dimen.pp_space_16)
        })
        card.addView(details, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        card.setOnLongClickListener {
            showCardMenu(card, model.frame)
            true
        }
        return card
    }

    private fun actionButton(textRes: Int, primary: Boolean): Button = Button(context).apply {
        text = resources.getString(textRes)
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.pp_text_body))
        setTextColor(context.getColor(if (primary) R.color.pp_surface_canvas else R.color.pp_accent_action))
        gravity = Gravity.CENTER
        val target = resources.getDimensionPixelSize(R.dimen.pp_touch_target)
        minWidth = target
        minimumWidth = target
        minHeight = target
        minimumHeight = target
        setSingleLine(false)
        ellipsize = null
        val pad = resources.getDimensionPixelSize(R.dimen.pp_space_8)
        setPadding(pad, pad, pad, pad)
        background = actionBackground(primary)
        stateListAnimator = null
    }

    private fun actionBackground(primary: Boolean): RippleDrawable {
        val shape = GradientDrawable().apply {
            setColor(context.getColor(if (primary) R.color.pp_text_primary else R.color.pp_surface_panel))
            cornerRadius = resources.getDimension(R.dimen.pp_radius_group)
        }
        return RippleDrawable(ColorStateList.valueOf(context.getColor(R.color.pp_separator)), shape, null)
    }

    private fun showCardMenu(anchor: View, frame: FrameEntity) {
        val menu = android.widget.PopupMenu(context, anchor)
        menu.menu.add(0, MENU_RENAME, 0, resources.getString(R.string.common_rename))
        menu.menu.add(0, MENU_DUPLICATE, 1, resources.getString(R.string.frames_duplicate))
        menu.menu.add(0, MENU_SETTINGS, 2, resources.getString(R.string.frames_playback_settings))
        menu.menu.add(0, MENU_DELETE, 3, resources.getString(R.string.frames_delete))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RENAME -> onRename?.invoke(frame)
                MENU_DUPLICATE -> onDuplicate?.invoke(frame)
                MENU_SETTINGS -> onPlaybackSettings?.invoke(frame)
                MENU_DELETE -> onDelete?.invoke(frame)
            }
            true
        }
        menu.show()
    }

    private fun addText(parent: LinearLayout, textRes: Int, sizeRes: Int, colorRes: Int, marginRes: Int) {
        val res = resources
        val view = TextView(context).apply {
            text = res.getString(textRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(sizeRes))
            setTextColor(context.getColor(colorRes))
        }
        parent.addView(
            view,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(marginRes)
            },
        )
    }

    private class CoverView(context: Context, private val asset: AssetEntity?, private val loader: ThumbnailLoader) : FrameLayout(context) {
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        private val placeholder = BrandMarkView(context)
        private var requestedPixels = 0

        init {
            setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val side = resources.getDimensionPixelSize(R.dimen.pp_space_48) * 2
            addView(placeholder, LayoutParams(side, side, Gravity.CENTER))
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(width * 10 / 16, MeasureSpec.EXACTLY),
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            val cover = asset ?: return
            if (width <= 0) return
            val pixels = (((width + 127) / 128) * 128).coerceAtMost(1600)
            if (pixels <= requestedPixels) return
            requestedPixels = pixels
            val target = WeakReference(this)
            loader.load(cover, pixels, ThumbnailLoader.cacheKey(cover.id, pixels), object : ThumbnailLoader.Callback {
                override fun onLoaded(key: String, bitmap: android.graphics.Bitmap?) {
                    val view = target.get() ?: return
                    if (view.requestedPixels != pixels) return
                    view.image.setImageBitmap(bitmap)
                    view.placeholder.visibility = if (bitmap == null) VISIBLE else GONE
                }
            })
        }
    }

    private companion object {
        const val MENU_RENAME = 1
        const val MENU_DUPLICATE = 2
        const val MENU_DELETE = 3
        const val MENU_SETTINGS = 4
        const val COVER_CACHE_ENTRIES = 8
        const val DISABLED_ALPHA = 0.5f
        const val LARGE_TEXT_SCALE = 1.5f
    }
}
