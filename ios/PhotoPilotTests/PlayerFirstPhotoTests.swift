import CoreData
import UIKit
import XCTest
@testable import PhotoPilot

private actor PlayerFirstPhotoSource: RemoteReadableSource {
    private(set) var downloads = 0
    private var released = false
    let pixels = RemoteIndexFixture.pixels

    func enumerateAssets(cursor: String?) async throws -> SourceAssetPage {
        SourceAssetPage(assets: [SourceAssetDescriptor(remoteID: "family/first.png", revision: "v1", byteSize: pixels.count, remoteTakenAtMillis: nil)], nextCursor: nil, totalKnown: 1)
    }

    func downloadRemote(remoteID: String, to destination: URL, maximumBytes: Int64) async throws {
        downloads += 1
        for _ in 0..<500 where !released {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        try Task.checkCancellation()
        guard released else { throw RemoteMediaError.unavailable }
        guard maximumBytes >= pixels.count else { throw RemoteMediaError.tooLarge }
        try pixels.write(to: destination)
    }

    func release() { released = true }
    func assetPayload(remoteID: String) async throws -> LocalAssetPayload { throw RemoteMediaError.unavailable }
}

@MainActor
final class PlayerFirstPhotoTests: XCTestCase {
    private func prepare(preview: Bool) async throws -> (PlayerViewController, PlayerFirstPhotoSource, PersistenceController, String) {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let controller = PersistenceController(storeURL: root.appendingPathComponent("library.db"))
        let sourceID = try SourceRepository(container: controller.container).ensureWebdavSource(endpointKey: "webdav://nas.invalid:443/photos", displayName: "Test NAS", recursive: true, directories: [""])
        let source = PlayerFirstPhotoSource()
        let media = RemoteMediaService(controller: controller, cacheRoot: root.appendingPathComponent("cache"), adapterFactory: { _ in source })
        _ = try await media.index(sourceID: sourceID)
        let records = try await media.repository.records(sourceID: sourceID)
        let record = try XCTUnwrap(records.first)
        if preview {
            try await media.cache.storePreview(RemoteIndexFixture.pixels, descriptor: record.descriptor, contentHash: "sha256:" + SHA256Hex.digest(data: RemoteIndexFixture.pixels), authorized: { _ in true })
        }
        let manager = FrameManager(controller: controller)
        let frameID = try await manager.createFrame(name: "First photo", storageMode: .remote)
        _ = try await FrameRepository(controller: controller).save(frameID: frameID, baseVersion: 0, addIncludes: [MediaReference(runtimeID: record.descriptor.runtimeID).selection])
        let player = PlayerViewController(environment: AppEnvironment(persistence: controller, remoteMedia: media), frameID: frameID)
        player.loadViewIfNeeded()
        player.view.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        player.viewWillAppear(false)
        for _ in 0..<200 {
            if await source.downloads > 0 { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        return (player, source, controller, sourceID)
    }

    private func value<T>(_ label: String, in player: PlayerViewController, as type: T.Type) -> T? {
        Mirror(reflecting: player).children.first { $0.label == label }?.value as? T
    }

    private func indicator(in view: UIView) -> UIActivityIndicatorView? {
        (view as? UIActivityIndicatorView) ?? view.subviews.compactMap { indicator(in: $0) }.first
    }

    func testCachedFirstPreviewDisplaysWhileFullDownloadWaitsWithoutStartingHold() async throws {
        let (player, source, _, _) = try await prepare(preview: true)
        defer { PlayerViewController.dismissActiveIfAny() }
        let imageView = try XCTUnwrap(value("frontImageView", in: player, as: UIImageView.self))
        let preview = try XCTUnwrap(imageView.image)
        XCTAssertEqual(player.controllerState, PlaybackController.preparing)
        XCTAssertEqual(value("nextTransitionAtMs", in: player, as: Int64.self), 0)
        XCTAssertTrue(try XCTUnwrap(indicator(in: player.view)).isAnimating)
        let downloads = await source.downloads
        XCTAssertEqual(downloads, 1)
        await source.release()
        for _ in 0..<200 where player.controllerState == PlaybackController.preparing { try await Task.sleep(nanoseconds: 10_000_000) }
        XCTAssertEqual(player.controllerState, PlaybackController.holding)
        XCTAssertNotNil(imageView.image)
        XCTAssertFalse(imageView.image === preview)
        XCTAssertFalse(try XCTUnwrap(indicator(in: player.view)).isAnimating)
    }

    func testMissingPreviewShowsLoadingAndExitPreventsLateTexture() async throws {
        let (player, source, _, _) = try await prepare(preview: false)
        let imageView = try XCTUnwrap(value("frontImageView", in: player, as: UIImageView.self))
        XCTAssertNil(imageView.image)
        let loading = try XCTUnwrap(indicator(in: player.view))
        XCTAssertTrue(loading.isAnimating)
        XCTAssertNotNil(loading.accessibilityLabel)
        PlayerViewController.dismissActiveIfAny()
        XCTAssertFalse(loading.isAnimating)
        await source.release()
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertNil(imageView.image)
    }

    func testRevocationClearsWaitingPreviewAndRejectsLateFullImage() async throws {
        let (player, source, controller, sourceID) = try await prepare(preview: true)
        defer { PlayerViewController.dismissActiveIfAny() }
        let imageView = try XCTUnwrap(value("frontImageView", in: player, as: UIImageView.self))
        XCTAssertNotNil(imageView.image)
        let context = controller.container.viewContext
        let sourceRow = try XCTUnwrap(RemoteMediaRepository.fetch(context, entity: "Source", id: sourceID))
        sourceRow.setValue("inactive", forKey: "status")
        try context.save()
        NotificationCenter.default.post(name: .remoteMediaChanged, object: nil, userInfo: ["revoked": true])
        XCTAssertNil(imageView.image)
        await source.release()
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertNil(imageView.image)
    }
}
