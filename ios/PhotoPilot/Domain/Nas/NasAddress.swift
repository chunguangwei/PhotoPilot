import Foundation

struct NasHostInput: Sendable, Equatable {
    let host: String
    let port: Int?
}

struct NasEndpoint: Sendable, Equatable {
    let scheme: String
    let host: String
    let port: Int
    let path: String
}

enum NasHostKind: String, Sendable {
    case ipv4
    case ipv6
    case hostname
}

enum NasAddress {
    static let defaultPorts: [String: Int] = [
        "smb": 445,
        "webdav": 443,
        "webdav_http": 80,
    ]

    static func defaultPort(_ scheme: String) -> Int? {
        defaultPorts[scheme.lowercased()]
    }

    static func urlHost(_ host: String) -> String {
        parseIPv6(host) != nil ? "[\(host)]" : host
    }

    static func parseHostInput(_ raw: String) -> NasHostInput? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        var host: String
        var port: Int?
        if trimmed.hasPrefix("[") {
            guard let close = trimmed.firstIndex(of: "]") else { return nil }
            host = String(trimmed[trimmed.index(after: trimmed.startIndex)..<close])
            let rest = trimmed[trimmed.index(after: close)...]
            if !rest.isEmpty {
                guard rest.hasPrefix(":") else { return nil }
                guard let parsed = parsePort(String(rest.dropFirst())) else { return nil }
                port = parsed
            }
        } else {
            let colonCount = trimmed.filter { $0 == ":" }.count
            if colonCount == 1 {
                let parts = trimmed.split(separator: ":", maxSplits: 1)
                host = String(parts[0])
                guard let parsed = parsePort(String(parts[1])) else { return nil }
                port = parsed
            } else {
                host = trimmed
            }
        }
        host = host.lowercased()
        guard !host.isEmpty else { return nil }
        guard host.allSatisfy({ !$0.isWhitespace }) else { return nil }
        if let v4 = parseIPv4(host) {
            guard v4 != (0, 0, 0, 0) else { return nil }
            return NasHostInput(host: host, port: port)
        }
        if parseIPv6(host) != nil {
            return NasHostInput(host: host, port: port)
        }
        guard isValidHostname(host) else { return nil }
        return NasHostInput(host: host, port: port)
    }

    static func classifyHost(_ host: String) -> NasHostKind {
        if parseIPv4(host) != nil { return .ipv4 }
        if parseIPv6(host) != nil { return .ipv6 }
        return .hostname
    }

    static func isPrivateEligible(host: String) -> Bool {
        if let v4 = parseIPv4(host) {
            if v4.0 == 10 { return true }
            if v4.0 == 172, v4.1 >= 16, v4.1 <= 31 { return true }
            if v4.0 == 192, v4.1 == 168 { return true }
            if v4.0 == 169, v4.1 == 254 { return true }
            return false
        }
        if let v6 = parseIPv6(host) {
            if v6 == [0, 0, 0, 0, 0, 0, 0, 1] { return false }
            if v6[0] & 0xFFC0 == 0xFE80 { return true }
            if v6[0] & 0xFE00 == 0xFC00 { return true }
            return false
        }
        return false
    }

    static func sourceFingerprint(scheme: String, host: String, port: Int?, path: String) -> String {
        let normalizedScheme = scheme.lowercased()
        let normalizedHost = host.lowercased()
        var authority = normalizedHost
        if let resolvedPort = port ?? defaultPorts[normalizedScheme] {
            authority += ":\(resolvedPort)"
        }
        let normalizedPath = NasPath.normalize(path) ?? ""
        if normalizedPath.isEmpty {
            return "\(normalizedScheme)://\(authority)"
        }
        return "\(normalizedScheme)://\(authority)/\(normalizedPath)"
    }

    static func parseFingerprint(_ fingerprint: String) -> NasEndpoint? {
        guard let separator = fingerprint.range(of: "://") else { return nil }
        let scheme = String(fingerprint[..<separator.lowerBound]).lowercased()
        let rest = fingerprint[separator.upperBound...]
        let authorityEnd = rest.firstIndex(of: "/") ?? rest.endIndex
        let authority = String(rest[..<authorityEnd])
        let path = authorityEnd == rest.endIndex ? "" : String(rest[authorityEnd...])
        var host = authority
        var port = defaultPorts[scheme]
        if let lastColon = authority.lastIndex(of: ":") {
            let candidate = String(authority[authority.index(after: lastColon)...])
            if let parsed = Int(candidate), (1...65_535).contains(parsed) {
                host = String(authority[..<lastColon])
                port = parsed
            }
        }
        guard !scheme.isEmpty, !host.isEmpty, let resolvedPort = port else { return nil }
        return NasEndpoint(
            scheme: scheme,
            host: host,
            port: resolvedPort,
            path: NasPath.normalize(path) ?? ""
        )
    }

    private static func parsePort(_ text: String) -> Int? {
        guard !text.isEmpty, text.allSatisfy({ $0.isNumber }), let value = Int(text),
              value >= 1, value <= 65_535 else {
            return nil
        }
        return value
    }

    static func parseIPv4(_ host: String) -> (UInt8, UInt8, UInt8, UInt8)? {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }
        var octets: [UInt8] = []
        for part in parts {
            guard !part.isEmpty, part.allSatisfy({ $0.isASCII && $0.isNumber }),
                  let value = UInt8(part) else {
                return nil
            }
            octets.append(value)
        }
        return (octets[0], octets[1], octets[2], octets[3])
    }

    static func parseIPv6(_ text: String) -> [UInt16]? {
        var host = text
        if let percentIndex = host.firstIndex(of: "%") {
            host = String(host[..<percentIndex])
        }
        guard !host.isEmpty else { return nil }
        var v4Tail: [UInt16] = []
        if let lastColon = host.lastIndex(of: ":") {
            let candidate = String(host[host.index(after: lastColon)...])
            if candidate.contains(".") {
                guard let v4 = parseIPv4(candidate) else { return nil }
                v4Tail = [
                    UInt16(v4.0) << 8 | UInt16(v4.1),
                    UInt16(v4.2) << 8 | UInt16(v4.3),
                ]
                host = String(host[..<lastColon])
            }
        }
        let doubleParts = host.components(separatedBy: "::")
        guard doubleParts.count <= 2 else { return nil }
        guard let head = hextets(doubleParts[0]) else { return nil }
        var tail: [UInt16] = []
        if doubleParts.count == 2 {
            guard let parsed = hextets(doubleParts[1]) else { return nil }
            tail = parsed
        }
        var groups: [UInt16]
        if doubleParts.count == 2 {
            let fill = 8 - head.count - tail.count - v4Tail.count
            guard fill >= 1 else { return nil }
            groups = head + Array(repeating: 0, count: fill) + tail
        } else {
            groups = head + tail
        }
        groups += v4Tail
        guard groups.count == 8 else { return nil }
        return groups
    }

    private static func hextets(_ text: String) -> [UInt16]? {
        if text.isEmpty { return [] }
        var groups: [UInt16] = []
        for part in text.split(separator: ":", omittingEmptySubsequences: false) {
            guard !part.isEmpty, part.count <= 4, let value = UInt16(part, radix: 16) else {
                return nil
            }
            groups.append(value)
        }
        return groups
    }

    private static func isValidHostname(_ host: String) -> Bool {
        let labels = host.split(separator: ".", omittingEmptySubsequences: false)
        for label in labels {
            guard !label.isEmpty, label.count <= 63 else { return false }
            guard label.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") }) else {
                return false
            }
            guard !label.hasPrefix("-"), !label.hasSuffix("-") else { return false }
        }
        return true
    }
}
