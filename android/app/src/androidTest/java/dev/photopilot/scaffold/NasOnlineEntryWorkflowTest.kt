package dev.photopilot.scaffold

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.ui.frames.FrameEditView
import dev.photopilot.scaffold.ui.frames.FramesView
import dev.photopilot.scaffold.ui.remote.RemoteFrameView
import dev.photopilot.scaffold.ui.remote.RemoteThumbnailView
import dev.photopilot.scaffold.ui.nas.NasFormView
import dev.photopilot.scaffold.ui.nas.NasDirectoryPickerView
import dev.photopilot.scaffold.ui.nas.NasLanNoticeView
import dev.photopilot.scaffold.ui.player.PlayerEngine
import dev.photopilot.scaffold.data.remote.PlaybackItem
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.net.NasTlsPolicy
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.ui.library.LibraryView
import dev.photopilot.scaffold.ui.sources.SourcesView
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NasOnlineEntryWorkflowTest {
    private var localEditorFixtureId: String? = null

    private suspend fun ensureLocalEditorFixture(env: AppEnvironment) {
        if (env.database.assetDao().browseReadyPage(1, 0).isNotEmpty()) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "nas-local-entry-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val file = java.io.File(context.filesDir, "originals/$id.png").apply { parentFile!!.mkdirs() }
        val image = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val hash = dev.photopilot.scaffold.data.logic.ContentHash.sha256Hex(file.readBytes())
        env.database.sourceDao().upsert(SourceEntity(id, SourceType.LOCAL_FILES, "Synthetic local entry", null, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, null, now, now))
        env.database.sourceAssetDao().upsert(SourceAssetEntity(id, id, "$id.png", null, "1", file.length(), null, now, 0, AccessState.ACCESSIBLE, now, now))
        env.database.assetDao().upsert(AssetEntity(id, hash, file.length(), AssetFormat.PNG, 16, 16, 1, OrientationClass.SQUARE, null, CapturedAtSource.UNKNOWN, now, "$id.png", "Synthetic.png", AssetReadiness.READY, null, false, null, deletedAt = null, createdAt = now, updatedAt = now))
        env.database.assetOriginDao().upsert(AssetOriginEntity(id, id, now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
        localEditorFixtureId = id
    }

    private suspend fun cleanupLocalEditorFixture(env: AppEnvironment) {
        val id = localEditorFixtureId ?: return
        env.database.assetDao().getById(id)?.let { env.database.assetDao().delete(it) }
        env.database.sourceDao().getById(id)?.let { env.database.sourceDao().delete(it) }
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "originals/$id.png").delete()
        localEditorFixtureId = null
    }
    @Test fun metadataOnlyNasFoldersAppearThroughLibraryEntry() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        env.settings.setOnboardingCompleted(true)
        env.settings.putRaw("overlay_stack", "{\"stack\":[]}")
        val id = "nas-entry-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val sources = listOf(SourceType.SMB, SourceType.WEBDAV).map { type ->
            SourceEntity("$id-$type", type, "Online $type", null, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true}", now, now)
        }
        for (source in sources) {
            env.database.sourceDao().upsert(source)
            env.database.collectionDao().upsert(CollectionEntity(source.id, null, source.id, "Family", "family", CollectionType.SOURCE_FOLDER, "", now, now))
            env.database.sourceAssetDao().upsert(SourceAssetEntity(source.id, source.id, "family/photo.png", null, "1", 64, null, now, 0, AccessState.ACCESSIBLE, now, now))
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        SystemClock.sleep(6500)
        try {
            val callbacks = CountDownLatch(16)
            val snapshots = java.util.Collections.synchronizedList(mutableListOf<List<Pair<CollectionEntity, Long>>>())
            instrumentation.runOnMainSync {
                val library = MainActivity::class.java.getDeclaredMethod("buildLibraryView").apply { isAccessible = true }.invoke(activity) as LibraryView
                repeat(8) { library.dataSource!!.fetchCollections { snapshots.add(it); callbacks.countDown() } }
                val create = MainActivity::class.java.getDeclaredMethod("openFrameEditor", FrameEntity::class.java, Map::class.java).apply { isAccessible = true }
                create.invoke(activity, null, null)
                val editor = descendants(activity.window.decorView).filterIsInstance<FrameEditView>().last()
                repeat(8) { editor.dataSource!!.fetchCollections { snapshots.add(it); callbacks.countDown() } }
            }
            assertTrue(callbacks.await(15, TimeUnit.SECONDS))
            for (collections in snapshots) {
                assertEquals("Each overlapping library and frame query retains both NAS folder counts", 2,
                    collections.count { it.first.sourceId in sources.map { source -> source.id } && it.second == 1L })
            }
            instrumentation.runOnMainSync {
                val view = SourcesView(activity)
                view.renderNasSources(listOf(SourcesView.NasSourceModel(id, "Online", "Active")))
                assertTrue("An active NAS has a browse action", descendants(view).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.sources_browse_photos) })
            }
            assertTrue(sources.all { env.database.importJobDao().getBySource(it.id).isEmpty() })
            assertTrue(env.database.assetOriginDao().getBySourceAssets(sources.map { it.id }).isEmpty())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            for (source in sources) {
                env.database.collectionDao().getBySource(source.id).forEach { env.database.collectionDao().delete(it) }
                env.database.sourceDao().delete(source)
            }
        }
    }

    @Test fun localWebDavCatalogBrowseAndPlayerKeepOriginalsEmpty() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val endpoint = "nas-local-dav-" + UUID.randomUUID()
        val bytes = java.io.ByteArrayOutputStream().also {
            android.graphics.Bitmap.createBitmap(48, 32, android.graphics.Bitmap.Config.ARGB_8888).apply {
                val canvas = android.graphics.Canvas(this)
                val paint = android.graphics.Paint().apply { color = 0xffffbb55.toInt() }
                canvas.drawRect(0f, 0f, 48f, 16f, paint)
                paint.color = 0xff227788.toInt()
                canvas.drawRect(0f, 16f, 48f, 32f, paint)
            }.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val server = java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("10.0.2.15"))
        val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        workers.launch {
            while (isActive) {
                val connection = try { server.accept() } catch (_: Exception) { break }
                launch {
                    connection.use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val method = reader.readLine().orEmpty().substringBefore(' ')
                        var length = 0
                        while (true) {
                            val line = reader.readLine().orEmpty()
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        }
                        repeat(length) { reader.read() }
                        val body = if (method == "PROPFIND")
                            """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/online.png</d:href><d:propstat><d:prop><d:getcontentlength>${bytes.size}</d:getcontentlength><d:getetag>v1</d:getetag><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""".toByteArray()
                        else bytes
                        val status = if (method == "PROPFIND") "207 Multi-Status" else "200 OK"
                        socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                    }
                }
            }
        }
        env.tofuPins.addCleartextHost(NasTlsPolicy.hostPortKey("10.0.2.15", server.localPort))
        env.credentialStore.save(endpoint, JsonMini.toJson(mapOf("protocol" to "webdav_http", "host" to "10.0.2.15", "port" to server.localPort, "path" to "")))
        val source = env.localSources.ensureNasSource(SourceType.WEBDAV, endpoint, "Local WebDAV fixture", 0,
            "{\"catalogOnly\":true,\"directories\":[\"\"],\"recursive\":true}")
        val framesBefore = env.frameManager.listFrames().map { it.id }.toSet()
        env.settings.setLanguage("en")
        assertTrue("First-use protocol fixture starts with zero frames", framesBefore.isEmpty())
        env.settings.setOnboardingCompleted(false)
        env.settings.putRaw("overlay_stack", "{\"stack\":[]}")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        SystemClock.sleep(6500)
        try {
            env.remoteMedia.index(source.id, "{\"directories\":[\"\"]}", requireNotNull(env.nasAdapterFor(source)))
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val finish = MainActivity::class.java.getDeclaredMethod("finishSourceConnection", SourceEntity::class.java,
                        List::class.java, kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
                    val result = finish.invoke(activity, source, null, continuation)
                    if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) continuation.resumeWith(Result.success(Unit))
                }
            }
            await("first-use photo chooser") { descendants(activity.window.decorView).filterIsInstance<android.widget.CheckBox>().any { it.text.toString() == "online.png" } }
            val chooser = descendants(activity.window.decorView).filterIsInstance<RemoteFrameView>().last()
            instrumentation.runOnMainSync {
                descendants(chooser).filterIsInstance<android.widget.CheckBox>().first { it.text.toString() == "online.png" }.performClick()
                descendants(chooser).filterIsInstance<TextView>().first { it.text.toString() == "Confirm and play" }.performClick()
            }
            await("first-use actual player decoded") {
                val player = descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.player.PlayerView>().lastOrNull()
                player != null && descendants(player).filterIsInstance<android.widget.ImageView>().any { it.drawable is android.graphics.drawable.BitmapDrawable }
            }
            val player = descendants(activity.window.decorView).filterIsInstance<dev.photopilot.scaffold.ui.player.PlayerView>().last()
            val photoBitmaps = listOf("foregroundA", "foregroundB").mapNotNull { field ->
                val image = dev.photopilot.scaffold.ui.player.PlayerView::class.java.getDeclaredField(field).apply { isAccessible = true }.get(player) as android.widget.ImageView
                (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            }
            assertTrue("Actual foreground contains the colored fixture pixels", photoBitmaps.any { bitmap ->
                bitmap.getPixel(bitmap.width / 2, bitmap.height / 4) == 0xffffbb55.toInt() &&
                    bitmap.getPixel(bitmap.width / 2, bitmap.height * 3 / 4) == 0xff227788.toInt()
            })
            instrumentation.waitForIdleSync()
            SystemClock.sleep(150)
            assertEquals(1, env.frames.playbackItems(env.frameManager.listFrames().single().id).size)
            assertEquals("remote", JsonMini.parseObject(env.frameManager.listFrames().single().settingsJson)["mediaStorageMode"])
            java.io.File(context.cacheDir, "nas-online-first-use-player.png").outputStream().use {
                instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue(env.database.importJobDao().getBySource(source.id).isEmpty())
            assertTrue(env.database.assetOriginDao().getBySourceAssets(env.database.sourceAssetDao().getBySource(source.id).map { it.id }).isEmpty())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            env.frameManager.listFrames().filter { it.id !in framesBefore }.forEach { env.frameManager.deleteFrame(it.id) }
            env.localSources.disconnect(source.id, true, System.currentTimeMillis(), removeConfiguration = true)
            env.credentialStore.delete(endpoint)
            server.close()
            workers.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    @Test fun realSmbIndexBrowseSelectFrameAndDecodedPlayer() = runBlocking {
        org.junit.Assume.assumeTrue("Disposable SMB server required", InstrumentationRegistry.getArguments().getString("smbFixture") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val endpoint = "nas-protocol-" + UUID.randomUUID()
        val policyKey = NasTlsPolicy.hostPortKey("10.0.2.2", 14451)
        env.tofuPins.addCleartextHost(policyKey)
        env.credentialStore.save(endpoint, JsonMini.toJson(mapOf(
            "protocol" to "smb", "host" to "10.0.2.2", "port" to 14451,
            "path" to "Photos", "username" to "photopilot_fixture", "password" to "fixture_only_141",
        )))
        val source = env.localSources.ensureNasSource(SourceType.SMB, endpoint, "Protocol fixture", 0,
            "{\"catalogOnly\":true,\"directories\":[\"Album\"],\"recursive\":true}")
        val framesBefore = env.frameManager.listFrames().map { it.id }.toSet()
        env.settings.setLanguage("en")
        env.settings.setOnboardingCompleted(true)
        env.settings.putRaw("overlay_stack", "{\"stack\":[]}")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        SystemClock.sleep(6500)
        try {
            env.remoteMedia.index(source.id, "{\"directories\":[\"Album\"]}", requireNotNull(env.nasAdapterFor(source)))
            val rows = env.database.sourceAssetDao().getBySource(source.id)
            assertEquals(2, rows.size)
            assertTrue(rows.any { it.remoteKey.contains("Nested/") })
            assertTrue(env.database.importJobDao().getBySource(source.id).isEmpty())
            assertTrue(env.database.assetOriginDao().getBySourceAssets(rows.map { it.id }).isEmpty())
            chooseAndPlay(activity, env, source, framesBefore, "Album", "lake.jpg")
            chooseAndPlay(activity, env, source, env.frameManager.listFrames().map { it.id }.toSet(), "Album", "lake.jpg", fromFrames = true)
            assertTrue(env.database.importJobDao().getBySource(source.id).isEmpty())
            assertTrue(env.database.assetOriginDao().getBySourceAssets(rows.map { it.id }).isEmpty())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            env.frameManager.listFrames().filter { it.id !in framesBefore }.forEach { env.frameManager.deleteFrame(it.id) }
            env.localSources.disconnect(source.id, true, System.currentTimeMillis(), removeConfiguration = true)
            env.credentialStore.delete(endpoint)
            cleanupLocalEditorFixture(env)
        }
    }

    private suspend fun chooseAndPlay(
        activity: MainActivity,
        env: AppEnvironment,
        source: SourceEntity,
        framesBefore: Set<String>,
        folder: String?,
        filename: String,
        fromFrames: Boolean = false,
        existingTarget: FrameEntity? = null,
    ): FrameEntity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (fromFrames) {
            ensureLocalEditorFixture(env)
            instrumentation.runOnMainSync {
                val home = MainActivity::class.java.getDeclaredMethod("buildFramesView").apply { isAccessible = true }.invoke(activity) as FramesView
                home.onCreate!!.invoke()
            }
            await("new frame editor") { descendants(activity.window.decorView).any { it is FrameEditView } }
            val localEditor = descendants(activity.window.decorView).filterIsInstance<FrameEditView>().last()
            instrumentation.runOnMainSync { descendants(localEditor).filterIsInstance<TextView>().first { it.text.toString() == activity.getString(R.string.library_tab_folders) }.performClick() }
            val collection = requireNotNull(env.database.collectionDao().getBySourceAndPath(source.id, requireNotNull(folder)))
            val count = env.remoteMedia.members(source.id, folder, false).size
            val folderLabel = "${collection.name} ($count)"
            await("NAS folder in new frame") { descendants(localEditor).filterIsInstance<android.widget.CheckBox>().any { it.text.toString() == folderLabel } }
            instrumentation.runOnMainSync { descendants(localEditor).filterIsInstance<android.widget.CheckBox>().first { it.text.toString() == folderLabel }.performClick() }
        } else {
            instrumentation.runOnMainSync {
                val previous = MainActivity::class.java.getDeclaredField("remoteFrameView").apply { isAccessible = true }.get(activity) as? RemoteFrameView
                previous?.onBack?.invoke()
                val view = MainActivity::class.java.getDeclaredMethod("buildSourcesView").apply { isAccessible = true }.invoke(activity) as SourcesView
                view.onNasBrowse!!.invoke(source.id)
            }
            val targetLabel = existingTarget?.name ?: activity.getString(R.string.import_pick_frame_new)
            if (existingTarget != null) assertTrue("Existing target remains available", env.frameManager.listFrames().any { it.id == existingTarget.id && it.name == targetLabel })
            try {
                withTimeout(10000) {
                    while (true) {
                        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                        dismissFullscreenEducation()
                        if (instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(targetLabel)?.isNotEmpty() == true) break
                        delay(100)
                    }
                }
            } catch (failure: TimeoutCancellationException) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                fun labels(node: android.view.accessibility.AccessibilityNodeInfo): List<String> =
                    listOfNotNull(node.text?.toString()?.takeIf { node.className?.toString() != "android.widget.EditText" }) +
                        (0 until node.childCount).flatMap { node.getChild(it)?.let(::labels).orEmpty() }
                throw AssertionError("Frame target dialog missing, remoteEditor=${MainActivity::class.java.getDeclaredField("remoteFrameView").apply { isAccessible = true }.get(activity) != null}, visible=${root?.let(::labels)}", failure)
            }
            val node = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(targetLabel).last()
            assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        await("remote editor") { descendants(activity.window.decorView).any { it is RemoteFrameView } }
        val editor = descendants(activity.window.decorView).filterIsInstance<RemoteFrameView>().last()
        if (folder != null && !fromFrames) {
            await("folder navigation") { descendants(editor).filterIsInstance<TextView>().any { it.text.toString() == folder } }
            instrumentation.runOnMainSync { descendants(editor).filterIsInstance<TextView>().first { it.text.toString() == folder }.performClick() }
        }
        await("photo checkbox") { descendants(editor).filterIsInstance<android.widget.CheckBox>().any { it.text.toString() == filename } }
        instrumentation.runOnMainSync {
            assertEquals("remote", editor.draftState()["mode"])
            descendants(editor).filterIsInstance<android.widget.CheckBox>().first { it.text.toString() == filename }.performClick()
        }
        val selectedAsset = env.database.sourceAssetDao().getBySource(source.id).first { it.remoteKey.substringAfterLast('/') == filename }
        val selectedItem = requireNotNull(env.remoteMedia.item(selectedAsset.id))
        assertNull("Online selection has no stored original", selectedItem.local)
        assertNotNull("Direct NAS thumbnail decoding succeeds", env.remoteMedia.bitmap(selectedItem, 192, 192))
        val thumbnail = descendants(editor).filterIsInstance<RemoteThumbnailView>().first()
        await("thumbnail laid out") { thumbnail.width > 0 && thumbnail.height > 0 }
        instrumentation.runOnMainSync { thumbnail.requestRectangleOnScreen(android.graphics.Rect(0, 0, thumbnail.width, thumbnail.height), true) }
        await("thumbnail decoded") { thumbnail.drawable != null }
        java.io.File(context.cacheDir, "nas-online-visible-${source.type}.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        instrumentation.runOnMainSync { descendants(editor).filterIsInstance<TextView>().single { it.text.toString() in setOf("Save frame", "Confirm and play") }.performClick() }
        val expectedCount = if (existingTarget == null) 1 else 2
        await("frame saved") {
            if (existingTarget == null) env.frameManager.listFrames().any { it.id !in framesBefore }
            else env.frames.playbackItems(existingTarget.id).size == expectedCount
        }
        await("Saved remote editor closed") {
            MainActivity::class.java.getDeclaredField("remoteFrameView").apply { isAccessible = true }.get(activity) !== editor
        }
        val frame = if (existingTarget == null) env.frameManager.listFrames().single { it.id !in framesBefore }
            else requireNotNull(env.database.frameDao().getById(existingTarget.id))
        assertEquals(expectedCount, env.frames.playbackItems(frame.id).size)
        lateinit var home: FramesView
        instrumentation.runOnMainSync { home = MainActivity::class.java.getDeclaredMethod("buildFramesView").apply { isAccessible = true }.invoke(activity) as FramesView }
        await { (FramesView::class.java.getDeclaredField("currentCards").apply { isAccessible = true }.get(home) as List<*>).any { (it as FramesView.CardModel).frame.id == frame.id && it.playable == expectedCount } }
        assertFalse(FramesView::class.java.getDeclaredField("currentLibraryEmpty").apply { isAccessible = true }.getBoolean(home))
        val displayed = CountDownLatch(1)
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var player: PlayerEngine? = null
        val listener = object : PlayerEngine.Listener {
            override fun onStateChanged(state: String) = Unit
            override fun onShowAsset(asset: PlaybackItem, decoded: PlayerEngine.DecodedAsset, transitionMs: Long, direction: Int, position: Int) { displayed.countDown() }
            override fun onShowVideo(asset: PlaybackItem, path: String, blurred: android.graphics.Bitmap?, edgeColor: Int?) = Unit
            override fun onClearTextures() = Unit
            override fun onShowSafeImage() = Unit
            override fun onQueueInfo(size: Int) = Unit
            override fun onMediaLoadError(code: String) = Unit
            override fun onDecodeSkipped() = Unit
            override fun onKeepScreenOn(keep: Boolean) = Unit
        }
        try {
            instrumentation.runOnMainSync { player = PlayerEngine(context, env, frame, listener, uiScope); player!!.start(false) }
            assertTrue("Selected remote photo reaches decoded player", displayed.await(120, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { player?.stop(true) }
            uiScope.coroutineContext[Job]?.cancel()
        }
        return frame
    }

    @Test fun existingReviewNasFormsBrowseAndPlayBothProtocols() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val configFile = java.io.File(context.cacheDir, "nas141-review.json")
        org.junit.Assume.assumeTrue("Private review NAS runtime configuration required", configFile.isFile)
        val config = org.json.JSONObject(configFile.readText())
        val env = AppEnvironment.shared(context)
        env.settings.setLanguage("en")
        env.settings.setOnboardingCompleted(true)
        env.settings.putRaw("overlay_stack", "{\"stack\":[]}")
        for (protocol in listOf("smb", "webdav")) {
            if (protocol == "smb") {
                val client = dev.photopilot.scaffold.data.net.SmbClient(config.getString("host"), config.getInt("smbPort"),
                    config.getString("smbShare"), config.getString("username"), config.getString("password"),
                    tlsPolicy = AppEnvironment.newNasTlsPolicy(env.tofuPins))
                try {
                    val security = client.security()
                    assertTrue("Review NAS SMB session is encrypted", security.encrypted)
                    assertTrue("Review NAS negotiates SMB3", security.dialect.contains("3"))
                } finally { client.close() }
            } else assertEquals("https", java.net.URI(config.getString("webdav")).scheme)
            val beforeSources = env.database.sourceDao().getAll().map { it.id }.toSet()
            val framesBefore = env.frameManager.listFrames().map { it.id }.toSet()
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            SystemClock.sleep(6500)
            try {
                instrumentation.runOnMainSync {
                    val view = MainActivity::class.java.getDeclaredMethod("buildSourcesView").apply { isAccessible = true }.invoke(activity) as SourcesView
                    view.onNasClick!!.invoke()
                }
                await { descendants(activity.window.decorView).any { it is NasFormView || it is NasLanNoticeView } }
                descendants(activity.window.decorView).filterIsInstance<NasLanNoticeView>().lastOrNull()?.let { notice -> instrumentation.runOnMainSync { notice.onContinue!!.invoke() } }
                await { descendants(activity.window.decorView).any { it is NasFormView } }
                val form = descendants(activity.window.decorView).filterIsInstance<NasFormView>().last()
                val url = java.net.URI(config.getString("webdav"))
                instrumentation.runOnMainSync {
                    form.prefill(protocol, if (protocol == "smb") config.getString("host") else url.host,
                        if (protocol == "smb") config.getInt("smbPort") else url.port,
                        if (protocol == "smb") config.getString("smbShare") else url.path.trim('/'),
                        config.getString("username"), config.getString("password"))
                    assertTrue(form.currentForm()!!.catalogOnly)
                    descendants(form).filterIsInstance<TextView>().last { it.text.toString() == activity.getString(R.string.common_next) }.performClick()
                }
                await("NAS connection and directory picker") { descendants(activity.window.decorView).any { it is NasDirectoryPickerView } }
                val picker = descendants(activity.window.decorView).filterIsInstance<NasDirectoryPickerView>().last()
                await { descendants(picker).filterIsInstance<android.widget.CheckBox>().any { it.contentDescription?.toString() == activity.getString(R.string.source_nas_picker_whole) } }
                instrumentation.runOnMainSync {
                    descendants(picker).filterIsInstance<android.widget.CheckBox>().first { it.contentDescription?.toString() == activity.getString(R.string.source_nas_picker_whole) }.performClick()
                    descendants(picker).filterIsInstance<TextView>().last { it.text.toString() == activity.getString(R.string.common_save) }.performClick()
                }
                await("NAS source saved") { env.database.sourceDao().getAll().any { it.id !in beforeSources } }
                val source = env.database.sourceDao().getAll().single { it.id !in beforeSources }
                await("NAS indexed six photos") { env.database.sourceAssetDao().getBySource(source.id).size >= 6 }
                val rows = env.database.sourceAssetDao().getBySource(source.id)
                val rootPhoto = rows.first { !it.remoteKey.contains('/') }
                instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", "$protocol source entry → new frame\n") })
                val firstFrame = chooseAndPlay(activity, env, source, framesBefore, null, rootPhoto.remoteKey)
                val existingTarget = firstFrame.copy(name = "NAS existing target " + UUID.randomUUID())
                env.database.frameDao().update(existingTarget)
                val nestedPhoto = rows.first { it.remoteKey.contains('/') }
                instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", "$protocol nested folder → new frame\n") })
                chooseAndPlay(activity, env, source, env.frameManager.listFrames().map { it.id }.toSet(),
                    nestedPhoto.remoteKey.substringBeforeLast('/'), nestedPhoto.remoteKey.substringAfterLast('/'), fromFrames = true)
                instrumentation.sendStatus(1, android.os.Bundle().apply { putString("stream", "$protocol source entry → existing frame\n") })
                chooseAndPlay(activity, env, source, env.frameManager.listFrames().map { it.id }.toSet(),
                    nestedPhoto.remoteKey.substringBeforeLast('/'), nestedPhoto.remoteKey.substringAfterLast('/'), existingTarget = existingTarget)
                assertTrue(env.database.importJobDao().getBySource(source.id).isEmpty())
                assertTrue(env.database.assetOriginDao().getBySourceAssets(rows.map { it.id }).isEmpty())
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                env.frameManager.listFrames().filter { it.id !in framesBefore }.forEach { env.frameManager.deleteFrame(it.id) }
                cleanupLocalEditorFixture(env)
                env.database.sourceDao().getAll().filter { it.id !in beforeSources }.forEach { env.localSources.disconnect(it.id, true, System.currentTimeMillis(), removeConfiguration = true) }
            }
        }
    }

    private fun dismissFullscreenEducation() {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return
        if (root.findAccessibilityNodeInfosByText("Viewing full screen").isEmpty()) return
        var action = root.findAccessibilityNodeInfosByText("Got it").firstOrNull() ?: return
        while (!action.isClickable && action.parent != null) action = action.parent
        action.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
    }

    private suspend fun await(label: String = "NAS workflow condition", predicate: suspend () -> Boolean) {
        try { withTimeout(120000) { while (true) {
            if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            dismissFullscreenEducation()
            if (predicate()) break
            delay(100)
        } } }
        catch (failure: TimeoutCancellationException) { throw AssertionError(label, failure) }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
