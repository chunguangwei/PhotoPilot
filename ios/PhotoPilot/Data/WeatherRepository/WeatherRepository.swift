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

struct WeatherPlaceDisplay: Sendable, Equatable, Codable {
    var locality: String? = nil
    var subLocality: String? = nil
    var subAdminArea: String? = nil
    var adminArea: String? = nil
    var country: String? = nil

    static func clean(_ value: String?) -> String? {
        guard let text = value?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
        return text
    }

    var primary: String? {
        [locality, subLocality, subAdminArea, adminArea, country].compactMap(Self.clean).first
    }

    func compact(chinese: Bool) -> String {
        guard let primary else { return "" }
        guard Self.clean(locality) != nil, let secondary = Self.clean(subLocality),
              primary.caseInsensitiveCompare(secondary) != .orderedSame else { return primary }
        return chinese ? "\(primary) · \(secondary)" : "\(secondary), \(primary)"
    }

    var context: String {
        var seen = Set([primary, Self.clean(subLocality)].compactMap { $0?.lowercased() })
        return [subAdminArea, adminArea, country].compactMap(Self.clean).filter {
            seen.insert($0.lowercased()).inserted
        }.joined(separator: ", ")
    }

    func enriching(with newer: WeatherPlaceDisplay?) -> WeatherPlaceDisplay {
        guard let newer else { return self }
        let currentCity = Self.clean(locality)
        let currentDistrict = Self.clean(subLocality)
        let nextCity = Self.clean(newer.locality)
        let nextDistrict = Self.clean(newer.subLocality)
        let cityMatches = currentCity != nil && nextCity != nil && currentCity?.caseInsensitiveCompare(nextCity!) == .orderedSame
        let districtMatches = currentDistrict != nil && nextDistrict != nil && currentDistrict?.caseInsensitiveCompare(nextDistrict!) == .orderedSame
        if currentCity != nil, nextCity != nil, !cityMatches { return self }
        if currentDistrict != nil, nextDistrict != nil, !districtMatches { return self }
        if currentCity == nil, nextCity != nil, currentDistrict != nil, !districtMatches { return self }
        if currentDistrict == nil, nextDistrict != nil, currentCity != nil, !cityMatches { return self }
        return WeatherPlaceDisplay(locality: Self.clean(locality) ?? Self.clean(newer.locality),
                                   subLocality: Self.clean(subLocality) ?? Self.clean(newer.subLocality),
                                   subAdminArea: Self.clean(subAdminArea) ?? Self.clean(newer.subAdminArea),
                                   adminArea: Self.clean(adminArea) ?? Self.clean(newer.adminArea),
                                   country: Self.clean(country) ?? Self.clean(newer.country))
    }
}

struct WeatherPlace: Sendable, Equatable {
    let placeId: String
    let nameZh: String
    let nameEn: String
    let latE7: Int
    let lonE7: Int
    let region: String
    var displayZh: WeatherPlaceDisplay? = nil
    var displayEn: WeatherPlaceDisplay? = nil

    func compactName(chinese: Bool) -> String {
        let preferred = chinese ? displayZh : displayEn
        let fallback = chinese ? displayEn : displayZh
        return WeatherPlaceDisplay.clean(preferred?.compact(chinese: chinese))
            ?? WeatherPlaceDisplay.clean(fallback?.compact(chinese: chinese))
            ?? WeatherPlaceDisplay.clean(chinese ? nameZh : nameEn)
            ?? WeatherPlaceDisplay.clean(chinese ? nameEn : nameZh) ?? ""
    }

    func context(chinese: Bool) -> String {
        WeatherPlaceDisplay.clean((chinese ? displayZh : displayEn)?.context)
            ?? WeatherPlaceDisplay.clean((chinese ? displayEn : displayZh)?.context) ?? region
    }

    static func mergeCandidates(zh: [WeatherPlaceCandidate], en: [WeatherPlaceCandidate]) -> [WeatherPlace] {
        let zhByID = Dictionary(zh.map { ($0.placeId, $0) }, uniquingKeysWith: { first, _ in first })
        let enByID = Dictionary(en.map { ($0.placeId, $0) }, uniquingKeysWith: { first, _ in first })
        var seen = Set<String>()
        return (zh + en).filter { seen.insert($0.placeId).inserted }.map { candidate in
            let chinese = zhByID[candidate.placeId]
            let english = candidate.placeId.hasPrefix("om:") || chinese == nil ? enByID[candidate.placeId] : nil
            return WeatherPlace(placeId: candidate.placeId, nameZh: chinese?.name ?? candidate.name,
                                nameEn: english?.name ?? candidate.name, latE7: candidate.latE7,
                                lonE7: candidate.lonE7, region: candidate.region,
                                displayZh: chinese?.display, displayEn: english?.display)
        }
    }
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
    static let followLocationKey = "weather.follow_location"
    static let locationPlaceIdPrefix = "geo:"
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
                    region: entry["region"] as? String ?? "",
                    displayZh: Self.display(from: entry["displayZh"]),
                    displayEn: Self.display(from: entry["displayEn"])
                )
            }
            .sorted { $0.placeId < $1.placeId }
        }
    }

    func savePlace(_ place: WeatherPlace) throws {
        try savePlaceInternal(place, followLocation: nil)
    }

    private func savePlaceInternal(_ place: WeatherPlace, followLocation: Bool?) throws {
        let place = Self.preserveLocatedPlace(existing: try places().first { $0.placeId == place.placeId }, candidate: place)
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
            if let display = place.displayZh { map[place.placeId]?["displayZh"] = Self.dictionary(display) }
            if let display = place.displayEn { map[place.placeId]?["displayEn"] = Self.dictionary(display) }
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
            if let followLocation {
                let followRequest = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
                followRequest.predicate = NSPredicate(format: "key == %@", Self.followLocationKey)
                followRequest.fetchLimit = 1
                let followRow = try context.fetch(followRequest).first ?? NSEntityDescription.insertNewObject(
                    forEntityName: "SettingsKV",
                    into: context
                )
                followRow.setValue(Self.followLocationKey, forKey: "key")
                followRow.setValue(followLocation ? "true" : "false", forKey: "value_json")
                if followRow.value(forKey: "created_at") == nil {
                    followRow.setValue(NSNumber(value: now), forKey: "created_at")
                }
                followRow.setValue(NSNumber(value: now), forKey: "updated_at")
            }
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

    func savePlace(_ place: WeatherPlace, followLocation: Bool) throws {
        try savePlaceInternal(place, followLocation: followLocation)
    }

    static func preserveLocatedPlace(existing: WeatherPlace?, candidate: WeatherPlace) -> WeatherPlace {
        if var existing, existing.placeId == candidate.placeId {
            existing.displayZh = enrich(existing.displayZh, candidate.displayZh, rawName: existing.nameZh)
            existing.displayEn = enrich(existing.displayEn, candidate.displayEn, rawName: existing.nameEn)
            return existing
        }
        return candidate
    }

    private static func enrich(_ existing: WeatherPlaceDisplay?, _ candidate: WeatherPlaceDisplay?, rawName: String) -> WeatherPlaceDisplay? {
        if let existing { return existing.enriching(with: candidate) }
        guard let candidate else { return nil }
        if WeatherPlaceDisplay.clean(candidate.locality) != nil || WeatherPlaceDisplay.clean(candidate.subLocality) != nil {
            return candidate
        }
        guard let primary = candidate.primary, primary.caseInsensitiveCompare(rawName.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame else { return nil }
        return candidate
    }

    private static func display(from value: Any?) -> WeatherPlaceDisplay? {
        guard let value, JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value) else { return nil }
        return try? JSONDecoder().decode(WeatherPlaceDisplay.self, from: data)
    }

    private static func dictionary(_ display: WeatherPlaceDisplay) -> [String: Any] {
        guard let data = try? JSONEncoder().encode(display),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return object
    }

    func saveAutomaticPlace(_ candidate: WeatherPlace, expectedSelectedID: String?) throws -> Bool {
        guard try followsLocation(), try selectedPlace()?.placeId == expectedSelectedID else { return false }
        let existing = try places().first { $0.placeId == candidate.placeId }
        try savePlaceInternal(Self.preserveLocatedPlace(existing: existing, candidate: candidate), followLocation: nil)
        return true
    }

    static func followsLocation(storedFlag: String?, selectedPlaceID: String?) -> Bool {
        switch storedFlag {
        case "true":
            return true
        case "false":
            return false
        default:
            return selectedPlaceID?.hasPrefix(locationPlaceIdPrefix) ?? false
        }
    }

    static func shouldLocateOnPlayback(weatherEnabled: Bool, followsLocation: Bool) -> Bool {
        weatherEnabled && followsLocation
    }

    func followsLocation() throws -> Bool {
        let context = makeContext()
        let stored: String? = try context.performAndWait {
            let request = NSFetchRequest<NSManagedObject>(entityName: "SettingsKV")
            request.predicate = NSPredicate(format: "key == %@", Self.followLocationKey)
            request.fetchLimit = 1
            return try context.fetch(request).first?.value(forKey: "value_json") as? String
        }
        return Self.followsLocation(storedFlag: stored, selectedPlaceID: try selectedPlace()?.placeId)
    }
}
