import UIKit

final class FrameEditViewController: UIViewController, UITextFieldDelegate, UITableViewDataSource, UITableViewDelegate {
    private let environment: AppEnvironment
    private var frameID: String?
    private let routeToMainAfterSave: Bool

    private var detail: FrameDetailDTO?
    private var collections: [CollectionDTO] = []
    private var memberCache: [String: [String]] = [:]
    private var selectedAssetIDs: Set<String> = []
    private var currentExcludes: Set<String> = []
    private var dynamicRules: [String: SaveRuleRow] = [:]
    private var recursiveFlags: [String: Bool] = [:]
    private var pendingManualOrder: [String]?
    private var dirty = false

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

    init(environment: AppEnvironment, frameID: String?, routeToMainAfterSave: Bool = false) {
        self.environment = environment
        self.frameID = frameID
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
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.accessibilityTraits.insert(.header)

        backButton.setContentHuggingPriority(.required, for: .horizontal)
        let topBar = UIStackView(arrangedSubviews: [backButton, titleLabel, UIView()])
        topBar.axis = .horizontal
        topBar.alignment = .center
        topBar.spacing = ThemeMetrics.space12

        let header = UIStackView(arrangedSubviews: [topBar, nameField])
        header.axis = .vertical
        header.spacing = ThemeMetrics.space12
        header.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(header)

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

        noteLabel.text = L10n.string("library.selection_note_static")
        noteLabel.font = ThemeFont.meta()
        noteLabel.adjustsFontForContentSizeCategory = true
        noteLabel.textColor = ThemeColor.textSecondary

        pendingLabel.text = L10n.string("player.all_pending_title")
        pendingLabel.font = ThemeFont.meta()
        pendingLabel.adjustsFontForContentSizeCategory = true
        pendingLabel.textColor = ThemeColor.statusWarning
        pendingLabel.numberOfLines = 0
        pendingLabel.isHidden = true

        manualButton.addTarget(self, action: #selector(handleManualOrder), for: .touchUpInside)
        manualButton.isHidden = true
        saveButton.addTarget(self, action: #selector(handleSave), for: .touchUpInside)

        let bottomBar = UIStackView(arrangedSubviews: [
            pendingLabel, summaryLabel, noteLabel, manualButton, saveButton,
        ])
        bottomBar.axis = .vertical
        bottomBar.spacing = ThemeMetrics.space8
        bottomBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bottomBar)

        let readable = header.widthAnchor.constraint(equalToConstant: ThemeMetrics.bodyMaxWidth)
        readable.priority = .defaultHigh
        NSLayoutConstraint.activate([
            header.topAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.topAnchor,
                constant: ThemeMetrics.space8
            ),
            header.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            header.leadingAnchor.constraint(
                greaterThanOrEqualTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            header.trailingAnchor.constraint(
                lessThanOrEqualTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            readable,
            tabControl.topAnchor.constraint(equalTo: header.bottomAnchor, constant: ThemeMetrics.space12),
            tabControl.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            tabControl.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            photosContainer.topAnchor.constraint(
                equalTo: tabControl.bottomAnchor,
                constant: ThemeMetrics.space8
            ),
            photosContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            photosContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            photosContainer.bottomAnchor.constraint(equalTo: bottomBar.topAnchor),
            directoriesTable.topAnchor.constraint(equalTo: photosContainer.topAnchor),
            directoriesTable.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            directoriesTable.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            directoriesTable.bottomAnchor.constraint(equalTo: photosContainer.bottomAnchor),
            bottomBar.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            bottomBar.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            bottomBar.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
        ])

        embedGrid()
        enableKeyboardDismissal()
        reload()
    }

    private func embedGrid() {
        let grid = AssetGridViewController(environment: environment)
        grid.selectionEnabled = true
        grid.onSelectionChange = { [weak self] ids in
            self?.selectedAssetIDs = ids
            self?.dirty = true
            self?.updateSummary()
            self?.directoriesTable.reloadData()
        }
        if frameID != nil {
            grid.onLongPressAsset = { [weak self] assetID, cell in
                self?.presentCoverOptions(assetID: assetID, anchor: cell)
            }
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
                self?.applyCover(assetID: assetID)
            }
        ))
        alert.addAction(UIAlertAction(title: L10n.string("common.cancel"), style: .cancel))
        present(alert, animated: true)
    }

    private func applyCover(assetID: String) {
        guard let frameID else { return }
        Task { [weak self] in
            guard let self else { return }
            guard (try? await environment.frameManager.setCover(frameID: frameID, assetID: assetID)) != nil else {
                return
            }
            UIAccessibility.post(
                notification: .announcement,
                argument: L10n.string("frames.cover_set")
            )
        }
    }

    private func reload() {
        Task { [weak self] in
            guard let self else { return }
            collections = (try? await environment.assets.collections()) ?? []
            memberCache = [:]
            directoriesTable.reloadData()
            guard let frameID, let fetched = try? await environment.frameManager.frameDetail(frameID: frameID) else {
                return
            }
            detail = fetched
            nameField.text = fetched.name
            selectedAssetIDs = Set(fetched.includeAssetRefs)
            currentExcludes = Set(fetched.excludeRefs)
            dynamicRules = Dictionary(uniqueKeysWithValues: fetched.rules.map { ($0.collectionId, $0) })
            recursiveFlags = Dictionary(uniqueKeysWithValues: fetched.rules.map { ($0.collectionId, $0.recursive) })
            dirty = false
            pendingManualOrder = nil
            gridChild?.applySelection(selectedAssetIDs)
            collections = (try? await environment.assets.collections()) ?? []
            memberCache = [:]
            directoriesTable.reloadData()
            manualButton.isHidden = fetched.orderMode != PlaybackQueue.orderManual
            updateSummary()
            updatePendingBanner()
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
        summaryLabel.text = FormatStrings.string(
            "library.selection_summary",
            selectedAssetIDs.count,
            selectedAssetIDs.count
        )
    }

    private func members(of collectionID: String) async -> [String] {
        if let cached = memberCache[collectionID] { return cached }
        let members = (try? await environment.assets.collectionAssetIDs(collectionID: collectionID)) ?? []
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
        guard dirty else {
            dismiss(animated: true)
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
            handler: { [weak self] _ in self?.dismiss(animated: true) }
        ))
        alert.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel
        ))
        present(alert, animated: true)
    }

    @objc private func handleSave() {
        Task { [weak self] in
            guard let self else { return }
            let trimmed = nameField.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if frameID == nil {
                let createdName = trimmed.isEmpty ? L10n.string("frames.name_default") : trimmed
                guard let created = try? await environment.frameManager.createFrame(name: createdName) else { return }
                frameID = created
                detail = try? await environment.frameManager.frameDetail(frameID: created)
            }
            guard let detail, let frameID else { return }
            if !trimmed.isEmpty, trimmed != detail.name {
                try? await environment.frameManager.renameFrame(frameID: frameID, name: trimmed)
            }
            let loaded = Set(detail.includeAssetRefs)
            let addIncludes = selectedAssetIDs.subtracting(loaded).map {
                SelectionRefEdit(assetId: $0, sourceAssetId: nil)
            }
            var removeRefs = loaded.subtracting(selectedAssetIDs).map {
                SelectionRefEdit(assetId: $0, sourceAssetId: nil)
            }
            removeRefs += Set(detail.excludeRefs).subtracting(currentExcludes).map {
                SelectionRefEdit(assetId: $0, sourceAssetId: nil)
            }

            let now = Int(Date().timeIntervalSince1970 * 1000)
            let loadedRules = Dictionary(uniqueKeysWithValues: detail.rules.map { ($0.collectionId, $0) })
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
            let removeRules = detail.rules
                .map(\.collectionId)
                .filter { dynamicRules[$0] == nil }

            let result = try? await environment.frames.save(
                frameID: frameID,
                baseVersion: detail.selectionVersion,
                addIncludes: addIncludes,
                removeRefs: removeRefs,
                ruleChanges: ruleChanges,
                removeRules: removeRules,
                manualOrderRefs: pendingManualOrder
            )
            switch result {
            case .ok:
                finishAfterSave()
            default:
                showConflict()
            }
        }
    }

    private func finishAfterSave() {
        dirty = false
        if routeToMainAfterSave, let window = view.window, let frameID {
            let main = MainContainerViewController(environment: environment)
            main.autoOpenSettingsFrameID = frameID
            RootTransition.setRoot(main, in: window)
        } else {
            dismiss(animated: true)
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
            handler: { [weak self] _ in self?.reload() }
        ))
        present(alert, animated: true)
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        return true
    }

    private enum DirectoryRow {
        case collection(CollectionDTO)
        case recursiveToggle(CollectionDTO)
        case dynamicToggle(CollectionDTO)
        case excludedHeader
        case excluded(String)
    }

    private var directoryRows: [DirectoryRow] {
        var rows: [DirectoryRow] = []
        for collection in collections {
            rows.append(.collection(collection))
            rows.append(.recursiveToggle(collection))
            rows.append(.dynamicToggle(collection))
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
        case .collection(let collection):
            let cell = tableView.dequeueReusableCell(withIdentifier: "DirectoryCell", for: indexPath)
            var content = cell.defaultContentConfiguration()
            content.text = collection.name
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
