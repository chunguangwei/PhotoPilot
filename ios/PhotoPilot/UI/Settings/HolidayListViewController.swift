import UIKit

final class HolidayListViewController: UIViewController, UITableViewDataSource, UITableViewDelegate {
    private let environment: AppEnvironment
    private var rules: [HolidayRule] = []

    private let emptyLabel = UILabel()
    private let table = UITableView(frame: .zero, style: .plain)
    private let addButton = ActionButton(titleKey: "holiday.add", style: .primary)

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
        enableEdgeSwipeBack { [weak self] in self?.handleBack() }
        view.backgroundColor = ThemeColor.canvas

        let topBar = ScreenHeaderView(title: L10n.string("holiday.title"))
        topBar.onBack = { [weak self] in self?.handleBack() }
        topBar.pinToTop(of: view)

        emptyLabel.text = L10n.string("holiday.empty")
        emptyLabel.font = ThemeFont.body()
        emptyLabel.adjustsFontForContentSizeCategory = true
        emptyLabel.textColor = ThemeColor.textSecondary
        emptyLabel.numberOfLines = 0
        emptyLabel.textAlignment = .center
        emptyLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(emptyLabel)

        table.dataSource = self
        table.delegate = self
        table.backgroundColor = .clear
        table.separatorColor = ThemeColor.separator
        table.register(HolidayRuleCell.self, forCellReuseIdentifier: HolidayRuleCell.reuseID)
        table.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(table)

        addButton.addTarget(self, action: #selector(handleAdd), for: .touchUpInside)
        addButton.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(addButton)

        NSLayoutConstraint.activate([
            table.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: ThemeMetrics.space12),
            table.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            table.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            table.bottomAnchor.constraint(equalTo: addButton.topAnchor, constant: -ThemeMetrics.space12),
            emptyLabel.centerXAnchor.constraint(equalTo: table.centerXAnchor),
            emptyLabel.centerYAnchor.constraint(equalTo: table.centerYAnchor),
            emptyLabel.leadingAnchor.constraint(
                greaterThanOrEqualTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInset
            ),
            emptyLabel.trailingAnchor.constraint(
                lessThanOrEqualTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            addButton.leadingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            addButton.trailingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            addButton.bottomAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space16
            ),
        ])
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        reload()
    }

    private func reload() {
        Task { [weak self] in
            guard let self else { return }
            let fetched = (try? await self.environment.holidayStore.rules()) ?? []
            self.rules = fetched
            self.emptyLabel.isHidden = !fetched.isEmpty
            self.table.reloadData()
        }
    }

    private func openEditor(_ rule: HolidayRule?) {
        let editor = HolidayEditViewController(environment: environment, rule: rule)
        editor.modalPresentationStyle = .fullScreen
        present(editor, animated: true)
    }

    @objc private func handleBack() {
        dismiss(animated: true)
    }

    @objc private func handleAdd() {
        openEditor(nil)
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        rules.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: HolidayRuleCell.reuseID, for: indexPath)
        guard let ruleCell = cell as? HolidayRuleCell else { return cell }
        let rule = rules[indexPath.row]
        ruleCell.configure(rule: rule)
        ruleCell.onToggle = { [weak self] enabled in
            Task {
                try? await self?.environment.holidayStore.setEnabled(ruleID: rule.id, enabled: enabled)
                await MainActor.run { self?.reload() }
            }
        }
        return ruleCell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard indexPath.row < rules.count else { return }
        openEditor(rules[indexPath.row])
    }

    func tableView(
        _ tableView: UITableView,
        trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath
    ) -> UISwipeActionsConfiguration? {
        guard indexPath.row < rules.count else { return nil }
        let rule = rules[indexPath.row]
        let delete = UIContextualAction(style: .destructive, title: L10n.string("common.delete")) { [weak self] _, _, done in
            Task {
                try? await self?.environment.holidayStore.delete(ruleID: rule.id)
                await MainActor.run {
                    self?.reload()
                    done(true)
                }
            }
        }
        return UISwipeActionsConfiguration(actions: [delete])
    }
}

private final class HolidayRuleCell: UITableViewCell {
    static let reuseID = "HolidayRuleCell"

    var onToggle: ((Bool) -> Void)?

    private let nameLabel = UILabel()
    private let detailLabel = UILabel()
    private let enabledSwitch = UISwitch()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        backgroundColor = .clear
        contentView.backgroundColor = .clear

        nameLabel.font = ThemeFont.body()
        nameLabel.adjustsFontForContentSizeCategory = true
        nameLabel.textColor = ThemeColor.textPrimary
        nameLabel.numberOfLines = 0

        detailLabel.font = ThemeFont.meta()
        detailLabel.adjustsFontForContentSizeCategory = true
        detailLabel.textColor = ThemeColor.textSecondary
        detailLabel.numberOfLines = 0

        enabledSwitch.onTintColor = ThemeColor.accentAction
        enabledSwitch.addTarget(self, action: #selector(handleToggle), for: .valueChanged)

        let textStack = UIStackView(arrangedSubviews: [nameLabel, detailLabel])
        textStack.axis = .vertical
        textStack.spacing = ThemeMetrics.space4
        textStack.isUserInteractionEnabled = false

        let row = UIStackView(arrangedSubviews: [textStack, enabledSwitch])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = ThemeMetrics.space12
        row.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(row)

        NSLayoutConstraint.activate([
            row.topAnchor.constraint(equalTo: contentView.topAnchor, constant: ThemeMetrics.space12),
            row.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -ThemeMetrics.space12),
            row.leadingAnchor.constraint(
                equalTo: contentView.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            row.trailingAnchor.constraint(
                equalTo: contentView.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            enabledSwitch.heightAnchor.constraint(greaterThanOrEqualToConstant: ThemeMetrics.touchTarget),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    func configure(rule: HolidayRule) {
        nameLabel.text = rule.name
        detailLabel.text = Self.detailText(for: rule)
        enabledSwitch.isOn = rule.enabled
        isAccessibilityElement = false
        enabledSwitch.accessibilityLabel = rule.name
        accessibilityElements = [nameLabel, detailLabel, enabledSwitch]
    }

    @objc private func handleToggle() {
        onToggle?(enabledSwitch.isOn)
    }

    static func detailText(for rule: HolidayRule) -> String {
        var text = "\(rule.startDate) ~ \(rule.endDate)"
        if let start = rule.startTime, let end = rule.endTime {
            text += " · \(timeText(start))–\(timeText(end))"
        } else {
            text += " · \(L10n.string("common.none"))"
        }
        return text
    }

    static func timeText(_ minute: Int) -> String {
        String(format: "%02d:%02d", minute / 60, minute % 60)
    }
}
