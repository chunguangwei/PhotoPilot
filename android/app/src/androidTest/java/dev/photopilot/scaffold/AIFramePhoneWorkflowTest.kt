package dev.photopilot.scaffold

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.FrameEntity
import dev.photopilot.scaffold.data.repo.AppSettingsRepository
import dev.photopilot.scaffold.ui.aiframes.AIFrameView
import dev.photopilot.scaffold.ui.frames.FramesView
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AIFramePhoneWorkflowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun englishRealPreviewRenamesAndConfirmsOnceFromPhoneFooter() = runBlocking {
        workflow("en", "a photo of a cat")
    }

    @Test fun chineseRealPreviewRenamesAndConfirmsOnceFromPhoneFooter() = runBlocking {
        workflow("zh", "猫的照片")
    }

    private suspend fun workflow(language: String, description: String) {
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val model = env.aiFrames.models.models.single { it.id == "multilingual-clip-vitb32-lite" }
        val fixture = File(context.filesDir, "ai-validation")
        val cat = File(fixture, "validation-cats.png")
        val modelFixture = listOf("multilingual-clip4", "multilingual-clip")
            .map { File(fixture, it) }
            .firstOrNull { directory -> model.files.all { File(directory, it.name).isFile } }
        assumeTrue("Provision validation-cats.png and all multilingual-clip4 model files", cat.isFile && modelFixture != null)
        val directory = env.aiFrames.models.directory(model)
        model.files.forEach { file ->
            File(requireNotNull(modelFixture), file.name).copyTo(File(directory, file.name), overwrite = true)
            File(directory, file.name + ".verified").writeText(file.sha256)
        }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        val token = "AIPhone-" + UUID.randomUUID()
        val album = "$token-$language"
        val frameName = "$album-confirmed"
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$album.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$album")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        val settingKeys = listOf(
            AppSettingsRepository.KEY_UI_LANGUAGE,
            AppSettingsRepository.KEY_ONBOARDING_COMPLETED,
            AppSettingsRepository.KEY_AUTO_PLAY,
            "overlay_stack",
        )
        val savedSettings = settingKeys.associateWith { env.database.settingsKvDao().get(it) }
        var activity: MainActivity? = null
        val beforeFrames = env.database.frameDao().getAll().map { it.id }.toSet()
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { output -> cat.inputStream().use { it.copyTo(output) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val source = env.localReferences.indexPhotos()
            val collection = env.database.collectionDao().getBySource(source.id).single { it.name == album }
            val sourceAsset = env.database.sourceAssetDao().getBySource(source.id)
                .single { env.localReferences.displayName(it) == "$album.png" }
            assertTrue(env.database.importJobDao().getBySource(source.id).isEmpty())
            assertTrue(env.database.assetOriginDao().getBySourceAssets(listOf(sourceAsset.id)).isEmpty())
            env.settings.setLanguage(language)
            env.settings.setOnboardingCompleted(true)
            env.settings.setAutoPlayEnabled(false)
            env.settings.putRaw("overlay_stack", "{\"stack\":[]}")
            val launched = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )) as MainActivity
            activity = launched
            delay(6500)
            val draft = mapOf(
                "modelId" to model.id,
                "folders" to JSONArray(listOf(collection.id)).toString(),
                "descriptions" to description,
                "threshold" to model.defaultThreshold.toString(),
                "automatic" to "false",
                "recursive" to "false",
            )
            onMain {
                MainActivity::class.java.getDeclaredMethod("openAIFrameEditor", FrameEntity::class.java, Map::class.java)
                    .apply { isAccessible = true }.invoke(launched, null, draft)
            }
            val scanLabel = if (language == "zh") "扫描并预览" else "Scan and preview"
            val confirmLabel = if (language == "zh") "确认创建相框" else "Confirm frame creation"
            await("Initialized real-model editor must offer Scan") {
                onMain { editor(launched)?.let { view ->
                    view.draftState()["modelId"] == model.id && view.draftState()["folders"] == draft["folders"] &&
                        labels(view).any { it.text.toString() == scanLabel && it.isEnabled && it.isShown }
                } == true }
            }
            val ai = onMain { requireNotNull(editor(launched)) }
            onMain {
                val scan = labels(ai).single { it.text.toString() == scanLabel && it.isShown }
                assertOutsideScrollAndFullyVisible(scan)
                scan.performClick()
            }
            await("Real model must complete preview with a visible footer Confirm", 180_000) {
                onMain { labels(ai).any { it.text.toString() == confirmLabel && it.isShown && it.isEnabled } }
            }
            onMain {
                val confirm = labels(ai).single { it.text.toString() == confirmLabel && it.isShown }
                assertOutsideScrollAndFullyVisible(confirm)
                val status = AIFrameView::class.java.getDeclaredField("status").apply { isAccessible = true }.get(ai) as TextView
                assertTrue(status.text.isNotBlank())
                assertOutsideScrollAndFullyVisible(status)
                val names = descendants(ai).filterIsInstance<EditText>().filter {
                    it.hint?.toString() in setOf("相框名称", "Frame name") && it.isShown
                }
                assertEquals(1, names.size)
                assertFullyVisible(names.single())
                names.single().setText(frameName)
                assertTrue(confirm.performClick())
                confirm.performClick()
            }
            await("Confirm must save and return through Root to Frames") {
                val created = env.database.frameDao().getAll().filter { it.id !in beforeFrames }
                created.size == 1 && created.single().name == frameName && onMain {
                    editor(launched) == null && descendants(launched.window.decorView).filterIsInstance<FramesView>().any { it.isShown }
                }
            }
            delay(500)
            val created = env.database.frameDao().getAll().filter { it.id !in beforeFrames }
            assertEquals(1, created.size)
            assertEquals(frameName, created.single().name)
            assertEquals(listOf("remote:${sourceAsset.id}"), env.frames.buildPlayQueue(created.single().id))
            val configuration = requireNotNull(env.aiFrames.state.get(created.single().id))
            assertFalse(configuration.autoUpdate)
            assertEquals(setOf("remote:${sourceAsset.id}"), configuration.members)
            assertTrue(env.database.importJobDao().getBySource(source.id).isEmpty())
            assertTrue(env.database.assetOriginDao().getBySourceAssets(listOf(sourceAsset.id)).isEmpty())
        } finally {
            activity?.let { onMain { it.finish() } }
            env.database.frameDao().getAll().filter { it.name == frameName && it.id !in beforeFrames }
                .forEach { env.frameManager.deleteFrame(it.id) }
            resolver.delete(uri, null, null)
            savedSettings.forEach { (key, value) ->
                if (value != null) env.database.settingsKvDao().upsert(value)
                else env.database.settingsKvDao().get(key)?.let { env.database.settingsKvDao().delete(it) }
            }
        }
    }

    private fun editor(activity: MainActivity): AIFrameView? =
        descendants(activity.window.decorView).filterIsInstance<AIFrameView>().lastOrNull { it.isShown }

    private fun labels(view: View): List<TextView> = descendants(view).filterIsInstance<TextView>()

    private fun assertOutsideScrollAndFullyVisible(view: View) {
        var parent = view.parent
        while (parent is View) {
            assertFalse("Footer control must remain outside ScrollView", parent is ScrollView)
            parent = parent.parent
        }
        assertFullyVisible(view)
    }

    private fun assertFullyVisible(view: View) {
        val visible = Rect()
        assertTrue(view.isShown)
        assertTrue(view.getGlobalVisibleRect(visible))
        assertEquals(view.width, visible.width())
        assertEquals(view.height, visible.height())
        val screen = Rect()
        view.rootView.getWindowVisibleDisplayFrame(screen)
        assertTrue("Control must fit inside the visible window", screen.contains(visible))
    }

    private suspend fun await(message: String, timeoutMillis: Long = 20_000, condition: suspend () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            delay(100)
        }
        fail(message)
    }

    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
