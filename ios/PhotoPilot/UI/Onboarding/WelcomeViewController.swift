import UIKit

final class WelcomeViewController: UIViewController {
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
        view.backgroundColor = ThemeColor.canvas

        let titleLabel = UILabel()
        titleLabel.text = L10n.string("welcome.title")
        titleLabel.font = ThemeFont.title()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 0
        titleLabel.accessibilityTraits.insert(.header)

        let bodyLabel = UILabel()
        bodyLabel.text = L10n.string("welcome.body")
        bodyLabel.font = ThemeFont.body()
        bodyLabel.adjustsFontForContentSizeCategory = true
        bodyLabel.textColor = ThemeColor.textSecondary
        bodyLabel.textAlignment = .center
        bodyLabel.numberOfLines = 0

        let primaryButton = ActionButton(titleKey: "welcome.primary_action", style: .primary)
        primaryButton.addTarget(self, action: #selector(handlePrimary), for: .touchUpInside)

        let secondaryButton = ActionButton(titleKey: "welcome.secondary_action", style: .secondary)
        secondaryButton.addTarget(self, action: #selector(handleSecondary), for: .touchUpInside)

        let mark = BrandMarkView(frame: .zero)
        mark.translatesAutoresizingMaskIntoConstraints = false
        let markContainer = UIView()
        markContainer.addSubview(mark)
        NSLayoutConstraint.activate([
            mark.widthAnchor.constraint(equalToConstant: 112),
            mark.heightAnchor.constraint(equalToConstant: 112),
            mark.centerXAnchor.constraint(equalTo: markContainer.centerXAnchor),
            mark.topAnchor.constraint(equalTo: markContainer.topAnchor),
            mark.bottomAnchor.constraint(equalTo: markContainer.bottomAnchor)
        ])
        let stack = UIStackView(arrangedSubviews: [markContainer, titleLabel, bodyLabel, primaryButton, secondaryButton])
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = ThemeMetrics.space16
        stack.translatesAutoresizingMaskIntoConstraints = false

        let scrollView = UIScrollView()
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(stack)
        view.addSubview(scrollView)

        let readable = stack.widthAnchor.constraint(equalToConstant: ThemeMetrics.bodyMaxWidth)
        readable.priority = .defaultHigh
        let fillWidth = stack.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor, constant: -ThemeMetrics.contentInsetCompact * 2)
        fillWidth.priority = UILayoutPriority(999)
        let verticalCenter = stack.centerYAnchor.constraint(equalTo: scrollView.frameLayoutGuide.centerYAnchor)
        verticalCenter.priority = .defaultLow
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            stack.widthAnchor.constraint(lessThanOrEqualToConstant: ThemeMetrics.bodyMaxWidth),
            fillWidth,
            stack.topAnchor.constraint(
                greaterThanOrEqualTo: scrollView.contentLayoutGuide.topAnchor,
                constant: ThemeMetrics.space24
            ),
            stack.bottomAnchor.constraint(
                lessThanOrEqualTo: scrollView.contentLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space24
            ),
            stack.centerXAnchor.constraint(equalTo: scrollView.frameLayoutGuide.centerXAnchor),
            stack.leadingAnchor.constraint(
                greaterThanOrEqualTo: scrollView.frameLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            stack.trailingAnchor.constraint(
                lessThanOrEqualTo: scrollView.frameLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            readable,
            verticalCenter
        ])
    }

    @objc private func handlePrimary() {
        navigationController?.pushViewController(
            SourcesListViewController(environment: environment),
            animated: true
        )
    }

    @objc private func handleSecondary() {
        let demo = DemoPlaceholderViewController()
        demo.modalPresentationStyle = .fullScreen
        present(demo, animated: true)
    }
}
