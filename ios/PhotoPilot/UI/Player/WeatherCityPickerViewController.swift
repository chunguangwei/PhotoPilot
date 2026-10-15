import CoreLocation
import UIKit
final class WeatherCityPickerViewController: UIViewController, UITableViewDataSource, UITableViewDelegate, UITextFieldDelegate, @preconcurrency CLLocationManagerDelegate {
    private static let searchDebounceNanos: UInt64 = 300_000_000

    private let environment: AppEnvironment
    private let onPick: (WeatherPlace) -> Void

    private let searchField = UITextField()
    private let statusLabel = UILabel()
    private let table = UITableView(frame: .zero, style: .plain)
    private var results: [WeatherPlace] = []
    private var searchTask: Task<Void, Never>?
    private let locationManager = CLLocationManager()
    private let geocoder = CLGeocoder()
    private var awaitingLocation = false
    private let locationButton = ActionButton(titleKey: "weather.use_current_location", style: .secondary)

    init(environment: AppEnvironment, onPick: @escaping (WeatherPlace) -> Void) {
        self.environment = environment
        self.onPick = onPick
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        enableEdgeSwipeBack()
        view.backgroundColor = ThemeColor.canvas

        let topBar = ScreenHeaderView(title: L10n.string("weather.choose_city"))
        topBar.onBack = { [weak self] in self?.handleBack() }
        topBar.pinToTop(of: view)

        searchField.placeholder = L10n.string("weather.search_city")
        searchField.font = ThemeFont.body()
        searchField.adjustsFontForContentSizeCategory = true
        searchField.textColor = ThemeColor.textPrimary
        searchField.borderStyle = .roundedRect
        searchField.clearButtonMode = .whileEditing
        searchField.returnKeyType = .search
        searchField.delegate = self
        searchField.accessibilityLabel = L10n.string("weather.search_city")
        searchField.addTarget(self, action: #selector(handleSearchChanged), for: .editingChanged)
        searchField.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
        searchField.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(searchField)

        statusLabel.font = ThemeFont.meta()
        statusLabel.adjustsFontForContentSizeCategory = true
        statusLabel.textColor = ThemeColor.textSecondary
        statusLabel.textAlignment = .center
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(statusLabel)

        table.dataSource = self
        table.delegate = self
        table.backgroundColor = .clear
        table.separatorColor = ThemeColor.separator
        table.register(UITableViewCell.self, forCellReuseIdentifier: "CityCell")
        table.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(table)

        locationButton.addTarget(self, action: #selector(handleUseCurrentLocation), for: .touchUpInside)
        let locationHelp = UILabel()
        locationHelp.text = L10n.string("weather.location_help")
        locationHelp.font = ThemeFont.meta()
        locationHelp.adjustsFontForContentSizeCategory = true
        locationHelp.textColor = ThemeColor.textSecondary
        locationHelp.numberOfLines = 0
        let locationRow = UIStackView(arrangedSubviews: [locationButton, locationHelp])
        locationRow.axis = .horizontal
        locationRow.alignment = .center
        locationRow.spacing = ThemeMetrics.space12
        locationRow.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
        locationRow.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(locationRow)

        let readable = searchField.widthAnchor.constraint(equalToConstant: ThemeMetrics.bodyMaxWidth)
        readable.priority = .defaultHigh
        NSLayoutConstraint.activate([
            locationRow.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: ThemeMetrics.space8),
            locationRow.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            locationRow.leadingAnchor.constraint(
                greaterThanOrEqualTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInset
            ),
            locationRow.trailingAnchor.constraint(
                lessThanOrEqualTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            searchField.topAnchor.constraint(equalTo: locationRow.bottomAnchor, constant: ThemeMetrics.space8),
            searchField.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            searchField.leadingAnchor.constraint(
                greaterThanOrEqualTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInset
            ),
            searchField.trailingAnchor.constraint(
                lessThanOrEqualTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            readable,
            statusLabel.topAnchor.constraint(equalTo: searchField.bottomAnchor, constant: ThemeMetrics.space8),
            statusLabel.centerXAnchor.constraint(equalTo: searchField.centerXAnchor),
            table.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: ThemeMetrics.space8),
            table.leadingAnchor.constraint(equalTo: searchField.leadingAnchor),
            table.trailingAnchor.constraint(equalTo: searchField.trailingAnchor),
            table.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
            table.bottomAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.bottomAnchor),
        ])

        locationManager.delegate = self
        enableKeyboardDismissal()
    }

    @objc private func handleUseCurrentLocation() {
        guard CLLocationManager.locationServicesEnabled() else {
            statusLabel.text = L10n.string("weather.unavailable")
            return
        }
        switch locationManager.authorizationStatus {
        case .notDetermined:
            awaitingLocation = true
            statusLabel.text = L10n.string("common.loading")
            locationManager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            statusLabel.text = L10n.string("common.loading")
            locationManager.requestLocation()
        default:
            statusLabel.text = L10n.string("weather.unavailable")
        }
    }

    func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        guard awaitingLocation else { return }
        switch status {
        case .authorizedWhenInUse, .authorizedAlways:
            awaitingLocation = false
            locationManager.requestLocation()
        case .denied, .restricted:
            awaitingLocation = false
            statusLabel.text = L10n.string("weather.unavailable")
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.first else {
            statusLabel.text = L10n.string("weather.unavailable")
            return
        }
        let coarse = OpenMeteoProvider.coarsenE7(latitude: location.coordinate.latitude, longitude: location.coordinate.longitude)
        Task { [weak self] in
            guard let self else { return }
            let zh = await self.reverseName(for: location, locale: Locale(identifier: "zh_CN"))
            let en = await self.reverseName(for: location, locale: Locale(identifier: "en"))
            let fallback = String(
                format: "%.1f, %.1f",
                Double(coarse.latE7) / 10_000_000.0,
                Double(coarse.lonE7) / 10_000_000.0
            )
            let place = WeatherPlace(
                placeId: "geo:\(coarse.latE7),\(coarse.lonE7)",
                nameZh: zh ?? en ?? fallback,
                nameEn: en ?? zh ?? fallback,
                latE7: coarse.latE7,
                lonE7: coarse.lonE7,
                region: ""
            )
            try? await self.environment.weatherRepository.savePlace(place)
            self.onPick(place)
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        statusLabel.text = L10n.string("weather.unavailable")
    }

    private func reverseName(for location: CLLocation, locale: Locale) async -> String? {
        await withCheckedContinuation { continuation in
            geocoder.reverseGeocodeLocation(location, preferredLocale: locale) { placemarks, _ in
                guard let placemark = placemarks?.first else {
                    continuation.resume(returning: nil)
                    return
                }
                let name = [
                    placemark.thoroughfare,
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

    @objc private func handleSearchChanged() {
        searchTask?.cancel()
        searchTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: Self.searchDebounceNanos)
            guard !Task.isCancelled else { return }
            await self?.search()
        }
    }

    private func search() async {
        let query = (searchField.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else {
            results = []
            table.reloadData()
            statusLabel.text = nil
            return
        }
        statusLabel.text = L10n.string("common.loading")
        async let zhSearch = environment.weatherProvider.searchPlaces(query: query, language: "zh")
        async let enSearch = environment.weatherProvider.searchPlaces(query: query, language: "en")
        let zhResults = (try? await zhSearch) ?? []
        let enResults = (try? await enSearch) ?? []
        var namesEn: [String: String] = [:]
        for candidate in enResults {
            namesEn[candidate.placeId] = candidate.name
        }
        results = zhResults.map { candidate in
            WeatherPlace(
                placeId: candidate.placeId,
                nameZh: candidate.name,
                nameEn: namesEn[candidate.placeId] ?? candidate.name,
                latE7: candidate.latE7,
                lonE7: candidate.lonE7,
                region: candidate.region
            )
        }
        if results.isEmpty, zhResults.isEmpty, enResults.isEmpty {
            results = enResults.map { candidate in
                WeatherPlace(
                    placeId: candidate.placeId,
                    nameZh: candidate.name,
                    nameEn: candidate.name,
                    latE7: candidate.latE7,
                    lonE7: candidate.lonE7,
                    region: candidate.region
                )
            }
        }
        statusLabel.text = results.isEmpty
            ? L10n.string("library.search_no_result")
            : nil
        table.reloadData()
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        results.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "CityCell", for: indexPath)
        let place = results[indexPath.row]
        var content = cell.defaultContentConfiguration()
        content.text = AppLanguage.preferChinese ? place.nameZh : place.nameEn
        content.textProperties.font = ThemeFont.body()
        content.textProperties.color = ThemeColor.textPrimary
        content.secondaryText = place.region
        content.secondaryTextProperties.font = ThemeFont.meta()
        content.secondaryTextProperties.color = ThemeColor.textSecondary
        cell.contentConfiguration = content
        cell.backgroundColor = .clear
        cell.accessibilityTraits = [.button]
        return cell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let place = results[indexPath.row]
        Task { [weak self] in
            try? await self?.environment.weatherRepository.savePlace(place)
            self?.onPick(place)
        }
    }

    func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
        UITableView.automaticDimension
    }

    func tableView(_ tableView: UITableView, estimatedHeightForRowAt indexPath: IndexPath) -> CGFloat {
        ThemeMetrics.touchTarget
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        searchTask?.cancel()
        Task { await search() }
        return true
    }

    @objc private func handleBack() {
        dismiss(animated: true)
    }
}
