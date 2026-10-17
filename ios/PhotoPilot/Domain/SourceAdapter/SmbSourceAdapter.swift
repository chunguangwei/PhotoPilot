import AMSMB2
import Foundation
import Network
import OSLog

private let smbLog = Logger(subsystem: "com.photopilot", category: "smb")

struct SmbConfig: Sendable {
    let host: String
    let port: Int
    let share: String
    let username: String
    let password: String
}

final class SmbSourceAdapter: SourceAdapter, @unchecked Sendable {
    static let capabilityFlags = 83
    static let pageSize = 100

    private let config: SmbConfig
    private let directories: [String]
    private let recursive: Bool
    private let pageSize: Int
    private let tempDirectory: URL
    private(set) var sessionEncrypted = false

    init(
        config: SmbConfig,
        directories: [String],
        recursive: Bool = true,
        pageSize: Int = SmbSourceAdapter.pageSize,
        tempDirectory: URL? = nil
    ) {
        self.config = config
        self.directories = directories
        self.recursive = recursive
        self.pageSize = pageSize
        self.tempDirectory = tempDirectory
            ?? FileManager.default.temporaryDirectory.appendingPathComponent(
                "photopilot-smb-payload",
                isDirectory: true
            )
    }

    enum OperationPhase {
        case connect
        case read
    }

    static func mapError(_ error: Error, phase: OperationPhase) -> NasTransportError {
        guard let posix = error as? POSIXError else {
            return .unavailable
        }
        switch posix.code {
        case .EACCES:
            return phase == .connect ? .shareNotFound : .permissionDenied
        case .EPERM:
            return phase == .connect ? .authFailed : .permissionDenied
        case .ENOENT:
            return phase == .connect ? .shareNotFound : .pathNotFound
        case .ECONNREFUSED:
            return phase == .connect ? .authFailed : .unavailable
        case .EHOSTUNREACH, .EHOSTDOWN, .ENETUNREACH:
            return .unreachable
        case .ETIMEDOUT:
            return .timeout
        case .EOPNOTSUPP:
            return .protocolUnsupported
        default:
            return .unavailable
        }
    }

    static func mapConnectError(_ error: Error, host: String, port: Int) async -> NasTransportError {
        guard let posix = error as? POSIXError, posix.code == .ECONNREFUSED else {
            return mapError(error, phase: .connect)
        }
        return await probeTcp(host: host, port: port) ?? .authFailed
    }

    private static func probeTcp(host: String, port: Int) async -> NasTransportError? {
        guard let endpointPort = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else {
            return nil
        }
        return await withCheckedContinuation { continuation in
            final class ResumeBox: @unchecked Sendable {
                let lock = NSLock()
                var resumed = false
            }
            let box = ResumeBox()
            let resume: @Sendable (NasTransportError?) -> Void = { result in
                box.lock.lock()
                guard !box.resumed else {
                    box.lock.unlock()
                    return
                }
                box.resumed = true
                box.lock.unlock()
                continuation.resume(returning: result)
            }
            let connection = NWConnection(
                host: NWEndpoint.Host(host),
                port: endpointPort,
                using: .tcp
            )
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    resume(nil)
                    connection.cancel()
                case .failed(let error):
                    switch error {
                    case .posix(let code):
                        resume(code == .ETIMEDOUT ? .timeout : .unreachable)
                    case .dns:
                        resume(.hostUnknown)
                    default:
                        resume(.unreachable)
                    }
                    connection.cancel()
                default:
                    break
                }
            }
            connection.start(queue: .global(qos: .utility))
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 5) {
                resume(.timeout)
                connection.cancel()
            }
        }
    }

    private func makeManager() -> SMB2Manager? {
        let credential = config.username.isEmpty
            ? nil
            : URLCredential(
                user: config.username,
                password: config.password,
                persistence: .forSession
            )
        guard let url = URL(
            string: "smb://\(NasAddress.urlHost(config.host)):\(config.port)"
        ) else {
            return nil
        }
        return SMB2Manager(url: url, credential: credential)
    }

    private func connectedClient(share: String) async throws -> SMB2Manager {
        guard let preferred = makeManager() else {
            throw NasTransportError.unavailable
        }
        do {
            try await preferred.connectShare(name: share, encrypted: true)
            sessionEncrypted = true
            smbLog.notice("connectShare encrypted ok share=\(share, privacy: .public)")
            return preferred
        } catch {
            sessionEncrypted = false
            smbLog.notice("connectShare encrypted failed share=\(share, privacy: .public) error=\(String(describing: error), privacy: .public)")
        }
        guard let fallback = makeManager() else {
            throw NasTransportError.unavailable
        }
        do {
            try await fallback.connectShare(name: share, encrypted: false)
            smbLog.notice("connectShare plain ok share=\(share, privacy: .public)")
            return fallback
        } catch {
            smbLog.error("connectShare plain failed share=\(share, privacy: .public) error=\(String(describing: error), privacy: .public)")
            throw await Self.mapConnectError(error, host: config.host, port: config.port)
        }
    }

    private func connectedClient() async throws -> SMB2Manager {
        try await connectedClient(share: config.share)
    }

    func listShares() async throws -> [String] {
        guard let manager = makeManager() else {
            throw NasTransportError.unavailable
        }
        do {
            let shares = try await manager.listShares(enumerateHidden: false)
            smbLog.notice("listShares ok count=\(shares.count)")
            return shares.map(\.name).sorted()
        } catch {
            smbLog.error("listShares failed error=\(String(describing: error), privacy: .public)")
            throw await Self.mapConnectError(error, host: config.host, port: config.port)
        }
    }

    private func resolveDirectory(_ directory: String) -> (share: String, subpath: String) {
        if config.share.isEmpty {
            return NasPath.splitShare(directory)
        }
        return (config.share, directory)
    }

    func enumerateAssets(cursor: String?) async throws -> SourceAssetPage {
        smbLog.notice("enumerate start share=\(self.config.share, privacy: .public) dirs=\(self.directories.joined(separator: "|"), privacy: .public) cursor=\(cursor == nil ? "nil" : "yes", privacy: .public)")
        var state: WebDavSourceAdapter.EnumState
        if let cursor {
            state = WebDavSourceAdapter.decodeCursor(cursor)
                ?? WebDavSourceAdapter.EnumState(dirs: [], skip: 0)
        } else {
            state = WebDavSourceAdapter.EnumState(dirs: directories, skip: 0)
        }
        var client: SMB2Manager?
        var clientShare: String?
        if !config.share.isEmpty {
            client = try await connectedClient(share: config.share)
            clientShare = config.share
        }
        var assets: [SourceAssetDescriptor] = []
        defer {
            let current = client
            Task { try? await current?.disconnectShare() }
        }
        while !state.dirs.isEmpty, assets.count < pageSize {
            let directory = state.dirs[0]
            let resolved = resolveDirectory(directory)
            if resolved.share.isEmpty {
                let shares = try await listShares()
                state = WebDavSourceAdapter.EnumState(
                    dirs: Array(state.dirs.dropFirst()) + shares,
                    skip: 0
                )
                continue
            }
            if clientShare != resolved.share {
                if let client {
                    try? await client.disconnectShare()
                }
                client = try await connectedClient(share: resolved.share)
                clientShare = resolved.share
            }
            guard let active = client else {
                throw NasTransportError.unavailable
            }
            let entries: [[URLResourceKey: Any]]
            do {
                entries = try await active.contentsOfDirectory(
                    atPath: resolved.subpath.isEmpty ? "/" : resolved.subpath,
                    recursive: false
                )
                smbLog.notice("listdir share=\(resolved.share, privacy: .public) path=\(resolved.subpath, privacy: .public) entries=\(entries.count)")
            } catch {
                smbLog.error("listdir failed share=\(resolved.share, privacy: .public) path=\(resolved.subpath, privacy: .public) error=\(String(describing: error), privacy: .public)")
                throw Self.mapError(error, phase: .read)
            }
            var subdirs: [String] = []
            var files: [(path: String, size: Int, modifiedMillis: Int)] = []
            for entry in entries {
                guard let name = entry[.nameKey] as? String,
                      name != ".",
                      name != ".." else { continue }
                let isDirectory = (entry[.isDirectoryKey] as? Bool) ?? false
                let path = directory.isEmpty ? name : "\(directory)/\(name)"
                if isDirectory {
                    if recursive {
                        subdirs.append(path)
                    }
                } else if LocalFilesFixtureAdapter.mediaExtensions.contains(
                    (name as NSString).pathExtension.lowercased()
                ) {
                    let size = (entry[.fileSizeKey] as? Int) ?? 0
                    let modifiedMillis = (entry[.contentModificationDateKey] as? Date)
                        .map { Int($0.timeIntervalSince1970 * 1000) } ?? 0
                    files.append((path, size, modifiedMillis))
                }
            }
            subdirs.sort()
            files.sort { $0.path < $1.path }
            let emit = Array(files.dropFirst(state.skip).prefix(pageSize - assets.count))
            assets.append(contentsOf: emit.map { file in
                SourceAssetDescriptor(
                    remoteID: file.path,
                    revision: "\(file.modifiedMillis):\(file.size)",
                    byteSize: file.size,
                    remoteTakenAtMillis: nil
                )
            })
            let consumed = state.skip + emit.count
            if consumed < files.count {
                state.skip = consumed
            } else {
                state = WebDavSourceAdapter.EnumState(
                    dirs: Array(state.dirs.dropFirst()) + subdirs,
                    skip: 0
                )
            }
        }
        smbLog.notice("enumerate page emitted=\(assets.count) remainingDirs=\(state.dirs.count)")
        return SourceAssetPage(
            assets: assets,
            nextCursor: state.dirs.isEmpty ? nil : WebDavSourceAdapter.encodeCursor(state),
            totalKnown: nil
        )
    }

    func listSubdirectories(path: String) async throws -> [String] {
        let client = try await connectedClient()
        let entries: [[URLResourceKey: Any]]
        do {
            entries = try await client.contentsOfDirectory(
                atPath: path.isEmpty ? "/" : path,
                recursive: false
            )
        } catch {
            throw Self.mapError(error, phase: .read)
        }
        return entries.compactMap { entry -> String? in
            guard let name = entry[.nameKey] as? String,
                  name != ".",
                  name != "..",
                  (entry[.isDirectoryKey] as? Bool) == true else { return nil }
            return path.isEmpty ? name : "\(path)/\(name)"
        }
        .sorted()
    }

    func listFolders(path: String) async throws -> [String] {
        if !config.share.isEmpty {
            return try await listSubdirectories(path: path)
        }
        if path.isEmpty {
            return try await listShares()
        }
        let resolved = NasPath.splitShare(path)
        let client = try await connectedClient(share: resolved.share)
        defer { Task { try? await client.disconnectShare() } }
        let entries: [[URLResourceKey: Any]]
        do {
            entries = try await client.contentsOfDirectory(
                atPath: resolved.subpath.isEmpty ? "/" : resolved.subpath,
                recursive: false
            )
        } catch {
            throw Self.mapError(error, phase: .read)
        }
        return entries.compactMap { entry -> String? in
            guard let name = entry[.nameKey] as? String,
                  name != ".",
                  name != "..",
                  (entry[.isDirectoryKey] as? Bool) == true else { return nil }
            return "\(path)/\(name)"
        }
        .sorted()
    }

    func assetPayload(remoteID: String) async throws -> LocalAssetPayload {
        let resolved = resolveDirectory(remoteID)
        let client = try await connectedClient(share: resolved.share)
        let fileURL = tempDirectory
            .appendingPathComponent(SHA256Hex.digest(data: Data(remoteID.utf8)))
            .appendingPathExtension("payload")
        do {
            try FileManager.default.createDirectory(
                at: tempDirectory,
                withIntermediateDirectories: true
            )
            try await client.downloadItem(atPath: resolved.subpath, to: fileURL) { _, _ in true }
        } catch {
            smbLog.error("download failed remoteID=\(remoteID, privacy: .public) error=\(String(describing: error), privacy: .public)")
            throw Self.mapError(error, phase: .read)
        }
        let size = (try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        let image = try AssetFileProber.probeImage(fileURL)
        return LocalAssetPayload(
            localFileURL: fileURL,
            byteSize: size,
            contentHash: try AssetFileProber.contentHash(of: fileURL),
            head64kHash: try AssetFileProber.head64kHash(of: fileURL),
            format: image.format,
            pixelWidth: image.pixelWidth,
            pixelHeight: image.pixelHeight,
            exifOrientation: image.exifOrientation,
            capturedAtMillis: image.capturedAtMillis,
            mediaKind: image.mediaKind,
            durationMillis: image.durationMillis,
            capturedAtSource: image.capturedAtSource
        )
    }
}
