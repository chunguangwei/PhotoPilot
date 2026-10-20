import UIKit

final class NasCertTrustViewController: UIViewController {
    enum Mode {
        case untrusted(fingerprint: String, subject: String?)
        case hostnameMismatch(fingerprint: String)
        case changed(fingerprint: String)
    }

    private let mode: Mode
    private let onTrust: () -> Void

    init(mode: Mode, onTrust: @escaping () -> Void) {
        self.mode = mode
        self.onTrust = onTrust
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
        titleLabel.font = ThemeFont.heading()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 0
        titleLabel.accessibilityTraits.insert(.header)

        let bodyLabel = UILabel()
        bodyLabel.font = ThemeFont.body()
        bodyLabel.adjustsFontForContentSizeCategory = true
        bodyLabel.textColor = ThemeColor.textSecondary
        bodyLabel.textAlignment = .center
        bodyLabel.numberOfLines = 0

        let fingerprintLabel = UILabel()
        fingerprintLabel.font = ThemeFont.meta()
        fingerprintLabel.adjustsFontForContentSizeCategory = true
        fingerprintLabel.textColor = ThemeColor.textPrimary
        fingerprintLabel.textAlignment = .center
        fingerprintLabel.numberOfLines = 0

        switch mode {
        case .untrusted(let fingerprint, let subject):
            titleLabel.text = L10n.string("cert.title")
            bodyLabel.text = L10n.string("cert.body")
            var detail = "\(L10n.string("cert.fingerprint"))\n\(fingerprint)"
            if let subject, !subject.isEmpty {
                detail += "\n\(L10n.string("cert.issuer")) \(subject)"
            }
            fingerprintLabel.text = detail
        case .hostnameMismatch(let fingerprint):
            titleLabel.text = L10n.string("cert.title")
            bodyLabel.text = L10n.string("cert.hostname_mismatch")
            fingerprintLabel.text = "\(L10n.string("cert.fingerprint"))\n\(fingerprint)"
        case .changed(let fingerprint):
            titleLabel.text = L10n.string("cert.changed_title")
            bodyLabel.text = L10n.string("cert.changed_body")
            fingerprintLabel.text = "\(L10n.string("cert.fingerprint"))\n\(fingerprint)"
        }
        fingerprintLabel.accessibilityLabel = fingerprintLabel.text

        let trustButton = ActionButton(titleKey: "cert.trust_once", style: .primary)
        trustButton.addTarget(self, action: #selector(handleTrust), for: .touchUpInside)
        let cancelButton = ActionButton(titleKey: "common.cancel", style: .secondary)
        cancelButton.addTarget(self, action: #selector(handleCancel), for: .touchUpInside)

        let stack = UIStackView(arrangedSubviews: [
            titleLabel, bodyLabel, fingerprintLabel, trustButton, cancelButton,
        ])
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

    @objc private func handleTrust() {
        dismiss(animated: true) { [onTrust] in onTrust() }
    }

    @objc private func handleCancel() {
        dismiss(animated: true)
    }
}
