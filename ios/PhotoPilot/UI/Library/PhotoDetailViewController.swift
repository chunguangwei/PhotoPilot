import UIKit

final class PhotoDetailViewController: UIViewController {
    private static let maxDecodePixels: Int64 = 4096

    private let environment: AppEnvironment
    private let assetID: String
    private var currentAsset: AssetDTO?
    private var lastDecodeSize: CGSize = .zero

    private let imageView = UIImageView()
    private let metadataStack = UIStackView()
    private let unavailableLabel = UILabel()
    private let retryButton = ActionButton(titleKey: "common.retry", style: .primary)
    private let actionsStack = UIStackView()
    private let addToFrameButton = ActionButton(titleKey: "library.add_to_frame", style: .secondary)
    private let removeFromFrameButton = ActionButton(titleKey: "library.remove_from_frame", style: .secondary)
    private let hideGloballyButton = ActionButton(titleKey: "delete.hide_globally", style: .secondary)
    private let deleteButton = ActionButton(titleKey: "delete.from_device_action", style: .destructive)
    private let undoBar = UIStackView()
    private let undoLabel = UILabel()
    private let undoButton = ActionButton(titleKey: "delete.undo", style: .primary)
    private var undoTask: Task<Void, Never>?
    private var assetDeleted = false
    private var loadTask: Task<Void, Never>?

    init(environment: AppEnvironment, assetID: String) {
        self.environment = environment
        self.assetID = assetID
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

        let backRow = ScreenHeaderView()
        backRow.onBack = { [weak self] in self?.handleBack() }
        backRow.pinToTop(of: view)

        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true
        imageView.isAccessibilityElement = true
        imageView.accessibilityLabel = L10n.string("library.photo_detail")
        imageView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(imageView)

        unavailableLabel.text = L10n.string("library.detail_unavailable")
        unavailableLabel.font = ThemeFont.body()
        unavailableLabel.adjustsFontForContentSizeCategory = true
        unavailableLabel.textColor = ThemeColor.textSecondary
        unavailableLabel.textAlignment = .center
        unavailableLabel.numberOfLines = 0
        unavailableLabel.isHidden = true
        unavailableLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(unavailableLabel)

        retryButton.addTarget(self, action: #selector(handleRetry), for: .touchUpInside)
        retryButton.isHidden = true
        retryButton.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(retryButton)

        metadataStack.axis = .vertical
        metadataStack.spacing = ThemeMetrics.space12
        metadataStack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(metadataStack)

        addToFrameButton.addTarget(self, action: #selector(handleAddToFrame), for: .touchUpInside)
        removeFromFrameButton.addTarget(self, action: #selector(handleRemoveFromFrame), for: .touchUpInside)
        hideGloballyButton.addTarget(self, action: #selector(handleHideGlobally), for: .touchUpInside)
        deleteButton.addTarget(self, action: #selector(handleDelete), for: .touchUpInside)
        actionsStack.axis = .vertical
        actionsStack.spacing = ThemeMetrics.space8
        actionsStack.addArrangedSubview(addToFrameButton)
        actionsStack.addArrangedSubview(removeFromFrameButton)
        actionsStack.addArrangedSubview(hideGloballyButton)
        actionsStack.addArrangedSubview(deleteButton)
        actionsStack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(actionsStack)

        undoLabel.font = ThemeFont.meta()
        undoLabel.adjustsFontForContentSizeCategory = true
        undoLabel.textColor = ThemeColor.textSecondary
        undoButton.addTarget(self, action: #selector(handleUndo), for: .touchUpInside)
        undoButton.setContentHuggingPriority(.required, for: .horizontal)
        undoBar.axis = .horizontal
        undoBar.alignment = .center
        undoBar.spacing = ThemeMetrics.space12
        undoBar.addArrangedSubview(undoLabel)
        undoBar.addArrangedSubview(undoButton)
        undoBar.isHidden = true
        undoBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(undoBar)

        let readable = metadataStack.widthAnchor.constraint(equalToConstant: ThemeMetrics.bodyMaxWidth)
        readable.priority = .defaultHigh
        NSLayoutConstraint.activate([
            imageView.topAnchor.constraint(
                equalTo: backRow.bottomAnchor,
                constant: ThemeMetrics.space8
            ),
            imageView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            imageView.bottomAnchor.constraint(
                equalTo: metadataStack.topAnchor,
                constant: -ThemeMetrics.space16
            ),
            unavailableLabel.centerXAnchor.constraint(equalTo: imageView.centerXAnchor),
            unavailableLabel.centerYAnchor.constraint(equalTo: imageView.centerYAnchor),
            retryButton.centerXAnchor.constraint(equalTo: imageView.centerXAnchor),
            retryButton.topAnchor.constraint(
                equalTo: unavailableLabel.bottomAnchor,
                constant: ThemeMetrics.space16
            ),
            metadataStack.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            metadataStack.leadingAnchor.constraint(
                greaterThanOrEqualTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInset
            ),
            metadataStack.trailingAnchor.constraint(
                lessThanOrEqualTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            readable,
            metadataStack.bottomAnchor.constraint(
                equalTo: actionsStack.topAnchor,
                constant: -ThemeMetrics.space16
            ),
            actionsStack.centerXAnchor.constraint(equalTo: metadataStack.centerXAnchor),
            actionsStack.widthAnchor.constraint(equalTo: metadataStack.widthAnchor),
            actionsStack.bottomAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space16
            ),
            undoBar.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            undoBar.bottomAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space16
            ),
        ])

        load()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        loadTask?.cancel()
    }

    private func load() {
        loadTask?.cancel()
        loadTask = Task { [weak self] in
            guard let self else { return }
            guard let asset = try? await environment.assets.fetchAsset(id: assetID) else {
                showUnavailable()
                return
            }
            currentAsset = asset
            await renderMetadata(asset)
            await renderImage(asset)
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard let asset = currentAsset else { return }
        let size = imageView.bounds.size
        guard size.width > 0, size.height > 0 else { return }
        let previous = max(lastDecodeSize.width, lastDecodeSize.height)
        let current = max(size.width, size.height)
        guard previous == 0 || abs(current - previous) / previous > 0.25 else { return }
        lastDecodeSize = size
        Task { [weak self] in
            await self?.renderImage(asset)
        }
    }

    private func renderImage(_ asset: AssetDTO) async {
        let fileURL = environment.assets.assetFileURL(relPath: asset.originalRelPath)
        let bounds = imageView.bounds
        lastDecodeSize = bounds.size
        let scale = Int64(view.traitCollection.displayScale)
        let target = RenderGeometry.decodeTarget(
            viewW: Int64(max(bounds.width, 1)),
            viewH: Int64(max(bounds.height, 1)),
            scale: max(scale, 1),
            cap: Self.maxDecodePixels
        )
        let image: UIImage? = await Task.detached {
            guard FileManager.default.fileExists(atPath: fileURL.path),
                  let cgImage = ImageRenderer.thumbnail(
                      fileURL: fileURL,
                      maxPixelWidth: Int(target.0),
                      maxPixelHeight: Int(target.1)
                  ) else {
                return nil
            }
            return UIImage(cgImage: cgImage)
        }.value
        guard !Task.isCancelled else { return }
        if let image {
            imageView.image = image
            imageView.isHidden = false
            unavailableLabel.isHidden = true
            retryButton.isHidden = true
        } else {
            showUnavailable()
        }
    }

    private func renderMetadata(_ asset: AssetDTO) async {
        let sourceName = (try? await environment.assets.sourceDisplayName(assetID: asset.id))
            ?? L10n.string("common.unknown")
        let frames = (try? await environment.assets.frameCount(assetID: asset.id)) ?? 0

        var rows: [UIView] = []
        rows.append(makeRow(
            labelKey: "library.detail_source",
            value: sourceName
        ))
        rows.append(makeRow(
            labelKey: "library.detail_dimensions",
            value: FormatStrings.string("format.dimensions", asset.pixelWidth, asset.pixelHeight)
        ))
        rows.append(makeRow(
            labelKey: "library.detail_file_size",
            value: FormatStrings.fileSizeText(byteSize: Int64(asset.byteSize))
        ))
        if let captured = asset.capturedAtMillis {
            rows.append(makeRow(
                labelKey: "library.detail_taken_at",
                value: Self.dateText(millis: captured, withTime: true)
            ))
        }
        rows.append(makeRow(
            labelKey: "library.detail_imported_at",
            value: Self.dateText(millis: asset.importedAt, withTime: true)
        ))
        rows.append(makeRow(
            labelKey: nil,
            value: String(
                format: L10n.string("library.detail_in_frames"),
                frames
            )
        ))

        for subview in metadataStack.arrangedSubviews {
            subview.removeFromSuperview()
        }
        for row in rows {
            metadataStack.addArrangedSubview(row)
        }
        removeFromFrameButton.isHidden = frames == 0
    }

    private func makeRow(labelKey: String?, value: String) -> UIView {
        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = ThemeMetrics.space4
        if let labelKey {
            let label = UILabel()
            label.text = L10n.string(labelKey)
            label.font = ThemeFont.meta()
            label.adjustsFontForContentSizeCategory = true
            label.textColor = ThemeColor.textSecondary
            stack.addArrangedSubview(label)
        }
        let valueLabel = UILabel()
        valueLabel.text = value
        valueLabel.font = ThemeFont.body()
        valueLabel.adjustsFontForContentSizeCategory = true
        valueLabel.textColor = ThemeColor.textPrimary
        valueLabel.numberOfLines = 0
        stack.addArrangedSubview(valueLabel)
        return stack
    }

    private func showUnavailable() {
        imageView.isHidden = true
        unavailableLabel.isHidden = false
        retryButton.isHidden = false
    }

    private static func dateText(millis: Int, withTime: Bool) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = withTime ? .short : .none
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }

    @objc private func handleRetry() {
        load()
    }

    @objc private func handleAddToFrame() {
        Task { [weak self] in
            guard let self else { return }
            let cards = (try? await environment.frameManager.frameCards()) ?? []
            guard !cards.isEmpty else {
                let alert = UIAlertController(
                    title: L10n.string("frames.empty_title"),
                    message: L10n.string("frames.empty_body"),
                    preferredStyle: .alert
                )
                alert.addAction(UIAlertAction(
                    title: L10n.string("common.done"),
                    style: .default
                ))
                present(alert, animated: true)
                return
            }
            let sheet = UIAlertController(
                title: L10n.string("library.add_to_frame"),
                message: nil,
                preferredStyle: .actionSheet
            )
            for card in cards {
                sheet.addAction(UIAlertAction(
                    title: card.name,
                    style: .default,
                    handler: { [weak self] _ in
                        self?.saveFrameEdit(frameID: card.id, mode: .add, attempt: 0)
                    }
                ))
            }
            sheet.addAction(UIAlertAction(
                title: L10n.string("common.cancel"),
                style: .cancel
            ))
            sheet.popoverPresentationController?.sourceView = addToFrameButton
            present(sheet, animated: true)
        }
    }

    @objc private func handleRemoveFromFrame() {
        Task { [weak self] in
            guard let self else { return }
            let cards = (try? await environment.frameManager.frameCards()) ?? []
            let sheet = UIAlertController(
                title: L10n.string("library.remove_from_frame"),
                message: L10n.string("library.remove_from_frame_note"),
                preferredStyle: .actionSheet
            )
            for card in cards {
                sheet.addAction(UIAlertAction(
                    title: card.name,
                    style: .default,
                    handler: { [weak self] _ in
                        self?.saveFrameEdit(frameID: card.id, mode: .remove, attempt: 0)
                    }
                ))
            }
            sheet.addAction(UIAlertAction(
                title: L10n.string("common.cancel"),
                style: .cancel
            ))
            sheet.popoverPresentationController?.sourceView = removeFromFrameButton
            present(sheet, animated: true)
        }
    }

    private enum FrameEditMode {
        case add
        case remove
    }

    private func saveFrameEdit(frameID: String, mode: FrameEditMode, attempt: Int) {
        Task { [weak self] in
            guard let self else { return }
            guard let detail = try? await environment.frameManager.frameDetail(frameID: frameID) else {
                return
            }
            let edit = SelectionRefEdit(assetId: assetID, sourceAssetId: nil)
            let result = try? await environment.frames.save(
                frameID: frameID,
                baseVersion: detail.selectionVersion,
                addIncludes: mode == .add ? [edit] : [],
                removeRefs: mode == .remove ? [edit] : []
            )
            switch result {
            case .ok:
                if let asset = try? await environment.assets.fetchAsset(id: assetID) {
                    await renderMetadata(asset)
                }
            default:
                if attempt == 0 {
                    saveFrameEdit(frameID: frameID, mode: mode, attempt: 1)
                } else {
                    let alert = UIAlertController(
                        title: nil,
                        message: L10n.string("error.selection_conflict.body"),
                        preferredStyle: .alert
                    )
                    alert.addAction(UIAlertAction(
                        title: L10n.string("common.done"),
                        style: .default
                    ))
                    present(alert, animated: true)
                }
            }
        }
    }

    @objc private func handleHideGlobally() {
        let alert = UIAlertController(
            title: L10n.string("delete.hide_globally"),
            message: L10n.string("delete.hide_globally_body"),
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(
            title: L10n.string("delete.hide_globally"),
            style: .destructive,
            handler: { [weak self] _ in
                guard let self else { return }
                Task {
                    try? await self.environment.assets.hideAssetGlobally(id: self.assetID)
                    ThumbnailLoader.clearMemoryCache()
                    PlayerImageLoader.clearMemoryCache()
                    self.dismiss(animated: true)
                }
            }
        ))
        alert.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel
        ))
        present(alert, animated: true)
    }

    @objc private func handleDelete() {
        Task { [weak self] in
            guard let self else { return }
            let frames = (try? await environment.assets.frameCount(assetID: assetID)) ?? 0
            let alert = UIAlertController(
                title: String(
                    format: L10n.string("delete.from_device_title"),
                    1
                ),
                message: String(
                    format: L10n.string("delete.from_device_body"),
                    frames
                ),
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(
                title: L10n.string("delete.from_device_action"),
                style: .destructive,
                handler: { [weak self] _ in
                    self?.performDelete()
                }
            ))
            alert.addAction(UIAlertAction(
                title: L10n.string("common.cancel"),
                style: .cancel
            ))
            present(alert, animated: true)
        }
    }

    private func performDelete() {
        Task { [weak self] in
            guard let self else { return }
            try? await environment.assets.deleteAsset(id: assetID, reason: .userDelete)
            assetDeleted = true
            metadataStack.isHidden = true
            actionsStack.isHidden = true
            showUndoBar()
        }
    }

    private func showUndoBar() {
        undoLabel.text = L10n.string("delete.notice")
        undoLabel.isHidden = false
        undoButton.isHidden = false
        undoBar.isHidden = false
        undoTask?.cancel()
        undoTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard !Task.isCancelled else { return }
            self?.dismiss(animated: true)
        }
    }

    @objc private func handleUndo() {
        undoTask?.cancel()
        Task { [weak self] in
            guard let self else { return }
            try? await environment.assets.restoreDeletedAsset(id: assetID)
            assetDeleted = false
            metadataStack.isHidden = false
            actionsStack.isHidden = false
            undoLabel.isHidden = false
            undoLabel.text = L10n.string("delete.undone")
            undoButton.isHidden = true
            load()
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard !Task.isCancelled else { return }
                self?.undoBar.isHidden = true
            }
        }
    }

    @objc private func handleBack() {
        dismiss(animated: true)
    }
}
