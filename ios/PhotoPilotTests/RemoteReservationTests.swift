import Foundation
import XCTest
@testable import PhotoPilot

@MainActor
final class RemoteReservationTests: XCTestCase {
    func testFutureWritesAcrossCacheAndOfflineSyncReserveFreeSpaceTogether() async throws {
        let space = RemoteDiskReservations(root: FileManager.default.temporaryDirectory, reserve: 64, freeSpace: { 100 })
        let first = try await space.acquire(bytes: 20)
        do { _ = try await space.acquire(bytes: 20); XCTFail() }
        catch { XCTAssertEqual(error as? RemoteMediaError, .lowSpace) }
        let remaining = await space.localMaximum()
        XCTAssertEqual(remaining, 8)
        await first.release()
        await first.release()
        let second = try await space.acquire(bytes: 30)
        await second.release()
    }

    func testCancelledQueuedTransferReleasesItsPlace() async throws {
        let gate = RemoteTransferGate(limit: 1)
        let first = try await gate.acquire(priority: .visible)
        let waiting = Task { try await gate.acquire(priority: .background) }
        try await Task.sleep(nanoseconds: 10_000_000)
        waiting.cancel()
        do { _ = try await waiting.value; XCTFail() } catch is CancellationError {} catch { XCTFail() }
        await first.release()
        let next = try await gate.acquire(priority: .playback)
        await next.release()
    }
}
