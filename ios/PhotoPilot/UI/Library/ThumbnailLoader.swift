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

    func thumbnail(relPath: String, pointSize: CGFloat, scale: CGFloat) async -> UIImage? {
        let pixels = max(1, Int((pointSize * scale).rounded(.up)))
        if relPath.hasPrefix("remote:"), let media {
            return try? await media.image(runtimeID: relPath, width: pixels, height: pixels)
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
        guard generation == RemoteDecodedImages.shared.generation() else { return nil }
        if let image { RemoteDecodedImages.shared.store(image, key: key, generation: generation) }
        return image
    }
}
