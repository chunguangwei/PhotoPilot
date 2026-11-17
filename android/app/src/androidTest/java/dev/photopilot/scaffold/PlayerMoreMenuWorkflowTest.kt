package dev.photopilot.scaffold

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.db.FrameEntity
import dev.photopilot.scaffold.data.db.FrameOrderMode
import dev.photopilot.scaffold.ui.player.PlayerView
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class PlayerMoreMenuWorkflowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun fullscreenMoreActionsRemainReachableAndDispatchSettingsAndExit() {
        val originalSize = shell("wm size")
        val originalDensity = shell("wm density")
        var activity: MainActivity? = null
        try {
            shell("wm density 480")
            for (widthDp in listOf(320, 360)) for (landscape in listOf(false, true)) {
                val short = widthDp * 3
                val long = 2440
                val dimensions = if (landscape) "${long}x$short" else "${short}x$long"
                shell("wm size $dimensions")
                SystemClock.sleep(350)
                val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
                activity = host
                SystemClock.sleep(6500)
                for (language in listOf("en", "zh")) for (font in listOf(1f, 2f)) {
                    val label = "$widthDp landscape=$landscape $language font=$font"
                    instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", "$label\n") })
                    verify(host, widthDp, landscape, language, font)
                }
                instrumentation.runOnMainSync { host.finish() }
                activity = null
            }
        } finally {
            activity?.let { host -> instrumentation.runOnMainSync { host.finish() } }
            restoreDisplay("size", originalSize)
            restoreDisplay("density", originalDensity)
        }
    }

    private fun verify(activity: MainActivity, widthDp: Int, landscape: Boolean, language: String, font: Float) {
        lateinit var player: PlayerView
        lateinit var labels: List<String>
        lateinit var menuTitle: String
        var settingsFrame: FrameEntity? = null
        var exits = 0
        val frame = FrameEntity("player-more-fixture", "Menu fixture", null, "{}", 0, FrameOrderMode.CAPTURED_ASC, 0, 1, 1)
        instrumentation.runOnMainSync {
            val configuration = Configuration(activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
                fontScale = font
                screenWidthDp = if (landscape) 813 else widthDp
                screenHeightDp = if (landscape) widthDp else 813
            }
            val context = ContextThemeWrapper(activity, R.style.Theme_PhotoPilot).apply {
                applyOverrideConfiguration(configuration)
            }
            labels = listOf(R.string.player_remove_current, R.string.frames_playback_settings, R.string.player_exit)
                .map { context.getString(it) }
            menuTitle = context.getString(R.string.player_more)
            player = PlayerView(context, frame).apply {
                onPlaybackSettings = { settingsFrame = it }
                onExit = { exits++ }
            }
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            activity.setContentView(player)
            player.performClick()
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        openMore(player, menuTitle)
        val title = awaitNode { instrumentation.uiAutomation.rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId("android:id/alertTitle")?.firstOrNull { it.text?.toString() == menuTitle } }
        assertNotNull("More actions must use an anchor-independent dialog", title)
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val screen = screenshot?.let { Rect(0, 0, it.width, it.height) }
        screenshot?.recycle()
        labels.forEach { label ->
            val node = reachableAction(label)
            assertNotNull("Action must be reachable: $label", node)
            assertInsideScreen(requireNotNull(node))
            screen?.let { bounds ->
                val action = Rect().also(node::getBoundsInScreen)
                assertTrue("Action must fit physical screen", bounds.contains(action))
            }
        }
        assertTrue(requireNotNull(reachableAction(labels[1])).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        assertEquals(frame, settingsFrame)
        assertEquals(0, exits)
        openMore(player, menuTitle)
        assertTrue(requireNotNull(reachableAction(labels[2])).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        assertEquals(1, exits)
        openMore(player, menuTitle)
        assertTrue(requireNotNull(reachableAction(labels[0])).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
        assertEquals(1, exits)
        assertEquals(frame, settingsFrame)
    }

    private fun openMore(player: PlayerView, title: String) {
        instrumentation.runOnMainSync {
            val more = descendants(player).filterIsInstance<ImageButton>().single { it.contentDescription?.toString() == title }
            if (!more.isShown) player.performClick()
            more.performClick()
        }
        instrumentation.waitForIdleSync()
    }

    private fun reachableAction(label: String): AccessibilityNodeInfo? {
        repeat(4) { scrollList(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
        repeat(6) {
            textNode(label)?.let { return it }
            if (!scrollList(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return null
            SystemClock.sleep(80)
        }
        return null
    }

    private fun scrollList(action: Int): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        fun list(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.className?.toString() == "android.widget.ListView") return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { child -> list(child)?.let { return it } }
            return null
        }
        return list(root)?.performAction(action) == true
    }

    private fun textNode(label: String): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow
        ?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.text?.toString() == label && it.isVisibleToUser }

    private fun awaitNode(find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + 2500
        while (SystemClock.elapsedRealtime() < deadline) {
            find()?.let { return it }
            SystemClock.sleep(50)
        }
        return null
    }

    private fun assertInsideScreen(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val root = Rect().also { instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
        assertTrue(bounds.width() > 0 && bounds.height() > 0)
        assertTrue("Action $bounds must fit within dialog $root", root.contains(bounds))
    }

    private fun restoreDisplay(kind: String, original: String) {
        val override = original.lineSequence().firstOrNull { it.startsWith("Override") }?.substringAfter(": ")
        shell("wm $kind ${override ?: "reset"}")
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
