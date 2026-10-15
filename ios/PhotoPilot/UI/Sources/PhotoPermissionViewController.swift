import Photos
import UIKit

final class PhotoPermissionViewController: UIViewController {
    private enum ContentState {
        case prompt
        case cancelled
        case granted
        case limited
        case denied
    }

    private var didRequest = false
    private var contentStack: UIStackView?
    private let contentScroll = UIScrollView()
    private let environment: AppEnvironment

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

        let backRow = ScreenHeaderView()
        backRow.onBack = { [weak self] in self?.handleBack() }
        backRow.pinToTop(of: view)

        contentScroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(contentScroll)

        NSLayoutConstraint.activate([
            contentScroll.topAnchor.constraint(equalTo: backRow.bottomAnchor),
            contentScroll.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            contentScroll.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            contentScroll.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor)
        ])
        render()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        render()
    }

    private func render() {
        let state = currentState()

        let titleLabel = UILabel()
        titleLabel.text = L10n.string("source.local_photos.title")
        titleLabel.font = ThemeFont.heading()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 0
        titleLabel.accessibilityTraits.insert(.header)

        var arranged: [UIView] = [titleLabel]

        switch state {
        case .prompt:
            arranged.append(makeBody(key: "permission.photos.usage"))
            arranged.append(makeButton(key: "common.continue", style: .primary, action: #selector(handleRequest)))
        case .cancelled:
            arranged.append(makeBody(key: "error.auth_cancelled.body"))
            arranged.append(makeButton(key: "common.retry", style: .primary, action: #selector(handleRequest)))
        case .granted:
            arranged.append(makeBody(key: "sources.status_active"))
            arranged.append(makeButton(key: "common.continue", style: .primary, action: #selector(handleContinue)))
        case .limited:
            arranged.append(makeBody(key: "permission.photos.limited_notice"))
            arranged.append(makeBody(key: "error.auth_limited.body"))
            arranged.append(makeButton(key: "common.open_system_settings", style: .secondary, action: #selector(handleOpenSettings)))
            arranged.append(makeButton(key: "common.continue", style: .primary, action: #selector(handleContinue)))
        case .denied:
            arranged.append(makeBody(key: "error.auth_denied.body"))
            arranged.append(makeButton(key: "common.open_system_settings", style: .primary, action: #selector(handleOpenSettings)))
            arranged.append(makeButton(key: "common.back", style: .secondary, action: #selector(handleBack)))
        }

        contentStack?.removeFromSuperview()
        let stack = UIStackView(arrangedSubviews: arranged)
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = ThemeMetrics.space16
        stack.translatesAutoresizingMaskIntoConstraints = false
        contentScroll.addSubview(stack)
        let readable = stack.widthAnchor.constraint(equalToConstant: ThemeMetrics.bodyMaxWidth)
        readable.priority = .defaultHigh
        let verticalCenter = stack.centerYAnchor.constraint(equalTo: contentScroll.frameLayoutGuide.centerYAnchor)
        verticalCenter.priority = .defaultLow
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(
                greaterThanOrEqualTo: contentScroll.contentLayoutGuide.topAnchor,
                constant: ThemeMetrics.space24
            ),
            stack.bottomAnchor.constraint(
                lessThanOrEqualTo: contentScroll.contentLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space24
            ),
            stack.centerXAnchor.constraint(equalTo: contentScroll.frameLayoutGuide.centerXAnchor),
            stack.leadingAnchor.constraint(
                greaterThanOrEqualTo: contentScroll.frameLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            stack.trailingAnchor.constraint(
                lessThanOrEqualTo: contentScroll.frameLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            readable,
            verticalCenter
        ])
        contentStack = stack
    }

    private func currentState() -> ContentState {
        switch PhotoLibrarySourceAdapter.currentAccess() {
        case .authorized:
            return .granted
        case .limited:
            return .limited
        case .denied:
            return .denied
        case .notDetermined:
            return didRequest ? .cancelled : .prompt
        }
    }

    private func makeBody(key: String) -> UILabel {
        let label = UILabel()
        label.text = L10n.string(key)
        label.font = ThemeFont.body()
        label.adjustsFontForContentSizeCategory = true
        label.textColor = ThemeColor.textSecondary
        label.textAlignment = .center
        label.numberOfLines = 0
        return label
    }

    private func makeButton(key: String, style: ActionButton.Style, action: Selector) -> ActionButton {
        let button = ActionButton(titleKey: key, style: style)
        button.addTarget(self, action: action, for: .touchUpInside)
        return button
    }

    @objc private func handleRequest() {
        didRequest = true
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { [weak self] _ in
            DispatchQueue.main.async {
                self?.render()
            }
        }
    }

    @objc private func handleOpenSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    @objc private func handleContinue() {
        Task { [weak self] in
            guard let self else { return }
            do {
                let runner = try await environment.startPhotoLibraryImport()
                try? environment.settings.setOnboardingCompleted(true)
                show(ImportProgressViewController(runner: runner, environment: environment))
            } catch {
            }
        }
    }

    private func show(_ controller: UIViewController) {
        if let navigationController {
            navigationController.pushViewController(controller, animated: true)
        } else {
            controller.modalPresentationStyle = .fullScreen
            present(controller, animated: true)
        }
    }

    @objc private func handleBack() {
        close()
    }

    private func close() {
        if let navigationController, navigationController.viewControllers.first != self {
            navigationController.popViewController(animated: true)
        } else {
            dismiss(animated: true)
        }
    }
}
