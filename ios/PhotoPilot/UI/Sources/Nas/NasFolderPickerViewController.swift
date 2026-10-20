import UIKit

final class NasFolderPickerViewController: UIViewController, UITableViewDataSource, UITableViewDelegate {
    private let listFolders: @Sendable (String) async throws -> [String]
    private let path: String
    private let selection: DirectorySelection
    private let onFinish: ([String], Bool) -> Void

    private let table = UITableView(frame: .zero, style: .plain)
    private var folders: [String] = []
    private var loadFailed = false
    private var loading = true
    private let statusLabel = UILabel()
    private let wholeControl = UIControl()
    private let wholeIcon = UIImageView()
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

        wholeIcon.contentMode = .scaleAspectFit
        wholeIcon.widthAnchor.constraint(equalToConstant: ThemeMetrics.space24).isActive = true
        wholeIcon.heightAnchor.constraint(equalToConstant: ThemeMetrics.space24).isActive = true
        let wholeLabel = UILabel()
        wholeLabel.text = L10n.string("source.nas.picker_whole")
        wholeLabel.font = ThemeFont.body()
        wholeLabel.adjustsFontForContentSizeCategory = true
        wholeLabel.textColor = ThemeColor.textPrimary
        wholeLabel.numberOfLines = 0
        let wholeRow = UIStackView(arrangedSubviews: [wholeIcon, wholeLabel, UIView()])
        wholeRow.axis = .horizontal
        wholeRow.alignment = .center
        wholeRow.spacing = ThemeMetrics.space12
        wholeRow.isUserInteractionEnabled = false
        wholeControl.addSubview(wholeRow)
        wholeRow.translatesAutoresizingMaskIntoConstraints = false
        wholeControl.translatesAutoresizingMaskIntoConstraints = false
        wholeControl.addTarget(self, action: #selector(handleWholeToggle), for: .touchUpInside)
        wholeControl.accessibilityLabel = L10n.string("source.nas.picker_whole")
        wholeControl.accessibilityTraits = [.button]
        wholeControl.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
        NSLayoutConstraint.activate([
            wholeRow.topAnchor.constraint(equalTo: wholeControl.topAnchor),
            wholeRow.bottomAnchor.constraint(equalTo: wholeControl.bottomAnchor),
            wholeRow.leadingAnchor.constraint(equalTo: wholeControl.leadingAnchor),
            wholeRow.trailingAnchor.constraint(equalTo: wholeControl.trailingAnchor),
        ])
        view.addSubview(wholeControl)

        statusLabel.font = ThemeFont.body()
        statusLabel.adjustsFontForContentSizeCategory = true
        statusLabel.textColor = ThemeColor.textSecondary
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(statusLabel)

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

        let readable = header.widthAnchor.constraint(
            lessThanOrEqualToConstant: ThemeMetrics.bodyMaxWidth
        )
        let fillWidth = header.widthAnchor.constraint(
            equalTo: view.safeAreaLayoutGuide.widthAnchor,
            constant: -2 * ThemeMetrics.contentInsetCompact
        )
        fillWidth.priority = .defaultHigh
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
            fillWidth,
            wholeControl.topAnchor.constraint(equalTo: header.bottomAnchor, constant: ThemeMetrics.space8),
            wholeControl.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            wholeControl.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            table.topAnchor.constraint(equalTo: wholeControl.bottomAnchor),
            table.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            table.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            table.bottomAnchor.constraint(equalTo: bottomBar.topAnchor),
            statusLabel.leadingAnchor.constraint(equalTo: table.leadingAnchor),
            statusLabel.trailingAnchor.constraint(equalTo: table.trailingAnchor),
            statusLabel.centerYAnchor.constraint(equalTo: table.centerYAnchor),
            bottomBar.leadingAnchor.constraint(equalTo: header.leadingAnchor),
            bottomBar.trailingAnchor.constraint(equalTo: header.trailingAnchor),
            bottomBar.bottomAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space16
            ),
        ])

        updateWholeRow()
        updateStatus()
        updateBottomBar()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        recursiveSwitch.isOn = selection.recursive
        updateWholeRow()
        reloadFolders()
    }

    private func reloadFolders() {
        loading = true
        loadFailed = false
        updateStatus()
        Task { [weak self] in
            guard let self else { return }
            do {
                folders = try await listFolders(path).sorted()
            } catch {
                folders = []
                loadFailed = true
            }
            loading = false
            table.reloadData()
            updateStatus()
            updateBottomBar()
        }
    }

    private func updateStatus() {
        if loading {
            statusLabel.text = L10n.string("common.loading")
        } else if loadFailed {
            statusLabel.text = L10n.string("source.nas.picker_error")
        } else if folders.isEmpty {
            statusLabel.text = L10n.string("source.nas.picker_empty")
        } else {
            statusLabel.text = nil
        }
    }

    private func updateWholeRow() {
        let selected = selection.paths.contains(path)
        wholeIcon.image = UIImage(systemName: selected ? "checkmark.circle.fill" : "circle")
        wholeIcon.tintColor = selected ? ThemeColor.accentAction : ThemeColor.textSecondary
        if selected {
            wholeControl.accessibilityTraits.insert(.selected)
        } else {
            wholeControl.accessibilityTraits.remove(.selected)
        }
    }

    @objc private func handleWholeToggle() {
        if selection.paths.contains(path) {
            selection.paths.remove(path)
        } else {
            selection.paths.insert(path)
            selection.recursive = true
            recursiveSwitch.isOn = true
        }
        updateWholeRow()
        updateBottomBar()
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
