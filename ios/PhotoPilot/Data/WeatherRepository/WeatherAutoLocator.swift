import CoreLocation
import Foundation

@MainActor
final class WeatherAutoLocator: NSObject, @preconcurrency CLLocationManagerDelegate {
    private let repository: WeatherRepository
    private let manager = CLLocationManager()
    private let geocoder = CLGeocoder()
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
            let coarse = OpenMeteoProvider.coarsenE7(
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude
            )
            async let zh = reverseName(for: location, locale: Locale(identifier: "zh_CN"))
            async let en = reverseName(for: location, locale: Locale(identifier: "en"))
            let fallback = String(
                format: "%.1f, %.1f",
                Double(coarse.latE7) / 10_000_000.0,
                Double(coarse.lonE7) / 10_000_000.0
            )
            let nameZh = await zh
            let nameEn = await en
            let place = WeatherPlace(
                placeId: "geo:\(coarse.latE7),\(coarse.lonE7)",
                nameZh: nameZh ?? nameEn ?? fallback,
                nameEn: nameEn ?? nameZh ?? fallback,
                latE7: coarse.latE7,
                lonE7: coarse.lonE7,
                region: ""
            )
            try? await repository.savePlace(place)
            onPlaceUpdated?()
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        locating = false
    }

    private func reverseName(for location: CLLocation, locale: Locale) async -> String? {
        await withCheckedContinuation { continuation in
            geocoder.reverseGeocodeLocation(location, preferredLocale: locale) { placemarks, _ in
                guard let placemark = placemarks?.first else {
                    continuation.resume(returning: nil)
                    return
                }
                let name = [
                    placemark.subLocality,
                    placemark.locality,
                    placemark.subAdministrativeArea,
                    placemark.administrativeArea,
                ].compactMap { $0 }.first { !$0.isEmpty }
                    ?? placemark.name
                continuation.resume(returning: name?.isEmpty == true ? nil : name)
            }
        }
    }
}
