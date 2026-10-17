import CoreData
import Foundation
import OSLog
import SQLite3

private let contractIndexLog = Logger(subsystem: "com.photopilot", category: "persistence")

struct ContractIndexSpec: Sendable {
    let name: String
    let entityName: String
    let properties: [String]
    let unique: Bool
    let whereColumn: String?
    let whereSuffix: String?
}

enum ContractIndexes {
    static let specs: [ContractIndexSpec] = [
        ContractIndexSpec(
            name: "index_source_asset_source_id_remote_key",
            entityName: "SourceAsset",
            properties: ["source", "remote_key"],
            unique: true,
            whereColumn: nil,
            whereSuffix: nil
        ),
        ContractIndexSpec(
            name: "index_source_asset_source_id_last_seen_at",
            entityName: "SourceAsset",
            properties: ["source", "last_seen_at"],
            unique: false,
            whereColumn: nil,
            whereSuffix: nil
        ),
        ContractIndexSpec(
            name: "index_source_asset_stable_id",
            entityName: "SourceAsset",
            properties: ["stable_id"],
            unique: false,
            whereColumn: nil,
            whereSuffix: nil
        ),
        ContractIndexSpec(
            name: "index_asset_content_hash_not_deleted",
            entityName: "Asset",
            properties: ["content_hash"],
            unique: true,
            whereColumn: "readiness",
            whereSuffix: " != 'deleted'" 
        ),
        ContractIndexSpec(
            name: "index_asset_captured_at_id",
            entityName: "Asset",
            properties: ["captured_at", "id"],
            unique: false,
            whereColumn: nil,
            whereSuffix: nil
        ),
        ContractIndexSpec(
            name: "index_asset_readiness",
            entityName: "Asset",
            properties: ["readiness"],
            unique: false,
            whereColumn: nil,
            whereSuffix: nil
        ),
        ContractIndexSpec(
            name: "index_asset_orientation_class",
            entityName: "Asset",
            properties: ["orientation_class"],
            unique: false,
            whereColumn: nil,
            whereSuffix: nil
        ),
        ContractIndexSpec(
            name: "index_asset_original_file_name",
            entityName: "Asset",
            properties: ["original_file_name"],
            unique: false,
            whereColumn: nil,
            whereSuffix: nil
        ),
        ContractIndexSpec(
            name: "index_source_type_endpoint_key_not_null",
            entityName: "Source",
            properties: ["type", "endpoint_key"],
            unique: true,
            whereColumn: "endpoint_key",
            whereSuffix: " IS NOT NULL" 
        ),
        ContractIndexSpec(
            name: "index_collection_source_id_rel_path",
            entityName: "Collection",
            properties: ["source", "rel_path"],
            unique: true,
            whereColumn: nil,
            whereSuffix: nil
        ),
        ContractIndexSpec(
            name: "index_collection_asset_asset_id",
            entityName: "CollectionAsset",
            properties: ["asset"],
            unique: false,
            whereColumn: nil,
            whereSuffix: nil
        ),
        ContractIndexSpec(
            name: "index_import_item_job_id_state",
            entityName: "ImportItem",
            properties: ["job", "state"],
            unique: false,
            whereColumn: nil,
            whereSuffix: nil
        ),
        ContractIndexSpec(
            name: "index_import_item_retry_at",
            entityName: "ImportItem",
            properties: ["retry_at"],
            unique: false,
            whereColumn: nil,
            whereSuffix: nil
        ),
    ]

    static func statements(for model: NSManagedObjectModel) -> [String] {
        specs.compactMap { spec -> String? in
            guard let entity = model.entitiesByName[spec.entityName] else { return nil }
            let table = "Z" + entity.name!.uppercased()
            let resolver: (String) -> String = { propertyName in
                let stored = entity.propertiesByName[propertyName]?.renamingIdentifier
                let base = (stored == propertyName || stored == nil) ? propertyName : stored!
                return "Z" + base.uppercased()
            }
            let columns = spec.properties.map(resolver).joined(separator: ", ")
            let uniquePrefix = spec.unique ? "UNIQUE " : ""
            var sql = "CREATE \(uniquePrefix)INDEX IF NOT EXISTS \(spec.name) ON \(table) (\(columns))"
            if let whereColumn = spec.whereColumn, let whereSuffix = spec.whereSuffix {
                sql += " WHERE \(resolver(whereColumn))\(whereSuffix)"
            }
            return sql
        }
    }

    @discardableResult
    static func apply(storeURL: URL, model: NSManagedObjectModel) -> Int {
        var db: OpaquePointer?
        guard sqlite3_open_v2(
            storeURL.path,
            &db,
            SQLITE_OPEN_READWRITE,
            nil
        ) == SQLITE_OK, let db else {
            sqlite3_close_v2(db)
            return 0
        }
        defer { sqlite3_close_v2(db) }
        var applied = 0
        for statement in statements(for: model) {
            var errorPointer: UnsafeMutablePointer<CChar>?
            if sqlite3_exec(db, statement, nil, nil, &errorPointer) == SQLITE_OK {
                applied += 1
            } else {
                let message = errorPointer.map { String(cString: $0) } ?? "unknown"
                contractIndexLog.error("contract index failed: \(message, privacy: .public)")
                sqlite3_free(errorPointer)
            }
        }
        return applied
    }
}
