package dev.photopilot.scaffold

import androidx.room.withTransaction
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.repo.AppSettingsRepository
import dev.photopilot.scaffold.ui.aiframes.AIFrameView
import dev.photopilot.scaffold.ui.aiframes.AIModelsView
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AITaskNavigationTest {
    @Test fun englishMissingModelReturnsToUnchangedDraft() = runBlocking { exercise("en", false) }
    @Test fun compactMissingModelFocusesTheSecondSettingsModel() = runBlocking { exercise("en", false, missingCompact = true) }
    @Test fun chineseMissingModelReturnsToUnchangedDraft() = runBlocking { exercise("zh", false) }
    @Test fun closingAndRestoringPreviewDoesNotCancelOrRestartItsProcessTask() = runBlocking { exercise("en", true) }
    @Test fun chinesePausedResultsAndHomeCardRemainReadable() = runBlocking { exercise("zh", true) }
    @Test fun editingRulesWhileStoredPreviewLoadsCannotConfirmItsOldRules() = runBlocking { exercise("en", true, true) }
    private suspend fun exercise(language: String, runPreview: Boolean, editDuringLoad: Boolean = false, missingCompact: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val id = "task-nav-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val model = env.aiFrames.models.models.single { if (runPreview || missingCompact) it.runtime == "onnx" else it.runtime == "litertlm" }
        val marker = File(env.aiFrames.models.directory(model), model.files.first().name + ".verified")
        val markerBefore = marker.takeIf { it.isFile }?.readBytes()
        if (runPreview) {
            model.files.forEach { file -> File(context.filesDir, "ai-validation/multilingual-clip/${file.name}").copyTo(File(env.aiFrames.models.directory(model), file.name), true); File(env.aiFrames.models.directory(model), file.name + ".verified").writeText(file.sha256) }
        } else marker.delete()
        val keys = listOf(AppSettingsRepository.KEY_UI_LANGUAGE, AppSettingsRepository.KEY_ONBOARDING_COMPLETED, AppSettingsRepository.KEY_AUTO_PLAY, "overlay_stack")
        val previous = keys.associateWith { env.database.settingsKvDao().get(it) }
        val uiPrefs = context.getSharedPreferences("ui_state", android.content.Context.MODE_PRIVATE)
        val aiGroupKey = "settings.section.collapsed.settings.ai_models"
        val previousGroupState = if (uiPrefs.contains(aiGroupKey)) uiPrefs.getBoolean(aiGroupKey, true) else null
        val previousLocales = if (android.os.Build.VERSION.SDK_INT >= 33) context.getSystemService(android.app.LocaleManager::class.java).applicationLocales else null
        env.settings.setLanguage(language)
        dev.photopilot.scaffold.ui.UiPreferenceOverrides.update(context, language = language)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.runOnMainSync { context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = android.os.LocaleList.forLanguageTags(language) }
        }
        env.settings.setOnboardingCompleted(true)
        env.settings.setAutoPlayEnabled(false)
        env.settings.putRaw("overlay_stack", """{"stack":[]}""")
        env.database.sourceDao().upsert(SourceEntity(id, SourceType.LOCAL_FILES, "Synthetic album", null, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, null, now, now))
        env.database.collectionDao().upsert(CollectionEntity(id, null, id, "Synthetic album", null, CollectionType.SOURCE_FOLDER, id, now, now))
        val syntheticFile = File(context.filesDir, "originals/$id.png")
        if (runPreview) {
            syntheticFile.parentFile!!.mkdirs()
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            syntheticFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(syntheticFile.readBytes()).joinToString("") { "%02x".format(it) }
            env.database.sourceAssetDao().upsert(SourceAssetEntity(id, id, "$id.png", null, "1", syntheticFile.length(), null, now, 0, AccessState.ACCESSIBLE, now, now))
            env.database.assetDao().upsert(AssetEntity(id, hash, syntheticFile.length(), AssetFormat.PNG, 32, 32, 1, OrientationClass.SQUARE, null, CapturedAtSource.UNKNOWN, now, "$id.png", "$id.png", AssetReadiness.READY, null, false, null, deletedAt = null, createdAt = now, updatedAt = now))
            env.database.assetOriginDao().upsert(AssetOriginEntity(id, id, now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
            env.database.collectionAssetDao().upsert(CollectionAssetEntity(id, id, now, now))
        }
        val draft = mapOf("draftId" to id, "modelId" to model.id, "folders" to org.json.JSONArray(listOf(id)).toString(), "descriptions" to "Family\n海边", "includes" to "photos", "excludes" to "cars", "threshold" to model.defaultThreshold.toString(), "automatic" to "true", "recursive" to "false", "allIncludes" to "false", "names" to "Living room\n客厅")
        var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        val leaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val release = CompletableDeferred<Unit>()
        var originalRecords: Any? = null
        var snapshotGate: CompletableDeferred<Unit>? = null
        fun labels(view: View) = descendants(view).filterIsInstance<TextView>()
        fun editor(): AIFrameView = descendants(activity.window.decorView).filterIsInstance<AIFrameView>().last()
        fun clickBack(view: View) {
            var target: View = labels(view).first { it.text.toString() in setOf("Back", "返回") }
            while (!target.isClickable) target = target.parent as View
            assertTrue(target.performClick())
        }
        var phase = "initialization"
        suspend fun await(condition: () -> Boolean) {
            try { withTimeout(10000) { while (!condition()) delay(50) } }
            catch (_: TimeoutCancellationException) {
                instrumentation.runOnMainSync {
                    val root = MainActivity::class.java.getDeclaredField("root").apply { isAccessible = true }.get(activity) as dev.photopilot.scaffold.ui.AppRootView
                    val editors = descendants(activity.window.decorView).filterIsInstance<AIFrameView>()
                    println("AI navigation phase=$phase editors=${editors.size} top=${root.topOverlay()?.javaClass?.simpleName} task=${env.aiFrames.tasks.cached(id)?.status}")
                }
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                File(context.cacheDir, "ai-task-failure.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
                fail("Timed out in $phase")
            }
        }
        suspend fun capture(name: String) {
            instrumentation.waitForIdleSync()
            delay(250)
            val directory = File(context.cacheDir, "ai-task-ui").apply { mkdirs() }
            val image = instrumentation.uiAutomation.takeScreenshot()
            File(directory, "$language-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        try {
            delay(6500)
            instrumentation.runOnMainSync {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>().lastOrNull()?.let { activity = it }
                descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.MainContainerView>().single()
                    .selectSection(dev.photopilot.scaffold.ui.MainSection.FRAMES)
            }
            instrumentation.runOnMainSync { MainActivity::class.java.getDeclaredMethod("openAIFrameEditor", FrameEntity::class.java, Map::class.java).apply { isAccessible = true }.invoke(activity, null, draft) }
            var initialized = false
            await { instrumentation.runOnMainSync { initialized = editor().isAttachedToWindow && AIFrameView::class.java.getDeclaredField("initialized").apply { isAccessible = true }.getBoolean(editor()) && editor().draftState()["folders"] == draft["folders"] }; initialized }
            lateinit var before: Map<String, String>
            instrumentation.runOnMainSync { before = editor().draftState() }
            if (!runPreview) {
                instrumentation.runOnMainSync { labels(editor()).first { it.text.toString() in setOf("Manage model download", "前往下载模型") }.performClick() }
                var manager: AIModelsView? = null
                await { instrumentation.runOnMainSync { manager = descendants(activity.window.decorView).filterIsInstance<AIModelsView>().lastOrNull() }; manager != null }
                phase = "requested model binding and top alignment"
                var focusReady = false
                await {
                    instrumentation.runOnMainSync {
                        val settings = descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.settings.SettingsView>().lastOrNull()
                        val title = labels(requireNotNull(manager)).first { it.text.toString() == if (language == "zh") model.chinese else model.english }
                        val bounds = android.graphics.Rect()
                        val hostPosition = IntArray(2)
                        settings?.getLocationInWindow(hostPosition)
                        val topOffset = if (title.getGlobalVisibleRect(bounds)) bounds.top - hostPosition[1] else -1
                        focusReady = settings != null && title.isLaidOut && bounds.height() == title.height && topOffset >= 0 && topOffset <= 16 * activity.resources.displayMetrics.density
                    }
                    focusReady
                }
                instrumentation.runOnMainSync {
                    val settings = descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.settings.SettingsView>().lastOrNull()
                    assertNotNull("Model management must open the same settings panel", settings)
                    assertTrue("Model controls belong directly to the settings panel", descendants(requireNotNull(settings)).any { it === manager })
                    assertEquals("Only the settings host scrolls", 1, descendants(requireNotNull(settings)).filterIsInstance<android.widget.ScrollView>().size)
                    val title = labels(requireNotNull(manager)).first { it.text.toString() == if (language == "zh") model.chinese else model.english }
                    val bounds = android.graphics.Rect()
                    val titlePosition = IntArray(2).also(title::getLocationInWindow)
                    val host = requireNotNull(settings)
                    val visible = title.getGlobalVisibleRect(bounds)
                    assertTrue("Requested model is visible, titleY=${titlePosition[1]} titleHeight=${title.height} hostScroll=${host.scrollY} hostHeight=${host.height} contentHeight=${host.getChildAt(0).height}", visible)
                    assertEquals(title.height, bounds.height())
                    val hostPosition = IntArray(2).also(requireNotNull(settings)::getLocationInWindow)
                    assertTrue("The requested model starts at the top of the settings viewport", bounds.top - hostPosition[1] <= 16 * activity.resources.displayMetrics.density)
                }
                capture(if (missingCompact) "compact-model-focus" else "model-management")
                instrumentation.runOnMainSync {
                    val settings = descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.settings.SettingsView>().last()
                    clickBack(settings.parent as View)
                }
                instrumentation.runOnMainSync {
                    val appRoot = MainActivity::class.java.getDeclaredField("root").apply { isAccessible = true }.get(activity) as dev.photopilot.scaffold.ui.AppRootView
                    assertSame("Back reveals the original AI draft", editor(), appRoot.topOverlay())
                    assertEquals(before, editor().draftState())
                }
                capture("returned-draft")
                instrumentation.runOnMainSync {
                    clickBack(editor())
                    descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.MainContainerView>().single()
                        .selectSection(dev.photopilot.scaffold.ui.MainSection.SETTINGS)
                }
                phase = "ordinary settings section"
                var ordinarySettings: dev.photopilot.scaffold.ui.settings.SettingsView? = null
                await {
                    instrumentation.runOnMainSync {
                        ordinarySettings = descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.settings.SettingsView>().lastOrNull()
                        manager = ordinarySettings?.let { descendants(it).filterIsInstance<AIModelsView>().singleOrNull() }
                    }
                    manager != null
                }
                lateinit var groupHeader: View
                instrumentation.runOnMainSync {
                    val settings = requireNotNull(ordinarySettings)
                    val heading = labels(settings).single { it.text.toString() == activity.getString(R.string.settings_ai_models) }
                    groupHeader = heading.parent as View
                    val body = requireNotNull(manager).parent as View
                    if (body.visibility != View.GONE) groupHeader.performClick()
                    assertEquals("The normal settings group collapses in place", View.GONE, body.visibility)
                    settings.scrollTo(0, groupHeader.top)
                }
                delay(200)
                capture("settings-ai-collapsed")
                instrumentation.runOnMainSync {
                    assertTrue(groupHeader.performClick())
                    assertEquals("Opening the group immediately exposes model controls", View.VISIBLE, (requireNotNull(manager).parent as View).visibility)
                    assertEquals("Normal settings uses one scroll container", 1, descendants(requireNotNull(ordinarySettings)).filterIsInstance<android.widget.ScrollView>().size)
                    requireNotNull(ordinarySettings).scrollTo(0, groupHeader.top)
                }
                delay(300)
                capture("settings-ai-expanded")
                phase = "ordinary settings final model action"
                var finalAction: TextView? = null
                val modelActions = setOf("Download", "下载", "Continue / retry download", "继续 / 重试下载", "Pause download", "暂停下载", "Delete model", "删除模型")
                val finalModel = env.aiFrames.models.models.last()
                await {
                    instrumentation.runOnMainSync {
                        val finalModelTitle = labels(requireNotNull(manager)).single { it.text.toString() in setOf(finalModel.english, finalModel.chinese) }
                        finalAction = labels(finalModelTitle.parent as View).lastOrNull { it.text.toString() in modelActions && it.isShown }
                    }
                    finalAction != null
                }
                instrumentation.runOnMainSync {
                    val action = requireNotNull(finalAction)
                    action.requestRectangleOnScreen(android.graphics.Rect(0, 0, action.width, action.height), true)
                    val visible = android.graphics.Rect()
                    assertTrue("The final model's available action is reachable by the settings scroll", action.getGlobalVisibleRect(visible))
                    assertEquals("Model actions remain fully visible at large font sizes", action.height, visible.height())
                    assertTrue("Model actions keep their touch target", action.height >= 44 * activity.resources.displayMetrics.density)
                }
                capture("settings-ai-controls")
            } else {
                val entered = CompletableDeferred<Unit>()
                leaseScope.launch { env.aiFrames.models.withModelFiles(model) { entered.complete(Unit); release.await() } }
                entered.await()
                instrumentation.runOnMainSync { labels(editor()).first { it.text.toString() in setOf("Scan and preview", "扫描并预览") }.performClick() }
                phase = "process task start"
                await { env.aiFrames.tasks.running(id) }
                lateinit var restore: Map<String, String>
                instrumentation.runOnMainSync {
                    restore = editor().draftState()
                    clickBack(editor())
                }
                assertTrue("Closing a view keeps the process task alive", env.aiFrames.tasks.running(id))
                env.aiFrames.tasks.pause(id).join()
                release.complete(Unit)
                withTimeout(15000) { while (env.aiFrames.tasks.running(id)) delay(50) }
                val generation = env.aiFrames.tasks.record(id)?.generation
                instrumentation.runOnMainSync { MainActivity::class.java.getDeclaredMethod("openAIFrameEditor", FrameEntity::class.java, Map::class.java).apply { isAccessible = true }.invoke(activity, null, restore) }
                var visible = false
                phase = "stored preview restoration"
                await { instrumentation.runOnMainSync { visible = labels(editor()).any { it.text.toString() in setOf("Confirm frame creation", "确认创建相框") && it.isShown && it.isEnabled } }; visible }
                assertFalse(env.aiFrames.tasks.running(id))
                assertEquals(generation, env.aiFrames.tasks.record(id)?.generation)
                if (!editDuringLoad) {
                    instrumentation.runOnMainSync {
                        val names = AIFrameView::class.java.getDeclaredField("previewNames").apply { isAccessible = true }.get(editor()) as List<*>
                        (names.first() as android.widget.EditText).setText("My saved preview name")
                        restore = editor().draftState()
                        clickBack(editor())
                        MainActivity::class.java.getDeclaredMethod("openAIFrameEditor", FrameEntity::class.java, Map::class.java).apply { isAccessible = true }.invoke(activity, null, restore)
                    }
                    phase = "edited preview name restoration"
                    await { instrumentation.runOnMainSync { visible = labels(editor()).any { it.text.toString() in setOf("Confirm frame creation", "确认创建相框") && it.isShown && it.isEnabled } }; visible }
                    instrumentation.runOnMainSync {
                        val names = AIFrameView::class.java.getDeclaredField("previewNames").apply { isAccessible = true }.get(editor()) as List<*>
                        assertEquals("My saved preview name", (names.first() as android.widget.EditText).text.toString())
                    }
                    env.aiFrames.tasks.pause(id).join()
                    delay(500)
                    capture("paused-results")
                    instrumentation.runOnMainSync { labels(editor()).first { it.text.toString() in setOf("Confirm frame creation", "确认创建相框") }.performClick() }
                    phase = "paused home card"
                    await {
                        instrumentation.runOnMainSync { visible = descendants(activity.window.decorView).none { it is AIFrameView } && descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.frames.FramesView>().any { home -> home.isShown && descendants(home).filterIsInstance<TextView>().any { it.text.toString() in setOf("Continue AI classification", "继续 AI 分类") && it.isShown } } }
                        visible
                    }
                    val saved = requireNotNull(env.aiFrames.tasks.record(id))
                    val frameNames = saved.frameIds.map { requireNotNull(env.database.frameDao().getById(it)).name }.toSet()
                    instrumentation.runOnMainSync {
                        val home = descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.frames.FramesView>().single { it.isShown }
                        assertTrue("Zero-photo paused cards omit placeholder covers", descendants(home).none { it.javaClass.simpleName == "CoverView" })
                        val firstTitleTop = descendants(home).filterIsInstance<TextView>().filter { it.text.toString() in frameNames }
                            .minOf { title -> IntArray(2).also(title::getLocationInWindow)[1] }
                        assertTrue("Paused-frame titles remain near the top", firstTitleTop < 240 * activity.resources.displayMetrics.density)
                    }
                    capture("paused-home-card")
                    assertTrue(saved.frameIds.all { env.aiFrames.state.get(it)?.userPaused == true })
                }
                if (editDuringLoad) {
                    val recordsField = env.aiFrames.tasks.javaClass.getDeclaredField("records").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val oldRecords = recordsField.get(env.aiFrames.tasks) as java.util.concurrent.ConcurrentHashMap<String, dev.photopilot.scaffold.data.aiframes.AITaskRecord>
                    originalRecords = oldRecords
                    val enteredResult = CompletableDeferred<Unit>()
                    val watchedRecords = object : java.util.concurrent.ConcurrentHashMap<String, dev.photopilot.scaffold.data.aiframes.AITaskRecord>(oldRecords) {
                        override fun get(key: String): dev.photopilot.scaffold.data.aiframes.AITaskRecord? {
                            if (key == id && Thread.currentThread().stackTrace.any { it.methodName == "previewResult" }) enteredResult.complete(Unit)
                            return super.get(key)
                        }
                    }
                    instrumentation.runOnMainSync {
                        (AIFrameView::class.java.getDeclaredField("polling").apply { isAccessible = true }.get(editor()) as? Job)?.cancel()
                    }
                    delay(300)
                    @Suppress("UNCHECKED_CAST")
                    val resultMap = env.aiFrames.tasks.javaClass.getDeclaredField("results").apply { isAccessible = true }.get(env.aiFrames.tasks) as java.util.concurrent.ConcurrentHashMap<String, *>
                    val transactionEntered = CompletableDeferred<Unit>()
                    val loadGate = CompletableDeferred<Unit>()
                    snapshotGate = loadGate
                    val blocker = leaseScope.launch {
                        env.database.withTransaction {
                            transactionEntered.complete(Unit)
                            loadGate.await()
                        }
                    }
                    transactionEntered.await()
                    recordsField.set(env.aiFrames.tasks, watchedRecords)
                    resultMap.remove(id)
                    val reloadDone = CompletableDeferred<Unit>()
                    val completion = object : kotlin.coroutines.Continuation<Unit> {
                        override val context = Dispatchers.Main
                        override fun resumeWith(result: Result<Unit>) {
                            result.fold({ reloadDone.complete(Unit) }, { reloadDone.completeExceptionally(it) })
                        }
                    }
                    instrumentation.runOnMainSync {
                        AIFrameView::class.java.getDeclaredField("pendingPreview").apply { isAccessible = true }.set(editor(), null)
                        AIFrameView::class.java.getDeclaredField("shownTaskGeneration").apply { isAccessible = true }.set(editor(), null)
                        val returned = AIFrameView::class.java.getDeclaredMethod("refreshProgress", kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }.invoke(editor(), completion)
                        if (returned != kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) reloadDone.complete(Unit)
                    }
                    withTimeout(5000) { enteredResult.await() }
                    instrumentation.runOnMainSync {
                        val descriptions = AIFrameView::class.java.getDeclaredField("descriptions").apply { isAccessible = true }.get(editor()) as android.widget.EditText
                        descriptions.setText("Changed rule after snapshot request")
                    }
                    loadGate.complete(Unit)
                    blocker.join()
                    withTimeout(5000) { reloadDone.await() }
                    instrumentation.runOnMainSync {
                        assertNull("Old task data must not become the pending result for changed rules", AIFrameView::class.java.getDeclaredField("pendingPreview").apply { isAccessible = true }.get(editor()))
                        assertFalse("An old result must not adopt the edited form identity", labels(editor()).any { it.text.toString() in setOf("Confirm frame creation", "确认创建相框") && it.isShown && it.isEnabled })
                    }
                }
            }
        } finally {
            release.complete(Unit)
            snapshotGate?.complete(Unit)
            originalRecords?.let { env.aiFrames.tasks.javaClass.getDeclaredField("records").apply { isAccessible = true }.set(env.aiFrames.tasks, it) }
            leaseScope.coroutineContext[Job]?.cancelAndJoin()
            env.aiFrames.tasks.pause(id).join()
            env.aiFrames.tasks.record(id)?.frameIds.orEmpty().forEach { env.frameManager.deleteFrame(it) }
            instrumentation.runOnMainSync {
                activity.finish()
                if (android.os.Build.VERSION.SDK_INT >= 33 && previousLocales != null) context.getSystemService(android.app.LocaleManager::class.java).applicationLocales = previousLocales
            }
            if (!runPreview && markerBefore != null) marker.writeBytes(markerBefore)
            env.database.assetDao().getById(id)?.let { env.database.assetDao().delete(it) }
            syntheticFile.delete()
            env.database.collectionDao().getById(id)?.let { env.database.collectionDao().delete(it) }
            env.database.sourceDao().getById(id)?.let { env.database.sourceDao().delete(it) }
            uiPrefs.edit().apply {
                if (previousGroupState == null) remove(aiGroupKey) else putBoolean(aiGroupKey, previousGroupState)
            }.commit()
            for ((key, row) in previous) {
                if (row == null) env.database.settingsKvDao().get(key)?.let { env.database.settingsKvDao().delete(it) }
                else env.database.settingsKvDao().upsert(row)
            }
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
