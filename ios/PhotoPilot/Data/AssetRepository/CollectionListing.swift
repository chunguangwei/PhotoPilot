import Foundation

enum CollectionListing {
    static func visibleInFolderList(type: String, memberCount: Int) -> Bool {
        type != CollectionType.importBatch.rawValue || memberCount > 0
    }
}
