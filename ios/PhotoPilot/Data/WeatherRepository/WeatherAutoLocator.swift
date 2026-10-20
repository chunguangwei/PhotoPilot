import CoreLocation
import Foundation

@MainActor
final class WeatherAutoLocator: NSObject, @preconcurrency CLLocationManagerDelegate {
    private let repository: WeatherRepository
    private let manager = CLLocationManager()
    private var awaitingAuthorization = false
    private var locating = false
    var onPlaceUpdated: (() -> Void)?

    init(repository: WeatherRepository) {
        self.repository = repository
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
    }

    func refreshCurrentLocation() {
        guard CLLocationManager.locationServicesEnabled(), !locating else { return }
        switch manager.authorizationStatus {
        case .notDetermined:
            guard !awaitingAuthorization else { return }
            awaitingAuthorization = true
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            startLocating()
        default:
            break
        }
    }

    private func startLocating() {
        guard !locating else { return }
        locating = true
        manager.requestLocation()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard awaitingAuthorization else { return }
        awaitingAuthorization = false
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            startLocating()
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        locating = false
        guard let location = locations.first else { return }
        Task {
            let selectedID = try? await repository.selectedPlace()?.placeId
            guard (try? await repository.followsLocation()) == true else { return }
            let candidate = await WeatherReverseGeocoder.place(for: location)
            guard (try? await repository.saveAutomaticPlace(candidate, expectedSelectedID: selectedID)) == true else { return }

            onPlaceUpdated?()
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        locating = false
    }

}

@MainActor
enum WeatherReverseGeocoder {
    nonisolated static func display(_ placemark: CLPlacemark) -> WeatherPlaceDisplay {
        WeatherPlaceDisplay(locality: placemark.locality, subLocality: placemark.subLocality,
                            subAdminArea: placemark.subAdministrativeArea,
                            adminArea: placemark.administrativeArea, country: placemark.country)
    }

    static func place(for location: CLLocation) async -> WeatherPlace {
        let coarse = OpenMeteoProvider.coarsenE7(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude)
        let zh = await reverse(location, language: "zh_CN")
        let en = await reverse(location, language: "en")
        let fallback = String(format: "%.1f, %.1f", Double(coarse.latE7) / 10_000_000, Double(coarse.lonE7) / 10_000_000)
        return WeatherPlace(placeId: "geo:\(coarse.latE7),\(coarse.lonE7)",
                            nameZh: zh?.primary ?? en?.primary ?? fallback,
                            nameEn: en?.primary ?? zh?.primary ?? fallback,
                            latE7: coarse.latE7, lonE7: coarse.lonE7, region: "", displayZh: zh, displayEn: en)
    }

    private static func reverse(_ location: CLLocation, language: String) async -> WeatherPlaceDisplay? {
        let geocoder = CLGeocoder()
        return await withCheckedContinuation { continuation in
            geocoder.reverseGeocodeLocation(location, preferredLocale: Locale(identifier: language)) { placemarks, _ in
                continuation.resume(returning: placemarks?.first.map(display))
            }
        }
    }
}
