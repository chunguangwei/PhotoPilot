import Foundation

final class PickedFilesSourceAdapter: SourceAdapter {
    struct PickedFile: Sendable {
        let remoteID: String
        let fileURL: URL
        let byteSize: Int
    }

    private let files: [PickedFile]
    let stagingDirectory: URL?

    init(files: [PickedFile], stagingDirectory: URL? = nil) {
        self.files = files
        self.stagingDirectory = stagingDirectory
    }

    static func pickedRootDirectory() -> URL {
        let support = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first!
        return support.appendingPathComponent("picked", isDirectory: true)
    }

    static func stage(urls: [URL]) throws -> (supported: [PickedFile], skipped: Int, directory: URL) {
        let fileManager = FileManager.default
        let directory = pickedRootDirectory()
            .appendingPathComponent("staging-\(UUID().uuidString)", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        var supported: [PickedFile] = []
        var skipped = 0
        for url in urls {
            guard LocalFilesFixtureAdapter.mediaExtensions.contains(url.pathExtension.lowercased()) else {
                skipped += 1
                continue
            }
            let target = directory.appendingPathComponent(url.lastPathComponent)
            if fileManager.fileExists(atPath: target.path) {
                try fileManager.removeItem(at: target)
            }
            try fileManager.copyItem(at: url, to: target)
            let size = (try? target.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            supported.append(
                PickedFile(
                    remoteID: "\(url.lastPathComponent):\(size)",
                    fileURL: target,
                    byteSize: size
                )
            )
        }
        return (supported, skipped, directory)
    }

    func relocatedToJobDirectory(jobID: String) -> PickedFilesSourceAdapter {
        guard let stagingDirectory else { return self }
        let fileManager = FileManager.default
        let target = Self.pickedRootDirectory().appendingPathComponent(jobID, isDirectory: true)
        do {
            if fileManager.fileExists(atPath: target.path) {
                try fileManager.removeItem(at: target)
            }
            try fileManager.moveItem(at: stagingDirectory, to: target)
            let moved = files.map {
                PickedFile(
                    remoteID: $0.remoteID,
                    fileURL: target.appendingPathComponent($0.fileURL.lastPathComponent),
                    byteSize: $0.byteSize
                )
            }
            return PickedFilesSourceAdapter(files: moved, stagingDirectory: target)
        } catch {
            return self
        }
    }

    static func restore(jobID: String) -> PickedFilesSourceAdapter? {
        let fileManager = FileManager.default
        let directory = pickedRootDirectory().appendingPathComponent(jobID, isDirectory: true)
        guard let names = try? fileManager.contentsOfDirectory(atPath: directory.path) else {
            return nil
        }
        var files: [PickedFile] = []
        for name in names.sorted() {
            guard LocalFilesFixtureAdapter.mediaExtensions.contains(
                (name as NSString).pathExtension.lowercased()
            ) else { continue }
            let url = directory.appendingPathComponent(name)
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            files.append(PickedFile(remoteID: "\(name):\(size)", fileURL: url, byteSize: size))
        }
        guard !files.isEmpty else { return nil }
        return PickedFilesSourceAdapter(files: files, stagingDirectory: directory)
    }

    func enumerateAssets(cursor: String?) async throws -> SourceAssetPage {
        guard cursor == nil else {
            return SourceAssetPage(assets: [], nextCursor: nil, totalKnown: files.count)
        }
        return SourceAssetPage(
            assets: files.map {
                SourceAssetDescriptor(
                    remoteID: $0.remoteID,
                    revision: "\($0.byteSize)",
                    byteSize: $0.byteSize,
                    remoteTakenAtMillis: nil
                )
            },
            nextCursor: nil,
            totalKnown: files.count
        )
    }

    func assetPayload(remoteID: String) async throws -> LocalAssetPayload {
        guard let file = files.first(where: { $0.remoteID == remoteID }) else {
            throw AssetFileProber.ProbeError.unreadable
        }
        let image = try AssetFileProber.probeImage(file.fileURL)
        return LocalAssetPayload(
            localFileURL: file.fileURL,
            byteSize: file.byteSize,
            contentHash: try AssetFileProber.contentHash(of: file.fileURL),
            head64kHash: try AssetFileProber.head64kHash(of: file.fileURL),
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
