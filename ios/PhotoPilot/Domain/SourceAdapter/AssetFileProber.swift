import AVFoundation
import CryptoKit
import Foundation
import ImageIO

enum AssetFileProber {
    struct ProbedImage: Sendable {
        let format: String
        let pixelWidth: Int
        let pixelHeight: Int
        let exifOrientation: Int
        let capturedAtMillis: Int?
        let mediaKind: String
        let durationMillis: Int?
        let capturedAtSource: String

        init(
            format: String,
            pixelWidth: Int,
            pixelHeight: Int,
            exifOrientation: Int,
            capturedAtMillis: Int?,
            mediaKind: String = "image",
            durationMillis: Int? = nil,
            capturedAtSource: String = "unknown"
        ) {
            self.format = format
            self.pixelWidth = pixelWidth
            self.pixelHeight = pixelHeight
            self.exifOrientation = exifOrientation
            self.capturedAtMillis = capturedAtMillis
            self.mediaKind = mediaKind
            self.durationMillis = durationMillis
            self.capturedAtSource = capturedAtSource
        }
    }

    enum ProbeError: Error {
        case unreadable
        case unsupportedFormat
        case decodeFailed
    }

    static let videoExtensions: Set<String> = ["mp4", "mov"]
    static let rawExtensions: Set<String> = [
        "dng", "cr2", "nef", "arw", "raf", "orf", "rw2",
    ]
    static let videoTruncationMillis = 600_000

    static func rotationToExifOrientation(degrees: Int) -> Int {
        switch ((degrees % 360) + 360) % 360 {
        case 90: return 6
        case 180: return 3
        case 270: return 8
        default: return 1
        }
    }

    private static let head64kLength = 64 * 1024

    static func contentHash(of url: URL) throws -> String {
        try "sha256:" + SHA256Hex.digest(url: url)
    }

    static func head64kHash(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let data = handle.readData(ofLength: head64kLength)
        return "sha256:" + SHA256Hex.digest(data: data)
    }

    static func sniffFormat(_ url: URL) throws -> AssetFormat {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let header = handle.readData(ofLength: 12)
        guard header.count >= 4 else { throw ProbeError.unreadable }
        if header[0] == 0xFF, header[1] == 0xD8, header[2] == 0xFF { return .jpeg }
        if header.count >= 8,
           header[0] == 0x89, header[1] == 0x50, header[2] == 0x4E, header[3] == 0x47,
           header[4] == 0x0D, header[5] == 0x0A, header[6] == 0x1A, header[7] == 0x0A {
            return .png
        }
        if header.count >= 12,
           header[4] == 0x66, header[5] == 0x74, header[6] == 0x79, header[7] == 0x70 {
            let brand = String(decoding: header[8..<12], as: UTF8.self)
            if ["heic", "heix", "hevc", "hevx", "mif1", "msf1"].contains(brand) {
                return .heic
            }
        }
        throw ProbeError.unsupportedFormat
    }

    static func probeImage(_ url: URL) throws -> ProbedImage {
        let ext = url.pathExtension.lowercased()
        do {
            let format = try sniffFormat(url)
            guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                  CGImageSourceGetCount(source) > 0 else {
                throw ProbeError.decodeFailed
            }
            let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] ?? [:]
            guard let width = properties[kCGImagePropertyPixelWidth] as? Int,
                  let height = properties[kCGImagePropertyPixelHeight] as? Int else {
                throw ProbeError.decodeFailed
            }
            let rawOrientation = properties[kCGImagePropertyOrientation] as? Int ?? 1
            let orientation = (1...8).contains(rawOrientation) ? rawOrientation : 1
            var capturedAtMillis: Int?
            if let exif = properties[kCGImagePropertyExifDictionary] as? [CFString: Any],
               let raw = exif[kCGImagePropertyExifDateTimeOriginal] as? String {
                capturedAtMillis = Self.parseExifDateTime(raw)
            }
            let swapped = orientation >= 5 && orientation <= 8
            return ProbedImage(
                format: format.rawValue,
                pixelWidth: swapped ? height : width,
                pixelHeight: swapped ? width : height,
                exifOrientation: orientation,
                capturedAtMillis: capturedAtMillis,
                capturedAtSource: capturedAtMillis != nil ? CapturedAtSource.exif.rawValue : CapturedAtSource.unknown.rawValue
            )
        } catch ProbeError.unsupportedFormat {
            if videoExtensions.contains(ext) {
                return try probeVideo(url)
            }
            if rawExtensions.contains(ext) {
                return try probeRaw(url)
            }
            throw ProbeError.unsupportedFormat
        }
    }

    static func probeVideo(_ url: URL) throws -> ProbedImage {
        let asset = AVURLAsset(url: url)
        guard let track = asset.tracks(withMediaType: .video).first else {
            throw ProbeError.unsupportedFormat
        }
        let bounds = track.naturalSize.applying(track.preferredTransform)
        let width = Int(abs(bounds.width).rounded())
        let height = Int(abs(bounds.height).rounded())
        guard width > 0, height > 0 else {
            throw ProbeError.decodeFailed
        }
        let transform = track.preferredTransform
        let degrees = Int((atan2(transform.b, transform.a) * 180 / Double.pi).rounded())
        let orientation = rotationToExifOrientation(degrees: degrees)
        let durationMillis = Int((CMTimeGetSeconds(asset.duration) * 1000).rounded())
        var capturedAtMillis: Int?
        if let creationItem = asset.creationDate,
           let text = creationItem.value as? String {
            capturedAtMillis = parseISODateTime(text)
        }
        let ext = url.pathExtension.lowercased()
        return ProbedImage(
            format: ext == AssetFormat.mov.rawValue ? AssetFormat.mov.rawValue : AssetFormat.mp4.rawValue,
            pixelWidth: width,
            pixelHeight: height,
            exifOrientation: orientation,
            capturedAtMillis: capturedAtMillis,
            mediaKind: "video",
            durationMillis: durationMillis,
            capturedAtSource: capturedAtMillis != nil
                ? CapturedAtSource.container.rawValue
                : CapturedAtSource.unknown.rawValue
        )
    }

    static func probeRaw(_ url: URL) throws -> ProbedImage {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              CGImageSourceGetCount(source) > 0 else {
            throw ProbeError.unsupportedFormat
        }
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] ?? [:]
        guard let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int else {
            throw ProbeError.decodeFailed
        }
        let rawOrientation = properties[kCGImagePropertyOrientation] as? Int ?? 1
        let orientation = (1...8).contains(rawOrientation) ? rawOrientation : 1
        var capturedAtMillis: Int?
        if let exif = properties[kCGImagePropertyExifDictionary] as? [CFString: Any],
           let raw = exif[kCGImagePropertyExifDateTimeOriginal] as? String {
            capturedAtMillis = Self.parseExifDateTime(raw)
        }
        let swapped = orientation >= 5 && orientation <= 8
        return ProbedImage(
            format: url.pathExtension.lowercased(),
            pixelWidth: swapped ? height : width,
            pixelHeight: swapped ? width : height,
            exifOrientation: orientation,
            capturedAtMillis: capturedAtMillis,
            capturedAtSource: capturedAtMillis != nil ? CapturedAtSource.exif.rawValue : CapturedAtSource.unknown.rawValue
        )
    }

    static func parseISODateTime(_ text: String) -> Int? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: text) {
            return Int(date.timeIntervalSince1970 * 1000)
        }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: text).map { Int($0.timeIntervalSince1970 * 1000) }
    }

    private static func parseExifDateTime(_ raw: String) -> Int? {
        guard let date = exifDateFormatter.date(from: raw) else { return nil }
        return Int(date.timeIntervalSince1970 * 1000)
    }

    private static let exifDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy:MM:dd HH:mm:ss"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.calendar = Calendar(identifier: .gregorian)
        return formatter
    }()
}

enum SHA256Hex {
    static func digest(url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while autoreleasepool(invoking: {
            let chunk = handle.readData(ofLength: 256 * 1024)
            if chunk.isEmpty { return false }
            hasher.update(data: chunk)
            return true
        }) {}
        return hex(hasher.finalize())
    }

    static func digest(data: Data) -> String {
        var hasher = SHA256()
        hasher.update(data: data)
        return hex(hasher.finalize())
    }

    private static func hex(_ digest: SHA256Digest) -> String {
        digest.map { String(format: "%02x", $0) }.joined()
    }
}
