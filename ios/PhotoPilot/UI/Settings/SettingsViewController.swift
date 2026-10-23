import UIKit

final class SettingsViewController: UIViewController {
    private static let appearanceValues = ["system", "light", "dark"]

    private let environment: AppEnvironment
    private let formStack = UIStackView()
    private var formWidthConstraint: NSLayoutConstraint?
    private var adaptiveControlRows: [(stack: UIStackView, switchLabelWidth: NSLayoutConstraint?)] = []

    private let appearanceSegmented = UISegmentedControl()
    private let languageSegmented = UISegmentedControl()
    private let reduceMotionNote = UILabel()
    private let reduceTransparencyNote = UILabel()
    private let photosValueLabel = SettingsViewController.styledValueLabel()
    private let cacheValueLabel = SettingsViewController.styledValueLabel()
    private let freeValueLabel = SettingsViewController.styledValueLabel()
    private let storageLowLabel = UILabel()
    private let wifiOnlySwitch = UISwitch()
    private let autoPlaySwitch = UISwitch()
    private let shareStatisticsSwitch = UISwitch()
    private let weatherEnabledSwitch = UISwitch()
    private let weatherCityLabel = UILabel()
    private let weatherUnitsSegmented = UISegmentedControl()
    private let weatherObservedLabel = UILabel()
    private let weatherAttributionLabel = UILabel()
    private let weatherErrorLabel = UILabel()
    private lazy var weatherRetryButton = ActionButton(titleKey: "common.retry", style: .secondary)
    private var weatherPlace: WeatherPlace?
    private var weatherRefreshGeneration = 0

    init(environment: AppEnvironment) {
        self.environment = environment
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

        let titleLabel = UILabel()
        titleLabel.text = L10n.string("settings.title")
        titleLabel.font = ThemeFont.title()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.numberOfLines = 0
        titleLabel.accessibilityTraits.insert(.header)
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(titleLabel)

        let scrollView = UIScrollView()
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        formStack.axis = .vertical
        formStack.spacing = ThemeMetrics.space16
        formStack.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(formStack)
        view.addSubview(scrollView)

        let formWidth = formStack.widthAnchor.constraint(equalToConstant: ThemeMetrics.bodyMaxWidth)
        formWidthConstraint = formWidth
        NSLayoutConstraint.activate([
            titleLabel.topAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.topAnchor,
                constant: ThemeMetrics.space24
            ),
            titleLabel.leadingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInset
            ),
            titleLabel.trailingAnchor.constraint(
                lessThanOrEqualTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            scrollView.topAnchor.constraint(
                equalTo: titleLabel.bottomAnchor,
                constant: ThemeMetrics.space16
            ),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            formStack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            formStack.bottomAnchor.constraint(
                equalTo: scrollView.contentLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space24
            ),
            formStack.centerXAnchor.constraint(equalTo: scrollView.frameLayoutGuide.centerXAnchor),
            formWidth,
        ])

        buildForm()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reloadValues()
    }

    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        if view.bounds.width > 0 {
            let availableWidth = max(0, view.bounds.width - 2 * ThemeMetrics.contentInset)
            formWidthConstraint?.constant = min(availableWidth, ThemeMetrics.bodyMaxWidth)
        }
        let stacked = view.bounds.width < 480 || traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        for adaptiveRow in adaptiveControlRows where adaptiveRow.stack.axis != (stacked ? .vertical : .horizontal) {
            let row = adaptiveRow.stack
            let isSwitchRow = row.arrangedSubviews.last is UISwitch
            row.axis = stacked ? .vertical : .horizontal
            row.alignment = stacked ? (isSwitchRow ? .trailing : .fill) : .center
            row.spacing = stacked ? ThemeMetrics.space8 : ThemeMetrics.space12
            row.isLayoutMarginsRelativeArrangement = stacked && isSwitchRow
            row.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 8)
            adaptiveRow.switchLabelWidth?.isActive = stacked && isSwitchRow
            adaptiveRow.switchLabelWidth?.constant = -8
            for child in row.arrangedSubviews where type(of: child) == UIView.self {
                child.isHidden = stacked
            }
        }
    }

    private func buildForm() {
        configure(
            segmented: appearanceSegmented,
            items: [
                L10n.string("settings.appearance_system"),
                L10n.string("settings.appearance_light"),
                L10n.string("settings.appearance_dark"),
            ],
            action: #selector(handleAppearanceChanged)
        )
        formStack.addArrangedSubview(makeSection(
            titleKey: "settings.appearance",
            rows: [appearanceSegmented]
        ))

        configure(
            segmented: languageSegmented,
            items: AppLanguage.values.map { AppLanguage.titleValue(for: $0) },
            action: #selector(handleLanguageChanged)
        )
        formStack.addArrangedSubview(makeSection(
            titleKey: "settings.language",
            rows: [
                languageSegmented,
                makeNote(key: "settings.language_system_note"),
            ]
        ))

        styleNoteLabel(reduceMotionNote)
        reduceMotionNote.text = L10n.string("settings.reduce_motion_note")
        styleNoteLabel(reduceTransparencyNote)
        reduceTransparencyNote.text = L10n.string("settings.reduce_transparency_note")
        let accessibilityButton = ActionButton(titleKey: "common.open_system_settings", style: .secondary)
        accessibilityButton.addTarget(self, action: #selector(handleOpenSystemSettings), for: .touchUpInside)
        formStack.addArrangedSubview(makeSection(
            titleKey: "settings.accessibility",
            rows: [reduceMotionNote, reduceTransparencyNote, accessibilityButton]
        ))

        formStack.addArrangedSubview(makeSection(
            titleKey: "settings.storage",
            defaultCollapsed: true,
            rows: [
                makeRow(labelKey: "settings.storage_photos", control: photosValueLabel),
                makeRow(labelKey: "settings.storage_cache", control: cacheValueLabel),
                makeFullWidthRow(freeValueLabel),
                makeTextRow(key: "settings.storage_threshold"),
                makeNote(key: "settings.storage_threshold_help"),
                storageLowBanner(),
                makeClearCacheRow(),
                makeNote(key: "delete.clear_cache_body"),
                makeRestoreDeletedRow(),
            ]
        ))

        formStack.addArrangedSubview(makeSection(
            titleKey: "settings.network",
            rows: [
                makeSwitchRow(labelKey: "settings.wifi_only", toggle: wifiOnlySwitch, action: #selector(handleWifiOnly)),
                makeNote(key: "settings.background_note"),
            ]
        ))

        let holidayButton = ActionButton(titleKey: "common.settings", style: .secondary)
        holidayButton.accessibilityIdentifier = "settings.holiday.open"
        holidayButton.setContentHuggingPriority(.required, for: .horizontal)
        holidayButton.addTarget(self, action: #selector(handleOpenHolidayRules), for: .touchUpInside)
        let holidayRow = makeRow(labelKey: "settings.holiday", control: holidayButton)
        formStack.addArrangedSubview(makeSection(
            titleKey: "playback.title",
            rows: [
                makeSwitchRow(labelKey: "settings.auto_play", toggle: autoPlaySwitch, action: #selector(handleAutoPlay)),
                holidayRow,
            ]
        ))

        weatherCityLabel.font = ThemeFont.body()
        weatherCityLabel.adjustsFontForContentSizeCategory = true
        weatherCityLabel.textColor = ThemeColor.textPrimary
        weatherCityLabel.numberOfLines = 0
        let weatherCityButton = ActionButton(titleKey: "weather.choose_city", style: .secondary)
        weatherCityButton.setContentHuggingPriority(.required, for: .horizontal)
        weatherCityButton.addTarget(self, action: #selector(handleWeatherCity), for: .touchUpInside)
        let weatherCityRow = makeRow(label: weatherCityLabel, control: weatherCityButton)
        configure(
            segmented: weatherUnitsSegmented,
            items: [
                L10n.string("settings.appearance_system"),
                L10n.string("weather.unit_celsius"),
                L10n.string("weather.unit_fahrenheit"),
            ],
            action: #selector(handleWeatherUnits)
        )
        let unitsLabel = UILabel()
        unitsLabel.text = L10n.string("weather.units")
        unitsLabel.font = ThemeFont.body()
        unitsLabel.adjustsFontForContentSizeCategory = true
        unitsLabel.textColor = ThemeColor.textPrimary
        unitsLabel.numberOfLines = 0
        let unitsRow = UIStackView(arrangedSubviews: [unitsLabel, weatherUnitsSegmented])
        unitsRow.axis = .vertical
        unitsRow.alignment = .fill
        unitsRow.spacing = ThemeMetrics.space8
        weatherObservedLabel.font = ThemeFont.body()
        weatherObservedLabel.adjustsFontForContentSizeCategory = true
        weatherObservedLabel.textColor = ThemeColor.textSecondary
        weatherObservedLabel.numberOfLines = 0
        weatherObservedLabel.isHidden = true
        weatherAttributionLabel.font = ThemeFont.meta()
        weatherAttributionLabel.adjustsFontForContentSizeCategory = true
        weatherAttributionLabel.textColor = ThemeColor.textSecondary
        weatherAttributionLabel.numberOfLines = 0
        weatherAttributionLabel.isHidden = true
        weatherRetryButton.addTarget(self, action: #selector(handleWeatherRetry), for: .touchUpInside)
        weatherErrorLabel.font = ThemeFont.body()
        weatherErrorLabel.adjustsFontForContentSizeCategory = true
        weatherErrorLabel.textColor = ThemeColor.statusError
        weatherErrorLabel.numberOfLines = 0
        weatherErrorLabel.text = L10n.string("weather.unavailable")
        weatherErrorLabel.isHidden = true
        formStack.addArrangedSubview(makeSection(
            titleKey: "playback.weather",
            defaultCollapsed: true,
            rows: [
                makeSwitchRow(
                    labelKey: "weather.enable",
                    toggle: weatherEnabledSwitch,
                    action: #selector(handleWeatherEnabled)
                ),
                makeNote(key: "weather.scope_help"),
                weatherCityRow,
                weatherObservedLabel,
                weatherAttributionLabel,
                weatherRetryButton,
                weatherErrorLabel,
                unitsRow,
            ]
        ))

        formStack.addArrangedSubview(makeSection(
            titleKey: "settings.privacy",
            rows: [
                makeNote(key: "settings.privacy_summary"),
            ]
        ))

        let exportButton = ActionButton(titleKey: "settings.export_diagnostics", style: .secondary)
        exportButton.addTarget(self, action: #selector(handleExportDiagnostics(_:)), for: .touchUpInside)
        formStack.addArrangedSubview(makeSection(
            titleKey: "settings.diagnostics",
            rows: [makeNote(key: "settings.diagnostics_help"), exportButton]
        ))

        let clearAllButton = ActionButton(titleKey: "delete.clear_all_title", style: .secondary)
        clearAllButton.addTarget(self, action: #selector(handleClearAll), for: .touchUpInside)
        formStack.addArrangedSubview(makeSection(
            titleKey: "settings.danger",
            rows: [
                makeNote(key: "delete.clear_all_body"),
                clearAllButton,
            ]
        ))

        let versionValue = makeValueLabel()
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        versionValue.text = version
        let licenseNotice = UILabel()
        licenseNotice.text = L10n.string("settings.license_notice")
        licenseNotice.font = ThemeFont.meta()
        licenseNotice.adjustsFontForContentSizeCategory = true
        licenseNotice.textColor = ThemeColor.textSecondary
        licenseNotice.numberOfLines = 0
        let licensesButton = ActionButton(titleKey: "settings.licenses", style: .secondary)
        licensesButton.addTarget(self, action: #selector(handleLicenses), for: .touchUpInside)
        let privacyButton = ActionButton(titleKey: "settings.privacy_policy", style: .secondary)
        privacyButton.addTarget(self, action: #selector(handlePrivacyPolicy), for: .touchUpInside)
        formStack.addArrangedSubview(makeSection(
            titleKey: "settings.about",
            collapsible: false,
            rows: [makeRow(labelKey: "settings.version_label", control: versionValue), licenseNotice, privacyButton, licensesButton]
        ))
    }

    private func storageLowBanner() -> UIView {
        storageLowLabel.text = L10n.string("settings.storage_low_title")
        storageLowLabel.font = ThemeFont.meta()
        storageLowLabel.adjustsFontForContentSizeCategory = true
        storageLowLabel.textColor = ThemeColor.statusWarning
        storageLowLabel.numberOfLines = 0
        storageLowLabel.isHidden = true
        return storageLowLabel
    }

    private func makeClearCacheRow() -> UIView {
        let button = ActionButton(titleKey: "delete.clear_cache", style: .secondary)
        button.addTarget(self, action: #selector(handleClearCache), for: .touchUpInside)
        return button
    }

    private func makeRestoreDeletedRow() -> UIView {
        let button = ActionButton(titleKey: "delete.restore_deleted", style: .secondary)
        button.addTarget(self, action: #selector(handleRestoreDeleted), for: .touchUpInside)
        return button
    }

    @objc private func handleRestoreDeleted() {
        let alert = UIAlertController(
            title: L10n.string("delete.restore_deleted"),
            message: nil,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(
            title: L10n.string("delete.restore_deleted"),
            style: .default,
            handler: { [weak self] _ in
                Task { [weak self] in
                    _ = try? await self?.environment.assets.clearUserDeletionTombstones()
                    self?.refreshStorage()
                }
            }
        ))
        alert.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel
        ))
        present(alert, animated: true)
    }

    private func reloadValues() {
        reduceMotionNote.isHidden = !UIAccessibility.isReduceMotionEnabled
        reduceTransparencyNote.isHidden = !UIAccessibility.isReduceTransparencyEnabled

        Task { [weak self] in
            guard let self else { return }
            let appearance = (try? environment.settings.value(forKey: AppSettingsRepository.appearanceKey))
                ?? "system"
            appearanceSegmented.selectedSegmentIndex = Self.appearanceValues
                .firstIndex(of: appearance) ?? 0
            wifiOnlySwitch.isOn = (try? environment.settings.value(
                forKey: AppSettingsRepository.wifiOnlyKey
            )) != "false"
            autoPlaySwitch.isOn = (try? environment.settings.value(
                forKey: AppSettingsRepository.playbackAutoPlayKey
            )) != "false"
            shareStatisticsSwitch.isOn = (try? environment.settings.value(
                forKey: AppSettingsRepository.shareStatisticsKey
            )) == "true"
            weatherEnabledSwitch.isOn = (try? environment.settings.value(
                forKey: WeatherRepository.enabledKey
            )) == "true"
            switch (try? environment.settings.value(forKey: WeatherRepository.unitsKey)) ?? "" {
            case "celsius":
                weatherUnitsSegmented.selectedSegmentIndex = 1
            case "fahrenheit":
                weatherUnitsSegmented.selectedSegmentIndex = 2
            default:
                weatherUnitsSegmented.selectedSegmentIndex = 0
            }
            languageSegmented.selectedSegmentIndex = AppLanguage.values
                .firstIndex(of: AppLanguage.overrideValue) ?? 0
            if let place = try? await environment.weatherRepository.selectedPlace() {
                weatherPlace = place
                weatherCityLabel.text = [place.compactName(chinese: AppLanguage.preferChinese), place.context(chinese: AppLanguage.preferChinese)].filter { !$0.isEmpty }.joined(separator: "\n")
            } else {
                weatherCityLabel.text = L10n.string("weather.choose_city")
            }
            await refreshWeatherLine(manual: false)
            refreshStorage()
        }
    }

    private func refreshStorage() {
        let originalsBytes = StorageInspector.directoryBytes(environment.originalsRootURL)
        let tmpBytes = StorageInspector.directoryBytes(FileManager.default.temporaryDirectory)
        let stagingBytes = StorageInspector.directoryBytes(PersistenceController.temporaryDirectoryURL())
        photosValueLabel.text = FormatStrings.fileSizeText(byteSize: originalsBytes)
        cacheValueLabel.text = FormatStrings.fileSizeText(byteSize: tmpBytes + stagingBytes)
        let free = StorageInspector.freeBytes() ?? 0
        freeValueLabel.text = FormatStrings.string(
            "settings.storage_free",
            FormatStrings.fileSizeText(byteSize: free)
        )
        storageLowLabel.isHidden = free >= StorageInspector.reservedFreeBytes()
    }

    @objc private func handleAppearanceChanged() {
        let index = appearanceSegmented.selectedSegmentIndex
        let value = index >= 0 && index < Self.appearanceValues.count
            ? Self.appearanceValues[index]
            : "system"
        AppearanceApplier.apply(value)
        try? environment.settings.setValue(value, forKey: AppSettingsRepository.appearanceKey)
    }

    @objc private func handleOpenSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    @objc private func handleWifiOnly() {
        try? environment.settings.setValue(
            wifiOnlySwitch.isOn ? "true" : "false",
            forKey: AppSettingsRepository.wifiOnlyKey
        )
    }

    @objc private func handleAutoPlay() {
        try? environment.settings.setValue(
            autoPlaySwitch.isOn ? "true" : "false",
            forKey: AppSettingsRepository.playbackAutoPlayKey
        )
    }

    @objc private func handleShareStatistics() {
        try? environment.settings.setValue(
            shareStatisticsSwitch.isOn ? "true" : "false",
            forKey: AppSettingsRepository.shareStatisticsKey
        )
    }

    @objc private func handleWeatherEnabled() {
        try? environment.settings.setValue(
            weatherEnabledSwitch.isOn ? "true" : "false",
            forKey: WeatherRepository.enabledKey
        )
        Task { [weak self] in
            await self?.refreshWeatherLine(manual: false)
        }
    }

    @objc private func handleWeatherUnits() {
        let value: String
        switch weatherUnitsSegmented.selectedSegmentIndex {
        case 1:
            value = "celsius"
        case 2:
            value = "fahrenheit"
        default:
            value = "system"
        }
        try? environment.settings.setValue(value, forKey: WeatherRepository.unitsKey)
        Task { [weak self] in
            await self?.refreshWeatherLine(manual: false)
        }
    }

    @objc private func handleLanguageChanged() {
        let index = languageSegmented.selectedSegmentIndex
        let value = index >= 0 && index < AppLanguage.values.count
            ? AppLanguage.values[index]
            : AppLanguage.systemValue
        do {
            try environment.settings.setValue(value, forKey: AppSettingsRepository.languageKey)
            AppLanguage.configure(value: value)
            L10n.announceChange()
        } catch {
            languageSegmented.selectedSegmentIndex = AppLanguage.values
                .firstIndex(of: AppLanguage.overrideValue) ?? 0
        }
    }

    @objc private func handleWeatherRetry() {
        Task { [weak self] in
            guard let self else { return }
            await refreshWeatherLine(manual: true)
        }
    }

    private func refreshWeatherLine(manual: Bool) async {
        weatherRefreshGeneration += 1
        let generation = weatherRefreshGeneration
        weatherErrorLabel.isHidden = true
        weatherErrorLabel.textColor = ThemeColor.statusError
        weatherErrorLabel.text = L10n.string("weather.unavailable")
        guard weatherEnabledSwitch.isOn, let place = weatherPlace else {
            weatherObservedLabel.isHidden = true
            weatherAttributionLabel.isHidden = true
            return
        }
        let outcome = await environment.weatherRefresher.refresh(place: place, manual: manual)
        guard generation == weatherRefreshGeneration,
              weatherEnabledSwitch.isOn, weatherPlace?.placeId == place.placeId else { return }
        let snapshot = try? await environment.weatherRepository.snapshot(placeId: place.placeId)
        guard generation == weatherRefreshGeneration,
              weatherEnabledSwitch.isOn, weatherPlace?.placeId == place.placeId else { return }
        let now = Int(Date().timeIntervalSince1970 * 1000)
        let freshness = snapshot.map {
            WeatherRepository.freshness(observedAtMillis: $0.observedAt, nowMillis: now)
        } ?? .unavailable
        guard let snapshot, freshness != .unavailable else {
            weatherObservedLabel.isHidden = true
            weatherAttributionLabel.isHidden = true
            weatherErrorLabel.isHidden = false
            return
        }
        let units = (try? environment.settings.value(forKey: WeatherRepository.unitsKey)) ?? ""
        let fahrenheit = units == "fahrenheit"
            || (units != "celsius" && !Locale.current.usesMetricSystem)
        let milli = fahrenheit
            ? OpenMeteoProvider.celsiusMilliToFahrenheit(snapshot.temperatureMilli)
            : snapshot.temperatureMilli
        let temperature = FormatStrings.string("format.temperature", Int((Double(milli) / 1000).rounded()))
        let conditionKey = "weather.condition.\(snapshot.conditionCode)"
        let conditionRaw = snapshot.conditionCode == "unknown" ? nil : L10n.string(conditionKey)
        let condition = (conditionRaw == conditionKey) ? nil : conditionRaw
        let base = condition.map { "\(temperature) \($0)" } ?? temperature
        weatherObservedLabel.text = freshness == .delayed
            ? base + " · " + L10n.string("weather.delayed")
            : base
        switch outcome {
        case .updated:
            break
        case .failed:
            weatherErrorLabel.isHidden = false
        case .delayed where manual:
            weatherErrorLabel.textColor = ThemeColor.statusWarning
            weatherErrorLabel.text = L10n.string("weather.delayed")
            weatherErrorLabel.isHidden = false
        case .delayed:
            break
        }
        weatherObservedLabel.isHidden = false
        weatherAttributionLabel.text = FormatStrings.string(
            "weather.attribution",
            snapshot.attributionText ?? OpenMeteoProvider.attributionText
        )
        weatherAttributionLabel.isHidden = false
    }

    @objc private func handleWeatherCity() {
        let picker = WeatherCityPickerViewController(environment: environment) { [weak self] place in
            guard let self else { return }
            weatherPlace = place
            weatherCityLabel.text = [place.compactName(chinese: AppLanguage.preferChinese), place.context(chinese: AppLanguage.preferChinese)].filter { !$0.isEmpty }.joined(separator: "\n")
            dismiss(animated: true)
        }
        picker.modalPresentationStyle = .fullScreen
        present(picker, animated: true)
    }

    @objc private func handleClearCache() {
        let alert = UIAlertController(
            title: L10n.string("delete.clear_cache"),
            message: L10n.string("delete.clear_cache_body"),
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(
            title: L10n.string("delete.clear_cache"),
            style: .destructive,
            handler: { [weak self] _ in
                StorageInspector.clearRebuildableCache()
                self?.refreshStorage()
            }
        ))
        alert.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel
        ))
        present(alert, animated: true)
    }

    @objc private func handleExportDiagnostics(_ sender: ActionButton) {
        guard let url = try? DiagnosticsExporter.writeToTemporaryFile() else { return }
        let activity = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        activity.popoverPresentationController?.sourceView = sender
        present(activity, animated: true)
    }

    @objc private func handleClearAll() {
        let alert = UIAlertController(
            title: L10n.string("delete.clear_all_title"),
            message: L10n.string("delete.clear_all_body"),
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(
            title: L10n.string("common.delete"),
            style: .destructive,
            handler: { [weak self] _ in
                self?.eraseAllData()
            }
        ))
        alert.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel
        ))
        present(alert, animated: true)
    }

    private func eraseAllData() {
        Task { [weak self] in
            guard let self, let window = view.window else { return }
            PlayerViewController.dismissActiveIfAny()
            for runner in environment.runners.values {
                try? await runner.cancel()
            }
            do {
                try MusicCatalog.eraseImportedMusic()
            } catch {
                let alert = UIAlertController(
                    title: nil,
                    message: L10n.string("error.store_write_failed.body"),
                    preferredStyle: .alert
                )
                alert.addAction(UIAlertAction(title: L10n.string("common.close"), style: .default))
                present(alert, animated: true)
                return
            }
            environment.credentialStore.deleteAll()
            let persistence = environment.persistence
            let storeDirectory = persistence.storeURL.deletingLastPathComponent()
            let coordinator = persistence.container.persistentStoreCoordinator
            for store in coordinator.persistentStores {
                if let url = store.url {
                    try? coordinator.destroyPersistentStore(at: url, ofType: store.type)
                }
            }
            StorageInspector.clearRebuildableCache()
            try? FileManager.default.removeItem(at: storeDirectory)

            let freshEnvironment = AppEnvironment(persistence: PersistenceController())
            AppLanguage.configure(value: AppLanguage.systemValue)
            UserDefaults.standard.removeObject(forKey: AppLanguage.defaultsMirrorKey)
            AppearanceApplier.apply("system")
            let navigation = UINavigationController(
                rootViewController: WelcomeViewController(environment: freshEnvironment)
            )
            navigation.setNavigationBarHidden(true, animated: false)
            RootTransition.setRoot(navigation, in: window)
        }
    }

    @objc private func handleLicenses() {
        let licenses = LicensesViewController()
        licenses.modalPresentationStyle = .fullScreen
        present(licenses, animated: true)
    }

    @objc private func handlePrivacyPolicy() {
        guard let url = URL(string: "https://github.com/chunguangwei/PhotoPilot/blob/main/PRIVACY.md") else { return }
        UIApplication.shared.open(url)
    }

    @objc private func handleOpenHolidayRules() {
        let list = HolidayListViewController(environment: environment)
        list.modalPresentationStyle = .fullScreen
        present(list, animated: true)
    }

    private func configure(segmented: UISegmentedControl, items: [String], action: Selector) {
        segmented.removeAllSegments()
        for (index, title) in items.enumerated() {
            segmented.insertSegment(withTitle: title, at: index, animated: false)
        }
        segmented.addTarget(self, action: action, for: .valueChanged)
        segmented.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
    }

    private func makeSection(
        titleKey: String,
        defaultCollapsed: Bool = true,
        collapsible: Bool = true,
        rows: [UIView]
    ) -> UIView {
        CollapsibleSectionView(
            titleKey: titleKey,
            defaultCollapsed: defaultCollapsed,
            collapsible: collapsible,
            rows: rows
        )
    }

    private func makeRow(labelKey: String, control: UIView) -> UIView {
        let label = UILabel()
        label.text = L10n.string(labelKey)
        label.font = ThemeFont.body()
        label.adjustsFontForContentSizeCategory = true
        label.textColor = ThemeColor.textPrimary
        label.numberOfLines = 0
        return makeRow(label: label, control: control)
    }

    private func makeRow(label: UILabel, control: UIView) -> UIView {
        control.setContentHuggingPriority(.required, for: .horizontal)
        let row = UIStackView(arrangedSubviews: [label, UIView(), control])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = ThemeMetrics.space12
        row.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
        adaptiveControlRows.append((row, label.widthAnchor.constraint(equalTo: row.widthAnchor)))
        return row
    }

    private func makeSwitchRow(labelKey: String, toggle: UISwitch, action: Selector) -> UIView {
        toggle.onTintColor = ThemeColor.accentAction
        toggle.addTarget(self, action: action, for: .valueChanged)
        toggle.accessibilityLabel = L10n.string(labelKey)
        return makeRow(labelKey: labelKey, control: toggle)
    }

    private func makeNote(key: String) -> UILabel {
        let label = UILabel()
        label.text = L10n.string(key)
        styleNoteLabel(label)
        return label
    }

    private func styleNoteLabel(_ label: UILabel) {
        label.font = ThemeFont.meta()
        label.adjustsFontForContentSizeCategory = true
        label.textColor = ThemeColor.textSecondary
        label.numberOfLines = 0
    }

    private func makeValueLabel() -> UILabel {
        let label = UILabel()
        label.font = ThemeFont.body()
        label.adjustsFontForContentSizeCategory = true
        label.textColor = ThemeColor.textSecondary
        label.textAlignment = .right
        label.numberOfLines = 0
        return label
    }

    private static func styledValueLabel() -> UILabel {
        let label = UILabel()
        label.font = ThemeFont.body()
        label.adjustsFontForContentSizeCategory = true
        label.textColor = ThemeColor.textSecondary
        label.textAlignment = .right
        label.numberOfLines = 0
        label.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
        return label
    }

    private func makeTextRow(key: String) -> UIView {
        let label = UILabel()
        label.text = L10n.string(key)
        label.font = ThemeFont.body()
        label.adjustsFontForContentSizeCategory = true
        label.textColor = ThemeColor.textPrimary
        label.numberOfLines = 0
        label.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
        return label
    }

    private func makeFullWidthRow(_ value: UILabel) -> UIView {
        value.textAlignment = .left
        return value
    }
}

private final class CollapsibleSectionView: UIView {
    private let headerControl = UIControl()
    private let titleLabel = UILabel()
    private let chevronView = UIImageView()
    private let bodyStack = UIStackView()
    private let defaultsKey: String
    private var collapsed: Bool

    init(titleKey: String, defaultCollapsed: Bool, collapsible: Bool = true, rows: [UIView]) {
        defaultsKey = "settings.section.collapsed.\(titleKey)"
        if !collapsible {
            collapsed = false
        } else if UserDefaults.standard.object(forKey: defaultsKey) != nil {
            collapsed = UserDefaults.standard.bool(forKey: defaultsKey)
        } else {
            collapsed = defaultCollapsed
        }
        super.init(frame: .zero)
        backgroundColor = ThemeColor.panel
        layer.cornerRadius = ThemeMetrics.radiusGroup

        titleLabel.text = L10n.string(titleKey)
        titleLabel.font = ThemeFont.body()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textSecondary
        titleLabel.numberOfLines = 0
        titleLabel.isAccessibilityElement = false
        titleLabel.translatesAutoresizingMaskIntoConstraints = false

        chevronView.image = UIImage(systemName: "chevron.right")
        chevronView.tintColor = ThemeColor.textSecondary
        chevronView.contentMode = .scaleAspectFit
        chevronView.setContentHuggingPriority(.required, for: .horizontal)
        chevronView.isAccessibilityElement = false
        chevronView.translatesAutoresizingMaskIntoConstraints = false
        chevronView.isHidden = !collapsible
        headerControl.translatesAutoresizingMaskIntoConstraints = false
        if collapsible {
            headerControl.addTarget(self, action: #selector(handleToggle), for: .touchUpInside)
        }
        headerControl.isAccessibilityElement = true
        headerControl.accessibilityLabel = titleLabel.text
        headerControl.accessibilityValue = collapsible ? L10n.string(collapsed ? "a11y.collapsed" : "a11y.expanded") : nil
        headerControl.accessibilityTraits = collapsible ? [.button] : []

        headerControl.addSubview(titleLabel)
        headerControl.addSubview(chevronView)

        bodyStack.axis = .vertical
        bodyStack.spacing = ThemeMetrics.space8
        for row in rows {
            bodyStack.addArrangedSubview(row)
        }

        let contentStack = UIStackView(arrangedSubviews: [headerControl, bodyStack])
        contentStack.axis = .vertical
        contentStack.spacing = ThemeMetrics.space8
        contentStack.layoutMargins = UIEdgeInsets(
            top: ThemeMetrics.space12,
            left: ThemeMetrics.space16,
            bottom: ThemeMetrics.space12,
            right: ThemeMetrics.space16
        )
        contentStack.isLayoutMarginsRelativeArrangement = true
        contentStack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(contentStack)

        NSLayoutConstraint.activate([
            contentStack.topAnchor.constraint(equalTo: topAnchor),
            contentStack.leadingAnchor.constraint(equalTo: leadingAnchor),
            contentStack.trailingAnchor.constraint(equalTo: trailingAnchor),
            contentStack.bottomAnchor.constraint(equalTo: bottomAnchor),
            headerControl.heightAnchor.constraint(
                greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
            ),
            titleLabel.leadingAnchor.constraint(equalTo: headerControl.leadingAnchor),
            titleLabel.centerYAnchor.constraint(equalTo: headerControl.centerYAnchor),
            titleLabel.topAnchor.constraint(greaterThanOrEqualTo: headerControl.topAnchor),
            titleLabel.bottomAnchor.constraint(lessThanOrEqualTo: headerControl.bottomAnchor),
            titleLabel.trailingAnchor.constraint(
                lessThanOrEqualTo: chevronView.leadingAnchor,
                constant: -ThemeMetrics.space12
            ),
            chevronView.centerYAnchor.constraint(equalTo: headerControl.centerYAnchor),
            chevronView.trailingAnchor.constraint(equalTo: headerControl.trailingAnchor),
        ])

        bodyStack.isHidden = collapsed
        if !collapsed {
            chevronView.transform = CGAffineTransform(rotationAngle: .pi / 2)
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    @objc private func handleToggle() {
        collapsed.toggle()
        headerControl.accessibilityValue = L10n.string(collapsed ? "a11y.collapsed" : "a11y.expanded")
        UserDefaults.standard.set(collapsed, forKey: defaultsKey)
        let target = collapsed ? CGAffineTransform.identity : CGAffineTransform(rotationAngle: .pi / 2)
        UIView.animate(
            withDuration: UIAccessibility.isReduceMotionEnabled ? 0 : 0.2,
            animations: { [weak self] in
                guard let self else { return }
                self.bodyStack.isHidden = self.collapsed
                self.chevronView.transform = target
                self.layoutIfNeeded()
                self.window?.layoutIfNeeded()
            }
        )
    }
}

final class LicensesViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        enableEdgeSwipeBack()
        view.backgroundColor = ThemeColor.canvas

        let backButton = ActionButton(titleKey: "common.back", style: .secondary)
        backButton.addTarget(self, action: #selector(handleBack), for: .touchUpInside)
        let titleLabel = UILabel()
        titleLabel.text = L10n.string("settings.licenses")
        titleLabel.numberOfLines = 0
        titleLabel.font = ThemeFont.heading()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.accessibilityTraits.insert(.header)
        let topBar = UIStackView(arrangedSubviews: [backButton, titleLabel, UIView()])
        topBar.axis = .horizontal
        topBar.alignment = .center
        topBar.spacing = ThemeMetrics.space12
        topBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(topBar)

        let notices = Bundle.main.url(forResource: "ThirdPartyNotices", withExtension: "txt")
            .flatMap { try? String(contentsOf: $0, encoding: .utf8) }
            ?? "AMSMB2 with libsmb2 — LGPL-2.1; AMSMB2 Swift sources — MIT"
        let content = UITextView()
        content.text = [
            L10n.string("settings.license_notice"),
            L10n.string("licenses.music_note"),
            notices
        ].joined(separator: "\n\n")
        content.font = ThemeFont.meta()
        content.adjustsFontForContentSizeCategory = true
        content.textColor = ThemeColor.textPrimary
        content.backgroundColor = .clear
        content.isEditable = false
        content.dataDetectorTypes = .link
        content.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(content)

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
            content.topAnchor.constraint(
                equalTo: topBar.bottomAnchor,
                constant: ThemeMetrics.space16
            ),
            content.leadingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            content.trailingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            content.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
        ])
    }

    @objc private func handleBack() {
        dismiss(animated: true)
    }
}
