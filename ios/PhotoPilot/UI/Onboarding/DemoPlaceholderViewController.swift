import UIKit

enum DemoImageFactory {
    static let sampleCount = 5

    static func makeImages(size: CGSize, scale: CGFloat) -> [UIImage] {
        let pixelSize = CGSize(
            width: max(size.width * scale, 1),
            height: max(size.height * scale, 1)
        )
        return (0..<sampleCount).map { index in
            makeImage(index: index, pixelSize: pixelSize)
        }
    }

    private static func makeImage(index: Int, pixelSize: CGSize) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: pixelSize, format: format)
        return renderer.image { context in
            let rect = CGRect(origin: .zero, size: pixelSize)
            let hueShift = CGFloat(index) / CGFloat(sampleCount)
            let topColor = shifted(ThemeColor.canvas, hueShift: hueShift, lightness: 0.08)
            let bottomColor = shifted(ThemeColor.accentBrand, hueShift: hueShift, lightness: -0.18)
            let colors = [topColor.cgColor, bottomColor.cgColor] as CFArray
            if let gradient = CGGradient(
                colorsSpace: CGColorSpaceCreateDeviceRGB(),
                colors: colors,
                locations: [0, 1]
            ) {
                context.cgContext.drawLinearGradient(
                    gradient,
                    start: CGPoint(x: rect.minX, y: rect.minY),
                    end: CGPoint(x: rect.maxX, y: rect.maxY),
                    options: []
                )
            }
            let dotDiameter = min(pixelSize.width, pixelSize.height) / 6
            let dotRect = CGRect(
                x: pixelSize.width * (0.28 + 0.1 * CGFloat(index % 3)) - dotDiameter / 2,
                y: pixelSize.height * (0.3 + 0.12 * CGFloat(index % 2)) - dotDiameter / 2,
                width: dotDiameter,
                height: dotDiameter
            )
            context.cgContext.setFillColor(
                shifted(ThemeColor.accentBrand, hueShift: hueShift, lightness: 0.12).cgColor
            )
            context.cgContext.fillEllipse(in: dotRect)
            let haloRect = dotRect.insetBy(dx: -dotDiameter / 3, dy: -dotDiameter / 3)
            context.cgContext.setStrokeColor(ThemeColor.textOnPhoto.withAlphaComponent(0.35).cgColor)
            context.cgContext.setLineWidth(max(dotDiameter / 24, 1))
            context.cgContext.strokeEllipse(in: haloRect)
        }
    }

    private static func shifted(_ color: UIColor, hueShift: CGFloat, lightness: CGFloat) -> UIColor {
        var hue: CGFloat = 0
        var saturation: CGFloat = 0
        var brightness: CGFloat = 0
        var alpha: CGFloat = 0
        let resolved = color.resolvedColor(with: UITraitCollection(userInterfaceStyle: .light))
        guard resolved.getHue(&hue, saturation: &saturation, brightness: &brightness, alpha: &alpha) else {
            return color
        }
        return UIColor(
            hue: (hue + hueShift).truncatingRemainder(dividingBy: 1),
            saturation: min(max(saturation * 0.7, 0), 1),
            brightness: min(max(brightness + lightness, 0), 1),
            alpha: alpha
        )
    }
}

final class DemoPlaceholderViewController: UIViewController {
    private static let holdNanos: UInt64 = 3_000_000_000
    private static let fadeDuration: TimeInterval = 0.8

    private let frontImageView = UIImageView()
    private let backImageView = UIImageView()
    private let overlayBar = UIView()
    private var images: [UIImage] = []
    private var index = 0
    private var playTask: Task<Void, Never>?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ThemeColor.restSurface

        backImageView.contentMode = .scaleAspectFill
        backImageView.clipsToBounds = true
        backImageView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(backImageView)
        frontImageView.contentMode = .scaleAspectFill
        frontImageView.clipsToBounds = true
        frontImageView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(frontImageView)

        let badgeLabel = UILabel()
        badgeLabel.text = L10n.string("common.demo_badge")
        badgeLabel.font = ThemeFont.caption()
        badgeLabel.adjustsFontForContentSizeCategory = true
        badgeLabel.textColor = ThemeColor.textOnPhoto

        let noticeLabel = UILabel()
        noticeLabel.text = L10n.string("welcome.demo_notice")
        noticeLabel.font = ThemeFont.meta()
        noticeLabel.adjustsFontForContentSizeCategory = true
        noticeLabel.textColor = ThemeColor.textOnPhoto
        noticeLabel.numberOfLines = 0

        let exitButton = ActionButton(titleKey: "welcome.demo_exit", style: .primary)
        exitButton.addTarget(self, action: #selector(handleExit), for: .touchUpInside)
        exitButton.setContentHuggingPriority(.required, for: .horizontal)

        let barContent = UIStackView(arrangedSubviews: [badgeLabel, noticeLabel, exitButton])
        barContent.axis = .horizontal
        barContent.alignment = .center
        barContent.spacing = ThemeMetrics.space12
        barContent.translatesAutoresizingMaskIntoConstraints = false

        let bar = overlayBar
        bar.backgroundColor = ThemeColor.photoControl.withAlphaComponent(ThemeMetrics.controlOpacity)
        bar.translatesAutoresizingMaskIntoConstraints = false
        bar.addSubview(barContent)
        view.addSubview(bar)

        NSLayoutConstraint.activate([
            backImageView.topAnchor.constraint(equalTo: view.topAnchor),
            backImageView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            backImageView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            backImageView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            frontImageView.topAnchor.constraint(equalTo: view.topAnchor),
            frontImageView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            frontImageView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            frontImageView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            bar.topAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.topAnchor,
                constant: ThemeMetrics.space16
            ),
            bar.leadingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            bar.trailingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            bar.heightAnchor.constraint(greaterThanOrEqualToConstant: ThemeMetrics.touchTarget),
            barContent.topAnchor.constraint(equalTo: bar.topAnchor, constant: ThemeMetrics.space8),
            barContent.bottomAnchor.constraint(equalTo: bar.bottomAnchor, constant: -ThemeMetrics.space8),
            barContent.leadingAnchor.constraint(equalTo: bar.leadingAnchor, constant: ThemeMetrics.space16),
            barContent.trailingAnchor.constraint(
                equalTo: bar.trailingAnchor,
                constant: -ThemeMetrics.space16
            ),
        ])
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        overlayBar.layer.cornerRadius = ThemeMetrics.radiusGroup
        guard images.isEmpty, view.bounds.width > 0 else { return }
        images = DemoImageFactory.makeImages(
            size: view.bounds.size,
            scale: view.traitCollection.displayScale
        )
        frontImageView.image = images.first
        startPlayback()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        playTask?.cancel()
        playTask = nil
    }

    private func startPlayback() {
        playTask?.cancel()
        playTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: Self.holdNanos)
                guard !Task.isCancelled else { return }
                self?.advance()
            }
        }
    }

    private func advance() {
        guard images.count > 1 else { return }
        let next = (index + 1) % images.count
        let image = images[next]
        index = next
        guard !UIAccessibility.isReduceMotionEnabled else {
            frontImageView.image = image
            return
        }
        backImageView.image = image
        backImageView.alpha = 0
        UIView.animate(
            withDuration: Self.fadeDuration,
            animations: {
                self.backImageView.alpha = 1
                self.frontImageView.alpha = 0
            },
            completion: { [weak self] _ in
                guard let self else { return }
                frontImageView.image = backImageView.image
                frontImageView.alpha = 1
                backImageView.image = nil
                backImageView.alpha = 0
            }
        )
    }

    @objc private func handleExit() {
        dismiss(animated: true)
    }
}
