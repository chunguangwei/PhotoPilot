import UIKit

final class NasFolderPickerViewController: UIViewController, UITableViewDataSource, UITableViewDelegate {
    private let listFolders: @Sendable (String) async throws -> [String]
    private let path: String
    private let selection: DirectorySelection
    private let onFinish: ([String], Bool) -> Void

    private let table = UITableView(frame: .zero, style: .plain)
    private var folders: [String] = []
    private let countLabel = UILabel()
    private let recursiveSwitch = UISwitch()
    private let confirmButton = ActionButton(titleKey: "common.done", style: .primary)

    init(
        listFolders: @escaping @Sendable (String) async throws -> [String],
        path: String,
        selection: DirectorySelection,
        onFinish: @escaping ([String], Bool) -> Void
    ) {
        self.listFolders = listFolders
        self.path = path
        self.selection = selection
        self.onFinish = onFinish
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ThemeColor.canvas

        let backButton = ActionButton(titleKey: "common.back", style: .secondary)
        backButton.addTarget(self, action: #selector(handleBack), for: .touchUpInside)
        let titleLabel = UILabel()
        titleLabel.text = L10n.string("source.nas.choose_folders")
        titleLabel.font = ThemeFont.heading()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.numberOfLines = 0
        titleLabel.accessibilityTraits.insert(.header)
        let pathLabel = UILabel()
        pathLabel.text = path.isEmpty ? "/" : path
        pathLabel.font = ThemeFont.meta()
        pathLabel.adjustsFontForContentSizeCategory = true
        pathLabel.textColor = ThemeColor.textSecondary
        let topBar = UIStackView(arrangedSubviews: [backButton, titleLabel, UIView()])
        topBar.axis = .horizontal
        topBar.alignment = .center
        topBar.spacing = ThemeMetrics.space12

        let header = UIStackView(arrangedSubviews: [topBar, pathLabel])
        header.axis = .vertical
        header.spacing = ThemeMetrics.space4
        header.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(header)

        table.dataSource = self
        table.delegate = self
        table.backgroundColor = .clear
        table.separatorColor = ThemeColor.separator
        table.register(UITableViewCell.self, forCellReuseIdentifier: "FolderCell")
        table.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(table)

        recursiveSwitch.isOn = selection.recursive
        recursiveSwitch.onTintColor = ThemeColor.accentAction
        recursiveSwitch.accessibilityLabel = L10n.string("source.nas.include_subfolders")
        recursiveSwitch.addTarget(self, action: #selector(handleRecursive), for: .valueChanged)
        let recursiveLabel = UILabel()
        recursiveLabel.text = L10n.string("source.nas.include_subfolders")
        recursiveLabel.font = ThemeFont.body()
        recursiveLabel.adjustsFontForContentSizeCategory = true
        recursiveLabel.textColor = ThemeColor.textPrimary
        let recursiveRow = UIStackView(arrangedSubviews: [recursiveLabel, recursiveSwitch])
        recursiveRow.axis = .horizontal
        recursiveRow.alignment = .center
        recursiveRow.spacing = ThemeMetrics.space12
        recursiveRow.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true

        countLabel.font = ThemeFont.body()
        countLabel.adjustsFontForContentSizeCategory = true
        countLabel.textColor = ThemeColor.textSecondary
        confirmButton.addTarget(self, action: #selector(handleConfirm), for: .touchUpInside)

        let bottomBar = UIStackView(arrangedSubviews: [recursiveRow, countLabel, confirmButton])
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
                constant: ThemeMetrics.contentInset
            ),
            header.trailingAnchor.constraint(
                lessThanOrEqualTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            readable,
            table.topAnchor.constraint(equalTo: header.bottomAnchor, constant: ThemeMetrics.space8),
            table.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            table.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            table.bottomAnchor.constraint(equalTo: bottomBar.topAnchor),
            bottomBar.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            bottomBar.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            bottomBar.bottomAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space16
            ),
        ])

        updateBottomBar()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reloadFolders()
    }

    private func reloadFolders() {
        Task { [weak self] in
            guard let self else { return }
            folders = ((try? await listFolders(path)) ?? []).sorted()
            table.reloadData()
            updateBottomBar()
        }
    }

    private func updateBottomBar() {
        let count = selection.paths.count
        countLabel.text = String(
            format: L10n.string("frames.selected_count"),
            count
        )
        confirmButton.isEnabled = count > 0
        confirmButton.alpha = count > 0 ? 1 : ThemeMetrics.controlOpacity
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        folders.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "FolderCell", for: indexPath)
        let folder = folders[indexPath.row]
        var content = cell.defaultContentConfiguration()
        content.text = (folder as NSString).lastPathComponent
        content.textProperties.font = ThemeFont.body()
        content.textProperties.color = ThemeColor.textPrimary
        content.secondaryText = folder
        content.secondaryTextProperties.font = ThemeFont.meta()
        content.secondaryTextProperties.color = ThemeColor.textSecondary
        let selected = selection.paths.contains(folder)
        content.image = UIImage(systemName: selected ? "checkmark.circle.fill" : "circle")
        content.imageProperties.tintColor = selected ? ThemeColor.accentAction : ThemeColor.textSecondary
        cell.contentConfiguration = content
        cell.backgroundColor = .clear
        cell.accessoryType = .detailDisclosureButton
        cell.accessibilityTraits = [.button]
        return cell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let folder = folders[indexPath.row]
        if selection.paths.contains(folder) {
            selection.paths.remove(folder)
        } else {
            selection.paths.insert(folder)
        }
        tableView.reloadRows(at: [indexPath], with: .none)
        updateBottomBar()
    }

    func tableView(
        _ tableView: UITableView,
        accessoryButtonTappedForRowWith indexPath: IndexPath
    ) {
        let folder = folders[indexPath.row]
        let next = NasFolderPickerViewController(
            listFolders: listFolders,
            path: folder,
            selection: selection,
            onFinish: onFinish
        )
        navigationController?.pushViewController(next, animated: true)
    }

    func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
        UITableView.automaticDimension
    }

    func tableView(_ tableView: UITableView, estimatedHeightForRowAt indexPath: IndexPath) -> CGFloat {
        ThemeMetrics.touchTarget
    }

    @objc private func handleRecursive() {
        selection.recursive = recursiveSwitch.isOn
    }

    @objc private func handleConfirm() {
        guard !selection.paths.isEmpty else { return }
        onFinish(Array(selection.paths).sorted(), selection.recursive)
    }

    @objc private func handleBack() {
        if let navigationController, navigationController.viewControllers.first != self {
            navigationController.popViewController(animated: true)
        } else {
            dismiss(animated: true)
        }
    }
}
