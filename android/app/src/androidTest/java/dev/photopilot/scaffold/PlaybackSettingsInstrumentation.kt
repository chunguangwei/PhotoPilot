package dev.photopilot.scaffold

import android.app.Instrumentation
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import dev.photopilot.scaffold.data.db.FrameEntity
import dev.photopilot.scaffold.data.db.FrameOrderMode
import dev.photopilot.scaffold.data.logic.PlaybackSettingsEdit
import dev.photopilot.scaffold.ui.player.PlaybackSettingsView

class PlaybackSettingsInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            runOnMainSync { verifyReopenedControls() }
            result.putString("stream", "Playback settings native controls passed\n")
            finish(-1, result)
        } catch (failure: Throwable) {
            result.putString("stream", "FAILED: ${failure.message}\n")
            finish(0, result)
        }
    }

    private fun verifyReopenedControls() {
        val positions = listOf("top_leading", "top_center", "top_trailing", "bottom_leading", "bottom_center", "bottom_trailing")
        val labels = listOf(R.string.playback_position_top_leading, R.string.playback_position_top_center, R.string.playback_position_top_trailing, R.string.playback_position_bottom_leading, R.string.playback_position_bottom_center, R.string.playback_position_bottom_trailing)
        for (language in listOf("zh", "en")) {
            val config = android.content.res.Configuration(targetContext.resources.configuration)
            config.setLocale(java.util.Locale(language))
            val context = targetContext.createConfigurationContext(config)
            val view = PlaybackSettingsView(context)
            for ((index, position) in positions.withIndex()) {
                var frame = FrameEntity("settings-test", "Test", null, """{"holdSeconds":120,"clock":{"enabled":false,"position":"$position","hour24":false},"date":{"enabled":false},"keepAwakeWhenPaused":false,"rest":{"enabled":true,"startMinute":60,"endMinute":120,"display":"black"},"brightness":0.53,"qualityLevel":"low","fitMode":"fit_color","solidColor":"warm_white"}""", 0, FrameOrderMode.MANUAL, 0, 0, 0)
                repeat(2) {
                    view.bind(frame)
                    val children = descendants(view)
                    fun selected(label: Int): Boolean = children.filterIsInstance<TextView>().single { it.text.toString() == context.getString(label) }.isSelected
                    check(selected(labels[index])) { "$language: saved $position not selected" }
                    check(selected(R.string.common_custom)) { "custom hold not selected" }
                    check(selected(R.string.playback_hour_12)) { "hour format not restored" }
                    check(selected(R.string.playback_order_manual)) { "order not restored" }
                    check(selected(R.string.playback_quality_low)) { "quality not restored" }
                    check(selected(R.string.playback_solid_warm_white)) { "solid colour not restored" }
                    for (label in listOf(R.string.playback_clock, R.string.playback_date, R.string.playback_keep_awake_paused, R.string.settings_appearance_system)) {
                        check(!children.filterIsInstance<Switch>().single { it.text.toString() == context.getString(label) }.isChecked) { "switch $label not restored" }
                    }
                    check(children.filterIsInstance<Switch>().single { it.text.toString() == context.getString(R.string.settings_rest_window) }.isChecked)
                    check(!view.hasChanges()) { "binding must not dirty the form" }
                    var saved: String? = null
                    view.onSave = { draft, order ->
                        check(draft.clockPosition == position)
                        check(draft.brightness == 0.53) { "saved brightness drifted" }
                        check(order == FrameOrderMode.MANUAL)
                        saved = PlaybackSettingsEdit.merge(frame.settingsJson, draft)
                    }
                    children.filterIsInstance<TextView>().single { it.text.toString() == context.getString(R.string.common_save) }.performClick()
                    frame = frame.copy(settingsJson = checkNotNull(saved))
                }
            }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
