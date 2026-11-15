import UIKit

final class RemoteSourcePickerViewController: UITableViewController {
    private let environment: AppEnvironment
    private let onSelect: ((String) -> Void)?
    private var sources: [SourceInfoDTO] = []
    private var loadingSource: String?

    init(environment: AppEnvironment, onSelect: ((String) -> Void)? = nil) {
        self.environment = environment
        self.onSelect = onSelect
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = AIText.value("Online photos", "在线选片")
        view.backgroundColor = ThemeColor.canvas
        view.tintColor = ThemeColor.accentAction
        tableView.separatorColor = ThemeColor.separator
        navigationItem.leftBarButtonItem = UIBarButtonItem(title: AIText.value("Close", "关闭"), style: .plain, target: self, action: #selector(close))
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: AIText.value("Connect", "连接来源"), style: .plain, target: self, action: #selector(connect))
        navigationItem.leftBarButtonItem?.tintColor = ThemeColor.accentAction
        navigationItem.rightBarButtonItem?.tintColor = ThemeColor.accentAction
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 64
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(false, animated: animated)
        sources = (try? environment.sources.webdavSources()) ?? []
        tableView.reloadData()
    }

    override func numberOfSections(in tableView: UITableView) -> Int { 1 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { sources.count }
    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        AIText.value("Browse WebDAV or SMB photo metadata. Originals are read when needed and share a 128 MiB temporary cache. Local synchronization is optional per frame.", "浏览 WebDAV 或 SMB 照片目录。按需读取的原图共用 128 MiB 临时缓存，可在每个相框中选择是否同步本地。")
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let source = sources[indexPath.row]
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.backgroundColor = ThemeColor.panel
        cell.textLabel?.textColor = ThemeColor.textPrimary
        cell.detailTextLabel?.textColor = ThemeColor.textSecondary
        cell.textLabel?.text = source.displayName
        cell.textLabel?.font = ThemeFont.body()
        cell.textLabel?.adjustsFontForContentSizeCategory = true
        cell.textLabel?.numberOfLines = 0
        cell.detailTextLabel?.text = loadingSource == source.id ? AIText.value("Refreshing photo catalog…", "正在刷新照片目录…")
            : source.catalogOnly ? AIText.value("Online catalog · WebDAV / SMB", "在线目录 · WebDAV / SMB")
            : AIText.value("Also synchronized at source level", "此来源还启用了来源级同步")
        cell.detailTextLabel?.numberOfLines = 0
        cell.detailTextLabel?.adjustsFontForContentSizeCategory = true
        cell.accessoryType = .disclosureIndicator
        cell.accessibilityIdentifier = "remote.source." + source.id
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let source = sources[indexPath.row]
        guard source.status == "active", loadingSource == nil else { return }
        loadingSource = source.id
        tableView.reloadData()
        Task { [weak self] in
            guard let self else { return }
            do {
                _ = try await environment.remoteMedia.index(sourceID: source.id)
                loadingSource = nil
                finish(sourceID: source.id)
            } catch {
                loadingSource = nil
                tableView.reloadData()
                let alert = UIAlertController(title: AIText.value("Photo catalog unavailable", "照片目录暂不可用"), message: error.localizedDescription, preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: AIText.value("OK", "好"), style: .default))
                present(alert, animated: true)
            }
        }
    }

    private func finish(sourceID: String) {
        if let onSelect { onSelect(sourceID) }
        else {
            navigationController?.popToViewController(self, animated: false)
            SourcesListViewController.chooseFrame(environment: environment, presenter: self) { [weak self] frameID in
                guard let self else { return }
                let editor = FrameEditViewController(environment: environment, frameID: frameID, remoteSourceID: sourceID)
                navigationController?.setNavigationBarHidden(true, animated: false)
                navigationController?.pushViewController(editor, animated: true)
            }
        }
    }

    @objc private func connect() {
        let controller = NasAddViewController(environment: environment)
        controller.onCatalogReady = { [weak self] sourceID in self?.finish(sourceID: sourceID) }
        navigationController?.pushViewController(controller, animated: true)
    }

    @objc private func close() { dismiss(animated: true) }
}
