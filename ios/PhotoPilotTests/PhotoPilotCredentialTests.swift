import Security
import XCTest
@testable import PhotoPilot

final class PhotoPilotCredentialTests: XCTestCase {

    func testAccountKeyIsSha256Hex() {
        let key = CredentialStore.accountKey(fingerprint: "smb://nas.local:445/Photos")
        XCTAssertEqual(key.count, 64)
        XCTAssertTrue(key.allSatisfy { $0.isHexDigit })
        XCTAssertEqual(key, CredentialStore.accountKey(fingerprint: "smb://nas.local:445/Photos"))
        XCTAssertNotEqual(key, CredentialStore.accountKey(fingerprint: "smb://nas.local:446/Photos"))
    }

    func testBaseQueryFields() {
        let query = CredentialStore.baseQuery(fingerprint: "webdav://192.168.1.2:80")
        XCTAssertEqual(query[kSecClass as String] as? String, kSecClassGenericPassword as String)
        XCTAssertEqual(
            query[kSecAttrService as String] as? String,
            CredentialStore.service
        )
        XCTAssertEqual(
            query[kSecAttrAccount as String] as? String,
            CredentialStore.accountKey(fingerprint: "webdav://192.168.1.2:80")
        )
        XCTAssertEqual(query[kSecAttrSynchronizable as String] as? Bool, false)
        XCTAssertNil(query[kSecValueData as String])
    }

    func testWriteAttributesAccessibility() {
        let attributes = CredentialStore.writeAttributes(payload: Data([0x01]))
        XCTAssertEqual(
            attributes[kSecAttrAccessible as String] as? String,
            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String
        )
        XCTAssertEqual(attributes[kSecValueData as String] as? Data, Data([0x01]))
    }

    func testCredentialRoundTripDictionary() {
        let credential = NasCredential(
            scheme: "smb",
            host: "nas.local",
            port: 445,
            path: "Photos/2024",
            username: "user",
            password: "secret"
        )
        let dictionary = credential.toDictionary()
        XCTAssertEqual(dictionary["protocol"] as? String, "smb")
        XCTAssertEqual(dictionary["port"] as? Int, 445)
        XCTAssertEqual(NasCredential.from(dictionary: dictionary), credential)
        XCTAssertNil(NasCredential.from(dictionary: ["host": "nas.local"]))
    }
}
