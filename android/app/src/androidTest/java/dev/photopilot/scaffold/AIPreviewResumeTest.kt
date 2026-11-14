package dev.photopilot.scaffold

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.aiframes.*
import dev.photopilot.scaffold.data.db.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AIPreviewResumeTest {
    @Test fun interruptedPreviewReusesCompletedAssetAfterNewServiceStarts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = DatabaseFactory.builder(context).build()
        val service = AIFrameService(context, db)
        val model = service.models.models.single { it.id == "siglip2-base-int8" }
        for (file in model.files) {
            File(context.filesDir, "ai-validation/${file.name}").copyTo(File(service.models.directory(model), file.name), true)
            File(service.models.directory(model), file.name + ".verified").writeText(file.sha256)
        }
        val prefix = "ai-resume-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val originals = File(context.filesDir, "originals").apply { mkdirs() }
        db.sourceDao().upsert(SourceEntity(prefix, SourceType.LOCAL_FILES, "Synthetic validation", null, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, null, now, now))
        db.collectionDao().upsert(CollectionEntity(prefix, null, prefix, "Synthetic validation", null, CollectionType.SOURCE_FOLDER, prefix, now, now))
        val ids = (0..2).map { "$prefix-$it" }
        for ((index, id) in ids.withIndex()) {
            val file = File(originals, "$id.png")
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(listOf(Color.RED, Color.GREEN, Color.BLUE)[index]) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            db.sourceAssetDao().upsert(SourceAssetEntity(id, prefix, "$id.png", null, "1", file.length(), null, now, 0, AccessState.ACCESSIBLE, now, now))
            db.assetDao().upsert(AssetEntity(id, hash, file.length(), AssetFormat.PNG, 64, 64, 1, OrientationClass.SQUARE, null, CapturedAtSource.UNKNOWN, now, "$id.png", "$id.png", AssetReadiness.READY, null, false, null, deletedAt = null, createdAt = now, updatedAt = now))
            db.assetOriginDao().upsert(AssetOriginEntity(id, id, now, PolicyState.ALLOWED, OriginState.ACTIVE, now, now))
            db.collectionAssetDao().upsert(CollectionAssetEntity(prefix, id, now, now))
        }
        var restarted: AIFrameService? = null
        try {
            val interrupted = launch {
                service.preview(model, listOf(prefix), true, listOf(AIRule("a photo of a cat", threshold = .075f))) { completed, _ -> if (completed == 1) this@launch.cancel() }
            }
            interrupted.join()
            assertTrue(interrupted.isCancelled)
            service.shutdown()
            val next = AIFrameService(context, db).also { restarted = it }
            val resumed = next.preview(model, listOf(prefix), true, listOf(AIRule("a photo of a cat", threshold = .075f))) { _, _ -> }.single()
            assertEquals(3, resumed.fingerprints.size)
            assertEquals(1, resumed.reusedAssetCount)
            next.foreground = false
            try {
                next.preview(model, listOf(prefix), true, listOf(AIRule("a photo of a cat", threshold = .075f))) { _, _ -> }
                fail("Background previews must pause")
            } catch (paused: java.io.IOException) { assertEquals("preview_paused", paused.message) }
            next.foreground = true
            val foregroundResume = next.preview(model, listOf(prefix), true, listOf(AIRule("a photo of a cat", threshold = .075f))) { _, _ -> }.single()
            assertEquals(3, foregroundResume.reusedAssetCount)
            val oldWriter = AIPreviewCheckpoint.open(db, model, listOf(prefix), true, listOf(AIRule("a photo of a cat", threshold = .075f)), null)
            dev.photopilot.scaffold.data.repo.AssetRepository(db, dev.photopilot.scaffold.data.repo.LibraryVersionRepository(db.settingsKvDao())).hideAssetGlobally(ids.first(), "validation", now)
            try { oldWriter.save(); fail("Hidden-photo checkpoints must stay invalidated") }
            catch (invalidated: java.io.IOException) { assertEquals("preview_invalidated", invalidated.message) }
            assertTrue(db.settingsKvDao().getByPrefix(AIPreviewCheckpoint.PREFIX).isEmpty())
            val afterHide = next.preview(model, listOf(prefix), true, listOf(AIRule("a photo of a cat", threshold = .075f))) { _, _ -> }.single()
            assertEquals(0, afterHide.reusedAssetCount)
            assertEquals(2, afterHide.fingerprints.size)
            assertFalse(ids.first() in afterHide.fingerprints)
        } finally {
            service.shutdown()
            restarted?.shutdown()
            for (id in ids) { db.assetDao().getById(id)?.let { db.assetDao().delete(it) }; File(originals, "$id.png").delete() }
            db.collectionDao().getById(prefix)?.let { db.collectionDao().delete(it) }
            db.sourceDao().getById(prefix)?.let { db.sourceDao().delete(it) }
            db.close()
        }
    }
}
