package dev.photopilot.scaffold.data.aiframes

import androidx.room.withTransaction
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.remote.*
import java.io.File
import org.json.JSONArray
import kotlinx.coroutines.*

internal data class AIMedia(val id: String, val fingerprint: String, val item: PlaybackItem)

internal suspend fun AIFrameService.mediaCandidates(collections: List<String>, recursive: Boolean): Map<String, AIMedia> {
    val all = db.collectionDao().getAll()
    val ids = AISourceScope.expand(collections, recursive, all.associate { it.id to it.parentId })
    val localIds = ids.toList().chunked(400).flatMap { db.collectionAssetDao().getByCollections(it) }.map { it.assetId }.distinct()
    val result = linkedMapOf<String, AIMedia>()
    val remoteLocals = mutableSetOf<String>()
    remoteMedia?.let { media ->
        for (collection in all.filter { it.id in ids && it.sourceId != null && it.relPath != null }) {
            for (sourceAsset in media.members(requireNotNull(collection.sourceId), collection.relPath.orEmpty(), false)) {
                val item = media.item(sourceAsset.id) ?: continue
                remoteLocals.addAll(db.assetOriginDao().getBySourceAssets(listOf(sourceAsset.id)).map { it.assetId })
                result[item.id] = AIMedia(item.id, "${sourceAsset.sourceId}:${item.sourceEpoch}:${sourceAsset.revision}:${item.local?.contentHash.orEmpty()}", item)
            }
        }
    }
    for (batch in localIds.filter { it !in remoteLocals }.chunked(128)) {
        val allowed = db.assetOriginDao().allowedAssetIds(batch).toSet()
        for (asset in db.assetDao().getByIds(batch)) {
            if (AIEligibility.allowed(asset.readiness.wire, asset.privacyExcluded, asset.deletedAt != null, asset.mediaKind.wire, asset.id in allowed, false) && File(context.filesDir, "originals/${asset.originalRelPath}").isFile) {
                result[asset.id] = AIMedia(asset.id, asset.contentHash, PlaybackItem(local = asset))
            }
        }
    }
    return result
}

internal suspend fun AIFrameService.selectionAliases(candidate: AIMedia): Set<String> {
    val sourceIds = candidate.item.local?.let { db.assetOriginDao().getByAsset(it.id).map { origin -> origin.sourceAssetId } }.orEmpty() + listOfNotNull(candidate.item.sourceAssetId)
    val assetIds = sourceIds.distinct().chunked(400).flatMap { db.assetOriginDao().getBySourceAssets(it) }.map { it.assetId }
    return (sourceIds + assetIds + listOfNotNull(candidate.item.local?.id)).toSet()
}

internal suspend fun AIFrameService.excluded(candidate: AIMedia, frameId: String?): Boolean {
    val rows = frameId?.let { db.frameSelectionDao().getByFrame(it) }.orEmpty()
    val aliases = selectionAliases(candidate)
    return rows.any { it.mode == FrameSelectionMode.EXCLUDE && it.refKey in aliases }
}

internal suspend fun AIFrameService.mediaVector(candidate: AIMedia, engine: AIEmbedder, model: AIModel): FloatArray {
    val cacheKey = "ai.vector." + java.security.MessageDigest.getInstance("SHA-256").digest("${candidate.id}:${candidate.fingerprint}:${model.id}:${model.revision}:${model.preprocessingVersion}".toByteArray()).joinToString("") { "%02x".format(it) }
    db.settingsKvDao().get(cacheKey)?.takeIf { candidate.item.referenceCacheable }?.let { row ->
        val array = JSONArray(row.valueJson)
        val now = System.currentTimeMillis()
        db.settingsKvDao().upsert(row.copy(updatedAt = now))
        return FloatArray(array.length()) { array.getDouble(it).toFloat() }
    }
    val vector = if (candidate.item.remote != null) {
        val media = requireNotNull(remoteMedia)
        if (media.isLocalReference(candidate.item)) {
            val bitmap = requireNotNull(media.bitmap(candidate.item, 1024, 1024)) { "LOCAL_REFERENCE_UNAVAILABLE" }
            engine.image(bitmap)
        } else media.read(candidate.item) { file, orientation ->
            val bitmap = AIImageDecoder.decode(file, orientation)
            try { engine.image(bitmap) } finally { bitmap.recycle() }
        }
    } else {
        val asset = requireNotNull(candidate.item.local)
        val bitmap = AIImageDecoder.decode(File(context.filesDir, "originals/${asset.originalRelPath}"), asset.exifOrientation.toInt())
        try { engine.image(bitmap) } finally { bitmap.recycle() }
    }
    val value = JSONArray(vector.map { it.toDouble() }).toString()
    val now = System.currentTimeMillis()
    db.withTransaction {
        if (candidate.item.remote != null && remoteMedia?.valid(candidate.item) != true) throw java.io.IOException("REMOTE_REVOKED")
        candidate.item.local?.let { original ->
            val current = db.assetDao().getById(original.id)
            if (current == null || current.privacyExcluded || current.deletedAt != null || current.contentHash != original.contentHash) throw java.io.IOException("REMOTE_REVOKED")
        }
        if (candidate.item.referenceCacheable) {
            db.settingsKvDao().upsert(SettingsKvEntity(cacheKey, value, now, now))
            var bytes = 0L
            for (row in db.settingsKvDao().getByPrefix("ai.vector.").sortedByDescending { it.updatedAt }) {
                bytes += row.valueJson.toByteArray(Charsets.UTF_8).size
                if (bytes > 16777216) db.settingsKvDao().delete(row)
            }
        }
    }
    return vector
}
