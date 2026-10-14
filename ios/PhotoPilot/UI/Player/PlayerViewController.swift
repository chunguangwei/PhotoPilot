import AVFoundation
import UIKit

struct PlayerSettings: Sendable {
    var holdSeconds = 10
    var transition = PlaybackTiming.transitionCrossfade
    var transitionSeconds = 0.8
    var fitMode = "fit_blur"
    var clockEnabled = true
    var clockPosition = "bottom_leading"
    var dateEnabled = true
    var hour24: Bool?
    var keepAwakeWhenPaused = true
    var qualityLevel = "auto"
    var solidColor: String?
    var restEnabled = false
    var restStartMinute = 0
    var restEndMinute = 0
    var brightness: Double?

    static func parse(json: String) -> PlayerSettings {
        var settings = PlayerSettings()
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return settings
        }
        if let hold = object["holdSeconds"] as? Double, PlaybackTiming.isValidHoldSeconds(hold) {
            settings.holdSeconds = Int(hold.rounded())
        }
        if let transition = object["transition"] as? String,
           PlaybackTiming.transitionTypes.contains(transition) {
            settings.transition = transition
        }
        if let seconds = object["transitionSeconds"] as? Double,
           PlaybackTiming.isValidTransitionSeconds(seconds) {
            settings.transitionSeconds = seconds
        }
        if let fitMode = object["fitMode"] as? String,
           ["fit_blur", "fit_color", "fill_crop"].contains(fitMode) {
            settings.fitMode = fitMode
        }
        if let clock = object["clock"] as? [String: Any] {
            settings.clockEnabled = clock["enabled"] as? Bool ?? true
            if let position = clock["position"] as? String,
               ["top_leading", "top_trailing", "bottom_leading", "bottom_trailing"].contains(position) {
                settings.clockPosition = position
            }
            settings.hour24 = clock["hour24"] as? Bool
        }
        if let date = object["date"] as? [String: Any] {
            settings.dateEnabled = date["enabled"] as? Bool ?? true
        }
        settings.keepAwakeWhenPaused = object["keepAwakeWhenPaused"] as? Bool ?? true
        if let quality = object["qualityLevel"] as? String,
           ["auto", "balanced", "low"].contains(quality) {
            settings.qualityLevel = quality
        }
        if let rest = object["rest"] as? [String: Any] {
            settings.restEnabled = rest["enabled"] as? Bool ?? false
            settings.restStartMinute = rest["startMinute"] as? Int ?? 0
            settings.restEndMinute = rest["endMinute"] as? Int ?? 0
        }
        settings.brightness = object["brightness"] as? Double
        if let solid = object["solidColor"] as? String,
           ["black", "warm_white"].contains(solid) {
            settings.solidColor = solid
        }
        return settings
    }
}

final class PlayerImageLoader: @unchecked Sendable {
    static let maxDecodePixels: Int64 = 4096

    private nonisolated(unsafe) static let sharedCache = NSCache<NSString, UIImage>()

    private let originalsRoot: URL

    init(originalsRoot: URL) {
        self.originalsRoot = originalsRoot
    }

    static func clearMemoryCache() {
        sharedCache.removeAllObjects()
    }

    func image(
        assetID: String,
        relPath: String,
        pointSize: CGSize,
        scale: CGFloat,
        quality: String = "auto"
    ) async -> UIImage? {
        let key = "\(assetID)#\(quality)" as NSString
        if let cached = Self.sharedCache.object(forKey: key) {
            return cached
        }
        let factor: Double
        switch quality {
        case "balanced":
            factor = 0.75
        case "low":
            factor = 0.5
        default:
            factor = 1
        }
        let root = originalsRoot
        let image: UIImage? = await Task.detached {
            let url = root.appendingPathComponent(relPath)
            guard FileManager.default.fileExists(atPath: url.path) else { return nil }
            let target = RenderGeometry.decodeTarget(
                viewW: Int64((Double(max(pointSize.width, 1)) * factor).rounded(.down)),
                viewH: Int64((Double(max(pointSize.height, 1)) * factor).rounded(.down)),
                scale: Int64(max(scale, 1)),
                cap: Self.maxDecodePixels
            )
            if ThumbnailLoader.isVideoPath(relPath) {
                guard let cgImage = ThumbnailLoader.videoThumbnail(
                    url: url,
                    maxPixels: Int(max(target.0, target.1))
                ) else { return nil }
                return UIImage(cgImage: cgImage)
            }
            guard let cgImage = ImageRenderer.thumbnail(
                fileURL: url,
                maxPixelWidth: Int(target.0),
                maxPixelHeight: Int(target.1)
            ) else { return nil }
            return UIImage(cgImage: cgImage)
        }.value
        if let image {
            Self.sharedCache.setObject(image, forKey: key)
        }
        return image
    }

    func blurred(assetID: String, image: UIImage) async -> UIImage? {
        let key = "\(assetID)#blur" as NSString
        if let cached = Self.sharedCache.object(forKey: key) {
            return cached
        }
        let blurred: UIImage? = await Task.detached {
            guard let cgImage = image.cgImage,
                  let output = ImageRenderer.blurredBackground(of: cgImage) else { return nil }
            return UIImage(cgImage: output)
        }.value
        if let blurred {
            Self.sharedCache.setObject(blurred, forKey: key)
        }
        return blurred
    }

    func edgeColorHex(of image: UIImage) async -> String? {
        await Task.detached {
            guard let cgImage = image.cgImage else { return nil }
            return ImageRenderer.edgeColor(of: cgImage)
        }.value
    }

    func clear() {
        Self.sharedCache.removeAllObjects()
    }
}

final class PlayerViewController: UIViewController {
    static let blurDimOpacity: CGFloat = 0.4
    private static let controlsHideDelayNanos: UInt64 = 4_000_000_000
    private static weak var activePlayer: PlayerViewController?

    static func dismissActiveIfAny() {
        activePlayer?.exitPlayer()
    }

    private let environment: AppEnvironment
    private var frameID: String
    private let loader: PlayerImageLoader

    private var settings = PlayerSettings()
    private var randomSeed = 0
    private var selectionVersion: Int64 = 0
    private var queue: [String] = []
    private var relPaths: [String: String] = [:]
    private var index = 0
    private var controllerState = PlaybackController.idle
    private var decodeFailStreak = 0
    private var skippedAny = false
    private var pendingSnapshot: PlaybackSnapshotDTO?
    private var pendingRebuild = false
    private var skipRestOnce = false
    private var preHolidayFrameID: String?
    private var activeHolidayRuleID: String?
    private var holidayHandledRuleID: String?
    private var holidayNoticeText: String?
    private var holidayReturnInFlight = false
    private var safeImageShown = false
    private var remainingHoldMs: Int64?
    private var nextTransitionAtMs: Int64 = 0
    private var originalBrightness: CGFloat?

    private var holdTask: Task<Void, Never>?
    private var workTask: Task<Void, Never>?
    private var controlsHideTask: Task<Void, Never>?
    private var clockTask: Task<Void, Never>?
    private var restTask: Task<Void, Never>?
    private var holidayTask: Task<Void, Never>?
    private var holidayNoticeTask: Task<Void, Never>?

    private let backgroundView = UIImageView()
    private let dimView = UIView()
    private let solidView = UIView()
    private let frontImageView = UIImageView()
    private let backImageView = UIImageView()
    private let controlsCapsule = UIView()
    private let previousButton = UIButton(type: .system)
    private let pauseButton = UIButton(type: .system)
    private let nextButton = UIButton(type: .system)
    private let moreButton = UIButton(type: .system)
    private let exitButton = UIButton(type: .system)
    private let clockLabel = UILabel()
    private let dateLabel = UILabel()
    private let noticeLabel = UILabel()
    private let singleNoticeLabel = UILabel()
    private let safeOverlay = UIStackView()
    private let emptyOverlay = UIStackView()
    private let restingScreen = UIView()
    private let restClockLabel = UILabel()
    private var videoPlayer: AVPlayer?
    private var videoLayer: AVPlayerLayer?
    private var videoEndObserver: NSObjectProtocol?
    private var videoTruncateTask: Task<Void, Never>?
    private let weatherLabel = UILabel()
    private let weatherNoteLabel = UILabel()
    private var controlsPresented = false
    private var weatherTask: Task<Void, Never>?
    private let clockStack = UIStackView()
    private var clockConstraintsByPosition: [String: [NSLayoutConstraint]] = [:]

    init(environment: AppEnvironment, frameID: String) {
        self.environment = environment
        self.frameID = frameID
        self.loader = PlayerImageLoader(originalsRoot: environment.originalsRootURL)
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ThemeColor.canvas

        if let existing = Self.activePlayer, existing !== self {
            existing.takeover()
        }
        Self.activePlayer = self

        backgroundView.contentMode = .scaleAspectFill
        backgroundView.clipsToBounds = true
        backgroundView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(backgroundView)

        dimView.backgroundColor = ThemeColor.restSurface.withAlphaComponent(Self.blurDimOpacity)
        dimView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(dimView)

        solidView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(solidView)

        backImageView.contentMode = .scaleAspectFit
        backImageView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(backImageView)
        frontImageView.contentMode = .scaleAspectFit
        frontImageView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(frontImageView)

        clockLabel.font = ThemeFont.clock()
        clockLabel.textColor = ThemeColor.textOnPhoto
        clockLabel.adjustsFontForContentSizeCategory = true
        dateLabel.font = ThemeFont.meta()
        dateLabel.textColor = ThemeColor.textOnPhoto
        dateLabel.adjustsFontForContentSizeCategory = true
        dateLabel.numberOfLines = 0
        weatherLabel.font = ThemeFont.caption()
        weatherLabel.textColor = ThemeColor.textOnPhoto
        weatherLabel.adjustsFontForContentSizeCategory = true
        weatherLabel.isHidden = true
        weatherNoteLabel.font = ThemeFont.meta()
        weatherNoteLabel.textColor = ThemeColor.textOnPhoto
        weatherNoteLabel.adjustsFontForContentSizeCategory = true
        weatherNoteLabel.numberOfLines = 0
        weatherNoteLabel.isHidden = true
        clockStack.axis = .vertical
        clockStack.spacing = ThemeMetrics.space4
        clockStack.addArrangedSubview(clockLabel)
        clockStack.addArrangedSubview(dateLabel)
        clockStack.addArrangedSubview(weatherLabel)
        clockStack.addArrangedSubview(weatherNoteLabel)
        clockStack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(clockStack)

        noticeLabel.text = L10n.string("player.decode_failed_notice")
        noticeLabel.font = ThemeFont.meta()
        noticeLabel.adjustsFontForContentSizeCategory = true
        noticeLabel.textColor = ThemeColor.textOnPhoto
        noticeLabel.numberOfLines = 0
        noticeLabel.isHidden = true
        noticeLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(noticeLabel)

        singleNoticeLabel.text = L10n.string("player.single_photo_notice")
        singleNoticeLabel.font = ThemeFont.meta()
        singleNoticeLabel.adjustsFontForContentSizeCategory = true
        singleNoticeLabel.textColor = ThemeColor.textOnPhoto
        singleNoticeLabel.isHidden = true
        singleNoticeLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(singleNoticeLabel)

        buildControls()
        buildOverlays()

        NSLayoutConstraint.activate([
            backgroundView.topAnchor.constraint(equalTo: view.topAnchor),
            backgroundView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            backgroundView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            backgroundView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            dimView.topAnchor.constraint(equalTo: view.topAnchor),
            dimView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            dimView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            dimView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            solidView.topAnchor.constraint(equalTo: view.topAnchor),
            solidView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            solidView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            solidView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            backImageView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            backImageView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            backImageView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            backImageView.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            frontImageView.topAnchor.constraint(equalTo: backImageView.topAnchor),
            frontImageView.leadingAnchor.constraint(equalTo: backImageView.leadingAnchor),
            frontImageView.trailingAnchor.constraint(equalTo: backImageView.trailingAnchor),
            frontImageView.bottomAnchor.constraint(equalTo: backImageView.bottomAnchor),
            noticeLabel.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            noticeLabel.topAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.topAnchor,
                constant: ThemeMetrics.space16
            ),
            singleNoticeLabel.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            singleNoticeLabel.topAnchor.constraint(
                equalTo: noticeLabel.bottomAnchor,
                constant: ThemeMetrics.space8
            ),
        ])

        let tap = UITapGestureRecognizer(target: self, action: #selector(handleTap))
        view.addGestureRecognizer(tap)

        buildClockConstraints()

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleBackground),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )

        startClock()
        startRestMonitor()
        startHolidayMonitor()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        applyBrightness()
        guard controllerState == PlaybackController.idle else {
            checkRevocation()
            dispatch(PlaybackEvent(type: "selection_changed"))
            return
        }
        workTask = Task { [weak self] in
            guard let self else { return }
            guard let bundle = try? await environment.frameManager.framePlaybackBundle(frameID: frameID) else {
                showEmptyOverlay()
                return
            }
            settings = PlayerSettings.parse(json: bundle.settingsJSON)
            randomSeed = bundle.randomSeed
            selectionVersion = bundle.selectionVersion
            pendingSnapshot = try? await environment.frameManager.playbackSnapshot(frameID: frameID)
            updateClockVisibility()
            startWeatherTask()
            if restActiveNow(), !skipRestOnce {
                controllerState = PlaybackController.resting
                updateStateChrome()
                return
            }
            dispatch(PlaybackEvent(type: "start"))
        }
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        restoreBrightness()
        UIApplication.shared.isIdleTimerDisabled = false
        if Self.activePlayer === self {
            Self.activePlayer = nil
        }
    }

    private func takeover() {
        _ = PlaybackController.reduce(
            state: controllerState,
            event: PlaybackEvent(type: "takeover_by_other_window")
        )
        clearTextures()
        dismiss(animated: false)
    }

    private func dispatch(_ event: PlaybackEvent) {
        let outcome = PlaybackController.reduce(state: controllerState, event: event)
        let enteredTransition = outcome.state == PlaybackController.transitioning
            && controllerState != PlaybackController.transitioning
        controllerState = outcome.state
        for effect in outcome.effects {
            perform(effect: effect)
        }
        if enteredTransition {
            advance(by: event.type == "user_prev" ? queue.count - 1 : 1)
        }
        updateStateChrome()
    }

    private func perform(effect: String) {
        switch effect {
        case PlaybackController.effectClearTextures:
            clearTextures()
        case PlaybackController.effectShowSafeImage:
            showSafeImage()
        case PlaybackController.effectRebuildQueue:
            rebuildQueue(keepIndex: false)
        case PlaybackController.effectRebuildAtSafePoint:
            if controllerState == PlaybackController.transitioning {
                pendingRebuild = true
            } else {
                rebuildQueue(keepIndex: true)
            }
        case PlaybackController.effectApplyPendingRebuild:
            if pendingRebuild {
                pendingRebuild = false
                rebuildQueue(keepIndex: true)
            }
        case PlaybackController.effectPersistSnapshot:
            persistSnapshot()
        case PlaybackController.effectSaveRemainingHold:
            saveRemainingHold()
        case PlaybackController.effectCompleteTransition:
            completeTransition()
        case PlaybackController.effectResetHoldTimer:
            if !currentIsVideo() {
                scheduleHoldTimer()
            }
        case PlaybackController.effectResumeUntilRestEnd:
            resumeFromRest()
        case PlaybackController.effectSkipToNext:
            advance(by: 1)
        default:
            break
        }
    }

    private func rebuildQueue(keepIndex: Bool) {
        workTask?.cancel()
        workTask = Task { [weak self] in
            guard let self else { return }
            if !keepIndex, let snapshot = pendingSnapshot,
               snapshot.selectionVersion == Int(selectionVersion),
               snapshot.seed == randomSeed,
               let cursor = Self.parseCursor(snapshot.cursorJSON),
               !cursor.queue.isEmpty {
                pendingSnapshot = nil
                queue = cursor.queue
                index = min(cursor.index, queue.count - 1)
                remainingHoldMs = Int64(snapshot.remainingHoldMs)
                safeImageShown = false
                await refreshRelPaths()
                loadCurrent()
                return
            }
            pendingSnapshot = nil
            let newQueue = (try? await environment.frames.buildPlayQueue(frameID: frameID)) ?? []
            guard !Task.isCancelled else { return }
            if newQueue.isEmpty {
                queue = []
                dispatch(PlaybackEvent(type: "none_playable"))
                return
            }
            let currentID = keepIndex && index < queue.count ? queue[index] : nil
            queue = newQueue
            if let currentID, let found = newQueue.firstIndex(of: currentID) {
                index = found
            } else {
                index = min(index, newQueue.count - 1)
            }
            safeImageShown = false
            await refreshRelPaths()
            loadCurrent()
        }
    }

    private func refreshRelPaths() async {
        let items = (try? await environment.assets.libraryItems()) ?? []
        relPaths = Dictionary(uniqueKeysWithValues: items.map { ($0.id, $0.originalRelPath) })
    }

    private func loadCurrent() {
        guard index < queue.count else { return }
        let assetID = queue[index]
        if let relPath = relPaths[assetID], ThumbnailLoader.isVideoPath(relPath) {
            setupVideo(assetID: assetID, animated: false, firstEntry: true)
            return
        }
        Task { [weak self] in
            guard let self else { return }
            guard let image = await loadImage(assetID: assetID) else {
                handleDecodeFail()
                return
            }
            decodeFailStreak = 0
            show(image: image, assetID: assetID, animated: false)
            prefetchNext()
            dispatch(PlaybackEvent(type: "first_ready"))
            markPlayedOnce()
        }
    }

    private func loadImage(assetID: String) async -> UIImage? {
        guard let relPath = relPaths[assetID] else {
            let asset = try? await environment.assets.fetchAsset(id: assetID)
            guard let relPath = asset?.originalRelPath else { return nil }
            relPaths[assetID] = relPath
            return await loader.image(
                assetID: assetID,
                relPath: relPath,
                pointSize: view.bounds.size,
                scale: view.traitCollection.displayScale,
                quality: settings.qualityLevel
            )
        }
        return await loader.image(
            assetID: assetID,
            relPath: relPath,
            pointSize: view.bounds.size,
            scale: view.traitCollection.displayScale,
            quality: settings.qualityLevel
        )
    }

    private func prefetchNext() {
        guard queue.count > 1 else { return }
        let nextID = queue[(index + 1) % queue.count]
        Task { [weak self] in
            _ = await self?.loadImage(assetID: nextID)
        }
    }

    private func isNextReady() -> Bool {
        guard queue.count > 1 else { return false }
        let nextID = queue[(index + 1) % queue.count]
        return relPaths[nextID] != nil
    }

    private func handleDecodeFail() {
        skippedAny = true
        decodeFailStreak += 1
        let limit = PlaybackController.decodeFailLimit(queueLength: max(queue.count, 1))
        dispatch(PlaybackEvent(type: "decode_fail", limitReached: decodeFailStreak >= limit))
    }

    private func setupVideo(assetID: String, animated: Bool, firstEntry: Bool) {
        guard let relPath = relPaths[assetID] else {
            handleDecodeFail()
            return
        }
        let url = environment.originalsRootURL.appendingPathComponent(relPath)
        guard FileManager.default.fileExists(atPath: url.path) else {
            handleDecodeFail()
            return
        }
        let item = AVPlayerItem(url: url)
        item.asset.loadValuesAsynchronously(forKeys: ["playable"]) { [weak self] in
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                guard item.asset.statusOfValue(forKey: "playable", error: nil) == .loaded,
                      item.asset.isPlayable else {
                    self.handleDecodeFail()
                    return
                }
                self.startVideoPlayback(item: item, relPath: relPath, animated: animated, firstEntry: firstEntry)
            }
        }
    }

    private func startVideoPlayback(
        item: AVPlayerItem,
        relPath: String,
        animated: Bool,
        firstEntry: Bool
    ) {
        stopVideo()
        let player = AVPlayer(playerItem: item)
        player.isMuted = true
        let layer = AVPlayerLayer(player: player)
        layer.videoGravity = settings.fitMode == "fill_crop" ? .resizeAspectFill : .resizeAspect
        layer.frame = view.bounds
        if let controls = view.subviews.last?.layer {
            view.layer.insertSublayer(layer, below: controls)
        } else {
            view.layer.addSublayer(layer)
        }
        videoPlayer = player
        videoLayer = layer

        if let cgImage = ThumbnailLoader.videoThumbnail(
            url: environment.originalsRootURL.appendingPathComponent(relPath),
            maxPixels: 256
        ) {
            updateBackdrop(for: UIImage(cgImage: cgImage), assetID: relPath)
        }

        let reduced = UIAccessibility.isReduceMotionEnabled
        if animated, !reduced, settings.transition != PlaybackTiming.transitionNone {
            layer.opacity = 0
            let fade = CABasicAnimation(keyPath: "opacity")
            fade.fromValue = 0
            fade.toValue = 1
            fade.duration = settings.transitionSeconds
            fade.isRemovedOnCompletion = false
            fade.fillMode = .forwards
            layer.add(fade, forKey: "videoFade")
        }

        videoEndObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            self?.handleVideoEnded()
        }
        let seconds = CMTimeGetSeconds(item.asset.duration)
        if seconds * 1000 > Double(AssetFileProber.videoTruncationMillis) {
            videoTruncateTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(AssetFileProber.videoTruncationMillis) * 1_000_000)
                guard !Task.isCancelled else { return }
                self?.handleVideoEnded()
            }
        }
        player.play()
        decodeFailStreak = 0
        prefetchNext()
        if firstEntry {
            dispatch(PlaybackEvent(type: "first_ready"))
            markPlayedOnce()
        } else {
            dispatch(PlaybackEvent(type: "transition_finished"))
        }
    }

    private func handleVideoEnded() {
        guard videoPlayer != nil else { return }
        dispatch(PlaybackEvent(type: "hold_elapsed", nextReady: isNextReady()))
    }

    private func stopVideo() {
        if let videoEndObserver {
            NotificationCenter.default.removeObserver(videoEndObserver)
        }
        videoEndObserver = nil
        videoTruncateTask?.cancel()
        videoTruncateTask = nil
        videoPlayer?.pause()
        videoLayer?.removeFromSuperlayer()
        videoPlayer = nil
        videoLayer = nil
    }

    private func advance(by offset: Int) {
        guard !queue.isEmpty else { return }
        stopVideo()
        let next = (index + offset + queue.count) % queue.count
        transitionTo(index: next, direction: offset == 1 ? 1 : -1)
    }

    private func transitionTo(index target: Int, direction: Int = 1) {
        guard target < queue.count else { return }
        let assetID = queue[target]
        if let relPath = relPaths[assetID], ThumbnailLoader.isVideoPath(relPath) {
            index = target
            setupVideo(assetID: assetID, animated: true, firstEntry: false)
            return
        }
        Task { [weak self] in
            guard let self else { return }
            guard let image = await loadImage(assetID: assetID) else {
                handleDecodeFail()
                return
            }
            decodeFailStreak = 0
            index = target
            show(image: image, assetID: assetID, animated: true, direction: direction)
            prefetchNext()
        }
    }

    private func show(image: UIImage, assetID: String, animated: Bool, direction: Int = 1) {
        updateBackdrop(for: image, assetID: assetID)
        let reduced = UIAccessibility.isReduceMotionEnabled
        let duration = animated && !reduced && settings.transition != PlaybackTiming.transitionNone
            ? settings.transitionSeconds
            : 0
        guard duration > 0 else {
            frontImageView.image = image
            frontImageView.alpha = 1
            frontImageView.transform = .identity
            backImageView.image = nil
            backImageView.alpha = 0
            backImageView.transform = .identity
            dispatch(PlaybackEvent(type: "transition_finished"))
            return
        }
        let incoming = backImageView
        let outgoing = frontImageView
        incoming.image = image
        incoming.alpha = 0
        incoming.transform = .identity
        let edge = view.bounds.width * CGFloat(direction >= 0 ? 1 : -1)
        switch settings.transition {
        case PlaybackTiming.transitionSlide, PlaybackTiming.transitionWipe:
            incoming.alpha = 1
            incoming.transform = CGAffineTransform(translationX: edge, y: 0)
        case PlaybackTiming.transitionZoom:
            incoming.transform = CGAffineTransform(scaleX: 1.08, y: 1.08)
        case PlaybackTiming.transitionRipple:
            incoming.transform = CGAffineTransform(scaleX: 0.9, y: 0.9)
        default:
            break
        }
        view.insertSubview(incoming, aboveSubview: outgoing)
        UIView.animate(
            withDuration: duration,
            animations: {
                switch self.settings.transition {
                case PlaybackTiming.transitionSlide:
                    incoming.transform = .identity
                    outgoing.transform = CGAffineTransform(translationX: -edge, y: 0)
                case PlaybackTiming.transitionWipe:
                    incoming.transform = .identity
                default:
                    incoming.alpha = 1
                    incoming.transform = .identity
                    outgoing.alpha = 0
                }
            },
            completion: { [weak self] _ in
                guard let self else { return }
                frontImageView.image = incoming.image
                frontImageView.alpha = 1
                frontImageView.transform = .identity
                backImageView.image = nil
                backImageView.alpha = 0
                backImageView.transform = .identity
                dispatch(PlaybackEvent(type: "transition_finished"))
            }
        )
    }

    private func updateBackdrop(for image: UIImage, assetID: String) {
        switch settings.fitMode {
        case "fill_crop":
            frontImageView.contentMode = .scaleAspectFill
            backImageView.contentMode = .scaleAspectFill
            backgroundView.image = nil
            dimView.isHidden = true
            solidView.isHidden = true
        case "fit_color":
            frontImageView.contentMode = .scaleAspectFit
            backImageView.contentMode = .scaleAspectFit
            backgroundView.image = nil
            dimView.isHidden = true
            solidView.isHidden = false
            if let solid = settings.solidColor {
                solidView.backgroundColor = Self.solidBackgroundColor(for: solid)
            } else {
                Task { [weak self, loader] in
                    guard let hex = await loader.edgeColorHex(of: image) else { return }
                    self?.solidView.backgroundColor = Self.color(fromHex: hex) ?? ThemeColor.canvas
                }
            }
        default:
            frontImageView.contentMode = .scaleAspectFit
            backImageView.contentMode = .scaleAspectFit
            dimView.isHidden = false
            solidView.isHidden = true
            Task { [weak self, loader] in
                guard let blurred = await loader.blurred(assetID: assetID, image: image) else { return }
                self?.backgroundView.image = blurred
            }
        }
    }

    private func currentIsVideo() -> Bool {
        guard index < queue.count else { return false }
        guard let relPath = relPaths[queue[index]] else { return false }
        return ThumbnailLoader.isVideoPath(relPath)
    }

    private func scheduleHoldTimer() {
        holdTask?.cancel()
        let holdMs = remainingHoldMs ?? Int64(settings.holdSeconds * 1000)
        remainingHoldMs = nil
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        nextTransitionAtMs = now + holdMs
        guard queue.count > 1 else { return }
        holdTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(max(holdMs, 0)) * 1_000_000)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            dispatch(PlaybackEvent(type: "hold_elapsed", nextReady: isNextReady()))
        }
    }

    private func saveRemainingHold() {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        remainingHoldMs = PlaybackTiming.remainingAtPause(
            nextTransitionAt: nextTransitionAtMs,
            pauseAt: now
        )
        holdTask?.cancel()
        persistSnapshot()
    }

    private func persistSnapshot() {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        if remainingHoldMs == nil, nextTransitionAtMs > now {
            remainingHoldMs = PlaybackTiming.remainingAtPause(
                nextTransitionAt: nextTransitionAtMs,
                pauseAt: now
            )
        }
        let cursor = Self.cursorJSON(queue: queue, index: index)
        let remaining = Int(remainingHoldMs ?? 0)
        Task { [weak self] in
            guard let self else { return }
            try? await environment.frameManager.savePlaybackSnapshot(
                frameID: frameID,
                remainingHoldMs: remaining,
                cursorJSON: cursor,
                seed: randomSeed,
                selectionVersion: Int(selectionVersion)
            )
        }
    }

    private func completeTransition() {
        view.layer.removeAllAnimations()
        frontImageView.layer.removeAllAnimations()
        backImageView.layer.removeAllAnimations()
        if backImageView.image != nil {
            frontImageView.image = backImageView.image
            backImageView.image = nil
        }
        frontImageView.alpha = 1
        frontImageView.transform = .identity
        backImageView.alpha = 0
        backImageView.transform = .identity
    }

    private func clearTextures() {
        holdTask?.cancel()
        stopVideo()
        frontImageView.image = nil
        backImageView.image = nil
        backgroundView.image = nil
        loader.clear()
    }

    private func showSafeImage() {
        safeImageShown = true
        updateStateChrome()
    }

    private func resumeFromRest() {
        if queue.isEmpty {
            rebuildQueue(keepIndex: false)
        } else if !currentIsVideo() {
            scheduleHoldTimer()
        }
    }

    private func markPlayedOnce() {
        Task { [weak self] in
            guard let self else { return }
            try? environment.settings.setValue("true", forKey: AppSettingsRepository.playbackHasPlayedOnceKey)
            try? environment.settings.setValue(frameID, forKey: AppSettingsRepository.playbackLastFrameIDKey)
        }
    }

    private func checkRevocation() {
        guard PhotoLibrarySourceAdapter.currentAccess() == .denied else { return }
        dispatch(PlaybackEvent(
            type: "selection_changed",
            playableRemaining: queue.count > 1,
            category: "revoke"
        ))
    }

    private func restActiveNow() -> Bool {
        let components = Calendar.current.dateComponents([.hour, .minute], from: Date())
        let minute = (components.hour ?? 0) * 60 + (components.minute ?? 0)
        return PlaybackTiming.restActive(
            enabled: settings.restEnabled && !skipRestOnce,
            startMinute: settings.restStartMinute,
            endMinute: settings.restEndMinute,
            nowMinute: minute
        )
    }

    private func startRestMonitor() {
        restTask?.cancel()
        restTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                guard !Task.isCancelled else { return }
                await self?.evaluateRestWindow()
            }
        }
    }

    private func evaluateRestWindow() {
        let active = restActiveNow()
        if active {
            dispatch(PlaybackEvent(type: "rest_window_start"))
        } else if controllerState == PlaybackController.resting {
            dispatch(PlaybackEvent(type: "user_tap"))
        }
        if controllerState == PlaybackController.resting {
            updateRestClock()
        }
    }

    private func startHolidayMonitor() {
        holidayTask?.cancel()
        holidayTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            while !Task.isCancelled {
                await self?.evaluateHolidayWindow()
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        }
    }

    private func canApplyHolidaySwitch() -> Bool {
        !restActiveNow() && (
            controllerState == PlaybackController.holding
            || controllerState == PlaybackController.paused
            || controllerState == PlaybackController.empty
            || controllerState == PlaybackController.failed
        )
    }

    private func holidayNow() -> (date: String, minute: Int) {
        let now = Date()
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.dateFormat = "yyyy-MM-dd"
        let components = Calendar.current.dateComponents([.hour, .minute], from: now)
        return (formatter.string(from: now), (components.hour ?? 0) * 60 + (components.minute ?? 0))
    }

    private func evaluateHolidayWindow() async {
        let rules = (try? await environment.holidayStore.rules()) ?? []
        let now = holidayNow()
        let hitID = HolidayTiming.pick(rules: rules, date: now.date, minute: now.minute)
        if let activeID = activeHolidayRuleID, hitID != activeID {
            returnFromHoliday()
            return
        }
        guard let hitID else { return }
        guard let rule = rules.first(where: { $0.id == hitID }) else { return }
        guard rule.frameID != frameID else { return }
        guard holidayHandledRuleID != rule.id else { return }
        guard canApplyHolidaySwitch() else { return }
        holidayHandledRuleID = rule.id
        enterHoliday(rule: rule)
    }

    private func enterHoliday(rule: HolidayRule) {
        let previousFrameID = frameID
        Task { [weak self] in
            guard let self else { return }
            let bundle = try? await environment.frameManager.framePlaybackBundle(frameID: rule.frameID)
            guard !Task.isCancelled else { return }
            guard let bundle else {
                holidayHandledRuleID = nil
                showHolidayNotice(L10n.format("holiday.notice_missing", rule.name))
                return
            }
            let newQueue = (try? await environment.frames.buildPlayQueue(frameID: rule.frameID)) ?? []
            guard !Task.isCancelled else { return }
            guard !newQueue.isEmpty else {
                holidayHandledRuleID = nil
                showHolidayNotice(L10n.format("holiday.notice_missing", rule.name))
                return
            }
            guard canApplyHolidaySwitch() else {
                holidayHandledRuleID = nil
                return
            }
            preHolidayFrameID = previousFrameID
            frameID = rule.frameID
            settings = PlayerSettings.parse(json: bundle.settingsJSON)
            randomSeed = bundle.randomSeed
            selectionVersion = bundle.selectionVersion
            pendingSnapshot = nil
            activeHolidayRuleID = rule.id
            queue = newQueue
            index = min(index, newQueue.count - 1)
            safeImageShown = false
            decodeFailStreak = 0
            await refreshRelPaths()
            if controllerState == PlaybackController.empty || controllerState == PlaybackController.failed {
                dispatch(PlaybackEvent(type: "start"))
            }
            showHolidayNotice(L10n.format("holiday.notice_switched", rule.name))
        }
    }

    private func returnFromHoliday() {
        guard canApplyHolidaySwitch() else { return }
        guard let previousFrameID = preHolidayFrameID else {
            activeHolidayRuleID = nil
            return
        }
        guard !holidayReturnInFlight else { return }
        holidayReturnInFlight = true
        Task { [weak self] in
            guard let self else { return }
            let bundle = try? await environment.frameManager.framePlaybackBundle(frameID: previousFrameID)
            guard !Task.isCancelled else { return }
            guard let bundle else {
                holidayReturnInFlight = false
                return
            }
            let newQueue = (try? await environment.frames.buildPlayQueue(frameID: previousFrameID)) ?? []
            guard !Task.isCancelled else { return }
            guard canApplyHolidaySwitch() else {
                holidayReturnInFlight = false
                return
            }
            frameID = previousFrameID
            settings = PlayerSettings.parse(json: bundle.settingsJSON)
            randomSeed = bundle.randomSeed
            selectionVersion = bundle.selectionVersion
            pendingSnapshot = nil
            queue = newQueue
            index = newQueue.isEmpty ? 0 : min(index, newQueue.count - 1)
            safeImageShown = false
            decodeFailStreak = 0
            activeHolidayRuleID = nil
            preHolidayFrameID = nil
            holidayHandledRuleID = nil
            holidayReturnInFlight = false
            await refreshRelPaths()
            if newQueue.isEmpty {
                showEmptyOverlay()
            } else if controllerState == PlaybackController.empty || controllerState == PlaybackController.failed {
                dispatch(PlaybackEvent(type: "start"))
            }
            showHolidayNotice(L10n.string("holiday.notice_returned"))
        }
    }

    private func showHolidayNotice(_ text: String) {
        holidayNoticeText = text
        noticeLabel.text = text
        updateStateChrome()
        holidayNoticeTask?.cancel()
        holidayNoticeTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 8_000_000_000)
            guard !Task.isCancelled else { return }
            self?.holidayNoticeText = nil
            self?.noticeLabel.text = L10n.string("player.decode_failed_notice")
            self?.updateStateChrome()
        }
    }

    private func startClock() {
        updateClock()
        clockTask?.cancel()
        clockTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 60_000_000_000)
                guard !Task.isCancelled else { return }
                self?.updateClock()
            }
        }
    }

    private func updateClock() {
        let now = Date()
        clockLabel.text = currentTimeText(now)
        let dateFormatter = DateFormatter()
        dateFormatter.dateStyle = .full
        dateFormatter.timeStyle = .none
        dateLabel.text = dateFormatter.string(from: now)
    }

    private func updateRestClock() {
        restClockLabel.text = currentTimeText(Date())
    }

    private func currentTimeText(_ date: Date) -> String {
        let clockFormatter = DateFormatter()
        if let hour24 = settings.hour24 {
            clockFormatter.dateFormat = hour24 ? "HH:mm" : "h:mm a"
        } else {
            clockFormatter.setLocalizedDateFormatFromTemplate("jmm")
        }
        return clockFormatter.string(from: date)
    }

    private func updateClockVisibility() {
        clockLabel.isHidden = !settings.clockEnabled
        dateLabel.isHidden = !settings.dateEnabled
        updateClockPosition()
    }

    private func startWeatherTask() {
        weatherTask?.cancel()
        guard weatherGloballyEnabled else {
            updateWeather()
            return
        }
        weatherTask = Task { [weak self] in
            await self?.refreshWeather(manual: false)
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30 * 60 * 1_000_000_000)
                guard !Task.isCancelled else { return }
                await self?.refreshWeather(manual: false)
            }
        }
    }

    private var weatherGloballyEnabled: Bool {
        (try? environment.settings.value(forKey: WeatherRepository.enabledKey)) == "true"
    }

    private func currentWeatherPlace() async -> WeatherPlace? {
        (try? await environment.weatherRepository.places())?.last
    }

    private func refreshWeather(manual: Bool) async {
        guard let place = await currentWeatherPlace() else { return }
        _ = await environment.weatherRefresher.refresh(place: place, manual: manual)
        updateWeather()
    }

    private func updateWeather() {
        guard weatherGloballyEnabled else {
            weatherLabel.isHidden = true
            weatherNoteLabel.isHidden = true
            return
        }
        Task { [weak self] in
            guard let self else { return }
            guard let place = await currentWeatherPlace() else {
                weatherLabel.isHidden = true
                weatherNoteLabel.isHidden = true
                return
            }
            let snapshot = try? await environment.weatherRepository.snapshot(placeId: place.placeId)
            guard let snapshot else {
                weatherLabel.isHidden = true
                weatherNoteLabel.text = L10n.string("weather.unavailable")
                weatherNoteLabel.isHidden = false
                return
            }
            let now = Int(Date().timeIntervalSince1970 * 1000)
            let temperature = temperatureText(milliCelsius: snapshot.temperatureMilli)
            let condition = conditionText(for: snapshot.conditionCode)
            let placeName = AppLanguage.preferChinese ? place.nameZh : place.nameEn
            let weatherText = condition.map { "\(temperature) \($0)" } ?? temperature
            let display = placeName.isEmpty ? weatherText : "\(placeName) · \(weatherText)"
            switch WeatherRepository.freshness(observedAtMillis: snapshot.observedAt, nowMillis: now) {
            case .normal:
                weatherLabel.text = display
                weatherLabel.isHidden = false
                weatherNoteLabel.isHidden = true
            case .delayed:
                weatherLabel.text = display
                weatherLabel.isHidden = false
                weatherNoteLabel.text = L10n.string("weather.delayed")
                weatherNoteLabel.isHidden = false
            case .unavailable:
                weatherLabel.isHidden = true
                weatherNoteLabel.text = L10n.string("weather.unavailable")
                weatherNoteLabel.isHidden = false
            }
            weatherLabel.accessibilityLabel = FormatStrings.string(
                "a11y.weather_overlay",
                condition ?? "",
                weatherLabel.text ?? ""
            )
        }
    }

    private func conditionText(for code: String) -> String? {
        guard code != "unknown" else { return nil }
        let key = "weather.condition.\(code)"
        let text = L10n.string(key)
        return text == key ? nil : text
    }

    private func temperatureText(milliCelsius: Int) -> String {
        let units = try? environment.settings.value(forKey: WeatherRepository.unitsKey)
        let fahrenheit = units == "fahrenheit"
            || (units == nil && !Locale.current.usesMetricSystem)
        let milli = fahrenheit
            ? OpenMeteoProvider.celsiusMilliToFahrenheit(milliCelsius)
            : milliCelsius
        return FormatStrings.string("format.temperature", Int((Double(milli) / 1000).rounded()))
    }

    private func buildClockConstraints() {
        let safeArea = view.safeAreaLayoutGuide
        let inset = ThemeMetrics.contentInset
        let vertical = ThemeMetrics.space24
        clockConstraintsByPosition = [
            "top_leading": [
                clockStack.leadingAnchor.constraint(equalTo: safeArea.leadingAnchor, constant: inset),
                clockStack.topAnchor.constraint(equalTo: safeArea.topAnchor, constant: vertical),
            ],
            "top_trailing": [
                clockStack.trailingAnchor.constraint(equalTo: safeArea.trailingAnchor, constant: -inset),
                clockStack.topAnchor.constraint(equalTo: safeArea.topAnchor, constant: vertical),
            ],
            "bottom_leading": [
                clockStack.leadingAnchor.constraint(equalTo: safeArea.leadingAnchor, constant: inset),
                clockStack.bottomAnchor.constraint(equalTo: safeArea.bottomAnchor, constant: -vertical),
            ],
            "bottom_trailing": [
                clockStack.trailingAnchor.constraint(equalTo: safeArea.trailingAnchor, constant: -inset),
                clockStack.bottomAnchor.constraint(equalTo: safeArea.bottomAnchor, constant: -vertical),
            ],
        ]
        updateClockPosition()
    }

    private func updateClockPosition() {
        for (position, constraints) in clockConstraintsByPosition {
            constraints.forEach { $0.isActive = position == settings.clockPosition }
        }
        let trailing = settings.clockPosition.hasSuffix("trailing")
        clockStack.alignment = trailing ? .trailing : .leading
        clockLabel.textAlignment = trailing ? .right : .left
        dateLabel.textAlignment = trailing ? .right : .left
    }

    private func applyBrightness() {
        guard let brightness = settings.brightness else { return }
        if originalBrightness == nil {
            originalBrightness = UIScreen.main.brightness
        }
        UIScreen.main.brightness = CGFloat(min(max(brightness, 0), 1))
    }

    private func restoreBrightness() {
        guard let originalBrightness else { return }
        UIScreen.main.brightness = originalBrightness
        self.originalBrightness = nil
    }

    private func buildControls() {
        configure(controlButton: previousButton, symbol: "backward.fill", labelKey: "player.previous", action: #selector(handlePrevious))
        configure(controlButton: pauseButton, symbol: "pause.fill", labelKey: "player.pause", action: #selector(handlePauseResume))
        configure(controlButton: nextButton, symbol: "forward.fill", labelKey: "player.next", action: #selector(handleNext))
        configure(controlButton: moreButton, symbol: "ellipsis", labelKey: "player.more", action: #selector(handleMore))
        configure(controlButton: exitButton, symbol: "xmark", labelKey: "player.exit", action: #selector(handleExit))

        let stack = UIStackView(arrangedSubviews: [
            previousButton, pauseButton, nextButton, moreButton, exitButton,
        ])
        stack.axis = .horizontal
        stack.spacing = ThemeMetrics.space16
        stack.translatesAutoresizingMaskIntoConstraints = false

        controlsCapsule.backgroundColor = ThemeColor.photoControl
        controlsCapsule.alpha = ThemeMetrics.controlOpacity
        controlsCapsule.translatesAutoresizingMaskIntoConstraints = false
        controlsCapsule.addSubview(stack)
        if UIAccessibility.isReduceTransparencyEnabled {
            controlsCapsule.alpha = 1
        }
        view.addSubview(controlsCapsule)
        controlsCapsule.accessibilityLabel = L10n.string("a11y.player_controls")

        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: controlsCapsule.topAnchor, constant: ThemeMetrics.space8),
            stack.bottomAnchor.constraint(
                equalTo: controlsCapsule.bottomAnchor,
                constant: -ThemeMetrics.space8
            ),
            stack.leadingAnchor.constraint(
                equalTo: controlsCapsule.leadingAnchor,
                constant: ThemeMetrics.space16
            ),
            stack.trailingAnchor.constraint(
                equalTo: controlsCapsule.trailingAnchor,
                constant: -ThemeMetrics.space16
            ),
            controlsCapsule.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
            controlsCapsule.bottomAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.bottomAnchor,
                constant: -ThemeMetrics.space24
            ),
        ])
        controlsCapsule.layoutIfNeeded()
    }

    private func configure(controlButton button: UIButton, symbol: String, labelKey: String, action: Selector) {
        button.setImage(UIImage(systemName: symbol), for: .normal)
        button.tintColor = ThemeColor.textOnPhoto
        button.accessibilityLabel = L10n.string(labelKey)
        button.addTarget(self, action: action, for: .touchUpInside)
        NSLayoutConstraint.activate([
            button.widthAnchor.constraint(greaterThanOrEqualToConstant: ThemeMetrics.touchTarget),
            button.heightAnchor.constraint(greaterThanOrEqualToConstant: ThemeMetrics.touchTarget),
        ])
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        controlsCapsule.layer.cornerRadius = controlsCapsule.bounds.height / 2
        videoLayer?.frame = view.bounds
    }

    private func buildOverlays() {
        let safeLabel = UILabel()
        safeLabel.text = L10n.string("player.revoked_fallback")
        safeLabel.font = ThemeFont.body()
        safeLabel.adjustsFontForContentSizeCategory = true
        safeLabel.textColor = ThemeColor.textPrimary
        safeLabel.textAlignment = .center
        safeLabel.numberOfLines = 0
        safeOverlay.axis = .vertical
        safeOverlay.alignment = .center
        safeOverlay.spacing = ThemeMetrics.space16
        safeOverlay.addArrangedSubview(safeLabel)
        safeOverlay.isHidden = true
        safeOverlay.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(safeOverlay)

        let emptyTitle = UILabel()
        emptyTitle.text = L10n.string("player.empty_title")
        emptyTitle.font = ThemeFont.heading()
        emptyTitle.adjustsFontForContentSizeCategory = true
        emptyTitle.textColor = ThemeColor.textPrimary
        emptyTitle.textAlignment = .center
        emptyTitle.accessibilityTraits.insert(.header)
        let emptyBody = UILabel()
        emptyBody.text = L10n.string("player.empty_body")
        emptyBody.font = ThemeFont.body()
        emptyBody.adjustsFontForContentSizeCategory = true
        emptyBody.textColor = ThemeColor.textSecondary
        emptyBody.textAlignment = .center
        emptyBody.numberOfLines = 0
        let chooseButton = ActionButton(titleKey: "player.empty_action_choose", style: .primary)
        chooseButton.addTarget(self, action: #selector(handleEmptyChoose), for: .touchUpInside)
        let downloadsButton = ActionButton(titleKey: "player.empty_action_downloads", style: .secondary)
        downloadsButton.addTarget(self, action: #selector(handleEmptyDownloads), for: .touchUpInside)
        emptyOverlay.axis = .vertical
        emptyOverlay.alignment = .center
        emptyOverlay.spacing = ThemeMetrics.space16
        emptyOverlay.addArrangedSubview(emptyTitle)
        emptyOverlay.addArrangedSubview(emptyBody)
        emptyOverlay.addArrangedSubview(chooseButton)
        emptyOverlay.addArrangedSubview(downloadsButton)
        emptyOverlay.isHidden = true
        emptyOverlay.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(emptyOverlay)

        restingScreen.backgroundColor = ThemeColor.restSurface
        restingScreen.isHidden = true
        restingScreen.translatesAutoresizingMaskIntoConstraints = false
        restingScreen.isAccessibilityElement = true
        restingScreen.accessibilityLabel = L10n.string("player.resting_title")
        restingScreen.accessibilityHint = L10n.string("player.resting_resume")
        restingScreen.accessibilityTraits = [.button]
        let restTap = UITapGestureRecognizer(target: self, action: #selector(handleRestingResume))
        restingScreen.addGestureRecognizer(restTap)
        restClockLabel.font = ThemeFont.clock()
        restClockLabel.textColor = ThemeColor.textOnPhoto
        restClockLabel.textAlignment = .center
        restClockLabel.translatesAutoresizingMaskIntoConstraints = false
        restingScreen.addSubview(restClockLabel)
        NSLayoutConstraint.activate([
            restClockLabel.centerXAnchor.constraint(equalTo: restingScreen.centerXAnchor),
            restClockLabel.centerYAnchor.constraint(equalTo: restingScreen.centerYAnchor),
        ])
        view.addSubview(restingScreen)
        NSLayoutConstraint.activate([
            restingScreen.topAnchor.constraint(equalTo: view.topAnchor),
            restingScreen.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            restingScreen.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            restingScreen.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        for overlay in [safeOverlay, emptyOverlay] {
            NSLayoutConstraint.activate([
                overlay.centerXAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerXAnchor),
                overlay.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor),
                overlay.leadingAnchor.constraint(
                    greaterThanOrEqualTo: view.safeAreaLayoutGuide.leadingAnchor,
                    constant: ThemeMetrics.contentInset
                ),
                overlay.trailingAnchor.constraint(
                    lessThanOrEqualTo: view.safeAreaLayoutGuide.trailingAnchor,
                    constant: -ThemeMetrics.contentInset
                ),
            ])
        }
    }

    private func updateStateChrome() {
        let isResting = controllerState == PlaybackController.resting
        let isEmpty = controllerState == PlaybackController.empty
            || controllerState == PlaybackController.failed
        restingScreen.isHidden = !isResting
        if isResting {
            updateRestClock()
        }
        emptyOverlay.isHidden = !isEmpty
        safeOverlay.isHidden = !safeImageShown
        if isResting {
            view.backgroundColor = ThemeColor.restSurface
        } else {
            view.backgroundColor = ThemeColor.canvas
        }
        let chromeHidden = isResting || isEmpty || safeImageShown
        controlsCapsule.isHidden = chromeHidden
        if !chromeHidden, !controlsPresented {
            showControls()
            controlsPresented = true
        }
        clockLabel.superview?.isHidden = chromeHidden
        noticeLabel.isHidden = (!skippedAny && holidayNoticeText == nil) || chromeHidden
        singleNoticeLabel.isHidden = queue.count != 1 || chromeHidden
        frontImageView.isAccessibilityElement = !queue.isEmpty
        frontImageView.accessibilityLabel = queue.isEmpty
            ? nil
            : FormatStrings.string("a11y.current_photo", index + 1, queue.count)
        UIApplication.shared.isIdleTimerDisabled = idleTimerShouldBeDisabled()
        if controllerState == PlaybackController.paused {
            pauseButton.setImage(UIImage(systemName: "play.fill"), for: .normal)
            pauseButton.accessibilityLabel = L10n.string("player.play")
        } else {
            pauseButton.setImage(UIImage(systemName: "pause.fill"), for: .normal)
            pauseButton.accessibilityLabel = L10n.string("player.pause")
        }
    }

    private func showEmptyOverlay() {
        controllerState = PlaybackController.empty
        updateStateChrome()
    }

    private func idleTimerShouldBeDisabled() -> Bool {
        switch controllerState {
        case PlaybackController.holding, PlaybackController.transitioning, PlaybackController.preparing:
            return true
        case PlaybackController.paused:
            return settings.keepAwakeWhenPaused
        default:
            return false
        }
    }

    @objc private func handleTap() {
        if controllerState == PlaybackController.resting {
            handleRestingResume()
            return
        }
        if controlsCapsule.isHidden { return }
        if controlsCapsule.alpha > 0 {
            controlsHideTask?.cancel()
            controlsCapsule.alpha = 0
        } else {
            showControls()
        }
    }

    private func showControls() {
        controlsCapsule.alpha = UIAccessibility.isReduceTransparencyEnabled
            ? 1
            : ThemeMetrics.controlOpacity
        scheduleControlsHide()
    }

    private func scheduleControlsHide() {
        controlsHideTask?.cancel()
        guard !UIAccessibility.isVoiceOverRunning else { return }
        controlsHideTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: Self.controlsHideDelayNanos)
            guard !Task.isCancelled else { return }
            guard let self, !controlsCapsule.isHidden, controlsCapsule.alpha > 0 else { return }
            UIView.animate(withDuration: RootTransition.crossfadeDuration) {
                self.controlsCapsule.alpha = 0
            }
        }
    }

    @objc private func handlePrevious() {
        dispatch(PlaybackEvent(type: "user_prev"))
        showControls()
    }

    @objc private func handleNext() {
        dispatch(PlaybackEvent(type: "user_next"))
        showControls()
    }

    @objc private func handlePauseResume() {
        if controllerState == PlaybackController.paused {
            dispatch(PlaybackEvent(type: "user_resume"))
            scheduleHoldTimer()
            videoPlayer?.play()
        } else {
            dispatch(PlaybackEvent(type: "user_pause"))
            videoPlayer?.pause()
        }
        showControls()
    }

    @objc private func handleMore() {
        let sheet = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        sheet.addAction(UIAlertAction(
            title: L10n.string("frames.playback_settings"),
            style: .default,
            handler: { [weak self] _ in self?.openPlaybackSettings() }
        ))
        sheet.addAction(UIAlertAction(
            title: L10n.string("player.remove_current"),
            style: .destructive,
            handler: { [weak self] _ in self?.removeCurrent() }
        ))
        sheet.addAction(UIAlertAction(
            title: L10n.string("player.exit"),
            style: .default,
            handler: { [weak self] _ in self?.exitPlayer() }
        ))
        sheet.addAction(UIAlertAction(
            title: L10n.string("common.cancel"),
            style: .cancel
        ))
        sheet.popoverPresentationController?.sourceView = moreButton
        present(sheet, animated: true)
    }

    @objc private func handleExit() {
        exitPlayer()
    }

    private func removeCurrent() {
        guard index < queue.count else { return }
        let assetID = queue[index]
        Task { [weak self] in
            guard let self else { return }
            let result = try? await environment.frames.save(
                frameID: frameID,
                baseVersion: selectionVersion,
                addExcludes: [SelectionRefEdit(assetId: assetID, sourceAssetId: nil)]
            )
            if case .ok(let newVersion, _, _, _) = result {
                selectionVersion = newVersion
            }
            dispatch(PlaybackEvent(type: "selection_changed"))
        }
    }

    private func openPlaybackSettings() {
        let settings = PlaybackSettingsViewController(environment: environment, frameID: frameID)
        settings.modalPresentationStyle = .fullScreen
        present(settings, animated: true)
    }

    private func exitPlayer() {
        holdTask?.cancel()
        workTask?.cancel()
        clockTask?.cancel()
        restTask?.cancel()
        holidayTask?.cancel()
        holidayNoticeTask?.cancel()
        weatherTask?.cancel()
        controlsHideTask?.cancel()
        clearTextures()
        restoreBrightness()
        UIApplication.shared.isIdleTimerDisabled = false
        dismiss(animated: true)
    }

    @objc private func handleBackground() {
        dispatch(PlaybackEvent(type: "app_background"))
        restoreBrightness()
    }

    @objc private func handleForeground() {
        applyBrightness()
        checkRevocation()
        Task { [weak self] in
            guard let self else { return }
            let current = (try? await environment.frameManager.framePlaybackBundle(frameID: frameID))
            let intact = current.map {
                $0.selectionVersion == selectionVersion && !$0.settingsJSON.isEmpty
            } ?? false
            dispatch(PlaybackEvent(type: "app_foreground", queueIntact: intact))
            if controllerState == PlaybackController.holding, !currentIsVideo() {
                scheduleHoldTimer()
            }
        }
    }

    @objc private func handleRestingResume() {
        skipRestOnce = true
        dispatch(PlaybackEvent(type: "user_tap"))
    }

    @objc private func handleEmptyChoose() {
        let editor = FrameEditViewController(environment: environment, frameID: frameID)
        editor.modalPresentationStyle = .fullScreen
        present(editor, animated: true)
    }

    @objc private func handleEmptyDownloads() {
        Task { [weak self] in
            guard let self, let job = try? await environment.latestLocalPhotosJob() else { return }
            let runner = environment.openRunner(jobID: job.id)
            let progress = ImportProgressViewController(runner: runner, environment: environment)
            progress.modalPresentationStyle = .fullScreen
            present(progress, animated: true)
        }
    }

    private static func cursorJSON(queue: [String], index: Int) -> String {
        guard let data = try? JSONSerialization.data(
            withJSONObject: ["queue": queue, "index": index],
            options: [.sortedKeys]
        ) else { return "{}" }
        return String(decoding: data, as: UTF8.self)
    }

    private static func parseCursor(_ json: String) -> (queue: [String], index: Int)? {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let queue = object["queue"] as? [String] else { return nil }
        return (queue, object["index"] as? Int ?? 0)
    }

    private static func solidBackgroundColor(for solid: String) -> UIColor {
        switch solid {
        case "black":
            return ThemeColor.restSurface
        default:
            return ThemeColor.canvas
        }
    }

    private static func color(fromHex hex: String) -> UIColor? {
        var raw = hex
        if raw.hasPrefix("#") { raw.removeFirst() }
        guard raw.count == 6, let value = UInt32(raw, radix: 16) else { return nil }
        return UIColor(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: 1
        )
    }
}
