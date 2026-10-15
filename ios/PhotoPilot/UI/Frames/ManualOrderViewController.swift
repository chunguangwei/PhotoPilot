import UIKit

final class ManualOrderCell: UICollectionViewCell {
    static let reuseID = "ManualOrderCell"

    let thumbView = UIImageView()
    let badgeLabel = UILabel()
    let handleView = UIImageView()
    var assetRef: String?

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.backgroundColor = ThemeColor.panel
        contentView.layer.cornerRadius = ThemeMetrics.radiusGroup

        thumbView.contentMode = .scaleAspectFill
        thumbView.clipsToBounds = true
        thumbView.layer.cornerRadius = ThemeMetrics.radiusThumbnail
        thumbView.backgroundColor = ThemeColor.canvas
        thumbView.translatesAutoresizingMaskIntoConstraints = false

        badgeLabel.text = L10n.string("order.new_badge")
        badgeLabel.font = ThemeFont.meta()
        badgeLabel.adjustsFontForContentSizeCategory = true
        badgeLabel.textColor = ThemeColor.accentAction
        badgeLabel.isHidden = true
        badgeLabel.translatesAutoresizingMaskIntoConstraints = false

        handleView.image = UIImage(systemName: "line.3.horizontal")
        handleView.tintColor = ThemeColor.textSecondary
        handleView.contentMode = .scaleAspectFit
        handleView.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(thumbView)
        contentView.addSubview(badgeLabel)
        contentView.addSubview(handleView)
        let side = ThemeMetrics.touchTarget * 2
        NSLayoutConstraint.activate([
            thumbView.leadingAnchor.constraint(
                equalTo: contentView.leadingAnchor,
                constant: ThemeMetrics.space12
            ),
            thumbView.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            thumbView.widthAnchor.constraint(equalToConstant: side),
            thumbView.heightAnchor.constraint(equalToConstant: side),
            badgeLabel.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            badgeLabel.leadingAnchor.constraint(
                equalTo: thumbView.trailingAnchor,
                constant: ThemeMetrics.space12
            ),
            handleView.trailingAnchor.constraint(
                equalTo: contentView.trailingAnchor,
                constant: -ThemeMetrics.space16
            ),
            handleView.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            contentView.heightAnchor.constraint(
                greaterThanOrEqualToConstant: side + ThemeMetrics.space24
            ),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        assetRef = nil
        thumbView.image = nil
        badgeLabel.isHidden = true
    }
}

final class ManualOrderViewController: UIViewController,
    UICollectionViewDataSource, UICollectionViewDelegate,
    UICollectionViewDragDelegate, UICollectionViewDropDelegate {
    private let environment: AppEnvironment
    private let onDone: ([String]) -> Void

    private let loader: ThumbnailLoader
    private var items: [String]
    private var newRefs: Set<String>
    private var relPaths: [String: String] = [:]
    private var hasExcludes: Bool

    private let layout = UICollectionViewFlowLayout()
    private var collectionView: UICollectionView?
    private let newAppendedLabel = UILabel()
    private let excludedNoteLabel = UILabel()

    init(
        environment: AppEnvironment,
        initialOrder: [String],
        newRefs: Set<String>,
        hasExcludes: Bool,
        onDone: @escaping ([String]) -> Void
    ) {
        self.environment = environment
        self.items = initialOrder
        self.newRefs = newRefs
        self.hasExcludes = hasExcludes
        self.onDone = onDone
        self.loader = ThumbnailLoader(originalsRoot: environment.originalsRootURL)
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
        let titleLabel = UILabel()
        titleLabel.text = L10n.string("order.manual_title")
        titleLabel.font = ThemeFont.heading()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.accessibilityTraits.insert(.header)
        let topBar = UIStackView(arrangedSubviews: [backButton, titleLabel, UIView()])
        topBar.axis = .horizontal
        topBar.alignment = .center
        topBar.spacing = ThemeMetrics.space12

        let hintLabel = UILabel()
        hintLabel.text = L10n.string("order.manual_hint")
        hintLabel.font = ThemeFont.meta()
        hintLabel.adjustsFontForContentSizeCategory = true
        hintLabel.textColor = ThemeColor.textSecondary
        hintLabel.numberOfLines = 0

        newAppendedLabel.text = L10n.string("order.new_appended")
        newAppendedLabel.font = ThemeFont.meta()
        newAppendedLabel.adjustsFontForContentSizeCategory = true
        newAppendedLabel.textColor = ThemeColor.statusWarning
        newAppendedLabel.numberOfLines = 0
        newAppendedLabel.isHidden = newRefs.isEmpty

        excludedNoteLabel.text = L10n.string("order.excluded_order_preserved")
        excludedNoteLabel.font = ThemeFont.meta()
        excludedNoteLabel.adjustsFontForContentSizeCategory = true
        excludedNoteLabel.textColor = ThemeColor.textSecondary
        excludedNoteLabel.numberOfLines = 0
        excludedNoteLabel.isHidden = !hasExcludes

        let doneButton = ActionButton(titleKey: "common.done", style: .primary)
        doneButton.addTarget(self, action: #selector(handleBack), for: .touchUpInside)

        layout.minimumLineSpacing = ThemeMetrics.space12
        let grid = UICollectionView(frame: .zero, collectionViewLayout: layout)
        grid.backgroundColor = .clear
        grid.dataSource = self
        grid.delegate = self
        grid.dragDelegate = self
        grid.dropDelegate = self
        grid.dragInteractionEnabled = true
        grid.register(ManualOrderCell.self, forCellWithReuseIdentifier: ManualOrderCell.reuseID)
        grid.translatesAutoresizingMaskIntoConstraints = false
        collectionView = grid

        let header = UIStackView(arrangedSubviews: [
            topBar, hintLabel, newAppendedLabel, excludedNoteLabel,
        ])
        header.axis = .vertical
        header.spacing = ThemeMetrics.space8
        header.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(header)
        view.addSubview(grid)
        doneButton.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(doneButton)

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
            grid.topAnchor.constraint(equalTo: header.bottomAnchor, constant: ThemeMetrics.space12),
            grid.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            grid.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            grid.bottomAnchor.constraint(equalTo: doneButton.topAnchor, constant: -ThemeMetrics.space12),
            doneButton.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            doneButton.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            doneButton.bottomAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space16
            ),
        ])

        Task { [weak self] in
            guard let self else { return }
            let library = (try? await environment.assets.libraryItems()) ?? []
            relPaths = Dictionary(uniqueKeysWithValues: library.map { ($0.id, $0.originalRelPath) })
            collectionView?.reloadData()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard let grid = collectionView, grid.bounds.width > 0 else { return }
        let size = CGSize(
            width: grid.bounds.width,
            height: ThemeMetrics.touchTarget * 2 + ThemeMetrics.space24
        )
        if layout.itemSize != size {
            layout.itemSize = size
            layout.invalidateLayout()
        }
    }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        items.count
    }

    func collectionView(
        _ collectionView: UICollectionView,
        cellForItemAt indexPath: IndexPath
    ) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(
            withReuseIdentifier: ManualOrderCell.reuseID,
            for: indexPath
        ) as! ManualOrderCell
        let ref = items[indexPath.item]
        cell.assetRef = ref
        cell.badgeLabel.isHidden = !newRefs.contains(ref)
        cell.isAccessibilityElement = true
        cell.accessibilityLabel = L10n.string("a11y.photo_cell_no_date")
        cell.handleView.accessibilityLabel = L10n.string("a11y.drag_handle")
        if let relPath = relPaths[ref] {
            Task { [weak cell, loader] in
                let image = await loader.thumbnail(
                    relPath: relPath,
                    pointSize: ThemeMetrics.touchTarget * 2,
                    scale: collectionView.traitCollection.displayScale
                )
                guard let cell, cell.assetRef == ref else { return }
                cell.thumbView.image = image
            }
        }
        return cell
    }

    func collectionView(
        _ collectionView: UICollectionView,
        itemsForBeginning session: UIDragSession,
        at indexPath: IndexPath
    ) -> [UIDragItem] {
        let provider = NSItemProvider(object: items[indexPath.item] as NSString)
        let dragItem = UIDragItem(itemProvider: provider)
        dragItem.localObject = indexPath
        return [dragItem]
    }

    func collectionView(
        _ collectionView: UICollectionView,
        dropSessionDidUpdate session: UIDropSession,
        withDestinationIndexPath destinationIndexPath: IndexPath?
    ) -> UICollectionViewDropProposal {
        UICollectionViewDropProposal(operation: .move, intent: .insertAtDestinationIndexPath)
    }

    func collectionView(
        _ collectionView: UICollectionView,
        performDropWith coordinator: UICollectionViewDropCoordinator
    ) {
        guard let item = coordinator.items.first,
              let source = item.sourceIndexPath,
              let destination = coordinator.destinationIndexPath,
              source.item != destination.item else { return }
        collectionView.performBatchUpdates {
            let moved = items.remove(at: source.item)
            items.insert(moved, at: destination.item)
            collectionView.moveItem(at: source, to: destination)
        }
        coordinator.drop(item.dragItem, toItemAt: destination)
    }

    func collectionView(
        _ collectionView: UICollectionView,
        contextMenuConfigurationForItemAt indexPath: IndexPath,
        point: CGPoint
    ) -> UIContextMenuConfiguration? {
        UIContextMenuConfiguration(actionProvider: { [weak self] _ in
            guard let self else { return nil }
            let moveStart = UIAction(
                title: L10n.string("order.move_to_start")
            ) { _ in
                self.moveItem(at: indexPath, toStart: true)
            }
            let moveEnd = UIAction(
                title: L10n.string("order.move_to_end")
            ) { _ in
                self.moveItem(at: indexPath, toStart: false)
            }
            return UIMenu(children: [moveStart, moveEnd])
        })
    }

    private func moveItem(at indexPath: IndexPath, toStart: Bool) {
        guard let collectionView else { return }
        let target = toStart ? 0 : items.count - 1
        guard indexPath.item != target else { return }
        collectionView.performBatchUpdates {
            let moved = items.remove(at: indexPath.item)
            items.insert(moved, at: target)
            collectionView.moveItem(at: indexPath, to: IndexPath(item: target, section: 0))
        }
    }

    @objc private func handleBack() {
        let order = items
        dismiss(animated: true) { [onDone] in
            onDone(order)
        }
    }
}
