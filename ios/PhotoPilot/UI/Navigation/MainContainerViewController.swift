import UIKit

final class MainContainerViewController: UIViewController {
    private enum NavChrome {
        case bottom
        case rail
        case sidebar

        static let compactMaxWidth: CGFloat = 600
        static let railMaxWidth: CGFloat = 840

        static func make(width: CGFloat) -> NavChrome {
            if width < compactMaxWidth { return .bottom }
            if width < railMaxWidth { return .rail }
            return .sidebar
        }
    }

    private let items: [NavItem] = [
        NavItem(titleKey: "nav.frames", symbolName: "photo.on.rectangle"),
        NavItem(titleKey: "nav.library", symbolName: "photo.stack"),
        NavItem(titleKey: "nav.sources", symbolName: "externaldrive"),
        NavItem(titleKey: "nav.settings", symbolName: "gearshape")
    ]

    private let contentView = UIView()
    private let environment: AppEnvironment
    private var currentChrome: NavChrome?
    private var chromeView: (any NavChromeBar)?
    private var contentEdgeConstraints: [NSLayoutConstraint] = []
    private var sectionCache: [Int: UIViewController] = [:]
    private var currentChild: UIViewController?
    private var selectedIndex = 0
    private lazy var sourcesSectionIndex = items.firstIndex { $0.titleKey == "nav.sources" } ?? 0
    private lazy var librarySectionIndex = items.firstIndex { $0.titleKey == "nav.library" } ?? 0
    private lazy var framesSectionIndex = items.firstIndex { $0.titleKey == "nav.frames" } ?? 0
    private lazy var settingsSectionIndex = items.firstIndex { $0.titleKey == "nav.settings" } ?? 0

    init(environment: AppEnvironment) {
        self.environment = environment
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    var autoPlayFrameID: String?
    var autoOpenSettingsFrameID: String?
    var initialSectionTitleKey: String?

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if let frameID = autoOpenSettingsFrameID {
            autoOpenSettingsFrameID = nil
            let settings = PlaybackSettingsViewController(environment: environment, frameID: frameID)
            settings.modalPresentationStyle = .fullScreen
            present(settings, animated: false)
            return
        }
        guard let frameID = autoPlayFrameID else { return }
        autoPlayFrameID = nil
        let player = PlayerViewController(environment: environment, frameID: frameID)
        player.modalPresentationStyle = .fullScreen
        present(player, animated: false)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ThemeColor.canvas
        contentView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(contentView)
        NSLayoutConstraint.activate([
            contentView.topAnchor.constraint(equalTo: view.topAnchor),
            contentView.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleForegroundSync),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleLanguageChanged),
            name: L10n.didChangeNotification,
            object: nil
        )
        let initialIndex = initialSectionTitleKey.flatMap { key in
            items.firstIndex { $0.titleKey == key }
        } ?? selectedIndex
        selectSection(initialIndex)
    }

    @objc private func handleForegroundSync() {
        guard presentedViewController == nil else { return }
        Task {
            await environment.autoSyncWebDavSources()
        }
    }

    @objc private func handleLanguageChanged() {
        guard let window = view.window else { return }
        let next = MainContainerViewController(environment: environment)
        next.initialSectionTitleKey = items.indices.contains(selectedIndex)
            ? items[selectedIndex].titleKey
            : nil
        RootTransition.setRoot(next, in: window)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let chrome: NavChrome = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
            ? .bottom
            : NavChrome.make(width: view.bounds.width)
        guard chrome != currentChrome else { return }
        currentChrome = chrome
        installChrome(chrome)
    }

    private func installChrome(_ chrome: NavChrome) {
        chromeView?.removeFromSuperview()
        let bar = makeChromeBar(for: chrome)
        bar.translatesAutoresizingMaskIntoConstraints = false
        bar.onSelect = { [weak self] index in
            self?.selectSection(index)
        }
        view.addSubview(bar)
        var edges: [NSLayoutConstraint]
        switch chrome {
        case .bottom:
            edges = [
                bar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                bar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
                bar.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                contentView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                contentView.bottomAnchor.constraint(equalTo: bar.topAnchor)
            ]
        case .rail:
            edges = [
                bar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                bar.topAnchor.constraint(equalTo: view.topAnchor),
                bar.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                bar.widthAnchor.constraint(equalToConstant: ThemeMetrics.railWidth),
                contentView.leadingAnchor.constraint(equalTo: bar.trailingAnchor),
                contentView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
            ]
        case .sidebar:
            edges = [
                bar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
                bar.topAnchor.constraint(equalTo: view.topAnchor),
                bar.bottomAnchor.constraint(equalTo: view.bottomAnchor),
                bar.widthAnchor.constraint(equalToConstant: ThemeMetrics.sidebarWidth),
                contentView.leadingAnchor.constraint(equalTo: bar.trailingAnchor),
                contentView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
            ]
        }
        contentEdgeConstraints.forEach { $0.isActive = false }
        NSLayoutConstraint.activate(edges)
        contentEdgeConstraints = edges
        bar.select(index: selectedIndex)
        chromeView = bar
    }

    private func makeChromeBar(for chrome: NavChrome) -> any NavChromeBar {
        switch chrome {
        case .bottom:
            return BottomNavBar(items: items)
        case .rail:
            return RailNavBar(items: items)
        case .sidebar:
            return SidebarNavBar(items: items)
        }
    }

    private func sectionViewController(at index: Int) -> UIViewController {
        if let cached = sectionCache[index] {
            return cached
        }
        let controller: UIViewController
        if index == framesSectionIndex {
            controller = FramesListViewController(environment: environment)
        } else if index == sourcesSectionIndex {
            controller = SourcesListViewController(environment: environment)
        } else if index == librarySectionIndex {
            controller = LibraryViewController(environment: environment)
        } else if index == settingsSectionIndex {
            controller = SettingsViewController(environment: environment)
        } else {
            controller = SectionPlaceholderViewController(titleKey: items[index].titleKey)
        }
        sectionCache[index] = controller
        return controller
    }

    private func selectSection(_ index: Int) {
        guard index != selectedIndex || currentChild == nil else { return }
        selectedIndex = index
        chromeView?.select(index: index)

        let incoming = sectionViewController(at: index)
        let outgoing = currentChild

        outgoing?.beginAppearanceTransition(false, animated: false)
        incoming.beginAppearanceTransition(true, animated: false)
        outgoing?.willMove(toParent: nil)
        addChild(incoming)
        let childView = incoming.view as UIView
        childView.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(childView)
        NSLayoutConstraint.activate([
            childView.topAnchor.constraint(equalTo: contentView.topAnchor),
            childView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            childView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            childView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor)
        ])
        incoming.didMove(toParent: self)
        incoming.endAppearanceTransition()
        outgoing?.view.removeFromSuperview()
        outgoing?.removeFromParent()
        outgoing?.endAppearanceTransition()
        currentChild = incoming
    }
}
