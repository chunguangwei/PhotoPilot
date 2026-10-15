import Foundation

final class LocalFilesFixtureAdapter: SourceAdapter {
    static let mediaExtensions: Set<String> = [
        "jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "bmp", "tif", "tiff",
        "mp4", "mov",
        "dng", "cr2", "nef", "arw", "raf", "orf", "rw2",
    ]

    let rootURL: URL
    private let pageSize: Int

    init(rootURL: URL, pageSize: Int = 50) {
        self.rootURL = rootURL
        self.pageSize = pageSize
    }

    private var allRemoteIDs: [String] {
        guard let enumerator = FileManager.default.enumerator(
            at: rootURL,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ) else { return [] }
        var ids: [String] = []
        for case let url as URL in enumerator {
            guard Self.mediaExtensions.contains(url.pathExtension.lowercased()) else { continue }
            let relative = url.path.replacingOccurrences(of: rootURL.path + "/", with: "")
            ids.append(relative.precomposedStringWithCanonicalMapping)
        }
        return ids.sorted()
    }

    func enumerateAssets(cursor: String?) async throws -> SourceAssetPage {
        try await enumerateAssets(pageSize: pageSize, cursor: cursor)
    }

    func enumerateAssets(pageSize size: Int, cursor: String?) async throws -> SourceAssetPage {
        let all = allRemoteIDs
        guard let offset = try OffsetCursor.decode(cursor), offset < all.count else {
            return SourceAssetPage(assets: [], nextCursor: nil, totalKnown: all.count)
        }
        let end = min(offset + size, all.count)
        let assets = all[offset..<end].map { remoteID in
            SourceAssetDescriptor(
                remoteID: remoteID,
                revision: "1",
                byteSize: fileSize(remoteID),
                remoteTakenAtMillis: modificationMillis(remoteID)
            )
        }
        return SourceAssetPage(
            assets: assets,
            nextCursor: end < all.count ? OffsetCursor.encode(end) : nil,
            totalKnown: all.count
        )
    }

    func assetPayload(remoteID: String) async throws -> LocalAssetPayload {
        let url = fileURL(for: remoteID)
        let values = try url.resourceValues(forKeys: [.fileSizeKey])
        guard let byteSize = values.fileSize else { throw AssetFileProber.ProbeError.unreadable }
        let image = try AssetFileProber.probeImage(url)
        return LocalAssetPayload(
            localFileURL: url,
            byteSize: byteSize,
            contentHash: try AssetFileProber.contentHash(of: url),
            head64kHash: try AssetFileProber.head64kHash(of: url),
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

    private func fileURL(for remoteID: String) -> URL {
        rootURL.appendingPathComponent(remoteID)
    }

    private func fileSize(_ remoteID: String) -> Int? {
        try? fileURL(for: remoteID).resourceValues(forKeys: [.fileSizeKey]).fileSize
    }

    private func modificationMillis(_ remoteID: String) -> Int? {
        guard let seconds = try? fileURL(for: remoteID)
            .resourceValues(forKeys: [.contentModificationDateKey])
            .contentModificationDate?
            .timeIntervalSince1970 else { return nil }
        return Int(seconds * 1000)
    }
}
