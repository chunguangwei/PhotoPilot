import UIKit

final class LibraryViewController: UIViewController, UITableViewDataSource, UITableViewDelegate, UITextFieldDelegate {
    private static let searchDebounceNanos: UInt64 = 300_000_000

    private let environment: AppEnvironment
    private let tabControl: UISegmentedControl
    private let photosContainer = UIView()
    private let foldersTable = UITableView(frame: .zero, style: .plain)
    private let selectButton = ActionButton(titleKey: "common.select", style: .secondary)
    private let searchField = UITextField()
    private let filterButton = ActionButton(titleKey: "common.filter", style: .secondary)
    private let sortButton = ActionButton(titleKey: "common.sort", style: .secondary)
    private let filterActiveLabel = UILabel()
    private let clearFilterButton = ActionButton(titleKey: "common.clear_filter", style: .secondary)
    private let selectionBar = UIStackView()
    private let selectionSummaryLabel = UILabel()
    private let addToFrameButton = ActionButton(titleKey: "library.add_to_frame", style: .primary)

    private var collections: [CollectionDTO] = []
    private var gridChild: AssetGridViewController?
    private var query = LibraryQuery()
    private var sources: [SourceOptionDTO] = []
    private var selectionMode = false
    private var readyIDs: Set<String> = []
    private var searchTask: Task<Void, Never>?

    init(environment: AppEnvironment) {
        self.environment = environment
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
        view.backgroundColor = ThemeColor.canvas

        let titleLabel = UILabel()
        titleLabel.text = L10n.string("library.title")
        titleLabel.font = ThemeFont.title()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.numberOfLines = 0
        titleLabel.accessibilityTraits.insert(.header)

        selectButton.addTarget(self, action: #selector(handleSelectToggle), for: .touchUpInside)
        selectButton.setContentHuggingPriority(.required, for: .horizontal)
        let titleRow = UIStackView(arrangedSubviews: [titleLabel, selectButton])
        titleRow.axis = .horizontal
        titleRow.alignment = .center
        titleRow.spacing = ThemeMetrics.space16

        searchField.placeholder = L10n.string("library.search_placeholder")
        searchField.font = ThemeFont.body()
        searchField.adjustsFontForContentSizeCategory = true
        searchField.textColor = ThemeColor.textPrimary
        searchField.borderStyle = .roundedRect
        searchField.clearButtonMode = .whileEditing
        searchField.returnKeyType = .search
        searchField.delegate = self
        searchField.accessibilityLabel = L10n.string("library.search_placeholder")
        searchField.addTarget(self, action: #selector(handleSearchChanged), for: .editingChanged)
        searchField.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true

        filterButton.addTarget(self, action: #selector(handleFilter), for: .touchUpInside)
        sortButton.addTarget(self, action: #selector(handleSort), for: .touchUpInside)
        filterActiveLabel.text = L10n.string("library.filter_active")
        filterActiveLabel.font = ThemeFont.meta()
        filterActiveLabel.adjustsFontForContentSizeCategory = true
        filterActiveLabel.textColor = ThemeColor.accentAction
        filterActiveLabel.isHidden = true
        filterActiveLabel.setContentHuggingPriority(.required, for: .horizontal)
        clearFilterButton.addTarget(self, action: #selector(handleClearFilter), for: .touchUpInside)
        clearFilterButton.isHidden = true
        let filterRow = UIStackView(arrangedSubviews: [
            filterButton, sortButton, filterActiveLabel, clearFilterButton, UIView(),
        ])
        filterRow.axis = .horizontal
        filterRow.alignment = .center
        filterRow.spacing = ThemeMetrics.space8

        tabControl.selectedSegmentIndex = 0
        tabControl.addTarget(self, action: #selector(handleTabChange), for: .valueChanged)
        tabControl.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true

        let header = UIStackView(arrangedSubviews: [titleRow, searchField, filterRow, tabControl])
        header.axis = .vertical
        header.spacing = ThemeMetrics.space12
        header.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(header)

        photosContainer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(photosContainer)

        foldersTable.dataSource = self
        foldersTable.delegate = self
        foldersTable.backgroundColor = .clear
        foldersTable.separatorColor = ThemeColor.separator
        foldersTable.register(UITableViewCell.self, forCellReuseIdentifier: "CollectionCell")
        foldersTable.isHidden = true
        foldersTable.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(foldersTable)

        selectionSummaryLabel.font = ThemeFont.body()
        selectionSummaryLabel.adjustsFontForContentSizeCategory = true
        selectionSummaryLabel.textColor = ThemeColor.textPrimary
        addToFrameButton.addTarget(self, action: #selector(handleAddToFrame), for: .touchUpInside)
        selectionBar.axis = .vertical
        selectionBar.spacing = ThemeMetrics.space8
        selectionBar.addArrangedSubview(selectionSummaryLabel)
        selectionBar.addArrangedSubview(addToFrameButton)
        selectionBar.isHidden = true
        selectionBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(selectionBar)

        let readable = header.widthAnchor.constraint(equalToConstant: ThemeMetrics.bodyMaxWidth)
        readable.priority = .defaultHigh
        NSLayoutConstraint.activate([
            header.topAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.topAnchor,
                constant: ThemeMetrics.space24
            ),
            header.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            header.leadingAnchor.constraint(
                greaterThanOrEqualTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInset
            ),
            header.trailingAnchor.constraint(
                lessThanOrEqualTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            readable,
            photosContainer.topAnchor.constraint(
                equalTo: header.bottomAnchor,
                constant: ThemeMetrics.space12
            ),
            photosContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            photosContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            photosContainer.bottomAnchor.constraint(equalTo: selectionBar.topAnchor),
            foldersTable.topAnchor.constraint(equalTo: photosContainer.topAnchor),
            foldersTable.leadingAnchor.constraint(equalTo: photosContainer.leadingAnchor),
            foldersTable.trailingAnchor.constraint(equalTo: photosContainer.trailingAnchor),
            foldersTable.bottomAnchor.constraint(equalTo: photosContainer.bottomAnchor),
            selectionBar.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            selectionBar.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            selectionBar.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
        ])

        embedGrid()
        enableKeyboardDismissal()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refreshItems()
        refreshCollections()
    }

    private func embedGrid() {
        let grid = AssetGridViewController(environment: environment)
        grid.onSelectionChange = { [weak self] ids in
            self?.updateSelectionBar(ids)
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

    private func refreshItems() {
        Task { [weak self] in
            guard let self else { return }
            async let sourcesFetch = environment.assets.sourceOptions()
            var base: [LibraryItemDTO] = []
            let includeReady = query.statuses.isEmpty || query.statuses.contains("ready")
            let includeFailed = query.statuses.contains("failed")
            if includeReady {
                base += (try? await environment.assets.libraryItems()) ?? []
            }
            readyIDs = Set(base.map(\.id))
            if includeFailed {
                base += (try? await environment.assets.libraryItems(readiness: Readiness.failed.rawValue)) ?? []
            }
            if query.statuses.isEmpty || query.statuses.contains("pending") {
                base += (try? await environment.assets.pendingSourceItems()) ?? []
            }
            if !query.searchText.isEmpty,
               let ids = try? await environment.assets.searchAssetIDs(matching: query.searchText) {
                base = base.filter { ids.contains($0.id) }
            }
            if !query.sourceIDs.isEmpty,
               let ids = try? await environment.assets.assetIDs(inSourceIDs: query.sourceIDs) {
                base = base.filter { ids.contains($0.id) }
            }
            if !query.orientations.isEmpty {
                base = base.filter { query.orientations.contains($0.orientationClass) }
            }
            if query.noDateOnly {
                base = base.filter { $0.capturedAtMillis == nil }
            }
            base = Self.sorted(base, by: query.sort)

            sources = (try? await sourcesFetch) ?? []
            let result = base
            let grid = gridChild
            grid?.emptyTitleKey = query.isDefault ? nil : "library.search_no_result"
            grid?.emptyBodyKey = query.isDefault ? nil : ""
            grid?.setExternalItems(result)
            updateFilterActiveUI()
            updateSelectionBar(grid?.selectedAssetIDs ?? [])
        }
    }

    private static func sorted(_ items: [LibraryItemDTO], by sort: LibraryQuery.Sort) -> [LibraryItemDTO] {
        items.sorted {
            switch sort {
            case .capturedDesc:
                let left = $0.capturedAtMillis ?? $0.importedAtMillis
                let right = $1.capturedAtMillis ?? $1.importedAtMillis
                if left != right { return left > right }
            case .capturedAsc:
                let left = $0.capturedAtMillis ?? $0.importedAtMillis
                let right = $1.capturedAtMillis ?? $1.importedAtMillis
                if left != right { return left < right }
            case .importedDesc:
                if $0.importedAtMillis != $1.importedAtMillis {
                    return $0.importedAtMillis > $1.importedAtMillis
                }
            case .nameAsc:
                let left = $0.remoteKey ?? ""
                let right = $1.remoteKey ?? ""
                let order = left.localizedCaseInsensitiveCompare(right)
                if order != .orderedSame { return order == .orderedAscending }
            }
            return $0.id < $1.id
        }
    }

    private func updateFilterActiveUI() {
        filterActiveLabel.isHidden = query.isDefault
        clearFilterButton.isHidden = query.isDefault
    }

    private func updateSelectionBar(_ ids: Set<String>) {
        let readyCount = ids.filter { readyIDs.contains($0) }.count
        let text = FormatStrings.string("library.selection_summary", ids.count, readyCount)
        selectionSummaryLabel.text = text
        addToFrameButton.isEnabled = !ids.isEmpty
        addToFrameButton.alpha = ids.isEmpty ? ThemeMetrics.controlOpacity : 1
        if selectionMode {
            UIAccessibility.post(notification: .announcement, argument: text)
        }
    }

    private func refreshCollections() {
        Task { [weak self] in
            guard let self else { return }
            collections = ((try? await environment.assets.collections()) ?? [])
                .filter { CollectionListing.visibleInFolderList(type: $0.type, memberCount: $0.memberCount) }
            foldersTable.reloadData()
        }
    }

    @objc private func handleTabChange() {
        let showPhotos = tabControl.selectedSegmentIndex == 0
        photosContainer.isHidden = !showPhotos
        foldersTable.isHidden = showPhotos
        if !showPhotos {
            refreshCollections()
        }
    }

    @objc private func handleSelectToggle() {
        selectionMode.toggle()
        gridChild?.selectionEnabled = selectionMode
        if !selectionMode {
            gridChild?.applySelection([])
        }
        selectionBar.isHidden = !selectionMode
        selectButton.setTitleKey(selectionMode ? "common.cancel" : "common.select")
    }

    @objc private func handleSearchChanged() {
        searchTask?.cancel()
        searchTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: Self.searchDebounceNanos)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            query.searchText = searchField.text ?? ""
            refreshItems()
        }
    }

    @objc private func handleFilter() {
        let panel = LibraryFilterViewController(query: query, sources: sources) { [weak self] updated in
            self?.query = updated
            self?.refreshItems()
        }
        present(panel, animated: true)
    }

    @objc private func handleSort() {
        let sheet = UIAlertController(
            title: L10n.string("common.sort"),
            message: nil,
            preferredStyle: .actionSheet
        )
        let options: [(String, LibraryQuery.Sort)] = [
            ("library.sort_captured_desc", .capturedDesc),
            ("library.sort_captured_asc", .capturedAsc),
            ("library.sort_imported_desc", .importedDesc),
            ("library.sort_name_asc", .nameAsc),
        ]
        for (key, value) in options {
            sheet.addAction(UIAlertAction(
                title: L10n.string(key),
                style: .default,
                handler: { [weak self] _ in
                    self?.query.sort = value
                    self?.refreshItems()
                }
            ))
        }
        sheet.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel
        ))
        sheet.popoverPresentationController?.sourceView = sortButton
        present(sheet, animated: true)
    }

    @objc private func handleClearFilter() {
        query = LibraryQuery(searchText: searchField.text ?? "", sort: query.sort)
        refreshItems()
    }

    @objc private func handleAddToFrame() {
        let selected = gridChild?.selectedAssetIDs ?? []
        guard !selected.isEmpty else { return }
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
                        self?.addSelection(to: card.id, attempt: 0)
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

    private func addSelection(to frameID: String, attempt: Int) {
        let selected = gridChild?.selectedAssetIDs ?? []
        Task { [weak self] in
            guard let self else { return }
            guard let detail = try? await environment.frameManager.frameDetail(frameID: frameID) else {
                return
            }
            let result = try? await environment.frames.save(
                frameID: frameID,
                baseVersion: detail.selectionVersion,
                addIncludes: selected.map { SelectionRefEdit(assetId: $0, sourceAssetId: nil) }
            )
            switch result {
            case .ok:
                handleSelectToggle()
                UIAccessibility.post(
                    notification: .announcement,
                    argument: L10n.string("library.added_to_frame")
                )
            default:
                if attempt == 0 {
                    addSelection(to: frameID, attempt: 1)
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

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        return true
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        collections.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "CollectionCell", for: indexPath)
        let collection = collections[indexPath.row]
        var content = cell.defaultContentConfiguration()
        content.text = CollectionDisplayName.display(name: collection.name, type: collection.type)
        content.textProperties.font = ThemeFont.body()
        content.textProperties.color = ThemeColor.textPrimary
        content.secondaryText = "\(collection.memberCount)"
        content.secondaryTextProperties.font = ThemeFont.meta()
        content.secondaryTextProperties.color = ThemeColor.textSecondary
        cell.contentConfiguration = content
        cell.backgroundColor = .clear
        cell.accessoryType = .disclosureIndicator
        cell.accessibilityTraits = [.button]
        return cell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let collection = collections[indexPath.row]
        let grid = AssetGridViewController(
            environment: environment,
            collectionID: collection.id,
            headerTitle: CollectionDisplayName.display(name: collection.name, type: collection.type),
            showsCloseButton: true
        )
        grid.modalPresentationStyle = .fullScreen
        present(grid, animated: true)
    }

    func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
        UITableView.automaticDimension
    }

    func tableView(_ tableView: UITableView, estimatedHeightForRowAt indexPath: IndexPath) -> CGFloat {
        ThemeMetrics.touchTarget
    }
}
