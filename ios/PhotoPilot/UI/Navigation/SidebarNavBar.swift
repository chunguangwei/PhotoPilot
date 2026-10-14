import UIKit

final class SidebarNavBar: UIView, NavChromeBar {
    var onSelect: ((Int) -> Void)?

    private let buttons: [NavItemButton]

    init(items: [NavItem]) {
        buttons = items.map { NavItemButton(item: $0, style: .sidebar) }
        super.init(frame: .zero)
        backgroundColor = ThemeColor.panel

        let brandLabel = UILabel()
        brandLabel.text = L10n.string("brand.name")
        brandLabel.font = ThemeFont.heading()
        brandLabel.adjustsFontForContentSizeCategory = true
        brandLabel.textColor = ThemeColor.textPrimary

        let subtitleLabel = UILabel()
        subtitleLabel.text = L10n.string("nav.subtitle")
        subtitleLabel.font = ThemeFont.meta()
        subtitleLabel.adjustsFontForContentSizeCategory = true
        subtitleLabel.textColor = ThemeColor.textSecondary
        subtitleLabel.numberOfLines = 0

        let brandMark = BrandMarkView(frame: .zero)
        brandMark.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            brandMark.widthAnchor.constraint(equalToConstant: 48),
            brandMark.heightAnchor.constraint(equalToConstant: 48)
        ])
        let markRow = UIStackView(arrangedSubviews: [brandMark, UIView()])
        let brandStack = UIStackView(arrangedSubviews: [markRow, brandLabel, subtitleLabel])
        brandStack.axis = .vertical
        brandStack.spacing = ThemeMetrics.space4
        brandStack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(brandStack)

        let navStack = UIStackView(arrangedSubviews: buttons)
        navStack.axis = .vertical
        navStack.spacing = ThemeMetrics.space4
        navStack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(navStack)

        let footerLabel = UILabel()
        footerLabel.text = L10n.string("nav.privacy_footer")
        footerLabel.font = ThemeFont.meta()
        footerLabel.adjustsFontForContentSizeCategory = true
        footerLabel.textColor = ThemeColor.textSecondary
        footerLabel.numberOfLines = 0
        footerLabel.translatesAutoresizingMaskIntoConstraints = false
        addSubview(footerLabel)

        NSLayoutConstraint.activate([
            brandStack.topAnchor.constraint(equalTo: safeAreaLayoutGuide.topAnchor, constant: ThemeMetrics.space24),
            brandStack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: ThemeMetrics.space24),
            brandStack.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -ThemeMetrics.space24),
            navStack.topAnchor.constraint(equalTo: brandStack.bottomAnchor, constant: ThemeMetrics.space32),
            navStack.leadingAnchor.constraint(equalTo: leadingAnchor),
            navStack.trailingAnchor.constraint(equalTo: trailingAnchor),
            footerLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: ThemeMetrics.space24),
            footerLabel.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -ThemeMetrics.space24),
            footerLabel.bottomAnchor.constraint(equalTo: safeAreaLayoutGuide.bottomAnchor, constant: -ThemeMetrics.space16)
        ])

        for (index, button) in buttons.enumerated() {
            button.tag = index
            button.addTarget(self, action: #selector(handleTap(_:)), for: .touchUpInside)
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    func select(index: Int) {
        for (buttonIndex, button) in buttons.enumerated() {
            button.isSelected = buttonIndex == index
        }
    }

    @objc private func handleTap(_ sender: NavItemButton) {
        onSelect?(sender.tag)
    }
}
