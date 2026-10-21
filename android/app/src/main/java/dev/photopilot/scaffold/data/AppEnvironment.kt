package dev.photopilot.scaffold.data

import android.content.Context
import dev.photopilot.scaffold.data.db.PhotoPilotDatabase
import dev.photopilot.scaffold.data.db.SourceEntity
import dev.photopilot.scaffold.data.db.SourceType
import dev.photopilot.scaffold.data.imports.ImportCoordinator
import dev.photopilot.scaffold.data.imports.ImportRunner
import dev.photopilot.scaffold.data.imports.LocalImportStore
import dev.photopilot.scaffold.data.imports.MediaStoreSourceAdapter
import dev.photopilot.scaffold.data.imports.PickedFilesSourceAdapter
import dev.photopilot.scaffold.data.imports.RoomCommitScope
import dev.photopilot.scaffold.data.imports.SmbSourceAdapter
import dev.photopilot.scaffold.data.imports.SourceAdapter
import dev.photopilot.scaffold.data.imports.WebDavSourceAdapter
import dev.photopilot.scaffold.data.logic.JsonMini
import dev.photopilot.scaffold.data.net.NasTlsPolicy
import dev.photopilot.scaffold.data.net.SmbClient
import dev.photopilot.scaffold.data.net.TofuPinStore
import dev.photopilot.scaffold.data.net.WeatherProvider
import dev.photopilot.scaffold.data.net.FallbackWeatherProvider
import dev.photopilot.scaffold.data.net.NativeGeocoderProvider
import dev.photopilot.scaffold.data.net.OpenMeteoProvider
import dev.photopilot.scaffold.data.net.WebDavClient
import dev.photopilot.scaffold.data.repo.AppSettingsRepository
import dev.photopilot.scaffold.data.repo.WeatherRepository
import dev.photopilot.scaffold.data.repo.AssetRepository
import dev.photopilot.scaffold.data.repo.DeletedAssetSweeper
import dev.photopilot.scaffold.data.repo.FrameManager
import dev.photopilot.scaffold.data.repo.FrameRepository
import dev.photopilot.scaffold.data.repo.ImportProgressRepository
import dev.photopilot.scaffold.data.repo.LibraryVersionRepository
import dev.photopilot.scaffold.data.repo.LocalSourceRegistry
import dev.photopilot.scaffold.data.secure.CredentialStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class AppEnvironment private constructor(
    val database: PhotoPilotDatabase,
    val settings: AppSettingsRepository,
    val assets: AssetRepository,
    val coordinator: ImportCoordinator,
    val localSources: LocalSourceRegistry,
    val progress: ImportProgressRepository,
    val mediaStoreAdapter: MediaStoreSourceAdapter,
    val runner: ImportRunner,
    val frames: FrameRepository,
    val frameManager: FrameManager,
    val deletedSweeper: DeletedAssetSweeper,
    val credentialStore: CredentialStore,
    val tofuPins: TofuPinStore,
    val weather: WeatherRepository,
    val weatherRefresher: WeatherRefresher,
    val weatherProvider: WeatherProvider,
    private val pickedAdapters: ConcurrentHashMap<String, PickedFilesSourceAdapter>,
    private val nasAdapters: ConcurrentHashMap<String, SourceAdapter>,
) {

    fun registerPickedAdapter(jobId: String, adapter: PickedFilesSourceAdapter) {
        pickedAdapters[jobId] = adapter
    }

    fun registerNasAdapter(jobId: String, adapter: SourceAdapter) {
        nasAdapters[jobId] = adapter
    }

    fun nasAdapterFor(source: SourceEntity): SourceAdapter? =
        buildNasAdapter(source, credentialStore, tofuPins)

    fun nasTlsPolicy(): NasTlsPolicy = newNasTlsPolicy(tofuPins)

    suspend fun weatherProviderSearch(query: String, language: String) =
        withContext(Dispatchers.IO) {
            weatherProvider.search(query, language)
        }

    private suspend fun rebuildNasAdapter(jobId: String): SourceAdapter? {
        val job = database.importJobDao().getById(jobId) ?: return null
        val source = database.sourceDao().getById(job.sourceId) ?: return null
        if (source.type != SourceType.WEBDAV) return null
        return nasAdapterFor(source)
    }

    companion object {

        private val processScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        @Volatile private var processOwner: ProcessResourceOwner<AppEnvironment>? = null

        private fun owner(context: Context): ProcessResourceOwner<AppEnvironment> =
            processOwner ?: synchronized(this) {
                processOwner ?: run {
                    val appContext = context.applicationContext
                    var database: PhotoPilotDatabase? = null
                    ProcessResourceOwner(processScope) {
                        val db = database ?: dev.photopilot.scaffold.data.db.DatabaseFactory.builder(appContext).build()
                            .also { database = it }
                        val environment = create(appContext, db)
                        try {
                            environment.runner.markInterruptedJobsRecoverable()
                            environment.deletedSweeper.sweepExpired(System.currentTimeMillis())
                            environment.runner.resumeInterruptedJobs()
                            environment
                        } catch (failure: Throwable) {
                            environment.runner.shutdownAndJoin()
                            throw failure
                        }
                    }.also { processOwner = it }
                }
            }

        suspend fun shared(context: Context): AppEnvironment = owner(context).get()

        suspend fun clearAllAndReset(context: Context): AppEnvironment {
            val appContext = context.applicationContext
            return owner(appContext).reset { environment ->
                environment.runner.shutdownAndJoin()
                LocalUserDataCleaner.clear(appContext.filesDir, appContext.cacheDir)
                environment.database.clearAllTables()
            }
        }

        internal fun newNasTlsPolicy(tofuPins: TofuPinStore): NasTlsPolicy = NasTlsPolicy(
            pinLookup = { key -> runBlocking { tofuPins.pinFor(key) } },
            cleartextHosts = { runBlocking { tofuPins.cleartextHosts() } },
        )

        internal fun buildNasAdapter(
            source: SourceEntity,
            credentialStore: CredentialStore,
            tofuPins: TofuPinStore,
        ): SourceAdapter? {
            val endpointKey = source.endpointKey ?: return null
            val credential = credentialStore.load(endpointKey) ?: return null
            val map = JsonMini.parseObject(credential)
            val protocol = map["protocol"] as? String ?: return null
            val host = map["host"] as? String ?: return null
            val port = (map["port"] as? Double)?.toInt() ?: return null
            val path = map["path"] as? String ?: ""
            val username = map["username"] as? String ?: ""
            val password = map["password"] as? String ?: ""
            val scheme = if (protocol == "webdav") "https" else "http"
            if (protocol == "smb") {
                val shareName = path.substringBefore('/').ifEmpty { return null }
                return SmbSourceAdapter(
                    share = SmbClient(host, port, shareName, username, password, tlsPolicy = newNasTlsPolicy(tofuPins)),
                    sourceFingerprint = endpointKey,
                    recursive = dev.photopilot.scaffold.data.logic.NasSourceLifecycle
                        .recursiveFromSettings(source.settingsJson),
                )
            }
            val baseUrl = "$scheme://$host:$port/$path"
            val policy = newNasTlsPolicy(tofuPins)
            return WebDavSourceAdapter(
                client = WebDavClient(
                    baseUrl = baseUrl,
                    username = username,
                    password = password,
                    tlsPolicy = policy,
                    tlsContext = policy.connectionContext(host, port),
                ),
                rootPath = path,
                sourceFingerprint = endpointKey,
                recursive = dev.photopilot.scaffold.data.logic.NasSourceLifecycle
                    .recursiveFromSettings(source.settingsJson),
            )
        }

        private suspend fun rebuildNasAdapter(
            database: PhotoPilotDatabase,
            credentialStore: CredentialStore,
            tofuPins: TofuPinStore,
            jobId: String,
        ): SourceAdapter? {
            val job = database.importJobDao().getById(jobId) ?: return null
            val source = database.sourceDao().getById(job.sourceId) ?: return null
            if (source.type != SourceType.WEBDAV && source.type != SourceType.SMB) return null
            return buildNasAdapter(source, credentialStore, tofuPins)
        }

        fun create(context: Context, database: PhotoPilotDatabase): AppEnvironment {
            val appContext = context.applicationContext
            val coordinator = ImportCoordinator(
                scope = RoomCommitScope(database),
                store = LocalImportStore(
                    originalsDir = File(appContext.filesDir, "originals"),
                    tmpDir = File(appContext.cacheDir, "import-tmp"),
                ),
            )
            val mediaStoreAdapter = MediaStoreSourceAdapter(appContext)
            val pickedAdapters = ConcurrentHashMap<String, PickedFilesSourceAdapter>()
            val nasAdapters = ConcurrentHashMap<String, SourceAdapter>()
            val tofuPins = TofuPinStore(
                getValue = { key -> AppSettingsRepository(database.settingsKvDao()).rawValue(key) },
                putValue = { key, value -> AppSettingsRepository(database.settingsKvDao()).putRaw(key, value) },
            )
            val credentialStore = CredentialStore(appContext)
            val appSettings = AppSettingsRepository(database.settingsKvDao())
            val weatherProvider: WeatherProvider = FallbackWeatherProvider(
                forecast = listOf(OpenMeteoProvider()),
                search = listOf(OpenMeteoProvider(), NativeGeocoderProvider(appContext)),
            )
            val adapterResolver: suspend (String) -> SourceAdapter = { jobId ->
                pickedAdapters[jobId]
                    ?: nasAdapters[jobId]
                    ?: PickedFilesSourceAdapter.fromDirectory(
                        File(appContext.cacheDir, "picked/$jobId"),
                    )
                    ?: rebuildNasAdapter(database, credentialStore, tofuPins, jobId)
                    ?: mediaStoreAdapter
            }
            return AppEnvironment(
                database = database,
                settings = AppSettingsRepository(database.settingsKvDao()),
                assets = AssetRepository(
                    database,
                    LibraryVersionRepository(database.settingsKvDao()),
                ),
                coordinator = coordinator,
                localSources = LocalSourceRegistry(
                    database,
                    File(appContext.filesDir, "originals"),
                    coordinator,
                ),
                progress = ImportProgressRepository(database),
                mediaStoreAdapter = mediaStoreAdapter,
                runner = ImportRunner(
                    database,
                    coordinator,
                    adapterResolver,
                    networkAllowed = {
                        val manager = appContext.getSystemService(android.net.ConnectivityManager::class.java)
                        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
                        val wifiOnly = kotlinx.coroutines.runBlocking {
                            appSettings.isWifiOnly()
                        }
                        dev.photopilot.scaffold.data.imports.NasNetworkPolicy.allows(
                            capabilities != null,
                            capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
                            wifiOnly,
                        )
                    },
                ).apply {
                    activeDriverListener = { count ->
                        if (count > 0) {
                            dev.photopilot.scaffold.data.imports.ImportKeepAliveService.start(appContext)
                        } else {
                            dev.photopilot.scaffold.data.imports.ImportKeepAliveService.stop(appContext)
                        }
                    }
                },
                frames = FrameRepository(
                    database,
                    LibraryVersionRepository(database.settingsKvDao()),
                    File(appContext.filesDir, "originals"),
                ),
                frameManager = FrameManager(database),
                deletedSweeper = DeletedAssetSweeper(
                    database,
                    File(appContext.filesDir, "originals"),
                    LibraryVersionRepository(database.settingsKvDao()),
                ),
                credentialStore = credentialStore,
                tofuPins = tofuPins,
                weather = WeatherRepository(database, appSettings),
                weatherRefresher = WeatherRefresher(
                    provider = weatherProvider,
                    repository = WeatherRepository(database, appSettings),
                    online = {
                        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                            as? android.net.ConnectivityManager
                        manager?.activeNetworkInfo?.isConnected == true
                    },
                ),
                weatherProvider = weatherProvider,
                pickedAdapters = pickedAdapters,
                nasAdapters = nasAdapters,
            )
        }
    }
}
