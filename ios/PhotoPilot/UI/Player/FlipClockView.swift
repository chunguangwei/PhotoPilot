import UIKit

final class FlipClockView: UIView {
    var hour24: Bool?

    private let hourCard = FlipCardView()
    private let minuteCard = FlipCardView()
    private let secondCard = FlipCardView()
    private let periodLabel = UILabel()
    private var lastKey: String = ""

    override init(frame: CGRect) {
        super.init(frame: frame)
        let row = UIStackView(arrangedSubviews: [hourCard, minuteCard, secondCard])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = ThemeMetrics.space16
        row.translatesAutoresizingMaskIntoConstraints = false

        periodLabel.font = ThemeFont.meta()
        periodLabel.textColor = ThemeColor.textOnPhoto
        periodLabel.isHidden = true

        let stack = UIStackView(arrangedSubviews: [row, periodLabel])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = ThemeMetrics.space8
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: topAnchor),
            stack.bottomAnchor.constraint(equalTo: bottomAnchor),
            stack.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor),
            stack.centerXAnchor.constraint(equalTo: centerXAnchor),
            row.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor),
        ])
        for card in [hourCard, minuteCard, secondCard] {
            card.heightAnchor.constraint(equalTo: row.heightAnchor).isActive = true
            card.widthAnchor.constraint(equalTo: card.heightAnchor, multiplier: 0.78).isActive = true
        }
        isAccessibilityElement = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    func setDate(_ date: Date) {
        let calendar = Calendar.current
        let resolved24: Bool
        if let hour24 {
            resolved24 = hour24
        } else {
            let template = DateFormatter.dateFormat(
                fromTemplate: "j",
                options: 0,
                locale: Locale.current
            ) ?? "HH"
            resolved24 = !template.contains("a")
        }
        var hour = calendar.component(.hour, from: date)
        var period: String?
        if !resolved24 {
            period = hour < 12 ? "AM" : "PM"
            hour = hour % 12 == 0 ? 12 : hour % 12
        }
        let hourText = String(format: "%02d", hour)
        let minuteText = String(format: "%02d", calendar.component(.minute, from: date))
        let secondText = String(format: "%02d", calendar.component(.second, from: date))
        let key = hourText + minuteText + secondText + (period ?? "")
        guard key != lastKey else { return }
        lastKey = key
        hourCard.setValue(hourText)
        minuteCard.setValue(minuteText)
        secondCard.setValue(secondText)
        periodLabel.text = period
        periodLabel.isHidden = period == nil
    }
}

private final class FlipCardView: UIView {
    private let topHalf = HalfLabelView(isTop: true)
    private let bottomHalf = HalfLabelView(isTop: false)
    private let flipTop = HalfLabelView(isTop: true)
    private let separator = UIView()

    private(set) var value = ""
    private var animating = false
    private var pendingValue: String?
    private var laidOut = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = ThemeColor.restCard
        layer.cornerRadius = ThemeMetrics.radiusGroup
        clipsToBounds = true

        for half in [topHalf, bottomHalf, flipTop] {
            half.translatesAutoresizingMaskIntoConstraints = false
            addSubview(half)
        }
        flipTop.isHidden = true
        flipTop.layer.zPosition = 3
        flipTop.layer.isDoubleSided = false

        separator.backgroundColor = ThemeColor.restSurface
        separator.translatesAutoresizingMaskIntoConstraints = false
        separator.layer.zPosition = 4
        addSubview(separator)

        NSLayoutConstraint.activate([
            topHalf.topAnchor.constraint(equalTo: topAnchor),
            topHalf.leadingAnchor.constraint(equalTo: leadingAnchor),
            topHalf.trailingAnchor.constraint(equalTo: trailingAnchor),
            topHalf.heightAnchor.constraint(equalTo: heightAnchor, multiplier: 0.5),
            bottomHalf.bottomAnchor.constraint(equalTo: bottomAnchor),
            bottomHalf.leadingAnchor.constraint(equalTo: leadingAnchor),
            bottomHalf.trailingAnchor.constraint(equalTo: trailingAnchor),
            bottomHalf.heightAnchor.constraint(equalTo: heightAnchor, multiplier: 0.5),
            flipTop.topAnchor.constraint(equalTo: topAnchor),
            flipTop.leadingAnchor.constraint(equalTo: leadingAnchor),
            flipTop.trailingAnchor.constraint(equalTo: trailingAnchor),
            flipTop.heightAnchor.constraint(equalTo: heightAnchor, multiplier: 0.5),
            separator.centerYAnchor.constraint(equalTo: centerYAnchor),
            separator.leadingAnchor.constraint(equalTo: leadingAnchor),
            separator.trailingAnchor.constraint(equalTo: trailingAnchor),
            separator.heightAnchor.constraint(equalToConstant: 1.5),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let fontSize = max(bounds.height * 0.62, 12)
        for half in [topHalf, bottomHalf, flipTop] {
            half.label.font = .monospacedDigitSystemFont(ofSize: fontSize, weight: .bold)
        }
        setAnchor(CGPoint(x: 0.5, y: 1), for: flipTop)
        laidOut = true
    }

    func setValue(_ next: String) {
        guard next != value else { return }
        if animating {
            pendingValue = next
            return
        }
        startFlip(to: next)
    }

    private func startFlip(to next: String) {
        let old = value
        value = next
        animating = true
        topHalf.text = next
        flipTop.text = old
        bottomHalf.text = old
        if !laidOut { layoutIfNeeded() }
        flipTop.layer.transform = CATransform3DIdentity
        flipTop.isHidden = false
        topHalf.shade.alpha = 0.35
        flipTop.shade.alpha = 0
        let duration = 0.85
        UIView.animate(withDuration: duration, delay: 0, options: [.curveEaseInOut, .beginFromCurrentState]) {
            self.flipTop.layer.transform = self.flipRotation(-179.9)
            self.flipTop.shade.alpha = 0.5
            self.topHalf.shade.alpha = 0
        } completion: { _ in
            self.flipTop.isHidden = true
            self.flipTop.layer.transform = CATransform3DIdentity
            self.bottomHalf.text = next
            self.animating = false
            if let pending = self.pendingValue, pending != next {
                self.pendingValue = nil
                self.startFlip(to: pending)
            } else {
                self.pendingValue = nil
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + duration * 0.5) { [weak self] in
            guard let self, self.animating, self.value == next else { return }
            self.bottomHalf.text = next
        }
    }

    private func flipRotation(_ degrees: CGFloat) -> CATransform3D {
        var transform = CATransform3DIdentity
        transform.m34 = -1.0 / max(bounds.height * 5, 320)
        return CATransform3DRotate(transform, degrees * .pi / 180, 1, 0, 0)
    }

    private func setAnchor(_ anchor: CGPoint, for view: UIView) {
        let oldPoint = CGPoint(
            x: view.bounds.width * view.layer.anchorPoint.x,
            y: view.bounds.height * view.layer.anchorPoint.y
        )
        let newPoint = CGPoint(
            x: view.bounds.width * anchor.x,
            y: view.bounds.height * anchor.y
        )
        var position = view.layer.position
        position.x += newPoint.x - oldPoint.x
        position.y += newPoint.y - oldPoint.y
        view.layer.anchorPoint = anchor
        view.layer.position = position
    }
}

private final class HalfLabelView: UIView {
    let label = UILabel()
    let shade = UIView()

    init(isTop: Bool) {
        super.init(frame: .zero)
        clipsToBounds = true
        backgroundColor = ThemeColor.restCard
        label.textColor = ThemeColor.textOnPhoto
        label.textAlignment = .center
        label.lineBreakMode = .byClipping
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.4
        label.baselineAdjustment = .alignCenters
        label.translatesAutoresizingMaskIntoConstraints = false
        addSubview(label)
        shade.backgroundColor = .black
        shade.alpha = 0
        shade.isUserInteractionEnabled = false
        shade.translatesAutoresizingMaskIntoConstraints = false
        addSubview(shade)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: centerXAnchor),
            label.widthAnchor.constraint(equalTo: widthAnchor),
            label.heightAnchor.constraint(equalTo: heightAnchor, multiplier: 2),
            isTop
                ? label.topAnchor.constraint(equalTo: topAnchor)
                : label.bottomAnchor.constraint(equalTo: bottomAnchor),
            shade.topAnchor.constraint(equalTo: topAnchor),
            shade.bottomAnchor.constraint(equalTo: bottomAnchor),
            shade.leadingAnchor.constraint(equalTo: leadingAnchor),
            shade.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
        isAccessibilityElement = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    var text: String {
        get { label.text ?? "" }
        set { label.text = newValue }
    }
}
