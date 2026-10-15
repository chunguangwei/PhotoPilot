import UIKit

final class PlaybackSettingsViewController: UIViewController, UITextFieldDelegate {
    private static let holdPresets = [5, 10, 15, 30, 60]
    private static let transitionWireValues = [
        PlaybackTiming.transitionNone,
        PlaybackTiming.transitionCrossfade,
        PlaybackTiming.transitionSlide,
        PlaybackTiming.transitionWipe,
        PlaybackTiming.transitionZoom,
        PlaybackTiming.transitionRipple,
    ]
    private static let transitionTitleKeys = [
        "playback.transition_none",
        "playback.transition_crossfade",
        "playback.transition_slide",
        "playback.transition_wipe",
        "playback.transition_zoom",
        "playback.transition_ripple",
    ]
    private static let fitWireValues = ["fit_blur", "fit_color", "fill_crop"]
    private static let solidWireValues = ["", "black", "warm_white"]
    private static let positionWireValues = [
        "top_leading", "top_trailing", "bottom_leading", "bottom_trailing",
    ]
    private static let orderWireValues = [
        PlaybackQueue.orderCapturedAsc,
        PlaybackQueue.orderRandom,
        PlaybackQueue.orderManual,
    ]
    private static let qualityWireValues = ["auto", "balanced", "low"]

    private let environment: AppEnvironment
    private let frameID: String
    private let loader: ThumbnailLoader
    private let playerLoader: PlayerImageLoader

    private var originalSettings: [String: Any] = [:]
    private var originalOrderMode = PlaybackQueue.orderCapturedAsc
    private var previewImage: UIImage?
    private var previewImageNext: UIImage?
    private var dirty = false

    private let previewBackground = UIImageView()
    private let previewDim = UIView()
    private let previewImageView = UIImageView()
    private let demoImageView = UIImageView()
    private let previewContainer = UIView()
    private var isDemoRunning = false
    private let holdSegmented = UISegmentedControl()
    private let customField = UITextField()
    private let unitSegmented = UISegmentedControl()
    private let holdErrorLabel = UILabel()
    private var transitionIndex = 1
    private let transitionMenuButton = UIButton(type: .system)
    private let durationSlider = UISlider()
    private let durationValueLabel = UILabel()
    private let durationDisabledLabel = UILabel()
    private let reduceMotionLabel = UILabel()
    private var fitIndex = 0
    private let fitOptionButtons = [
        ActionButton(titleKey: "playback.fit_blur", style: .secondary),
        ActionButton(titleKey: "playback.fit_color", style: .secondary),
        ActionButton(titleKey: "playback.fill_crop", style: .secondary),
    ]
    private let solidSegmented = UISegmentedControl()
    private let cropWarningLabel = UILabel()
    private let clockSwitch = UISwitch()
    private let dateSwitch = UISwitch()
    private var positionIndex = 2
    private let positionButtons = [
        ActionButton(titleKey: "playback.position_top_leading", style: .secondary),
        ActionButton(titleKey: "playback.position_top_trailing", style: .secondary),
        ActionButton(titleKey: "playback.position_bottom_leading", style: .secondary),
        ActionButton(titleKey: "playback.position_bottom_trailing", style: .secondary)
    ]
    private let hourSegmented = UISegmentedControl()
    private let orderSegmented = UISegmentedControl()
    private let orderHintLabel = UILabel()
    private let brightnessSegmented = UISegmentedControl()
    private let brightnessSlider = UISlider()
    private var originalScreenBrightness: CGFloat?
    private var brightnessPreviewApplied = false
    private let keepAwakeSwitch = UISwitch()
    private let restSwitch = UISwitch()
    private let restStartPicker = UIDatePicker()
    private let restEndPicker = UIDatePicker()
    private let restErrorLabel = UILabel()
    private var restStartRow = UIView()
    private var restEndRow = UIView()
    private let qualitySegmented = UISegmentedControl()
    private var musicSelection = MusicSelection()
    private let musicButton = UIButton(type: .system)
    private let formStack = UIStackView()

    init(environment: AppEnvironment, frameID: String) {
        self.environment = environment
        self.frameID = frameID
        self.loader = ThumbnailLoader(originalsRoot: environment.originalsRootURL)
        self.playerLoader = PlayerImageLoader(originalsRoot: environment.originalsRootURL)
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        enableEdgeSwipeBack { [weak self] in self?.handleBack() }
        view.backgroundColor = ThemeColor.canvas
        buildLayout()
        enableKeyboardDismissal()
        load()
    }

    private func buildLayout() {
        let backButton = ActionButton(titleKey: "common.back", style: .secondary)
        backButton.addTarget(self, action: #selector(handleBack), for: .touchUpInside)
        let titleLabel = UILabel()
        titleLabel.text = L10n.string("playback.title")
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

        let scrollView = UIScrollView()
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        formStack.axis = .vertical
        formStack.spacing = ThemeMetrics.space12
        formStack.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(formStack)
        view.addSubview(scrollView)

        let cancelButton = ActionButton(titleKey: "common.cancel", style: .secondary)
        cancelButton.addTarget(self, action: #selector(handleBack), for: .touchUpInside)
        let saveButton = ActionButton(titleKey: "common.save", style: .primary)
        saveButton.addTarget(self, action: #selector(handleSave), for: .touchUpInside)
        let bottomBar = UIStackView(arrangedSubviews: [cancelButton, saveButton])
        bottomBar.axis = .horizontal
        bottomBar.distribution = .fillEqually
        bottomBar.spacing = ThemeMetrics.space12
        bottomBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bottomBar)

        let readable = formStack.widthAnchor.constraint(equalToConstant: ThemeMetrics.bodyMaxWidth)
        readable.priority = .defaultHigh
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
            scrollView.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: ThemeMetrics.space8),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: bottomBar.topAnchor),
            formStack.topAnchor.constraint(
                equalTo: scrollView.contentLayoutGuide.topAnchor,
                constant: ThemeMetrics.space8
            ),
            formStack.bottomAnchor.constraint(
                equalTo: scrollView.contentLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space24
            ),
            formStack.centerXAnchor.constraint(equalTo: scrollView.frameLayoutGuide.centerXAnchor),
            formStack.leadingAnchor.constraint(
                greaterThanOrEqualTo: scrollView.frameLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInset
            ),
            formStack.trailingAnchor.constraint(
                lessThanOrEqualTo: scrollView.frameLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            readable,
            bottomBar.leadingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInset
            ),
            bottomBar.trailingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            bottomBar.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
        ])

        buildForm()
    }

    private func buildForm() {
        formStack.addArrangedSubview(makeSectionLabel(key: "playback.preview"))
        previewBackground.contentMode = .scaleAspectFill
        previewBackground.clipsToBounds = true
        previewDim.backgroundColor = ThemeColor.restSurface
            .withAlphaComponent(PlayerViewController.blurDimOpacity)
        previewImageView.contentMode = .scaleAspectFit
        previewImageView.clipsToBounds = true
        previewContainer.clipsToBounds = true
        previewContainer.layer.cornerRadius = ThemeMetrics.radiusGroup
        previewContainer.backgroundColor = ThemeColor.panel
        demoImageView.contentMode = .scaleAspectFit
        demoImageView.clipsToBounds = true
        demoImageView.alpha = 0
        for subview in [previewBackground, previewDim, previewImageView, demoImageView] {
            subview.translatesAutoresizingMaskIntoConstraints = false
            previewContainer.addSubview(subview)
            NSLayoutConstraint.activate([
                subview.topAnchor.constraint(equalTo: previewContainer.topAnchor),
                subview.leadingAnchor.constraint(equalTo: previewContainer.leadingAnchor),
                subview.trailingAnchor.constraint(equalTo: previewContainer.trailingAnchor),
                subview.bottomAnchor.constraint(equalTo: previewContainer.bottomAnchor),
            ])
        }
        previewContainer.heightAnchor.constraint(
            equalToConstant: ThemeMetrics.gridCellMax
        ).isActive = true
        formStack.addArrangedSubview(previewContainer)
        let managePhotosButton = ActionButton(titleKey: "playback.manage_photos", style: .secondary)
        managePhotosButton.addTarget(self, action: #selector(handleManagePhotos), for: .touchUpInside)
        formStack.addArrangedSubview(managePhotosButton)

        formStack.addArrangedSubview(makeSectionLabel(key: "playback.hold_duration"))
        formStack.addArrangedSubview(makeHelpLabel(key: "playback.hold_duration_help"))
        configure(
            segmented: holdSegmented,
            items: Self.holdPresets.map { FormatStrings.string("format.seconds_short", $0) }
                + [L10n.string("common.custom")],
            action: #selector(handleControlChanged)
        )
        formStack.addArrangedSubview(holdSegmented)
        customField.placeholder = L10n.string("playback.hold_custom_title")
        customField.font = ThemeFont.body()
        customField.adjustsFontForContentSizeCategory = true
        customField.textColor = ThemeColor.textPrimary
        customField.borderStyle = .roundedRect
        customField.keyboardType = .numberPad
        customField.delegate = self
        customField.accessibilityLabel = L10n.string("playback.hold_custom_title")
        customField.addTarget(self, action: #selector(handleControlChanged), for: .editingChanged)
        configure(
            segmented: unitSegmented,
            items: [
                L10n.string("playback.unit_seconds"),
                L10n.string("playback.unit_minutes"),
            ],
            action: #selector(handleControlChanged)
        )
        let customRow = UIStackView(arrangedSubviews: [customField, unitSegmented])
        customRow.axis = .horizontal
        customRow.spacing = ThemeMetrics.space12
        customRow.distribution = .fillEqually
        formStack.addArrangedSubview(customRow)
        configureErrorLabel(holdErrorLabel)
        formStack.addArrangedSubview(holdErrorLabel)

        formStack.addArrangedSubview(makeSectionLabel(key: "playback.transition"))
        transitionMenuButton.contentHorizontalAlignment = .fill
        transitionMenuButton.showsMenuAsPrimaryAction = true
        transitionMenuButton.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
        applyTransitionIndex(transitionIndex)
        formStack.addArrangedSubview(transitionMenuButton)
        durationSlider.minimumValue = 0.2
        durationSlider.maximumValue = 3.0
        durationSlider.addTarget(self, action: #selector(handleDurationSlider), for: .valueChanged)
        durationSlider.accessibilityLabel = L10n.string("playback.transition_duration")
        let durationRow = UIStackView(arrangedSubviews: [durationSlider, durationValueLabel])
        durationRow.axis = .horizontal
        durationRow.spacing = ThemeMetrics.space12
        durationValueLabel.font = ThemeFont.meta()
        durationValueLabel.adjustsFontForContentSizeCategory = true
        durationValueLabel.textColor = ThemeColor.textSecondary
        durationValueLabel.setContentHuggingPriority(.required, for: .horizontal)
        formStack.addArrangedSubview(durationRow)
        durationDisabledLabel.text = L10n.string("playback.transition_duration_disabled")
        styleNoteLabel(durationDisabledLabel)
        formStack.addArrangedSubview(durationDisabledLabel)
        reduceMotionLabel.text = L10n.string("settings.reduce_motion_note")
        styleNoteLabel(reduceMotionLabel)
        formStack.addArrangedSubview(reduceMotionLabel)

        formStack.addArrangedSubview(makeSectionLabel(key: "playback.fit_mode"))
        for (index, button) in fitOptionButtons.enumerated() {
            button.tag = index
            button.addTarget(self, action: #selector(handleFitOption), for: .touchUpInside)
            formStack.addArrangedSubview(button)
        }
        configure(
            segmented: solidSegmented,
            items: [
                L10n.string("playback.solid_auto"),
                L10n.string("playback.solid_black"),
                L10n.string("playback.solid_warm_white"),
            ],
            action: #selector(handleFitChanged)
        )
        formStack.addArrangedSubview(solidSegmented)
        cropWarningLabel.text = L10n.string("playback.fit_crop_warning")
        styleNoteLabel(cropWarningLabel)
        cropWarningLabel.textColor = ThemeColor.statusWarning
        formStack.addArrangedSubview(cropWarningLabel)

        formStack.addArrangedSubview(makeSectionLabel(key: "playback.clock"))
        formStack.addArrangedSubview(makeSwitchRow(key: "playback.clock", toggle: clockSwitch))
        formStack.addArrangedSubview(makeSwitchRow(key: "playback.date", toggle: dateSwitch))
        formStack.addArrangedSubview(makeHelpLabel(key: "playback.overlay_position"))
        for (index, button) in positionButtons.enumerated() {
            button.tag = index
            button.addTarget(self, action: #selector(handlePositionChanged(_:)), for: .touchUpInside)
            formStack.addArrangedSubview(button)
        }
        applyPositionIndex(positionIndex)
        formStack.addArrangedSubview(makeHelpLabel(key: "playback.hour_format"))
        configure(
            segmented: hourSegmented,
            items: [
                L10n.string("settings.appearance_system"),
                L10n.string("playback.hour_12"),
                L10n.string("playback.hour_24"),
            ],
            action: #selector(handleControlChanged)
        )
        formStack.addArrangedSubview(hourSegmented)

        formStack.addArrangedSubview(makeSectionLabel(key: "playback.order"))
        configure(
            segmented: orderSegmented,
            items: [
                L10n.string("playback.order_captured"),
                L10n.string("playback.order_random"),
                L10n.string("playback.order_manual"),
            ],
            action: #selector(handleOrderChanged)
        )
        formStack.addArrangedSubview(orderSegmented)
        orderHintLabel.text = L10n.string("order.manual_hint")
        styleNoteLabel(orderHintLabel)
        formStack.addArrangedSubview(orderHintLabel)

        formStack.addArrangedSubview(makeSectionLabel(key: "playback.brightness"))
        configure(
            segmented: brightnessSegmented,
            items: [
                L10n.string("settings.appearance_system"),
                L10n.string("common.custom"),
            ],
            action: #selector(handleBrightnessModeChanged)
        )
        formStack.addArrangedSubview(brightnessSegmented)
        brightnessSlider.minimumValue = 0
        brightnessSlider.maximumValue = 1
        brightnessSlider.addTarget(self, action: #selector(handleBrightnessSliderChanged), for: .valueChanged)
        brightnessSlider.accessibilityLabel = L10n.string("playback.brightness")
        formStack.addArrangedSubview(brightnessSlider)
        let brightnessHelp = makeHelpLabel(key: "playback.brightness_restore_help")
        formStack.addArrangedSubview(brightnessHelp)
        formStack.addArrangedSubview(makeSwitchRow(
            key: "playback.keep_awake_paused",
            toggle: keepAwakeSwitch
        ))

        formStack.addArrangedSubview(makeSwitchRow(key: "settings.rest_window", toggle: restSwitch))
        formStack.addArrangedSubview(makeHelpLabel(key: "settings.rest_help"))
        restStartPicker.datePickerMode = .time
        restStartPicker.addTarget(self, action: #selector(handleControlChanged), for: .valueChanged)
        restStartPicker.accessibilityLabel = L10n.string("settings.rest_start")
        restEndPicker.datePickerMode = .time
        restEndPicker.addTarget(self, action: #selector(handleControlChanged), for: .valueChanged)
        restEndPicker.accessibilityLabel = L10n.string("settings.rest_end")
        restStartRow = makePickerRow(labelKey: "settings.rest_start", picker: restStartPicker)
        restEndRow = makePickerRow(labelKey: "settings.rest_end", picker: restEndPicker)
        formStack.addArrangedSubview(restStartRow)
        formStack.addArrangedSubview(restEndRow)
        configureErrorLabel(restErrorLabel)
        formStack.addArrangedSubview(restErrorLabel)

        formStack.addArrangedSubview(makeSectionLabel(key: "playback.quality"))
        configure(
            segmented: qualitySegmented,
            items: [
                L10n.string("playback.quality_auto"),
                L10n.string("playback.quality_balanced"),
                L10n.string("playback.quality_low"),
            ],
            action: #selector(handleControlChanged)
        )
        formStack.addArrangedSubview(qualitySegmented)

        formStack.addArrangedSubview(makeSectionLabel(key: "playback.music_title"))
        formStack.addArrangedSubview(makeHelpLabel(key: "playback.music_help"))
        musicButton.contentHorizontalAlignment = .fill
        musicButton.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
        musicButton.addTarget(self, action: #selector(handleMusic), for: .touchUpInside)
        applyMusicSelection()
        formStack.addArrangedSubview(musicButton)

    }


    private func load() {
        Task { [weak self] in
            guard let self,
                  let bundle = try? await environment.frameManager.framePlaybackBundle(frameID: frameID)
            else { return }
            let parsed = PlayerSettings.parse(json: bundle.settingsJSON)
            originalSettings = (try? JSONSerialization.jsonObject(
                with: Data(bundle.settingsJSON.utf8)
            )) as? [String: Any] ?? [:]
            originalOrderMode = bundle.orderMode

            if let preset = Self.holdPresets.firstIndex(of: parsed.holdSeconds) {
                holdSegmented.selectedSegmentIndex = preset
                customField.isHidden = true
                unitSegmented.isHidden = true
            } else {
                holdSegmented.selectedSegmentIndex = Self.holdPresets.count
                customField.text = "\(parsed.holdSeconds)"
                unitSegmented.selectedSegmentIndex = 0
            }
            applyTransitionIndex(Self.transitionWireValues.firstIndex(of: parsed.transition) ?? 1)
            durationSlider.value = Float(parsed.transitionSeconds)
            applyFitIndex(Self.fitWireValues.firstIndex(of: parsed.fitMode) ?? 0)
            solidSegmented.selectedSegmentIndex = Self.solidWireValues
                .firstIndex(of: parsed.solidColor ?? "") ?? 0
            clockSwitch.isOn = parsed.clockEnabled
            dateSwitch.isOn = parsed.dateEnabled
            applyPositionIndex(Self.positionWireValues.firstIndex(of: parsed.clockPosition) ?? 2)
            hourSegmented.selectedSegmentIndex = parsed.hour24.map { $0 ? 2 : 1 } ?? 0
            orderSegmented.selectedSegmentIndex = Self.orderWireValues
                .firstIndex(of: bundle.orderMode) ?? 0
            brightnessSegmented.selectedSegmentIndex = parsed.brightness == nil ? 0 : 1
            brightnessSlider.value = Float(parsed.brightness ?? 1)
            keepAwakeSwitch.isOn = parsed.keepAwakeWhenPaused
            restSwitch.isOn = parsed.restEnabled
            restStartPicker.date = Self.date(fromMinute: parsed.restStartMinute)
            restEndPicker.date = Self.date(fromMinute: parsed.restEndMinute)
            qualitySegmented.selectedSegmentIndex = Self.qualityWireValues
                .firstIndex(of: parsed.qualityLevel) ?? 0
            musicSelection = parsed.music
            applyMusicSelection()

            dirty = false
            refreshDependentUI()
            loadPreview()
        }
    }

    private func loadPreview() {
        Task { [weak self] in
            guard let self else { return }
            let relPaths = (try? await environment.frameManager
                .queueAssetRelPaths(frameID: frameID, limit: 2)) ?? []
            guard let first = relPaths.first else { return }
            let scale = view.traitCollection.displayScale
            previewImage = await loader.thumbnail(
                relPath: first,
                pointSize: ThemeMetrics.gridCellMax * 2,
                scale: scale
            )
            if relPaths.count > 1 {
                previewImageNext = await loader.thumbnail(
                    relPath: relPaths[1],
                    pointSize: ThemeMetrics.gridCellMax * 2,
                    scale: scale
                )
            } else {
                previewImageNext = nil
            }
            updatePreview()
        }
    }

    private func updatePreview() {
        guard let image = previewImage else { return }
        previewImageView.transform = .identity
        previewImageView.alpha = 1
        demoImageView.alpha = 0
        demoImageView.transform = .identity
        demoImageView.image = previewImageNext ?? previewImage
        let fitMode = Self.fitWireValues[safe: fitIndex] ?? "fit_blur"
        switch fitMode {
        case "fill_crop":
            previewImageView.contentMode = .scaleAspectFill
            previewImageView.image = image
            previewBackground.image = nil
            previewDim.isHidden = true
        case "fit_color":
            previewImageView.contentMode = .scaleAspectFit
            previewImageView.image = image
            previewDim.isHidden = true
            let solidValue = Self.solidWireValues[safe: solidSegmented.selectedSegmentIndex] ?? ""
            if !solidValue.isEmpty {
                previewBackground.image = nil
                previewBackground.backgroundColor = solidValue == "black"
                    ? ThemeColor.restSurface
                    : ThemeColor.panel
                return
            }
            Task { [weak self, playerLoader] in
                guard let hex = await playerLoader.edgeColorHex(of: image) else { return }
                guard let self else { return }
                previewBackground.image = nil
                previewBackground.backgroundColor = Self.color(fromHex: hex) ?? ThemeColor.canvas
            }
        default:
            previewImageView.contentMode = .scaleAspectFit
            previewImageView.image = image
            previewDim.isHidden = false
            Task { [weak self, playerLoader] in
                guard let blurred = await playerLoader.blurred(assetID: "preview", image: image) else {
                    return
                }
                self?.previewBackground.image = blurred
            }
        }
    }

    private func refreshDependentUI() {
        let reduced = UIAccessibility.isReduceMotionEnabled
        reduceMotionLabel.isHidden = !reduced
        if reduced {
            applyTransitionIndex(0)
        }
        transitionMenuButton.isEnabled = !reduced
        let transition = Self.transitionWireValues[safe: transitionIndex]
            ?? PlaybackTiming.transitionCrossfade
        let durationEnabled = transition != PlaybackTiming.transitionNone && !reduced
        durationSlider.isEnabled = durationEnabled
        durationDisabledLabel.isHidden = durationEnabled
        durationValueLabel.text = FormatStrings.string(
            "format.seconds_decimal",
            String(format: "%.1f", Double(Self.quantized(durationSlider.value)))
        )
        cropWarningLabel.isHidden = Self.fitWireValues[safe: fitIndex] != "fill_crop"
        solidSegmented.isHidden = Self.fitWireValues[safe: fitIndex] != "fit_color"
        orderHintLabel.isHidden = Self.orderWireValues[safe: orderSegmented.selectedSegmentIndex]
            != PlaybackQueue.orderManual
        brightnessSlider.isEnabled = brightnessSegmented.selectedSegmentIndex == 1
        holdErrorLabel.isHidden = true
        restErrorLabel.isHidden = true
        restStartRow.isHidden = !restSwitch.isOn
        restEndRow.isHidden = !restSwitch.isOn
    }

    private func applyPositionIndex(_ index: Int) {
        positionIndex = index
        for (buttonIndex, button) in positionButtons.enumerated() {
            button.isOptionSelected = buttonIndex == index
        }
    }

    @objc private func handlePositionChanged(_ sender: ActionButton) {
        applyPositionIndex(sender.tag)
        handleControlChanged()
    }

    @objc private func handleControlChanged() {
        dirty = true
        refreshDependentUI()
    }

    private func applyTransitionIndex(_ index: Int) {
        transitionIndex = index
        var configuration = UIButton.Configuration.plain()
        configuration.title = L10n.string(Self.transitionTitleKeys[safe: index]
            ?? Self.transitionTitleKeys[1])
        configuration.image = UIImage(systemName: "chevron.down")
        configuration.imagePlacement = .trailing
        configuration.imagePadding = ThemeMetrics.space12
        configuration.contentInsets = NSDirectionalEdgeInsets(
            top: ThemeMetrics.space12, leading: ThemeMetrics.space16,
            bottom: ThemeMetrics.space12, trailing: ThemeMetrics.space16
        )
        configuration.titleAlignment = .leading
        configuration.baseForegroundColor = ThemeColor.textPrimary
        configuration.background.backgroundColor = ThemeColor.panel
        configuration.background.cornerRadius = ThemeMetrics.radiusGroup
        configuration.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer {
            var attributes = $0
            attributes.font = ThemeFont.body()
            return attributes
        }
        transitionMenuButton.configuration = configuration
        transitionMenuButton.menu = UIMenu(children: Self.transitionTitleKeys.enumerated().map {
            (index, key) in
            UIAction(
                title: L10n.string(key),
                state: index == transitionIndex ? .on : .off
            ) { [weak self] _ in
                self?.handleTransitionSelection(index)
            }
        })
        transitionMenuButton.accessibilityLabel = L10n.string("playback.transition")
    }

    private func handleTransitionSelection(_ index: Int) {
        guard index != transitionIndex else { return }
        applyTransitionIndex(index)
        dirty = true
        refreshDependentUI()
        playTransitionDemoOnce()
    }

    private func demoDuration() -> TimeInterval {
        let transition = Self.transitionWireValues[safe: transitionIndex]
            ?? PlaybackTiming.transitionCrossfade
        let milliseconds = transition == PlaybackTiming.transitionNone
            ? 250
            : max(Int(Self.quantized(durationSlider.value) * 1000), 250)
        return TimeInterval(milliseconds) / 1000
    }

    private func playTransitionDemoOnce() {
        guard !isDemoRunning, previewImageView.image != nil else { return }
        let transition = Self.transitionWireValues[safe: transitionIndex]
            ?? PlaybackTiming.transitionCrossfade
        guard transition != PlaybackTiming.transitionNone else {
            previewImageView.alpha = 0
            UIView.animate(
                withDuration: 0.2,
                animations: { self.previewImageView.alpha = 1 }
            )
            return
        }
        let width = max(previewContainer.bounds.width, 1)
        let demoImage = previewImageNext ?? previewImage
        let previous = previewImage
        guard let demoImage else { return }
        isDemoRunning = true
        previewContainer.bringSubviewToFront(demoImageView)
        demoImageView.transform = .identity
        demoImageView.image = demoImage
        switch transition {
        case PlaybackTiming.transitionCrossfade:
            demoImageView.alpha = 0
        case PlaybackTiming.transitionSlide:
            demoImageView.alpha = 1
            demoImageView.transform = CGAffineTransform(translationX: width, y: 0)
        case PlaybackTiming.transitionWipe:
            demoImageView.alpha = 1
            demoImageView.transform = CGAffineTransform(translationX: width, y: 0)
        case PlaybackTiming.transitionZoom:
            demoImageView.alpha = 0
            demoImageView.transform = CGAffineTransform(scaleX: 1.08, y: 1.08)
        case PlaybackTiming.transitionRipple:
            demoImageView.alpha = 0
            demoImageView.transform = CGAffineTransform(scaleX: 0.9, y: 0.9)
        default:
            demoImageView.alpha = 0
        }
        UIView.animate(
            withDuration: demoDuration(),
            delay: 0,
            options: [.curveEaseInOut, .beginFromCurrentState],
            animations: {
                self.demoImageView.alpha = 1
                self.demoImageView.transform = .identity
            },
            completion: { _ in
                UIView.animate(
                    withDuration: 0.2,
                    animations: { self.previewImageView.alpha = 0 },
                    completion: { _ in
                        self.previewImageView.image = demoImage
                        self.previewImageView.transform = .identity
                        self.demoImageView.alpha = 0
                        self.demoImageView.transform = .identity
                        self.previewImageView.alpha = 1
                        self.previewImage = demoImage
                        self.previewImageNext = previous
                        self.isDemoRunning = false
                    }
                )
            }
        )
    }

    @objc private func handleDurationSlider() {
        dirty = true
        durationSlider.value = Self.quantized(durationSlider.value)
        refreshDependentUI()
    }

    @objc private func handleFitChanged() {
        dirty = true
        refreshDependentUI()
        updatePreview()
    }

    private func applyFitIndex(_ index: Int) {
        fitIndex = index
        for (i, button) in fitOptionButtons.enumerated() {
            button.isOptionSelected = i == index
        }
    }

    @objc private func handleFitOption(_ sender: ActionButton) {
        guard sender.tag != fitIndex else { return }
        applyFitIndex(sender.tag)
        dirty = true
        refreshDependentUI()
        updatePreview()
    }

    @objc private func handleManagePhotos() {
        let editor = FrameEditViewController(environment: environment, frameID: frameID)
        editor.modalPresentationStyle = .fullScreen
        present(editor, animated: true)
    }

    private func applyMusicSelection() {
        var configuration = UIButton.Configuration.plain()
        configuration.title = MusicCatalog.displayName(for: musicSelection)
        configuration.image = UIImage(systemName: "chevron.right")
        configuration.imagePlacement = .trailing
        configuration.imagePadding = ThemeMetrics.space12
        configuration.contentInsets = NSDirectionalEdgeInsets(
            top: ThemeMetrics.space12, leading: ThemeMetrics.space16,
            bottom: ThemeMetrics.space12, trailing: ThemeMetrics.space16
        )
        configuration.titleAlignment = .leading
        configuration.baseForegroundColor = ThemeColor.textPrimary
        configuration.background.backgroundColor = ThemeColor.panel
        configuration.background.cornerRadius = ThemeMetrics.radiusGroup
        configuration.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer {
            var attributes = $0
            attributes.font = ThemeFont.body()
            return attributes
        }
        musicButton.configuration = configuration
        musicButton.accessibilityLabel = L10n.string("playback.music_title")
    }

    @objc private func handleMusic() {
        let picker = MusicPickerViewController(selection: musicSelection) { [weak self] updated in
            guard let self else { return }
            musicSelection = updated
            applyMusicSelection()
            dirty = true
        }
        picker.modalPresentationStyle = .fullScreen
        present(picker, animated: true)
    }

    @objc private func handleOrderChanged() {
        dirty = true
        refreshDependentUI()
    }

    @objc private func handleBrightnessModeChanged() {
        dirty = true
        refreshDependentUI()
        if brightnessSegmented.selectedSegmentIndex == 1 {
            applyBrightnessPreview(brightnessSlider.value)
        } else {
            restoreBrightnessPreview()
        }
    }

    @objc private func handleBrightnessSliderChanged() {
        handleControlChanged()
        if brightnessSegmented.selectedSegmentIndex == 1 {
            applyBrightnessPreview(brightnessSlider.value)
        }
    }

    private func applyBrightnessPreview(_ value: Float) {
        if originalScreenBrightness == nil {
            originalScreenBrightness = UIScreen.main.brightness
        }
        UIScreen.main.brightness = CGFloat(min(max(value, 0), 1))
        brightnessPreviewApplied = true
    }

    private func restoreBrightnessPreview() {
        guard brightnessPreviewApplied, let original = originalScreenBrightness else { return }
        UIScreen.main.brightness = original
        brightnessPreviewApplied = false
    }

    @objc private func handleBack() {
        guard dirty else {
            dismiss(animated: true)
            return
        }
        let alert = UIAlertController(
            title: L10n.string("playback.unsaved_title"),
            message: nil,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(
            title: L10n.string("common.discard"),
            style: .destructive,
            handler: { [weak self] _ in
                self?.restoreBrightnessPreview()
                self?.dismiss(animated: true)
            }
        ))
        alert.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel
        ))
        present(alert, animated: true)
    }

    @objc private func handleSave() {
        guard let holdSeconds = validatedHoldSeconds() else { return }
        guard validatedRest() else { return }

        var settings = originalSettings
        settings["holdSeconds"] = holdSeconds
        let transition = Self.transitionWireValues[safe: transitionIndex]
            ?? PlaybackTiming.transitionCrossfade
        settings["transition"] = transition
        settings["transitionSeconds"] = Double(Self.quantized(durationSlider.value))
        settings["fitMode"] = Self.fitWireValues[safe: fitIndex] ?? "fit_blur"
        let solidValue = Self.solidWireValues[safe: solidSegmented.selectedSegmentIndex] ?? ""
        settings["solidColor"] = solidValue.isEmpty ? NSNull() : solidValue
        var clock = settings["clock"] as? [String: Any] ?? [:]
        clock["enabled"] = clockSwitch.isOn
        clock["position"] = Self.positionWireValues[safe: positionIndex]
            ?? "bottom_leading"
        switch hourSegmented.selectedSegmentIndex {
        case 1: clock["hour24"] = false
        case 2: clock["hour24"] = true
        default: clock["hour24"] = NSNull()
        }
        settings["clock"] = clock
        var date = settings["date"] as? [String: Any] ?? [:]
        date["enabled"] = dateSwitch.isOn
        settings["date"] = date
        settings["keepAwakeWhenPaused"] = keepAwakeSwitch.isOn
        var rest = settings["rest"] as? [String: Any] ?? [:]
        rest["enabled"] = restSwitch.isOn
        rest["startMinute"] = Self.minute(from: restStartPicker.date)
        rest["endMinute"] = Self.minute(from: restEndPicker.date)
        settings["rest"] = rest
        if brightnessSegmented.selectedSegmentIndex == 1 {
            settings["brightness"] = Double(brightnessSlider.value)
        } else {
            settings["brightness"] = NSNull()
        }
        settings["qualityLevel"] = Self.qualityWireValues[safe: qualitySegmented.selectedSegmentIndex]
            ?? "auto"
        var music = settings["music"] as? [String: Any] ?? [:]
        music["enabled"] = musicSelection.enabled
        music["source"] = musicSelection.source
        music["trackId"] = musicSelection.trackId ?? NSNull()
        music["localPath"] = musicSelection.localPath ?? NSNull()
        music["localTitle"] = musicSelection.localTitle ?? NSNull()
        settings["music"] = music

        guard let data = try? JSONSerialization.data(withJSONObject: settings, options: [.sortedKeys]) else {
            return
        }
        let json = String(decoding: data, as: UTF8.self)
        let orderMode = Self.orderWireValues[safe: orderSegmented.selectedSegmentIndex]
            ?? PlaybackQueue.orderCapturedAsc
        Task { [weak self] in
            guard let self else { return }
            guard (try? await environment.frameManager.updateSettingsJSON(
                frameID: frameID,
                settingsJSON: json
            )) != nil else { return }
            if orderMode != originalOrderMode {
                try? await environment.frameManager.updateOrderMode(frameID: frameID, orderMode: orderMode)
            }
            dirty = false
            restoreBrightnessPreview()
            dismiss(animated: true)
        }
    }

    private func validatedHoldSeconds() -> Int? {
        let presetIndex = holdSegmented.selectedSegmentIndex
        if presetIndex >= 0, presetIndex < Self.holdPresets.count {
            holdErrorLabel.isHidden = true
            return Self.holdPresets[presetIndex]
        }
        guard let text = customField.text, let raw = Int(text) else {
            holdErrorLabel.text = L10n.string("playback.invalid_number")
            holdErrorLabel.isHidden = false
            return nil
        }
        let seconds: Int
        if unitSegmented.selectedSegmentIndex == 1 {
            let scaled = raw.multipliedReportingOverflow(by: 60)
            guard !scaled.overflow else {
                holdErrorLabel.text = FormatStrings.string(
                    "playback.out_of_range",
                    PlaybackTiming.holdMinSeconds,
                    PlaybackTiming.holdMaxSeconds
                )
                holdErrorLabel.isHidden = false
                return nil
            }
            seconds = scaled.partialValue
        } else {
            seconds = raw
        }
        guard PlaybackTiming.isValidHoldSeconds(Double(seconds)) else {
            holdErrorLabel.text = FormatStrings.string(
                "playback.out_of_range",
                PlaybackTiming.holdMinSeconds,
                PlaybackTiming.holdMaxSeconds
            )
            holdErrorLabel.isHidden = false
            return nil
        }
        holdErrorLabel.isHidden = true
        return seconds
    }

    private func validatedRest() -> Bool {
        guard restSwitch.isOn else {
            restErrorLabel.isHidden = true
            return true
        }
        let start = Self.minute(from: restStartPicker.date)
        let end = Self.minute(from: restEndPicker.date)
        guard start != end else {
            restErrorLabel.text = L10n.string("settings.rest_invalid")
            restErrorLabel.isHidden = false
            return false
        }
        restErrorLabel.isHidden = true
        return true
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        return true
    }

    private static func quantized(_ value: Float) -> Float {
        Float((Double(value) * 10).rounded() / 10)
    }

    private static func date(fromMinute minute: Int) -> Date {
        var components = DateComponents()
        components.hour = minute / 60
        components.minute = minute % 60
        return Calendar.current.date(from: components) ?? Date()
    }

    private static func minute(from date: Date) -> Int {
        let components = Calendar.current.dateComponents([.hour, .minute], from: date)
        return (components.hour ?? 0) * 60 + (components.minute ?? 0)
    }

    private static func color(fromHex hex: String) -> UIColor? {
        var raw = hex
        if raw.hasPrefix("#") { raw.removeFirst() }
        guard raw.count == 6, let value = UInt32(raw, radix: 16) else { return nil }
        return UIColor(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: 1
        )
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

    private func makeSectionLabel(key: String) -> UILabel {
        let label = UILabel()
        label.text = L10n.string(key)
        label.font = ThemeFont.body()
        label.adjustsFontForContentSizeCategory = true
        label.textColor = ThemeColor.textPrimary
        label.numberOfLines = 0
        label.accessibilityTraits.insert(.header)
        return label
    }

    private func makeHelpLabel(key: String) -> UILabel {
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

    private func configureErrorLabel(_ label: UILabel) {
        label.font = ThemeFont.meta()
        label.adjustsFontForContentSizeCategory = true
        label.textColor = ThemeColor.statusError
        label.numberOfLines = 0
        label.isHidden = true
    }

    private func makeSwitchRow(key: String, toggle: UISwitch) -> UIView {
        toggle.onTintColor = ThemeColor.accentAction
        toggle.addTarget(self, action: #selector(handleControlChanged), for: .valueChanged)
        toggle.accessibilityLabel = L10n.string(key)
        let label = UILabel()
        label.text = L10n.string(key)
        label.font = ThemeFont.body()
        label.adjustsFontForContentSizeCategory = true
        label.textColor = ThemeColor.textPrimary
        label.numberOfLines = 0
        let row = UIStackView(arrangedSubviews: [label, toggle])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = ThemeMetrics.space12
        row.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
        return row
    }

    private func makePickerRow(labelKey: String, picker: UIDatePicker) -> UIView {
        let label = UILabel()
        label.text = L10n.string(labelKey)
        label.font = ThemeFont.body()
        label.adjustsFontForContentSizeCategory = true
        label.textColor = ThemeColor.textPrimary
        let row = UIStackView(arrangedSubviews: [label, picker])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = ThemeMetrics.space12
        row.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
        return row
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
