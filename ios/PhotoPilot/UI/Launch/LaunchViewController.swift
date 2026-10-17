import UIKit

enum RootTransition {
    static let crossfadeDuration: TimeInterval = 0.3

    static func setRoot(_ controller: UIViewController, in window: UIWindow) {
        guard !UIAccessibility.isReduceMotionEnabled else {
            window.rootViewController = controller
            return
        }
        UIView.transition(
            with: window,
            duration: crossfadeDuration,
            options: .transitionCrossDissolve,
            animations: {
                window.rootViewController = controller
            }
        )
    }
}

final class LaunchViewController: UIViewController {
    private static let slowNoticeDelay: TimeInterval = 3

    private let presentationSession = LaunchPresentationSession.shared
    private var readiness = LaunchReadinessState()
    private var brandingView: LaunchBrandingView?
    private var brandingTask: Task<Void, Never>?
    private var loadedLibrary: (PersistenceController, Bool, String?)?
    private var loadStartedAt: TimeInterval?
    private var isVisible = false
    private var contentStack: UIStackView?
    private var contentScroll: UIScrollView?
    private var slowNoticeTask: Task<Void, Never>?
    private var loadTask: Task<Void, Never>?
    private var lastErrorCode: String?
    private var lastErrorType: String?

    private var now: TimeInterval { ProcessInfo.processInfo.systemUptime }

    private var isActive: Bool {
        guard UIApplication.shared.applicationState == .active else { return false }
        return view.window?.windowScene?.activationState == .foregroundActive
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ThemeColor.canvas
        presentationSession.beginIfNeeded(now: now)
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(refreshPresentation), name: LaunchPresentationSession.didChangeNotification, object: presentationSession)
        center.addObserver(self, selector: #selector(refreshPresentation), name: UIApplication.didBecomeActiveNotification, object: nil)
        center.addObserver(self, selector: #selector(refreshPresentation), name: UIScene.didActivateNotification, object: nil)
        center.addObserver(self, selector: #selector(suspendPresentation), name: UIApplication.willResignActiveNotification, object: nil)
        center.addObserver(self, selector: #selector(sceneWillDeactivate(_:)), name: UIScene.willDeactivateNotification, object: nil)
        center.addObserver(self, selector: #selector(refreshPresentation), name: UIAccessibility.reduceMotionStatusDidChangeNotification, object: nil)
        startLoad()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        isVisible = true
        refreshPresentation()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        isVisible = false
        suspendPresentation()
    }

    deinit {
        brandingTask?.cancel()
        slowNoticeTask?.cancel()
        loadTask?.cancel()
        NotificationCenter.default.removeObserver(self)
    }

    private func startLoad() {
        guard readiness.beginLoading() else { return }
        loadStartedAt = now
        loadedLibrary = nil
        showLoading()
        refreshPresentation()
        loadTask = Task { [weak self] in
            let result: Result<(PersistenceController, Bool, String?), Error> = await Task.detached {
                do {
                    if CommandLine.arguments.contains("-uitest.reset") {
                        let storeURL = PersistenceController.defaultStoreURL()
                        for suffix in ["", "-wal", "-shm"] {
                            try? FileManager.default.removeItem(
                                at: URL(fileURLWithPath: storeURL.path + suffix)
                            )
                        }
                    }
                    let persistence = PersistenceController()
                    guard !persistence.container.persistentStoreCoordinator.persistentStores.isEmpty else {
                        throw PersistenceError.dbMigrationFailed
                    }
                    let settings = AppSettingsRepository(container: persistence.container)
                    let completed = try settings.isOnboardingCompleted()
                    var autoPlayFrameID: String?
                    if completed,
                       (try settings.value(forKey: AppSettingsRepository.playbackHasPlayedOnceKey)) == "true",
                       (try settings.value(forKey: AppSettingsRepository.playbackAutoPlayKey)) != "false",
                       let lastFrameID = try settings.value(
                           forKey: AppSettingsRepository.playbackLastFrameIDKey
                       ),
                       !lastFrameID.isEmpty {
                        let manager = FrameManager(controller: persistence)
                        if (try? await manager.frameExists(frameID: lastFrameID)) == true {
                            autoPlayFrameID = lastFrameID
                        }
                    }
                    return .success((persistence, completed, autoPlayFrameID))
                } catch {
                    return .failure(error)
                }
            }.value
            guard !Task.isCancelled, let self else { return }
            self.slowNoticeTask?.cancel()
            self.slowNoticeTask = nil
            self.loadTask = nil
            switch result {
            case .success(let loaded):
                self.loadedLibrary = loaded
                self.readiness.completeLoading()
                self.refreshPresentation()
            case .failure(let error):
                self.showFailure(error)
            }
        }
    }

    private func showLoading() {
        contentStack?.removeFromSuperview()
        contentScroll?.removeFromSuperview()
        contentStack = nil
        contentScroll = nil
        brandingView?.removeFromSuperview()
        let branding = LaunchBrandingView()
        branding.translatesAutoresizingMaskIntoConstraints = false
        branding.skipButton.addTarget(self, action: #selector(handleSkip), for: .touchUpInside)
        view.addSubview(branding)
        NSLayoutConstraint.activate([
            branding.topAnchor.constraint(equalTo: view.topAnchor),
            branding.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            branding.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            branding.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])
        brandingView = branding
    }

    @objc private func refreshPresentation() {
        guard readiness.phase != .failed, readiness.phase != .routed else { return }
        brandingTask?.cancel()
        brandingTask = nil
        slowNoticeTask?.cancel()
        slowNoticeTask = nil
        let currentTime = now
        let state = presentationSession.state
        if state.startedAt != nil, !state.hasEnded, !state.isPresenting(now: currentTime) {
            presentationSession.finish()
            return
        }
        let elapsed = currentTime - (loadStartedAt ?? currentTime)
        brandingView?.updateStatus(isLoading: readiness.phase == .loading, isSlow: elapsed >= Self.slowNoticeDelay)
        let canAnimate = isVisible && isActive
        brandingView?.updatePresentation(state, now: currentTime, animate: canAnimate)
        if canAnimate {
            let remaining = state.remaining(now: currentTime)
            if remaining > 0 {
                brandingTask = Task { [weak self] in
                    try? await Task.sleep(nanoseconds: UInt64(min(remaining, 1) * 1_000_000_000))
                    guard !Task.isCancelled else { return }
                    self?.refreshPresentation()
                }
            }
            if readiness.phase == .loading, elapsed < Self.slowNoticeDelay {
                let delay = Self.slowNoticeDelay - elapsed
                slowNoticeTask = Task { [weak self] in
                    try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                    guard !Task.isCancelled else { return }
                    self?.refreshPresentation()
                }
            }
        }
        tryRoute()
    }

    @objc private func sceneWillDeactivate(_ notification: Notification) {
        guard let scene = notification.object as? UIScene, scene === view.window?.windowScene else { return }
        suspendPresentation()
    }

    @objc private func suspendPresentation() {
        brandingTask?.cancel()
        brandingTask = nil
        slowNoticeTask?.cancel()
        slowNoticeTask = nil
        brandingView?.stopAnimations()
    }

    @objc private func handleSkip() {
        presentationSession.skip()
    }

    private func tryRoute() {
        guard readiness.canRoute(
            presentation: presentationSession.state,
            now: now,
            isVisible: isVisible && view.window != nil && presentedViewController == nil,
            isActive: isActive
        ), let loaded = loadedLibrary else { return }
        readiness.markRouted()
        loadedLibrary = nil
        suspendPresentation()
        route(persistence: loaded.0, onboardingCompleted: loaded.1, autoPlayFrameID: loaded.2)
    }

    private func showFailure(_ error: Error) {
        readiness.failLoading()
        loadedLibrary = nil
        suspendPresentation()
        presentationSession.finish()
        brandingView?.removeFromSuperview()
        brandingView = nil
        if let persistenceError = error as? PersistenceError {
            lastErrorCode = persistenceError.rawValue
        } else {
            lastErrorCode = nil
        }
        lastErrorType = String(describing: type(of: error))

        let titleLabel = UILabel()
        titleLabel.text = L10n.string("launch.migration_failed_title")
        titleLabel.font = ThemeFont.heading()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 0
        titleLabel.accessibilityTraits.insert(.header)

        let bodyLabel = UILabel()
        bodyLabel.text = L10n.string("launch.migration_failed_body")
        bodyLabel.font = ThemeFont.body()
        bodyLabel.adjustsFontForContentSizeCategory = true
        bodyLabel.textColor = ThemeColor.textSecondary
        bodyLabel.textAlignment = .center
        bodyLabel.numberOfLines = 0

        let retryButton = ActionButton(titleKey: "common.retry", style: .primary)
        retryButton.addTarget(self, action: #selector(handleRetry), for: .touchUpInside)

        let exportButton = ActionButton(titleKey: "settings.export_diagnostics", style: .secondary)
        exportButton.addTarget(self, action: #selector(handleExport(_:)), for: .touchUpInside)

        setContent([titleLabel, bodyLabel, retryButton, exportButton])
    }

    private func setContent(_ arranged: [UIView]) {
        contentStack?.removeFromSuperview()
        contentScroll?.removeFromSuperview()
        let stack = UIStackView(arrangedSubviews: arranged)
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = ThemeMetrics.space16
        stack.translatesAutoresizingMaskIntoConstraints = false
        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(stack)
        view.addSubview(scroll)
        contentScroll = scroll
        let readable = stack.widthAnchor.constraint(equalToConstant: ThemeMetrics.bodyMaxWidth)
        readable.priority = .defaultHigh
        let verticalCenter = stack.centerYAnchor.constraint(equalTo: scroll.frameLayoutGuide.centerYAnchor)
        verticalCenter.priority = .defaultLow
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            stack.topAnchor.constraint(
                greaterThanOrEqualTo: scroll.contentLayoutGuide.topAnchor,
                constant: ThemeMetrics.space24
            ),
            stack.bottomAnchor.constraint(
                lessThanOrEqualTo: scroll.contentLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space24
            ),
            stack.centerXAnchor.constraint(equalTo: scroll.frameLayoutGuide.centerXAnchor),
            stack.leadingAnchor.constraint(
                greaterThanOrEqualTo: scroll.frameLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            stack.trailingAnchor.constraint(
                lessThanOrEqualTo: scroll.frameLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            readable,
            verticalCenter
        ])
        contentStack = stack
    }

    private func route(
        persistence: PersistenceController,
        onboardingCompleted: Bool,
        autoPlayFrameID: String?
    ) {
        guard let window = view.window else { return }
        let environment = AppEnvironment(persistence: persistence)
        let root: UIViewController
        if onboardingCompleted {
            let main = MainContainerViewController(environment: environment)
            main.autoPlayFrameID = autoPlayFrameID
            root = main
        } else {
            let navigation = UINavigationController(
                rootViewController: WelcomeViewController(environment: environment)
            )
            navigation.setNavigationBarHidden(true, animated: false)
            root = navigation
        }
        if let appearance = try? environment.settings.value(forKey: AppSettingsRepository.appearanceKey) {
            AppearanceApplier.apply(appearance)
        }
        if let language = try? environment.settings.value(forKey: AppSettingsRepository.languageKey) {
            AppLanguage.configure(value: language)
        }
        RootTransition.setRoot(root, in: window)
        if persistence.didRecreateStore {
            let alert = UIAlertController(
                title: L10n.string("launch.store_recovered_title"),
                message: L10n.string("launch.store_recovered_body"),
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(
                title: L10n.string("common.continue"),
                style: .default
            ))
            window.rootViewController?.present(alert, animated: true)
        }
        Task {
            await environment.runStartupMaintenance()
        }
    }

    @objc private func handleRetry() {
        startLoad()
    }

    @objc private func handleExport(_ sender: ActionButton) {
        guard let url = try? DiagnosticsExporter.writeToTemporaryFile(
            errorCode: lastErrorCode,
            errorType: lastErrorType
        ) else { return }
        let activity = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        activity.popoverPresentationController?.sourceView = sender
        present(activity, animated: true)
    }
}
