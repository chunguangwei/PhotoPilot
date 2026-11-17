import UIKit

final class AIModelsViewController: UITableViewController {
    private let environment: AppEnvironment
    private let focusModelID: String?
    private var models: [AIModelDescriptor] = []
    private var states: [String: AIModelDownloadState] = [:]
    private var installed: Set<String> = []
    private var polling: Task<Void, Never>?
    private var focused = false
    private var renderedState = ""
    private let sourceControl = UISegmentedControl(items: [AIText.value("Auto", "自动"), AIText.value("China", "国内"), AIText.value("Global", "国际")])
    private let cellularSwitch = UISwitch()

    init(environment: AppEnvironment, focusModelID: String? = nil) {
        self.environment = environment
        self.focusModelID = focusModelID
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = AIText.value("AI models", "AI 模型管理")
        view.backgroundColor = ThemeColor.canvas
        view.tintColor = ThemeColor.accentAction
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 72
        sourceControl.selectedSegmentIndex = Int((try? environment.settings.value(forKey: "ai.models.source")) ?? "0") ?? 0
        cellularSwitch.isOn = (try? environment.settings.value(forKey: "ai.models.cellular")) == "true"
        sourceControl.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        sourceControl.addTarget(self, action: #selector(preferencesChanged), for: .valueChanged)
        cellularSwitch.addTarget(self, action: #selector(preferencesChanged), for: .valueChanged)
        if navigationController?.viewControllers.first === self {
            navigationItem.leftBarButtonItem = UIBarButtonItem(title: AIText.value("Done", "完成"), style: .done, target: self, action: #selector(close))
            navigationItem.leftBarButtonItem?.tintColor = ThemeColor.accentAction
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        polling?.cancel()
        polling = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                await refresh()
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        polling?.cancel()
        polling = nil
    }

    private func refresh() async {
        guard let manager = await environment.aiFrames.models else { return }
        models = (try? await environment.aiFrames.catalog()) ?? []
        states = Dictionary(uniqueKeysWithValues: await manager.downloadStates().map { ($0.modelID, $0) })
        var ready: Set<String> = []
        for model in models where await manager.isInstalled(model) { ready.insert(model.id) }
        installed = ready
        guard !Task.isCancelled, !tableView.isTracking, !tableView.isDragging, !tableView.isDecelerating,
              !sourceControl.isTracking, !cellularSwitch.isTracking else { return }
        let signature = models.map { model in
            let state = states[model.id]
            return model.id + ":" + String(installed.contains(model.id)) + ":" + (state?.status ?? "") + ":" + String((state?.completedBytes ?? 0) / 1_048_576)
        }.joined(separator: "|")
        if renderedState != signature {
            if renderedState.isEmpty { tableView.reloadData() }
            else { tableView.reloadSections(IndexSet(integersIn: 1..<(models.count + 1)), with: .none) }
            renderedState = signature
        }
        if !focused, let id = focusModelID, let index = models.firstIndex(where: { $0.id == id }) {
            focused = true
            tableView.scrollToRow(at: IndexPath(row: 0, section: index + 1), at: .top, animated: false)
            tableView.selectRow(at: IndexPath(row: 0, section: index + 1), animated: false, scrollPosition: .none)
            UIAccessibility.post(notification: .screenChanged, argument: tableView.cellForRow(at: IndexPath(row: 0, section: index + 1)))
        }
    }

    override func numberOfSections(in tableView: UITableView) -> Int { models.count + 1 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { section == 0 ? 2 : 3 }
    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        section == 0 ? AIText.value("Download preferences", "下载偏好") : models[section - 1].title
    }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        section == 0 ? AIText.value("Completed files are retained. Background downloads depend on the system. If iOS cannot resume the current file, that file is downloaded again.", "已完成文件会保留。后台下载受系统调度；若 iOS 无法续传当前文件，将重新下载该文件。")
            : AIText.value("Models are stored on this device, excluded from backups, and can be deleted to free space. Photos are processed on this device.", "模型仅保存在本机，排除备份，可删除回收空间。照片仍在本机处理。")
    }
    override func tableView(_ tableView: UITableView, willDisplayHeaderView view: UIView, forSection section: Int) { (view as? UITableViewHeaderFooterView)?.textLabel?.textColor = ThemeColor.textSecondary }
    override func tableView(_ tableView: UITableView, willDisplayFooterView view: UIView, forSection section: Int) { (view as? UITableViewHeaderFooterView)?.textLabel?.textColor = ThemeColor.textSecondary }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.backgroundColor = ThemeColor.panel
        cell.textLabel?.textColor = ThemeColor.textPrimary
        cell.detailTextLabel?.textColor = ThemeColor.textSecondary
        cell.textLabel?.font = ThemeFont.body()
        cell.detailTextLabel?.font = ThemeFont.meta()
        cell.textLabel?.numberOfLines = 0
        cell.detailTextLabel?.numberOfLines = 0
        cell.textLabel?.adjustsFontForContentSizeCategory = true
        cell.detailTextLabel?.adjustsFontForContentSizeCategory = true
        if indexPath.section == 0 {
            if indexPath.row == 0 {
                let title = UILabel()
                title.text = AIText.value("Download source", "下载来源")
                title.font = ThemeFont.body()
                title.textColor = ThemeColor.textPrimary
                title.numberOfLines = 0
                title.adjustsFontForContentSizeCategory = true
                sourceControl.accessibilityLabel = title.text
                let stack = UIStackView(arrangedSubviews: [title, sourceControl])
                stack.axis = .vertical
                stack.spacing = 10
                stack.translatesAutoresizingMaskIntoConstraints = false
                cell.contentView.addSubview(stack)
                NSLayoutConstraint.activate([
                    stack.leadingAnchor.constraint(equalTo: cell.contentView.leadingAnchor, constant: 16),
                    stack.trailingAnchor.constraint(equalTo: cell.contentView.trailingAnchor, constant: -16),
                    stack.topAnchor.constraint(equalTo: cell.contentView.topAnchor, constant: 12),
                    stack.bottomAnchor.constraint(equalTo: cell.contentView.bottomAnchor, constant: -12)
                ])
            } else {
                cell.textLabel?.text = AIText.value("Allow cellular downloads", "允许蜂窝网络下载")
                cell.accessoryView = cellularSwitch
            }
            return cell
        }
        let model = models[indexPath.section - 1]
        let state = states[model.id]
        if indexPath.row == 0 {
            cell.accessibilityIdentifier = "models.item." + model.id
            cell.textLabel?.text = AIText.value("Model status", "模型状态")
            cell.accessibilityLabel = model.title
            let size = ByteCountFormatter.string(fromByteCount: model.byteSize, countStyle: .file)
            cell.detailTextLabel?.text = installed.contains(model.id) ? size + " · " + AIText.value("Ready", "已就绪")
                : state.map { ByteCountFormatter.string(fromByteCount: $0.completedBytes, countStyle: .file) + " / " + size + " · " + status($0.status) } ?? size
        } else if indexPath.row == 1 {
            cell.accessibilityIdentifier = "models.action." + model.id
            cell.textLabel?.text = state?.status == "downloading" ? AIText.value("Pause download", "暂停下载")
                : (installed.contains(model.id) ? AIText.value("Downloaded", "已下载") : AIText.value("Download / continue", "下载 / 继续下载"))
            cell.textLabel?.textColor = installed.contains(model.id) ? ThemeColor.textSecondary : view.tintColor
            cell.selectionStyle = installed.contains(model.id) ? .none : .default
            cell.isUserInteractionEnabled = !installed.contains(model.id)
        } else {
            cell.accessibilityIdentifier = "models.delete." + model.id
            cell.textLabel?.text = AIText.value("Delete model and partial files", "删除模型及未完成文件")
            cell.textLabel?.textColor = .systemRed
        }
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard indexPath.section > 0, indexPath.row > 0 else { return }
        let model = models[indexPath.section - 1]
        if indexPath.row == 2 {
            let alert = UIAlertController(title: AIText.value("Delete model?", "删除模型？"), message: model.title, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: AIText.value("Cancel", "取消"), style: .cancel))
            alert.addAction(UIAlertAction(title: AIText.value("Delete", "删除"), style: .destructive) { [weak self] _ in
                Task { await self?.remove(model.id) }
            })
            present(alert, animated: true)
        } else if states[model.id]?.status == "downloading" {
            Task { await environment.aiFrames.models?.cancel(id: model.id); await refresh() }
        } else if !installed.contains(model.id) {
            let source = AIModelDownloadSource(rawValue: sourceControl.selectedSegmentIndex) ?? .automatic
            let cellular = cellularSwitch.isOn
            Task { [weak self, environment] in
                do { try await environment.aiFrames.models?.download(id: model.id, source: source, allowCellular: cellular, progress: { _ in }) }
                catch is CancellationError { }
                catch { self?.show(error) }
                await self?.refresh()
            }
        }
    }

    private func status(_ value: String) -> String {
        switch value {
        case "downloading": return AIText.value("Downloading", "正在下载")
        case "paused": return AIText.value("Paused", "已暂停")
        case "failed": return AIText.value("Retry available", "可以重试")
        case "complete": return AIText.value("Ready", "已就绪")
        default: return AIText.value("Waiting", "等待中")
        }
    }
    private func remove(_ id: String) async {
        do { try await environment.aiFrames.models?.remove(id: id) } catch { show(error) }
        await refresh()
    }
    private func show(_ error: Error) {
        guard viewIfLoaded?.window != nil else { return }
        let alert = UIAlertController(title: nil, message: error.localizedDescription, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: AIText.value("OK", "好"), style: .default))
        present(alert, animated: true)
    }
    @objc private func preferencesChanged() {
        try? environment.settings.setValue(String(sourceControl.selectedSegmentIndex), forKey: "ai.models.source")
        try? environment.settings.setValue(cellularSwitch.isOn ? "true" : "false", forKey: "ai.models.cellular")
    }
    @objc private func close() { dismiss(animated: true) }
}
