import UIKit

final class BrandMarkView: UIView {
    static let revealDuration: TimeInterval = 1.2

    private let geometryLayer = CALayer()
    private let outlineLayer = CAShapeLayer()
    private let dotLayer = CAShapeLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        configureLayers()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configureLayers()
    }

    override var intrinsicContentSize: CGSize {
        CGSize(width: 152, height: 152)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        geometryLayer.position = CGPoint(x: bounds.midX, y: bounds.midY)
        let scale = min(bounds.width, bounds.height) / 256
        geometryLayer.setAffineTransform(CGAffineTransform(scaleX: scale, y: scale))
        CATransaction.commit()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        updateColors()
    }

    func reveal(elapsed: TimeInterval = 0) {
        stopAnimation()
        guard !UIAccessibility.isReduceMotionEnabled,
              elapsed >= 0, elapsed < 5.6 else { return }
        let beganAt = CACurrentMediaTime() - elapsed
        if elapsed < Self.revealDuration {
            let stroke = CABasicAnimation(keyPath: "strokeEnd")
            stroke.fromValue = 0
            stroke.toValue = 1
            stroke.duration = Self.revealDuration
            stroke.beginTime = beganAt
            stroke.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            outlineLayer.add(stroke, forKey: "brandReveal")
        }

        let times: [NSNumber] = [0, 0.0625, 0.1517857, 0.2142857, 0.4107143, 0.6071429, 0.8035714, 1]
        let opacity = CAKeyframeAnimation(keyPath: "opacity")
        opacity.values = [0, 0, 1, 1, 0.82, 1, 0.82, 1]
        opacity.keyTimes = times
        opacity.duration = 5.6
        let scale = CAKeyframeAnimation(keyPath: "transform.scale")
        scale.values = [0.7, 0.7, 1, 1, 1.08, 1, 1.08, 1]
        scale.keyTimes = times
        scale.duration = 5.6
        let curves = Array(repeating: CAMediaTimingFunction(name: .easeInEaseOut), count: times.count - 1)
        opacity.timingFunctions = curves
        scale.timingFunctions = curves
        let dot = CAAnimationGroup()
        dot.animations = [opacity, scale]
        dot.duration = 5.6
        dot.beginTime = beganAt
        dotLayer.add(dot, forKey: "brandDotReveal")
    }

    func stopAnimation() {
        outlineLayer.removeAllAnimations()
        dotLayer.removeAllAnimations()
    }

    private func configureLayers() {
        isAccessibilityElement = false
        isUserInteractionEnabled = false
        geometryLayer.bounds = CGRect(x: 0, y: 0, width: 256, height: 256)
        outlineLayer.frame = geometryLayer.bounds
        outlineLayer.fillColor = nil
        outlineLayer.lineWidth = 14
        outlineLayer.lineCap = .round
        outlineLayer.lineJoin = .round
        let outline = UIBezierPath()
        outline.move(to: CGPoint(x: 164, y: 56))
        outline.addLine(to: CGPoint(x: 84, y: 56))
        outline.addQuadCurve(to: CGPoint(x: 56, y: 84), controlPoint: CGPoint(x: 56, y: 56))
        outline.addLine(to: CGPoint(x: 56, y: 172))
        outline.addQuadCurve(to: CGPoint(x: 84, y: 200), controlPoint: CGPoint(x: 56, y: 200))
        outline.addLine(to: CGPoint(x: 172, y: 200))
        outline.addQuadCurve(to: CGPoint(x: 200, y: 172), controlPoint: CGPoint(x: 200, y: 200))
        outline.addLine(to: CGPoint(x: 200, y: 112))
        outlineLayer.path = outline.cgPath
        dotLayer.bounds = CGRect(x: 0, y: 0, width: 32, height: 32)
        dotLayer.position = CGPoint(x: 200, y: 56)
        dotLayer.path = UIBezierPath(ovalIn: dotLayer.bounds).cgPath
        geometryLayer.addSublayer(outlineLayer)
        geometryLayer.addSublayer(dotLayer)
        layer.addSublayer(geometryLayer)
        updateColors()
    }

    private func updateColors() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        outlineLayer.strokeColor = ThemeColor.textPrimary.resolvedColor(with: traitCollection).cgColor
        dotLayer.fillColor = ThemeColor.accentBrand.resolvedColor(with: traitCollection).cgColor
        CATransaction.commit()
    }
}
