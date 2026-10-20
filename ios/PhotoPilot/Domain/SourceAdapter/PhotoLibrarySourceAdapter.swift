import Foundation
import Photos

enum PhotoLibraryAccess: String, Sendable {
    case authorized
    case limited
    case denied
    case notDetermined
}

final class PhotoLibrarySourceAdapter: SourceAdapter {
    static let fingerprint = "ios.photolibrary.images:v1"

    private let networkPolicy: (any ImportNetworkPolicy)?
    private let pageSize: Int
    private let tempDirectory: URL

    init(pageSize: Int = 100, tempDirectory: URL? = nil, networkPolicy: (any ImportNetworkPolicy)? = nil) {
        self.networkPolicy = networkPolicy
        self.pageSize = pageSize
        self.tempDirectory = tempDirectory
            ?? FileManager.default.temporaryDirectory.appendingPathComponent("photopilot-photo-payload", isDirectory: true)
    }

    static func currentAccess() -> PhotoLibraryAccess {
        switch PHPhotoLibrary.authorizationStatus(for: .readWrite) {
        case .authorized:
            return .authorized
        case .limited:
            return .limited
        case .notDetermined:
            return .notDetermined
        default:
            return .denied
        }
    }

    func enumerateAssets(cursor: String?) async throws -> SourceAssetPage {
        let result = PHAsset.fetchAssets(with: Self.fetchOptions())
        let total = result.count
        guard let offset = try OffsetCursor.decode(cursor), offset < total else {
            return SourceAssetPage(assets: [], nextCursor: nil, totalKnown: total)
        }
        let end = min(offset + pageSize, total)
        var assets: [SourceAssetDescriptor] = []
        assets.reserveCapacity(end - offset)
        for index in offset..<end {
            let asset = result.object(at: index)
            assets.append(
                SourceAssetDescriptor(
                    remoteID: asset.localIdentifier,
                    revision: Self.revision(of: asset),
                    byteSize: Self.byteSize(of: asset),
                    remoteTakenAtMillis: asset.creationDate.map { Int($0.timeIntervalSince1970 * 1000) }
                )
            )
        }
        return SourceAssetPage(
            assets: assets,
            nextCursor: end < total ? OffsetCursor.encode(end) : nil,
            totalKnown: total
        )
    }

    func assetPayload(remoteID: String) async throws -> LocalAssetPayload {
        let result = PHAsset.fetchAssets(withLocalIdentifiers: [remoteID], options: nil)
        guard let asset = result.firstObject else {
            throw AssetFileProber.ProbeError.unreadable
        }
        if asset.mediaType == .video {
            throw AssetFileProber.ProbeError.unsupportedFormat
        }
        let fileURL = tempDirectory.appendingPathComponent(Self.tempFileName(for: remoteID))
        try FileManager.default.createDirectory(at: tempDirectory, withIntermediateDirectories: true)
        try await exportOriginal(asset: asset, to: fileURL)
        let values = try fileURL.resourceValues(forKeys: [.fileSizeKey])
        guard let byteSize = values.fileSize else { throw AssetFileProber.ProbeError.unreadable }
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
            capturedAtMillis: image.capturedAtMillis,
            mediaKind: image.mediaKind,
            durationMillis: image.durationMillis,
            capturedAtSource: image.capturedAtSource
        )
    }

    func purgePayloads() throws {
        guard FileManager.default.fileExists(atPath: tempDirectory.path) else { return }
        try FileManager.default.removeItem(at: tempDirectory)
    }

    final class NetworkRequestState: @unchecked Sendable {
        let networkAccessAllowed: Bool
        private let policy: (any ImportNetworkPolicy)?
        private let lock = NSLock()
        private var blocked = false

        init(policy: (any ImportNetworkPolicy)?) {
            self.policy = policy
            self.networkAccessAllowed = policy?.allowsTransfer ?? true
        }

        func shouldStopTransfer() -> Bool {
            guard policy?.allowsTransfer == false else { return false }
            lock.lock()
            blocked = true
            lock.unlock()
            return true
        }

        func failure(error: Error?, isInCloud: Bool) -> Error? {
            lock.lock()
            let wasBlocked = blocked
            lock.unlock()
            if wasBlocked { return NasNetworkBlockedError() }
            let failure = error as NSError?
            let networkRequired = failure?.domain == PHPhotosErrorDomain
                && failure?.code == PHPhotosError.Code.networkAccessRequired.rawValue
            if (isInCloud || networkRequired), !networkAccessAllowed || policy?.allowsTransfer == false {
                return NasNetworkBlockedError()
            }
            return error
        }
    }

    private func exportOriginal(asset: PHAsset, to fileURL: URL) async throws {
        let state = NetworkRequestState(policy: networkPolicy)
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let options = PHImageRequestOptions()
            options.isNetworkAccessAllowed = state.networkAccessAllowed
            options.deliveryMode = .highQualityFormat
            options.isSynchronous = false
            options.progressHandler = { _, _, stop, _ in
                if state.shouldStopTransfer() { stop.pointee = true }
            }
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, _, _, info in
                let error = state.failure(
                    error: info?[PHImageErrorKey] as? Error,
                    isInCloud: info?[PHImageResultIsInCloudKey] as? Bool == true
                )
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                if info?[PHImageCancelledKey] as? Bool == true {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                guard let data else {
                    continuation.resume(throwing: AssetFileProber.ProbeError.unreadable)
                    return
                }
                do {
                    try data.write(to: fileURL, options: .atomic)
                    continuation.resume()
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    private static func fetchOptions() -> PHFetchOptions {
        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: true)]
        options.includeHiddenAssets = false
        options.predicate = NSPredicate(
            format: "mediaType == %d",
            PHAssetMediaType.image.rawValue
        )
        return options
    }

    private static func revision(of asset: PHAsset) -> String {
        let modified = asset.modificationDate.map { Int($0.timeIntervalSince1970 * 1000) } ?? 0
        return "\(modified):\(asset.pixelWidth)x\(asset.pixelHeight)"
    }

    private static func byteSize(of asset: PHAsset) -> Int? {
        let resources = PHAssetResource.assetResources(for: asset)
        guard let resource = resources.first(where: { $0.type == .photo }) ?? resources.first else {
            return nil
        }
        return (resource.value(forKey: "fileSize") as? NSNumber)?.intValue
    }

    private static func tempFileName(for remoteID: String) -> String {
        let safe = remoteID.replacingOccurrences(of: "/", with: "_")
        return "\(safe).payload"
    }
}
