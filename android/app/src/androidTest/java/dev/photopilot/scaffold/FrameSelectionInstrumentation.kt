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
        val trace = mutableListOf<String>()
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
                    override fun fetchPhotos(limit: Int, offset: Int, callback: (List<AssetEntity>) -> Unit) { host.window.decorView.post { callback(photos.drop(offset).take(limit)) } }
                    override fun fetchCollections(callback: (List<Pair<CollectionEntity, Long>>) -> Unit) { host.window.decorView.post { callback(emptyList()) } }
                    override fun fetchAssetsByIds(refs: List<String>, callback: (Map<String, AssetEntity>) -> Unit) { host.window.decorView.post { callback(photos.filter { it.id in refs }.associateBy { it.id }) } }
                }
                view.bind(FrameEditView.Model("Synthetic", FrameOrderMode.CAPTURED_ASC, emptySet(), emptySet(), emptyMap(), false))
                host.setContentView(view)
            }
            waitUntil {
                descendants(view).filterIsInstance<GridView>().singleOrNull()?.let { grid ->
                    grid.adapter.count == photos.size && grid.width > 0 && grid.height > 0 && grid.childCount > 0 && !grid.isLayoutRequested
                } == true
            }
            for (index in photos.indices) {
                val bounds = Rect()
                onMain {
                    val grid = descendants(view).filterIsInstance<GridView>().single()
                    check(grid.adapter.count == photos.size) { "Expected six photos in the adapter; found ${grid.adapter.count}" }
                    grid.setSelection(index)
                }
                waitUntil {
                    val grid = descendants(view).filterIsInstance<GridView>().single()
                    index in grid.firstVisiblePosition..grid.lastVisiblePosition && !grid.isLayoutRequested && grid.getChildAt(index - grid.firstVisiblePosition) != null
                }
                onMain {
                    val grid = descendants(view).filterIsInstance<GridView>().single()
                    trace += "index=$index grid=${grid.width}x${grid.height} count=${grid.adapter.count} visible=${grid.firstVisiblePosition}..${grid.lastVisiblePosition} children=${grid.childCount} font=${host.resources.configuration.fontScale}"
                    val firstVisible = grid.firstVisiblePosition
                    check(index in firstVisible..grid.lastVisiblePosition) { "Photo $index did not become visible after scrolling" }
                    val cell = grid.getChildAt(index - firstVisible)
                    check(cell != null) { "Photo $index did not get a visible child" }
                    check(cell.isAttachedToWindow) { "Photo $index was not attached to its window" }
                    check(!cell.isTemporarilyDetached) { "Photo $index remains temporarily detached after layout" }
                    check(cell.getGlobalVisibleRect(bounds) && !bounds.isEmpty) { "Photo $index is not visible" }
                }
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    onMain { check(host.dispatchTouchEvent(event)) }
                    event.recycle()
                }
                waitUntil { photos[index].id in view.currentState().selectedAssetIds }
            }
            onMain { check(view.currentState().selectedAssetIds == photos.map { it.id }.toSet()) }
            result.putString("stream", "All six photos, including the first, accept View-level dispatched touch selection\n${trace.joinToString("\n")}\n")
        } catch (error: Throwable) {
            result.putString("stream", "FAILED: ${error.stackTraceToString()}\n${trace.joinToString("\n")}\n")
            status = 0
        } finally {
            activity?.let { host -> onMain { host.finish() } }
            directory?.deleteRecursively()
        }
        finish(status, result)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        error("Adapter/layout did not settle in the bounded window")
    }

    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
