import XCTest
import UIKit
@testable import PhotoPilot

final class WeatherLocationFollowTests: XCTestCase {
    func testFollowContractVectors() throws {
        let url = try XCTUnwrap(
            Bundle(for: type(of: self)).url(forResource: "weather-location-follow", withExtension: "json"),
            "向量文件未入测试 bundle：weather-location-follow.json"
        )
        let data = try Data(contentsOf: url)
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let cases = try XCTUnwrap(root["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 8)
        for item in cases {
            let id = try XCTUnwrap(item["id"] as? String)
            let storedFlag = item["stored_follow_location"] as? String
            let selectedPlaceID = item["selected_place_id"] as? String
            let follows = WeatherRepository.followsLocation(
                storedFlag: storedFlag,
                selectedPlaceID: selectedPlaceID
            )
            XCTAssertEqual(follows, try XCTUnwrap(item["follows_location"] as? Bool), id)
            let enabled = try XCTUnwrap(item["weather_enabled"] as? Bool)
            XCTAssertEqual(
                WeatherRepository.shouldLocateOnPlayback(weatherEnabled: enabled, followsLocation: follows),
                try XCTUnwrap(item["should_locate_on_playback"] as? Bool),
                id
            )
        }
    }

    func testPreserveContractVectors() throws {
        let url = try XCTUnwrap(
            Bundle(for: type(of: self)).url(forResource: "weather-location-preserve", withExtension: "json"),
            "向量文件未入测试 bundle：weather-location-preserve.json"
        )
        let data = try Data(contentsOf: url)
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let cases = try XCTUnwrap(root["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 3)
        for item in cases {
            let id = try XCTUnwrap(item["id"] as? String)
            let existing = (item["existing"] as? [String: Any]).map(place(from:))
            let candidate = place(from: try XCTUnwrap(item["candidate"] as? [String: Any]))
            let result = WeatherRepository.preserveLocatedPlace(existing: existing, candidate: candidate)
            XCTAssertEqual(result.placeId, item["result_place_id"] as? String, id)
            XCTAssertEqual(result.nameZh, item["result_name_zh"] as? String, id)
            XCTAssertEqual(result.nameEn, item["result_name_en"] as? String, id)
            XCTAssertEqual(result.latE7, item["result_lat_e7"] as? Int, id)
            XCTAssertEqual(result.lonE7, item["result_lon_e7"] as? Int, id)
        }
    }

    func testGlobalDisplayOrderingAndFallback() {
        let place = WeatherPlace(placeId: "geo:1,2", nameZh: "旧名称", nameEn: "Old", latE7: 1, lonE7: 2, region: "", displayZh: WeatherPlaceDisplay(locality: " 北京 ", subLocality: "海淀区"), displayEn: WeatherPlaceDisplay(locality: "Beijing", subLocality: "Haidian"))
        XCTAssertEqual(place.compactName(chinese: true), "北京 · 海淀区")
        XCTAssertEqual(place.compactName(chinese: false), "Haidian, Beijing")
        XCTAssertEqual(WeatherPlaceDisplay(subAdminArea: "County", adminArea: "State").compact(chinese: false), "County")
    }

    func testSameIdentityEnrichesWithoutReplacingRawDataOrDowngrading() {
        let old = WeatherPlace(placeId: "geo:1,2", nameZh: "旧", nameEn: "Old", latE7: 1, lonE7: 2, region: "raw", displayZh: WeatherPlaceDisplay(locality: "北京", subLocality: "海淀区"))
        let candidate = WeatherPlace(placeId: old.placeId, nameZh: "新", nameEn: "New", latE7: 3, lonE7: 4, region: "new", displayZh: WeatherPlaceDisplay(locality: "北京"), displayEn: WeatherPlaceDisplay(locality: "Beijing", subLocality: "Haidian"))
        let merged = WeatherRepository.preserveLocatedPlace(existing: old, candidate: candidate)
        XCTAssertEqual(merged.nameZh, old.nameZh)
        XCTAssertEqual(merged.latE7, old.latE7)
        XCTAssertEqual(merged.region, old.region)
        XCTAssertEqual(merged.compactName(chinese: true), "北京 · 海淀区")
        XCTAssertEqual(merged.compactName(chinese: false), "Haidian, Beijing")
    }

    func testEnglishOnlyCandidatesAndNamesakesRemainDistinct() {
        let first = WeatherPlaceCandidate(placeId: "om:1", name: "Springfield", region: "Illinois", latE7: 1, lonE7: 2)
        let second = WeatherPlaceCandidate(placeId: "om:2", name: "Springfield", region: "Queensland", latE7: 3, lonE7: 4)
        let places = WeatherPlace.mergeCandidates(zh: [], en: [first, second])
        XCTAssertEqual(places.map(\.placeId), ["om:1", "om:2"])
        XCTAssertEqual(places.map(\.region), ["Illinois", "Queensland"])
    }

    func testStoredDisplaySurvivesRepositoryRoundTrip() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let controller = PersistenceController(storeURL: directory.appendingPathComponent("library.db"))
        let settings = AppSettingsRepository(container: controller.container)
        let raw = #"{"geo:1,2":{"nameZh":"旧","nameEn":"Old","latE7":1,"lonE7":2,"region":"","displayZh":{"locality":"北京","subLocality":"海淀区"}}}"#
        try settings.setValue(raw, forKey: WeatherRepository.placesKey)
        let repository = WeatherRepository(controller: controller)
        let decoded = try await repository.places()
        try await repository.savePlace(XCTUnwrap(decoded.first))
        controller.container.viewContext.refreshAllObjects()
        let stored = try XCTUnwrap(settings.value(forKey: WeatherRepository.placesKey))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(stored.utf8)) as? [String: [String: Any]])
        let display = object["geo:1,2"]?["displayZh"] as? [String: String]
        XCTAssertEqual(display?["subLocality"], "海淀区")
    }

    func testSharedGlobalDisplayVectors() throws {
        let url = try XCTUnwrap(Bundle(for: type(of: self)).url(forResource: "weather-place-display", withExtension: "json"))
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        for item in try XCTUnwrap(object["cases"] as? [[String: Any]]) {
            let fields = try XCTUnwrap(item["fields"])
            let display = try JSONDecoder().decode(WeatherPlaceDisplay.self, from: JSONSerialization.data(withJSONObject: fields))
            XCTAssertEqual(display.compact(chinese: true), item["zh"] as? String, item["id"] as? String ?? "")
            XCTAssertEqual(display.compact(chinese: false), item["en"] as? String, item["id"] as? String ?? "")
            XCTAssertEqual(display.context, item["context"] as? String, item["id"] as? String ?? "")
        }
    }

    @MainActor
    func testInlineWeatherKeepsConditionsVisibleWithLongRTLAndLargeFont() {
        for category in [UIContentSizeCategory.large, .accessibilityExtraExtraExtraLarge] {
        for width in [320.0, 375.0, 1024.0] {
            let weather = WeatherTextView()
            weather.backgroundColor = .white
            weather.textColor = .black
            weather.font = UIFont.preferredFont(forTextStyle: .body, compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
            weather.setPlace(String(repeating: "ديرة, دبي · Reykjavík ", count: 12))
            weather.text = "24°C Clear"
            weather.frame = CGRect(x: 0, y: 0, width: width, height: 500)
            weather.layoutIfNeeded()
            XCTAssertEqual(weather.placeLabel.numberOfLines, 1)
            XCTAssertGreaterThan(weather.conditionLabel.frame.height, 0)
            XCTAssertLessThanOrEqual(weather.conditionLabel.frame.maxX, width + 1)
            XCTAssertEqual(weather.conditionLabel.frame.midY, weather.placeLabel.frame.midY, accuracy: 1)
            XCTAssertLessThanOrEqual(weather.placeLabel.frame.maxX, weather.conditionLabel.frame.minX)
            XCTAssertGreaterThanOrEqual(weather.placeLabel.bounds.width, width * 0.3)
            XCTAssertLessThanOrEqual(weather.conditionLabel.frame.maxY, weather.bounds.height)
            let attachment = XCTAttachment(image: UIGraphicsImageRenderer(bounds: weather.bounds).image { weather.layer.render(in: $0.cgContext) })
            attachment.name = "weather-\(Int(width))-\(category.rawValue)"
            attachment.lifetime = .keepAlways
            add(attachment)
            XCTAssertTrue(weather.accessibilityLabel?.contains("24°C Clear") == true)
            XCTAssertTrue(weather.accessibilityLabel?.contains("Reykjavík") == true)
        }
        }
    }

    @MainActor
    func testInlineWeatherReservesCompleteTemperatureAtLargestText() {
        let weather = WeatherTextView()
        weather.font = UIFont.preferredFont(forTextStyle: .body, compatibleWith: UITraitCollection(preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge))
        weather.setPlace(String(repeating: "Reykjavík · ديرة ", count: 10))
        weather.text = "−148°F Thunderstorm"
        weather.frame = CGRect(x: 0, y: 0, width: 288, height: 100)
        weather.layoutIfNeeded()
        let minimum = ("−148°F" as NSString).size(withAttributes: [.font: weather.font!]).width
        XCTAssertGreaterThanOrEqual(weather.conditionLabel.bounds.width, minimum)
        XCTAssertGreaterThan(weather.placeLabel.bounds.width, 20)
        XCTAssertEqual(weather.conditionLabel.frame.midY, weather.placeLabel.frame.midY, accuracy: 1)
        XCTAssertLessThanOrEqual(weather.conditionLabel.frame.maxX, weather.bounds.width)
        XCTAssertEqual(weather.conditionLabel.numberOfLines, 1)
        XCTAssertTrue(weather.accessibilityLabel?.contains("Thunderstorm") == true)
    }

    @MainActor
    func testInlineWeatherOmitsDescriptionAndIconBeforeTruncatingTemperature() throws {
        let weather = WeatherTextView()
        weather.font = UIFont.preferredFont(forTextStyle: .body, compatibleWith: UITraitCollection(preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge))
        weather.setPlace("北京 · 海淀区")
        let icon = NSTextAttachment()
        icon.image = try XCTUnwrap(UIImage(systemName: "cloud.sun.fill", withConfiguration: UIImage.SymbolConfiguration(font: weather.font)))
        let content = NSMutableAttributedString(attachment: icon)
        content.append(NSAttributedString(string: " −148°F Thunderstorm"))
        weather.attributedText = content
        weather.frame = CGRect(x: 0, y: 0, width: 288, height: 100)
        weather.layoutIfNeeded()
        XCTAssertEqual(weather.conditionLabel.attributedText?.string, "−148°F")
        XCTAssertGreaterThanOrEqual(weather.conditionLabel.bounds.width, ("−148°F" as NSString).size(withAttributes: [.font: weather.font!]).width)
        XCTAssertGreaterThanOrEqual(weather.placeLabel.bounds.width, 44)
        XCTAssertEqual(weather.attributedText?.string, content.string)
        XCTAssertTrue(weather.accessibilityLabel?.contains("Thunderstorm") == true)
    }

    @MainActor
    func testInlineWeatherCentersCompleteShortTextOnWideScreens() {
        let weather = WeatherTextView()
        weather.font = .systemFont(ofSize: 17)
        weather.textAlignment = .center
        weather.setPlace("北京 · 海淀区")
        weather.text = "24°C 晴"
        weather.frame = CGRect(x: 0, y: 0, width: 1024, height: 60)
        weather.layoutIfNeeded()
        XCTAssertGreaterThanOrEqual(weather.placeLabel.bounds.width, weather.placeLabel.intrinsicContentSize.width)
        XCTAssertGreaterThanOrEqual(weather.conditionLabel.bounds.width, weather.conditionLabel.intrinsicContentSize.width)
        XCTAssertEqual((weather.placeLabel.frame.minX + weather.conditionLabel.frame.maxX) / 2, 512, accuracy: 1)
        weather.setPlace("")
        weather.layoutIfNeeded()
        XCTAssertTrue(weather.placeLabel.isHidden)
        XCTAssertEqual(weather.conditionLabel.frame.midX, 512, accuracy: 1)
    }

    func testSparseMetadataCannotReplaceLegacyFriendlyName() {
        let old = WeatherPlace(placeId: "geo:1,2", nameZh: "北京", nameEn: "Beijing", latE7: 1, lonE7: 2, region: "")
        let candidate = WeatherPlace(placeId: old.placeId, nameZh: "中国", nameEn: "China", latE7: 1, lonE7: 2, region: "", displayZh: WeatherPlaceDisplay(country: "中国"), displayEn: WeatherPlaceDisplay(country: "China"))
        let merged = WeatherRepository.preserveLocatedPlace(existing: old, candidate: candidate)
        XCTAssertEqual(merged.compactName(chinese: true), "北京")
        XCTAssertEqual(merged.compactName(chinese: false), "Beijing")
    }

    func testAutomaticSaveRejectsChangedSelectionAndFollowMode() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let controller = PersistenceController(storeURL: directory.appendingPathComponent("library.db"))
        let repository = WeatherRepository(controller: controller)
        let old = WeatherPlace(placeId: "geo:1,2", nameZh: "北京", nameEn: "Beijing", latE7: 1, lonE7: 2, region: "")
        let manual = WeatherPlace(placeId: "om:3", nameZh: "巴黎", nameEn: "Paris", latE7: 3, lonE7: 4, region: "")
        try await repository.savePlace(old, followLocation: true)
        try await repository.savePlace(manual, followLocation: false)
        let saved = try await repository.saveAutomaticPlace(old, expectedSelectedID: old.placeId)
        XCTAssertFalse(saved)
        let selected = try await repository.selectedPlace()
        XCTAssertEqual(selected, manual)
        try await repository.savePlace(manual, followLocation: true)
        let changed = try await repository.saveAutomaticPlace(old, expectedSelectedID: old.placeId)
        XCTAssertFalse(changed)
    }

    func testEmptyPreferredContextFallsBackToOtherLanguage() {
        let place = WeatherPlace(placeId: "om:1", nameZh: "北京", nameEn: "Beijing", latE7: 1, lonE7: 2, region: "", displayZh: WeatherPlaceDisplay(locality: "北京", country: "中国"), displayEn: WeatherPlaceDisplay(locality: "Beijing"))
        XCTAssertEqual(place.context(chinese: false), "中国")
    }

    func testSameIDManualReselectPreservesBilingualMetadata() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let controller = PersistenceController(storeURL: directory.appendingPathComponent("library.db"))
        let repository = WeatherRepository(controller: controller)
        let old = WeatherPlace(placeId: "om:1", nameZh: "北京", nameEn: "Beijing", latE7: 1, lonE7: 2, region: "", displayZh: WeatherPlaceDisplay(locality: "北京", subLocality: "海淀区"), displayEn: WeatherPlaceDisplay(locality: "Beijing", subLocality: "Haidian"))
        try await repository.savePlace(old, followLocation: false)
        let partial = WeatherPlace(placeId: old.placeId, nameZh: "北京", nameEn: "北京", latE7: 3, lonE7: 4, region: "", displayZh: WeatherPlaceDisplay(locality: "北京"))
        try await repository.savePlace(partial, followLocation: false)
        let stored = try await repository.selectedPlace()
        let selected = try XCTUnwrap(stored)
        XCTAssertEqual(selected, old)
    }

    func testCoarseGeocoderIDsNeverPairDifferentVillageTranslations() {
        let zh = WeatherPlaceCandidate(placeId: "geo:1,2", name: "东村", region: "省", latE7: 1, lonE7: 2, display: WeatherPlaceDisplay(locality: "东村"))
        let en = WeatherPlaceCandidate(placeId: "geo:1,2", name: "West Village", region: "State", latE7: 1, lonE7: 2, display: WeatherPlaceDisplay(locality: "West Village"))
        let merged = WeatherPlace.mergeCandidates(zh: [zh], en: [en])
        XCTAssertEqual(merged.first?.nameEn, "东村")
        XCTAssertNil(merged.first?.displayEn)
    }

    @MainActor
    func testReturnSearchCannotReplaceNewQueryWithDelayedResults() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let started = expectation(description: "Old bilingual search began")
        started.expectedFulfillmentCount = 2
        let provider = DelayedWeatherSearchProvider(started: started)
        let environment = AppEnvironment(persistence: PersistenceController(storeURL: directory.appendingPathComponent("library.db")), weatherProvider: provider)
        let picker = WeatherCityPickerViewController(environment: environment, onPick: { _ in })
        picker.loadViewIfNeeded()
        let field = try XCTUnwrap(picker.view.subviews.compactMap { $0 as? UITextField }.first)
        let table = try XCTUnwrap(picker.view.subviews.compactMap { $0 as? UITableView }.first)
        field.text = "old"
        _ = picker.textFieldShouldReturn(field)
        await fulfillment(of: [started], timeout: 2)
        field.text = "new"
        field.sendActions(for: .editingChanged)
        for _ in 0..<100 where table.numberOfRows(inSection: 0) == 0 {
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTAssertEqual(table.numberOfRows(inSection: 0), 1)
        await provider.releaseOld()
        try await Task.sleep(nanoseconds: 100_000_000)
        let cell = picker.tableView(table, cellForRowAt: IndexPath(row: 0, section: 0))
        let content = try XCTUnwrap(cell.contentConfiguration as? UIListContentConfiguration)
        XCTAssertEqual(content.text, "New City")
    }

    @MainActor
    func testRestWeatherConditionsStayInsideShortLandscapeAtLargestText() async throws {
        for size in [CGSize(width: 667, height: 320), CGSize(width: 375, height: 667), CGSize(width: 1024, height: 744)] {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            let environment = AppEnvironment(persistence: PersistenceController(storeURL: directory.appendingPathComponent("library.db")))
            let frameID = try await environment.frameManager.createFrame(name: "Layout")
            let player = PlayerViewController(environment: environment, frameID: frameID)
            let host = UIViewController()
            host.addChild(player)
            host.setOverrideTraitCollection(UITraitCollection(preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge), forChild: player)
            host.view.addSubview(player.view)
            player.view.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                player.view.leadingAnchor.constraint(equalTo: host.view.leadingAnchor),
                player.view.trailingAnchor.constraint(equalTo: host.view.trailingAnchor),
                player.view.topAnchor.constraint(equalTo: host.view.topAnchor),
                player.view.bottomAnchor.constraint(equalTo: host.view.bottomAnchor),
            ])
            player.didMove(toParent: host)
            defer { PlayerViewController.dismissActiveIfAny() }
            host.view.frame = CGRect(origin: .zero, size: size)
            host.view.layoutIfNeeded()
            let values = Mirror(reflecting: player).children
            let rest = try XCTUnwrap(values.first { $0.label == "restingScreen" }?.value as? UIView)
            let weather = try XCTUnwrap(values.first { $0.label == "restWeatherLabel" }?.value as? WeatherTextView)
            let date = try XCTUnwrap(values.first { $0.label == "restDateLabel" }?.value as? UILabel)
            let clock = try XCTUnwrap(values.first { $0.label == "flipClock" }?.value as? FlipClockView)
            rest.isHidden = false
            weather.isHidden = false
            let largest = UITraitCollection(preferredContentSizeCategory: .accessibilityExtraExtraExtraLarge)
            weather.adjustsFontForContentSizeCategory = false
            date.adjustsFontForContentSizeCategory = false
            weather.font = UIFont.preferredFont(forTextStyle: .body, compatibleWith: largest)
            date.font = UIFont.preferredFont(forTextStyle: .title2, compatibleWith: largest)
            XCTAssertGreaterThanOrEqual(weather.font.pointSize, 50)
            XCTAssertGreaterThanOrEqual(date.font.pointSize, 50)
            weather.setPlace(String(repeating: "ديرة, دبي · Reykjavík ", count: 12))
            weather.text = "24°C Clear"
            date.text = "Wednesday, October 20, 2026"
            clock.setDate(Date(timeIntervalSince1970: 1_792_483_200))
            player.view.layoutIfNeeded()
            XCTAssertGreaterThanOrEqual(clock.bounds.height, 48)
            let rect = weather.conditionLabel.convert(weather.conditionLabel.bounds, to: player.view)
            let safe = player.view.safeAreaLayoutGuide.layoutFrame
            XCTAssertGreaterThan(rect.height, 0)
            XCTAssertGreaterThanOrEqual(rect.minY, safe.minY, "\(size)")
            XCTAssertLessThanOrEqual(rect.maxY, safe.maxY, "\(size)")
            let attachment = XCTAttachment(image: UIGraphicsImageRenderer(bounds: player.view.bounds).image { player.view.layer.render(in: $0.cgContext) })
            attachment.name = "rest-AX-\(Int(size.width))x\(Int(size.height))"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
    }

    func testNativeSearchIdentityDistinguishesCitiesInsideOneWeatherTile() {
        let first = OpenMeteoProvider.coarsenE7(latitude: 40.7674, longitude: -74.0323)
        let second = OpenMeteoProvider.coarsenE7(latitude: 40.7879, longitude: -74.0143)
        XCTAssertEqual(first.latE7, second.latE7)
        XCTAssertEqual(first.lonE7, second.lonE7)
        let unionCity = WeatherPlaceDisplay(locality: "Union City", adminArea: "New Jersey", country: "United States")
        let westNewYork = WeatherPlaceDisplay(locality: "West New York", adminArea: "New Jersey", country: "United States")
        let firstID = SystemGeocodingProvider.placeID(display: unionCity, language: "en", latE7: first.latE7, lonE7: first.lonE7)
        let secondID = SystemGeocodingProvider.placeID(display: westNewYork, language: "en", latE7: second.latE7, lonE7: second.lonE7)
        XCTAssertNotEqual(firstID, secondID)
        XCTAssertEqual(firstID, SystemGeocodingProvider.placeID(display: unionCity, language: "en", latE7: first.latE7, lonE7: first.lonE7))
    }

    func testNativeIdentityKeepsStructuredFieldBoundaries() {
        let first = WeatherPlaceDisplay(locality: "a\u{001F}b", subLocality: "c")
        let second = WeatherPlaceDisplay(locality: "a", subLocality: "b\u{001F}c")
        XCTAssertNotEqual(SystemGeocodingProvider.placeID(display: first, language: "en", latE7: 1, lonE7: 2), SystemGeocodingProvider.placeID(display: second, language: "en", latE7: 1, lonE7: 2))
    }

    func testPartialSettlementMetadataCannotInventHierarchy() {
        let district = WeatherPlaceDisplay(subLocality: "Haidian")
        for candidate in [WeatherPlaceDisplay(locality: "Beijing"), WeatherPlaceDisplay(locality: "Beijing", subLocality: "Xicheng")] {
            XCTAssertEqual(district.enriching(with: candidate), district)
        }
        let city = WeatherPlaceDisplay(locality: "Beijing")
        for candidate in [WeatherPlaceDisplay(subLocality: "Haidian"), WeatherPlaceDisplay(locality: "Shanghai", subLocality: "Haidian")] {
            XCTAssertEqual(city.enriching(with: candidate), city)
        }
        XCTAssertEqual(city.enriching(with: WeatherPlaceDisplay(locality: "Beijing", subLocality: "Haidian")).compact(chinese: false), "Haidian, Beijing")
        XCTAssertEqual(district.enriching(with: WeatherPlaceDisplay(locality: "Beijing", subLocality: "Haidian")).compact(chinese: false), "Haidian, Beijing")
    }

    private func place(from dict: [String: Any]) -> WeatherPlace {
        WeatherPlace(
            placeId: dict["place_id"] as? String ?? "",
            nameZh: dict["name_zh"] as? String ?? "",
            nameEn: dict["name_en"] as? String ?? "",
            latE7: dict["lat_e7"] as? Int ?? 0,
            lonE7: dict["lon_e7"] as? Int ?? 0,
            region: ""
        )
    }
}

private actor DelayedWeatherSearchProvider: WeatherProvider {
    let started: XCTestExpectation
    private var pending: [CheckedContinuation<[WeatherPlaceCandidate], Never>] = []

    init(started: XCTestExpectation) { self.started = started }

    func fetchCurrent(latE7: Int, lonE7: Int) async throws -> WeatherSnapshot {
        throw WeatherFetchError.unavailable
    }

    func searchPlaces(query: String, language: String) async throws -> [WeatherPlaceCandidate] {
        if query == "old" {
            return await withCheckedContinuation { continuation in
                pending.append(continuation)
                started.fulfill()
            }
        }
        return [WeatherPlaceCandidate(placeId: "om:2", name: "New City", region: "Country", latE7: 3, lonE7: 4)]
    }

    func releaseOld() {
        let result = [WeatherPlaceCandidate(placeId: "om:1", name: "Old City", region: "Country", latE7: 1, lonE7: 2)]
        pending.forEach { $0.resume(returning: result) }
        pending.removeAll()
    }
}
