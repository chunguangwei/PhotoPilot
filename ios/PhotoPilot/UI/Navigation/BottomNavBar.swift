import UIKit

final class BottomNavBar: UIView, NavChromeBar {
    var onSelect: ((Int) -> Void)?

    private let buttons: [NavItemButton]
    private var contentHeight: NSLayoutConstraint?

    init(items: [NavItem]) {
        buttons = items.map { NavItemButton(item: $0, style: .bottomBar) }
        super.init(frame: .zero)
        backgroundColor = ThemeColor.panel

        let stack = UIStackView(arrangedSubviews: buttons)
        stack.axis = .horizontal
        stack.distribution = .fillEqually
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)

        let height = stack.heightAnchor.constraint(equalToConstant: NavChromeMetrics.bottomBarContentHeight)
        contentHeight = height
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.leadingAnchor.constraint(equalTo: safeAreaLayoutGuide.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: safeAreaLayoutGuide.trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: safeAreaLayoutGuide.bottomAnchor),
            height,
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

    override func layoutSubviews() {
        let required = buttons.map(\.requiredContentHeight).max() ?? NavChromeMetrics.bottomBarContentHeight
        if contentHeight?.constant != required { contentHeight?.constant = required }
        super.layoutSubviews()
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
