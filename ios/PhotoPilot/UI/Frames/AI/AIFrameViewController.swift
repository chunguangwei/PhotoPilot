import UIKit

final class AIFrameViewController: UITableViewController, UITextViewDelegate {
    private struct Preview {
        var configuration: AIFrameConfiguration
        var name: String
        var matches: [AIFrameMatch]
        var selected: Set<String>
        var initiallySelected: Set<String>
        var savedFrameID: String?
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
        guard let frameID else { return }
        stateMonitor = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                if !self.busy && !self.downloading,
                   let state = try? await self.environment.aiFrames.store.state(frameID: frameID) {
                    let wasRunning = self.savedJobRunning
                    self.savedJobRunning = state.status == "running"
                    if self.savedJobRunning || wasRunning || state.lastError != nil {
                        let errorText = state.lastError.map { code in
                            AIFrameError(rawValue: code)?.localizedDescription ?? AIText.value("Classification failed. Check the model and scan again.", "分类失败，请检查模型并重新扫描。")
                        }
                        self.statusLabel.text = errorText ?? AIText.value("Processed \(state.succeeded + state.skipped)/\(state.total) · Failed \(state.failed)", "已处理 \(state.succeeded + state.skipped)/\(state.total) · 失败 \(state.failed)")
                    }
                    if wasRunning != self.savedJobRunning { self.tableView.reloadSections(IndexSet(integer: 3), with: .none) }
                }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
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
        case 0: return models.count + 3
        case 1: return folderRows.count + 1
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
        case 1: return collections.isEmpty ? AIText.value("Import photos from Sources first, then select their folders here.", "请先在“来源”中导入照片，再返回选择目录。") : nil
        case 2: return AIText.value("For example: family, pets or the beach. Enter one category per line. Each category creates a frame, with a name you can edit in the preview.", "例如：家人、宠物、海边。每行填写一个类别，每个类别会生成一个相框；预览结果后可修改名称。")
        case 3: return AIText.value("Only selected folders are scanned. Continuous classification processes new photos after import while the app is open. It can be changed later.", "仅扫描选定目录。持续分类会在导入完成且 App 打开时处理新照片，之后可修改。")
        default:
            let preview = previews[section - 4]
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
            if indexPath.row == folderRows.count { configureToggle(cell, title: AIText.value("Include subfolders", "包含子目录"), toggle: recursiveSwitch) }
            else {
                let row = folderRows[indexPath.row]
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
                cell.textLabel?.text = savedJobRunning ? AIText.value("Cancel classification", "取消分类任务") : (busy || downloading ? L10n.string("common.cancel") : AIText.value("Scan and preview", "扫描并预览"))
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
        } else if row == models.count { embed([sourceControl], in: cell) }
        else {
            let delete = row == models.count + 2
            cell.textLabel?.text = delete ? AIText.value("Delete downloaded model", "删除已下载模型") : AIText.value("Download selected model", "下载所选模型")
            cell.textLabel?.textColor = delete ? .systemRed : view.tintColor
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
                guard let asset = try? await environment.assets.fetchAsset(id: match.assetID) else { return }
                path = asset.originalRelPath
            }
            let thumbnail = await thumbnailLoader.thumbnail(relPath: path, pointSize: 56, scale: self.traitCollection.displayScale)
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
                    statusLabel.text = AIText.value("Classification cancelled. Scan again to resume.", "分类已取消，可重新扫描以继续。")
                    tableView.reloadSections(IndexSet(integer: 3), with: .none)
                }
            } else if busy || downloading { cancelOperation() } else { scan() }
            return
        }
        guard !busy, !downloading else { return }
        switch indexPath.section {
        case 0:
            if indexPath.row < models.count {
                selectedModelID = models[indexPath.row].id
                thresholdSlider.value = models[indexPath.row].defaultThreshold ?? 0.2
                settingsChanged()
            } else if indexPath.row == models.count + 1 { confirmDownload() }
            else if indexPath.row == models.count + 2 { deleteModel() }
        case 1:
            guard indexPath.row < folderRows.count, let collection = folderRows[indexPath.row].collection else { return }
            let id = collection.id
            if selectedCollections.contains(id) { selectedCollections.remove(id) } else { selectedCollections.insert(id) }
            settingsChanged()
        case 4...:
            guard indexPath.row > 0 else { return }
            let index = indexPath.section - 4
            let id = visibleMatches(index)[indexPath.row - 1].assetID
            if previews[index].selected.contains(id) { previews[index].selected.remove(id) } else { previews[index].selected.insert(id) }
        default: break
        }
        tableView.reloadData()
    }

    func textViewDidChange(_ textView: UITextView) { invalidatePreview() }
    @objc private func settingsChanged() { thresholdLabel.text = thresholdTitle; invalidatePreview() }
    @objc private func automaticChanged() {
        for index in previews.indices { previews[index].configuration.automatic = automaticSwitch.isOn }
    }
    private func invalidatePreview() {
        if !previews.isEmpty { previews.removeAll(); tableView.reloadData() }
        navigationItem.rightBarButtonItem?.isEnabled = frameID != nil
    }
    @objc private func nameChanged(_ field: UITextField) {
        guard previews.indices.contains(field.tag) else { return }
        previews[field.tag].name = field.text ?? ""
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

    private func scan() {
        view.endEditing(true)
        do {
            let configurations = try configurations()
            guard installed.contains(selectedModelID) else { throw AIFrameError.missingModel }
            busy = true
            statusLabel.text = AIText.value("Preparing the local model…", "正在准备本机模型…")
            progressView.progress = 0
            previews.removeAll()
            updateBusy()
            operation = Task { [weak self] in
                guard let self else { return }
                do {
                    for configuration in configurations {
                        let results = try await environment.aiFrames.preview(configuration: configuration) { [weak self] progress in
                            Task { @MainActor [weak self] in
                                guard let self else { return }
                                self.statusLabel.text = AIText.value("\(progress.completed)/\(progress.total) · \(progress.matched) matched · \(progress.review) review · \(progress.failed) failed", "\(progress.completed)/\(progress.total) · 匹配 \(progress.matched) · 待确认 \(progress.review) · 失败 \(progress.failed)")
                                self.progressView.progress = progress.total > 0 ? Float(progress.completed) / Float(progress.total) : 0
                            }
                        }
                        try Task.checkCancellation()
                        let themeIDs = results.filter { $0.decision == .matched }.flatMap(\.themes)
                        let counts = Dictionary(grouping: themeIDs, by: { $0 }).mapValues(\.count)
                        let themes = counts.keys.sorted { counts[$0, default: 0] > counts[$1, default: 0] }
                        let name = existingName ?? AIFrameNames.suggestion(category: configuration.rule.category, themes: themes)
                        let selected = Set(results.filter { $0.decision == .matched && !preservedExclusions.contains($0.assetID) }.map(\.assetID))
                        previews.append(Preview(configuration: configuration, name: name, matches: results,
                                                selected: selected, initiallySelected: selected))
                    }
                    if previews.allSatisfy({ $0.matches.isEmpty }) { statusLabel.text = AIText.value("No eligible photos in the selected folders.", "所选目录中没有可分类照片。") }
                } catch is CancellationError { statusLabel.text = AIText.value("Scan cancelled.", "已取消扫描。") }
                catch { show(error) }
                busy = false
                updateBusy()
            }
        } catch { show(error) }
    }

    @objc private func save() {
        view.endEditing(true)
        guard !busy, !downloading else { return }
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

    private func confirmDownload() {
        guard let model = models.first(where: { $0.id == selectedModelID }) else { return }
        let alert = UIAlertController(title: model.title,
                                      message: AIText.value("Download \(ByteCountFormatter.string(fromByteCount: model.byteSize, countStyle: .file)) of model files?", "下载 \(ByteCountFormatter.string(fromByteCount: model.byteSize, countStyle: .file)) 模型文件？"), preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: AIText.value("Wi-Fi only", "仅 Wi-Fi"), style: .default) { [weak self] _ in self?.download(allowCellular: false) })
        alert.addAction(UIAlertAction(title: AIText.value("Allow cellular", "允许蜂窝网络"), style: .default) { [weak self] _ in self?.download(allowCellular: true) })
        alert.addAction(UIAlertAction(title: L10n.string("common.cancel"), style: .cancel))
        present(alert, animated: true)
    }

    private func download(allowCellular: Bool) {
        downloading = true
        updateBusy()
        let modelID = selectedModelID
        let source = AIModelDownloadSource(rawValue: sourceControl.selectedSegmentIndex) ?? .automatic
        operation = Task { [weak self] in
            guard let self else { return }
            do {
                guard let manager = environment.aiFrames.models else { throw AIFrameError.invalidManifest }
                try await manager.download(id: modelID, source: source, allowCellular: allowCellular) { [weak self] progress in
                    Task { @MainActor [weak self] in
                        self?.progressView.progress = Float(progress.fraction)
                        self?.statusLabel.text = ByteCountFormatter.string(fromByteCount: progress.completedBytes, countStyle: .file) + " / " + ByteCountFormatter.string(fromByteCount: progress.totalBytes, countStyle: .file)
                    }
                }
                try await refreshInstalled()
                statusLabel.text = AIText.value("Model verified and ready for offline use.", "模型已校验，可离线使用。")
            } catch is CancellationError { statusLabel.text = AIText.value("Download paused. Tap Download to resume.", "下载已暂停，点击下载可继续。") }
            catch { show(error) }
            downloading = false
            updateBusy()
        }
    }

    private func deleteModel() {
        Task {
            do {
                guard let manager = environment.aiFrames.models else { throw AIFrameError.invalidManifest }
                try await manager.remove(id: selectedModelID)
                try await refreshInstalled()
                tableView.reloadData()
            } catch { show(error) }
        }
    }

    private func cancelOperation() {
        operation?.cancel()
        Task {
            await environment.aiFrames.cancelPreview()
            if downloading, let manager = environment.aiFrames.models { await manager.cancel(id: selectedModelID) }
        }
    }

    @objc private func dismissKeyboard() { view.endEditing(true) }

    @objc private func close() { cancelOperation(); dismiss(animated: true) }

    private func updateBusy() {
        navigationItem.leftBarButtonItem?.isEnabled = !saving
        navigationItem.rightBarButtonItem?.isEnabled = !busy && !downloading && (frameID != nil || !previews.isEmpty)
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
