import UIKit

final class ActionButton: UIControl {
    enum Style {
        case primary
        case secondary
        case destructive
    }

    private let titleLabel = UILabel()
    private let style: Style
    var isOptionSelected = false {
        didSet {
            guard isOptionSelected != oldValue else { return }
            applyPalette()
        }
    }

    override var isHighlighted: Bool {
        didSet { updateFeedback() }
    }

    override var isEnabled: Bool {
        didSet { updateFeedback() }
    }

    override var isSelected: Bool {
        didSet { applyPalette() }
    }

    init(titleKey: String, style: Style) {
        self.style = style
        super.init(frame: .zero)

        titleLabel.text = L10n.string(titleKey)
        titleLabel.font = ThemeFont.body()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 0
        titleLabel.lineBreakMode = .byWordWrapping
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.setContentCompressionResistancePriority(.required, for: .vertical)
        addSubview(titleLabel)

        isAccessibilityElement = true
        accessibilityLabel = titleLabel.text
        accessibilityTraits = [.button]

        layer.cornerRadius = ThemeMetrics.radiusGroup
        applyPalette()

        NSLayoutConstraint.activate([
            titleLabel.topAnchor.constraint(equalTo: topAnchor, constant: ThemeMetrics.space12),
            titleLabel.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -ThemeMetrics.space12),
            titleLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: ThemeMetrics.space16),
            titleLabel.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -ThemeMetrics.space16),
            heightAnchor.constraint(greaterThanOrEqualToConstant: ThemeMetrics.touchTarget)
        ])
    }

    private func applyPalette() {
        let selected = isOptionSelected || isSelected
        accessibilityTraits = [.button]
        if selected { accessibilityTraits.insert(.selected) }
        if !isEnabled { accessibilityTraits.insert(.notEnabled) }
        if selected {
            backgroundColor = ThemeColor.accentAction
            titleLabel.textColor = ThemeColor.canvas
        } else {
            switch style {
            case .primary:
                backgroundColor = ThemeColor.textPrimary
                titleLabel.textColor = ThemeColor.canvas
            case .secondary:
                backgroundColor = .clear
                titleLabel.textColor = ThemeColor.accentAction
            case .destructive:
                backgroundColor = .clear
                titleLabel.textColor = ThemeColor.statusError
            }
        }
        updateFeedback()
    }

    private func updateFeedback() {
        alpha = !isEnabled ? 0.45 : (isHighlighted ? 0.72 : 1)
        if isEnabled {
            accessibilityTraits.remove(.notEnabled)
        } else {
            accessibilityTraits.insert(.notEnabled)
        }
        layer.borderWidth = isFocused ? 2 : 0
        layer.borderColor = ThemeColor.accentAction.resolvedColor(with: traitCollection).cgColor
    }

    override func didUpdateFocus(in context: UIFocusUpdateContext, with coordinator: UIFocusAnimationCoordinator) {
        super.didUpdateFocus(in: context, with: coordinator)
        updateFeedback()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        updateFeedback()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override var intrinsicContentSize: CGSize {
        let titleSize = titleLabel.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude))
        return CGSize(
            width: titleSize.width + ThemeMetrics.space16 * 2,
            height: max(ThemeMetrics.touchTarget, titleSize.height + ThemeMetrics.space12 * 2)
        )
    }

    func setTitleKey(_ titleKey: String) {
        titleLabel.text = L10n.string(titleKey)
        accessibilityLabel = titleLabel.text
        invalidateIntrinsicContentSize()
    }

    func setTitle(_ title: String) {
        titleLabel.text = title
        accessibilityLabel = title
        invalidateIntrinsicContentSize()
    }
}
