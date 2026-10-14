import XCTest
import UIKit
@testable import PhotoPilot

@MainActor
final class KeyboardDismissTests: XCTestCase {
    func testBlankTouchesAreAcceptedButUnrelatedViewsAreRejected() {
        let controller = UIViewController()
        let stack = UIStackView()
        let label = UILabel()
        controller.view.addSubview(stack)
        stack.addArrangedSubview(label)
        let helper = controller.enableKeyboardDismissal()

        XCTAssertTrue(helper.shouldDismiss(for: controller.view))
        XCTAssertTrue(helper.shouldDismiss(for: stack))
        XCTAssertTrue(helper.shouldDismiss(for: label))
        XCTAssertFalse(helper.shouldDismiss(for: nil))
        XCTAssertFalse(helper.shouldDismiss(for: UIView()))
        XCTAssertFalse(helper.tapGesture.cancelsTouchesInView)
        XCTAssertFalse(helper.tapGesture.delaysTouchesBegan)
        XCTAssertFalse(helper.tapGesture.delaysTouchesEnded)
        XCTAssertTrue(helper.tapGesture.delegate === helper)
    }

    func testInputsControlsAndAllTheirDescendantsAreExcluded() {
        let controller = UIViewController()
        let helper = controller.enableKeyboardDismissal()
        let protectedViews: [UIView] = [
            UITextField(), UITextView(), UISearchBar(), UIButton(type: .system),
            UISwitch(), UISlider(), UISegmentedControl(items: ["One", "Two"]), UIDatePicker(),
            UITableViewCell(), UICollectionViewCell(frame: .zero)
        ]
        for protectedView in protectedViews {
            controller.view.addSubview(protectedView)
            let container = UIView()
            let leaf = UILabel()
            protectedView.addSubview(container)
            container.addSubview(leaf)
            XCTAssertFalse(helper.shouldDismiss(for: protectedView))
            XCTAssertFalse(helper.shouldDismiss(for: container))
            XCTAssertFalse(helper.shouldDismiss(for: leaf))
        }
        let searchBar = UISearchBar()
        controller.view.addSubview(searchBar)
        XCTAssertFalse(helper.shouldDismiss(for: searchBar.searchTextField))
    }

    func testRepeatedInstallationIsIdempotentAndFindsNestedScrollViews() {
        let controller = UIViewController()
        let wrapper = UIView()
        let scroll = UIScrollView()
        let table = UITableView()
        let collection = UICollectionView(frame: .zero, collectionViewLayout: UICollectionViewFlowLayout())
        let delegate = InputDelegate()
        let field = UITextField()
        field.delegate = delegate
        field.returnKeyType = .next
        controller.view.addSubview(wrapper)
        wrapper.addSubview(scroll)
        scroll.addSubview(table)
        wrapper.addSubview(collection)
        scroll.addSubview(field)
        let insets = UIEdgeInsets(top: 3, left: 4, bottom: 19, right: 5)
        scroll.contentInset = insets
        scroll.scrollIndicatorInsets = insets
        scroll.delegate = delegate
        table.delegate = delegate
        collection.delegate = delegate
        let otherGesture = UILongPressGestureRecognizer()
        controller.view.addGestureRecognizer(otherGesture)

        let first = controller.enableKeyboardDismissal()
        let toolbar = field.inputAccessoryView
        let second = controller.enableKeyboardDismissal()

        XCTAssertTrue(first === second)
        XCTAssertEqual(controller.view.gestureRecognizers?.count, 2)
        XCTAssertTrue(controller.view.gestureRecognizers?.contains(otherGesture) == true)
        XCTAssertTrue(field.inputAccessoryView === toolbar)
        XCTAssertTrue(field.delegate === delegate)
        XCTAssertEqual(field.returnKeyType, .next)
        XCTAssertTrue(scroll.delegate === delegate)
        XCTAssertTrue(table.delegate === delegate)
        XCTAssertTrue(collection.delegate === delegate)
        XCTAssertEqual(scroll.contentInset, insets)
        XCTAssertEqual(scroll.verticalScrollIndicatorInsets, insets)
        XCTAssertEqual(scroll.horizontalScrollIndicatorInsets, insets)
        for item in [scroll, table, collection] {
            XCTAssertEqual(item.keyboardDismissMode, .interactive)
            XCTAssertTrue(item.alwaysBounceVertical)
        }

        let lateScroll = UIScrollView()
        let lateField = UITextField()
        wrapper.addSubview(lateScroll)
        lateScroll.addSubview(lateField)
        controller.enableKeyboardDismissal()
        XCTAssertEqual(lateScroll.keyboardDismissMode, .interactive)
        XCTAssertNotNil(lateField.inputAccessoryView)
        XCTAssertEqual(controller.view.gestureRecognizers?.count, 2)
    }

    func testTextEditingInternalsAreNotReconfigured() {
        let controller = UIViewController()
        let textView = UITextView()
        textView.keyboardDismissMode = .onDrag
        textView.alwaysBounceVertical = false
        let searchBar = UISearchBar()
        let searchAccessory = UIView()
        searchBar.searchTextField.inputAccessoryView = searchAccessory
        searchBar.searchTextField.returnKeyType = .search
        controller.view.addSubview(textView)
        controller.view.addSubview(searchBar)

        controller.enableKeyboardDismissal()

        XCTAssertEqual(textView.keyboardDismissMode, .onDrag)
        XCTAssertFalse(textView.alwaysBounceVertical)
        XCTAssertTrue(searchBar.searchTextField.inputAccessoryView === searchAccessory)
        XCTAssertEqual(searchBar.searchTextField.returnKeyType, .search)
    }

    func testNumberDecimalPasswordAndSearchKeepTraitsAndGainLocalizedDone() throws {
        let controller = UIViewController()
        let fields = [UITextField(), UITextField(), UITextField(), UITextField()]
        fields[0].keyboardType = .numberPad
        fields[1].keyboardType = .decimalPad
        fields[2].isSecureTextEntry = true
        fields[3].returnKeyType = .search
        fields.forEach { controller.view.addSubview($0) }
        controller.enableKeyboardDismissal()

        for field in fields {
            let toolbar = try XCTUnwrap(field.inputAccessoryView as? UIToolbar)
            let item = try XCTUnwrap(toolbar.items?.last)
            XCTAssertEqual(item.title, L10n.string("common.done"))
            XCTAssertTrue((item.target as? UITextField) === field)
            XCTAssertEqual(item.action, #selector(UIResponder.resignFirstResponder))
            XCTAssertGreaterThan(toolbar.bounds.height, 0)
        }
        XCTAssertEqual(fields[0].keyboardType, .numberPad)
        XCTAssertEqual(fields[1].keyboardType, .decimalPad)
        XCTAssertTrue(fields[2].isSecureTextEntry)
        XCTAssertEqual(fields[3].returnKeyType, .search)
    }

    func testExistingAccessoryIsNotReplaced() {
        let field = UITextField()
        let existing = UIToolbar()
        field.inputAccessoryView = existing
        KeyboardDismiss.installDoneToolbar(on: field)
        XCTAssertTrue(field.inputAccessoryView === existing)
    }

    func testDoneResignsFocusWithoutChangingTextInvokingReturnOrNavigating() throws {
        let controller = UIViewController()
        let field = UITextField(frame: CGRect(x: 20, y: 80, width: 240, height: 44))
        let delegate = InputDelegate()
        field.delegate = delegate
        field.text = "12.50 unchanged"
        field.returnKeyType = .search
        controller.view.addSubview(field)
        controller.enableKeyboardDismissal()
        let window = show(controller)
        defer { window.isHidden = true; window.rootViewController = nil }
        XCTAssertTrue(field.becomeFirstResponder())
        field.selectedTextRange = field.textRange(
            from: field.beginningOfDocument,
            to: field.endOfDocument
        )
        let toolbar = try XCTUnwrap(field.inputAccessoryView as? UIToolbar)
        let done = try XCTUnwrap(toolbar.items?.last)
        let action = try XCTUnwrap(done.action)

        XCTAssertTrue(UIApplication.shared.sendAction(action, to: done.target, from: done, for: nil))

        XCTAssertFalse(field.isFirstResponder)
        XCTAssertEqual(field.text, "12.50 unchanged")
        XCTAssertEqual(delegate.returnCount, 0)
        XCTAssertNil(controller.presentedViewController)
        XCTAssertFalse(controller.isBeingDismissed)
        XCTAssertTrue(window.rootViewController === controller)
    }

    func testBlankDismissalRetainsTextAndCanBeRepeatedWithoutNavigation() {
        let controller = UIViewController()
        let field = UITextField(frame: CGRect(x: 20, y: 80, width: 240, height: 44))
        let delegate = InputDelegate()
        field.delegate = delegate
        field.text = "unsaved draft"
        controller.view.addSubview(field)
        let helper = controller.enableKeyboardDismissal()
        let window = show(controller)
        defer { window.isHidden = true; window.rootViewController = nil }
        XCTAssertTrue(field.becomeFirstResponder())

        helper.dismissKeyboard()
        helper.dismissKeyboard()

        XCTAssertFalse(field.isFirstResponder)
        XCTAssertEqual(field.text, "unsaved draft")
        XCTAssertEqual(delegate.returnCount, 0)
        XCTAssertTrue(window.rootViewController === controller)
        XCTAssertNil(controller.presentedViewController)
        XCTAssertFalse(controller.isBeingDismissed)
    }

    func testRevealScrollsFocusedInputWithoutChangingInsets() {
        let controller = UIViewController()
        let scroll = UIScrollView(frame: CGRect(x: 0, y: 60, width: 320, height: 240))
        scroll.contentSize = CGSize(width: 320, height: 1000)
        scroll.contentInsetAdjustmentBehavior = .never
        scroll.contentInset.bottom = 23
        let field = UITextField(frame: CGRect(x: 20, y: 700, width: 240, height: 44))
        scroll.addSubview(field)
        controller.view.addSubview(scroll)
        let helper = controller.enableKeyboardDismissal()
        let window = show(controller)
        defer { window.isHidden = true; window.rootViewController = nil }
        XCTAssertTrue(field.becomeFirstResponder())
        scroll.setContentOffset(.zero, animated: false)

        helper.revealFocusedInput()

        XCTAssertGreaterThan(scroll.contentOffset.y, 0)
        XCTAssertTrue(scroll.bounds.contains(field.frame))
        XCTAssertEqual(scroll.contentInset.bottom, 23)
    }

    func testRootRetainsHelperAndReleasesItWithoutControllerOrFieldLeak() {
        weak var weakController: UIViewController?
        weak var weakRoot: UIView?
        weak var weakHelper: KeyboardDismiss?
        weak var weakField: UITextField?
        autoreleasepool {
            let controller = UIViewController()
            let field = UITextField()
            controller.view.addSubview(field)
            weakController = controller
            weakRoot = controller.view
            weakField = field
            weakHelper = controller.enableKeyboardDismissal()
            XCTAssertNotNil(weakHelper)
            XCTAssertTrue(controller.view.gestureRecognizers?.first?.delegate === weakHelper)
        }
        XCTAssertNil(weakController)
        XCTAssertNil(weakRoot)
        XCTAssertNil(weakHelper)
        XCTAssertNil(weakField)
    }

    private func show(_ controller: UIViewController) -> UIWindow {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 393, height: 852))
        window.rootViewController = controller
        window.makeKeyAndVisible()
        controller.view.layoutIfNeeded()
        return window
    }
}

@MainActor
private final class InputDelegate: NSObject, UITextFieldDelegate, UITableViewDelegate, UICollectionViewDelegate {
    var returnCount = 0

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        returnCount += 1
        return true
    }
}
