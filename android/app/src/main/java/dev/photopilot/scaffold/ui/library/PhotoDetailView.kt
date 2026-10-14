package dev.photopilot.scaffold.ui.library

import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.photopilot.scaffold.R
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.logic.ByteSizeFormat
import dev.photopilot.scaffold.data.logic.RenderGeometry
import dev.photopilot.scaffold.data.render.BitmapRenderer
import dev.photopilot.scaffold.ui.widget.secondaryButton
import java.io.File
import java.util.Date
import java.util.concurrent.Executors

class PhotoDetailView(context: Context) : LinearLayout(context) {

    var onBack: (() -> Unit)? = null
    var onAddToFrame: ((AssetEntity) -> Unit)? = null
    var onRemoveFromFrame: ((AssetEntity) -> Unit)? = null
    var onDeleteFromDevice: ((AssetEntity) -> Unit)? = null
    var onHideGlobally: ((AssetEntity) -> Unit)? = null

    private val imageFrame: FrameLayout
    private val imageView: ImageView
    private val fallbackColumn: LinearLayout
    private val metaColumn: LinearLayout
    private var asset: AssetEntity? = null
    private var imageFile: File? = null
    private var decoded: Bitmap? = null
    private var decodeRequested = false
    private var lastDecodeW = 0
    private var lastDecodeH = 0
    private val executor = Executors.newSingleThreadExecutor()

    init {
        orientation = VERTICAL
        setBackgroundColor(context.getColor(R.color.pp_surface_canvas))
        isClickable = true
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)

        val backRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_8), inset, 0)
        }
        val back = secondaryButton(context, R.string.common_back).apply {
            setOnClickListener { onBack?.invoke() }
        }
        backRow.addView(back, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(backRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        imageFrame = FrameLayout(context)
        imageView = ImageView(context).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        imageFrame.addView(imageView, FrameLayout.LayoutParams(0, 0))
        fallbackColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        imageFrame.addView(
            fallbackColumn,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(imageFrame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val metaScroll = ScrollView(context)
        metaColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(inset, res.getDimensionPixelSize(R.dimen.pp_space_12), inset, inset)
        }
        metaScroll.addView(
            metaColumn,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(metaScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(asset: AssetEntity, imageFile: File, sourceName: String?, framesCount: Long) {
        this.asset = asset
        this.imageFile = imageFile
        decoded = null
        decodeRequested = false
        lastDecodeW = 0
        lastDecodeH = 0
        imageView.setImageDrawable(null)
        fallbackColumn.visibility = View.GONE
        buildMetaRows(asset, sourceName, framesCount)
        decodeAndShow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (decodeRequested && lastDecodeW > 0 && lastDecodeH > 0) {
            val frameW = imageFrame.width
            val frameH = imageFrame.height
            if (frameW > 0 && frameH > 0 &&
                (kotlin.math.abs(frameW - lastDecodeW) * 4 > lastDecodeW ||
                    kotlin.math.abs(frameH - lastDecodeH) * 4 > lastDecodeH)
            ) {
                decodeRequested = false
            }
        }
        decodeAndShow()
        layoutImage()
    }

    private fun decodeAndShow() {
        if (decodeRequested) return
        val current = asset ?: return
        val file = imageFile ?: return
        if (imageFrame.width <= 0 || imageFrame.height <= 0) {
            post { decodeAndShow() }
            return
        }
        decodeRequested = true
        val targetW = imageFrame.width
        val targetH = imageFrame.height
        lastDecodeW = targetW
        lastDecodeH = targetH
        executor.execute {
            val bitmap = if (file.isFile) {
                runCatching {
                    BitmapRenderer.decodeToTarget(
                        file,
                        targetW,
                        targetH,
                        current.exifOrientation.toInt(),
                    )
                }.getOrNull()
            } else {
                null
            }
            post {
                if (asset !== current) return@post
                decoded = bitmap
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    fallbackColumn.visibility = View.GONE
                } else {
                    showUnavailable()
                }
                layoutImage()
            }
        }
    }

    private fun layoutImage() {
        val current = asset ?: return
        if (decoded == null) return
        if (imageFrame.width <= 0 || imageFrame.height <= 0) return
        val orientedW: Long
        val orientedH: Long
        if (current.exifOrientation == 6L || current.exifOrientation == 8L) {
            orientedW = current.pixelHeight
            orientedH = current.pixelWidth
        } else {
            orientedW = current.pixelWidth
            orientedH = current.pixelHeight
        }
        val rect = RenderGeometry.aspectFit(
            orientedW.coerceAtLeast(1L),
            orientedH.coerceAtLeast(1L),
            imageFrame.width.toLong(),
            imageFrame.height.toLong(),
        )
        imageView.layoutParams = FrameLayout.LayoutParams(
            rect.width.toInt().coerceAtLeast(1),
            rect.height.toInt().coerceAtLeast(1),
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun showUnavailable() {
        fallbackColumn.removeAllViews()
        fallbackColumn.visibility = View.VISIBLE
        val res = resources
        val message = TextView(context).apply {
            text = res.getString(R.string.library_detail_unavailable)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_status_error))
        }
        val retry = secondaryButton(context, R.string.common_retry).apply {
            setOnClickListener {
                decodeRequested = false
                decodeAndShow()
            }
        }
        fallbackColumn.addView(
            message,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        fallbackColumn.addView(
            retry,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_12)
            },
        )
    }

    private fun buildMetaRows(asset: AssetEntity, sourceName: String?, framesCount: Long) {
        metaColumn.removeAllViews()
        val res = resources
        sourceName?.let { addMetaRow(R.string.library_detail_source, it) }
        addMetaRow(
            R.string.library_detail_dimensions,
            res.getString(R.string.format_dimensions, asset.pixelWidth, asset.pixelHeight),
        )
        addMetaRow(R.string.library_detail_file_size, formatBytes(asset.byteSize))
        asset.capturedAt?.let {
            addMetaRow(R.string.library_detail_taken_at, formatTime(it))
        }
        addMetaRow(R.string.library_detail_imported_at, formatTime(asset.importedAt))
        addValueRow(
            res.getQuantityString(
                R.plurals.library_detail_in_frames,
                framesCount.toInt(),
                framesCount.toInt(),
            ),
        )
        addActionRow(R.string.library_add_to_frame, destructive = false) {
            onAddToFrame?.invoke(asset)
        }
        if (framesCount > 0L) {
            addActionRow(R.string.library_remove_from_frame, destructive = false) {
                onRemoveFromFrame?.invoke(asset)
            }
        }
        addActionRow(R.string.delete_hide_globally, destructive = true) {
            onHideGlobally?.invoke(asset)
        }
        addActionRow(R.string.delete_from_device_action, destructive = true) {
            onDeleteFromDevice?.invoke(asset)
        }
    }

    private fun addActionRow(textRes: Int, destructive: Boolean, onClick: () -> Unit) {
        val res = resources
        val row = TextView(context).apply {
            text = res.getString(textRes)
            contentDescription = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(
                context.getColor(
                    if (destructive) R.color.pp_status_error else R.color.pp_accent_action,
                ),
            )
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = res.getDimensionPixelSize(R.dimen.pp_touch_target)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        metaColumn.addView(
            row,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
    }

    private fun addValueRow(value: String) {
        val res = resources
        val valueView = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
        }
        metaColumn.addView(
            valueView,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
    }

    private fun addMetaRow(labelRes: Int, value: String) {
        val res = resources
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }
        val label = TextView(context).apply {
            text = res.getString(labelRes)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_secondary))
        }
        val valueView = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
            setTextColor(context.getColor(R.color.pp_text_primary))
        }
        row.addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        row.addView(
            valueView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = res.getDimensionPixelSize(R.dimen.pp_space_16)
            },
        )
        metaColumn.addView(
            row,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = res.getDimensionPixelSize(R.dimen.pp_space_8)
            },
        )
    }

    private fun formatTime(millis: Long): String {
        val date = Date(millis)
        val datePart = DateFormat.getDateFormat(context).format(date)
        val timePart = DateFormat.getTimeFormat(context).format(date)
        return "$datePart $timePart"
    }

    private fun formatBytes(bytes: Long): String {
        val res = resources
        return when (ByteSizeFormat.unitOf(bytes)) {
            ByteSizeFormat.Unit.KB ->
                res.getString(R.string.format_file_size_kb, ByteSizeFormat.valueOf(bytes).toInt())
            ByteSizeFormat.Unit.MB ->
                res.getString(R.string.format_file_size_mb, ByteSizeFormat.valueOf(bytes).toInt())
            ByteSizeFormat.Unit.GB ->
                res.getString(R.string.format_file_size_gb, ByteSizeFormat.valueOf(bytes).toInt())
        }
    }

    private companion object {
    }
}
