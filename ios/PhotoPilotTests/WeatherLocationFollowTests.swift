import XCTest
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
