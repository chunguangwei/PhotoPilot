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
import kotlinx.coroutines.launch
import java.io.File
import java.lang.ref.WeakReference

internal fun remotePhotoCount(count: Int, chinese: Boolean): String =
    if (chinese) "$count 张照片" else if (count == 1) "1 photo" else "$count photos"

class FramesView(
    context: Context,
    originalsDir: File,
) : ScrollView(context) {

    data class CardModel(
        val frame: FrameEntity,
        val cover: AssetEntity?,
        val playable: Int,
        val pending: Int,
        val remoteCover: dev.photopilot.scaffold.data.remote.PlaybackItem? = null,
        val hasNasSource: Boolean = true,
        val hasDeviceReferences: Boolean = false,
        val loading: Boolean = false,
        val aiPaused: Boolean = false,
        val aiProgress: String? = null,
    )

    var remoteMedia: dev.photopilot.scaffold.data.remote.RemoteMediaRepository? = null
    var onOpen: ((FrameEntity) -> Unit)? = null
    var onCreateAI: (() -> Unit)? = null
    var onCreate: (() -> Unit)? = null
    var onAIRules: ((FrameEntity) -> Unit)? = null
    var onContinueAI: ((FrameEntity) -> Unit)? = null
    var onRetry: (() -> Unit)? = null
    var onRename: ((FrameEntity) -> Unit)? = null
    var onDuplicate: ((FrameEntity) -> Unit)? = null
    var onDelete: ((FrameEntity) -> Unit)? = null
    var onPlay: ((FrameEntity) -> Unit)? = null
    var onPlaybackSettings: ((FrameEntity) -> Unit)? = null

    private val contentColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val cardsColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val headerRow = LinearLayout(context)
    private val loader = ThumbnailLoader(originalsDir, COVER_CACHE_ENTRIES)
    private data class CardEntry(val model: CardModel, val view: View, val cover: CoverView?)
    private var cardEntries = emptyList<CardEntry>()
    private var cardViews = emptyList<View>()
    private var columnCount = 0
    private var currentCards = emptyList<CardModel>()
    private var currentLibraryEmpty = true
    private var headerHorizontal = false
    private enum class ContentState { LOADING, READY, FAILED }
    private var contentState = ContentState.LOADING

    init {
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        addView(contentColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        renderContent()
    }

    fun render(cards: List<CardModel>, libraryEmpty: Boolean) {
        currentCards = cards.toList()
        currentLibraryEmpty = libraryEmpty
        contentState = ContentState.READY
        renderContent()
    }

    fun showLoading() {
        if (contentState != ContentState.READY) {
            contentState = ContentState.LOADING
            renderContent()
        }
    }

    fun showLoadingFrames(frames: List<FrameEntity>) {
        val previous = currentCards.associateBy { it.frame.id }
        currentCards = frames.map { frame -> previous[frame.id]?.copy(frame = frame) ?: CardModel(frame, null, 0, 0, loading = true) }
        currentLibraryEmpty = false
        showLoading()
    }

    fun showLoadFailure() {
        contentState = ContentState.FAILED
        renderContent()
    }

    private fun renderContent() {
        val cards = currentCards
        val libraryEmpty = currentLibraryEmpty
        val ready = contentState == ContentState.READY
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
        if (cards.isNotEmpty() || !ready) {
            headerRow.addView(actionButton(R.string.frames_create, false).apply {
                setOnClickListener { showCreateMenu(it) }
            })
        }
        contentColumn.addView(headerRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (!ready) {
            addText(contentColumn, if (contentState == ContentState.LOADING) R.string.common_loading else R.string.frames_load_failed, R.dimen.pp_text_body, R.color.pp_text_secondary, R.dimen.pp_space_16)
            if (contentState == ContentState.LOADING) {
                contentColumn.addView(android.widget.ProgressBar(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO })
            } else {
                contentColumn.addView(actionButton(R.string.common_retry, false).apply { setOnClickListener { onRetry?.invoke() } })
            }
        }
        if (ready && cards.isEmpty()) {
            addText(contentColumn, R.string.frames_empty_title, R.dimen.pp_text_heading, R.color.pp_text_primary, R.dimen.pp_space_24)
            addText(contentColumn, R.string.frames_empty_body, R.dimen.pp_text_body, R.color.pp_text_secondary, R.dimen.pp_space_8)
        }
        if (ready && libraryEmpty) {
            addText(contentColumn, R.string.frames_curate_hint_title, R.dimen.pp_text_body, R.color.pp_text_primary, R.dimen.pp_space_24)
            addText(contentColumn, R.string.frames_curate_hint_body, R.dimen.pp_text_body, R.color.pp_text_secondary, R.dimen.pp_space_8)
        }
        if (ready && cards.isEmpty()) {
            contentColumn.addView(
                actionButton(R.string.frames_create, true).apply { setOnClickListener { showCreateMenu(it) } },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = res.getDimensionPixelSize(R.dimen.pp_space_24)
                },
            )
        }
        cardsColumn.removeAllViews()
        cardEntries = rebuildCardEntries(cards)
        cardViews = cardEntries.map { it.view }
        contentColumn.addView(cardsColumn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        columnCount = 0
        updateLayout(width)
        requestLayout()
    }

    private fun cardKey(model: CardModel): String =
        "${model.frame.id}|${model.frame.name}|${model.cover?.id}|${model.remoteCover?.id}|${model.remoteCover?.remote?.revision}|${model.remoteCover?.sourceEpoch}|${model.playable}|${model.pending}|${model.aiProgress}|${model.aiPaused}|${model.loading}"

    private fun rebuildCardEntries(cards: List<CardModel>): List<CardEntry> {
        val previous = cardEntries.associateBy { cardKey(it.model) }
        return cards.map { model ->
            previous[cardKey(model)]?.let { CardEntry(model, it.view, it.cover) }
                ?: run {
                    val built = cardView(model)
                    val found = findCover(built)
                    CardEntry(model, built, found)
                }
        }
    }

    private fun findCover(card: View): CoverView? {
        val layout = card as? LinearLayout
        if (layout == null) return null
        var found: CoverView? = null
        for (index in 0 until layout.childCount) {
            val child = layout.getChildAt(index)
            if (child is CoverView) {
                found = child
                break
            }
        }
        return found
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        cardEntries.forEach { entry -> entry.cover?.checkVisibleOnScroll() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateLayout(MeasureSpec.getSize(widthMeasureSpec))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        renderContent()
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
        val headerRequired = (0 until headerRow.childCount).sumOf { index ->
            val label = headerRow.getChildAt(index) as TextView
            kotlin.math.ceil(label.paint.measureText(label.text.toString()).toDouble()).toInt() +
                label.compoundPaddingLeft + label.compoundPaddingRight
        } + res.getDimensionPixelSize(R.dimen.pp_space_16)
        val horizontal = headerRequired <= widthPx - inset * 2 && !largeText
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

    private fun showCoverPreview(model: CardModel, image: android.graphics.Bitmap?) {
        val chinese = resources.configuration.locales[0].language == "zh"
        val onDemand = !dev.photopilot.scaffold.data.logic.FramePlaybackSettings.requiresLocalCopies(model.frame.settingsJson)
        val mode = if (onDemand && model.hasNasSource) {
            if (chinese) "按需播放" else "On-demand playback"
        } else {
            if (chinese) "本地播放" else "Local playback"
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(CoverView(context, model.cover, loader, model.remoteCover, remoteMedia).apply { showPreviewImage(image) })
            addView(TextView(context).apply {
                val inset = resources.getDimensionPixelSize(R.dimen.pp_space_16)
                setPadding(inset, inset, inset, inset)
                text = "${remotePhotoCount(model.playable, chinese)} · $mode"
            })
        }
        android.app.AlertDialog.Builder(context)
            .setTitle(model.frame.name)
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton(R.string.common_close, null)
            .setPositiveButton(R.string.common_edit) { _, _ -> onOpen?.invoke(model.frame) }
            .show()
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
            isFocusable = false
        }
        if (!model.loading && model.playable > 0 && (model.cover != null || model.remoteCover != null)) {
            card.addView(CoverView(context, model.cover, loader, model.remoteCover, remoteMedia).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = if (resources.configuration.locales[0].language == "zh") "预览相框封面" else "Preview frame cover"
                setOnClickListener { showCoverPreview(model, previewImage) }
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
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
        val remoteMode = !dev.photopilot.scaffold.data.logic.FramePlaybackSettings.requiresLocalCopies(model.frame.settingsJson)
        val photoCount = when {
            model.loading -> res.getString(R.string.common_loading)
            model.hasDeviceReferences -> if (resources.configuration.locales[0].language == "zh") "${model.playable} 张照片" else "${model.playable} ${if (model.playable == 1) "photo" else "photos"}"
            remoteMode && model.hasNasSource -> remotePhotoCount(model.playable, resources.configuration.locales[0].language == "zh")
            else -> res.getQuantityString(R.plurals.frames_playable_count, model.playable, model.playable)
        }
        addCount(photoCount, R.dimen.pp_space_8)
        model.aiProgress?.let { addCount(it, R.dimen.pp_space_4) }
        if (model.aiProgress != null) {
            details.addView(dev.photopilot.scaffold.ui.widget.secondaryButton(context, R.string.common_edit).apply {
                text = if (resources.configuration.locales[0].language == "zh") "AI 分类规则" else "AI classification rules"
                setOnClickListener { onAIRules?.invoke(model.frame) }
            })
        }
        if (model.aiPaused) {
            details.addView(dev.photopilot.scaffold.ui.widget.secondaryButton(context, R.string.common_continue).apply {
                text = if (resources.configuration.locales[0].language == "zh") "继续 AI 分类" else "Continue AI classification"
                setOnClickListener { onContinueAI?.invoke(model.frame) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8) })
        }
        if (model.pending > 0) {
            addCount(res.getQuantityString(R.plurals.frames_pending_count, model.pending, model.pending), R.dimen.pp_space_4)
        }
        val play = actionButton(R.string.frames_play, true).apply {
            setOnClickListener { onPlay?.invoke(model.frame) }
            isEnabled = !model.loading && model.playable > 0
            alpha = if (isEnabled) 1f else DISABLED_ALPHA
        }
        val target = res.getDimensionPixelSize(R.dimen.pp_touch_target)
        val more = ImageButton(context).apply {
            setImageResource(R.drawable.ic_player_more_24)
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
        listOf(play, more).forEachIndexed { index, action ->
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

    private fun showCreateMenu(anchor: View) {
        val menu = android.widget.PopupMenu(context, anchor)
        menu.menu.add(R.string.frames_create).setOnMenuItemClickListener {
            onCreate?.invoke()
            true
        }
        val aiLabel = if (resources.configuration.locales[0].language == "zh") "AI 创建相框" else "Create AI frame"
        menu.menu.add(aiLabel).setOnMenuItemClickListener {
            onCreateAI?.invoke()
            true
        }
        menu.show()
    }

    private fun showCardMenu(anchor: View, frame: FrameEntity) {
        val menu = android.widget.PopupMenu(context, anchor)
        menu.menu.add(0, MENU_EDIT, 0, resources.getString(R.string.common_edit))
        menu.menu.add(0, MENU_RENAME, 1, resources.getString(R.string.common_rename))
        menu.menu.add(0, MENU_DUPLICATE, 2, resources.getString(R.string.frames_duplicate))
        menu.menu.add(0, MENU_SETTINGS, 3, resources.getString(R.string.frames_playback_settings))
        menu.menu.add(0, MENU_DELETE, 4, resources.getString(R.string.frames_delete))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EDIT -> onOpen?.invoke(frame)
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

    private class CoverView(context: Context, private val asset: AssetEntity?, private val loader: ThumbnailLoader, private val remote: dev.photopilot.scaffold.data.remote.PlaybackItem?, private val media: dev.photopilot.scaffold.data.remote.RemoteMediaRepository?) : FrameLayout(context) {
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val previewImage: android.graphics.Bitmap?
            get() = (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap

        fun showPreviewImage(bitmap: android.graphics.Bitmap?) {
            image.setImageBitmap(bitmap)
            placeholder.visibility = if (bitmap == null) VISIBLE else GONE
        }

        private val placeholder = BrandMarkView(context)
        private val viewportRect = android.graphics.Rect()
        private var requestedPixels = 0
        private var remoteJob: kotlinx.coroutines.Job? = null
        override fun onDetachedFromWindow() {
            remoteJob?.cancel()
            remoteJob = null
            requestedPixels = 0
            remoteFailures = 0
            image.setImageDrawable(null)
            placeholder.visibility = VISIBLE
            super.onDetachedFromWindow()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            post { if (width > 0) resolveOnLayout() }
        }

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
            if (width <= 0) return
            resolveOnLayout()
        }

        fun checkVisibleOnScroll() {
            if (width <= 0) return
            resolveOnLayout()
        }

        private fun resolveOnLayout() {
            if ((asset != null || remote != null) && media != null) {
                resolveRemoteCover()
                return
            }
            val cover = asset ?: return
            val pixels = (((width + 127) / 128) * 128).coerceAtMost(COVER_MAX_PIXELS)
            val cached = loader.peek(ThumbnailLoader.cacheKey(cover.id, pixels))
            if (cached != null) {
                image.setImageBitmap(cached)
                placeholder.visibility = GONE
                return
            }
            if (pixels <= requestedPixels) return
            if (!isInViewport()) return
            requestedPixels = pixels
            placeholder.visibility = VISIBLE
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

        private fun resolveRemoteCover() {
            val repo = media
            val item = remote ?: asset?.let { dev.photopilot.scaffold.data.remote.PlaybackItem(local = it) }
            if (repo == null || item == null) return
            val remoteWidth = (((width + 127) / 128) * 128).coerceAtMost(1280)
            if (requestedPixels > 0) return
            if (!isInViewport()) return
            requestedPixels = width
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
            remoteJob = scope.launch {
                val bitmap = runCatching { repo.coverBitmap(item, remoteWidth) }.getOrNull()
                if (!isAttachedToWindow) return@launch
                if (bitmap == null) {
                    placeholder.visibility = if (image.drawable == null) VISIBLE else GONE
                    remoteFailures++
                    if (remoteFailures <= 5) {
                        kotlinx.coroutines.delay((2000L shl (remoteFailures - 1)).coerceAtMost(30000L))
                        if (isAttachedToWindow) { requestedPixels = 0; resolveRemoteCover() }
                    }
                    return@launch
                }
                remoteFailures = 0
                if (repo.valid(item)) { image.setImageBitmap(bitmap); placeholder.visibility = GONE }
                while (isAttachedToWindow) {
                    kotlinx.coroutines.delay(1000)
                    if (!repo.valid(item)) { image.setImageDrawable(null); placeholder.visibility = VISIBLE; break }
                }
            }
        }

        private var remoteFailures = 0

        private fun isInViewport(): Boolean = getLocalVisibleRect(viewportRect)
    }

    private companion object {
        const val MENU_EDIT = 0
        const val MENU_RENAME = 1
        const val MENU_DUPLICATE = 2
        const val MENU_DELETE = 3
        const val MENU_SETTINGS = 4
        const val COVER_CACHE_ENTRIES = 8
        const val COVER_MAX_PIXELS = 1024
        const val DISABLED_ALPHA = 0.5f
        const val LARGE_TEXT_SCALE = 1.5f
    }
}
