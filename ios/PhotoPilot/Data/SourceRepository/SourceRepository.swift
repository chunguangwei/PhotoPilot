import CoreData
import Foundation

struct SourceInfoDTO: Sendable {
    let id: String
    let type: String
    let displayName: String
    let endpointKey: String?
    let status: String
    let recursive: Bool
    let directories: [String]
    let mirrorDelete: Bool
}

final class SourceRepository {
    static let localPhotosID = "source.local_photos"
    static let localFilesID = "source.local_files"

    private let container: NSPersistentContainer

    init(container: NSPersistentContainer) {
        self.container = container
    }

    func ensureLocalPhotosSource() throws -> String {
        try ensureSource(id: Self.localPhotosID, type: "local_photos", displayName: "Photos on this device")
    }

    func ensureLocalFilesSource() throws -> String {
        try ensureSource(id: Self.localFilesID, type: "local_files", displayName: "Files")
    }

    func ensureWebdavSource(
        endpointKey: String,
        displayName: String,
        recursive: Bool,
        directories: [String]
    ) throws -> String {
        try ensureRemoteSource(
            type: "webdav",
            endpointKey: endpointKey,
            displayName: displayName,
            recursive: recursive,
            directories: directories
        )
    }

    func ensureSmbSource(
        endpointKey: String,
        displayName: String,
        recursive: Bool,
        directories: [String]
    ) throws -> String {
        try ensureRemoteSource(
            type: "smb",
            endpointKey: endpointKey,
            displayName: displayName,
            recursive: recursive,
            directories: directories
        )
    }

    private func ensureRemoteSource(
        type: String,
        endpointKey: String,
        displayName: String,
        recursive: Bool,
        directories: [String]
    ) throws -> String {
        let context = container.viewContext
        let request = NSFetchRequest<NSManagedObject>(entityName: "Source")
        request.predicate = NSPredicate(
            format: "type == %@ AND endpoint_key == %@",
            type,
            endpointKey
        )
        request.fetchLimit = 1
        if let existing = try context.fetch(request).first {
            if (existing.value(forKey: "status") as? String) == "disconnected" {
                var merged: [String: Any] = [:]
                if let raw = existing.value(forKey: "settings_json") as? String,
                   let data = raw.data(using: .utf8),
                   let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    merged = parsed
                }
                merged["recursive"] = recursive
                merged["directories"] = directories
                let settings = try? JSONSerialization.data(
                    withJSONObject: merged,
                    options: [.sortedKeys]
                )
                existing.setValue("active", forKey: "status")
                existing.setValue(displayName, forKey: "display_name")
                existing.setValue(
                    settings.map { String(decoding: $0, as: UTF8.self) },
                    forKey: "settings_json"
                )
                existing.setValue(
                    Int(Date().timeIntervalSince1970 * 1000),
                    forKey: "updated_at"
                )
                if context.hasChanges {
                    try context.save()
                }
                try upsertFolderCollections(
                    sourceID: existing.value(forKey: "id") as? String ?? "",
                    directories: directories,
                    displayName: displayName
                )
            }
            return existing.value(forKey: "id") as? String ?? ""
        }
        guard let entity = context.persistentStoreCoordinator?
            .managedObjectModel.entitiesByName["Source"] else {
            throw PersistenceError.dbConstraintViolation
        }
        let settings = try? JSONSerialization.data(
            withJSONObject: ["recursive": recursive, "directories": directories],
            options: [.sortedKeys]
        )
        let now = Int(Date().timeIntervalSince1970 * 1000)
        let id = "source.webdav.\(SHA256Hex.digest(data: Data(endpointKey.utf8)).prefix(16))"
        let row = NSManagedObject(entity: entity, insertInto: context)
        row.setValue(id, forKey: "id")
        row.setValue(type, forKey: "type")
        row.setValue(displayName, forKey: "display_name")
        row.setValue(endpointKey, forKey: "endpoint_key")
        row.setValue("credential", forKey: "credential_ref")
        row.setValue(NSNumber(value: WebDavSourceAdapter.capabilityFlags), forKey: "capability_flags")
        row.setValue("user_managed", forKey: "retention_policy")
        row.setValue("active", forKey: "status")
        row.setValue(NSNumber(value: 1), forKey: "epoch")
        row.setValue(settings.map { String(decoding: $0, as: UTF8.self) }, forKey: "settings_json")
        row.setValue(now, forKey: "created_at")
        row.setValue(now, forKey: "updated_at")
        if context.hasChanges {
            try context.save()
        }
        try upsertFolderCollections(sourceID: id, directories: directories, displayName: displayName)
        return id
    }

    private func upsertFolderCollections(
        sourceID: String,
        directories: [String],
        displayName: String
    ) throws {
        let context = container.viewContext
        let sourceRequest = NSFetchRequest<NSManagedObject>(entityName: "Source")
        sourceRequest.predicate = NSPredicate(format: "id == %@", sourceID)
        sourceRequest.fetchLimit = 1
        guard let source = try context.fetch(sourceRequest).first else {
            throw PersistenceError.dbConstraintViolation
        }
        let now = Int(Date().timeIntervalSince1970 * 1000)
        for directory in directories {
            let request = NSFetchRequest<NSManagedObject>(entityName: "Collection")
            request.predicate = NSPredicate(
                format: "source.id == %@ AND type == %@ AND rel_path == %@",
                sourceID,
                "source_folder",
                directory
            )
            request.fetchLimit = 1
            if try context.fetch(request).first != nil {
                continue
            }
            guard let entity = context.persistentStoreCoordinator?
                .managedObjectModel.entitiesByName["Collection"] else {
                throw PersistenceError.dbConstraintViolation
            }
            let lastSegment = directory.split(separator: "/").last.map(String.init)
            let collection = NSManagedObject(entity: entity, insertInto: context)
            collection.setValue(UUID().uuidString, forKey: "id")
            collection.setValue(source, forKey: "source")
            collection.setValue(
                directory.isEmpty ? displayName : (lastSegment ?? directory),
                forKey: "name"
            )
            collection.setValue(directory, forKey: "rel_path")
            collection.setValue("source_folder", forKey: "type")
            collection.setValue(directory, forKey: "sort_key")
            collection.setValue(now, forKey: "created_at")
            collection.setValue(now, forKey: "updated_at")
        }
        if context.hasChanges {
            try context.save()
        }
    }

    func setWebdavMirrorDelete(sourceID: String, enabled: Bool) throws {
        let context = container.viewContext
        let request = NSFetchRequest<NSManagedObject>(entityName: "Source")
        request.predicate = NSPredicate(format: "id == %@", sourceID)
        request.fetchLimit = 1
        guard let row = try context.fetch(request).first else {
            throw PersistenceError.dbConstraintViolation
        }
        var settings: [String: Any] = [:]
        if let raw = row.value(forKey: "settings_json") as? String,
           let data = raw.data(using: .utf8),
           let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            settings = parsed
        }
        settings["mirrorDelete"] = enabled
        guard let data = try? JSONSerialization.data(withJSONObject: settings, options: [.sortedKeys]) else {
            return
        }
        row.setValue(String(decoding: data, as: UTF8.self), forKey: "settings_json")
        row.setValue(Int(Date().timeIntervalSince1970 * 1000), forKey: "updated_at")
        if enabled {
            let assetsRequest = NSFetchRequest<NSManagedObject>(entityName: "SourceAsset")
            assetsRequest.predicate = NSPredicate(format: "source.id == %@", sourceID)
            for sourceAsset in try context.fetch(assetsRequest) {
                sourceAsset.setValue(NSNumber(value: 0), forKey: "missing_scan_count")
            }
        }
        if context.hasChanges {
            try context.save()
        }
    }

    func webdavMirrorDeleteEnabled(sourceID: String) throws -> Bool {
        let context = container.viewContext
        let request = NSFetchRequest<NSManagedObject>(entityName: "Source")
        request.predicate = NSPredicate(format: "id == %@", sourceID)
        request.fetchLimit = 1
        guard let row = try context.fetch(request).first,
              let raw = row.value(forKey: "settings_json") as? String,
              let data = raw.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return false
        }
        return parsed["mirrorDelete"] as? Bool ?? false
    }

    func webdavSources() throws -> [SourceInfoDTO] {
        let context = container.viewContext
        let request = NSFetchRequest<NSManagedObject>(entityName: "Source")
        request.predicate = NSPredicate(format: "type IN %@", ["webdav", "smb"])
        return try context.fetch(request).map { row in
            var recursive = true
            var directories: [String] = []
            var mirrorDelete = false
            if let raw = row.value(forKey: "settings_json") as? String,
               let data = raw.data(using: .utf8),
               let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                recursive = parsed["recursive"] as? Bool ?? true
                directories = parsed["directories"] as? [String] ?? []
                mirrorDelete = parsed["mirrorDelete"] as? Bool ?? false
            }
            return SourceInfoDTO(
                id: row.value(forKey: "id") as? String ?? "",
                type: row.value(forKey: "type") as? String ?? "",
                displayName: row.value(forKey: "display_name") as? String ?? "",
                endpointKey: row.value(forKey: "endpoint_key") as? String,
                status: row.value(forKey: "status") as? String ?? "",
                recursive: recursive,
                directories: directories,
                mirrorDelete: mirrorDelete
            )
        }
    }

    private func ensureSource(id: String, type: String, displayName: String) throws -> String {
        let context = container.viewContext
        let request = NSFetchRequest<NSManagedObject>(entityName: "Source")
        request.predicate = NSPredicate(format: "id == %@", id)
        request.fetchLimit = 1
        if try context.fetch(request).first != nil {
            return id
        }
        guard let entity = context.persistentStoreCoordinator?
            .managedObjectModel.entitiesByName["Source"] else {
            throw PersistenceError.dbConstraintViolation
        }
        let now = Int(Date().timeIntervalSince1970 * 1000)
        let row = NSManagedObject(entity: entity, insertInto: context)
        row.setValue(id, forKey: "id")
        row.setValue(type, forKey: "type")
        row.setValue(displayName, forKey: "display_name")
        row.setValue(NSNumber(value: 0), forKey: "capability_flags")
        row.setValue("user_managed", forKey: "retention_policy")
        row.setValue("active", forKey: "status")
        row.setValue(NSNumber(value: 1), forKey: "epoch")
        row.setValue(now, forKey: "created_at")
        row.setValue(now, forKey: "updated_at")
        if context.hasChanges {
            try context.save()
        }
        return id
    }
}
