import Photos
import UIKit

final class LocalPhotoAlbumsViewController: UITableViewController {
    private let environment: AppEnvironment
    var onConnected: (([String]) -> Void)?
    private var albums: [PHAssetCollection] = []
    private var selected: Set<String> = []

    init(environment: AppEnvironment) {
        self.environment = environment
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = AIText.value("Choose albums", "选择相册")
        view.backgroundColor = ThemeColor.canvas
        view.tintColor = ThemeColor.accentAction
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 56
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: L10n.string("common.continue"), style: .done, target: self, action: #selector(connect))
        navigationItem.leftBarButtonItem = UIBarButtonItem(title: L10n.string("common.back"), style: .plain, target: self, action: #selector(back))
        PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: nil).enumerateObjects { [weak self] album, _, _ in
            self?.albums.append(album)
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(false, animated: animated)
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { albums.count + 1 }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        AIText.value("Photos stay in your photo library. Only album metadata is indexed. Choose photos for a frame after connecting.", "照片保留在系统相册中，仅索引相册元数据。连接后再为相框确认选片。")
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
        cell.backgroundColor = ThemeColor.panel
        cell.textLabel?.textColor = ThemeColor.textPrimary
        cell.textLabel?.font = ThemeFont.body()
        cell.textLabel?.adjustsFontForContentSizeCategory = true
        cell.textLabel?.numberOfLines = 0
        if indexPath.row == 0 {
            cell.textLabel?.text = AIText.value("All accessible photos", "所有可访问照片")
            cell.accessoryType = selected.isEmpty ? .checkmark : .none
        } else {
            let album = albums[indexPath.row - 1]
            cell.textLabel?.text = album.localizedTitle ?? AIText.value("Album", "相册")
            cell.accessoryType = selected.contains(album.localIdentifier) ? .checkmark : .none
        }
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if indexPath.row == 0 { selected.removeAll() }
        else {
            let id = albums[indexPath.row - 1].localIdentifier
            if !selected.insert(id).inserted { selected.remove(id) }
        }
        tableView.reloadData()
    }

    @objc private func connect() {
        navigationItem.rightBarButtonItem?.isEnabled = false
        Task { [weak self] in
            guard let self else { return }
            do {
                let id = try await environment.connectLocalPhotoReferences(albumIDs: selected.sorted())
                onConnected?([id])
            } catch {
                let alert = UIAlertController(title: AIText.value("Photos are unavailable", "照片暂不可用"), message: AIText.value("Check Photos permission and choose the albums again.", "请检查照片授权并重新选择相册。"), preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: L10n.string("common.done"), style: .default))
                present(alert, animated: true)
            }
            navigationItem.rightBarButtonItem?.isEnabled = true
        }
    }

    @objc private func back() {
        if let navigationController, navigationController.viewControllers.first != self {
            navigationController.popViewController(animated: true)
        }
        else { dismiss(animated: true) }
    }
}
