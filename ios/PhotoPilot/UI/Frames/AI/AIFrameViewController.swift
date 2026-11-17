import UIKit

final class AIFrameViewController: UITableViewController, UITextViewDelegate {
    private struct Preview {
        var configuration: AIFrameConfiguration
        var name: String
        var matches: [AIFrameMatch]
        var selected: Set<String>
        var initiallySelected: Set<String>
        var savedFrameID: String?
        var categoryID = ""
        var complete = false
    }

    private let environment: AppEnvironment
    private let frameID: String?
    private var models: [AIModelDescriptor] = []
    private var filtersExpanded = false
    private var collections: [CollectionDTO] = []
    private var sourceNames: [String: String] = [:]
    private var folderRows: [(title: String?, collection: CollectionDTO?)] {
        var rows: [(title: String?, collection: CollectionDTO?)] = []
        for sourceID in Set(collections.map { sourceNames[$0.sourceID ?? ""] == nil ? "" : ($0.sourceID ?? "") }).sorted() {
            rows.append((sourceNames[sourceID] ?? AIText.value("On this device", "本机目录"), nil))
            rows.append(contentsOf: collections.filter { (sourceNames[$0.sourceID ?? ""] == nil ? "" : ($0.sourceID ?? "")) == sourceID }.map { (nil, $0) })
        }
        return rows
    }
    private var installed: Set<String> = []
    private var selectedModelID = "embeddinggemma2-vision"
    private var selectedCollections: Set<String> = []
    private var previews: [Preview] = []
    private var draftID: String?
    private var draftStatus = "idle"
    private var formDirty = false
    private var renderedDraftDate: Date?
    private var renderedSavedStatus = ""
    private var loadedFrameRevision = 1
    private var existingName: String?
    private var preservedExclusions: Set<String> = []
    private var busy = false
    private var saving = false
    private var downloading = false
    private var savedJobRunning = false
    private var stateMonitor: Task<Void, Never>?
    private var operation: Task<Void, Never>?
    private let categoryInput = UITextView()
    private let includesInput = UITextView()
    private let excludesInput = UITextView()
    private let recursiveSwitch = UISwitch()
    private let automaticSwitch = UISwitch()
    private let matchControl = UISegmentedControl(items: [AIText.value("All", "全部"), AIText.value("Any", "任意")])
    private let sourceControl = UISegmentedControl(items: [AIText.value("Auto", "自动"), AIText.value("China", "国内"), AIText.value("Global", "国际")])
    private let thresholdSlider = UISlider()
    private let thresholdLabel = UILabel()
    private let statusLabel = UILabel()
    private let progressView = UIProgressView(progressViewStyle: .default)
    private lazy var thumbnailLoader = ThumbnailLoader(originalsRoot: environment.originalsRootURL, media: environment.remoteMedia)
    var onSaved: (() -> Void)?

    init(environment: AppEnvironment, frameID: String? = nil) {
        self.environment = environment
        self.frameID = frameID
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = frameID == nil ? AIText.value("Create AI frames", "AI 创建相框") : AIText.value("AI frame settings", "AI 相框设置")
        view.backgroundColor = ThemeColor.canvas
        tableView.keyboardDismissMode = .interactive
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 60
        navigationItem.leftBarButtonItem = UIBarButtonItem(title: L10n.string("common.back"), style: .plain, target: self, action: #selector(close))
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: L10n.string("common.save"), style: .done, target: self, action: #selector(save))
        navigationItem.rightBarButtonItem?.isEnabled = frameID != nil
        for input in [categoryInput, includesInput, excludesInput] {
            input.font = .preferredFont(forTextStyle: .body)
            input.adjustsFontForContentSizeCategory = true
            input.delegate = self
            input.backgroundColor = .secondarySystemGroupedBackground
            input.isScrollEnabled = true
            input.heightAnchor.constraint(greaterThanOrEqualToConstant: 96).isActive = true
            let toolbar = UIToolbar()
            toolbar.items = [UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil),
                             UIBarButtonItem(title: L10n.string("common.done"), style: .done, target: self, action: #selector(dismissKeyboard))]
            toolbar.sizeToFit()
            input.inputAccessoryView = toolbar
        }
        categoryInput.accessibilityIdentifier = "ai.categories"
        includesInput.accessibilityIdentifier = "ai.includes"
        excludesInput.accessibilityIdentifier = "ai.excludes"
        automaticSwitch.accessibilityIdentifier = "ai.automatic"
        thresholdSlider.accessibilityIdentifier = "ai.threshold"
        categoryInput.accessibilityLabel = AIText.value("Categories, one per line", "分类，每行一个")
        includesInput.accessibilityLabel = AIText.value("Include descriptions, one per line", "包含描述，每行一个")
        excludesInput.accessibilityLabel = AIText.value("Exclude descriptions, one per line", "排除描述，每行一个")
        recursiveSwitch.isOn = true
        automaticSwitch.isOn = false
        matchControl.selectedSegmentIndex = 0
        sourceControl.selectedSegmentIndex = 0
        thresholdSlider.minimumValue = 0.02
        thresholdSlider.maximumValue = 0.95
        thresholdSlider.value = 0.2
        thresholdSlider.accessibilityLabel = AIText.value("Match strictness", "匹配严格度")
        thresholdSlider.addTarget(self, action: #selector(settingsChanged), for: .valueChanged)
        recursiveSwitch.addTarget(self, action: #selector(settingsChanged), for: .valueChanged)
        automaticSwitch.addTarget(self, action: #selector(automaticChanged), for: .valueChanged)
        matchControl.addTarget(self, action: #selector(settingsChanged), for: .valueChanged)
        for label in [thresholdLabel, statusLabel] {
            label.numberOfLines = 0
            label.font = .preferredFont(forTextStyle: .footnote)
            label.adjustsFontForContentSizeCategory = true
        }
        thresholdLabel.text = thresholdTitle
        statusLabel.text = AIText.value("Photos and descriptions are processed on this device.", "照片和分类描述均在本机处理。")
        enableKeyboardDismissal()
        Task { [weak self] in await self?.load() }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        stateMonitor?.cancel()
        stateMonitor = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                if let id = self.draftID, let draft = try? await self.environment.aiFrames.draft(id: id) {
                    self.display(draft)
                } else if let frameID = self.frameID, let state = try? await self.environment.aiFrames.store.state(frameID: frameID) {
                    self.savedJobRunning = state.status == "running"
                    self.statusLabel.text = state.isUserPaused ? AIText.value("Paused. Tap Continue to scan remaining photos.", "已暂停，点击继续处理剩余照片。")
                        : AIText.value("Processed \(state.succeeded + state.skipped)/\(state.total) · Failed \(state.failed)", "已处理 \(state.succeeded + state.skipped)/\(state.total) · 失败 \(state.failed)")
                    if self.renderedSavedStatus != state.status {
                        self.renderedSavedStatus = state.status
                        self.tableView.reloadSections(IndexSet(integer: 3), with: .none)
                    }
                }
                let oldInstalled = self.installed
                try? await self.refreshInstalled()
                if oldInstalled != self.installed { self.tableView.reloadSections(IndexSet(integer: 0), with: .none) }
                try? await Task.sleep(nanoseconds: 700_000_000)
            }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stateMonitor?.cancel()
    }

    private var thresholdTitle: String {
        AIText.value("Similarity threshold", "相似度阈值") + String(format: " %.3f", thresholdSlider.value)
    }

    private func load() async {
        do {
            models = try await environment.aiFrames.catalog()
            collections = try await environment.assets.collections()
            sourceNames = Dictionary(((try? environment.sources.webdavSources()) ?? []).map { ($0.id, $0.displayName) }, uniquingKeysWith: { first, _ in first })
            if let frameID {
                let detail = try await environment.frameManager.frameDetail(frameID: frameID)
                existingName = detail.name
                preservedExclusions = Set(detail.excludeRefs)
            }
            if let frameID, let config = try await environment.aiFrames.configuration(frameID: frameID) {
                loadedFrameRevision = config.revision
                selectedModelID = config.modelID
                selectedCollections = Set(config.collectionIDs)
                categoryInput.text = config.rule.category
                includesInput.text = config.rule.includes.joined(separator: "\n")
                excludesInput.text = config.rule.excludes.joined(separator: "\n")
                recursiveSwitch.isOn = config.recursive
                automaticSwitch.isOn = config.automatic
                thresholdSlider.value = config.rule.threshold
                matchControl.selectedSegmentIndex = config.rule.matchAll ? 0 : 1
                let detail = try await environment.frameManager.frameDetail(frameID: frameID)
                existingName = detail.name
                preservedExclusions = Set(detail.excludeRefs)
                if let state = try await environment.aiFrames.store.state(frameID: frameID) { preservedExclusions.formUnion(state.manualExcludedAssetIDs) }
            } else if frameID == nil, let draft = try await environment.aiFrames.currentDraft(), let first = draft.categories.first {
                selectedModelID = first.configuration.modelID
                selectedCollections = Set(first.configuration.collectionIDs)
                categoryInput.text = draft.categories.map { $0.configuration.rule.category }.joined(separator: "\n")
                includesInput.text = first.configuration.rule.includes.joined(separator: "\n")
                excludesInput.text = first.configuration.rule.excludes.joined(separator: "\n")
                thresholdSlider.value = first.configuration.rule.threshold
                recursiveSwitch.isOn = first.configuration.recursive
                matchControl.selectedSegmentIndex = first.configuration.rule.matchAll ? 0 : 1
                automaticSwitch.isOn = first.configuration.automatic
                display(draft)
            } else if frameID == nil, let previous = await environment.aiFrames.resumablePreviewConfiguration() {
                selectedModelID = previous.modelID
                selectedCollections = Set(previous.collectionIDs)
                categoryInput.text = previous.rule.category
                includesInput.text = previous.rule.includes.joined(separator: "\n")
                excludesInput.text = previous.rule.excludes.joined(separator: "\n")
                thresholdSlider.value = previous.rule.threshold
                recursiveSwitch.isOn = previous.recursive
                matchControl.selectedSegmentIndex = previous.rule.matchAll ? 0 : 1
                automaticSwitch.isOn = previous.automatic
                statusLabel.text = AIText.value("A previous scan can be resumed. Tap Scan and preview to continue.", "可以恢复上次扫描，点击扫描并预览以继续。")
            } else if let model = models.first {
                selectedModelID = model.id
                thresholdSlider.value = model.defaultThreshold ?? 0.2
            }
            if let form = try await environment.aiFrames.drafts.loadForm(), form.frameID == frameID {
                selectedModelID = form.modelID
                selectedCollections = Set(form.collections)
                categoryInput.text = form.categoryText
                includesInput.text = form.includesText
                excludesInput.text = form.excludesText
                thresholdSlider.value = form.threshold
                recursiveSwitch.isOn = form.recursive
                automaticSwitch.isOn = form.automatic
                matchControl.selectedSegmentIndex = form.matchAll ? 0 : 1
                draftID = form.draftID ?? draftID
                formDirty = form.scanDirty
                if formDirty { previews.removeAll() }
            }
            if !models.contains(where: { $0.id == selectedModelID }), let replacement = models.first(where: { $0.runtime == "onnx" }) ?? models.first {
                selectedModelID = replacement.id
                thresholdSlider.value = replacement.defaultThreshold ?? 0.2
                statusLabel.text = AIText.value("The previous model is unavailable. Review the selected replacement and its threshold before scanning.", "原模型已不可用，请检查替代模型与相似度阈值后重新扫描。")
            }
            try await refreshInstalled()
            thresholdLabel.text = thresholdTitle
            tableView.reloadData()
        } catch { show(error) }
    }

    private func refreshInstalled() async throws {
        guard let manager = environment.aiFrames.models else { throw AIFrameError.invalidManifest }
        installed.removeAll()
        for model in models where await manager.isInstalled(model) { installed.insert(model.id) }
    }

    override func numberOfSections(in tableView: UITableView) -> Int { 4 + previews.count }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch section {
        case 0: return models.count + 1
        case 1: return folderRows.count + 2
        case 2: return filtersExpanded ? 7 : 2
        case 3: return 3
        default: return 1 + previews[section - 4].matches.filter { $0.decision != .rejected }.count
        }
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch section {
        case 0: return AIText.value("Model", "模型")
        case 1: return AIText.value("Source folders", "来源目录")
        case 2: return AIText.value("Classification rules", "分类规则")
        case 3: return AIText.value("Scan", "扫描")
        default: return previews[section - 4].configuration.rule.category
        }
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        switch section {
        case 0: return AIText.value("Models download on demand. Both support Chinese and English. Domestic mirrors may redirect to overseas servers.", "模型按需下载，两种模型均支持中英文。国内镜像可能重定向至境外服务器。")
        case 1: return collections.isEmpty ? AIText.value("Add photos from Photo Library, files, folders, or NAS, then choose folders to classify.", "从照片库、照片文件、目录或 NAS 添加照片，再选择要分类的目录。") : nil
        case 2: return AIText.value("For example: family, pets or the beach. Enter one category per line. Each category creates a frame, with a name you can edit in the preview.", "例如：家人、宠物、海边。每行填写一个类别，每个类别会生成一个相框；预览结果后可修改名称。")
        case 3: return AIText.value("Only selected folders are scanned. Continuous classification processes new photos. Pausing takes priority until you explicitly continue. Background work depends on iOS permission.", "仅扫描选定目录。持续分类处理新增照片。手动暂停后需明确继续；后台处理受 iOS 运行许可约束。")
        default:
            let preview = previews[section - 4]
            if !preview.complete {
                return AIText.value("Partial results · \(preview.selected.count) selected. Unscanned photos remain pending. Save now or continue scanning.", "部分结果 · 已选 \(preview.selected.count) 张。未扫描照片待继续，可先保存相框。")
            }
            return preview.matches.contains(where: { $0.decision != .rejected }) ? AIText.value("Review the matches, edit the suggested name and deselect mistakes before saving.", "保存前请确认匹配结果、修改建议名称并取消误选。") : AIText.value("No matches. Adjust the descriptions or lower the threshold, then scan again.", "没有匹配照片，请调整描述或降低阈值后重新扫描。")
        }
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.textLabel?.font = .preferredFont(forTextStyle: .body)
        cell.textLabel?.adjustsFontForContentSizeCategory = true
        cell.textLabel?.numberOfLines = 0
        cell.detailTextLabel?.numberOfLines = 0
        cell.detailTextLabel?.adjustsFontForContentSizeCategory = true
        switch indexPath.section {
        case 0: configureModelCell(cell, row: indexPath.row)
        case 1:
            if indexPath.row == 0 {
                cell.textLabel?.text = AIText.value("Add photos from a source", "从来源添加照片")
                cell.detailTextLabel?.text = AIText.value("Photo Library, files, folders, or NAS", "照片库、照片文件、目录或 NAS")
                cell.accessoryType = .disclosureIndicator
                cell.accessibilityIdentifier = "ai.sources.add"
            } else if indexPath.row == folderRows.count + 1 {
                configureToggle(cell, title: AIText.value("Include subfolders", "包含子目录"), toggle: recursiveSwitch)
            } else {
                let row = folderRows[indexPath.row - 1]
                guard let collection = row.collection else {
                    cell.textLabel?.text = row.title
                    cell.textLabel?.font = ThemeFont.heading()
                    cell.selectionStyle = .none
                    return cell
                }
                cell.accessibilityIdentifier = "ai.folder." + collection.id
                cell.textLabel?.text = collection.name
                cell.detailTextLabel?.text = AIText.value(
                    collection.memberCount == 1 ? "1 photo" : "\(collection.memberCount) photos",
                    "\(collection.memberCount) 张照片")
                cell.accessoryType = selectedCollections.contains(collection.id) ? .checkmark : .none
            }
        case 2: configureRuleCell(cell, row: indexPath.row)
        case 3:
            if indexPath.row == 0 {
                configureToggle(cell, title: AIText.value("Continuous auto-classification", "持续自动分类"), toggle: automaticSwitch)
            } else if indexPath.row == 1 {
                cell.textLabel?.text = savedJobRunning || busy ? AIText.value("Pause and view results", "暂停并查看结果")
                    : (draftID != nil || frameID != nil ? AIText.value("Continue scanning", "继续扫描") : AIText.value("Scan and preview", "扫描并预览"))
                cell.textLabel?.textColor = view.tintColor
                cell.accessibilityIdentifier = "ai.scan"
                cell.isUserInteractionEnabled = !saving
            } else { embed([statusLabel, progressView], in: cell) }
        default: configurePreviewCell(cell, indexPath: indexPath)
        }
        return cell
    }

    private func configureModelCell(_ cell: UITableViewCell, row: Int) {
        if row < models.count {
            let model = models[row]
            cell.accessibilityIdentifier = "ai.model." + model.id
            cell.textLabel?.text = model.title
            let size = ByteCountFormatter.string(fromByteCount: model.byteSize, countStyle: .file)
            cell.detailTextLabel?.text = size + " · " + (installed.contains(model.id) ? AIText.value("Downloaded", "已下载") : AIText.value("Download required", "需要下载"))
            cell.accessoryType = model.id == selectedModelID ? .checkmark : .none
        } else {
            cell.textLabel?.text = AIText.value("Manage AI models", "管理 AI 模型")
            cell.textLabel?.textColor = view.tintColor
            cell.accessibilityIdentifier = "ai.manageModels"
            cell.accessoryType = .disclosureIndicator
        }
    }

    private func configureRuleCell(_ cell: UITableViewCell, row: Int) {
        if row == 1 {
            cell.textLabel?.text = filtersExpanded ? AIText.value("Hide filters", "收起筛选条件") : AIText.value("More filters (optional)", "更多筛选条件（可选）")
            cell.accessibilityIdentifier = "ai.filters"
            cell.accessibilityTraits = .button
            return
        }
        switch row == 0 ? 0 : row - 1 {
        case 0: embed([label(AIText.value("What photos would you like to show?", "想展示什么照片？")), categoryInput], in: cell)
        case 1: embed([label(AIText.value("Include descriptions · optional, one per line", "包含描述 · 可选，每行一个")), includesInput], in: cell)
        case 2: embed([label(AIText.value("Exclude descriptions · optional, one per line", "排除描述 · 可选，每行一个")), excludesInput], in: cell)
        case 3: embed([label(AIText.value("Include match", "包含匹配方式")), matchControl], in: cell)
        case 4: embed([thresholdLabel, thresholdSlider], in: cell)
        default:
            cell.textLabel?.text = AIText.value("One category creates one frame. A photo can appear in multiple frames.", "每个类别创建一个相框，同一张照片可出现在多个相框中。")
            cell.selectionStyle = .none
        }
    }

    private func configurePreviewCell(_ cell: UITableViewCell, indexPath: IndexPath) {
        let index = indexPath.section - 4
        let preview = previews[index]
        if indexPath.row == 0 {
            let name = UITextField()
            name.text = preview.name
            name.borderStyle = .roundedRect
            KeyboardDismiss.installDoneToolbar(on: name)
            name.font = .preferredFont(forTextStyle: .body)
            name.adjustsFontForContentSizeCategory = true
            name.accessibilityIdentifier = "ai.name.\(index)"
            name.accessibilityLabel = AIText.value("Suggested frame name", "建议相框名称")
            name.tag = index
            name.addTarget(self, action: #selector(nameChanged(_:)), for: .editingChanged)
            embed([label(AIText.value("Suggested name", "建议名称")), name], in: cell)
            return
        }
        let match = visibleMatches(index)[indexPath.row - 1]
        let needsReview = match.decision == .review
        let themes = match.themes.compactMap { id in AIFrameNames.themes.first { $0.id == id } }
            .map { AIText.chinese ? $0.zh : $0.en }.joined(separator: ", ")
        cell.accessibilityIdentifier = "ai.match." + match.assetID
        cell.textLabel?.text = (needsReview ? AIText.value("Review", "待确认") : AIText.value("Matched", "已匹配")) + String(format: " · %.3f", match.score)
        cell.detailTextLabel?.text = themes
        cell.accessoryType = preview.selected.contains(match.assetID) ? .checkmark : .none
        cell.imageView?.image = UIImage(systemName: "photo")
        Task { [weak self, weak cell] in
            guard let self else { return }
            let path: String
            if match.assetID.hasPrefix("remote:") { path = match.assetID }
            else {
                guard let asset = try? await environment.assets.fetchAsset(id: match.assetID),
                      !asset.privacyExcluded, asset.deletedAt == nil, asset.readiness == "ready" else { return }
                path = asset.originalRelPath
            }
            let thumbnail = await thumbnailLoader.thumbnail(relPath: path, pointSize: 56, scale: self.traitCollection.displayScale)
            if !match.assetID.hasPrefix("remote:") {
                guard let current = try? await environment.assets.fetchAsset(id: match.assetID),
                      !current.privacyExcluded, current.deletedAt == nil, current.readiness == "ready",
                      match.contentVersion == nil || current.contentHash == match.contentVersion else { return }
            }
            guard let cell, self.tableView.indexPath(for: cell) == indexPath else { return }
            cell.imageView?.image = thumbnail
            cell.setNeedsLayout()
        }
    }

    private func visibleMatches(_ index: Int) -> [AIFrameMatch] { previews[index].matches.filter { $0.decision != .rejected } }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if indexPath.section == 2 && indexPath.row == 1 {
            filtersExpanded.toggle()
            tableView.reloadSections(IndexSet(integer: 2), with: .automatic)
            return
        }
        if indexPath.section == 3 && indexPath.row == 1 {
            if savedJobRunning, let frameID {
                Task {
                    await environment.aiFrames.cancel(frameID: frameID)
                    savedJobRunning = false
                    statusLabel.text = AIText.value("Classification paused. Tap Continue to resume.", "分类已暂停，点击继续以恢复。")
                    tableView.reloadSections(IndexSet(integer: 3), with: .none)
                }
            } else if busy, let id = draftID {
                Task { try? await environment.aiFrames.pauseDraft(id: id) }
            } else { scan() }
            return
        }
        guard !busy, !downloading else { return }
        switch indexPath.section {
        case 0:
            if indexPath.row < models.count {
                selectedModelID = models[indexPath.row].id
                thresholdSlider.value = models[indexPath.row].defaultThreshold ?? 0.2
                settingsChanged()
            } else { openModelManagement() }
        case 1:
            if indexPath.row == 0 {
                openSources()
                return
            }
            guard indexPath.row <= folderRows.count, let collection = folderRows[indexPath.row - 1].collection else { return }
            let id = collection.id
            if selectedCollections.contains(id) { selectedCollections.remove(id) } else { selectedCollections.insert(id) }
            settingsChanged()
        case 4...:
            guard indexPath.row > 0 else { return }
            let index = indexPath.section - 4
            let id = visibleMatches(index)[indexPath.row - 1].assetID
            if previews[index].selected.contains(id) { previews[index].selected.remove(id) } else { previews[index].selected.insert(id) }
            persistPresentation(index)
        default: break
        }
        tableView.reloadData()
    }

    private func openSources() {
        view.endEditing(true)
        let sources = SourcesListViewController(environment: environment)
        let navigation = UINavigationController(rootViewController: sources)
        navigation.setNavigationBarHidden(true, animated: false)
        navigation.modalPresentationStyle = .fullScreen
        sources.onConnected = { [weak self, weak navigation] sourceIDs, _ in
            guard let self, let navigation, presentedViewController === navigation else { return }
            navigation.dismiss(animated: true) { [weak self] in
                guard let self, viewIfLoaded?.window != nil else { return }
                Task { [weak self] in
                    guard let self else { return }
                    do {
                        collections = try await environment.assets.collections()
                        sourceNames = Dictionary(((try? environment.sources.webdavSources()) ?? []).map { ($0.id, $0.displayName) }, uniquingKeysWith: { first, _ in first })
                        let connected = Set(sourceIDs)
                        selectedCollections.formUnion(collections.filter { $0.sourceID.map(connected.contains) == true }.map(\.id))
                        settingsChanged()
                        tableView.reloadData()
                    } catch { show(error) }
                }
            }
        }
        present(navigation, animated: true)
    }

    func textViewDidChange(_ textView: UITextView) { invalidatePreview() }
    @objc private func settingsChanged() { thresholdLabel.text = thresholdTitle; invalidatePreview() }
    @objc private func automaticChanged() {
        for index in previews.indices { previews[index].configuration.automatic = automaticSwitch.isOn }
        let automatic = automaticSwitch.isOn
        if let id = draftID {
            Task { try? await environment.aiFrames.drafts.update(id) { draft in
                for index in draft.categories.indices { draft.categories[index].configuration.automatic = automatic }
            } }
        }
        persistForm()
    }
    private func invalidatePreview() {
        formDirty = true
        persistForm()
        if !previews.isEmpty { previews.removeAll(); tableView.reloadData() }
        draftStatus = "idle"
        navigationItem.rightBarButtonItem?.isEnabled = frameID != nil
    }
    @objc private func nameChanged(_ field: UITextField) {
        guard previews.indices.contains(field.tag) else { return }
        previews[field.tag].name = field.text ?? ""
        persistPresentation(field.tag)
    }

    private func configurations() throws -> [AIFrameConfiguration] {
        guard let model = models.first(where: { $0.id == selectedModelID }), !selectedCollections.isEmpty else { throw AIFrameError.invalidRules }
        let categories = lines(categoryInput.text)
        guard !categories.isEmpty, categories.count <= 10, frameID == nil || categories.count == 1 else { throw AIFrameError.invalidRules }
        return try categories.map { category in
            var configuration = AIFrameConfiguration(frameID: frameID ?? UUID().uuidString, modelID: model.id, modelRevision: model.revision,
                                                       collectionIDs: selectedCollections.sorted(),
                                                       rule: AIFrameRule(category: category, includes: lines(includesInput.text), excludes: lines(excludesInput.text),
                                                                         matchAll: matchControl.selectedSegmentIndex == 0, threshold: thresholdSlider.value))
            if frameID != nil { configuration.revision = loadedFrameRevision }
            configuration.recursive = recursiveSwitch.isOn
            configuration.automatic = automaticSwitch.isOn
            try configuration.validate()
            return configuration
        }
    }

    private func lines(_ value: String?) -> [String] {
        Array(NSOrderedSet(array: (value ?? "").components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty })) as? [String] ?? []
    }

    private func persistPresentation(_ index: Int) {
        guard let id = draftID, previews.indices.contains(index), !previews[index].categoryID.isEmpty else { return }
        let preview = previews[index]
        Task { try? await environment.aiFrames.drafts.presentation(id: id, categoryID: preview.categoryID, name: preview.name, selected: preview.selected) }
    }

    private func display(_ draft: AIScanDraft) {
        guard !saving, !formDirty, renderedDraftDate != draft.updatedAt else { return }
        renderedDraftDate = draft.updatedAt
        let wasBusy = busy
        draftID = draft.id
        draftStatus = draft.status
        busy = ["running", "pausing"].contains(draft.status)
        let editingName = containsActiveInput(view)
        if !editingName && !(wasBusy && busy) {
            previews = draft.categories.map { category in
                var preview = Preview(configuration: category.configuration, name: category.name, matches: category.matches,
                    selected: category.selected, initiallySelected: category.initiallySelected, savedFrameID: category.frameID)
                preview.categoryID = category.id
                preview.complete = category.complete
                return preview
            }
        }
        let state: String
        if draft.status == "pausing" { state = AIText.value("Pausing after the current photo", "完成当前照片后暂停") }
        else if draft.userPaused || draft.status == "needsResume" { state = AIText.value("Paused · continue when ready", "已暂停 · 可稍后继续") }
        else if draft.status == "complete" { state = AIText.value("Classification complete", "分类完成") }
        else if draft.status == "running" { state = AIText.value("Classifying", "正在分类") }
        else { state = AIText.value("Ready to continue", "待继续") }
        statusLabel.text = state + " · " + AIText.value("Classification progress \(draft.completedCount)/\(draft.totalCount)", "分类进度 \(draft.completedCount)/\(draft.totalCount)") + (draft.lastError.map { "\n" + $0 } ?? "")
        progressView.progress = draft.totalCount > 0 ? Float(draft.completedCount) / Float(draft.totalCount) : 0
        if !editingName && !(wasBusy && busy) { updateBusy() }
    }

    private func scan() {
        view.endEditing(true)
        operation = Task { [weak self] in
            guard let self else { return }
            do {
                let draft = try await environment.aiFrames.prepareDraft(configurations: configurations(), existingID: draftID)
                if let existingName, draft.categories.count == 1, let category = draft.categories.first {
                    try await environment.aiFrames.drafts.presentation(id: draft.id, categoryID: category.id, name: existingName, selected: category.selected)
                }
                draftID = draft.id
                formDirty = false
                persistForm()
                display(draft)
                guard installed.contains(selectedModelID) else { openModelManagement(); return }
                try await environment.aiFrames.startDraft(id: draft.id)
                if let running = try await environment.aiFrames.draft(id: draft.id) { display(running) }
            } catch { show(error) }
        }
    }

    private func openModelManagement() {
        view.endEditing(true)
        Task { [weak self] in
            guard let self else { return }
            try? await environment.aiFrames.drafts.saveForm(formSnapshot())
            let models = AIModelsViewController(environment: environment, focusModelID: selectedModelID)
            present(UINavigationController(rootViewController: models), animated: true)
        }
    }

    @objc private func save() {
        view.endEditing(true)
        guard !busy, !downloading else { return }
        if let id = draftID, !previews.isEmpty {
            guard previews.allSatisfy({ !$0.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else { show(AIFrameError.invalidRules); return }
            for index in previews.indices { persistPresentation(index) }
            busy = true
            saving = true
            updateBusy()
            operation = Task { [weak self] in
                guard let self else { return }
                do {
                    for preview in previews { try await environment.aiFrames.drafts.presentation(id: id, categoryID: preview.categoryID, name: preview.name, selected: preview.selected) }
                    _ = try await environment.aiFrames.saveDraft(id: id)
                    onSaved?()
                    dismiss(animated: true)
                } catch { show(error) }
                busy = false
                saving = false
                updateBusy()
            }
            return
        }
        if previews.isEmpty {
            guard let frameID else { return }
            do {
                guard var configuration = try configurations().first else { throw AIFrameError.invalidRules }
                configuration.frameID = frameID
                busy = true
                saving = true
                updateBusy()
                operation = Task {
                    defer { busy = false; saving = false; updateBusy() }
                    do {
                        try await environment.aiFrames.configure(configuration)
                        if configuration.automatic { await environment.aiFrames.start(frameID: frameID) }
                        onSaved?()
                        dismiss(animated: true)
                    } catch { show(error) }
                }
            } catch { show(error) }
            return
        }
        guard previews.allSatisfy({ !$0.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else {
            show(AIFrameError.invalidRules)
            return
        }
        busy = true
        saving = true
        updateBusy()
        operation = Task { [weak self] in
            guard let self else { return }
            do {
                for index in previews.indices {
                    let preview = previews[index]
                    if preview.savedFrameID != nil { continue }
                    let id: String
                    if let frameID { id = frameID }
                    else { id = try await environment.frameManager.createFrame(name: preview.name, storageMode: preview.matches.contains(where: { $0.assetID.hasPrefix("remote:") }) ? .remote : nil) }
                    do {
                        let matches = preview.matches.map { match in
                            AIFrameMatch(assetID: match.assetID, score: match.score,
                                         decision: preview.selected.contains(match.assetID) ? .matched : .rejected, themes: match.themes, contentVersion: match.contentVersion)
                        }
                        let removed = preview.initiallySelected.subtracting(preview.selected)
                        let manual = Set(preview.matches.filter { ($0.decision == .review || preservedExclusions.contains($0.assetID)) && preview.selected.contains($0.assetID) }.map(\.assetID))
                        try await environment.aiFrames.savePreview(frameID: id, configuration: preview.configuration, matches: matches, manualIncludes: manual, manualExcludes: removed)
                        if frameID != nil { try await environment.frameManager.renameFrame(frameID: id, name: preview.name) }
                        previews[index].savedFrameID = id
                    } catch {
                        if frameID == nil { try? await environment.aiFrames.store.discardEmptyDraft(frameID: id, name: preview.name, configuration: preview.configuration) }
                        throw error
                    }
                }
                onSaved?()
                dismiss(animated: true)
            } catch { show(error) }
            busy = false
            saving = false
            updateBusy()
        }
    }

    @objc private func dismissKeyboard() { view.endEditing(true) }

    private func containsActiveInput(_ view: UIView) -> Bool {
        view.isFirstResponder || view.subviews.contains { containsActiveInput($0) }
    }

    private func formSnapshot() -> AIFormDraft {
        var form = AIFormDraft()
        form.scanDirty = formDirty
        form.modelID = selectedModelID
        form.collections = Array(selectedCollections)
        form.categoryText = categoryInput.text ?? ""
        form.includesText = includesInput.text ?? ""
        form.excludesText = excludesInput.text ?? ""
        form.threshold = thresholdSlider.value
        form.automatic = automaticSwitch.isOn
        form.recursive = recursiveSwitch.isOn
        form.matchAll = matchControl.selectedSegmentIndex == 0
        form.frameID = frameID
        form.draftID = draftID
        return form
    }

    private func persistForm() {
        let form = formSnapshot()
        Task { try? await environment.aiFrames.drafts.saveForm(form) }
    }

    @objc private func close() {
        view.endEditing(true)
        let form = formSnapshot()
        Task { try? await environment.aiFrames.drafts.saveForm(form) }
        dismiss(animated: true)
    }

    private func updateBusy() {
        navigationItem.leftBarButtonItem?.isEnabled = !saving
        navigationItem.rightBarButtonItem?.isEnabled = !busy && !downloading && (frameID != nil || (!previews.isEmpty && draftStatus != "idle"))
        for control in [recursiveSwitch, automaticSwitch] { control.isEnabled = !busy && !downloading }
        for input in [categoryInput, includesInput, excludesInput] { input.isEditable = !busy && !downloading }
        matchControl.isEnabled = !busy && !downloading
        sourceControl.isEnabled = !busy && !downloading
        thresholdSlider.isEnabled = !busy && !downloading
        tableView.reloadData()
    }

    private func show(_ error: Error) {
        let message = AIFrameError.message(for: error)
        statusLabel.text = message
        let alert = UIAlertController(title: AIText.value("AI frames", "AI 相框"), message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: L10n.string("common.close"), style: .default))
        if presentedViewController == nil { present(alert, animated: true) }
    }

    private func configureToggle(_ cell: UITableViewCell, title: String, toggle: UISwitch) {
        cell.textLabel?.text = title
        cell.accessoryView = toggle
        toggle.accessibilityLabel = title
        cell.selectionStyle = .none
    }

    private func label(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text
        label.numberOfLines = 0
        label.font = .preferredFont(forTextStyle: .body)
        label.adjustsFontForContentSizeCategory = true
        return label
    }

    private func embed(_ views: [UIView], in cell: UITableViewCell) {
        let stack = UIStackView(arrangedSubviews: views)
        stack.axis = .vertical
        stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false
        cell.contentView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: cell.contentView.layoutMarginsGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: cell.contentView.layoutMarginsGuide.trailingAnchor),
            stack.topAnchor.constraint(equalTo: cell.contentView.layoutMarginsGuide.topAnchor),
            stack.bottomAnchor.constraint(equalTo: cell.contentView.layoutMarginsGuide.bottomAnchor)
        ])
        cell.selectionStyle = .none
    }
}
