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
}
