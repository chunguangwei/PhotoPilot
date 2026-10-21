package dev.photopilot.scaffold

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridView
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.AssetFormat
import dev.photopilot.scaffold.data.db.AssetReadiness
import dev.photopilot.scaffold.data.db.CapturedAtSource
import dev.photopilot.scaffold.data.db.CollectionEntity
import dev.photopilot.scaffold.data.db.FrameOrderMode
import dev.photopilot.scaffold.data.db.OrientationClass
import dev.photopilot.scaffold.ui.frames.FrameEditView
import java.io.File

class FrameSelectionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var status = -1
        var activity: Activity? = null
        var directory: File? = null
        try {
            check(targetContext.packageName != "dev.photopilot.scaffold") { "Requires an isolated test installation" }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val host = checkNotNull(activity)
            val photos = (0 until 6).map { index ->
                AssetEntity("synthetic-$index", "sha256:" + index.toString().repeat(64), 1, AssetFormat.PNG,
                    100, 100, 1, OrientationClass.SQUARE, null, CapturedAtSource.UNKNOWN, 1,
                    "synthetic-$index.png", null, AssetReadiness.READY, null, false, null,
                    deletedAt = null, createdAt = 1, updatedAt = 1)
            }
            directory = File(targetContext.cacheDir, "selection-test-${SystemClock.uptimeMillis()}").apply { mkdirs() }
            lateinit var view: FrameEditView
            onMain {
                view = FrameEditView(host, checkNotNull(directory))
                view.dataSource = object : FrameEditView.DataSource {
                    override fun fetchPhotos(limit: Int, offset: Int, callback: (List<AssetEntity>) -> Unit) = callback(photos.drop(offset).take(limit))
                    override fun fetchCollections(callback: (List<Pair<CollectionEntity, Long>>) -> Unit) = callback(emptyList())
                    override fun fetchAssetsByIds(refs: List<String>, callback: (Map<String, AssetEntity>) -> Unit) = callback(photos.filter { it.id in refs }.associateBy { it.id })
                }
                view.bind(FrameEditView.Model("Synthetic", FrameOrderMode.CAPTURED_ASC, emptySet(), emptySet(), emptyMap(), false))
                host.setContentView(view)
            }
            waitForIdleSync()
            for (index in photos.indices) {
                val bounds = Rect()
                onMain {
                    val grid = descendants(view).filterIsInstance<GridView>().single()
                    check(grid.childCount == 6) { "Expected six visible photos; found ${grid.childCount}" }
                    val cell = grid.getChildAt(index)
                    check(cell.isAttachedToWindow) { "Photo $index was not attached to its window" }
                    check(!cell.isTemporarilyDetached) { "Photo $index remains temporarily detached after layout" }
                    check(cell.getGlobalVisibleRect(bounds) && !bounds.isEmpty) { "Photo $index is not visible" }
                }
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    check(uiAutomation.injectInputEvent(event, true))
                    event.recycle()
                }
                waitForIdleSync()
                onMain { check(photos[index].id in view.currentState().selectedAssetIds) { "Touch did not select photo $index" } }
            }
            onMain { check(view.currentState().selectedAssetIds == photos.map { it.id }.toSet()) }
            result.putString("stream", "All six photos, including the first, accept real touch selection\n")
        } catch (error: Throwable) {
            result.putString("stream", "FAILED: ${error.stackTraceToString()}\n")
            status = 0
        } finally {
            activity?.let { host -> onMain { host.finish() } }
            directory?.deleteRecursively()
        }
        finish(status, result)
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
