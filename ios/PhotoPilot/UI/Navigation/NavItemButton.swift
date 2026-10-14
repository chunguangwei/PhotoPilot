import UIKit

final class NavItemButton: UIControl {
    enum Style {
        case bottomBar
        case rail
        case sidebar
    }

    private let iconView = UIImageView()
    private let titleLabel = UILabel()
    private let indicator = UIView()
    private let style: Style
    private var labelHeight: NSLayoutConstraint?

    var requiredContentHeight: CGFloat {
        let font = UIFontMetrics(forTextStyle: .subheadline).scaledFont(
            for: UIFont.systemFont(ofSize: 15), compatibleWith: traitCollection
        )
        let labelHeight = font.lineHeight * (traitCollection.preferredContentSizeCategory.isAccessibilityCategory ? 2 : 1)
        return max(NavChromeMetrics.bottomBarContentHeight, 28 + labelHeight + 14)
    }

    override var isSelected: Bool {
        didSet { updateAppearance() }
    }

    init(item: NavItem, style: Style) {
        self.style = style
        super.init(frame: .zero)

        isAccessibilityElement = true
        accessibilityLabel = L10n.string(item.titleKey)
        accessibilityTraits = [.button]

        indicator.backgroundColor = ThemeColor.accentAction
        indicator.translatesAutoresizingMaskIntoConstraints = false
        addSubview(indicator)

        iconView.image = UIImage(systemName: item.symbolName)
        iconView.contentMode = .scaleAspectFit
        iconView.translatesAutoresizingMaskIntoConstraints = false

        titleLabel.text = L10n.string(item.titleKey)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.setContentCompressionResistancePriority(.required, for: .vertical)
        titleLabel.translatesAutoresizingMaskIntoConstraints = false

        let content = UIStackView(arrangedSubviews: [iconView, titleLabel])
        content.alignment = .center
        content.isUserInteractionEnabled = false
        content.translatesAutoresizingMaskIntoConstraints = false
        addSubview(content)

        switch style {
        case .bottomBar:
            content.axis = .vertical
            content.spacing = ThemeMetrics.space4
            iconView.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 24, weight: .regular)
            titleLabel.font = ThemeFont.caption()
            titleLabel.numberOfLines = 2
            titleLabel.lineBreakMode = .byCharWrapping
            titleLabel.textAlignment = .center
            let height = titleLabel.heightAnchor.constraint(equalToConstant: requiredContentHeight - 42)
            labelHeight = height
            NSLayoutConstraint.activate([
                content.widthAnchor.constraint(equalTo: widthAnchor, constant: -8),
                titleLabel.widthAnchor.constraint(equalTo: content.widthAnchor),
                height,
                iconView.widthAnchor.constraint(equalToConstant: 28),
                iconView.heightAnchor.constraint(equalToConstant: 28),
                indicator.topAnchor.constraint(equalTo: topAnchor),
                indicator.leadingAnchor.constraint(equalTo: leadingAnchor),
                indicator.trailingAnchor.constraint(equalTo: trailingAnchor),
                indicator.heightAnchor.constraint(equalToConstant: NavChromeMetrics.selectionIndicator),
                content.topAnchor.constraint(greaterThanOrEqualTo: indicator.bottomAnchor, constant: ThemeMetrics.space4),
                content.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor, constant: -ThemeMetrics.space4),
                content.centerYAnchor.constraint(equalTo: centerYAnchor),
                content.centerXAnchor.constraint(equalTo: centerXAnchor),
            ])
        case .rail:
            content.axis = .vertical
            content.spacing = ThemeMetrics.space4
            iconView.preferredSymbolConfiguration = UIImage.SymbolConfiguration(font: ThemeFont.heading())
            titleLabel.font = ThemeFont.caption()
            titleLabel.textAlignment = .center
            NSLayoutConstraint.activate([
                indicator.leadingAnchor.constraint(equalTo: leadingAnchor),
                indicator.topAnchor.constraint(equalTo: topAnchor),
                indicator.bottomAnchor.constraint(equalTo: bottomAnchor),
                indicator.widthAnchor.constraint(equalToConstant: NavChromeMetrics.selectionIndicator),
                content.topAnchor.constraint(greaterThanOrEqualTo: topAnchor, constant: ThemeMetrics.space4),
                content.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor, constant: -ThemeMetrics.space4),
                content.centerXAnchor.constraint(equalTo: centerXAnchor),
                content.centerYAnchor.constraint(equalTo: centerYAnchor)
            ])
        case .sidebar:
            content.axis = .horizontal
            content.spacing = ThemeMetrics.space12
            iconView.preferredSymbolConfiguration = UIImage.SymbolConfiguration(font: ThemeFont.body())
            titleLabel.font = ThemeFont.body()
            NSLayoutConstraint.activate([
                indicator.leadingAnchor.constraint(equalTo: leadingAnchor),
                indicator.topAnchor.constraint(equalTo: topAnchor),
                indicator.bottomAnchor.constraint(equalTo: bottomAnchor),
                indicator.widthAnchor.constraint(equalToConstant: NavChromeMetrics.selectionIndicator),
                content.topAnchor.constraint(greaterThanOrEqualTo: topAnchor, constant: ThemeMetrics.space4),
                content.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor, constant: -ThemeMetrics.space4),
                content.leadingAnchor.constraint(equalTo: indicator.trailingAnchor, constant: ThemeMetrics.space16),
                content.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -ThemeMetrics.space8),
                content.centerYAnchor.constraint(equalTo: centerYAnchor)
            ])
        }

        iconView.setContentHuggingPriority(.required, for: .horizontal)
        iconView.setContentHuggingPriority(.required, for: .vertical)
        iconView.setContentCompressionResistancePriority(.required, for: .horizontal)
        iconView.setContentCompressionResistancePriority(.required, for: .vertical)

        heightAnchor.constraint(greaterThanOrEqualToConstant: ThemeMetrics.touchTarget).isActive = true

        updateAppearance()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func layoutSubviews() {
        if style == .bottomBar { labelHeight?.constant = requiredContentHeight - 42 }
        super.layoutSubviews()
    }

    private func updateAppearance() {
        let color = isSelected ? ThemeColor.accentAction : ThemeColor.textSecondary
        iconView.tintColor = color
        titleLabel.textColor = color
        indicator.isHidden = !isSelected
        if isSelected {
            accessibilityTraits.insert(.selected)
        } else {
            accessibilityTraits.remove(.selected)
        }
    }
}
