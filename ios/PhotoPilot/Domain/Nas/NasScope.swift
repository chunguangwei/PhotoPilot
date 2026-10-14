import Foundation

enum NasScope {
    static func normalizeScope(_ directories: [String]) -> [String]? {
        var normalized = Set<String>()
        for directory in directories {
            guard let path = NasPath.normalize(directory) else { return nil }
            normalized.insert(path)
        }
        guard !normalized.isEmpty else { return nil }
        if normalized.contains("") {
            return [""]
        }
        var result: [String] = []
        for path in normalized.sorted() {
            let contained = result.contains { parent in
                path == parent || path.hasPrefix(parent + "/")
            }
            if !contained {
                result.append(path)
            }
        }
        return result
    }

    static func scopeJSON(_ directories: [String]) -> String? {
        guard let normalized = normalizeScope(directories),
              let data = try? JSONSerialization.data(
                  withJSONObject: ["directories": normalized],
                  options: [.sortedKeys]
              ) else {
            return nil
        }
        return String(decoding: data, as: UTF8.self)
    }
}
