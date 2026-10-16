import Foundation

final class AppEnvironment: @unchecked Sendable {
    static let defaultScopeJSON = "{}"

    let persistence: PersistenceController
    let settings: AppSettingsRepository
    let sources: SourceRepository
    let assets: AssetRepository
    let frames: FrameRepository
    let frameManager: FrameManager
    let holidayStore: HolidayStore
    let photoLibraryAdapter: PhotoLibrarySourceAdapter
    let credentialStore = CredentialStore()
    let weatherRepository: WeatherRepository
    let weatherProvider: any WeatherProvider
    let weatherRefresher: WeatherRefresher

    var tofuPins: TofuPinStore {
        TofuPinStore(settings: settings)
    }

    private(set) var runners: [String: ImportRunner] = [:]

    var originalsRootURL: URL {
        persistence.storeURL.deletingLastPathComponent()
            .appendingPathComponent("originals", isDirectory: true)
    }

    init(persistence: PersistenceController) {
        self.persistence = persistence
        self.settings = AppSettingsRepository(container: persistence.container)
        self.sources = SourceRepository(container: persistence.container)
        self.assets = AssetRepository(controller: persistence)
        self.frames = FrameRepository(controller: persistence)
        self.frameManager = FrameManager(controller: persistence)
        self.holidayStore = HolidayStore(controller: persistence)
        self.photoLibraryAdapter = PhotoLibrarySourceAdapter()
        self.weatherRepository = WeatherRepository(controller: persistence)
        self.weatherProvider = OpenMeteoProvider()
        self.weatherRefresher = WeatherRefresher(
            repository: weatherRepository,
            provider: weatherProvider
        )
    }

    func startPhotoLibraryImport() async throws -> ImportRunner {
        let sourceID = try sources.ensureLocalPhotosSource()
        let coordinator = ImportCoordinator(controller: persistence, adapter: photoLibraryAdapter)
        let job = try await coordinator.createJob(sourceID: sourceID, scopeJSON: Self.defaultScopeJSON)
        let runner = registerRunner(coordinator: coordinator, jobID: job.id)
        await runner.start()
        return runner
    }

    func startPickedFilesImport(adapter: PickedFilesSourceAdapter) async throws -> ImportRunner {
        let sourceID = try sources.ensureLocalFilesSource()
        let bootstrapCoordinator = ImportCoordinator(controller: persistence, adapter: adapter)
        let job = try await bootstrapCoordinator.createJob(
            sourceID: sourceID,
            scopeJSON: Self.defaultScopeJSON
        )
        let runnerAdapter = adapter.relocatedToJobDirectory(jobID: job.id)
        let coordinator = ImportCoordinator(controller: persistence, adapter: runnerAdapter)
        let runner = registerRunner(coordinator: coordinator, jobID: job.id)
        await runner.start()
        return runner
    }

    func openRunner(jobID: String, adapter: (any SourceAdapter)? = nil) -> ImportRunner {
        let coordinator = ImportCoordinator(
            controller: persistence,
            adapter: adapter ?? photoLibraryAdapter
        )
        return registerRunner(coordinator: coordinator, jobID: jobID)
    }

    func latestLocalPhotosJob() async throws -> ImportJobDTO? {
        let coordinator = ImportCoordinator(controller: persistence, adapter: photoLibraryAdapter)
        return try await coordinator.fetchLatestJob(sourceID: SourceRepository.localPhotosID)
    }

    func startWebDavImport(sourceID: String) async throws -> ImportRunner {
        guard let info = try sources.webdavSources().first(where: { $0.id == sourceID }),
              let endpointKey = info.endpointKey,
              let endpoint = NasAddress.parseFingerprint(endpointKey) else {
            throw ImportFailureCode.srcUnavailable
        }
        let credential = credentialStore.load(fingerprint: endpointKey)
        let urlScheme = endpoint.scheme == "webdav" ? "https" : "http"
        var components = URLComponents()
        components.scheme = urlScheme
        components.host = NasAddress.urlHost(endpoint.host)
        components.port = endpoint.port
        components.path = "/" + endpoint.path
        guard let baseURL = components.url else {
            throw ImportFailureCode.srcUnavailable
        }
        let pins = tofuPins
        let cleartextPermitted = urlScheme == "http"
            && NasTlsPolicy.isCleartextPermitted(host: endpoint.host, pinStore: pins)
        let (session, policy) = WebDavClient.makeSession(pinStore: pins)
        let client = WebDavClient(
            config: WebDavClient.Config(
                baseURL: baseURL,
                username: credential?.username ?? "",
                password: credential?.password ?? "",
                cleartextPermitted: cleartextPermitted
            ),
            session: session,
            tlsPolicy: policy
        )
        guard let scopeJSON = NasScope.scopeJSON(info.directories) else {
            throw ImportFailureCode.srcUnavailable
        }
        let adapter = WebDavSourceAdapter(
            client: client,
            scopeJSON: scopeJSON,
            recursive: info.recursive
        )
        let coordinator = ImportCoordinator(controller: persistence, adapter: adapter)
        let job = try await coordinator.createJob(sourceID: sourceID, scopeJSON: scopeJSON)
        let runner = registerRunner(coordinator: coordinator, jobID: job.id)
        await runner.start()
        return runner
    }

    func startSmbImport(sourceID: String) async throws -> ImportRunner {
        guard let info = try sources.webdavSources().first(where: { $0.id == sourceID }),
              let endpointKey = info.endpointKey,
              let endpoint = NasAddress.parseFingerprint(endpointKey) else {
            throw ImportFailureCode.srcUnavailable
        }
        let credential = credentialStore.load(fingerprint: endpointKey)
        let permitted = NasTlsPolicy.isCleartextPermitted(
            host: endpoint.host,
            pinStore: tofuPins
        )
        guard permitted else {
            throw NasTransportError.cleartextBlocked
        }
        let split = NasPath.splitShare(credential?.path ?? endpoint.path)
        let adapter = SmbSourceAdapter(
            config: SmbConfig(
                host: endpoint.host,
                port: endpoint.port,
                share: split.share,
                username: credential?.username ?? "",
                password: credential?.password ?? ""
            ),
            directories: split.subpath.isEmpty
                ? info.directories
                : [split.subpath] + info.directories,
            recursive: info.recursive
        )
        guard let scopeJSON = NasScope.scopeJSON(info.directories) else {
            throw ImportFailureCode.srcUnavailable
        }
        let coordinator = ImportCoordinator(controller: persistence, adapter: adapter)
        let job = try await coordinator.createJob(sourceID: sourceID, scopeJSON: scopeJSON)
        let runner = registerRunner(coordinator: coordinator, jobID: job.id)
        await runner.start()
        return runner
    }

    func runStartupMaintenance() async {
        _ = try? await assets.purgeExpiredDeletedAssets()
        let coordinator = ImportCoordinator(controller: persistence, adapter: photoLibraryAdapter)
        let interruptedJobs = (try? await coordinator.fetchJobs(
            withState: ImportJobState.running.rawValue
        )) ?? []
        for job in interruptedJobs {
            _ = try? await coordinator.handleSignal(jobID: job.id, signal: .autoPause)
        }
        let pausedJobs = (try? await coordinator.fetchJobs(
            withState: ImportJobState.pausedAuto.rawValue
        )) ?? []
        for job in pausedJobs {
            if job.sourceId == SourceRepository.localPhotosID {
                let runner = openRunner(jobID: job.id)
                try? await runner.resume()
            } else if let adapter = PickedFilesSourceAdapter.restore(jobID: job.id) {
                let runner = openRunner(jobID: job.id, adapter: adapter)
                try? await runner.resume()
            }
        }
    }

    static let autoSyncThrottleMillis = 60_000

    func autoSyncWebDavSources() async {
        let now = Int(Date().timeIntervalSince1970 * 1000)
        for source in (try? sources.webdavSources()) ?? [] where source.status == "active" {
            guard let endpointKey = source.endpointKey,
                  credentialStore.load(fingerprint: endpointKey) != nil else { continue }
            let key = "nas.last_auto_sync:\(source.id)"
            let last = (try? settings.value(forKey: key)).flatMap(Int.init) ?? 0
            guard now - last >= Self.autoSyncThrottleMillis else { continue }
            try? settings.setValue("\(now)", forKey: key)
            _ = try? await startWebDavImport(sourceID: source.id)
        }
    }

    private func registerRunner(coordinator: ImportCoordinator, jobID: String) -> ImportRunner {
        let runner = ImportRunner(coordinator: coordinator, jobID: jobID)
        Task {
            await runner.setOnTerminal { [weak self] finishedJobID in
                self?.runners.removeValue(forKey: finishedJobID)
            }
        }
        runners[jobID] = runner
        return runner
    }
}
