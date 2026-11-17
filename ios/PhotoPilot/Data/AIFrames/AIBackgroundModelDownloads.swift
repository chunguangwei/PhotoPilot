import Foundation

struct AIModelDownloadState: Codable, Sendable {
    let modelID: String
    let revision: String
    let source: AIModelDownloadSource
    let allowCellular: Bool
    let totalBytes: Int64
    var generation = UUID().uuidString
    var status = "idle"
    var fileBytes: [String: Int64] = [:]
    var lastError: String?
    var completedBytes: Int64 { min(totalBytes, fileBytes.values.reduce(0, +)) }
}

actor AIModelDownloadJournal {
    let root: URL
    init(root: URL) { self.root = root.appendingPathComponent("download-journal", isDirectory: true) }

    func get(_ id: String) throws -> AIModelDownloadState? {
        let file = root.appendingPathComponent(id + ".json")
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        return try JSONDecoder().decode(AIModelDownloadState.self, from: Data(contentsOf: file))
    }

    func all() throws -> [AIModelDownloadState] {
        guard let files = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) else { return [] }
        return try files.filter { $0.pathExtension == "json" }.map { try JSONDecoder().decode(AIModelDownloadState.self, from: Data(contentsOf: $0)) }
    }

    func put(_ state: AIModelDownloadState) throws {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var directory = root
        try directory.setResourceValues(values)
        try JSONEncoder().encode(state).write(to: root.appendingPathComponent(state.modelID + ".json"), options: .atomic)
    }

    func begin(_ requested: AIModelDownloadState) throws -> AIModelDownloadState {
        var state = try get(requested.modelID)
        if state?.revision != requested.revision || state?.source != requested.source || state?.allowCellular != requested.allowCellular {
            state = requested
        }
        guard var state else { throw AIFrameError.invalidState }
        if ["paused", "failed"].contains(state.status) { state.generation = UUID().uuidString }
        state.status = "downloading"
        state.lastError = nil
        try put(state)
        return state
    }

    func remove(_ id: String) throws {
        let file = root.appendingPathComponent(id + ".json")
        if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
    }

    func update(_ id: String, generation: String, change: @Sendable (inout AIModelDownloadState) throws -> Void) throws {
        guard var state = try get(id), state.generation == generation else { return }
        try change(&state)
        try put(state)
    }

    func accepts(modelID: String, generation: String) throws -> Bool { try get(modelID)?.generation == generation }
}

struct AIModelResumePolicy: Codable, Equatable, Sendable {
    let url: String
    let allowCellular: Bool
}

struct AITransferToken: Codable, Sendable {
    let modelID: String
    let revision: String
    let file: String
    let generation: String
    let offset: Int64
    let urlIndex: Int
    var resumed: Bool? = nil
}

struct AIInboundDownload: Codable, Sendable {
    let token: AITransferToken
    let url: String
    let status: Int
    let headers: [String: String]
}

private final class AIModelSessionDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    weak var owner: AIBackgroundModelDownloads?
    let incoming: URL
    let group = DispatchGroup()
    init(root: URL) { incoming = root.appendingPathComponent("incoming", isDirectory: true) }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard FileManager.default.fileExists(atPath: incoming.path) else { return }
        guard let description = downloadTask.taskDescription,
              let token = try? JSONDecoder().decode(AITransferToken.self, from: Data(description.utf8)),
              let response = downloadTask.response as? HTTPURLResponse else { return }
        let preserved = incoming.appendingPathComponent(UUID().uuidString)
        let metadata = preserved.appendingPathExtension("json")
        let headers = response.allHeaderFields.reduce(into: [String: String]()) { $0[String(describing: $1.key)] = String(describing: $1.value) }
        let record = AIInboundDownload(token: token, url: response.url?.absoluteString ?? "", status: response.statusCode, headers: headers)
        do {
            try JSONEncoder().encode(record).write(to: metadata, options: .atomic)
            try FileManager.default.moveItem(at: location, to: preserved)
        } catch { return }
        group.enter()
        Task {
            await owner?.received(record, file: preserved)
            await owner?.completedTask(downloadTask.taskIdentifier)
            group.leave()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        Task { await owner?.progress(downloadTask, bytes: totalBytesWritten) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        group.enter()
        Task {
            await owner?.failed(task, error: error)
            group.leave()
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        group.notify(queue: .main) { Task { await self.owner?.finishEvents() } }
    }
}

actor AIBackgroundModelDownloads {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var instances: [String: AIBackgroundModelDownloads] = [:]
    let root: URL
    let journal: AIModelDownloadJournal
    private let catalog: AIModelCatalog
    private let bridge: AIModelSessionDelegate
    private let session: URLSession
    private let beforeStart: (@Sendable () async -> Void)?
    private var tasks: [Int: URLSessionDownloadTask] = [:]
    private var lastProgress: [String: Date] = [:]
    private var completion: (@MainActor @Sendable () -> Void)?
    private var restoration: Task<Void, Never>?
    private var blocked: Set<String> = []
    private var intents: [String: UUID] = [:]
    private var activeGenerations: [String: String] = [:]

    static var identifier: String { (Bundle.main.bundleIdentifier ?? "dev.photopilot.scaffold") + ".ai.models" }

    static func shared(root: URL, catalog: AIModelCatalog) -> AIBackgroundModelDownloads {
        lock.lock()
        defer { lock.unlock() }
        if let existing = instances[root.path] { return existing }
        let value = AIBackgroundModelDownloads(root: root, catalog: catalog)
        instances[root.path] = value
        return value
    }

    init(root: URL, catalog: AIModelCatalog, sessionIdentifier: String? = nil, beforeStart: (@Sendable () async -> Void)? = nil) {
        self.beforeStart = beforeStart
        self.root = root
        self.catalog = catalog
        journal = AIModelDownloadJournal(root: root)
        bridge = AIModelSessionDelegate(root: root)
        let configuration = URLSessionConfiguration.background(withIdentifier: sessionIdentifier ?? Self.identifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.httpMaximumConnectionsPerHost = 2
        session = URLSession(configuration: configuration, delegate: bridge, delegateQueue: nil)
        bridge.owner = self
        Task { await restore() }
    }

    func handleEvents(_ handler: @escaping @MainActor @Sendable () -> Void) async {
        completion = handler
        await restore()
    }

    func finishEvents() async {
        let handler = completion
        completion = nil
        await handler?()
    }

    func activeRequests() -> [URLRequest] { tasks.values.compactMap(\.originalRequest) }

    func states() async -> [AIModelDownloadState] { (try? await journal.all()) ?? [] }

    func restore() async {
        if let restoration { await restoration.value; return }
        let work = Task { await self.restoreTasks() }
        restoration = work
        await work.value
    }

    private func restoreTasks() async {
        await restoreIncoming()
        let existing = await withCheckedContinuation { continuation in session.getAllTasks { continuation.resume(returning: $0) } }
        for task in existing {
            guard let download = task as? URLSessionDownloadTask, let token = token(task),
                  let state = try? await journal.get(token.modelID), state.generation == token.generation,
                  state.status == "downloading" else { task.cancel(); continue }
            guard !blocked.contains(token.modelID), activeGenerations[token.modelID] == nil || activeGenerations[token.modelID] == token.generation else { task.cancel(); continue }
            activeGenerations[token.modelID] = token.generation
            tasks[task.taskIdentifier] = download
            if task.state == .suspended { task.resume() }
        }
    }

    private func restoreIncoming() async {
        guard let files = try? FileManager.default.contentsOfDirectory(at: bridge.incoming, includingPropertiesForKeys: [.contentModificationDateKey]) else { return }
        for metadata in files where metadata.pathExtension == "json" {
            let body = metadata.deletingPathExtension()
            if let data = try? Data(contentsOf: metadata), let record = try? JSONDecoder().decode(AIInboundDownload.self, from: data),
               FileManager.default.fileExists(atPath: body.path) {
                await received(record, file: body)
            } else { try? FileManager.default.removeItem(at: metadata) }
        }
        for file in files where file.pathExtension != "json" {
            if !FileManager.default.fileExists(atPath: file.appendingPathExtension("json").path),
               let modified = try? file.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate,
               Date().timeIntervalSince(modified) > 3600 { try? FileManager.default.removeItem(at: file) }
        }
    }

    func download(model: AIModelDescriptor, source: AIModelDownloadSource, allowCellular: Bool,
                  progress report: @escaping @Sendable (AIModelDownloadProgress) -> Void) async throws {
        let intent = UUID()
        intents[model.id] = intent
        blocked.remove(model.id)
        await restore()
        try Task.checkCancellation()
        guard intents[model.id] == intent, !blocked.contains(model.id) else { throw CancellationError() }
        let requested = AIModelDownloadState(modelID: model.id, revision: model.revision, source: source, allowCellular: allowCellular, totalBytes: model.byteSize)
        let state = try await journal.begin(requested)
        guard intents[model.id] == intent, !blocked.contains(model.id) else {
            try await journal.update(model.id, generation: state.generation) { $0.status = "paused" }
            throw CancellationError()
        }
        activeGenerations[model.id] = state.generation
        for task in tasks.values where token(task)?.modelID == model.id && token(task)?.generation != state.generation { task.cancel() }
        let directory = directory(model)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: bridge.incoming, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var base = root
        try base.setResourceValues(values)
        for file in model.files {
            let partial = directory.appendingPathComponent(file.name + ".partial")
            let destination = directory.appendingPathComponent(file.name)
            try await journal.update(model.id, generation: state.generation) { current in
                guard current.status == "downloading" else { return }
                if try AIModelManager.verify(partial, file: file) {
                    try? FileManager.default.removeItem(at: destination)
                    try FileManager.default.moveItem(at: partial, to: destination)
                }
            }
        }
        var verifiedBytes: [String: Int64] = [:]
        for file in model.files {
            verifiedBytes[file.name] = try AIModelManager.verify(directory.appendingPathComponent(file.name), file: file) ? file.size : 0
        }
        try Task.checkCancellation()
        let snapshot = verifiedBytes
        try await journal.update(model.id, generation: state.generation) { current in
            if current.status == "downloading" { current.fileBytes = snapshot }
        }
        for file in model.files where verifiedBytes[file.name] != file.size {
            if !tasks.values.contains(where: { token($0)?.modelID == model.id && token($0)?.file == file.name && $0.state != .completed }) {
                try await startFile(model: model, file: file, state: state, urlIndex: 0)
            }
        }
        try await finishModel(model, generation: state.generation)
        let wakeOwner = "model." + UUID().uuidString
        await ScreenWakeOwner.shared.set(wakeOwner, enabled: true)
        defer { Task { await ScreenWakeOwner.shared.set(wakeOwner, enabled: false) } }
        while let current = try await journal.get(model.id), current.generation == state.generation {
            report(AIModelDownloadProgress(completedBytes: current.completedBytes, totalBytes: model.byteSize))
            if current.status == "complete" { return }
            if current.status == "paused" { throw CancellationError() }
            if current.status == "failed" { throw AIFrameError.invalidDownload }
            try await Task.sleep(nanoseconds: 200_000_000)
        }
        throw CancellationError()
    }

    func pause(_ id: String) async {
        blocked.insert(id)
        guard let state = try? await journal.get(id) else { return }
        try? await journal.update(id, generation: state.generation) { $0.status = "paused" }
        let pending = tasks.values.filter { token($0)?.modelID == id && token($0)?.generation == state.generation }
            .map { ($0, token($0)) }
        await withTaskGroup(of: Void.self) { group in
            for (task, captured) in pending {
                group.addTask {
                    let data: Data? = await withCheckedContinuation { continuation in
                        task.cancel { continuation.resume(returning: $0) }
                    }
                    if let data, let captured { await self.saveResume(data, token: captured) }
                }
            }
            await group.waitForAll()
        }
    }

    func remove(_ id: String) async {
        blocked.insert(id)
        intents[id] = UUID()
        activeGenerations[id] = nil
        let previous = tasks.values.filter { token($0)?.modelID == id }
        try? await journal.remove(id)
        for task in previous { task.cancel() }
    }

    func erase() async {
        for state in await states() { await remove(state.modelID) }
    }

    private func token(_ task: URLSessionTask) -> AITransferToken? {
        guard let value = task.taskDescription else { return nil }
        return try? JSONDecoder().decode(AITransferToken.self, from: Data(value.utf8))
    }

    private func directory(_ model: AIModelDescriptor) -> URL { root.appendingPathComponent(model.id).appendingPathComponent(model.revision) }
    private func urls(_ file: AIModelDescriptor.File, source: AIModelDownloadSource) -> [String] {
        switch source {
        case .domestic: return file.urls.domestic
        case .international: return file.urls.international
        case .automatic: return file.urls.domestic + file.urls.international
        }
    }

    private func startFile(model: AIModelDescriptor, file: AIModelDescriptor.File, state: AIModelDownloadState, urlIndex: Int) async throws {
        guard let current = try await journal.get(model.id), current.generation == state.generation,
              current.status == "downloading", !blocked.contains(model.id), activeGenerations[model.id] == state.generation else { throw CancellationError() }
        if let beforeStart { await beforeStart() }
        guard !blocked.contains(model.id), activeGenerations[model.id] == state.generation else { throw CancellationError() }
        if tasks.values.contains(where: {
            token($0)?.modelID == model.id && token($0)?.generation == state.generation && token($0)?.file == file.name
                && $0.state != .completed && $0.state != .canceling
        }) { return }
        let sources = urls(file, source: state.source)
        guard sources.indices.contains(urlIndex), let url = URL(string: sources[urlIndex]) else { throw AIFrameError.invalidDownload }
        let directory = directory(model)
        let resume = directory.appendingPathComponent(file.name + ".resume")
        let partial = directory.appendingPathComponent(file.name + ".partial")
        let etag = (try? String(contentsOf: directory.appendingPathComponent(file.name + ".identity"), encoding: .utf8)) ?? ""
        var offset = Int64((try? partial.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        if offset >= file.size || etag.isEmpty { offset = 0 }
        var request = URLRequest(url: url)
        request.allowsCellularAccess = state.allowCellular
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
        if offset > 0 { request.setValue("bytes=\(offset)-\(file.size - 1)", forHTTPHeaderField: "Range"); request.setValue(etag, forHTTPHeaderField: "If-Range") }
        let task: URLSessionDownloadTask
        var resumed = false
        let policyFile = directory.appendingPathComponent(file.name + ".resume-policy")
        let policy = (try? Data(contentsOf: policyFile)).flatMap { try? JSONDecoder().decode(AIModelResumePolicy.self, from: $0) }
        let expectedPolicy = AIModelResumePolicy(url: url.absoluteString, allowCellular: state.allowCellular)
        if urlIndex == 0, policy == expectedPolicy, let data = try? Data(contentsOf: resume), (try? PropertyListSerialization.propertyList(from: data, format: nil)) != nil {
            task = session.downloadTask(withResumeData: data)
            offset = 0
            resumed = true
        } else {
            try? FileManager.default.removeItem(at: resume)
            try? FileManager.default.removeItem(at: policyFile)
            task = session.downloadTask(with: request)
        }
        let token = AITransferToken(modelID: model.id, revision: model.revision, file: file.name, generation: state.generation, offset: offset, urlIndex: urlIndex, resumed: resumed)
        task.taskDescription = String(decoding: try JSONEncoder().encode(token), as: UTF8.self)
        task.countOfBytesClientExpectsToReceive = file.size - offset
        tasks[task.taskIdentifier] = task
        task.resume()
    }

    fileprivate func progress(_ task: URLSessionDownloadTask, bytes: Int64) async {
        guard let token = token(task), let state = try? await journal.get(token.modelID), state.generation == token.generation,
              state.status == "downloading", let model = catalog.models.first(where: { $0.id == token.modelID }),
              let file = model.files.first(where: { $0.name == token.file }) else { return }
        if token.offset + bytes > file.size { task.cancel(); await fail(token, error: AIFrameError.invalidDownload); return }
        guard Date().timeIntervalSince(lastProgress[token.modelID] ?? .distantPast) > 0.5 else { return }
        lastProgress[token.modelID] = Date()
        let received = min(file.size, token.offset + bytes)
        try? await journal.update(token.modelID, generation: token.generation) { current in
            if current.status == "downloading" { current.fileBytes[token.file] = received }
        }
    }

    fileprivate func completedTask(_ id: Int) { tasks[id] = nil }

    func received(_ record: AIInboundDownload, file location: URL) async {
        defer {
            try? FileManager.default.removeItem(at: location)
            try? FileManager.default.removeItem(at: location.appendingPathExtension("json"))
        }
        let token = record.token
        guard (try? await journal.accepts(modelID: token.modelID, generation: token.generation)) == true,
              let model = catalog.models.first(where: { $0.id == token.modelID && $0.revision == token.revision }),
              let file = model.files.first(where: { $0.name == token.file }),
              let url = URL(string: record.url),
              let response = HTTPURLResponse(url: url, statusCode: record.status, httpVersion: nil, headerFields: record.headers) else { return }
        do {
            let directory = directory(model)
            try await journal.update(model.id, generation: token.generation) { state in
                let partial = directory.appendingPathComponent(file.name + ".partial")
                let destination = directory.appendingPathComponent(file.name)
                if try AIModelManager.verify(destination, file: file) {
                    state.fileBytes[file.name] = file.size
                    return
                }
                let bytes = Int64(try location.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
                let completePartial = try AIModelManager.verify(partial, file: file)
                if !completePartial && (response.statusCode == 200 || (token.resumed == true && response.statusCode == 206)) {
                    guard bytes == file.size else { throw AIFrameError.invalidDownload }
                    try? FileManager.default.removeItem(at: partial)
                    try FileManager.default.moveItem(at: location, to: partial)
                } else if !completePartial {
                    guard response.statusCode == 206, bytes == file.size - token.offset,
                          response.value(forHTTPHeaderField: "Content-Range") == "bytes \(token.offset)-\(file.size - 1)/\(file.size)" else { throw AIFrameError.invalidDownload }
                    let expected = try String(contentsOf: directory.appendingPathComponent(file.name + ".identity"), encoding: .utf8)
                    guard token.offset > 0, !expected.isEmpty, response.value(forHTTPHeaderField: "ETag") == expected else { throw AIFrameError.invalidDownload }
                    let partialBytes = Int64(try partial.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
                    guard partialBytes >= token.offset else { throw AIFrameError.invalidDownload }
                    let output = try FileHandle(forWritingTo: partial)
                    defer { try? output.close() }
                    try output.truncate(atOffset: UInt64(token.offset))
                    try output.seekToEnd()
                    let input = try FileHandle(forReadingFrom: location)
                    defer { try? input.close() }
                    while let chunk = try input.read(upToCount: 1_048_576), !chunk.isEmpty {
                        try output.write(contentsOf: chunk)
                    }
                    try output.synchronize()
                }
                guard try AIModelManager.verify(partial, file: file) else { throw AIFrameError.invalidDownload }
                try? FileManager.default.removeItem(at: destination)
                try FileManager.default.moveItem(at: partial, to: destination)
                try? FileManager.default.removeItem(at: directory.appendingPathComponent(file.name + ".resume"))
                state.fileBytes[file.name] = file.size
            }
            try await finishModel(model, generation: token.generation)
        } catch {
            let directory = directory(model)
            try? await journal.update(model.id, generation: token.generation) { _ in
                try? FileManager.default.removeItem(at: directory.appendingPathComponent(file.name + ".partial"))
                try? FileManager.default.removeItem(at: directory.appendingPathComponent(file.name + ".identity"))
            }
            await fail(token, error: error)
        }
    }

    private func finishModel(_ model: AIModelDescriptor, generation: String) async throws {
        let directory = directory(model)
        try await journal.update(model.id, generation: generation) { state in
            guard state.status == "downloading", model.files.allSatisfy({ state.fileBytes[$0.name] == $0.size }) else { return }
            for file in model.files {
                guard try AIModelManager.verify(directory.appendingPathComponent(file.name), file: file) else { return }
            }
            let marker = model.files.map { "\($0.name):\($0.sha256)" }.joined(separator: "\n")
            try marker.write(to: directory.appendingPathComponent("verified"), atomically: true, encoding: .utf8)
            state.status = "complete"
        }
    }

    private func saveResume(_ data: Data, token: AITransferToken) async {
        guard let model = catalog.models.first(where: { $0.id == token.modelID }) else { return }
        let resume = directory(model).appendingPathComponent(token.file + ".resume")
        let policyFile = directory(model).appendingPathComponent(token.file + ".resume-policy")
        guard let file = model.files.first(where: { $0.name == token.file }) else { return }
        let sources = urls(file, source: (try? await journal.get(token.modelID))?.source ?? .automatic)
        guard sources.indices.contains(token.urlIndex) else { return }
        let sourceURL = sources[token.urlIndex]
        try? await journal.update(token.modelID, generation: token.generation) { state in
            try data.write(to: resume, options: .atomic)
            try JSONEncoder().encode(AIModelResumePolicy(url: sourceURL, allowCellular: state.allowCellular)).write(to: policyFile, options: .atomic)
        }
    }

    fileprivate func failed(_ task: URLSessionTask, error: Error) async {
        defer { tasks[task.taskIdentifier] = nil }
        guard let token = token(task), (try? await journal.accepts(modelID: token.modelID, generation: token.generation)) == true else { return }
        if let data = (error as NSError).userInfo[NSURLSessionDownloadTaskResumeData] as? Data { await saveResume(data, token: token) }
        if (try? await journal.get(token.modelID))?.status == "paused" { return }
        if token.resumed == true, (error as NSError).userInfo[NSURLSessionDownloadTaskResumeData] == nil,
           let state = try? await journal.get(token.modelID), state.generation == token.generation, state.status == "downloading",
           let model = catalog.models.first(where: { $0.id == token.modelID }),
           let file = model.files.first(where: { $0.name == token.file }),
           !blocked.contains(token.modelID), activeGenerations[token.modelID] == token.generation {
            try? FileManager.default.removeItem(at: directory(model).appendingPathComponent(file.name + ".resume"))
            do { try await startFile(model: model, file: file, state: state, urlIndex: token.urlIndex); return } catch { }
        }
        await fail(token, error: error)
    }

    private func fail(_ token: AITransferToken, error: Error) async {
        guard let state = try? await journal.get(token.modelID), state.generation == token.generation, state.status == "downloading" else { return }
        if let model = catalog.models.first(where: { $0.id == token.modelID }),
           let file = model.files.first(where: { $0.name == token.file }),
           urls(file, source: state.source).indices.contains(token.urlIndex + 1) {
            do { try await startFile(model: model, file: file, state: state, urlIndex: token.urlIndex + 1); return } catch { }
        }
        try? await journal.update(token.modelID, generation: token.generation) { current in
            if current.status == "downloading" {
                current.status = "failed"
                current.lastError = AIFrameError.invalidDownload.localizedDescription
            }
        }
    }
}
