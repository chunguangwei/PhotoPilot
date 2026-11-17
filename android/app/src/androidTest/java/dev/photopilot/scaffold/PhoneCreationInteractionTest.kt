package dev.photopilot.scaffold

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.imports.MediaStoreSourceAdapter
import dev.photopilot.scaffold.ui.aiframes.AIFrameView
import dev.photopilot.scaffold.ui.permissions.PhotoPermissionOverlayView
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PhoneCreationInteractionTest {
    @Test fun aiLibraryConnectionReturnsToDraftAndExposesRealMediaStoreAlbum() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val environment = AppEnvironment.shared(context)
        val stamp = System.currentTimeMillis()
        val album = "PhotoPilotPhone-$stamp"
        val filename = "phone-$stamp.png"
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$album")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        resolver.openOutputStream(uri)!!.use { stream ->
            val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        environment.settings.setOnboardingCompleted(true)
        environment.settings.putRaw("overlay_stack", "{\"stack\":[]}")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            delay(6500)
            instrumentation.runOnMainSync {
                descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.MainContainerView>().single()
                    .selectSection(dev.photopilot.scaffold.ui.MainSection.FRAMES)
            }
            withTimeout(10000) {
                while (descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.frames.FramesView>().flatMap { descendants(it) }
                    .filterIsInstance<TextView>().none { it.isClickable && it.text.toString() == context.getString(R.string.frames_create) }) delay(100)
            }
            instrumentation.runOnMainSync {
                val home = descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.frames.FramesView>().single()
                descendants(home).filterIsInstance<TextView>().single { it.isClickable && it.text.toString() == context.getString(R.string.frames_create) }.performClick()
            }
            withTimeout(10000) {
                var chosen = false
                while (!chosen) {
                    if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                    val item = listOf("Create AI frame", "AI 创建相框").firstNotNullOfOrNull { label ->
                        instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.text.toString() == label }
                    }
                    var action = item
                    while (action != null && !action.isClickable && action.parent != null) action = action.parent
                    chosen = action?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
                    if (!chosen) delay(100)
                }
            }
            lateinit var editor: AIFrameView
            withTimeout(15000) {
                while (true) {
                    val found = descendants(activity.window.decorView).filterIsInstance<AIFrameView>().lastOrNull()
                    if (found != null && found.javaClass.getDeclaredField("initialized").apply { isAccessible = true }.getBoolean(found)) { editor = found; break }
                    delay(100)
                }
            }
            instrumentation.runOnMainSync {
                val categories = descendants(editor).filterIsInstance<EditText>().first()
                categories.setText("Family")
                descendants(editor).filterIsInstance<TextView>().single {
                    it.text.toString() in listOf("Photo Library", "照片库")
                }.performClick()
            }
            withTimeout(10000) {
                while (descendants(activity.window.decorView).filterIsInstance<PhotoPermissionOverlayView>().isEmpty()) delay(100)
            }
            instrumentation.runOnMainSync {
                val permissionView = descendants(activity.window.decorView).filterIsInstance<PhotoPermissionOverlayView>().last()
                assertEquals(PhotoPermissionOverlayView.State.GRANTED, permissionView.state)
                descendants(permissionView).filterIsInstance<TextView>().single {
                    it.text.toString() in listOf("Continue", "继续")
                }.performClick()
            }
            withTimeout(20000) {
                while (environment.database.collectionDao().getAll().none { it.name == album }) delay(100)
            }
            withTimeout(10000) {
                while (descendants(activity.window.decorView).filterIsInstance<PhotoPermissionOverlayView>().isNotEmpty()) delay(100)
            }
            withTimeout(10000) {
                var albumVisible = false
                while (!albumVisible) {
                    instrumentation.runOnMainSync {
                        val folders = editor.javaClass.getDeclaredField("folders").apply { isAccessible = true }.get(editor) as View
                        albumVisible = descendants(folders).filterIsInstance<TextView>().any { it.text.toString() == album }
                    }
                    if (!albumVisible) delay(100)
                }
            }
            instrumentation.runOnMainSync {
                assertTrue(descendants(activity.window.decorView).any { it === editor })
                assertEquals("Family", descendants(editor).filterIsInstance<EditText>().first().text.toString())
                val folders = editor.javaClass.getDeclaredField("folders").apply { isAccessible = true }.get(editor) as View
                assertTrue(descendants(folders).filterIsInstance<TextView>().any { it.text.toString() == album })
            }
            val source = environment.database.sourceDao().getAll().single { it.endpointKey == MediaStoreSourceAdapter.FINGERPRINT }
            val indexed = environment.database.sourceAssetDao().getBySource(source.id)
            assertTrue(indexed.any { environment.localReferences.displayName(it) == filename })
            assertTrue(environment.database.importJobDao().getBySource(source.id).isEmpty())
            assertTrue(environment.database.assetOriginDao().getBySourceAssets(indexed.map { it.id }).isEmpty())
            if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            listOf("Done", "完成").firstNotNullOfOrNull { label ->
                instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)?.firstOrNull { it.isClickable && it.text.toString() == label }
            }?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.runOnMainSync {
                editor.reloadCollections()
                activity.setContentView(TextView(activity))
                val reload = editor.javaClass.getDeclaredField("sourceReload").apply { isAccessible = true }.get(editor) as kotlinx.coroutines.Job
                assertTrue("Leaving AI creation must cancel its pending source reload", reload.isCancelled)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            resolver.delete(uri, null, null)
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
