import UIKit

final class ScreenHeaderView: UIView {
    static let height: CGFloat = 52

    private final class BackControl: UIControl {
        override var isHighlighted: Bool {
            didSet { alpha = isHighlighted ? 0.72 : 1 }
        }
    }

    private let backControl = BackControl()
    private let backStack = UIStackView()
    private let chevronView = UIImageView()
    private let backLabel = UILabel()
    private let titleLabel = UILabel()
    private let trailingContainer = UIView()

    var onBack: (() -> Void)?

    init(title: String? = nil, titleFont: UIFont = ThemeFont.heading(), showsBackButton: Bool = true) {
        super.init(frame: .zero)

        let chevronConfiguration = UIImage.SymbolConfiguration(pointSize: 16, weight: .semibold)
        chevronView.image = UIImage(systemName: "chevron.left", withConfiguration: chevronConfiguration)
        chevronView.tintColor = ThemeColor.textPrimary
        chevronView.contentMode = .scaleAspectFit

        backLabel.text = L10n.string("common.back")
        backLabel.font = ThemeFont.body()
        backLabel.adjustsFontForContentSizeCategory = true
        backLabel.textColor = ThemeColor.textPrimary

        backStack.axis = .horizontal
        backStack.alignment = .center
        backStack.spacing = ThemeMetrics.space4
        backStack.isUserInteractionEnabled = false
        backStack.addArrangedSubview(chevronView)
        backStack.addArrangedSubview(backLabel)
        backStack.translatesAutoresizingMaskIntoConstraints = false

        backControl.isHidden = !showsBackButton
        backControl.isAccessibilityElement = true
        backControl.accessibilityLabel = backLabel.text
        backControl.accessibilityTraits = [.button]
        backControl.addTarget(self, action: #selector(handleBackTap), for: .touchUpInside)
        backControl.addSubview(backStack)
        backControl.translatesAutoresizingMaskIntoConstraints = false

        titleLabel.text = title
        titleLabel.font = titleFont
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.numberOfLines = 0
        titleLabel.isHidden = title == nil
        titleLabel.accessibilityTraits.insert(.header)
        titleLabel.translatesAutoresizingMaskIntoConstraints = false

        trailingContainer.translatesAutoresizingMaskIntoConstraints = false

        addSubview(backControl)
        addSubview(titleLabel)
        addSubview(trailingContainer)

        let margins = layoutMarginsGuide
        NSLayoutConstraint.activate([
            heightAnchor.constraint(greaterThanOrEqualToConstant: Self.height),
            backStack.topAnchor.constraint(equalTo: backControl.topAnchor),
            backStack.bottomAnchor.constraint(equalTo: backControl.bottomAnchor),
            backStack.leadingAnchor.constraint(equalTo: backControl.leadingAnchor),
            backStack.trailingAnchor.constraint(equalTo: backControl.trailingAnchor),
            backControl.leadingAnchor.constraint(equalTo: margins.leadingAnchor),
            backControl.centerYAnchor.constraint(equalTo: margins.centerYAnchor),
            backControl.topAnchor.constraint(greaterThanOrEqualTo: margins.topAnchor),
            backControl.bottomAnchor.constraint(lessThanOrEqualTo: margins.bottomAnchor),
            backControl.heightAnchor.constraint(greaterThanOrEqualToConstant: ThemeMetrics.touchTarget),
            trailingContainer.trailingAnchor.constraint(equalTo: margins.trailingAnchor),
            trailingContainer.centerYAnchor.constraint(equalTo: margins.centerYAnchor),
            trailingContainer.topAnchor.constraint(greaterThanOrEqualTo: margins.topAnchor),
            trailingContainer.bottomAnchor.constraint(lessThanOrEqualTo: margins.bottomAnchor),
            titleLabel.centerYAnchor.constraint(equalTo: margins.centerYAnchor),
            titleLabel.topAnchor.constraint(greaterThanOrEqualTo: margins.topAnchor),
            titleLabel.bottomAnchor.constraint(lessThanOrEqualTo: margins.bottomAnchor),
            titleLabel.trailingAnchor.constraint(
                lessThanOrEqualTo: trailingContainer.leadingAnchor,
                constant: -ThemeMetrics.space8
            ),
        ])
        if showsBackButton {
            titleLabel.leadingAnchor.constraint(
                equalTo: backControl.trailingAnchor,
                constant: ThemeMetrics.space8
            ).isActive = true
        } else {
            titleLabel.leadingAnchor.constraint(equalTo: margins.leadingAnchor).isActive = true
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    func setTitle(_ title: String?) {
        titleLabel.text = title
        titleLabel.isHidden = title == nil
    }

    func addTrailing(_ view: UIView) {
        view.translatesAutoresizingMaskIntoConstraints = false
        trailingContainer.addSubview(view)
        NSLayoutConstraint.activate([
            view.topAnchor.constraint(equalTo: trailingContainer.topAnchor),
            view.bottomAnchor.constraint(equalTo: trailingContainer.bottomAnchor),
            view.leadingAnchor.constraint(equalTo: trailingContainer.leadingAnchor),
            view.trailingAnchor.constraint(equalTo: trailingContainer.trailingAnchor),
        ])
    }

    func pinToTop(of view: UIView) {
        translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(self)
        NSLayoutConstraint.activate([
            topAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.topAnchor,
                constant: ThemeMetrics.space8
            ),
            leadingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            trailingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
        ])
    }

    @objc private func handleBackTap() {
        onBack?()
    }
}
