import UIKit
import ObjectiveC

private final class EdgeSwipeBackHandler: NSObject {
    weak var controller: UIViewController?
    var action: (() -> Void)?

    init(controller: UIViewController) {
        self.controller = controller
    }

    @objc func handle(_ gesture: UIScreenEdgePanGestureRecognizer) {
        guard gesture.state == .recognized else { return }
        guard let controller, controller.parent == nil, controller.presentingViewController != nil else { return }
        if controller.presentedViewController != nil { return }
        if let action {
            action()
        } else {
            controller.dismiss(animated: true)
        }
    }
}

nonisolated(unsafe) var edgeSwipeBackKey = 0

extension UIViewController {
    func enableEdgeSwipeBack(perform backAction: (() -> Void)? = nil) {
        let handler = EdgeSwipeBackHandler(controller: self)
        handler.action = backAction
        objc_setAssociatedObject(self, &edgeSwipeBackKey, handler, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        let gesture = UIScreenEdgePanGestureRecognizer(target: handler, action: #selector(EdgeSwipeBackHandler.handle(_:)))
        gesture.edges = .left
        gesture.delaysTouchesBegan = true
        view.addGestureRecognizer(gesture)
    }
}
