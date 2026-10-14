import Foundation
import Security

struct NasCredential: Sendable, Equatable {
    let scheme: String
    let host: String
    let port: Int
    let path: String
    let username: String
    let password: String

    func toDictionary() -> [String: Any] {
        [
            "protocol": scheme,
            "host": host,
            "port": port,
            "path": path,
            "username": username,
            "password": password,
        ]
    }

    static func from(dictionary: [String: Any]) -> NasCredential? {
        guard let scheme = dictionary["protocol"] as? String,
              let host = dictionary["host"] as? String,
              let port = dictionary["port"] as? Int,
              let path = dictionary["path"] as? String,
              let username = dictionary["username"] as? String,
              let password = dictionary["password"] as? String else {
            return nil
        }
        return NasCredential(
            scheme: scheme,
            host: host,
            port: port,
            path: path,
            username: username,
            password: password
        )
    }
}

final class CredentialStore: @unchecked Sendable {
    static let service = Bundle.main.bundleIdentifier ?? "dev.photopilot.scaffold"

    static func accountKey(fingerprint: String) -> String {
        SHA256Hex.digest(data: Data(fingerprint.utf8))
    }

    static func baseQuery(fingerprint: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountKey(fingerprint: fingerprint),
            kSecAttrSynchronizable as String: false,
        ]
    }

    static func writeAttributes(payload: Data) -> [String: Any] {
        [
            kSecValueData as String: payload,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
    }

    func save(fingerprint: String, credential: NasCredential) -> Bool {
        guard let payload = try? JSONSerialization.data(
            withJSONObject: credential.toDictionary(),
            options: [.sortedKeys]
        ) else { return false }
        let query = Self.baseQuery(fingerprint: fingerprint)
        let attributes = Self.writeAttributes(payload: payload)
        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess {
            return true
        }
        if updateStatus == errSecItemNotFound {
            var insert = query
            for (key, value) in attributes {
                insert[key] = value
            }
            return SecItemAdd(insert as CFDictionary, nil) == errSecSuccess
        }
        return false
    }

    func load(fingerprint: String) -> NasCredential? {
        var query = Self.baseQuery(fingerprint: fingerprint)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let dictionary = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return NasCredential.from(dictionary: dictionary)
    }

    func delete(fingerprint: String) {
        SecItemDelete(Self.baseQuery(fingerprint: fingerprint) as CFDictionary)
    }

    func deleteAll() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrSynchronizable as String: false,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
