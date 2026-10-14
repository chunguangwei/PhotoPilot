import UIKit

final class LaunchBrandingView: UIView {
    let skipButton = UIButton(type: .system)
    private let mark = BrandMarkView()
    private let statusLabel = UILabel()
    private let progressView = LaunchBrandProgressView()
    private let presentationLabel = UILabel()
    private let brandLabel = UILabel()
    private let tagline = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        configureLayout()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func updateStatus(isLoading: Bool, isSlow: Bool) {
        statusLabel.isHidden = !isLoading
        statusLabel.text = L10n.string(isSlow ? "launch.taking_longer" : "launch.preparing_library")
    }

    func updatePresentation(_ state: LaunchPresentationState, now: TimeInterval, animate: Bool) {
        let presenting = state.isPresenting(now: now)
        skipButton.isHidden = !presenting
        progressView.isHidden = !presenting
        presentationLabel.isHidden = !presenting
        progressView.update(progress: state.progress(now: now), remaining: state.remaining(now: now), animate: animate)
        if presenting && animate && !UIAccessibility.isReduceMotionEnabled {
            let elapsed = now - (state.startedAt ?? now)
            mark.reveal(elapsed: elapsed)
            revealText(brandLabel, elapsed: elapsed, delay: 0.25, duration: 0.65, distance: 8)
            revealText(tagline, elapsed: elapsed, delay: 0.45, duration: 0.65, distance: 6)
        } else {
            mark.stopAnimation()
            brandLabel.layer.removeAllAnimations()
            tagline.layer.removeAllAnimations()
        }
    }

    private func revealText(_ label: UILabel, elapsed: TimeInterval, delay: TimeInterval, duration: TimeInterval, distance: CGFloat) {
        label.layer.removeAllAnimations()
        guard elapsed < delay + duration else { return }
        let opacity = CABasicAnimation(keyPath: "opacity")
        opacity.fromValue = 0
        opacity.toValue = 1
        opacity.duration = duration
        let position = CABasicAnimation(keyPath: "transform.translation.y")
        position.fromValue = distance
        position.toValue = 0
        position.duration = duration
        let group = CAAnimationGroup()
        group.animations = [opacity, position]
        group.beginTime = CACurrentMediaTime() - elapsed + delay
        group.duration = duration
        group.fillMode = .backwards
        group.timingFunction = CAMediaTimingFunction(name: .easeOut)
        label.layer.add(group, forKey: "brandTextReveal")
    }

    func stopAnimations() {
        brandLabel.layer.removeAllAnimations()
        tagline.layer.removeAllAnimations()
        mark.stopAnimation()
        progressView.layer.removeAllAnimations()
        progressView.stopAnimation()
    }

    private func configureLayout() {
        backgroundColor = ThemeColor.canvas
        skipButton.setTitle(L10n.string("common.skip"), for: .normal)
        skipButton.setTitleColor(ThemeColor.accentAction, for: .normal)
        skipButton.titleLabel?.font = UIFontMetrics(forTextStyle: .body).scaledFont(
            for: UIFont.systemFont(ofSize: 17), maximumPointSize: 22
        )
        skipButton.titleLabel?.adjustsFontForContentSizeCategory = true
        skipButton.accessibilityIdentifier = "launch.skip"
        skipButton.translatesAutoresizingMaskIntoConstraints = false
        addSubview(skipButton)

        brandLabel.text = L10n.string("brand.name")
        brandLabel.font = UIFontMetrics(forTextStyle: .largeTitle).scaledFont(
            for: UIFont.systemFont(ofSize: 42, weight: .semibold)
        )
        brandLabel.adjustsFontForContentSizeCategory = true
        brandLabel.textColor = ThemeColor.textPrimary
        brandLabel.textAlignment = .center
        brandLabel.numberOfLines = 0
        brandLabel.lineBreakMode = .byCharWrapping
        brandLabel.accessibilityTraits.insert(.header)

        tagline.text = L10n.string("brand.tagline")
        tagline.font = ThemeFont.body()
        tagline.adjustsFontForContentSizeCategory = true
        tagline.textColor = ThemeColor.textSecondary
        tagline.textAlignment = .center
        tagline.numberOfLines = 0

        let markContainer = UIView()
        mark.translatesAutoresizingMaskIntoConstraints = false
        markContainer.addSubview(mark)
        NSLayoutConstraint.activate([
            mark.widthAnchor.constraint(equalToConstant: 152),
            mark.heightAnchor.constraint(equalToConstant: 152),
            mark.centerXAnchor.constraint(equalTo: markContainer.centerXAnchor),
            mark.topAnchor.constraint(equalTo: markContainer.topAnchor),
            mark.bottomAnchor.constraint(equalTo: markContainer.bottomAnchor)
        ])

        statusLabel.font = ThemeFont.caption()
        statusLabel.adjustsFontForContentSizeCategory = true
        statusLabel.textColor = ThemeColor.textSecondary
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        statusLabel.accessibilityIdentifier = "launch.initializationStatus"
        let stack = UIStackView(arrangedSubviews: [markContainer, brandLabel, tagline, statusLabel])
        stack.axis = .vertical
        stack.alignment = .fill
        stack.spacing = ThemeMetrics.space16
        stack.setCustomSpacing(ThemeMetrics.space32, after: tagline)
        stack.translatesAutoresizingMaskIntoConstraints = false

        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        let column = UIView()
        column.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(column)
        column.addSubview(stack)
        addSubview(scroll)
        progressView.translatesAutoresizingMaskIntoConstraints = false
        addSubview(progressView)
        presentationLabel.text = L10n.string("launch.brand_presentation")
        presentationLabel.font = ThemeFont.meta()
        presentationLabel.adjustsFontForContentSizeCategory = true
        presentationLabel.textColor = ThemeColor.textSecondary
        presentationLabel.textAlignment = .center
        presentationLabel.numberOfLines = 0
        presentationLabel.accessibilityIdentifier = "launch.brandPresentation"
        presentationLabel.translatesAutoresizingMaskIntoConstraints = false
        addSubview(presentationLabel)
        let columnHeight = column.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor)
        columnHeight.priority = .defaultLow
        let columnWidth = stack.widthAnchor.constraint(equalTo: column.widthAnchor, constant: -48)
        columnWidth.priority = UILayoutPriority(999)
        NSLayoutConstraint.activate([
            skipButton.topAnchor.constraint(equalTo: safeAreaLayoutGuide.topAnchor, constant: 8),
            skipButton.trailingAnchor.constraint(equalTo: safeAreaLayoutGuide.trailingAnchor, constant: -16),
            skipButton.leadingAnchor.constraint(greaterThanOrEqualTo: safeAreaLayoutGuide.leadingAnchor, constant: 16),
            skipButton.widthAnchor.constraint(greaterThanOrEqualToConstant: 64),
            skipButton.heightAnchor.constraint(equalToConstant: 44),
            scroll.topAnchor.constraint(equalTo: skipButton.bottomAnchor, constant: 8),
            scroll.leadingAnchor.constraint(equalTo: safeAreaLayoutGuide.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: safeAreaLayoutGuide.trailingAnchor),
            scroll.bottomAnchor.constraint(equalTo: presentationLabel.topAnchor, constant: -24),
            column.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            column.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            column.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            column.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            column.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor),
            columnHeight,
            stack.centerXAnchor.constraint(equalTo: column.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: column.centerYAnchor),
            stack.topAnchor.constraint(greaterThanOrEqualTo: column.topAnchor, constant: 24),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: column.bottomAnchor, constant: -24),
            columnWidth,
            stack.widthAnchor.constraint(lessThanOrEqualToConstant: ThemeMetrics.bodyMaxWidth),
            presentationLabel.leadingAnchor.constraint(equalTo: progressView.leadingAnchor),
            presentationLabel.trailingAnchor.constraint(equalTo: progressView.trailingAnchor),
            presentationLabel.bottomAnchor.constraint(equalTo: progressView.topAnchor, constant: -8),
            progressView.leadingAnchor.constraint(equalTo: safeAreaLayoutGuide.leadingAnchor, constant: 32),
            progressView.trailingAnchor.constraint(equalTo: safeAreaLayoutGuide.trailingAnchor, constant: -32),
            progressView.bottomAnchor.constraint(equalTo: safeAreaLayoutGuide.bottomAnchor, constant: -24),
            progressView.heightAnchor.constraint(equalToConstant: 2)
        ])
    }
}

private final class LaunchBrandProgressView: UIView {
    private let fillLayer = CAShapeLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = false
        accessibilityElementsHidden = true
        isUserInteractionEnabled = false
        backgroundColor = ThemeColor.separator
        fillLayer.lineWidth = 2
        layer.addSublayer(fillLayer)
        updateColors()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        fillLayer.frame = bounds
        let path = UIBezierPath()
        path.move(to: CGPoint(x: 0, y: bounds.midY))
        path.addLine(to: CGPoint(x: bounds.width, y: bounds.midY))
        fillLayer.path = path.cgPath
        CATransaction.commit()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        updateColors()
    }

    func update(progress: Double, remaining: TimeInterval, animate: Bool) {
        stopAnimation()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        fillLayer.strokeEnd = animate && !UIAccessibility.isReduceMotionEnabled ? 1 : CGFloat(progress)
        CATransaction.commit()
        guard animate, remaining > 0, !UIAccessibility.isReduceMotionEnabled else { return }
        let animation = CABasicAnimation(keyPath: "strokeEnd")
        animation.fromValue = progress
        animation.toValue = 1
        animation.duration = remaining
        animation.timingFunction = CAMediaTimingFunction(name: .linear)
        fillLayer.add(animation, forKey: "brandTime")
    }

    func stopAnimation() {
        fillLayer.removeAllAnimations()
    }

    private func updateColors() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        fillLayer.strokeColor = ThemeColor.accentBrand.resolvedColor(with: traitCollection).cgColor
        CATransaction.commit()
    }
}
