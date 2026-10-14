import Foundation

struct WeatherPlaceCandidate: Sendable, Equatable {
    let placeId: String
    let name: String
    let region: String
    let latE7: Int
    let lonE7: Int
}

enum WeatherFetchError: Error, Equatable {
    case unavailable
    case stale(retryAfterMillis: Int?)
}

protocol WeatherProvider: Sendable {
    func fetchCurrent(latE7: Int, lonE7: Int) async throws -> WeatherSnapshot
    func searchPlaces(query: String, language: String) async throws -> [WeatherPlaceCandidate]
}

final class OpenMeteoProvider: WeatherProvider {
    static let attributionText = "Open-Meteo.com"
    static let attributionURL = "https://open-meteo.com/"

    private let session: URLSession
    private let forecastBaseURL: String
    private let geocodingBaseURL: String

    static func defaultSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.timeoutIntervalForResource = 30
        return URLSession(configuration: configuration)
    }

    init(
        session: URLSession = OpenMeteoProvider.defaultSession(),
        forecastBaseURL: String = "https://api.open-meteo.com/v1/forecast",
        geocodingBaseURL: String = "https://geocoding-api.open-meteo.com/v1/search"
    ) {
        self.session = session
        self.forecastBaseURL = forecastBaseURL
        self.geocodingBaseURL = geocodingBaseURL
    }

    static func conditionCode(wmo: Int) -> String {
        switch wmo {
        case 0: return "clear"
        case 1, 2: return "partly_cloudy"
        case 3: return "cloudy"
        case 45, 48: return "fog"
        case 51, 53, 55, 56, 57: return "drizzle"
        case 61, 63, 66, 80, 81: return "rain"
        case 65, 82: return "heavy_rain"
        case 71, 73, 77, 85: return "snow"
        case 75, 86: return "heavy_snow"
        case 95, 96, 99: return "thunderstorm"
        default: return "unknown"
        }
    }

    static func coarsenE7(latitude: Double, longitude: Double) -> (latE7: Int, lonE7: Int) {
        let lat = (latitude * 10).rounded() / 10
        let lon = (longitude * 10).rounded() / 10
        return (
            Int((lat * 10_000_000).rounded()),
            Int((lon * 10_000_000).rounded())
        )
    }

    static func celsiusMilliToFahrenheit(_ milliCelsius: Int) -> Int {
        Int((Double(milliCelsius) * 9 / 5 + 32_000).rounded())
    }

    func fetchCurrent(latE7: Int, lonE7: Int) async throws -> WeatherSnapshot {
        let lat = Double(latE7) / 10_000_000
        let lon = Double(lonE7) / 10_000_000
        var components = URLComponents(string: forecastBaseURL)
        components?.queryItems = [
            URLQueryItem(name: "latitude", value: String(format: "%.1f", lat)),
            URLQueryItem(name: "longitude", value: String(format: "%.1f", lon)),
            URLQueryItem(name: "current", value: "temperature_2m,weather_code"),
            URLQueryItem(name: "timezone", value: "auto"),
        ]
        guard let url = components?.url else {
            throw WeatherFetchError.unavailable
        }
        let (data, response) = try await perform(url: url)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else {
            throw mapStatus(status: status, response: response as? HTTPURLResponse)
        }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let current = object["current"] as? [String: Any],
              let temperature = (current["temperature_2m"] as? NSNumber)?.doubleValue,
              let wmo = (current["weather_code"] as? NSNumber)?.intValue,
              let timeText = current["time"] as? String else {
            throw WeatherFetchError.unavailable
        }
        let offsetSeconds = (object["utc_offset_seconds"] as? NSNumber)?.intValue ?? 0
        let observed = Self.parseLocalTime(timeText, utcOffsetSeconds: offsetSeconds)
        let now = Int(Date().timeIntervalSince1970 * 1000)
        return WeatherSnapshot(
            placeId: "",
            conditionCode: Self.conditionCode(wmo: wmo),
            temperatureMilli: Int((temperature * 1000).rounded()),
            observedAt: observed,
            fetchedAt: now,
            expiresAt: observed + WeatherRepository.normalLimitMillis,
            attributionText: Self.attributionText,
            attributionURL: Self.attributionURL
        )
    }

    func searchPlaces(query: String, language: String) async throws -> [WeatherPlaceCandidate] {
        var components = URLComponents(string: geocodingBaseURL)
        components?.queryItems = [
            URLQueryItem(name: "name", value: query),
            URLQueryItem(name: "count", value: "8"),
            URLQueryItem(name: "language", value: language),
            URLQueryItem(name: "format", value: "json"),
        ]
        guard let url = components?.url else {
            throw WeatherFetchError.unavailable
        }
        let (data, response) = try await perform(url: url)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else {
            throw mapStatus(status: status, response: response as? HTTPURLResponse)
        }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let results = object["results"] as? [[String: Any]] else {
            return []
        }
        return results.compactMap { entry in
            guard let id = (entry["id"] as? NSNumber)?.intValue,
                  let name = entry["name"] as? String,
                  let lat = (entry["latitude"] as? NSNumber)?.doubleValue,
                  let lon = (entry["longitude"] as? NSNumber)?.doubleValue else {
                return nil
            }
            let region = [
                entry["admin1"] as? String,
                entry["country"] as? String,
            ]
            .compactMap { $0 }
            .joined(separator: ", ")
            return WeatherPlaceCandidate(
                placeId: "om:\(id)",
                name: name,
                region: region,
                latE7: Int((lat * 10_000_000).rounded()),
                lonE7: Int((lon * 10_000_000).rounded())
            )
        }
    }

    private func perform(url: URL) async throws -> (Data, URLResponse) {
        do {
            return try await session.data(from: url)
        } catch let error as URLError {
            switch error.code {
            case .timedOut, .networkConnectionLost, .notConnectedToInternet:
                throw WeatherFetchError.stale(retryAfterMillis: nil)
            default:
                throw WeatherFetchError.stale(retryAfterMillis: nil)
            }
        }
    }

    private func mapStatus(status: Int, response: HTTPURLResponse?) -> WeatherFetchError {
        if status == 429 {
            let retryAfter = response?.value(forHTTPHeaderField: "Retry-After")
                .flatMap(Int.init)
            return .stale(retryAfterMillis: retryAfter.map { $0 * 1000 })
        }
        if status >= 500 {
            return .stale(retryAfterMillis: nil)
        }
        return .unavailable
    }

    static func parseLocalTime(_ text: String, utcOffsetSeconds: Int) -> Int {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm"
        guard let local = formatter.date(from: text) else {
            return Int(Date().timeIntervalSince1970 * 1000)
        }
        return Int((local.timeIntervalSince1970 - TimeInterval(utcOffsetSeconds)) * 1000)
    }
}
