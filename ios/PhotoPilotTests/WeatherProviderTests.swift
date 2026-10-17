import XCTest
@testable import PhotoPilot

final class WeatherProviderTests: XCTestCase {

    func testWttrParsesCurrentCondition() throws {
        let json = """
        {
          "current_condition": [{
            "temp_C": "16",
            "temp_F": "61",
            "weatherCode": "356",
            "weatherDesc": [{"value": "Moderate or heavy rain shower"}],
            "observation_time": "10:30 AM"
          }]
        }
        """
        let snapshot = try WttrProvider.parseSnapshot(
            data: Data(json.utf8),
            nowMillis: 1_760_000_000_000
        )
        XCTAssertEqual(snapshot.conditionCode, "heavy_rain")
        XCTAssertEqual(snapshot.temperatureMilli, 16_000)
        XCTAssertEqual(snapshot.observedAt, 1_760_000_000_000)
        XCTAssertEqual(snapshot.attributionText, "wttr.in")
        XCTAssertEqual(snapshot.attributionURL, "https://wttr.in/")
    }

    func testWttrRejectsMalformedPayload() {
        let json = "{\"current_condition\": [{}]}"
        XCTAssertThrowsError(
            try WttrProvider.parseSnapshot(data: Data(json.utf8), nowMillis: 0)
        ) { error in
            XCTAssertEqual(error as? WeatherFetchError, .unavailable)
        }
    }

    func testWttrConditionMapping() {
        XCTAssertEqual(WttrProvider.conditionCode(wwo: 113), "clear")
        XCTAssertEqual(WttrProvider.conditionCode(wwo: 116), "partly_cloudy")
        XCTAssertEqual(WttrProvider.conditionCode(wwo: 122), "cloudy")
        XCTAssertEqual(WttrProvider.conditionCode(wwo: 248), "fog")
        XCTAssertEqual(WttrProvider.conditionCode(wwo: 266), "drizzle")
        XCTAssertEqual(WttrProvider.conditionCode(wwo: 302), "rain")
        XCTAssertEqual(WttrProvider.conditionCode(wwo: 359), "heavy_rain")
        XCTAssertEqual(WttrProvider.conditionCode(wwo: 326), "snow")
        XCTAssertEqual(WttrProvider.conditionCode(wwo: 338), "heavy_snow")
        XCTAssertEqual(WttrProvider.conditionCode(wwo: 389), "thunderstorm")
        XCTAssertEqual(WttrProvider.conditionCode(wwo: 999), "unknown")
    }

    func testFallbackForecastUsesSecondaryWhenPrimaryFails() async throws {
        let chain = FallbackWeatherProvider(
            forecast: [FailingWeatherProvider(), StubWeatherProvider(conditionCode: "clear")],
            search: [FailingWeatherProvider()]
        )
        let snapshot = try await chain.fetchCurrent(latE7: 399_000_000, lonE7: 1_164_000_000)
        XCTAssertEqual(snapshot.conditionCode, "clear")
    }

    func testFallbackForecastThrowsWhenAllFail() async {
        let chain = FallbackWeatherProvider(
            forecast: [FailingWeatherProvider()],
            search: []
        )
        do {
            _ = try await chain.fetchCurrent(latE7: 0, lonE7: 0)
            XCTFail("应当抛出主源错误")
        } catch {
            XCTAssertEqual(error as? WeatherFetchError, .stale(retryAfterMillis: nil))
        }
    }

    func testFallbackSearchSkipsEmptyResults() async throws {
        let chain = FallbackWeatherProvider(
            forecast: [],
            search: [
                StubWeatherProvider(results: []),
                StubWeatherProvider(results: [
                    WeatherPlaceCandidate(
                        placeId: "geo:1,2",
                        name: "海淀",
                        region: "北京, 中国",
                        latE7: 1,
                        lonE7: 2
                    ),
                ]),
            ]
        )
        let results = try await chain.searchPlaces(query: "海淀", language: "zh")
        XCTAssertEqual(results.map(\.placeId), ["geo:1,2"])
    }
}

private final class FailingWeatherProvider: WeatherProvider {
    func fetchCurrent(latE7: Int, lonE7: Int) async throws -> WeatherSnapshot {
        throw WeatherFetchError.stale(retryAfterMillis: nil)
    }

    func searchPlaces(query: String, language: String) async throws -> [WeatherPlaceCandidate] {
        throw WeatherFetchError.unavailable
    }
}

private final class StubWeatherProvider: WeatherProvider {
    let conditionCode: String
    let results: [WeatherPlaceCandidate]

    init(conditionCode: String = "clear", results: [WeatherPlaceCandidate] = []) {
        self.conditionCode = conditionCode
        self.results = results
    }

    func fetchCurrent(latE7: Int, lonE7: Int) async throws -> WeatherSnapshot {
        WeatherSnapshot(
            placeId: "",
            conditionCode: conditionCode,
            temperatureMilli: 20_000,
            observedAt: 0,
            fetchedAt: 0,
            expiresAt: 0,
            attributionText: "stub",
            attributionURL: nil
        )
    }

    func searchPlaces(query: String, language: String) async throws -> [WeatherPlaceCandidate] {
        results
    }
}
