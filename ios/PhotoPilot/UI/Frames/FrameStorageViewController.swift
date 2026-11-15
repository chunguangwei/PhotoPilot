import UIKit

final class FrameStorageViewController: UITableViewController {
    private let environment: AppEnvironment
    private let frameID: String
    private let localSwitch = UISwitch()
    private var mode = FrameStorageMode.local
    private var progress = FrameStorageState()
    private var summary = RemoteReleaseSummary(assetIDs: [], bytes: 0, retained: 0)
    private var cacheBytes: Int64 = 0
    private var selectionVersion: Int64 = 0
    private var monitoring: Task<Void, Never>?
    private var changing = false
    private var hasNasPhotos = true
    private var lastStatus = ""
    private var displayedState = ""

    init(environment: AppEnvironment, frameID: String) {
        self.environment = environment
        self.frameID = frameID
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = AIText.value("Photo storage", "照片存储")
        view.backgroundColor = ThemeColor.canvas
        tableView.separatorColor = ThemeColor.separator
        view.tintColor = ThemeColor.accentAction
        navigationItem.leftBarButtonItem = UIBarButtonItem(title: AIText.value("Done", "完成"), style: .done, target: self, action: #selector(close))
        navigationItem.leftBarButtonItem?.tintColor = ThemeColor.accentAction
        localSwitch.accessibilityIdentifier = "storage.syncLocal"
        localSwitch.addTarget(self, action: #selector(modeChanged), for: .valueChanged)
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 72
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        monitoring = Task { [weak self] in
            guard let self else { return }
            await refresh(force: true)
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard !Task.isCancelled else { return }
                await refresh()
            }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        monitoring?.cancel()
        monitoring = nil
    }

    private func refresh(force: Bool = false) async {
        do {
            let bundle = try await environment.frameManager.framePlaybackBundle(frameID: frameID)
            mode = FrameStorageMode.read(bundle.settingsJSON)
            selectionVersion = bundle.selectionVersion
            let references = Set(try await environment.frames.resolve(frameID: frameID).candidates)
            let records = try await environment.remoteMedia.repository.records()
            hasNasPhotos = records.contains { !$0.referenceOnly && references.contains($0.descriptor.runtimeID) }
            progress = try await environment.frameStorage.state(frameID: frameID)
            cacheBytes = await environment.remoteMedia.cache.usage()
            if force || progress.status != lastStatus {
                summary = try await environment.frameStorage.releasable(frameID: frameID)
                lastStatus = progress.status
            }
            guard !Task.isCancelled else { return }
            guard !changing else { return }
            let rendered = "\(mode.rawValue)|\(progress.status)|\(progress.completed)|\(progress.total)|\(progress.error ?? "")|\(cacheBytes)|\(summary.bytes)|\(summary.retained)"
            guard force || rendered != displayedState else { return }
            displayedState = rendered
            localSwitch.isOn = mode == .local
            tableView.reloadData()
        } catch { if !Task.isCancelled { show(error) } }
    }

    override func tableView(_ tableView: UITableView, willDisplayHeaderView view: UIView, forSection section: Int) {
        (view as? UITableViewHeaderFooterView)?.textLabel?.textColor = ThemeColor.textSecondary
    }

    override func tableView(_ tableView: UITableView, willDisplayFooterView view: UIView, forSection section: Int) {
        (view as? UITableViewHeaderFooterView)?.textLabel?.textColor = ThemeColor.textSecondary
    }

    override func numberOfSections(in tableView: UITableView) -> Int { hasNasPhotos ? 3 : 1 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch section {
        case 0: return hasNasPhotos && mode == .local ? 3 : 1
        case 1: return 2
        default: return 2
        }
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch section {
        case 0: return AIText.value("This frame", "此相框")
        case 1: return AIText.value("Shared temporary cache", "共享临时缓存")
        default: return AIText.value("Offline copies", "离线副本")
        }
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        switch section {
        case 0: return !hasNasPhotos ? AIText.value("Directly references device photos. Existing imported originals remain on this device.", "直接引用本机照片，已有导入原图继续保留在本机。") : AIText.value("Online playback reads NAS photos when needed. Local synchronization downloads only this frame’s selected NAS photos for offline playback. Device photo and file references use system access. Private imports use app storage.", "在线播放按需读取 NAS 照片。本地同步仅下载此相框选中的 NAS 照片，以供离线播放。本机照片和文件引用通过系统授权读取，私有导入照片使用应用存储。")
        case 1: return AIText.value("All online frames, thumbnails, covers and AI reads share 128 MiB. Downloads and reservations count toward the limit. At least 64 MiB of device space is reserved.", "所有在线相框、缩略图、封面及 AI 读取共用 128 MiB，下载中数据和预约空间均计入限额，并保留至少 64 MiB 设备可用空间。")
        default: return AIText.value("Releasing copies preserves your selections, frame rules and NAS originals. Copies used by other offline frames, local imports, source-wide synchronization or unverified versions are protected. Legacy copies can be checked against the remote file before release.", "释放副本会保留选片、相框规则和 NAS 原图。其他离线相框、本机导入、来源级同步及未经核验的版本所使用的副本会保留。旧副本可先从远端逐张核验，再释放符合条件的文件。")
        }
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.backgroundColor = ThemeColor.panel
        cell.textLabel?.textColor = ThemeColor.textPrimary
        cell.detailTextLabel?.textColor = ThemeColor.textSecondary
        cell.textLabel?.font = ThemeFont.body()
        cell.textLabel?.adjustsFontForContentSizeCategory = true
        cell.textLabel?.numberOfLines = 0
        cell.detailTextLabel?.font = ThemeFont.meta()
        cell.detailTextLabel?.adjustsFontForContentSizeCategory = true
        cell.detailTextLabel?.numberOfLines = 0
        if indexPath.section == 0 {
            if indexPath.row == 0 {
                cell.textLabel?.text = hasNasPhotos ? AIText.value("Synchronize selected NAS photos locally", "将选中的 NAS 照片同步到本地") : AIText.value("Directly references device photos", "直接引用本机照片")
                cell.accessoryView = hasNasPhotos ? localSwitch : nil
                cell.accessibilityIdentifier = "storage.mode"
            } else if indexPath.row == 1 {
                cell.textLabel?.text = progress.status == "complete" ? AIText.value("NAS photos ready for offline playback", "NAS 离线照片已就绪")
                    : AIText.value("Synchronization", "同步进度")
                cell.detailTextLabel?.text = "\(progress.completed) / \(progress.total)" + (progress.error.map { "\n" + $0 } ?? "")
                cell.accessibilityIdentifier = "storage.progress"
            } else {
                cell.textLabel?.text = progress.status == "running" ? AIText.value("Pause", "暂停同步") : AIText.value("Resume synchronization", "继续同步")
                cell.textLabel?.textColor = view.tintColor
                cell.accessibilityIdentifier = "storage.resume"
            }
        } else if indexPath.section == 1 {
            if indexPath.row == 0 {
                cell.textLabel?.text = ByteCountFormatter.string(fromByteCount: cacheBytes, countStyle: .memory) + " / 128 MiB"
                cell.accessibilityIdentifier = "storage.cacheUsage"
            } else {
                cell.textLabel?.text = AIText.value("Clear temporary cache", "清空临时缓存")
                cell.textLabel?.textColor = view.tintColor
                cell.accessibilityIdentifier = "storage.clearCache"
            }
        } else if indexPath.row == 0 {
            cell.textLabel?.text = AIText.value("Release unused NAS copies", "释放不再需要的 NAS 副本")
            cell.detailTextLabel?.text = ByteCountFormatter.string(fromByteCount: Int64(summary.bytes), countStyle: .file)
                + AIText.value(" can be released", " 可释放")
            cell.textLabel?.textColor = view.tintColor
            cell.accessibilityIdentifier = "storage.releaseCopies"
        } else {
            cell.textLabel?.text = AIText.value("Verify legacy copies and release", "核验旧副本并释放")
            cell.detailTextLabel?.text = AIText.value("Reads selected remote photos one at a time using the bounded cache.", "使用限额缓存逐张读取选中的远端照片。")
            cell.textLabel?.textColor = view.tintColor
            cell.accessibilityIdentifier = "storage.verifyLegacy"
        }
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard !changing else { return }
        if indexPath.section == 0 && indexPath.row == 2 {
            Task { [weak self] in
                guard let self else { return }
                if progress.status == "running" { await environment.frameStorage.pause(frameID: frameID) }
                else { await environment.frameStorage.start(frameID: frameID) }
                await refresh(force: true)
            }
        } else if indexPath.section == 1 && indexPath.row == 1 {
            confirm(title: AIText.value("Clear temporary cache?", "清空临时缓存？"), message: AIText.value("Photos will be fetched again when needed.", "之后需要时会重新获取照片。")) { [weak self] in
                guard let self else { return }
                await environment.remoteMedia.clearCache()
                await refresh(force: true)
            }
        } else if indexPath.section == 2 {
            guard mode == .remote else {
                show(RemoteMediaError.localCopyMissing)
                return
            }
            let verify = indexPath.row == 1
            let version = selectionVersion
            confirm(title: AIText.value("Release offline copies?", "释放离线副本？"),
                message: verify ? AIText.value("Current remote files will be read to verify old copies. Matching unused copies will be released. This uses the network.", "会读取当前远端文件来核验旧副本，释放匹配且未被离线使用的副本，此操作会使用网络。")
                    : AIText.value("Release \(ByteCountFormatter.string(fromByteCount: Int64(summary.bytes), countStyle: .file)) of unused copies for this frame?", "释放此相框 \(ByteCountFormatter.string(fromByteCount: Int64(summary.bytes), countStyle: .file)) 不再使用的本地副本？")) { [weak self] in
                guard let self else { return }
                do { try await environment.frameStorage.releaseCopies(frameID: frameID, verifyLegacy: verify, expectedVersion: version) }
                catch { show(error) }
                await refresh(force: true)
            }
        }
    }

    @objc private func modeChanged() {
        guard !changing else { return }
        changing = true
        localSwitch.isEnabled = false
        let requested: FrameStorageMode = localSwitch.isOn ? .local : .remote
        Task { [weak self] in
            guard let self else { return }
            do { try await environment.frameStorage.setMode(frameID: frameID, mode: requested) }
            catch { show(error) }
            changing = false
            localSwitch.isEnabled = true
            await refresh(force: true)
        }
    }

    private func confirm(title: String, message: String, action: @escaping @MainActor () async -> Void) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: AIText.value("Cancel", "取消"), style: .cancel))
        alert.addAction(UIAlertAction(title: AIText.value("Continue", "继续"), style: .destructive) { _ in Task { await action() } })
        present(alert, animated: true)
    }

    private func show(_ error: Error) {
        guard presentedViewController == nil else { return }
        let alert = UIAlertController(title: nil, message: error.localizedDescription, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: AIText.value("OK", "好"), style: .default))
        present(alert, animated: true)
    }

    @objc private func close() { dismiss(animated: true) }
}
