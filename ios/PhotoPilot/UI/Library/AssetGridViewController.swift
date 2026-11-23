import UIKit

final class AssetGridViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegate {
    private static let pageSize = 120
    private static let prefetchThreshold = 30

    private let environment: AppEnvironment
    private let loader: ThumbnailLoader
    private let collectionID: String?
    private let headerTitle: String?
    private let showsCloseButton: Bool

    private var items: [LibraryItemDTO] = []
    private var preferredAssetIDs: Set<String> = []
    private var visibleCount = 0
    private var columns = 0
    private var itemSide: CGFloat = 0
    private var lastLayoutWidth: CGFloat = 0

    private let layout = UICollectionViewFlowLayout()
    private var collectionView: UICollectionView?
    private let emptyView = UIStackView()
    private let emptyScroll = UIScrollView()

    var selectionEnabled = false
    private(set) var selectedAssetIDs: Set<String> = []
    var includesRemoteMedia = false
    var remoteSourceID: String?
    var recursiveMembers = false

    var onSelectionChange: ((Set<String>) -> Void)?
    var onLongPressAsset: ((String, UICollectionViewCell) -> Void)?
    var onCoverStarTap: ((String) -> Void)?
    var coverAssetID: String? {
        didSet { refreshCoverStars() }
    }
    var emptyTitleKey: String?
    var emptyBodyKey: String?

    private var scopedSourceIDs: Set<String>?
    private var scopedAssetIDs: Set<String>?
    private var externalItems: [LibraryItemDTO]?
    private let emptyTitleLabel = UILabel()
    private let emptyBodyLabel = UILabel()

    func setScope(sourceIDs: Set<String>?, assetIDs: Set<String>? = nil) {
        scopedSourceIDs = sourceIDs
        scopedAssetIDs = assetIDs
        includesRemoteMedia = includesRemoteMedia || sourceIDs != nil
        reload()
    }

    func setExternalItems(_ items: [LibraryItemDTO]?) {
        externalItems = items
        reload()
    }

    func prioritizePhotos(_ ids: Set<String>) {
        guard ids != preferredAssetIDs else { return }
        preferredAssetIDs = ids
        guard isViewLoaded, !items.isEmpty else { return }
        apply(items: items)
    }

    func applySelection(_ ids: Set<String>) {
        selectedAssetIDs = ids
        collectionView?.visibleCells.forEach {
            guard let cell = $0 as? AssetGridCell, let id = cell.assetID else { return }
            cell.setCellSelected(ids.contains(id), announced: false)
            applyStarState(to: cell, assetID: id)
        }
    }

    func relPath(for assetID: String) -> String? {
        items.first { $0.id == assetID }?.originalRelPath
    }

    private func applyStarState(to cell: AssetGridCell, assetID: String) {
        let selected = selectionEnabled && selectedAssetIDs.contains(assetID)
        cell.setStarVisible(onCoverStarTap != nil && selected, isCover: coverAssetID == assetID)
    }

    private func refreshCoverStars() {
        collectionView?.visibleCells.forEach {
            guard let cell = $0 as? AssetGridCell, let id = cell.assetID else { return }
            applyStarState(to: cell, assetID: id)
        }
    }

    init(
        environment: AppEnvironment,
        collectionID: String? = nil,
        headerTitle: String? = nil,
        showsCloseButton: Bool = false
    ) {
        self.environment = environment
        self.loader = ThumbnailLoader(originalsRoot: environment.originalsRootURL, media: environment.remoteMedia)
        self.collectionID = collectionID
        self.headerTitle = headerTitle
        self.showsCloseButton = showsCloseButton
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        NotificationCenter.default.addObserver(self, selector: #selector(remoteMediaChanged), name: .remoteMediaChanged, object: nil)
        enableEdgeSwipeBack()
        view.backgroundColor = ThemeColor.canvas

        let root = UIStackView()
        root.axis = .vertical
        root.spacing = ThemeMetrics.space12
        root.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(root)

        if showsCloseButton || headerTitle != nil {
            let bar = UIStackView()
            bar.axis = .horizontal
            bar.alignment = .center
            bar.spacing = ThemeMetrics.space12
            if showsCloseButton {
                let backButton = ActionButton(titleKey: "common.back", style: .secondary)
                backButton.addTarget(self, action: #selector(handleClose), for: .touchUpInside)
                bar.addArrangedSubview(backButton)
            }
            if let headerTitle {
                let titleLabel = UILabel()
                titleLabel.text = headerTitle
                titleLabel.font = ThemeFont.heading()
                titleLabel.adjustsFontForContentSizeCategory = true
                titleLabel.textColor = ThemeColor.textPrimary
                titleLabel.numberOfLines = 0
                titleLabel.accessibilityTraits.insert(.header)
                bar.addArrangedSubview(titleLabel)
            }
            bar.addArrangedSubview(UIView())
            root.addArrangedSubview(bar)
        }

        layout.minimumInteritemSpacing = ThemeMetrics.gridGap
        layout.minimumLineSpacing = ThemeMetrics.gridGap
        let grid = UICollectionView(frame: .zero, collectionViewLayout: layout)
        grid.backgroundColor = .clear
        grid.dataSource = self
        grid.delegate = self
        grid.register(AssetGridCell.self, forCellWithReuseIdentifier: AssetGridCell.reuseID)
        grid.contentInset = UIEdgeInsets(
            top: 0,
            left: ThemeMetrics.contentInsetCompact,
            bottom: ThemeMetrics.contentInsetCompact,
            right: ThemeMetrics.contentInsetCompact
        )
        root.addArrangedSubview(grid)
        collectionView = grid
        grid.addGestureRecognizer(UILongPressGestureRecognizer(
            target: self,
            action: #selector(handleLongPress(_:))
        ))

        emptyTitleLabel.text = L10n.string("library.empty_title")
        emptyTitleLabel.font = ThemeFont.heading()
        emptyTitleLabel.adjustsFontForContentSizeCategory = true
        emptyTitleLabel.textColor = ThemeColor.textPrimary
        emptyTitleLabel.textAlignment = .center
        emptyTitleLabel.numberOfLines = 0
        emptyTitleLabel.accessibilityTraits.insert(.header)

        emptyBodyLabel.text = L10n.string("library.empty_body")
        emptyBodyLabel.font = ThemeFont.body()
        emptyBodyLabel.adjustsFontForContentSizeCategory = true
        emptyBodyLabel.textColor = ThemeColor.textSecondary
        emptyBodyLabel.textAlignment = .center
        emptyBodyLabel.numberOfLines = 0

        emptyView.axis = .vertical
        emptyView.spacing = ThemeMetrics.space8
        emptyView.addArrangedSubview(emptyTitleLabel)
        emptyView.addArrangedSubview(emptyBodyLabel)
        emptyView.translatesAutoresizingMaskIntoConstraints = false
        emptyScroll.translatesAutoresizingMaskIntoConstraints = false
        emptyScroll.addSubview(emptyView)
        view.addSubview(emptyScroll)
        let centered = emptyView.centerYAnchor.constraint(equalTo: emptyScroll.frameLayoutGuide.centerYAnchor)
        centered.priority = .defaultLow

        NSLayoutConstraint.activate([
            root.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            root.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            root.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            root.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            emptyScroll.topAnchor.constraint(equalTo: grid.topAnchor),
            emptyScroll.bottomAnchor.constraint(equalTo: grid.bottomAnchor),
            emptyScroll.leadingAnchor.constraint(equalTo: grid.leadingAnchor),
            emptyScroll.trailingAnchor.constraint(equalTo: grid.trailingAnchor),
            emptyView.topAnchor.constraint(
                greaterThanOrEqualTo: emptyScroll.contentLayoutGuide.topAnchor,
                constant: ThemeMetrics.space8
            ),
            emptyView.bottomAnchor.constraint(
                equalTo: emptyScroll.contentLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space8
            ),
            emptyView.leadingAnchor.constraint(
                equalTo: emptyScroll.contentLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInset
            ),
            emptyView.trailingAnchor.constraint(
                equalTo: emptyScroll.contentLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            emptyView.widthAnchor.constraint(
                equalTo: emptyScroll.frameLayoutGuide.widthAnchor,
                constant: -2 * ThemeMetrics.contentInset
            ),
            centered
        ])

        reload()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reload()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        updateColumns()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if traitCollection.preferredContentSizeCategory
            != previousTraitCollection?.preferredContentSizeCategory {
            columns = 0
            updateColumns()
        }
    }

    func enableRemoteMedia(sourceID: String? = nil) {
        includesRemoteMedia = true
        remoteSourceID = sourceID
        if isViewLoaded { reload() }
    }

    @objc private func remoteMediaChanged() {
        guard viewIfLoaded?.window != nil, includesRemoteMedia || items.contains(where: { $0.id.hasPrefix("remote:") }) else { return }
        reload()
    }

    private func reload() {
        if let externalItems {
            apply(items: externalItems)
            return
        }
        Task { [weak self] in
            guard let self else { return }
            let sourceScope = scopedSourceIDs
            let assetScope = scopedAssetIDs
            var fetched: [LibraryItemDTO] = []
            if let sourceScope {
                for sourceID in sourceScope.sorted() {
                    fetched += (try? await environment.assets.libraryItems(collectionID: collectionID, includeRemote: true, remoteSourceID: sourceID, recursive: recursiveMembers)) ?? []
                }
            } else {
                fetched = (try? await environment.assets.libraryItems(collectionID: collectionID, includeRemote: includesRemoteMedia, remoteSourceID: remoteSourceID, recursive: recursiveMembers)) ?? []
            }
            guard scopedSourceIDs == sourceScope, scopedAssetIDs == assetScope else { return }
            if let assetScope { fetched = fetched.filter { assetScope.contains($0.id) } }
            apply(items: fetched)
        }
    }

    private func apply(items fetched: [LibraryItemDTO]) {
        items = fetched.filter { preferredAssetIDs.contains($0.id) } + fetched.filter { !preferredAssetIDs.contains($0.id) }
        visibleCount = min(Self.pageSize, fetched.count)
        emptyTitleLabel.text = L10n.string(emptyTitleKey ?? "library.empty_title")
        emptyBodyLabel.text = L10n.string(emptyBodyKey ?? "library.empty_body")
        emptyView.isHidden = !fetched.isEmpty
        emptyScroll.isHidden = !fetched.isEmpty
        columns = 0
        updateColumns()
        collectionView?.reloadData()
    }

    private func updateColumns() {
        guard let grid = collectionView, grid.bounds.width > 0 else { return }
        let cellMin: CGFloat
        if traitCollection.preferredContentSizeCategory.isAccessibilityCategory {
            cellMin = ThemeMetrics.gridCellMax
        } else if traitCollection.horizontalSizeClass == .compact {
            cellMin = ThemeMetrics.gridCellMinCompact
        } else {
            cellMin = ThemeMetrics.gridCellMin
        }
        let available = grid.bounds.width - grid.contentInset.left - grid.contentInset.right
        let gap = ThemeMetrics.gridGap
        let count = max(1, Int((available + gap) / (cellMin + gap)))
        guard count != columns || available != lastLayoutWidth else { return }
        columns = count
        lastLayoutWidth = available
        itemSide = ((available - gap * CGFloat(count - 1)) / CGFloat(count)).rounded(.down)
        layout.itemSize = CGSize(width: itemSide, height: itemSide)
        layout.invalidateLayout()
    }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        visibleCount
    }

    func collectionView(
        _ collectionView: UICollectionView,
        cellForItemAt indexPath: IndexPath
    ) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(
            withReuseIdentifier: AssetGridCell.reuseID,
            for: indexPath
        ) as! AssetGridCell
        let item = items[indexPath.item]
        cell.onStarTap = { [weak self, weak cell] in
            guard let self, let id = cell?.assetID else { return }
            onCoverStarTap?(id)
        }
        if item.isPending {
            cell.configure(
                assetID: item.id,
                accessibilityText: L10n.string("a11y.photo_pending")
            )
            cell.setCellSelected(selectionEnabled && selectedAssetIDs.contains(item.id), announced: false)
            applyStarState(to: cell, assetID: item.id)
            cell.showPending()
            return cell
        }
        cell.accessibilityIdentifier = "grid.photo." + item.id
        cell.configure(assetID: item.id, accessibilityText: Self.accessibilityText(for: item))
        cell.setCellSelected(selectionEnabled && selectedAssetIDs.contains(item.id), announced: false)
        applyStarState(to: cell, assetID: item.id)
        cell.onRetry = { [weak self, weak cell] in
            guard let self, let cell, cell.assetID == item.id else { return }
            self.loadThumbnail(item: item, cell: cell)
        }
        return cell
    }

    private func loadThumbnail(item: LibraryItemDTO, cell: AssetGridCell) {
        guard !item.isPending, cell.assetID == item.id, cell.imageView.image == nil else { return }
        let side = max(itemSide, ThemeMetrics.gridCellMin)
        let scale = traitCollection.displayScale
        cell.imageTask?.cancel()
        cell.showLoading()
        cell.imageTask = Task { [weak cell, loader] in
            do {
                let image = try await loader.image(relPath: item.originalRelPath, pointSize: side, scale: scale)
                guard !Task.isCancelled, let cell, cell.assetID == item.id else { return }
                cell.imageTask = nil
                cell.showImage(image)
            } catch {
                guard !Task.isCancelled, let cell, cell.assetID == item.id else { return }
                cell.imageTask = nil
                cell.showFailure(reason: error.localizedDescription)
            }
        }
    }

    func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        guard let cell = cell as? AssetGridCell else { return }
        cell.imageTask?.cancel()
        cell.imageTask = nil
    }

    func collectionView(
        _ collectionView: UICollectionView,
        willDisplay cell: UICollectionViewCell,
        forItemAt indexPath: IndexPath
    ) {
        if let cell = cell as? AssetGridCell, indexPath.item < items.count {
            loadThumbnail(item: items[indexPath.item], cell: cell)
        }
        guard indexPath.item >= visibleCount - Self.prefetchThreshold,
              visibleCount < items.count else { return }
        let previous = visibleCount
        visibleCount = min(items.count, visibleCount + Self.pageSize)
        let indexPaths = (previous..<visibleCount).map { IndexPath(item: $0, section: 0) }
        collectionView.insertItems(at: indexPaths)
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        let item = items[indexPath.item]
        if item.isPending {
            return
        }
        if selectionEnabled {
            if selectedAssetIDs.contains(item.id) {
                selectedAssetIDs.remove(item.id)
            } else {
                selectedAssetIDs.insert(item.id)
            }
            (collectionView.cellForItem(at: indexPath) as? AssetGridCell)?
                .setCellSelected(selectedAssetIDs.contains(item.id), announced: true)
            if let cell = collectionView.cellForItem(at: indexPath) as? AssetGridCell {
                applyStarState(to: cell, assetID: item.id)
            }
            onSelectionChange?(selectedAssetIDs)
            return
        }
        let detail = PhotoDetailViewController(
            environment: environment,
            assetID: item.id
        )
        detail.modalPresentationStyle = .fullScreen
        present(detail, animated: true)
    }

    private static func accessibilityText(for item: LibraryItemDTO) -> String {
        guard let captured = item.capturedAtMillis else {
            return L10n.string("a11y.photo_cell_no_date")
        }
        let date = Date(timeIntervalSince1970: TimeInterval(captured) / 1000)
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return FormatStrings.string("a11y.photo_cell", formatter.string(from: date))
    }

    @objc private func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began,
              let onLongPressAsset,
              let grid = collectionView else { return }
        let point = gesture.location(in: grid)
        guard let indexPath = grid.indexPathForItem(at: point),
              indexPath.item < items.count else { return }
        let item = items[indexPath.item]
        guard !item.isPending, let cell = grid.cellForItem(at: indexPath) else { return }
        onLongPressAsset(item.id, cell)
    }

    @objc private func handleClose() {
        dismiss(animated: true)
    }
}
