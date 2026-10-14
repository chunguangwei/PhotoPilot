import AVFoundation
import UIKit

final class ThumbnailLoader: @unchecked Sendable {
    private nonisolated(unsafe) static let sharedCache = NSCache<NSString, UIImage>()

    private let originalsRoot: URL

    init(originalsRoot: URL) {
        self.originalsRoot = originalsRoot
    }

    static func clearMemoryCache() {
        sharedCache.removeAllObjects()
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
        let key = "\(relPath)#\(pixels)" as NSString
        if let cached = Self.sharedCache.object(forKey: key) {
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
        if let image {
            Self.sharedCache.setObject(image, forKey: key)
        }
        return image
    }
}
