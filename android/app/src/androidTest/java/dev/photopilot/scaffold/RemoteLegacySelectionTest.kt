package dev.photopilot.scaffold

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.aiframes.*
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.repo.SelectionRefEdit
import dev.photopilot.scaffold.ui.remote.RemoteFrameView
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RemoteLegacySelectionTest {
    @Test fun firstNasAdditionToLegacyLocalFrameDefaultsOnlineAndPreservesLocalPhoto() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val db = env.database
        val id = "legacy-storage-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val bytes = java.io.ByteArrayOutputStream().also {
            android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val file = java.io.File(context.filesDir, "originals/$id.png").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val localSource = SourceEntity("$id-local", SourceType.LOCAL_FILES, "Local fixture", null, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{}", now, now)
        val nasSource = localSource.copy(id = "$id-nas", type = SourceType.SMB, settingsJson = "{\"catalogOnly\":true}")
        db.sourceDao().upsert(localSource)
        db.sourceDao().upsert(nasSource)
        val localRef = SourceAssetEntity("$id-local-ref", localSource.id, "local.png", null, "1", bytes.size.toLong(), null, now, 0, AccessState.ACCESSIBLE, now, now)
        val nasRef = localRef.copy(id = "$id-nas-ref", sourceId = nasSource.id, remoteKey = "online.png")
        db.sourceAssetDao().upsert(localRef)
        db.sourceAssetDao().upsert(nasRef)
        val asset = AssetEntity(id, dev.photopilot.scaffold.data.logic.ContentHash.sha256Hex(bytes), bytes.size.toLong(), AssetFormat.PNG, 16, 16, 1, OrientationClass.SQUARE, null, CapturedAtSource.UNKNOWN, now, "$id.png", "local.png", AssetReadiness.READY, null, false, null, deletedAt = null, createdAt = now, updatedAt = now)
        db.assetDao().upsert(asset)
        db.assetOriginDao().upsert(AssetOriginEntity(id, localRef.id, now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
        val frame = env.frameManager.createFrame("Legacy local fixture")
        assertFalse(org.json.JSONObject(frame.settingsJson).has("mediaStorageMode"))
        env.frames.save(frame.id, 0, addIncludes = listOf(SelectionRefEdit(id, null)))
        env.settings.setOnboardingCompleted(true)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val saved = CountDownLatch(1)
        lateinit var view: RemoteFrameView
        try {
            val current = requireNotNull(db.frameDao().getById(frame.id))
            instrumentation.runOnMainSync {
                view = RemoteFrameView(activity, env, current, mapOf("sourceId" to nasSource.id, "selected" to "[\"${nasRef.id}\"]"))
                view.onSaved = { saved.countDown() }
                activity.setContentView(view)
            }
            withTimeout(15000) { while (org.json.JSONArray(view.draftState().getValue("selected")).length() != 1) delay(100) }
            instrumentation.runOnMainSync {
                val sync = descendants(view).filterIsInstance<android.widget.Switch>().single()
                assertFalse("Adding first NAS photo requires explicit offline opt-in", sync.isChecked)
            }
            withTimeout(15000) {
                while (descendants(view).filterIsInstance<android.widget.TextView>().first { it.text.toString() in listOf("Resume local sync", "继续本地同步") }.isEnabled) delay(100)
            }
            instrumentation.runOnMainSync {
                descendants(view).filterIsInstance<android.widget.TextView>().first { it.text.toString() in listOf("Save frame", "保存相框") }.performClick()
            }
            assertTrue(saved.await(15, TimeUnit.SECONDS))
            val updated = requireNotNull(db.frameDao().getById(frame.id))
            assertEquals("remote", org.json.JSONObject(updated.settingsJson).getString("mediaStorageMode"))
            val selections = db.frameSelectionDao().getByFrame(frame.id)
            assertTrue(selections.any { it.assetId == id && it.mode == FrameSelectionMode.INCLUDE })
            assertTrue(selections.any { it.sourceAssetId == nasRef.id && it.mode == FrameSelectionMode.INCLUDE })
            assertTrue(file.isFile)
            assertEquals(2, env.frames.playbackItems(frame.id).size)
            assertTrue(db.assetOriginDao().getBySourceAssets(listOf(nasRef.id)).isEmpty())
            assertTrue(db.importJobDao().getBySource(nasSource.id).isEmpty())
            assertEquals("paused", org.json.JSONObject(requireNotNull(db.settingsKvDao().get("remote.sync.${frame.id}")).valueJson).getString("state"))
            instrumentation.runOnMainSync {
                val explicitLocal = updated.copy(settingsJson = org.json.JSONObject(updated.settingsJson).put("mediaStorageMode", "local").toString())
                val explicitView = RemoteFrameView(activity, env, explicitLocal)
                activity.setContentView(explicitView)
                assertTrue("Explicit offline preference remains selected", descendants(explicitView).filterIsInstance<android.widget.Switch>().single().isChecked)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            env.frameManager.deleteFrame(frame.id)
            db.assetDao().delete(asset)
            db.sourceDao().delete(localSource)
            db.sourceDao().delete(nasSource)
            file.delete()
        }
        Unit
    }

    private fun descendants(view: android.view.View): List<android.view.View> = listOf(view) + if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun savingLegacyNasFrameMigratesSelectionsOrderAndOwnershipTogether() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val env = AppEnvironment.shared(context)
        val db = env.database
        val id = "legacy-nas-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        db.sourceDao().upsert(SourceEntity(id, SourceType.SMB, "Legacy fixture", id, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{\"catalogOnly\":true}", now, now))
        db.sourceDao().upsert(SourceEntity("$id-local", SourceType.LOCAL_FILES, "Local fixture", "$id-local", null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, "{}", now, now))
        for (index in 1..4) {
            val assetId = "$id-asset-$index"
            val bytes = java.io.ByteArrayOutputStream().also { android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xff000000.toInt() or (assetId.hashCode() and 0xffffff)) }.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            java.io.File(context.filesDir, "originals/$assetId.png").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            db.sourceAssetDao().upsert(SourceAssetEntity("$id-remote-$index", id, "$index.png", null, "1", 100, null, now, 0, AccessState.ACCESSIBLE, now, now))
            db.assetDao().upsert(AssetEntity(assetId, dev.photopilot.scaffold.data.logic.ContentHash.sha256Hex(bytes), bytes.size.toLong(), AssetFormat.PNG, 16, 16, 1, OrientationClass.SQUARE, null, CapturedAtSource.UNKNOWN, now, "$assetId.png", "$index.png", AssetReadiness.READY, null, false, null, deletedAt = null, createdAt = now, updatedAt = now))
            db.assetOriginDao().upsert(AssetOriginEntity(assetId, "$id-remote-$index", now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
            dev.photopilot.scaffold.data.imports.RoomCommitScope(db).recordProvenance(assetId, requireNotNull(db.sourceAssetDao().getById("$id-remote-$index")), dev.photopilot.scaffold.data.logic.ContentHash.sha256Hex(bytes), 1, now)
        }
        db.sourceAssetDao().upsert(SourceAssetEntity("$id-local-ref", "$id-local", "local.png", null, "1", 100, null, now, 0, AccessState.ACCESSIBLE, now, now))
        db.assetOriginDao().upsert(AssetOriginEntity("$id-asset-4", "$id-local-ref", now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
        val frame = env.frameManager.createFrame("Legacy NAS")
        db.frameDao().update(frame.copy(orderMode = FrameOrderMode.MANUAL))
        env.frames.save(frame.id, 0, addIncludes = listOf(1, 3, 4).map { SelectionRefEdit("$id-asset-$it", null) }, addExcludes = listOf(SelectionRefEdit("$id-asset-2", null)), manualOrderRefs = listOf("$id-asset-3", "$id-asset-1", "$id-asset-4"))
        env.frameManager.setCover(frame.id, "$id-asset-1")
        val model = env.aiFrames.models.models.first()
        env.aiFrames.state.put(AIConfiguration(frame.id, 1, model.id, model.revision, emptyList(), false, false, AIRule("Photos"), members = setOf("$id-asset-1")))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val saved = CountDownLatch(1)
        lateinit var view: RemoteFrameView
        try {
            val current = requireNotNull(db.frameDao().getById(frame.id))
            instrumentation.runOnMainSync {
                view = RemoteFrameView(activity, env, current, mapOf("mode" to "remote"))
                view.onSaved = { saved.countDown() }
                activity.setContentView(view)
            }
            kotlinx.coroutines.withTimeout(15000) {
                while (org.json.JSONArray(view.draftState().getValue("selected")).length() != 2) delay(100)
            }
            instrumentation.runOnMainSync {
                assertEquals("Legacy selections appear before save without database mutation", setOf("$id-remote-1", "$id-remote-3"), org.json.JSONArray(view.draftState().getValue("selected")).let { array -> (0 until array.length()).map { array.getString(it) }.toSet() })
            }
            assertNotNull(db.frameSelectionDao().getByRef(frame.id, "$id-asset-1"))
            instrumentation.runOnMainSync { RemoteFrameView::class.java.getDeclaredMethod("save").apply { isAccessible = true }.invoke(view) }
            assertTrue(saved.await(15, TimeUnit.SECONDS))
            val migrated = requireNotNull(db.frameDao().getById(frame.id))
            assertNull(migrated.coverAssetId)
            assertEquals("$id-remote-1", org.json.JSONObject(migrated.settingsJson).getString("remoteCoverRef"))
            val rows = db.frameSelectionDao().getByFrame(frame.id)
            assertEquals(FrameSelectionMode.EXCLUDE, rows.single { it.sourceAssetId == "$id-remote-2" }.mode)
            assertTrue(rows.any { it.assetId == "$id-asset-4" })
            assertFalse(rows.any { it.assetId in listOf("$id-asset-1", "$id-asset-2", "$id-asset-3") })
            assertEquals(listOf("$id-remote-3", "$id-remote-1", "$id-asset-4"), rows.filter { it.mode == FrameSelectionMode.INCLUDE }.sortedBy { it.manualOrder }.map { it.refKey })
            assertEquals(setOf("remote:$id-remote-1"), requireNotNull(env.aiFrames.state.get(frame.id)).members)
            assertEquals(setOf("$id-remote-1", "$id-remote-3"), env.frames.remoteItems(frame.id).mapNotNull { it.sourceAssetId }.toSet())
            assertTrue(env.remoteCopies.release(frame.id, false) > 0)
            assertFalse(java.io.File(context.filesDir, "originals/$id-asset-1.png").exists())
            assertEquals("$id-remote-1", env.frames.coverItem(frame.id)?.sourceAssetId)
            env.frameManager.setCover(frame.id, "$id-asset-4")
            assertFalse(org.json.JSONObject(requireNotNull(db.frameDao().getById(frame.id)).settingsJson).has("remoteCoverRef"))
            assertEquals("$id-asset-4", env.frames.coverItem(frame.id)?.local?.id)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            env.frameManager.deleteFrame(frame.id)
            for (index in 1..4) db.assetDao().getById("$id-asset-$index")?.let { java.io.File(context.filesDir, "originals/${it.originalRelPath}").delete(); db.assetDao().delete(it) }
            for (source in listOf(id, "$id-local")) db.sourceDao().getById(source)?.let { db.sourceDao().delete(it) }
        }
        Unit
    }
}
