import UIKit

struct NavItem {
    let titleKey: String
    let symbolName: String
}

enum NavChromeMetrics {
    static let selectionIndicator: CGFloat = 2
    static let bottomBarContentHeight: CGFloat = 64
}

protocol NavChromeBar: UIView {
    var onSelect: ((Int) -> Void)? { get set }
    func select(index: Int)
}
