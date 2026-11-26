import UIKit

final class CoverPreviewViewController: UIViewController {
    private let loader: ThumbnailLoader
    private let relPath: String?
    private let frameName: String
    private let photoCount: Int
    private let storageMode: FrameStorageMode
    private let playbackSummary: String?
    private let onConfirm: () -> Void
    private let coverView = UIImageView()
    private let statusLabel = UILabel()
    private var revoked = false
    private var loadTask: Task<Void, Never>?

    init(
        environment: AppEnvironment,
        relPath: String?,
        frameName: String,
        photoCount: Int,
        playbackSummary: String? = nil,
        storageMode: FrameStorageMode = .remote,
        initialImage: UIImage? = nil,
        onConfirm: @escaping () -> Void
    ) {
        self.loader = ThumbnailLoader(originalsRoot: environment.originalsRootURL, media: environment.remoteMedia)
        self.relPath = relPath
        self.frameName = frameName
        self.photoCount = photoCount
        self.playbackSummary = playbackSummary
        self.storageMode = storageMode
        self.onConfirm = onConfirm
        super.init(nibName: nil, bundle: nil)
        coverView.image = initialImage
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ThemeColor.photoControl.withAlphaComponent(0.55)

        let titleLabel = UILabel()
        titleLabel.text = playbackSummary == nil ? L10n.string("frames.set_cover") : AIText.value("Frame preview", "相框预览")
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

        coverView.contentMode = playbackSummary == nil ? .scaleAspectFill : .scaleAspectFit
        coverView.clipsToBounds = true
        coverView.backgroundColor = ThemeColor.canvas
        coverView.isAccessibilityElement = false
        coverView.heightAnchor.constraint(
            equalTo: coverView.widthAnchor,
            multiplier: 10.0 / 16.0
        ).isActive = true
        statusLabel.font = ThemeFont.meta()
        statusLabel.adjustsFontForContentSizeCategory = true
        statusLabel.textColor = ThemeColor.textSecondary
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        statusLabel.text = AIText.value("Loading the photo…", "正在加载照片…")
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        coverView.addSubview(statusLabel)
        NSLayoutConstraint.activate([
            statusLabel.centerYAnchor.constraint(equalTo: coverView.centerYAnchor),
            statusLabel.leadingAnchor.constraint(equalTo: coverView.leadingAnchor, constant: ThemeMetrics.contentInsetCompact),
            statusLabel.trailingAnchor.constraint(equalTo: coverView.trailingAnchor, constant: -ThemeMetrics.contentInsetCompact),
        ])
        coverView.isUserInteractionEnabled = true
        coverView.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(handleRetryTap)))

        let nameLabel = UILabel()
        nameLabel.text = frameName
        nameLabel.font = ThemeFont.heading()
        nameLabel.adjustsFontForContentSizeCategory = true
        nameLabel.textColor = ThemeColor.textPrimary
        nameLabel.numberOfLines = 0

        let countLabel = UILabel()
        countLabel.text = relPath?.hasPrefix("remote:") == true
            ? AIText.value(photoCount == 1 ? "1 photo" : "\(photoCount) photos", "\(photoCount) 张照片")
            : String(format: L10n.string("frames.playable_count"), photoCount)
        countLabel.font = ThemeFont.meta()
        countLabel.adjustsFontForContentSizeCategory = true
        countLabel.textColor = ThemeColor.textSecondary
        countLabel.numberOfLines = 0

        if let playbackSummary { countLabel.text = AIText.value("\(photoCount) photos", "\(photoCount) 张照片") + " · " + playbackSummary }

        let confirmButton = ActionButton(titleKey: playbackSummary == nil ? "frames.set_cover" : "common.edit", style: .primary)
        confirmButton.addTarget(self, action: #selector(handleConfirm), for: .touchUpInside)
        let cancelButton = ActionButton(titleKey: playbackSummary == nil ? "common.cancel" : "common.close", style: .secondary)
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
        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scroll)
        let body = UIView()
        body.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(body)
        body.addSubview(stack)

        let readable = stack.widthAnchor.constraint(equalToConstant: 420)
        readable.priority = .defaultHigh
        let centered = stack.centerYAnchor.constraint(equalTo: body.centerYAnchor)
        centered.priority = .defaultHigh
        let viewportHeight = body.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor)
        viewportHeight.priority = .defaultLow
        let safeArea = view.safeAreaLayoutGuide
        let contentGuide = scroll.contentLayoutGuide
        NSLayoutConstraint.activate([
            scroll.topAnchor.constraint(equalTo: safeArea.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: safeArea.bottomAnchor),
            scroll.leadingAnchor.constraint(equalTo: safeArea.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: safeArea.trailingAnchor),
            body.topAnchor.constraint(equalTo: contentGuide.topAnchor),
            body.bottomAnchor.constraint(equalTo: contentGuide.bottomAnchor),
            body.leadingAnchor.constraint(equalTo: contentGuide.leadingAnchor),
            body.trailingAnchor.constraint(equalTo: contentGuide.trailingAnchor),
            body.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor),
            body.heightAnchor.constraint(greaterThanOrEqualTo: scroll.frameLayoutGuide.heightAnchor),
            viewportHeight,
            stack.centerXAnchor.constraint(equalTo: body.centerXAnchor),
            centered,
            stack.topAnchor.constraint(greaterThanOrEqualTo: body.topAnchor, constant: ThemeMetrics.space16),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: body.bottomAnchor, constant: -ThemeMetrics.space16),
            stack.leadingAnchor.constraint(greaterThanOrEqualTo: body.leadingAnchor, constant: ThemeMetrics.contentInsetCompact),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: body.trailingAnchor, constant: -ThemeMetrics.contentInsetCompact),
            readable,
        ])

        NotificationCenter.default.addObserver(self, selector: #selector(authorityChanged(_:)), name: .remoteMediaChanged, object: nil)
        statusLabel.isHidden = coverView.image != nil
        if coverView.image == nil { loadCover(into: coverView) }
    }

    private func loadCover(into coverView: UIImageView) {
        guard let relPath else {
            statusLabel.text = AIText.value("No cover photo", "暂无封面照片")
            return
        }
        let scale = traitCollection.displayScale
        statusLabel.isHidden = false
        statusLabel.text = AIText.value("Loading the photo…", "正在加载照片…")
        loadTask?.cancel()
        loadTask = Task { [weak self, loader, storageMode, playbackSummary, weak coverView] in
            var image = if playbackSummary != nil {
                await loader.coverThumbnail(relPath: relPath, pixels: min(1600, Int(ceil(420 * scale / 128)) * 128), mode: storageMode)
            } else {
                await loader.thumbnail(relPath: relPath, pointSize: 420, scale: scale)
            }
            if image == nil, !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 300_000_000)
                image = await loader.thumbnail(relPath: relPath, pointSize: 260, scale: scale, mode: storageMode)
            }
            guard !Task.isCancelled, let self, let coverView else { return }
            coverView.image = image
            statusLabel.isHidden = image != nil
            if image == nil {
                statusLabel.text = AIText.value("Couldn’t load the photo. Tap to retry.", "照片加载失败，点按重试。")
            }
        }
    }

    @objc private func handleRetryTap() {
        guard !revoked, coverView.image == nil else { return }
        loadCover(into: coverView)
    }

    @objc private func authorityChanged(_ notification: Notification) {
        guard notification.userInfo?["revoked"] as? Bool == true else { return }
        revoked = true
        loadTask?.cancel()
        coverView.image = nil
    }

    @objc private func handleConfirm() {
        guard !revoked || playbackSummary != nil else { dismiss(animated: true); return }
        if playbackSummary == nil {
            onConfirm()
            dismiss(animated: true)
        } else {
            dismiss(animated: true, completion: onConfirm)
        }
    }

    @objc private func handleCancel() {
        dismiss(animated: true)
    }

    deinit {
        loadTask?.cancel()
    }
}
