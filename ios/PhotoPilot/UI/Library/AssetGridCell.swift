import UIKit

final class AssetGridCell: UICollectionViewCell {
    static let reuseID = "AssetGridCell"

    let imageView = UIImageView()
    private let statusLabel = UILabel()
    private let badgeView = UIImageView()
    private(set) var assetID: String?
    private(set) var cellSelected = false

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
        badgeView.isHidden = !selected
        if selected {
            accessibilityTraits.insert(.selected)
        } else {
            accessibilityTraits.remove(.selected)
        }
        if announced {
            accessibilityValue = L10n.string(selected ? "a11y.photo_selected" : "a11y.photo_not_selected")
        }
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
