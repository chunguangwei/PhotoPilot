import Foundation

struct SourceAssetDescriptor: Sendable, Equatable {
    let remoteID: String
    let revision: String
    let byteSize: Int?
    let remoteTakenAtMillis: Int?
}

struct SourceAssetPage: Sendable {
    let assets: [SourceAssetDescriptor]
    let nextCursor: String?
    let totalKnown: Int?
}

enum OffsetCursorError: Error {
    case invalidCursor
}

enum OffsetCursor {
    static let prefix = "offset:"
    static let doneSentinel = "__done__"

    static func encode(_ offset: Int) -> String {
        prefix + String(offset)
    }

    static func decode(_ cursor: String?) throws -> Int? {
        guard let cursor else { return 0 }
        guard cursor != doneSentinel else { return nil }
        guard cursor.hasPrefix(prefix) else { throw OffsetCursorError.invalidCursor }
        guard let offset = Int(cursor.dropFirst(prefix.count)), offset >= 0 else { throw OffsetCursorError.invalidCursor }
        return offset
    }
}

struct LocalAssetPayload: Sendable {
    let localFileURL: URL
    let byteSize: Int
    let contentHash: String
    let head64kHash: String
    let format: String
    let pixelWidth: Int
    let pixelHeight: Int
    let exifOrientation: Int
    let capturedAtMillis: Int?
    let mediaKind: String
    let durationMillis: Int?
    let capturedAtSource: String

    init(
        localFileURL: URL,
        byteSize: Int,
        contentHash: String,
        head64kHash: String,
        format: String,
        pixelWidth: Int,
        pixelHeight: Int,
        exifOrientation: Int,
        capturedAtMillis: Int?,
        mediaKind: String = "image",
        durationMillis: Int? = nil,
        capturedAtSource: String = "unknown"
    ) {
        self.localFileURL = localFileURL
        self.byteSize = byteSize
        self.contentHash = contentHash
        self.head64kHash = head64kHash
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

protocol SourceAdapter: Sendable {
    func enumerateAssets(cursor: String?) async throws -> SourceAssetPage
    func assetPayload(remoteID: String) async throws -> LocalAssetPayload
}
