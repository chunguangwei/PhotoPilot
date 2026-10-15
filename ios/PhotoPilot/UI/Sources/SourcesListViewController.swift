import UIKit
import UniformTypeIdentifiers

final class SourcesListViewController: UIViewController, UIDocumentPickerDelegate {
    private let environment: AppEnvironment
    private let rowsStack = UIStackView()
    private var statusRow: UIControl?

    init(environment: AppEnvironment) {
        self.environment = environment
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ThemeColor.canvas

        let headerStack = UIStackView()
        headerStack.axis = .vertical
        headerStack.alignment = .fill
        headerStack.spacing = ThemeMetrics.space8
        headerStack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(headerStack)

        if navigationController != nil {
            let backRow = ScreenHeaderView()
            backRow.onBack = { [weak self] in self?.handleBack() }
            headerStack.addArrangedSubview(backRow)
        }

        let titleLabel = UILabel()
        titleLabel.text = L10n.string("sources.title")
        titleLabel.font = ThemeFont.title()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.numberOfLines = 0
        titleLabel.accessibilityTraits.insert(.header)
        headerStack.addArrangedSubview(titleLabel)

        let addLabel = UILabel()
        addLabel.text = L10n.string("sources.add")
        addLabel.font = ThemeFont.body()
        addLabel.adjustsFontForContentSizeCategory = true
        addLabel.textColor = ThemeColor.textSecondary
        addLabel.numberOfLines = 0
        headerStack.addArrangedSubview(addLabel)

        rowsStack.axis = .vertical
        rowsStack.alignment = .fill
        rowsStack.spacing = ThemeMetrics.space12
        headerStack.setCustomSpacing(ThemeMetrics.space24, after: addLabel)
        headerStack.addArrangedSubview(rowsStack)

        rowsStack.addArrangedSubview(makeRow(
            titleKey: "source.local_photos.title",
            bodyKey: "source.local_photos.body",
            action: #selector(handleLocalSource)
        ))
        rowsStack.addArrangedSubview(makeRow(
            titleKey: "source.icloud.title",
            bodyKey: "source.icloud.body.ios",
            action: #selector(handleICloudPhotos)
        ))
        rowsStack.addArrangedSubview(makeRow(
            titleKey: "source.nas.title",
            bodyKey: "source.nas.body",
            action: #selector(handleNas)
        ))

        let footerLabel = UILabel()
        footerLabel.text = L10n.string("sources.import_vs_display")
        footerLabel.font = ThemeFont.meta()
        footerLabel.adjustsFontForContentSizeCategory = true
        footerLabel.textColor = ThemeColor.textSecondary
        footerLabel.textAlignment = .center
        footerLabel.numberOfLines = 0
        footerLabel.translatesAutoresizingMaskIntoConstraints = false

        let contentStack = UIStackView(arrangedSubviews: [headerStack, footerLabel])
        contentStack.axis = .vertical
        contentStack.alignment = .fill
        contentStack.spacing = ThemeMetrics.space24
        contentStack.translatesAutoresizingMaskIntoConstraints = false

        let scrollView = UIScrollView()
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(contentStack)
        view.addSubview(scrollView)

        let readable = headerStack.widthAnchor.constraint(equalToConstant: ThemeMetrics.bodyMaxWidth)
        readable.priority = .defaultHigh
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            contentStack.topAnchor.constraint(
                equalTo: scrollView.contentLayoutGuide.topAnchor,
                constant: ThemeMetrics.space24
            ),
            contentStack.bottomAnchor.constraint(
                lessThanOrEqualTo: scrollView.contentLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space24
            ),
            contentStack.centerXAnchor.constraint(equalTo: scrollView.frameLayoutGuide.centerXAnchor),
            contentStack.leadingAnchor.constraint(
                greaterThanOrEqualTo: scrollView.frameLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInset
            ),
            contentStack.trailingAnchor.constraint(
                lessThanOrEqualTo: scrollView.frameLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            footerLabel.centerXAnchor.constraint(equalTo: contentStack.centerXAnchor),
            footerLabel.leadingAnchor.constraint(
                greaterThanOrEqualTo: contentStack.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            footerLabel.trailingAnchor.constraint(
                lessThanOrEqualTo: contentStack.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            readable
        ])
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refreshJobStatus()
        refreshNasSources()
    }

    private func refreshNasSources() {
        nasStatusViews.forEach { $0.removeFromSuperview() }
        nasStatusViews = []
        let sources = (try? environment.sources.webdavSources()) ?? []
        for source in sources {
            rowsStack.addArrangedSubview(makeNasStatusRow(source))
        }
        presentPendingDeleteConfirmIfNeeded(sources)
    }

    private var pendingAlertPresented = false

    private func presentPendingDeleteConfirmIfNeeded(_ sources: [SourceInfoDTO]) {
        guard !pendingAlertPresented else { return }
        for source in sources where source.status != "disconnected" {
            let flagged = (try? environment.settings.value(
                forKey: "nas.pending_delete:\(source.id)"
            )) == "true"
            guard flagged else { continue }
            pendingAlertPresented = true
            Task { [weak self] in
                guard let self else { return }
                let coordinator = ImportCoordinator(
                    controller: environment.persistence,
                    adapter: environment.photoLibraryAdapter
                )
                let count = (try? await coordinator.pendingDeleteCandidateCount(
                    sourceID: source.id
                )) ?? 0
                let alert = UIAlertController(
                    title: L10n.string("source.nas.mirror_delete"),
                    message: L10n.string("source.nas.mirror_delete_body")
                        + "\n"
                        + String(
                            format: L10n.string("source.nas.mirror_delete_confirm"),
                            count
                        ),
                    preferredStyle: .alert
                )
                alert.addAction(UIAlertAction(
                    title: L10n.string("delete.from_device_action"),
                    style: .destructive,
                    handler: { [weak self] _ in
                        guard let self else { return }
                        Task {
                            try? await coordinator.executePendingMirrorDelete(sourceID: source.id)
                            self.pendingAlertPresented = false
                            self.refreshNasSources()
                        }
                    }
                ))
                alert.addAction(UIAlertAction(
                    title: L10n.string("common.cancel"),
                    style: .cancel,
                    handler: { [weak self] _ in
                        guard let self else { return }
                        Task {
                            try? await coordinator.refusePendingMirrorDelete(sourceID: source.id)
                            self.pendingAlertPresented = false
                            self.refreshNasSources()
                        }
                    }
                ))
                present(alert, animated: true)
            }
            return
        }
    }

    @objc private func handleMirrorToggle(_ sender: UISwitch) {
        guard let sourceID = sender.accessibilityIdentifier else { return }
        if sender.isOn {
            let alert = UIAlertController(
                title: L10n.string("source.nas.mirror_delete"),
                message: L10n.string("source.nas.mirror_delete_body"),
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(
                title: L10n.string("common.continue"),
                style: .destructive,
                handler: { [weak self] _ in
                    try? self?.environment.sources.setWebdavMirrorDelete(
                        sourceID: sourceID,
                        enabled: true
                    )
                    self?.refreshNasSources()
                }
            ))
            alert.addAction(UIAlertAction(
                title: L10n.string("common.cancel"),
                style: .cancel,
                handler: { [weak self] _ in
                    self?.refreshNasSources()
                }
            ))
            present(alert, animated: true)
        } else {
            try? environment.sources.setWebdavMirrorDelete(sourceID: sourceID, enabled: false)
            refreshNasSources()
        }
    }

    private var nasStatusViews: [UIView] = []

    private func makeNasStatusRow(_ source: SourceInfoDTO) -> UIControl {
        let statusKey: String
        switch source.status {
        case "needs_auth":
            statusKey = "sources.status_needs_auth"
        case "unavailable":
            statusKey = "sources.status_unavailable"
        case "disconnected":
            statusKey = "sources.status_disconnected"
        default:
            statusKey = "sources.status_active"
        }

        let row = UIControl()
        row.backgroundColor = ThemeColor.panel
        row.layer.cornerRadius = ThemeMetrics.radiusGroup

        let titleLabel = UILabel()
        titleLabel.text = source.displayName
        titleLabel.font = ThemeFont.body()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.numberOfLines = 0

        let statusLabel = UILabel()
        statusLabel.text = L10n.string(statusKey)
        statusLabel.font = ThemeFont.meta()
        statusLabel.adjustsFontForContentSizeCategory = true
        statusLabel.textColor = ThemeColor.textSecondary

        let textStack = UIStackView(arrangedSubviews: [titleLabel, statusLabel])
        textStack.axis = .vertical
        textStack.spacing = ThemeMetrics.space4
        textStack.isUserInteractionEnabled = false

        let syncButton = ActionButton(titleKey: "sources.sync_now", style: .secondary)
        syncButton.addTarget(self, action: #selector(handleNasSync(_:)), for: .touchUpInside)
        syncButton.setContentHuggingPriority(.required, for: .horizontal)

        let disconnectButton = ActionButton(titleKey: "sources.disconnect", style: .destructive)
        disconnectButton.addTarget(
            self,
            action: #selector(handleNasDisconnect(_:)),
            for: .touchUpInside
        )
        disconnectButton.setContentHuggingPriority(.required, for: .horizontal)

        syncButton.accessibilityIdentifier = source.id
        disconnectButton.accessibilityIdentifier = source.id
        syncButton.isHidden = source.status == "disconnected"
        disconnectButton.isHidden = source.status == "disconnected"

        let mirrorSwitch = UISwitch()
        mirrorSwitch.isOn = source.mirrorDelete
        mirrorSwitch.onTintColor = ThemeColor.accentAction
        mirrorSwitch.accessibilityLabel = L10n.string("source.nas.mirror_delete")
        mirrorSwitch.accessibilityIdentifier = source.id
        mirrorSwitch.addTarget(self, action: #selector(handleMirrorToggle(_:)), for: .valueChanged)
        let mirrorLabel = UILabel()
        mirrorLabel.text = L10n.string("source.nas.mirror_delete")
        mirrorLabel.font = ThemeFont.meta()
        mirrorLabel.adjustsFontForContentSizeCategory = true
        mirrorLabel.textColor = ThemeColor.textSecondary
        let mirrorRow = UIStackView(arrangedSubviews: [mirrorLabel, mirrorSwitch])
        mirrorRow.axis = .horizontal
        mirrorRow.alignment = .center
        mirrorRow.spacing = ThemeMetrics.space8
        mirrorRow.isHidden = source.status == "disconnected"
        mirrorRow.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true

        let actions = UIStackView(arrangedSubviews: [syncButton, disconnectButton, mirrorRow])
        actions.axis = .vertical
        actions.spacing = ThemeMetrics.space4

        let content = UIStackView(arrangedSubviews: [textStack, actions])
        content.axis = .horizontal
        content.alignment = .center
        content.spacing = ThemeMetrics.space12
        content.translatesAutoresizingMaskIntoConstraints = false
        row.addSubview(content)
        NSLayoutConstraint.activate([
            content.topAnchor.constraint(equalTo: row.topAnchor, constant: ThemeMetrics.space12),
            content.bottomAnchor.constraint(
                equalTo: row.bottomAnchor,
                constant: -ThemeMetrics.space12
            ),
            content.leadingAnchor.constraint(
                equalTo: row.leadingAnchor,
                constant: ThemeMetrics.space16
            ),
            content.trailingAnchor.constraint(
                equalTo: row.trailingAnchor,
                constant: -ThemeMetrics.space16
            ),
            row.heightAnchor.constraint(
                greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
            ),
        ])
        nasStatusViews.append(row)
        return row
    }

    @objc private func handleNas() {
        let noticeSeen = (try? environment.settings.value(
            forKey: AppSettingsRepository.nasLanNoticeSeenKey
        )) == "true"
        let root: UIViewController = noticeSeen
            ? NasAddViewController(environment: environment)
            : NasLanNoticeViewController(environment: environment)
        let navigation = UINavigationController(rootViewController: root)
        navigation.modalPresentationStyle = .fullScreen
        present(navigation, animated: true)
    }

    @objc private func handleNasSync(_ sender: ActionButton) {
        guard let sourceID = sender.accessibilityIdentifier,
              let source = try? environment.sources.webdavSources()
                .first(where: { $0.id == sourceID }) else { return }
        Task { [weak self] in
            guard let self else { return }
            let runner: ImportRunner?
            if source.type == "smb" {
                runner = try? await environment.startSmbImport(sourceID: source.id)
            } else {
                runner = try? await environment.startWebDavImport(sourceID: source.id)
            }
            guard let runner else { return }
            show(ImportProgressViewController(runner: runner, environment: environment))
        }
    }

    @objc private func handleNasDisconnect(_ sender: ActionButton) {
        guard let sourceID = sender.accessibilityIdentifier,
              let source = try? environment.sources.webdavSources()
                .first(where: { $0.id == sourceID }) else { return }
        let sheet = UIAlertController(
            title: FormatStrings.string("sources.disconnect_confirm_title", source.displayName),
            message: L10n.string("sources.disconnect_body"),
            preferredStyle: .actionSheet
        )
        sheet.addAction(UIAlertAction(
            title: L10n.string("sources.disconnect_keep_copies"),
            style: .default,
            handler: { [weak self] _ in
                self?.disconnect(source: source, keepCopies: true)
            }
        ))
        sheet.addAction(UIAlertAction(
            title: L10n.string("delete.from_device_action"),
            style: .destructive,
            handler: { [weak self] _ in
                self?.disconnect(source: source, keepCopies: false)
            }
        ))
        sheet.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel
        ))
        sheet.popoverPresentationController?.sourceView = sender
        present(sheet, animated: true)
    }

    private func disconnect(source: SourceInfoDTO, keepCopies: Bool) {
        Task { [weak self] in
            guard let self else { return }
            try? await environment.assets.disconnectSource(
                sourceID: source.id,
                keepCopies: keepCopies
            )
            if let endpointKey = source.endpointKey {
                environment.credentialStore.delete(fingerprint: endpointKey)
            }
            refreshNasSources()
        }
    }

    private func makeRow(titleKey: String, bodyKey: String, action: Selector) -> UIControl {
        let row = UIControl()
        row.backgroundColor = ThemeColor.panel
        row.layer.cornerRadius = ThemeMetrics.radiusGroup
        row.isAccessibilityElement = true
        row.accessibilityLabel = L10n.string(titleKey)
        row.accessibilityTraits = [.button]
        row.addTarget(self, action: action, for: .touchUpInside)

        let titleLabel = UILabel()
        titleLabel.text = L10n.string(titleKey)
        titleLabel.font = ThemeFont.body()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.numberOfLines = 0

        let bodyLabel = UILabel()
        bodyLabel.text = L10n.string(bodyKey)
        bodyLabel.font = ThemeFont.meta()
        bodyLabel.adjustsFontForContentSizeCategory = true
        bodyLabel.textColor = ThemeColor.textSecondary
        bodyLabel.numberOfLines = 0

        let textStack = UIStackView(arrangedSubviews: [titleLabel, bodyLabel])
        textStack.axis = .vertical
        textStack.spacing = ThemeMetrics.space4
        textStack.isUserInteractionEnabled = false

        let chevron = UIImageView(image: UIImage(systemName: "chevron.right"))
        chevron.tintColor = ThemeColor.textSecondary
        chevron.contentMode = .scaleAspectFit
        chevron.setContentHuggingPriority(.required, for: .horizontal)
        chevron.isUserInteractionEnabled = false

        let content = UIStackView(arrangedSubviews: [textStack, chevron])
        content.axis = .horizontal
        content.alignment = .center
        content.spacing = ThemeMetrics.space12
        content.isUserInteractionEnabled = false
        content.translatesAutoresizingMaskIntoConstraints = false
        row.addSubview(content)

        NSLayoutConstraint.activate([
            content.topAnchor.constraint(equalTo: row.topAnchor, constant: ThemeMetrics.space12),
            content.bottomAnchor.constraint(equalTo: row.bottomAnchor, constant: -ThemeMetrics.space12),
            content.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: ThemeMetrics.space16),
            content.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -ThemeMetrics.space16),
            row.heightAnchor.constraint(greaterThanOrEqualToConstant: ThemeMetrics.touchTarget)
        ])
        return row
    }

    private func refreshJobStatus() {
        Task { [weak self] in
            guard let self else { return }
            let job = try? await environment.latestLocalPhotosJob()
            updateStatusRow(job)
        }
    }

    private func updateStatusRow(_ job: ImportJobDTO?) {
        statusRow?.removeFromSuperview()
        statusRow = nil
        guard let job else { return }
        let row = makeRow(
            titleKey: "import.title",
            bodyKey: Self.statusKey(for: job.state),
            action: #selector(handleOpenJob)
        )
        row.accessibilityHint = nil
        statusRow = row
        rowsStack.addArrangedSubview(row)
    }

    private static func statusKey(for state: String) -> String {
        switch state {
        case ImportJobState.running.rawValue:
            return "import.enumerating"
        case ImportJobState.paused.rawValue, ImportJobState.pausedAuto.rawValue:
            return "import.pause"
        case ImportJobState.partialFailed.rawValue, ImportJobState.failed.rawValue:
            return "import.partial_failed"
        case ImportJobState.canceled.rawValue:
            return "common.cancel"
        default:
            return "import.done"
        }
    }

    @objc private func handleBack() {
        navigationController?.popViewController(animated: true)
    }

    @objc private func handleLocalSource(_ sender: UIControl) {
        let sheet = UIAlertController(
            title: L10n.string("sources.add"),
            message: nil,
            preferredStyle: .actionSheet
        )
        sheet.addAction(UIAlertAction(
            title: L10n.string("source.local_photos.title"),
            style: .default,
            handler: { [weak self] _ in self?.handleLocalPhotos() }
        ))
        sheet.addAction(UIAlertAction(
            title: L10n.string("source.local_files.title"),
            style: .default,
            handler: { [weak self] _ in self?.handleLocalFiles() }
        ))
        sheet.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel
        ))
        sheet.popoverPresentationController?.sourceView = sender
        sheet.popoverPresentationController?.sourceRect = sender.bounds
        present(sheet, animated: true)
    }

    private func handleLocalPhotos() {
        show(PhotoPermissionViewController(environment: environment))
    }

    @objc private func handleICloudPhotos() {
        show(PhotoPermissionViewController(environment: environment))
    }

    private func handleLocalFiles() {
        let picker = UIDocumentPickerViewController(
            forOpeningContentTypes: [UTType.image],
            asCopy: true
        )
        picker.allowsMultipleSelection = true
        picker.delegate = self
        present(picker, animated: true)
    }

    @objc private func handleOpenJob() {
        Task { [weak self] in
            guard let self, let job = try? await environment.latestLocalPhotosJob() else { return }
            let runner = environment.openRunner(jobID: job.id)
            show(ImportProgressViewController(runner: runner, environment: environment))
        }
    }

    func documentPicker(
        _ controller: UIDocumentPickerViewController,
        didPickDocumentsAt urls: [URL]
    ) {
        Task { [weak self] in
            guard let self else { return }
            do {
                let staged = try await Task.detached {
                    try PickedFilesSourceAdapter.stage(urls: urls)
                }.value
                guard !staged.supported.isEmpty else { return }
                let adapter = PickedFilesSourceAdapter(
                    files: staged.supported,
                    stagingDirectory: staged.directory
                )
                let runner = try await environment.startPickedFilesImport(adapter: adapter)
                try? environment.settings.setOnboardingCompleted(true)
                show(ImportProgressViewController(
                    runner: runner,
                    environment: environment,
                    skippedUnsupported: staged.skipped
                ))
            } catch {
            }
        }
    }

    private func show(_ controller: UIViewController) {
        if let navigationController {
            navigationController.pushViewController(controller, animated: true)
        } else {
            controller.modalPresentationStyle = .fullScreen
            present(controller, animated: true)
        }
    }
}
