import Foundation

actor ScopedRemoteImportAdapter: SourceAdapter {
    private let descriptors: [RemoteMediaDescriptor]
    private let underlying: any RemoteReadableSource
    private let media: RemoteMediaService
    private let directory: URL
    private let frameID: String
    private let selectionVersion: Int64
    private var payloads: [Data: LocalAssetPayload] = [:]
    private var spaces: [Data: RemoteSpaceLease] = [:]
    private var localSlots: [Data: RemoteTransferLease] = [:]

    init(descriptors: [RemoteMediaDescriptor], underlying: any RemoteReadableSource, media: RemoteMediaService,
         directory: URL, frameID: String, selectionVersion: Int64) {
        self.descriptors = descriptors
        self.underlying = underlying
        self.media = media
        self.directory = directory
        self.frameID = frameID
        self.selectionVersion = selectionVersion
    }

    func enumerateAssets(cursor: String?) async throws -> SourceAssetPage {
        try await checkCurrent()
        return SourceAssetPage(assets: descriptors.map { SourceAssetDescriptor(remoteID: $0.remoteKey, revision: $0.revision,
            byteSize: $0.byteSize.map(Int.init), remoteTakenAtMillis: $0.takenAt.map(Int.init)) }, nextCursor: nil, totalKnown: descriptors.count)
    }

    func assetPayload(remoteID: String) async throws -> LocalAssetPayload {
        try Task.checkCancellation()
        try await checkCurrent()
        guard let descriptor = descriptors.first(where: { Data($0.remoteKey.utf8) == Data(remoteID.utf8) }),
              await media.repository.authorized(descriptor) else { throw RemoteMediaError.revoked }
        if let payload = payloads[Data(remoteID.utf8)] { return payload }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let localSlot = try await media.localTransfers.acquire(priority: .background)
        let available = await media.diskReservations.localMaximum()
        let maximum = descriptor.byteSize.flatMap { $0 > 0 ? $0 : nil } ?? available
        guard maximum > 0, maximum <= available else {
            await localSlot.release()
            throw RemoteMediaError.lowSpace
        }
        let space: RemoteSpaceLease
        do { space = try await media.diskReservations.acquire(bytes: maximum * 2) }
        catch { await localSlot.release(); throw error }
        let file = directory.appendingPathComponent(descriptor.sourceAssetID + ".payload")
        do {
            let transfer = try await media.transfers.acquire(priority: .background)
            do {
                try await underlying.downloadRemote(remoteID: remoteID, to: file, maximumBytes: maximum)
                await transfer.release()
            } catch {
                await transfer.release()
                throw error
            }
            try Task.checkCancellation()
            try await checkCurrent()
            guard await media.repository.authorized(descriptor) else { throw RemoteMediaError.revoked }
            let info = try AssetFileProber.probeImage(file)
            let payload = LocalAssetPayload(localFileURL: file,
                byteSize: (try FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.intValue ?? 0,
                contentHash: try AssetFileProber.contentHash(of: file), head64kHash: try AssetFileProber.head64kHash(of: file),
                format: info.format, pixelWidth: info.pixelWidth, pixelHeight: info.pixelHeight, exifOrientation: info.exifOrientation,
                capturedAtMillis: info.capturedAtMillis, mediaKind: info.mediaKind, durationMillis: info.durationMillis, capturedAtSource: info.capturedAtSource)
            payloads[Data(remoteID.utf8)] = payload
            spaces[Data(remoteID.utf8)] = space
            localSlots[Data(remoteID.utf8)] = localSlot
            return payload
        } catch {
            try? FileManager.default.removeItem(at: file)
            await space.release()
            await localSlot.release()
            throw error
        }
    }

    func clean() async {
        payloads.removeAll()
        try? FileManager.default.removeItem(at: directory)
        for space in spaces.values { await space.release() }
        for slot in localSlots.values { await slot.release() }
        spaces.removeAll()
        localSlots.removeAll()
    }

    private func checkCurrent() async throws {
        let bundle = try await FrameManager(controller: media.controller).framePlaybackBundle(frameID: frameID)
        guard FrameStorageMode.read(bundle.settingsJSON) == .local, bundle.selectionVersion == selectionVersion else { throw RemoteMediaError.changed }
    }
}
