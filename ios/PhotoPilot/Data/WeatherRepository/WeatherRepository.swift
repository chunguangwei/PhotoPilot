import CoreData
import Foundation

struct WeatherSnapshot: Sendable, Equatable {
    let placeId: String
    let conditionCode: String
    let temperatureMilli: Int
    let observedAt: Int
    let fetchedAt: Int
    let expiresAt: Int
    let attributionText: String?
    let attributionURL: String?
}

struct WeatherPlace: Sendable, Equatable {
    let placeId: String
    let nameZh: String
    let nameEn: String
    let latE7: Int
    let lonE7: Int
    let region: String
}

enum WeatherFreshness: String, Sendable {
    case normal
    case delayed
    case unavailable
}

actor WeatherRepository {
    static let placesKey = "weather.places"
    static let enabledKey = "weather.enabled"
    static let unitsKey = "weather.units"
    static let selectedPlaceIdKey = "weather.selected_place_id"
    static let normalLimitMillis = 60 * 60 * 1000
    static let delayedLimitMillis = 180 * 60 * 1000

    private let controller: PersistenceController

    init(controller: PersistenceController) {
        self.controller = controller
    }

    private func makeContext() -> NSManagedObjectContext {
        controller.container.newBackgroundContext()
    }

    static func freshness(observedAtMillis: Int, nowMillis: Int) -> WeatherFreshness {
        let age = nowMillis - observedAtMillis
        if age <= normalLimitMillis { return .normal }
        if age <= delayedLimitMillis { return .delayed }
        return .unavailable
    }

    func snapshot(placeId: String) throws -> WeatherSnapshot? {
        let context = makeContext()
        return try context.performAndWait {
            let request = NSFetchRequest<NSManagedObject>(entityName: "WeatherSnapshot")
            request.predicate = NSPredicate(format: "place_id == %@", placeId)
            request.fetchLimit = 1
            guard let row = try context.fetch(request).first else { return nil }
            var text: String?
            var url: String?
            if let raw = row.value(forKey: "attribution") as? String,
               let data = raw.data(using: .utf8),
               let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: String] {
                text = parsed["text"]
                url = parsed["url"]
            }
            return WeatherSnapshot(
                placeId: placeId,
                conditionCode: row.value(forKey: "condition_code") as? String ?? "unknown",
                temperatureMilli: row.value(forKey: "temperature_milli") as? Int ?? 0,
                observedAt: row.value(forKey: "observed_at") as? Int ?? 0,
                fetchedAt: row.value(forKey: "fetched_at") as? Int ?? 0,
                expiresAt: row.value(forKey: "expires_at") as? Int ?? 0,
                attributionText: text,
                attributionURL: url
            )
        }
    }

    func saveSnapshot(_ snapshot: WeatherSnapshot) throws {
        let context = makeContext()
        let now = Int(Date().timeIntervalSince1970 * 1000)
        try context.performAndWait {
            let request = NSFetchRequest<NSManagedObject>(entityName: "WeatherSnapshot")
            request.predicate = NSPredicate(format: "place_id == %@", snapshot.placeId)
            request.fetchLimit = 1
            let row = try context.fetch(request).first ?? NSEntityDescription.insertNewObject(
                forEntityName: "WeatherSnapshot",
                into: context
            )
            row.setValue(snapshot.placeId, forKey: "place_id")
            row.setValue(snapshot.conditionCode, forKey: "condition_code")
            row.setValue(NSNumber(value: snapshot.temperatureMilli), forKey: "temperature_milli")
            row.setValue(NSNumber(value: snapshot.observedAt), forKey: "observed_at")
            row.setValue(NSNumber(value: now), forKey: "fetched_at")
            row.setValue(NSNumber(value: snapshot.expiresAt), forKey: "expires_at")
            if row.value(forKey: "created_at") == nil {
                row.setValue(NSNumber(value: now), forKey: "created_at")
            }
            row.setValue(NSNumber(value: now), forKey: "updated_at")
            if let text = snapshot.attributionText {
                let payload: [String: String] = [
                    "text": text,
                    "url": snapshot.attributionURL ?? "",
                ]
                if let data = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]) {
                    row.setValue(String(decoding: data, as: UTF8.self), forKey: "attribution")
                }
            } else {
                row.setValue(nil, forKey: "attribution")
            }
            try context.save()
        }
    }

    func places() throws -> [WeatherPlace] {
        let context = makeContext()
        return try context.performAndWait {
            let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
            request.predicate = NSPredicate(format: "key == %@", Self.placesKey)
            request.fetchLimit = 1
            guard let row = try context.fetch(request).first,
                  let raw = row.value(forKey: "value_json") as? String,
                  let data = raw.data(using: .utf8),
                  let map = try? JSONSerialization.jsonObject(with: data) as? [String: [String: Any]] else {
                return []
            }
            return map.map { placeId, entry in
                WeatherPlace(
                    placeId: placeId,
                    nameZh: entry["nameZh"] as? String ?? "",
                    nameEn: entry["nameEn"] as? String ?? "",
                    latE7: entry["latE7"] as? Int ?? 0,
                    lonE7: entry["lonE7"] as? Int ?? 0,
                    region: entry["region"] as? String ?? ""
                )
            }
            .sorted { $0.placeId < $1.placeId }
        }
    }

    func savePlace(_ place: WeatherPlace) throws {
        let context = makeContext()
        let now = Int(Date().timeIntervalSince1970 * 1000)
        try context.performAndWait {
            let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
            request.predicate = NSPredicate(format: "key == %@", Self.placesKey)
            request.fetchLimit = 1
            var map: [String: [String: Any]] = [:]
            if let row = try context.fetch(request).first,
               let raw = row.value(forKey: "value_json") as? String,
               let data = raw.data(using: .utf8),
               let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: [String: Any]] {
                map = parsed
            }
            map[place.placeId] = [
                "nameZh": place.nameZh,
                "nameEn": place.nameEn,
                "latE7": place.latE7,
                "lonE7": place.lonE7,
                "region": place.region,
            ]
            guard let data = try? JSONSerialization.data(withJSONObject: map, options: [.sortedKeys]) else {
                return
            }
            let row = try context.fetch(request).first ?? NSEntityDescription.insertNewObject(
                forEntityName: "SettingsKV",
                into: context
            )
            row.setValue(Self.placesKey, forKey: "key")
            row.setValue(String(decoding: data, as: UTF8.self), forKey: "value_json")
            if row.value(forKey: "created_at") == nil {
                row.setValue(NSNumber(value: now), forKey: "created_at")
            }
            row.setValue(NSNumber(value: now), forKey: "updated_at")
            let selectedRequest = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
            selectedRequest.predicate = NSPredicate(format: "key == %@", Self.selectedPlaceIdKey)
            selectedRequest.fetchLimit = 1
            let selectedRow = try context.fetch(selectedRequest).first ?? NSEntityDescription.insertNewObject(
                forEntityName: "SettingsKV",
                into: context
            )
            selectedRow.setValue(Self.selectedPlaceIdKey, forKey: "key")
            selectedRow.setValue(place.placeId, forKey: "value_json")
            if selectedRow.value(forKey: "created_at") == nil {
                selectedRow.setValue(NSNumber(value: now), forKey: "created_at")
            }
            selectedRow.setValue(NSNumber(value: now), forKey: "updated_at")
            try context.save()
        }
    }

    func selectedPlace() throws -> WeatherPlace? {
        let all = try places()
        guard !all.isEmpty else { return nil }
        let context = makeContext()
        let selectedID: String? = try context.performAndWait {
            let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
            request.predicate = NSPredicate(format: "key == %@", Self.selectedPlaceIdKey)
            request.fetchLimit = 1
            return try context.fetch(request).first?.value(forKey: "value_json") as? String
        }
        if let selectedID, let hit = all.first(where: { $0.placeId == selectedID }) {
            return hit
        }
        return all.last
    }
}
