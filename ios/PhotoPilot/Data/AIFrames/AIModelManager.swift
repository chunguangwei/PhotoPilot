import CryptoKit
import Foundation

struct AIModelCatalog: Decodable, Sendable {
    let formatVersion: Int
    let models: [AIModelDescriptor]

    static func load(data: Data) throws -> Self {
        let catalog = try JSONDecoder().decode(Self.self, from: data)
        guard catalog.formatVersion == 1, !catalog.models.isEmpty,
              Set(catalog.models.map(\.id)).count == catalog.models.count else { throw AIFrameError.invalidManifest }
        for model in catalog.models {
            guard safeLeaf(model.id), safeLeaf(model.revision), !model.files.isEmpty,
                  ["litertlm", "onnx"].contains(model.runtime),
                  Set(model.files.map(\.name)).count == model.files.count,
                  model.files.filter({ $0.role == "model" }).count == 1 else { throw AIFrameError.invalidManifest }
            let roles = model.files.map(\.role)
            if model.runtime == "litertlm" {
                guard model.embeddingDimension == 768, roles == ["model"] else { throw AIFrameError.invalidManifest }
            } else {
                guard model.embeddingDimension == 512, roles.count == 4,
                      Set(roles) == Set(["model", "text", "projection", "tokenizer"]) else { throw AIFrameError.invalidManifest }
            }
            if let threshold = model.defaultThreshold {
                guard threshold.isFinite, (0...1).contains(threshold) else { throw AIFrameError.invalidManifest }
            }
            for file in model.files {
                guard safeLeaf(file.name), file.size > 0, file.sha256.count == 64,
                      file.sha256.allSatisfy({ $0.isHexDigit }),
                      !file.urls.international.isEmpty else { throw AIFrameError.invalidManifest }
                for value in file.urls.international + file.urls.domestic {
                    guard let url = URL(string: value), url.scheme == "https", url.host != nil,
                          url.user == nil, url.password == nil else { throw AIFrameError.invalidManifest }
                }
            }
        }
        return catalog
    }

    private static func safeLeaf(_ string: String) -> Bool {
        !string.isEmpty && string != "." && string != ".." && !string.contains("/") && !string.contains("\\")
    }
}

struct AIModelDescriptor: Decodable, Sendable {
    struct File: Decodable, Sendable {
        struct Sources: Decodable, Sendable { let international: [String]; let domestic: [String] }
        let name: String
        let role: String
        let size: Int64
        let sha256: String
        let urls: Sources
    }
    let id: String
    let displayName: [String: String]
    let runtime: String
    let revision: String
    let files: [File]
    let embeddingDimension: Int
    let defaultThreshold: Float?
    var byteSize: Int64 { files.reduce(0) { $0 + $1.size } }
    var title: String { displayName[AIText.chinese ? "zh-Hans" : "en"] ?? id }
}

enum AIModelDownloadSource: Int, Sendable { case automatic, domestic, international }

struct AIModelDownloadProgress: Sendable {
    let completedBytes: Int64
    let totalBytes: Int64
    var fraction: Double { totalBytes > 0 ? Double(completedBytes) / Double(totalBytes) : 0 }
}

actor AIModelManager {
    let catalog: AIModelCatalog
    let root: URL
    private let sessionConfiguration: URLSessionConfiguration
    private var downloads: [String: Task<Void, Error>] = [:]
    private var leasedIDs: Set<String> = []

    init(root: URL, catalog: AIModelCatalog, sessionConfiguration: URLSessionConfiguration = .ephemeral) {
        self.root = root
        self.catalog = catalog
        self.sessionConfiguration = sessionConfiguration.copy() as! URLSessionConfiguration
    }

    static func bundled(root: URL) throws -> AIModelManager {
        guard let url = Bundle.main.url(forResource: "ai-models", withExtension: "json") else { throw AIFrameError.invalidManifest }
        return AIModelManager(root: root, catalog: try AIModelCatalog.load(data: Data(contentsOf: url)))
    }

    func descriptor(id: String) throws -> AIModelDescriptor {
        guard let model = catalog.models.first(where: { $0.id == id }) else { throw AIFrameError.invalidManifest }
        return model
    }

    func directory(for model: AIModelDescriptor) -> URL { root.appendingPathComponent(model.id).appendingPathComponent(model.revision) }

    func isInstalled(_ model: AIModelDescriptor) -> Bool {
        let directory = directory(for: model)
        guard let marker = try? String(contentsOf: directory.appendingPathComponent("verified"), encoding: .utf8),
              marker == model.files.map({ "\($0.name):\($0.sha256)" }).joined(separator: "\n") else { return false }
        return model.files.allSatisfy { file in
            let values = try? directory.appendingPathComponent(file.name).resourceValues(forKeys: [.fileSizeKey])
            return Int64(values?.fileSize ?? -1) == file.size
        }
    }

    func acquire(_ model: AIModelDescriptor) throws -> URL {
        guard isInstalled(model) else { throw AIFrameError.missingModel }
        guard !leasedIDs.contains(model.id) else { throw AIFrameError.busy }
        for file in model.files {
            guard try Self.verify(directory(for: model).appendingPathComponent(file.name), file: file) else {
                try? FileManager.default.removeItem(at: directory(for: model).appendingPathComponent("verified"))
                throw AIFrameError.invalidDownload
            }
        }
        leasedIDs.insert(model.id)
        return directory(for: model)
    }

    func release(id: String) { leasedIDs.remove(id) }

    func remove(id: String) throws {
        guard downloads[id] == nil, !leasedIDs.contains(id) else { throw AIFrameError.busy }
        let model = try descriptor(id: id)
        let directory = root.appendingPathComponent(model.id)
        if FileManager.default.fileExists(atPath: directory.path) { try FileManager.default.removeItem(at: directory) }
    }

    func cancelAllAndWait() async {
        let active = Array(downloads.values)
        for task in active { task.cancel() }
        for task in active { _ = try? await task.value }
    }

    func cancel(id: String) { downloads[id]?.cancel() }

    func download(id: String, source: AIModelDownloadSource, allowCellular: Bool,
                  progress: @escaping @Sendable (AIModelDownloadProgress) -> Void) async throws {
        if let task = downloads[id] { return try await task.value }
        let model = try descriptor(id: id)
        if isInstalled(model) { return }
        let directory = directory(for: model)
        let task = Task {
            try await Self.install(model, directory: directory, source: source, allowCellular: allowCellular, configuration: sessionConfiguration, progress: progress)
        }
        downloads[id] = task
        defer { downloads[id] = nil }
        try await task.value
    }

    private static func install(_ model: AIModelDescriptor, directory: URL, source: AIModelDownloadSource,
                                allowCellular: Bool, configuration: URLSessionConfiguration, progress: @escaping @Sendable (AIModelDownloadProgress) -> Void) async throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var backupValues = URLResourceValues()
        backupValues.isExcludedFromBackup = true
        var directory = directory
        try directory.setResourceValues(backupValues)
        let config = configuration.copy() as! URLSessionConfiguration
        config.allowsCellularAccess = allowCellular
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 3600
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        var completed: Int64 = 0
        for file in model.files {
            try Task.checkCancellation()
            let destination = directory.appendingPathComponent(file.name)
            if try verify(destination, file: file) { completed += file.size; continue }
            let urls: [String]
            switch source {
            case .domestic: urls = file.urls.domestic
            case .international: urls = file.urls.international
            case .automatic: urls = file.urls.domestic + file.urls.international
            }
            guard !urls.isEmpty else { throw AIFrameError.invalidDownload }
            var lastError: Error = AIFrameError.invalidDownload
            var installed = false
            for value in urls {
                do {
                    try await downloadFile(file, url: URL(string: value)!, directory: directory, session: session,
                                           completed: completed, total: model.byteSize, progress: progress)
                    installed = true
                    break
                } catch {
                    try Task.checkCancellation()
                    lastError = error
                }
            }
            guard installed else { throw lastError }
            completed += file.size
            progress(AIModelDownloadProgress(completedBytes: completed, totalBytes: model.byteSize))
        }
        try Task.checkCancellation()
        let marker = model.files.map { "\($0.name):\($0.sha256)" }.joined(separator: "\n")
        try marker.write(to: directory.appendingPathComponent("verified"), atomically: true, encoding: .utf8)
    }

    private static func downloadFile(_ file: AIModelDescriptor.File, url: URL, directory: URL, session: URLSession,
                                     completed: Int64, total: Int64,
                                     progress: @escaping @Sendable (AIModelDownloadProgress) -> Void) async throws {
        let partial = directory.appendingPathComponent(file.name + ".partial")
        let identity = directory.appendingPathComponent(file.name + ".identity")
        var offset = Int64((try? partial.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        var etag = (try? String(contentsOf: identity, encoding: .utf8)) ?? ""
        if offset > file.size || (offset > 0 && etag.isEmpty) {
            try? FileManager.default.removeItem(at: partial)
            offset = 0
        }
        let capacity = try? directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage
        if let capacity, capacity < file.size - offset + 16_777_216 { throw CocoaError(.fileWriteOutOfSpace) }
        while offset < file.size {
            try Task.checkCancellation()
            var request = URLRequest(url: url)
            let end = min(file.size - 1, offset + 4_194_303)
            request.setValue("bytes=\(offset)-\(end)", forHTTPHeaderField: "Range")
            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            if offset > 0 { request.setValue(etag, forHTTPHeaderField: "If-Range") }
            let (temporary, response) = try await session.download(for: request)
            defer { try? FileManager.default.removeItem(at: temporary) }
            try Task.checkCancellation()
            guard let http = response as? HTTPURLResponse else { throw AIFrameError.invalidDownload }
            let downloaded = Int64(try temporary.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
            let responseETag = http.value(forHTTPHeaderField: "ETag") ?? ""
            if http.statusCode == 200 {
                guard downloaded == file.size else { throw AIFrameError.invalidDownload }
                try? FileManager.default.removeItem(at: partial)
                try FileManager.default.moveItem(at: temporary, to: partial)
                offset = downloaded
            } else {
                guard http.statusCode == 206, downloaded == end - offset + 1,
                      http.value(forHTTPHeaderField: "Content-Range") == "bytes \(offset)-\(end)/\(file.size)" else {
                    throw AIFrameError.invalidDownload
                }
                if offset > 0 && responseETag != etag {
                    try? FileManager.default.removeItem(at: partial)
                    offset = 0
                    etag = ""
                    continue
                }
                if offset == 0 {
                    FileManager.default.createFile(atPath: partial.path, contents: nil)
                    etag = responseETag
                    try etag.write(to: identity, atomically: true, encoding: .utf8)
                }
                let writer = try FileHandle(forWritingTo: partial)
                do {
                    try writer.seekToEnd()
                    let reader = try FileHandle(forReadingFrom: temporary)
                    defer { try? reader.close() }
                    while let chunk = try reader.read(upToCount: 1_048_576), !chunk.isEmpty { try writer.write(contentsOf: chunk) }
                    try writer.synchronize()
                    try writer.close()
                } catch { try? writer.close(); throw error }
                offset += downloaded
            }
            progress(AIModelDownloadProgress(completedBytes: completed + offset, totalBytes: total))
        }
        guard try verify(partial, file: file) else {
            try? FileManager.default.removeItem(at: partial)
            try? FileManager.default.removeItem(at: identity)
            throw AIFrameError.invalidDownload
        }
        let destination = directory.appendingPathComponent(file.name)
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.moveItem(at: partial, to: destination)
        try? FileManager.default.removeItem(at: identity)
    }

    static func verify(_ url: URL, file: AIModelDescriptor.File) throws -> Bool {
        guard FileManager.default.fileExists(atPath: url.path),
              (try FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value == file.size else { return false }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hash = SHA256()
        while let data = try handle.read(upToCount: 1_048_576), !data.isEmpty { hash.update(data: data) }
        let actual = hash.finalize().map { String(format: "%02x", $0) }.joined()
        return actual == file.sha256.lowercased()
    }
}
