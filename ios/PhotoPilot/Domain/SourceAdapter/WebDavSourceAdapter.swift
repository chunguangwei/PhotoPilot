import Foundation

final class WebDavSourceAdapter: SourceAdapter {
    static let capabilityFlags = 83
    static let pageSize = 100

    private let client: WebDavClient
    private let directories: [String]
    private let recursive: Bool
    private let tempDirectory: URL
    private let pageSize: Int

    init(
        client: WebDavClient,
        scopeJSON: String,
        recursive: Bool = true,
        pageSize: Int = WebDavSourceAdapter.pageSize,
        tempDirectory: URL? = nil
    ) {
        self.client = client
        self.directories = Self.parseScope(scopeJSON)
        self.recursive = recursive
        self.pageSize = pageSize
        self.tempDirectory = tempDirectory
            ?? FileManager.default.temporaryDirectory.appendingPathComponent("photopilot-webdav-payload", isDirectory: true)
    }

    static func parseScope(_ scopeJSON: String) -> [String] {
        guard let data = scopeJSON.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let directories = object["directories"] as? [String] else {
            return []
        }
        return directories.compactMap { NasPath.normalize($0) }
    }

    static let cursorPrefix = "queue:"

    struct EnumState: Equatable {
        var dirs: [String]
        var skip: Int
    }

    static func encodeCursor(_ state: EnumState) -> String {
        let object: [String: Any] = ["dirs": state.dirs, "skip": state.skip]
        guard let data = try? JSONSerialization.data(withJSONObject: object) else {
            return cursorPrefix + "{\"dirs\":[],\"skip\":0}"
        }
        return cursorPrefix + String(decoding: data, as: UTF8.self)
    }

    static func decodeCursor(_ raw: String) -> EnumState? {
        guard raw.hasPrefix(cursorPrefix) else { return nil }
        let body = String(raw.dropFirst(cursorPrefix.count))
        guard let data = body.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let dirs = object["dirs"] as? [String] else {
            return nil
        }
        let skip = (object["skip"] as? NSNumber)?.intValue ?? 0
        return EnumState(dirs: dirs, skip: skip)
    }

    static func decodeCursorDirs(_ raw: String) -> [String]? {
        decodeCursor(raw)?.dirs
    }

    func enumerateAssets(cursor: String?) async throws -> SourceAssetPage {
        var state: EnumState
        if let cursor {
            state = Self.decodeCursor(cursor) ?? EnumState(dirs: [], skip: 0)
        } else {
            state = EnumState(dirs: directories, skip: 0)
        }
        var assets: [SourceAssetDescriptor] = []
        while !state.dirs.isEmpty, assets.count < pageSize {
            let directory = state.dirs[0]
            let entries = try await client.propfind(path: directory, depth: 1)
            var subdirs: [String] = []
            var files: [WebDavEntry] = []
            for entry in entries where entry.path != directory {
                if entry.isCollection {
                    if recursive {
                        subdirs.append(entry.path)
                    }
                } else if LocalFilesFixtureAdapter.mediaExtensions.contains(
                    (entry.path as NSString).pathExtension.lowercased()
                ) {
                    files.append(entry)
                }
            }
            subdirs.sort()
            files.sort { $0.path < $1.path }
            let emit = Array(files.dropFirst(state.skip).prefix(pageSize - assets.count))
            assets.append(contentsOf: emit.map(Self.descriptor(for:)))
            let consumed = state.skip + emit.count
            if consumed < files.count {
                state.skip = consumed
            } else {
                state = EnumState(dirs: Array(state.dirs.dropFirst()) + subdirs, skip: 0)
            }
        }
        return SourceAssetPage(
            assets: assets,
            nextCursor: state.dirs.isEmpty ? nil : Self.encodeCursor(state),
            totalKnown: nil
        )
    }

    func assetPayload(remoteID: String) async throws -> LocalAssetPayload {
        let fileURL = tempDirectory
            .appendingPathComponent(SHA256Hex.digest(data: Data(remoteID.utf8)))
            .appendingPathExtension("payload")
        let byteSize = try await client.download(path: remoteID, to: fileURL)
        let image = try AssetFileProber.probeImage(fileURL)
        return LocalAssetPayload(
            localFileURL: fileURL,
            byteSize: byteSize,
            contentHash: try AssetFileProber.contentHash(of: fileURL),
            head64kHash: try AssetFileProber.head64kHash(of: fileURL),
            format: image.format,
            pixelWidth: image.pixelWidth,
            pixelHeight: image.pixelHeight,
            exifOrientation: image.exifOrientation,
            capturedAtMillis: image.capturedAtMillis
        )
    }

    private static func descriptor(for entry: WebDavEntry) -> SourceAssetDescriptor {
        let revision: String
        if let etag = entry.etag {
            revision = etag
        } else {
            revision = "\(entry.lastModifiedMillis ?? 0):\(entry.contentLength ?? 0)"
        }
        return SourceAssetDescriptor(
            remoteID: entry.path,
            revision: revision,
            byteSize: entry.contentLength,
            remoteTakenAtMillis: nil
        )
    }
}
