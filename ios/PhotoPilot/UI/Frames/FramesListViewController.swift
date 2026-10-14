import UIKit

final class FramesListViewController: UIViewController {
    private let environment: AppEnvironment
    private let loader: ThumbnailLoader
    private let cardsStack = UIStackView()
    private let emptyStack = UIStackView()
    private let scrollView = UIScrollView()
    private let pageStack = UIStackView()
    private let header = UIStackView()
    private let createButton = ActionButton(titleKey: "frames.create", style: .secondary)
    private var cardViews: [UIControl] = []
    private var columnCount = 0
    private var pageWidthConstraint: NSLayoutConstraint?
    private var cards: [FrameCardDTO] = []
    private var libraryEmpty = true

    init(environment: AppEnvironment) {
        self.environment = environment
        self.loader = ThumbnailLoader(originalsRoot: environment.originalsRootURL)
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

        createButton.addTarget(self, action: #selector(handleCreate), for: .touchUpInside)
        titleLabel.setContentCompressionResistancePriority(.required, for: .horizontal)
        createButton.setContentHuggingPriority(.required, for: .horizontal)
        header.addArrangedSubview(titleLabel)
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
        let headerAxis: NSLayoutConstraint.Axis = availableWidth >= 480 && !largeText ? .horizontal : .vertical
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
                if relPath == nil {
                    relPath = try? await environment.frameManager.firstQueueAssetRelPath(frameID: card.id)
                }
                enriched.append(FrameCardDTO(
                    id: card.id,
                    name: card.name,
                    coverRelPath: relPath,
                    playableCount: counts.playable,
                    pendingCount: counts.pending,
                    updatedAt: card.updatedAt
                ))
            }
            cards = enriched
            renderCards()
        }
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
        updateLayout()
        arrangeCards()
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
        control.addTarget(self, action: #selector(handleOpenCard(_:for:)), for: .touchUpInside)

        let cover = FrameCoverView(loader: loader, relPath: card.coverRelPath)
        cover.heightAnchor.constraint(equalTo: cover.widthAnchor, multiplier: 10.0 / 16.0).isActive = true

        let nameLabel = UILabel()
        nameLabel.text = card.name
        nameLabel.font = ThemeFont.heading()
        nameLabel.adjustsFontForContentSizeCategory = true
        nameLabel.textColor = ThemeColor.textPrimary
        nameLabel.numberOfLines = 0

        let playableLabel = UILabel()
        playableLabel.text = String(format: L10n.string("frames.playable_count"), card.playableCount)
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

        let content = UIStackView(arrangedSubviews: [cover, details])
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

    @objc private func handleOpenCard(_ sender: UIControl, for event: UIEvent) {
        if let touch = event.touches(for: sender)?.first, touch.view !== sender {
            return
        }
        guard sender.tag < cards.count else { return }
        openEditor(frameID: cards[sender.tag].id)
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

    private func openEditor(frameID: String?) {
        let editor = FrameEditViewController(environment: environment, frameID: frameID)
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
    private let placeholder = BrandMarkView(frame: .zero)
    private let loader: ThumbnailLoader
    private let relPath: String?
    private var requestedPixels = 0
    private var loadTask: Task<Void, Never>?

    init(loader: ThumbnailLoader, relPath: String?) {
        self.loader = loader
        self.relPath = relPath
        super.init(frame: .zero)
        backgroundColor = ThemeColor.canvas
        isUserInteractionEnabled = false
        accessibilityElementsHidden = true
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
        guard pixels > requestedPixels else { return }
        requestedPixels = pixels
        loadTask?.cancel()
        loadTask = Task { [weak self, loader] in
            let image = await loader.thumbnail(relPath: relPath, pointSize: CGFloat(pixels), scale: 1)
            guard !Task.isCancelled, let self, requestedPixels == pixels else { return }
            imageView.image = image
            placeholder.isHidden = image != nil
        }
    }

    deinit {
        loadTask?.cancel()
    }
}
