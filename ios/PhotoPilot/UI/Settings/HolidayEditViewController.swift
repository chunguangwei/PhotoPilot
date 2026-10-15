import UIKit

final class HolidayEditViewController: UIViewController, UITextFieldDelegate {
    private let environment: AppEnvironment
    private let existing: HolidayRule?

    private let nameField = UITextField()
    private let startPicker = UIDatePicker()
    private let endPicker = UIDatePicker()
    private let timeStartPicker = UIDatePicker()
    private let timeEndPicker = UIDatePicker()
    private let timeHelpLabel = UILabel()
    private let frameValueLabel = UILabel()
    private let frameButton = ActionButton(titleKey: "holiday.frame_placeholder", style: .secondary)
    private let timeSectionLabel = UILabel()
    private let enabledSwitch = UISwitch()
    private let saveButton = ActionButton(titleKey: "common.save", style: .primary)

    private var frames: [FrameCardDTO] = []
    private var selectedFrameID: String?
    private var hasTimeStart = false
    private var hasTimeEnd = false

    init(environment: AppEnvironment, rule: HolidayRule?) {
        self.environment = environment
        self.existing = rule
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

        let topBar = ScreenHeaderView(title: L10n.string("holiday.title"))
        topBar.onBack = { [weak self] in self?.handleBack() }

        nameField.placeholder = L10n.string("holiday.name")
        nameField.font = ThemeFont.body()
        nameField.adjustsFontForContentSizeCategory = true
        nameField.textColor = ThemeColor.textPrimary
        nameField.borderStyle = .roundedRect
        nameField.delegate = self
        nameField.returnKeyType = .done
        nameField.accessibilityLabel = L10n.string("holiday.name")
        nameField.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true

        configure(startPicker, mode: .date, labelKey: "holiday.date_start")
        configure(endPicker, mode: .date, labelKey: "holiday.date_end")
        configure(timeStartPicker, mode: .time, labelKey: "holiday.time_range")
        configure(timeEndPicker, mode: .time, labelKey: "holiday.time_range")

        timeSectionLabel.text = L10n.string("holiday.time_range")
        timeSectionLabel.font = ThemeFont.body()
        timeSectionLabel.adjustsFontForContentSizeCategory = true
        timeSectionLabel.textColor = ThemeColor.textPrimary
        timeSectionLabel.accessibilityTraits.insert(.header)

        timeHelpLabel.text = L10n.string("holiday.time_help")
        timeHelpLabel.font = ThemeFont.meta()
        timeHelpLabel.adjustsFontForContentSizeCategory = true
        timeHelpLabel.textColor = ThemeColor.textSecondary
        timeHelpLabel.numberOfLines = 0

        let clearButton = ActionButton(titleKey: "common.none", style: .secondary)
        clearButton.addTarget(self, action: #selector(handleClearTime), for: .touchUpInside)
        let timeHeader = UIStackView(arrangedSubviews: [timeSectionLabel, UIView(), clearButton])
        timeHeader.axis = .horizontal
        timeHeader.alignment = .center
        timeHeader.spacing = ThemeMetrics.space12

        frameValueLabel.font = ThemeFont.body()
        frameValueLabel.adjustsFontForContentSizeCategory = true
        frameValueLabel.textColor = ThemeColor.textSecondary
        frameValueLabel.numberOfLines = 0
        frameButton.setContentHuggingPriority(.required, for: .horizontal)
        frameButton.addTarget(self, action: #selector(handleChooseFrame), for: .touchUpInside)
        let frameRow = UIStackView(arrangedSubviews: [makeLabel("holiday.frame"), UIView(), frameValueLabel, frameButton])
        frameRow.axis = .horizontal
        frameRow.alignment = .center
        frameRow.spacing = ThemeMetrics.space12
        frameRow.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true

        enabledSwitch.onTintColor = ThemeColor.accentAction
        enabledSwitch.accessibilityLabel = L10n.string("holiday.enabled")
        let enabledRow = UIStackView(arrangedSubviews: [makeLabel("holiday.enabled"), UIView(), enabledSwitch])
        enabledRow.axis = .horizontal
        enabledRow.alignment = .center
        enabledRow.spacing = ThemeMetrics.space12
        enabledRow.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true

        saveButton.addTarget(self, action: #selector(handleSave), for: .touchUpInside)

        let form = UIStackView(arrangedSubviews: [
            topBar,
            makeLabel("holiday.name"),
            nameField,
            makeLabel("holiday.date_range"),
            pickerRow(labelKey: "holiday.date_start", picker: startPicker),
            pickerRow(labelKey: "holiday.date_end", picker: endPicker),
            timeHeader,
            pickerRow(labelKey: "holiday.date_start", picker: timeStartPicker),
            pickerRow(labelKey: "holiday.date_end", picker: timeEndPicker),
            timeHelpLabel,
            frameRow,
            enabledRow,
            saveButton,
        ])
        form.axis = .vertical
        form.spacing = ThemeMetrics.space12
        form.translatesAutoresizingMaskIntoConstraints = false
        let scrollView = UIScrollView()
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(form)
        view.addSubview(scrollView)

        let readable = form.widthAnchor.constraint(equalToConstant: ThemeMetrics.bodyMaxWidth)
        readable.priority = .defaultHigh
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
            scrollView.bottomAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.bottomAnchor),
            form.topAnchor.constraint(
                equalTo: scrollView.contentLayoutGuide.topAnchor,
                constant: ThemeMetrics.space8
            ),
            form.centerXAnchor.constraint(equalTo: scrollView.frameLayoutGuide.centerXAnchor),
            form.leadingAnchor.constraint(
                greaterThanOrEqualTo: scrollView.frameLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            form.trailingAnchor.constraint(
                lessThanOrEqualTo: scrollView.frameLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            form.bottomAnchor.constraint(
                equalTo: scrollView.contentLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space8
            ),
            readable,
        ])

        enableKeyboardDismissal()
        applyRule()
        reloadFrames()
    }

    private func applyRule() {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: Date())
        if let rule = existing {
            nameField.text = rule.name
            startPicker.date = Self.date(fromDay: rule.startDate) ?? today
            endPicker.date = Self.date(fromDay: rule.endDate) ?? today
            if let start = rule.startTime {
                timeStartPicker.date = Self.date(fromMinute: start)
                hasTimeStart = true
            } else {
                timeStartPicker.date = Self.date(fromMinute: 540)
            }
            if let end = rule.endTime {
                timeEndPicker.date = Self.date(fromMinute: end)
                hasTimeEnd = true
            } else {
                timeEndPicker.date = Self.date(fromMinute: 1080)
            }
            selectedFrameID = rule.frameID
            enabledSwitch.isOn = rule.enabled
        } else {
            startPicker.date = today
            endPicker.date = today
            timeStartPicker.date = Self.date(fromMinute: 540)
            timeEndPicker.date = Self.date(fromMinute: 1080)
            enabledSwitch.isOn = true
        }
        updateFrameValueLabel()
    }

    private func reloadFrames() {
        Task { [weak self] in
            guard let self else { return }
            self.frames = (try? await self.environment.frameManager.frameCards()) ?? []
            self.updateFrameValueLabel()
        }
    }

    private func updateFrameValueLabel() {
        if let selectedFrameID, let card = frames.first(where: { $0.id == selectedFrameID }) {
            frameValueLabel.text = card.name
            frameButton.setTitleKey("common.edit")
        } else {
            frameValueLabel.text = nil
            frameButton.setTitleKey("holiday.frame_placeholder")
        }
    }

    @objc private func handleBack() {
        dismiss(animated: true)
    }

    @objc private func handleClearTime() {
        hasTimeStart = false
        hasTimeEnd = false
    }

    @objc private func handleChooseFrame() {
        guard !frames.isEmpty else { return }
        let sheet = UIAlertController(title: L10n.string("holiday.frame"), message: nil, preferredStyle: .actionSheet)
        for card in frames {
            let action = UIAlertAction(title: card.name, style: .default) { [weak self] _ in
                self?.selectedFrameID = card.id
                self?.updateFrameValueLabel()
            }
            if card.id == selectedFrameID {
                action.setValue(true, forKey: "checked")
            }
            sheet.addAction(action)
        }
        sheet.addAction(UIAlertAction(title: L10n.string("common.cancel"), style: .cancel))
        sheet.popoverPresentationController?.sourceView = frameButton
        present(sheet, animated: true)
    }

    @objc private func handleSave() {
        view.endEditing(true)
        let name = nameField.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let startDate = Self.dayString(from: startPicker.date)
        let endDate = Self.dayString(from: endPicker.date)
        let startTime = hasTimeStart ? Self.minute(from: timeStartPicker.date) : nil
        let endTime = hasTimeEnd ? Self.minute(from: timeEndPicker.date) : nil
        let enabled = enabledSwitch.isOn
        guard !name.isEmpty, !startDate.isEmpty, !endDate.isEmpty, endDate >= startDate,
              let frameID = selectedFrameID, frames.contains(where: { $0.id == frameID }) else {
            presentInvalid()
            return
        }
        if (startTime == nil) != (endTime == nil) {
            presentInvalid()
            return
        }
        let candidate = HolidayRule(
            id: existing?.id ?? "candidate",
            frameID: frameID,
            name: name,
            startDate: startDate,
            endDate: endDate,
            startTime: startTime,
            endTime: endTime,
            position: existing?.position ?? 0,
            enabled: enabled
        )
        Task { [weak self] in
            guard let self else { return }
            let rules = (try? await self.environment.holidayStore.rules()) ?? []
            let conflict = rules.first {
                $0.id != candidate.id && $0.enabled && HolidayTiming.overlap(a: $0, b: candidate)
            }
            if let conflict {
                self.presentConflict(conflictName: conflict.name, candidate: candidate)
            } else {
                self.persist(candidate)
            }
        }
    }

    private func presentInvalid() {
        let alert = UIAlertController(
            title: L10n.string("holiday.invalid"),
            message: nil,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: L10n.string("common.close"), style: .cancel))
        present(alert, animated: true)
    }

    private func presentConflict(conflictName: String, candidate: HolidayRule) {
        let alert = UIAlertController(
            title: L10n.string("holiday.conflict_title"),
            message: L10n.format("holiday.conflict_body", conflictName),
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: L10n.string("common.cancel"), style: .cancel))
        alert.addAction(UIAlertAction(
            title: L10n.string("holiday.conflict_confirm"),
            style: .default,
            handler: { [weak self] _ in self?.persist(candidate) }
        ))
        present(alert, animated: true)
    }

    private func persist(_ rule: HolidayRule) {
        Task { [weak self] in
            guard let self else { return }
            do {
                if let existing {
                    try await self.environment.holidayStore.update(
                        ruleID: existing.id,
                        name: rule.name,
                        startDate: rule.startDate,
                        endDate: rule.endDate,
                        startTime: rule.startTime,
                        endTime: rule.endTime,
                        frameID: rule.frameID,
                        enabled: rule.enabled
                    )
                } else {
                    _ = try await self.environment.holidayStore.create(
                        name: rule.name,
                        startDate: rule.startDate,
                        endDate: rule.endDate,
                        startTime: rule.startTime,
                        endTime: rule.endTime,
                        frameID: rule.frameID,
                        enabled: rule.enabled
                    )
                }
                self.dismiss(animated: true)
            } catch {
                self.presentInvalid()
            }
        }
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        return true
    }

    private func makeLabel(_ key: String) -> UILabel {
        let label = UILabel()
        label.text = L10n.string(key)
        label.font = ThemeFont.body()
        label.adjustsFontForContentSizeCategory = true
        label.textColor = ThemeColor.textPrimary
        label.numberOfLines = 0
        return label
    }

    private func configure(_ picker: UIDatePicker, mode: UIDatePicker.Mode, labelKey: String) {
        picker.datePickerMode = mode
        picker.timeZone = .current
        picker.addTarget(self, action: #selector(handleTimePicked), for: .valueChanged)
        picker.accessibilityLabel = L10n.string(labelKey)
    }

    @objc private func handleTimePicked(_ sender: UIDatePicker) {
        if sender === timeStartPicker {
            hasTimeStart = true
        } else if sender === timeEndPicker {
            hasTimeEnd = true
        }
    }

    private func pickerRow(labelKey: String, picker: UIDatePicker) -> UIView {
        let label = makeLabel(labelKey)
        let row = UIStackView(arrangedSubviews: [label, picker])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = ThemeMetrics.space12
        row.heightAnchor.constraint(
            greaterThanOrEqualToConstant: ThemeMetrics.touchTarget
        ).isActive = true
        return row
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

    private static func dayString(from date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    private static func date(fromDay day: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.date(from: day)
    }
}

private extension NSLayoutConstraint {
    func withPriority(_ level: UILayoutPriority) -> NSLayoutConstraint {
        priority = level
        return self
    }
}
