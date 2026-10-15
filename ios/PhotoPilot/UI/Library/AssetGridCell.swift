import UIKit

final class AssetGridCell: UICollectionViewCell {
    static let reuseID = "AssetGridCell"
    private static let coverStarTint = UIColor(red: 1.0, green: 0.84, blue: 0.04, alpha: 1)

    let imageView = UIImageView()
    private let statusLabel = UILabel()
    private let badgeView = UIImageView()
    private let starBackdrop = UIView()
    private let starButton = UIButton(type: .custom)
    private(set) var assetID: String?
    private(set) var cellSelected = false
    private var starVisible = false
    var onStarTap: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.clipsToBounds = true
        contentView.layer.cornerRadius = ThemeMetrics.radiusThumbnail
        contentView.backgroundColor = ThemeColor.panel

        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(imageView)

        statusLabel.text = L10n.string("library.status_failed")
        statusLabel.font = ThemeFont.meta()
        statusLabel.adjustsFontForContentSizeCategory = true
        statusLabel.textColor = ThemeColor.statusError
        statusLabel.textAlignment = .center
        statusLabel.isHidden = true
        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(statusLabel)

        badgeView.image = UIImage(systemName: "checkmark.circle.fill")
        badgeView.tintColor = ThemeColor.accentAction
        badgeView.backgroundColor = ThemeColor.panel
        badgeView.layer.cornerRadius = ThemeMetrics.radiusThumbnail
        badgeView.clipsToBounds = true
        badgeView.isHidden = true
        badgeView.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(badgeView)

        starBackdrop.backgroundColor = ThemeColor.photoControl.withAlphaComponent(0.88)
        starBackdrop.layer.cornerRadius = ThemeMetrics.space16
        starBackdrop.isUserInteractionEnabled = false
        starBackdrop.isHidden = true
        starBackdrop.translatesAutoresizingMaskIntoConstraints = false

        starButton.tintColor = .white
        starButton.isHidden = true
        starButton.accessibilityLabel = L10n.string("frames.set_cover")
        starButton.translatesAutoresizingMaskIntoConstraints = false
        starButton.addTarget(self, action: #selector(handleStarTap), for: .touchUpInside)
        contentView.addSubview(starBackdrop)
        contentView.addSubview(starButton)

        NSLayoutConstraint.activate([
            imageView.topAnchor.constraint(equalTo: contentView.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
            statusLabel.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            statusLabel.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            badgeView.topAnchor.constraint(equalTo: contentView.topAnchor, constant: ThemeMetrics.space4),
            badgeView.trailingAnchor.constraint(
                equalTo: contentView.trailingAnchor,
                constant: -ThemeMetrics.space4
            ),
            starButton.topAnchor.constraint(equalTo: contentView.topAnchor, constant: ThemeMetrics.space4),
            starButton.trailingAnchor.constraint(
                equalTo: contentView.trailingAnchor,
                constant: -ThemeMetrics.space4
            ),
            starButton.widthAnchor.constraint(equalToConstant: ThemeMetrics.touchTarget),
            starButton.heightAnchor.constraint(equalToConstant: ThemeMetrics.touchTarget),
            starBackdrop.centerXAnchor.constraint(equalTo: starButton.centerXAnchor),
            starBackdrop.centerYAnchor.constraint(equalTo: starButton.centerYAnchor),
            starBackdrop.widthAnchor.constraint(equalToConstant: ThemeMetrics.space32),
            starBackdrop.heightAnchor.constraint(equalToConstant: ThemeMetrics.space32),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        assetID = nil
        imageView.image = nil
        statusLabel.text = L10n.string("library.status_failed")
        statusLabel.textColor = ThemeColor.statusError
        statusLabel.isHidden = true
        onStarTap = nil
        setStarVisible(false, isCover: false)
        setCellSelected(false, announced: false)
    }

    func configure(assetID: String, accessibilityText: String) {
        self.assetID = assetID
        isAccessibilityElement = true
        accessibilityLabel = accessibilityText
        accessibilityTraits = [.image]
    }

    func setCellSelected(_ selected: Bool, announced: Bool) {
        cellSelected = selected
        contentView.layer.borderWidth = selected ? NavChromeMetrics.selectionIndicator : 0
        contentView.layer.borderColor = ThemeColor.accentAction.cgColor
        badgeView.isHidden = !selected || starVisible
        if selected {
            accessibilityTraits.insert(.selected)
        } else {
            accessibilityTraits.remove(.selected)
        }
        if announced {
            accessibilityValue = L10n.string(selected ? "a11y.photo_selected" : "a11y.photo_not_selected")
        }
    }

    func setStarVisible(_ visible: Bool, isCover: Bool) {
        starVisible = visible
        starButton.isHidden = !visible
        starBackdrop.isHidden = !visible
        badgeView.isHidden = !cellSelected || visible
        let symbol = isCover ? "star.fill" : "star"
        let color = isCover ? Self.coverStarTint : UIColor.white
        let configuration = UIImage.SymbolConfiguration(pointSize: 20, weight: .bold)
            .applying(UIImage.SymbolConfiguration(paletteColors: [color]))
        starButton.setImage(UIImage(systemName: symbol, withConfiguration: configuration), for: .normal)
    }

    @objc private func handleStarTap() {
        onStarTap?()
    }

    func showImage(_ image: UIImage) {
        imageView.image = image
        statusLabel.isHidden = true
    }

    func showFailure() {
        imageView.image = nil
        statusLabel.isHidden = false
    }

    func showPending() {
        imageView.image = nil
        statusLabel.text = L10n.string("library.pending_badge")
        statusLabel.textColor = ThemeColor.textSecondary
        statusLabel.isHidden = false
    }
}
