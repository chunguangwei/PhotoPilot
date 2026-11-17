import UIKit

final class FrameEditViewController: UIViewController, UITextFieldDelegate, UITableViewDataSource, UITableViewDelegate {
    private let environment: AppEnvironment
    private var frameID: String?
    private var remoteSourceID: String?
    private var includesRemoteMedia: Bool
    private let routeToMainAfterSave: Bool
    private var sourceIDs: Set<String>?
    private var assetScope: Set<String>?
    private var saving = false
    private var loading = true
    private var navigationBarWasHidden = false
    private var scopeTask: Task<Void, Never>?

    private var detail: FrameDetailDTO?
    private var collections: [CollectionDTO] = []
    private var memberCache: [String: [String]] = [:]
    private var selectedAssetIDs: Set<String> = []
    private var initialSelection: Set<String>
    private var currentExcludes: Set<String> = []
    private var dynamicRules: [String: SaveRuleRow] = [:]
    private var recursiveFlags: [String: Bool] = [:]
    private var pendingManualOrder: [String]?
    private var pendingCoverAssetID: String?
    private var dirty = false

    private let headerBar = UIStackView()
    private let headerScroll = UIScrollView()
    private let nameField = UITextField()
    private let tabControl: UISegmentedControl
    private let photosContainer = UIView()
    private let directoriesTable = UITableView(frame: .zero, style: .plain)
    private let summaryLabel = UILabel()
    private let noteLabel = UILabel()
    private let pendingLabel = UILabel()
    private let manualButton = ActionButton(titleKey: "order.manual_title", style: .secondary)
    private let saveButton = ActionButton(titleKey: "common.save", style: .primary)
    private var gridChild: AssetGridViewController?
    private var gridEdgeConstraints: [NSLayoutConstraint] = []
    private var gridColumnConstraints: [NSLayoutConstraint] = []

    init(environment: AppEnvironment, frameID: String?, routeToMainAfterSave: Bool = false, remoteSourceID: String? = nil, sourceIDs: Set<String>? = nil, assetScope: Set<String>? = nil, initialSelection: Set<String> = []) {
        self.initialSelection = initialSelection
        self.selectedAssetIDs = initialSelection
        self.dirty = !initialSelection.isEmpty
        self.environment = environment
        self.frameID = frameID
        self.assetScope = assetScope
        self.sourceIDs = sourceIDs ?? remoteSourceID.map { Set([$0]) }
        self.remoteSourceID = remoteSourceID
        self.includesRemoteMedia = true
        self.routeToMainAfterSave = routeToMainAfterSave
        self.tabControl = UISegmentedControl(items: [
            L10n.string("library.tab_photos"),
            L10n.string("library.tab_folders"),
        ])
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        enableEdgeSwipeBack { [weak self] in self?.handleBack() }
        view.backgroundColor = ThemeColor.canvas

        let backButton = ActionButton(titleKey: "common.back", style: .secondary)
        backButton.addTarget(self, action: #selector(handleBack), for: .touchUpInside)

        nameField.isHidden = routeToMainAfterSave
        if routeToMainAfterSave {
            saveButton.setTitle(AIText.value("Confirm and play", "确认并播放"))
        }
        nameField.placeholder = L10n.string("frames.name_placeholder")
        nameField.font = ThemeFont.body()
        nameField.adjustsFontForContentSizeCategory = true
        nameField.textColor = ThemeColor.textPrimary
        nameField.borderStyle = .roundedRect
        nameField.delegate = self
        nameField.returnKeyType = .done
        nameField.accessibilityLabel = L10n.string("frames.name_placeholder")
        nameField.addTarget(self, action: #selector(handleNameChanged), for: .editingChanged)

        let titleLabel = UILabel()
        titleLabel.text = L10n.string("library.choose_title")
        titleLabel.font = ThemeFont.heading()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.numberOfLines = 0
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.accessibilityTraits.insert(.header)

        backButton.setContentHuggingPriority(.required, for: .horizontal)
        let topBar = headerBar
        [backButton, titleLabel, UIView()].forEach(topBar.addArrangedSubview)
        topBar.axis = .horizontal
        topBar.alignment = .center
        topBar.spacing = ThemeMetrics.space12

        let addPhotos = ActionButton(titleKey: "frames.choose_content", style: .secondary)
        addPhotos.setTitle(AIText.value("Add photos from a source", "从来源添加照片"))
        addPhotos.addTarget(self, action: #selector(openSources), for: .touchUpInside)
        let header = UIStackView(arrangedSubviews: [topBar, nameField, addPhotos])
        let aiSettings = ActionButton(titleKey: "common.settings", style: .secondary)
        aiSettings.setTitle(AIText.value("AI classification settings", "AI 分类设置"))
        aiSettings.accessibilityIdentifier = "frame.ai.settings"
        aiSettings.addTarget(self, action: #selector(openAISettings), for: .touchUpInside)
        aiSettings.isHidden = true
        if let frameID {
            Task { [weak self, weak aiSettings] in
                guard let self else { return }
                let configuration = try? await self.environment.aiFrames.configuration(frameID: frameID)
                aiSettings?.isHidden = configuration == nil
            }
        }
        header.addArrangedSubview(aiSettings)
        header.axis = .vertical
        header.spacing = ThemeMetrics.space12
        header.translatesAutoresizingMaskIntoConstraints = false
        headerScroll.accessibilityIdentifier = "frame.editor.header"
        headerScroll.translatesAutoresizingMaskIntoConstraints = false
        headerScroll.addSubview(header)
        view.addSubview(headerScroll)
        let naturalHeaderHeight = headerScroll.heightAnchor.constraint(equalTo: header.heightAnchor)
        naturalHeaderHeight.priority = UILayoutPriority(749)
        let minimumGridHeight = photosContainer.heightAnchor.constraint(greaterThanOrEqualToConstant: 120)
        minimumGridHeight.priority = .defaultHigh
        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: headerScroll.contentLayoutGuide.topAnchor),
            header.bottomAnchor.constraint(equalTo: headerScroll.contentLayoutGuide.bottomAnchor),
            header.leadingAnchor.constraint(equalTo: headerScroll.contentLayoutGuide.leadingAnchor),
            header.trailingAnchor.constraint(equalTo: headerScroll.contentLayoutGuide.trailingAnchor),
            header.widthAnchor.constraint(equalTo: headerScroll.frameLayoutGuide.widthAnchor),
            headerScroll.heightAnchor.constraint(lessThanOrEqualTo: view.heightAnchor, multiplier: 0.35),
            headerScroll.heightAnchor.constraint(greaterThanOrEqualToConstant: ThemeMetrics.touchTarget),
            naturalHeaderHeight,
            minimumGridHeight,
        ])

        tabControl.selectedSegmentIndex = 0
        tabControl.addTarget(self, action: #selector(handleTabChange), for: .valueChanged)
        tabControl.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
        tabControl.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(tabControl)

        photosContainer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(photosContainer)

        directoriesTable.dataSource = self
        directoriesTable.delegate = self
        directoriesTable.backgroundColor = .clear
        directoriesTable.separatorColor = ThemeColor.separator
        directoriesTable.register(UITableViewCell.self, forCellReuseIdentifier: "DirectoryCell")
        directoriesTable.register(UITableViewCell.self, forCellReuseIdentifier: "ToggleCell")
        directoriesTable.register(UITableViewCell.self, forCellReuseIdentifier: "ExcludedCell")
        directoriesTable.isHidden = true
        directoriesTable.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(directoriesTable)

        summaryLabel.font = ThemeFont.body()
        summaryLabel.adjustsFontForContentSizeCategory = true
        summaryLabel.textColor = ThemeColor.textPrimary
        summaryLabel.numberOfLines = 0

        noteLabel.text = L10n.string("library.selection_note_static") + "\n" + L10n.string("frames.cover_hint")
        noteLabel.font = ThemeFont.meta()
        noteLabel.adjustsFontForContentSizeCategory = true
        noteLabel.textColor = ThemeColor.textSecondary
        noteLabel.numberOfLines = 0

        pendingLabel.text = L10n.string("player.all_pending_title")
        pendingLabel.font = ThemeFont.meta()
        pendingLabel.adjustsFontForContentSizeCategory = true
        pendingLabel.textColor = ThemeColor.statusWarning
        pendingLabel.numberOfLines = 0
        pendingLabel.isHidden = true

        manualButton.addTarget(self, action: #selector(handleManualOrder), for: .touchUpInside)
        manualButton.isHidden = true
        saveButton.addTarget(self, action: #selector(handleSave), for: .touchUpInside)

        let details = UIStackView(arrangedSubviews: [pendingLabel, summaryLabel, noteLabel, manualButton])
        details.axis = .vertical
        details.spacing = ThemeMetrics.space8
        details.translatesAutoresizingMaskIntoConstraints = false
        let detailsScroll = UIScrollView()
        detailsScroll.addSubview(details)
        let naturalHeight = detailsScroll.heightAnchor.constraint(equalTo: details.heightAnchor)
        naturalHeight.priority = .defaultLow
        NSLayoutConstraint.activate([
            details.topAnchor.constraint(equalTo: detailsScroll.contentLayoutGuide.topAnchor),
            details.bottomAnchor.constraint(equalTo: detailsScroll.contentLayoutGuide.bottomAnchor),
            details.leadingAnchor.constraint(equalTo: detailsScroll.contentLayoutGuide.leadingAnchor),
            details.trailingAnchor.constraint(equalTo: detailsScroll.contentLayoutGuide.trailingAnchor),
            details.widthAnchor.constraint(equalTo: detailsScroll.frameLayoutGuide.widthAnchor),
            naturalHeight
        ])
        let bottomBar = UIStackView(arrangedSubviews: [detailsScroll, saveButton])
        bottomBar.axis = .vertical
        bottomBar.spacing = ThemeMetrics.space8
        bottomBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bottomBar)
        detailsScroll.heightAnchor.constraint(lessThanOrEqualTo: view.heightAnchor, multiplier: 0.28).isActive = true

        let readable = headerScroll.widthAnchor.constraint(
            lessThanOrEqualToConstant: ThemeMetrics.bodyMaxWidth
        )
        let fillWidth = headerScroll.widthAnchor.constraint(
            equalTo: view.safeAreaLayoutGuide.widthAnchor,
            constant: -2 * ThemeMetrics.contentInsetCompact
        )
        fillWidth.priority = .defaultHigh
        NSLayoutConstraint.activate([
            headerScroll.topAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.topAnchor,
                constant: ThemeMetrics.space8
            ),
            headerScroll.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            headerScroll.leadingAnchor.constraint(
                greaterThanOrEqualTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            headerScroll.trailingAnchor.constraint(
                lessThanOrEqualTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            readable,
            fillWidth,
            tabControl.topAnchor.constraint(equalTo: headerScroll.bottomAnchor, constant: ThemeMetrics.space12),
            tabControl.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            tabControl.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            photosContainer.topAnchor.constraint(
                equalTo: tabControl.bottomAnchor,
                constant: ThemeMetrics.space8
            ),
            photosContainer.bottomAnchor.constraint(equalTo: bottomBar.topAnchor),
            directoriesTable.topAnchor.constraint(equalTo: photosContainer.topAnchor),
            directoriesTable.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            directoriesTable.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            directoriesTable.bottomAnchor.constraint(equalTo: photosContainer.bottomAnchor),
            bottomBar.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            bottomBar.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            bottomBar.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
        ])

        gridEdgeConstraints = [
            photosContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            photosContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ]
        gridColumnConstraints = [
            photosContainer.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            photosContainer.trailingAnchor.constraint(equalTo: header.trailingAnchor),
        ]
        updateGridWidthConstraints()
        updateHeaderLayout()

        embedGrid()
        enableKeyboardDismissal()
        reload()
        refreshSourceScope()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationBarWasHidden = navigationController?.isNavigationBarHidden ?? false
        navigationController?.setNavigationBarHidden(true, animated: animated)
        navigationController?.interactivePopGestureRecognizer?.isEnabled = false
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        navigationController?.setNavigationBarHidden(navigationBarWasHidden, animated: animated)
        navigationController?.interactivePopGestureRecognizer?.isEnabled = true
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if traitCollection.horizontalSizeClass != previousTraitCollection?.horizontalSizeClass {
            updateGridWidthConstraints()
        }
        updateHeaderLayout()
    }

    private func updateHeaderLayout() {
        let stacked = traitCollection.horizontalSizeClass == .compact && traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        headerBar.axis = stacked ? .vertical : .horizontal
        headerBar.arrangedSubviews.first?.setContentHuggingPriority(stacked ? .defaultLow : .required, for: .horizontal)
        headerBar.alignment = stacked ? .fill : .center
    }

    @objc private func openSources() {
        guard !saving else { return }
        let sources = SourcesListViewController(environment: environment)
        sources.onConnected = { [weak self] ids, selection in
            guard let self else { return }
            dismiss(animated: true) {
                self.selectedAssetIDs.formUnion(selection)
                self.dirty = self.dirty || !selection.isEmpty
                self.gridChild?.applySelection(self.selectedAssetIDs)
                self.assetScope = nil
                self.sourceIDs = Set(ids)
                self.remoteSourceID = nil
                self.includesRemoteMedia = true
                self.refreshSourceScope()
            }
        }
        let navigation = UINavigationController(rootViewController: sources)
        navigation.modalPresentationStyle = .fullScreen
        present(navigation, animated: true)
    }

    private func refreshSourceScope() {
        gridChild?.setScope(sourceIDs: sourceIDs, assetIDs: assetScope)
        guard let sourceIDs else { return }
        scopeTask?.cancel()
        scopeTask = Task { [weak self] in
            guard let self else { return }
            guard !Task.isCancelled, self.sourceIDs == sourceIDs else { return }
            collections = ((try? await environment.assets.collections()) ?? []).filter {
                $0.sourceID.map(sourceIDs.contains) == true && CollectionListing.visibleInFolderList(type: $0.type, memberCount: $0.memberCount)
            }
            directoriesTable.reloadData()
            updateSummary()
        }
    }

    @objc private func openAISettings() {
        guard !saving, let frameID else { return }
        if dirty {
            let alert = UIAlertController(title: L10n.string("playback.unsaved_title"), message: AIText.value("Save your photo selection before editing AI settings.", "请先保存选片草稿，再编辑 AI 设置。"), preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: L10n.string("common.save"), style: .default) { [weak self] _ in
                self?.performSave { [weak self] in self?.openAISettings() }
            })
            alert.addAction(UIAlertAction(title: L10n.string("common.cancel"), style: .cancel))
            present(alert, animated: true)
            return
        }
        let editor = AIFrameViewController(environment: environment, frameID: frameID)
        editor.onSaved = { [weak self] in self?.reload() }
        let navigation = UINavigationController(rootViewController: editor)
        navigation.modalPresentationStyle = .fullScreen
        present(navigation, animated: true)
    }

    private func updateGridWidthConstraints() {
        let useColumn = traitCollection.horizontalSizeClass == .regular
        NSLayoutConstraint.deactivate(useColumn ? gridEdgeConstraints : gridColumnConstraints)
        NSLayoutConstraint.activate(useColumn ? gridColumnConstraints : gridEdgeConstraints)
    }

    private func embedGrid() {
        let grid = AssetGridViewController(environment: environment)
        grid.selectionEnabled = true
        grid.onSelectionChange = { [weak self] ids in
            self?.selectedAssetIDs = ids
            self?.dirty = true
            if let cover = self?.pendingCoverAssetID, !ids.contains(cover) {
                self?.pendingCoverAssetID = nil
                self?.gridChild?.coverAssetID = nil
            }
            self?.updateSummary()
            self?.directoriesTable.reloadData()
        }
        grid.onLongPressAsset = { [weak self] assetID, cell in
            self?.presentCoverOptions(assetID: assetID, anchor: cell)
        }
        grid.onCoverStarTap = { [weak self] assetID in
            self?.presentCoverPreview(assetID: assetID)
        }
        addChild(grid)
        grid.view.translatesAutoresizingMaskIntoConstraints = false
        photosContainer.addSubview(grid.view)
        NSLayoutConstraint.activate([
            grid.view.topAnchor.constraint(equalTo: photosContainer.topAnchor),
            grid.view.leadingAnchor.constraint(equalTo: photosContainer.leadingAnchor),
            grid.view.trailingAnchor.constraint(equalTo: photosContainer.trailingAnchor),
            grid.view.bottomAnchor.constraint(equalTo: photosContainer.bottomAnchor),
        ])
        grid.didMove(toParent: self)
        if includesRemoteMedia { grid.enableRemoteMedia(sourceID: remoteSourceID) }
        gridChild = grid
    }

    private func presentCoverOptions(assetID: String, anchor: UIView) {
        let alert = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        alert.popoverPresentationController?.sourceView = anchor
        alert.popoverPresentationController?.sourceRect = anchor.bounds
        alert.addAction(UIAlertAction(
            title: L10n.string("frames.set_cover"),
            style: .default,
            handler: { [weak self] _ in
                self?.presentCoverPreview(assetID: assetID)
            }
        ))
        alert.addAction(UIAlertAction(title: L10n.string("common.cancel"), style: .cancel))
        present(alert, animated: true)
    }

    private func presentCoverPreview(assetID: String) {
        let trimmed = nameField.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        Task { [weak self] in
            guard let self else { return }
            let readyIDs = Set((try? await environment.assets.libraryItems(includeRemote: includesRemoteMedia, remoteSourceID: remoteSourceID))?.map(\.id) ?? [])
            let playable = selectedAssetIDs.intersection(readyIDs).count
            let preview = CoverPreviewViewController(
                environment: environment,
                relPath: gridChild?.relPath(for: assetID),
                frameName: trimmed.isEmpty ? L10n.string("frames.name_default") : trimmed,
                photoCount: playable
            ) { [weak self] in
                self?.setPendingCover(assetID)
            }
            preview.modalPresentationStyle = .overFullScreen
            preview.modalTransitionStyle = .crossDissolve
            present(preview, animated: true)
        }
    }

    private func setPendingCover(_ assetID: String) {
        pendingCoverAssetID = assetID
        dirty = true
        gridChild?.coverAssetID = assetID
        UIAccessibility.post(
            notification: .announcement,
            argument: L10n.string("frames.cover_set")
        )
    }

    private func reload() {
        Task { [weak self] in
            guard let self else { return }
            directorySourceNames = Dictionary(((try? environment.sources.webdavSources()) ?? []).map { ($0.id, $0.displayName) }, uniquingKeysWith: { first, _ in first })
            loading = true
            defer { loading = false }
            collections = ((try? await environment.assets.collections()) ?? [])
                .filter { CollectionListing.visibleInFolderList(type: $0.type, memberCount: $0.memberCount) }
            memberCache = [:]
            directoriesTable.reloadData()
            guard let frameID, let fetched = try? await environment.frameManager.frameDetail(frameID: frameID) else {
                gridChild?.applySelection(selectedAssetIDs)
                updateSummary()
                return
            }
            detail = fetched
            nameField.text = fetched.name
            let bundle = try? await environment.frameManager.framePlaybackBundle(frameID: frameID)
            if FrameStorageMode.isExplicit(bundle?.settingsJSON ?? "{}") || !fetched.includeSourceAssetRefs.isEmpty {
                includesRemoteMedia = true
                gridChild?.enableRemoteMedia(sourceID: remoteSourceID)
            }
            selectedAssetIDs = Set(fetched.includeAssetRefs + fetched.includeSourceAssetRefs.map { "remote:" + $0 }).union(initialSelection)
            let framePhotoIDs = Set((try? await environment.frames.buildPlayQueue(frameID: frameID)) ?? [])
            gridChild?.prioritizePhotos(selectedAssetIDs.union(framePhotoIDs))
            currentExcludes = Set(fetched.excludeRefs)
            dynamicRules = Dictionary(uniqueKeysWithValues: fetched.rules.map { ($0.collectionId, $0) })
            recursiveFlags = Dictionary(uniqueKeysWithValues: fetched.rules.map { ($0.collectionId, $0.recursive) })
            dirty = !initialSelection.isEmpty
            initialSelection.removeAll()
            pendingManualOrder = nil
            pendingCoverAssetID = fetched.coverAssetId
            if let explicit = fetched.coverAssetId {
                gridChild?.coverAssetID = explicit
            } else {
                gridChild?.coverAssetID = (try? await environment.frames.buildPlayQueue(frameID: frameID))?.first
            }
            gridChild?.applySelection(selectedAssetIDs)
            collections = ((try? await environment.assets.collections()) ?? [])
                .filter { CollectionListing.visibleInFolderList(type: $0.type, memberCount: $0.memberCount) }
            memberCache = [:]
            directoriesTable.reloadData()
            manualButton.isHidden = fetched.orderMode != PlaybackQueue.orderManual
            updateSummary()
            updatePendingBanner()
            refreshSourceScope()
        }
    }

    private func updatePendingBanner() {
        Task { [weak self] in
            guard let self, let detail else { return }
            let counts = try? await environment.frameManager.frameCounts(frameID: detail.id)
            let hasSelection = !detail.includeAssetRefs.isEmpty || !detail.includeSourceAssetRefs.isEmpty
            pendingLabel.isHidden = !hasSelection || (counts?.playable ?? 0) > 0
        }
    }

    private func updateSummary() {
        let selected = selectedAssetIDs
        Task { [weak self] in
            guard let self else { return }
            let readyIDs = Set((try? await environment.assets.libraryItems(includeRemote: includesRemoteMedia, remoteSourceID: remoteSourceID))?.map(\.id) ?? [])
            let playable = selected.intersection(readyIDs).count
            summaryLabel.text = FormatStrings.string(
                "library.selection_summary",
                selected.count,
                playable
            )
        }
    }

    private func members(of collectionID: String) async -> [String] {
        if let cached = memberCache[collectionID] { return cached }
        let members = (try? await environment.assets.collectionAssetIDs(collectionID: collectionID, includeRemote: includesRemoteMedia, recursive: recursiveFlags[collectionID] ?? false)) ?? []
        memberCache[collectionID] = members
        return members
    }

    @objc private func handleTabChange() {
        let showPhotos = tabControl.selectedSegmentIndex == 0
        photosContainer.isHidden = !showPhotos
        directoriesTable.isHidden = showPhotos
        noteLabel.isHidden = !showPhotos
    }

    @objc private func handleNameChanged() {
        dirty = true
    }

    @objc private func handleManualOrder() {
        guard let detail else { return }
        let ordered = detail.manualOrderedRefs.filter { selectedAssetIDs.contains($0) }
        let loadedNew = detail.newRefs.filter { selectedAssetIDs.contains($0) }
        let added = selectedAssetIDs
            .subtracting(Set(detail.manualOrderedRefs))
            .subtracting(Set(detail.newRefs))
            .sorted()
        let initial = ordered + loadedNew.sorted() + added
        let newSet = Set(loadedNew).union(added)
        let order = ManualOrderViewController(
            environment: environment,
            initialOrder: initial,
            newRefs: newSet,
            hasExcludes: !currentExcludes.isEmpty
        ) { [weak self] result in
            self?.pendingManualOrder = result
            self?.dirty = true
        }
        order.modalPresentationStyle = .fullScreen
        present(order, animated: true)
    }

    @objc private func handleBack() {
        guard !saving else { return }
        guard dirty else {
            close()
            return
        }
        let alert = UIAlertController(
            title: L10n.string("playback.unsaved_title"),
            message: nil,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(
            title: L10n.string("common.discard"),
            style: .destructive,
            handler: { [weak self] _ in self?.close() }
        ))
        alert.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel
        ))
        present(alert, animated: true)
    }

    private func close() {
        if let navigationController, navigationController.viewControllers.first != self {
            navigationController.popViewController(animated: true)
        } else { dismiss(animated: true) }
    }

    @objc private func handleSave() {
        performSave { [weak self] in self?.finishAfterSave() }
    }

    private func performSave(completion: @escaping () -> Void) {
        guard !saving, !loading else { return }
        guard !selectedAssetIDs.isEmpty || !dynamicRules.isEmpty else {
            let alert = UIAlertController(title: AIText.value("Choose photos", "请选择照片"), message: L10n.string("library.selection_note_static"), preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: L10n.string("common.done"), style: .default))
            present(alert, animated: true)
            return
        }
        saving = true
        view.isUserInteractionEnabled = false
        saveButton.isEnabled = false
        saveButton.setTitle(AIText.value("Saving…", "正在保存…"))
        Task { [weak self] in
            guard let self else { return }
            defer {
                saving = false
                view.isUserInteractionEnabled = true
                saveButton.isEnabled = true
                saveButton.setTitle(routeToMainAfterSave ? AIText.value("Confirm and play", "确认并播放") : L10n.string("common.save"))
            }
            do {
                if let frameID, detail == nil { detail = try await environment.frameManager.frameDetail(frameID: frameID) }
                let trimmed = nameField.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let newFrameName = frameID == nil ? (trimmed.isEmpty ? L10n.string("frames.name_default") : trimmed) : nil
                let savingFrameID = frameID ?? UUID().uuidString
                let loaded = Set((detail?.includeAssetRefs ?? []) + (detail?.includeSourceAssetRefs ?? []).map { "remote:" + $0 })
                let addIncludes = selectedAssetIDs.subtracting(loaded).map {
                    MediaReference(runtimeID: $0).selection
                }
                var removeRefs = loaded.subtracting(selectedAssetIDs).map {
                    MediaReference(runtimeID: $0).selection
                }
                removeRefs += Set(detail?.excludeRefs ?? []).subtracting(currentExcludes).map {
                    MediaReference(runtimeID: $0).selection
                }

                let now = Int(Date().timeIntervalSince1970 * 1000)
                let loadedRules = Dictionary(uniqueKeysWithValues: (detail?.rules ?? []).map { ($0.collectionId, $0) })
                var ruleChanges: [RuleChange] = []
                for (collectionID, rule) in dynamicRules where loadedRules[collectionID] != rule {
                    ruleChanges.append(RuleChange(
                        collectionId: collectionID,
                        recursive: rule.recursive,
                        includeFuture: true,
                        confirmed: true,
                        members: [],
                        addedAt: Int64(now)
                    ))
                }
                let removeRules = (detail?.rules ?? [])
                    .map(\.collectionId)
                    .filter { dynamicRules[$0] == nil }

                let result = try await environment.frames.save(
                    frameID: savingFrameID,
                    baseVersion: detail?.selectionVersion ?? 0,
                    addIncludes: addIncludes,
                    removeRefs: removeRefs,
                    ruleChanges: ruleChanges,
                    removeRules: removeRules,
                    manualOrderRefs: pendingManualOrder,
                    newFrameName: newFrameName,
                    frameName: trimmed.isEmpty ? nil : trimmed,
                    enableRemoteMedia: selectedAssetIDs.contains(where: { $0.hasPrefix("remote:") })
                        || collections.contains { dynamicRules[$0.id] != nil && $0.sourceID.map { directorySourceNames[$0] != nil } == true },
                    coverAssetID: pendingCoverAssetID == detail?.coverAssetId ? nil : pendingCoverAssetID
                )
                switch result {
                case .ok:
                    frameID = savingFrameID
                    if let bundle = try? await environment.frameManager.framePlaybackBundle(frameID: savingFrameID),
                       FrameStorageMode.isExplicit(bundle.settingsJSON), FrameStorageMode.read(bundle.settingsJSON) == .local {
                        await environment.frameStorage.start(frameID: savingFrameID)
                    }
                    self.detail = try await environment.frameManager.frameDetail(frameID: savingFrameID)
                    dirty = false
                    saving = false
                    completion()
                default:
                    showConflict()
                }
            } catch {
                showSaveError()
            }
        }
    }

    private func showSaveError() {
        let alert = UIAlertController(title: AIText.value("Unable to save", "保存失败"), message: AIText.value("Your draft is preserved. Please try saving again.", "草稿已保留，请重试保存。"), preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: L10n.string("common.done"), style: .default))
        present(alert, animated: true)
    }

    private func finishAfterSave() {
        dirty = false
        if routeToMainAfterSave, let window = view.window, let frameID {
            let main = MainContainerViewController(environment: environment)
            main.autoPlayFrameID = frameID
            try? environment.settings.setOnboardingCompleted(true)
            RootTransition.setRoot(main, in: window)
        } else {
            close()
        }
    }

    private func showConflict() {
        let alert = UIAlertController(
            title: nil,
            message: L10n.string("error.selection_conflict.body"),
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(
            title: L10n.string("common.retry"),
            style: .default,
            handler: { [weak self] _ in self?.retryConflict() }
        ))
        present(alert, animated: true)
    }

    func retryConflict() {
        guard let frameID, let previous = detail else { return }
        Task { [weak self] in
            guard let self else { return }
            do {
                let latest = try await environment.frameManager.frameDetail(frameID: frameID)
                let previousIDs = Set(previous.includeAssetRefs + previous.includeSourceAssetRefs.map { "remote:" + $0 })
                let latestIDs = Set(latest.includeAssetRefs + latest.includeSourceAssetRefs.map { "remote:" + $0 })
                let added = selectedAssetIDs.subtracting(previousIDs)
                let removed = previousIDs.subtracting(selectedAssetIDs)
                selectedAssetIDs = latestIDs.union(added).subtracting(removed)
                selectedAssetIDs.subtract(Set(latest.excludeRefs).subtracting(previous.excludeRefs))
                let restored = Set(previous.excludeRefs).subtracting(currentExcludes)
                currentExcludes = Set(latest.excludeRefs).subtracting(restored)
                let previousRules = Dictionary(uniqueKeysWithValues: previous.rules.map { ($0.collectionId, $0) })
                let draftRules = dynamicRules
                dynamicRules = Dictionary(uniqueKeysWithValues: latest.rules.map { ($0.collectionId, $0) })
                for key in Set(previousRules.keys).union(draftRules.keys) where previousRules[key] != draftRules[key] {
                    dynamicRules[key] = draftRules[key]
                }
                if nameField.text == previous.name { nameField.text = latest.name }
                if pendingCoverAssetID == previous.coverAssetId {
                    pendingCoverAssetID = latest.coverAssetId
                    gridChild?.coverAssetID = latest.coverAssetId
                }
                detail = latest
                gridChild?.applySelection(selectedAssetIDs)
                directoriesTable.reloadData()
                updateSummary()
                dirty = true
            } catch { showSaveError() }
        }
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        return true
    }

    private var directorySourceNames: [String: String] = [:]

    private enum DirectoryRow {
        case sourceHeader(String)
        case collection(CollectionDTO)
        case recursiveToggle(CollectionDTO)
        case dynamicToggle(CollectionDTO)
        case excludedHeader
        case excluded(String)
    }

    private var directoryRows: [DirectoryRow] {
        var rows: [DirectoryRow] = []
        for sourceID in Set(collections.map { directorySourceNames[$0.sourceID ?? ""] == nil ? "" : ($0.sourceID ?? "") }).sorted() {
            rows.append(.sourceHeader(directorySourceNames[sourceID] ?? AIText.value("On this device", "本机目录")))
            for collection in collections where (directorySourceNames[collection.sourceID ?? ""] == nil ? "" : (collection.sourceID ?? "")) == sourceID {
            rows.append(.collection(collection))
            rows.append(.recursiveToggle(collection))
            rows.append(.dynamicToggle(collection))
        }
        }
        if !currentExcludes.isEmpty {
            rows.append(.excludedHeader)
            for ref in currentExcludes.sorted() {
                rows.append(.excluded(ref))
            }
        }
        return rows
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        directoryRows.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        switch directoryRows[indexPath.row] {
        case .sourceHeader(let title):
            let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
            cell.textLabel?.text = title
            cell.textLabel?.font = ThemeFont.heading()
            cell.textLabel?.numberOfLines = 0
            cell.backgroundColor = .clear
            cell.selectionStyle = .none
            return cell
        case .collection(let collection):
            let cell = tableView.dequeueReusableCell(withIdentifier: "DirectoryCell", for: indexPath)
            var content = cell.defaultContentConfiguration()
            content.text = CollectionDisplayName.display(name: collection.name, type: collection.type)
            content.textProperties.font = ThemeFont.body()
            content.textProperties.color = ThemeColor.textPrimary
            content.secondaryText = "\(collection.memberCount)"
            content.secondaryTextProperties.font = ThemeFont.meta()
            content.secondaryTextProperties.color = ThemeColor.textSecondary
            cell.contentConfiguration = content
            cell.backgroundColor = .clear
            let dynamic = dynamicRules[collection.id] != nil
            cell.imageView?.image = UIImage(systemName: dynamic ? "checkmark.circle.fill" : "circle")
            cell.imageView?.tintColor = dynamic ? ThemeColor.accentAction : ThemeColor.textSecondary
            cell.accessibilityTraits = [.button]
            if includesRemoteMedia && collection.sourceID != nil { cell.accessoryType = .detailDisclosureButton }
            else { cell.accessoryType = .none }
            return cell
        case .recursiveToggle(let collection):
            return makeToggleCell(
                tableView,
                indexPath: indexPath,
                titleKey: "rule.recursive_toggle",
                isOn: dynamicRules[collection.id]?.recursive
                    ?? recursiveFlags[collection.id]
                    ?? false,
                tag: indexPath.row,
                action: #selector(handleRecursiveSwitch(_:))
            )
        case .dynamicToggle(let collection):
            return makeToggleCell(
                tableView,
                indexPath: indexPath,
                titleKey: "rule.dynamic_toggle",
                isOn: dynamicRules[collection.id]?.includeFuture ?? false,
                tag: indexPath.row,
                action: #selector(handleDynamicSwitch(_:))
            )
        case .excludedHeader:
            let cell = tableView.dequeueReusableCell(withIdentifier: "DirectoryCell", for: indexPath)
            var content = cell.defaultContentConfiguration()
            content.text = L10n.string("rule.manage_excluded")
            content.textProperties.font = ThemeFont.body()
            content.textProperties.color = ThemeColor.textPrimary
            content.secondaryText = String(
                format: L10n.string("rule.excluded_count"),
                currentExcludes.count
            )
            content.secondaryTextProperties.font = ThemeFont.meta()
            content.secondaryTextProperties.color = ThemeColor.textSecondary
            cell.contentConfiguration = content
            cell.backgroundColor = .clear
            cell.selectionStyle = .none
            cell.imageView?.image = nil
            return cell
        case .excluded:
            let cell = tableView.dequeueReusableCell(withIdentifier: "ExcludedCell", for: indexPath)
            var content = cell.defaultContentConfiguration()
            content.text = L10n.string("library.status_excluded")
            content.textProperties.font = ThemeFont.body()
            content.textProperties.color = ThemeColor.textSecondary
            cell.contentConfiguration = content
            cell.backgroundColor = .clear
            cell.imageView?.image = nil
            return cell
        }
    }

    private func makeToggleCell(
        _ tableView: UITableView,
        indexPath: IndexPath,
        titleKey: String,
        isOn: Bool,
        tag: Int,
        action: Selector
    ) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "ToggleCell", for: indexPath)
        var content = cell.defaultContentConfiguration()
        content.text = L10n.string(titleKey)
        content.textProperties.font = ThemeFont.body()
        content.textProperties.color = ThemeColor.textSecondary
        cell.contentConfiguration = content
        cell.backgroundColor = .clear
        cell.selectionStyle = .none
        cell.imageView?.image = nil
        let toggle = UISwitch()
        toggle.isOn = isOn
        toggle.onTintColor = ThemeColor.accentAction
        toggle.tag = tag
        toggle.addTarget(self, action: action, for: .valueChanged)
        toggle.accessibilityLabel = L10n.string(titleKey)
        cell.accessoryView = toggle
        return cell
    }

    func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
        UITableView.automaticDimension
    }

    func tableView(_ tableView: UITableView, estimatedHeightForRowAt indexPath: IndexPath) -> CGFloat {
        ThemeMetrics.touchTarget
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        switch directoryRows[indexPath.row] {
        case .collection(let collection):
            toggleCollection(collection)
        case .excluded(let ref):
            currentExcludes.remove(ref)
            dirty = true
            tableView.reloadData()
        default:
            break
        }
    }

    func tableView(_ tableView: UITableView, accessoryButtonTappedForRowWith indexPath: IndexPath) {
        guard case .collection(let collection) = directoryRows[indexPath.row] else { return }
        let grid = AssetGridViewController(environment: environment, collectionID: collection.id,
            headerTitle: CollectionDisplayName.display(name: collection.name, type: collection.type), showsCloseButton: true)
        grid.selectionEnabled = true
        grid.enableRemoteMedia(sourceID: collection.sourceID)
        grid.applySelection(selectedAssetIDs)
        grid.onSelectionChange = { [weak self] ids in
            self?.selectedAssetIDs = ids
            self?.dirty = true
            self?.gridChild?.applySelection(ids)
            self?.updateSummary()
        }
        grid.modalPresentationStyle = .fullScreen
        present(grid, animated: true)
    }

    private func toggleCollection(_ collection: CollectionDTO) {
        if dynamicRules[collection.id] != nil {
            dynamicRules.removeValue(forKey: collection.id)
            dirty = true
            directoriesTable.reloadData()
            return
        }
        Task { [weak self] in
            guard let self else { return }
            let members = await members(of: collection.id)
            let memberSet = Set(members)
            if memberSet.isSubset(of: selectedAssetIDs), !memberSet.isEmpty {
                selectedAssetIDs.subtract(memberSet)
            } else {
                selectedAssetIDs.formUnion(memberSet)
            }
            dirty = true
            gridChild?.applySelection(selectedAssetIDs)
            updateSummary()
            directoriesTable.reloadData()
        }
    }

    @objc private func handleRecursiveSwitch(_ sender: UISwitch) {
        guard case .recursiveToggle(let collection) = directoryRows[sender.tag] else { return }
        recursiveFlags[collection.id] = sender.isOn
        memberCache[collection.id] = nil
        if let rule = dynamicRules[collection.id] {
            dynamicRules[collection.id] = SaveRuleRow(
                collectionId: rule.collectionId,
                recursive: sender.isOn,
                includeFuture: rule.includeFuture
            )
        }
        dirty = true
    }

    @objc private func handleDynamicSwitch(_ sender: UISwitch) {
        guard case .dynamicToggle(let collection) = directoryRows[sender.tag] else { return }
        if !sender.isOn {
            dynamicRules.removeValue(forKey: collection.id)
            dirty = true
            directoriesTable.reloadData()
            return
        }
        let rule = SaveRuleRow(
            collectionId: collection.id,
            recursive: recursiveFlags[collection.id] ?? false,
            includeFuture: true
        )
        let alert = UIAlertController(
            title: nil,
            message: L10n.string("rule.dynamic_warning"),
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(
            title: L10n.string("common.continue"),
            style: .default,
            handler: { [weak self] _ in
                guard let self else { return }
                dynamicRules[collection.id] = rule
                dirty = true
                directoriesTable.reloadData()
            }
        ))
        alert.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel,
            handler: { [weak self] _ in
                self?.directoriesTable.reloadData()
            }
        ))
        present(alert, animated: true)
    }
}
