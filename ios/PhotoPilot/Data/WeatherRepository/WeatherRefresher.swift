import Foundation

enum WeatherRefreshOutcome: Sendable, Equatable {
    case updated
    case delayed
    case failed
}

actor WeatherRefresher {
    static let manualThrottleMillis = 60_000
    static let autoMinAgeMillis = 30 * 60 * 1000
    static let failureBackoffMillis = 60_000

    private let repository: WeatherRepository
    private let provider: any WeatherProvider
    private var manualLastAt: [String: Int] = [:]
    private var blockedUntil: [String: Int] = [:]

    init(repository: WeatherRepository, provider: any WeatherProvider) {
        self.repository = repository
        self.provider = provider
    }

    func refresh(place: WeatherPlace, manual: Bool) async -> WeatherRefreshOutcome {
        let now = Self.nowMillis()
        if manual {
            guard now - (manualLastAt[place.placeId] ?? 0) >= Self.manualThrottleMillis else {
                return .delayed
            }
            manualLastAt[place.placeId] = now
        } else if let snapshot = try? await repository.snapshot(placeId: place.placeId),
                  now - snapshot.fetchedAt < Self.autoMinAgeMillis {
            return .delayed
        }
        guard now >= (blockedUntil[place.placeId] ?? 0) else { return .delayed }
        do {
            var snapshot = try await provider.fetchCurrent(latE7: place.latE7, lonE7: place.lonE7)
            snapshot = WeatherSnapshot(
                placeId: place.placeId,
                conditionCode: snapshot.conditionCode,
                temperatureMilli: snapshot.temperatureMilli,
                observedAt: snapshot.observedAt,
                fetchedAt: snapshot.fetchedAt,
                expiresAt: snapshot.expiresAt,
                attributionText: snapshot.attributionText,
                attributionURL: snapshot.attributionURL
            )
            try await repository.saveSnapshot(snapshot)
            blockedUntil[place.placeId] = nil
            return .updated
        } catch WeatherFetchError.stale(let retryAfter) {
            blockedUntil[place.placeId] = now + (retryAfter ?? Self.failureBackoffMillis)
        } catch {
            blockedUntil[place.placeId] = now + Self.failureBackoffMillis
        }
        return .failed
    }

    private static func nowMillis() -> Int {
        Int(Date().timeIntervalSince1970 * 1000)
    }
}
