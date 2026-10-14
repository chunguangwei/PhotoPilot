import Foundation

struct TofuPin: Sendable, Equatable {
    let fingerprint: String
    let firstSeenAt: Int
    let confirmedAt: Int?
}

final class TofuPinStore {
    static let pinsKey = "nas.tofu_pins"
    static let cleartextHostsKey = "nas.cleartext_hosts"

    private let settings: AppSettingsRepository

    init(settings: AppSettingsRepository) {
        self.settings = settings
    }

    func pin(for hostPort: String) -> TofuPin? {
        guard let pins = readPins() else { return nil }
        guard let entry = pins[hostPort.lowercased()] as? [String: Any],
              let fingerprint = entry["fingerprint"] as? String,
              let firstSeenAt = entry["firstSeenAt"] as? Int else {
            return nil
        }
        return TofuPin(
            fingerprint: fingerprint,
            firstSeenAt: firstSeenAt,
            confirmedAt: entry["confirmedAt"] as? Int
        )
    }

    func savePin(hostPort: String, fingerprint: String, confirmed: Bool) {
        var pins = readPins() ?? [:]
        let key = hostPort.lowercased()
        let existing = pins[key] as? [String: Any]
        let firstSeen = existing?["firstSeenAt"] as? Int ?? Self.nowMillis()
        var entry: [String: Any] = [
            "fingerprint": fingerprint,
            "firstSeenAt": firstSeen,
        ]
        if confirmed {
            entry["confirmedAt"] = Self.nowMillis()
        } else if let confirmedAt = existing?["confirmedAt"] as? Int {
            entry["confirmedAt"] = confirmedAt
        }
        pins[key] = entry
        writePins(pins)
    }

    func removePin(hostPort: String) {
        var pins = readPins() ?? [:]
        pins.removeValue(forKey: hostPort.lowercased())
        writePins(pins)
    }

    func allowsCleartext(host: String) -> Bool {
        readCleartextHosts().contains(host.lowercased())
    }

    func cleartextHosts() -> Set<String> {
        readCleartextHosts()
    }

    func setCleartext(host: String, allowed: Bool) {
        var hosts = readCleartextHosts()
        if allowed {
            hosts.insert(host.lowercased())
        } else {
            hosts.remove(host.lowercased())
        }
        writeJSON(Array(hosts).sorted(), forKey: Self.cleartextHostsKey)
    }

    private func readPins() -> [String: Any]? {
        guard let raw = try? settings.value(forKey: Self.pinsKey),
              let data = raw.data(using: .utf8),
              let map = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return map
    }

    private func writePins(_ pins: [String: Any]) {
        writeJSON(pins, forKey: Self.pinsKey)
    }

    private func readCleartextHosts() -> Set<String> {
        guard let raw = try? settings.value(forKey: Self.cleartextHostsKey),
              let data = raw.data(using: .utf8),
              let list = try? JSONSerialization.jsonObject(with: data) as? [String] else {
            return []
        }
        return Set(list.map { $0.lowercased() })
    }

    private func writeJSON(_ value: Any, forKey key: String) {
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) else {
            return
        }
        try? settings.setValue(String(decoding: data, as: UTF8.self), forKey: key)
    }

    private static func nowMillis() -> Int {
        Int(Date().timeIntervalSince1970 * 1000)
    }
}
