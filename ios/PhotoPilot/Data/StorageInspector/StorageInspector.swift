import Foundation

enum StorageInspector {
    static func directoryBytes(_ url: URL) -> Int64 {
        guard let enumerator = FileManager.default.enumerator(
            at: url,
            includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else { return 0 }
        var total: Int64 = 0
        for case let fileURL as URL in enumerator {
            guard let values = try? fileURL.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]),
                  values.isRegularFile == true else { continue }
            total += Int64(values.fileSize ?? 0)
        }
        return total
    }

    static func freeBytes() -> Int64? {
        let url = URL(fileURLWithPath: NSHomeDirectory())
        let values = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage
    }

    static func totalCapacityBytes() -> Int64? {
        let url = URL(fileURLWithPath: NSHomeDirectory())
        let values = try? url.resourceValues(forKeys: [.volumeTotalCapacityKey])
        return values?.volumeTotalCapacity.map { Int64($0) }
    }

    static func reservedFreeBytes() -> Int64 {
        let gigabyte: Int64 = 1_073_741_824
        guard let total = totalCapacityBytes() else { return gigabyte }
        return max(gigabyte, total / 20)
    }

    static func clearRebuildableCache() {
        ThumbnailLoader.clearMemoryCache()
        PlayerImageLoader.clearMemoryCache()
        let fileManager = FileManager.default
        if let contents = try? fileManager.contentsOfDirectory(
            at: fileManager.temporaryDirectory,
            includingPropertiesForKeys: nil
        ) {
            for url in contents {
                try? fileManager.removeItem(at: url)
            }
        }
        let staging = PersistenceController.temporaryDirectoryURL()
        if let contents = try? fileManager.contentsOfDirectory(
            at: staging,
            includingPropertiesForKeys: nil
        ) {
            for url in contents {
                try? fileManager.removeItem(at: url)
            }
        }
    }
}
