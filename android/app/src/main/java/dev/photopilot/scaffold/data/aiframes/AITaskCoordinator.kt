package dev.photopilot.scaffold.data.aiframes

import android.content.*
import android.net.*
import android.os.Build
import android.os.PowerManager
import androidx.room.withTransaction
import dev.photopilot.scaffold.data.db.SettingsKvEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

internal data class AINotificationState(val target: String, val modelId: String, val title: String, val progress: String)

class AITaskCoordinator(private val aiFrames: AIFrameService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val records = java.util.concurrent.ConcurrentHashMap<String, AITaskRecord>()
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val results = java.util.concurrent.ConcurrentHashMap<String, List<AIPreview>>()
    private val resultVersions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val sessionPreviews = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pauseRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val executingPreviews = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val active = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val frameProgress = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
    private val draftWrites = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val writes = Mutex()
    private val modelOperations = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val taskOperations = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val deletingModels = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var serviceOwner: String? = null
    private var observing = false
    @Volatile var serviceRunning = false
        private set
    @Volatile var systemStopped = false
        private set
    @Volatile var systemStopReason = "interrupted"
        private set
    @Volatile var executionEpoch = 0L
        private set

    private fun modelOperation(id: String) = modelOperations.getOrPut(id) { Mutex() }
    private fun taskOperation(id: String) = taskOperations.getOrPut(id) { Mutex() }
    fun isDeleting(modelId: String) = modelId in deletingModels
    val hasActiveWork get() = !systemStopped && (active.isNotEmpty() || jobs.values.any { it.isActive })
    fun cached(id: String) = records[id]
    fun running(id: String) = jobs[id]?.isActive == true

    internal fun notificationState(chinese: Boolean): AINotificationState {
        val target = active.keys.firstOrNull().orEmpty()
        val modelId = active[target].orEmpty()
        val model = aiFrames.models.models.firstOrNull { it.id == modelId }
        val title = model?.let { if (chinese) it.chinese else it.english }.orEmpty()
        val task = records[target]
        val frame = frameProgress[target]
        val progress = when {
            task != null && task.total > 0 && task.kind == "download" -> (if (chinese) "下载 " else "Download ") + "${task.done * 100 / task.total}%"
            task != null && task.total > 0 -> (if (chinese) "分类 " else "Classifying ") + "${task.done} / ${task.total}"
            frame != null -> (if (chinese) "分类 " else "Classifying ") + "${frame.first} / ${frame.second}"
            else -> ""
        }
        return AINotificationState(target, modelId, title, progress)
    }

    fun attachService(owner: String) {
        synchronized(active) {
            serviceOwner = owner
            serviceRunning = true
        }
        ensureObservers()
    }

    fun releaseService(owner: String) {
        synchronized(active) {
            if (serviceOwner == owner) {
                serviceOwner = null
                serviceRunning = false
            }
        }
    }

    fun begin(owner: String, model: AIModel) {
        active[owner] = model.id
        if (!systemStopped && (aiFrames.foreground || serviceRunning)) AIWorkService.start(aiFrames.context)
    }

    fun progress(owner: String, completed: Int, total: Int) {
        frameProgress[owner] = completed to total
    }

    fun end(owner: String) {
        active.remove(owner)
        frameProgress.remove(owner)
    }

    suspend fun record(id: String): AITaskRecord? {
        records[id]?.let { return it }
        val row = aiFrames.db.settingsKvDao().get("ai.task.$id") ?: return null
        return runCatching { AITaskRecord.parse(JSONObject(row.valueJson)) }.getOrNull()?.also { records[id] = it }
    }

    private suspend fun put(record: AITaskRecord) {
        val now = System.currentTimeMillis()
        aiFrames.db.settingsKvDao().upsert(SettingsKvEntity("ai.task.${record.id}", record.json().toString(), now, now))
        records[record.id] = record
    }

    private suspend fun update(id: String, generation: Long, change: (AITaskRecord) -> AITaskRecord) = writes.withLock {
        val current = record(id) ?: return@withLock
        if (current.generation == generation && current.status !in listOf("deleted", "deleting")) put(change(current))
    }

    suspend fun startDownload(model: AIModel, source: String, allowMetered: Boolean) = modelOperation(model.id).withLock {
        val id = "download:${model.id}"
        taskOperation(id).withLock task@ {
            require(!aiFrames.closed) { "task_closed" }
            if (running(id)) return@task
            val old = record(id)
            val next = AITaskRecord(
                id = id, kind = "download", modelId = model.id, modelRevision = model.revision,
                source = source, allowMetered = allowMetered, total = model.bytes, generation = (old?.generation ?: 0) + 1,
            )
            pauseRequests.remove(id)
            writes.withLock { put(next) }
            launch(next, model)
        }
    }

    suspend fun saveDownloadPreferences(model: AIModel, source: String, allowMetered: Boolean) = writes.withLock {
        if (aiFrames.closed) return@withLock
        val id = "download:${model.id}"
        val current = record(id) ?: AITaskRecord(id, "download", model.id, model.revision, status = "idle", total = model.bytes)
        if (current.source == source && current.allowMetered == allowMetered) return@withLock
        if (!running(id) && current.status != "deleting") put(current.copy(source = source, allowMetered = allowMetered))
    }

    suspend fun startPreview(id: String, model: AIModel, collections: List<String>, recursive: Boolean, rules: List<AIRule>, frameId: String?, draft: Map<String, String>) = modelOperation(model.id).withLock {
        taskOperation(id).withLock task@ {
            require(!aiFrames.closed) { "task_closed" }
            if (running(id)) return@task
            val old = record(id)
            require(old?.status != "saved") { "preview_saved" }
            val oldCategories = old?.rules.orEmpty().zip(old?.categoryIds.orEmpty())
                .groupBy { AITaskRecord.ruleJson(it.first).toString() }
                .mapValues { it.value.map { pair -> pair.second }.toMutableList() }
            val categories = rules.map { rule ->
                oldCategories[AITaskRecord.ruleJson(rule).toString()]?.removeFirstOrNull() ?: UUID.randomUUID().toString()
            }
            val next = AITaskRecord(
                id = id, kind = "preview", modelId = model.id, modelRevision = model.revision,
                generation = (old?.generation ?: 0) + 1, collections = collections, recursive = recursive, rules = rules,
                frameId = frameId, frameRevision = frameId?.let { aiFrames.state.get(it)?.revision } ?: 0,
                categoryIds = categories, draft = draft,
            )
            pauseRequests.remove(id)
            results.remove(id)
            writes.withLock { put(next) }
            launch(next, model)
        }
    }

    fun pause(id: String): Job {
        val expectedGeneration = records[id]?.generation
        pauseRequests.add(id)
        return scope.launch {
            val current = record(id) ?: return@launch
            if (expectedGeneration != null && current.generation != expectedGeneration) return@launch
            update(id, current.generation) { it.copy(userPaused = true, status = "user_paused", reason = "user") }
            if (current.kind == "download") {
                jobs[id]?.cancel()
                aiFrames.models.interruptDownload(current.modelId)
            } else if (id !in executingPreviews) jobs[id]?.cancel()
        }
    }

    private suspend fun pauseCompetingPreviews(frameId: String?, exceptId: String? = null) {
        val competing = records.values.filter {
            it.kind == "preview" && it.frameId == frameId && it.id != exceptId && running(it.id)
        }
        for (preview in competing) pause(preview.id).join()
    }

    internal suspend fun pausePreviewsForFrame(frameId: String) = pauseCompetingPreviews(frameId)

    suspend fun continueTask(id: String) {
        val observed = record(id) ?: return
        jobs[id]?.join()
        modelOperation(observed.modelId).withLock {
            taskOperation(id).withLock task@ {
                val current = record(id) ?: return@task
                if (current.generation != observed.generation || current.status in listOf("deleted", "deleting")) return@task
                if (current.status == "saved") {
                    current.frameIds.forEach(aiFrames::resume)
                    return@task
                }
                val model = aiFrames.models.models.firstOrNull { it.id == current.modelId && it.revision == current.modelRevision } ?: return@task
                pauseRequests.remove(id)
                allowForegroundExecution()
                val next = current.copy(userPaused = false, status = "queued", reason = null, generation = current.generation + 1)
                writes.withLock { put(next) }
                launch(next, model)
            }
        }
    }

    private fun launch(task: AITaskRecord, model: AIModel) = synchronized(jobs) {
        if (jobs[task.id]?.isActive == true) return@synchronized
        if (task.kind == "preview" && jobs.any { (id, job) ->
                job.isActive && records[id]?.kind == "preview"
            }) {
            scope.launch {
                update(task.id, task.generation) { it.copy(status = "failed", reason = "preview_already_active") }
            }
            return@synchronized
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val current = records[task.id]
            if (current?.generation != task.generation || current.userPaused || task.id in pauseRequests || current.status in listOf("deleted", "deleting")) return@launch
            val epoch = executionEpoch
            begin(task.id, model)
            try {
                update(task.id, task.generation) {
                    it.copy(status = if (it.userPaused) "user_paused" else if (task.kind == "preview") "queued" else "running")
                }
                if (task.kind == "download") download(task, model, epoch)
                else preview(task, model, epoch)
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    update(task.id, task.generation) {
                        it.copy(status = if (it.userPaused) "user_paused" else "system_paused", reason = if (it.userPaused) "user" else aiFrames.pauseReason() ?: "interrupted")
                    }
                }
            } catch (failure: Throwable) {
                val protection = (failure as? AIProtectionPause)?.reason ?: aiFrames.pauseReason()
                val reason = when {
                    protection != null -> protection
                    failure.message == "preview_already_active" -> "preview_already_active"
                    failure is AIRuntimeInterrupted -> "runtime_interrupted"
                    failure.message == "model_queue_timeout" -> "model_queue_timeout"
                    failure.message == "metered" -> "wifi_required"
                    failure.message == "REMOTE_LOW_SPACE" -> "low_storage"
                    failure.message == "offline" || failure is java.net.SocketTimeoutException || failure is java.net.UnknownHostException || failure is java.net.ConnectException -> "network"
                    failure.message in listOf("model_missing", "model_integrity") -> "model_missing"
                    failure.message == "description_too_long" -> "description_too_long"
                    failure.message in listOf("preview_invalidated", "preview_superseded") -> "content_changed"
                    failure.message == "frame_removed" -> "frame_removed"
                    failure is LinkageError || failure.message == "runtime_unavailable" -> "device_unsupported"
                    else -> "operation_failed"
                }
                val status = when (reason) {
                    "low_battery", "temperature", "timeout", "background", "memory" -> "system_paused"
                    "network", "wifi_required", "low_storage" -> "waiting"
                    "model_missing" -> "model_missing"
                    "content_changed" -> "needs_resume"
                    "frame_removed" -> "deleted"
                    else -> "failed"
                }
                update(task.id, task.generation) {
                    it.copy(status = if (it.userPaused) "user_paused" else status, reason = if (it.userPaused) "user" else reason)
                }
                if (reason == "frame_removed") {
                    AIPreviewCheckpoint.delete(aiFrames.db, task.id)
                    results.remove(task.id)
                }
            } finally {
                executingPreviews.remove(task.id)
                end(task.id)
                val completedJob = currentCoroutineContext()[Job]
                synchronized(jobs) {
                    if (jobs[task.id] === completedJob) jobs.remove(task.id)
                }
                val latest = records[task.id]
                if (!aiFrames.closed && !aiFrames.shouldPause() && latest?.status == "system_paused" && !latest.userPaused) scope.launch { recover() }
            }
        }
        jobs[task.id] = job
        if (task.kind == "preview") sessionPreviews.add(task.id)
        job.start()
    }

    private suspend fun download(task: AITaskRecord, model: AIModel, epoch: Long) {
        var lastProgress = 0L
        aiFrames.models.download(model, task.source, task.allowMetered, shouldStop = {
            aiFrames.shouldPause() || task.id in pauseRequests || executionEpoch != epoch || records[task.id]?.generation != task.generation
        }) { count, total ->
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastProgress >= 500 || count == total) {
                lastProgress = now
                scope.launch {
                    update(task.id, task.generation) { if (it.status == "running") it.copy(done = count, total = total) else it }
                }
            }
        }
        update(task.id, task.generation) { if (it.userPaused) it else it.copy(status = "complete", reason = null, done = model.bytes, total = model.bytes) }
        aiFrames.resumeAutomatic()
    }

    private suspend fun preview(task: AITaskRecord, model: AIModel, epoch: Long) {
        var lastProgress = 0L
        val previews = aiFrames.preview(
            model, task.collections, task.recursive, task.rules, task.frameId, task.id, true,
            stopRequested = { task.id in pauseRequests || systemStopped || executionEpoch != epoch },
            commitAllowed = { executionEpoch == epoch && records[task.id]?.generation == task.generation },
            onLeaseAcquired = {
                executingPreviews.add(task.id)
                update(task.id, task.generation) { if (it.status == "queued") it.copy(status = "running", total = 0) else it }
            },
        ) { count, total ->
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastProgress >= 300 || count == total) {
                lastProgress = now
                scope.launch {
                    update(task.id, task.generation) { if (it.status == "running") it.copy(done = count.toLong(), total = total.toLong()) else it }
                }
            }
        }
        val latest = records[task.id]
        if (latest?.generation != task.generation || latest.status in listOf("deleted", "deleting")) return
        resultVersions.remove(task.id)
        results[task.id] = previews
        update(task.id, task.generation) {
            val status = when {
                it.userPaused -> "user_paused"
                previews.all { preview -> preview.isComplete } -> "awaiting_confirmation"
                else -> "system_paused"
            }
            val reason = when (status) {
                "user_paused" -> "user"
                "system_paused" -> aiFrames.pauseReason() ?: "interrupted"
                else -> null
            }
            it.copy(status = status, reason = reason, done = previews.first().scannedCount.toLong(), total = previews.first().totalCount.toLong())
        }
    }

    suspend fun previewResult(id: String): List<AIPreview>? {
        val libraryVersion = dev.photopilot.scaffold.data.repo.LibraryVersionRepository(aiFrames.db.settingsKvDao()).current() ?: 0L
        results[id]?.takeIf { resultVersions[id] == libraryVersion }?.let { return it }
        val task = record(id) ?: return null
        if (task.kind != "preview" || running(id) || task.status !in listOf("user_paused", "system_paused", "awaiting_confirmation")) return null
        val model = aiFrames.models.models.firstOrNull { it.id == task.modelId && it.revision == task.modelRevision } ?: return null
        return aiFrames.cachedPreview(model, task.collections, task.recursive, task.rules, task.frameId, task.id, task.done.toInt(), task.total.toInt(), task.status == "awaiting_confirmation").also {
            if (records[id]?.generation == task.generation) {
                results[id] = it
                resultVersions[id] = libraryVersion
            }
        }
    }

    suspend fun confirm(id: String, names: List<String>, chosen: List<Set<String>>, automatic: Boolean): List<String> {
        require(!aiFrames.closed) { "task_closed" }
        jobs[id]?.join()
        refreshFrameRevision(id)
        val task = requireNotNull(record(id))
        if (task.status == "saved") {
            require(task.frameIds.all { aiFrames.db.frameDao().getById(it) != null }) { "frame_removed" }
            return task.frameIds
        }
        val previews = requireNotNull(previewResult(id))
        task.frameId?.let { aiFrames.stop(it) }
        return writes.withLock {
            aiFrames.db.withTransaction {
                val row = requireNotNull(aiFrames.db.settingsKvDao().get("ai.task.$id"))
                val latest = AITaskRecord.parse(JSONObject(row.valueJson))
                require(latest.generation == task.generation && latest.status in listOf("user_paused", "system_paused", "awaiting_confirmation")) { "preview_superseded" }
                val frames = aiFrames.savePreviews(previews, names, chosen, automatic, task.frameId, task.frameRevision,
                    latest.userPaused || previews.any { !it.isComplete }, task.id, task.categoryIds)
                put(latest.copy(status = "saved", frameIds = frames))
                AIPreviewCheckpoint.delete(aiFrames.db, task.id)
                results.remove(id)
                frames
            }
        }
    }

    fun rememberDraft(id: String, draft: Map<String, String>, immediate: Boolean = false) {
        draftWrites.remove(id)?.cancel()
        draftWrites[id] = scope.launch {
            if (!immediate) delay(300)
            saveDraft(id, draft)
        }
    }

    suspend fun refreshFrameRevision(id: String) {
        val task = record(id) ?: return
        val configuration = task.frameId?.let { aiFrames.state.get(it) } ?: return
        if (task.modelId != configuration.modelId || task.modelRevision != configuration.modelRevision || task.collections != configuration.collections || task.recursive != configuration.recursive || task.rules != listOf(configuration.rule)) return
        update(id, task.generation) { it.copy(frameRevision = configuration.revision) }
    }

    suspend fun saveDraft(id: String, draft: Map<String, String>) {
        if (aiFrames.closed) return
        val current = record(id) ?: return
        update(id, current.generation) { it.copy(draft = draft) }
    }

    suspend fun deleteModel(model: AIModel) = modelOperation(model.id).withLock {
        deleteModelFiles(model)
    }

    private suspend fun deleteModelFiles(model: AIModel) {
        deletingModels.add(model.id)
        try {
            val affected = mutableListOf<AITaskRecord>()
            for (row in aiFrames.db.settingsKvDao().getByPrefix("ai.task.")) {
                val snapshot = runCatching { AITaskRecord.parse(JSONObject(row.valueJson)) }.getOrNull() ?: continue
                taskOperation(snapshot.id).withLock task@ {
                    val current = record(snapshot.id) ?: return@task
                    if (current.modelId != model.id) return@task
                    pauseRequests.add(current.id)
                    val invalidated = current.copy(status = "deleting", generation = current.generation + 1)
                    writes.withLock { put(invalidated) }
                    affected.add(invalidated)
                    jobs[current.id]?.cancel()
                    if (current.kind == "download") aiFrames.models.interruptDownload(model.id)
                    results.remove(current.id)
                }
            }
            aiFrames.deleteModel(model)
            for (task in affected) {
                writes.withLock {
                    if (records[task.id]?.generation == task.generation) put(task.copy(status = "deleted"))
                }
            }
        } finally {
            deletingModels.remove(model.id)
        }
    }

    fun systemStop(reason: String = "interrupted", owner: String? = null) {
        val stoppedEpoch: Long
        val stoppedTasks: List<AITaskRecord>
        val stoppedJobs: Map<String, Job>
        val stoppedFrames: List<String>
        synchronized(active) {
            if (owner != null && serviceOwner != owner) return
            systemStopped = true
            systemStopReason = reason
            serviceRunning = false
            serviceOwner = null
            executionEpoch++
            stoppedEpoch = executionEpoch
            stoppedTasks = records.values.filter { active.containsKey(it.id) }
            stoppedJobs = stoppedTasks.mapNotNull { task -> jobs[task.id]?.let { task.id to it } }.toMap()
            stoppedFrames = active.keys.filter { it.startsWith("frame:") }.map { it.removePrefix("frame:") }
        }
        scope.launch {
            if (executionEpoch == stoppedEpoch) {
                for (task in stoppedTasks) update(task.id, task.generation) {
                    if (it.userPaused) it else it.copy(status = "system_paused", reason = reason)
                }
                for (frameId in stoppedFrames) aiFrames.markSystemStopped(frameId, stoppedEpoch)
            }
            for (task in stoppedTasks.filter { it.kind == "download" }) {
                stoppedJobs[task.id]?.cancel()
                if (records[task.id]?.generation == task.generation) aiFrames.models.interruptDownload(task.modelId)
            }
        }
    }

    internal fun allowForegroundExecution() = synchronized(active) {
        if (systemStopped) executionEpoch++
        systemStopped = false
    }

    fun foregroundEntered() {
        allowForegroundExecution()
        ensureObservers()
        scope.launch { recover() }
    }

    suspend fun recover() {
        if (aiFrames.shouldPause()) return
        for (row in aiFrames.db.settingsKvDao().getByPrefix("ai.task.")) {
            val snapshot = runCatching { AITaskRecord.parse(JSONObject(row.valueJson)) }.getOrNull() ?: continue
            records.putIfAbsent(snapshot.id, snapshot)
            if (snapshot.kind == "preview" && snapshot.id !in sessionPreviews) continue
            val model = aiFrames.models.models.firstOrNull { it.id == snapshot.modelId && it.revision == snapshot.modelRevision } ?: continue
            modelOperation(model.id).withLock {
                val current = record(snapshot.id) ?: return@withLock
                if (current.status == "deleting") {
                    deleteModelFiles(model)
                } else {
                    taskOperation(current.id).withLock task@ {
                        val latest = record(current.id) ?: return@task
                        if (latest.generation != snapshot.generation || !latest.mayRecover || latest.id in pauseRequests || running(latest.id) || aiFrames.shouldPause()) return@task
                        launch(latest, model)
                    }
                }
            }
        }
    }

    private fun retryAfterEnvironmentChange() {
        if (!aiFrames.foreground && !serviceRunning) return
        scope.launch {
            if (aiFrames.shouldPause()) {
                records.values.filter { it.kind == "download" && running(it.id) }.forEach { aiFrames.models.interruptDownload(it.modelId) }
            } else {
                recover()
                aiFrames.resumeAutomatic()
            }
        }
    }

    private val networkObserver = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { retryAfterEnvironmentChange() }
        override fun onLost(network: Network) { retryAfterEnvironmentChange() }
    }
    private val batteryObserver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { retryAfterEnvironmentChange() }
    }
    private val thermalObserver = PowerManager.OnThermalStatusChangedListener { retryAfterEnvironmentChange() }

    private fun ensureObservers() = synchronized(active) {
        if (observing) return@synchronized
        observing = true
        runCatching { aiFrames.context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkObserver) }
        runCatching { aiFrames.context.getSystemService(PowerManager::class.java).addThermalStatusListener(thermalObserver) }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) aiFrames.context.registerReceiver(batteryObserver, filter, Context.RECEIVER_NOT_EXPORTED)
        else aiFrames.context.registerReceiver(batteryObserver, filter)
    }

    suspend fun shutdown() {
        if (observing) {
            runCatching { aiFrames.context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkObserver) }
            runCatching { aiFrames.context.getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalObserver) }
            runCatching { aiFrames.context.unregisterReceiver(batteryObserver) }
        }
        scope.coroutineContext[Job]?.cancelAndJoin()
        active.clear()
        AIWorkService.stop(aiFrames.context)
    }
}
