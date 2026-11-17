package dev.photopilot.scaffold

import androidx.room.withTransaction
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.LocaleManager
import android.app.UiModeManager
import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.util.TypedValue
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import dev.photopilot.scaffold.data.AppEnvironment
import dev.photopilot.scaffold.data.db.AssetEntity
import dev.photopilot.scaffold.data.db.AssetReadiness
import dev.photopilot.scaffold.data.db.CollectionEntity
import dev.photopilot.scaffold.data.db.DatabaseFactory
import dev.photopilot.scaffold.data.db.FrameEntity
import dev.photopilot.scaffold.data.db.HolidayScheduleEntity
import dev.photopilot.scaffold.data.db.FrameOrderMode
import dev.photopilot.scaffold.data.db.FrameSelectionMode
import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import dev.photopilot.scaffold.data.db.SourceEntity
import dev.photopilot.scaffold.data.db.SourceAssetEntity
import dev.photopilot.scaffold.data.db.SourceStatus
import dev.photopilot.scaffold.data.db.SourceType
import dev.photopilot.scaffold.data.diagnostics.DiagnosticsExporter
import dev.photopilot.scaffold.data.imports.ImportCoordinator
import dev.photopilot.scaffold.data.imports.PickedFilesSourceAdapter
import dev.photopilot.scaffold.data.imports.PickedFilesStager
import dev.photopilot.scaffold.data.imports.SmbSourceAdapter
import dev.photopilot.scaffold.data.imports.WebDavSourceAdapter
import dev.photopilot.scaffold.data.logic.AutoPlayRouter
import dev.photopilot.scaffold.data.logic.BatchDeleteScope
import dev.photopilot.scaffold.data.logic.ByteSizeFormat
import dev.photopilot.scaffold.data.logic.FrameEditDiff
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.logic.LibraryCriteria
import dev.photopilot.scaffold.data.logic.LibraryQueryBuilder
import dev.photopilot.scaffold.data.logic.LibrarySort
import dev.photopilot.scaffold.data.logic.LibraryVersionLogic
import dev.photopilot.scaffold.data.logic.MusicCatalog
import dev.photopilot.scaffold.data.logic.NasAddress
import dev.photopilot.scaffold.data.logic.NasPath
import dev.photopilot.scaffold.data.logic.NasScope
import dev.photopilot.scaffold.data.logic.NasSourceLifecycle
import dev.photopilot.scaffold.data.logic.WeatherLogic
import dev.photopilot.scaffold.data.logic.HolidayTiming
import dev.photopilot.scaffold.data.logic.PlaybackSettingsEdit
import dev.photopilot.scaffold.data.logic.SaveRuleRow
import dev.photopilot.scaffold.data.logic.SelectionSaveResult
import dev.photopilot.scaffold.data.logic.StorageThreshold
import dev.photopilot.scaffold.data.logic.SystemFeatureGates
import dev.photopilot.scaffold.data.net.NasTlsPolicy
import dev.photopilot.scaffold.data.net.SmbClient
import dev.photopilot.scaffold.data.net.TofuPinStore
import dev.photopilot.scaffold.data.net.WebDavClient
import dev.photopilot.scaffold.data.repo.AppSettingsRepository
import dev.photopilot.scaffold.data.repo.AssetRepository
import dev.photopilot.scaffold.data.repo.SelectionRefEdit
import dev.photopilot.scaffold.data.update.UpdateChecker
import dev.photopilot.scaffold.data.update.UpdateDownloadService
import dev.photopilot.scaffold.data.update.UpdateInstaller
import dev.photopilot.scaffold.data.update.UpdateState
import androidx.sqlite.db.SimpleSQLiteQuery
import dev.photopilot.scaffold.ui.AppRootView
import dev.photopilot.scaffold.ui.MainContainerView
import dev.photopilot.scaffold.ui.MainSection
import dev.photopilot.scaffold.ui.frames.FrameEditView
import dev.photopilot.scaffold.ui.frames.FramesView
import dev.photopilot.scaffold.ui.frames.ManualOrderView
import dev.photopilot.scaffold.ui.imports.ImportProgressView
import dev.photopilot.scaffold.ui.launch.LaunchFailureView
import dev.photopilot.scaffold.ui.launch.LaunchView
import dev.photopilot.scaffold.ui.launch.LaunchPresentationState
import dev.photopilot.scaffold.ui.library.LibraryView
import dev.photopilot.scaffold.ui.library.PhotoDetailView
import dev.photopilot.scaffold.ui.nas.NasCertView
import dev.photopilot.scaffold.ui.nas.NasCleartextView
import dev.photopilot.scaffold.ui.nas.NasDirectoryPickerView
import dev.photopilot.scaffold.ui.nas.NasFormView
import dev.photopilot.scaffold.ui.nas.NasLanNoticeView
import dev.photopilot.scaffold.ui.permissions.PhotoPermissionGate
import dev.photopilot.scaffold.ui.permissions.PhotoPermissionOverlayView
import dev.photopilot.scaffold.ui.player.PlaybackSettingsView
import dev.photopilot.scaffold.ui.player.MusicPickerView
import dev.photopilot.scaffold.ui.player.PlayerEngine
import dev.photopilot.scaffold.ui.player.PlayerView
import dev.photopilot.scaffold.ui.settings.LicensesView
import dev.photopilot.scaffold.ui.settings.HolidaySettingsView
import dev.photopilot.scaffold.ui.settings.SettingsView
import dev.photopilot.scaffold.ui.sources.SourcesView
import dev.photopilot.scaffold.ui.weather.WeatherCitySearchView
import dev.photopilot.scaffold.ui.welcome.DemoPlayerView
import dev.photopilot.scaffold.ui.welcome.WelcomeView
import dev.photopilot.scaffold.ui.widget.KeyboardDismiss
import dev.photopilot.scaffold.ui.widget.secondaryButton
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(dev.photopilot.scaffold.ui.UiPreferenceOverrides.wrap(newBase))
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main +
            kotlinx.coroutines.CoroutineExceptionHandler { _, _ ->
                android.util.Log.e("PhotoPilot", "uncaught coroutine error")
            },
    )
    private lateinit var root: AppRootView
    private lateinit var keyboardDismiss: KeyboardDismiss
    private var database: PhotoPilotDatabase? = null
    private var environment: AppEnvironment? = null
    private var activityResumed = false
    private var pendingSectionRestore = -1
    private var pendingOverlayRestore: String? = null
    private var coldStart = false
    private var configurationReceiverRegistered = false
    private val configurationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (dev.photopilot.scaffold.ui.UiPreferenceOverrides.followsSystemAppearance(this@MainActivity)) {
                applyAppearancePreference(AppSettingsRepository.VALUE_SYSTEM)
            }
        }
    }
    private val overlayMeta = mutableListOf<Pair<View, Map<String, String>>>()

    private fun pushOverlayTracked(view: View, meta: Map<String, String>?) {
        root.pushOverlay(view)
        if (meta != null) {
            overlayMeta += view to meta
        }
    }

    private fun popOverlayTracked(): View? {
        val popped = root.popOverlay() ?: return null
        overlayMeta.removeAll { it.first === popped }
        return popped
    }

    private fun setBaseTracked(view: View) {
        overlayMeta.clear()
        progressView = null
        progressJobId = null
        root.setBase(view)
    }

    private fun restoreOverlays(json: String?, permissionOnly: Boolean = false) {
        val env = environment ?: return
        if (json.isNullOrEmpty()) return
        val stack = JsonMini.parseObject(json)["stack"] as? List<*> ?: return
        val entries = stack.mapNotNull { it as? Map<*, *> }
        scope.launch {
            for (entry in entries) {
                when (entry["type"]) {
                    "permission" -> if (permissionOverlay == null) {
                        showPhotoPermissionOverlay()
                    }
                    "player" -> if (permissionOnly) continue else (entry["frameId"] as? String)?.let { id ->
                        val frame = withContext(Dispatchers.IO) {
                            env.database.frameDao().getById(id)
                        }
                        if (frame != null && playerView == null) {
                            openPlayer(frame, fromSnapshot = true)
                        }
                    }
                    "progress" -> if (permissionOnly) continue else (entry["jobId"] as? String)?.let { id ->
                        if (progressView == null) {
                            openImportProgress(id)
                        }
                    }
                    "photoDetail" -> if (permissionOnly) continue else (entry["assetId"] as? String)?.let { id ->
                        val asset = withContext(Dispatchers.IO) {
                            env.database.assetDao().getById(id)
                        }
                        if (asset != null) {
                            openPhotoDetail(asset)
                        }
                    }
                    "remoteFrame" -> if (permissionOnly && entry["firstUse"] != "true") continue else {
                        val id = entry["frameId"] as? String
                        val target = id?.takeIf { it.isNotEmpty() }?.let { env.database.frameDao().getById(it) }
                        openRemoteFrame(target, entry.entries.mapNotNull { (key, value) -> if (key is String && value is String) key to value else null }.toMap())
                    }
                    "frameEdit" -> if (permissionOnly) continue else {
                        val id = (entry["frameId"] as? String).orEmpty()
                        val frame = withContext(Dispatchers.IO) {
                            id.takeIf { it.isNotEmpty() }?.let { env.database.frameDao().getById(it) }
                        }
                        if (id.isEmpty() || frame != null) {
                            openFrameEditor(frame, entry.entries.mapNotNull { (key, value) -> if (key is String && value is String) key to value else null }.toMap())
                        }
                    }
                    "aiModels" -> if (!permissionOnly) openAIModels(entry["modelId"] as? String)
                    "aiFrame" -> if (permissionOnly) continue else {
                        val id = entry["frameId"] as? String ?: ""
                        val frame = if (id.isEmpty()) null else env.database.frameDao().getById(id)
                        if (id.isEmpty() || frame != null) openAIFrameEditor(frame, entry.entries.mapNotNull { (key, value) -> if (key is String && value is String) key to value else null }.toMap())
                    }
                    "playbackSettings" -> if (permissionOnly) continue else (entry["frameId"] as? String)?.let { id ->
                        val frame = withContext(Dispatchers.IO) {
                            env.database.frameDao().getById(id)
                        }
                        if (frame != null) {
                            showPlaybackSettings(frame)
                        }
                    }
                    "holiday" -> if (permissionOnly) continue else {
                        if (holidaySettingsView == null) {
                            openHolidaySettings()
                        }
                    }
                }
            }
        }
        pendingOverlayRestore = null
    }
    private var permissionOverlay: PhotoPermissionOverlayView? = null
    private var activeSourcesView: SourcesView? = null
    private var activeLibraryView: LibraryView? = null
    private var activeFramesView: FramesView? = null
    private var framesReloadJob: Job? = null
    private var activeSettingsView: SettingsView? = null
    private var progressView: ImportProgressView? = null
    private var progressJobId: String? = null
    private var photoDetailView: PhotoDetailView? = null
    private var frameEditView: FrameEditView? = null
    private var editorModel: FrameEditView.Model? = null
    private var frameSaveInFlight = false
    private var manualOrderView: ManualOrderView? = null
    private var playerView: PlayerView? = null
    private var playerEngine: PlayerEngine? = null
    private var playbackSettingsView: PlaybackSettingsView? = null
    private var holidaySettingsView: HolidaySettingsView? = null
    private var preHolidayFrameId: String? = null
    private var undoBar: View? = null
    private var undoBarHideRunnable: Runnable? = null
    private var sectionBackHandler: (() -> Boolean)? = null
    private var lastLibraryVersionJson: String? = null
    private var framesDirty = false
    private var lastRetryDriveMs = 0L
    private var updateCheckInFlight = false
    private var updateProgressDialog: AlertDialog? = null
    private var pendingUpdateRelease: UpdateChecker.ReleaseInfo? = null

    private val updatePollRunnable = object : Runnable {
        override fun run() {
            when (val state = UpdateDownloadService.latestState) {
                is UpdateState.Downloading -> {
                    val percent = if (state.total > 0L) (state.downloaded * 100L / state.total).toInt() else 0
                    updateProgressDialog?.setMessage(getString(R.string.update_progress, percent))
                    updateHandler.postDelayed(this, UPDATE_POLL_INTERVAL_MS)
                }
                is UpdateState.Completed -> {
                    val dialog = updateProgressDialog
                    updateProgressDialog = null
                    dialog?.dismiss()
                    showUpdateReady(File(state.apkPath))
                }
                UpdateState.Failed -> {
                    val dialog = updateProgressDialog
                    updateProgressDialog = null
                    dialog?.dismiss()
                    showUpdateFailed()
                }
                UpdateState.Idle ->
                    updateHandler.postDelayed(this, UPDATE_POLL_INTERVAL_MS)
            }
        }
    }
    private val updateHandler = Handler(Looper.getMainLooper())

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshJobUi()
            root.keepScreenOn = activityResumed && environment?.aiFrames?.tasks?.hasActiveWork == true
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SystemFeatureGates.usesNativeAppearance(Build.VERSION.SDK_INT)) {
            val filter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(configurationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(configurationReceiver, filter)
            configurationReceiverRegistered = true
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }
        root = AppRootView(this)
        setContentView(root)
        root.fitsSystemWindows = true
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        keyboardDismiss = KeyboardDismiss(root)
        keyboardDismiss.installMainWindowInsets(window)
        pendingSectionRestore = savedInstanceState?.getInt(KEY_SECTION, -1) ?: -1
        pendingOverlayRestore = savedInstanceState?.getString(KEY_OVERLAYS)
        coldStart = savedInstanceState == null
        launchPresentation.startIfNeeded()
        startInitialization()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::keyboardDismiss.isInitialized) keyboardDismiss.observe(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        if (configurationReceiverRegistered) unregisterReceiver(configurationReceiver)
        updateHandler.removeCallbacks(updatePollRunnable)
        updateProgressDialog?.dismiss()
        updateProgressDialog = null
        playerEngine?.stop(clearSnapshot = false)
        playerEngine = null
        environment?.aiFrames?.onProgress = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
        environment?.let { env ->
            val referenceEditor = remoteFrameView
            scope.launch {
                env.localReferences.refreshAll()
                playerEngine?.onExternalSelectionChanged()
                if (remoteFrameView === referenceEditor) referenceEditor?.reloadSources()
                activeSourcesView?.let { refreshNasSources(it) }
            }
        }
        environment?.aiFrames?.foreground = true
        environment?.aiFrames?.tasks?.foregroundEntered()
        environment?.aiFrames?.resumeAutomatic()
        maybeAutoSyncNas()
        val engine = playerEngine
        if (engine != null) {
            engine.onForeground()
            playerView?.onAppForeground()
            if (PhotoPermissionGate.status(this) == PhotoPermissionGate.Status.DENIED) {
                engine.onPermissionRevoked()
            }
        }
        val overlay = permissionOverlay ?: return
        when (PhotoPermissionGate.status(this)) {
            PhotoPermissionGate.Status.GRANTED ->
                overlay.setState(PhotoPermissionOverlayView.State.GRANTED)
            PhotoPermissionGate.Status.PARTIAL ->
                overlay.setState(PhotoPermissionOverlayView.State.PARTIAL)
            PhotoPermissionGate.Status.DENIED -> Unit
        }
    }

    override fun onPause() {
        activityResumed = false
        root.keepScreenOn = false
        pollHandler.removeCallbacks(pollRunnable)
        playerView?.onAppBackground()
        playerEngine?.onBackground()
        musicPickerView?.stopPreview()
        environment?.aiFrames?.foreground = false
        super.onPause()
    }

    private fun startInitialization() {
        val launchView = LaunchView(this, launchPresentation)
        setBaseTracked(launchView)
        scope.launch {
            try {
                val env = AppEnvironment.shared(applicationContext)
                database = env.database
                environment = env
                env.aiFrames.onProgress = { pollHandler.post { framesDirty = true } }
                env.aiFrames.foreground = activityResumed
                if (activityResumed) {
                    env.aiFrames.tasks.foregroundEntered()
                    env.aiFrames.resumeAutomatic()
                }
                val (completed, preferences) = withContext(Dispatchers.IO) {
                    env.settings.isOnboardingCompleted() to
                        (env.settings.appearance() to env.settings.language())
                }
                if (dev.photopilot.scaffold.ui.UiPreferenceOverrides.update(this@MainActivity, preferences.first, preferences.second)) {
                    recreate()
                    return@launch
                }
                applyAppearancePreference(preferences.first)
                applyLanguagePreference(preferences.second)
                launchView.onReady = {
                    if (completed) {
                        showMainContainer()
                        if (!intent.hasExtra("aiTaskId")) maybeAutoPlay(env)
                        restoreOverlays(pendingOverlayRestore)
                        routeAIIntent()
                    } else {
                        showWelcome()
                        restoreOverlays(pendingOverlayRestore, permissionOnly = true)
                    }
                }
                launchView.initializationReady()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                launchPresentation.initializationFailed()
                showLaunchFailure(error)
            }
        }
    }

    private fun showLaunchFailure(error: Throwable) {
        val view = LaunchFailureView(this).apply {
            onRetry = { startInitialization() }
            onExportDiagnostics = { shareDiagnostics(error) }
        }
        setBaseTracked(view)
    }

    private fun shareDiagnostics(error: Throwable?) {
        val exporter = DiagnosticsExporter(this)
        val json = exporter.exportJson(
            errorCode = error?.let { DiagnosticsExporter.ERROR_DB_MIGRATION_FAILED },
            errorClass = error?.let { it::class.java.simpleName },
        )
        exporter.writeToCache(json)
        startActivity(Intent.createChooser(exporter.buildShareIntent(json), null))
    }

    private fun showWelcome() {
        val view = WelcomeView(this).apply {
            onPrimary = { root.pushOverlay(buildSourcesView()) }
            onSecondary = { showDemoPlayer() }
        }
        setBaseTracked(view)
    }

    private fun showDemoPlayer() {
        val view = DemoPlayerView(this).apply {
            onExit = { popOverlayTracked() }
        }
        root.pushOverlay(view)
    }

    private fun buildSourcesView(): SourcesView =
        SourcesView(this).apply {
            onLocalPhotosClick = { showPhotoPermissionOverlay() }
            onLocalFilesClick = { showLocalFilesChoices() }
            onNasClick = { onNasSourceClicked() }
            onLocalReferenceClick = { sourceId ->
                scope.launch {
                    val source = withContext(Dispatchers.IO) { environment?.database?.sourceDao()?.getById(sourceId) }
                    if (source?.status == SourceStatus.ACTIVE) {
                        val editor = remoteFrameView
                        if (editor != null) editor.acceptSource(source.id, null)
                        else chooseRemoteFrame(mapOf("sourceId" to source.id))
                    } else if (source?.type == SourceType.LOCAL_PHOTOS) showPhotoPermissionOverlay()
                    else showLocalFilesChoices()
                }
            }
            onLocalReferenceRemove = { sourceId -> confirmNasDisconnect(sourceId) }
            onNasBrowse = { sourceId ->
                scope.launch {
                    val source = withContext(Dispatchers.IO) { environment?.database?.sourceDao()?.getById(sourceId) }
                    if (source?.status == SourceStatus.ACTIVE) {
                        val editor = remoteFrameView
                        if (editor != null) editor.acceptSource(source.id, null)
                        else chooseRemoteFrame(mapOf("sourceId" to source.id))
                    } else openNasEdit(sourceId)
                }
            }
            onNasSync = { sourceId -> nasSyncNow(sourceId) }
            onNasDisconnect = { sourceId -> confirmNasDisconnect(sourceId) }
            onNasReauth = { sourceId -> openNasEdit(sourceId) }
            onNasEdit = { sourceId -> openNasEdit(sourceId) }
            onNasMirrorToggle = { sourceId, enabled -> onNasMirrorToggle(sourceId, enabled) }
            onJobStatusClick = {
                val jobId = progressJobId
                if (jobId != null && progressView == null) {
                    openImportProgress(jobId)
                }
            }
            activeSourcesView = this
        }

    private fun showMainContainer() {
        val container = MainContainerView(this).apply {
            sectionContent = { section ->
                sectionBackHandler = null
                activeSourcesView = null
                activeLibraryView = null
                framesReloadJob?.cancel()
                activeFramesView = null
                activeSettingsView = null
                when (section) {
                    MainSection.SOURCES -> buildSourcesView()
                    MainSection.LIBRARY -> buildLibraryView()
                    MainSection.FRAMES -> buildFramesView()
                    MainSection.SETTINGS -> buildSettingsView()
                    else -> null
                }
            }
        }
        setBaseTracked(container)
        if (pendingSectionRestore >= 0) {
            container.selectSection(MainSection.entries[pendingSectionRestore])
            pendingSectionRestore = -1
        }
    }

    private fun buildSettingsView(): SettingsView {
        val view = SettingsView(this)
        view.onAIModels = { openAIModels(null) }
        view.onAppearance = { index ->
            val value = APPEARANCE_VALUES[index]
            scope.launch {
                environment?.settings?.setAppearance(value)
                applyAppearancePreference(value)
            }
        }
        view.onLanguage = { index ->
            val value = LANGUAGE_VALUES[index]
            scope.launch {
                environment?.settings?.setLanguage(value)
                applyLanguagePreference(value)
            }
        }
        view.onToggleAutoPlay = { enabled ->
            scope.launch { environment?.settings?.setAutoPlayEnabled(enabled) }
        }
        view.onToggleWifiOnly = { enabled ->
            scope.launch { environment?.settings?.setWifiOnly(enabled) }
        }
        view.onToggleShareStatistics = { enabled ->
            scope.launch { environment?.settings?.setShareStatisticsEnabled(enabled) }
        }
        view.onPrivacyPolicy = {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/chunguangwei/PhotoPilot/blob/main/PRIVACY.md")))
            }
        }
        view.onClearCache = { confirmClearCache(view) }
        view.onRestoreDeleted = { confirmRestoreDeleted() }
        view.onClearAll = { confirmClearAll() }
        view.onExportDiagnostics = { shareDiagnostics(null) }
        view.onOpenAccessibilitySettings = {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        view.onToggleWeatherDefault = { enabled ->
            scope.launch {
                environment?.settings?.putRaw(
                    WeatherLogic.KEY_ENABLED_DEFAULT,
                    if (enabled) AppSettingsRepository.VALUE_TRUE else AppSettingsRepository.VALUE_FALSE,
                )
            }
        }
        view.onWeatherUnits = { index ->
            scope.launch {
                environment?.settings?.putRaw(
                    WeatherLogic.KEY_UNITS,
                    when (index) {
                        1 -> WeatherLogic.UNITS_METRIC
                        2 -> WeatherLogic.UNITS_IMPERIAL
                        else -> ""
                    },
                )
                activeSettingsView?.let { reloadSettingsModel(it) }
            }
        }
        view.onWeatherChooseCity = {
            openWeatherSearch { _, name ->
                scope.launch {
                    activeSettingsView?.let { reloadSettingsModel(it) }
                }
            }
        }
        view.onWeatherManualRefresh = {
            activeSettingsView?.showWeatherRefreshing()
            scope.launch {
                val env = environment ?: return@launch
                val placeId = withContext(Dispatchers.IO) {
                    env.weather.selectedPlace()?.placeId
                } ?: return@launch
                val outcome = env.weatherRefresher.refreshIfDue(placeId, manual = true)
                activeSettingsView?.let { settingsView ->
                    when {
                        outcome.updated -> {
                            settingsView.showWeatherError(false)
                            reloadSettingsModel(settingsView)
                        }
                        outcome.errorCode != null -> settingsView.showWeatherError(true)
                        else -> {
                            settingsView.showWeatherError(false)
                            Toast.makeText(
                                this@MainActivity,
                                R.string.weather_delayed,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        }
        view.onHoliday = { openHolidaySettings() }
        view.onLicenses = { showLicenses() }
        view.onUpdate = { checkForUpdate() }
        activeSettingsView = view
        reloadSettingsModel(view)
        return view
    }

    private fun reloadSettingsModel(view: SettingsView) {
        val env = environment ?: return
        scope.launch {
            val model = withContext(Dispatchers.IO) {
                val settings = env.settings
                val originals = File(filesDir, "originals")
                val photosBytes = directoryBytes(originals)
                val cacheBytes = directoryBytes(cacheDir)
                val stat = android.os.StatFs(filesDir.absolutePath)
                val freeBytes = stat.availableBytes
                val totalBytes = stat.totalBytes
                SettingsView.Model(
                    appearance = settings.appearance(),
                    language = settings.language(),
                    autoPlay = settings.isAutoPlayEnabled(),
                    wifiOnly = settings.isWifiOnly(),
                    shareStatistics = settings.isShareStatisticsEnabled(),
                    reduceMotionActive = reduceMotionActive(),
                    versionName = appVersionName(),
                    storage = SettingsView.StorageModel(
                        photosBytes = photosBytes,
                        cacheBytes = cacheBytes,
                        freeBytes = freeBytes,
                        lowStorage = StorageThreshold.isLow(freeBytes, totalBytes),
                    ),
                    weatherEnabledDefault = settings.rawValue(WeatherLogic.KEY_ENABLED_DEFAULT) ==
                        AppSettingsRepository.VALUE_TRUE,
                    weatherPlaceName = env.weather.selectedPlace()?.let { WeatherLogic.detailedName(it, weatherLang()) },
                    weatherUnitsIndex = when (settings.rawValue(WeatherLogic.KEY_UNITS)) {
                        WeatherLogic.UNITS_METRIC -> 1
                        WeatherLogic.UNITS_IMPERIAL -> 2
                        else -> 0
                    },
                    weatherObservedText = weatherObservedText(env),
                    weatherAttributionText = weatherAttributionText(env),
                )
            }
            if (activeSettingsView === view) {
                view.render(model)
            }
        }
    }

    private fun directoryBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    private fun appVersionName(): String =
        runCatching {
            val pm = packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            info.versionName
        }.getOrNull().orEmpty()

    private fun reduceMotionActive(): Boolean =
        android.provider.Settings.Global.getFloat(
            contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f

    @SuppressLint("NewApi")
    private fun applyAppearancePreference(value: String) {
        if (!SystemFeatureGates.canApplyAppearance(Build.VERSION.SDK_INT)) return
        if (dev.photopilot.scaffold.ui.UiPreferenceOverrides.update(this, appearance = value)) {
            recreate()
            return
        }
        if (!SystemFeatureGates.usesNativeAppearance(Build.VERSION.SDK_INT)) return
        val manager = getSystemService(UiModeManager::class.java) ?: return
        manager.setApplicationNightMode(
            when (value) {
                AppSettingsRepository.VALUE_LIGHT -> UiModeManager.MODE_NIGHT_NO
                AppSettingsRepository.VALUE_DARK -> UiModeManager.MODE_NIGHT_YES
                else -> manager.nightMode.takeIf { it in 0..3 }
                    ?: if ((android.content.res.Resources.getSystem().configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    ) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
            },
        )
    }

    @SuppressLint("NewApi")
    private fun applyLanguagePreference(value: String) {
        if (!SystemFeatureGates.canApplyLanguage(Build.VERSION.SDK_INT)) return
        if (dev.photopilot.scaffold.ui.UiPreferenceOverrides.update(this, language = value)) {
            recreate()
            return
        }
        if (!SystemFeatureGates.usesNativeLanguage(Build.VERSION.SDK_INT)) return
        val manager = getSystemService(LocaleManager::class.java) ?: return
        manager.applicationLocales = when (value) {
            AppSettingsRepository.VALUE_ZH -> LocaleList.forLanguageTags("zh-CN")
            AppSettingsRepository.VALUE_EN -> LocaleList.forLanguageTags("en")
            else -> LocaleList.getEmptyLocaleList()
        }
    }

    private fun confirmClearCache(view: SettingsView) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_clear_cache)
            .setMessage(R.string.delete_clear_cache_body)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        dev.photopilot.scaffold.ui.library.ThumbnailLoader.clearMemoryCaches()
                    }
                    reloadSettingsModel(view)
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_clear_all_title)
            .setMessage(R.string.delete_clear_all_body)
            .setPositiveButton(R.string.common_delete) { _, _ -> clearAllData() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun confirmRestoreDeleted() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_restore_deleted)
            .setPositiveButton(R.string.common_continue) { _, _ ->
                scope.launch {
                    environment?.assets?.deleteTombstonesByReasons(
                        AssetRepository.USER_DELETE_REASONS,
                    )
                    Toast.makeText(this@MainActivity, R.string.delete_undone, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun clearAllData() {
        environment ?: return
        scope.launch {
            if (playerView != null) {
                closePlayer()
            }
            environment = try {
                AppEnvironment.clearAllAndReset(applicationContext)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(R.string.error_store_write_failed_body)
                    .setPositiveButton(R.string.common_close, null)
                    .show()
                return@launch
            }
            environment?.aiFrames?.foreground = activityResumed
            database = environment?.database
            lastLibraryVersionJson = null
            framesDirty = true
            progressView = null
            progressJobId = null
            permissionOverlay = null
            val recreatePreferences = dev.photopilot.scaffold.ui.UiPreferenceOverrides.update(
                this@MainActivity, AppSettingsRepository.VALUE_SYSTEM, AppSettingsRepository.VALUE_SYSTEM,
            )
            if (recreatePreferences) {
                recreate()
                return@launch
            }
            applyAppearancePreference(AppSettingsRepository.VALUE_SYSTEM)
            applyLanguagePreference(AppSettingsRepository.VALUE_SYSTEM)
            showWelcome()
        }
    }

    private fun showLicenses() {
        val view = LicensesView(this).apply {
            onAILicenses = { root.pushOverlay(dev.photopilot.scaffold.ui.settings.AILicensesView(this@MainActivity) { popOverlayTracked() }) }
            onBack = { popOverlayTracked() }
        }
        root.pushOverlay(view)
    }

    private fun checkForUpdate() {
        if (updateCheckInFlight) return
        updateCheckInFlight = true
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    UpdateChecker.parseLatestRelease(UpdateChecker.fetchLatestReleaseJson()) to appVersionCode()
                }
            }
            updateCheckInFlight = false
            val result = outcome.getOrNull()
            val release = result?.first
            when (UpdateChecker.compare(result?.second ?: 0L, release)) {
                UpdateChecker.CheckResult.UPDATE_AVAILABLE ->
                    release?.let { showUpdateAvailable(it) }
                UpdateChecker.CheckResult.UP_TO_DATE ->
                    Toast.makeText(this@MainActivity, R.string.update_latest, Toast.LENGTH_SHORT).show()
                UpdateChecker.CheckResult.FAILED ->
                    Toast.makeText(this@MainActivity, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun appVersionCode(): Long =
        runCatching {
            val pm = packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            info.longVersionCode
        }.getOrDefault(0L)

    private fun formatSize(bytes: Long): String =
        getString(
            when (ByteSizeFormat.unitOf(bytes)) {
                ByteSizeFormat.Unit.KB -> R.string.format_file_size_kb
                ByteSizeFormat.Unit.MB -> R.string.format_file_size_mb
                ByteSizeFormat.Unit.GB -> R.string.format_file_size_gb
            },
            ByteSizeFormat.valueOf(bytes).toInt(),
        )

    private fun showUpdateAvailable(release: UpdateChecker.ReleaseInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_body, release.versionName, formatSize(release.apkSize)))
            .setPositiveButton(R.string.update_download) { _, _ -> startUpdateDownload(release) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun startUpdateDownload(release: UpdateChecker.ReleaseInfo) {
        pendingUpdateRelease = release
        UpdateDownloadService.resetState()
        startForegroundService(
            UpdateDownloadService.startIntent(this, release.apkUrl, release.versionName, release.apkSize),
        )
        updateProgressDialog?.dismiss()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_downloading)
            .setMessage(getString(R.string.update_progress, 0))
            .setNegativeButton(R.string.common_cancel, null)
            .create()
        dialog.setOnDismissListener {
            updateHandler.removeCallbacks(updatePollRunnable)
            if (updateProgressDialog === dialog) updateProgressDialog = null
        }
        updateProgressDialog = dialog
        dialog.show()
        updateHandler.removeCallbacks(updatePollRunnable)
        updateHandler.post(updatePollRunnable)
    }

    private fun showUpdateReady(apkFile: File) {
        AlertDialog.Builder(this)
            .setMessage(R.string.update_ready)
            .setPositiveButton(R.string.update_install) { _, _ ->
                if (!UpdateInstaller.install(this@MainActivity, apkFile)) {
                    Toast.makeText(this@MainActivity, R.string.update_install_permission, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showUpdateFailed() {
        AlertDialog.Builder(this)
            .setMessage(R.string.update_failed)
            .setPositiveButton(R.string.common_retry) { _, _ ->
                pendingUpdateRelease?.let { startUpdateDownload(it) }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun onNasSourceClicked() {
        val env = environment ?: return
        scope.launch {
            val seen = withContext(Dispatchers.IO) {
                env.settings.rawValue(KEY_LAN_NOTICE_SEEN)
            } == AppSettingsRepository.VALUE_TRUE
            if (seen) {
                openNasForm()
            } else {
                val view = NasLanNoticeView(this@MainActivity).apply {
                    onContinue = {
                        popOverlayTracked()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                env.settings.putRaw(KEY_LAN_NOTICE_SEEN, AppSettingsRepository.VALUE_TRUE)
                            }
                        }
                        openNasForm()
                    }
                }
                root.pushOverlay(view)
            }
        }
    }

    private var nasFormView: NasFormView? = null
    private var nasSaveInFlight = false

    private data class NasEditContext(
        val sourceId: String,
        val endpointKey: String,
        val directories: Set<String>,
        val recursive: Boolean,
        val catalogOnly: Boolean,
    )

    private var nasEditContext: NasEditContext? = null

    private var remoteFrameView: dev.photopilot.scaffold.ui.remote.RemoteFrameView? = null

    private suspend fun finishSourceConnection(source: SourceEntity, selectedIds: List<String>? = null) {
        val draft = mutableMapOf("sourceId" to source.id)
        selectedIds?.let {
            draft["scopeIds"] = org.json.JSONArray(it).toString()
            draft["selected"] = org.json.JSONArray(it).toString()
        }
        val aiEditor = root.topOverlay() as? dev.photopilot.scaffold.ui.aiframes.AIFrameView
        if (aiEditor != null) {
            aiEditor.reloadCollections()
            return
        }
        val editor = remoteFrameView
        if (editor != null) {
            editor.acceptSource(source.id, selectedIds)
            return
        }
        val firstUse = root.baseView is WelcomeView && withContext(Dispatchers.IO) {
            environment?.database?.frameDao()?.getAll().orEmpty().isEmpty()
        }
        if (firstUse) {
            draft["firstUse"] = "true"
            openRemoteFrame(null, draft)
        } else {
            activeSourcesView?.let { refreshNasSources(it) }
            Toast.makeText(this, if (resources.configuration.locales[0].language == "zh") "来源已连接" else "Source connected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseRemoteFrame(draft: Map<String, String>? = null) {
        val env = environment ?: return
        scope.launch {
            val frames = withContext(Dispatchers.IO) { env.frameManager.listFrames() }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.import_pick_frame_title)
                .setItems(frames.map { it.name }.toTypedArray()) { _, which -> openRemoteFrame(frames[which], draft) }
                .setPositiveButton(R.string.import_pick_frame_new) { _, _ -> openRemoteFrame(null, draft.orEmpty() + ("firstUse" to "true")) }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    private fun openRemoteFrame(frame: FrameEntity?, draft: Map<String, String>? = null) {
        val env = environment ?: return
        val view = dev.photopilot.scaffold.ui.remote.RemoteFrameView(this, env, frame, draft)
        remoteFrameView = view
        view.onBack = { remoteFrameView = null; popOverlayTracked() }
        view.onConnect = { openNasForm() }
        view.onLocalPhotos = { showPhotoPermissionOverlay() }
        view.onLocalFiles = { showLocalFilesChoices() }
        view.onSaved = {
            remoteFrameView = null
            framesDirty = true
            if (draft?.get("firstUse") == "true") {
                scope.launch {
                    env.settings.setOnboardingCompleted(true)
                    while (root.topOverlay() != null) popOverlayTracked()
                    showMainContainer()
                    view.savedFrame?.let { openPlayer(it, fromSnapshot = false) }
                }
            } else {
                popOverlayTracked()
                val editor = frameEditView
                if (editor != null && root.topOverlay() === editor) {
                    scope.launch {
                        val fresh = withContext(Dispatchers.IO) { view.savedFrame?.id?.let { env.database.frameDao().getById(it) } }
                        closeFrameEditor()
                        openFrameEditor(fresh)
                    }
                }
            }
        }
        pushOverlayTracked(view, mapOf("type" to "remoteFrame", "frameId" to (frame?.id ?: "")))
    }

    private fun openNasForm(
        prefillProtocol: String = "webdav",
        prefillHost: String = "",
        prefillPort: Int? = null,
        prefillPath: String = "",
        prefillUsername: String = "",
        prefillPassword: String = "",
        editContext: NasEditContext? = null,
    ) {
        nasEditContext = editContext
        val view = NasFormView(this)
        view.setCatalogOnly(editContext?.catalogOnly ?: true)
        nasFormView = view
        view.onBack = {
            nasFormView = null
            nasEditContext = null
            popOverlayTracked()
        }
        view.onTest = { form -> testNasConnection(view, form) }
        view.onSave = onSave@{ form ->
            val conn = buildNasConnection(form) ?: return@onSave
            openNasDirectoryPicker(conn)
        }
        root.pushOverlay(view)
        if (prefillHost.isNotEmpty()) {
            view.prefill(prefillProtocol, prefillHost, prefillPort, prefillPath, prefillUsername, prefillPassword)
        }
    }

    private data class NasConnection(
        val form: NasFormView.Form,
        val protocol: String,
        val host: String,
        val port: Int,
        val path: String,
        val username: String,
        val password: String,
        val fingerprint: String,
        val tlsContext: dev.photopilot.scaffold.data.net.NasTlsPolicy.ConnectionContext?,
        val browseRoot: String,
        val testConnection: suspend () -> String?,
        val securityText: suspend () -> String?,
        val listChildDirs: suspend (String) -> List<String>,
        val adapterFor: (Boolean) -> dev.photopilot.scaffold.data.imports.SourceAdapter,
    )

    private fun buildNasConnection(form: NasFormView.Form): NasConnection? {
        val env = environment ?: return null
        val parsed = NasAddress.parseHostInput(form.host) ?: return null
        val host = parsed.host
        val path = NasPath.normalize(form.path) ?: return null
        val username = form.username.trim()
        val password = form.password
        if (form.protocol == "smb") {
            val port = parsed.port ?: form.port ?: 445
            val shareName = path.substringBefore('/').ifEmpty { return null }
            val pathInShare = path.substringAfter('/', "")
            val fingerprint = NasAddress.sourceFingerprint("smb", host, port, path)
            val policy = env.nasTlsPolicy()
            val smbClient = SmbClient(host, port, shareName, username, password, tlsPolicy = policy)
            return NasConnection(
                form = form,
                protocol = "smb",
                host = host,
                port = port,
                path = path,
                username = username,
                password = password,
                fingerprint = fingerprint,
                tlsContext = null,
                browseRoot = pathInShare,
                testConnection = {
                    runCatching {
                        smbClient.security()
                        if (pathInShare.isNotEmpty()) {
                            smbClient.list(pathInShare)
                        }
                        null
                    }.getOrElse {
                        (it as? SmbClient.SmbException)?.code ?: WebDavClient.ERR_SRC_UNAVAILABLE
                    }
                },
                securityText = {
                    runCatching { smbClient.security() }.getOrNull()?.let { security ->
                        buildString {
                            append(
                                getString(
                                    if (security.encrypted) {
                                        R.string.source_nas_security_encrypted
                                    } else {
                                        R.string.source_nas_security_plain
                                    },
                                ),
                            )
                            if (security.signing) {
                                append(" · ")
                                append(getString(R.string.source_nas_security_signed))
                            }
                            append(" · ")
                            append(security.dialect)
                        }
                    }
                },
                listChildDirs = { dir ->
                    smbClient.list(dir)
                        .filter { it.isCollection }
                        .map { it.name }
                        .sorted()
                },
                adapterFor = { recursive ->
                    SmbSourceAdapter(
                        share = smbClient,
                        sourceFingerprint = fingerprint,
                        recursive = recursive,
                    )
                },
            )
        }
        val port = parsed.port ?: form.port ?: if (form.protocol == "webdav_http") 80 else 443
        val protocol = form.protocol
        val scheme = if (protocol == "webdav_http") "http" else "https"
        val fingerprint = NasAddress.sourceFingerprint(protocol, host, port, path)
        val policy = env.nasTlsPolicy()
        val context = policy.connectionContext(host, port)
        val client = WebDavClient(
            baseUrl = "$scheme://$host:$port/$path",
            username = username,
            password = password,
            tlsPolicy = policy,
            tlsContext = context,
        )
        return NasConnection(
            form = form,
            protocol = protocol,
            host = host,
            port = port,
            path = path,
            username = username,
            password = password,
            fingerprint = fingerprint,
            tlsContext = context,
            browseRoot = "",
            testConnection = {
                try {
                    client.propfind("", 0)
                    null
                } catch (e: WebDavClient.DavException) {
                    android.util.Log.w("PhotoPilotNas", "webdav test failed code=${e.code}")
                    e.code
                } catch (e: Exception) {
                    android.util.Log.w(
                        "PhotoPilotNas",
                        "webdav test failed code=${WebDavClient.ERR_SRC_UNAVAILABLE}",
                    )
                    WebDavClient.ERR_SRC_UNAVAILABLE
                }
            },
            securityText = {
                getString(
                    if (protocol == "webdav") {
                        R.string.source_nas_security_encrypted
                    } else {
                        R.string.source_nas_security_plain
                    },
                )
            },
            listChildDirs = { dir ->
                client.propfind(dir, 1)
                    .filter { it.isCollection }
                    .mapNotNull { entry -> childDirName(protocol, path, dir, entry.href) }
                    .sorted()
            },
            adapterFor = { recursive ->
                WebDavSourceAdapter(
                    client = client,
                    rootPath = path,
                    sourceFingerprint = fingerprint,
                    recursive = recursive,
                )
            },
        )
    }

    private fun childDirName(protocol: String, rootPath: String, dir: String, href: String): String? {
        val decoded = WebDavClient.decodeHref(href).removePrefix("/")
        val base = if (rootPath.isEmpty()) "" else "$rootPath/"
        val prefix = if (dir.isEmpty()) base else "$base$dir/"
        if (!decoded.startsWith(prefix)) return null
        val rel = decoded.removePrefix(prefix).trimEnd('/')
        if (rel.isEmpty() || rel == dir) return null
        return rel.substringAfterLast('/')
    }

    private fun testNasConnection(view: NasFormView, form: NasFormView.Form) {
        view.showTesting()
        scope.launch {
            val conn = buildNasConnection(form)
            if (conn == null) {
                view.showTestError(R.string.error_src_unavailable_body)
                return@launch
            }
            val code = withContext(Dispatchers.IO) {
                conn.testConnection()
            }
            when (code) {
                null -> {
                    conn.securityText()?.let { text -> view.setSecurityText(text) }
                    view.showTestOk()
                }
                NasTlsPolicy.ERR_CERT_UNTRUSTED,
                NasTlsPolicy.ERR_HOSTNAME_MISMATCH,
                -> {
                    if (conn.tlsContext?.lastError?.fingerprint != null) {
                        openNasCert(view, conn, changed = false)
                    } else {
                        view.showTestError(R.string.error_src_unavailable_body)
                    }
                }
                NasTlsPolicy.ERR_FINGERPRINT_CHANGED -> {
                    if (conn.tlsContext?.lastError?.fingerprint != null) {
                        openNasCert(view, conn, changed = true)
                    } else {
                        view.showTestError(R.string.error_src_unavailable_body)
                    }
                }
                NasTlsPolicy.ERR_CLEARTEXT_BLOCKED -> openNasCleartext(view, conn)
                NasTlsPolicy.ERR_PROTOCOL_MISMATCH ->
                    view.showTestError(R.string.error_src_protocol_mismatch_body)
                WebDavClient.ERR_SRC_UNREACHABLE -> view.showTestError(R.string.error_src_unreachable_body)
                WebDavClient.ERR_SRC_HOST_UNKNOWN -> view.showTestError(R.string.error_src_host_unknown_body)
                WebDavClient.ERR_NET_TIMEOUT -> view.showTestError(R.string.error_net_timeout_body)
                WebDavClient.ERR_SRC_NOT_WEBDAV -> view.showTestError(R.string.error_src_not_webdav_body)
                SmbClient.ERR_SRC_SHARE_NOT_FOUND -> view.showTestError(R.string.source_nas_share_not_found)
                SmbClient.ERR_SRC_PERMISSION_DENIED ->
                    view.showTestError(R.string.error_src_permission_denied_body)
                WebDavClient.ERR_SRC_AUTH_FAILED -> view.showTestError(R.string.error_src_auth_failed_body)
                WebDavClient.ERR_SRC_PATH_NOT_FOUND -> view.showTestError(R.string.error_src_path_not_found_body)
                else -> view.showTestError(R.string.error_src_unavailable_body)
            }
        }
    }

    private fun openNasCert(
        formView: NasFormView,
        conn: NasConnection,
        changed: Boolean,
    ) {
        val env = environment ?: return
        val error = conn.tlsContext?.lastError
        if (error?.fingerprint == null) {
            formView.showTestError(R.string.error_src_unavailable_body)
            return
        }
        val view = NasCertView(this).apply {
            onTrust = trust@{
                val fingerprint = error?.fingerprint ?: return@trust
                scope.launch {
                    val now = System.currentTimeMillis()
                    runCatching {
                        env.tofuPins.savePin(
                            NasTlsPolicy.hostPortKey(conn.host, conn.port),
                            TofuPinStore.Pin(fingerprint, now, now),
                        )
                    }
                    popOverlayTracked()
                    testNasConnection(formView, conn.form)
                }
            }
            onCancel = { popOverlayTracked() }
        }
        root.pushOverlay(view)
        view.bind(
            fingerprint = error?.fingerprint.orEmpty(),
            issuer = error?.issuer,
            validUntilMs = error?.validUntilMs,
            changed = changed,
        )
    }

    private fun openNasCleartext(formView: NasFormView, conn: NasConnection) {
        val env = environment ?: return
        val view = NasCleartextView(this).apply {
            onAllowPrivate = {
                scope.launch {
                    env.tofuPins.addCleartextHost(NasTlsPolicy.hostPortKey(conn.host, conn.port))
                    popOverlayTracked()
                    testNasConnection(formView, conn.form)
                }
            }
            onBack = { popOverlayTracked() }
        }
        root.pushOverlay(view)
        view.bind(privateEligible = NasAddress.isPrivateEligible(conn.host))
    }

    private fun openNasDirectoryPicker(conn: NasConnection) {
        val view = NasDirectoryPickerView(this)
        view.rootPath = conn.browseRoot
        view.onBack = { popOverlayTracked() }
        nasEditContext?.let { view.preselect(it.directories, it.recursive) }
        view.onLoadDirectory = { dir, callback ->
            scope.launch {
                val children = withContext(Dispatchers.IO) {
                    runCatching { conn.listChildDirs(dir) }.getOrNull()
                }
                callback(children)
            }
        }
        view.onConfirm = { selected, recursive ->
            scope.launch { createNasSourceAndImport(conn, selected, recursive) }
        }
        root.pushOverlay(view)
    }

    private suspend fun createNasSourceAndImport(conn: NasConnection, selected: Set<String>, recursive: Boolean) {
        val env = environment ?: return
        val dirs = NasScope.normalizeDirectories(selected.toList()) ?: return
        if (nasSaveInFlight) return
        nasSaveInFlight = true
        try {
            val scopeJson = NasScope.scopeJson(dirs)
            val settingsJson = NasSourceLifecycle.settingsJsonWithRecursive(recursive)
            val credentialJson = JsonMini.toJson(
                mapOf(
                    "protocol" to conn.protocol,
                    "host" to conn.host,
                    "port" to conn.port.toDouble(),
                    "path" to conn.path,
                    "username" to conn.username,
                    "password" to conn.password,
                ),
            )
            val existing = withContext(Dispatchers.IO) { env.database.sourceDao().getAll().firstOrNull { it.endpointKey == conn.fingerprint && it.type == if (conn.protocol == "smb") SourceType.SMB else SourceType.WEBDAV } }
            val requestedSettings = JsonMini.toJson(
                JsonMini.parseObject(NasSourceLifecycle.mergeSettings(existing?.settingsJson ?: settingsJson, recursive = recursive)) +
                    mapOf("catalogOnly" to conn.form.catalogOnly, "directories" to dirs),
            )
            val editContext = nasEditContext
            try {
                withContext(Dispatchers.IO) {
                    existing?.let { source ->
                        env.remoteCopies.cancelSource(source.id)
                        env.database.importJobDao().getBySource(source.id).forEach { env.runner.cancel(it.id) }
                    }
                    env.credentialStore.save(conn.fingerprint, credentialJson)
                    val ensured = env.localSources.ensureNasSource(
                        if (conn.protocol == "smb") SourceType.SMB else SourceType.WEBDAV,
                        conn.fingerprint,
                        NasAddress.displayName(conn.protocol, conn.host, conn.port),
                        WebDavSourceAdapter.WEBDAV_CAPABILITY_FLAGS,
                        requestedSettings,
                    )
                    val source = if (existing != null && ensured.epoch == existing.epoch) {
                        ensured.copy(status = SourceStatus.ACTIVE, epoch = ensured.epoch + 1, updatedAt = System.currentTimeMillis()).also { env.database.sourceDao().update(it) }
                    } else ensured
                    val newJobId = if (conn.form.catalogOnly) {
                        env.remoteMedia.index(source.id, scopeJson, conn.adapterFor(recursive), expectedEpoch = source.epoch)
                        null
                    } else {
                        val id = env.coordinator.createJob(source.id, scopeJson)
                        env.registerNasAdapter(id, conn.adapterFor(recursive))
                        env.remoteMedia.index(source.id, scopeJson, conn.adapterFor(recursive), expectedEpoch = source.epoch)
                        env.runner.start(id)
                        id
                    }
                    env.localSources.clearSyncError(source.id, source.epoch, source.status)
                    newJobId
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                AlertDialog.Builder(this).setMessage(R.string.error_store_write_failed_body).setPositiveButton(R.string.common_close, null).show()
                return
            }
            if (editContext != null && editContext.endpointKey != conn.fingerprint) {
                withContext(Dispatchers.IO) {
                    env.database.importJobDao().getBySource(editContext.sourceId).forEach { env.runner.cancel(it.id) }
                    env.localSources.disconnect(editContext.sourceId, true, System.currentTimeMillis())
                    env.credentialStore.delete(editContext.endpointKey)
                }
            }
            nasEditContext = null
            nasFormView = null
            framesDirty = true
            popOverlayTracked()
            popOverlayTracked()
            val connected = withContext(Dispatchers.IO) { env.database.sourceDao().getAll().firstOrNull { it.endpointKey == conn.fingerprint } }
            if (connected != null) finishSourceConnection(connected)
        } finally {
            nasSaveInFlight = false
        }
    }

    private fun nasSyncNow(sourceId: String, reportFailure: Boolean = true) {
        val env = environment ?: return
        val requestingView = activeSourcesView
        val requestingOverlay = root.topOverlay()
        scope.launch {
            var authority: SourceEntity? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val captured = env.database.sourceDao().getById(sourceId) ?: return@withContext null
                    authority = captured
                    val adapter = env.nasAdapterFor(captured)
                    val source = env.localSources.prepareSync(captured.id, captured.epoch, captured.status, adapter != null, System.currentTimeMillis()) ?: return@withContext null
                    authority = source
                    if (adapter == null) return@withContext null
                    if (JsonMini.parseObject(source.settingsJson)["catalogOnly"] == true) {
                        val directories = JsonMini.parseObject(source.settingsJson)["directories"] ?: listOf("")
                        env.remoteMedia.index(sourceId, JsonMini.toJson(mapOf("directories" to directories)), adapter, expectedEpoch = source.epoch)
                        if (env.localSources.clearSyncError(sourceId, source.epoch, source.status)) env.aiFrames.resumeAutomatic()
                        return@withContext null
                    }
                    val scopeJson = NasScope.sourceScope(source.settingsJson, env.database.importJobDao().getBySource(sourceId).sortedByDescending { it.createdAt }.map { it.scopeJson }) ?: throw java.io.IOException("NAS_SCOPE_UNAVAILABLE")
                    val jobId = env.database.withTransaction {
                        val current = env.database.sourceDao().getById(source.id)
                        if (current?.epoch != source.epoch || current.status != source.status || current.status == SourceStatus.DISCONNECTED) throw java.io.IOException("REMOTE_REVOKED")
                        env.coordinator.createJob(source.id, scopeJson)
                    }
                    env.registerNasAdapter(jobId, adapter)
                    env.runner.start(jobId)
                    env.localSources.clearSyncError(sourceId, source.epoch, source.status)
                    jobId
                }
                if (result != null) refreshJobUi()
                activeSourcesView?.let { refreshNasSources(it) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                var message = R.string.error_src_unavailable_body
                var currentAuthority = false
                val captured = authority
                try {
                    if (captured != null) {
                        val code = (failure as? WebDavClient.DavException)?.code ?: (failure as? SmbClient.SmbException)?.code
                        currentAuthority = env.localSources.recordSyncError(sourceId, captured.epoch, captured.status,
                            code in setOf(WebDavClient.ERR_SRC_AUTH_FAILED, SmbClient.ERR_SRC_PERMISSION_DENIED), System.currentTimeMillis())
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (storeFailure: Exception) {
                    message = R.string.error_store_write_failed_body
                }
                if (reportFailure && currentAuthority && activeSourcesView === requestingView && root.topOverlay() === requestingOverlay) {
                    AlertDialog.Builder(this@MainActivity).setMessage(message).setPositiveButton(R.string.common_close, null).show()
                }
                activeSourcesView?.let { refreshNasSources(it) }
            }
        }
    }

    private fun confirmNasDisconnect(sourceId: String) {
        val env = environment ?: return
        scope.launch {
            val source = withContext(Dispatchers.IO) {
                env.database.sourceDao().getById(sourceId)
            } ?: return@launch
            val removeConfiguration = source.status == SourceStatus.DISCONNECTED || JsonMini.parseObject(source.settingsJson)["referenceOnly"] == true
            AlertDialog.Builder(this@MainActivity)
                .setTitle(if (removeConfiguration) getString(R.string.sources_remove_configuration) else getString(R.string.sources_disconnect_confirm_title, source.displayName))
                .setMessage(if (removeConfiguration) R.string.sources_remove_configuration_body else R.string.sources_disconnect_body)
                .setNeutralButton(R.string.sources_disconnect_keep_copies) { _, _ ->
                    nasDisconnect(source, keepCopies = true)
                }
                .setPositiveButton(R.string.common_delete) { _, _ ->
                    nasDisconnect(source, keepCopies = removeConfiguration, removeConfiguration = true)
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show().also { dialog ->
                    if (removeConfiguration) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).visibility = View.GONE
                }
        }
    }

    private fun nasDisconnect(source: SourceEntity, keepCopies: Boolean, removeConfiguration: Boolean = !keepCopies || source.status == SourceStatus.DISCONNECTED) {
        val env = environment ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    env.remoteCopies.cancelSource(source.id)
                    env.database.importJobDao().getBySource(source.id).forEach { env.runner.cancel(it.id) }
                    try {
                        env.localSources.disconnect(source.id, keepCopies, System.currentTimeMillis(), removeConfiguration = removeConfiguration)
                    } finally {
                        val current = env.database.sourceDao().getById(source.id)
                        if (current == null || current.status == SourceStatus.DISCONNECTED) source.endpointKey?.let { env.credentialStore.delete(it) }
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                AlertDialog.Builder(this@MainActivity).setMessage(R.string.sources_remove_failed).setPositiveButton(R.string.common_close, null).show()
            }
            framesDirty = true
            remoteFrameView?.reloadSources()
            activeSourcesView?.let { refreshNasSources(it) }
            refreshJobUi()
        }
    }

    private fun refreshNasSources(view: SourcesView) {
        val env = environment ?: return
        scope.launch {
            val references = withContext(Dispatchers.IO) {
                env.database.sourceDao().getAll().filter { JsonMini.parseObject(it.settingsJson)["referenceOnly"] == true }.map { source ->
                    SourcesView.LocalReferenceModel(source.id, source.displayName,
                        getString(if (source.status == SourceStatus.ACTIVE) R.string.source_local_reference_mode else R.string.source_local_reference_unavailable))
                }
            }
            if (activeSourcesView === view) view.renderLocalReferences(references)
            val models = withContext(Dispatchers.IO) {
                env.database.sourceDao().getAll()
                    .filter { it.type == SourceType.WEBDAV || it.type == SourceType.SMB }
                    .map { source ->
                        val hasCredential = source.endpointKey
                            ?.let { env.credentialStore.load(it) } != null
                        val orphan = !hasCredential && source.status == SourceStatus.ACTIVE
                        SourcesView.NasSourceModel(
                            sourceId = source.id,
                            displayName = source.displayName,
                            statusText = if (orphan) {
                                getString(R.string.sources_status_needs_auth)
                            } else if (source.status == SourceStatus.ACTIVE && env.database.settingsKvDao().get("nas.connection_error.${source.id}") != null) {
                                getString(R.string.sources_status_unavailable)
                            } else {
                                nasStatusText(source.status)
                            },
                            needsReauth = orphan || source.status == SourceStatus.NEEDS_AUTH,
                            isDisconnected = source.status == SourceStatus.DISCONNECTED,
                            catalogOnly = JsonMini.parseObject(source.settingsJson)["catalogOnly"] == true,
                            mirrorDelete = NasSourceLifecycle.mirrorDeleteFromSettings(
                                source.settingsJson,
                            ),
                        )
                    }
            }
            if (activeSourcesView === view) {
                view.renderNasSources(models)
            }
        }
    }

    private fun maybeAutoSyncNas() {
        val env = environment ?: return
        if (root.baseView !is MainContainerView) return
        scope.launch {
            val now = System.currentTimeMillis()
            val due = withContext(Dispatchers.IO) {
                env.database.sourceDao().getAll()
                    .filter { it.type in setOf(SourceType.WEBDAV, SourceType.SMB) && it.status == SourceStatus.ACTIVE }
                    .filter { source ->
                        source.endpointKey?.let { env.credentialStore.load(it) } != null
                    }
                    .filter {
                        now - (lastAutoSyncMs[it.id] ?: 0L) >= AUTO_SYNC_INTERVAL_MS
                    }
            }
            due.forEach { source ->
                lastAutoSyncMs[source.id] = now
                nasSyncNow(source.id, reportFailure = false)
            }
        }
    }

    private fun openNasEdit(sourceId: String) {
        val env = environment ?: return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val source = env.database.sourceDao().getById(sourceId) ?: return@withContext null
                val endpointKey = source.endpointKey ?: return@withContext null
                val credentialJson = env.credentialStore.load(endpointKey)
                val scopeJson = NasScope.sourceScope(source.settingsJson, env.database.importJobDao().getBySource(sourceId).sortedByDescending { it.createdAt }.map { it.scopeJson })
                NasEditContext(
                    sourceId = source.id,
                    endpointKey = endpointKey,
                    directories = (
                        JsonMini.parseObject(scopeJson)["directories"] as? List<*>
                        )
                        ?.mapNotNull { it as? String }
                        ?.toSet()
                        .orEmpty(),
                    recursive = NasSourceLifecycle.recursiveFromSettings(source.settingsJson),
                    catalogOnly = JsonMini.parseObject(source.settingsJson)["catalogOnly"] == true,
                ) to credentialJson
            } ?: return@launch
            val (editContext, credentialJson) = loaded
            val credential = JsonMini.parseObject(credentialJson)
            val endpointKey = editContext.endpointKey
            val protocol = endpointKey.substringBefore("://")
            val body = endpointKey.substringAfter("://")
            val hostPort = body.substringBefore('/')
            val path = body.substringAfter('/', "")
            val host = hostPort.substringBeforeLast(':')
            val port = hostPort.substringAfterLast(':', "").toIntOrNull()
            openNasForm(
                prefillProtocol = protocol,
                prefillHost = host,
                prefillPort = port,
                prefillPath = path,
                prefillUsername = credential["username"] as? String ?: "",
                prefillPassword = credential["password"] as? String ?: "",
                editContext = editContext,
            )
        }
    }

    private var pendingDeleteDialogShowing = false

    private val lastAutoSyncMs = mutableMapOf<String, Long>()

    private fun onNasMirrorToggle(sourceId: String, enabled: Boolean) {
        val env = environment ?: return
        if (!enabled) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    env.localSources.setMirrorDelete(sourceId, false, System.currentTimeMillis())
                }
                activeSourcesView?.let { refreshNasSources(it) }
            }
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.source_nas_mirror_delete)
            .setMessage(R.string.source_nas_mirror_delete_body)
            .setPositiveButton(R.string.common_continue) { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        env.localSources.setMirrorDelete(sourceId, true, System.currentTimeMillis())
                    }
                    activeSourcesView?.let { refreshNasSources(it) }
                }
            }
            .setNegativeButton(R.string.common_cancel) { _, _ ->
                activeSourcesView?.let { refreshNasSources(it) }
            }
            .show()
    }

    private fun checkPendingMirrorDelete() {
        val env = environment ?: return
        if (pendingDeleteDialogShowing) return
        scope.launch {
            val pending = withContext(Dispatchers.IO) {
                env.database.sourceDao().getAll()
                    .filter { it.type == SourceType.WEBDAV || it.type == SourceType.SMB }
                    .mapNotNull { source ->
                        val key = NasSourceLifecycle.pendingDeleteKey(source.id)
                        env.database.settingsKvDao().get(key)?.valueJson?.toIntOrNull()
                            ?.let { source to it }
                    }
            }
            val first = pending.firstOrNull() ?: return@launch
            pendingDeleteDialogShowing = true
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.source_nas_mirror_delete)
                .setMessage(
                    resources.getQuantityString(
                        R.plurals.source_nas_mirror_delete_confirm,
                        first.second,
                        first.second,
                    ),
                )
                .setPositiveButton(R.string.common_delete) { _, _ ->
                    pendingDeleteDialogShowing = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            env.coordinator.executeMirrorDeletion(first.first.id)
                            val key = NasSourceLifecycle.pendingDeleteKey(first.first.id)
                            env.database.settingsKvDao().get(key)
                                ?.let { env.database.settingsKvDao().delete(it) }
                        }
                        framesDirty = true
                        refreshJobUi()
                    }
                }
                .setNegativeButton(R.string.common_cancel) { _, _ ->
                    pendingDeleteDialogShowing = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val key = NasSourceLifecycle.pendingDeleteKey(first.first.id)
                            env.database.settingsKvDao().get(key)
                                ?.let { env.database.settingsKvDao().delete(it) }
                        }
                    }
                }
                .show()
        }
    }

    private fun nasStatusText(status: SourceStatus): String = getString(        when (status) {
            SourceStatus.ACTIVE -> R.string.sources_status_active
            SourceStatus.NEEDS_AUTH -> R.string.sources_status_needs_auth
            SourceStatus.UNAVAILABLE -> R.string.sources_status_unavailable
            SourceStatus.DISCONNECTED -> R.string.sources_status_disconnected
        },
    )

    private fun buildFramesView(): FramesView {
        val view = FramesView(this, File(filesDir, "originals")).apply { remoteMedia = environment?.remoteMedia }
        view.onOpen = { frame -> openFrameEditor(frame) }
        view.onCreate = {
            scope.launch {
                val hasPrivatePhotos = withContext(Dispatchers.IO) { environment?.database?.assetDao()?.browseReadyPage(1, 0).orEmpty().isNotEmpty() }
                if (hasPrivatePhotos) openFrameEditor(null)
                else openRemoteFrame(null, mapOf("firstUse" to "true"))
            }
        }
        view.onCreateAI = { openAIFrameEditor(null) }
        view.onContinueAI = { frame -> environment?.aiFrames?.resume(frame.id); framesDirty = true }
        view.onPlay = { frame -> openPlayer(frame, fromSnapshot = false) }
        view.onPlaybackSettings = { frame -> openPlaybackSettings(frame) }
        view.onRename = { frame -> showRenameFrameDialog(frame) }
        view.onDuplicate = { frame ->
            scope.launch {
                environment?.frameManager?.duplicateFrame(frame.id)
                framesDirty = true
                refreshJobUi()
            }
        }
        view.onDelete = { frame -> showDeleteFrameDialog(frame) }
        view.onRetry = { reloadFrames(view) }
        activeFramesView = view
        reloadFrames(view)
        return view
    }

    private fun reloadFrames(view: FramesView) {
        val env = environment ?: return
        if (activeFramesView !== view) return
        if (framesReloadJob?.isActive == true) {
            framesDirty = true
            return
        }
        framesDirty = false
        view.showLoading()
        framesReloadJob = scope.launch {
            try {
                val frames = withContext(Dispatchers.IO) { env.frameManager.listFrames() }
                if (activeFramesView !== view) return@launch
                view.showLoadingFrames(frames)
                val (cards, libraryEmpty) = withContext(Dispatchers.IO) {
                    val sources = env.database.sourceDao().getAll().associateBy { it.id }
                    val cardModels = frames.map { frame ->
                        val items = env.frames.playbackItems(frame.id)
                        val coverItem = env.frames.coverItem(frame.id, items)
                        val cover = coverItem?.takeIf { it.remote == null }?.local
                        val remoteMode = !dev.photopilot.scaffold.data.logic.FramePlaybackSettings.requiresLocalCopies(frame.settingsJson)
                        val readySources = items.filter { it.local != null || env.remoteMedia.isLocalReference(it) }.mapNotNull { it.sourceAssetId }.toSet()
                        val includedSources = env.database.frameSelectionDao().pendingIncludedSourceAssets(frame.id)
                        val pending = if (remoteMode) 0 else includedSources.count { it.id !in readySources }
                        val sourceIds = includedSources.map { it.sourceId } + env.database.frameDirectoryRuleDao().getByFrame(frame.id).mapNotNull { env.database.collectionDao().getById(it.collectionId)?.sourceId }
                        val hasNas = sourceIds.any { sources[it]?.type in setOf(SourceType.SMB, SourceType.WEBDAV) }
                        val hasDeviceReferences = sourceIds.any { JsonMini.parseObject(sources[it]?.settingsJson)["referenceOnly"] == true }
                        val ai = env.aiFrames.state.get(frame.id)
                        val aiPaused = ai?.userPaused == true
                        val aiProgress = ai?.let { configuration ->
                            val zh = resources.configuration.locales[0].language == "zh"
                            val state = if (aiPaused) { if (zh) "分类已暂停，待继续" else "Classification paused, ready to continue" }
                                else if (configuration.status == "running") { if (zh) "分类中" else "Classifying" }
                                else if (configuration.status == "complete") { if (zh) "分类完成" else "Classification complete" }
                                else { if (zh) "分类待继续" else "Classification pending" }
                            val reason = configuration.pauseReason?.let { dev.photopilot.scaffold.data.aiframes.aiPauseExplanation(it, zh) }.orEmpty()
                            "$state · ${configuration.completed} / ${configuration.total}" + if (reason.isEmpty()) "" else System.lineSeparator() + reason
                        }
                        FramesView.CardModel(frame, cover, items.size, pending, coverItem?.takeIf { it.remote != null }, hasNas, hasDeviceReferences, aiPaused = aiPaused, aiProgress = aiProgress)
                    }
                    val empty = cardModels.none { it.playable > 0 } && env.database.assetDao().browsePage(1, 0).isEmpty() && sources.values.none { source ->
                        env.remoteMedia.isMetadataSource(source) && env.remoteMedia.members(source.id, "", true).isNotEmpty()
                    }
                    cardModels to empty
                }
                if (activeFramesView === view) {
                    view.render(cards, libraryEmpty)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (activeFramesView === view) view.showLoadFailure()
            } finally {
                if (framesReloadJob === coroutineContext[Job]) {
                    framesReloadJob = null
                    if (coroutineContext[Job]?.isActive == true && activeFramesView === view && framesDirty) reloadFrames(view)
                }
            }
        }
    }

    private fun showRenameFrameDialog(frame: FrameEntity) {
        val input = EditText(this).apply {
            setText(frame.name)
            hint = getString(R.string.frames_name_placeholder)
            setSingleLine(true)
            KeyboardDismiss.configureDone(this)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.common_rename)
            .setView(input)
            .setPositiveButton(R.string.common_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    scope.launch {
                        environment?.frameManager?.renameFrame(frame.id, name)
                        framesDirty = true
                        refreshJobUi()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .create()
        dialog.setOnShowListener { KeyboardDismiss.attachDialog(dialog) }
        dialog.show()
    }

    private fun showDeleteFrameDialog(frame: FrameEntity) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.frames_delete_confirm_title, frame.name))
            .setMessage(R.string.frames_delete_confirm_body)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                scope.launch {
                    environment?.aiFrames?.cancel(frame.id)
                    environment?.frameManager?.deleteFrame(frame.id)
                    framesDirty = true
                    refreshJobUi()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun chooseFrameForContent() {
        val env = environment ?: return
        scope.launch {
            val frames = withContext(Dispatchers.IO) { env.frameManager.listFrames() }
            if (frames.isEmpty()) {
                openFrameEditor(null)
                return@launch
            }
            val names = frames.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.import_pick_frame_title)
                .setItems(names) { _, which -> openFrameEditor(frames[which]) }
                .setPositiveButton(R.string.import_pick_frame_new) { _, _ -> openFrameEditor(null) }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (environment != null) routeAIIntent()
    }

    private fun routeAIIntent() {
        val env = environment ?: return
        val taskId = intent.getStringExtra("aiTaskId") ?: return
        val modelId = intent.getStringExtra("aiModelId")
        intent.removeExtra("aiTaskId")
        intent.removeExtra("aiModelId")
        scope.launch {
            if (taskId.startsWith("frame:")) {
                val frame = withContext(Dispatchers.IO) { env.database.frameDao().getById(taskId.removePrefix("frame:")) }
                if (frame != null) openAIFrameEditor(frame)
            } else {
                val task = withContext(Dispatchers.IO) { env.aiFrames.tasks.record(taskId) }
                if (task?.kind == "preview") {
                    val frame = task.frameId?.let { withContext(Dispatchers.IO) { env.database.frameDao().getById(it) } }
                    openAIFrameEditor(frame, task.draft + ("draftId" to task.id))
                } else openAIModels(modelId ?: task?.modelId)
            }
        }
    }

    private fun openAIModels(modelId: String?) {
        val env = environment ?: return
        lateinit var view: dev.photopilot.scaffold.ui.aiframes.AIModelsView
        view = dev.photopilot.scaffold.ui.aiframes.AIModelsView(this, env, modelId) {
            if (root.topOverlay() === view) popOverlayTracked()
        }
        pushOverlayTracked(view, mapOf("type" to "aiModels", "modelId" to modelId.orEmpty()))
    }

    private fun openAIFrameEditor(frame: FrameEntity?, draft: Map<String, String>? = null) {
        val env = environment ?: return
        lateinit var view: dev.photopilot.scaffold.ui.aiframes.AIFrameView
        view = dev.photopilot.scaffold.ui.aiframes.AIFrameView(this, env, scope, frame,
            onClose = {
                if (root.topOverlay() === view) popOverlayTracked()
                framesDirty = true
                refreshJobUi()
            },
            onChanged = { framesDirty = true },
            restoredDraft = draft,
        )
        view.onLocalPhotos = { showPhotoPermissionOverlay() }
        view.onLocalFiles = { showLocalFilesChoices() }
        view.onConnect = { openNasForm() }
        view.onManageModels = { modelId -> openAIModels(modelId) }
        pushOverlayTracked(view, mapOf("type" to "aiFrame", "frameId" to (frame?.id ?: "")))
    }

    private fun openFrameEditor(frame: FrameEntity?, draft: Map<String, String>? = null) {
        val env = environment ?: return
        val view = FrameEditView(this, File(filesDir, "originals"))
        var referenceSources = emptySet<String>()
        view.dataSource = object : FrameEditView.DataSource {
            override fun fetchPhotos(limit: Int, offset: Int, callback: (List<AssetEntity>) -> Unit) {
                scope.launch {
                    val page = withContext(Dispatchers.IO) {
                        env.database.assetDao().browseReadyPage(limit, offset)
                    }
                    callback(page)
                }
            }

            override fun fetchCollections(callback: (List<Pair<CollectionEntity, Long>>) -> Unit) {
                scope.launch {
                    val collections = withContext(Dispatchers.IO) {
                        val metadataSources = env.database.sourceDao().getAll().filter { env.remoteMedia.isMetadataSource(it) }.map { it.id }.toSet()
                        env.database.collectionDao().getAll().map { collection ->
                            val count = if (collection.sourceId in metadataSources && collection.relPath != null) {
                                env.remoteMedia.members(requireNotNull(collection.sourceId), collection.relPath, false).size.toLong()
                            } else env.database.collectionAssetDao().getByCollection(collection.id).size.toLong()
                            collection to count
                        }.filter { dev.photopilot.scaffold.data.logic.CollectionListing.visibleInFolderList(it.first.type, it.second) } to metadataSources
                    }
                    referenceSources = collections.second
                    view.sourceNames = withContext(Dispatchers.IO) { env.database.sourceDao().getAll().filter { it.type in setOf(SourceType.SMB, SourceType.WEBDAV) }.associate { it.id to it.displayName } }
                    callback(collections.first)
                }
            }

            override fun fetchAssetsByIds(refs: List<String>, callback: (Map<String, AssetEntity>) -> Unit) {
                scope.launch {
                    val resolved = withContext(Dispatchers.IO) {
                        env.database.assetDao().getByIds(refs).associateBy { it.id }
                    }
                    callback(resolved)
                }
            }
        }
        view.onBack = { hasChanges -> handleEditorBack(view, hasChanges) }
        view.onSave = { state -> saveFrameEdit(view, frame, state) }
        view.onRemotePhotos = { leaveEditorFor(view, frame) { target -> openRemoteFrame(target) } }
        view.onOpenReferenceCollection = { collection ->
            if (collection.sourceId in referenceSources && collection.relPath != null) {
                leaveEditorFor(view, frame) { target -> openRemoteFrame(target, mapOf("sourceId" to requireNotNull(collection.sourceId), "folder" to collection.relPath)) }
                true
            } else false
        }
        view.onAISettings = { leaveEditorFor(view, frame) { target -> openAIFrameEditor(target) } }
        view.onManualOrder = { frame?.let { target -> openManualOrder(view, target) } }
        view.onConfirmDynamic = { completion -> confirmDynamicRule(completion) }
        frameEditView = view
        pushOverlayTracked(view, mapOf("type" to "frameEdit", "frameId" to (frame?.id ?: "")))
        scope.launch {
            val model = loadEditModel(frame)
            editorModel = model
            if (frameEditView === view) {
                view.bind(model)
                view.setAISettingsVisible(withContext(Dispatchers.IO) { frame?.let { env.aiFrames.state.get(it.id) } != null })
                draft?.let { view.restoreDraft(it) }
            }
        }
    }

    private fun leaveEditorFor(view: FrameEditView, frame: FrameEntity?, continuation: (FrameEntity?) -> Unit) {
        if (!view.hasChanges()) {
            continuation(frame)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.playback_unsaved_title)
            .setPositiveButton(R.string.common_save) { _, _ -> saveFrameEdit(view, frame, view.currentState(), continuation) }
            .setNeutralButton(R.string.common_discard) { _, _ ->
                scope.launch {
                    val model = loadEditModel(frame)
                    editorModel = model
                    view.bind(model)
                    continuation(frame)
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private suspend fun loadEditModel(frame: FrameEntity?): FrameEditView.Model {
        val env = requireNotNull(environment)
        return withContext(Dispatchers.IO) {
            if (frame == null) {
                FrameEditView.Model(
                    name = getString(R.string.frames_name_default),
                    orderMode = dev.photopilot.scaffold.data.db.FrameOrderMode.CAPTURED_ASC,
                    includedAssetIds = emptySet(),
                    excludedRefs = emptySet(),
                    dynamicRules = emptyMap(),
                    showAllPending = false,
                )
            } else {
                val selections = env.database.frameSelectionDao().getByFrame(frame.id)
                val included = selections
                    .filter { it.mode == FrameSelectionMode.INCLUDE && it.assetId != null }
                    .mapNotNull { it.assetId }
                    .toSet()
                val excluded = selections
                    .filter { it.mode == FrameSelectionMode.EXCLUDE }
                    .map { it.refKey }
                    .toSet()
                val rules = env.database.frameDirectoryRuleDao().getByFrame(frame.id)
                    .associate {
                        it.collectionId to SaveRuleRow(it.collectionId, it.recursive, it.includeFuture)
                    }
                val hasPlayable = env.frames.resolve(frame.id).playable.isNotEmpty()
                val hasSelections = selections.isNotEmpty() || rules.isNotEmpty()
                val persistedOrder = if (frame.orderMode == FrameOrderMode.MANUAL) {
                    selections
                        .filter { it.mode == FrameSelectionMode.INCLUDE && it.assetId != null }
                        .sortedWith(
                            compareBy(
                                { it.manualOrder == null },
                                { it.manualOrder ?: 0L },
                                { it.addedAt },
                            ),
                        )
                        .mapNotNull { selection -> selection.assetId?.let { it to selection.manualOrder } }
                } else {
                    emptyList()
                }
                FrameEditView.Model(
                    name = frame.name,
                    orderMode = frame.orderMode,
                    includedAssetIds = included,
                    excludedRefs = excluded,
                    dynamicRules = rules,
                    showAllPending = !hasPlayable && hasSelections,
                    persistedManualOrder = persistedOrder,
                    coverAssetId = frame.coverAssetId?.let { env.frames.eligibleCover(it)?.id },
                    defaultCoverAssetId = frame.coverAssetId?.let { env.frames.eligibleCover(it)?.id }
                        ?: env.frames.buildPlayQueue(frame.id).firstOrNull(),
                )
            }
        }
    }

    private fun handleEditorBack(view: FrameEditView, hasChanges: Boolean) {
        if (frameSaveInFlight) return
        if (!hasChanges) {
            closeFrameEditor()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.playback_unsaved_title)
            .setPositiveButton(R.string.common_discard) { _, _ -> closeFrameEditor() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun closeFrameEditor() {
        frameEditView = null
        editorModel = null
        popOverlayTracked()
    }

    private fun confirmDynamicRule(completion: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(R.string.rule_dynamic_warning)
            .setPositiveButton(R.string.common_continue) { _, _ -> completion(true) }
            .setNegativeButton(R.string.common_cancel) { _, _ -> completion(false) }
            .show()
    }

    private fun saveFrameEdit(view: FrameEditView, frame: FrameEntity?, state: FrameEditView.State, continuation: ((FrameEntity?) -> Unit)? = null) {
        if (frameSaveInFlight) return
        val env = environment ?: return
        val model = editorModel ?: return
        frameSaveInFlight = true
        view.setSaving(true)
        Toast.makeText(this, if (resources.configuration.locales[0].language == "zh") "正在保存…" else "Saving…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                saveFrameEditInternal(env, model, frame, state, continuation)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                AlertDialog.Builder(this@MainActivity).setMessage(R.string.error_store_write_failed_body).setPositiveButton(R.string.common_close, null).show()
            } finally {
                frameSaveInFlight = false
                view.setSaving(false)
            }
        }
    }

    private suspend fun saveFrameEditInternal(
        env: AppEnvironment,
        model: FrameEditView.Model,
        frame: FrameEntity?,
        state: FrameEditView.State,
        continuation: ((FrameEntity?) -> Unit)? = null,
    ) {
        val saved = withContext(Dispatchers.IO) {
            env.database.withTransaction {
                val now = System.currentTimeMillis()
                val target = if (frame != null) {
                    if (state.name != frame.name) {
                        env.frameManager.renameFrame(frame.id, state.name)
                    }
                    requireNotNull(env.database.frameDao().getById(frame.id))
                } else {
                    env.frameManager.createFrame(
                        state.name.ifEmpty { getString(R.string.frames_name_default) },
                    )
                }
                val staticMembers = mutableMapOf<String, List<String>>()
                state.ruleStates
                    .filter { it.value.checked && !it.value.dynamic }
                    .keys
                    .forEach { collectionId ->
                        staticMembers[collectionId] = withContext(Dispatchers.IO) {
                            env.database.collectionAssetDao().getByCollection(collectionId)
                                .map { it.assetId }
                        }
                    }
                val diff = FrameEditDiff.plan(
                    includedAssetIds = model.includedAssetIds,
                    dynamicRules = model.dynamicRules,
                    selectedAssetIds = state.selectedAssetIds,
                    removedExcludes = state.removedExcludes,
                    ruleStates = state.ruleStates,
                    staticMembers = staticMembers,
                    now = now,
                )
                val result = env.frames.save(
                    frameId = target.id,
                    baseVersion = target.selectionVersion,
                    addIncludes = diff.addIncludes.map {
                        SelectionRefEdit(assetId = it.ref, sourceAssetId = null)
                    },
                    removeRefs = diff.removeRefs.map {
                        SelectionRefEdit(assetId = it, sourceAssetId = null)
                    },
                    ruleChanges = diff.ruleChanges,
                    removeRules = diff.removeRules,
                    manualOrderRefs = state.manualOrder,
                    now = now,
                )
                if (result !is SelectionSaveResult.Ok) throw java.io.IOException("SELECTION_CONFLICT")
                if (state.pendingCoverId != model.coverAssetId) env.frameManager.setCover(target.id, state.pendingCoverId)
                requireNotNull(env.database.frameDao().getById(target.id))
            }
        }
        framesDirty = true
        closeFrameEditor()
        if (continuation != null) {
            openFrameEditor(saved)
            continuation(saved)
        } else if (root.baseView !is MainContainerView) {
            showMainContainer()
            openPlayer(saved, fromSnapshot = false)
        }
    }

    private var weatherTextCache: String? = null
    private var weatherPlaceCache: String? = null
    private var weatherA11yCache: String? = null
    private var weatherRefreshJob: Job? = null

    private fun openHolidaySettings() {
        val env = environment ?: return
        val view = HolidaySettingsView(this)
        holidaySettingsView = view
        view.onBack = {
            holidaySettingsView = null
            popOverlayTracked()
        }
        view.onRequestAdd = {
            scope.launch {
                val frames = withContext(Dispatchers.IO) {
                    env.frameManager.listFrames().map { HolidaySettingsView.FrameOption(it.id, it.name) }
                }
                if (holidaySettingsView === view) view.openEditor(null, frames)
            }
        }
        view.onRequestEdit = { rule ->
            scope.launch {
                val frames = withContext(Dispatchers.IO) {
                    env.frameManager.listFrames().map { HolidaySettingsView.FrameOption(it.id, it.name) }
                }
                if (holidaySettingsView === view) view.openEditor(rule, frames)
            }
        }
        view.onToggleEnabled = { rule, enabled ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    val dao = env.database.holidayDao()
                    dao.getById(rule.id)?.let {
                        dao.upsert(it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
                    }
                }
                reloadHolidayRules(view)
            }
        }
        view.onDeleteRule = { rule ->
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.frames_delete_confirm_title, rule.name))
                .setMessage(R.string.frames_delete_confirm_body)
                .setPositiveButton(R.string.common_delete) { _, _ ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            env.database.holidayDao().getById(rule.id)?.let {
                                env.database.holidayDao().delete(it)
                            }
                        }
                        reloadHolidayRules(view)
                    }
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
        view.onSaveDraft = { draft, confirmed -> saveHolidayRule(view, draft, confirmed) }
        pushOverlayTracked(view, mapOf("type" to "holiday"))
        reloadHolidayRules(view)
    }

    private fun reloadHolidayRules(view: HolidaySettingsView) {
        val env = environment ?: return
        scope.launch {
            val rows = withContext(Dispatchers.IO) {
                env.database.holidayDao().getAll().map {
                    HolidaySettingsView.RuleRow(
                        id = it.id,
                        frameId = it.frameId,
                        name = it.name,
                        startDate = it.startDate,
                        endDate = it.endDate,
                        startTime = it.startTime?.toInt(),
                        endTime = it.endTime?.toInt(),
                        position = it.position,
                        enabled = it.enabled,
                    )
                }
            }
            if (holidaySettingsView === view) view.renderRules(rows)
        }
    }

    private fun saveHolidayRule(
        view: HolidaySettingsView,
        draft: HolidaySettingsView.Draft,
        confirmed: Boolean,
    ) {
        val env = environment ?: return
        val name = draft.name.trim()
        val startDate = draft.startDate ?: return
        val endDate = draft.endDate ?: return
        val frameId = draft.frameId ?: return
        scope.launch {
            val dao = env.database.holidayDao()
            val others = withContext(Dispatchers.IO) {
                dao.getEnabled().filter { it.id != draft.id }.map {
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
            }
            val candidate = HolidayTiming.Rule(
                id = draft.id ?: "",
                enabled = draft.enabled,
                position = 0,
                startDate = startDate,
                endDate = endDate,
                startTime = draft.startTime,
                endTime = draft.endTime,
                frameId = frameId,
                name = name,
            )
            if (!confirmed) {
                val conflict = others.firstOrNull { HolidayTiming.overlap(candidate, it) }
                if (conflict != null) {
                    if (holidaySettingsView === view) view.showConflict(conflict.name)
                    return@launch
                }
            }
            withContext(Dispatchers.IO) {
                val old = draft.id?.let { dao.getById(it) }
                val now = System.currentTimeMillis()
                dao.upsert(
                    HolidayScheduleEntity(
                        id = draft.id ?: java.util.UUID.randomUUID().toString(),
                        frameId = frameId,
                        name = name,
                        startDate = startDate,
                        endDate = endDate,
                        startTime = draft.startTime?.toLong(),
                        endTime = draft.endTime?.toLong(),
                        position = old?.position ?: (dao.maxPosition() + 1),
                        enabled = draft.enabled,
                        createdAt = old?.createdAt ?: now,
                        updatedAt = now,
                    ),
                )
            }
            if (holidaySettingsView === view) {
                view.closeEditor()
                reloadHolidayRules(view)
            }
        }
    }

    private fun openPlayer(frame: FrameEntity, fromSnapshot: Boolean) {
        if (playerView != null) return
        val env = environment ?: return
        val view = PlayerView(this, frame)
        val engine = PlayerEngine(this, env, frame, view, scope)
        view.engine = engine
        view.onExit = { closePlayer() }
        view.onPlaybackSettings = { frame -> openPlaybackSettings(frame) }
        view.weatherTextProvider = { weatherTextCache }
        view.weatherPlaceProvider = { weatherPlaceCache }
        view.weatherA11yProvider = { weatherA11yCache }
        view.onEmptyChoose = { target ->
            closePlayer()
            openFrameEditor(target)
        }
        view.onEmptyDownloads = {
            closePlayer()
            scope.launch {
                val latest = env.progress.latest()
                if (latest != null) {
                    openImportProgress(latest.jobId)
                }
            }
        }
        engine.onHolidayEnter = { ruleId, ruleFrameId, ruleName ->
            if (playerEngine === engine) {
                scope.launch {
                    val target = withContext(Dispatchers.IO) {
                        env.database.frameDao().getById(ruleFrameId)
                    }
                    if (target != null && playerEngine === engine && playerView != null) {
                        val previousFrameId = frame.id
                        closePlayer()
                        preHolidayFrameId = previousFrameId
                        openPlayer(target, fromSnapshot = false)
                        playerEngine?.adoptHolidayRule(ruleId, ruleName)
                        playerView?.showNotice(getString(R.string.holiday_notice_switched, ruleName))
                        env.settings.putRaw("playback.session_source", "holiday:$ruleId")
                    }
                }
            }
        }
        engine.onHolidayMissing = { _, ruleName ->
            playerView?.showNotice(getString(R.string.holiday_notice_missing, ruleName))
        }
        engine.onHolidayReturn = { _, _ ->
            val back = preHolidayFrameId
            if (back != null && playerView != null && playerEngine === engine) {
                scope.launch {
                    val target = withContext(Dispatchers.IO) {
                        env.database.frameDao().getById(back)
                    }
                    if (playerView != null && playerEngine === engine) {
                        closePlayer()
                        preHolidayFrameId = null
                        if (target != null) openPlayer(target, fromSnapshot = true)
                        playerView?.showNotice(getString(R.string.holiday_notice_returned))
                        env.settings.putRaw("playback.session_source", "")
                    }
                }
            }
        }
        playerView = view
        playerEngine = engine
        pushOverlayTracked(view, mapOf("type" to "player", "frameId" to frame.id))
        view.applyBrightnessIfNeeded(this)
        engine.start(fromSnapshot)
        scope.launch { refreshWeatherLine() }
        autoLocateWeatherForPlayback()
    }

    private fun closePlayer() {
        val player = playerView
        playerEngine?.stop(clearSnapshot = false)
        playerEngine = null
        playerView = null
        if (player == null) {
            popOverlayTracked()
        } else {
            while (root.topOverlay() != null && root.topOverlay() !== player) {
                val popped = popOverlayTracked() ?: break
                if (popped === playbackSettingsView) playbackSettingsView = null
                if (popped === holidaySettingsView) holidaySettingsView = null
                if (popped === musicPickerView) musicPickerView = null
                if (popped === permissionOverlay) permissionOverlay = null
            }
            root.removeOverlay(player)
            overlayMeta.removeAll { it.first === player }
        }
        framesDirty = true
        refreshJobUi()
    }

    private fun maybeAutoPlay(env: AppEnvironment) {
        scope.launch {
            val frameId = withContext(Dispatchers.IO) {
                AutoPlayRouter.decideFrame(
                    coldStart = coldStart,
                    onboardingCompleted = true,
                    hasPlayedOnce = env.settings.hasPlayedOnce(),
                    autoPlayEnabled = env.settings.isAutoPlayEnabled(),
                    lastFrameId = env.settings.lastFrameId(),
                )
            } ?: return@launch
            val frame = withContext(Dispatchers.IO) {
                env.database.frameDao().getById(frameId)
            } ?: return@launch
            openPlayer(frame, fromSnapshot = true)
        }
    }

    private fun openPlaybackSettings(frame: FrameEntity) {
        val env = environment ?: return
        scope.launch {
            val latest = withContext(Dispatchers.IO) {
                env.database.frameDao().getById(frame.id)
            } ?: return@launch
            if (playbackSettingsView == null) showPlaybackSettings(latest)
        }
    }

    private fun showPlaybackSettings(frame: FrameEntity) {
        val env = environment ?: return
        val view = PlaybackSettingsView(this)
        playbackSettingsView = view
        view.onBack = { hasChanges -> handleSettingsBack(view, hasChanges) }
        view.onSave = { draft, orderMode -> savePlaybackSettings(view, frame, draft, orderMode) }
        view.onManagePhotos = { openFrameEditor(frame) }
        view.onPickMusic = { openMusicPicker(view) }
        view.onWeatherSetup = {
            scope.launch {
                env.settings.putRaw(WeatherLogic.KEY_ENABLED_DEFAULT, AppSettingsRepository.VALUE_TRUE)
            }
            openWeatherSearch { _, _ ->
                scope.launch { view.weatherReady = weatherReadyNow(env) }
            }
        }
        scope.launch { view.weatherReady = weatherReadyNow(env) }
        pushOverlayTracked(view, mapOf("type" to "playbackSettings", "frameId" to frame.id))
        view.bind(frame)
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                val queue = env.frames.buildPlayQueue(frame.id)
                val all = env.frames.playbackItems(frame.id).associateBy { it.id }
                queue.take(2).mapNotNull { all[it] }
            }
            val decoded = items.map { item -> runCatching { env.remoteMedia.bitmap(item, 640, 640) }.getOrNull() }
            if (playbackSettingsView === view) view.bindRemotePreview(decoded.getOrNull(0), decoded.getOrNull(1))
        }
    }

    private fun handleSettingsBack(view: PlaybackSettingsView, hasChanges: Boolean) {
        if (!hasChanges) {
            closePlaybackSettings()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.playback_unsaved_title)
            .setPositiveButton(R.string.common_discard) { _, _ -> closePlaybackSettings() }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun closePlaybackSettings() {
        playbackSettingsView?.cancelBrightnessPreview()
        playbackSettingsView = null
        popOverlayTracked()
    }

    private fun openMusicPicker(settingsView: PlaybackSettingsView) {
        val view = MusicPickerView(this)
        musicPickerView = view
        view.onBack = {
            musicPickerView = null
            popOverlayTracked()
        }
        view.onSelect = { selection -> settingsView.updateMusic(selection) }
        view.onChooseLocal = { launchMusicPicker() }
        view.bind(settingsView.musicSelection())
        root.pushOverlay(view)
    }

    private fun launchMusicPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        startActivityForResult(intent, REQUEST_PICK_MUSIC)
    }

    private fun importLocalMusic(uri: Uri) {
        val picker = musicPickerView ?: return
        scope.launch {
            val staged = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = runCatching {
                        val cursor = contentResolver.query(
                            uri,
                            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null,
                        )
                        cursor?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                    }.getOrNull()
                    val extension = displayName?.substringAfterLast('.', "")?.lowercase()
                        ?.takeIf { it.isNotEmpty() } ?: "m4a"
                    val dir = MusicCatalog.musicDir(this@MainActivity)
                    dir.mkdirs()
                    val fileName = "${java.util.UUID.randomUUID()}.$extension"
                    val target = File(dir, fileName)
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { out -> input.copyTo(out) }
                        } ?: throw java.io.FileNotFoundException("unreadable uri")
                    } catch (error: Exception) {
                        target.delete()
                        throw error
                    }
                    fileName to displayName
                }.getOrNull()
            }
            if (musicPickerView !== picker) {
                staged?.let { File(MusicCatalog.musicDir(this@MainActivity), it.first).delete() }
                return@launch
            }
            if (staged == null) {
                Toast.makeText(this@MainActivity, R.string.playback_music_local_error, Toast.LENGTH_SHORT).show()
            } else {
                picker.setLocalTrack(staged.first, staged.second)
            }
        }
    }

    private fun savePlaybackSettings(
        view: PlaybackSettingsView,
        frame: FrameEntity,
        draft: PlaybackSettingsEdit.Draft,
        orderMode: FrameOrderMode,
    ) {
        val env = environment ?: return
        val error = PlaybackSettingsEdit.validate(draft)
        if (error != null) {
            view.showError(error)
            return
        }
        if (playbackSettingsView !== view) return
        scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                env.database.withTransaction {
                    val current = env.database.frameDao().getById(frame.id) ?: return@withTransaction null
                    current.copy(
                        settingsJson = PlaybackSettingsEdit.merge(current.settingsJson, draft),
                        orderMode = orderMode,
                        updatedAt = System.currentTimeMillis(),
                    ).also { env.database.frameDao().update(it) }
                }
            }
            if (fresh != null && playerEngine?.frameId() == fresh.id) {
                playerEngine?.reloadSettings(fresh)
                playerView?.reloadSettings(fresh)
                view.commitBrightnessPreview()
            }
            framesDirty = true
            if (playbackSettingsView === view) closePlaybackSettings()
            refreshJobUi()
        }
    }

    private var weatherSearchView: WeatherCitySearchView? = null
    private var pendingLocationAction: (() -> Unit)? = null
    private val geocodeExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "photopilot-geocode").apply { isDaemon = true }
    }
    private var musicPickerView: MusicPickerView? = null

    private fun openWeatherSearch(onSelected: (WeatherLogic.StoredPlace, String) -> Unit) {
        val env = environment ?: return
        val view = WeatherCitySearchView(this)
        weatherSearchView = view
        var generation = 0L
        view.onBack = {
            generation++
            weatherSearchView = null
            popOverlayTracked()
        }
        view.onSearch = { query ->
            val request = ++generation
            view.showSearching()
            scope.launch {
                val lang = weatherLang()
                val results = withContext(Dispatchers.IO) {
                    val otherLang = if (lang == "zh") "en" else "zh"
                    val (preferred, other) = coroutineScope {
                        val preferredRequest = async { runCatching { env.weatherProviderSearch(query, lang) } }
                        val otherRequest = async { runCatching { env.weatherProviderSearch(query, otherLang) } }
                        preferredRequest.await() to otherRequest.await()
                    }
                    if (preferred.isFailure && other.isFailure) preferred else Result.success(
                        dev.photopilot.scaffold.data.net.PlaceResult.mergeLocalized(
                            preferred.getOrDefault(emptyList()), other.getOrDefault(emptyList()), lang))
                }
                if (weatherSearchView !== view || request != generation) return@launch
                results.onSuccess { view.showResults(it) }
                results.onFailure { view.showError(R.string.weather_unavailable) }
            }
        }
        view.onSelect = { place ->
            val request = ++generation
            scope.launch {
                val stored = withContext(Dispatchers.IO) {
                    val lang = place.language ?: weatherLang()
                    val otherLang = if (lang == "zh") "en" else "zh"
                    val other = if (place.placeId.startsWith("om:")) runCatching {
                        env.weatherProviderSearch(place.name, otherLang)
                            .firstOrNull { it.placeId == place.placeId }
                    }.getOrNull() else null
                    place.stored(lang, other)
                }
                if (weatherSearchView !== view || request != generation) return@launch
                env.weather.putPlace(stored, followLocation = false)
                if (weatherSearchView === view && request == generation) {
                    weatherSearchView = null
                    popOverlayTracked()
                    val name = WeatherLogic.detailedName(stored, weatherLang())
                    onSelected(stored, name)
                }
            }
        }
        view.onSelectCurrentLocation = {
            val request = ++generation
            useCurrentLocation(view, onSelected) { request == generation }
        }
        root.pushOverlay(view)
    }

    private fun useCurrentLocation(
        view: WeatherCitySearchView,
        onSelected: (WeatherLogic.StoredPlace, String) -> Unit,
        isCurrent: () -> Boolean,
    ) {
        if (!isCurrent() || weatherSearchView !== view) return
        val env = environment ?: return
        view.showSearching()
        val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingLocationAction = { useCurrentLocation(view, onSelected, isCurrent) }
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQUEST_LOCATION,
            )
            return
        }
        scope.launch {
            val stored = try {
                withContext(Dispatchers.IO) { currentLocationPlace() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                null
            }
            if (weatherSearchView !== view || !isCurrent()) return@launch
            if (stored == null) {
                view.showError(R.string.weather_unavailable)
                return@launch
            }
            env.weather.putPlace(stored, followLocation = true)
            weatherSearchView = null
            popOverlayTracked()
            val name = WeatherLogic.detailedName(stored, weatherLang())
            onSelected(stored, name)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocationPlace(): WeatherLogic.StoredPlace? {
        val manager = getSystemService(LocationManager::class.java) ?: return null
        val providers = manager.getProviders(true)
        if (providers.isEmpty()) return null
        var location: Location? = providers.mapNotNull {
            runCatching { manager.getLastKnownLocation(it) }.getOrNull()
        }
            .filter { System.currentTimeMillis() - it.time <= 5 * 60_000L }
            .maxByOrNull { it.time }
        if (location == null) {
            var listener: LocationListener? = null
            location = try {
                withTimeoutOrNull(LOCATION_FIX_TIMEOUT_MS) {
                    suspendCancellableCoroutine { continuation ->
                        val next = LocationListener { fix ->
                            if (continuation.isActive) continuation.resume(fix)
                        }
                        listener = next
                        val started = providers.count {
                            runCatching {
                                manager.requestLocationUpdates(it, 0L, 0f, next, Looper.getMainLooper())
                            }.isSuccess
                        }
                        if (started == 0 && continuation.isActive) continuation.resume(null)
                    }
                }
            } finally {
                listener?.let { runCatching { manager.removeUpdates(it) } }
            }
        }
        val fix = location ?: return null
        val lat = WeatherLogic.coarsen(fix.latitude)
        val lon = WeatherLogic.coarsen(fix.longitude)
        val latE7 = Math.round(lat * 10_000_000.0).toInt()
        val lonE7 = Math.round(lon * 10_000_000.0).toInt()
        val names = reverseGeocodeNames(fix.latitude, fix.longitude)
        val fallback = String.format(Locale.US, "%.1f, %.1f", lat, lon)
        val candidate = WeatherLogic.StoredPlace(
            placeId = "${WeatherLogic.PLACE_ID_LOCATION_PREFIX}$latE7,$lonE7",
            nameZh = WeatherLogic.compact(names.first ?: names.second, fallback, "zh"),
            nameEn = WeatherLogic.compact(names.second ?: names.first, fallback, "en"),
            displayZh = names.first,
            displayEn = names.second,
            latE7 = latE7,
            lonE7 = lonE7,
        )
        val existing = environment?.weather?.place(candidate.placeId)
        return WeatherLogic.preserveLocatedPlace(existing, candidate)
    }

    private suspend fun reverseGeocodeNames(lat: Double, lon: Double): Pair<WeatherLogic.DisplayFields?, WeatherLogic.DisplayFields?> =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext (null to null)
            val zh = geocodeExecutor.submit<WeatherLogic.DisplayFields?> { reverseGeocodeName(lat, lon, Locale("zh")) }
            val en = geocodeExecutor.submit<WeatherLogic.DisplayFields?> { reverseGeocodeName(lat, lon, Locale.ENGLISH) }
            val zhName = runCatching { zh.get(GEOCODE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            val enName = runCatching { en.get(GEOCODE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            zhName to enName
        }

    private fun reverseGeocodeName(lat: Double, lon: Double, locale: Locale): WeatherLogic.DisplayFields? =
        runCatching {
            Geocoder(this, locale).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { address ->
                dev.photopilot.scaffold.data.net.NativeGeocoderProvider.displayFields(address)
            }
        }.getOrNull()

    private fun autoLocateWeatherForPlayback() {
        val env = environment ?: return
        val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        scope.launch {
            val weatherOn = env.settings.rawValue(WeatherLogic.KEY_ENABLED_DEFAULT) ==
                AppSettingsRepository.VALUE_TRUE
            if (!weatherOn || !env.weather.followsLocation()) return@launch
            if (!granted) {
                if (pendingLocationAction == null) {
                    pendingLocationAction = { autoLocateWeatherForPlayback() }
                    requestPermissions(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                        REQUEST_LOCATION,
                    )
                }
                return@launch
            }
            val startingPlaceId = env.weather.selectedPlace()?.placeId
            val place = try {
                withContext(Dispatchers.IO) { currentLocationPlace() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                null
            } ?: return@launch
            if (!env.weather.putLocatedPlace(place, startingPlaceId)) return@launch
            refreshWeatherLine()
        }
    }

    private fun weatherLang(): String =
        if (resources.configuration.locales[0]?.language == "zh") "zh" else "en"

    private fun openManualOrder(editor: FrameEditView, frame: FrameEntity) {
        val env = environment ?: return
        val working = editor.currentManualOrder() ?: return
        val view = ManualOrderView(this, File(filesDir, "originals"))
        manualOrderView = view
        view.onBack = { dirty ->
            if (dirty) {
                editor.setManualOrder(view.currentOrder())
            }
            closeManualOrder()
        }
        root.pushOverlay(view)
        scope.launch {
            val persistedIds = editorModel?.persistedManualOrder?.map { it.first }?.toSet() ?: emptySet()
            val rows = withContext(Dispatchers.IO) {
                val assets = env.database.assetDao().getByIds(working).associateBy { it.id }
                working.mapNotNull { id ->
                    assets[id]?.let { it to if (id in persistedIds) 1L else null }
                }
            }
            val hasExcludes = editorModel?.excludedRefs?.isNotEmpty() == true
            if (manualOrderView === view) {
                view.bind(rows, hasExcludes)
            }
        }
    }

    private fun closeManualOrder() {
        manualOrderView = null
        popOverlayTracked()
    }

    private fun buildLibraryView(): LibraryView {
        val env = environment
        var referenceSources = emptySet<String>()
        val view = LibraryView(this, File(filesDir, "originals"))
        if (env != null) {
            view.dataSource = object : LibraryView.DataSource {
                override fun fetchPhotos(
                    criteria: LibraryCriteria,
                    limit: Int,
                    offset: Int,
                    callback: (List<AssetEntity>) -> Unit,
                ) {
                    scope.launch {
                        val page = withContext(Dispatchers.IO) {
                            if (criteria.isFiltered() || criteria.sort != LibrarySort.CAPTURED_DESC) {
                                val (sql, args) = LibraryQueryBuilder.build(criteria, limit, offset)
                                env.database.assetDao().rawAssets(SimpleSQLiteQuery(sql, args))
                            } else {
                                env.database.assetDao().browsePage(limit, offset)
                            }
                        }
                        callback(page)
                    }
                }

                override fun fetchCollectionPhotos(
                    collectionId: String,
                    limit: Int,
                    offset: Int,
                    callback: (List<AssetEntity>) -> Unit,
                ) {
                    scope.launch {
                        val page = withContext(Dispatchers.IO) {
                            env.database.assetDao().browseCollectionPage(collectionId, limit, offset)
                        }
                        callback(page)
                    }
                }

                override fun fetchCollections(callback: (List<Pair<CollectionEntity, Long>>) -> Unit) {
                    scope.launch {
                        val collections = withContext(Dispatchers.IO) {
                            val metadataSources = env.database.sourceDao().getAll().filter { env.remoteMedia.isMetadataSource(it) }.map { it.id }.toSet()
                            env.database.collectionDao().getAll()
                                .map { collection ->
                                    val count = if (collection.sourceId in metadataSources && collection.relPath != null) {
                                        env.remoteMedia.members(requireNotNull(collection.sourceId), collection.relPath, false).size.toLong()
                                    } else env.database.collectionAssetDao().getByCollection(collection.id).size.toLong()
                                    collection to count
                                }
                                .filter { dev.photopilot.scaffold.data.logic.CollectionListing.visibleInFolderList(it.first.type, it.second) } to metadataSources
                        }
                        referenceSources = collections.second
                        callback(collections.first)
                    }
                }

                override fun fetchSources(callback: (List<SourceEntity>) -> Unit) {
                    scope.launch {
                        val sources = withContext(Dispatchers.IO) {
                            env.database.sourceDao().getAll()
                        }
                        callback(sources)
                    }
                }

                override fun fetchPending(callback: (List<SourceAssetEntity>) -> Unit) {
                    scope.launch {
                        val pending = withContext(Dispatchers.IO) {
                            env.database.sourceAssetDao().pendingForActiveJobs()
                        }
                        callback(pending)
                    }
                }
            }
        }
        view.onOpenReferenceCollection = { collection ->
            if (collection.sourceId in referenceSources && collection.relPath != null) {
                chooseRemoteFrame(mapOf("sourceId" to requireNotNull(collection.sourceId), "folder" to collection.relPath))
                true
            } else false
        }
        view.onBrowseSourcePhotos = { chooseRemoteFrame() }
        view.onPhotoClick = { asset -> openPhotoDetail(asset) }
        view.onAddToFrame = { selected ->
            showFramePickerForAssets(selected) { view.exitSelectionMode() }
        }
        sectionBackHandler = { view.navigateBackWithin() }
        activeLibraryView = view
        return view
    }

    private fun showFramePickerForAssets(selectedIds: Set<String>, onAdded: () -> Unit) {
        val env = environment ?: return
        if (selectedIds.isEmpty()) return
        scope.launch {
            val frames = withContext(Dispatchers.IO) { env.frameManager.listFrames() }
            if (frames.isEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(R.string.frames_empty_title)
                    .setPositiveButton(R.string.common_done, null)
                    .show()
                return@launch
            }
            val names = frames.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.library_add_to_frame)
                .setItems(names) { _, which ->
                    addAssetsToFrame(frames[which], selectedIds, retryOnConflict = true, onAdded)
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    private fun addAssetsToFrame(
        frame: FrameEntity,
        selectedIds: Set<String>,
        retryOnConflict: Boolean,
        onAdded: () -> Unit,
    ) {
        val env = environment ?: return
        scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                env.database.frameDao().getById(frame.id)
            } ?: return@launch
            val result = env.frames.save(
                frameId = fresh.id,
                baseVersion = fresh.selectionVersion,
                addIncludes = selectedIds.sorted().map {
                    SelectionRefEdit(assetId = it, sourceAssetId = null)
                },
            )
            when (result) {
                is SelectionSaveResult.Ok -> {
                    onAdded()
                    framesDirty = true
                    Toast.makeText(
                        this@MainActivity,
                        R.string.library_added_to_frame,
                        Toast.LENGTH_SHORT,
                    ).show()
                    refreshJobUi()
                }
                is SelectionSaveResult.Conflict,
                SelectionSaveResult.Rejected,
                -> {
                    if (retryOnConflict) {
                        addAssetsToFrame(fresh, selectedIds, retryOnConflict = false, onAdded)
                    } else {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage(R.string.error_selection_conflict_body)
                            .setPositiveButton(R.string.common_done, null)
                            .show()
                    }
                }
            }
        }
    }

    private fun openPhotoDetail(asset: AssetEntity) {
        val view = PhotoDetailView(this).apply {
            onBack = { closePhotoDetail() }
            onAddToFrame = { target ->
                showFramePickerForAssets(setOf(target.id)) { bindPhotoDetail(photoDetailView, target) }
            }
            onRemoveFromFrame = { target -> showRemoveFromFrame(target) }
            onDeleteFromDevice = { target -> confirmDeleteFromDevice(target) }
            onHideGlobally = { target -> confirmHideGlobally(target) }
        }
        photoDetailView = view
        pushOverlayTracked(view, mapOf("type" to "photoDetail", "assetId" to asset.id))
        bindPhotoDetail(view, asset)
    }

    private fun bindPhotoDetail(view: PhotoDetailView?, asset: AssetEntity) {
        val env = environment ?: return
        if (view == null) return
        scope.launch {
            val (sourceName, framesCount) = withContext(Dispatchers.IO) {
                val source = env.database.sourceDao().sourceForAsset(asset.id)
                val name = when (source?.type) {
                    SourceType.LOCAL_PHOTOS -> getString(R.string.source_local_photos_title)
                    SourceType.LOCAL_FILES -> getString(R.string.source_local_files_title)
                    else -> source?.displayName
                }
                val count = env.database.frameSelectionDao().countForAsset(asset.id)
                name to count
            }
            if (photoDetailView === view) {
                view.bind(
                    asset = asset,
                    imageFile = File(File(filesDir, "originals"), asset.originalRelPath),
                    sourceName = sourceName,
                    framesCount = framesCount,
                )
            }
        }
    }

    private fun closePhotoDetail() {
        photoDetailView = null
        popOverlayTracked()
    }

    private fun showRemoveFromFrame(asset: AssetEntity) {
        val env = environment ?: return
        scope.launch {
            val frames = withContext(Dispatchers.IO) {
                env.database.frameSelectionDao().getByAsset(asset.id)
                    .mapNotNull { env.database.frameDao().getById(it.frameId) }
                    .distinctBy { it.id }
            }
            if (frames.isEmpty()) return@launch
            val names = frames.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.library_remove_from_frame)
                .setItems(names) { _, which -> confirmRemoveFromFrame(frames[which], asset) }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    private fun confirmRemoveFromFrame(frame: FrameEntity, asset: AssetEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.library_remove_from_frame)
            .setMessage(R.string.library_remove_from_frame_note)
            .setPositiveButton(R.string.library_remove_from_frame) { _, _ ->
                removeFromFrame(frame, asset, retryOnConflict = true)
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun removeFromFrame(frame: FrameEntity, asset: AssetEntity, retryOnConflict: Boolean) {
        val env = environment ?: return
        scope.launch {
            val fresh = withContext(Dispatchers.IO) {
                env.database.frameDao().getById(frame.id)
            } ?: return@launch
            val result = env.frames.save(
                frameId = fresh.id,
                baseVersion = fresh.selectionVersion,
                removeRefs = listOf(SelectionRefEdit(assetId = asset.id, sourceAssetId = null)),
            )
            when (result) {
                is SelectionSaveResult.Ok -> {
                    playerEngine?.onExternalSelectionChanged()
                    framesDirty = true
                    bindPhotoDetail(photoDetailView, asset)
                    refreshJobUi()
                }
                is SelectionSaveResult.Conflict,
                SelectionSaveResult.Rejected,
                -> {
                    if (retryOnConflict) {
                        removeFromFrame(fresh, asset, retryOnConflict = false)
                    } else {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage(R.string.error_selection_conflict_body)
                            .setPositiveButton(R.string.common_done, null)
                            .show()
                    }
                }
            }
        }
    }

    private fun confirmDeleteFromDevice(asset: AssetEntity) {
        AlertDialog.Builder(this)
            .setTitle(resources.getQuantityString(R.plurals.delete_from_device_title, 1, 1))
            .setMessage(resources.getQuantityString(R.plurals.delete_from_device_body, 1, 1))
            .setPositiveButton(R.string.common_delete) { _, _ -> deleteFromDevice(asset) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun deleteFromDevice(asset: AssetEntity) {
        val env = environment ?: return
        scope.launch {
            env.assets.deleteAssetWithTombstone(
                asset.id,
                AssetRepository.REASON_USER_DELETE,
                System.currentTimeMillis(),
            )
            playerEngine?.onExternalSelectionChanged()
            closePhotoDetail()
            framesDirty = true
            refreshJobUi()
            showUndoBar(asset.id)
        }
    }

    private fun showUndoBar(assetId: String) {
        hideUndoBar()
        val res = resources
        val inset = res.getDimensionPixelSize(R.dimen.pp_content_inset)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(getColor(R.color.pp_surface_panel))
                cornerRadius = res.getDimension(R.dimen.pp_radius_modal)
            }
            val padH = res.getDimensionPixelSize(R.dimen.pp_space_16)
            val padV = res.getDimensionPixelSize(R.dimen.pp_space_8)
            setPadding(padH, padV, padH, padV)
            val notice = TextView(this@MainActivity).apply {
                text = res.getString(R.string.delete_notice)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.pp_text_body))
                setTextColor(getColor(R.color.pp_text_primary))
                gravity = Gravity.CENTER_VERTICAL
            }
            val undo = secondaryButton(this@MainActivity, R.string.delete_undo).apply {
                setOnClickListener { undoDelete(assetId) }
            }
            addView(
                notice,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                undo,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = res.getDimensionPixelSize(R.dimen.pp_space_16)
                },
            )
        }
        undoBar = bar
        root.addFloat(
            bar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = inset
            },
        )
        val hide = Runnable { hideUndoBar() }
        undoBarHideRunnable = hide
        pollHandler.postDelayed(hide, UNDO_WINDOW_MS)
    }

    private fun hideUndoBar() {
        undoBarHideRunnable?.let { pollHandler.removeCallbacks(it) }
        undoBarHideRunnable = null
        undoBar?.let { root.removeFloat(it) }
        undoBar = null
    }

    private fun undoDelete(assetId: String) {
        val env = environment ?: return
        scope.launch {
            env.assets.restoreDeletedAsset(assetId, System.currentTimeMillis())
            hideUndoBar()
            playerEngine?.onExternalSelectionChanged()
            framesDirty = true
            Toast.makeText(this@MainActivity, R.string.delete_undone, Toast.LENGTH_SHORT).show()
            refreshJobUi()
        }
    }

    private fun confirmHideGlobally(asset: AssetEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_hide_globally)
            .setMessage(R.string.delete_hide_globally_body)
            .setPositiveButton(R.string.delete_hide_globally) { _, _ -> hideGlobally(asset) }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun hideGlobally(asset: AssetEntity) {
        val env = environment ?: return
        scope.launch {
            env.assets.hideAssetGlobally(
                asset.id,
                AssetRepository.REASON_PRIVACY_HIDE,
                System.currentTimeMillis(),
            )
            playerEngine?.onExternalSelectionChanged()
            closePhotoDetail()
            framesDirty = true
            refreshJobUi()
        }
    }

    private fun showPhotoPermissionOverlay() {
        val overlay = PhotoPermissionOverlayView(this).apply {
            onContinue = { requestPhotoPermission() }
            onRetry = { requestPhotoPermission() }
            onOpenSettings = { openAppSettings() }
            onDone = { startLocalPhotosImport() }
            onBack = { dismissPermissionOverlay() }
        }
        overlay.setState(
            when (PhotoPermissionGate.status(this)) {
                PhotoPermissionGate.Status.GRANTED -> PhotoPermissionOverlayView.State.GRANTED
                PhotoPermissionGate.Status.PARTIAL -> PhotoPermissionOverlayView.State.PARTIAL
                PhotoPermissionGate.Status.DENIED -> PhotoPermissionOverlayView.State.PROMPT
            },
        )
        permissionOverlay = overlay
        pushOverlayTracked(overlay, mapOf("type" to "permission"))
    }

    private fun dismissPermissionOverlay() {
        permissionOverlay = null
        popOverlayTracked()
    }

    private fun startLocalPhotosImport() {
        val env = environment ?: return
        dismissPermissionOverlay()
        scope.launch {
            try {
                val previous = env.localSources.ensureLocalPhotos()
                env.database.importJobDao().getBySource(previous.id).forEach { env.runner.cancel(it.id) }
                val source = env.localReferences.indexPhotos()
                finishSourceConnection(source)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                showLocalReferenceFailure()
            }
        }
    }

    private fun showLocalFilesChoices() {
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.source_local_files_choose_files), getString(R.string.source_local_files_choose_folder))) { _, choice ->
                if (choice == 0) launchFilePicker() else launchFolderPicker()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun launchFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*"))
        }
        startActivityForResult(intent, REQUEST_PICK_FILES)
    }

    private fun launchFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_PICK_FOLDER)
    }

    private fun showLocalReferenceFailure() {
        AlertDialog.Builder(this).setMessage(R.string.source_local_reference_reselect).setPositiveButton(R.string.common_close, null).show()
    }

    @Deprecated("framework-only result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_MUSIC) {
            if (resultCode == RESULT_OK) {
                data?.data?.let { importLocalMusic(it) }
            }
            return
        }
        if (requestCode == REQUEST_PICK_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val env = environment ?: return
            scope.launch {
                try {
                    val source = env.localReferences.indexTree(uri, data.flags)
                    finishSourceConnection(source)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    showLocalReferenceFailure()
                }
            }
            return
        }
        if (requestCode != REQUEST_PICK_FILES || resultCode != RESULT_OK) return
        val uris = mutableListOf<Uri>()
        data?.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                uris.add(clip.getItemAt(index).uri)
            }
        }
        data?.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        startLocalFilesImport(uris.distinct(), data?.flags ?: 0)
    }

    private fun startLocalFilesImport(uris: List<Uri>, grantFlags: Int) {
        val env = environment ?: return
        scope.launch {
            try {
                val source = env.localReferences.indexFiles(uris, grantFlags)
                val selectedUris = uris.map { it.toString() }.toSet()
                val selectedIds = withContext(Dispatchers.IO) {
                    env.database.sourceAssetDao().getBySource(source.id).filter { asset ->
                        JsonMini.parseObject(env.database.settingsKvDao().get("reference.asset.${asset.id}")?.valueJson)["uri"] in selectedUris
                    }.map { it.id }
                }
                finishSourceConnection(source, selectedIds)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                showLocalReferenceFailure()
            }
        }
    }

    private fun openImportProgress(jobId: String, showSkippedNotice: Boolean = false) {
        val env = environment ?: return
        progressView?.let { popOverlayTracked(); progressView = null }
        val view = ImportProgressView(this).apply {
            this.showSkippedNotice = showSkippedNotice
            onPauseClick = { scope.launch { env.runner.pause(jobId) } }
            onResumeClick = { scope.launch { env.runner.resume(jobId) } }
            onRetryFailedClick = { scope.launch { env.runner.retryFailed(jobId) } }
            onCancelClick = { confirmCancelJob(jobId) }
            onCloseClick = { closeImportProgress() }
            onChooseContentClick = { chooseFrameForContent() }
        }
        progressView = view
        progressJobId = jobId
        pushOverlayTracked(view, mapOf("type" to "progress", "jobId" to jobId))
        refreshJobUi()
    }

    private fun closeImportProgress() {
        progressView = null
        progressJobId = null
        popOverlayTracked()
        scope.launch {
            val completed = withContext(Dispatchers.IO) {
                environment?.settings?.isOnboardingCompleted()
            } == true
            if (completed && root.baseView !is MainContainerView) {
                showMainContainer()
            }
        }
    }

    private fun confirmCancelJob(jobId: String) {
        val env = environment ?: return
        AlertDialog.Builder(this)
            .setMessage(R.string.import_cancel_keeps_imported)
            .setPositiveButton(R.string.import_cancel_job) { _, _ ->
                scope.launch { env.runner.cancel(jobId) }
            }
            .setNeutralButton(R.string.import_cancel_and_delete) { _, _ ->
                cancelAndDeleteBatch(jobId)
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun cancelAndDeleteBatch(jobId: String) {
        val env = environment ?: return
        scope.launch {
            env.runner.cancel(jobId)
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                val collectionId = ImportCoordinator.importBatchCollectionId(jobId)
                val members = env.database.collectionAssetDao().getByCollection(collectionId)
                val originCounts = members.associate { ca ->
                    ca.assetId to env.database.assetOriginDao().getByAsset(ca.assetId).size
                }
                val deleted = members.mapNotNull {
                    env.database.assetDao().getById(it.assetId)
                }.filter { it.readiness == AssetReadiness.DELETED }
                    .map { it.id }
                    .toSet()
                BatchDeleteScope.deletable(
                    memberAssetIds = members.map { it.assetId },
                    originCountByAsset = originCounts,
                    alreadyDeleted = deleted,
                ).forEach { assetId ->
                    runCatching {
                        env.assets.deleteAssetWithTombstone(
                            assetId,
                            AssetRepository.REASON_USER_DELETE,
                            now,
                        )
                    }
                }
                env.database.collectionDao().getById(collectionId)?.let {
                    env.database.collectionDao().delete(it)
                }
                env.database.importJobDao().getById(jobId)?.let {
                    env.database.importJobDao().delete(it)
                }
            }
            playerEngine?.onExternalSelectionChanged()
            framesDirty = true
            closeImportProgress()
            refreshJobUi()
        }
    }

    private suspend fun weatherReadyNow(env: AppEnvironment): Boolean = withContext(Dispatchers.IO) {
        env.settings.rawValue(WeatherLogic.KEY_ENABLED_DEFAULT) ==
            AppSettingsRepository.VALUE_TRUE && env.weather.places().isNotEmpty()
    }

    private suspend fun weatherObservedText(env: AppEnvironment): String? {
        val placeId = env.weather.selectedPlace()?.placeId ?: return null
        val snapshot = env.weather.snapshot(placeId) ?: return null
        val date = java.util.Date(snapshot.observedAt)
        val datePart = android.text.format.DateFormat.getDateFormat(this).format(date)
        val timePart = android.text.format.DateFormat.getTimeFormat(this).format(date)
        return getString(R.string.weather_updated_at, "$datePart $timePart")
    }

    private suspend fun weatherAttributionText(env: AppEnvironment): String? {
        val placeId = env.weather.selectedPlace()?.placeId ?: return null
        val snapshot = env.weather.snapshot(placeId) ?: return null
        val text = WeatherLogic.attributionText(snapshot.attribution) ?: return null
        return getString(R.string.weather_attribution, text)
    }

    private suspend fun refreshWeatherLine() {
        val env = environment ?: return
        val engine = playerEngine
        val targetView = playerView
        if (engine == null || targetView == null) {
            weatherTextCache = null
            weatherPlaceCache = null
            weatherA11yCache = null
            return
        }
        val placeId = env.weather.selectedPlace()?.placeId
        val weatherOn = env.settings.rawValue(WeatherLogic.KEY_ENABLED_DEFAULT) ==
            AppSettingsRepository.VALUE_TRUE
        val presentation: Triple<String?, String?, String?> = if (weatherOn && placeId != null) {
            withContext(Dispatchers.IO) {
                val snapshot = env.weather.snapshot(placeId)
                val segment = snapshot?.let {
                    WeatherLogic.segmentOf(it.observedAt, System.currentTimeMillis())
                } ?: WeatherLogic.TtlSegment.EXPIRED
                if (snapshot == null || !WeatherLogic.weatherDataAllowed(snapshot.attribution) ||
                    segment == WeatherLogic.TtlSegment.EXPIRED
                ) {
                    Triple(null, null, null)
                } else {
                    val imperial = env.settings.rawValue(WeatherLogic.KEY_UNITS) ==
                        WeatherLogic.UNITS_IMPERIAL
                    val temp = getString(
                        R.string.format_temperature,
                        WeatherLogic.displayDegrees(snapshot.temperatureMilli, imperial),
                    )
                    val conditionText = WeatherLogic.conditionTextRes(snapshot.conditionCode)
                        ?.let { getString(it) }
                    val a11y = if (conditionText != null) {
                        getString(R.string.a11y_weather_overlay, temp, conditionText)
                    } else {
                        temp
                    }
                    val placeName = env.weather.places()[placeId]?.let {
                        WeatherLogic.displayName(it, weatherLang())
                    }
                    val base = if (conditionText != null) "$temp · $conditionText" else temp
                    val withIcon = "${WeatherLogic.conditionIcon(snapshot.conditionCode)} $base"
                    val text = when (segment) {
                        WeatherLogic.TtlSegment.EXPIRED -> null
                        WeatherLogic.TtlSegment.STALE ->
                            "$withIcon · ${getString(R.string.weather_delayed)}"
                        WeatherLogic.TtlSegment.FRESH -> withIcon
                    }
                    Triple(text, listOfNotNull(placeName, a11y).joinToString(", "), placeName)
                }
            }
        } else {
            Triple(null, null, null)
        }
        if (env.weather.selectedPlace()?.placeId != placeId) return
        if (playerEngine !== engine || playerView !== targetView) return
        weatherTextCache = presentation.first
        weatherA11yCache = presentation.second
        weatherPlaceCache = presentation.third
        targetView.refreshWeather()
        if (weatherOn && placeId != null && weatherRefreshJob?.isActive != true) {
            weatherRefreshJob = scope.launch {
                withContext(Dispatchers.IO) { env.weatherRefresher.refreshIfDue(placeId) }
                refreshWeatherLine()
            }
        }
    }

    private fun refreshJobUi() {        val env = environment ?: return
        scope.launch {
            val now = System.currentTimeMillis()
            if (now - lastRetryDriveMs >= RETRY_DRIVE_INTERVAL_MS) {
                lastRetryDriveMs = now
                env.runner.driveActiveJobs()
            }
            val currentProgress = progressView
            if (currentProgress != null) {
                val snapshot = progressJobId?.let { env.progress.byId(it) }
                currentProgress.render(snapshot)
            }
            activeSourcesView?.renderJobStatus(env.progress.latest())
            activeSourcesView?.let {
                refreshNasSources(it)
                checkPendingMirrorDelete()
            }
            refreshWeatherLine()
            val versionJson = withContext(Dispatchers.IO) {
                env.database.settingsKvDao().get(LibraryVersionLogic.KEY)?.valueJson
            }
            val versionChanged = versionJson != lastLibraryVersionJson
            lastLibraryVersionJson = versionJson
            val libraryView = activeLibraryView
            if (libraryView != null && versionChanged) {
                libraryView.refresh()
            }
            val framesView = activeFramesView
            if (framesView != null && (framesDirty || versionChanged)) {
                reloadFrames(framesView)
            }
            val settingsView = activeSettingsView
            if (settingsView != null && versionChanged) {
                reloadSettingsModel(settingsView)
            }
        }
    }

    private fun requestPhotoPermission() {
        requestPermissions(PhotoPermissionGate.requiredPermissions(), REQUEST_PHOTOS)
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            val action = pendingLocationAction
            pendingLocationAction = null
            if (action != null && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                action()
            } else {
                weatherSearchView?.showError(R.string.weather_unavailable)
            }
            return
        }
        if (requestCode != REQUEST_PHOTOS) return
        val overlay = permissionOverlay ?: return
        overlay.setState(
            when (PhotoPermissionGate.status(this)) {
                PhotoPermissionGate.Status.GRANTED -> PhotoPermissionOverlayView.State.GRANTED
                PhotoPermissionGate.Status.PARTIAL -> PhotoPermissionOverlayView.State.PARTIAL
                PhotoPermissionGate.Status.DENIED ->
                    if (shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES)) {
                        PhotoPermissionOverlayView.State.CANCELLED
                    } else {
                        PhotoPermissionOverlayView.State.DENIED
                    }
            },
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val base = root.baseView
        if (base is MainContainerView) {
            outState.putInt(KEY_SECTION, base.currentSection.ordinal)
        }
        if (overlayMeta.isNotEmpty()) {
            outState.putString(
                KEY_OVERLAYS,
                JsonMini.toJson(mapOf("stack" to overlayMeta.map { (view, meta) -> when (view) { is dev.photopilot.scaffold.ui.aiframes.AIFrameView -> meta + view.draftState(); is dev.photopilot.scaffold.ui.remote.RemoteFrameView -> meta + view.draftState(); is FrameEditView -> meta + view.draftState(); else -> meta } })),
            )
        }
    }

    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        val top = root.topOverlay()
        val editor = frameEditView
        if (top != null && editor != null && top === editor) {
            handleEditorBack(editor, editor.hasChanges())
            return
        }
        if (top != null && top === remoteFrameView) {
            remoteFrameView?.requestBack()
            return
        }
        val manual = manualOrderView
        if (top != null && manual != null && top === manual) {
            manual.onBack?.invoke(manual.hasUnsavedChanges())
            return
        }
        val settingsView = playbackSettingsView
        if (top != null && settingsView != null && top === settingsView) {
            handleSettingsBack(settingsView, settingsView.hasChanges())
            return
        }
        val popped = popOverlayTracked()
        if (popped != null) {
            if (popped === permissionOverlay) permissionOverlay = null
            if (popped === progressView) {
                progressView = null
                progressJobId = null
            }
            if (popped === photoDetailView) photoDetailView = null
            if (popped === holidaySettingsView) holidaySettingsView = null
            if (popped === musicPickerView) musicPickerView = null
            if (popped === playerView) {
                playerEngine?.stop(clearSnapshot = false)
                playerEngine = null
                playerView = null
            }
            if (popped === remoteFrameView) remoteFrameView = null
            if (popped === nasFormView) nasFormView = null
            if (popped === activeSourcesView) activeSourcesView = null
            return
        }
        if (sectionBackHandler?.invoke() == true) {
            return
        }
        val base = root.baseView
        if (base is MainContainerView && base.currentSection != MainSection.FRAMES) {
            base.selectSection(MainSection.FRAMES)
            return
        }
        super.onBackPressed()
    }

    private companion object {
        const val KEY_SECTION = "main_section"
        const val KEY_OVERLAYS = "overlay_stack"
        const val REQUEST_PHOTOS = 41
        const val REQUEST_PICK_FILES = 42
        const val REQUEST_PICK_FOLDER = 43
        const val REQUEST_LOCATION = 43
        const val LOCATION_FIX_TIMEOUT_MS = 10_000L
        const val GEOCODE_TIMEOUT_MS = 5_000L
        const val REQUEST_PICK_MUSIC = 44
        const val POLL_INTERVAL_MS = 1_000L
        const val UPDATE_POLL_INTERVAL_MS = 500L
        const val RETRY_DRIVE_INTERVAL_MS = 15_000L
        const val AUTO_SYNC_INTERVAL_MS = 60_000L
        val launchPresentation = LaunchPresentationState(SystemClock::elapsedRealtime)
        const val UNDO_WINDOW_MS = 10_000L
        const val SCOPE_ALL = "{}"
        const val KEY_LAN_NOTICE_SEEN = "nas.lan_notice_seen"
        val APPEARANCE_VALUES = listOf(
            AppSettingsRepository.VALUE_SYSTEM,
            AppSettingsRepository.VALUE_LIGHT,
            AppSettingsRepository.VALUE_DARK,
        )
        val LANGUAGE_VALUES = listOf(
            AppSettingsRepository.VALUE_SYSTEM,
            AppSettingsRepository.VALUE_ZH,
            AppSettingsRepository.VALUE_EN,
        )
    }
}
