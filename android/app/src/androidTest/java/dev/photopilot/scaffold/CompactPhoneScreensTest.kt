package dev.photopilot.scaffold

import android.content.res.Configuration
import android.content.Intent
import android.os.LocaleList
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.ui.library.LibraryView
import dev.photopilot.scaffold.ui.sources.SourcesView
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactPhoneScreensTest {
    @Test fun deviceSourceEntryIsDistinctFromConnectedPhotoLibrary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (language in listOf("en", "zh")) {
                for (font in listOf(1f, 1.5f)) {
                    val context = themedContext(language, font)
                    val sources = SourcesView(context)
                    val library = context.getString(R.string.source_local_photos_title)
                    sources.renderLocalReferences(listOf(SourcesView.LocalReferenceModel("device-library", library, "Ready")))
                    var selected: String? = null
                    sources.onLocalReferenceClick = { selected = it }
                    layout(sources, 320)
                    val labels = descendants(sources).filterIsInstance<TextView>()
                    val add = labels.single { it.text.toString() == context.getString(R.string.source_local_add) }
                    val connected = labels.single { it.text.toString() == library }
                    val addCard = add.parent as View
                    val connectedCard = connected.parent as View
                    assertTrue(addCard !== connectedCard)
                    val first = android.graphics.Rect()
                    val second = android.graphics.Rect()
                    addCard.getDrawingRect(first)
                    connectedCard.getDrawingRect(second)
                    sources.offsetDescendantRectToMyCoords(addCard, first)
                    sources.offsetDescendantRectToMyCoords(connectedCard, second)
                    assertTrue(second.top > first.bottom)
                    assertTrue(connectedCard.performClick())
                    assertEquals("device-library", selected)
                }
            }
        }
    }

    @Test fun nasManagementMenuRetainsDisconnectAndRemoveActions() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        delay(6500)
        lateinit var sources: SourcesView
        val requested = mutableListOf<String>()
        suspend fun choose(label: String) {
            try { withTimeout(10000) {
                var clicked = false
                while (!clicked) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                    var item = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.text.toString() == label }
                    clicked = item?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
                    if (clicked) break
                    val visible = android.graphics.Rect()
                    item?.getBoundsInScreen(visible)
                    if (!visible.isEmpty) {
                        val now = android.os.SystemClock.uptimeMillis()
                        instrumentation.uiAutomation.injectInputEvent(android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, visible.exactCenterX(), visible.exactCenterY(), 0), true)
                        clicked = instrumentation.uiAutomation.injectInputEvent(android.view.MotionEvent.obtain(now, now + 50, android.view.MotionEvent.ACTION_UP, visible.exactCenterX(), visible.exactCenterY(), 0), true)
                        if (clicked) break
                    }
                    while (item != null && !item.isClickable && item.parent != null) item = item.parent
                    clicked = item?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
                    if (!clicked) delay(100)
                }
            } } catch (failure: kotlinx.coroutines.TimeoutCancellationException) {
                fun labels(node: android.view.accessibility.AccessibilityNodeInfo): List<String> =
                    listOfNotNull(node.text?.toString()) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::labels).orEmpty() }
                throw AssertionError("Missing NAS menu item $label; visible=${instrumentation.uiAutomation.rootInActiveWindow?.let(::labels)}", failure)
            }
            instrumentation.waitForIdleSync()
        }
        try {
            instrumentation.runOnMainSync {
                sources = SourcesView(themedContext("en", 1f))
                sources.onNasDisconnect = { requested.add(it) }
                sources.renderNasSources(listOf(SourcesView.NasSourceModel("test-menu", "Test NAS", "Ready", catalogOnly = true)))
                activity.setContentView(sources)
            }
            instrumentation.waitForIdleSync()
            for ((disconnected, label) in listOf(false to R.string.sources_disconnect, true to R.string.sources_remove_configuration)) {
                instrumentation.runOnMainSync {
                    sources.renderNasSources(listOf(SourcesView.NasSourceModel("test-menu", "Test NAS", "Ready", catalogOnly = true, isDisconnected = disconnected)))
                }
                instrumentation.waitForIdleSync()
                lateinit var more: TextView
                instrumentation.runOnMainSync {
                    more = descendants(sources).filterIsInstance<TextView>().single { it.contentDescription?.toString() == sources.context.getString(R.string.player_more) + ", Test NAS" }
                    more.requestRectangleOnScreen(android.graphics.Rect(0, 0, more.width, more.height), true)
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertTrue("NAS menu anchor must be on screen", more.getGlobalVisibleRect(android.graphics.Rect()))
                    more.performClick()
                }
                instrumentation.waitForIdleSync()
                android.os.SystemClock.sleep(250)
                choose(sources.context.getString(label))
                withTimeout(10000) {
                    while (requested.size < if (disconnected) 2 else 1) delay(100)
                }
            }
            assertEquals(listOf("test-menu", "test-menu"), requested)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun sourceCardsKeepPrimaryActionDirectAndManagementCompact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (language in listOf("en", "zh")) for (font in listOf(1f, 2f)) {
                val context = themedContext(language, font)
                val sources = SourcesView(context)
                var browsed: String? = null
                var reauthenticated: String? = null
                var edited: String? = null
                sources.onNasBrowse = { browsed = it }
                sources.onNasReauth = { reauthenticated = it }
                sources.onNasEdit = { edited = it }
                sources.renderNasSources(listOf(
                    SourcesView.NasSourceModel("online", "Online", "Ready", catalogOnly = true),
                    SourcesView.NasSourceModel("reauth", "Authentication", "Sign in", needsReauth = true),
                    SourcesView.NasSourceModel("offline", "Disconnected", "Offline", isDisconnected = true),
                ))
                layout(sources, 320)
                val labels = descendants(sources).filterIsInstance<TextView>()
                assertTrue(descendants(sources).none { it is Switch })
                assertTrue(labels.none { it.text == context.getString(R.string.sources_disconnect) })
                val browse = labels.single { it.text == context.getString(R.string.sources_browse_photos) }
                browse.performClick()
                assertEquals("online", browsed)
                val reconfigure = labels.filter { it.text == context.getString(R.string.sources_reconfigure) }
                assertEquals(2, reconfigure.size)
                reconfigure[0].performClick()
                reconfigure[1].performClick()
                assertEquals("reauth", reauthenticated)
                assertEquals("offline", edited)
                val actions = labels.filter { it.isClickable }
                val target = context.resources.getDimensionPixelSize(R.dimen.pp_touch_target)
                actions.forEach { action ->
                    assertTrue(action.height >= target)
                    assertTrue(action.width >= target)
                    val textLayout = requireNotNull(action.layout)
                    assertTrue((0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 })
                }
                assertEquals(3, labels.count { it.text == context.getString(R.string.player_more) })
            }
        }
    }

    @Test fun librarySourcesEntrySharesPhoneHeaderAndRemainsDirect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (language in listOf("en", "zh")) for (font in listOf(1f, 2f)) {
                val context = themedContext(language, font)
                val library = LibraryView(context, context.cacheDir)
                var opened = false
                library.onBrowseSourcePhotos = { opened = true }
                layout(library, 320)
                val labels = descendants(library).filterIsInstance<TextView>()
                val sources = labels.single { it.text == context.getString(R.string.sources_title) }
                val select = labels.single { it.text == context.getString(R.string.common_select) }
                assertEquals(sources.parent, select.parent)
                if (font == 1f) assertEquals(sources.top, select.top)
                sources.performClick()
                assertTrue(opened)
                val target = context.resources.getDimensionPixelSize(R.dimen.pp_touch_target)
                assertTrue(sources.width >= target)
                assertTrue(sources.height >= target)
            }
        }
    }

    private fun themedContext(language: String, font: Float): ContextThemeWrapper {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(language)))
            fontScale = font
            screenWidthDp = 320
        }
        return ContextThemeWrapper(context, R.style.Theme_PhotoPilot).apply {
            applyOverrideConfiguration(configuration)
        }
    }

    private fun layout(view: View, widthDp: Int) {
        val width = (widthDp * view.resources.displayMetrics.density).toInt()
        val height = (800 * view.resources.displayMetrics.density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
