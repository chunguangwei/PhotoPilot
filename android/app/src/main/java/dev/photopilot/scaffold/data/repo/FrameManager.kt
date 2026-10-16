package dev.photopilot.scaffold.data.repo

import androidx.room.withTransaction
import dev.photopilot.scaffold.data.db.FrameEntity
import dev.photopilot.scaffold.data.db.FrameOrderMode
import dev.photopilot.scaffold.data.db.FrameSelectionMode
import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class FrameManager(
    private val db: PhotoPilotDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
    private val seedGen: () -> Long = { (0 until Long.MAX_VALUE).random() },
) {

    suspend fun listFrames(): List<FrameEntity> = withContext(Dispatchers.IO) {
        db.frameDao().getAll().sortedWith(
            compareByDescending<FrameEntity> { it.updatedAt }.thenBy { it.id },
        )
    }

    suspend fun createFrame(name: String): FrameEntity = withContext(Dispatchers.IO) {
        val now = nowMillis()
        val frame = FrameEntity(
            id = idGen(),
            name = name,
            coverAssetId = null,
            settingsJson = DEFAULT_SETTINGS_JSON,
            selectionVersion = 0L,
            orderMode = FrameOrderMode.CAPTURED_ASC,
            randomSeed = seedGen(),
            createdAt = now,
            updatedAt = now,
        )
        db.frameDao().upsert(frame)
        frame
    }

    suspend fun renameFrame(frameId: String, name: String) = withContext(Dispatchers.IO) {
        val frame = db.frameDao().getById(frameId) ?: return@withContext
        db.frameDao().update(frame.copy(name = name, updatedAt = nowMillis()))
    }

    suspend fun updateSettings(frameId: String, settingsJson: String) = withContext(Dispatchers.IO) {
        val frame = db.frameDao().getById(frameId) ?: return@withContext
        db.frameDao().update(frame.copy(settingsJson = settingsJson, updatedAt = nowMillis()))
    }

    suspend fun updateOrderMode(frameId: String, mode: FrameOrderMode) = withContext(Dispatchers.IO) {
        val frame = db.frameDao().getById(frameId) ?: return@withContext
        db.frameDao().update(frame.copy(orderMode = mode, updatedAt = nowMillis()))
    }

    suspend fun setCover(frameId: String, assetId: String?) = withContext(Dispatchers.IO) {
        val frame = db.frameDao().getById(frameId) ?: return@withContext
        db.frameDao().update(frame.copy(coverAssetId = assetId, updatedAt = nowMillis()))
    }

    suspend fun duplicateFrame(frameId: String): FrameEntity? = withContext(Dispatchers.IO) {
        db.withTransaction {
            val source = db.frameDao().getById(frameId) ?: return@withTransaction null
            val now = nowMillis()
            val copy = source.copy(
                id = idGen(),
                selectionVersion = 0L,
                randomSeed = seedGen(),
                createdAt = now,
                updatedAt = now,
            )
            db.frameDao().upsert(copy)
            db.frameSelectionDao().getByFrame(frameId).forEach { selection ->
                db.frameSelectionDao().upsertRaw(
                    selection.copy(
                        frameId = copy.id,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
            db.frameDirectoryRuleDao().getByFrame(frameId).forEach { rule ->
                db.frameDirectoryRuleDao().upsert(
                    rule.copy(frameId = copy.id, createdAt = now, updatedAt = now),
                )
            }
            copy
        }
    }

    suspend fun deleteFrame(frameId: String) = withContext(Dispatchers.IO) {
        val frame = db.frameDao().getById(frameId) ?: return@withContext
        db.frameDao().delete(frame)
    }

    suspend fun applyManualOrder(
        frameId: String,
        orderedAssetIds: List<String>,
        baseVersion: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        db.withTransaction {
            val frame = db.frameDao().getById(frameId) ?: return@withTransaction false
            if (frame.selectionVersion != baseVersion) return@withTransaction false
            val now = nowMillis()
            val includes = db.frameSelectionDao().getByFrame(frameId)
                .filter { it.mode == FrameSelectionMode.INCLUDE }
            val byRef = includes.associateBy { it.refKey }
            orderedAssetIds.forEachIndexed { index, ref ->
                byRef[ref]?.let {
                    db.frameSelectionDao().upsertRaw(
                        it.copy(manualOrder = (index + 1).toLong(), updatedAt = now),
                    )
                }
            }
            val ordered = orderedAssetIds.toSet()
            includes.filter { it.refKey !in ordered }
                .sortedBy { it.addedAt }
                .forEachIndexed { index, row ->
                    db.frameSelectionDao().upsertRaw(
                        row.copy(manualOrder = (orderedAssetIds.size + index + 1).toLong(), updatedAt = now),
                    )
                }
            db.frameDao().bumpSelectionVersion(frameId, now)
            true
        }
    }

    companion object {
        const val DEFAULT_SETTINGS_JSON =
            "{\"holdSeconds\":10,\"transition\":\"random\",\"transitionSeconds\":0.8," +
                "\"fitMode\":\"fit_blur\",\"solidColor\":null," +
                "\"clock\":{\"enabled\":true,\"position\":\"bottom_leading\",\"hour24\":null}," +
                "\"date\":{\"enabled\":true}," +
                "\"weather\":{\"enabled\":false,\"placeId\":null,\"units\":null}," +
                "\"keepAwakeWhenPaused\":true," +
                "\"rest\":{\"enabled\":false,\"startMinute\":null,\"endMinute\":null,\"display\":\"clock\"}," +
                "\"music\":{\"enabled\":false,\"source\":\"builtin\"," +
                "\"trackId\":null,\"localPath\":null,\"localTitle\":null}," +
                "\"brightness\":null,\"qualityLevel\":\"auto\"}"
    }
}
