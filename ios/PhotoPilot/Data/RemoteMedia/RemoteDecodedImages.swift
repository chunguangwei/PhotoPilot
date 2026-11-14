import UIKit

final class RemoteDecodedImages: @unchecked Sendable {
    static let shared = RemoteDecodedImages()
    private let images = NSCache<NSString, UIImage>()
    private let lock = NSLock()
    private var revision = 0

    private init() { images.totalCostLimit = 33_554_432 }

    func generation() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return revision
    }

    func image(key: String) -> UIImage? { images.object(forKey: key as NSString) }

    func store(_ image: UIImage, key: String, generation: Int) {
        lock.lock()
        defer { lock.unlock() }
        guard generation == revision else { return }
        let cost = (image.cgImage?.bytesPerRow ?? 0) * (image.cgImage?.height ?? 0)
        images.setObject(image, forKey: key as NSString, cost: cost)
    }

    func clear() {
        lock.lock()
        revision &+= 1
        images.removeAllObjects()
        lock.unlock()
    }
}
