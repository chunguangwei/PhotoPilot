import AVFoundation

final class MusicPlayer {
    private var player: AVAudioPlayer?
    private var url: URL?
    private var sessionActive = false
    private(set) var isPlaying = false

    var hasPlayableContent: Bool {
        player != nil || url != nil
    }

    func start(selection: MusicSelection) {
        guard let url = MusicCatalog.url(for: selection) else {
            stop()
            return
        }
        start(url: url)
    }

    func start(url: URL) {
        stop()
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .default)
            try session.setActive(true)
            sessionActive = true
            let audio = try AVAudioPlayer(contentsOf: url)
            audio.numberOfLoops = -1
            audio.prepareToPlay()
            audio.play()
            player = audio
            self.url = url
            isPlaying = true
        } catch {
            player = nil
            self.url = nil
            isPlaying = false
            deactivateSession()
        }
    }

    func pause() {
        player?.pause()
        isPlaying = false
    }

    func resume() {
        guard !isPlaying else { return }
        if let player {
            do {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playback, mode: .default)
                try session.setActive(true)
                sessionActive = true
            } catch {
                return
            }
            isPlaying = player.play()
        } else if let url {
            start(url: url)
        }
    }

    func stop() {
        player?.stop()
        player = nil
        url = nil
        isPlaying = false
        deactivateSession()
    }

    private func deactivateSession() {
        guard sessionActive else { return }
        sessionActive = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
