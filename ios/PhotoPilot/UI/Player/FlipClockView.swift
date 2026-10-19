import UIKit

final class FlipClockView: UIView {
    var hour24: Bool?

    private let hourCard = FlipCardView()
    private let minuteCard = FlipCardView()
    private let secondCard = FlipCardView()
    private let periodLabel = UILabel()
    private var lastKey: String = ""
    private var lastDate: Date?

    override init(frame: CGRect) {
        super.init(frame: frame)
        let row = UIStackView(arrangedSubviews: [hourCard, minuteCard, secondCard])
        row.axis = .horizontal
        row.alignment = .fill
        row.distribution = .fillEqually
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
        let preferredHeight = row.heightAnchor.constraint(equalTo: heightAnchor)
        preferredHeight.priority = .defaultHigh
        NSLayoutConstraint.activate([
            preferredHeight,
            stack.topAnchor.constraint(greaterThanOrEqualTo: topAnchor),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: bottomAnchor),
            stack.centerYAnchor.constraint(equalTo: centerYAnchor),
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
        let animate = lastDate.map { date.timeIntervalSince($0) > 0 && date.timeIntervalSince($0) < 1.5 } ?? false
        lastDate = date
        guard key != lastKey else { return }
        lastKey = key
        let beginTime = CACurrentMediaTime()
        hourCard.setValue(hourText, animate: animate, beginTime: beginTime)
        minuteCard.setValue(minuteText, animate: animate, beginTime: beginTime)
        secondCard.setValue(secondText, animate: animate, beginTime: beginTime)
        periodLabel.text = period
        periodLabel.isHidden = period == nil
    }
}

private final class FlipCardView: UIView, CAAnimationDelegate {
    private let topHalf = HalfLabelView(isTop: true)
    private let bottomHalf = HalfLabelView(isTop: false)
    private let flipBottom = HalfLabelView(isTop: false)
    private let flipTop = HalfLabelView(isTop: true)
    private let separator = UIView()
    private var value = ""
    private var animationToken = 0
    private var previousSize = CGSize.zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = ThemeColor.restCard
        layer.cornerRadius = ThemeMetrics.radiusGroup
        clipsToBounds = true
        for half in [topHalf, bottomHalf, flipBottom, flipTop] {
            addSubview(half)
        }
        flipBottom.layer.anchorPoint = CGPoint(x: 0.5, y: 0)
        flipTop.layer.anchorPoint = CGPoint(x: 0.5, y: 1)
        flipBottom.layer.isDoubleSided = false
        flipTop.layer.isDoubleSided = false
        flipBottom.layer.opacity = 0
        flipTop.layer.opacity = 0
        separator.backgroundColor = ThemeColor.restSurface
        addSubview(separator)
        NotificationCenter.default.addObserver(
            self, selector: #selector(stopFlip), name: UIApplication.willResignActiveNotification, object: nil
        )
        NotificationCenter.default.addObserver(
            self, selector: #selector(stopFlip), name: UIAccessibility.reduceMotionStatusDidChangeNotification, object: nil
        )
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.size != previousSize else { return }
        stopFlip()
        previousSize = bounds.size
        let halfHeight = bounds.height / 2
        let font = UIFont.monospacedDigitSystemFont(ofSize: max(bounds.height * 0.62, 12), weight: .bold)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        topHalf.frame = CGRect(x: 0, y: 0, width: bounds.width, height: halfHeight)
        bottomHalf.frame = CGRect(x: 0, y: halfHeight, width: bounds.width, height: halfHeight)
        for flap in [flipBottom, flipTop] {
            flap.bounds = CGRect(x: 0, y: 0, width: bounds.width, height: halfHeight)
            flap.layer.position = CGPoint(x: bounds.midX, y: halfHeight)
        }
        for half in [topHalf, bottomHalf, flipBottom, flipTop] {
            half.label.font = font
        }
        separator.frame = CGRect(x: 0, y: halfHeight - 0.75, width: bounds.width, height: 1.5)
        CATransaction.commit()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { stopFlip() }
    }

    func setValue(_ next: String, animate: Bool, beginTime: CFTimeInterval) {
        guard next != value else { return }
        layoutIfNeeded()
        let old = value
        stopFlip()
        value = next
        guard animate, !old.isEmpty, window != nil, bounds.height > 0,
              !UIAccessibility.isReduceMotionEnabled, UIView.areAnimationsEnabled,
              UIApplication.shared.applicationState == .active else {
            stopFlip()
            return
        }
        topHalf.text = old
        bottomHalf.text = next
        flipBottom.text = old
        flipTop.text = next
        layoutIfNeeded()
        let token = animationToken
        let duration = 0.92
        let times = (0...120).map { NSNumber(value: Double($0) / 120) }
        let progress = (0...120).map { index -> CGFloat in
            let t = CGFloat(index) / 120
            return t * t * (3 - 2 * t)
        }
        let outgoing = progress.map { rotation(min($0 * .pi, .pi / 2)) }
        let incoming = progress.map { rotation(max(($0 - 1) * .pi, -.pi / 2)) }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for (flap, transforms, isIncoming) in [(flipBottom, outgoing, false), (flipTop, incoming, true)] {
            let transform = CAKeyframeAnimation(keyPath: "transform")
            transform.values = transforms.map { NSValue(caTransform3D: $0) }
            transform.keyTimes = times
            transform.duration = duration
            let visibility = CAKeyframeAnimation(keyPath: "opacity")
            visibility.values = isIncoming ? [0, 1, 1] : [1, 0, 0]
            visibility.keyTimes = [0, 0.5, 1]
            visibility.calculationMode = .discrete
            visibility.duration = duration
            let group = CAAnimationGroup()
            group.animations = [transform, visibility]
            group.duration = duration
            group.fillMode = .forwards
            group.isRemovedOnCompletion = false
            group.beginTime = flap.layer.convertTime(beginTime, from: nil)
            if isIncoming {
                group.delegate = self
                group.setValue(token, forKey: "flipToken")
            }
            flap.layer.add(group, forKey: "flip")
            let shade = CAKeyframeAnimation(keyPath: "opacity")
            shade.values = progress.map { NSNumber(value: Double(isIncoming ? 0.3 * min(2 * (1 - $0), 1) : 0.3 * min(2 * $0, 1))) }
            shade.keyTimes = times
            shade.duration = duration
            shade.beginTime = flap.shade.layer.convertTime(beginTime, from: nil)
            flap.shade.layer.add(shade, forKey: "flipShade")
        }
        CATransaction.commit()
    }

    nonisolated func animationDidStop(_ anim: CAAnimation, finished flag: Bool) {
        guard flag, let token = anim.value(forKey: "flipToken") as? Int else { return }
        Task { @MainActor [weak self] in
            guard let self, self.animationToken == token else { return }
            self.stopFlip()
        }
    }

    @objc private func stopFlip() {
        animationToken += 1
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for flap in [flipBottom, flipTop] {
            flap.layer.removeAnimation(forKey: "flip")
            flap.shade.layer.removeAnimation(forKey: "flipShade")
            flap.layer.opacity = 0
        }
        topHalf.text = value
        bottomHalf.text = value
        CATransaction.commit()
    }

    private func rotation(_ radians: CGFloat) -> CATransform3D {
        var transform = CATransform3DIdentity
        transform.m34 = -1 / max(bounds.height * 5, 320)
        return CATransform3DRotate(transform, radians, 1, 0, 0)
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
