package dev.photopilot.scaffold

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Switch
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.repo.SelectionRefEdit
import dev.photopilot.scaffold.ui.remote.RemoteFrameView
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteFrameLayoutTest {
    @Test fun bilingualRemoteSelectionAndStorageControls() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val language = args.getString("language") ?: "en"
        val variant = args.getString("variant") ?: "portrait"
        val env = AppEnvironment.shared(instrumentation.targetContext)
        val id = "remote-ui-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        env.database.sourceDao().upsert(SourceEntity(id, SourceType.SMB, if (language == "zh") "家庭照片 · 示例来源" else "Family photos · Sample source", id, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true}", now, now))
        env.database.collectionDao().upsert(CollectionEntity(id, null, id, if (language == "zh") "家庭照片" else "Family photos", "", CollectionType.SOURCE_FOLDER, "", now, now))
        for (index in 1..4) env.database.sourceAssetDao().upsert(SourceAssetEntity("$id-$index", id, "Photo $index.png", null, "1", 2048, null, now, 0, AccessState.ACCESSIBLE, now, now))
        val frame = env.frameManager.createFrame(if (language == "zh") "客厅相框" else "Living room")
        env.database.frameDao().update(frame.copy(settingsJson = "{\"mediaStorageMode\":\"remote\"}"))
        env.frames.save(frame.id, 0, addIncludes = listOf(SelectionRefEdit(null, "$id-1"), SelectionRefEdit(null, "$id-2")))
        val model = env.aiFrames.models.models.single { it.id == "multilingual-clip-vitb32-lite" }
        val ownership = setOf("remote:$id-1", "remote:$id-2")
        env.aiFrames.state.put(dev.photopilot.scaffold.data.aiframes.AIConfiguration(frame.id, 1, model.id, model.revision, listOf(id), false, false, dev.photopilot.scaffold.data.aiframes.AIRule("Photos", threshold = model.defaultThreshold), members = ownership))
        val current = requireNotNull(env.database.frameDao().getById(frame.id))
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        SystemClock.sleep(500)
        val saved = java.util.concurrent.CountDownLatch(1)
        lateinit var view: RemoteFrameView
        instrumentation.runOnMainSync {
            val config = Configuration(activity.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language)))
                fontScale = if (variant.contains("large")) 1.5f else 1f
                if (variant.contains("dark")) uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
            val themed = android.view.ContextThemeWrapper(activity, R.style.Theme_PhotoPilot).apply { applyOverrideConfiguration(config) }
            view = RemoteFrameView(themed, env, current, mapOf("sourceId" to id))
            view.onSaved = { saved.countDown() }
            activity.setContentView(android.widget.FrameLayout(activity).apply { fitsSystemWindows = true; addView(view) })
        }
        SystemClock.sleep(700)
        try {
            instrumentation.runOnMainSync {
                assertFalse(descendants(view).filterIsInstance<Switch>().single().isChecked)
                assertNotNull(view.background)
                if (variant.contains("dark")) {
                    assertEquals(Configuration.UI_MODE_NIGHT_YES, view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                    assertEquals(view.context.getColor(R.color.pp_surface_canvas), (view.background as android.graphics.drawable.ColorDrawable).color)
                }
                val photoChecks = descendants(view).filterIsInstance<android.widget.CheckBox>().filter { it.text.startsWith("Photo ") }
                assertEquals(4, photoChecks.size)
                assertTrue(descendants(view).filterIsInstance<android.widget.TextView>().filter {
                    it.text.toString() in setOf("Previous", "Next", "上一页", "下一页")
                }.none { it.isShown })
                photoChecks.forEach { check ->
                    assertEquals("Photo rows keep a single readable line at large font", 1, check.maxLines)
                    assertEquals(android.text.TextUtils.TruncateAt.END, check.ellipsize)
                    assertEquals(check.text.toString(), check.contentDescription.toString())
                    if (variant.contains("dark")) {
                        val foreground = android.graphics.Color.luminance(check.currentTextColor)
                        val background = android.graphics.Color.luminance(view.context.getColor(R.color.pp_surface_canvas))
                        assertTrue("Dark checkbox text must retain accessible contrast", (maxOf(foreground, background) + 0.05) / (minOf(foreground, background) + 0.05) >= 4.5)
                    }
                    assertTrue(check.minimumHeight >= (48 * view.resources.displayMetrics.density).toInt())
                }
                assertEquals(2, org.json.JSONArray(view.draftState().getValue("selected")).length())
            }
            val directory = File(instrumentation.targetContext.cacheDir, "remote-ui").apply { mkdirs() }
            fun capture(section: String) {
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                File(directory, "$language-$variant-$section.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            capture("storage")
            instrumentation.runOnMainSync {
                val scroll = descendants(view).filterIsInstance<ScrollView>().first()
                scroll.scrollTo(0, (scroll.getChildAt(0).height - scroll.height).coerceAtLeast(0) / 2)
            }
            SystemClock.sleep(250)
            capture("folders")
            instrumentation.runOnMainSync { descendants(view).filterIsInstance<ScrollView>().first().fullScroll(View.FOCUS_DOWN) }
            SystemClock.sleep(300)
            capture("selection")
            instrumentation.runOnMainSync {
                descendants(view).filterIsInstance<android.widget.TextView>().first { it.text.toString() == if (language == "zh") "保存相框" else "Save frame" }.performClick()
            }
            assertTrue(saved.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val persisted = requireNotNull(env.database.frameDao().getById(frame.id))
            assertEquals("remote", dev.photopilot.scaffold.data.logic.FramePlaybackSettings.parse(persisted.settingsJson).mediaStorageMode)
            assertEquals(2, env.database.frameSelectionDao().getByFrame(frame.id).count { it.sourceAssetId != null && it.mode == FrameSelectionMode.INCLUDE })
            assertEquals(ownership, env.aiFrames.state.get(frame.id)?.members)
            val cleared = java.util.concurrent.CountDownLatch(1)
            instrumentation.runOnMainSync {
                view.onSaved = { cleared.countDown() }
                descendants(view).filterIsInstance<android.widget.CheckBox>().filter { it.text.startsWith("Photo ") }.forEach { it.isChecked = false }
                val save = descendants(view).filterIsInstance<android.widget.TextView>().single { it.text.toString() == if (language == "zh") "保存相框" else "Save frame" }
                assertTrue("Existing frames must allow saving an empty selection", save.isEnabled)
                save.performClick()
            }
            assertTrue(cleared.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(env.database.frameSelectionDao().getByFrame(frame.id).none { it.mode == FrameSelectionMode.INCLUDE })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            env.frameManager.deleteFrame(frame.id)
            env.database.sourceDao().getById(id)?.let { env.database.sourceDao().delete(it) }
        }
        Unit
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
