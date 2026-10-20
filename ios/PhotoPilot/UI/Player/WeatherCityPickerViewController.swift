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
    private var awaitingLocation = false
    private var locationGeneration = 0
    private var requestedLocationGeneration: Int?
    private let locationButton = ActionButton(titleKey: "weather.use_current_location", style: .secondary)
    private let locationRow = UIStackView()

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

        let backButton = ActionButton(titleKey: "common.back", style: .secondary)
        backButton.addTarget(self, action: #selector(handleBack), for: .touchUpInside)
        let titleLabel = UILabel()
        titleLabel.text = L10n.string("weather.choose_city")
        titleLabel.font = ThemeFont.heading()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.numberOfLines = 0
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.accessibilityTraits.insert(.header)
        let topBar = UIStackView(arrangedSubviews: [backButton, titleLabel, UIView()])
        topBar.axis = .horizontal
        topBar.alignment = .center
        topBar.spacing = ThemeMetrics.space12
        topBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(topBar)

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
        statusLabel.numberOfLines = 0
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
        locationRow.addArrangedSubview(locationButton)
        locationRow.addArrangedSubview(locationHelp)
        updateLocationLayout()
        locationRow.spacing = ThemeMetrics.space12
        locationRow.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
        locationRow.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(locationRow)

        let readable = searchField.widthAnchor.constraint(
            lessThanOrEqualToConstant: ThemeMetrics.bodyMaxWidth
        )
        let fillWidth = searchField.widthAnchor.constraint(
            equalTo: view.safeAreaLayoutGuide.widthAnchor,
            constant: -2 * ThemeMetrics.contentInsetCompact
        )
        fillWidth.priority = .defaultHigh
        NSLayoutConstraint.activate([
            topBar.topAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.topAnchor,
                constant: ThemeMetrics.space8
            ),
            topBar.leadingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            topBar.trailingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            locationRow.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: ThemeMetrics.space8),
            locationRow.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            locationRow.widthAnchor.constraint(equalTo: searchField.widthAnchor),
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
            fillWidth,
            statusLabel.topAnchor.constraint(equalTo: searchField.bottomAnchor, constant: ThemeMetrics.space8),
            statusLabel.centerXAnchor.constraint(equalTo: searchField.centerXAnchor),
            statusLabel.widthAnchor.constraint(equalTo: searchField.widthAnchor),
            table.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: ThemeMetrics.space8),
            table.leadingAnchor.constraint(equalTo: searchField.leadingAnchor),
            table.trailingAnchor.constraint(equalTo: searchField.trailingAnchor),
            table.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
            table.bottomAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.bottomAnchor),
        ])

        locationManager.delegate = self
        enableKeyboardDismissal()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        updateLocationLayout()
    }

    private func updateLocationLayout() {
        let large = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        locationRow.axis = large ? .vertical : .horizontal
        locationRow.alignment = large ? .fill : .center
    }

    @objc private func handleUseCurrentLocation() {
        locationGeneration += 1
        requestedLocationGeneration = locationGeneration
        searchTask?.cancel()
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
        guard awaitingLocation, requestedLocationGeneration == locationGeneration else { return }
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
        guard let generation = requestedLocationGeneration, generation == locationGeneration else { return }
        requestedLocationGeneration = nil
        guard let location = locations.first else {
            statusLabel.text = L10n.string("weather.unavailable")
            return
        }
        searchTask?.cancel()
        Task { [weak self] in
            guard let self else { return }
            let candidate = await WeatherReverseGeocoder.place(for: location)
            guard generation == self.locationGeneration, self.viewIfLoaded?.window != nil else { return }
            let place = candidate
            do {
                try await self.environment.weatherRepository.savePlace(place, followLocation: true)
                guard let stored = try await self.environment.weatherRepository.selectedPlace(),
                      stored.placeId == place.placeId, generation == self.locationGeneration else { return }
                self.onPick(stored)
            } catch {
                self.statusLabel.text = L10n.string("weather.unavailable")
            }
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        statusLabel.text = L10n.string("weather.unavailable")
    }

    @objc private func handleSearchChanged() {
        locationGeneration += 1
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
        guard !Task.isCancelled, query == (searchField.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines) else { return }
        results = WeatherPlace.mergeCandidates(zh: zhResults, en: enResults)
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
        content.text = WeatherPlaceDisplay.clean(AppLanguage.preferChinese ? place.nameZh : place.nameEn) ?? place.compactName(chinese: AppLanguage.preferChinese)
        content.textProperties.font = ThemeFont.body()
        content.textProperties.color = ThemeColor.textPrimary
        content.secondaryText = place.context(chinese: AppLanguage.preferChinese)
        content.secondaryTextProperties.font = ThemeFont.meta()
        content.secondaryTextProperties.color = ThemeColor.textSecondary
        cell.contentConfiguration = content
        cell.backgroundColor = .clear
        cell.accessibilityTraits = [.button]
        return cell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        locationGeneration += 1
        tableView.deselectRow(at: indexPath, animated: true)
        let place = results[indexPath.row]
        let generation = locationGeneration
        Task { [weak self] in
            guard let self else { return }
            do {
                try await self.environment.weatherRepository.savePlace(place, followLocation: false)
                guard let stored = try await self.environment.weatherRepository.selectedPlace(),
                      stored.placeId == place.placeId, generation == self.locationGeneration else { return }
                self.onPick(stored)
            } catch {
                self.statusLabel.text = L10n.string("weather.unavailable")
            }
        }
    }

    func tableView(_ tableView: UITableView, heightForRowAt indexPath: IndexPath) -> CGFloat {
        UITableView.automaticDimension
    }

    func tableView(_ tableView: UITableView, estimatedHeightForRowAt indexPath: IndexPath) -> CGFloat {
        ThemeMetrics.touchTarget
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        locationGeneration += 1
        textField.resignFirstResponder()
        searchTask?.cancel()
        searchTask = Task { await search() }
        return true
    }

    @objc private func handleBack() {
        locationGeneration += 1
        dismiss(animated: true)
    }
}
