import UIKit

final class RailNavBar: UIView, NavChromeBar {
    var onSelect: ((Int) -> Void)?

    private let buttons: [NavItemButton]

    init(items: [NavItem]) {
        buttons = items.map { NavItemButton(item: $0, style: .rail) }
        super.init(frame: .zero)
        backgroundColor = ThemeColor.panel

        let stack = UIStackView(arrangedSubviews: buttons)
        stack.axis = .vertical
        stack.distribution = .fillEqually
        stack.spacing = ThemeMetrics.space8
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: safeAreaLayoutGuide.topAnchor, constant: ThemeMetrics.space16),
            stack.leadingAnchor.constraint(equalTo: leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: trailingAnchor)
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
