package dev.photopilot.scaffold

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.aiframes.*
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.ui.aiframes.AIFrameView
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ClipLegacyModelUITest {
    @Test fun retiredModelAndRestoredDraftUseLiteDefaultAndPreserveOwnership() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val environment = AppEnvironment.shared(context)
        val db = environment.database
        val prefix = "legacy-clip-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val frame = environment.frameManager.createFrame("Legacy")
        val ids = listOf("$prefix-owned", "$prefix-manual")
        db.sourceDao().upsert(SourceEntity(prefix, SourceType.LOCAL_FILES, "Synthetic validation", null, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, null, now, now))
        db.collectionDao().upsert(CollectionEntity(prefix, null, prefix, "Synthetic validation", null, CollectionType.SOURCE_FOLDER, prefix, now, now))
        for ((index, id) in ids.withIndex()) {
            val file = File(context.filesDir, "originals/$id.png").apply { parentFile!!.mkdirs() }
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(if (index == 0) Color.BLUE else Color.GREEN) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            db.sourceAssetDao().upsert(SourceAssetEntity("$id-source", prefix, "$id.png", null, "1", file.length(), null, now, 0, AccessState.ACCESSIBLE, now, now))
            db.assetDao().upsert(AssetEntity(id, hash, file.length(), AssetFormat.PNG, 16, 16, 1, OrientationClass.SQUARE, null, CapturedAtSource.UNKNOWN, now, "$id.png", "$id.png", AssetReadiness.READY, null, false, null, deletedAt = null, createdAt = now, updatedAt = now))
            db.assetOriginDao().upsert(AssetOriginEntity(id, "$id-source", now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
            db.collectionAssetDao().upsert(CollectionAssetEntity(prefix, id, now, now))
            db.frameSelectionDao().upsertRaw(FrameSelectionEntity(frame.id, id, null, FrameSelectionMode.INCLUDE, null, now, id, now, now))
        }
        val legacy = AIConfiguration(frame.id, 1, "siglip2-base-int8", "ba1f3b0843f24bc5417d38e19c37b287d719b2f4", listOf(prefix), false, true, AIRule("cats", threshold = .075f), members = setOf(ids.first()))
        environment.aiFrames.state.put(legacy)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val drafts = listOf<Map<String, String>?>(null, mapOf("modelId" to "siglip2-base-int8", "threshold" to "0.075", "descriptions" to "cats", "names" to "Restored legacy", "folders" to org.json.JSONArray(listOf(prefix)).toString(), "automatic" to "true"))
            for (draft in drafts) {
                environment.aiFrames.stop(frame.id)
                environment.aiFrames.state.put(legacy)
                lateinit var view: AIFrameView
                instrumentation.runOnMainSync {
                    val config = android.content.res.Configuration(activity.resources.configuration).apply { setLocales(LocaleList(Locale.ENGLISH)) }
                    val themed = android.view.ContextThemeWrapper(activity, R.style.Theme_PhotoPilot).apply { applyOverrideConfiguration(config) }
                    view = AIFrameView(themed, environment, uiScope, frame, {}, {}, draft)
                    activity.setContentView(android.widget.FrameLayout(activity).apply { fitsSystemWindows = true; addView(view) })
                }
                val expectedName = if (draft == null) "Legacy" else "Restored legacy"
                withTimeout(5000) {
                    while (true) {
                        var ready = false
                        instrumentation.runOnMainSync { ready = view.draftState()["names"] == expectedName && view.draftState()["folders"].orEmpty().contains(prefix) }
                        if (ready) break
                        delay(20)
                    }
                }
                instrumentation.runOnMainSync {
                    assertEquals("multilingual-clip-vitb32-lite", view.draftState()["modelId"])
                    assertEquals(.24f, view.draftState()["threshold"]!!.toFloat(), 0f)
                    val save = descendants(view).filterIsInstance<TextView>().single { it.text.toString() == "Save settings without scanning" }
                    save.performClick()
                }
                withTimeout(5000) {
                    while (environment.aiFrames.state.get(frame.id)?.modelId != "multilingual-clip-vitb32-lite") delay(20)
                }
                val saved = requireNotNull(environment.aiFrames.state.get(frame.id))
                assertEquals(.24f, saved.rule.threshold, 0f)
                assertEquals(setOf(ids.first()), saved.members)
                assertEquals(ids.toSet(), db.frameSelectionDao().getByFrame(frame.id).mapNotNull { it.assetId }.toSet())
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            uiScope.cancel()
            environment.aiFrames.stop(frame.id)
            environment.frameManager.deleteFrame(frame.id)
            for (id in ids) { db.assetDao().getById(id)?.let { db.assetDao().delete(it) }; File(context.filesDir, "originals/$id.png").delete() }
            db.collectionDao().getById(prefix)?.let { db.collectionDao().delete(it) }
            db.sourceDao().getById(prefix)?.let { db.sourceDao().delete(it) }
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
