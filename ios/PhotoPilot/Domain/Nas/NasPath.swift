import Foundation

enum NasPath {
    static let maxLength = 1024

    static func normalize(_ input: String) -> String? {
        guard input.count <= maxLength else { return nil }
        for scalar in input.unicodeScalars {
            if scalar.value < 0x20 || scalar.value == 0x7F {
                return nil
            }
        }
        let unified = input.replacingOccurrences(of: "\\", with: "/")
        var segments: [String] = []
        for segment in unified.split(separator: "/", omittingEmptySubsequences: true) {
            let part = String(segment)
            if part == "." {
                continue
            }
            if part == ".." {
                guard !segments.isEmpty else { return nil }
                segments.removeLast()
                continue
            }
            segments.append(part)
        }
        return segments.joined(separator: "/")
    }

    static func splitShare(_ path: String) -> (share: String, subpath: String) {
        guard let index = path.firstIndex(of: "/") else { return (path, "") }
        return (String(path[..<index]), String(path[path.index(after: index)...]))
    }
}
