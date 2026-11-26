import AVFoundation
import UIKit

final class ThumbnailLoader: @unchecked Sendable {

    private let originalsRoot: URL
    private let media: RemoteMediaService?

    init(originalsRoot: URL, media: RemoteMediaService? = nil) {
        self.media = media
        self.originalsRoot = originalsRoot
    }

    static func clearMemoryCache() {
        RemoteDecodedImages.shared.clear()
    }

    static func videoThumbnail(url: URL, maxPixels: Int) -> CGImage? {
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: maxPixels, height: maxPixels)
        return try? generator.copyCGImage(at: .zero, actualTime: nil)
    }

    static func isVideoPath(_ relPath: String) -> Bool {
        AssetFileProber.videoExtensions.contains(
            (relPath as NSString).pathExtension.lowercased()
        )
    }

    func peekThumbnail(relPath: String, pointSize: CGFloat, scale: CGFloat) -> UIImage? {
        let pixels = max(1, Int((pointSize * scale).rounded(.up)))
        let key = "thumbnail:\(relPath)#\(pixels)"
        return RemoteDecodedImages.shared.image(key: key)
    }

    func coverThumbnail(relPath: String, pixels: Int, mode: FrameStorageMode = .remote) async -> UIImage? {
        if relPath.hasPrefix("remote:"), let media {
            return try? await media.coverImage(runtimeID: relPath, mode: mode, pixels: pixels)
        }
        let original = originalsRoot.appendingPathComponent(relPath)
        let root = originalsRoot.deletingLastPathComponent().appendingPathComponent("local-frame-covers", isDirectory: true)
        return await LocalFrameCoverThumbnails.shared.image(original: original, root: root, pixels: pixels)
    }

    func thumbnail(relPath: String, pointSize: CGFloat, scale: CGFloat, mode: FrameStorageMode = .remote) async -> UIImage? {
        try? await image(relPath: relPath, pointSize: pointSize, scale: scale, mode: mode)
    }

    func image(relPath: String, pointSize: CGFloat, scale: CGFloat, mode: FrameStorageMode = .remote) async throws -> UIImage {
        try Task.checkCancellation()
        let pixels = max(1, Int((pointSize * scale).rounded(.up)))
        if relPath.hasPrefix("remote:"), let media {
            var retries = 0
            while true {
                do { return try await media.image(runtimeID: relPath, mode: mode, width: pixels, height: pixels) }
                catch RemoteMediaError.busy {
                    guard retries < 3 else { throw RemoteMediaError.busy }
                    retries += 1
                    try await Task.sleep(nanoseconds: UInt64(retries) * 200_000_000)
                }
            }
        }
        let key = "thumbnail:\(relPath)#\(pixels)"
        let generation = RemoteDecodedImages.shared.generation()
        if let cached = RemoteDecodedImages.shared.image(key: key) {
            return cached
        }
        let root = originalsRoot
        let image: UIImage? = await Task.detached {
            let url = root.appendingPathComponent(relPath)
            guard FileManager.default.fileExists(atPath: url.path) else {
                return nil
            }
            if Self.isVideoPath(relPath) {
                guard let cgImage = Self.videoThumbnail(url: url, maxPixels: pixels) else {
                    return nil
                }
                return UIImage(cgImage: cgImage)
            }
            guard let cgImage = ImageRenderer.thumbnail(
                fileURL: url,
                maxPixelWidth: pixels,
                maxPixelHeight: pixels
            ) else {
                return nil
            }
            return UIImage(cgImage: cgImage)
        }.value
        try Task.checkCancellation()
        guard generation == RemoteDecodedImages.shared.generation() else { throw RemoteMediaError.revoked }
        guard let image else { throw RemoteMediaError.invalidFile }
        RemoteDecodedImages.shared.store(image, key: key, generation: generation)
        return image
    }
}

private actor LocalFrameCoverThumbnails {
    static let shared = LocalFrameCoverThumbnails()
    private let budget = 24 * 1024 * 1024

    func image(original: URL, root: URL, pixels: Int) -> UIImage? {
        let fm = FileManager.default
        guard !Task.isCancelled,
              let revision = try? original.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey]),
              let size = revision.fileSize, let date = revision.contentModificationDate else { return nil }
        let key = SHA256Hex.digest(data: Data("\(original.path):\(size):\(date.timeIntervalSinceReferenceDate):\(pixels)".utf8))
        let target = root.appendingPathComponent(key + ".jpg")
        if let stored = UIImage(contentsOfFile: target.path) {
            try? fm.setAttributes([.modificationDate: Date()], ofItemAtPath: target.path)
            return stored
        }
        guard let decoded = ImageRenderer.thumbnail(fileURL: original, maxPixelWidth: pixels, maxPixelHeight: pixels) else { return nil }
        let image = UIImage(cgImage: decoded)
        guard !Task.isCancelled, let data = image.jpegData(compressionQuality: 0.8),
              data.count <= 2_621_440, fm.fileExists(atPath: original.path) else { return image }
        do {
            try fm.createDirectory(at: root, withIntermediateDirectories: true)
            var directory = root
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try directory.setResourceValues(values)
            let files = try fm.contentsOfDirectory(at: root, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])
                .filter { $0.pathExtension == "jpg" }
                .compactMap { url -> (url: URL, size: Int, date: Date)? in
                    guard let attributes = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey]),
                          let size = attributes.fileSize else { return nil }
                    return (url, size, attributes.contentModificationDate ?? .distantPast)
                }
                .sorted { $0.date < $1.date }
            var used = files.reduce(0) { $0 + $1.size }
            for file in files where used + data.count > budget {
                try fm.removeItem(at: file.url)
                used -= file.size
            }
            if used + data.count <= budget { try data.write(to: target, options: .atomic) }
        } catch {
            return image
        }
        return image
    }
}
