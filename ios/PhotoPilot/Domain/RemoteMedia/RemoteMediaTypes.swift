import Foundation

enum FrameStorageMode: String, Codable, Sendable {
    case remote
    case local

    static func read(_ json: String) -> FrameStorageMode {
        guard let object = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
              let value = object["mediaStorageMode"] as? String else { return .local }
        return FrameStorageMode(rawValue: value) ?? .local
    }

    static func isExplicit(_ json: String) -> Bool {
        guard let object = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
              let value = object["mediaStorageMode"] as? String else { return false }
        return FrameStorageMode(rawValue: value) != nil
    }

    func applying(to json: String) throws -> String {
        var object = (try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any]) ?? [:]
        object["mediaStorageMode"] = rawValue
        return String(decoding: try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]), as: UTF8.self)
    }
}

enum MediaReference: Hashable, Sendable {
    case asset(String)
    case sourceAsset(String)

    init(runtimeID: String) {
        self = runtimeID.hasPrefix("remote:") ? .sourceAsset(String(runtimeID.dropFirst(7))) : .asset(runtimeID)
    }

    var rawID: String {
        switch self {
        case .asset(let id), .sourceAsset(let id): return id
        }
    }

    var runtimeID: String {
        switch self {
        case .asset(let id): return id
        case .sourceAsset(let id): return "remote:" + id
        }
    }

    var selection: SelectionRefEdit {
        switch self {
        case .asset(let id): return SelectionRefEdit(assetId: id, sourceAssetId: nil)
        case .sourceAsset(let id): return SelectionRefEdit(assetId: nil, sourceAssetId: id)
        }
    }
}

enum RemoteMediaPath {
    static func normalized(_ value: String) -> String? {
        let parts = value.replacingOccurrences(of: "\\", with: "/").split(separator: "/")
        guard !parts.contains("..") else { return nil }
        return NasPath.normalize(value)
    }

    static func contains(folder: String, remoteKey: String, recursive: Bool) -> Bool {
        guard let folder = normalized(folder), let remote = normalized(remoteKey), !remote.isEmpty else { return false }
        let parent = remote.split(separator: "/").dropLast().joined(separator: "/")
        let parentBytes = Data(parent.utf8)
        let folderBytes = Data(folder.utf8)
        return parentBytes == folderBytes || (recursive && (folder.isEmpty || parentBytes.starts(with: Data((folder + "/").utf8))))
    }
}

enum RemoteMediaEligibility {
    static func playable(mode: FrameStorageMode, sourceStatus: String, accessState: String,
                         policyAllowed: Bool, privacyExcluded: Bool, deleted: Bool,
                         localReady: Bool, localFileExists: Bool, mediaKind: String) -> Bool {
        guard policyAllowed, !privacyExcluded, !deleted, mediaKind != "video" else { return false }
        if localReady && localFileExists { return true }
        return mode == .remote && sourceStatus == "active" && accessState == "accessible"
    }
}

enum RemoteMediaPriority: Int, Sendable {
    case background
    case visible
    case playback
}

struct RemoteMediaDescriptor: Hashable, Codable, Sendable {
    let sourceID: String
    let sourceEpoch: Int
    let sourceAssetID: String
    let revision: String
    let remoteKey: String
    let byteSize: Int64?
    let takenAt: Int64?

    var runtimeID: String { "remote:" + sourceAssetID }
    var identity: String { [sourceID, String(sourceEpoch), sourceAssetID, revision].joined(separator: "\u{0}") }
}

enum RemoteMediaError: String, Error, LocalizedError, Equatable {
    case unavailable
    case revoked
    case tooLarge
    case lowSpace
    case busy
    case localCopyMissing
    case changed
    case invalidFile

    var errorDescription: String? {
        switch self {
        case .unavailable: return AIText.value("The remote source is unavailable. Check the connection and retry.", "远端来源暂不可用，请检查网络后重试。")
        case .revoked: return AIText.value("This photo is no longer available to this frame.", "此照片已不可供该相框使用。")
        case .tooLarge: return AIText.value("This photo exceeds the temporary cache limit. Enable local synchronization to keep an offline copy.", "此照片超过临时缓存限额，请开启本地同步以保存离线副本。")
        case .lowSpace: return AIText.value("There is not enough free space. Free some storage and retry.", "可用空间不足，请释放存储空间后重试。")
        case .busy: return AIText.value("The temporary cache is busy. Retry after the current photo finishes loading.", "临时缓存正在使用中，请待当前照片加载完成后重试。")
        case .localCopyMissing: return AIText.value("The offline copy is not ready. Resume synchronization or use online playback.", "离线副本尚未就绪，请继续同步或使用在线播放。")
        case .changed: return AIText.value("The frame or source changed. Retry with its current settings.", "相框或来源已变更，请按当前设置重试。")
        case .invalidFile: return AIText.value("The photo could not be read.", "无法读取此照片。")
        }
    }
}

struct RemoteImportProvenance: Codable, Sendable, Equatable {
    var version = 1
    let sourceEpoch: Int
    let importedRevision: String
    let contentHash: String
    var frameScoped = true

    static func key(assetID: String, sourceAssetID: String) -> String { "remote.import.v1." + assetID + "." + sourceAssetID }
}
