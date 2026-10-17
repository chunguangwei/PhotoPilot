import CoreData
import Foundation

enum PermanentIDHook {
    static func install(on context: NSManagedObjectContext) {
        NotificationCenter.default.addObserver(
            forName: Notification.Name("NSManagedObjectContextWillSaveNotification"),
            object: context,
            queue: nil
        ) { notification in
            guard let context = notification.object as? NSManagedObjectContext else { return }
            let inserted = context.insertedObjects
            guard !inserted.isEmpty else { return }
            try? context.obtainPermanentIDs(for: Array(inserted))
        }
    }
}

final class PhotoPilotContainer: NSPersistentContainer {
    override func newBackgroundContext() -> NSManagedObjectContext {
        let context = super.newBackgroundContext()
        PermanentIDHook.install(on: context)
        return context
    }
}

final class PersistenceController: @unchecked Sendable {
    let container: NSPersistentContainer
    let storeURL: URL
    let didRecreateStore: Bool
    let recoveredBackupURL: URL?

    static let libraryVersionKey = "library_version"
    static let schemaVersionKey = "schema_version"

    init(storeURL: URL? = nil, excludedFromBackup: Bool = true) {
        let model = PersistenceSchema.makeModel()
        let resolvedStoreURL = storeURL ?? PersistenceController.defaultStoreURL()
        self.storeURL = resolvedStoreURL

        var directory = resolvedStoreURL.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        if excludedFromBackup {
            var resourceValues = URLResourceValues()
            resourceValues.isExcludedFromBackup = true
            try? directory.setResourceValues(resourceValues)
        }

        let storeDescription = Self.makeStoreDescription(url: resolvedStoreURL)
        let loaded = Self.loadContainer(
            model: model,
            storeURL: resolvedStoreURL,
            storeDescription: storeDescription
        )
        self.container = loaded.container
        self.didRecreateStore = loaded.didRecreateStore
        self.recoveredBackupURL = loaded.backupURL
        if let loadedStoreURL = loaded.container.persistentStoreCoordinator
            .persistentStores.first?.url {
            _ = ContractIndexes.apply(storeURL: loadedStoreURL, model: model)
        }
        container.viewContext.automaticallyMergesChangesFromParent = true
        PermanentIDHook.install(on: container.viewContext)
    }

    static func defaultStoreURL() -> URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        return support.appendingPathComponent("PhotoPilot", isDirectory: true)
            .appendingPathComponent("library.db")
    }

    static func temporaryDirectoryURL() -> URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
            .appendingPathComponent("tmp", isDirectory: true)
    }

    private static func makeStoreDescription(url: URL) -> NSPersistentStoreDescription {
        let description = NSPersistentStoreDescription(url: url)
        description.type = NSSQLiteStoreType
        description.setOption(true as NSNumber, forKey: NSMigratePersistentStoresAutomaticallyOption)
        description.setOption(true as NSNumber, forKey: NSInferMappingModelAutomaticallyOption)
        description.setOption(["journal_mode": "WAL"] as NSDictionary, forKey: NSSQLitePragmasOption)
        return description
    }

    private static func loadContainer(
        model: NSManagedObjectModel,
        storeURL: URL,
        storeDescription: NSPersistentStoreDescription
    ) -> (container: NSPersistentContainer, didRecreateStore: Bool, backupURL: URL?) {
        let container = PhotoPilotContainer(name: "PhotoPilot", managedObjectModel: model)
        container.persistentStoreDescriptions = [storeDescription]
        do {
            try loadStoresSynchronously(container)
            return (container, false, nil)
        } catch {
            let backupURL = backupStore(storeURL)
            let freshURL = storeURL.deletingLastPathComponent()
                .appendingPathComponent("library.fresh.db")
            let freshContainer = PhotoPilotContainer(name: "PhotoPilot", managedObjectModel: model)
            freshContainer.persistentStoreDescriptions = [makeStoreDescription(url: freshURL)]
            try? loadStoresSynchronously(freshContainer)
            return (freshContainer, true, backupURL)
        }
    }

    private static func loadStoresSynchronously(_ container: NSPersistentContainer) throws {
        final class Box: @unchecked Sendable {
            var result: Result<Void, Error>?
        }
        let box = Box()
        let semaphore = DispatchSemaphore(value: 0)
        container.loadPersistentStores { _, error in
            if let error {
                box.result = .failure(error)
            } else {
                box.result = .success(())
            }
            semaphore.signal()
        }
        semaphore.wait()
        try box.result?.get()
    }

    @discardableResult
    static func backupStore(_ storeURL: URL) -> URL? {
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: storeURL.path) else { return nil }
        let version = (try? schemaVersionValue(inStoreAt: storeURL)) ?? PersistenceSchema.version
        var backupURL = storeURL.deletingLastPathComponent()
            .appendingPathComponent("library.db.bak-\(version)")
        if fileManager.fileExists(atPath: backupURL.path) {
            backupURL = storeURL.deletingLastPathComponent()
                .appendingPathComponent("library.db.bak-\(version)-\(UUID().uuidString)")
        }
        do {
            try fileManager.moveItem(at: storeURL, to: backupURL)
        } catch {
            try? fileManager.removeItem(at: storeURL)
        }
        for suffix in ["-wal", "-shm"] {
            let sidecar = URL(fileURLWithPath: storeURL.path + suffix)
            guard fileManager.fileExists(atPath: sidecar.path) else { continue }
            try? fileManager.moveItem(
                at: sidecar,
                to: URL(fileURLWithPath: backupURL.path + suffix)
            )
        }
        return backupURL
    }

    private static func schemaVersionValue(inStoreAt storeURL: URL) throws -> Int {
        let coordinator = NSPersistentStoreCoordinator(managedObjectModel: PersistenceSchema.makeModel())
        _ = try coordinator.addPersistentStore(
            ofType: NSSQLiteStoreType,
            configurationName: nil,
            at: storeURL,
            options: [NSReadOnlyPersistentStoreOption: true]
        )
        let context = NSManagedObjectContext(concurrencyType: .mainQueueConcurrencyType)
        context.persistentStoreCoordinator = coordinator
        let request = NSFetchRequest<NSManagedObject>(entityName: "SchemaMeta")
        request.predicate = NSPredicate(format: "key == %@", schemaVersionKey)
        request.fetchLimit = 1
        guard let row = try context.fetch(request).first,
              let value = row.value(forKey: "value") as? String,
              let version = Int(value) else {
            throw PersistenceError.dbMigrationFailed
        }
        return version
    }

    @discardableResult
    func bumpLibraryVersion() throws -> Int {
        let context = container.viewContext
        let newVersion = try Self.bumpLibraryVersionValue(in: context)
        if context.hasChanges {
            try context.save()
        }
        return newVersion
    }

    @discardableResult
    static func bumpLibraryVersionValue(in context: NSManagedObjectContext) throws -> Int {
        try storeKVValue(
            context: context,
            entityName: "SettingsKV",
            key: libraryVersionKey,
            read: { ($0.value(forKey: "value_json") as? String).flatMap(Int.init) ?? 0 },
            write: { $0.setValue("\($1)", forKey: "value_json") }
        )
    }

    func schemaVersion() throws -> Int? {
        let context = container.viewContext
        let request = NSFetchRequest<NSManagedObject>(entityName: "SchemaMeta")
        request.predicate = NSPredicate(format: "key == %@", Self.schemaVersionKey)
        request.fetchLimit = 1
        guard let row = try context.fetch(request).first else { return nil }
        return (row.value(forKey: "value") as? String).flatMap(Int.init)
    }

    func setSchemaVersion(_ version: Int) throws {
        let context = container.viewContext
        let request = NSFetchRequest<NSManagedObject>(entityName: "SchemaMeta")
        request.predicate = NSPredicate(format: "key == %@", Self.schemaVersionKey)
        request.fetchLimit = 1
        let now = Int(Date().timeIntervalSince1970 * 1000)
        if let row = try context.fetch(request).first {
            row.setValue("\(version)", forKey: "value")
            row.setValue(now, forKey: "updated_at")
        } else {
            guard let entity = context.persistentStoreCoordinator?
                .managedObjectModel.entitiesByName["SchemaMeta"] else {
                throw PersistenceError.dbConstraintViolation
            }
            let created = NSManagedObject(entity: entity, insertInto: context)
            created.setValue(Self.schemaVersionKey, forKey: "key")
            created.setValue("\(version)", forKey: "value")
            created.setValue(now, forKey: "created_at")
            created.setValue(now, forKey: "updated_at")
        }
        if context.hasChanges {
            try context.save()
        }
    }

    private static func storeKVValue(
        context: NSManagedObjectContext,
        entityName: String,
        key: String,
        read: (NSManagedObject) -> Int,
        write: (NSManagedObject, Int) -> Void
    ) throws -> Int {
        let request = NSFetchRequest<NSManagedObject>(entityName: entityName)
        request.predicate = NSPredicate(format: "key == %@", key)
        request.fetchLimit = 1
        let row = try context.fetch(request).first
        let now = Int(Date().timeIntervalSince1970 * 1000)
        if let row {
            let next = read(row) + 1
            write(row, next)
            row.setValue(now, forKey: "updated_at")
            return next
        }
        guard let entity = context.persistentStoreCoordinator?
            .managedObjectModel.entitiesByName[entityName] else {
            throw PersistenceError.dbConstraintViolation
        }
        let created = NSManagedObject(entity: entity, insertInto: context)
        created.setValue(key, forKey: "key")
        write(created, 1)
        created.setValue(now, forKey: "created_at")
        created.setValue(now, forKey: "updated_at")
        return 1
    }

    func reconcileOrphanedPartFiles(activeItemIDs: Set<String>) throws -> Int {
        let directory = Self.temporaryDirectoryURL()
        guard let files = try? FileManager.default.contentsOfDirectory(atPath: directory.path) else {
            return 0
        }
        var removed = 0
        for file in files where file.hasSuffix(".part") {
            let itemID = file.replacingOccurrences(of: ".part", with: "")
            if !activeItemIDs.contains(itemID) {
                try FileManager.default.removeItem(at: directory.appendingPathComponent(file))
                removed += 1
            }
        }
        return removed
    }
}
