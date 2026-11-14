package dev.photopilot.scaffold.data.remote

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.*
import dev.photopilot.scaffold.data.imports.*
import dev.photopilot.scaffold.data.logic.FramePlaybackSettings
import dev.photopilot.scaffold.data.logic.JsonMini
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RemoteLocalCopies(private val context: Context, private val env: AppEnvironment, private val adapterFor: (SourceEntity) -> SourceAdapter? = env::nasAdapterFor) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val lock = Mutex()
    private val versions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var selectionRefresh: Job? = null
    private val db get() = env.database
    private val originals = File(context.filesDir, "originals").apply { mkdirs() }
    private val trash = File(context.filesDir, "remote-trash").apply { mkdirs() }
    private val selectionObserver = object : androidx.room.InvalidationTracker.Observer("frame", "frame_selection", "frame_directory_rule") {
        override fun onInvalidated(tables: Set<String>) {
            synchronized(jobs) {
                selectionRefresh?.cancel()
                selectionRefresh = scope.launch { delay(500); refreshLocalFrames(null) }
            }
        }
    }
    init { db.invalidationTracker.addObserver(selectionObserver) }
    private suspend fun refreshLocalFrames(sourceId: String?) {
        for (frame in db.frameDao().getAll()) {
            if (FramePlaybackSettings.parse(frame.settingsJson).mediaStorageMode != "local") continue
            if (JsonMini.parseObject(db.settingsKvDao().get("remote.sync.${frame.id}")?.valueJson)["state"] == "paused") continue
            val items = env.frames.remoteItems(frame.id)
            if (sourceId != null && items.none { it.remote?.sourceId == sourceId }) continue
            if (items.any { item -> (item.local == null || item.localIsRetained) && db.sourceDao().getById(requireNotNull(item.remote).sourceId)?.status == SourceStatus.ACTIVE } && (!isRunning(frame.id) || versions[frame.id] != frame.selectionVersion)) start(frame.id)
        }
    }
    fun isRunning(frameId: String): Boolean = synchronized(jobs) { jobs[frameId]?.isActive == true }
    fun start(frameId: String) = synchronized(jobs) {
        jobs.remove(frameId)?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { sync(frameId) }
            catch (failure: CancellationException) { writeStatus(frameId, "paused", 0, 0); throw failure }
            catch (failure: Exception) { writeStatus(frameId, failure.message?.takeIf { it.startsWith("REMOTE_") } ?: "failed", 0, 0) }
            finally { val completedJob = currentCoroutineContext()[Job]; synchronized(jobs) { if (jobs[frameId] === completedJob) jobs.remove(frameId) } }
        }
        jobs[frameId] = job
        job.start()
    }
    suspend fun shutdown() { db.invalidationTracker.removeObserver(selectionObserver); scope.coroutineContext[Job]?.cancelAndJoin() }
    fun pause(frameId: String) { synchronized(jobs) { jobs.remove(frameId)?.cancel() }; scope.launch { writeStatus(frameId, "paused", 0, 0) } }
    suspend fun status(frameId: String): String? {
        val value = JsonMini.parseObject(db.settingsKvDao().get("remote.sync.$frameId")?.valueJson)
        val state = value["state"] as? String ?: return null
        val zh = context.resources.configuration.locales[0].language == "zh"
        val label = when (state) {
            "complete" -> if (zh) "本地同步已完成" else "Local sync complete"
            "running" -> if (zh) "正在同步本相框照片" else "Syncing this frame"
            "paused" -> if (zh) "本地同步已暂停" else "Local sync paused"
            "REMOTE_UNSUPPORTED" -> if (zh) "此格式暂无法建立本地副本" else "This format cannot be copied locally"
            "REMOTE_LOW_SPACE" -> if (zh) "空间不足，同步已暂停" else "Sync paused, low storage"
            else -> if (zh) "同步失败，可重试" else "Sync failed, retry available"
        }
        return "$label ${(value["done"] as? Number)?.toInt() ?: 0}/${(value["total"] as? Number)?.toInt() ?: 0}"
    }
    private suspend fun writeStatus(frameId: String, state: String, done: Int, total: Int) {
        val owner = currentCoroutineContext()[Job]
        if (synchronized(jobs) { jobs[frameId]?.let { it !== owner } == true }) return
        val now = System.currentTimeMillis()
        db.settingsKvDao().upsert(SettingsKvEntity("remote.sync.$frameId", JsonMini.toJson(mapOf("frameId" to frameId, "state" to state, "done" to done, "total" to total)), now, now))
    }
    suspend fun sync(frameId: String) = lock.withLock {
        val frame = db.frameDao().getById(frameId) ?: return@withLock
        if (FramePlaybackSettings.parse(frame.settingsJson).mediaStorageMode != "local") return@withLock
        versions[frameId] = frame.selectionVersion
        val items = env.frames.remoteItems(frameId)
        var done = 0
        writeStatus(frameId, "running", done, items.size)
        for ((sourceId, selected) in items.groupBy { requireNotNull(it.remote).sourceId }) {
            val source = db.sourceDao().getById(sourceId) ?: throw IOException("REMOTE_REVOKED")
            val pending = selected.filter { it.local == null || it.localIsRetained && source.status == SourceStatus.ACTIVE }
            if (pending.isEmpty()) { done += selected.size; continue }
            if (source.status != SourceStatus.ACTIVE || pending.any { it.remote?.accessState != AccessState.ACCESSIBLE }) throw IOException("REMOTE_REVOKED")
            val adapter = adapterFor(source) ?: throw IOException("REMOTE_AUTH_REQUIRED")
            for (old in db.importJobDao().getBySource(sourceId)) {
                if (JsonMini.parseObject(old.scopeJson)["frameId"] != frameId) continue
                env.coordinator.signal(old.id, dev.photopilot.scaffold.data.logic.ImportJobAggregate.Signal.USER_CANCEL)
                db.importItemDao().getByJob(old.id).forEach { previous ->
                    previous.tempRelPath?.let { File(context.cacheDir, "import-tmp/$it").delete() }
                    File(context.cacheDir, "import-tmp/item-${previous.id}.part").delete()
                }
            }
            val scopeJson = JsonMini.toJson(mapOf("frameId" to frame.id, "selectionVersion" to frame.selectionVersion, "directories" to emptyList<String>(), "sourceRevisions" to pending.associate { requireNotNull(it.sourceAssetId) to requireNotNull(it.remote).revision }))
            val jobId = env.coordinator.createJob(sourceId, scopeJson)
            val enumerator = object : SourceAdapter {
                override val sourceFingerprint = adapter.sourceFingerprint
                override suspend fun enumerate(scopeJson: String, cursor: String?) = EnumerationPage(pending.map { item -> requireNotNull(item.remote).let { EnumeratedAsset(it.remoteKey, it.stableId, it.revision, it.byteSize, it.remoteTakenAt) } }, null, pending.size.toLong())
                override fun openAsset(remoteKey: String) = adapter.openAsset(remoteKey)
            }
            env.coordinator.enumerate(jobId, enumerator)
            for (importItem in db.importItemDao().getByJob(jobId)) {
                currentCoroutineContext().ensureActive()
                if (!RoomCommitScope(db).frameScopeValid(scopeJson)) throw IOException("REMOTE_SYNC_STALE")
                val remote = db.sourceAssetDao().getById(importItem.sourceAssetId) ?: throw IOException("REMOTE_REVOKED")
                val syncJob = currentCoroutineContext()[Job]
                val guarded = object : SourceAdapter by adapter {
                    override fun openAsset(remoteKey: String) = object : java.io.FilterInputStream(adapter.openAsset(remoteKey)) {
                        private fun check(bytesToWrite: Int = 0) {
                            if (syncJob?.isActive == false) throw CancellationException("paused")
                            if (originals.usableSpace - bytesToWrite < 67108864) throw IOException("REMOTE_LOW_SPACE")
                            if (!env.remoteMedia.allowsNetwork()) throw NasNetworkBlockedException()
                            if (!runBlocking { RoomCommitScope(db).frameScopeValid(scopeJson) && env.remoteMedia.item(remote.id)?.sourceEpoch == source.epoch }) throw IOException("REMOTE_SYNC_STALE")
                        }
                        override fun read(): Int { check(); return `in`.read().also { check(if (it >= 0) 1 else 0) } }
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { check(); return `in`.read(buffer, offset, length).also { check(it.coerceAtLeast(0)) } }
                    }
                }
                if ((remote.byteSize ?: 0) + 67108864 > originals.usableSpace) throw IOException("REMOTE_LOW_SPACE")
                val outcome = try { env.coordinator.stage(importItem.id, guarded) } catch (failure: Throwable) {
                    File(context.cacheDir, "import-tmp/item-${importItem.id}.part").delete()
                    throw failure
                }
                if (outcome is ImportCoordinator.StageOutcome.Staged) {
                    val file = File(context.cacheDir, "import-tmp/${outcome.payload.tempRelPath}")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    val format = when (bounds.outMimeType) { "image/jpeg" -> AssetFormat.JPEG; "image/png" -> AssetFormat.PNG; "image/heic", "image/heif" -> AssetFormat.HEIC; else -> throw IOException("REMOTE_UNSUPPORTED") }
                    val orientation = runCatching { android.media.ExifInterface(file).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1).let(dev.photopilot.scaffold.data.logic.ExifOrientation::normalize)
                    val committed = env.coordinator.commit(importItem.id, outcome.payload, ImportCoordinator.AssetCommitMetadata(format, bounds.outWidth.toLong(), bounds.outHeight.toLong(), orientation.toLong(), remote.remoteTakenAt, CapturedAtSource.UNKNOWN, remote.remoteKey.substringAfterLast('/')))
                    if (committed !is ImportCoordinator.CommitOutcome.Imported) throw IOException("REMOTE_SYNC_${committed::class.java.simpleName}")
                } else throw IOException("REMOTE_SYNC_EXCLUDED")
                done++
                writeStatus(frameId, "running", done, items.size)
            }
        }
        writeStatus(frameId, "complete", items.size, items.size)
    }
    suspend fun release(frameId: String, verifyLegacy: Boolean): Long = lock.withLock {
        val frame = db.frameDao().getById(frameId) ?: return@withLock 0L
        if (FramePlaybackSettings.parse(frame.settingsJson).mediaStorageMode != "remote") throw IOException("REMOTE_SWITCH_MODE_FIRST")
        var released = 0L
        val candidates = env.frames.remoteItems(frameId)
        for (item in candidates) {
            val remote = requireNotNull(item.remote)
            for (origin in db.assetOriginDao().getBySourceAssets(listOf(remote.id))) {
                val asset = db.assetDao().getById(origin.assetId) ?: continue
                val file = File(originals, asset.originalRelPath)
                if (!file.isFile || asset.privacyExcluded || asset.deletedAt != null) continue
                val origins = db.assetOriginDao().getByAsset(asset.id)
                var protected = false
                for (otherOrigin in origins) {
                    val otherRemote = db.sourceAssetDao().getById(otherOrigin.sourceAssetId)
                    val source = otherRemote?.let { db.sourceDao().getById(it.sourceId) }
                    if (source == null || source.type !in setOf(SourceType.SMB, SourceType.WEBDAV) || source.status != SourceStatus.ACTIVE || otherRemote.accessState != AccessState.ACCESSIBLE || JsonMini.parseObject(source.settingsJson)["catalogOnly"] != true) { protected = true; break }
                    val key = RemoteMediaRepository.provenanceKey(asset.id, otherRemote.id)
                    var provenance = JsonMini.parseObject(db.settingsKvDao().get(key)?.valueJson)
                    if (provenance.isEmpty() && verifyLegacy) {
                        val remoteItem = env.remoteMedia.item(otherRemote.id) ?: continue
                        val hash = env.remoteMedia.read(remoteItem.copy(local = null)) { cached, _ ->
                            dev.photopilot.scaffold.data.logic.ContentHash.stream(cached.inputStream()).contentHash
                        }
                        if (hash == asset.contentHash) {
                            RoomCommitScope(db).recordProvenance(asset.id, otherRemote, hash, source.epoch, System.currentTimeMillis())
                            provenance = JsonMini.parseObject(db.settingsKvDao().get(key)?.valueJson)
                        }
                    }
                    if (provenance["revision"] != otherRemote.revision || (provenance["epoch"] as? Number)?.toLong() != source.epoch || provenance["contentHash"] != asset.contentHash) { protected = true; break }
                }
                if (protected) continue
                for (otherFrame in db.frameDao().getAll()) {
                    if (otherFrame.id == frameId || FramePlaybackSettings.parse(otherFrame.settingsJson).mediaStorageMode != "local") continue
                    if (asset.id in env.frames.resolve(otherFrame.id).allowed || env.frames.remoteItems(otherFrame.id).any { candidate -> origins.any { it.sourceAssetId == candidate.sourceAssetId } }) { protected = true; break }
                }
                if (db.frameSelectionDao().getByAsset(asset.id).any { it.mode == FrameSelectionMode.INCLUDE }) protected = true
                if (protected) continue
                val fresh = db.frameDao().getById(frameId)
                if (fresh?.selectionVersion != frame.selectionVersion || FramePlaybackSettings.parse(fresh.settingsJson).mediaStorageMode != "remote") throw IOException("REMOTE_SYNC_STALE")
                val now = System.currentTimeMillis()
                val trashFile = File(trash, asset.id)
                val intent = SettingsKvEntity("remote.cleanup.${asset.id}", JsonMini.toJson(mapOf("assetId" to asset.id, "path" to asset.originalRelPath)), now, now)
                db.settingsKvDao().upsert(intent)
                if (!file.renameTo(trashFile)) throw IOException("REMOTE_CLEANUP_FAILED")
                var committed = false
                try {
                    db.withTransaction {
                        val latestFrame = db.frameDao().getById(frameId) ?: throw IOException("REMOTE_SYNC_STALE")
                        if (latestFrame.selectionVersion != frame.selectionVersion || FramePlaybackSettings.parse(latestFrame.settingsJson).mediaStorageMode != "remote") throw IOException("REMOTE_SYNC_STALE")
                        val latestAsset = db.assetDao().getById(asset.id) ?: throw IOException("REMOTE_REVOKED")
                        if (latestAsset.contentHash != asset.contentHash || latestAsset.deletedAt != null || latestAsset.privacyExcluded) throw IOException("REMOTE_REVOKED")
                        val currentOrigins = db.assetOriginDao().getByAsset(asset.id)
                        if (currentOrigins.map { it.sourceAssetId }.toSet() != origins.map { it.sourceAssetId }.toSet()) throw IOException("REMOTE_COPY_IN_USE")
                        for (currentOrigin in currentOrigins) {
                            val currentRemote = db.sourceAssetDao().getById(currentOrigin.sourceAssetId) ?: throw IOException("REMOTE_REVOKED")
                            val currentSource = db.sourceDao().getById(currentRemote.sourceId) ?: throw IOException("REMOTE_REVOKED")
                            val provenance = JsonMini.parseObject(db.settingsKvDao().get(RemoteMediaRepository.provenanceKey(asset.id, currentRemote.id))?.valueJson)
                            if (currentSource.status != SourceStatus.ACTIVE || currentRemote.accessState != AccessState.ACCESSIBLE || provenance["revision"] != currentRemote.revision || (provenance["epoch"] as? Number)?.toLong() != currentSource.epoch || provenance["contentHash"] != asset.contentHash) throw IOException("REMOTE_REVOKED")
                        }
                        if (db.frameSelectionDao().getByAsset(asset.id).any { it.mode == FrameSelectionMode.INCLUDE }) throw IOException("REMOTE_COPY_IN_USE")
                        for (consumer in db.frameDao().getAll()) {
                            if (consumer.id != frameId && FramePlaybackSettings.parse(consumer.settingsJson).mediaStorageMode == "local" &&
                                (asset.id in env.frames.resolve(consumer.id).allowed || env.frames.remoteItems(consumer.id).any { ref -> origins.any { it.sourceAssetId == ref.sourceAssetId } })) throw IOException("REMOTE_COPY_IN_USE")
                        }
                        db.assetDao().update(asset.copy(readiness = AssetReadiness.PENDING, updatedAt = now))
                        dev.photopilot.scaffold.data.repo.LibraryVersionRepository(db.settingsKvDao()).bump(now)
                    }
                    committed = true
                    val removedBytes = trashFile.length()
                    if (!trashFile.delete()) throw IOException("REMOTE_CLEANUP_FAILED")
                    released += removedBytes
                    db.settingsKvDao().delete(intent)
                } catch (error: Throwable) {
                    if (!committed) withContext(NonCancellable) {
                        val currentAsset = db.assetDao().getById(asset.id)
                        if (currentAsset == null || currentAsset.deletedAt != null || currentAsset.readiness == AssetReadiness.DELETED) trashFile.delete()
                        else trashFile.renameTo(file)
                    }
                    throw error
                }
            }
        }
        released
    }
    fun metadataUpdated(sourceId: String) { scope.launch { refreshLocalFrames(sourceId) } }
    suspend fun resumeInterrupted() {
        for (row in db.settingsKvDao().getByPrefix("remote.sync.")) {
            val value = JsonMini.parseObject(row.valueJson)
            val id = value["frameId"] as? String ?: continue
            if (value["state"] == "running") start(id)
        }
    }
    suspend fun recover() = lock.withLock {
        for (intent in db.settingsKvDao().getByPrefix("remote.cleanup.")) {
            val value = JsonMini.parseObject(intent.valueJson)
            val id = value["assetId"] as? String ?: continue
            val path = value["path"] as? String ?: continue
            val file = File(originals, path)
            val discarded = File(trash, id)
            val asset = db.assetDao().getById(id)
            if (asset == null || asset.deletedAt != null || asset.readiness in setOf(AssetReadiness.PENDING, AssetReadiness.DELETED)) discarded.delete()
            else if (!file.exists() && discarded.exists()) {
                if (!discarded.renameTo(file)) throw IOException("REMOTE_CLEANUP_RECOVERY")
            } else if (file.exists()) discarded.delete()
            else db.assetDao().update(asset.copy(readiness = AssetReadiness.PENDING, updatedAt = System.currentTimeMillis()))
            if (discarded.exists()) throw IOException("REMOTE_CLEANUP_RECOVERY")
            db.settingsKvDao().delete(intent)
        }
    }
}
