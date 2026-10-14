import XCTest
@testable import PhotoPilot

final class HolidayTimingTests: XCTestCase {

    private func loadVector() throws -> [String: Any] {
        let url = try XCTUnwrap(
            Bundle(for: type(of: self)).url(forResource: "holiday-windows", withExtension: "json"),
            "向量文件未入测试 bundle：holiday-windows.json"
        )
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func rule(_ json: [String: Any], id: String = "rule") throws -> HolidayRule {
        func time(_ key: String) -> Int? {
            (json[key] as? NSNumber)?.intValue
        }
        return HolidayRule(
            id: (json["id"] as? String) ?? id,
            frameID: "frame",
            name: "case",
            startDate: try XCTUnwrap(json["start_date"] as? String),
            endDate: try XCTUnwrap(json["end_date"] as? String),
            startTime: time("start_time"),
            endTime: time("end_time"),
            position: (json["position"] as? NSNumber)?.intValue ?? 0,
            enabled: (json["enabled"] as? NSNumber)?.intValue ?? 1 != 0
        )
    }

    func testHolidayContractVectors() throws {
        let vector = try loadVector()
        let cases = try XCTUnwrap(vector["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 30)
        for vectorCase in cases {
            let id = try XCTUnwrap(vectorCase["id"] as? String)
            let scenario = try XCTUnwrap(vectorCase["scenario"] as? String)
            switch scenario {
            case "active":
                let rule = try rule(try XCTUnwrap(vectorCase["rule"] as? [String: Any]))
                let minute = try XCTUnwrap((vectorCase["minute"] as? NSNumber)?.intValue)
                let date = try XCTUnwrap(vectorCase["date"] as? String)
                let expected = try XCTUnwrap(vectorCase["expected"] as? Bool)
                XCTAssertEqual(
                    HolidayTiming.isActive(rule: rule, date: date, minute: minute),
                    expected,
                    id
                )
            case "pick":
                let jsons = try XCTUnwrap(vectorCase["rules"] as? [[String: Any]])
                let rules = try jsons.map { try rule($0) }
                let minute = try XCTUnwrap((vectorCase["minute"] as? NSNumber)?.intValue)
                let date = try XCTUnwrap(vectorCase["date"] as? String)
                XCTAssertEqual(
                    HolidayTiming.pick(rules: rules, date: date, minute: minute),
                    vectorCase["expected"] as? String,
                    id
                )
            case "overlap":
                let a = try rule(try XCTUnwrap(vectorCase["a"] as? [String: Any]), id: "a")
                let b = try rule(try XCTUnwrap(vectorCase["b"] as? [String: Any]), id: "b")
                let expected = try XCTUnwrap(vectorCase["expected"] as? Bool)
                XCTAssertEqual(HolidayTiming.overlap(a: a, b: b), expected, id)
                XCTAssertEqual(HolidayTiming.overlap(a: b, b: a), expected, id)
            default:
                XCTFail("未知场景：\(id) \(scenario)")
            }
        }
    }
}
