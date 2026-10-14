import UIKit
import UniformTypeIdentifiers

final class MusicPickerViewController: UIViewController, UIDocumentPickerDelegate {
    private var selection: MusicSelection
    private let onSelect: (MusicSelection) -> Void
    private let previewPlayer = MusicPlayer()
    private var previewingKey: String?

    private let offButton = ActionButton(titleKey: "playback.music_off", style: .secondary)
    private var trackButtons: [ActionButton] = []
    private let localButton = ActionButton(titleKey: "playback.music_local_section", style: .secondary)
    private let chooseLocalButton = ActionButton(
        titleKey: "playback.music_choose_local",
        style: .secondary
    )

    init(selection: MusicSelection, onSelect: @escaping (MusicSelection) -> Void) {
        self.selection = selection
        self.onSelect = onSelect
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        enableEdgeSwipeBack { [weak self] in self?.handleBack() }
        view.backgroundColor = ThemeColor.canvas

        let backButton = ActionButton(titleKey: "common.back", style: .secondary)
        backButton.addTarget(self, action: #selector(handleBack), for: .touchUpInside)
        let titleLabel = UILabel()
        titleLabel.text = L10n.string("playback.music_picker_title")
        titleLabel.font = ThemeFont.heading()
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = ThemeColor.textPrimary
        titleLabel.accessibilityTraits.insert(.header)
        let topBar = UIStackView(arrangedSubviews: [backButton, titleLabel, UIView()])
        topBar.axis = .horizontal
        topBar.alignment = .center
        topBar.spacing = ThemeMetrics.space12
        topBar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(topBar)

        let scrollView = UIScrollView()
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        let formStack = UIStackView()
        formStack.axis = .vertical
        formStack.spacing = ThemeMetrics.space12
        formStack.translatesAutoresizingMaskIntoConstraints = false
        scrollView.addSubview(formStack)
        view.addSubview(scrollView)

        let readable = formStack.widthAnchor.constraint(equalToConstant: ThemeMetrics.bodyMaxWidth)
        readable.priority = .defaultHigh
        NSLayoutConstraint.activate([
            topBar.topAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.topAnchor,
                constant: ThemeMetrics.space8
            ),
            topBar.leadingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInsetCompact
            ),
            topBar.trailingAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInsetCompact
            ),
            scrollView.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: ThemeMetrics.space8),
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
            formStack.topAnchor.constraint(
                equalTo: scrollView.contentLayoutGuide.topAnchor,
                constant: ThemeMetrics.space8
            ),
            formStack.bottomAnchor.constraint(
                equalTo: scrollView.contentLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space24
            ),
            formStack.centerXAnchor.constraint(equalTo: scrollView.frameLayoutGuide.centerXAnchor),
            formStack.leadingAnchor.constraint(
                greaterThanOrEqualTo: scrollView.frameLayoutGuide.leadingAnchor,
                constant: ThemeMetrics.contentInset
            ),
            formStack.trailingAnchor.constraint(
                lessThanOrEqualTo: scrollView.frameLayoutGuide.trailingAnchor,
                constant: -ThemeMetrics.contentInset
            ),
            readable,
        ])

        offButton.addTarget(self, action: #selector(handleOff), for: .touchUpInside)
        formStack.addArrangedSubview(offButton)

        formStack.addArrangedSubview(makeSectionLabel(key: "playback.music_builtin_section"))
        for (index, track) in MusicCatalog.tracks.enumerated() {
            let button = ActionButton(titleKey: track.titleKey, style: .secondary)
            button.tag = index
            button.addTarget(self, action: #selector(handleTrack(_:)), for: .touchUpInside)
            trackButtons.append(button)
            formStack.addArrangedSubview(button)
        }

        formStack.addArrangedSubview(makeSectionLabel(key: "playback.music_local_section"))
        localButton.addTarget(self, action: #selector(handleLocal), for: .touchUpInside)
        formStack.addArrangedSubview(localButton)
        chooseLocalButton.addTarget(self, action: #selector(handleChooseLocal), for: .touchUpInside)
        formStack.addArrangedSubview(chooseLocalButton)

        refreshLocalRow()
        refreshSelection()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stopPreview()
    }

    private func makeSectionLabel(key: String) -> UILabel {
        let label = UILabel()
        label.text = L10n.string(key)
        label.font = ThemeFont.body()
        label.adjustsFontForContentSizeCategory = true
        label.textColor = ThemeColor.textPrimary
        label.numberOfLines = 0
        label.accessibilityTraits.insert(.header)
        return label
    }

    private func refreshLocalRow() {
        let hasLocal = selection.source == MusicCatalog.sourceLocal && selection.localPath != nil
        localButton.isHidden = !hasLocal
        if hasLocal {
            localButton.setTitle(MusicCatalog.displayName(for: selection))
        }
    }

    private func refreshSelection() {
        offButton.isOptionSelected = !selection.enabled
        for (index, button) in trackButtons.enumerated() {
            button.isOptionSelected = selection.enabled
                && selection.source == MusicCatalog.sourceBuiltin
                && selection.trackId == MusicCatalog.tracks[index].id
        }
        localButton.isOptionSelected = selection.enabled
            && selection.source == MusicCatalog.sourceLocal
    }

    private func playPreview(key: String, url: URL?) {
        if previewingKey == key, previewPlayer.isPlaying {
            stopPreview()
            return
        }
        guard let url else {
            stopPreview()
            return
        }
        previewPlayer.start(url: url)
        previewingKey = previewPlayer.isPlaying ? key : nil
    }

    private func stopPreview() {
        previewPlayer.stop()
        previewingKey = nil
    }

    @objc private func handleOff() {
        selection = MusicSelection()
        stopPreview()
        refreshLocalRow()
        refreshSelection()
        onSelect(selection)
    }

    @objc private func handleTrack(_ sender: ActionButton) {
        let track = MusicCatalog.tracks[sender.tag]
        let key = "builtin:\(track.id)"
        if selection.enabled,
           selection.source == MusicCatalog.sourceBuiltin,
           selection.trackId == track.id {
            playPreview(key: key, url: MusicCatalog.builtinURL(trackId: track.id))
            return
        }
        selection = MusicSelection(
            enabled: true,
            source: MusicCatalog.sourceBuiltin,
            trackId: track.id,
            localPath: nil,
            localTitle: nil
        )
        refreshLocalRow()
        refreshSelection()
        onSelect(selection)
        playPreview(key: key, url: MusicCatalog.builtinURL(trackId: track.id))
    }

    @objc private func handleLocal() {
        guard let localPath = selection.localPath else { return }
        playPreview(key: "local", url: MusicCatalog.localURL(localPath: localPath))
    }

    @objc private func handleChooseLocal() {
        let picker = UIDocumentPickerViewController(
            forOpeningContentTypes: [UTType.audio],
            asCopy: true
        )
        picker.delegate = self
        present(picker, animated: true)
    }

    func documentPicker(
        _ controller: UIDocumentPickerViewController,
        didPickDocumentsAt urls: [URL]
    ) {
        guard let url = urls.first else { return }
        do {
            let directory = MusicCatalog.musicDirectory()
            let pathExtension = url.pathExtension
            let filename = UUID().uuidString + (pathExtension.isEmpty ? "" : ".\(pathExtension)")
            let destination = directory.appendingPathComponent(filename)
            try FileManager.default.copyItem(at: url, to: destination)
            if selection.source == MusicCatalog.sourceLocal,
               let previous = selection.localPath,
               previous != filename {
                try? FileManager.default.removeItem(at: directory.appendingPathComponent(previous))
            }
            selection = MusicSelection(
                enabled: true,
                source: MusicCatalog.sourceLocal,
                trackId: nil,
                localPath: filename,
                localTitle: url.deletingPathExtension().lastPathComponent
            )
            refreshLocalRow()
            refreshSelection()
            onSelect(selection)
            playPreview(key: "local", url: destination)
        } catch {
            let alert = UIAlertController(
                title: L10n.string("playback.music_local_error"),
                message: nil,
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(
                title: L10n.string("common.done"),
                style: .default
            ))
            present(alert, animated: true)
        }
    }

    @objc private func handleBack() {
        stopPreview()
        dismiss(animated: true)
    }
}
