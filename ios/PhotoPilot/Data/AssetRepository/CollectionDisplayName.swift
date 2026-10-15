import Foundation

enum CollectionDisplayName {
    static func display(name: String, type: String) -> String {
        guard type == CollectionType.importBatch.rawValue else { return name }
        return L10n.format("library.import_batch_name", name)
    }
}
