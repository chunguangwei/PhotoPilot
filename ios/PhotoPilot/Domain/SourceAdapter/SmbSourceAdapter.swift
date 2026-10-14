import AMSMB2
import Foundation

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
        case .EACCES, .EPERM:
            return phase == .connect ? .authFailed : .permissionDenied
        case .ENOENT:
            return .pathNotFound
        case .EOPNOTSUPP:
            return .protocolUnsupported
        default:
            return .unavailable
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

    private func connectedClient() async throws -> SMB2Manager {
        guard let preferred = makeManager() else {
            throw NasTransportError.unavailable
        }
        do {
            try await preferred.connectShare(name: config.share, encrypted: true)
            sessionEncrypted = true
            return preferred
        } catch {
            sessionEncrypted = false
        }
        guard let fallback = makeManager() else {
            throw NasTransportError.unavailable
        }
        do {
            try await fallback.connectShare(name: config.share, encrypted: false)
            return fallback
        } catch {
            throw Self.mapError(error, phase: .connect)
        }
    }

    func enumerateAssets(cursor: String?) async throws -> SourceAssetPage {
        var state: WebDavSourceAdapter.EnumState
        if let cursor {
            state = WebDavSourceAdapter.decodeCursor(cursor)
                ?? WebDavSourceAdapter.EnumState(dirs: [], skip: 0)
        } else {
            state = WebDavSourceAdapter.EnumState(dirs: directories, skip: 0)
        }
        var client: SMB2Manager?
        do {
            client = try await connectedClient()
        } catch let error as NasTransportError {
            throw error
        }
        guard let client else {
            throw NasTransportError.unavailable
        }
        var assets: [SourceAssetDescriptor] = []
        defer {
            Task { try? await client.disconnectShare() }
        }
        while !state.dirs.isEmpty, assets.count < pageSize {
            let directory = state.dirs[0]
            let entries: [[URLResourceKey: Any]]
            do {
                entries = try await client.contentsOfDirectory(
                    atPath: directory.isEmpty ? "/" : directory,
                    recursive: false
                )
            } catch {
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

    func assetPayload(remoteID: String) async throws -> LocalAssetPayload {
        let client = try await connectedClient()
        let fileURL = tempDirectory
            .appendingPathComponent(SHA256Hex.digest(data: Data(remoteID.utf8)))
            .appendingPathExtension("payload")
        do {
            try FileManager.default.createDirectory(
                at: tempDirectory,
                withIntermediateDirectories: true
            )
            try await client.downloadItem(atPath: remoteID, to: fileURL) { _, _ in true }
        } catch {
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
            capturedAtMillis: image.capturedAtMillis
        )
    }
}
