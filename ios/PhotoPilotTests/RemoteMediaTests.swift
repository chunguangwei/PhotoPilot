import Foundation
import XCTest
@testable import PhotoPilot

final class RemoteMediaTests: XCTestCase {
    func testStorageModeDefaultsAndPreservesOtherSettings() throws {
        XCTAssertEqual(FrameStorageMode.read("{}"), .local)
        let json = try FrameStorageMode.remote.applying(to: "{\"holdSeconds\":17,\"music\":{\"enabled\":true}}")
        XCTAssertEqual(FrameStorageMode.read(json), .remote)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        XCTAssertEqual(object["holdSeconds"] as? Int, 17)
        XCTAssertEqual((object["music"] as? [String: Any])?["enabled"] as? Bool, true)
    }

    func testSharedRemotePathVectors() throws {
        #if SWIFT_PACKAGE
        let bundle = Bundle.module
        #else
        let bundle = Bundle(for: type(of: self))
        #endif
        let url = try XCTUnwrap(bundle.url(forResource: "frame-remote-paths", withExtension: "json"))
        struct Reference: Decodable { let cases: [Example] }
        struct Example: Decodable { let id: String; let folder: String; let remoteKey: String; let recursive: Bool; let expectedMember: Bool }
        for example in try JSONDecoder().decode(Reference.self, from: Data(contentsOf: url)).cases {
            XCTAssertEqual(RemoteMediaPath.contains(folder: example.folder, remoteKey: example.remoteKey, recursive: example.recursive), example.expectedMember, example.id)
        }
    }

    func testSharedEligibilityVectors() throws {
        #if SWIFT_PACKAGE
        let bundle = Bundle.module
        #else
        let bundle = Bundle(for: type(of: self))
        #endif
        let url = try XCTUnwrap(bundle.url(forResource: "frame-remote-eligibility", withExtension: "json"))
        struct Reference: Decodable { let cases: [Example] }
        struct Example: Decodable {
            let id: String; let mode: FrameStorageMode; let sourceStatus: String; let accessState: String
            let policyAllowed: Bool; let privacyExcluded: Bool; let deleted: Bool
            let localReady: Bool; let localFileExists: Bool; let mediaKind: String; let expectedPlayable: Bool
        }
        for e in try JSONDecoder().decode(Reference.self, from: Data(contentsOf: url)).cases {
            XCTAssertEqual(RemoteMediaEligibility.playable(mode: e.mode, sourceStatus: e.sourceStatus, accessState: e.accessState,
                policyAllowed: e.policyAllowed, privacyExcluded: e.privacyExcluded, deleted: e.deleted,
                localReady: e.localReady, localFileExists: e.localFileExists, mediaKind: e.mediaKind), e.expectedPlayable, e.id)
        }
    }

    func testUnicodeNASPathsUseExactUTF8Identity() {
        XCTAssertFalse(RemoteMediaPath.contains(folder: "caf\u{E9}", remoteKey: "cafe\u{301}/photo.jpg", recursive: true))
        XCTAssertFalse(RemoteMediaPath.contains(folder: "cafe\u{301}", remoteKey: "caf\u{E9}/photo.jpg", recursive: true))
    }

    func testTypedSourceReferencesDoNotCollideWithLocalIDs() {
        XCTAssertEqual(MediaReference.sourceAsset("abc").runtimeID, "remote:abc")
        XCTAssertEqual(MediaReference.asset("abc").runtimeID, "abc")
        XCTAssertEqual(MediaReference(runtimeID: "remote:abc"), .sourceAsset("abc"))
        XCTAssertEqual(MediaReference(runtimeID: "abc"), .asset("abc"))
    }
}
