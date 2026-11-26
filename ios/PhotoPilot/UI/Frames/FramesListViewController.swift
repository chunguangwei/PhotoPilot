import UIKit

final class FramesListViewController: UIViewController, UIScrollViewDelegate {
    private let environment: AppEnvironment
    private let loader: ThumbnailLoader
    private let cardsStack = UIStackView()
    private let emptyStack = UIStackView()
    private let scrollView = UIScrollView()
    private let pageStack = UIStackView()
    private let header = UIStackView()
    private let createButton = UIButton(type: .system)
    private let aiButton = UIButton(type: .system)
    private let onlineButton = UIButton(type: .system)
    private var cardViews: [UIControl] = []
    private var covers: [FrameCoverView] = []
    private var columnCount = 0
    private var pageWidthConstraint: NSLayoutConstraint?
    private var cards: [FrameCardDTO] = []
    private var libraryEmpty = true

    init(environment: AppEnvironment) {
        self.environment = environment
        self.loader = ThumbnailLoader(originalsRoot: environment.originalsRootURL, media: environment.remoteMedia)
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ThemeColor.canvas

        let titleLabel = UILabel()
        titleLabel.text = L10n.string("frames.title")
        titleLabel.font = ThemeFont.title()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.numberOfLines = 0
        titleLabel.accessibilityTraits.insert(.header)

        NotificationCenter.default.addObserver(self, selector: #selector(remoteMediaChanged), name: .remoteMediaChanged, object: nil)
        createButton.addTarget(self, action: #selector(handleCreate), for: .touchUpInside)
        var createConfiguration = UIButton.Configuration.bordered()
        createConfiguration.title = L10n.string("frames.create")
        createConfiguration.image = UIImage(systemName: "plus")
        createConfiguration.imagePadding = 8
        createConfiguration.baseForegroundColor = ThemeColor.accentAction
        createConfiguration.baseBackgroundColor = ThemeColor.panel
        createConfiguration.contentInsets = NSDirectionalEdgeInsets(top: 12, leading: 14, bottom: 12, trailing: 14)
        createButton.configuration = createConfiguration
        createButton.titleLabel?.font = ThemeFont.body()
        createButton.titleLabel?.adjustsFontForContentSizeCategory = true
        createButton.titleLabel?.numberOfLines = 0
        createButton.accessibilityIdentifier = "frames.createOptions"
        createButton.heightAnchor.constraint(greaterThanOrEqualToConstant: ThemeMetrics.touchTarget).isActive = true
        createButton.showsMenuAsPrimaryAction = true
        createButton.menu = UIMenu(children: [
            UIAction(title: AIText.value("Create AI frames", "AI 创建相框"), image: UIImage(systemName: "sparkles"), identifier: UIAction.Identifier("frames.createAI")) { [weak self] _ in self?.openAIFrames() },
            UIAction(title: AIText.value("Online photos", "在线选片"), image: UIImage(systemName: "network"), identifier: UIAction.Identifier("frames.onlinePhotos")) { [weak self] _ in self?.openOnlinePhotos() },
            UIAction(title: AIText.value("Select photos manually", "手动选片"), image: UIImage(systemName: "rectangle.stack")) { [weak self] _ in self?.handleCreate() }
        ])
        titleLabel.setContentCompressionResistancePriority(.required, for: .horizontal)
        createButton.setContentHuggingPriority(.required, for: .horizontal)
        header.addArrangedSubview(titleLabel)
        aiButton.setTitle(AIText.value("Create AI frames", "AI 创建相框"), for: .normal)
        aiButton.titleLabel?.font = ThemeFont.body()
        aiButton.titleLabel?.adjustsFontForContentSizeCategory = true
        aiButton.titleLabel?.numberOfLines = 0
        aiButton.heightAnchor.constraint(greaterThanOrEqualToConstant: ThemeMetrics.touchTarget).isActive = true
        aiButton.accessibilityIdentifier = "frames.createAI"
        aiButton.addTarget(self, action: #selector(openAIFrames), for: .touchUpInside)
        header.addArrangedSubview(aiButton)
        onlineButton.setTitle(AIText.value("Online photos", "在线选片"), for: .normal)
        onlineButton.titleLabel?.font = ThemeFont.body()
        onlineButton.titleLabel?.adjustsFontForContentSizeCategory = true
        onlineButton.titleLabel?.numberOfLines = 0
        onlineButton.heightAnchor.constraint(greaterThanOrEqualToConstant: ThemeMetrics.touchTarget).isActive = true
        onlineButton.accessibilityIdentifier = "frames.onlinePhotos"
        onlineButton.addTarget(self, action: #selector(openOnlinePhotos), for: .touchUpInside)
        header.addArrangedSubview(onlineButton)
        header.axis = .vertical
        header.spacing = ThemeMetrics.space16

        cardsStack.axis = .vertical
        cardsStack.spacing = ThemeMetrics.space16
        emptyStack.axis = .vertical
        emptyStack.alignment = .fill
        emptyStack.spacing = ThemeMetrics.space8
        emptyStack.isHidden = true

        pageStack.axis = .vertical
        pageStack.spacing = ThemeMetrics.space24
        pageStack.translatesAutoresizingMaskIntoConstraints = false
        pageStack.addArrangedSubview(header)
        pageStack.addArrangedSubview(cardsStack)
        pageStack.addArrangedSubview(emptyStack)
        scrollView.addSubview(pageStack)
        scrollView.delegate = self
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scrollView)

        let width = pageStack.widthAnchor.constraint(
            equalTo: scrollView.frameLayoutGuide.widthAnchor,
            constant: -2 * ThemeMetrics.contentInsetCompact
        )
        pageWidthConstraint = width
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            pageStack.topAnchor.constraint(
                equalTo: scrollView.contentLayoutGuide.topAnchor,
                constant: ThemeMetrics.space24
            ),
            pageStack.bottomAnchor.constraint(
                equalTo: scrollView.contentLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space24
            ),
            scrollView.contentLayoutGuide.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor),
            pageStack.centerXAnchor.constraint(equalTo: scrollView.contentLayoutGuide.centerXAnchor),
            width,
        ])
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        updateLayout()
        covers.forEach { $0.resolveIfVisible(in: scrollView) }
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        covers.forEach { $0.resolveIfVisible(in: scrollView) }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        guard isViewLoaded else { return }
        if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            renderCards()
        }
    }

    private func updateLayout() {
        let width = view.safeAreaLayoutGuide.layoutFrame.width
        guard width > 0 else { return }
        let inset = width < 600 ? ThemeMetrics.contentInsetCompact : ThemeMetrics.contentInset
        if pageWidthConstraint?.constant != -2 * inset {
            pageWidthConstraint?.constant = -2 * inset
        }
        let availableWidth = width - 2 * inset
        let largeText = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        let headerAxis: NSLayoutConstraint.Axis = !largeText && (!cards.isEmpty || availableWidth >= 480) ? .horizontal : .vertical
        if header.axis != headerAxis {
            header.axis = headerAxis
            header.alignment = headerAxis == .horizontal ? .center : .fill
        }
        let columns = availableWidth >= 720 && !largeText ? 2 : 1
        guard columns != columnCount else { return }
        columnCount = columns
        arrangeCards()
    }

    private func arrangeCards() {
        cardViews.forEach { $0.removeFromSuperview() }
        cardsStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for start in stride(from: 0, to: cardViews.count, by: max(1, columnCount)) {
            let row = UIStackView()
            row.axis = .horizontal
            row.alignment = .top
            row.distribution = .fillEqually
            row.spacing = ThemeMetrics.space16
            for index in start..<min(start + max(1, columnCount), cardViews.count) {
                row.addArrangedSubview(cardViews[index])
            }
            if columnCount == 2 && row.arrangedSubviews.count == 1 {
                let spacer = UIView()
                spacer.isAccessibilityElement = false
                row.addArrangedSubview(spacer)
            }
            cardsStack.addArrangedSubview(row)
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reload()
    }

    private func reload() {
        Task { [weak self] in
            guard let self else { return }
            async let cardsFetch = environment.frameManager.frameCards()
            async let libraryFetch = environment.assets.libraryItems()
            let fetched = (try? await cardsFetch) ?? []
            libraryEmpty = ((try? await libraryFetch) ?? []).isEmpty
            var enriched: [FrameCardDTO] = []
            for card in fetched {
                let counts = (try? await environment.frameManager.frameCounts(frameID: card.id))
                    ?? (playable: 0, pending: 0)
                var relPath = card.coverRelPath
                if relPath == nil,
                   let firstID = (try? await environment.frames.buildPlayQueue(frameID: card.id))?.first {
                    relPath = firstID.hasPrefix("remote:") ? firstID : try? await environment.assets.fetchAsset(id: firstID)?.originalRelPath
                }
                let paused = (try? await environment.aiFrames.store.state(frameID: card.id))?.isUserPaused ?? false
                enriched.append(FrameCardDTO(
                    id: card.id,
                    name: card.name,
                    coverRelPath: relPath,
                    playableCount: counts.playable,
                    pendingCount: counts.pending,
                    updatedAt: card.updatedAt,
                    storageMode: card.storageMode,
                    hasReferencedDevicePhotos: card.hasReferencedDevicePhotos,
                    aiPaused: paused
                ))
            }
            cards = enriched
            renderCards()
        }
    }

    static func playableCountText(for card: FrameCardDTO) -> String {
        if card.storageMode == .remote || card.hasReferencedDevicePhotos {
            return AIText.value(card.playableCount == 1 ? "1 photo" : "\(card.playableCount) photos", "\(card.playableCount) 张照片")
        }
        return String(format: L10n.string("frames.playable_count"), card.playableCount)
    }

    private func renderCards() {
        for subview in cardsStack.arrangedSubviews {
            subview.removeFromSuperview()
        }
        for subview in emptyStack.arrangedSubviews {
            subview.removeFromSuperview()
        }

        cardViews.removeAll()
        let isEmpty = cards.isEmpty
        cardsStack.isHidden = isEmpty
        aiButton.isHidden = !isEmpty
        onlineButton.isHidden = !isEmpty
        if isEmpty {
            createButton.removeFromSuperview()
        } else if createButton.superview == nil {
            header.addArrangedSubview(createButton)
        }
        emptyStack.isHidden = !isEmpty

        if isEmpty {
            emptyStack.addArrangedSubview(makeLabel(
                key: "frames.empty_title",
                font: ThemeFont.heading(),
                color: ThemeColor.textPrimary,
                header: true
            ))
            emptyStack.addArrangedSubview(makeLabel(
                key: "frames.empty_body",
                font: ThemeFont.body(),
                color: ThemeColor.textSecondary,
                header: false
            ))
            if libraryEmpty {
                emptyStack.addArrangedSubview(makeLabel(
                    key: "frames.curate_hint_title",
                    font: ThemeFont.body(),
                    color: ThemeColor.textPrimary,
                    header: false
                ))
                emptyStack.addArrangedSubview(makeLabel(
                    key: "frames.curate_hint_body",
                    font: ThemeFont.meta(),
                    color: ThemeColor.textSecondary,
                    header: false
                ))
            }
            let createButton = ActionButton(titleKey: "frames.create", style: .primary)
            createButton.addTarget(self, action: #selector(handleCreate), for: .touchUpInside)
            emptyStack.setCustomSpacing(ThemeMetrics.space16, after: emptyStack.arrangedSubviews.last!)
            emptyStack.addArrangedSubview(createButton)
            return
        }

        cardViews = cards.enumerated().map { makeCard($0.element, index: $0.offset) }
        covers = collectCovers(views: cardViews)
        updateLayout()
        arrangeCards()
    }

    private func collectCovers(views: [UIView]) -> [FrameCoverView] {
        var result: [FrameCoverView] = []
        func visit(_ view: UIView) {
            if let cover = view as? FrameCoverView {
                result.append(cover)
                return
            }
            for child in view.subviews { visit(child) }
        }
        views.forEach(visit)
        return result
    }

    private func makeLabel(key: String, font: UIFont, color: UIColor, header: Bool) -> UILabel {
        let label = UILabel()
        label.text = L10n.string(key)
        label.font = font
        label.adjustsFontForContentSizeCategory = true
        label.textColor = color
        label.textAlignment = .center
        label.numberOfLines = 0
        if header {
            label.accessibilityTraits.insert(.header)
        }
        return label
    }

    private func makeCard(_ card: FrameCardDTO, index: Int) -> UIControl {
        let control = FrameCardControl()
        control.backgroundColor = ThemeColor.panel
        control.layer.cornerRadius = ThemeMetrics.radiusGroup
        control.clipsToBounds = true
        control.isAccessibilityElement = false
        control.shouldGroupAccessibilityChildren = true
        control.tag = index


        let nameLabel = UILabel()
        nameLabel.text = card.name
        nameLabel.font = ThemeFont.heading()
        nameLabel.adjustsFontForContentSizeCategory = true
        nameLabel.textColor = ThemeColor.textPrimary
        nameLabel.numberOfLines = 0

        let playableLabel = UILabel()
        playableLabel.text = Self.playableCountText(for: card) + (card.aiPaused ? AIText.value(" · Paused, continue in AI settings", " · 已暂停，可在 AI 设置中继续") : "")
        playableLabel.font = ThemeFont.meta()
        playableLabel.adjustsFontForContentSizeCategory = true
        playableLabel.textColor = ThemeColor.textSecondary
        playableLabel.numberOfLines = 0

        let pendingLabel = UILabel()
        pendingLabel.text = String(format: L10n.string("frames.pending_count"), card.pendingCount)
        pendingLabel.font = ThemeFont.meta()
        pendingLabel.adjustsFontForContentSizeCategory = true
        pendingLabel.textColor = ThemeColor.textSecondary
        pendingLabel.numberOfLines = 0
        pendingLabel.isHidden = card.pendingCount == 0
        for label in [nameLabel, playableLabel, pendingLabel] {
            label.isAccessibilityElement = true
            label.setContentCompressionResistancePriority(.required, for: .vertical)
        }

        let textStack = UIStackView(arrangedSubviews: [nameLabel, playableLabel, pendingLabel])
        textStack.axis = .vertical
        textStack.spacing = ThemeMetrics.space4
        textStack.setCustomSpacing(ThemeMetrics.space8, after: nameLabel)

        let playButton = ActionButton(titleKey: "frames.play", style: .primary)
        playButton.tag = index
        playButton.isEnabled = card.playableCount > 0
        playButton.addTarget(self, action: #selector(handlePlayCard(_:)), for: .touchUpInside)

        let actionsButton = UIButton(type: .system)
        actionsButton.setImage(UIImage(systemName: "ellipsis"), for: .normal)
        actionsButton.tintColor = ThemeColor.accentAction
        actionsButton.accessibilityLabel = L10n.string("player.more")
        actionsButton.accessibilityIdentifier = "frame.actions." + card.id
        actionsButton.tag = index
        actionsButton.addTarget(self, action: #selector(handleCardActions(_:)), for: .touchUpInside)
        let buttons: [UIControl] = [playButton, actionsButton]
        for button in buttons {
            NSLayoutConstraint.activate([
                button.widthAnchor.constraint(greaterThanOrEqualToConstant: ThemeMetrics.touchTarget),
                button.heightAnchor.constraint(greaterThanOrEqualToConstant: ThemeMetrics.touchTarget),
            ])
        }

        let actions = UIStackView(arrangedSubviews: [playButton, actionsButton])
        let largeText = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        actions.axis = largeText ? .vertical : .horizontal
        actions.spacing = ThemeMetrics.space8
        if !largeText {
            actionsButton.widthAnchor.constraint(equalToConstant: ThemeMetrics.touchTarget).isActive = true
        }
        let details = UIStackView(arrangedSubviews: [textStack, actions])
        details.axis = .vertical
        details.spacing = ThemeMetrics.space16
        details.isLayoutMarginsRelativeArrangement = true
        details.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)

        var sections: [UIView] = []
        if card.playableCount > 0 || card.coverRelPath != nil {
            let cover = FrameCoverView(loader: loader, relPath: card.coverRelPath, mode: card.storageMode)
            cover.heightAnchor.constraint(equalTo: cover.widthAnchor, multiplier: 10.0 / 16.0).isActive = true
            let previewButton = UIButton(type: .custom)
            previewButton.tag = index
            previewButton.accessibilityLabel = AIText.value("Preview frame cover", "预览相框封面")
            previewButton.addTarget(self, action: #selector(handleCoverPreview(_:)), for: .touchUpInside)
            cover.addSubview(previewButton)
            previewButton.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                previewButton.topAnchor.constraint(equalTo: cover.topAnchor),
                previewButton.bottomAnchor.constraint(equalTo: cover.bottomAnchor),
                previewButton.leadingAnchor.constraint(equalTo: cover.leadingAnchor),
                previewButton.trailingAnchor.constraint(equalTo: cover.trailingAnchor),
            ])
            sections.append(cover)
        }
        sections.append(details)
        let content = UIStackView(arrangedSubviews: sections)
        content.axis = .vertical
        content.translatesAutoresizingMaskIntoConstraints = false
        control.addSubview(content)
        NSLayoutConstraint.activate([
            content.topAnchor.constraint(equalTo: control.topAnchor),
            content.bottomAnchor.constraint(equalTo: control.bottomAnchor),
            content.leadingAnchor.constraint(equalTo: control.leadingAnchor),
            content.trailingAnchor.constraint(equalTo: control.trailingAnchor),
        ])
        return control
    }

    @objc private func handleCreate() {
        openEditor(frameID: nil)
    }

    @objc private func handleCoverPreview(_ sender: UIButton) {
        guard cards.indices.contains(sender.tag) else { return }
        let card = cards[sender.tag]
        let mode = card.storageMode == .remote
            ? AIText.value("On-demand playback", "按需播放")
            : AIText.value("Local playback", "本地播放")
        let preview = CoverPreviewViewController(environment: environment, relPath: card.coverRelPath,
            frameName: card.name, photoCount: card.playableCount, playbackSummary: mode, storageMode: card.storageMode,
            initialImage: (sender.superview as? FrameCoverView)?.previewImage) { [weak self] in
            self?.openEditor(frameID: card.id)
        }
        preview.modalPresentationStyle = .overFullScreen
        present(preview, animated: true)
    }

    @objc private func remoteMediaChanged() {
        guard viewIfLoaded?.window != nil else { return }
        reload()
    }

    @objc private func openOnlinePhotos() {
        let navigation = UINavigationController(rootViewController: RemoteSourcePickerViewController(environment: environment))
        navigation.modalPresentationStyle = .fullScreen
        present(navigation, animated: true)
    }

    @objc private func openAIFrames() {
        let editor = AIFrameViewController(environment: environment)
        editor.onSaved = { [weak self] in self?.reload() }
        let navigation = UINavigationController(rootViewController: editor)
        navigation.modalPresentationStyle = .fullScreen
        present(navigation, animated: true)
    }

    @objc private func handlePlayCard(_ sender: UIControl) {
        guard sender.tag < cards.count else { return }
        let player = PlayerViewController(
            environment: environment,
            frameID: cards[sender.tag].id
        )
        player.modalPresentationStyle = .fullScreen
        present(player, animated: true)
    }

    @objc private func handleCardActions(_ sender: UIButton) {
        guard sender.tag < cards.count else { return }
        let card = cards[sender.tag]
        let sheet = UIAlertController(title: card.name, message: nil, preferredStyle: .actionSheet)
        sheet.addAction(UIAlertAction(
            title: L10n.string("common.edit"),
            style: .default,
            handler: { [weak self] _ in self?.openEditor(frameID: card.id) }
        ))
        sheet.addAction(UIAlertAction(
            title: L10n.string("common.rename"),
            style: .default,
            handler: { [weak self] _ in self?.promptRename(card) }
        ))
        sheet.addAction(UIAlertAction(
            title: L10n.string("frames.playback_settings"),
            style: .default,
            handler: { [weak self] _ in
                guard let self else { return }
                let settings = PlaybackSettingsViewController(
                    environment: environment,
                    frameID: card.id
                )
                settings.modalPresentationStyle = .fullScreen
                present(settings, animated: true)
            }
        ))
        sheet.addAction(UIAlertAction(title: AIText.value("Photo storage", "照片存储"), style: .default) { [weak self] _ in
            guard let self else { return }
            let navigation = UINavigationController(rootViewController: FrameStorageViewController(environment: environment, frameID: card.id))
            navigation.modalPresentationStyle = .fullScreen
            present(navigation, animated: true)
        })
        sheet.addAction(UIAlertAction(title: AIText.value("Choose online photos", "选择远端照片"), style: .default) { [weak self] _ in
            guard let self else { return }
            let picker = RemoteSourcePickerViewController(environment: environment) { [weak self] sourceID in
                self?.dismiss(animated: true) { [weak self] in self?.openEditor(frameID: card.id, remoteSourceID: sourceID) }
            }
            let navigation = UINavigationController(rootViewController: picker)
            navigation.modalPresentationStyle = .fullScreen
            present(navigation, animated: true)
        })
        sheet.addAction(UIAlertAction(
            title: L10n.string("frames.duplicate"),
            style: .default,
            handler: { [weak self] _ in
                Task { [weak self] in
                    _ = try? await self?.environment.frameManager.duplicateFrame(frameID: card.id)
                    self?.reload()
                }
            }
        ))
        sheet.addAction(UIAlertAction(
            title: L10n.string("frames.delete"),
            style: .destructive,
            handler: { [weak self] _ in self?.confirmDelete(card) }
        ))
        sheet.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel
        ))
        sheet.popoverPresentationController?.sourceView = sender
        present(sheet, animated: true)
    }

    private func promptRename(_ card: FrameCardDTO) {
        let alert = UIAlertController(
            title: L10n.string("common.rename"),
            message: nil,
            preferredStyle: .alert
        )
        alert.addTextField { textField in
            textField.text = card.name
            textField.placeholder = L10n.string("frames.name_placeholder")
            KeyboardDismiss.installDoneToolbar(on: textField)
        }
        alert.addAction(UIAlertAction(
            title: L10n.string("common.save"),
            style: .default,
            handler: { [weak self, weak alert] _ in
                let name = alert?.textFields?.first?.text?.trimmingCharacters(in: .whitespacesAndNewlines)
                guard let name, !name.isEmpty else { return }
                Task { [weak self] in
                    try? await self?.environment.frameManager.renameFrame(frameID: card.id, name: name)
                    self?.reload()
                }
            }
        ))
        alert.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel
        ))
        present(alert, animated: true)
    }

    private func confirmDelete(_ card: FrameCardDTO) {
        let alert = UIAlertController(
            title: FormatStrings.string("frames.delete_confirm_title", card.name),
            message: L10n.string("frames.delete_confirm_body"),
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(
            title: L10n.string("frames.delete"),
            style: .destructive,
            handler: { [weak self] _ in
                Task { [weak self] in
                    await self?.environment.aiFrames.cancel(frameID: card.id)
                    try? await self?.environment.frameManager.deleteFrame(frameID: card.id)
                    self?.reload()
                }
            }
        ))
        alert.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel
        ))
        present(alert, animated: true)
    }

    private func openEditor(frameID: String?, remoteSourceID: String? = nil) {
        let editor = FrameEditViewController(environment: environment, frameID: frameID, remoteSourceID: remoteSourceID)
        editor.modalPresentationStyle = .fullScreen
        present(editor, animated: true)
    }
}

private final class FrameCardControl: UIControl {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard let hit = super.hitTest(point, with: event) else { return nil }
        var candidate: UIView? = hit
        while let current = candidate, current !== self {
            if current is UIControl { return current }
            candidate = current.superview
        }
        return self
    }
}

private final class FrameCoverView: UIView {
    private let imageView = UIImageView()
    var previewImage: UIImage? { imageView.image }
    private let placeholder = BrandMarkView(frame: .zero)
    private let loader: ThumbnailLoader
    private let relPath: String?
    private let mode: FrameStorageMode
    private var requestedPixels = 0
    private var failedAttempts = 0
    private var loadTask: Task<Void, Never>?

    init(loader: ThumbnailLoader, relPath: String?, mode: FrameStorageMode) {
        self.loader = loader
        self.relPath = relPath
        self.mode = mode
        super.init(frame: .zero)
        backgroundColor = ThemeColor.canvas
        imageView.isUserInteractionEnabled = false
        placeholder.isUserInteractionEnabled = false
        imageView.contentMode = .scaleAspectFit
        addSubview(placeholder)
        addSubview(imageView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        imageView.frame = bounds
        let side = min(ThemeMetrics.space48 * 2, bounds.height * 0.4)
        placeholder.frame = CGRect(x: (bounds.width - side) / 2, y: (bounds.height - side) / 2, width: side, height: side)
        guard bounds.width > 0, let relPath else { return }
        let pixels = min(1600, Int(ceil(bounds.width * max(1, traitCollection.displayScale) / 128)) * 128)
        if let cached = loader.peekThumbnail(relPath: relPath, pointSize: CGFloat(pixels), scale: 1), cached.size.width > 0 {
            imageView.image = cached
            placeholder.isHidden = true
        }
        resolveIfVisible(in: enclosingScrollView)
    }

    private var enclosingScrollView: UIScrollView? {
        var current = superview
        while let view = current {
            if let scroll = view as? UIScrollView { return scroll }
            current = view.superview
        }
        return nil
    }

    func resolveIfVisible(in scrollView: UIScrollView?) {
        if let scrollView {
            let visibleRect = scrollView.bounds
            guard !visibleRect.isEmpty else { return }
            if !visibleRect.intersects(convert(bounds, to: scrollView)) { return }
        }
        requestLoad()
    }

    private func requestLoad() {
        guard bounds.width > 0, let relPath else { return }
        let pixels = min(1600, Int(ceil(bounds.width * max(1, traitCollection.displayScale) / 128)) * 128)
        guard pixels > requestedPixels else { return }
        requestedPixels = pixels
        loadTask?.cancel()
        loadTask = Task { [weak self, loader, mode] in
            let image = await loader.coverThumbnail(relPath: relPath, pixels: pixels, mode: mode)
            guard !Task.isCancelled, let self, requestedPixels == pixels else { return }
            if let image {
                failedAttempts = 0
                imageView.image = image
                placeholder.isHidden = true
                return
            }
            placeholder.isHidden = imageView.image != nil
            guard failedAttempts < 5 else { return }
            failedAttempts += 1
            let delay = UInt64(min(30, 2 << failedAttempts)) * 1_000_000_000
            try? await Task.sleep(nanoseconds: delay)
            guard !Task.isCancelled, window != nil, requestedPixels == pixels else { return }
            requestedPixels = 0
            resolveIfVisible(in: enclosingScrollView)
        }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        guard window != nil, imageView.image == nil else { return }
        loadTask?.cancel()
        requestedPixels = 0
        failedAttempts = 0
        setNeedsLayout()
    }

    deinit {
        loadTask?.cancel()
    }
}
