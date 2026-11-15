package dev.photopilot.scaffold

import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.repo.LocalSourceRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NasSyncAuthorityTest {
    @Test fun delayedAdapterAndLateOutcomesCannotOverwriteDisconnectOrEdits() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = DatabaseFactory.builder(context).build()
        val id = "sync-authority-" + UUID.randomUUID()
        val registry = LocalSourceRegistry(db, File(context.cacheDir, id))
        val source = registry.ensureNasSource(SourceType.WEBDAV, id, "Authority fixture", 0, "{\"directories\":[\"old\"]}")
        try {
            val adapterReady = CompletableDeferred<Unit>()
            val oldPreparation = async(Dispatchers.IO) {
                adapterReady.await()
                registry.prepareSync(source.id, source.epoch, source.status, false, 2)
            }
            registry.disconnect(source.id, true, 1)
            adapterReady.complete(Unit)
            assertNull(oldPreparation.await())
            assertEquals(SourceStatus.DISCONNECTED, db.sourceDao().getById(source.id)?.status)
            assertFalse(registry.recordSyncError(source.id, source.epoch, source.status, true, 3))
            val reconnected = registry.ensureNasSource(SourceType.WEBDAV, id, "Authority fixture", 0)
            val edited = reconnected.copy(epoch = reconnected.epoch + 1, settingsJson = "{\"directories\":[\"new\"],\"mirrorDelete\":true}", updatedAt = 4)
            val failureReady = CompletableDeferred<Unit>()
            val oldFailure = async(Dispatchers.IO) {
                failureReady.await()
                registry.recordSyncError(source.id, reconnected.epoch, reconnected.status, true, 5)
            }
            db.sourceDao().update(edited)
            db.settingsKvDao().upsert(SettingsKvEntity("nas.connection_error.${source.id}", "new", 4, 4))
            failureReady.complete(Unit)
            assertFalse(oldFailure.await())
            assertNull(registry.prepareSync(source.id, reconnected.epoch, reconnected.status, true, 6))
            assertFalse(registry.clearSyncError(source.id, reconnected.epoch, reconnected.status))
            assertEquals(edited, db.sourceDao().getById(source.id))
            assertEquals("new", db.settingsKvDao().get("nas.connection_error.${source.id}")?.valueJson)
            db.sourceDao().update(edited.copy(status = SourceStatus.NEEDS_AUTH))
            assertNull(registry.prepareSync(source.id, edited.epoch, SourceStatus.ACTIVE, true, 7))
            assertFalse(registry.recordSyncError(source.id, edited.epoch, SourceStatus.ACTIVE, false, 8))
            assertEquals(SourceStatus.NEEDS_AUTH, db.sourceDao().getById(source.id)?.status)
        } finally {
            registry.disconnect(source.id, false, 10)
            db.close()
        }
        Unit
    }
}
