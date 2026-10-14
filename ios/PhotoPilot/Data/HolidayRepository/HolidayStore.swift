import CoreData
import Foundation

final class HolidayStore: @unchecked Sendable {
    private let controller: PersistenceController

    init(controller: PersistenceController) {
        self.controller = controller
    }

    private func makeContext() -> NSManagedObjectContext {
        controller.container.newBackgroundContext()
    }

    private func nowMillis() -> Int {
        Int(Date().timeIntervalSince1970 * 1000)
    }

    func rules() async throws -> [HolidayRule] {
        let context = makeContext()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "HolidaySchedule")
            request.sortDescriptors = [
                NSSortDescriptor(key: "position", ascending: true),
                NSSortDescriptor(key: "created_at", ascending: true),
            ]
            return try context.fetch(request).map(Self.rule(from:))
        }
    }

    func create(
        name: String,
        startDate: String,
        endDate: String,
        startTime: Int?,
        endTime: Int?,
        frameID: String,
        enabled: Bool
    ) async throws -> String {
        let context = makeContext()
        let now = nowMillis()
        return try await context.perform {
            let request = NSFetchRequest<NSManagedObject>(entityName: "HolidaySchedule")
            request.sortDescriptors = [NSSortDescriptor(key: "position", ascending: false)]
            request.fetchLimit = 1
            let maxPosition = try context.fetch(request).first?.value(forKey: "position") as? Int ?? -1
            let rule = NSEntityDescription.insertNewObject(forEntityName: "HolidaySchedule", into: context)
            let ruleID = UUID().uuidString
            rule.setValue(ruleID, forKey: "id")
            rule.setValue(name, forKey: "name")
            rule.setValue(startDate, forKey: "start_date")
            rule.setValue(endDate, forKey: "end_date")
            rule.setValue(startTime.map(NSNumber.init(value:)), forKey: "start_time")
            rule.setValue(endTime.map(NSNumber.init(value:)), forKey: "end_time")
            rule.setValue(NSNumber(value: maxPosition + 1), forKey: "position")
            rule.setValue(NSNumber(value: enabled), forKey: "enabled")
            rule.setValue(try Self.fetchFrame(context: context, frameID: frameID), forKey: "frame")
            rule.setValue(now, forKey: "created_at")
            rule.setValue(now, forKey: "updated_at")
            try context.save()
            return ruleID
        }
    }

    func update(
        ruleID: String,
        name: String,
        startDate: String,
        endDate: String,
        startTime: Int?,
        endTime: Int?,
        frameID: String,
        enabled: Bool
    ) async throws {
        let context = makeContext()
        let now = nowMillis()
        try await context.perform {
            let rule = try Self.fetchRule(context: context, ruleID: ruleID)
            rule.setValue(name, forKey: "name")
            rule.setValue(startDate, forKey: "start_date")
            rule.setValue(endDate, forKey: "end_date")
            rule.setValue(startTime.map(NSNumber.init(value:)), forKey: "start_time")
            rule.setValue(endTime.map(NSNumber.init(value:)), forKey: "end_time")
            rule.setValue(NSNumber(value: enabled), forKey: "enabled")
            rule.setValue(try Self.fetchFrame(context: context, frameID: frameID), forKey: "frame")
            rule.setValue(now, forKey: "updated_at")
            try context.save()
        }
    }

    func setEnabled(ruleID: String, enabled: Bool) async throws {
        let context = makeContext()
        let now = nowMillis()
        try await context.perform {
            let rule = try Self.fetchRule(context: context, ruleID: ruleID)
            rule.setValue(NSNumber(value: enabled), forKey: "enabled")
            rule.setValue(now, forKey: "updated_at")
            try context.save()
        }
    }

    func delete(ruleID: String) async throws {
        let context = makeContext()
        try await context.perform {
            let rule = try Self.fetchRule(context: context, ruleID: ruleID)
            context.delete(rule)
            try context.save()
        }
    }

    private static func fetchRule(context: NSManagedObjectContext, ruleID: String) throws -> NSManagedObject {
        let request = NSFetchRequest<NSManagedObject>(entityName: "HolidaySchedule")
        request.predicate = NSPredicate(format: "id == %@", ruleID)
        request.fetchLimit = 1
        guard let rule = try context.fetch(request).first else {
            throw PersistenceError.dbConstraintViolation
        }
        return rule
    }

    private static func fetchFrame(context: NSManagedObjectContext, frameID: String) throws -> NSManagedObject {
        let request = NSFetchRequest<NSManagedObject>(entityName: "Frame")
        request.predicate = NSPredicate(format: "id == %@", frameID)
        request.fetchLimit = 1
        guard let frame = try context.fetch(request).first else {
            throw PersistenceError.dbConstraintViolation
        }
        return frame
    }

    private static func rule(from object: NSManagedObject) -> HolidayRule {
        HolidayRule(
            id: object.value(forKey: "id") as? String ?? "",
            frameID: object.value(forKeyPath: "frame.id") as? String ?? "",
            name: object.value(forKey: "name") as? String ?? "",
            startDate: object.value(forKey: "start_date") as? String ?? "",
            endDate: object.value(forKey: "end_date") as? String ?? "",
            startTime: object.value(forKey: "start_time") as? Int,
            endTime: object.value(forKey: "end_time") as? Int,
            position: object.value(forKey: "position") as? Int ?? 0,
            enabled: object.value(forKey: "enabled") as? Bool ?? true
        )
    }
}
