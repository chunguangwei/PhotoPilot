import Foundation

enum AIFrameError: String, Error, LocalizedError, Equatable {
    case mediaBusy, invalidVector, invalidRules, invalidState, missingModel, invalidManifest, invalidDownload, staleRun, interrupted, promptTooLong, classificationFailed, busy, requiresForeground, lowPower, thermal, lowMemory

    var errorDescription: String? {
        switch self {
        case .mediaBusy: return AIText.value("Classification paused while playback uses the temporary cache. Return here to resume.", "播放正在使用临时缓存，分类已暂停，请返回此处继续扫描。")
        case .lowMemory: return AIText.value("There is not enough memory for this model. Try Multilingual CLIP Lite, or retry with more memory available.", "可用内存不足，无法运行此模型。请尝试 多语 CLIP 轻量版，或释放内存后重试。")
        case .busy: return AIText.value("Another classification or model operation is running. Wait or cancel it first.", "已有分类或模型操作正在进行，请等待完成或先取消。")
        case .requiresForeground: return AIText.value("Paused. Return to PhotoPilot to continue.", "已暂停，请回到 PhotoPilot 前台以继续。")
        case .lowPower: return AIText.value("Paused while Low Power Mode is enabled.", "低电量模式已启用，分类已暂停。")
        case .thermal: return AIText.value("Paused while the device is too warm. Try again after it cools.", "设备温度过高，分类已暂停，请降温后重试。")
        case .classificationFailed: return AIText.value("No photos could be classified. Check the model and photo formats, then scan again.", "未能完成照片分类，请检查模型与照片格式后重新扫描。")
        case .promptTooLong: return AIText.value("This description is too long. Shorten it or choose the standard model.", "描述过长，请缩短或选择标准模型。")
        case .invalidVector: return AIText.value("The model returned an invalid embedding.", "模型返回了无效的特征向量。")
        case .invalidRules: return AIText.value("Enter a category and select at least one folder.", "请输入类别并至少选择一个目录。")
        case .invalidState: return AIText.value("The saved AI task could not be read. Your photos are safe.", "无法读取已保存的 AI 任务，照片仍保存在图库中。")
        case .missingModel: return AIText.value("Download the selected model first.", "请先下载所选模型。")
        case .invalidManifest: return AIText.value("The model catalog is invalid.", "模型清单无效。")
        case .invalidDownload: return AIText.value("Model verification failed. Retry the download.", "模型校验失败，请重试下载。")
        case .staleRun: return AIText.value("The AI settings changed. Run the scan again.", "AI 设置已变更，请重新扫描。")
        case .interrupted: return AIText.value("Paused. Keep PhotoPilot open to continue.", "已暂停，请保持 PhotoPilot 在前台以继续。")
        }
    }
    static func message(for error: Error) -> String {
        if let error = error as? AIFrameError { return error.localizedDescription }
        if let error = error as? URLError {
            switch error.code {
            case .notConnectedToInternet, .networkConnectionLost, .cannotConnectToHost, .cannotFindHost, .timedOut:
                return AIText.value("The model download was interrupted. Check the connection and retry to resume.", "模型下载中断，请检查网络后重试以继续下载。")
            case .dataNotAllowed, .internationalRoamingOff:
                return AIText.value("This download requires Wi-Fi or explicit cellular permission.", "请连接 Wi-Fi，或明确允许蜂窝网络下载。")
            default: break
            }
        }
        let systemError = error as NSError
        if systemError.domain == NSCocoaErrorDomain && systemError.code == CocoaError.fileWriteOutOfSpace.rawValue {
            return AIText.value("There is not enough free space to download this model.", "可用存储空间不足，无法下载此模型。")
        }
        return AIText.value("The operation could not be completed.", "无法完成此操作。") + " " + error.localizedDescription
    }

}

enum AIText {
    static var chinese: Bool { AppLanguage.preferChinese }
    static func value(_ english: String, _ chinese: String) -> String { self.chinese ? chinese : english }
}

enum AIFrameDecision: String, Codable, Sendable { case matched, review, rejected }

struct AIFrameRule: Codable, Sendable, Equatable {
    var category: String
    var includes: [String] = []
    var excludes: [String] = []
    var matchAll = true
    var threshold: Float = 0.2

    func validate() throws {
        guard !category.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              includes.count <= 20, excludes.count <= 20,
              (includes + excludes).allSatisfy({ !$0.isEmpty }),
              threshold.isFinite, (0...1).contains(threshold) else { throw AIFrameError.invalidRules }
        guard ([category] + includes + excludes).allSatisfy({ $0.unicodeScalars.count <= 500 && $0.utf8.count <= 2000 }) else {
            throw AIFrameError.promptTooLong
        }
    }

    func decision(categoryScore: Float, includeScores: [Float], excludeScores: [Float]) -> AIFrameDecision {
        guard threshold.isFinite, (0...1).contains(threshold), categoryScore.isFinite, includeScores.count == includes.count,
              excludeScores.count == excludes.count,
              (includeScores + excludeScores).allSatisfy(\.isFinite) else { return .rejected }
        if excludeScores.contains(where: { $0 >= threshold }) { return .rejected }
        let includeAggregate = matchAll ? includeScores.min() : includeScores.max()
        let effectiveScore = min(categoryScore, includeAggregate ?? categoryScore)
        if effectiveScore >= threshold { return .matched }
        return effectiveScore >= threshold - 0.04 ? .review : .rejected
    }
}

enum AIFrameScoring {
    static func cosine(_ left: [Float], _ right: [Float]) throws -> Float {
        guard !left.isEmpty, left.count == right.count,
              left.allSatisfy(\.isFinite), right.allSatisfy(\.isFinite) else { throw AIFrameError.invalidVector }
        var product: Double = 0
        var leftNorm: Double = 0
        var rightNorm: Double = 0
        for index in left.indices {
            let a = Double(left[index]), b = Double(right[index])
            product += a * b
            leftNorm += a * a
            rightNorm += b * b
        }
        guard leftNorm > 0, rightNorm > 0 else { throw AIFrameError.invalidVector }
        return Float(max(-1, min(1, product / sqrt(leftNorm * rightNorm))))
    }
}

struct AIFrameConfiguration: Codable, Sendable, Equatable {
    var frameID: String
    var modelID: String
    var modelRevision: String
    var collectionIDs: [String]
    var rule: AIFrameRule
    var recursive = true
    var automatic = false
    var revision = 1

    var scanFingerprint: Data {
        var context = self
        context.frameID = ""
        context.revision = 1
        context.automatic = false
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return (try? encoder.encode(context)) ?? Data()
    }

    func validate() throws {
        try rule.validate()
        guard !frameID.isEmpty, !modelID.isEmpty, !modelRevision.isEmpty,
              !collectionIDs.isEmpty, revision > 0 else { throw AIFrameError.invalidRules }
    }
}

struct AIFrameRunToken: Codable, Equatable, Sendable {
    let id: UUID
    let revision: Int
}

struct AIFrameMatch: Codable, Sendable {
    let assetID: String
    let score: Float
    let decision: AIFrameDecision
    let themes: [String]
    var contentVersion: String? = nil
}

struct AIFrameState: Codable, Sendable {
    var schemaVersion = 1
    var configuration: AIFrameConfiguration
    var ownedAssetIDs: Set<String> = []
    var manualExcludedAssetIDs: Set<String> = []
    var completedContentVersions: [String: String] = [:]
    var matches: [String: AIFrameMatch] = [:]
    var runToken: AIFrameRunToken?
    var status = "idle"
    var userPaused: Bool? = nil
    var isUserPaused: Bool { userPaused == true || ["userPaused", "cancelled", "needsResume"].contains(status) }
    var lastError: String?
    var succeeded = 0
    var failed = 0
    var skipped = 0
    var total = 0

    static func decode(_ data: Data) throws -> AIFrameState {
        let state = try JSONDecoder().decode(Self.self, from: data)
        guard state.schemaVersion == 1 else { throw AIFrameError.invalidState }
        try state.configuration.validate()
        return state
    }

    mutating func beginRun() -> AIFrameRunToken {
        let token = AIFrameRunToken(id: UUID(), revision: configuration.revision)
        runToken = token
        status = "running"
        lastError = nil
        succeeded = 0
        failed = 0
        skipped = 0
        return token
    }

    func accepts(_ token: AIFrameRunToken) -> Bool {
        runToken == token && configuration.revision == token.revision && status == "running"
    }

    var shouldRunOnForeground: Bool {
        !isUserPaused && !["cancelled", "failed", "needsResume"].contains(status)
            && (configuration.automatic || ["running", "pausedAuto"].contains(status))
    }

    mutating func cancel() { runToken = nil; status = "cancelled"; userPaused = true }

    mutating func recordManualChange(includes: Set<String>, removed: Set<String>) {
        ownedAssetIDs.subtract(includes.union(removed))
        manualExcludedAssetIDs.formUnion(removed)
        manualExcludedAssetIDs.subtract(includes)
    }

    func canAutomaticallyInclude(_ assetID: String) -> Bool { !manualExcludedAssetIDs.contains(assetID) }
}

enum AIFrameNames {
    static let themes: [(id: String, en: String, zh: String)] = [
        ("nature", "Nature", "自然"), ("beach", "Beach", "海边"),
        ("mountains", "Mountains", "山野"), ("pets", "Pets", "宠物"),
        ("food", "Food", "美食"), ("flowers", "Flowers", "花卉"),
        ("city", "City", "城市"), ("family", "Family", "家庭"),
        ("sunset", "Sunset", "日落"), ("snow", "Snow", "雪景"),
        ("travel", "Travel", "旅行"), ("art", "Art", "艺术")
    ]

    static func suggestion(category: String, themes ids: [String], chinese: Bool = AIText.chinese) -> String {
        let category = category.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let id = ids.first, let theme = themes.first(where: { $0.id == id }) else { return category }
        let title = chinese ? theme.zh : theme.en
        return category.localizedCaseInsensitiveContains(title) ? category : "\(category) · \(title)"
    }
}

/// Conservative admission budgets based on measured native working sets.
/// The rest of the app remains available when a model cannot be admitted.
enum AIFrameMemoryPolicy {
    static let minimumWhileRunning: UInt64 = 134_217_728

    static func canLoad(runtime: String, physicalBytes: UInt64, availableBytes: UInt64) -> Bool {
        if runtime == "litertlm" && physicalBytes <= 2_147_483_648 { return false }
        let required: UInt64 = runtime == "litertlm" ? 1_500_000_000 : 550_000_000
        return availableBytes >= required
    }
}
