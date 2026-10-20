package dev.photopilot.scaffold.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.FrameEntity
import dev.photopilot.scaffold.data.db.MediaKind
import dev.photopilot.scaffold.data.db.PlaybackSnapshotEntity
import dev.photopilot.scaffold.data.logic.FramePlaybackSettings
import dev.photopilot.scaffold.data.logic.PlaybackController
import dev.photopilot.scaffold.data.logic.PlaybackEvent
import dev.photopilot.scaffold.data.logic.HolidayTiming
import dev.photopilot.scaffold.data.logic.PlaybackRestore
import dev.photopilot.scaffold.data.logic.PlaybackTiming
import dev.photopilot.scaffold.data.logic.QualityScale
import dev.photopilot.scaffold.data.render.BitmapRenderer
import dev.photopilot.scaffold.ui.permissions.PhotoPermissionGate
import dev.photopilot.scaffold.data.repo.SelectionRefEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors

class PlayerEngine(
    context: Context,
    private val env: AppEnvironment,
    private val frame: FrameEntity,
    private val listener: Listener,
    private val scope: CoroutineScope,
) {

    interface Listener {
        fun onStateChanged(state: String)
        fun onShowAsset(
            asset: AssetEntity,
            decoded: DecodedAsset,
            transitionMs: Long,
            direction: Int,
            position: Int,
        )
        fun onShowVideo(
            asset: AssetEntity,
            path: String,
            blurred: Bitmap?,
            edgeColor: Int?,
        )
        fun onClearTextures()
        fun onShowSafeImage()
        fun onQueueInfo(size: Int)
        fun onDecodeSkipped()
        fun onKeepScreenOn(keep: Boolean)
    }

    data class DecodedAsset(
        val bitmap: Bitmap,
        val blurred: Bitmap?,
        val edgeColor: Int?,
    )

    private val appContext = context.applicationContext
    private var settings = FramePlaybackSettings.parse(frame.settingsJson)
    private val originalsDir = File(appContext.filesDir, "originals")
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var state = PlaybackController.IDLE
    private var queue: List<String> = emptyList()
    private var assetsById: Map<String, AssetEntity> = emptyMap()
    private var index = 0
    private var transitionTargetIndex: Int? = null
    private var decodedCurrent: DecodedAsset? = null
    private var decodedNext: DecodedAsset? = null
    private var consecutiveDecodeFails = 0
    private var remainingHoldMs: Long? = null
    private var nextTransitionAtMs = 0L
    private var pendingRebuild = false
    private var restOverride = false
    private var stopped = false
    private var markedPlayedOnce = false
    private val holidayEnterAnnounced = mutableSetOf<String>()
    private val holidayMissingAnnounced = mutableSetOf<String>()
    private var holidayPendingReturn: Pair<String, String>? = null
    private var lastHolidayPickId: String? = null
    private var pendingHolidayEnter: HolidayEnter? = null
    private var holidayCheck: kotlinx.coroutines.Job? = null

    data class HolidayEnter(val ruleId: String, val frameId: String, val name: String)

    var onHolidayEnter: ((ruleId: String, frameId: String, name: String) -> Unit)? = null
    var onHolidayReturn: ((ruleId: String, name: String) -> Unit)? = null
    var onHolidayMissing: ((ruleId: String, name: String) -> Unit)? = null

    fun adoptHolidayRule(ruleId: String, ruleName: String) {
        holidayEnterAnnounced.add(ruleId)
        holidayPendingReturn = ruleId to ruleName
    }

    private var useSnapshotOnRebuild = false
    private var queueGeneration = 0L

    private val holdRunnable = Runnable {
        dispatch(
            PlaybackEvent(
                type = "hold_elapsed",
                nextReady = queue.size > 1 && (decodedNext != null || decodedCurrent != null),
            ),
        )
    }

    private val transitionWatchdogRunnable = Runnable {
        if (state == PlaybackController.TRANSITIONING) {
            transitionTargetIndex = null
            dispatch(PlaybackEvent(type = "transition_finished"))
        }
    }

    private val restTickRunnable = object : Runnable {
        override fun run() {
            if (stopped) return
            checkRestWindow()
            ensureHoldTimerIfHolding()
            handler.postDelayed(this, REST_TICK_MS)
        }
    }
    private val holidayTickRunnable = object : Runnable {
        override fun run() {
            if (stopped) return
            checkHolidayWindow()
            handler.postDelayed(this, HOLIDAY_TICK_MS)
        }
    }

    fun currentState(): String = state

    fun settings(): FramePlaybackSettings = settings

    fun reloadSettings(freshFrame: FrameEntity) {
        settings = FramePlaybackSettings.parse(freshFrame.settingsJson)
    }

    fun start(fromSnapshot: Boolean) {
        useSnapshotOnRebuild = fromSnapshot
        handler.postDelayed(restTickRunnable, REST_TICK_MS)
        handler.postDelayed(holidayTickRunnable, HOLIDAY_TICK_MS)
        if (restActiveNow()) {
            state = PlaybackController.RESTING
            listener.onStateChanged(state)
            updateKeepScreenOn()
            rebuildQueue(safePoint = false, allowSnapshot = fromSnapshot, silent = true)
            return
        }
        dispatch(PlaybackEvent(type = "start"))
    }

    fun stop(clearSnapshot: Boolean) {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        holidayCheck?.cancel()
        if (clearSnapshot) {
            scope.launch(Dispatchers.IO) {
                env.database.playbackSnapshotDao().getByFrame(frame.id)?.let {
                    env.database.playbackSnapshotDao().delete(it)
                }
            }
        }
    }

    fun userPause() = dispatch(PlaybackEvent(type = "user_pause"))

    fun userResume() = dispatch(PlaybackEvent(type = "user_resume"))

    fun userNext() = dispatch(PlaybackEvent(type = "user_next"))

    fun userPrev() = dispatch(PlaybackEvent(type = "user_prev"))

    fun userRetry() = dispatch(PlaybackEvent(type = "user_retry"))

    fun onRestTap() {
        restOverride = true
        dispatch(PlaybackEvent(type = "user_tap"))
    }

    fun onBackground() = dispatch(PlaybackEvent(type = "app_background"))

    fun onForeground() {
        scope.launch {
            val intact = withContext(Dispatchers.IO) {
                val fresh = env.database.frameDao().getById(frame.id) ?: return@withContext false
                fresh.selectionVersion == frame.selectionVersion
            }
            if (stopped) return@launch
            if (state == PlaybackController.SUSPENDED && restActiveNow()) {
                state = PlaybackController.RESTING
                listener.onStateChanged(state)
                updateKeepScreenOn()
                if (!intact) rebuildQueue(safePoint = true, allowSnapshot = false, silent = true)
            } else {
                dispatch(PlaybackEvent(type = "app_foreground", queueIntact = intact))
                checkRestWindow()
            }
        }
    }

    fun onPermissionRevoked() {
        scope.launch {
            val remaining = PhotoPermissionGate.status(appContext) != PhotoPermissionGate.Status.DENIED &&
                queue.isNotEmpty()
            dispatch(PlaybackEvent(type = "asset_revoked", playableRemaining = remaining))
        }
    }

    fun removeCurrent() {
        val assetId = queue.getOrNull(index) ?: return
        scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                env.database.frameDao().getById(frame.id)
            } ?: return@launch
            env.frames.save(
                frameId = frame.id,
                baseVersion = fresh.selectionVersion,
                addExcludes = listOf(SelectionRefEdit(assetId = assetId, sourceAssetId = null)),
            )
            dispatch(PlaybackEvent(type = "selection_changed", category = null))
        }
    }

    fun onExternalSelectionChanged() {
        dispatch(PlaybackEvent(type = "selection_changed", category = null))
    }

    fun transitionFinished() = dispatch(PlaybackEvent(type = "transition_finished"))

    private fun dispatch(event: PlaybackEvent) {
        if (stopped) return
        val outcome = PlaybackController.reduce(state, event)
        state = outcome.state
        listener.onStateChanged(state)
        updateKeepScreenOn()
        val effects = outcome.effects
        effects.forEach(::applyEffect)
        if (state == PlaybackController.TRANSITIONING && transitionTargetIndex == null) {
            when (event.type) {
                "user_prev" -> beginTransitionTo(index - 1, -1)
                "hold_elapsed", "user_next" -> beginTransitionTo(index + 1, 1)
                else -> Unit
            }
        }
        if (state == PlaybackController.TRANSITIONING) {
            handler.removeCallbacks(transitionWatchdogRunnable)
            handler.postDelayed(transitionWatchdogRunnable, TRANSITION_WATCHDOG_MS)
        } else {
            handler.removeCallbacks(transitionWatchdogRunnable)
        }
        ensureHoldTimerIfHolding()
        if (state == PlaybackController.HOLDING && !markedPlayedOnce) {
            markedPlayedOnce = true
            scope.launch(Dispatchers.IO) {
                env.settings.markPlayedOnce(frame.id)
            }
        }
    }

    private fun applyEffect(effect: String) {
        when (effect) {
            PlaybackController.EFFECT_REBUILD_QUEUE ->
                rebuildQueue(safePoint = false, allowSnapshot = useSnapshotOnRebuild)
            PlaybackController.EFFECT_REBUILD_AT_SAFE_POINT ->
                if (state == PlaybackController.TRANSITIONING) {
                    pendingRebuild = true
                } else {
                    rebuildQueue(safePoint = true, allowSnapshot = false)
                }
            PlaybackController.EFFECT_APPLY_PENDING_REBUILD ->
                if (pendingRebuild) {
                    pendingRebuild = false
                    rebuildQueue(safePoint = true, allowSnapshot = false)
                }
            PlaybackController.EFFECT_PERSIST_SNAPSHOT -> persistSnapshot()
            PlaybackController.EFFECT_SAVE_REMAINING_HOLD -> {
                remainingHoldMs = PlaybackTiming.remainingAtPause(
                    nextTransitionAtMs,
                    System.currentTimeMillis(),
                )
                handler.removeCallbacks(holdRunnable)
            }
            PlaybackController.EFFECT_RESET_HOLD_TIMER -> {
                if (!currentIsVideo()) {
                    val delay = remainingHoldMs ?: settings.holdSeconds * 1000L
                    remainingHoldMs = null
                    scheduleHold(delay)
                }
            }
            PlaybackController.EFFECT_COMPLETE_TRANSITION ->
                transitionTargetIndex?.let { target ->
                    transitionTargetIndex = null
                    decodedNext?.let { decoded ->
                        index = target
                        decodedCurrent = decoded
                        currentAsset()?.let { asset ->
                            flushHolidayEnter()
                            listener.onShowAsset(asset, decoded, 0L, 0, index + 1)
                        }
                    }
                }
            PlaybackController.EFFECT_CLEAR_TEXTURES -> {
                decodedCurrent = null
                decodedNext = null
                listener.onClearTextures()
            }
            PlaybackController.EFFECT_SHOW_SAFE_IMAGE -> listener.onShowSafeImage()
            PlaybackController.EFFECT_SKIP_TO_NEXT -> skipToNext()
            PlaybackController.EFFECT_RESUME_UNTIL_REST_END ->
                if (!currentIsVideo()) {
                    scheduleHold(settings.holdSeconds * 1000L)
                }
        }
    }

    private fun rebuildQueue(safePoint: Boolean, allowSnapshot: Boolean, silent: Boolean = false) {
        val generation = ++queueGeneration
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val live = env.frames.buildPlayQueue(frame.id)
                val restored = if (allowSnapshot) loadSnapshotQueue(live) else null
                val result = restored ?: PlaybackRestore.Result(live, 0, null)
                result to env.database.assetDao().getByIds(result.queue).associateBy { it.id }
            }
            if (stopped || generation != queueGeneration) return@launch
            val anchor = if (safePoint) queue.getOrNull(index) else null
            queue = loaded.first.queue
            assetsById = loaded.second
            decodedNext = null
            transitionTargetIndex = null
            listener.onQueueInfo(queue.size)
            if (queue.isEmpty()) {
                decodedCurrent = null
                listener.onClearTextures()
                listener.onShowSafeImage()
                if (state !in setOf(PlaybackController.RESTING, PlaybackController.PAUSED, PlaybackController.SUSPENDED)) {
                    state = PlaybackController.EMPTY
                }
                listener.onStateChanged(state)
                updateKeepScreenOn()
                handler.removeCallbacks(holdRunnable)
                return@launch
            }
            index = if (safePoint) {
                anchor?.let { queue.indexOf(it).takeIf { pos -> pos >= 0 } }
                    ?: index.coerceIn(0, queue.size - 1)
            } else {
                remainingHoldMs = loaded.first.remainingHoldMs
                loaded.first.index
            }
            if (state == PlaybackController.EMPTY) {
                state = PlaybackController.PREPARING
                listener.onStateChanged(state)
                updateKeepScreenOn()
            }
            decodeAndShowFirst(silent)
        }
    }

    private suspend fun loadSnapshotQueue(live: List<String>): PlaybackRestore.Result? {
        val snapshot = env.database.playbackSnapshotDao().getByFrame(frame.id) ?: return null
        val fresh = env.database.frameDao().getById(frame.id) ?: return null
        if (snapshot.selectionVersion != fresh.selectionVersion) return null
        if (snapshot.seed != fresh.randomSeed) return null
        val cursor = dev.photopilot.scaffold.data.logic.JsonMini.parseObject(snapshot.cursorJson)
        val ids = (cursor["queue"] as? List<*>)?.mapNotNull { it as? String } ?: return null
        if (ids.isEmpty()) return null
        val snapIndex = (cursor["index"] as? Number)?.toInt() ?: 0
        return PlaybackRestore.resolve(live, ids, snapIndex, snapshot.remainingHoldMs)
    }

    private fun showVideoIfNeeded(asset: AssetEntity, silent: Boolean): Boolean {
        if (asset.mediaKind != MediaKind.VIDEO) return false
        val path = java.io.File(originalsDir, asset.originalRelPath).absolutePath
        executor.execute {
            val retriever = android.media.MediaMetadataRetriever()
            val frame = runCatching {
                retriever.setDataSource(path)
                retriever.getFrameAtTime(FIRST_FRAME_TIME_US)
            }.getOrNull()
            try {
                Unit
            } finally {
                retriever.release()
            }
            val blurred = frame?.let { runCatching { BitmapRenderer.blurBackground(it) }.getOrNull() }
            val edge = frame?.let {
                runCatching {
                    BitmapRenderer.edgeColor(it)?.let(android.graphics.Color::parseColor)
                }.getOrNull()
            }
            mainHandler.post {
                if (stopped) return@post
                if (frame == null) {
                    if (!silent) {
                        handleDecodeFailure()
                    }
                    return@post
                }
                consecutiveDecodeFails = 0
                decodedCurrent = null
                prefetchNext()
                flushHolidayEnter()
                listener.onShowVideo(asset, path, blurred, edge)
                if (!silent) {
                    dispatch(PlaybackEvent(type = "first_ready"))
                }
            }
        }
        return true
    }

    fun onVideoCompleted() {
        dispatch(
            PlaybackEvent(
                type = "hold_elapsed",
                nextReady = queue.size > 1,
            ),
        )
    }

    fun onVideoError() {
        handleDecodeFailure()
    }

    private fun currentIsVideo(): Boolean =
        queue.getOrNull(index)?.let { assetsById[it]?.mediaKind == MediaKind.VIDEO } == true

    private fun decodeAndShowFirst(silent: Boolean) {
        val assetId = queue.getOrNull(index) ?: return
        assetsById[assetId]?.let { asset ->
            if (showVideoIfNeeded(asset, silent)) {
                return
            }
        }
        decodeAsset(assetId) { decoded ->
            if (stopped) return@decodeAsset
            if (decoded == null) {
                handleDecodeFailure()
                return@decodeAsset
            }
            consecutiveDecodeFails = 0
            decodedCurrent = decoded
            prefetchNext()
            val asset = assetsById[assetId] ?: return@decodeAsset
            flushHolidayEnter()
            listener.onShowAsset(asset, decoded, 0L, 0, index + 1)
            if (!silent) {
                dispatch(PlaybackEvent(type = "first_ready"))
            }
        }
    }

    private fun beginTransitionTo(target: Int, direction: Int) {
        if (queue.isEmpty()) return
        val safeTarget = ((target % queue.size) + queue.size) % queue.size
        assetsById[queue[safeTarget]]?.let { asset ->
            if (asset.mediaKind == MediaKind.VIDEO) {
                transitionTargetIndex = null
                consecutiveDecodeFails = 0
                index = safeTarget
                if (showVideoIfNeeded(asset, silent = true)) {
                    dispatch(PlaybackEvent(type = "transition_finished"))
                }
                return
            }
        }
        transitionTargetIndex = safeTarget
        val prefetched = if (safeTarget == nextIndex()) decodedNext else null
        if (prefetched != null) {
            completeTransitionTo(safeTarget, prefetched, direction)
            return
        }
        decodeAsset(queue[safeTarget]) { decoded ->
            if (stopped) return@decodeAsset
            if (state != PlaybackController.TRANSITIONING || transitionTargetIndex != safeTarget) {
                return@decodeAsset
            }
            if (decoded == null) {
                handleDecodeFailure()
            } else {
                completeTransitionTo(safeTarget, decoded, direction)
            }
        }
    }

    private fun completeTransitionTo(target: Int, decoded: DecodedAsset, direction: Int) {
        transitionTargetIndex = null
        consecutiveDecodeFails = 0
        index = target
        decodedCurrent = decoded
        prefetchNext()
        val asset = currentAsset() ?: return
        flushHolidayEnter()
        listener.onShowAsset(asset, decoded, transitionMillis(), direction, index + 1)
    }

    private fun skipToNext() {
        listener.onDecodeSkipped()
        if (queue.isEmpty()) return
        val base = transitionTargetIndex ?: index
        if (state == PlaybackController.PREPARING) {
            index = (base + 1) % queue.size
            decodeAndShowFirst(silent = false)
            return
        }
        beginTransitionTo((base + 1) % queue.size, 1)
    }

    private fun handleDecodeFailure() {
        consecutiveDecodeFails += 1
        val limitReached = consecutiveDecodeFails >=
            PlaybackController.decodeFailLimit(queue.size.coerceAtLeast(1))
        dispatch(
            PlaybackEvent(
                type = "decode_fail",
                limitReached = limitReached,
            ),
        )
        if (!limitReached && state == PlaybackController.PREPARING) {
            skipToNext()
        }
    }

    private fun prefetchNext() {
        decodedNext = null
        if (queue.size < 2) return
        val target = nextIndex()
        val assetId = queue.getOrNull(target) ?: return
        decodeAsset(assetId) { decoded ->
            if (stopped || decoded == null) return@decodeAsset
            if (nextIndex() == target) {
                decodedNext = decoded
                if (state == PlaybackController.HOLDING &&
                    nextTransitionAtMs > 0L &&
                    System.currentTimeMillis() >= nextTransitionAtMs
                ) {
                    handler.removeCallbacks(holdRunnable)
                    handler.post(holdRunnable)
                }
            }
        }
    }

    private fun decodeAsset(assetId: String, callback: (DecodedAsset?) -> Unit) {
        val generation = queueGeneration
        val asset = assetsById[assetId]
        if (asset == null) {
            mainHandler.post { if (!stopped && generation == queueGeneration) callback(null) }
            return
        }
        val file = File(originalsDir, asset.originalRelPath)
        val metrics = appContext.resources.displayMetrics
        val targetW = QualityScale.scale(metrics.widthPixels, settings.qualityLevel)
        val targetH = QualityScale.scale(metrics.heightPixels, settings.qualityLevel)
        executor.execute {
            val bitmap = if (file.isFile) {
                runCatching {
                    BitmapRenderer.decodeToTarget(file, targetW, targetH, asset.exifOrientation.toInt())
                }.getOrNull()
            } else {
                null
            }
            val decoded = bitmap?.let {
                val blurred = if (settings.fitMode != FramePlaybackSettings.FIT_COLOR) {
                    runCatching { BitmapRenderer.blurBackground(it) }.getOrNull()
                } else {
                    null
                }
                val edge = if (settings.fitMode == FramePlaybackSettings.FIT_COLOR) {
                    runCatching {
                        BitmapRenderer.edgeColor(it)?.let { hex -> android.graphics.Color.parseColor(hex) }
                    }.getOrNull()
                } else {
                    null
                }
                DecodedAsset(bitmap = it, blurred = blurred, edgeColor = edge)
            }
            mainHandler.post { if (!stopped && generation == queueGeneration) callback(decoded) }
        }
    }

    private fun transitionMillis(): Long {
        if (settings.transition == PlaybackTiming.TRANSITION_NONE) return 0L
        if (reduceMotionEnabled()) return 0L
        return PlaybackTiming.transitionMillis(settings.transition, settings.transitionSeconds)
    }

    private fun reduceMotionEnabled(): Boolean =
        android.provider.Settings.Global.getFloat(
            appContext.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f

    private fun scheduleHold(delayMs: Long) {
        handler.removeCallbacks(holdRunnable)
        val safeDelay = delayMs.coerceAtLeast(0L)
        nextTransitionAtMs = System.currentTimeMillis() + safeDelay
        handler.postDelayed(holdRunnable, safeDelay)
    }

    private fun ensureHoldTimerIfHolding() {
        if (state != PlaybackController.HOLDING || currentIsVideo() || queue.size < 2) return
        if (handler.hasCallbacks(holdRunnable)) return
        val delay = remainingHoldMs ?: settings.holdSeconds * 1000L
        remainingHoldMs = null
        scheduleHold(delay)
    }

    private fun checkRestWindow() {
        val calendar = Calendar.getInstance()
        val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        if (!settings.restActiveAt(minute, false)) {
            restOverride = false
        }
        val active = restActiveNow()
        if (active && state in REST_INTERRUPTIBLE_STATES) {
            dispatch(PlaybackEvent(type = "rest_window_start"))
        } else if (!active && state == PlaybackController.RESTING) {
            dispatch(PlaybackEvent(type = "user_tap"))
        }
    }

    private fun flushHolidayEnter() {
        val pending = pendingHolidayEnter ?: return
        pendingHolidayEnter = null
        handler.post {
            if (!stopped) onHolidayEnter?.invoke(pending.ruleId, pending.frameId, pending.name)
        }
    }

    private fun checkHolidayWindow() {
        if (restActiveNow() || holidayCheck?.isActive == true) return
        val calendar = Calendar.getInstance()
        val date = String.format(
            java.util.Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        holidayCheck = scope.launch {
            if (stopped) return@launch
            val rows = withContext(Dispatchers.IO) { env.database.holidayDao().getEnabled() }
            if (stopped || restActiveNow()) return@launch
            val rules = rows.map {
                HolidayTiming.Rule(
                    id = it.id,
                    enabled = it.enabled,
                    position = it.position,
                    startDate = it.startDate,
                    endDate = it.endDate,
                    startTime = it.startTime?.toInt(),
                    endTime = it.endTime?.toInt(),
                    frameId = it.frameId,
                    name = it.name,
                )
            }
            val pickedId = HolidayTiming.pick(rules, date, minute)
            val picked = rules.firstOrNull { it.id == pickedId }
            if (pickedId != lastHolidayPickId) {
                val previous = lastHolidayPickId
                lastHolidayPickId = pickedId
                if (previous != null) {
                    holidayEnterAnnounced.remove(previous)
                    holidayMissingAnnounced.remove(previous)
                    pendingHolidayEnter?.let { if (it.ruleId != pickedId) pendingHolidayEnter = null }
                }
                if (pickedId == null) {
                    holidayEnterAnnounced.clear()
                    holidayMissingAnnounced.clear()
                    pendingHolidayEnter = null
                    val pending = holidayPendingReturn
                    holidayPendingReturn = null
                    if (pending != null) {
                        onHolidayReturn?.invoke(pending.first, pending.second)
                    }
                }
            }
            if (picked == null) return@launch
            if (frame.id == picked.frameId) {
                pendingHolidayEnter?.let { if (it.ruleId == picked.id) pendingHolidayEnter = null }
                return@launch
            }
            if (picked.id in holidayEnterAnnounced) return@launch
            val playable = withContext(Dispatchers.IO) {
                env.database.frameDao().getById(picked.frameId) != null &&
                    runCatching { env.frames.buildPlayQueue(picked.frameId).isNotEmpty() }.getOrDefault(false)
            }
            if (stopped || restActiveNow()) return@launch
            if (!playable) {
                if (picked.id !in holidayMissingAnnounced) {
                    holidayMissingAnnounced.add(picked.id)
                    onHolidayMissing?.invoke(picked.id, picked.name)
                }
                return@launch
            }
            holidayEnterAnnounced.add(picked.id)
            pendingHolidayEnter = HolidayEnter(picked.id, picked.frameId, picked.name)
            if (queue.size <= 1 && state == PlaybackController.HOLDING) flushHolidayEnter()
        }
    }

    private fun restActiveNow(): Boolean {
        val calendar = Calendar.getInstance()
        val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return settings.restActiveAt(minute, restOverride)
    }

    private fun updateKeepScreenOn() {
        val keep = when (state) {
            PlaybackController.HOLDING,
            PlaybackController.TRANSITIONING,
            PlaybackController.PREPARING,
            PlaybackController.RESTING,
            -> true
            PlaybackController.PAUSED -> settings.keepAwakeWhenPaused
            else -> false
        }
        listener.onKeepScreenOn(keep)
    }

    private fun currentAsset(): AssetEntity? =
        queue.getOrNull(index)?.let(assetsById::get)

    private fun nextIndex(): Int =
        if (queue.isEmpty()) 0 else (index + 1) % queue.size

    private fun persistSnapshot() {
        val assetId = queue.getOrNull(index)
        val remaining = remainingHoldMs ?: PlaybackTiming.remainingAtPause(
            nextTransitionAtMs,
            System.currentTimeMillis(),
        )
        val cursor = buildString {
            append("{\"queue\":[")
            append(queue.joinToString(",") { "\"$it\"" })
            append("],\"index\":")
            append(index)
            append("}")
        }
        scope.launch(Dispatchers.IO) {
            val fresh = env.database.frameDao().getById(frame.id) ?: return@launch
            val now = System.currentTimeMillis()
            env.database.playbackSnapshotDao().upsert(
                PlaybackSnapshotEntity(
                    frameId = frame.id,
                    assetId = assetId,
                    remainingHoldMs = remaining,
                    cursorJson = cursor,
                    seed = fresh.randomSeed,
                    selectionVersion = fresh.selectionVersion,
                    savedAt = now,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    private companion object {
        const val REST_TICK_MS = 60_000L
        const val HOLIDAY_TICK_MS = 60_000L
        const val FIRST_FRAME_TIME_US = 500_000L
        const val TRANSITION_WATCHDOG_MS = 12_000L

        val REST_INTERRUPTIBLE_STATES = setOf(
            PlaybackController.HOLDING,
            PlaybackController.PAUSED,
            PlaybackController.TRANSITIONING,
        )
    }
}
