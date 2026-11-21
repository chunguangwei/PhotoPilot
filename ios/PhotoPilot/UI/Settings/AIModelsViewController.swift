import Network
import UIKit

final class AIModelsViewController: UIViewController {
    private let environment: AppEnvironment
    private let contentStack = UIStackView()
    private var models: [AIModelDescriptor] = []
    private var states: [String: AIModelDownloadState] = [:]
    private var installed: Set<String> = []
    private var polling: Task<Void, Never>?
    private var statusLabels: [String: UILabel] = [:]
    private var actionButtons: [String: ActionButton] = [:]
    private var modelViews: [String: UIView] = [:]
    private var renderedIDs: [String] = []
    private var pendingFocusID: String?
    var onFocus: ((UIView) -> Void)?
    var isPolling: Bool { polling != nil }
    private let sourceControl = UISegmentedControl(items: [AIText.value("Auto", "自动"), AIText.value("China", "国内"), AIText.value("Global", "国际")])

    init(environment: AppEnvironment) {
        self.environment = environment
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.tintColor = ThemeColor.accentAction
        contentStack.axis = .vertical
        contentStack.spacing = ThemeMetrics.space16
        contentStack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(contentStack)
        let bottom = contentStack.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        bottom.priority = .defaultHigh
        NSLayoutConstraint.activate([
            contentStack.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            contentStack.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            contentStack.topAnchor.constraint(equalTo: view.topAnchor),
            bottom
        ])
        sourceControl.selectedSegmentIndex = Int((try? environment.settings.value(forKey: "ai.models.source")) ?? "0") ?? 0
        sourceControl.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        sourceControl.accessibilityIdentifier = "models.source"
        sourceControl.accessibilityLabel = AIText.value("Download source", "下载来源")
        sourceControl.addTarget(self, action: #selector(preferencesChanged), for: .valueChanged)
        contentStack.addArrangedSubview(label(AIText.value("Download source", "下载来源")))
        contentStack.addArrangedSubview(sourceControl)
        contentStack.addArrangedSubview(label(AIText.value("Completed files are retained. Background downloads depend on the system. If iOS cannot resume the current file, that file is downloaded again.", "已完成文件会保留。后台下载受系统调度；若 iOS 无法续传当前文件，将重新下载该文件。"), secondary: true))
    }

    deinit { polling?.cancel() }

    func setActive(_ active: Bool) {
        guard active else {
            polling?.cancel()
            polling = nil
            return
        }
        guard polling == nil else { return }
        sourceControl.selectedSegmentIndex = Int((try? environment.settings.value(forKey: "ai.models.source")) ?? "0") ?? 0
        polling = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                do { try await Task.sleep(nanoseconds: 500_000_000) }
                catch { return }
            }
        }
    }

    func focus(modelID: String) {
        pendingFocusID = modelID
    }

    private func label(_ text: String, secondary: Bool = false) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = secondary ? ThemeFont.meta() : ThemeFont.body()
        label.textColor = secondary ? ThemeColor.textSecondary : ThemeColor.textPrimary
        label.numberOfLines = 0
        label.adjustsFontForContentSizeCategory = true
        return label
    }

    private func refresh() async {
        guard let manager = await environment.aiFrames.models else { return }
        let catalog = (try? await environment.aiFrames.catalog()) ?? []
        let downloads = await manager.downloadStates()
        var ready: Set<String> = []
        for model in catalog where await manager.isInstalled(model) { ready.insert(model.id) }
        guard !Task.isCancelled else { return }
        models = catalog
        states = Dictionary(uniqueKeysWithValues: downloads.map { ($0.modelID, $0) })
        installed = ready
        if renderedIDs != models.map(\.id) {
            for view in modelViews.values { view.removeFromSuperview() }
            modelViews.removeAll()
            statusLabels.removeAll()
            actionButtons.removeAll()
            for model in models { addModel(model) }
            renderedIDs = models.map(\.id)
        }
        for model in models {
            let state = states[model.id]
            let size = ByteCountFormatter.string(fromByteCount: model.byteSize, countStyle: .file)
            let detail = installed.contains(model.id) ? size + " · " + AIText.value("Ready", "已就绪")
                : state.map { ByteCountFormatter.string(fromByteCount: $0.completedBytes, countStyle: .file) + " / " + size + " · " + status($0.status) } ?? size
            statusLabels[model.id]?.text = detail
            statusLabels[model.id]?.accessibilityLabel = model.title + " · " + detail
            let action = actionButtons[model.id]
            action?.setTitle(state?.status == "downloading" ? AIText.value("Pause download", "暂停下载")
                : (installed.contains(model.id) ? AIText.value("Downloaded", "已下载") : AIText.value("Download / continue", "下载 / 继续下载")))
            action?.isHidden = installed.contains(model.id)
        }
        if let id = pendingFocusID, let target = modelViews[id] {
            pendingFocusID = nil
            view.layoutIfNeeded()
            onFocus?(target)
        }
    }

    private func addModel(_ model: AIModelDescriptor) {
        let title = label(model.title)
        title.accessibilityTraits.insert(.header)
        let detail = label("", secondary: true)
        detail.accessibilityIdentifier = "models.item." + model.id
        let action = button(id: "models.action." + model.id) { [weak self] in self?.activate(model) }
        let delete = button(id: "models.delete." + model.id, style: .destructive) { [weak self] in self?.confirmRemove(model) }
        delete.setTitle(AIText.value("Delete model and partial files", "删除模型及未完成文件"))
        let group = UIStackView(arrangedSubviews: [title, detail, action, delete, label(AIText.value("Models are stored on this device, excluded from backups, and can be deleted to free space. Photos are processed on this device.", "模型仅保存在本机，排除备份，可删除回收空间。照片仍在本机处理。"), secondary: true)])
        group.axis = .vertical
        group.spacing = ThemeMetrics.space8
        contentStack.addArrangedSubview(group)
        modelViews[model.id] = group
        statusLabels[model.id] = detail
        actionButtons[model.id] = action
    }

    private func button(id: String, style: ActionButton.Style = .secondary, action: @escaping () -> Void) -> ActionButton {
        let button = ActionButton(titleKey: "", style: style)
        button.accessibilityIdentifier = id
        button.addAction(UIAction { _ in action() }, for: .touchUpInside)
        return button
    }

    private func confirmRemove(_ model: AIModelDescriptor) {
        let alert = UIAlertController(title: AIText.value("Delete model?", "删除模型？"), message: model.title, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: AIText.value("Cancel", "取消"), style: .cancel))
        alert.addAction(UIAlertAction(title: AIText.value("Delete", "删除"), style: .destructive) { [weak self] _ in Task { await self?.remove(model.id) } })
        present(alert, animated: true)
    }

    private func activate(_ model: AIModelDescriptor) {
        if states[model.id]?.status == "downloading" {
            Task { await environment.aiFrames.models?.cancel(id: model.id); await refresh() }
        } else if !installed.contains(model.id) {
            let source = AIModelDownloadSource(rawValue: sourceControl.selectedSegmentIndex) ?? .automatic
            let start = { [weak self, environment, source] in
                Task { [weak self, environment] in
                    do { try await environment.aiFrames.models?.download(id: model.id, source: source, allowCellular: true, progress: { _ in }) }
                    catch is CancellationError { }
                    catch { self?.show(error) }
                    if self?.isPolling == true { await self?.refresh() }
                }
            }
            if Self.isCellularNetwork() {
                let alert = UIAlertController(
                    title: AIText.value("Download over cellular?", "使用蜂窝网络下载？"),
                    message: AIText.value("This model is large. Downloading over cellular uses mobile data.", "模型较大，蜂窝网络下载会消耗移动数据流量。"),
                    preferredStyle: .alert
                )
                alert.addAction(UIAlertAction(title: AIText.value("Cancel", "取消"), style: .cancel))
                alert.addAction(UIAlertAction(title: AIText.value("Download", "下载"), style: .default) { _ in start() })
                present(alert, animated: true)
            } else {
                start()
            }
        }
    }

    private static func isCellularNetwork() -> Bool {
        let monitor = NWPathMonitor()
        monitor.start(queue: DispatchQueue(label: "photopilot.models.network"))
        Thread.sleep(forTimeInterval: 0.05)
        let expensive = monitor.currentPath.isExpensive
        monitor.cancel()
        return expensive
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
    func show(_ error: Error) {
        guard isPolling, viewIfLoaded?.window != nil else { return }
        let alert = UIAlertController(title: nil, message: error.localizedDescription, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: AIText.value("OK", "好"), style: .default))
        present(alert, animated: true)
    }
    @objc private func preferencesChanged() {
        try? environment.settings.setValue(String(sourceControl.selectedSegmentIndex), forKey: "ai.models.source")
    }
}
