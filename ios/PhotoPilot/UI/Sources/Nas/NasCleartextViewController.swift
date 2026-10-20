import UIKit

final class NasCleartextViewController: UIViewController {
    private let host: String
    private let privateEligible: Bool
    private let onAllow: () -> Void

    init(host: String, privateEligible: Bool, onAllow: @escaping () -> Void) {
        self.host = host
        self.privateEligible = privateEligible
        self.onAllow = onAllow
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
        titleLabel.text = L10n.string("cleartext.title")
        titleLabel.font = ThemeFont.heading()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 0
        titleLabel.accessibilityTraits.insert(.header)

        let bodyLabel = UILabel()
        bodyLabel.text = L10n.string("cleartext.body")
        bodyLabel.font = ThemeFont.body()
        bodyLabel.adjustsFontForContentSizeCategory = true
        bodyLabel.textColor = ThemeColor.textSecondary
        bodyLabel.textAlignment = .center
        bodyLabel.numberOfLines = 0

        var arranged: [UIView] = [titleLabel, bodyLabel]
        if privateEligible {
            let allowButton = ActionButton(titleKey: "cleartext.allow_private_only", style: .primary)
            allowButton.addTarget(self, action: #selector(handleAllow), for: .touchUpInside)
            arranged.append(allowButton)
        } else {
            let blockedLabel = UILabel()
            blockedLabel.text = L10n.string("cleartext.public_blocked")
            blockedLabel.font = ThemeFont.body()
            blockedLabel.adjustsFontForContentSizeCategory = true
            blockedLabel.textColor = ThemeColor.statusError
            blockedLabel.textAlignment = .center
            blockedLabel.numberOfLines = 0
            arranged.append(blockedLabel)
        }
        let cancelButton = ActionButton(titleKey: "common.cancel", style: .secondary)
        cancelButton.addTarget(self, action: #selector(handleCancel), for: .touchUpInside)
        arranged.append(cancelButton)

        let stack = UIStackView(arrangedSubviews: arranged)
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = ThemeMetrics.space16
        stack.translatesAutoresizingMaskIntoConstraints = false

        let scrollView = UIScrollView()
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(stack)
        view.addSubview(scrollView)

        let readable = stack.widthAnchor.constraint(
            lessThanOrEqualToConstant: ThemeMetrics.bodyMaxWidth
        )
        let fillWidth = stack.widthAnchor.constraint(
            equalTo: scrollView.frameLayoutGuide.widthAnchor,
            constant: -2 * ThemeMetrics.contentInsetCompact
        )
        fillWidth.priority = .defaultHigh
        let verticalCenter = stack.centerYAnchor.constraint(equalTo: scrollView.frameLayoutGuide.centerYAnchor)
        verticalCenter.priority = .defaultLow
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
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
                constant: ThemeMetrics.contentInset
            ),
            stack.trailingAnchor.constraint(
                lessThanOrEqualTo: scrollView.frameLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            readable,
            fillWidth,
            verticalCenter,
        ])
    }

    @objc private func handleAllow() {
        dismiss(animated: true) { [onAllow] in onAllow() }
    }

    @objc private func handleCancel() {
        dismiss(animated: true)
    }
}
