import UIKit

final class SectionPlaceholderViewController: UIViewController {
    private let titleKey: String

    init(titleKey: String) {
        self.titleKey = titleKey
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ThemeColor.canvas

        let label = UILabel()
        label.text = L10n.string(titleKey)
        label.font = ThemeFont.title()
        label.adjustsFontForContentSizeCategory = true
        label.textColor = ThemeColor.textPrimary
        label.textAlignment = .center
        label.numberOfLines = 0
        label.accessibilityTraits.insert(.header)
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)

        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: view.safeAreaLayoutGuide.leadingAnchor, constant: ThemeMetrics.contentInsetCompact),
            label.trailingAnchor.constraint(lessThanOrEqualTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -ThemeMetrics.contentInsetCompact)
        ])
    }
}
