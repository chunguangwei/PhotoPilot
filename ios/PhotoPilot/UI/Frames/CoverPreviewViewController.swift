import UIKit

final class CoverPreviewViewController: UIViewController {
    private let loader: ThumbnailLoader
    private let relPath: String?
    private let frameName: String
    private let photoCount: Int
    private let onConfirm: () -> Void
    private var loadTask: Task<Void, Never>?

    init(
        environment: AppEnvironment,
        relPath: String?,
        frameName: String,
        photoCount: Int,
        onConfirm: @escaping () -> Void
    ) {
        self.loader = ThumbnailLoader(originalsRoot: environment.originalsRootURL)
        self.relPath = relPath
        self.frameName = frameName
        self.photoCount = photoCount
        self.onConfirm = onConfirm
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ThemeColor.photoControl.withAlphaComponent(0.55)

        let titleLabel = UILabel()
        titleLabel.text = L10n.string("frames.set_cover")
        titleLabel.font = ThemeFont.heading()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textOnPhoto
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 0
        titleLabel.accessibilityTraits.insert(.header)

        let card = UIView()
        card.backgroundColor = ThemeColor.panel
        card.layer.cornerRadius = ThemeMetrics.radiusGroup
        card.clipsToBounds = true

        let coverView = UIImageView()
        coverView.contentMode = .scaleAspectFill
        coverView.clipsToBounds = true
        coverView.backgroundColor = ThemeColor.canvas
        coverView.isAccessibilityElement = false
        coverView.heightAnchor.constraint(
            equalTo: coverView.widthAnchor,
            multiplier: 10.0 / 16.0
        ).isActive = true

        let nameLabel = UILabel()
        nameLabel.text = frameName
        nameLabel.font = ThemeFont.heading()
        nameLabel.adjustsFontForContentSizeCategory = true
        nameLabel.textColor = ThemeColor.textPrimary
        nameLabel.numberOfLines = 0

        let countLabel = UILabel()
        countLabel.text = String(format: L10n.string("frames.playable_count"), photoCount)
        countLabel.font = ThemeFont.meta()
        countLabel.adjustsFontForContentSizeCategory = true
        countLabel.textColor = ThemeColor.textSecondary
        countLabel.numberOfLines = 0

        let confirmButton = ActionButton(titleKey: "frames.set_cover", style: .primary)
        confirmButton.addTarget(self, action: #selector(handleConfirm), for: .touchUpInside)
        let cancelButton = ActionButton(titleKey: "common.cancel", style: .secondary)
        cancelButton.addTarget(self, action: #selector(handleCancel), for: .touchUpInside)
        let buttons = UIStackView(arrangedSubviews: [cancelButton, confirmButton])
        buttons.axis = .horizontal
        buttons.distribution = .fillEqually
        buttons.spacing = ThemeMetrics.space8

        let details = UIStackView(arrangedSubviews: [nameLabel, countLabel, buttons])
        details.axis = .vertical
        details.spacing = ThemeMetrics.space16
        details.isLayoutMarginsRelativeArrangement = true
        details.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)

        let content = UIStackView(arrangedSubviews: [coverView, details])
        content.axis = .vertical
        content.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(content)
        NSLayoutConstraint.activate([
            content.topAnchor.constraint(equalTo: card.topAnchor),
            content.bottomAnchor.constraint(equalTo: card.bottomAnchor),
            content.leadingAnchor.constraint(equalTo: card.leadingAnchor),
            content.trailingAnchor.constraint(equalTo: card.trailingAnchor),
        ])

        let stack = UIStackView(arrangedSubviews: [titleLabel, card])
        stack.axis = .vertical
        stack.spacing = ThemeMetrics.space16
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        let readable = stack.widthAnchor.constraint(equalToConstant: 420)
        readable.priority = .defaultHigh
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor),
            stack.leadingAnchor.constraint(
                greaterThanOrEqualTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            stack.trailingAnchor.constraint(
                lessThanOrEqualTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            readable,
        ])

        loadCover(into: coverView)
    }

    private func loadCover(into coverView: UIImageView) {
        guard let relPath else { return }
        let scale = traitCollection.displayScale
        loadTask = Task { [weak self, loader, weak coverView] in
            let image = await loader.thumbnail(relPath: relPath, pointSize: 420, scale: scale)
            guard !Task.isCancelled, let self, let coverView else { return }
            coverView.image = image
        }
    }

    @objc private func handleConfirm() {
        onConfirm()
        dismiss(animated: true)
    }

    @objc private func handleCancel() {
        dismiss(animated: true)
    }

    deinit {
        loadTask?.cancel()
    }
}
