package dev.photopilot.scaffold

import androidx.test.platform.app.InstrumentationRegistry
import dev.photopilot.scaffold.data.aiframes.*
import dev.photopilot.scaffold.data.db.DatabaseFactory
import dev.photopilot.scaffold.data.repo.AppSettingsRepository
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AICheckpointMigrationTest {
    @Test fun legacyHeaderMigratesAndChangedConfigurationDiscardsOnlyItsOwnRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = DatabaseFactory.builder(context).build()
        val models = AIModelManager(context)
        val model = models.models.single { it.id == "multilingual-clip-vitb32-lite" }
        val id = "checkpoint-migration-${UUID.randomUUID()}"
        val otherId = "$id-other"
        val rules = listOf(AIRule("cats", threshold = model.defaultThreshold))
        val cached = AICachedAsset("fingerprint", listOf(AICachedCategory(AIMatch.MATCH, .5f)), emptyMap())
        try {
            AIPreviewCheckpoint.open(db, model, emptyList(), false, rules, null, id)
            val key = AIPreviewCheckpoint.PREFIX + id
            val settings = AppSettingsRepository(db.settingsKvDao())
            val legacy = JSONObject(requireNotNull(settings.rawValue(key)))
                .put("version", 1)
                .put("assets", JSONObject().put("photo", cached.json()))
                .put("failures", JSONObject().put("retry", "retry-fingerprint"))
            settings.putRaw(key, legacy.toString())
            val migrated = AIPreviewCheckpoint.open(db, model, emptyList(), false, rules, null, id)
            assertEquals(cached, migrated.assets["photo"])
            assertEquals("retry-fingerprint", migrated.failures["retry"])
            assertEquals(2, JSONObject(requireNotNull(settings.rawValue(key))).getInt("version"))
            assertEquals(2, db.settingsKvDao().getByPrefix(AIPreviewCheckpoint.itemsPrefix(key)).size)
            val reopened = AIPreviewCheckpoint.open(db, model, emptyList(), false, rules, null, id)
            assertEquals(cached, reopened.assets["photo"])
            val other = AIPreviewCheckpoint.open(db, model, emptyList(), false, rules, null, otherId)
            other.assets["keep"] = cached
            other.saveAsset("keep")
            val changed = AIPreviewCheckpoint.open(db, model, emptyList(), false, listOf(AIRule("beach")), null, id)
            assertTrue(changed.assets.isEmpty())
            assertTrue(db.settingsKvDao().getByPrefix(AIPreviewCheckpoint.itemsPrefix(key)).isEmpty())
            assertEquals(1, AIPreviewCheckpoint.open(db, model, emptyList(), false, rules, null, otherId).assets.size)
            val nextRevision = model.copy(revision = "changed-revision")
            assertTrue(AIPreviewCheckpoint.open(db, nextRevision, emptyList(), false, rules, null, otherId).assets.isEmpty())
            AIPreviewCheckpoint.deleteModel(db, model.id)
            assertNull(settings.rawValue(key))
            assertNull(settings.rawValue(AIPreviewCheckpoint.PREFIX + otherId))
        } finally {
            AIPreviewCheckpoint.delete(db, id)
            AIPreviewCheckpoint.delete(db, otherId)
            db.close()
        }
    }
}
