package dev.photopilot.scaffold

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.ui.remote.RemoteFrameView
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RemoteReferenceReloadTest {
    @Test fun overlappingReloadAndSourceSwitchKeepOneCurrentGridAndSelection() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val id = "reload-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val sources = listOf(id to "Reload A", "$id-other" to "Reload B")
        for ((sourceId, name) in sources) {
            env.database.sourceDao().upsert(SourceEntity(sourceId, SourceType.SMB, name, sourceId, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true}", now, now))
            repeat(4) { index -> env.database.sourceAssetDao().upsert(SourceAssetEntity("$sourceId-$index", sourceId, "${if (sourceId == id) "Current" else "Other"} $index.png", null, "1", 10, null, now, 0, AccessState.ACCESSIBLE, now, now)) }
        }
        env.settings.setOnboardingCompleted(true)
        env.settings.putRaw("overlay_stack", "{\"stack\":[]}")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        delay(6500)
        try {
            lateinit var view: RemoteFrameView
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("openRemoteFrame", FrameEntity::class.java, Map::class.java).apply { isAccessible = true }.invoke(activity, null, mapOf("sourceId" to id))
                view = MainActivity::class.java.getDeclaredField("remoteFrameView").apply { isAccessible = true }.get(activity) as RemoteFrameView
                repeat(4) { view.reloadSources() }
            }
            await { descendants(view).filterIsInstance<CheckBox>().count { it.text.startsWith("Current ") } == 4 }
            instrumentation.runOnMainSync {
                descendants(view).filterIsInstance<CheckBox>().first { it.text.startsWith("Current ") }.performClick()
                repeat(4) { view.reloadSources() }
            }
            delay(600)
            instrumentation.runOnMainSync {
                val rows = descendants(view).filterIsInstance<CheckBox>().filter { it.text.startsWith("Current ") }
                assertEquals(4, rows.size)
                assertEquals(1, rows.count { it.isChecked })
                for (name in listOf("Reload B", "Reload A")) {
                    view.javaClass.getDeclaredMethod("chooseAdditionalSource").apply { isAccessible = true }.invoke(view)
                    val list = android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::descendants)
                        .filterIsInstance<android.widget.ListView>().single { it.isShown }
                    val position = (0 until list.adapter.count).single { list.adapter.getItem(it).toString() == name }
                    list.performItemClick(list.getChildAt(position - list.firstVisiblePosition), position, list.adapter.getItemId(position))
                }
            }
            await { descendants(view).filterIsInstance<CheckBox>().count { it.text.startsWith("Current ") } == 4 }
            delay(400)
            instrumentation.runOnMainSync {
                val rows = descendants(view).filterIsInstance<CheckBox>()
                assertEquals(4, rows.count { it.text.startsWith("Current ") })
                assertTrue(rows.none { it.text.startsWith("Other ") })
                assertEquals(1, rows.count { it.isChecked })
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            for ((sourceId, _) in sources) env.database.sourceDao().getById(sourceId)?.let { env.database.sourceDao().delete(it) }
        }
        Unit
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(10000) { while (!predicate()) delay(100) }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
