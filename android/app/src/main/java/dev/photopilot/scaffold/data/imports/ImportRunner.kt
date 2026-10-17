package dev.photopilot.scaffold.data.imports

import android.graphics.BitmapFactory
import android.media.ExifInterface
import dev.photopilot.scaffold.data.db.AssetFormat
import dev.photopilot.scaffold.data.db.CapturedAtSource
import dev.photopilot.scaffold.data.db.ImportItemEntity
import dev.photopilot.scaffold.data.db.ImportItemState
import dev.photopilot.scaffold.data.db.ImportJobState
import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import dev.photopilot.scaffold.data.db.SettingsKvEntity
import dev.photopilot.scaffold.data.db.SourceAssetEntity
import dev.photopilot.scaffold.data.db.SourceType
import dev.photopilot.scaffold.data.logic.ExifOrientation
import dev.photopilot.scaffold.data.logic.ImportJobAggregate
import dev.photopilot.scaffold.data.logic.NasSourceLifecycle
import dev.photopilot.scaffold.data.logic.VideoProbe
import dev.photopilot.scaffold.data.net.SmbClient
import dev.photopilot.scaffold.data.net.WebDavClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class ImportRunner(
    private val db: PhotoPilotDatabase,
    private val coordinator: ImportCoordinator,
    private val adapterResolver: suspend (String) -> SourceAdapter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val drivers = ConcurrentHashMap<String, Job>()

    var activeDriverListener: ((Int) -> Unit)? = null

    fun start(jobId: String) {
        if (drivers[jobId]?.isActive == true) return
        val job = scope.launch { drive(jobId) }
        drivers[jobId] = job
        job.invokeOnCompletion {
            drivers.remove(jobId, job)
            notifyActiveDrivers()
        }
        notifyActiveDrivers()
    }

    private fun notifyActiveDrivers() {
        activeDriverListener?.invoke(drivers.values.count { it.isActive })
    }

    suspend fun pause(jobId: String) {
        coordinator.signal(jobId, ImportJobAggregate.Signal.USER_PAUSE)
    }

    suspend fun resume(jobId: String) {
        requeueInterrupted(jobId)
        coordinator.resume(jobId)
        start(jobId)
    }

    suspend fun retryFailed(jobId: String) {
        requeueInterrupted(jobId)
        val now = System.currentTimeMillis()
        db.importItemDao().getByJob(jobId)
            .filter { it.state == ImportItemState.FAILED }
            .forEach {
                db.importItemDao().transition(
                    id = it.id,
                    state = ImportItemState.QUEUED,
                    retryAt = null,
                    failureCode = null,
                    now = now,
                )
            }
        start(jobId)
    }

    suspend fun cancel(jobId: String) {
        coordinator.signal(jobId, ImportJobAggregate.Signal.USER_CANCEL)
        drivers[jobId]?.cancel()
    }

    fun stopAll() {
        drivers.values.forEach { it.cancel() }
        drivers.clear()
    }

    suspend fun markInterruptedJobsRecoverable() {
        db.importJobDao().getByState(ImportJobState.RUNNING).forEach {
            coordinator.signal(it.id, ImportJobAggregate.Signal.AUTO_PAUSE)
        }
    }

    suspend fun resumeInterruptedJobs() {
        db.importJobDao().getByState(ImportJobState.PAUSED_AUTO).forEach {
            resume(it.id)
        }
    }

    suspend fun driveActiveJobs() {
        db.importJobDao().getByState(ImportJobState.RUNNING).forEach {
            start(it.id)
        }
        db.importJobDao().getByState(ImportJobState.PAUSED_AUTO).forEach {
            start(it.id)
        }
    }

    private suspend fun requeueInterrupted(jobId: String) {
        val now = System.currentTimeMillis()
        db.importItemDao().getByJob(jobId)
            .filter { it.state in INTERRUPTED_ITEM_STATES }
            .forEach {
                db.importItemDao().transition(
                    id = it.id,
                    state = ImportItemState.QUEUED,
                    retryAt = null,
                    failureCode = null,
                    now = now,
                )
            }
    }

    private suspend fun finalizeNasSync(jobId: String) {
        val job = db.importJobDao().getById(jobId) ?: return
        val source = db.sourceDao().getById(job.sourceId) ?: return
        if (source.type != SourceType.WEBDAV && source.type != SourceType.SMB) return
        val mirrorDelete = NasSourceLifecycle.mirrorDeleteFromSettings(source.settingsJson)
        val outcome = coordinator.finalizeSync(jobId, mirrorDelete)
        if (outcome is ImportCoordinator.SyncFinalizeOutcome.PendingConfirmation) {
            val now = System.currentTimeMillis()
            db.settingsKvDao().upsert(
                SettingsKvEntity(
                    key = NasSourceLifecycle.pendingDeleteKey(source.id),
                    valueJson = outcome.count.toString(),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    private suspend fun drive(jobId: String) {
        val adapter = adapterResolver(jobId)
        try {
            val job = db.importJobDao().getById(jobId) ?: return
            if (job.cursor != ImportCoordinator.CURSOR_DONE) {
                coordinator.enumerate(jobId, adapter)
                finalizeNasSync(jobId)
            }
            while (true) {
                val current = db.importJobDao().getById(jobId) ?: return
                if (current.state in DRIVING_STOP_STATES) return
                val item = nextItem(jobId) ?: return
                processOne(item, adapter)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            runCatching { coordinator.signal(jobId, ImportJobAggregate.Signal.FATAL_ERROR) }
        }
    }

    private suspend fun nextItem(jobId: String): ImportItemEntity? {
        val queued = db.importItemDao().getByJob(jobId).firstOrNull {
            it.state == ImportItemState.QUEUED
        }
        if (queued != null) return queued
        return db.importItemDao().dueForRetry(System.currentTimeMillis())
            .firstOrNull { it.jobId == jobId }
    }

    private suspend fun processOne(item: ImportItemEntity, adapter: SourceAdapter) {
        val sourceAsset = db.sourceAssetDao().getById(item.sourceAssetId)
        if (sourceAsset == null) {
            failQuietly(item.id, ERR_ASSET_GONE, retryable = false)
            return
        }
        val metadata = try {
            resolveMetadata(adapter, sourceAsset)
        } catch (failure: Throwable) {
            failQuietly(item.id, errorCodeOf(failure), isRetryable(failure))
            return
        }
        try {
            coordinator.processItem(item.id, adapter, metadata)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failQuietly(item.id, errorCodeOf(failure), isRetryable(failure))
        }
    }

    private suspend fun failQuietly(itemId: String, code: String, retryable: Boolean) {
        runCatching { coordinator.fail(itemId, code, retryable) }
    }

    private fun errorCodeOf(failure: Throwable): String = when (failure) {
        is WebDavClient.DavException -> failure.code
        is AssetTruncatedException -> ERR_MEDIA_TRUNCATED
        is SmbClient.SmbException -> failure.code
        is DecodeFailedException -> ERR_MEDIA_DECODE_FAILED
        is UnsupportedFormatException -> ERR_MEDIA_UNSUPPORTED
        is FileNotFoundException -> ERR_ASSET_GONE
        is SecurityException -> ERR_PERMISSION_DENIED
        is IllegalArgumentException -> ERR_DB_CONSTRAINT_VIOLATION
        is IOException ->
            if (failure.message?.contains("ENOSPC") == true ||
                failure.message?.contains("No space") == true
            ) {
                ERR_STORE_LOW_SPACE
            } else {
                ERR_SRC_UNAVAILABLE
            }
        else -> ERR_SRC_UNAVAILABLE
    }

    private fun isRetryable(failure: Throwable): Boolean =
        errorCodeOf(failure) == ERR_SRC_UNAVAILABLE || errorCodeOf(failure) == ERR_MEDIA_TRUNCATED

    private fun resolveMetadata(
        adapter: SourceAdapter,
        sourceAsset: SourceAssetEntity,
    ): ImportCoordinator.AssetCommitMetadata {
        val extension = sourceAsset.remoteKey.substringAfterLast('.', "").lowercase()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        adapter.openAsset(sourceAsset.remoteKey).use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            if (VideoProbe.isRawExtension(extension)) {
                throw UnsupportedFormatException()
            }
            throw DecodeFailedException()
        }
        val format = when (bounds.outMimeType?.lowercase()) {
            "image/jpeg", "image/jpg" -> AssetFormat.JPEG
            "image/png" -> AssetFormat.PNG
            "image/heic", "image/heif" -> AssetFormat.HEIC
            else -> throw UnsupportedFormatException()
        }
        val orientation = ExifOrientation.normalize(
            runCatching {
                adapter.openAsset(sourceAsset.remoteKey).use { stream ->
                    ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                }
            }.getOrDefault(1),
        )
        return ImportCoordinator.AssetCommitMetadata(
            format = format,
            pixelWidth = bounds.outWidth.toLong(),
            pixelHeight = bounds.outHeight.toLong(),
            exifOrientation = orientation.toLong(),
            capturedAt = sourceAsset.remoteTakenAt,
            capturedAtSource = if (sourceAsset.remoteTakenAt != null) {
                CapturedAtSource.EXIF
            } else {
                CapturedAtSource.UNKNOWN
            },
            originalFileName = null,
        )
    }

    private class DecodeFailedException : Exception()

    private class UnsupportedFormatException : Exception()

    companion object {
        private val DRIVING_STOP_STATES = setOf(
            ImportJobState.PAUSED,
            ImportJobState.PAUSED_AUTO,
            ImportJobState.CANCELED,
        )

        private val INTERRUPTED_ITEM_STATES = setOf(
            ImportItemState.DOWNLOADING,
            ImportItemState.VERIFYING,
            ImportItemState.COMMITTING,
        )

        const val ERR_ASSET_GONE = "SRC_ASSET_GONE"
        const val ERR_MEDIA_DECODE_FAILED = "MEDIA_DECODE_FAILED"
        const val ERR_MEDIA_UNSUPPORTED = "MEDIA_UNSUPPORTED_FORMAT"
        const val ERR_MEDIA_TRUNCATED = "MEDIA_TRUNCATED"
        const val ERR_PERMISSION_DENIED = "SRC_PERMISSION_DENIED"
        const val ERR_STORE_LOW_SPACE = "STORE_LOW_SPACE"
        const val ERR_SRC_UNAVAILABLE = "SRC_UNAVAILABLE"
        const val ERR_DB_CONSTRAINT_VIOLATION = "DB_CONSTRAINT_VIOLATION"
    }
}
