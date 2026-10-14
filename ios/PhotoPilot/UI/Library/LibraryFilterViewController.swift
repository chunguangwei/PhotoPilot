import UIKit

struct LibraryQuery: Sendable, Equatable {
    enum Sort: Int, Sendable {
        case capturedDesc
        case capturedAsc
        case importedDesc
        case nameAsc
    }

    var searchText = ""
    var sourceIDs: Set<String> = []
    var orientations: Set<String> = []
    var statuses: Set<String> = []
    var noDateOnly = false
    var sort: Sort = .capturedDesc

    var isDefault: Bool {
        searchText.isEmpty
            && sourceIDs.isEmpty
            && orientations.isEmpty
            && statuses.isEmpty
            && !noDateOnly
            && sort == .capturedDesc
    }
}

final class LibraryFilterViewController: UIViewController, UITableViewDataSource, UITableViewDelegate {
    private var query: LibraryQuery
    private let sources: [SourceOptionDTO]
    private let onChange: (LibraryQuery) -> Void
    private let table = UITableView(frame: .zero, style: .insetGrouped)

    private static let orientationValues = ["landscape", "portrait", "square"]
    private static let statusValues = ["ready", "failed", "pending"]
    private static let sortValues: [LibraryQuery.Sort] = [
        .capturedDesc, .capturedAsc, .importedDesc, .nameAsc,
    ]

    init(
        query: LibraryQuery,
        sources: [SourceOptionDTO],
        onChange: @escaping (LibraryQuery) -> Void
    ) {
        self.query = query
        self.sources = sources
        self.onChange = onChange
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
        titleLabel.text = L10n.string("common.filter")
        titleLabel.font = ThemeFont.heading()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.accessibilityTraits.insert(.header)

        let clearButton = ActionButton(titleKey: "common.clear_filter", style: .secondary)
        clearButton.addTarget(self, action: #selector(handleClear), for: .touchUpInside)

        let doneButton = ActionButton(titleKey: "common.done", style: .primary)
        doneButton.addTarget(self, action: #selector(handleDone), for: .touchUpInside)

        let topBar = UIStackView(arrangedSubviews: [titleLabel, UIView(), clearButton, doneButton])
        topBar.axis = .horizontal
        topBar.alignment = .center
        topBar.spacing = ThemeMetrics.space12
        topBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(topBar)

        table.dataSource = self
        table.delegate = self
        table.backgroundColor = .clear
        table.separatorColor = ThemeColor.separator
        table.register(UITableViewCell.self, forCellReuseIdentifier: "FilterCell")
        table.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(table)

        NSLayoutConstraint.activate([
            topBar.topAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.topAnchor,
                constant: ThemeMetrics.space16
            ),
            topBar.leadingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInset
            ),
            topBar.trailingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            table.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: ThemeMetrics.space16),
            table.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            table.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            table.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    private func numberOfSections() -> Int {
        sources.isEmpty ? 4 : 5
    }

    private func sectionKind(_ section: Int) -> String {
        if sources.isEmpty {
            return ["orientation", "status", "time", "sort"][section]
        }
        return ["source", "orientation", "status", "time", "sort"][section]
    }

    func numberOfSections(in tableView: UITableView) -> Int {
        numberOfSections()
    }

    func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch sectionKind(section) {
        case "source": return L10n.string("library.filter_source")
        case "orientation": return L10n.string("library.filter_orientation")
        case "status": return L10n.string("library.filter_status")
        case "time": return L10n.string("library.filter_time")
        default: return L10n.string("common.sort")
        }
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch sectionKind(section) {
        case "source": return sources.count
        case "orientation": return Self.orientationValues.count
        case "status": return Self.statusValues.count
        case "time": return 1
        default: return Self.sortValues.count
        }
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "FilterCell", for: indexPath)
        var content = cell.defaultContentConfiguration()
        content.textProperties.font = ThemeFont.body()
        content.textProperties.color = ThemeColor.textPrimary
        var checked = false
        switch sectionKind(indexPath.section) {
        case "source":
            let source = sources[indexPath.row]
            content.text = source.displayName
            checked = query.sourceIDs.contains(source.id)
        case "orientation":
            let value = Self.orientationValues[indexPath.row]
            content.text = L10n.string("library.orientation_\(value)")
            checked = query.orientations.contains(value)
        case "status":
            let value = Self.statusValues[indexPath.row]
            content.text = L10n.string("library.status_\(value)")
            checked = query.statuses.contains(value)
        case "time":
            content.text = L10n.string("library.filter_no_date")
            checked = query.noDateOnly
        default:
            content.text = L10n.string(Self.sortKeys[indexPath.row])
            checked = query.sort == Self.sortValues[indexPath.row]
        }
        cell.contentConfiguration = content
        cell.backgroundColor = ThemeColor.panel
        cell.accessoryType = checked ? .checkmark : .none
        cell.tintColor = ThemeColor.accentAction
        return cell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        switch sectionKind(indexPath.section) {
        case "source":
            let id = sources[indexPath.row].id
            if query.sourceIDs.contains(id) {
                query.sourceIDs.remove(id)
            } else {
                query.sourceIDs.insert(id)
            }
        case "orientation":
            let value = Self.orientationValues[indexPath.row]
            if query.orientations.contains(value) {
                query.orientations.remove(value)
            } else {
                query.orientations.insert(value)
            }
        case "status":
            let value = Self.statusValues[indexPath.row]
            if query.statuses.contains(value) {
                query.statuses.remove(value)
            } else {
                query.statuses.insert(value)
            }
        case "time":
            query.noDateOnly.toggle()
        default:
            query.sort = Self.sortValues[indexPath.row]
        }
        tableView.reloadSections(IndexSet(integer: indexPath.section), with: .none)
        onChange(query)
    }

    private static let sortKeys = [
        "library.sort_captured_desc",
        "library.sort_captured_asc",
        "library.sort_imported_desc",
        "library.sort_name_asc",
    ]

    @objc private func handleClear() {
        let sort = query.sort
        query = LibraryQuery(searchText: query.searchText, sort: sort)
        table.reloadData()
        onChange(query)
    }

    @objc private func handleDone() {
        dismiss(animated: true)
    }
}
