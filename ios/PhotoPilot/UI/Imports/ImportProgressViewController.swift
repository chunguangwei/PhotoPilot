import UIKit

final class ImportProgressViewController: UIViewController {
    private static let pollIntervalNanos: UInt64 = 1_000_000_000

    private let runner: ImportRunner
    private let environment: AppEnvironment
    private let skippedUnsupported: Int
    private var pollTask: Task<Void, Never>?

    private let statusLabel = UILabel()
    private let progressView = UIProgressView(progressViewStyle: .default)
    private let activityIndicator = UIActivityIndicatorView(style: .medium)
    private let summaryLabel = UILabel()
    private let sizeLabel = UILabel()
    private let spaceBanner = UILabel()
    private let videoSkippedBanner = UILabel()
    private let partialBanner = UILabel()
    private let pauseButton = ActionButton(titleKey: "import.pause", style: .secondary)
    private let resumeButton = ActionButton(titleKey: "import.resume", style: .primary)
    private let retryButton = ActionButton(titleKey: "import.retry_failed_only", style: .primary)
    private let cancelButton = ActionButton(titleKey: "import.cancel_job", style: .secondary)
    private let doneButton = ActionButton(titleKey: "common.done", style: .primary)
    private let chooseContentButton = ActionButton(titleKey: "frames.choose_content", style: .primary)

    init(runner: ImportRunner, environment: AppEnvironment, skippedUnsupported: Int = 0) {
        self.runner = runner
        self.environment = environment
        self.skippedUnsupported = skippedUnsupported
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        enableEdgeSwipeBack()
        view.backgroundColor = ThemeColor.canvas

        let backButton = ActionButton(titleKey: "common.back", style: .secondary)
        backButton.addTarget(self, action: #selector(handleBack), for: .touchUpInside)
        let backRow = UIStackView(arrangedSubviews: [backButton, UIView()])
        backRow.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(backRow)

        let titleLabel = UILabel()
        titleLabel.text = L10n.string("import.title")
        titleLabel.font = ThemeFont.heading()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.textAlignment = .center
        titleLabel.accessibilityTraits.insert(.header)

        configure(statusLabel, font: ThemeFont.body(), color: ThemeColor.textSecondary)
        configure(summaryLabel, font: ThemeFont.body(), color: ThemeColor.textPrimary)
        configure(sizeLabel, font: ThemeFont.meta(), color: ThemeColor.textSecondary)
        configure(spaceBanner, font: ThemeFont.meta(), color: ThemeColor.statusWarning)
        configure(videoSkippedBanner, font: ThemeFont.meta(), color: ThemeColor.statusWarning)
        configure(partialBanner, font: ThemeFont.meta(), color: ThemeColor.statusWarning)

        statusLabel.text = L10n.string("import.preparing_unknown_total")
        sizeLabel.text = L10n.string("import.size_calculating")
        sizeLabel.isHidden = true
        spaceBanner.text = L10n.string("import.waiting_space")
        videoSkippedBanner.text = L10n.string("import.video_skipped")
        partialBanner.text = L10n.string("import.partial_failed")

        activityIndicator.hidesWhenStopped = true
        activityIndicator.startAnimating()

        pauseButton.addTarget(self, action: #selector(handlePause), for: .touchUpInside)
        resumeButton.addTarget(self, action: #selector(handleResume), for: .touchUpInside)
        retryButton.addTarget(self, action: #selector(handleRetryFailed), for: .touchUpInside)
        cancelButton.addTarget(self, action: #selector(handleCancel), for: .touchUpInside)
        doneButton.addTarget(self, action: #selector(handleDone), for: .touchUpInside)
        chooseContentButton.addTarget(self, action: #selector(handleChooseContent), for: .touchUpInside)
        chooseContentButton.isHidden = true

        let stack = UIStackView(arrangedSubviews: [
            titleLabel,
            statusLabel,
            progressView,
            activityIndicator,
            summaryLabel,
            sizeLabel,
            spaceBanner,
            videoSkippedBanner,
            partialBanner,
            pauseButton,
            resumeButton,
            retryButton,
            cancelButton,
            chooseContentButton,
            doneButton,
        ])
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = ThemeMetrics.space12
        stack.translatesAutoresizingMaskIntoConstraints = false

        let scrollView = UIScrollView()
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(stack)
        view.addSubview(scrollView)

        let readable = stack.widthAnchor.constraint(
            lessThanOrEqualToConstant: ThemeMetrics.bodyMaxWidth
        )
        let fillWidth = stack.widthAnchor.constraint(
            equalTo: scrollView.frameLayoutGuide.widthAnchor,
            constant: -2 * ThemeMetrics.contentInsetCompact
        )
        fillWidth.priority = .defaultHigh
        let verticalCenter = stack.centerYAnchor.constraint(equalTo: scrollView.frameLayoutGuide.centerYAnchor)
        verticalCenter.priority = .defaultLow
        NSLayoutConstraint.activate([
            backRow.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            backRow.leadingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            backRow.trailingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            scrollView.topAnchor.constraint(equalTo: backRow.bottomAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            stack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            stack.bottomAnchor.constraint(
                lessThanOrEqualTo: scrollView.contentLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space24
            ),
            stack.centerXAnchor.constraint(equalTo: scrollView.frameLayoutGuide.centerXAnchor),
            stack.leadingAnchor.constraint(
                greaterThanOrEqualTo: scrollView.frameLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInset
            ),
            stack.trailingAnchor.constraint(
                lessThanOrEqualTo: scrollView.frameLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            readable,
            fillWidth,
            verticalCenter
        ])
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        UIApplication.shared.isIdleTimerDisabled = true
        startPolling()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        UIApplication.shared.isIdleTimerDisabled = false
        pollTask?.cancel()
        pollTask = nil
    }

    private func configure(_ label: UILabel, font: UIFont, color: UIColor) {
        label.font = font
        label.adjustsFontForContentSizeCategory = true
        label.textColor = color
        label.textAlignment = .center
        label.numberOfLines = 0
    }

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                try? await Task.sleep(nanoseconds: Self.pollIntervalNanos)
            }
        }
    }

    private func refresh() async {
        guard let job = try? await runner.job() else { return }
        let driving = await runner.isDriving
        update(with: job, driving: driving)
    }

    private func update(with job: ImportJobDTO, driving: Bool) {
        let counts = job.counts
        let imported = counts[ImportItemState.imported.rawValue] ?? 0
        let failed = counts[ImportItemState.failed.rawValue] ?? 0
        let skipped = counts[ImportItemState.skipped.rawValue] ?? 0
        let canceled = counts[ImportItemState.canceled.rawValue] ?? 0
        let waitingSpace = counts[ImportItemState.waitingSpace.rawValue] ?? 0
        let enqueued = counts.values.reduce(0, +)
        let total = job.totalKnown ?? enqueued
        let processed = imported + failed + skipped + canceled

        let enumerationDone = job.cursor == ImportCoordinator.enumerationDoneCursor
        if !enumerationDone {
            statusLabel.text = L10n.string(total > 0 ? "import.enumerating" : "import.preparing_unknown_total")
        } else if job.state == ImportJobState.completed.rawValue {
            statusLabel.text = imported == 0 && total > 0
                ? String(format: L10n.string("import.no_new_items"), total)
                : L10n.string("import.done")
        } else {
            statusLabel.text = L10n.string("import.enumerating")
        }

        sizeLabel.isHidden = job.state != ImportJobState.running.rawValue

        if job.totalKnown == nil, !enumerationDone {
            progressView.isHidden = true
            activityIndicator.startAnimating()
        } else {
            activityIndicator.stopAnimating()
            progressView.isHidden = false
            progressView.progress = total > 0 ? Float(processed) / Float(total) : 0
        }

        summaryLabel.text = String(
            format: L10n.string("import.summary"),
            imported,
            total,
            imported,
            failed
        )

        spaceBanner.isHidden = waitingSpace == 0
        videoSkippedBanner.isHidden = skippedUnsupported == 0

        let isRunning = job.state == ImportJobState.running.rawValue
        let isPaused = job.state == ImportJobState.paused.rawValue
            || job.state == ImportJobState.pausedAuto.rawValue
        let isPartialFailed = job.state == ImportJobState.partialFailed.rawValue
            || job.state == ImportJobState.failed.rawValue
        let isTerminal = job.state == ImportJobState.completed.rawValue
            || job.state == ImportJobState.canceled.rawValue

        partialBanner.isHidden = !isPartialFailed
        pauseButton.isHidden = !isRunning || !driving
        resumeButton.isHidden = !isPaused && !(isRunning && !driving)
        retryButton.isHidden = !isPartialFailed
        cancelButton.isHidden = isTerminal || isPartialFailed
        chooseContentButton.isHidden = job.state != ImportJobState.completed.rawValue
        doneButton.isHidden = !isTerminal && !isPartialFailed
    }

    @objc private func handlePause() {
        Task { try? await runner.pause() }
    }

    @objc private func handleResume() {
        Task { try? await runner.resume() }
    }

    @objc private func handleRetryFailed() {
        Task { try? await runner.retryFailed() }
    }

    @objc private func handleCancel() {
        let alert = UIAlertController(
            title: L10n.string("import.cancel_job"),
            message: L10n.string("import.cancel_keeps_imported"),
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(
            title: L10n.string("import.cancel_job"),
            style: .destructive,
            handler: { [weak self] _ in
                guard let self else { return }
                Task { try? await self.runner.cancel() }
            }
        ))
        alert.addAction(UIAlertAction(
            title: L10n.string("import.cancel_and_delete"),
            style: .destructive,
            handler: { [weak self] _ in
                self?.confirmCancelAndDelete()
            }
        ))
        alert.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel
        ))
        present(alert, animated: true)
    }

    private func confirmCancelAndDelete() {
        Task { [weak self] in
            guard let self else { return }
            let memberIDs = (try? await runner.batchMemberAssetIDs()) ?? []
            let frames = (try? await environment.assets.affectedFrameCount(assetIDs: memberIDs)) ?? 0
            let alert = UIAlertController(
                title: L10n.string("import.cancel_and_delete"),
                message: String(
                    format: L10n.string("delete.from_device_body"),
                    frames
                ),
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(
                title: L10n.string("import.cancel_and_delete"),
                style: .destructive,
                handler: { [weak self] _ in
                    guard let self else { return }
                    Task {
                        try? await self.runner.cancelAndDelete()
                        self.close()
                    }
                }
            ))
            alert.addAction(UIAlertAction(
                title: L10n.string("common.cancel"),
                style: .cancel
            ))
            present(alert, animated: true)
        }
    }

    @objc private func handleDone() {
        close()
    }

    @objc private func handleChooseContent() {
        Task { [weak self] in
            guard let self else { return }
            let cards = (try? await environment.frameManager.frameCards()) ?? []
            if cards.isEmpty {
                presentEditor(frameID: nil)
                return
            }
            let alert = UIAlertController(
                title: L10n.string("import.pick_frame_title"),
                message: nil,
                preferredStyle: .actionSheet
            )
            for card in cards {
                alert.addAction(UIAlertAction(title: card.name, style: .default) { [weak self] _ in
                    self?.presentEditor(frameID: card.id)
                })
            }
            alert.addAction(UIAlertAction(
                title: L10n.string("import.pick_frame_new"),
                style: .default
            ) { [weak self] _ in
                self?.presentEditor(frameID: nil)
            })
            alert.addAction(UIAlertAction(title: L10n.string("common.cancel"), style: .cancel))
            alert.popoverPresentationController?.sourceView = chooseContentButton
            alert.popoverPresentationController?.sourceRect = chooseContentButton.bounds
            present(alert, animated: true)
        }
    }

    private func presentEditor(frameID: String?) {
        let editor = FrameEditViewController(
            environment: environment,
            frameID: frameID,
            routeToMainAfterSave: true
        )
        editor.modalPresentationStyle = .fullScreen
        present(editor, animated: true)
    }

    @objc private func handleBack() {
        close()
    }

    private func close() {
        if let navigationController, navigationController.viewControllers.first != self {
            navigationController.popViewController(animated: true)
        } else {
            dismiss(animated: true)
        }
    }
}
