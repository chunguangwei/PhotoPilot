import Foundation

struct MusicSelection: Equatable, Sendable {
    var enabled = false
    var source = "builtin"
    var trackId: String?
    var localPath: String?
    var localTitle: String?
}

enum MusicCatalog {
    struct Track {
        let id: String
        let titleKey: String
    }

    static let sourceBuiltin = "builtin"
    static let sourceLocal = "local"

    static let tracks: [Track] = [
        Track(id: "birthday_01", titleKey: "music.track_birthday"),
        Track(id: "wedding_01", titleKey: "music.track_wedding"),
        Track(id: "spring_festival_01", titleKey: "music.track_spring_festival"),
        Track(id: "christmas_01", titleKey: "music.track_christmas"),
        Track(id: "qixi_01", titleKey: "music.track_qixi"),
        Track(id: "valentine_01", titleKey: "music.track_valentine"),
        Track(id: "festive_01", titleKey: "music.track_festive"),
        Track(id: "classical_01", titleKey: "music.track_classical"),
        Track(id: "travel_01", titleKey: "music.track_travel"),
        Track(id: "coffee_01", titleKey: "music.track_coffee"),
        Track(id: "lounge_01", titleKey: "music.track_lounge"),
        Track(id: "morning_01", titleKey: "music.track_morning"),
        Track(id: "night_01", titleKey: "music.track_night"),
        Track(id: "nature_01", titleKey: "music.track_nature"),
    ]

    static func builtinURL(trackId: String) -> URL? {
        guard tracks.contains(where: { $0.id == trackId }) else { return nil }
        return Bundle.main.url(forResource: "music_\(trackId)", withExtension: "m4a")
    }

    static func musicDirectory() -> URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let directory = support.appendingPathComponent("music", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    static func localURL(localPath: String) -> URL? {
        guard !localPath.isEmpty,
              localPath != ".",
              localPath != "..",
              !localPath.contains("/") else {
            return nil
        }
        return musicDirectory().appendingPathComponent(localPath)
    }

    static func url(for selection: MusicSelection) -> URL? {
        guard selection.enabled else { return nil }
        switch selection.source {
        case sourceLocal:
            guard let localPath = selection.localPath,
                  let url = localURL(localPath: localPath),
                  FileManager.default.fileExists(atPath: url.path) else { return nil }
            return url
        default:
            guard let trackId = selection.trackId else { return nil }
            return builtinURL(trackId: trackId)
        }
    }

    static func displayName(for selection: MusicSelection) -> String {
        guard selection.enabled else { return L10n.string("playback.music_off") }
        switch selection.source {
        case sourceLocal:
            guard let localPath = selection.localPath,
                  let url = localURL(localPath: localPath),
                  FileManager.default.fileExists(atPath: url.path) else {
                return L10n.string("playback.music_missing")
            }
            return selection.localTitle ?? localPath
        default:
            guard let trackId = selection.trackId,
                  let track = tracks.first(where: { $0.id == trackId }) else {
                return L10n.string("playback.music_off")
            }
            return L10n.string(track.titleKey)
        }
    }
}
