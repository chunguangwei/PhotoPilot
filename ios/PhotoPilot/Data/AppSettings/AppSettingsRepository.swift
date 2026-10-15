import CoreData
import Foundation

final class AppSettingsRepository {
    static let onboardingCompletedKey = "onboarding.completed"
    static let playbackHasPlayedOnceKey = "playback.has_played_once"
    static let playbackAutoPlayKey = "playback.auto_play"
    static let playbackLastFrameIDKey = "playback.last_frame_id"
    static let appearanceKey = "ui.appearance"
    static let languageKey = "ui.language"
    static let wifiOnlyKey = "network.wifi_only"
    static let shareStatisticsKey = "privacy.share_statistics"
    static let nasLanNoticeSeenKey = "nas.lan_notice_seen"

    private let container: NSPersistentContainer

    init(container: NSPersistentContainer) {
        self.container = container
    }

    func isOnboardingCompleted() throws -> Bool {
        try value(forKey: Self.onboardingCompletedKey) == "true"
    }

    func setOnboardingCompleted(_ completed: Bool) throws {
        try setValue(completed ? "true" : "false", forKey: Self.onboardingCompletedKey)
    }

    func value(forKey key: String) throws -> String? {
        let context = container.viewContext
        return try context.performAndWait {
            let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
            request.predicate = NSPredicate(format: "key == %@", key)
            request.fetchLimit = 1
            return try context.fetch(request).first?.value(forKey: "value_json") as? String
        }
    }

    func setValue(_ value: String, forKey key: String) throws {
        let context = container.viewContext
        try context.performAndWait {
            let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
            request.predicate = NSPredicate(format: "key == %@", key)
            request.fetchLimit = 1
            let now = Int(Date().timeIntervalSince1970 * 1000)
            if let row = try context.fetch(request).first {
                row.setValue(value, forKey: "value_json")
                row.setValue(now, forKey: "updated_at")
            } else {
                guard let entity = context.persistentStoreCoordinator?
                    .managedObjectModel.entitiesByName["SettingsKV"] else {
                    throw PersistenceError.dbConstraintViolation
                }
                let created = NSManagedObject(entity: entity, insertInto: context)
                created.setValue(key, forKey: "key")
                created.setValue(value, forKey: "value_json")
                created.setValue(now, forKey: "created_at")
                created.setValue(now, forKey: "updated_at")
            }
            if context.hasChanges {
                try context.save()
            }
        }
    }
}
