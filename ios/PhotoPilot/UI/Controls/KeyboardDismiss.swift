import UIKit
import ObjectiveC

@MainActor
final class KeyboardDismiss: NSObject, UIGestureRecognizerDelegate {
    private weak var rootView: UIView?
    let tapGesture: UITapGestureRecognizer

    init(rootView: UIView) {
        self.rootView = rootView
        tapGesture = UITapGestureRecognizer()
        super.init()
        tapGesture.addTarget(self, action: #selector(dismissKeyboard))
        tapGesture.cancelsTouchesInView = false
        tapGesture.delaysTouchesBegan = false
        tapGesture.delaysTouchesEnded = false
        tapGesture.delegate = self
        rootView.addGestureRecognizer(tapGesture)
        for name in [UIResponder.keyboardDidShowNotification, UIResponder.keyboardDidChangeFrameNotification] {
            NotificationCenter.default.addObserver(self, selector: #selector(keyboardDidChange(_:)), name: name, object: nil)
        }
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(inputDidBeginEditing(_:)),
            name: UITextField.textDidBeginEditingNotification,
            object: nil
        )
        refresh()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    func refresh() {
        guard let rootView else { return }
        configure(rootView)
    }

    private func configure(_ view: UIView) {
        if let field = view as? UITextField {
            Self.installDoneToolbar(on: field)
            return
        }
        if view is UITextView || view is UISearchBar { return }
        if let scroll = view as? UIScrollView {
            scroll.keyboardDismissMode = .interactive
            scroll.alwaysBounceVertical = true
        }
        view.subviews.forEach(configure)
    }

    static func installDoneToolbar(on field: UITextField) {
        guard field.inputAccessoryView == nil else { return }
        let toolbar = UIToolbar()
        toolbar.items = [
            UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil),
            UIBarButtonItem(
                title: L10n.string("common.done"),
                style: .done,
                target: field,
                action: #selector(UIResponder.resignFirstResponder)
            )
        ]
        toolbar.sizeToFit()
        field.inputAccessoryView = toolbar
    }

    func shouldDismiss(for touchedView: UIView?) -> Bool {
        guard let rootView, let touchedView,
              touchedView === rootView || touchedView.isDescendant(of: rootView) else { return false }
        var current: UIView? = touchedView
        while let view = current {
            if view is UITextField || view is UITextView || view is UISearchBar || view is UIControl
                || view is UITableViewCell || view is UICollectionViewCell {
                return false
            }
            if view === rootView { return true }
            current = view.superview
        }
        return false
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        shouldDismiss(for: touch.view)
    }

    @objc func dismissKeyboard() {
        rootView?.endEditing(true)
    }

    @objc private func inputDidBeginEditing(_ notification: Notification) {
        guard let field = notification.object as? UITextField,
              let rootView, field.isDescendant(of: rootView) else { return }
        DispatchQueue.main.async { [weak self] in
            self?.revealFocusedInput()
        }
    }

    @objc private func keyboardDidChange(_ notification: Notification) {
        guard let rootView, let window = rootView.window,
              let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue else { return }
        let keyboardFrame = rootView.convert(frame.cgRectValue, from: window.screen.coordinateSpace)
        guard rootView.bounds.intersects(keyboardFrame) else { return }
        revealFocusedInput()
    }

    func revealFocusedInput() {
        guard let rootView, rootView.window != nil,
              let input = firstResponder(in: rootView) else { return }
        rootView.layoutIfNeeded()
        var ancestor = input.superview
        while let view = ancestor {
            if let scroll = view as? UIScrollView, !(scroll is UITextView) {
                let rect = input.convert(input.bounds, to: scroll).insetBy(dx: 0, dy: -ThemeMetrics.space8)
                scroll.scrollRectToVisible(rect, animated: false)
            }
            if view === rootView { break }
            ancestor = view.superview
        }
    }

    private func firstResponder(in view: UIView) -> UIView? {
        if view.isFirstResponder { return view }
        for child in view.subviews {
            if let responder = firstResponder(in: child) { return responder }
        }
        return nil
    }
}

@MainActor
private var keyboardDismissKey: UInt8 = 0

extension UIViewController {
    @discardableResult
    func enableKeyboardDismissal() -> KeyboardDismiss {
        let rootView: UIView = view
        if let helper = objc_getAssociatedObject(rootView, &keyboardDismissKey) as? KeyboardDismiss {
            helper.refresh()
            return helper
        }
        let helper = KeyboardDismiss(rootView: rootView)
        objc_setAssociatedObject(rootView, &keyboardDismissKey, helper, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        return helper
    }
}
