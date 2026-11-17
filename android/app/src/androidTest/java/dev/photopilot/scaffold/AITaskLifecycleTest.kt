package dev.photopilot.scaffold

import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.aiframes.*
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.repo.FrameManager
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AITaskLifecycleTest {
    @Test fun pausedEmptyDraftRestoresWithoutScanningAndConfirmationRemainsBoundToFrames() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = DatabaseFactory.builder(context).build()
        var service = AIFrameService(context, db)
        val id = "durable-preview-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val model = service.models.models.single { it.id == "multilingual-clip-vitb32-lite" }
        model.files.forEach { file -> File(context.filesDir, "ai-validation/multilingual-clip/${file.name}").copyTo(File(service.models.directory(model), file.name), true); File(service.models.directory(model), file.name + ".verified").writeText(file.sha256) }
        db.sourceDao().upsert(SourceEntity(id, SourceType.LOCAL_FILES, "Fixture", null, null, null, 0, RetentionPolicy.USER_MANAGED, SourceStatus.ACTIVE, 1, null, null, now, now))
        db.collectionDao().upsert(CollectionEntity(id, null, id, "Fixture", null, CollectionType.SOURCE_FOLDER, id, now, now))
        var frames = emptyList<String>()
        try {
            service.tasks.startPreview(id, model, listOf(id), false, listOf(AIRule("cats"), AIRule("beach")), null, mapOf("descriptions" to "cats\nbeach", "draftId" to id))
            service.tasks.pause(id).join()
            withTimeout(10000) { while (service.tasks.running(id)) delay(50) }
            val record = requireNotNull(service.tasks.record(id))
            assertTrue(record.userPaused)
            assertEquals("user_paused", record.status)
            assertEquals(2, record.categoryIds.distinct().size)
            service.shutdown()
            service = AIFrameService(context, db)
            service.tasks.recover()
            assertFalse(service.tasks.running(id))
            val result = requireNotNull(service.tasks.previewResult(id))
            assertEquals(2, result.size)
            assertTrue(result.all { it.matches.isEmpty() })
            frames = service.tasks.confirm(id, listOf("Paused cats", "Paused beach"), listOf(emptySet(), emptySet()), true)
            assertEquals(frames, service.tasks.confirm(id, listOf("Unused rename", "Unused rename"), listOf(emptySet(), emptySet()), true))
            assertTrue(frames.all { service.state.get(it)?.userPaused == true })
            service.resumeAutomatic()
            delay(200)
            assertTrue(frames.none(service::isRunning))
            FrameManager(db).deleteFrame(frames.first())
            try {
                service.tasks.confirm(id, listOf("cats", "beach"), listOf(emptySet(), emptySet()), true)
                fail("Deleted bound frame must not be recreated")
            } catch (expected: IllegalArgumentException) { assertEquals("frame_removed", expected.message) }
        } finally {
            service.shutdown()
            frames.forEach { FrameManager(db).deleteFrame(it) }
            db.collectionDao().getById(id)?.let { db.collectionDao().delete(it) }
            db.sourceDao().getById(id)?.let { db.sourceDao().delete(it) }
            db.settingsKvDao().getByPrefix("ai.task.$id").forEach { db.settingsKvDao().delete(it) }
            db.close()
        }
    }
    @Test fun continueWaitingForOldJobCannotResurrectADeletedTask() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = DatabaseFactory.builder(context).build()
        val service = AIFrameService(context, db)
        val model = AIModel("task-race-" + UUID.randomUUID(), "fixture", "onnx", "Fixture", "夹具", emptyList())
        service.models.javaClass.getDeclaredField("models\$delegate").apply { isAccessible = true }.set(service.models, lazyOf(listOf(model)))
        service.tasks.saveDownloadPreferences(model, "domestic", true)
        val id = "download:${model.id}"
        val gate = CompletableDeferred<Unit>()
        val oldJob = launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) { gate.await() } }
        @Suppress("UNCHECKED_CAST")
        val jobs = service.tasks.javaClass.getDeclaredField("jobs").apply { isAccessible = true }.get(service.tasks) as java.util.concurrent.ConcurrentHashMap<String, Job>
        jobs[id] = oldJob
        val continuation = async(start = CoroutineStart.UNDISPATCHED) { service.tasks.continueTask(id) }
        try {
            service.tasks.deleteModel(model)
            assertEquals("deleted", service.tasks.record(id)?.status)
            gate.complete(Unit)
            continuation.await()
            delay(100)
            assertEquals("Continue must not overwrite the deletion generation", "deleted", service.tasks.record(id)?.status)
            assertFalse(service.tasks.running(id))
        } finally {
            gate.complete(Unit)
            continuation.cancelAndJoin()
            oldJob.join()
            service.shutdown()
            db.settingsKvDao().get("ai.task.$id")?.let { db.settingsKvDao().delete(it) }
            db.close()
        }
    }
    @Test fun downloadingOneModelDoesNotBlockLeasingAnotherReadyModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AIModelManager(context)
        val compact = manager.models.single { it.runtime == "onnx" }
        val standard = manager.models.single { it.runtime == "litertlm" }
        val began = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch { manager.withModelFiles(standard) { began.complete(Unit); release.await() } }
        began.await()
        val waitingInference = launch(start = CoroutineStart.UNDISPATCHED) { manager.lease(standard) { assertTrue(true) } }
        try {
            withTimeout(500) { manager.lease(compact) { assertTrue(true) } }
        } finally {
            release.complete(Unit)
            first.join()
            waitingInference.join()
        }
    }
}
