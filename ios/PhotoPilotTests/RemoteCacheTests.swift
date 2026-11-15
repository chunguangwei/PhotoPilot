import Foundation
import XCTest
@testable import PhotoPilot

private actor RemoteFetchCounter {
    var count = 0
    func next() -> Int { count += 1; return count }
}

@MainActor
final class RemoteCacheTests: XCTestCase {
    private static func descriptor(_ id: String, size: Int64 = 6) -> RemoteMediaDescriptor {
        RemoteMediaDescriptor(sourceID: "source", sourceEpoch: 1, sourceAssetID: id, revision: "v1", remoteKey: id + ".jpg", byteSize: size, takenAt: nil)
    }

    func testCoalescesAndPinsUntilEveryReaderReleases() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let counter = RemoteFetchCounter()
        let cache = RemoteMediaCache(root: root, budget: 12, freeReserve: 0, freeSpace: { 1_000 })
        let fetch: RemoteMediaCache.Fetch = { _, destination, limit in
            _ = await counter.next()
            XCTAssertEqual(limit, 6)
            try await Task.sleep(nanoseconds: 30_000_000)
            try Data("abcdef".utf8).write(to: destination)
        }
        async let first = cache.acquire(Self.descriptor("a"), fetch: fetch, authorized: { _ in true })
        async let second = cache.acquire(Self.descriptor("a"), fetch: fetch, authorized: { _ in true })
        let (a, b) = try await (first, second)
        let count = await counter.count
        XCTAssertEqual(count, 1)
        XCTAssertEqual(a.fileURL, b.fileURL)
        let c = try await cache.acquire(Self.descriptor("c"), fetch: fetch, authorized: { _ in true })
        do {
            _ = try await cache.acquire(Self.descriptor("d"), fetch: fetch, authorized: { _ in true })
            XCTFail("Pinned entries cannot be evicted")
        } catch { XCTAssertEqual(error as? RemoteMediaError, .busy) }
        await a.release()
        await b.release()
        let d = try await cache.acquire(Self.descriptor("d"), fetch: fetch, authorized: { _ in true })
        XCTAssertFalse(FileManager.default.fileExists(atPath: a.fileURL.path))
        await c.release()
        await d.release()
        let usage = await cache.usage()
        XCTAssertLessThanOrEqual(usage, 12)
    }

    func testPlaybackPreemptsBackgroundWithoutOverbookingReservation() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let cache = RemoteMediaCache(root: root, budget: 6, freeReserve: 0, freeSpace: { 1_000 })
        let background = Task {
            try await cache.acquire(Self.descriptor("background"), priority: .background, fetch: { _, destination, _ in
                try await Task.sleep(nanoseconds: 1_000_000_000)
                try Data("abcdef".utf8).write(to: destination)
            }, authorized: { _ in true })
        }
        try await Task.sleep(nanoseconds: 20_000_000)
        let lease = try await cache.acquire(Self.descriptor("playback"), priority: .playback, fetch: { _, destination, _ in
            try Data("abcdef".utf8).write(to: destination)
        }, authorized: { _ in true })
        do { _ = try await background.value; XCTFail() }
        catch { XCTAssertEqual(error as? RemoteMediaError, .busy) }
        let usage = await cache.usage()
        XCTAssertEqual(usage, 6)
        await lease.release()
    }

    func testOnlyOneBackgroundTransferRunsAtATime() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let cache = RemoteMediaCache(root: root, budget: 30, freeReserve: 0, freeSpace: { 1_000 })
        let first = Task {
            try await cache.acquire(Self.descriptor("a"), priority: .background, fetch: { _, destination, _ in
                try await Task.sleep(nanoseconds: 100_000_000)
                try Data("abcdef".utf8).write(to: destination)
            }, authorized: { _ in true })
        }
        try await Task.sleep(nanoseconds: 20_000_000)
        do {
            _ = try await cache.acquire(Self.descriptor("b"), priority: .background, fetch: { _, _, _ in XCTFail() }, authorized: { _ in true })
            XCTFail()
        } catch { XCTAssertEqual(error as? RemoteMediaError, .busy) }
        let lease = try await first.value
        await lease.release()
    }

    func testOversizeAndFreeSpacePreventFetch() async throws {
        let counter = RemoteFetchCounter()
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let cache = RemoteMediaCache(root: root, budget: 10, freeReserve: 64, freeSpace: { 69 })
        let fetch: RemoteMediaCache.Fetch = { _, _, _ in _ = await counter.next() }
        for size in [11, 6] {
            do { _ = try await cache.acquire(Self.descriptor("a", size: Int64(size)), fetch: fetch, authorized: { _ in true }); XCTFail() }
            catch { XCTAssertTrue([RemoteMediaError.tooLarge, .lowSpace].contains(error as? RemoteMediaError ?? .unavailable)) }
        }
        let count = await counter.count
        XCTAssertEqual(count, 0)
    }

    func testCancelledWaiterDoesNotCancelOtherConsumer() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let cache = RemoteMediaCache(root: root, budget: 20, freeReserve: 0, freeSpace: { 1_000 })
        let fetch: RemoteMediaCache.Fetch = { _, destination, _ in
            try await Task.sleep(nanoseconds: 100_000_000)
            try Data("abcdef".utf8).write(to: destination)
        }
        let cancelled = Task { try await cache.acquire(Self.descriptor("a"), fetch: fetch, authorized: { _ in true }) }
        let survivor = Task { try await cache.acquire(Self.descriptor("a"), fetch: fetch, authorized: { _ in true }) }
        try await Task.sleep(nanoseconds: 20_000_000)
        cancelled.cancel()
        do { _ = try await cancelled.value; XCTFail() } catch is CancellationError {} catch { XCTFail("\(error)") }
        let lease = try await survivor.value
        XCTAssertEqual(try Data(contentsOf: lease.fileURL), Data("abcdef".utf8))
        await lease.release()
    }

    func testRevocationRejectsLateDownloadAndErasesPartial() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let cache = RemoteMediaCache(root: root, budget: 20, freeReserve: 0, freeSpace: { 1_000 })
        let counter = RemoteFetchCounter()
        do {
            _ = try await cache.acquire(Self.descriptor("a"), fetch: { _, destination, _ in
                try Data("abcdef".utf8).write(to: destination)
            }, authorized: { _ in await counter.next() == 1 })
            XCTFail()
        } catch { XCTAssertEqual(error as? RemoteMediaError, .revoked) }
        let usage = await cache.usage()
        XCTAssertEqual(usage, 0)
        XCTAssertFalse((try? FileManager.default.contentsOfDirectory(atPath: root.path))?.contains(where: { $0.hasSuffix(".partial") }) ?? false)
    }
}

extension RemoteCacheTests {
    func testTransientReservationsShareBudgetWithCachedAndPinnedFiles() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let cache = RemoteMediaCache(root: root, budget: 12, freeReserve: 0, freeSpace: { 1_000 })
        let lease = try await cache.acquire(Self.descriptor("pinned", size: 8), fetch: { _, destination, _ in
            try Data(repeating: 1, count: 8).write(to: destination)
        }, authorized: { _ in true })
        do { _ = try await cache.reserveTransient(bytes: 6); XCTFail("Pinned file exceeded shared budget") }
        catch { XCTAssertEqual(error as? RemoteMediaError, .busy) }
        await lease.release()
        let first = try await cache.reserveTransient(bytes: 6)
        XCTAssertFalse(FileManager.default.fileExists(atPath: lease.fileURL.path))
        let second = try await cache.reserveTransient(bytes: 6)
        let full = await cache.usage()
        XCTAssertEqual(full, 12)
        do { _ = try await cache.reserveTransient(bytes: 1); XCTFail("Transient reservations exceeded shared budget") }
        catch { XCTAssertEqual(error as? RemoteMediaError, .busy) }
        await cache.releaseTransient(first)
        await cache.releaseTransient(first)
        let remaining = await cache.usage()
        XCTAssertEqual(remaining, 6)
        await cache.releaseTransient(second)
        let empty = await cache.usage()
        XCTAssertEqual(empty, 0)
    }
}
